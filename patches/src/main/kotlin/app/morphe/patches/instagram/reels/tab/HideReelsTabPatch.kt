/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide the Reels tab"
private const val REELS_TAB = "$EXTENSION_PACKAGE/reels/ReelsTab;"
internal const val SHOWN_TABS = "$REELS_TAB->tabs(Ljava/util/List;)Ljava/util/List;"
internal const val TAB_TO_OPEN = "$REELS_TAB->tab(Ljava/lang/Object;)Ljava/lang/Object;"

/** The names Instagram's tab enum gives Reels and Home, and the module name only its Reels tab carries. */
internal const val REELS = "CLIPS"
internal const val HOME = "FEED"
internal const val REELS_MODULE = "clips_viewer_clips_tab"

/** The saved-state key the tab host's constructor reads, and the report its tab switch files without a pager. */
internal const val TAB_HOST_STATE = "MainTabControllerImpl.BUNDLE_KEY_IS_CURRENT_TAB_LOADED"
internal const val TAB_SWITCH_REPORT = "feed_viewpager_view_not_found"

internal const val LIST = "Ljava/util/List;"
internal const val SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * Takes Reels off Instagram's tab bar. See ReelsTab in the extension for what each hook asks.
 *
 * Instagram 449 builds the tab host's list of tabs in one method, which hands back either a fixed
 * list or one ordered by the server; the extension gets the list as it's returned. The same class
 * has the method that names the home tab, which is Reels for accounts that open on Reels, and the
 * tab host's switch to a tab is where every start, notification and link that picks a tab ends up;
 * both hand their Reels to the extension, which answers with Home.
 *
 * In the default selection with its switch off, so the tab bar stays as it was until the reader turns it
 * on.
 */
@Suppress("unused")
val hideReelsTabPatch = bytecodePatch(
    name = "Hide the Reels tab",
    description = "Takes the Reels tab off the tab bar. Reels in your feed and reels people send you still open. " +
        "Restart Instagram to see the change. Starts off. Turn it on in HushGram settings > Reels. " +
        "Under Tab bar, switches do the same for Search, Create and Profile, switches take the Create and notifications buttons off Home's header, one adds a Ghost mode button to it, and a list picks the tab Instagram opens on.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        // Every anchor is found and checked, and SettingsStatus is confirmed to carry the switch's
        // method, before any hook changes an instruction.
        requireStatusMethod("reelsTab")
        // Home's header is found first too, so a build that lacks it is left as it was.
        val header = findHomeHeader()
        hideReelsTab()
        hideHomeHeaderButtons(header)
        enableStatus("reelsTab")
    }
}

/** Finds and checks the three hooks, then writes them. A build that fails any check is left as it was. */
internal fun BytecodePatchContext.hideReelsTab() {
    val found = findReelsTab()
    val builder = mutable(found.builder)
    val home = mutable(found.home)
    val switch = mutable(found.switch)
    // Code put in front of an instruction something jumps to would be skipped by the jump. The
    // builder's return may be a jump target, since it's replaced in place rather than put in front of.
    if (home.labelled(found.homeReturn)) refuse("something jumps to the return of Reels in ${home.describe()}")
    if (switch.labelled(0)) refuse("something jumps to the start of ${switch.describe()}")
    // move-result-object and check-cast name their register in eight bits.
    if (found.switchTab > 255) refuse("the tab switch ${switch.describe()} keeps its tab in v${found.switchTab}, past v255")

    askAtReturn(builder, found.builderReturn)
    askBefore(home, found.homeReturn, (home.getInstructionAt(found.homeReturn) as OneRegisterInstruction).registerA, found.tabType)
    askBefore(switch, 0, found.switchTab, found.tabType)
}

/**
 * The tab enum, the tab list builder and its single return, the home tab method and the return of
 * Reels in it, and the tab host's switch with the register holding its tab.
 */
internal class ReelsTabHooks(
    val tabType: String,
    val builder: Method,
    val builderReturn: Int,
    val home: Method,
    val homeReturn: Int,
    val switch: Method,
    val switchTab: Int,
)

internal fun BytecodePatchContext.findReelsTab(): ReelsTabHooks {
    val enums = mutableListOf<ClassDef>()
    val hosts = mutableListOf<Method>()
    val holders = (classesHolding(REELS, REELS_MODULE) + classesHolding(TAB_HOST_STATE)).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.name != "<clinit>" && method.name != "<init>") return@forEach
            val strings = method.code().mapNotNull { it.stringLoaded() }
            if (method.name == "<clinit>" && classDef.superclass == "Ljava/lang/Enum;" && REELS in strings && REELS_MODULE in strings) {
                enums += classDef
            }
            if (method.name == "<init>" && TAB_HOST_STATE in strings) hosts += method
        }
    }
    val tabs = enums.singleOrNull()
        ?: refuse("expected one enum whose setup names $REELS with $REELS_MODULE, found ${enums.size}")
    val setup = tabs.methods.single { it.name == "<clinit>" }.code()
    if (setup.none { it.stringLoaded() == HOME }) refuse("${tabs.type} has no $HOME tab to send Reels to")
    val reels = reelsField(tabs.type, setup)

    val host = hosts.singleOrNull()
        ?: refuse("expected one constructor reading \"$TAB_HOST_STATE\", found ${hosts.size}")
    val hostCode = host.code()
    val calls = hostCode.indices.mapNotNull { index ->
        val call = hostCode[index].methodReference() ?: return@mapNotNull null
        if (call.returnType != LIST || SESSION !in call.parameterTypes.map(CharSequence::toString)) return@mapNotNull null
        val kept = hostCode.getOrNull(index + 2)
        val stored = hostCode.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            kept?.opcode == Opcode.IPUT_OBJECT && kept.fieldReference()?.let { it.definingClass == host.definingClass && it.type == LIST } == true
        if (stored) call else null
    }
    val call = calls.singleOrNull()
        ?: refuse("${host.definingClass}'s constructor keeps ${calls.size} tab lists built from the session, expected one")
    val builderClass = classDefByOrNull(call.definingClass) ?: refuse("the tab list builder's class ${call.definingClass} isn't in the app")
    val builder = builderClass.methods.singleOrNull { it.sameAs(call) } ?: refuse("${call.definingClass} has no ${call.name}")
    val builderCode = builder.code()
    val returns = builderCode.indices.filter { builderCode[it].opcode == Opcode.RETURN_OBJECT }
    val builderReturn = returns.singleOrNull()
        ?: refuse("the tab list builder ${builder.describe()} returns in ${returns.size} places, expected one")

    // The home tab: Reels read and straight away returned, in a method of the builder's class answering with a tab.
    val homes = builderClass.methods.filter { it.returnType == tabs.type }.flatMap { method ->
        val code = method.code()
        code.indices.filter { index ->
            val read = code[index]
            val back = code.getOrNull(index + 1)
            read.opcode == Opcode.SGET_OBJECT && read.fieldReference()?.sameField(reels) == true &&
                back?.opcode == Opcode.RETURN_OBJECT &&
                (back as OneRegisterInstruction).registerA == (read as OneRegisterInstruction).registerA
        }.map { method to it + 1 }
    }
    val (home, homeReturn) = homes.singleOrNull()
        ?: refuse("expected one method in ${builderClass.type} that returns Reels as a tab, found ${homes.size}")

    val hostClass = classDefByOrNull(host.definingClass) ?: refuse("the tab host ${host.definingClass} isn't in the app")
    val switches = hostClass.methods.filter { method -> method.code().any { it.stringLoaded() == TAB_SWITCH_REPORT } }
    val switch = switches.singleOrNull()
        ?: refuse("expected one method in ${hostClass.type} reporting \"$TAB_SWITCH_REPORT\", found ${switches.size}")
    val tabParameters = switch.parameterTypes.indices.filter { switch.parameterTypes[it].toString() == tabs.type }
    val tabParameter = tabParameters.singleOrNull()
        ?: refuse("the tab switch ${switch.describe()} takes ${tabParameters.size} tabs, expected one")

    return ReelsTabHooks(tabs.type, builder, builderReturn, home, homeReturn, switch, switch.parameterRegisterNumber(tabParameter))
}

/** The static field [type]'s setup stores its Reels tab in: the first one of that type written after the name. */
private fun reelsField(type: String, setup: List<Instruction>): FieldReference {
    val named = setup.indices.filter { setup[it].stringLoaded() == REELS }
    val at = named.singleOrNull() ?: refuse("$type's setup names $REELS ${named.size} times, expected once")
    return setup.drop(at).firstNotNullOfOrNull { instruction ->
        instruction.fieldReference()?.takeIf { instruction.opcode == Opcode.SPUT_OBJECT && it.definingClass == type && it.type == type }
    } ?: refuse("$type's setup doesn't store its $REELS tab")
}

/**
 * The builder's return becomes the ask, so whatever jumps to it jumps to the ask, and the answer is
 * returned right after.
 */
private fun askAtReturn(method: MutableMethod, index: Int) {
    val register = (method.getInstructionAt(index) as OneRegisterInstruction).registerA
    method.replaceInstruction(index, "invoke-static/range { v$register .. v$register }, $SHOWN_TABS")
    method.addInstructions(
        index + 1,
        """
            move-result-object v$register
            return-object v$register
        """,
    )
}

/** The tab in [register] goes to the extension at [index], and its answer, cast back to a tab, takes its place. */
private fun askBefore(method: MutableMethod, index: Int, register: Int, tabType: String) {
    method.addInstructions(
        index,
        """
            invoke-static/range { v$register .. v$register }, $TAB_TO_OPEN
            move-result-object v$register
            check-cast v$register, $tabType
        """,
    )
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }

private fun MutableMethod.labelled(index: Int): Boolean =
    (implementation!!.instructions[index] as BuilderInstruction).location.labels.isNotEmpty()

private fun MutableMethod.getInstructionAt(index: Int): Instruction = implementation!!.instructions[index]

private fun Method.sameAs(other: MethodReference): Boolean =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun FieldReference.sameField(other: FieldReference): Boolean =
    definingClass == other.definingClass && name == other.name && type == other.type

private fun Method.describe(): String = "$definingClass->$name"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
