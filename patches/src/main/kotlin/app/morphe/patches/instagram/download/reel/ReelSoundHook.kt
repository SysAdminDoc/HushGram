/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.soundBridges
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val SOUND_PATCH = "Download any reel"

internal const val SOUND_TYPE = "$EXTENSION_PACKAGE/download/ReelSoundButton;"
internal const val SOUND_ON = "$SOUND_TYPE->on()Z"
internal const val SOUND_LITHO = "$SOUND_TYPE->litho(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val SOUND_LITHO_ABOVE_MORE =
    "$SOUND_TYPE->lithoAboveMore(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"

/** The extension's list of hidden sounds, which filters each Reels tab page. */
internal const val HIDDEN_SOUNDS = "$EXTENSION_PACKAGE/reels/HiddenSounds;"
internal const val SOUND_PAGE_FILTER = "$HIDDEN_SOUNDS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

private const val SOUND_OBJECT = "Ljava/lang/Object;"
private const val SOUND_STRING = "Ljava/lang/String;"
private const val SOUND_LIST = "Ljava/util/List;"

/** How many instructions one icon's block is: the Download, Not interested and Block ones alike. */
internal const val ICON_BLOCK = 14

/**
 * Adds the Hide this sound icon beside a reel and the Reels tab filter it feeds (#191), in front of
 * the column's other icons. A build where the sound, the page or the column can't be told gets a
 * warning and keeps everything else of the patch.
 */
internal fun BytecodePatchContext.addSoundButtonWhereItFits(site: ReelButtonSite) {
    try {
        val writeSound = soundBridges(SOUND_PATCH)
        val page = findReelsPage(SOUND_PATCH)
        val starts = findSoundStarts(site)
        addSoundButton(site, starts, page, writeSound)
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $SOUND_PATCH goes in without Hide reels by sound.")
    }
}

private fun refuseSound(detail: String): Nothing = throw PatchException("$SOUND_PATCH, Hide reels by sound: $detail")

/**
 * Where the sound icon's block goes at each of [site]'s places: in front of the first icon block
 * there. Each place starts with the Download icon's block, which may have the Not interested and
 * Block ones in front of it, so the walk goes back from Download's a block at a time while the one
 * before is one of those. Also checks the extension holds what the filter needs. Fails before
 * anything changes.
 */
internal fun BytecodePatchContext.findSoundStarts(site: ReelButtonSite): List<Pair<ReelSaveSite, Int>> {
    val extension = classDefByOrNull(HIDDEN_SOUNDS) ?: refuseSound("the extension has no $HIDDEN_SOUNDS")
    if (extension.methods.none { it.isStaticStub("page", listOf(SOUND_LIST, SOUND_STRING), SOUND_LIST) }) {
        refuseSound("the extension has no static page(List, String)List")
    }
    if (extension.methods.none { it.isStaticStub("media", listOf(SOUND_OBJECT), SOUND_OBJECT) }) {
        refuseSound("the extension has no static media(Object)Object to fill")
    }
    if (classDefByOrNull(SOUND_TYPE) == null) refuseSound("$SOUND_TYPE is missing")

    // The mutable class, since the Download, Not interested and Block blocks are already in it.
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.parameterNames() == site.render.parameterTypes.map(CharSequence::toString) &&
            it.returnType == site.render.returnType
    }
    val code = render.implementation?.instructions?.toList() ?: refuseSound("the column's render has no body")
    fun names(at: Int, vararg references: String) = (code.getOrNull(at) as? ReferenceInstruction)?.reference?.toString() in references
    val downloads = code.indices.filter { names(it, REEL_BUTTON_ON) }
    if (downloads.size != site.sites.size) {
        refuseSound("expected ${site.sites.size} Download icon blocks in the column's render, found ${downloads.size}")
    }
    return site.sites.sortedBy { it.at }.zip(downloads).map { (place, download) ->
        var start = download
        while (names(start - ICON_BLOCK, NOT_INTERESTED_ON, BLOCK_ON)) start -= ICON_BLOCK
        place to start
    }
}

/**
 * Fills the filter's item stub, writes the sound bridges and hands each Reels page to
 * [SOUND_PAGE_FILTER], then adds the sound icon at each of [starts] ([addSoundIcons]), so an icon
 * never goes in with nothing behind it.
 */
internal fun BytecodePatchContext.addSoundButton(
    site: ReelButtonSite,
    starts: List<Pair<ReelSaveSite, Int>>,
    page: ReelsPageSites,
    writeSound: () -> Unit,
) {
    fillReelsItemMedia(HIDDEN_SOUNDS, page)
    writeSound()
    filterReelsPage(page, SOUND_PAGE_FILTER)
    addSoundIcons(site, starts)
}

/**
 * Right in front of the first icon block at each place, asks the extension whether the sound icon is
 * on, and when it is adds the icon it answers to the column, as the other blocks do. Those are left
 * as they were, so they still run after it and skip on their own.
 */
internal fun BytecodePatchContext.addSoundIcons(site: ReelButtonSite, starts: List<Pair<ReelSaveSite, Int>>) {
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.parameterNames() == site.render.parameterTypes.map(CharSequence::toString) &&
            it.returnType == site.render.returnType
    }
    starts.sortedByDescending { it.second }.forEach { (place, start) ->
        val litho = if (place.aboveMore) SOUND_LITHO_ABOVE_MORE else SOUND_LITHO
        render.addInstructionsWithLabels(
            start,
            columnIconBlock(site, place, SOUND_ON, litho),
            ExternalLabel("skip", render.getInstruction(start)),
        )
    }
}
