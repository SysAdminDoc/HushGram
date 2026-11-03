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

private const val PLAYER_ICONS_PATCH = "Download any reel"

internal const val SPEED_ICON_TYPE = "$EXTENSION_PACKAGE/download/ReelSpeedButton;"
internal const val SPEED_ICON_ON = "$SPEED_ICON_TYPE->on()Z"
internal const val SPEED_ICON_LITHO = "$SPEED_ICON_TYPE->litho(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val SPEED_ICON_LITHO_ABOVE_MORE =
    "$SPEED_ICON_TYPE->lithoAboveMore(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"

internal const val FULL_SCREEN_ICON_TYPE = "$EXTENSION_PACKAGE/download/ReelFullScreenButton;"
internal const val FULL_SCREEN_ICON_ON = "$FULL_SCREEN_ICON_TYPE->on()Z"
internal const val FULL_SCREEN_ICON_LITHO =
    "$FULL_SCREEN_ICON_TYPE->litho(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val FULL_SCREEN_ICON_LITHO_ABOVE_MORE =
    "$FULL_SCREEN_ICON_TYPE->lithoAboveMore(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"

/**
 * Adds the speed icon beside a reel (#480), in front of the column's other icons. A tap offers the
 * speeds from 0.5x to 3x. A build where the column can't be told gets a warning and keeps everything
 * else of the patch.
 */
internal fun BytecodePatchContext.addSpeedButtonWhereItFits(site: ReelButtonSite) {
    try {
        addPlayerIcons(site, findPlayerIconStarts(site, SPEED_ICON_TYPE, "speed"), SPEED_ICON_ON, SPEED_ICON_LITHO, SPEED_ICON_LITHO_ABOVE_MORE)
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $PLAYER_ICONS_PATCH goes in without the speed icon.")
    }
}

/**
 * Adds the Full screen icon beside a reel (#480), in front of the speed icon. A tap puts the reel in
 * Instagram's clear mode, as Full screen in its menu does. A build where the column can't be told
 * gets a warning and keeps everything else of the patch.
 */
internal fun BytecodePatchContext.addFullScreenButtonWhereItFits(site: ReelButtonSite) {
    try {
        addPlayerIcons(
            site, findPlayerIconStarts(site, FULL_SCREEN_ICON_TYPE, "Full screen"),
            FULL_SCREEN_ICON_ON, FULL_SCREEN_ICON_LITHO, FULL_SCREEN_ICON_LITHO_ABOVE_MORE,
        )
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $PLAYER_ICONS_PATCH goes in without the Full screen icon.")
    }
}

/**
 * Where an icon of [type]'s block goes at each of [site]'s places: in front of the first icon block
 * there. Each place starts with the Download icon's block, which may have any of the other icons'
 * blocks in front of it, so the walk goes back from Download's a block at a time while the one before
 * is one of those. Fails before anything changes.
 */
internal fun BytecodePatchContext.findPlayerIconStarts(site: ReelButtonSite, type: String, icon: String): List<Pair<ReelSaveSite, Int>> {
    fun refuse(detail: String): Nothing = throw PatchException("$PLAYER_ICONS_PATCH, $icon icon: $detail")
    if (classDefByOrNull(type) == null) refuse("$type is missing")
    // The mutable class, since the other icons' blocks are already in it.
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.parameterNames() == site.render.parameterTypes.map(CharSequence::toString) &&
            it.returnType == site.render.returnType
    }
    val code = render.implementation?.instructions?.toList() ?: refuse("the column's render has no body")
    fun names(at: Int, vararg references: String) = (code.getOrNull(at) as? ReferenceInstruction)?.reference?.toString() in references
    val downloads = code.indices.filter { names(it, REEL_BUTTON_ON) }
    if (downloads.size != site.sites.size) {
        refuse("expected ${site.sites.size} Download icon blocks in the column's render, found ${downloads.size}")
    }
    return site.sites.sortedBy { it.at }.zip(downloads).map { (place, download) ->
        var start = download
        while (names(start - ICON_BLOCK, NOT_INTERESTED_ON, BLOCK_ON, SOUND_ON, TEXT_ON, SPEED_ICON_ON)) start -= ICON_BLOCK
        place to start
    }
}

/**
 * Right in front of the first icon block at each place, asks [on] whether the icon is on, and when it
 * is adds the icon [litho] (or [lithoAboveMore] where More comes next) answers to the column, as the
 * other blocks do. Those are left as they were, so they still run after it and skip on their own.
 */
internal fun BytecodePatchContext.addPlayerIcons(
    site: ReelButtonSite,
    starts: List<Pair<ReelSaveSite, Int>>,
    on: String,
    litho: String,
    lithoAboveMore: String,
) {
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.parameterNames() == site.render.parameterTypes.map(CharSequence::toString) &&
            it.returnType == site.render.returnType
    }
    starts.sortedByDescending { it.second }.forEach { (place, start) ->
        render.addInstructionsWithLabels(
            start,
            columnIconBlock(site, place, on, if (place.aboveMore) lithoAboveMore else litho),
            ExternalLabel("skip", render.getInstruction(start)),
        )
    }
}
