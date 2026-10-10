/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.likes

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Shows a post's like count when its poster hid it and Instagram's server still sent it. Only how
 * the like rows draw changes: the owner's own menus and the request that changes the setting read
 * the flag elsewhere. In the default selection with its switch off.
 */
@Suppress("unused")
val showHiddenLikeCountsPatch = bytecodePatch(
    name = "Show hidden like counts",
    description = "Shows how many likes a post or reel has when its owner hid the count, as long as Instagram's " +
        "server still sends the number. If it doesn't, nothing changes. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("hiddenLikeCounts")
        applyHiddenLikeCounts(findHiddenLikeCounts())
        enableStatus("hiddenLikeCounts")
    }
}

/**
 * The flag goes through [HIDDEN_DECISION] first thing in the decider, as an int, and what it
 * answers is what the decider tests; the like count reader hands its tree and what it read to
 * [SAW_LIKE_COUNT] right after the read, before its own null test; each like row hands its tree and
 * the flag it just read to [ROW_READ], so the decision is that post's own; and the two stubs are
 * filled with the reader's own reads on the tree, so the extension reads the flag and the count the
 * way Instagram reads its own fields.
 */
internal fun BytecodePatchContext.applyHiddenLikeCounts(anchors: HiddenLikeCountAnchors) {
    mutable(anchors.counter).addInstructions(
        anchors.countAt + 1,
        "invoke-static { v${anchors.tree}, v${anchors.count} }, $SAW_LIKE_COUNT",
    )
    mutable(anchors.decider).addInstructions(
        0,
        """
            invoke-static/range { v${anchors.flag} .. v${anchors.flag} }, $HIDDEN_DECISION
            move-result v${anchors.flag}
        """,
    )

    // Each like row hands the post it just read the flag of, and the flag, to the extension, which
    // looks at that post's own count. Later reads first, so earlier indexes stay where they were.
    for ((method, reads) in anchors.rows.groupBy { it.method.toString() }.values.map { it.first().method to it }) {
        val target = mutable(method)
        for (row in reads.sortedByDescending { it.at }) {
            target.addInstructions(row.at + 1, "invoke-static { v${row.tree}, v${row.flag} }, $ROW_READ")
        }
    }

    for ((stub, read) in listOf(TREE_FLAG_STUB to anchors.treeFlagRead, TREE_COUNT_STUB to anchors.countRead)) {
        mutableClassDefBy(HIDDEN_LIKE_COUNTS).methods.single {
            it.name == stub && AccessFlags.STATIC.isSet(it.accessFlags)
        }.addInstructions(
            0,
            """
                check-cast p0, ${read.definingClass}
                invoke-interface { p0, p1 }, ${read.definingClass}->${read.name}(I)${read.returnType}
                move-result-object p0
                return-object p0
            """,
        )
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }
