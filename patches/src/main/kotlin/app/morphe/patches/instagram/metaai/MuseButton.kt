/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.profile.threads.BAR_GROUP
import app.morphe.patches.instagram.profile.threads.PROFILE_ACTION_BAR
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val MUSE_BUTTON = "$EXTENSION_PACKAGE/metaai/MetaAi;->museButton()Z"

/**
 * The tag the Muse button's own view, the animated blue m, logs under when its video won't start or
 * let go. Hatch is Muse's name inside Instagram, and no other view in the bar holds it.
 */
internal const val MUSE_BUTTON_VIEW = "HatchUpsellVideoButton"

/**
 * What the static method filling the profile's top bar takes, in order: the activity, a context,
 * the session, the bar's two groups of buttons, its logger (any class, since Redex renames it), the
 * list of the bar's items and a callback.
 */
private val BAR_FILLER = listOf(
    "Landroid/app/Activity;", "Landroid/content/Context;", "Lcom/instagram/common/session/UserSession;",
    BAR_GROUP, BAR_GROUP, null, "Ljava/util/List;", "Lkotlin/jvm/functions/Function1;",
)

/** Which of [BAR_FILLER] is the list of the bar's items. */
private const val ITEMS = 6

private const val LIST_ITERATOR = "Ljava/util/List;->iterator()Ljava/util/Iterator;"
private const val HAS_NEXT = "Ljava/util/Iterator;->hasNext()Z"
private const val NEXT = "Ljava/util/Iterator;->next()Ljava/lang/Object;"

/**
 * Where the bar's filler tests an item for the Muse button: its class, name and parameters, the
 * Muse button's item class, the index of the branch on that test, and the index of the hasNext at
 * the head of the walk of the items.
 */
internal class MuseButtonTest(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val item: String,
    val branch: Int,
    val loop: Int,
)

/**
 * Your profile's top bar is filled by one static method that [PROFILE_ACTION_BAR] calls. It empties
 * the bar's two groups, then walks the list of items it's handed, testing each one's class in turn
 * and adding one view for it before going back for the next. The Muse button's item is the one
 * class it tests for whose view comes from a static builder answering the view that holds
 * [MUSE_BUTTON_VIEW]. It's tested for once, with an if-eqz on the answer right after, so the code
 * just inside that branch runs for the Muse button and nothing else.
 *
 * Fails, since that's an update this patch hasn't seen, when the bar is missing or calls no such
 * filler or more than one, when no tested class or more than one has the Muse view's builder, when
 * the filler tests for it any number of times but once or doesn't branch right on the answer, when
 * what it tests isn't what the walk of its list took next, or when something else jumps into the
 * branch.
 */
internal fun BytecodePatchContext.findMuseButtonTest(): MuseButtonTest {
    val bar = classDefByOrNull(PROFILE_ACTION_BAR)
        ?: throw PatchException("Hide Meta AI: the profile's top bar $PROFILE_ACTION_BAR is missing")
    val calls = bar.methods.flatMap { it.code() }.mapNotNull { it.staticCall() }.filter { it.fillsBar() }
        .distinctBy { it.toString() }
    val call = calls.singleOrNull()
        ?: throw PatchException("Hide Meta AI: $PROFILE_ACTION_BAR calls ${calls.size} methods filling it, not one")
    val parameters = call.parameterTypes.map(CharSequence::toString)
    val filler = classDefByOrNull(call.definingClass)?.methods?.singleOrNull {
        it.name == call.name && it.returnType == call.returnType && it.parameterTypes.map(CharSequence::toString) == parameters
    } ?: throw PatchException("Hide Meta AI: the profile's top bar's filler $call is missing")
    val where = "Hide Meta AI: ${call.definingClass}->${call.name}"
    val code = filler.code()

    val tested = code.filter { it.opcode == Opcode.INSTANCE_OF }.mapTo(HashSet()) { it.referenceText() }
    val museViews = classesLoadingString(MUSE_BUTTON_VIEW).mapTo(HashSet()) { it.type }
    val items = code.mapNotNull { it.staticCall() }.filter { it.returnType in museViews }
        .flatMap { builder -> builder.parameterTypes.map(CharSequence::toString).filter { it in tested } }.distinct()
    val item = items.singleOrNull()
        ?: throw PatchException("$where builds the Muse button ($MUSE_BUTTON_VIEW) for ${items.size} kinds of item, not one")
    val tests = code.indices.filter { code[it].opcode == Opcode.INSTANCE_OF && code[it].referenceText() == item }
    val test = tests.singleOrNull()
        ?: throw PatchException("$where tests for the Muse button's item $item ${tests.size} times, not once")
    val answer = code[test] as TwoRegisterInstruction
    val branch = code.getOrNull(test + 1)
    if (branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != answer.registerA) {
        throw PatchException("$where doesn't branch right on its test for the Muse button's item")
    }
    val loop = itemLoop(filler, code, where, answer.registerB, test)
    if (test + 2 in filler.jumpTargets()) throw PatchException("$where has something else jump into the Muse button's branch")
    return MuseButtonTest(call.definingClass, call.name, parameters, item, test + 1, loop)
}

/**
 * The head of the filler's walk of its items: the one hasNext on the iterator it takes from its
 * list of items. Fails unless it takes that iterator once, asks it hasNext and next once each, and
 * the register the Muse test reads, [item], is the one next's answer went in, between the head
 * and the [test].
 */
private fun itemLoop(filler: Method, code: List<Instruction>, where: String, item: Int, test: Int): Int {
    val list = filler.parameterRegisterNumber(ITEMS)
    val iterators = code.indices.filter { code[it].referenceText() == LIST_ITERATOR && code[it].firstRegister() == list }
    val iterator = iterators.singleOrNull()?.let { code.resultAt(it + 1) }
        ?: throw PatchException("$where takes its items' iterator ${iterators.size} times, not once")
    val heads = code.indices.filter { code[it].referenceText() == HAS_NEXT && code[it].firstRegister() == iterator }
    val nexts = code.indices.filter { code[it].referenceText() == NEXT && code[it].firstRegister() == iterator }
    val head = heads.singleOrNull()
        ?: throw PatchException("$where asks its items' iterator hasNext ${heads.size} times, not once")
    val next = nexts.singleOrNull()
        ?: throw PatchException("$where asks its items' iterator next ${nexts.size} times, not once")
    if (code.resultAt(next + 1) != item || head > next || next > test) {
        throw PatchException("$where tests something other than the item its walk took next for the Muse button")
    }
    return head
}

/**
 * Puts the ask just inside the Muse button's branch. With Hide the Muse button on your profile on,
 * the filler goes back to the head of its walk, as every item's branch does once its view is in,
 * so the bar moves on to its next item without the button. The switch is read each time the bar is
 * filled, so off and Pause add it the way Instagram does. The answer goes in a local that nothing
 * reads from the branch's start or from the loop's head.
 */
internal fun BytecodePatchContext.holdMuseButton(test: MuseButtonTest) {
    val method = mutableClassDefBy(test.type).methods.single {
        it.name == test.name && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == test.parameters
    }
    val code = method.code()
    val intact = code.getOrNull(test.branch - 1)?.let { it.opcode == Opcode.INSTANCE_OF && it.referenceText() == test.item } == true &&
        code.getOrNull(test.branch)?.opcode == Opcode.IF_EQZ && code.getOrNull(test.loop)?.referenceText() == HAS_NEXT
    if (!intact) throw PatchException("Hide Meta AI: something moved ${test.type}->${test.name}'s Muse test before it was hooked")
    val start = test.branch + 1
    val answer = method.freeLocalsAt("Hide Meta AI", start, 1, targets = listOf(test.loop), highest = 255).single()
    method.addInstructionsWithLabels(
        start,
        """
            invoke-static { }, $MUSE_BUTTON
            move-result v$answer
            if-nez v$answer, :next
        """,
        ExternalLabel("next", method.getInstruction(test.loop)),
    )
}

/** Whether this is the filler's shape: [BAR_FILLER], answering nothing. */
private fun MethodReference.fillsBar(): Boolean {
    val takes = parameterTypes.map(CharSequence::toString)
    return returnType == "V" && takes.size == BAR_FILLER.size &&
        BAR_FILLER.zip(takes).all { (wanted, taken) -> wanted?.equals(taken) ?: taken.startsWith("L") }
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

private fun Instruction.staticCall(): MethodReference? =
    if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) (this as ReferenceInstruction).reference as? MethodReference else null

/** The first register an invoke hands over, if it hands any. */
private fun Instruction.firstRegister(): Int? = when (this) {
    is FiveRegisterInstruction -> registerC.takeIf { registerCount > 0 }
    is RegisterRangeInstruction -> startRegister.takeIf { registerCount > 0 }
    else -> null
}

/** The register a move-result-object at [index] fills, or null when there's none there. */
private fun List<Instruction>.resultAt(index: Int): Int? =
    getOrNull(index)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { (it as OneRegisterInstruction).registerA }
