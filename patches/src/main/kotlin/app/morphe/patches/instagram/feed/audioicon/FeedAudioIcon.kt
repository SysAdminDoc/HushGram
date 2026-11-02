/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.audioicon

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide the audio icon on posts"

internal const val FEED_AUDIO_ICON = "$EXTENSION_PACKAGE/feed/FeedAudioIcon;"
internal const val HIDES_AUDIO_ICON = "$FEED_AUDIO_ICON->hides(I)Z"

/**
 * The text Instagram 453's feed post state prints in front of its hide-the-audio-icon flag. The state
 * is a Kotlin data class, so its toString names every field in the order the compiler wrote them
 * and the obfuscated field can be read off it (#464).
 */
internal const val AUDIO_ICON_LABEL = ", hidesAudioIcon="

private const val STRING_BUILDER = "Ljava/lang/StringBuilder;"

/**
 * The store of the audio icon flag in the post state's constructor, found. [write] hands the value
 * about to be stored to FeedAudioIcon.hides(), which answers yes for a flag already set or the
 * switch on, so both of Instagram's drawing paths (the icon and its slide-in view) skip the icon.
 */
internal class FeedAudioIconStore(val type: String, val field: String, val index: Int, val register: Int) {
    fun write(context: BytecodePatchContext) {
        val constructor = context.mutableClassDefBy(type).methods.single { method ->
            method.name == "<init>" && method.implementation?.instructions?.toList()?.let { storeIndex(it, field) } == index
        }
        constructor.addInstructions(
            index,
            """
                invoke-static/range { v$register .. v$register }, $HIDES_AUDIO_ICON
                move-result v$register
            """,
        )
    }
}

/**
 * Finds the state class by the label its toString prints, reads off which field follows the label,
 * and finds the one constructor store of that field: an iput-boolean whose register the instruction
 * right before it set. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findFeedAudioIcon(): FeedAudioIconStore {
    val states = classesLoadingString(AUDIO_ICON_LABEL).filter { classDef ->
        classDef.methods.any { it.name == "toString" && printsLabel(it) }
    }
    val state = states.singleOrNull() ?: refuse("expected one class printing $AUDIO_ICON_LABEL, found ${states.size}")
    val toString = state.methods.single { it.name == "toString" && printsLabel(it) }
    val field = flagFieldOf(toString) ?: refuse("${state.type}->toString doesn't print a boolean field after $AUDIO_ICON_LABEL")
    if (field.definingClass != state.type) refuse("the field after $AUDIO_ICON_LABEL belongs to ${field.definingClass}, not ${state.type}")
    val stores = state.methods.filter { it.name == "<init>" }.mapNotNull { constructor ->
        val code = constructor.implementation?.instructions?.toList() ?: return@mapNotNull null
        val at = storeIndex(code, field.name) ?: return@mapNotNull null
        Triple(constructor, at, (code[at] as TwoRegisterInstruction).registerA)
    }
    val store = stores.singleOrNull()
        ?: refuse("expected one constructor storing ${state.type}->${field.name}, found ${stores.size}")
    return FeedAudioIconStore(state.type, field.name, store.second, store.third)
}

private fun BytecodePatchContext.printsLabel(method: Method): Boolean {
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.indices.any { stringLoadedAt(code, it) == AUDIO_ICON_LABEL }
}

/**
 * The field printed right after the label: the label itself is appended first, then the field's
 * value with append(Z), whose register was set by an iget-boolean before the first append.
 */
private fun BytecodePatchContext.flagFieldOf(toString: Method): FieldReference? {
    val code = toString.implementation?.instructions?.toList() ?: return null
    val label = code.indices.firstOrNull { stringLoadedAt(code, it) == AUDIO_ICON_LABEL } ?: return null
    val appends = (label + 1 until code.size).filter { isAppend(code[it]) }.take(2)
    if (appends.size != 2) return null
    val valueAppend = appends[1]
    val call = code[valueAppend] as ReferenceInstruction
    val method = call.reference as MethodReference
    if (method.parameterTypes.map(Any::toString) != listOf("Z")) return null
    val register = (call as FiveRegisterInstruction).registerD
    val read = (valueAppend - 1 downTo 0).firstOrNull { writesRegister(code[it], register) } ?: return null
    if (code[read].opcode != Opcode.IGET_BOOLEAN) return null
    return (code[read] as ReferenceInstruction).reference as? FieldReference
}

private fun isAppend(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return false
    val method = (instruction as ReferenceInstruction).reference as? MethodReference ?: return false
    return method.definingClass == STRING_BUILDER && method.name == "append"
}

private fun writesRegister(instruction: Instruction, register: Int): Boolean =
    instruction is TwoRegisterInstruction && instruction.registerA == register &&
        instruction.opcode != Opcode.IPUT_BOOLEAN && instruction.opcode != Opcode.IPUT && instruction.opcode != Opcode.IPUT_OBJECT

/**
 * The index of the iput-boolean storing [name], when the instruction right before it moves a value
 * into the stored register (so the hook sits on a straight line from the value to the store), else null.
 */
private fun storeIndex(code: List<Instruction>, name: String): Int? {
    val at = code.indices.firstOrNull { index ->
        val instruction = code[index]
        instruction.opcode == Opcode.IPUT_BOOLEAN &&
            ((instruction as ReferenceInstruction).reference as FieldReference).name == name
    } ?: return null
    val store = code[at] as TwoRegisterInstruction
    val before = code.getOrNull(at - 1) ?: return null
    val moves = before.opcode == Opcode.MOVE || before.opcode == Opcode.MOVE_FROM16 || before.opcode == Opcode.MOVE_16
    return if (moves && (before as TwoRegisterInstruction).registerA == store.registerA && store.registerA <= 255) at else null
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the post state: $detail")
