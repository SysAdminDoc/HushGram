/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.consent

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Skip the ad data reminder"
internal const val AD_DATA_REMINDER = "$EXTENSION_PACKAGE/misc/AdDataReminder;"
internal const val SKIP_AD_DATA_REMINDER = "$AD_DATA_REMINDER->skip(Ljava/lang/String;)Z"

@Suppress("unused")
val skipAdDataReminderPatch = bytecodePatch(
    name = "Skip the ad data reminder",
    description = "Stops Instagram's reminder to review whether it can process your data for ads from opening by itself. " +
        "Your ad choice doesn't change and nothing is answered for you. Starts off. Turn it on in HushGram settings > Ads and privacy.",
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("adDataReminder")
        skipAdDataReminder(SKIP_AD_DATA_REMINDER)?.let { throw PatchException("$PATCH: $it") }
        enableStatus("adDataReminder")
    }
}
