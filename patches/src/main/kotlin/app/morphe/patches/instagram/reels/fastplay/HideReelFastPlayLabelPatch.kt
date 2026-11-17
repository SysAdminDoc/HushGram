/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fastplay

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterReturnAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide the 2x popup on Reels"
internal const val FAST_PLAY_LABEL = "$EXTENSION_PACKAGE/reels/FastPlayLabel;->label(Ljava/lang/Object;)Ljava/lang/Object;"

/** The test tag the reel's fast play part gives its swipe hint as it draws (0Er0.A0p on 456's 386214574). */
internal const val FAST_PLAY_RENDER = "swipe_indicator"

/** The tag on the "2x" icon, drawn by the call in it that builds the label with its arrows and lock icon (0Er0.A00). */
internal const val FAST_PLAY_LABEL_RENDER = "2x_active_icon"

internal const val LOCK_TOAST = "$EXTENSION_PACKAGE/reels/FastPlayLabel;->lockToast(Landroid/widget/Toast;)V"

private const val TOAST = "Landroid/widget/Toast;"

/**
 * Where the "Locked at 2x speed" toast and the one when the lock ends sit: top, centered across
 * (Gravity.TOP | CENTER_HORIZONTAL), loaded as a literal right before the toast's `setGravity`. No
 * other toast Instagram shows takes it.
 */
internal const val LOCK_TOAST_GRAVITY = 0x31

private val narrowConsts = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/** Whether this calls [name] with [parameters] on a Toast. */
private fun Instruction.callsToast(name: String, parameters: String): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return call.definingClass == TOAST && call.name == name && call.returnType == "V" &&
        call.parameterTypes.joinToString("") { it.toString() } == parameters
}

/** The registers this call passes, in order. */
private fun Instruction.callRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

/**
 * Whether the `Toast.show()` at [at] shows a toast placed at [LOCK_TOAST_GRAVITY] right before it: the
 * instruction ahead sets that same toast's gravity, and the one ahead of that loads the gravity it passes.
 */
private fun placedForLock(code: List<Instruction>, at: Int): Boolean {
    val toast = code[at].callRegisters().singleOrNull() ?: return false
    val placing = code.getOrNull(at - 1)?.takeIf { it.callsToast("setGravity", "III") }?.callRegisters() ?: return false
    if (placing.size != 4 || placing[0] != toast) return false
    val gravity = code.getOrNull(at - 2)?.takeIf { it.opcode in narrowConsts } ?: return false
    return (gravity as OneRegisterInstruction).registerA == placing[1] &&
        (gravity as NarrowLiteralInstruction).narrowLiteral == LOCK_TOAST_GRAVITY
}

/**
 * Keeps the "2x" label off a reel while a held finger plays it faster, and with its own switch the
 * toasts Instagram shows when a reel's speed is locked and unlocked. In the default selection with
 * both switches off.
 */
@Suppress("unused")
val hideReelFastPlayLabelPatch = bytecodePatch(
    name = "Hide the 2x popup on Reels",
    description = "Takes the 2x label off a reel while you hold it to play faster. A second switch hides the " +
        "message Instagram shows when you lock a reel's speed. The speed-up and the lock still work. " +
        "Starts off. Turn them on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelFastLabel")
        val label = findFastPlayLabel()
        val toasts = findLockToasts()
        hideFastPlayLabel(label)
        hideLockToasts(toasts)
        enableStatus("reelFastLabel")
    }
}

/** Where a speed lock toast is shown: the `Toast.show()` call at [at] in [method], on [register]. */
internal class LockToastShow(val method: Method, val at: Int, val register: Int)

/**
 * The show of each speed lock toast: every `Toast.show()` right after a `setGravity` placing that toast
 * at [LOCK_TOAST_GRAVITY]. On 456 Redex folds both toasts into one lambda's `invoke()`, which either
 * shows each on its own branch (0BR6.invoke on 386214574, two shows) or lets the branches meet at one
 * show the two share (0BPD.invoke on 386214562, 0BP5.invoke on 386214575), so the shows are all in one
 * method, one or two of them. Any other toast that method shows is left alone. Fails before anything
 * changes when any of it isn't so.
 */
internal fun BytecodePatchContext.findLockToasts(): List<LockToastShow> {
    val placing = classesCalling(TOAST, "setGravity").flatMap { it.methods }.mapNotNull { method ->
        val code = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val shows = code.indices.filter { at -> code[at].callsToast("show", "") && placedForLock(code, at) }
        if (shows.isEmpty()) null else Triple(method, code, shows)
    }
    val (method, code, shows) = placing.singleOrNull() ?: refuse(
        "expected one method showing a toast placed at gravity 0x${LOCK_TOAST_GRAVITY.toString(16)}, found " +
            "${placing.size}: ${placing.map { "${it.first.definingClass}->${it.first.name}" }}",
    )
    if (shows.size > 2) {
        refuse("expected ${method.definingClass}->${method.name} to show the speed lock toasts once or once each, found ${shows.size}")
    }
    return shows.map { at -> LockToastShow(method, at, code[at].callRegisters().single()) }
}

/** Hands each speed lock toast to [LOCK_TOAST] in place of showing it, which shows it unless its switch is on. */
internal fun BytecodePatchContext.hideLockToasts(found: List<LockToastShow>) {
    for (show in found) {
        val method = mutableClassDefBy(show.method.definingClass).methods.single {
            it.name == show.method.name &&
                it.parameterTypes.map(Any::toString) == show.method.parameterTypes.map(Any::toString) &&
                it.returnType == show.method.returnType
        }
        method.replaceInstruction(show.at, "invoke-static/range { v${show.register} .. v${show.register} }, $LOCK_TOAST")
    }
}

/**
 * Where the fast play part hands back its label: [ret] is the index of the `return-object` right
 * after the label call's `move-result-object` in [render], and [type] the label call's return type.
 */
internal class FastPlayLabelReturn(val render: Method, val ret: Int, val type: String)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The fast play part's draw: its one instance, one parameter method loading [FAST_PLAY_RENDER]
 * that answers an object. In it, the one direct call to a method of its own class that loads
 * [FAST_PLAY_LABEL_RENDER], whose answer it returns straight away. Fails before anything changes
 * when any of it isn't so.
 */
internal fun BytecodePatchContext.findFastPlayLabel(): FastPlayLabelReturn {
    val renders = classesLoadingString(FAST_PLAY_RENDER).flatMap { it.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
            method.parameterTypes.size == 1 && method.returnType.startsWith("L") &&
            loadsString(method, FAST_PLAY_RENDER)
    }
    val render = renders.singleOrNull()
        ?: refuse("expected one fast play draw loading \"$FAST_PLAY_RENDER\", found ${renders.size}")
    val where = "${render.definingClass}->${render.name}"
    val owner = classDefBy(render.definingClass)
    val code = render.implementation!!.instructions.toList()
    val calls = code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            call.definingClass == render.definingClass &&
            owner.methods.any { method ->
                method.name == call.name && method.returnType == call.returnType &&
                    method.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString) &&
                    loadsString(method, FAST_PLAY_LABEL_RENDER)
            }
    }
    val call = calls.singleOrNull()
        ?: refuse("expected $where to call its label builder loading \"$FAST_PLAY_LABEL_RENDER\" once, found ${calls.size}")
    val result = code.getOrNull(call + 1)
    val ret = code.getOrNull(call + 2)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || ret?.opcode != Opcode.RETURN_OBJECT ||
        (result as OneRegisterInstruction).registerA != (ret as OneRegisterInstruction).registerA
    ) {
        refuse("$where doesn't return its label builder's answer straight away")
    }
    val type = ((code[call] as ReferenceInstruction).reference as MethodReference).returnType
    if (!type.startsWith("L")) refuse("$where's label builder answers $type, not an object")
    return FastPlayLabelReturn(render, call + 2, type)
}

/** Passes the label the fast play draw returns through [FAST_PLAY_LABEL], which hides it while the switch is on. */
internal fun BytecodePatchContext.hideFastPlayLabel(found: FastPlayLabelReturn) {
    val render = mutableClassDefBy(found.render.definingClass).methods.single {
        it.name == found.render.name &&
            it.parameterTypes.map(Any::toString) == found.render.parameterTypes.map(Any::toString) &&
            it.returnType == found.render.returnType
    }
    render.filterReturnAt(PATCH, found.ret, FAST_PLAY_LABEL, found.type)
}
