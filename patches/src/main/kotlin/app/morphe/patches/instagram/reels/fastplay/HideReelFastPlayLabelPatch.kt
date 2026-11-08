/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fastplay

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterReturnAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide the 2x popup on Reels"
internal const val FAST_PLAY_LABEL = "$EXTENSION_PACKAGE/reels/FastPlayLabel;->label(Ljava/lang/Object;)Ljava/lang/Object;"

/** Loaded by the reel's fast play part as it draws (0Bcw.A0q on 454's 386014758). */
internal const val FAST_PLAY_RENDER = "android_purge_26_q3_ClipsFastPlayComponent_render"

/** Loaded by the call in it that builds the "2x" label with its arrows and lock icon (0Bcw.A00). */
internal const val FAST_PLAY_LABEL_RENDER = "android_purge_26_q3_ClipsFastPlayComponent_renderFastPlayLabel"

/**
 * Keeps the "2x" label off a reel while a held finger plays it faster. In the default selection
 * with its switch off.
 */
@Suppress("unused")
val hideReelFastPlayLabelPatch = bytecodePatch(
    name = "Hide the 2x popup on Reels",
    description = "Takes the 2x label off a reel while you hold it to play faster. The speed-up still works. " +
        "Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelFastLabel")
        hideFastPlayLabel(findFastPlayLabel())
        enableStatus("reelFastLabel")
    }
}

/**
 * Where the fast play part hands back its label: [ret] is the index of the `return-object` right
 * after the label call's `move-result-object` in [render], and [type] the label call's return type.
 */
internal class FastPlayLabelReturn(val render: Method, val ret: Int, val type: String)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The fast play part's draw: its one instance, one parameter method loading [FAST_PLAY_RENDER]
 * that answers an object. In it, the one direct call to a method of its own class that loads
 * [FAST_PLAY_LABEL_RENDER], whose answer it returns straight away. Fails before anything changes
 * when any of it isn't so.
 */
internal fun BytecodePatchContext.findFastPlayLabel(): FastPlayLabelReturn {
    val renders = classesLoadingString(FAST_PLAY_RENDER).flatMap { it.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
            method.parameterTypes.size == 1 && method.returnType.startsWith("L") &&
            loadsString(method, FAST_PLAY_RENDER)
    }
    val render = renders.singleOrNull()
        ?: refuse("expected one fast play draw loading \"$FAST_PLAY_RENDER\", found ${renders.size}")
    val where = "${render.definingClass}->${render.name}"
    val owner = classDefBy(render.definingClass)
    val code = render.implementation!!.instructions.toList()
    val calls = code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            call.definingClass == render.definingClass &&
            owner.methods.any { method ->
                method.name == call.name && method.returnType == call.returnType &&
                    method.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString) &&
                    loadsString(method, FAST_PLAY_LABEL_RENDER)
            }
    }
    val call = calls.singleOrNull()
        ?: refuse("expected $where to call its label builder loading \"$FAST_PLAY_LABEL_RENDER\" once, found ${calls.size}")
    val result = code.getOrNull(call + 1)
    val ret = code.getOrNull(call + 2)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || ret?.opcode != Opcode.RETURN_OBJECT ||
        (result as OneRegisterInstruction).registerA != (ret as OneRegisterInstruction).registerA
    ) {
        refuse("$where doesn't return its label builder's answer straight away")
    }
    val type = ((code[call] as ReferenceInstruction).reference as MethodReference).returnType
    if (!type.startsWith("L")) refuse("$where's label builder answers $type, not an object")
    return FastPlayLabelReturn(render, call + 2, type)
}

/** Passes the label the fast play draw returns through [FAST_PLAY_LABEL], which hides it while the switch is on. */
internal fun BytecodePatchContext.hideFastPlayLabel(found: FastPlayLabelReturn) {
    val render = mutableClassDefBy(found.render.definingClass).methods.single {
        it.name == found.render.name &&
            it.parameterTypes.map(Any::toString) == found.render.parameterTypes.map(Any::toString) &&
            it.returnType == found.render.returnType
    }
    render.filterReturnAt(PATCH, found.ret, FAST_PLAY_LABEL, found.type)
}
