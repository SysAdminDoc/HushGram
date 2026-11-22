/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.surface

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val PLAYER_SURFACES = "$EXTENSION_PACKAGE/media/PlayerSurfaces;"
internal const val DRAWS_INTO = "$PLAYER_SURFACES->drawsInto(Landroid/view/SurfaceView;Landroid/view/Surface;)V"

/** Loaded by the surfaceCreated of Instagram's Groot listener, the one that gives a SurfaceView's player a Surface of its own. */
internal const val GROOT_SURFACE_CREATED = "GrootControlSurfaceViewListenerImpl.surfaceCreated"

private const val SURFACE = "Landroid/view/Surface;"
private const val SURFACE_VIEW = "Landroid/view/SurfaceView;"
private const val SURFACE_HOLDER = "Landroid/view/SurfaceHolder;"
private const val PATCH = "Player surfaces"

/**
 * Where the hook goes: [method], Groot's surfaceCreated, after [store], the one iput of the Surface its
 * player draws into, kept in a field of the listener's superclass; [view] is the listener's own
 * SurfaceView field.
 */
internal class PlayerSurfaceStore(val method: Method, val store: Int, val view: FieldReference)

/**
 * The listener class loading [GROOT_SURFACE_CREATED] from a surfaceCreated(SurfaceHolder)V of its own,
 * its single SurfaceView field, and in that method the single iput-object of a Surface into a field
 * its superclass declares. Null, saying why in the log, unless there's exactly one of each.
 */
internal fun BytecodePatchContext.findPlayerSurfaceStore(): PlayerSurfaceStore? {
    val listeners = classesLoadingString(GROOT_SURFACE_CREATED).filter { classDef -> surfaceCreatedOf(classDef) != null }
    val listener = listeners.singleOrNull() ?: return skip("found ${listeners.size} classes with a surfaceCreated loading \"$GROOT_SURFACE_CREATED\"")
    val method = surfaceCreatedOf(listener)!!
    val views = listener.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == SURFACE_VIEW }
    val view = views.singleOrNull() ?: return skip("${listener.type} has ${views.size} SurfaceView fields")
    val code = method.implementation!!.instructions.toList()
    val stores = code.indices.filter { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IPUT_OBJECT && field?.type == SURFACE && field.definingClass == listener.superclass
    }
    val store = stores.singleOrNull() ?: return skip("${listener.type}->surfaceCreated stores ${stores.size} Surfaces into its superclass ${listener.superclass}")
    return PlayerSurfaceStore(method, store, view)
}

private fun BytecodePatchContext.surfaceCreatedOf(classDef: ClassDef): Method? = classDef.methods.singleOrNull { method ->
    method.name == "surfaceCreated" && method.returnType == "V" && method.parameterTypes.map { it.toString() } == listOf(SURFACE_HOLDER) &&
        loadsString(method, GROOT_SURFACE_CREATED)
}

private fun <T> skip(why: String): T? {
    patchLog.warning("$PATCH: $why, so a reel Instagram's player draws on a surface of its own stays black to the glass and Lens")
    return null
}

/**
 * Hands PlayerSurfaces the Surface Groot's player draws a SurfaceView's video into, right after
 * surfaceCreated stores it, with the listener's SurfaceView. Answers the method it changed, or null
 * when the store can't be found or can't take the hook: the copies then read the SurfaceView's own
 * surface, as they did.
 */
internal fun BytecodePatchContext.tellPlayerSurfaces(): MutableMethod? {
    val found = findPlayerSurfaceStore() ?: return null
    val method = mutableClassDefBy(found.method.definingClass).methods.first {
        it.name == found.method.name && it.parameterTypes.map { type -> type.toString() } == listOf(SURFACE_HOLDER)
    }
    val put = method.implementation!!.instructions.elementAt(found.store) as TwoRegisterInstruction
    val surface = put.registerA
    val listener = put.registerB
    val at = found.store + 1
    if (at in method.jumpTargets()) return skip("${method.definingClass}->surfaceCreated jumps to the instruction after the store")
    if (surface > 15 || listener > 15) return skip("${method.definingClass}->surfaceCreated keeps the Surface or itself past v15")
    val view = try {
        method.freeLocalsAt(PATCH, at, 1, except = listOf(surface, listener)).first()
    } catch (unknown: PatchException) {
        return skip(unknown.message ?: "no free register after the store")
    }
    method.addInstructions(
        at,
        """
            iget-object v$view, v$listener, ${found.view.definingClass}->${found.view.name}:$SURFACE_VIEW
            invoke-static { v$view, v$surface }, $DRAWS_INTO
        """,
    )
    return method
}

/**
 * Tells the extension which Surface Instagram's player draws each SurfaceView's video into, for the
 * patches that copy a video's frame: the glass, ambient light, the reel bars' blur and the Lens
 * buttons. It has no switch: it only remembers, and the copies read it while their own switches are on.
 */
internal val playerSurfacesPatch = bytecodePatch {
    dependsOn(instagramExtensionPatch)

    execute {
        tellPlayerSurfaces()
    }
}
