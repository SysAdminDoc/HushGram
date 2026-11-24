/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Instagram 457 slimmed its video player interface (0Ify) down to a few calls. What 456's player
 * answered itself, its speed and its state among them, is now asked of what its one implementation
 * (08v5) holds: Instagram's own code casts the player to it and reads a field or two (08v5.A0B, then
 * 06n0.A0G for the speed). These read a call's receiver back the way Instagram's code reaches it, so
 * the extension can walk the same way from the player it's handed.
 */

/** One step from a player towards what a call is made on: a cast to [type], or a read of [field]. */
internal class PlayerStep(val type: String?, val field: FieldReference?) {
    override fun toString() = type?.let { "cast to $it" } ?: "read of $field"
}

/**
 * A value reached from a player: the [player] interface, the [steps] from it in the order they run,
 * and [start], the index of the instruction that wrote the player's value (a field read, a call's
 * answer or a cast).
 */
internal data class PlayerPath(val player: String, val steps: List<PlayerStep>, val start: Int)

/** How many casts and fields a player path may go through. */
private const val MAX_PLAYER_STEPS = 6

/** Where the call at [at] in [code] gets its receiver, as [valuePath] reads it. */
internal fun BytecodePatchContext.receiverPath(code: List<Instruction>, at: Int): PlayerPath? {
    val call = code[at]
    val register = (call as? FiveRegisterInstruction)?.takeIf { it.registerCount > 0 }?.registerC
        ?: (call as? RegisterRangeInstruction)?.takeIf { it.registerCount > 0 }?.startRegister
        ?: return null
    return valuePath(code, at, register)
}

/**
 * Where register [from] gets the value it holds at [at] in [code]: the interface type of the first value
 * read back from it that has one (a field read, a call's answer or a cast), and the casts and object
 * fields between that value and [at]. An object move just follows its source. Null when the value
 * comes from anything else (a parameter, a constant) or from more than [MAX_PLAYER_STEPS] steps away.
 * The code is read backwards in order, which these straight runs of Instagram's are.
 */
internal fun BytecodePatchContext.valuePath(code: List<Instruction>, at: Int, from: Int): PlayerPath? {
    var register = from
    val steps = mutableListOf<PlayerStep>()
    var before = at
    while (steps.size <= MAX_PLAYER_STEPS) {
        val writer = (before - 1 downTo 0).firstOrNull { code[it].writesObject(register) } ?: return null
        val written = code[writer]
        before = writer
        if (written.opcode in OBJECT_MOVES) {
            register = (written as TwoRegisterInstruction).registerB
            continue
        }
        val reference = (written as? ReferenceInstruction)?.reference
        val type = when (written.opcode) {
            Opcode.CHECK_CAST -> (reference as TypeReference).type
            Opcode.IGET_OBJECT -> (reference as FieldReference).type
            Opcode.MOVE_RESULT_OBJECT -> ((code.getOrNull(writer - 1) as? ReferenceInstruction)?.reference as? MethodReference)?.returnType
            else -> null
        } ?: return null
        if (classDefByOrNull(type)?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true) return PlayerPath(type, steps.reversed(), writer)
        when (written.opcode) {
            Opcode.CHECK_CAST -> steps += PlayerStep(type, null)
            Opcode.IGET_OBJECT -> {
                steps += PlayerStep(null, reference as FieldReference)
                register = (written as TwoRegisterInstruction).registerB
            }
            else -> return null
        }
    }
    return null
}

private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

private fun Instruction.writesObject(register: Int): Boolean =
    opcode.setsRegister() && (this as? OneRegisterInstruction)?.registerA == register

/** What along [path] the extension can't reach: a class that isn't public, or a field that isn't a public instance field. */
internal fun BytecodePatchContext.unreachableAlong(path: List<PlayerStep>): List<String> = path.mapNotNull { step ->
    val owner = step.type ?: step.field!!.definingClass
    val ownerClass = classDefByOrNull(owner) ?: return@mapNotNull owner
    if (!AccessFlags.PUBLIC.isSet(ownerClass.accessFlags)) return@mapNotNull owner
    val field = step.field ?: return@mapNotNull null
    val declared = ownerClass.fields.singleOrNull { it.name == field.name && it.type == field.type }
    if (declared == null || !AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) "$field" else null
}

/**
 * The smali walking [register] along [path]: a check-cast for each cast and an iget-object for each
 * field, each read followed by a jump to [none] when it answers null, as Instagram's own reads are.
 */
internal fun List<PlayerStep>.walk(register: String, none: String): String = joinToString("") { step ->
    step.type?.let { "check-cast $register, $it\n" } ?: "iget-object $register, $register, ${step.field}\nif-eqz $register, $none\n"
}
