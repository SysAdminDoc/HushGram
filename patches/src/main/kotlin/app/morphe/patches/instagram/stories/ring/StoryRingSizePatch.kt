/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.ring

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

private const val PATCH = "Story ring size"
internal const val RING_SIZE = "$EXTENSION_PACKAGE/stories/StoryRing;->size(F)F"

/**
 * What Instagram works a stories row item's size out from: no smaller than 66dp, no larger than
 * 100dp, and the screen's width shared among 3.75 items. The literals as Instagram loads them.
 */
internal val SMALLEST_ITEM = 66f.toRawBits()
internal val LARGEST_ITEM = 100f.toRawBits()
internal val ITEMS_A_SCREEN = 3.75.toRawBits()

/** How many places each sizing method settles the size: below the smallest, above the largest, and between. */
internal const val SETTLED_PER_METHOD = 3

/**
 * Draws the rings in the stories row at the top of Home at a share of their size. Included in the
 * default selection. Its switch starts on and the size starts as Instagram's own, so
 * nothing changes until a size is chosen.
 */
@Suppress("unused")
val storyRingSizePatch = bytecodePatch(
    name = "Story ring size",
    description = "Draws the rings in the stories row at the top of Home smaller, so more fit on screen, or " +
        "larger. Nothing changes until you pick a size. On by default. Turn it off in HushGram settings > " +
        "Stories.",
    default = true,
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyRingSize")
        scaleRingSizes(findRingSizes())
        enableStatus("storyRingSize")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * A method that works out the stories row item's size: its class, name and parameter types, and
 * the index of each double-to-float that settles the size in [register].
 */
internal class RingSizing(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val settled: List<Int>,
    val register: Int,
)

/**
 * Every method holding the three literals [SMALLEST_ITEM], [LARGEST_ITEM] and [ITEMS_A_SCREEN]. On
 * 449 there are three: the row's dimensions, which derive the ring, the picture and the spacing
 * from the size, and two static helpers other parts of the row ask for it. After the largest
 * size's literal, each turns the clamped double into a float in exactly [SETTLED_PER_METHOD]
 * places, all into one register, and that float is the size. A method settling it any other way
 * is an update this patch hasn't seen, so it fails rather than scale half the row.
 */
internal fun BytecodePatchContext.findRingSizes(): List<RingSizing> {
    val found = mutableListOf<RingSizing>()
    val loading = classesLoading(ITEMS_A_SCREEN).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in loading || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method -> method.ringSizing(classDef.type)?.let(found::add) }
    }
    if (found.isEmpty()) refuse("no method holds the stories row's 66dp, 100dp and 3.75 items a screen")
    return found
}

private fun Method.ringSizing(type: String): RingSizing? {
    val code = implementation?.instructions?.toList() ?: return null
    fun narrow(bits: Int) = code.indexOfFirst {
        it is NarrowLiteralInstruction && it.opcode in NARROW_CONSTS && it.narrowLiteral == bits
    }
    val smallest = narrow(SMALLEST_ITEM)
    val largest = narrow(LARGEST_ITEM)
    val items = code.any { it is WideLiteralInstruction && it.opcode in WIDE_CONSTS && it.wideLiteral == ITEMS_A_SCREEN }
    if (smallest < 0 || largest < 0 || !items) return null
    val where = "$type->$name"
    if (largest < smallest) refuse("$where loads its largest size before its smallest")
    val settled = code.withIndex().filter { it.index > largest && it.value.opcode == Opcode.DOUBLE_TO_FLOAT }.map { it.index }
    if (settled.size != SETTLED_PER_METHOD) {
        refuse("$where settles its size in ${settled.size} places, expected $SETTLED_PER_METHOD")
    }
    val registers = settled.map { (code[it] as TwoRegisterInstruction).registerA }.toSet()
    val register = registers.singleOrNull() ?: refuse("$where settles its size into ${registers.size} registers, expected one")
    return RingSizing(type, name, parameterTypes.map(CharSequence::toString), settled, register)
}

private val NARROW_CONSTS = setOf(Opcode.CONST, Opcode.CONST_HIGH16)
private val WIDE_CONSTS = setOf(Opcode.CONST_WIDE, Opcode.CONST_WIDE_HIGH16)

/**
 * Right after each place a method settles the size, passes it through [RING_SIZE]. Each hook goes
 * just after its double-to-float, ahead of any label on the next instruction, so only that path
 * runs it: in the row's dimensions the three paths meet at one label, and each is scaled once.
 */
internal fun BytecodePatchContext.scaleRingSizes(sizings: List<RingSizing>) {
    for (sizing in sizings) {
        val method = mutableClassDefBy(sizing.type).methods.single {
            it.name == sizing.name && it.parameterTypes.map(CharSequence::toString) == sizing.parameters
        }
        for (at in sizing.settled.sortedDescending()) {
            method.addInstructions(
                at + 1,
                """
                    invoke-static { v${sizing.register} }, $RING_SIZE
                    move-result v${sizing.register}
                """,
            )
        }
    }
}
