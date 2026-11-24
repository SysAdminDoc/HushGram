/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

/** Asked first by both of Instagram's reels picture in picture checks: yes answers yes (#572). */
internal const val REELS_PIP = "$EXTENSION_PACKAGE/media/ReelsPip;->on()Z"

/**
 * The server flag Instagram's check for picture in picture on reels reads (076m.A0A on 456's
 * 386214574; 455 also logged ClipsViewerExperimentUtil_isPictureInPictureEnabledPersistentVersion
 * there, which 456 dropped). The reels viewer sets its picture in picture up only when this or the
 * session check says yes, and both the menu row and the window's automatic start ask it through
 * ClipsViewerPipUtil.
 */
// 457 moved it from 456's 0x810a4200003abb.
internal const val PIP_ALLOWED_FLAG = 0x810a3700003a7aL

/**
 * The server flag the check of Instagram's own switch for it reads (0ATr.A08 on 456's 386214574;
 * 455 also logged ClipsViewerPipUtil_isClipsPipEnabled there). The switch is a preference that
 * starts off and that only a menu row behind this flag turns on. A reel's picture in picture window
 * opens on its own as you leave only when this check says yes.
 */
// 457 moved it from 456's 0x810a4200063ac1.
internal const val PIP_SWITCH_FLAG = 0x810a3700063a80L

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** The two checks, each a static (UserSession, boolean) method answering a boolean. */
internal class ReelsPipChecks(val allowed: Method, val switchedOn: Method)

/** Finds both checks before anything changes, each the one method of its shape reading its flag. */
internal fun BytecodePatchContext.findReelsPipChecks(patch: String): ReelsPipChecks =
    ReelsPipChecks(pipCheck(patch, PIP_ALLOWED_FLAG), pipCheck(patch, PIP_SWITCH_FLAG))

private fun BytecodePatchContext.pipCheck(patch: String, flag: Long): Method {
    val checks = classesLoading(flag).flatMap { it.methods }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
            method.parameterTypes.map(Any::toString) == listOf(USER_SESSION, "Z") && method.readsFlag(flag)
    }
    return checks.singleOrNull()
        ?: throw PatchException("$patch: expected one (UserSession, boolean) check reading ${flag.toString(16)}, found ${checks.size}")
}

private fun Method.readsFlag(flag: Long): Boolean =
    implementation?.instructions?.any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == flag
    } == true

/**
 * Makes both checks answer yes from their first instruction while the switch is on. Off and Pause
 * leave them to Instagram. Nothing holds a local before a method's first instruction, so v0 is free.
 * The window still needs Instagram's own device check to pass (Android 15 or newer, with picture in
 * picture allowed for Instagram) and a reel it can show in one.
 */
internal fun BytecodePatchContext.allowReelsPip(patch: String, checks: ReelsPipChecks) {
    for (check in listOf(checks.allowed, checks.switchedOn)) {
        val method = mutableClassDefBy(check.definingClass).methods.single {
            it.name == check.name && it.returnType == check.returnType &&
                it.parameterTypes.map(Any::toString) == check.parameterTypes.map(Any::toString)
        }
        method.requireLocals(patch, 1)
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $REELS_PIP
                move-result v0
                if-eqz v0, :stock
                const/4 v0, 0x1
                return v0
            """,
            ExternalLabel("stock", method.getInstruction(0)),
        )
    }
}
