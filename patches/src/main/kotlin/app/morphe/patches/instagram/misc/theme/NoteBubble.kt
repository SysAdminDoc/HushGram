/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val NOTE_BUBBLE = "$EXTENSION_PACKAGE/misc/NoteBubble;->color(I)I"

internal const val NOTE_CARD = "$EXTENSION_PACKAGE/misc/NoteBubble;->card(J)J"

/**
 * How the Compose trace of the card a note sits in on the notes row over the inbox starts. The
 * file's line number follows and changes from build to build.
 */
internal const val NOTE_CARD_TRACE = "com.instagram.direct.inbox.notes.tray.compose.bubble.NotesTrayBubbleCard ("

/** A note's own theme, which the card takes as an argument. */
internal const val NOTE_CUSTOM_THEME = "Lcom/instagram/direct/inbox/notes/models/domain/notecustomtheme/NoteCustomTheme;"

private val WIDE_MOVES = setOf(Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16)

/** The view a note's bubble is, over the Direct inbox. */
internal const val NOTE_BUBBLE_VIEW = "Lcom/instagram/direct/inbox/notes/ui/NoteBubbleView;"

/** Where the bubble takes its fill: the bubble's paint and each of its cards, for every kind of note. */
internal const val NOTE_BUBBLE_SETTER = "setBubbleBackgroundColor"

private const val NOTE_PATCH = "Pure black dark mode"

/**
 * Sends the color [NOTE_BUBBLE_SETTER] is handed through the hook first, so the dark gray Instagram
 * fills a note's bubble with at night comes in black (#543). The constructor and every kind of note
 * (text, music, polls, a custom theme) fill the bubble through it, and the hook keeps a note's own
 * color and the day theme's white. A build without the setter fails the patch.
 */
internal fun BytecodePatchContext.blackenNoteBubbles() {
    val view = classDefByOrNull(NOTE_BUBBLE_VIEW)
        ?: throw PatchException("$NOTE_PATCH: $NOTE_BUBBLE_VIEW is gone, so a note's bubble would stay gray")
    val setter = view.methods.singleOrNull { method ->
        method.name == NOTE_BUBBLE_SETTER && method.returnType == "V" &&
            method.parameterTypes.map(Any::toString) == listOf("I") &&
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null
    } ?: throw PatchException("$NOTE_PATCH: $NOTE_BUBBLE_VIEW no longer has $NOTE_BUBBLE_SETTER(I)V")
    val method = mutableClassDefBy(view.type).methods.single {
        it.name == setter.name && it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == "V"
    }
    // The color is the last register: p1 of an instance method taking one int.
    val color = method.implementation!!.registerCount - 1
    method.addInstructions(
        0,
        """
            invoke-static/range { v$color .. v$color }, $NOTE_BUBBLE
            move-result v$color
        """,
    )
}

/** Whether [method] starts the Compose trace of the note card, [NOTE_CARD_TRACE]. */
internal fun tracesNoteCard(method: Method): Boolean = method.implementation?.instructions?.any { instruction ->
    (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((instruction as ReferenceInstruction).reference as StringReference).string.startsWith(NOTE_CARD_TRACE)
} == true

/**
 * Where the note card has its fill in one register on every path, and that register, or null.
 * The fill is the one register more than one color read is moved into: the color the card is
 * handed, or else the theme's. A note's own theme color lands there too. The hook goes after the
 * last write to it that comes before its first read, where the paths meet.
 */
internal fun noteCardFill(code: List<Instruction>): Pair<Int, Int>? {
    val moved = code.indices.filter { at ->
        val read = code[at]
        val move = code.getOrNull(at + 1) ?: return@filter false
        read.opcode == Opcode.IGET_WIDE && move.opcode in WIDE_MOVES &&
            (move as TwoRegisterInstruction).registerB == (read as TwoRegisterInstruction).registerA
    }.map { (code[it + 1] as OneRegisterInstruction).registerA }
    val fill = moved.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.singleOrNull() ?: return null
    val read = code.indexOfFirst { it.opcode in WIDE_MOVES && (it as TwoRegisterInstruction).registerB == fill }
    if (read < 0) return null
    val write = (0 until read).lastOrNull { at ->
        (code[at].opcode in WIDE_MOVES || code[at].opcode == Opcode.MOVE_RESULT_WIDE) &&
            (code[at] as OneRegisterInstruction).registerA == fill
    } ?: return null
    return write + 1 to fill
}

/**
 * Sends the fill of each note on the notes row over the inbox through the hook, so its dark gray
 * comes in black (#543). The row is Compose on 454 and never reaches the bubble view's setter. Its
 * card is the one composable taking a [NOTE_CUSTOM_THEME] that starts the [NOTE_CARD_TRACE] trace,
 * and the hook goes where every path has picked the fill, the branches into that spot included.
 * Anything else fails the patch.
 */
internal fun BytecodePatchContext.blackenNoteCards() {
    val cards = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (NOTE_CUSTOM_THEME in method.parameterTypes.map(Any::toString) && tracesNoteCard(method)) {
                cards += classDef.type to method
            }
        }
    }
    val (type, card) = cards.singleOrNull()
        ?: throw PatchException("$NOTE_PATCH: expected one note card on the inbox's notes row, found ${cards.size}")
    val (at, fill) = noteCardFill(card.implementation!!.instructions.toList())
        ?: throw PatchException("$NOTE_PATCH: the inbox's note card no longer picks its fill in one register")
    val method = mutableClassDefBy(type).methods.single {
        it.name == card.name && it.returnType == card.returnType &&
            it.parameterTypes.map(Any::toString) == card.parameterTypes.map(Any::toString)
    }
    method.addInstructionsAtControlFlowLabel(
        at,
        """
            invoke-static/range { v$fill .. v${fill + 1} }, $NOTE_CARD
            move-result-wide v$fill
        """,
    )
}
