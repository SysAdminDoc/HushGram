/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.zoom

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Pinch to zoom on Reels"
internal const val REEL_ZOOM = "$EXTENSION_PACKAGE/reels/ReelZoom;"
internal const val ZOOM = "$REEL_ZOOM->zoom(I)Z"

/**
 * isLithoZoomableComponentEnabled: a reel's video sits in Instagram's zoomable component, and the
 * Reels gesture detector hands it a two-finger move. Read once, into the session's zoom settings.
 * 456 renumbered it from 455's 0x8105c500011af0 (454 had 0x8105d100011b36, 453 0x8105f500011b5e).
 */
internal const val ZOOMABLE_FLAG = 0x8105be00011aadL

/**
 * isLithoZoomGestureEnabled: a second finger on a reel starts a zoom instead of being ignored. Read
 * right after [ZOOMABLE_FLAG], into the same settings. 456 renumbered it from 455's 0x208105c500021af1
 * (454 had 0x208105d100021b37, 453 0x208105f500021b5f).
 */
internal const val ZOOM_GESTURE_FLAG = 0x208105be00021aaeL

/**
 * Lets two fingers zoom into a playing reel, through Instagram's own zoom for Reels, which it keeps
 * behind two server flags. Both are read once a session, so a change takes a restart. In the
 * default selection with its switch off.
 */
@Suppress("unused")
val pinchZoomReelsPatch = bytecodePatch(
    name = "Pinch to zoom on Reels",
    description = "Lets you zoom into a playing reel with two fingers, the way a photo zooms. Lift your fingers " +
        "and it goes back. Restart Instagram to see the change. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelZoom")
        zoomReels()
        enableStatus("reelZoom")
    }
}

/** Finds both reads with [findReelZoom] and has the extension answer each through [ZOOM]. */
internal fun BytecodePatchContext.zoomReels() {
    answerFlagLoads(findReelZoom().map { it to ZOOM })
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the one read of [ZOOMABLE_FLAG] and of [ZOOM_GESTURE_FLAG]. Both have to be the flag's own
 * read, in one method, the zoom settings' factory, each stored straight into a boolean of the
 * settings, the class the gesture flag's read is stored into. One method outside the factory has to
 * ask the session for those settings by their class and read both booleans: the one building the
 * ZoomConfigurationInfo the Reels viewer and its gesture detector read (455 traced it as
 * ClipsZoomUtil_createZoomConfig, which 456 dropped; 0JBZ.A0K on 456's 386214574 folds it into a
 * merged lambda). Where the zoomable flag is read more than once, the read is the one stored into
 * the settings: 456 also reads it in a startup task that only warms the zoom classes up when it's
 * on (`LX/08rV;->A00` on 386214574, beside `LX/0PGO;->A02`, the settings' factory), which stays
 * Instagram's. Fails before anything changes when any of it isn't so, since that's an update this
 * patch hasn't seen.
 */
internal fun BytecodePatchContext.findReelZoom(): List<FlagLoad> {
    val gesture = ownRead(ZOOM_GESTURE_FLAG) { true }
    val gestureId = ZOOM_GESTURE_FLAG.toString(16)
    val settings = storedField(gesture)?.definingClass
        ?: refuse("the read of $gestureId isn't stored straight into a field of the zoom settings")
    val zoomable = ownRead(ZOOMABLE_FLAG) { storedField(it)?.definingClass == settings }
    val reads = listOf(zoomable, gesture)
    if (reads.map { Triple(it.type, it.name, it.parameters) }.distinct().size != 1) {
        refuse("the zoom flags are read in ${reads.joinToString(" and ") { "${it.type}->${it.name}" }}, not in one method")
    }
    val stored = reads.map { read -> storedField(read) }
    stored.forEachIndexed { index, field ->
        val id = reads[index].flag.toString(16)
        if (field == null || field.definingClass != settings) {
            refuse("the read of $id isn't stored straight into a field of the zoom settings $settings")
        }
    }
    val fields = stored.map { it!!.name }
    val askers = fields.flatMap { classesAccessing(settings, it, Opcode.IGET_BOOLEAN) }.distinctBy { it.type }
        .sortedBy { it.type }.flatMap { it.methods }.filter { method ->
            method.implementation?.instructions?.any {
                it.opcode == Opcode.CONST_CLASS && ((it as ReferenceInstruction).reference as TypeReference).type == settings
            } == true
        }
    val asker = askers.singleOrNull() ?: refuse(
        "expected one method asking for the zoom settings $settings by class and reading them, found ${askers.size}",
    )
    val asked = asker.implementation!!.instructions.mapNotNull {
        if (it.opcode != Opcode.IGET_BOOLEAN) null else (it as ReferenceInstruction).reference as FieldReference
    }.filter { it.definingClass == settings }.map { it.name }
    fields.forEachIndexed { index, field ->
        if (field !in asked) {
            refuse("${asker.definingClass}->${asker.name} doesn't read $field, where ${reads[index].flag.toString(16)} goes")
        }
    }
    return reads
}

/**
 * The one read of [flag] of its own, the one [keep] answers yes for where there are several. Fails
 * with the counts it found otherwise, or when the read is shared with another flag's.
 */
private fun BytecodePatchContext.ownRead(flag: Long, keep: (FlagLoad) -> Boolean): FlagLoad {
    val id = flag.toString(16)
    val loads = findFlagLoads(PATCH, flag, "Z")
    val kept = if (loads.size > 1) loads.filter(keep) else loads
    val read = kept.singleOrNull() ?: refuse(
        "expected one read of the zoom flag $id, found ${loads.size}" +
            if (loads.size > 1) ", ${kept.size} of them stored into the zoom settings" else "",
    )
    if (read.shared) refuse("the read of $id in ${read.type}->${read.name} is shared with another flag")
    return read
}

/** The field [read]'s answer goes into when the instruction after its move-result is an iput-boolean of it. */
private fun BytecodePatchContext.storedField(read: FlagLoad): FieldReference? {
    val method = classDefBy(read.type).methods.single {
        it.name == read.name && it.returnType == read.returnType && it.parameterTypes.map(CharSequence::toString) == read.parameters
    }
    val next = method.implementation!!.instructions.toList().getOrNull(read.resultAt + 1) ?: return null
    if (next.opcode != Opcode.IPUT_BOOLEAN || (next as TwoRegisterInstruction).registerA != read.register) return null
    return (next as ReferenceInstruction).reference as FieldReference
}
