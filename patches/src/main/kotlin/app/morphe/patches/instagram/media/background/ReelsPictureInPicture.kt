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
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/** Asked first by both of Instagram's reels picture in picture checks: yes answers yes (#572). */
internal const val REELS_PIP = "$EXTENSION_PACKAGE/media/ReelsPip;->on()Z"

/**
 * Logged by Instagram's server check for picture in picture on reels (04EI.A0B on 454). The reels
 * viewer sets its picture in picture up only when this or the session check says yes, and both the
 * menu row and the window's automatic start ask it through ClipsViewerPipUtil (084m).
 */
internal const val PIP_ALLOWED = "android_purge_26_q2_ClipsViewerExperimentUtil_isPictureInPictureEnabledPersistentVersion"

/**
 * Logged by the check of Instagram's own switch for it (084m.A09 on 454), a preference that starts
 * off and that only a menu row behind another server flag turns on. A reel's picture in picture
 * window opens on its own as you leave only when this says yes.
 */
internal const val PIP_SWITCHED_ON = "android_purge_26_q2_ClipsViewerPipUtil_isClipsPipEnabled"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** The two checks, each a static (UserSession, boolean) method answering a boolean. */
internal class ReelsPipChecks(val allowed: Method, val switchedOn: Method)

/** Finds both checks before anything changes, each the one method of its shape loading its string. */
internal fun BytecodePatchContext.findReelsPipChecks(patch: String): ReelsPipChecks =
    ReelsPipChecks(pipCheck(patch, PIP_ALLOWED), pipCheck(patch, PIP_SWITCHED_ON))

private fun BytecodePatchContext.pipCheck(patch: String, marker: String): Method {
    val checks = classesLoadingString(marker).flatMap { it.methods }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == "Z" &&
            method.parameterTypes.map(Any::toString) == listOf(USER_SESSION, "Z") && loadsString(method, marker)
    }
    return checks.singleOrNull()
        ?: throw PatchException("$patch: expected one (UserSession, boolean) check loading \"$marker\", found ${checks.size}")
}

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
