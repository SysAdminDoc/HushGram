/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.tray

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Split the notes row"
internal const val SPLIT_NOTES_ROW_STATUS = "splitNotesRow"
internal const val SPLIT_NOTES_ROW = "$EXTENSION_PACKAGE/direct/SplitNotesRow;->split(I)Z"

/**
 * The server test behind the split top of your messages on 454: Instants, Notes and Stories as
 * three stacks instead of one row of notes. It's read four times, each as a yes or no: when your
 * messages open (a fragment's onCreate, which picks the split row's maker over the old row's),
 * in the notes row's constructor, and in two places that get the row's contents ready.
 */
internal const val SPLIT_NOTES_ROW_FLAG = 0x8115f000007436L

/** The read that picks the split row when your messages open: an onCreate taking a Bundle. */
private val INBOX_OPEN = listOf("Landroid/os/Bundle;")

/**
 * Split the notes row (#558). Instagram already draws the split row for the accounts in its test,
 * so the patch only answers the test's reads through [SPLIT_NOTES_ROW]: yes while the switch is on,
 * Instagram's own answer otherwise.
 */
@Suppress("unused")
val splitNotesRowPatch = bytecodePatch(
    name = "Split the notes row",
    description = "Splits the row of notes at the top of your messages into three stacks, Instants, Notes and " +
        "Stories, the layout Instagram is trying out. It takes a restart. Starts off. Turn it on in HushGram " +
        "settings > Messages.",
    default = true,
) {
    category("Messages")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(SPLIT_NOTES_ROW_STATUS)
        splitNotesRow()
        enableStatus(SPLIT_NOTES_ROW_STATUS)
    }
}

/** Answers every read of [SPLIT_NOTES_ROW_FLAG] through [SPLIT_NOTES_ROW]. */
internal fun BytecodePatchContext.splitNotesRow() {
    answerFlagLoads(findSplitNotesRow().map { it to SPLIT_NOTES_ROW })
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Every read of [SPLIT_NOTES_ROW_FLAG], each its own (not shared with another flag), one of them
 * the onCreate that opens your messages. Fails before anything changes when that isn't so, since
 * that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findSplitNotesRow(): List<FlagLoad> {
    val flag = SPLIT_NOTES_ROW_FLAG.toString(16)
    val reads = findFlagLoads(PATCH, SPLIT_NOTES_ROW_FLAG, "Z")
    if (reads.isEmpty()) refuse("nothing reads the split notes row flag $flag")
    reads.firstOrNull { it.shared }?.let { refuse("the read of $flag in ${it.type}->${it.name} is shared with another flag") }
    val opens = reads.count { it.name == "onCreate" && it.parameters == INBOX_OPEN && it.returnType == "V" }
    if (opens != 1) refuse("expected one read of $flag in an onCreate(Bundle), found $opens in ${reads.map { "${it.type}->${it.name}" }}")
    return reads
}
