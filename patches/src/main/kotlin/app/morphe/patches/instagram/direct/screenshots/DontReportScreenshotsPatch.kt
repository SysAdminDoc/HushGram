/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.screenshots

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.direct.ghost.ghostModeEntryPatch
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

internal const val SCREENSHOT_REPORTS_PATCH = "Don't report screenshots"
internal const val SCREENSHOT_REPORTS = "$EXTENSION_PACKAGE/direct/ScreenshotReports;"
internal const val HOLD_SCREENSHOT = "$SCREENSHOT_REPORTS->hold()Z"

/** The log tag and line of the detector that watches the phone's media, written as a screen starts listening. */
internal const val CONTENT_DETECTOR = "ScreenshotDetectorByContent"
internal const val DETECTOR_SESSION = "Started new screenshot session"

/** What the detector that scans the screenshot folders logs as it hands a screenshot to each listener. */
internal const val FOLDER_REPORT = "Reporting screenshot: %s -> %s"

/** (time, path, list): how the media detector takes a screenshot it found. */
internal val FOUND_PARAMETERS = listOf("J", "Ljava/lang/String;", "Ljava/util/List;")

/** The media detector's session starter, the one method writing both of its log lines. */
internal object ContentDetectorFingerprint : Fingerprint(
    strings = listOf(CONTENT_DETECTOR, DETECTOR_SESSION),
)

/** The folder detector's static (detector, path, list) report, the one method logging [FOLDER_REPORT]. */
internal object FolderReportFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(FOLDER_REPORT),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(CharSequence::toString).drop(1) == listOf("Ljava/lang/String;", "Ljava/util/List;")
    },
)

@Suppress("unused")
val dontReportScreenshotsPatch = bytecodePatch(
    name = "Don't report screenshots",
    description = "Stops Instagram from telling people when you take a screenshot of their disappearing photo or " +
        "video. Ghost mode, at the top of Ads and privacy, turns it on with the others. Starts off. Turn it on in " +
        "HushGram settings > Messages.",
    default = true,
) {
    category("Ghost mode")
    dependsOn(settingsPatch, instagramExtensionPatch, ghostModeEntryPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("screenshotReports")
        findScreenshotReports().forEach(::holdScreenshotReport)
        enableStatus("screenshotReports")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$SCREENSHOT_REPORTS_PATCH: $why")

/**
 * Where each of Instagram's two screenshot detectors hands a new screenshot on: the media
 * detector's one instance (time, path, list) method, and the folder detector's report. Every screen
 * listening for screenshots hears of one through these. Proved before anything changes: nothing
 * jumps back to either's first instruction, where the hook goes, and each has a local for the answer.
 */
internal fun BytecodePatchContext.findScreenshotReports(): List<MutableMethod> {
    val session = uniqueMethod(SCREENSHOT_REPORTS_PATCH, "media screenshot detector", ContentDetectorFingerprint)
    val found = mutableClassDefBy(session.definingClass).methods.filter {
        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == FOUND_PARAMETERS &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    }
    val content = found.singleOrNull() ?: refuse("expected one screenshot hand-off in the media detector, found ${found.size}")
    val folder = uniqueMethod(SCREENSHOT_REPORTS_PATCH, "folder screenshot report", FolderReportFingerprint)
    for ((what, method) in listOf("media detector's hand-off" to content, "folder screenshot report" to folder)) {
        if (0 in method.jumpTargets()) refuse("something jumps back to the $what's first instruction")
        method.requireLocals(SCREENSHOT_REPORTS_PATCH, 1)
    }
    return listOf(content, folder)
}

/** The method returns before it hands the screenshot to anyone while the switch is on. */
internal fun holdScreenshotReport(report: MutableMethod) {
    report.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_SCREENSHOT
            move-result v0
            if-eqz v0, :report
            return-void
        """,
        ExternalLabel("report", report.getInstruction(0)),
    )
}
