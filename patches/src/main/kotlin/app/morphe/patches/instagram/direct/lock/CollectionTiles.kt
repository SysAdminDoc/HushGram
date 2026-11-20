/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Lock collection and Remove lock on a Saved collection's long press (#678): handed each collection tile Instagram makes. */
internal const val COLLECTION_TILE = "$EXTENSION_PACKAGE/direct/CollectionLockMenu;->tile(Landroid/view/View;)V"

/**
 * Instagram 456's id/saved_collection_thumbnail, the same in all four 456 builds (aapt2). Only the
 * two makers of a Saved collection tile load it: the See all grid's row binder (0IZf.createView,
 * layout save_home_collections_saved_collection) and the Saved home row's adapter (0Csf.A0Z,
 * layout layout_grid_item_collections_list_item). The extension finds the same view by name.
 */
internal const val SAVED_COLLECTION_THUMBNAIL = 0x7f0b393d

/** A maker of collection tiles, where the hook goes in it and the register holding the tile there. */
internal class CollectionTileSite(val method: MutableMethod, val at: Int, val tile: Int)

/**
 * [findCollectionTiles], or none with a warning in the patch log when this build's tiles have moved:
 * the long press is a shortcut to Locked collections in settings, and the rest of the lock goes on
 * without it.
 */
internal fun BytecodePatchContext.findCollectionTilesOrSkip(): List<CollectionTileSite> = try {
    findCollectionTiles()
} catch (e: PatchException) {
    patchLog.warning("${e.message}. Saved's collections get no Lock collection long press, and the rest of the lock goes on.")
    emptyList()
}

/**
 * Each method loading [SAVED_COLLECTION_THUMBNAIL], and the one place in it where the tile is in
 * hand: just before the id loads for the tile's thumbnail lookup, the tile being that lookup's
 * view. That's the view Instagram's tap goes on when it binds a collection later, so the long
 * press goes on it too. It has to be this early: the grid binder then gives the tile an
 * accessibility delegate that keeps the long click flag it finds and puts it back on every
 * accessibility query, so a listener set after that lost its long press whenever an accessibility
 * service (uiautomator included) read the screen. All are proved before any changes.
 */
internal fun BytecodePatchContext.findCollectionTiles(): List<CollectionTileSite> {
    val makers = classesLoading(SAVED_COLLECTION_THUMBNAIL.toLong()).flatMap { owner ->
        owner.methods.filter { it.loadsThumbnail() }
    }
    if (makers.isEmpty()) refuseTiles("found no method making a Saved collection tile")
    return makers.map { found ->
        val method = mutableClassDefBy(found.definingClass).methods.single { it.sameShape(found) }
        val code = method.implementation!!.instructions.toList()
        val where = "${method.definingClass}->${method.name}"
        val loads = code.indices.filter { code[it].isThumbnailId() }
        val load = loads.singleOrNull()
            ?: refuseTiles("expected $where to load its collection tile's thumbnail id once, found ${loads.size}")
        val id = (code[load] as? OneRegisterInstruction)?.registerA
        val tile = id?.let { code.getOrNull(load + 1)?.thumbnailLookupOn(it) }
            ?: refuseTiles("expected $where to look up its collection tile's thumbnail right after loading its id")
        if (load in method.jumpTargets()) refuseTiles("something in $where jumps to its collection tile's thumbnail id")
        CollectionTileSite(method, load, tile)
    }
}

/** Before each maker looks up its tile's thumbnail, the tile goes to [COLLECTION_TILE]. */
internal fun hookCollectionTiles(sites: List<CollectionTileSite>) {
    sites.forEach { site ->
        site.method.addInstructions(site.at, "invoke-static/range { v${site.tile} .. v${site.tile} }, $COLLECTION_TILE")
    }
}

private fun Instruction.isThumbnailId() = (this as? NarrowLiteralInstruction)?.narrowLiteral == SAVED_COLLECTION_THUMBNAIL

private fun Method.loadsThumbnail(): Boolean = implementation?.instructions?.any { it.isThumbnailId() } == true

private fun Method.sameShape(other: Method) = name == other.name && returnType == other.returnType &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/**
 * The view a lookup of the id in [id] is on, or null for anything else: the view's own findViewById
 * or requireViewById, or a static (View, int) helper answering a view, as 386214562's adapter has.
 */
private fun Instruction.thumbnailLookupOn(id: Int): Int? {
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val types = call.parameterTypes.map(CharSequence::toString)
    val lookup = when (opcode) {
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE ->
            (call.name == "findViewById" || call.name == "requireViewById") && types == listOf("I")
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE ->
            types == listOf("Landroid/view/View;", "I") && call.returnType.startsWith("L")
        else -> false
    }
    if (!lookup) return null
    val (view, looked) = when (this) {
        is RegisterRangeInstruction -> startRegister to startRegister + 1
        is FiveRegisterInstruction -> registerC to registerD
        else -> return null
    }
    return view.takeIf { looked == id && view != id }
}

private fun refuseTiles(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")
