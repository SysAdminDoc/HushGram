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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Accent color"

private const val VIEW = "Landroid/view/View;"

private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"

/**
 * The tab layout Saved's filters along the top are inflated from (`fixed_tabbar_button`), the same id
 * in every 457 build (aapt2; read it again each update). The class making Saved's tabs loads it.
 */
internal const val SAVED_TAB_BUTTON = 0x7f0e0612

/** Hands the extension a filter's background, the tab it's for and its id, and gives the tab what comes back (#187). */
internal const val SAVED_FILTER = "$ACCENT_THEME->savedFilter($DRAWABLE${VIEW}I)$DRAWABLE"

/**
 * Where a tab's background is loaded and given to it: at [at], right after `Context.getDrawable(id)`
 * answers into [drawable], with the id still in [id] and the tab in [tab], the register the
 * background call that follows gives it to.
 */
internal class FilterBackground(val method: MutableMethod, val at: Int, val drawable: Int, val tab: Int, val id: Int)

/**
 * Every place Saved's tabs get their background: in the classes loading [SAVED_TAB_BUTTON], a
 * `Context.getDrawable(I)` whose answer goes, within the next few instructions, to a static
 * (Drawable, View, int)V call (Instagram's setBackground with a marker, `LX/01cE;->A03` in
 * `LX/0LXK;->A01` on 386314341) with no call and nothing writing the drawable or the tab in between.
 * A place something jumps onto, or one needing a register past v15, is left out. Answers none, with
 * a warning, when there's no such place, so the filters keep Instagram's colors. Refuses when the
 * extension lacks [SAVED_FILTER].
 */
internal fun BytecodePatchContext.findSavedFilterBackgrounds(): List<FilterBackground> {
    val sites = mutableListOf<Pair<String, Pair<Method, IntArray>>>()
    for (classDef in classesLoading(SAVED_TAB_BUTTON.toLong()).distinctBy { it.type }) {
        for (method in classDef.methods) {
            val code = method.code()
            val targets by lazy { method.jumpTargets() }
            for (at in 0 until code.size - 1) {
                val load = code[at]
                if (load.opcode != Opcode.INVOKE_VIRTUAL || !load.isGetDrawable()) continue
                val result = code[at + 1]
                if (result.opcode != Opcode.MOVE_RESULT_OBJECT) continue
                val drawable = (result as OneRegisterInstruction).registerA
                val id = (load as FiveRegisterInstruction).registerD
                if (drawable == id) continue
                val given = (at + 2 until minOf(code.size, at + 5)).firstOrNull { code[it].isBackgroundCall() } ?: continue
                val call = code[given] as FiveRegisterInstruction
                if (call.registerC != drawable) continue
                val tab = call.registerD
                val between = (at + 2 until given).map { code[it] }
                if (between.any { it.writes(drawable) || it.writes(tab) || it.called() != null }) continue
                if (at + 2 in targets || maxOf(drawable, tab, id) > 15) continue
                sites += classDef.type to (method to intArrayOf(at + 2, drawable, tab, id))
            }
        }
    }
    if (sites.isEmpty()) {
        patchLog.warning("$PATCH: nothing gives Saved's tabs their background, so Saved's filters keep Instagram's colors")
        return emptyList()
    }
    val present = classDefByOrNull(ACCENT_THEME)?.methods?.any {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SAVED_FILTER &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } == true
    if (!present) throw PatchException("$PATCH: the extension has no public static $SAVED_FILTER")
    return sites.map { (type, found) ->
        val (method, site) = found
        val mutable = mutableClassDefBy(type).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
        }
        FilterBackground(mutable, site[0], site[1], site[2], site[3])
    }
}

/**
 * Hands each of Saved's tab backgrounds to the extension between its load and the call giving it to
 * the tab, and gives the tab what comes back, the same background or, with Filters in the accent
 * color on, pills in the picked color (#187). Saved's filters (All, Collections, Reels and the rest)
 * kept Instagram's gray border: their background reads Instagram's stroke and secondary button
 * colors, which other borders and buttons share.
 */
internal fun BytecodePatchContext.tintSavedFilters() {
    for (site in findSavedFilterBackgrounds().sortedByDescending { it.at }) {
        site.method.addInstructions(
            site.at,
            """
                invoke-static { v${site.drawable}, v${site.tab}, v${site.id} }, $SAVED_FILTER
                move-result-object v${site.drawable}
            """,
        )
    }
}

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.isGetDrawable(): Boolean = called()?.let {
    it.definingClass == "Landroid/content/Context;" && it.name == "getDrawable" &&
        it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == DRAWABLE
} == true

/** A static (Drawable, View, int)V call: Instagram's setBackground with a marker. */
private fun Instruction.isBackgroundCall(): Boolean = opcode == Opcode.INVOKE_STATIC && called()?.let {
    it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(DRAWABLE, VIEW, "I")
} == true

private fun Instruction.writes(register: Int): Boolean =
    opcode.setsRegister() && this is OneRegisterInstruction &&
        (registerA == register || opcode.setsWideRegister() && registerA + 1 == register)

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
