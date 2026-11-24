/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Accent color"

private const val VIEW = "Landroid/view/View;"

/**
 * The outlined pill Saved's filters along the top are drawn with (`mini_pdp_pill_bg_large_outlined`),
 * the same id in every 457 build (aapt2; read it again each update).
 */
internal const val PILL_OUTLINED = 0x7f0816b2

/** The selected one, for the filter you're on (`mini_pdp_pill_bg_large_selected`), the same id in every 457 build. */
internal const val PILL_SELECTED = 0x7f0816b3

/** Hands the extension a pill and the background it was just given (#187). */
internal const val PILLS = "$ACCENT_THEME->pills(${VIEW}I)V"

/** Where a pill gets one of [PILL_OUTLINED] or [PILL_SELECTED]: right after the call at [at], with the pill in [view] and the background's id in [background]. */
internal class PillBackground(val method: MutableMethod, val at: Int, val view: Int, val background: Int)

/**
 * Every call giving a view one of Saved's pill backgrounds: a static `(View, int, int)V` (Instagram's
 * setBackgroundResource with a marker, `LX/01cE;->A0U` on 386314341) whose second register was loaded
 * with [PILL_OUTLINED] or [PILL_SELECTED] in the few instructions before it, in the classes loading
 * [PILL_SELECTED]. The call's five-register form keeps both registers under v16, so the hook takes
 * them as they are. A call with something jumping onto the instruction after it is left out, since
 * the jump would skip the hook. Answers none, with a warning, when there's no such call, so the pills
 * keep Instagram's colors. Refuses when the extension lacks [PILLS].
 */
internal fun BytecodePatchContext.findPillBackgrounds(): List<PillBackground> {
    val sites = mutableListOf<Triple<String, Method, Triple<Int, Int, Int>>>()
    for (classDef in classesLoading(PILL_SELECTED.toLong()).distinctBy { it.type }) {
        for (method in classDef.methods) {
            val code = method.code()
            val targets by lazy { method.jumpTargets() }
            for (at in code.indices) {
                val call = code[at]
                if (call.opcode != Opcode.INVOKE_STATIC) continue
                val called = (call as ReferenceInstruction).reference as? MethodReference ?: continue
                if (called.returnType != "V" || called.parameterTypes.map(Any::toString) != listOf(VIEW, "I", "I")) continue
                val registers = call as FiveRegisterInstruction
                val background = registers.registerD
                if (!pillLoaded(code, at, background) || at + 1 in targets) continue
                sites += Triple(classDef.type, method, Triple(at, registers.registerC, background))
            }
        }
    }
    if (sites.isEmpty()) {
        patchLog.warning("$PATCH: nothing gives a pill Saved's pill backgrounds, so Saved's filters keep Instagram's colors")
        return emptyList()
    }
    val present = classDefByOrNull(ACCENT_THEME)?.methods?.any {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == PILLS &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } == true
    if (!present) throw PatchException("$PATCH: the extension has no public static $PILLS")
    return sites.map { (type, method, site) ->
        val mutable = mutableClassDefBy(type).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
        }
        PillBackground(mutable, site.first, site.second, site.third)
    }
}

/**
 * Hands each pill given Saved's outlined or selected background to the extension right after, which
 * colors its border, title and fill with Filters in the accent color on (#187). Saved's filters
 * (All, Collections, Reels and the rest) kept Instagram's gray border and fill: the backgrounds read
 * Instagram's border and secondary background colors, which other borders share.
 */
internal fun BytecodePatchContext.tintPills() {
    for (site in findPillBackgrounds().sortedByDescending { it.at }) {
        site.method.addInstructions(site.at + 1, "invoke-static { v${site.view}, v${site.background} }, $PILLS")
    }
}

/**
 * Whether [register] holds [PILL_OUTLINED] or [PILL_SELECTED] at [at]: one of them is loaded into it
 * within the four instructions before, with nothing else writing it after that load but a load of the
 * other one.
 */
private fun pillLoaded(code: List<Instruction>, at: Int, register: Int): Boolean {
    var found = false
    for (back in (at - 1) downTo maxOf(0, at - 4)) {
        val instruction = code[back]
        val literal = (instruction as? NarrowLiteralInstruction)?.narrowLiteral
        val writes = instruction is OneRegisterInstruction && instruction.opcode.setsRegister() && instruction.registerA == register
        if (!writes) continue
        if (literal != PILL_OUTLINED && literal != PILL_SELECTED) return found
        found = true
    }
    return found
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
