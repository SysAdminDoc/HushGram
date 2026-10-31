/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.setupscreens

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Skip the contacts and location popups"
internal const val SETUP_POPUPS = "$EXTENSION_PACKAGE/misc/SetupPopups;"

/** Asked with a Bloks screen's app id before Instagram opens it; yes keeps the screen closed. */
internal const val SKIP_SETUP = "$SETUP_POPUPS->skip(Ljava/lang/String;)Z"

/**
 * Keeps the "Set up on new device" screens that ask to sync contacts and turn on location services
 * from opening, through [skipSetupScreens]. Instagram's server sends them as Bloks screens on a cold
 * start until the phone shares contacts and location, so skipping them once doesn't stop the next
 * start from asking. Only those two app ids are skipped, while the switch is on. In the default
 * selection with its switch off (#295, #329, #421).
 */
@Suppress("unused")
val skipSetupPopupsPatch = bytecodePatch(
    name = "Skip the contacts and location popups",
    description = "Stops the screens asking you to sync your contacts and turn on location services from " +
        "opening each time Instagram starts. Nothing else is skipped. Starts off. Turn it on in HushGram " +
        "settings > Ads and privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("setupPopups")
        skipSetupScreens(SKIP_SETUP)?.let { throw PatchException("$PATCH: $it") }
        enableStatus("setupPopups")
    }
}
