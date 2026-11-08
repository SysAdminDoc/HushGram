/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.live

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide lives in Reels"
internal const val LIVE_REELS = "$EXTENSION_PACKAGE/reels/LiveReels;"
internal const val LIVE_PAGE_FILTER = "$LIVE_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/**
 * The marker of the method that works out what a live preview in Reels shows. It asks the Reels
 * item for its live preview part first, by that part's key, and stops when there isn't one.
 */
internal const val LIVE_PREVIEW_STATE = "ClipsLivePreviewUseCase_getUiState"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * How a Reels item is asked for its live preview part: [key], the static field holding that part's
 * key, and [lookup], the item's method answering the part for a key, or null when it has none.
 */
internal class LivePreviewKey(val key: FieldReference, val lookup: MethodReference)

/**
 * Finds [LivePreviewKey] in the one method marked [LIVE_PREVIEW_STATE]: a static field read straight
 * into a call of the Reels item's ([itemType]) one-argument method answering an Object, with that
 * field as the argument. Every such read there has to agree. The field, its class, the method and
 * the item all have to be public so the extension's stub can reach them, and the field's type has to
 * be the key type the method takes.
 */
internal fun BytecodePatchContext.findLivePreviewKey(itemType: String): LivePreviewKey {
    val marked = typesMarked(LIVE_PREVIEW_STATE).sorted()
        .flatMap { type -> classDefBy(type).methods.filter { LIVE_PREVIEW_STATE in it.markers() } }
    val state = marked.singleOrNull()
        ?: refuse("expected one method marked $LIVE_PREVIEW_STATE, found ${marked.size}")
    val code = state.implementation?.instructions?.toList().orEmpty()
    val found = LinkedHashMap<String, LivePreviewKey>()
    for (at in 0 until code.size - 1) {
        if (code[at].opcode != Opcode.SGET_OBJECT) continue
        val key = (code[at] as ReferenceInstruction).reference as? FieldReference ?: continue
        val held = (code[at] as OneRegisterInstruction).registerA
        val call = code[at + 1]
        if (call.opcode != Opcode.INVOKE_VIRTUAL && call.opcode != Opcode.INVOKE_VIRTUAL_RANGE) continue
        val lookup = (call as ReferenceInstruction).reference as? MethodReference ?: continue
        if (lookup.definingClass != itemType || lookup.returnType != OBJECT || lookup.parameterTypes.size != 1) continue
        if (call.registers().getOrNull(1) != held) continue
        found["$key $lookup"] = LivePreviewKey(key, lookup)
    }
    val live = found.values.singleOrNull() ?: refuse(
        if (found.isEmpty()) "${state.describe()} doesn't ask a Reels item ($itemType) for a part by a key"
        else "${state.describe()} asks a Reels item for more than one part: ${found.keys}",
    )

    val holder = classDefByOrNull(live.key.definingClass) ?: refuse("${live.key.definingClass} isn't a class of the app")
    val field = holder.fields.firstOrNull { it.name == live.key.name && it.type == live.key.type }
        ?: refuse("${live.key} isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(holder.accessFlags) || !AccessFlags.PUBLIC.isSet(field.accessFlags) ||
        !AccessFlags.STATIC.isSet(field.accessFlags)
    ) refuse("${live.key} isn't a public static field the extension can read")
    val taken = live.lookup.parameterTypes.single().toString()
    val keyClass = classDefByOrNull(live.key.type)
    if (live.key.type != taken && keyClass?.interfaces?.contains(taken) != true) {
        refuse("${live.key} is a ${live.key.type}, not the $taken ${live.lookup.name} takes")
    }
    val item = classDefBy(itemType)
    val method = item.methods.firstOrNull {
        it.name == live.lookup.name && it.returnType == OBJECT && it.parameterTypes.map(Any::toString) == listOf(taken)
    } ?: refuse("${live.lookup} isn't declared by $itemType")
    if (!AccessFlags.PUBLIC.isSet(item.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags) ||
        AccessFlags.STATIC.isSet(method.accessFlags)
    ) refuse("${live.lookup} isn't a public method the extension can call")

    val extension = classDefByOrNull(LIVE_REELS) ?: refuse("the extension has no $LIVE_REELS")
    if (extension.methods.none { it.isStaticStub("page", listOf(LIST, STRING), LIST) }) {
        refuse("the extension has no static page(List, String)List")
    }
    if (extension.methods.none { it.isStaticStub("livePreview", listOf(OBJECT), OBJECT) }) {
        refuse("the extension has no static livePreview(Object)Object to fill")
    }
    return live
}

/**
 * Fills the extension's livePreview stub, which answers a Reels item's live preview part and null for
 * anything else, then hands each page the Reels viewer takes to [LIVE_PAGE_FILTER] with the viewer's
 * module name, where the other Reels filters read it.
 */
internal fun BytecodePatchContext.hideLiveReels(page: ReelsPageSites, live: LivePreviewKey) {
    fillLivePreview(page.itemType, live)
    filterReelsPage(page, LIVE_PAGE_FILTER)
}

/** Fills the extension's livePreview(Object)Object stub for Reels items of [itemType]. */
internal fun BytecodePatchContext.fillLivePreview(itemType: String, live: LivePreviewKey) {
    fillStub(
        LIVE_REELS, "livePreview", OBJECT, 2,
        """
            instance-of v0, p0, $itemType
            if-nez v0, :item
            const/4 v0, 0x0
            return-object v0
            :item
            check-cast p0, $itemType
            sget-object v0, ${live.key}
            invoke-virtual { p0, v0 }, ${live.lookup}
            move-result-object v0
            return-object v0
        """,
    )
}

private fun Method.describe() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** The registers an invoke passes, the receiver first. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/**
 * Leaves the previews of live broadcasts Instagram puts between reels out of the Reels tab and out
 * of the reels Explore plays when a tile is tapped (#566). A viewer opened on something the user
 * chose keeps them. In the default selection with its switch off.
 */
@Suppress("unused")
val hideLiveReelsPatch = bytecodePatch(
    name = "Hide lives in Reels",
    description = "Leaves out the live broadcasts Instagram puts between reels in the Reels tab and when you swipe " +
        "through reels from Explore. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("liveReels")
        val page = findReelsPage(PATCH)
        val live = findLivePreviewKey(page.itemType)
        hideLiveReels(page, live)
        enableStatus("liveReels")
    }
}
