/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.instagram.misc.flags.findFlagReads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Start Home on Following"
internal const val FEED_FLAG = "$EXTENSION_PACKAGE/feed/FollowingFeed;->flag(I)Z"
internal const val SAVED_FEED = "$EXTENSION_PACKAGE/feed/FollowingFeed;->saved(Ljava/lang/String;)Ljava/lang/String;"
internal const val LIMIT_PICKER = "$EXTENSION_PACKAGE/feed/FollowingFeed;->limitPicker(Ljava/util/List;)V"
internal const val KEEP_LOGO = "$EXTENSION_PACKAGE/feed/FollowingFeed;->keepLogo(Ljava/lang/Object;)I"

/** Home's title view: a view Instagram's layouts name, so its class keeps its name across builds. */
internal const val TITLE_SWITCHER = "Lcom/instagram/actionbar/ActionBarTitleViewSwitcher;"
private const val VIEW_ANIMATOR = "Landroid/widget/ViewAnimator;"
private const val STRING = "Ljava/lang/String;"

/** The trace name of the code that builds Home's feed picker. */
internal const val FEED_PICKER = "FeedPickerStateManager"

/** How far before its read of the saved feed the picker may freeze its list of feeds. */
private const val FREEZE_REACH = 6

/** The preference Instagram keeps the feed you last picked from Home's feed picker in. */
internal const val SAVED_FEED_KEY = "last_selected_feed_type"

/** The server flag that has Instagram remember the feed you pick. On 450 it's read twelve times. */
internal const val REMEMBERED_FEED_FLAG = 0x810e6b00064f9bL

/**
 * The server flag that puts For you first in Home's feed picker and the picked feed's name at the
 * top of Home. On 450 it's read three times. Without it the picker has no For you, since Home
 * stands for it, and Home's top shows only the picker's arrow.
 */
internal const val FOR_YOU_PICKER_FLAG = 0x810e6b00004f95L

/** The flags the patch answers on: together they're Instagram's own For you and Following picker. */
internal val FEED_PICKER_FLAGS = listOf(REMEMBERED_FEED_FLAG, FOR_YOU_PICKER_FLAG)

/**
 * Opens Home on the Following feed. In the default selection with its switch off: which feed Home
 * starts on is the user's pick, and For you stays one tap away at the top of Home.
 */
@Suppress("unused")
val startOnFollowingPatch = bytecodePatch(
    name = "Start Home on Following",
    description = "Opens Home on posts from accounts you follow instead of For you. A second switch removes For " +
        "you from Home, and a third keeps Instagram's logo at the top of Home in place of the feed's name. Restart " +
        "Instagram to see the change. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("followingFeed")
        val reads = findFlagReads(PATCH, FEED_PICKER_FLAGS)
        val saved = findSavedFeedReturn()
        val title = findTitleLabel()
        // The getter reads its flag before it returns, so its return is hooked first, while the
        // reads' indexes still hold.
        defaultSavedFeed(saved)
        answerFlagReads(reads, FEED_FLAG)
        // Found after the flags are answered, since the picker reads them in the same method.
        limitPicker(findPickerFreeze(saved))
        keepLogoInTitle(title)
        enableStatus("followingFeed")
    }
}

/** Where the saved feed's getter hands back the name it read: the class, the method, and the return's index and register. */
internal class SavedFeedReturn(
    val type: String,
    val name: String,
    val returnAt: Int,
    val register: Int,
)

/**
 * Finds the one class whose constructor names [SAVED_FEED_KEY], its one getter (an instance method
 * taking nothing and answering a String) that loads [REMEMBERED_FEED_FLAG], and in it the
 * return of the name it read, cast to a String. Fails when any of them isn't exactly one, since
 * that's an update the patch hasn't seen.
 */
internal fun BytecodePatchContext.findSavedFeedReturn(): SavedFeedReturn {
    val holders = mutableListOf<Pair<String, List<Method>>>()
    classesHolding(SAVED_FEED_KEY).forEach { classDef ->
        if (classDef.methods.any { it.name == "<init>" && SAVED_FEED_KEY in it.strings() }) {
            holders += classDef.type to classDef.methods.toList()
        }
    }
    val (type, methods) = holders.singleOrNull()
        ?: refuse("expected one class keeping $SAVED_FEED_KEY, found ${holders.size}")
    val getters = methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<init>" &&
            it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    }
    val getter = getters.singleOrNull() ?: refuse("$type has ${getters.size} getters of the saved feed, expected one")
    val code = getter.implementation!!.instructions.toList()
    val where = "$type->${getter.name}"
    if (code.none { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == REMEMBERED_FEED_FLAG }) {
        refuse("$where doesn't ask the remembered feed flag")
    }
    val returns = code.indices.filter { at ->
        val cast = code.getOrNull(at - 1)
        code[at].opcode == Opcode.RETURN_OBJECT && cast?.opcode == Opcode.CHECK_CAST &&
            (cast as ReferenceInstruction).reference.toString() == "Ljava/lang/String;" &&
            (cast as OneRegisterInstruction).registerA == (code[at] as OneRegisterInstruction).registerA
    }
    val returnAt = returns.singleOrNull() ?: refuse("$where returns a saved name ${returns.size} times, expected once")
    return SavedFeedReturn(type, getter.name, returnAt, (code[returnAt] as OneRegisterInstruction).registerA)
}

/** Passes the saved feed's name through [SAVED_FEED] on its way out of the getter. */
internal fun BytecodePatchContext.defaultSavedFeed(saved: SavedFeedReturn) {
    val method = mutableClassDefBy(saved.type).methods.single {
        it.name == saved.name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    }
    val name = saved.register
    method.addInstructionsAtControlFlowLabel(
        saved.returnAt,
        """
            invoke-static/range { v$name .. v$name }, $SAVED_FEED
            move-result-object v$name
        """,
    )
}

/** Where the picker freezes its list of feeds: the method, the call's index and the list's register. */
internal class PickerFreeze(val type: String, val name: String, val parameters: List<String>, val at: Int, val register: Int)

/**
 * Finds the one method outside the extension that loads [FEED_PICKER] and reads the saved feed
 * through [saved]'s getter, once. Just before that read, within [FREEZE_REACH] instructions, a
 * static call takes a List and freezes the picker's feeds: the list it's handed is the one the
 * patch filters. Fails when any of these isn't there exactly once, since that's an update the
 * patch hasn't seen.
 */
internal fun BytecodePatchContext.findPickerFreeze(saved: SavedFeedReturn): PickerFreeze {
    val getter = "${saved.type}->${saved.name}()Ljava/lang/String;"
    val pickers = mutableListOf<Pair<String, Method>>()
    classesHolding(FEED_PICKER).forEach { classDef ->
        classDef.methods.filter { FEED_PICKER in it.strings() && it.calls(getter) > 0 }.forEach { pickers += classDef.type to it }
    }
    val (type, picker) = pickers.singleOrNull()
        ?: refuse("expected one method loading $FEED_PICKER and reading the saved feed, found ${pickers.size}")
    val code = picker.implementation!!.instructions.toList()
    val where = "$type->${picker.name}"
    val read = code.indices.singleOrNull { code[it].calledSignature() == getter }
        ?: refuse("$where reads the saved feed ${picker.calls(getter)} times, expected once")
    val freezes = (maxOf(0, read - FREEZE_REACH) until read).filter { at ->
        val instruction = code[at]
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode == Opcode.INVOKE_STATIC && called != null &&
            called.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;") && called.returnType != "V"
    }
    val at = freezes.singleOrNull() ?: refuse("$where freezes ${freezes.size} lists before reading the saved feed, expected one")
    val list = (code[at] as FiveRegisterInstruction).registerC
    if (code.subList(0, at).none { it.addsTo(list) }) refuse("$where adds nothing to the list it freezes")
    return PickerFreeze(type, picker.name, picker.parameterTypes.map(CharSequence::toString), at, list)
}

/**
 * Just before the picker freezes its list, hands the list to [LIMIT_PICKER]. Every branch that
 * landed on the freeze lands on the call first.
 */
internal fun BytecodePatchContext.limitPicker(freeze: PickerFreeze) {
    val method = mutableClassDefBy(freeze.type).methods.single {
        it.name == freeze.name && it.parameterTypes.map(CharSequence::toString) == freeze.parameters
    }
    method.addInstructionsAtControlFlowLabel(
        freeze.at,
        "invoke-static/range { v${freeze.register} .. v${freeze.register} }, $LIMIT_PICKER",
    )
}

/**
 * Where Home's title view shows the picked feed's name: the class, the method that shows a name
 * (it takes the arrow's visibility and the name), the sibling that shows the logo instead (it takes
 * a title, a second label, a delay flag and the arrow's visibility), and a free local register.
 */
internal class TitleLabel(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val logo: String,
    val free: Int,
)

/**
 * Finds the two ways [TITLE_SWITCHER], a ViewAnimator, shows its title: its one method taking a
 * boolean and a String that switches to its second child (the feed's name and arrow), and its one
 * taking two Strings and two booleans that switches to its first (Instagram's logo). Fails when
 * either isn't there exactly once, when the name method has no free local register to hold the
 * hook's answer, when its registers pass v15 or when something jumps to its start.
 */
internal fun BytecodePatchContext.findTitleLabel(): TitleLabel {
    val switcher = classDefByOrNull(TITLE_SWITCHER) ?: refuse("this Instagram build has no $TITLE_SWITCHER")
    if (switcher.superclass != VIEW_ANIMATOR) refuse("$TITLE_SWITCHER extends ${switcher.superclass}, not a ViewAnimator")
    val labels = switcher.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Z", STRING) && method.showsChild(1)
    }
    val label = labels.singleOrNull() ?: refuse("expected one method in $TITLE_SWITCHER showing the feed's name, found ${labels.size}")
    val logos = switcher.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(STRING, STRING, "Z", "Z") && method.showsChild(0)
    }
    val logo = logos.singleOrNull() ?: refuse("expected one method in $TITLE_SWITCHER showing the logo, found ${logos.size}")
    // The call to the logo method names this, the arrow's flag and the free local, all within v15.
    if (label.localRegisterCount() + 1 > 15) refuse("${label.name} in $TITLE_SWITCHER keeps its parameters past v15")
    if (label.jumpTargets().contains(0)) refuse("something jumps to the start of ${label.name} in $TITLE_SWITCHER")
    val free = label.freeLocalsAt("Home title logo", 0, 1).single()
    return TitleLabel(switcher.type, label.name, label.parameterTypes.map(CharSequence::toString), logo.name, free)
}

/**
 * First thing in the name method, asks [KEEP_LOGO]. A yes shows the logo, with the arrow the caller
 * asked for, and returns before the name is set. Only called once [findTitleLabel] found it.
 */
internal fun BytecodePatchContext.keepLogoInTitle(title: TitleLabel) {
    val method = mutableClassDefBy(title.type).methods.single {
        it.name == title.name && it.parameterTypes.map(CharSequence::toString) == title.parameters
    }
    val free = "v${title.free}"
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p0 }, $KEEP_LOGO
            move-result $free
            if-eqz $free, :name
            const/4 $free, 0x0
            invoke-virtual { p0, $free, $free, $free, p1 }, ${title.type}->${title.logo}(${STRING}${STRING}ZZ)V
            return-void
        """,
        ExternalLabel("name", method.getInstruction(0)),
    )
}

/** Whether this calls setDisplayedChild with [child], set by the constant right before the call. */
private fun Method.showsChild(child: Int): Boolean {
    val code = implementation?.instructions?.toList() ?: return false
    return code.indices.any { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        if (call.name != "setDisplayedChild" || call.parameterTypes.map(CharSequence::toString) != listOf("I")) return@any false
        val arguments = code[at] as? FiveRegisterInstruction ?: return@any false
        val set = code.getOrNull(at - 1)
        set is NarrowLiteralInstruction && set is OneRegisterInstruction && set.registerA == arguments.registerD &&
            set.narrowLiteral == child
    }
}

private fun Instruction.calledSignature(): String? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val called = (this as ReferenceInstruction).reference as MethodReference
    return "${called.definingClass}->${called.name}(${called.parameterTypes.joinToString("")})${called.returnType}"
}

private fun Method.calls(signature: String) = implementation?.instructions?.count { it.calledSignature() == signature } ?: 0

/** Whether this calls add on a collection held in [register], as the picker fills its list. */
private fun Instruction.addsTo(register: Int): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_INTERFACE) return false
    val called = (this as ReferenceInstruction).reference as MethodReference
    return called.name == "add" && called.parameterTypes.size == 1 && (this as FiveRegisterInstruction).registerC == register
}

private fun Method.strings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    ?.toSet() ?: emptySet()

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
