/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Accent color"

/**
 * Instagram's color/igds_prism_secondary_button_background_filled and its _filled_light sibling, the
 * fills its button styles give the default secondary button (Edit profile, Share profile, Following):
 * 0x7f06038b and 0x7f06038d in 457 (aapt2, the same in both 457 builds; 0x7f060389 and 0x7f06038b in 456,
 * 0x7f060357 and 0x7f060359 in 455). One class loads both of a version's pair. 454 had the fill at
 * 0x7f060355, which 455 gives a borderless button's label, and 457 gives 456's fill, 0x7f060389, to one too,
 * so only the declared version's pair is read. Read it again each update.
 */
internal val SECONDARY_FILL_PAIRS = listOf(0x7f06038bL to 0x7f06038dL)

/** Every fill of [SECONDARY_FILL_PAIRS]. */
internal val SECONDARY_FILL_IDS = SECONDARY_FILL_PAIRS.flatMap { listOf(it.first, it.second) }

/** The extension's answer to the default secondary button's fill (#187). */
internal const val SECONDARY_BUTTON_FILL = "$ACCENT_THEME->secondaryButtonFill(Landroid/content/Context;I)I"

private const val CONTEXT = "Landroid/content/Context;"

/**
 * One method of Instagram's button styles loading a fill of [SECONDARY_FILL_IDS]: its context register
 * and the index of each load. 455 loads the fill straight into a return in the int answer, into a jump to
 * the ColorStateList build in the ColorStateList answer, and the _light one in the static method both
 * call on a dark screen; 456 builds the ColorStateList from the int answer.
 */
internal class SecondaryFill(val method: Method, val context: Int, val loads: List<Int>)

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/**
 * The methods of the one class loading both fills of a pair of [SECONDARY_FILL_PAIRS] that load either,
 * with their context register and their loads (#187). Leaves a method out with a warning when it takes no
 * context first, writes its context, or holds it or the fill past v15. Answers none with a warning when
 * there's no one such class, and refuses without the extension's hook.
 */
internal fun BytecodePatchContext.findSecondaryFills(): List<SecondaryFill> {
    val classes = SECONDARY_FILL_PAIRS.flatMap { (filled, light) ->
        val lights = classesLoading(light).map { it.type }.toSet()
        classesLoading(filled).filter { it.type in lights }.map { it to setOf(filled, light) }
    }
    val (styles, ids) = classes.singleOrNull() ?: run {
        patchLog.warning("$PATCH: found ${classes.size} classes answering a secondary button's fill, so Edit profile and Share profile keep Instagram's gray")
        return emptyList()
    }
    if (classDefByOrNull(ACCENT_THEME)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SECONDARY_BUTTON_FILL &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $SECONDARY_BUTTON_FILL")
    }
    return styles.methods.mapNotNull { method ->
        val code = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val loads = code.indices.filter { at ->
            val load = code[at]
            load is NarrowLiteralInstruction && load is OneRegisterInstruction && (load.narrowLiteral.toLong() and 0xffffffffL) in ids
        }
        if (loads.isEmpty()) return@mapNotNull null
        val where = "${method.definingClass}->${method.name}"
        if (method.parameterTypes.firstOrNull()?.toString() != CONTEXT) {
            patchLog.warning("$PATCH: $where takes no context first, so its secondary buttons keep Instagram's gray")
            return@mapNotNull null
        }
        val context = method.localRegisterCount() + if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        when {
            code.any { it.writes(context) } -> patchLog.warning("$PATCH: $where writes its context, so its secondary buttons keep Instagram's gray")
            (loads.map { (code[it] as OneRegisterInstruction).registerA } + context).any { it > 15 } ->
                patchLog.warning("$PATCH: $where holds its fill past v15, so its secondary buttons keep Instagram's gray")
            else -> return@mapNotNull SecondaryFill(method, context, loads)
        }
        null
    }
}

/**
 * Hands each fill [findSecondaryFills] found to AccentTheme.secondaryButtonFill with the context right
 * after it's loaded, and keeps the answer in its place, so the default secondary button takes the shade
 * the theme gives the other secondary buttons once the backgrounds are in the accent color or Classic
 * colors are on (#187), whichever of the styles' answers draws it. Only the loaded fill goes through the
 * hook: a jump onto the instruction after a load still lands there. Leaves the buttons as they are when
 * there's no such method.
 */
internal fun BytecodePatchContext.fillSecondaryButtons() {
    for (fill in findSecondaryFills()) {
        val method = mutableClassDefBy(fill.method.definingClass).methods.single {
            it.name == fill.method.name && it.parameterTypes.map(Any::toString) == fill.method.parameterTypes.map(Any::toString) &&
                it.returnType == fill.method.returnType
        }
        for (at in fill.loads.sortedDescending()) {
            val register = (method.implementation!!.instructions.elementAt(at) as OneRegisterInstruction).registerA
            method.addInstructions(
                at + 1,
                """
                    invoke-static { v${fill.context}, v$register }, $SECONDARY_BUTTON_FILL
                    move-result v$register
                """,
            )
        }
    }
}

/** Whether this writes [register], or a wide value over it. */
private fun Instruction.writes(register: Int): Boolean {
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return (opcode.setsRegister() && written == register) || (opcode.setsWideRegister() && written + 1 == register)
}
