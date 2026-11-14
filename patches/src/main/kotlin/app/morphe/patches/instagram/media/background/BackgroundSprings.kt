/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Thrown by the spring system's activate for a spring id it doesn't know (02iJ.A03 on 454). The
 * system is Rebound's BaseSpringSystem, which the story viewer's pager (ReboundViewPager) slides to
 * the next story with.
 */
internal const val SPRING_UNREGISTERED = " does not reference a registered spring"

/** Told by the spring system's activate, each time one of its springs sets off, the system's frame callback. */
internal const val SPRING_MOVING = "$BACKGROUND_PLAY->springMoving(Ljava/lang/Object;)V"

private const val FRAME_CALLBACK = "Landroid/view/Choreographer\$FrameCallback;"

/**
 * The spring system's [activate] (String), its [looper] field (02iJ.A01 on 454) and the looper's
 * [frames], the Choreographer frame callback that steps every spring of the system (02iI.A03).
 */
internal class SpringActivate(val activate: Method, val looper: FieldReference, val frames: FieldReference)

/**
 * Finds [SpringActivate] before anything changes: the one instance (String) void method loading
 * [SPRING_UNREGISTERED], the one frame callback field it reads, and the one field of its own class
 * it reads holding the class that callback belongs to.
 */
internal fun BytecodePatchContext.findSpringActivate(patch: String): SpringActivate {
    val found = classesLoadingString(SPRING_UNREGISTERED).flatMap { it.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == "V" &&
            method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;") && loadsString(method, SPRING_UNREGISTERED)
    }
    val activate = found.singleOrNull()
        ?: throw PatchException("$patch: expected one spring system activate loading \"$SPRING_UNREGISTERED\", found ${found.size}")
    val reads = (activate.implementation?.instructions?.toList() ?: emptyList())
        .filter { it.opcode == Opcode.IGET_OBJECT }
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
    val frames = reads.filter { it.type == FRAME_CALLBACK }.distinctBy { it.toString() }.singleOrNull()
        ?: throw PatchException("$patch: the spring system's activate reads no one frame callback in ${activate.definingClass}")
    val looper = reads.filter { it.definingClass == activate.definingClass && it.type == frames.definingClass }
        .distinctBy { it.toString() }.singleOrNull()
        ?: throw PatchException("$patch: the spring system's activate reads no one ${frames.definingClass} of its own")
    return SpringActivate(activate, looper, frames)
}

/**
 * Hands the extension the system's frame callback first thing in [SpringActivate.activate], after
 * any hook another patch put there, so a slide that sets off while Instagram is in the background
 * gets frames (#497). Nothing holds a local before the method's own first instruction, so v0 is
 * free. A system with no looper skips the call.
 */
internal fun BytecodePatchContext.keepSpringsMoving(patch: String, springs: SpringActivate) {
    mutable(springs.activate).apply {
        requireLocals(patch, 1)
        addAfterLeadingHooks(
            """
                move-object/from16 v0, p0
                iget-object v0, v0, ${springs.looper.text()}
                if-eqz v0, :start
                iget-object v0, v0, ${springs.frames.text()}
                invoke-static/range { v0 .. v0 }, $SPRING_MOVING
            """,
            guard = true,
        )
    }
}

private fun FieldReference.text() = "$definingClass->$name:$type"
