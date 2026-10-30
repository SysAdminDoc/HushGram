/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.captionwords

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.captionBridges
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Hide reels by caption words"
internal const val CAPTION_WORD_REELS = "$EXTENSION_PACKAGE/reels/CaptionWordReels;"
internal const val CAPTION_PAGE_FILTER = "$CAPTION_WORD_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"

/**
 * Leaves reels whose caption holds a word or phrase the user typed out of the Reels tab. Reels the
 * user went to see stay. In the default selection with its switch off and its list empty, so
 * nothing changes until the user picks words. Asked for in #192.
 */
@Suppress("unused")
val hideCaptionWordReelsPatch = bytecodePatch(
    name = "Hide reels by caption words",
    description = "Leaves out reels whose caption holds a word or phrase from your list, and Home's posts too " +
        "with Also on Home. Starts off. " +
        "Turn it on and type the words in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("captionWordReels")
        val page = findReelsPage(PATCH)
        val caption = findCaptionWordReels()
        hideCaptionWordReels(page, caption)
        enableStatus("captionWordReels")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Checks the extension has the page filter and the item stub, then finds how a post's caption is
 * read: the caption bridges Details' Copy caption uses, Media's caption and that comment's text.
 * Answers the writer for those bridges. Fails before anything changes when any of it can't be told,
 * since without the caption there's nothing to match.
 */
internal fun BytecodePatchContext.findCaptionWordReels(): () -> Unit {
    val extension = classDefByOrNull(CAPTION_WORD_REELS) ?: refuse("the extension has no $CAPTION_WORD_REELS")
    if (extension.methods.none { it.isStaticStub("page", listOf(LIST, STRING), LIST) }) {
        refuse("the extension has no static page(List, String)List")
    }
    if (extension.methods.none { it.isStaticStub("media", listOf(OBJECT), OBJECT) }) {
        refuse("the extension has no static media(Object)Object to fill")
    }
    return captionBridges(PATCH, "$PATCH can't read a reel's caption") ?: refuse("can't read a post's caption in this Instagram build")
}

/**
 * Fills the extension's item stub, writes the caption bridges (left as they are when Download any
 * video wrote them first), then hands each Reels page to [CAPTION_PAGE_FILTER] where the page and
 * its fallback meet.
 */
internal fun BytecodePatchContext.hideCaptionWordReels(page: ReelsPageSites, writeCaption: () -> Unit) {
    fillReelsItemMedia(CAPTION_WORD_REELS, page)
    writeCaption()
    filterReelsPage(page, CAPTION_PAGE_FILTER)
}
