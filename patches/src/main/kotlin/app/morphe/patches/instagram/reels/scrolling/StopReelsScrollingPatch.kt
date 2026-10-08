/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.scrolling

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Stop Reels scrolling"
internal const val REEL_SCROLLING = "$EXTENSION_PACKAGE/reels/ReelScrolling;"
internal const val PAGER_HOOK = "$REEL_SCROLLING->pager(Ljava/lang/Object;)I"
internal const val USER_INPUT_HOOK = "$REEL_SCROLLING->userInput(Ljava/lang/Object;I)I"
internal const val PULL_HOOK = "$REEL_SCROLLING->pull()I"

/** AndroidX's pager, which Instagram keeps by name along with its public setter. */
internal const val VIEW_PAGER = "Landroidx/viewpager2/widget/ViewPager2;"
internal const val SET_USER_INPUT = "setUserInputEnabled"

/** The pull-down layout around the Reels viewer, named in Instagram's layouts so its name stays. */
internal const val PULL_LAYOUT = "Linstagram/features/clips/viewer/ui/ClipsSwipeRefreshLayout;"

/** The layout's two touch handlers, Android's names. */
internal val PULL_TOUCHES = listOf("onInterceptTouchEvent", "onTouchEvent")

/** The trace name the Reels viewer's onViewCreated gives the pager's setup. */
internal const val PAGER_SETUP = "android_purge_26_q3_ClipsViewPagerImpl_setupView"

/** The trace name of the Reels pager's own switch that lets a finger move it again. */
internal const val ENABLE_SCROLLING = "android_purge_26_q3_ClipsViewPagerImpl_enableScrolling"

private const val VIEW = "Landroid/view/View;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"

/**
 * Keeps a finger from moving the Reels viewer on to the next reel, and a pull down from loading
 * fresh ones. Included in the default selection with its switch initially off, so stopping them
 * is the user's pick. The reel you opened plays as before, and everything on it still works.
 */
@Suppress("unused")
val stopReelsScrollingPatch = bytecodePatch(
    name = "Stop Reels scrolling",
    description = "Keeps a swipe in Reels from moving on to the next reel, and a pull down from loading new ones. " +
        "The reel you opened still plays, and its buttons still work. A second switch lets you watch 20 reels, then " +
        "stops swiping until you've had a 15 minute break.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelScrolling")
        val site = findReelsScrolling()
        val pages = findPageStores()
        stopReelsScrolling(site)
        reportPages(pages)
        enableStatus("reelScrolling")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The Reels viewer's onViewCreated, by the trace name of the pager's setup. */
internal object ReelsViewerSetupFingerprint : Fingerprint(
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf(VIEW, BUNDLE),
    strings = listOf(PAGER_SETUP),
    custom = { method, _ -> method.holdsString(PAGER_SETUP) },
)

/** The Reels pager's switch that lets a finger move it again, by its trace name. */
internal object ReelsPagerEnableFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    strings = listOf(ENABLE_SCROLLING),
    custom = { method, _ -> method.holdsString(ENABLE_SCROLLING) },
)

/**
 * Where the three hooks go: after the viewer's store of its pager, with the pager's register and
 * a free local there; in front of the pager's setter, with the register of its `this`; and at the
 * start of each of the pull-down layout's touch handlers, with a free local in each.
 */
internal class ReelsScrollingSite(
    val viewer: String,
    val store: Int,
    val pager: Int,
    val answer: Int,
    val setterSelf: Int,
    val touches: Map<String, Int>,
)

/**
 * Finds all three places before anything changes, failing when one isn't there exactly once, since
 * that's an update this patch hasn't seen.
 *
 * The Reels pager is the [VIEW_PAGER] field its own [ENABLE_SCROLLING] switch reads, and the
 * viewer's onViewCreated stores it once. AndroidX's [SET_USER_INPUT] stores its parameter in one
 * field. The pull-down layout declares both [PULL_TOUCHES] itself.
 */
internal fun BytecodePatchContext.findReelsScrolling(): ReelsScrollingSite {
    val enable = uniqueMethod(PATCH, "Reels pager switch holding \"$ENABLE_SCROLLING\"", ReelsPagerEnableFingerprint)
    val enableCode = enable.instructions()
    val pagerReads = enableCode.mapNotNull { instruction ->
        instruction.fieldReference()?.takeIf {
            instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == enable.definingClass && it.type == VIEW_PAGER
        }?.toString()
    }.distinct()
    val field = pagerReads.singleOrNull()
        ?: refuse("expected ${enable.definingClass}->${enable.name} to read one $VIEW_PAGER field of its class, found ${pagerReads.size}")
    if (enableCode.none { it.methodReference()?.let { called -> called.definingClass == VIEW_PAGER && called.name == SET_USER_INPUT } == true }) {
        refuse("${enable.definingClass}->${enable.name} doesn't call $VIEW_PAGER->$SET_USER_INPUT")
    }

    val viewer = uniqueMethod(PATCH, "Reels viewer onViewCreated holding \"$PAGER_SETUP\"", ReelsViewerSetupFingerprint)
    if (AccessFlags.STATIC.isSet(viewer.accessFlags)) refuse("${viewer.definingClass}->onViewCreated is static")
    val code = viewer.instructions()
    val where = "${viewer.definingClass}->onViewCreated"
    val stores = code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].fieldReference()?.toString() == field }
    val store = stores.singleOrNull() ?: refuse("expected $where to store $field once, found ${stores.size}")
    if (store + 1 !in code.indices) refuse("$where ends at its store of $field")
    // An iput names v15 at most, so the pager's register fits the hook's invokes as it is.
    val pager = (code[store] as TwoRegisterInstruction).registerA
    // Not the pager's own register, which the hook reads after writing the answer.
    val live = viewer.liveAcrossInjection(store + 1)
    val free = (0 until minOf(viewer.localRegisterCount(), 16)).filter { it !in live && it != pager }
    val answer = free.firstOrNull()
        ?: refuse("$where has no local register up to v15 that nothing reads after storing its pager, and the hook needs 1")

    val pagerClass = classDefByOrNull(VIEW_PAGER) ?: refuse("$VIEW_PAGER isn't in this build")
    val setterSelf = findSetter(pagerClass)

    val layout = classDefByOrNull(PULL_LAYOUT) ?: refuse("$PULL_LAYOUT isn't in this build")
    val touches = PULL_TOUCHES.associateWith { name ->
        val handlers = layout.methods.filter {
            it.name == name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT)
        }
        val handler = handlers.singleOrNull() ?: refuse("expected one $name($MOTION_EVENT)Z in $PULL_LAYOUT, found ${handlers.size}")
        if (AccessFlags.STATIC.isSet(handler.accessFlags) || handler.implementation == null) {
            refuse("$PULL_LAYOUT->$name isn't an instance method with code")
        }
        handler.freeLocalsAt(PATCH, 0, 1).single()
    }
    return ReelsScrollingSite(viewer.definingClass, store, pager, answer, setterSelf, touches)
}

/**
 * The register holding `this` in the pager's [SET_USER_INPUT], whose parameter comes next. The
 * setter has to store its parameter, untouched, in one boolean field of the pager, and nothing may
 * jump back to its start.
 */
private fun findSetter(pagerClass: ClassDef): Int {
    val setters = pagerClass.methods.filter {
        it.name == SET_USER_INPUT && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
    }
    val setter = setters.singleOrNull() ?: refuse("expected one $SET_USER_INPUT(Z)V in $VIEW_PAGER, found ${setters.size}")
    val where = "$VIEW_PAGER->$SET_USER_INPUT"
    if (AccessFlags.STATIC.isSet(setter.accessFlags) || setter.implementation == null) refuse("$where isn't an instance method with code")
    val self = setter.localRegisterCount()
    val enabled = setter.parameterRegisterNumber(0)
    val code = setter.instructions()
    val stores = code.indices.filter { index ->
        val instruction = code[index]
        val stored = instruction.fieldReference()
        instruction.opcode == Opcode.IPUT_BOOLEAN && stored?.definingClass == VIEW_PAGER && stored.type == "Z" &&
            (instruction as TwoRegisterInstruction).registerA == enabled && instruction.registerB == self
    }
    val store = stores.singleOrNull() ?: refuse("expected $where to store its parameter in one field, found ${stores.size}")
    if (0 in setter.jumpTargets()) refuse("something in $where jumps back to its start")
    setter.requireParameterIntact(PATCH, 0, listOf(store))
    return self
}

/**
 * Writes the three hooks, everything having been found first.
 *
 * After the viewer stores its pager, [PAGER_HOOK] gets the pager, and on a 0 the pager's input is
 * turned off. In front of everything in [SET_USER_INPUT], [USER_INPUT_HOOK] gets the pager and the
 * value as an int and hands back the value the setter stores. At the start of each touch handler of
 * the pull-down layout, [PULL_HOOK] answers 0 to have it return false and let the touch go by, and
 * 1 to run it as before.
 */
internal fun BytecodePatchContext.stopReelsScrolling(site: ReelsScrollingSite) {
    val viewer = mutableClassDefBy(site.viewer).methods.single {
        it.name == "onViewCreated" && it.parameterTypes.map(CharSequence::toString) == listOf(VIEW, BUNDLE)
    }
    val answer = site.answer
    val pager = site.pager
    viewer.addInstructionsWithLabels(
        site.store + 1,
        """
            invoke-static { v$pager }, $PAGER_HOOK
            move-result v$answer
            if-nez v$answer, :keep
            const/4 v$answer, 0x0
            invoke-virtual { v$pager, v$answer }, $VIEW_PAGER->$SET_USER_INPUT(Z)V
        """,
        ExternalLabel("keep", viewer.getInstruction(site.store + 1)),
    )

    val setter = mutableClassDefBy(VIEW_PAGER).methods.single {
        it.name == SET_USER_INPUT && it.parameterTypes.map(CharSequence::toString) == listOf("Z")
    }
    // The setter's store names both registers, so they're v15 or below.
    val self = site.setterSelf
    val enabled = self + 1
    setter.addInstructions(
        0,
        """
            invoke-static { v$self, v$enabled }, $USER_INPUT_HOOK
            move-result v$enabled
        """,
    )

    val layout = mutableClassDefBy(PULL_LAYOUT)
    for ((name, free) in site.touches) {
        val handler = layout.methods.single {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT)
        }
        handler.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $PULL_HOOK
                move-result v$free
                if-nez v$free, :stock
                const/4 v$free, 0x0
                return v$free
            """,
            ExternalLabel("stock", handler.getInstruction(0)),
        )
    }
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.holdsString(value: String) = instructions().any { it.stringLoaded() == value }

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
