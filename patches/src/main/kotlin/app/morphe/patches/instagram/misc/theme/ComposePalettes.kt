/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Accent color"

internal const val SWAP_COMPOSE = "$ACCENT_COLOR->composeColor(J)J"

/** The blues a palette has to store for the patch to know it found 451's: the dark link and Prism's indigo fill (#8). */
internal val NEEDED_PALETTE_BLUES = listOf(0x85a1ff, 0x4a5df9)

private val WIDE_CONSTS = setOf(Opcode.CONST_WIDE, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE_16)

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** An accent blue a Compose palette's set-up stores: in [method], the `sput-wide` at [store] of the pair from [register]. */
internal class PaletteStore(val method: Method, val store: Int, val register: Int, val argb: Int)

/**
 * Every accent blue Instagram's Compose palettes store as they're set up. Compose screens such as
 * the Activity tab and a profile's bio take their colors from palettes written into the code, not
 * from the theme, so no theme read or style reaches them (#8). A palette's static initializer writes
 * each color to a static long: its ARGB as a `const-wide`, made a Compose color by shifting it up 32
 * bits (`shl-long/2addr` by a register last set to 32) or by a static (J)J, then `sput-wide` to a
 * field of its own class. The classes read are the ones the literal index names for one of the
 * blues at full alpha, and in each every blue stored counts, see-through ones too.
 *
 * 451 keeps them in BasePrismColors, BasePrismColorsV2 and a holder of the dark link alone. Refuses
 * unless [NEEDED_PALETTE_BLUES] are among them, or without the extension's hook.
 */
internal fun BytecodePatchContext.findPaletteStores(): List<PaletteStore> {
    val classes = ACCENT_BLUES.keys.flatMap { classesLoading(0xff000000L or it.toLong()) }.distinctBy { it.type }
    val stores = classes.flatMap { classDef -> classDef.methods.filter { it.name == "<clinit>" }.flatMap { it.paletteStores() } }
    for (needed in NEEDED_PALETTE_BLUES) {
        if (stores.none { it.argb and 0xffffff == needed }) refuse("no Compose palette stores #%06x".format(needed))
    }
    if (classDefByOrNull(ACCENT_COLOR)?.methods?.any {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == SWAP_COMPOSE &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } != true
    ) {
        refuse("the extension has no public static $SWAP_COMPOSE")
    }
    return stores
}

/**
 * Each blue [stores] names goes through AccentColor.composeColor right before it's stored, at the
 * store's own label, so the palette holds the picked color's shade from the start. Each palette is
 * read again as it stands now, in case another patch changed it after it was found.
 */
internal fun BytecodePatchContext.swapComposePalettes(stores: List<PaletteStore> = findPaletteStores()) {
    for (type in stores.map { it.method.definingClass }.distinct()) {
        val setUp = mutableClassDefBy(type).methods.single { it.name == "<clinit>" }
        val now = setUp.paletteStores()
        if (now.isEmpty()) refuse("$type's set-up no longer stores a blue")
        for (store in now.asReversed()) {
            setUp.addInstructionsAtControlFlowLabel(
                store.store,
                """
                    invoke-static/range { v${store.register} .. v${store.register + 1} }, $SWAP_COMPOSE
                    move-result-wide v${store.register}
                """,
            )
        }
    }
}

/** The accent blues this method stores into its own class's static longs as Compose colors, in order. */
internal fun Method.paletteStores(): List<PaletteStore> {
    val code = implementation?.instructions?.toList().orEmpty()
    val stores = ArrayList<PaletteStore>()
    for ((at, instruction) in code.withIndex()) {
        if (instruction.opcode !in WIDE_CONSTS) continue
        val value = (instruction as WideLiteralInstruction).wideLiteral
        if (value ushr 32 != 0L) continue
        val argb = value.toInt()
        if (argb ushr 24 == 0 || ACCENT_BLUES[argb and 0xffffff] == null) continue
        val register = (instruction as OneRegisterInstruction).registerA
        val next = code.getOrNull(at + 1) ?: continue
        val store = when {
            next.opcode == Opcode.SHL_LONG_2ADDR && (next as TwoRegisterInstruction).registerA == register &&
                next.registerB != register && next.registerB != register + 1 &&
                code.lastWriteBefore(at, next.registerB)?.let { it is NarrowLiteralInstruction && it.narrowLiteral == 32 } == true -> at + 2
            next.convertsPair(register) && code.getOrNull(at + 2)?.let {
                it.opcode == Opcode.MOVE_RESULT_WIDE && (it as OneRegisterInstruction).registerA == register
            } == true -> at + 3
            else -> continue
        }
        val put = code.getOrNull(store) ?: continue
        if (put.opcode != Opcode.SPUT_WIDE || (put as OneRegisterInstruction).registerA != register) continue
        val field = (put as ReferenceInstruction).reference as FieldReference
        if (field.definingClass != definingClass || field.type != "J") continue
        stores += PaletteStore(this, store, register, argb)
    }
    return stores
}

/** Whether this is `invoke-static {vN, vN+1}` of a static (J)J, the pair being [register]'s. */
private fun Instruction.convertsPair(register: Int): Boolean {
    if (opcode != Opcode.INVOKE_STATIC) return false
    val called = (this as ReferenceInstruction).reference as MethodReference
    if (called.parameterTypes.map(Any::toString) != listOf("J") || called.returnType != "J") return false
    val passed = this as FiveRegisterInstruction
    return passed.registerCount == 2 && passed.registerC == register && passed.registerD == register + 1
}

/** The last instruction before [at] writing [register], as one register or half of a pair, or null for none. */
private fun List<Instruction>.lastWriteBefore(at: Int, register: Int): Instruction? =
    subList(0, at).lastOrNull { instruction ->
        val written = (instruction as? OneRegisterInstruction)?.registerA
        instruction.opcode.setsRegister() && written != null &&
            (written == register || instruction.opcode.setsWideRegister() && written + 1 == register)
    }
