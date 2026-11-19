/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The extension's side of Long press a profile picture to view it (#737). */
internal const val PICTURE_HOLD = "$EXTENSION_PACKAGE/download/ProfilePictureHold;"
internal const val HOLD_HOOK = "$PICTURE_HOLD->hold(Landroid/view/View;Landroid/view/View\$OnLongClickListener;)V"

/** What the profile header's bind loads to trace each of its avatar binders, which names it on every 456 build. */
internal const val LEGACY_AVATAR = "LegacyProfileAvatarViewBinder.bindView"
internal const val COMPOSE_AVATAR = "ComposeProfileAvatarViewBinder.bindView"

/** The call the hook stands in for, on the profile header's picture only. */
internal const val SET_LONG_CLICK = "Landroid/view/View;->setOnLongClickListener(Landroid/view/View\$OnLongClickListener;)V"

/** The fields the header's picture is read from: the picture itself, and the frame around it that takes the presses. */
internal val PICTURE_VIEWS = setOf(
    "Lcom/instagram/ui/widget/imageview/PulsingMultiImageView;",
    "Lcom/instagram/common/ui/base/IgFrameLayout;",
)

private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
private const val COMPOSE_REACH = 80
private const val HOLD_PATCH = "$PROFILE_PICTURE_PATCH: Long press a profile picture to view it"

/** One method whose picture listener calls the patch replaces: [owner]'s [name], and where those calls are. */
internal class PictureHoldMethod(
    val owner: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val sites: List<Int>,
)

/**
 * Finds the calls that give the profile header's picture its long press listener, or null, after
 * the patch log says why, so the rest of Save profile picture goes in without it. The legacy bind is
 * the one method loading both [LEGACY_AVATAR] and [COMPOSE_AVATAR]; the Compose binder is the
 * method it calls with a Fragment right after loading [COMPOSE_AVATAR], and goes without it when it
 * can't be read. In each, a site is a [SET_LONG_CLICK] on a view last read from a [PICTURE_VIEWS] field.
 */
internal fun BytecodePatchContext.findProfilePictureHold(): List<PictureHoldMethod>? {
    fun skip(why: String): List<PictureHoldMethod>? {
        patchLog.warning("$HOLD_PATCH: $why. The patch goes in without it.")
        return null
    }
    val hold = classDefByOrNull(PICTURE_HOLD) ?: throw PatchException("$HOLD_PATCH: the extension has no $PICTURE_HOLD")
    if (hold.methods.none { it.reference() == HOLD_HOOK && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }) {
        throw PatchException("$HOLD_PATCH: the extension has no public static $HOLD_HOOK")
    }
    val binds = classesLoadingString(LEGACY_AVATAR).flatMap { host ->
        host.methods.filter { loadsString(it, LEGACY_AVATAR) && loadsString(it, COMPOSE_AVATAR) }
    }
    val bind = binds.singleOrNull() ?: return skip("expected one method loading $LEGACY_AVATAR and $COMPOSE_AVATAR, found ${binds.size}")
    val legacy = bind.holdSites()
    if (legacy.isEmpty()) return skip("${bind.definingClass}->${bind.name} gives the picture no long press listener")
    val found = mutableListOf(bind.held(legacy))
    composeBinder(bind)?.let { found += it }
    return found
}

/** The Compose binder's sites, or null after the log says why the patch goes on with the legacy bind alone. */
private fun BytecodePatchContext.composeBinder(bind: Method): PictureHoldMethod? {
    fun skip(why: String): PictureHoldMethod? {
        patchLog.warning("$HOLD_PATCH: $why. The legacy profile header keeps the long press without it.")
        return null
    }
    val code = bind.implementation?.instructions?.toList() ?: return skip("the header bind has no code")
    // The trace's begin and the binder's own lookup come between the load and the call (29 instructions on 456).
    val calls = code.indices.filter { stringLoadedAt(code, it) == COMPOSE_AVATAR }.mapNotNull { at ->
        code.subList(at + 1, minOf(code.size, at + 1 + COMPOSE_REACH)).firstNotNullOfOrNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                instruction.opcode.isInvoke() && it.parameterTypes.firstOrNull()?.toString() == FRAGMENT && it.returnType == "V"
            }
        }
    }.distinctBy { it.toString() }
    val call = calls.singleOrNull() ?: return skip("expected one Compose avatar binder called with a Fragment, found ${calls.size}")
    val binder = classDefByOrNull(call.definingClass)?.methods?.singleOrNull {
        it.name == call.name && it.returnType == call.returnType &&
            it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
    } ?: return skip("the Compose avatar binder $call isn't in the app")
    val sites = binder.holdSites()
    if (sites.isEmpty()) return skip("the Compose avatar binder $call gives the picture no long press listener")
    return binder.held(sites)
}

/** Only called with what [findProfilePictureHold] found: each site calls [HOLD_HOOK] on its registers instead. */
internal fun BytecodePatchContext.applyProfilePictureHold(found: List<PictureHoldMethod>) {
    for (site in found) {
        val method = mutableClassDefBy(site.owner).methods.single {
            it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(Any::toString) == site.parameters
        }
        val code = method.implementation!!.instructions.toList()
        if (method.holdSites() != site.sites) {
            throw PatchException("$HOLD_PATCH: ${site.owner}->${site.name} changed after its picture listener calls were found")
        }
        for (index in site.sites) method.replaceInstruction(index, holdCall(code[index]))
    }
}

/** [HOLD_HOOK] on the registers of [call], a [SET_LONG_CLICK]: the same size, so nothing in the method moves. */
private fun holdCall(call: Instruction): String = when (call) {
    is FiveRegisterInstruction -> "invoke-static { v${call.registerC}, v${call.registerD} }, $HOLD_HOOK"
    is RegisterRangeInstruction ->
        "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }, $HOLD_HOOK"
    else -> throw PatchException("$HOLD_PATCH: a picture listener call in a form it can't replace")
}

/** Where this method gives a view last read from a [PICTURE_VIEWS] field its long press listener. */
internal fun Method.holdSites(): List<Int> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        val call = code[at]
        val reference = (call as? ReferenceInstruction)?.reference as? MethodReference
        (call.opcode == Opcode.INVOKE_VIRTUAL || call.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            reference?.toString() == SET_LONG_CLICK && pictureRead(code, at, viewRegister(call))
    }
}

private fun viewRegister(call: Instruction): Int = when (call) {
    is FiveRegisterInstruction -> call.registerC
    is RegisterRangeInstruction -> call.startRegister
    else -> -1
}

/** Whether the last write to [register] before [at] reads it from a [PICTURE_VIEWS] field. */
private fun pictureRead(code: List<Instruction>, at: Int, register: Int): Boolean {
    if (register < 0) return false
    for (back in at - 1 downTo 0) {
        val write = code[back]
        if (!write.opcode.setsRegister() || write !is OneRegisterInstruction) continue
        val wrote = write.registerA == register || (write.opcode.setsWideRegister() && write.registerA + 1 == register)
        if (!wrote) continue
        val field = (write as? ReferenceInstruction)?.reference as? FieldReference
        return write.opcode == Opcode.IGET_OBJECT && field?.type in PICTURE_VIEWS
    }
    return false
}

/** The extension's answer for the picture that turns between the photo and the avatar (#758): whether the viewer opened. */
internal const val FLIP_HOOK = "$PICTURE_HOLD->flipped(Landroid/view/View;)Z"

/** That picture, which keeps its name on every 456 build. */
internal const val COIN_FLIP_VIEW = "Lcom/instagram/avatars/coinflip/ProfileCoinFlipView;"

/** What the picture's gesture listener calls to open Instagram's card on a long press. */
internal const val OPEN_CARD = "Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;"

internal const val GESTURE_DETECTOR = "Landroid/view/GestureDetector;"
internal const val GESTURE_LISTENER = "Landroid/view/GestureDetector\$OnGestureListener;"
internal const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

/**
 * The coin-flip picture's long press: in [owner]'s onLongPress, [read] reads Instagram's card off
 * the picture's controller in [controller] into [card], and the call right after it opens the card.
 * [picture] is the controller's field holding the picture.
 */
internal class CoinFlipHold(val owner: String, val read: Int, val controller: Int, val card: Int, val picture: String)

/**
 * Finds the long press of the profile picture that turns between the photo and the account's avatar
 * (#758), or null after the patch log says why, so the other pictures keep the hook without it. That
 * picture takes its presses through a GestureDetector, not a long press listener. Its controller is
 * the one class creating a GestureDetector that has a [COIN_FLIP_VIEW] field; the listener is the
 * GestureDetector listener the controller creates, and its onLongPress reads a Function0 off the
 * controller and calls [OPEN_CARD] on it, once.
 */
internal fun BytecodePatchContext.findCoinFlipHold(): CoinFlipHold? {
    fun skip(why: String): CoinFlipHold? {
        patchLog.warning("$HOLD_PATCH: $why. The other profile pictures keep the long press without it.")
        return null
    }
    val hold = classDefByOrNull(PICTURE_HOLD) ?: throw PatchException("$HOLD_PATCH: the extension has no $PICTURE_HOLD")
    if (hold.methods.none { it.reference() == FLIP_HOOK && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }) {
        throw PatchException("$HOLD_PATCH: the extension has no public static $FLIP_HOOK")
    }
    val controllers = classesCreating(GESTURE_DETECTOR).filter { it.pictureFields().size == 1 }
    val controller = controllers.singleOrNull() ?: return skip("expected one coin-flip picture controller, found ${controllers.size}")
    val picture = controller.pictureFields().single()
    // The listener implements Instagram's own interface, which extends the platform's.
    fun listens(type: String, depth: Int = 0): Boolean =
        type == GESTURE_LISTENER || (depth < 4 && classDefByOrNull(type)?.interfaces?.any { listens(it.toString(), depth + 1) } == true)
    val listeners = controller.methods.flatMap { method ->
        method.implementation?.instructions?.filter { it.opcode == Opcode.NEW_INSTANCE }.orEmpty()
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
    }.distinct().mapNotNull { classDefByOrNull(it) }.filter { listener -> listener.interfaces.any { listens(it.toString()) } }
    val listener = listeners.singleOrNull() ?: return skip("expected one gesture listener for the coin-flip picture, found ${listeners.size}")
    val press = listener.methods.singleOrNull {
        it.name == "onLongPress" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT)
    } ?: return skip("the coin-flip picture's gesture listener ${listener.type} has no onLongPress")
    return press.coinFlipSite(controller.type, picture) ?: skip("${listener.type}->onLongPress doesn't open the card the way the hook was written for")
}

/** [COIN_FLIP_VIEW] fields of this class, as references. */
private fun ClassDef.pictureFields(): List<String> =
    fields.filter { it.type == COIN_FLIP_VIEW && !AccessFlags.STATIC.isSet(it.accessFlags) }.map { "${it.definingClass}->${it.name}:${it.type}" }

/**
 * Where this onLongPress opens the card, or null when it doesn't the one way the hook was written
 * for: one [OPEN_CARD] on a register an iget-object of a Function0 field of [controller] wrote right
 * before it, nothing branching onto that read, no move-result taking the card's answer, and nothing
 * after the call using the card's register, which the hook leaves holding its own answer.
 */
internal fun Method.coinFlipSite(controller: String, picture: String): CoinFlipHold? {
    val code = implementation?.instructions?.toList() ?: return null
    val calls = code.indices.filter { code[it].opcode == Opcode.INVOKE_INTERFACE && (code[it] as ReferenceInstruction).reference.toString() == OPEN_CARD }
    val call = calls.singleOrNull() ?: return null
    val read = call - 1
    val get = code.getOrNull(read) as? TwoRegisterInstruction ?: return null
    val field = (get as? ReferenceInstruction)?.reference as? FieldReference ?: return null
    val card = (code[call] as FiveRegisterInstruction).registerC
    if (get.opcode != Opcode.IGET_OBJECT || field.definingClass != controller || field.type != FUNCTION0 || get.registerA != card) return null
    // The hook reads the picture into the card's register, so the controller has to live elsewhere.
    if (get.registerB == card) return null
    val after = code.getOrNull(call + 1) ?: return null
    if (after.opcode == Opcode.MOVE_RESULT_OBJECT || read in jumpTargets()) return null
    if (code.drop(call + 1).any { card in it.registers() }) return null
    return CoinFlipHold(definingClass, read, get.registerB, card, picture)
}

/**
 * Only called with what [findCoinFlipHold] found: right before the card's read, the picture goes to
 * [FLIP_HOOK], and when the viewer opened the long press goes on past the card's call.
 */
internal fun BytecodePatchContext.applyCoinFlipHold(found: CoinFlipHold) {
    val method = mutableClassDefBy(found.owner).methods.single {
        it.name == "onLongPress" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT)
    }
    val site = method.coinFlipSite(found.picture.substringBefore("->"), found.picture)
    if (site == null || site.read != found.read || site.controller != found.controller || site.card != found.card) {
        throw PatchException("$HOLD_PATCH: ${found.owner}->onLongPress changed after its card call was found")
    }
    method.addInstructionsWithLabels(
        found.read,
        """
            iget-object v${found.card}, v${found.controller}, ${found.picture}
            invoke-static/range { v${found.card} .. v${found.card} }, $FLIP_HOOK
            move-result v${found.card}
            if-nez v${found.card}, :held
        """,
        ExternalLabel("held", method.getInstruction(found.read + 2)),
    )
}

/** The registers an instruction names, wide halves left out. */
private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
    is TwoRegisterInstruction -> listOf(registerA, registerB)
    is OneRegisterInstruction -> listOf(registerA)
    else -> emptyList()
}

private fun Opcode.isInvoke(): Boolean = this in setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
    Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
)

private fun Method.reference(): String = "$definingClass->$name(${parameterTypes.joinToString("") { it.toString() }})$returnType"

private fun Method.held(sites: List<Int>) =
    PictureHoldMethod(definingClass, name, parameterTypes.map(Any::toString), returnType, sites)
