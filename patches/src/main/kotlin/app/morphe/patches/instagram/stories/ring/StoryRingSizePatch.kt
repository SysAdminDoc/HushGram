/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.ring

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

private const val PATCH = "Story ring size"
internal const val RING_SIZE = "$EXTENSION_PACKAGE/stories/StoryRing;->size(F)F"
internal const val RING_HIDES = "$EXTENSION_PACKAGE/stories/StoryRing;->hides(Landroid/view/View;)I"

/** The ring view's own draw, which the platform calls by this name and shape. */
internal const val RING_DRAW = "onDraw"
internal val RING_DRAW_PARAMETERS = listOf("Landroid/graphics/Canvas;")

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
 * Draws the rings in the stories row at the top of Home at a share of their size, the ring of a
 * story that hasn't been watched in a chosen color, and the rings at a share of their thickness.
 * Included in the default selection. Its switch starts on and the size, the color and the
 * thickness start as Instagram's own, so nothing changes until one is chosen.
 *
 * The color reaches the ring view's gradients ([colorRingGradients]) and, on Compose screens, the
 * default ring's colors ([colorComposeRing], #528), and on the chat list, the list of colors its rings'
 * brush is made from ([colorComposeRingBrush], #187). The thickness reaches every width the ring view
 * reads ([thickenRings], #528).
 */
@Suppress("unused")
val storyRingSizePatch = bytecodePatch(
    name = "Story ring size",
    description = "Draws the rings in the stories row at the top of Home smaller, so more fit on screen, or " +
        "larger, the ring of a story you haven't watched in a color you pick, and story rings thinner or thicker. " +
        "Another switch takes the rings off profile pictures everywhere else, like posts, comments, reels and " +
        "profiles. Nothing changes until you pick a size, a color or a thickness or turn that switch on. On by " +
        "default. Turn it off in HushGram settings > Stories.",
    default = true,
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyRingSize")
        // Everything is found before anything is changed, so a build missing one is left as it was.
        val sizings = findRingSizes()
        val gradients = findRingGradients()
        val draw = findRingDraw()
        val composeRing = findComposeRing()
        val brush = findRingBrush()
        val widths = findRingWidthReads()
        scaleRingSizes(sizings)
        // The widths go in by index, before the color hook moves the builder's code along and
        // before the early return goes in at the top of the ring's draw.
        thickenRings(widths)
        colorRingGradients(gradients)
        colorComposeRing(composeRing, RING_DEFAULT_COLORS)
        brush?.let { colorComposeRingBrush(it, RING_BRUSH_COLORS) }
        hideRingsOutsideTheRow(draw)
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
 * The ring view's own `onDraw(Canvas)`, where Hide rings outside the stories row returns before
 * anything is drawn (#719). Every ring around a profile picture is that view, in the stories row,
 * on a post's header, in comments, on reels and on profiles, and the picture is a view of its own.
 * Proved before anything changes: the ring declares its own draw, returning nothing, with a local
 * for the answer, and nothing jumps back to its first instruction, where the early return goes.
 */
internal fun BytecodePatchContext.findRingDraw(): Method {
    val spinner = classDefByOrNull(GRADIENT_SPINNER) ?: refuse("the build has no $GRADIENT_SPINNER")
    val draws = spinner.methods.filter {
        it.name == RING_DRAW && it.parameterTypes.map(CharSequence::toString) == RING_DRAW_PARAMETERS
    }
    val draw = draws.singleOrNull() ?: refuse("expected one $RING_DRAW(Canvas) in $GRADIENT_SPINNER, found ${draws.size}")
    val where = "$GRADIENT_SPINNER->$RING_DRAW"
    if (AccessFlags.STATIC.isSet(draw.accessFlags) || draw.returnType != "V") refuse("$where isn't the view's own draw")
    if (draw.implementation == null) refuse("$where has no body")
    if (draw.localRegisterCount() < 1) refuse("$where has no local register for the answer")
    if (0 in draw.jumpTargets()) refuse("something jumps back to the first instruction of $where")
    return draw
}

/**
 * The brush the chat list's story rings are made from ([findComposeRingBrush], #187), which never sees
 * the default ring's colors. None, with a warning, when it can't be found: Ring color then leaves only
 * the chat list's rings in Instagram's gradient, and the rest of the patch goes in.
 */
internal fun BytecodePatchContext.findRingBrush(): ComposeRingBrush? =
    try {
        findComposeRingBrush()
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. $PATCH goes in without coloring the rings on the chat list.")
        null
    }

/** The ring's draw returns first thing while [RING_HIDES] answers yes for that ring, so nothing of it is drawn. */
internal fun BytecodePatchContext.hideRingsOutsideTheRow(draw: Method) {
    val method = mutableClassDefBy(GRADIENT_SPINNER).methods.single {
        it.name == draw.name && it.parameterTypes.map(CharSequence::toString) == RING_DRAW_PARAMETERS
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p0 }, $RING_HIDES
            move-result v0
            if-eqz v0, :draw
            return-void
        """,
        ExternalLabel("draw", method.getInstruction(0)),
    )
}

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
