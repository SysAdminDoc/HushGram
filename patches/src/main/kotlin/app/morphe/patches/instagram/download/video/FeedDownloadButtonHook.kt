/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.share.findFeedUfiSite
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Download any video"

internal const val FEED_BUTTON =
    "$EXTENSION_PACKAGE/download/FeedDownloadButton;->bind(Landroid/view/View;Ljava/lang/Object;Ljava/lang/Object;Landroid/app/Activity;)V"

/** Feed's inflated Save button in Instagram 450 (`row_feed_button_save`), the same id in every 450 build. */
internal const val SAVE_BUTTON_ID = 0x7f0b370e

private const val BOUNCY = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
private const val ACTIVITY = "Landroid/app/Activity;"

/**
 * Where a feed post's action row is bound, and what the "Download button on feed posts" hook reads
 * there: the holder's Save button ([save]), the post the row's state keeps ([media], read from the
 * parameter at [state]), the feed state at [item] that knows the carousel page on screen, and the
 * activity the binder keeps ([activity]). [holder] is the parameter that holds the row's views.
 */
internal class FeedButtonSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val holder: Int,
    val state: Int,
    val item: Int,
    val save: FieldReference,
    val media: FieldReference,
    val activity: FieldReference,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the binder Hide the Repost button hooks (the one inflating `reposts_ufi_icon`), then what
 * the button hook reads in it, all by structure: the holder is the parameter of the class that
 * keeps the repost icon, its Save button the bouncy field its constructor stores the view
 * [SAVE_BUTTON_ID] names into, the state the one other parameter holding a [MEDIA] field, the feed
 * state the one parameter of the type [page] reads the carousel page from, and the activity the
 * binder's one Activity field. Fails naming what it didn't find exactly once.
 */
internal fun BytecodePatchContext.findFeedButtonSite(page: PageIndex): FeedButtonSite {
    val ufi = try {
        findFeedUfiSite()
    } catch (failure: PatchException) {
        refuse("the Feed action-row binder isn't found (${failure.message})")
    }
    val binder = classDefByOrNull(ufi.type) ?: refuse("${ufi.type} is missing")
    val binders = binder.methods.filter { it.name == ufi.name && it.parameterTypes.map(CharSequence::toString) == ufi.parameters }
    val method = binders.singleOrNull() ?: refuse("expected one ${ufi.type}->${ufi.name}, found ${binders.size}")
    if (AccessFlags.STATIC.isSet(method.accessFlags)) refuse("${ufi.type}->${ufi.name} is static")
    val holderType = ufi.icon.definingClass

    val holders = ufi.parameters.indices.filter { ufi.parameters[it] == holderType }
    val holder = holders.singleOrNull() ?: refuse("${ufi.type}->${ufi.name} takes the row's holder ${holders.size} times")
    val holderClass = classDefByOrNull(holderType) ?: refuse("$holderType is missing")
    val saves = holderClass.methods.flatMap { constructor ->
        val code = constructor.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.filter { code[it].loadsLiteral(SAVE_BUTTON_ID) }.mapNotNull { code.fieldStoreAfter(it, holderType) }
    }.distinctBy { it.toString() }
    val save = saves.singleOrNull() ?: refuse("expected one Save button stored from view $SAVE_BUTTON_ID in $holderType, found ${saves.size}")

    val states = ufi.parameters.indices.filter { index ->
        index != holder && classDefByOrNull(ufi.parameters[index])?.fields?.count {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MEDIA
        } == 1
    }
    val state = states.singleOrNull() ?: refuse("expected one parameter of ${ufi.type}->${ufi.name} holding a $MEDIA, found ${states.size}")
    val media = classDefBy(ufi.parameters[state]).fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MEDIA }

    val items = ufi.parameters.indices.filter { ufi.parameters[it] == page.index.definingClass }
    val item = items.singleOrNull() ?: refuse(
        "expected one parameter of ${ufi.type}->${ufi.name} for the carousel page's feed state ${page.index.definingClass}, found ${items.size}",
    )
    if (item == state || item == holder) refuse("${ufi.type}->${ufi.name}'s feed state is also its row state or holder")

    val activities = binder.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == ACTIVITY }
    val activity = activities.singleOrNull() ?: refuse("expected one Activity field in ${ufi.type}, found ${activities.size}")

    return FeedButtonSite(
        ufi.type, ufi.name, ufi.parameters, holder, state, item,
        save,
        ImmutableFieldReference(media.definingClass, media.name, media.type),
        ImmutableFieldReference(activity.definingClass, activity.name, activity.type),
    )
}

/**
 * First thing in the binder, hands [FEED_BUTTON] the holder's Save button, the post, the feed state
 * and the activity. Nothing is live yet but the parameters, so the four locals it borrows are free;
 * each parameter is copied with a 16-bit move since its register can be past what a field read
 * reaches.
 */
internal fun BytecodePatchContext.addFeedDownloadButton(site: FeedButtonSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.requireLocals(PATCH, 4)
    val self = method.localRegisterCount()
    method.addInstructions(
        0,
        """
            move-object/from16 v0, v${method.parameterRegisterNumber(site.holder)}
            iget-object v0, v0, ${site.save}
            move-object/from16 v1, v${method.parameterRegisterNumber(site.state)}
            iget-object v1, v1, ${site.media}
            move-object/from16 v2, v${method.parameterRegisterNumber(site.item)}
            move-object/from16 v3, v$self
            iget-object v3, v3, ${site.activity}
            invoke-static { v0, v1, v2, v3 }, $FEED_BUTTON
        """,
    )
}

private fun Instruction.loadsLiteral(value: Int) =
    this is NarrowLiteralInstruction && opcode == Opcode.CONST && narrowLiteral == value

/** The bouncy field of [holder] stored within a few instructions after the view lookup at [start]. */
private fun List<Instruction>.fieldStoreAfter(start: Int, holder: String): FieldReference? {
    for (at in start + 1 until minOf(size, start + 12)) {
        val instruction = this[at]
        if (instruction.opcode != Opcode.IPUT_OBJECT || instruction !is TwoRegisterInstruction) continue
        val field = ((instruction as? ReferenceInstruction)?.reference as? FieldReference) ?: continue
        if (field.definingClass == holder && field.type == BOUNCY) return field
    }
    return null
}

