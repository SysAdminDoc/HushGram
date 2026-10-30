/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.zoom

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Pinch to zoom on Reels"
internal const val REEL_ZOOM = "$EXTENSION_PACKAGE/reels/ReelZoom;"
internal const val ZOOM = "$REEL_ZOOM->zoom(I)Z"

/**
 * The marker of the method that turns the Reels zoom settings into the ZoomConfigurationInfo the
 * Reels viewer and its gesture detector read. It asks the session for the settings by their class.
 */
internal const val CREATE_ZOOM_CONFIG = "ClipsZoomUtil_createZoomConfig"

/**
 * isLithoZoomableComponentEnabled: a reel's video sits in Instagram's zoomable component, and the
 * Reels gesture detector hands it a two-finger move. Read once, into the session's zoom settings.
 */
internal const val ZOOMABLE_FLAG = 0x8105f500011b5eL

/**
 * isLithoZoomGestureEnabled: a second finger on a reel starts a zoom instead of being ignored. Read
 * right after [ZOOMABLE_FLAG], into the same settings.
 */
internal const val ZOOM_GESTURE_FLAG = 0x208105f500021b5fL

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
 * read, in one method, each stored straight into a boolean of the settings class the method marked
 * [CREATE_ZOOM_CONFIG] asks the session for, and that method has to read both fields. Fails before
 * anything changes when any of it isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findReelZoom(): List<FlagLoad> {
    val settings = zoomSettingsType()
    val reads = listOf(ZOOMABLE_FLAG, ZOOM_GESTURE_FLAG).map { flag ->
        val id = flag.toString(16)
        val loads = findFlagLoads(PATCH, flag, "Z")
        val read = loads.singleOrNull() ?: refuse("expected one read of the zoom flag $id, found ${loads.size}")
        if (read.shared) refuse("the read of $id in ${read.type}->${read.name} is shared with another flag")
        read
    }
    if (reads.map { Triple(it.type, it.name, it.parameters) }.distinct().size != 1) {
        refuse("the zoom flags are read in ${reads.joinToString(" and ") { "${it.type}->${it.name}" }}, not in one method")
    }
    val stored = reads.map { read -> storedField(read) }
    stored.forEachIndexed { index, field ->
        val id = reads[index].flag.toString(16)
        if (field == null || field.definingClass != settings.first) {
            refuse("the read of $id isn't stored straight into a field of the zoom settings ${settings.first}")
        }
    }
    val asked = settings.second.implementation!!.instructions.mapNotNull {
        if (it.opcode != Opcode.IGET_BOOLEAN) null else (it as ReferenceInstruction).reference as FieldReference
    }.map { it.name }
    stored.forEachIndexed { index, field ->
        if (field!!.name !in asked) {
            refuse("${settings.second.definingClass}->${settings.second.name} doesn't read ${field.name}, where ${reads[index].flag.toString(16)} goes")
        }
    }
    return reads
}

/** The settings class the [CREATE_ZOOM_CONFIG] method asks for, and that method. */
private fun BytecodePatchContext.zoomSettingsType(): Pair<String, Method> {
    val makers = typesMarked(CREATE_ZOOM_CONFIG).sorted().flatMap { type ->
        classDefBy(type).methods.filter { CREATE_ZOOM_CONFIG in it.markers() }
    }
    val maker = makers.singleOrNull() ?: refuse("expected one method marked $CREATE_ZOOM_CONFIG, found ${makers.size}")
    val classes = maker.implementation!!.instructions.filter { it.opcode == Opcode.CONST_CLASS }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()
    val type = classes.singleOrNull()
        ?: refuse("${maker.definingClass}->${maker.name} asks for ${classes.size} classes, not the zoom settings alone")
    return type to maker
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
