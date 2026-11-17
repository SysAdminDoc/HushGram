/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.topbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep the Reels top bar"
internal const val KEEP_REELS_TOP_BAR_STATUS = "keepReelsTopBar"
internal const val REELS_TOP_BAR_SCROLL = "$EXTENSION_PACKAGE/reels/ReelsTopBarScroll;"
internal const val KEEP_IN_PLACE = "$REELS_TOP_BAR_SCROLL->keepInPlace(I)Z"

/**
 * The top bar, a class Redex leaves its name on. Its scroll callback, onReelScroll, is A0O on 456's
 * 386214574; 455 also traced ClipsViewerActionBar_onReelScroll there, which 456 dropped.
 */
internal const val ACTION_BAR = "Linstagram/features/clips/viewer/actionbar/ClipsViewerActionBar;"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * onReelScroll's parameters: the session, the scroll state, the first visible item and the scroll
 * distance, then five flags. The second flag, at [FLAG], is the one it hands each update as
 * [UPDATE_FLAG].
 */
private const val FLAG = 5

/**
 * The scroll helper's update, which moves one part of the bar (0AU0.A03 on 456's 386214574; 455 also
 * traced ClipsViewerActionBarScrollHelper_update there). Its parameters: the view, the scroll state,
 * its key, a callback, the first visible item and the distance, then seven flags. The third flag, at
 * [UPDATE_FLAG], keeps the view in place. A null is a type the build names its own way.
 */
private val UPDATE_PARAMETERS =
    listOf("Landroid/view/View;", null, null, "Lkotlin/jvm/functions/Function0;", "I", "I") + List(7) { "Z" }

/** The index of the update's flag that keeps the view in place, among [UPDATE_PARAMETERS]. */
private const val UPDATE_FLAG = 8

private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/**
 * Keeps the row at the top of a reels viewer in place while you swipe through reels (#411). See
 * ReelsTopBarScroll in the extension.
 *
 * On each scroll the top bar's onReelScroll hands its title, its tabs and its buttons, one at a
 * time, to the scroll helper's update with a flag that keeps the view where it is. Without it the
 * view slides up with the reel under it until it's off screen. Instagram sets the flag only for some
 * reels. The patch passes the flag through the extension as onReelScroll starts, and with the switch
 * on it reads yes. The patch switches the status method on, and the settings screen then offers the
 * switch. In the default selection with its switch off.
 */
@Suppress("unused")
val keepReelsTopBarPatch = bytecodePatch(
    name = "Keep the Reels top bar",
    description = "Keeps the Reels title, the Friends tab and the buttons beside them at the top while you " +
        "swipe through reels. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(KEEP_REELS_TOP_BAR_STATUS)
        findReelScroll().write(this)
        enableStatus(KEEP_REELS_TOP_BAR_STATUS)
    }
}

/** onReelScroll, by its class, name and parameters, and the register holding its keep-in-place flag. */
internal class ReelScroll(val type: String, val name: String, val parameters: List<String>, val flag: Int) {
    /**
     * Puts the flag through [KEEP_IN_PLACE] before the method's first instruction, keeping the answer
     * in the same register. Nothing jumps to the start (checked when it was found).
     */
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single { it.name == name && it.parameters() == parameters }
        method.addInstructions(
            0,
            """
                invoke-static/range { v$flag .. v$flag }, $KEEP_IN_PLACE
                move-result v$flag
            """,
        )
    }
}

/**
 * Finds onReelScroll, the one instance method of [ACTION_BAR] taking a session, the scroll state, two
 * ints and five flags, and in it the one update it calls, a method taking [UPDATE_PARAMETERS]. Then
 * checks onReelScroll hands its second flag, or a copy of it, to update as the flag that keeps a view
 * in place. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findReelScroll(): ReelScroll {
    val scrolls = classDefByOrNull(ACTION_BAR)?.methods?.filter { it.isReelScroll() } ?: emptyList()
    val scroll = scrolls.singleOrNull() ?: refuse(
        "expected one instance method of $ACTION_BAR taking a session, the scroll state, two ints and five flags, " +
            "found ${scrolls.size}",
    )
    val where = "${scroll.definingClass}->${scroll.name}"
    val parameters = scroll.parameters()
    val implementation = scroll.implementation ?: refuse("$where has no code")
    val updates = implementation.instructions.mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { it.isUpdate() }
    }.distinctBy { it.toString() }
    val update = updates.singleOrNull()
        ?: refuse("$where calls ${updates.size} updates taking a view, two states, a callback, two ints and seven flags, expected one")
    // The parameters take the last registers, `this` first, and each of them is one register wide.
    val self = implementation.registerCount - parameters.size - 1
    val flag = self + 1 + FLAG
    if (flag > 255) refuse("$where keeps its flag in v$flag, past v255")
    if (0 in scroll.jumpTargets()) refuse("something in $where jumps back to its start")

    val code = implementation.instructions.toList()
    val copies = copiesOf(flag, code)
    val handed = code.count { instruction ->
        instruction.calls(update) && instruction.argument(UPDATE_FLAG + 1) in copies
    }
    if (handed == 0) refuse("$where doesn't hand its second flag to ${update.definingClass}->${update.name}")
    return ReelScroll(scroll.definingClass, scroll.name, parameters, flag)
}

/** An instance method returning nothing and taking a session, any state, two ints and five flags. */
private fun Method.isReelScroll(): Boolean {
    val parameters = parameters()
    return !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" && parameters.size == 9 &&
        parameters[0] == USER_SESSION && parameters.subList(2, 4) == listOf("I", "I") && parameters.subList(4, 9).all { it == "Z" }
}

/** A method returning nothing and taking [UPDATE_PARAMETERS], any type where that list holds a null. */
private fun MethodReference.isUpdate(): Boolean {
    val parameters = parameterTypes.map(CharSequence::toString)
    return returnType == "V" && parameters.size == UPDATE_PARAMETERS.size &&
        UPDATE_PARAMETERS.zip(parameters).all { (wanted, type) -> wanted == null || wanted == type }
}

/** [register] and every register a move in [code] copies it, or a copy of it, into. */
private fun copiesOf(register: Int, code: List<Instruction>): Set<Int> {
    val copies = mutableSetOf(register)
    do {
        val before = copies.size
        code.filter { it.opcode in MOVES }.map { it as TwoRegisterInstruction }
            .filter { it.registerB in copies }.forEach { copies += it.registerA }
    } while (copies.size != before)
    return copies
}

private fun Instruction.calls(method: MethodReference): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val target = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return target.toString() == method.toString()
}

/** The register a call passes as its [index]th argument, `this` being the 0th. */
private fun Instruction.argument(index: Int): Int? = when (this) {
    is RegisterRangeInstruction -> if (index < registerCount) startRegister + index else null
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount).getOrNull(index)
    else -> null
}

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
