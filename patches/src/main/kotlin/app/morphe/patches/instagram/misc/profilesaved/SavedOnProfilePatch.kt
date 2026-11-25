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
 * Its second switch, Fast scroll in Saved, is SavedFastScroll in the extension (#644), which also
 * carries Fast scroll on Home and Fast scroll in chats (#826).
 *
 * The bookmark and the fast scroll change no Instagram code. The extension finds the profile's tabs by
 * the ids Instagram's own code looks them up by, and the application-start hook the settings patch
 * already writes is what starts the watching.
 *
 * Two hooks do change Instagram's code, both for #762 (SavedHomeHooks). The screen Instagram's Saved
 * link opens, which the bookmark starts, makes the tabbed Saved the profile menu opens, on All, when
 * the bookmark started it. And both Saved screens ask the extension before they add the + that makes a
 * new collection, the third switch. For #810 the tabbed Saved hands the extension the list of its tabs,
 * which takes the Reels tab out of it, and the Search tab's top bar asks before it makes its Saved
 * bookmark. A build where any of them can't be read keeps Instagram's own, with a warning in the patch
 * log, and the bookmark still works. In the default selection with its switches off.
 */
@Suppress("unused")
val savedOnProfilePatch = bytecodePatch(
    name = "Saved on your profile",
    description = "Adds a bookmark to the tabs on your own profile, beside posts, reels and tagged, that opens " +
        "Saved on All, a handle to drag through Saved and your collections, and switches that hide the + " +
        "for a new collection, Saved's Reels tab and the Saved bookmark on the Search tab. The same handle " +
        "can run down Home and your chats. All start off. Turn them on in HushGram settings > Profiles, " +
        "Feed and Messages. The bookmarks need an Instagram restart.",
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("savedOnProfile")
        requireStatusMethod("savedOnAll")
        requireStatusMethod("savedNewCollection")
        requireStatusMethod("savedReelsTab")
        requireStatusMethod("exploreSaved")
        val hooked = hookSavedHome()
        if (hooked.opensOnAll) enableStatus("savedOnAll")
        if (hooked.newCollectionScreens > 0) enableStatus("savedNewCollection")
        if (hooked.reelsTab) enableStatus("savedReelsTab")
        if (hooked.exploreSaved) enableStatus("exploreSaved")
        enableStatus("savedOnProfile")
    }
}
