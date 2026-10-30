/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.search

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.direct.notes.askForSections
import app.morphe.patches.instagram.direct.notes.findNotesSections
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Hide the search bar in Messages"
internal const val INBOX_SEARCH_BAR = "$EXTENSION_PACKAGE/direct/InboxSearchBar;"
internal const val KEEP_SEARCH_SECTIONS = "$INBOX_SEARCH_BAR->sections([Ljava/lang/Object;)[Ljava/lang/Object;"

/**
 * Leaves the search bar, the Meta AI one included, out of your messages. Included in the default
 * selection with its switch initially off, so leaving it out remains the user's pick.
 *
 * It asks at the same place "Hide the notes row" does, where your messages name the sections they
 * may show, and the extension drops the search bar's section by name. Both patches can go in
 * together: whichever goes in second finds the construction again past the first one's question
 * and asks right before it, so both ask and every jump still lands on the first.
 */
@Suppress("unused")
val hideInboxSearchPatch = bytecodePatch(
    name = "Hide the search bar in Messages",
    description = "Takes the search bar and its Filter button off the top of your messages, the Meta AI bar " +
        "too. Your chats and notes stay. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("inboxSearch")
        hideInboxSearch()
        enableStatus("inboxSearch")
    }
}

/** Finds where your messages name their sections, refusing under this patch's name, and hands them to [KEEP_SEARCH_SECTIONS]. */
internal fun BytecodePatchContext.hideInboxSearch() = askForSections(findNotesSections(PATCH), KEEP_SEARCH_SECTIONS)
