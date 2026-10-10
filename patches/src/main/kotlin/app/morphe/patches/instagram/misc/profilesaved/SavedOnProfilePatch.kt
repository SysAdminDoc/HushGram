/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.profilesaved

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

/**
 * Adds a bookmark to the tabs on your own profile that opens Saved. See ProfileSaved in the extension.
 *
 * It changes no Instagram code. The extension finds the profile's tabs by the ids Instagram's own code
 * looks them up by, and the application-start hook the settings patch already writes is what starts the
 * watching; this patch only switches the status method on, so the settings screen offers the switch and
 * the extension acts. In the default selection with its switch off.
 */
@Suppress("unused")
val savedOnProfilePatch = bytecodePatch(
    name = "Saved on your profile",
    description = "Adds a bookmark to the tabs on your own profile, beside posts, reels and tagged, that opens " +
        "Saved. Starts off. Turn it on in HushGram settings > Profiles, then restart Instagram.",
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("savedOnProfile")
        enableStatus("savedOnProfile")
    }
}
