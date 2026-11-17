/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
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

private fun Opcode.isInvoke(): Boolean = this in setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
    Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE,
)

private fun Method.reference(): String = "$definingClass->$name(${parameterTypes.joinToString("") { it.toString() }})$returnType"

private fun Method.held(sites: List<Int>) =
    PictureHoldMethod(definingClass, name, parameterTypes.map(Any::toString), returnType, sites)
