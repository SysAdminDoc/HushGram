/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.quality

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.feed.photos.fullResolutionPhotosPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

internal const val DATA_SAVER = "$EXTENSION_PACKAGE/media/DataSaver;"

/**
 * Data saver has no hooks of its own. Full resolution photos' hook at the start of the size picker
 * and Default playback quality's hook in the video track choice both ask the extension's
 * DataSaver first, so this patch brings those two along and tells the extension it's in. Each of
 * them fails the build on its own when its anchor is gone, so nothing here can be half done.
 */
@Suppress("unused")
val dataSaverPatch = bytecodePatch(
    name = "Data saver",
    description = "Saves mobile data by loading smaller photos and starting videos at the lowest quality. It uses " +
        "Full resolution photos and Default playback quality, so it turns both on. It starts with mobile data " +
        "only. Starts off. Turn it on in HushGram settings > Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch, fullResolutionPhotosPatch, defaultPlaybackQualityPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("dataSaver")
        enableStatus("dataSaver")
    }
}
