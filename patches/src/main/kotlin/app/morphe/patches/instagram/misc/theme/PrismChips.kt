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
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Accent color"

private const val CONTEXT = "Landroid/content/Context;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val COLORS = "Landroid/content/res/ColorStateList;"

/** The extension's answer to the fill a prism chip's background reads, for each of its states (#187). */
internal const val PRISM_FILL = "$ACCENT_THEME->prismFill(${CONTEXT}$COLORS)$COLORS"

/** The extension's answer to the color a prism chip's background outlines it in (#187). */
internal const val PRISM_STROKE = "$ACCENT_THEME->prismStroke(${CONTEXT}I)I"

/** Hands the extension a prism chip once it's built or selected, for its label's colors (#187). */
internal const val PRISM_CHIP = "$ACCENT_THEME->prismChip(Landroid/view/View;)V"

/** Instagram's chip, the filters under a search among its uses. It keeps its name. */
internal const val IGDS_PRISM_CHIP = "Lcom/instagram/igds/components/segmentedpills/IgdsPrismChip;"

/** A color the background reads at [at] (the move-result) in its constructor, into [color], with the Context in [context]. */
internal class ChipColorRead(val at: Int, val context: Int, val color: Int)

/** Where [method], the chip background's constructor, reads its [fill] and its [stroke]. */
internal class PrismBackground(val method: MutableMethod, val fill: ChipColorRead, val stroke: ChipColorRead)

/**
 * The drawable [IGDS_PRISM_CHIP] draws its pill with (`LX/0BOO` on 386314286), the one class extending
 * Drawable the chip makes, and in its constructor the one read of a ColorStateList from a Context and
 * an int it keeps in a field of its own, its fill for each state, and the one read of an int from a
 * Context and an int it keeps in an int field, its outline's color. The search tab's filters take those
 * from Instagram's theme, so they kept its gray with Filters in the accent color on (#187). Answers null with a warning when there's no such class, constructor or read,
 * a register past v15 holds a Context or a color, or something jumps to right after a read. Refuses
 * when the extension lacks [PRISM_FILL] or [PRISM_STROKE].
 */
internal fun BytecodePatchContext.findPrismBackground(): PrismBackground? {
    fun skip(why: String): PrismBackground? {
        patchLog.warning("$PATCH: $why, so the chips under a search keep Instagram's colors")
        return null
    }
    val chip = classDefByOrNull(IGDS_PRISM_CHIP) ?: return skip("no $IGDS_PRISM_CHIP")
    val made = chip.methods.flatMap { it.code() }.filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()
        .filter { classDefByOrNull(it)?.superclass == DRAWABLE }
    val type = made.singleOrNull() ?: return skip("$IGDS_PRISM_CHIP makes ${made.size} drawables of its own, not one")
    val constructors = classDefByOrNull(type)?.methods?.filter { it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == CONTEXT }.orEmpty()
    val constructor = constructors.singleOrNull() ?: return skip("$type has ${constructors.size} constructors taking a Context, not one")
    val code = constructor.code()
    val fills = code.indices.filter { code.keepsRead(it, type, COLORS, Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT) }
    val strokes = code.indices.filter { code.keepsRead(it, type, "I", Opcode.MOVE_RESULT, Opcode.IPUT) }
    val fill = fills.singleOrNull() ?: return skip("${constructor.where()} keeps ${fills.size} fills, not one")
    val stroke = strokes.singleOrNull() ?: return skip("${constructor.where()} keeps ${strokes.size} outline colors, not one")
    val targets = constructor.jumpTargets()
    val reads = listOf(fill, stroke).map { at ->
        val context = (code[at] as FiveRegisterInstruction).registerC
        val color = (code[at + 1] as OneRegisterInstruction).registerA
        if (context > 15 || color > 15 || context == color) return skip("${constructor.where()} keeps a Context or color past v15")
        if (at + 2 in targets) return skip("${constructor.where()} jumps to right after a color's read")
        ChipColorRead(at + 1, context, color)
    }
    requireHook(PRISM_FILL)
    requireHook(PRISM_STROKE)
    val mutable = mutableClassDefBy(type).methods.single { it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == CONTEXT }
    return PrismBackground(mutable, reads[0], reads[1])
}

/**
 * Whether [at] calls a method answering [returns] from a Context and an int, takes the answer with
 * [move] and stores it with [store] into a field of [type] of that type.
 */
private fun List<Instruction>.keepsRead(at: Int, type: String, returns: String, move: Opcode, store: Opcode): Boolean {
    val call = this[at].called() ?: return false
    if (call.returnType != returns || call.parameterTypes.map(Any::toString) != listOf(CONTEXT, "I")) return false
    if (this[at].opcode != Opcode.INVOKE_STATIC) return false
    val taken = getOrNull(at + 1)?.takeIf { it.opcode == move } as? OneRegisterInstruction ?: return false
    val kept = getOrNull(at + 2)?.takeIf { it.opcode == store } ?: return false
    val field = (kept as ReferenceInstruction).reference as FieldReference
    return field.definingClass == type && field.type == returns && (kept as TwoRegisterInstruction).registerA == taken.registerA
}

/**
 * Hands the fill and the outline color [findPrismBackground]'s constructor reads to the extension
 * right after each read, with the Context it was read from, and keeps the answers instead (#187).
 */
internal fun BytecodePatchContext.tintPrismBackgrounds() {
    val found = findPrismBackground() ?: return
    val hooks = listOf(Triple(found.fill, PRISM_FILL, "move-result-object"), Triple(found.stroke, PRISM_STROKE, "move-result"))
    for ((read, hook, move) in hooks.sortedByDescending { it.first.at }) {
        found.method.addInstructions(
            read.at + 1,
            """
                invoke-static { v${read.context}, v${read.color} }, $hook
                $move v${read.color}
            """,
        )
    }
}

/**
 * The methods of [IGDS_PRISM_CHIP] that hand the chip to [PRISM_CHIP] before each return: each
 * constructor that builds the chip rather than calling another of its own, and setSelected(boolean),
 * which sets the label's color again on some chips. Answers an empty list with a warning when the chip
 * isn't there. Refuses when the extension lacks the hook, a method never returns or `this` is
 * written over before a return.
 */
internal fun BytecodePatchContext.findPrismChipLabels(): List<MutableMethod> {
    val chip = classDefByOrNull(IGDS_PRISM_CHIP) ?: run {
        patchLog.warning("$PATCH: no $IGDS_PRISM_CHIP, so the chips under a search keep Instagram's label colors")
        return emptyList()
    }
    val methods = chip.methods.filter { it.isChipBuilder() || it.isSelectedSetter() }
    if (methods.none { it.isSelectedSetter() } || methods.none { it.isChipBuilder() }) {
        patchLog.warning("$PATCH: $IGDS_PRISM_CHIP has no constructor or setSelected of its own, so its labels keep Instagram's colors")
        return emptyList()
    }
    requireHook(PRISM_CHIP)
    for (method in methods) {
        val returns = method.code().indices.filter { method.code()[it].opcode == Opcode.RETURN_VOID }
        if (returns.isEmpty()) refuse("${method.where()} never returns")
        method.requireThisIntact(PATCH, returns)
    }
    val mutable = mutableClassDefBy(IGDS_PRISM_CHIP).methods
    return methods.map { method ->
        mutable.single { it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) }
    }
}

private fun Method.isChipBuilder(): Boolean = name == "<init>" && code().none { instruction ->
    instruction.called()?.let { it.definingClass == IGDS_PRISM_CHIP && it.name == "<init>" } == true
}

private fun Method.isSelectedSetter(): Boolean =
    name == "setSelected" && parameterTypes.map(Any::toString) == listOf("Z") && returnType == "V" && !AccessFlags.STATIC.isSet(accessFlags)

/** Hands the chip to the extension before each return of [findPrismChipLabels]' methods, at the return's own label (#187). */
internal fun BytecodePatchContext.tintPrismChipLabels() {
    for (method in findPrismChipLabels()) {
        for (at in method.code().indices.filter { method.code()[it].opcode == Opcode.RETURN_VOID }.reversed()) {
            method.addInstructionsAtControlFlowLabel(at, "invoke-static/range { p0 .. p0 }, $PRISM_CHIP")
        }
    }
}

/** Hands the extension the view carrying the outline of the chip with only an icon before Explore's chips (#187). */
internal const val EXPLORE_PILL = "$ACCENT_THEME->explorePill(Landroid/view/View;)V"

/**
 * Instagram 457's id/settings_background, the outlined view of the chip with only an icon that leads
 * Explore's chips (its layout is explore_carrera_settings_pill_view_holder), the same in all four 457
 * builds (aapt2).
 */
internal const val EXPLORE_SETTINGS_PILL = 0x7f0b3b38

/** Where Explore's chip list finds its icon chip's outlined view: the move-result at [at] taking it into [view]. */
internal class ExplorePillFind(val method: MutableMethod, val at: Int, val view: Int)

/**
 * The one place loading [EXPLORE_SETTINGS_PILL] that hands it straight to a call answering a View and
 * takes the answer, where Explore's chip list makes the holder of its icon chip (`LX/0CUr;->A0c` on
 * 386314286). The chip's outline comes from its layout's background, not from the prism chips' drawable,
 * so [tintPrismBackgrounds] never reached it and it stayed gray (#187). Answers null with a warning
 * when there isn't one such place or something jumps to right after it. Refuses when the extension
 * lacks [EXPLORE_PILL].
 */
internal fun BytecodePatchContext.findExplorePill(): ExplorePillFind? {
    fun skip(why: String): ExplorePillFind? {
        patchLog.warning("$PATCH: $why, so the icon chip before Explore's chips keeps Instagram's outline")
        return null
    }
    val sites = classesLoading(EXPLORE_SETTINGS_PILL.toLong()).distinctBy { it.type }.flatMap { it.methods }.flatMap { method ->
        method.code().let { code -> code.indices.filter { code.findsPill(it) }.map { method to it + 2 } }
    }
    val (finder, at) = sites.singleOrNull() ?: return skip("${sites.size} places find the icon chip's outline, not one")
    if (at + 1 in finder.jumpTargets()) return skip("${finder.where()} jumps to right after finding the icon chip's outline")
    requireHook(EXPLORE_PILL)
    val mutable = mutableClassDefBy(finder.definingClass).methods.single {
        it.name == finder.name && it.parameterTypes.map(Any::toString) == finder.parameterTypes.map(Any::toString) && it.returnType == finder.returnType
    }
    return ExplorePillFind(mutable, at, (finder.code()[at] as OneRegisterInstruction).registerA)
}

/** Whether [at] loads [EXPLORE_SETTINGS_PILL], the next instruction hands it to a call answering a View and the one after takes the answer. */
private fun List<Instruction>.findsPill(at: Int): Boolean {
    if ((this[at] as? NarrowLiteralInstruction)?.narrowLiteral != EXPLORE_SETTINGS_PILL) return false
    val id = (this[at] as OneRegisterInstruction).registerA
    val call = getOrNull(at + 1) ?: return false
    val called = call.called() ?: return false
    if (called.returnType != "Landroid/view/View;") return false
    val handed = when (call) {
        is RegisterRangeInstruction -> id in call.startRegister until call.startRegister + call.registerCount
        is FiveRegisterInstruction -> id in listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
        else -> false
    }
    return handed && getOrNull(at + 2)?.opcode == Opcode.MOVE_RESULT_OBJECT
}

/** Hands [findExplorePill]'s view to the extension right after it's found, which outlines it like the chips beside it (#187). */
internal fun BytecodePatchContext.tintExplorePill() {
    val found = findExplorePill() ?: return
    found.method.addInstructions(found.at + 1, "invoke-static/range { v${found.view} .. v${found.view} }, $EXPLORE_PILL")
}

/** The hooks giving Instagram's prism chips, and the icon chip before Explore's, the picked color with Filters in the accent color on (#187). */
internal fun BytecodePatchContext.tintPrismChips() {
    tintPrismBackgrounds()
    tintPrismChipLabels()
    tintExplorePill()
}

private fun BytecodePatchContext.requireHook(hook: String) {
    val present = classDefByOrNull(ACCENT_THEME)?.methods?.any {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } == true
    if (!present) refuse("the extension has no public static $hook")
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.where(): String = "$definingClass->$name"
