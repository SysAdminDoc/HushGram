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
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val TEXT_PATCH = "Download any reel"

internal const val TEXT_TYPE = "$EXTENSION_PACKAGE/download/ReelTextButton;"
internal const val TEXT_ON = "$TEXT_TYPE->on()Z"
internal const val TEXT_LITHO = "$TEXT_TYPE->litho(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val TEXT_LITHO_ABOVE_MORE =
    "$TEXT_TYPE->lithoAboveMore(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"

/**
 * Adds the Copy text icon beside a reel (#303), in front of the column's other icons. A tap sends
 * what the reel shows to Google Lens. A build where the column can't be told gets a warning and
 * keeps everything else of the patch.
 */
internal fun BytecodePatchContext.addTextButtonWhereItFits(site: ReelButtonSite) {
    try {
        addTextIcons(site, findTextStarts(site))
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $TEXT_PATCH goes in without the Copy text icon.")
    }
}

private fun refuseText(detail: String): Nothing = throw PatchException("$TEXT_PATCH, Copy text icon: $detail")

/**
 * Where the text icon's block goes at each of [site]'s places: in front of the first icon block
 * there. Each place starts with the Download icon's block, which may have the Not interested, Block
 * and sound ones in front of it, so the walk goes back from Download's a block at a time while the
 * one before is one of those. Fails before anything changes.
 */
internal fun BytecodePatchContext.findTextStarts(site: ReelButtonSite): List<Pair<ReelSaveSite, Int>> {
    if (classDefByOrNull(TEXT_TYPE) == null) refuseText("$TEXT_TYPE is missing")
    // The mutable class, since the other icons' blocks are already in it.
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.parameterNames() == site.render.parameterTypes.map(CharSequence::toString) &&
            it.returnType == site.render.returnType
    }
    val code = render.implementation?.instructions?.toList() ?: refuseText("the column's render has no body")
    fun names(at: Int, vararg references: String) = (code.getOrNull(at) as? ReferenceInstruction)?.reference?.toString() in references
    val downloads = code.indices.filter { names(it, REEL_BUTTON_ON) }
    if (downloads.size != site.sites.size) {
        refuseText("expected ${site.sites.size} Download icon blocks in the column's render, found ${downloads.size}")
    }
    return site.sites.sortedBy { it.at }.zip(downloads).map { (place, download) ->
        var start = download
        while (names(start - ICON_BLOCK, NOT_INTERESTED_ON, BLOCK_ON, SOUND_ON)) start -= ICON_BLOCK
        place to start
    }
}

/**
 * Right in front of the first icon block at each place, asks the extension whether the text icon is
 * on, and when it is adds the icon it answers to the column, as the other blocks do. Those are left
 * as they were, so they still run after it and skip on their own.
 */
internal fun BytecodePatchContext.addTextIcons(site: ReelButtonSite, starts: List<Pair<ReelSaveSite, Int>>) {
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.parameterNames() == site.render.parameterTypes.map(CharSequence::toString) &&
            it.returnType == site.render.returnType
    }
    starts.sortedByDescending { it.second }.forEach { (place, start) ->
        val litho = if (place.aboveMore) TEXT_LITHO_ABOVE_MORE else TEXT_LITHO
        render.addInstructionsWithLabels(
            start,
            columnIconBlock(site, place, TEXT_ON, litho),
            ExternalLabel("skip", render.getInstruction(start)),
        )
    }
}
