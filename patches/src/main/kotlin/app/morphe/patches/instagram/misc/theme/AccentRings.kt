/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.stories.ring.ComposeRing
import app.morphe.patches.instagram.stories.ring.ComposeRingBrush
import app.morphe.patches.instagram.stories.ring.GRADIENT_SPINNER
import app.morphe.patches.instagram.stories.ring.GroupRing
import app.morphe.patches.instagram.stories.ring.RING_COLORS
import app.morphe.patches.instagram.stories.ring.RingGradient
import app.morphe.patches.instagram.stories.ring.colorComposeRing
import app.morphe.patches.instagram.stories.ring.colorComposeRingBrush
import app.morphe.patches.instagram.stories.ring.colorGroupRing
import app.morphe.patches.instagram.stories.ring.colorRingGradients
import app.morphe.patches.instagram.stories.ring.findComposeRing
import app.morphe.patches.instagram.stories.ring.findComposeRingBrush
import app.morphe.patches.instagram.stories.ring.findGroupRing
import app.morphe.patches.instagram.stories.ring.findRingGradients
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val ACCENT_RINGS = "$EXTENSION_PACKAGE/misc/AccentRings;"
internal const val ACCENT_RING_COLORS = "$ACCENT_RINGS->colors([I[F)[I"
internal const val ACCENT_DEFAULT_RING = "$ACCENT_RINGS->composeColors([I)[I"
internal const val ACCENT_SEEN_RING = "$ACCENT_RINGS->seen(I)I"
internal const val ACCENT_RING_BRUSH = "$ACCENT_RINGS->composeBrush(Ljava/util/List;)Ljava/util/List;"
internal const val ACCENT_COMPOSE_SEEN = "$ACCENT_RINGS->composeSeen(J)J"
internal const val ACCENT_GROUP_RING = "$ACCENT_RINGS->composeStop(FJ)J"
internal const val ACCENT_GROUP_BOXED_RING = "$ACCENT_RINGS->composeBoxedStop(Ljava/lang/Object;J)J"

/** What the chat list's ring brush takes, a list of Compose colors. */
private const val BRUSH_TAKES = "Ljava/util/List;"

/** The ring view's setter for the color a seen story's ring is drawn in, under the name Instagram keeps for it. */
internal const val INACTIVE_SETTER = "setInactiveColour"

/**
 * Where Story rings in the accent color reaches the rings the theme doesn't: the ring view's gradients,
 * the Compose default ring, the chat list's ring brush, and the register the ring view's
 * [INACTIVE_SETTER] gets its color in.
 */
internal class AccentRingHooks(
    val gradients: List<RingGradient>,
    val composeRing: ComposeRing,
    val brush: ComposeRingBrush,
    val seenRegister: Int,
    val composeSeen: List<ComposeSeenRing> = emptyList(),
    val group: GroupRing? = null,
)

/**
 * Finds the rings Story rings in the accent color has to reach outside the theme (#528). The ring
 * style [applyAccentTheme] lays on each theme reaches only the rings that read their colors from it.
 * Instagram's default ring reads its colors once, from a context no accent theme is laid on, and
 * every ring built from it (on profiles, posts and chats, and the Compose screens' rings) kept
 * Instagram's gradient. They are the same places Story ring size colors, so the same finders read
 * them, and refuse in this patch's name. A seen story's ring is drawn in one gray instead, which
 * every ring view is handed through its [INACTIVE_SETTER] (#187), from its constructor and from the
 * screens that pick a gray of their own. That setter takes one int and is the ring view's only one by
 * that name, and the hook goes first in it, so nothing may jump back to its first instruction. The
 * chat list's rings take Compose's own yellow, orange, pink and purple and make their brush from them
 * in one static the finder for it reads (#683). Those rows draw a seen story's ring themselves, in a
 * gray from Compose's palette ([findComposeSeenRings]); a build where none is found goes in without
 * them, with a warning. So does one without the spinning ring around a group of profile pictures
 * ([findGroupRing]), which pairs Compose's yellow, orange, pink and purple with the stops itself.
 */
internal fun BytecodePatchContext.findAccentRings(): AccentRingHooks {
    val declared = classDefByOrNull(ACCENT_RINGS)?.methods?.toList().orEmpty()
        .filter { AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    val hooks = listOf(
        ACCENT_RING_COLORS, ACCENT_DEFAULT_RING, ACCENT_SEEN_RING, ACCENT_RING_BRUSH, ACCENT_COMPOSE_SEEN, ACCENT_GROUP_RING,
        ACCENT_GROUP_BOXED_RING,
    )
    for (hook in hooks) {
        if (hook !in declared) throw PatchException("Accent color: the extension has no public static $hook")
    }
    val gradients = findRingGradients("Accent color")
    val composeRing = findComposeRing("Accent color")
    val brush = findComposeRingBrush("Accent color")
    val setters = classDefByOrNull(GRADIENT_SPINNER)?.methods?.filter { it.isInactiveSetter() }.orEmpty()
    val setter = setters.singleOrNull()
        ?: throw PatchException("Accent color: expected one $INACTIVE_SETTER(I)V in $GRADIENT_SPINNER, found ${setters.size}")
    if (0 in setter.jumpTargets()) {
        throw PatchException("Accent color: something jumps back to the first instruction of $GRADIENT_SPINNER->$INACTIVE_SETTER")
    }
    val composeSeen = findComposeSeenRings(brush)
    if (composeSeen.isEmpty()) {
        patchLog.warning("Accent color: no chat list row reads a seen story ring's color. It goes in without those rings.")
    }
    val group = try {
        findGroupRing("Accent color")
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. It goes in without the ring around a group of pictures.")
        null
    }
    return AccentRingHooks(gradients, composeRing, brush, setter.parameterRegisterNumber(0), composeSeen, group)
}

/** A chat list row that draws a seen story's ring in a color from Compose's palette: its method, by class, name and parameters. */
internal class ComposeSeenRing(val type: String, val name: String, val parameters: List<String>)

/**
 * Finds where the chat list's rows read the color they draw a seen story's ring in (#187). Those rows
 * draw their rings themselves, apart from the ring view, so its [INACTIVE_SETTER] never sees that
 * gray. Each makes an unseen story's brush with [brush] and keeps which ring it draws as a state: a
 * seen story's is one object kept in a static field of its own type, and when the row's state is that
 * one it reads a color from Compose's palette and makes a border of it right away (`LX/0J2X;` and
 * `LX/05SA;->A1B` in `0G9f.A06` and `0KEB.A02` on 457). Every method calling [brush] that reads one
 * such color is answered, unless something jumps onto the instruction after the read, where the hook
 * goes.
 */
internal fun BytecodePatchContext.findComposeSeenRings(brush: ComposeRingBrush): List<ComposeSeenRing> =
    classesCalling(brush.type, brush.name, listOf(BRUSH_TAKES)).flatMap { classDef ->
        classDef.methods.filter { it.callsBrush(brush) && it.seenRingRead() != null }
            .map { ComposeSeenRing(classDef.type, it.name, it.parameterTypes.map(CharSequence::toString)) }
    }

private fun Method.callsBrush(brush: ComposeRingBrush) = implementation?.instructions?.any { instruction ->
    instruction.opcode == Opcode.INVOKE_STATIC && ((instruction as ReferenceInstruction).reference as MethodReference).let {
        it.definingClass == brush.type && it.name == brush.name && it.parameterTypes.map(CharSequence::toString) == listOf(BRUSH_TAKES)
    }
} == true

/**
 * The method's one read of a seen ring's color: the index of its `iget-wide` and the register the
 * color lands in. Null for none, for more than one, and for one something jumps past.
 */
internal fun Method.seenRingRead(): Pair<Int, Int>? {
    val flow = try {
        ControlFlow.of(this)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val code = flow.instructions
    val kept = code.mapNotNullTo(HashSet()) { instruction ->
        if (instruction.opcode != Opcode.SGET_OBJECT) return@mapNotNullTo null
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        field.definingClass.takeIf { it == field.type }
    }
    val reads = code.indices.mapNotNull { at ->
        val check = code[at]
        if (check.opcode != Opcode.INSTANCE_OF || at + 1 >= code.size) return@mapNotNull null
        if (((check as ReferenceInstruction).reference as TypeReference).type !in kept) return@mapNotNull null
        val branch = code[at + 1]
        if (branch.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != (check as OneRegisterInstruction).registerA) {
            return@mapNotNull null
        }
        val end = flow.normal[at + 1].firstOrNull()?.takeIf { it > at + 1 } ?: return@mapNotNull null
        (at + 2 until end).singleOrNull { code.readsBorderColor(it) }
    }
    val read = reads.singleOrNull() ?: return null
    if (read + 1 in jumpTargets()) return null
    return read to (code[read] as OneRegisterInstruction).registerA
}

/**
 * Whether the instruction at [at] reads a long from the palette the `invoke-static` two before it
 * answers, which one of the next three instructions makes a modifier of.
 */
private fun List<Instruction>.readsBorderColor(at: Int): Boolean {
    val read = this[at]
    if (at < 2 || read.opcode != Opcode.IGET_WIDE) return false
    val field = (read as ReferenceInstruction).reference as FieldReference
    val result = this[at - 1]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || (result as OneRegisterInstruction).registerA != (read as TwoRegisterInstruction).registerB) {
        return false
    }
    val palette = this[at - 2]
    if (palette.opcode != Opcode.INVOKE_STATIC || ((palette as ReferenceInstruction).reference as MethodReference).returnType != field.definingClass) {
        return false
    }
    val color = (read as OneRegisterInstruction).registerA
    return (at + 1..minOf(at + 3, lastIndex)).any { this[it].makesModifierOf(color) }
}

/** Whether this is a static answering the class its first parameter is, whose last parameter is the long in [color]. */
private fun Instruction.makesModifierOf(color: Int): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val call = (this as ReferenceInstruction).reference as MethodReference
    val types = call.parameterTypes.map(CharSequence::toString)
    if (types.size < 2 || types.last() != "J" || types.first() != call.returnType) return false
    val registers = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> return false
    }
    return registers.size >= 2 && registers[registers.size - 2] == color
}

/** Passes the color a chat list row draws a seen story's ring in through [ACCENT_COMPOSE_SEEN], right after the row reads it. */
internal fun BytecodePatchContext.colorComposeSeenRing(ring: ComposeSeenRing) {
    val method = mutableClassDefBy(ring.type).methods.single {
        it.name == ring.name && it.parameterTypes.map(CharSequence::toString) == ring.parameters
    }
    val (read, register) = method.seenRingRead()
        ?: throw PatchException("Accent color: ${ring.type}->${ring.name} no longer reads one seen story ring's color")
    method.addInstructions(
        read + 1,
        """
            invoke-static/range { v$register .. v${register + 1} }, $ACCENT_COMPOSE_SEEN
            move-result-wide v$register
        """,
    )
}

private fun Method.isInactiveSetter() = name == INACTIVE_SETTER && returnType == "V" && implementation != null &&
    !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.map(CharSequence::toString) == listOf("I")

/**
 * Hands the ring view's gradients and the Compose default ring's colors to the extension's accent
 * rings. In the ring view the hook goes behind Story ring size's own guard when that is in already,
 * so its guard stays first in either order and a color picked under Ring color is what this one is
 * given, and leaves alone. The seen ring's gray goes to the extension first thing in
 * [INACTIVE_SETTER], which then keeps the answer, and so do the chat list's ring colors first thing
 * in their brush. Story ring size hooks that brush too, and whichever goes in later runs first, which
 * draws the same ring either way: this one answers a picked Ring color back, and Ring color answers
 * back what it's given while none is picked. The chat list rows' seen ring color goes to the
 * extension right after each row reads it, and each color of the group ring right before it's paired
 * with its stop. Whichever patch goes in later puts its hook after the other's, right before the
 * pairing, which draws the same ring either way: this one gives way to a picked Ring color, and Ring
 * color answers back what it's given while none is picked.
 */
internal fun BytecodePatchContext.colorAccentRings(hooks: AccentRingHooks) {
    colorRingGradients(hooks.gradients, ACCENT_RING_COLORS, behind = RING_COLORS)
    colorComposeRing(hooks.composeRing, ACCENT_DEFAULT_RING)
    colorComposeRingBrush(hooks.brush, ACCENT_RING_BRUSH)
    val register = hooks.seenRegister
    mutableClassDefBy(GRADIENT_SPINNER).methods.single { it.isInactiveSetter() }.addInstructions(
        0,
        """
            invoke-static/range { v$register .. v$register }, $ACCENT_SEEN_RING
            move-result v$register
        """,
    )
    hooks.composeSeen.forEach { colorComposeSeenRing(it) }
    hooks.group?.let { colorGroupRing(it, ACCENT_GROUP_RING, ACCENT_GROUP_BOXED_RING, "Accent color") }
}
