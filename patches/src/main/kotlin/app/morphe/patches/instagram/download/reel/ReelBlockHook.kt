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
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val BLOCK_PATCH = "Download any reel"

internal const val BLOCK_TYPE = "$EXTENSION_PACKAGE/download/ReelBlockButton;"
internal const val BLOCK_ON = "$BLOCK_TYPE->on()Z"
internal const val BLOCK_LITHO = "$BLOCK_TYPE->litho(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"
internal const val BLOCK_LITHO_ABOVE_MORE =
    "$BLOCK_TYPE->lithoAboveMore(Ljava/lang/Object;Ljava/lang/Object;JJ)Ljava/lang/Object;"

/**
 * A string Instagram's block sheet helper holds: the entry point it checks for. The helper is the
 * one class holding it whose shape is the helper's (see [findBlockSite]).
 */
internal const val BLOCK_HELPER_ANCHOR = "comment_deletion_bottom_sheet_upsell"

private const val BLOCK_CONTEXT = "Landroid/content/Context;"
private const val BLOCK_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
private const val BLOCK_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val BLOCK_USER = "Lcom/instagram/user/model/User;"
private const val BLOCK_STRING = "Ljava/lang/String;"
private const val BLOCK_OBJECT = "Ljava/lang/Object;"

/** How many instructions the Not interested icon's block is, which the Block icon's goes in front of. */
internal const val NOT_INTERESTED_BLOCK = 14

/**
 * What the Block icon beside a reel (#191) needs. Instagram's [helper] opens its own block sheet for
 * a user through [open], a method of the helper's one instance [instance], with the screen's
 * activity and the signed-in session. The delegate the Not interested icon remembers holds both, as
 * [activity] and [session].
 */
internal class BlockSite(
    val helper: String,
    val instance: FieldReference,
    val open: MethodReference,
    val activity: FieldReference,
    val session: FieldReference,
)

private fun refuseBlock(detail: String): Nothing = throw PatchException("$BLOCK_PATCH, the Block button beside a reel: $detail")

/**
 * Finds what [addBlockButton] needs: the one class loading [BLOCK_HELPER_ANCHOR] that is public,
 * keeps its one instance in a static field of its own type, and has one public instance method
 * taking a context, an activity, a session, a user and two strings; and the one public activity
 * and one public session field of [found]'s delegate. Fails naming what it didn't find exactly
 * once, before anything changes.
 */
internal fun BytecodePatchContext.findBlockSite(found: NotInterestedSite): BlockSite {
    val shape = listOf(BLOCK_CONTEXT, BLOCK_ACTIVITY, BLOCK_SESSION, BLOCK_USER, BLOCK_STRING, BLOCK_STRING)
    fun opens(helper: ClassDef) = helper.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == shape
    }
    fun instances(helper: ClassDef) = helper.fields.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.type == helper.type
    }
    val helpers = classesLoadingString(BLOCK_HELPER_ANCHOR).filter { helper ->
        AccessFlags.PUBLIC.isSet(helper.accessFlags) && opens(helper).size == 1 && instances(helper).size == 1
    }
    val helper = helpers.singleOrNull()
        ?: refuseBlock("expected one helper class loading \"$BLOCK_HELPER_ANCHOR\", found ${helpers.size}")
    val open = opens(helper).single()
    val instance = instances(helper).single()

    val delegate = classDefByOrNull(found.delegate) ?: refuseBlock("the delegate ${found.delegate} is missing")
    fun field(type: String) = delegate.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
        .singleOrNull()?.takeIf { AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ?: refuseBlock("expected one public $type field in the delegate ${delegate.type}")
    val activity = field(BLOCK_ACTIVITY)
    val session = field(BLOCK_SESSION)
    if (classDefByOrNull(BLOCK_TYPE) == null) refuseBlock("$BLOCK_TYPE is missing")

    return BlockSite(
        helper.type,
        ImmutableFieldReference(helper.type, instance.name, instance.type),
        ImmutableMethodReference(helper.type, open.name, shape, "V"),
        ImmutableFieldReference(delegate.type, activity.name, activity.type),
        ImmutableFieldReference(delegate.type, session.name, session.type),
    )
}

/**
 * Right in front of the Not interested icon's block at each of [site]'s places, asks the extension
 * whether the Block icon is on, and when it is adds the icon it answers to the column, as the Not
 * interested block does. That block is left as it was, so it still runs after this one and skips on
 * its own. Then the bridge the extension opens Instagram's own block sheet through is written.
 */
internal fun BytecodePatchContext.addBlockButton(site: ReelButtonSite, found: NotInterestedSite, block: BlockSite) {
    val render = mutableClassDefBy(site.column).methods.single {
        it.name == site.render.name && it.parameterNames() == site.render.parameterTypes.map(CharSequence::toString) &&
            it.returnType == site.render.returnType
    }
    // Everything that can refuse is checked before anything changes.
    val bridges = mutableClassDefBy(BLOCK_TYPE)
    val runOf = bridges.methods.singleOrNull {
        it.name == "blockRun" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == BLOCK_OBJECT &&
            it.parameterNames() == listOf(BLOCK_OBJECT, BLOCK_OBJECT, BLOCK_STRING, BLOCK_STRING)
    } ?: throw PatchException("$BLOCK_PATCH: $BLOCK_TYPE has no static blockRun(Object, Object, String, String)")
    val ascending = site.sites.sortedBy { it.at }
    // Each place before this one already gained the Download block and the Not interested one.
    val starts = ascending.mapIndexed { rank, place -> place to place.at + (DOWNLOAD_BLOCK + NOT_INTERESTED_BLOCK) * rank }
    for ((_, start) in starts) {
        val first = render.implementation?.instructions?.elementAtOrNull(start)
        if ((first as? ReferenceInstruction)?.reference.toString() != NOT_INTERESTED_ON) {
            throw PatchException("$BLOCK_PATCH: the Not interested button's block isn't where the Block button goes in $start")
        }
    }

    starts.sortedByDescending { it.second }.forEach { (place, start) ->
        val litho = if (place.aboveMore) BLOCK_LITHO_ABOVE_MORE else BLOCK_LITHO
        render.addInstructionsWithLabels(
            start,
            columnIconBlock(site, place, BLOCK_ON, litho),
            ExternalLabel("skip", render.getInstruction(start)),
        )
    }

    // Instagram's own reels bubble opens the same sheet with the screen's activity twice, as the
    // context and the activity, then the session, the user, the screen's module and the user's id.
    rewriteStub(
        bridges, runOf, 7,
        """
            check-cast p0, ${found.delegate}
            check-cast p1, $BLOCK_USER
            sget-object v0, ${block.instance}
            iget-object v1, p0, ${block.activity}
            move-object v2, v1
            iget-object v3, p0, ${block.session}
            move-object v4, p1
            move-object v5, p2
            move-object v6, p3
            invoke-virtual/range { v0 .. v6 }, ${block.open}
            const/4 v0, 0x0
            return-object v0
        """,
    )
}
