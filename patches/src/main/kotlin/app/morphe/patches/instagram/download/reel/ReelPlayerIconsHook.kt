/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.reels.cleanup.CLEAR_MODE_BUTTON
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

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

internal const val MUTE_ICON_TYPE = "$EXTENSION_PACKAGE/download/ReelMuteButton;"
internal const val MUTE_ICON_ON = "$MUTE_ICON_TYPE->on()Z"
internal const val MUTE_ICON_LITHO = "$MUTE_ICON_TYPE->litho(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val MUTE_ICON_LITHO_ABOVE_MORE =
    "$MUTE_ICON_TYPE->lithoAboveMore(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"

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
 * Adds the speaker icon beside a reel (#608), in front of the Full screen icon. A tap turns
 * the reel's sound off or on through Control taps and volume on Reels, so the reel keeps playing. A
 * build where the column can't be told gets a warning and keeps everything else of the patch.
 */
internal fun BytecodePatchContext.addMuteButtonWhereItFits(site: ReelButtonSite) {
    try {
        addPlayerIcons(site, findPlayerIconStarts(site, MUTE_ICON_TYPE, "mute"), MUTE_ICON_ON, MUTE_ICON_LITHO, MUTE_ICON_LITHO_ABOVE_MORE)
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $PLAYER_ICONS_PATCH goes in without the mute icon.")
    }
}

internal const val MUTE_CLEAR_MODE = "$MUTE_ICON_TYPE->clearMode(Ljava/lang/Object;)V"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val LIST = "Ljava/util/List;"

/**
 * Puts the speaker icon next to clear mode's exit button (#608), for Mute button in clear mode. The
 * exit button's render adds the button to a list it hands to the row it answers, so right after that
 * add the extension may put the speaker in the list too. A build whose render differs gets a warning
 * and keeps everything else of the patch.
 */
internal fun BytecodePatchContext.addMuteButtonToClearMode() {
    try {
        val (render, at, list) = findClearModeRowAdd()
        render.addInstructions(at, "invoke-static/range { v$list .. v$list }, $MUTE_CLEAR_MODE")
    } catch (unknown: PatchException) {
        patchLog.warning("${unknown.message}. $PLAYER_ICONS_PATCH goes in without the mute icon in clear mode.")
    }
}

/**
 * Where [addMuteButtonToClearMode] goes: in the one method marked [CLEAR_MODE_BUTTON] (the mutable
 * one, since Clear mode's patch may have hooked it already), right after the last add to the one list
 * it builds, when the next thing it does is build the row whose constructor takes a list. Answers the
 * method, that index and the list's register. Fails before anything changes.
 */
internal fun BytecodePatchContext.findClearModeRowAdd(): Triple<MutableMethod, Int, Int> {
    fun refuse(detail: String): Nothing = throw PatchException("$PLAYER_ICONS_PATCH, mute icon in clear mode: $detail")
    if (classDefByOrNull(MUTE_ICON_TYPE) == null) refuse("$MUTE_ICON_TYPE is missing")
    val marked = typesMarked(CLEAR_MODE_BUTTON).flatMap { type ->
        classDefByOrNull(type)?.methods?.filter { CLEAR_MODE_BUTTON in it.markers() } ?: emptyList()
    }
    val stock = marked.singleOrNull() ?: refuse("expected one method marked $CLEAR_MODE_BUTTON, found ${marked.size}")
    val render = mutableClassDefBy(stock.definingClass).methods.single {
        it.name == stock.name && it.parameterNames() == stock.parameterNames() && it.returnType == stock.returnType
    }
    val code = render.implementation?.instructions?.toList() ?: refuse("${render.name} has no body")
    fun type(at: Int) = ((code.getOrNull(at) as? ReferenceInstruction)?.reference as? TypeReference)?.type
    fun called(at: Int) = (code.getOrNull(at) as? ReferenceInstruction)?.reference as? MethodReference

    val lists = code.indices.filter { code[it].opcode == Opcode.NEW_INSTANCE && type(it) == ARRAY_LIST }
    val list = (code[lists.singleOrNull() ?: refuse("builds ${lists.size} lists, expected one")] as OneRegisterInstruction).registerA
    val add = code.indices.lastOrNull { at ->
        val call = called(at)
        val invoke = code[at] as? FiveRegisterInstruction
        (code[at].opcode == Opcode.INVOKE_VIRTUAL || code[at].opcode == Opcode.INVOKE_INTERFACE) && invoke != null &&
            invoke.registerCount == 2 && invoke.registerC == list && call != null && call.name == "add" &&
            call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
    } ?: refuse("adds nothing to the list it builds")
    val after = if (code.getOrNull(add + 1)?.opcode == Opcode.MOVE_RESULT) add + 2 else add + 1
    if (after == add + 2 && (code[add + 1] as OneRegisterInstruction).registerA == list) refuse("writes the add's answer over its list")
    val row = type(after)?.takeIf { code[after].opcode == Opcode.NEW_INSTANCE }
        ?: refuse("builds no row right after the last add to its list")
    val built = (after + 1 until code.size).any { at ->
        val call = called(at)
        (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) && call != null &&
            call.definingClass == row && call.name == "<init>" && LIST in call.parameterTypes.map(CharSequence::toString)
    }
    if (!built) refuse("the row $row it builds takes no list")
    if (after in render.jumpTargets()) refuse("jumps to the row it builds, so the hook would be skipped")
    return Triple(render, after, list)
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
        // Every block skips onto the next one's first instruction, so an icon put in front of anything
        // but the first block would be jumped over whenever the block before it is off (#608).
        while (names(start - ICON_BLOCK, NOT_INTERESTED_ON, BLOCK_ON, SOUND_ON, TEXT_ON, SPEED_ICON_ON, FULL_SCREEN_ICON_ON, MUTE_ICON_ON)) {
            start -= ICON_BLOCK
        }
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
