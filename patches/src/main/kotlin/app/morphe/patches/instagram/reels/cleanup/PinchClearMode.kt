/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val PINCH_CLEAR_MODE_PART = "Clean up Reels: Pinch for clear mode"
internal const val REEL_PINCH_CLEAR_MODE = "$EXTENSION_PACKAGE/reels/ReelPinchClearMode;"
internal const val PINCH_CLEAR_MODE = "$REEL_PINCH_CLEAR_MODE->pinch(I)Z"
internal const val EXIT_BUTTON_DRAWABLE = "Landroid/graphics/drawable/Drawable;"
internal const val CLEAR_MODE_EXIT_ICON = "$REEL_PINCH_CLEAR_MODE->exitIcon($EXIT_BUTTON_DRAWABLE)$EXIT_BUTTON_DRAWABLE"
internal const val CLEAR_MODE_EXIT_PART = "$REEL_PINCH_CLEAR_MODE->exitPart(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
internal const val PINCH_CLEAR_MODE_TURN = "$REEL_PINCH_CLEAR_MODE->turn(Ljava/lang/Object;Ljava/lang/Object;I)V"
internal const val PINCH_CLEAR_MODE_WRITE =
    "$REEL_PINCH_CLEAR_MODE->write(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V"
internal const val PINCH_CLEAR_MODE_STATE_STUB = "state"
internal const val PINCH_CLEAR_MODE_PUT_STUB = "put"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * The server flag behind Instagram's own pinch for clear mode, read each time two fingers lift off a
 * reel, in the action the Reels viewer hands its pinch listener (`LX/0BV9;->invoke` on 456's
 * 386214574, called from `LX/0DCL;->onScaleEnd`). Yes writes the reel's clear mode state on, and
 * logs PINCH_TO_ZOOM_IN, or off with PINCH_TO_ZOOM_OUT when the state says it's in it already. On
 * 456 nothing redraws for that write, so the reel looks the same. 386214534 and 386214574 write
 * through an interface (`LX/0mqG;->HUn`), and 386214562 and 386214575, where Redex inlined that
 * method, through the store's own static write (`LX/07C2;->A08` on 386214575), handed the state's
 * key and a boxed yes or no. The listener only gets the pinch
 * while Instagram's zoom for Reels is off.
 */
// 457 moved it from 456's 0x810586000a199f.
internal const val PINCH_CLEAR_MODE_FLAG = 0x81057f000a1998L

/**
 * Where Pinch for clear mode goes (#744): the flag's read, the pinch's two writes of the reel's
 * clear mode state and the method they call, and, where Keep clear mode on found the exit button's
 * render, the three places in it that Hide the exit button in clear mode changes.
 */
internal class PinchClearModeHooks(
    val pinch: FlagLoad,
    /** How many instructions the read's method had when the read was found. */
    val length: Int,
    /** The two writes, on and off, by index in the read's method. */
    val writes: List<Int>,
    val write: MethodReference,
    /** Whether [write] is the store's static write rather than an interface's method. */
    val static: Boolean,
    val button: Method?,
    val parts: ExitButtonParts?,
) {
    /** The extension's hook the writes are handed to. */
    val hook: String get() = if (static) PINCH_CLEAR_MODE_WRITE else PINCH_CLEAR_MODE_TURN
}

/**
 * The exit button render's places, by index: the call building the button's icon, with the register
 * its drawable is in, and the calls adding the circle behind it and its tap to the button's modifier,
 * each with the register it answers into and the one holding the modifier it was handed.
 */
internal class ExitButtonParts(
    val icon: Int,
    val iconDrawable: Int,
    val circle: Int,
    val circleAnswer: Int,
    val circleBefore: Int,
    val tap: Int,
    val tapAnswer: Int,
    val tapBefore: Int,
    val modifier: String,
) {
    fun sameShape(other: ExitButtonParts): Boolean =
        iconDrawable == other.iconDrawable && circleAnswer == other.circleAnswer && circleBefore == other.circleBefore &&
            tapAnswer == other.tapAnswer && tapBefore == other.tapBefore && modifier == other.modifier
}

private fun stopPinch(detail: String): Nothing = throw PatchException("$PINCH_CLEAR_MODE_PART: $detail")

/**
 * What [findPinchClearMode] finds, or null with a warning when the build differs, so Clean up Reels
 * goes in without Pinch for clear mode rather than not at all. An exit button that doesn't fit
 * leaves only Hide the exit button in clear mode out.
 */
internal fun BytecodePatchContext.findPinchClearModeWhereItFits(clearMode: ClearModeHooks?): PinchClearModeHooks? = try {
    findPinchClearMode(clearMode)
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. Clean up Reels goes in without Pinch for clear mode.")
    null
}

/**
 * The one read of [PINCH_CLEAR_MODE_FLAG], which has to be the flag's own, decide a branch right
 * away and sit in a method that reads the reel state's clear mode flag, the way the pinch's action
 * asks whether to turn clear mode on or off. After that ask come the pinch's two writes of the
 * reel's state, one per branch, both to one method: an interface's, taking the reel Keep clear mode
 * on found and a yes or no, or a public class's static one, taking the reel, a key, a store, a value
 * and a yes or no. Every class the extension's stub casts to has to be public. Then the exit button's parts in the render [clearMode]
 * found, or null with a warning where they don't fit. Fails before anything changes when the read
 * isn't so, or when Keep clear mode on wasn't found, since its handler is what turns clear mode on
 * and off.
 */
internal fun BytecodePatchContext.findPinchClearMode(clearMode: ClearModeHooks?): PinchClearModeHooks {
    if (clearMode == null) stopPinch("Keep clear mode on's places weren't found, so nothing can turn clear mode on and off for a pinch")
    val button = clearMode.button
    val id = PINCH_CLEAR_MODE_FLAG.toString(16)
    val loads = findFlagLoads(PINCH_CLEAR_MODE_PART, PINCH_CLEAR_MODE_FLAG, "Z")
    val load = loads.singleOrNull() ?: stopPinch("expected one read of the pinch flag $id, found ${loads.size}")
    if (load.shared) stopPinch("the read of $id in ${load.type}->${load.name} is shared with another flag")
    val method = classDefBy(load.type).methods.single {
        it.name == load.name && it.returnType == load.returnType && it.parameterTypes.map(CharSequence::toString) == load.parameters
    }
    val code = method.implementation!!.instructions.toList()
    val test = code.getOrNull(load.resultAt + 1)
    if (test == null || (test.opcode != Opcode.IF_EQZ && test.opcode != Opcode.IF_NEZ) ||
        (test as OneRegisterInstruction).registerA != load.register
    ) {
        stopPinch("${load.type}->${load.name} doesn't test the answer of $id right after reading it")
    }
    if (load.resultAt + 1 in method.jumpTargets()) stopPinch("${load.type}->${load.name} jumps to its test of $id, so the answer would be skipped")
    val flag = reelClearModeFlag()
    val asks = code.indexOfFirst { instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        instruction.opcode == Opcode.IGET_BOOLEAN && field != null && field.definingClass == flag.definingClass &&
            field.name == flag.name && field.type == flag.type
    }
    if (asks < 0) stopPinch("${load.type}->${load.name} reads $id without asking whether the reel is in clear mode")
    val writes = code.indices.filter { it > asks && code[it].writesReelState(clearMode.item) }
    if (writes.size != 2) {
        stopPinch("expected the pinch's two writes of the reel's clear mode state in ${load.type}->${load.name}, found ${writes.size}")
    }
    val write = (code[writes[0]] as ReferenceInstruction).reference as MethodReference
    if (writes.any { "${(code[it] as ReferenceInstruction).reference}" != "$write" || code[it].opcode != code[writes[0]].opcode }) {
        stopPinch("the pinch's two writes of the reel's clear mode state call different methods")
    }
    val static = code[writes[0]].opcode == Opcode.INVOKE_STATIC || code[writes[0]].opcode == Opcode.INVOKE_STATIC_RANGE
    val target = classDefByOrNull(write.definingClass)
    if (target == null || !AccessFlags.PUBLIC.isSet(target.accessFlags)) {
        stopPinch("the pinch writes the reel's clear mode state through ${write.definingClass}, which the extension can't reach")
    }
    if (static && target.methods.none { it.isPublicStatic(write) }) {
        stopPinch("the pinch's write ${write.name} in ${write.definingClass} isn't a public static method the extension can call")
    }
    val unreachable = write.stubCasts(static).firstOrNull { type ->
        val cast = classDefByOrNull(type)
        cast == null || !AccessFlags.PUBLIC.isSet(cast.accessFlags)
    }
    if (unreachable != null) stopPinch("the pinch's write takes $unreachable, which the extension can't reach")
    val stub = classDefBy(REEL_PINCH_CLEAR_MODE).methods.count { it.isWriteStub(static) }
    if (stub != 1) stopPinch("$REEL_PINCH_CLEAR_MODE has no static void ${stubName(static)}${stubParameters(static)}")
    val parts = try {
        button.exitButtonParts()
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. Pinch for clear mode goes in without Hide the exit button in clear mode.")
        null
    }
    return PinchClearModeHooks(load, code.size, writes, write, static, button.takeIf { parts != null }, parts)
}

/**
 * Whether [this] writes the reel's ([item]) state, answering nothing: an interface's method taking
 * the reel and a yes or no, or a static one taking the reel, a key, a store, a value and a yes or no.
 */
private fun Instruction.writesReelState(item: String): Boolean {
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (call.returnType != "V") return false
    val types = call.parameterTypes.map(CharSequence::toString)
    return when (opcode) {
        Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE -> types == listOf(item, "Z")
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE ->
            types.size == 5 && types[0] == item && types[1].startsWith("L") && types[2].startsWith("L") && types[3] == OBJECT && types[4] == "Z"
        else -> false
    }
}

/** The classes the extension's stub casts to before making [this] write: its interface and the reel, or the reel, key and store. */
private fun MethodReference.stubCasts(static: Boolean): List<String> {
    val types = parameterTypes.map(CharSequence::toString)
    return if (static) types.take(3) else listOf(definingClass, types[0])
}

private fun Method.isPublicStatic(write: MethodReference): Boolean =
    name == write.name && returnType == write.returnType && AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == write.parameterTypes.map(CharSequence::toString)

private fun stubName(static: Boolean) = if (static) PINCH_CLEAR_MODE_PUT_STUB else PINCH_CLEAR_MODE_STATE_STUB

private fun stubParameters(static: Boolean) =
    if (static) "(Object, Object, Object, Object, boolean)" else "(Object, Object, boolean)"

private fun Method.isWriteStub(static: Boolean): Boolean =
    name == stubName(static) && returnType == "V" && AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == if (static) listOf(OBJECT, OBJECT, OBJECT, OBJECT, "Z") else listOf(OBJECT, OBJECT, "Z")

/**
 * Answers the pinch flag through [PINCH_CLEAR_MODE] and hands the pinch's two writes of the reel's
 * clear mode state to [PINCH_CLEAR_MODE_TURN], with the same registers, and fills the extension's
 * state stub with the write, so the extension can let it through. Where the exit button's parts were
 * found, it also hands the button's icon to [CLEAR_MODE_EXIT_ICON] and its circle and tap to
 * [CLEAR_MODE_EXIT_PART], each with the modifier from before it, so a hidden button keeps its place
 * and size but draws nothing and takes no tap. Its visibility handler stays, which is how Instagram
 * hears the button leave the screen and ends clear mode on a swipe. Goes in after Keep clear mode
 * on's hooks, so the render's places are found again in the hooked render, and stops when they
 * aren't the ones found before.
 */
internal fun BytecodePatchContext.pinchClearMode(found: PinchClearModeHooks) {
    val load = found.pinch
    val reader = mutableClassDefBy(load.type).methods.single {
        it.name == load.name && it.returnType == load.returnType && it.parameterTypes.map(CharSequence::toString) == load.parameters
    }
    val code = reader.implementation!!.instructions.toList()
    if (code.size != found.length || found.writes.any { "${(code[it] as? ReferenceInstruction)?.reference}" != "${found.write}" }) {
        stopPinch("${load.type}->${load.name} changed after the read of the pinch flag was found")
    }
    val stub = mutableClassDefBy(REEL_PINCH_CLEAR_MODE).methods.single { it.isWriteStub(found.static) }
    val render = found.button?.let { button ->
        mutableClassDefBy(button.definingClass).methods.single {
            it.name == button.name && it.parameterTypes.map(Any::toString) == button.parameterTypes.map(Any::toString) &&
                it.returnType == button.returnType
        }
    }
    val parts = render?.let { hooked ->
        val again = hooked.exitButtonParts()
        if (!again.sameShape(found.parts!!)) stopPinch("the exit button's render changed shape after its places were found")
        again
    }
    // One instruction for one, so nothing below moves and the flag's read stays where it was found.
    // The hook takes as many registers as the write, all but the last an object, so they carry over as they are.
    found.writes.forEach { at ->
        val call = code[at]
        val turn = if (call is RegisterRangeInstruction) {
            "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }, ${found.hook}"
        } else {
            "invoke-static { ${call.pinchRegisters().joinToString { "v$it" }} }, ${found.hook}"
        }
        reader.replaceInstruction(at, turn)
    }
    val write = found.write
    val casts = write.stubCasts(found.static).mapIndexed { register, type -> "check-cast p$register, $type" }.joinToString("\n")
    val original = if (found.static) "invoke-static { p0, p1, p2, p3, p4 }, $write" else "invoke-interface { p0, p1, p2 }, $write"
    stub.addInstructions(
        0,
        """
            $casts
            $original
            return-void
        """,
    )
    answerFlagLoads(listOf(load to PINCH_CLEAR_MODE))
    if (render != null && parts != null) hideExitButton(render, parts)
}

private fun hideExitButton(render: MutableMethod, parts: ExitButtonParts) {
    val modifier = parts.modifier
    fun kept(answer: Int, before: Int) = """
        invoke-static { v$before, v$answer }, $CLEAR_MODE_EXIT_PART
        move-result-object v$answer
        check-cast v$answer, $modifier
    """
    val icon = """
        invoke-static/range { v${parts.iconDrawable} .. v${parts.iconDrawable} }, $CLEAR_MODE_EXIT_ICON
        move-result-object v${parts.iconDrawable}
    """
    // Last place first, so no write moves another's. The tap's answer and the icon's call can share a
    // place, and either order there is the same, since they touch different registers.
    listOf(
        parts.icon to icon,
        parts.tap + 2 to kept(parts.tapAnswer, parts.tapBefore),
        parts.circle + 2 to kept(parts.circleAnswer, parts.circleBefore),
    ).sortedByDescending { it.first }.forEach { (at, smali) -> render.addInstructions(at, smali) }
}

/**
 * The exit button's places in [this] render: the one static call taking a drawable and a modifier and
 * answering something else, the button's icon; the one taking a drawable and a modifier and answering
 * a modifier, the circle behind it; and the tap, the static call taking a modifier and an action and
 * answering a modifier, whose answer is the modifier the icon gets, with nothing in between writing
 * it. Each answer is moved straight into a register other than the modifier it was handed, which
 * stays as it was for the hook, and the hooks' two-register calls need both under v16. Nothing may
 * jump into a place a hook goes, or the hook would be skipped.
 */
internal fun Method.exitButtonParts(): ExitButtonParts {
    val code = implementation?.instructions?.toList() ?: stopPinch("the exit button's render $definingClass->$name has no body")
    fun staticCall(at: Int): MethodReference? {
        val instruction = code[at]
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return null
        return (instruction as ReferenceInstruction).reference as? MethodReference
    }
    fun parameters(call: MethodReference) = call.parameterTypes.map(CharSequence::toString)
    val icons = code.indices.filter { at ->
        val call = staticCall(at) ?: return@filter false
        val types = parameters(call)
        types.size == 2 && types[0] == EXIT_BUTTON_DRAWABLE && call.returnType.startsWith("L") && call.returnType != types[1]
    }
    val icon = icons.singleOrNull() ?: stopPinch("expected one call building the exit button's icon, found ${icons.size}")
    val modifier = parameters(staticCall(icon)!!)[1]
    val (iconDrawable, iconModifier) = code[icon].pinchRegisters()
    val circles = code.indices.filter { at ->
        val call = staticCall(at) ?: return@filter false
        parameters(call) == listOf(EXIT_BUTTON_DRAWABLE, modifier) && call.returnType == modifier
    }
    val circle = circles.singleOrNull() ?: stopPinch("expected one call adding the exit button's circle, found ${circles.size}")
    val circleAnswer = answerOf(code, circle) ?: stopPinch("the exit button's circle isn't moved straight into a register")
    val circleBefore = code[circle].pinchRegisters()[1]
    val written = (icon - 1 downTo 0).firstOrNull { code[it].writesRegister(iconModifier) }
        ?: stopPinch("the exit button's icon gets a modifier nothing wrote")
    val tap = written - 1
    val tapCall = staticCall(tap.coerceAtLeast(0))
    if (code[written].opcode != Opcode.MOVE_RESULT_OBJECT || tapCall == null ||
        parameters(tapCall) != listOf(modifier, FUNCTION1) || tapCall.returnType != modifier
    ) {
        stopPinch("the exit button's icon doesn't get its modifier straight from the call adding its tap")
    }
    val tapAnswer = iconModifier
    val tapBefore = code[tap].pinchRegisters()[0]
    if (tapBefore == tapAnswer || circleBefore == circleAnswer) stopPinch("the exit button writes a modifier over the one it was handed")
    if (listOf(tapBefore, tapAnswer, circleBefore, circleAnswer).any { it > 15 }) {
        stopPinch("the exit button keeps its modifiers past v15, out of reach of the hook's call")
    }
    val targets = jumpTargets()
    if (listOf(icon, tap + 2, circle + 2).any { it in targets }) stopPinch("the exit button's render jumps into a place a hook goes")
    return ExitButtonParts(icon, iconDrawable, circle, circleAnswer, circleBefore, tap, tapAnswer, tapBefore, modifier)
}

/** The register the call at [at] answers into, when the next instruction moves its answer straight into one. */
private fun answerOf(code: List<Instruction>, at: Int): Int? {
    val next = code.getOrNull(at + 1) ?: return null
    return if (next.opcode == Opcode.MOVE_RESULT_OBJECT) (next as OneRegisterInstruction).registerA else null
}

/** The registers an invoke names, in order. */
private fun Instruction.pinchRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** Whether [this] writes [register], wide writes included. */
private fun Instruction.writesRegister(register: Int): Boolean {
    if (this !is OneRegisterInstruction) return false
    if (opcode.setsRegister() && registerA == register) return true
    return opcode.setsWideRegister() && registerA + 1 == register
}
