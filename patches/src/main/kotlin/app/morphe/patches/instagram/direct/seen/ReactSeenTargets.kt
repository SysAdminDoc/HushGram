/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val REACTED =
    "$THREAD_SEEN->reacted(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;I)V"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/** What the reaction sender logs: the kind of reaction, what it reacts to, and whether it's added rather than taken off. */
private const val REACTION_LIKE = "like"
private const val REACTION_ITEM = "item"
private const val REACTION_CREATED = "created"

/** What Mark a chat seen when you react needs from this build, resolved before any edit. */
internal data class ReactSeenTargets(
    val send: MutableMethod,
    /** Where the hook goes: right after the sender looked up the message the reaction is on. */
    val at: Int,
    val session: Int,
    val key: Int,
    val message: Int,
    val item: Int,
    /** The sender's last argument: true when the reaction is added, false when it's taken off. */
    val created: Int,
    /** Locals up to v15 the hook may copy each register above v15 into, in the order they're needed. */
    val scratch: List<Int>,
)

private fun Instruction.call() = visualReference() as? MethodReference
private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.parameters() = parameterTypes.map(Any::toString)

/** The chat's message lookup: an interface call taking a chat key and a message id and answering an object. */
private fun Instruction.messageLookup(): MethodReference? {
    if (opcode != Opcode.INVOKE_INTERFACE) return null
    val call = call() ?: return null
    return call.takeIf { it.parameterTypes.map(Any::toString) == listOf(THREAD_KEY, STRING) && it.returnType.startsWith("L") }
}

/**
 * Instagram's one sender for a reaction to a message in a chat: an instance method taking the chat
 * key once and ending in whether the reaction is added, which logs "like", "item" and "created"
 * and looks the message up by the chat key and its id before building the request.
 */
private fun BytecodePatchContext.reactionSenders(): List<Method> = classesLoadingString(REACTION_CREATED).flatMap { owner ->
    owner.methods.filter { method ->
        val parameters = method.parameters()
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            parameters.count { it == THREAD_KEY } == 1 && parameters.lastOrNull() == "Z" &&
            method.visualCode().count { it.messageLookup() != null } == 1 &&
            loadsString(method, REACTION_LIKE) && loadsString(method, REACTION_ITEM) && loadsString(method, REACTION_CREATED)
    }
}

/**
 * Resolves the reaction sender and the registers holding the account, the chat key, the message
 * reacted to, its id and whether the reaction is added, all read where the sender has just looked
 * the message up. Null when this build's sender doesn't show them, which leaves reacting with no
 * effect on the receipt, as before. Throws when two methods answer for the sender.
 */
internal fun BytecodePatchContext.findReactSeen(marks: MarkReadTargets): ReactSeenTargets? {
    val matches = reactionSenders()
    if (matches.isEmpty()) return null
    if (matches.size != 1) {
        throw PatchException("$THREAD_SEEN_PATCH: expected one reaction sender, found ${matches.joinToString { it.key() }}")
    }
    val found = matches.single()
    val send = mutableClassDefBy(found.definingClass).methods.single { it.key() == found.key() }
    val code = send.visualCode()

    val lookupAt = code.indexOfFirst { it.messageLookup() != null }
    val lookup = code[lookupAt].messageLookup()!!
    val (receiver, key, item) = code[lookupAt].namedRegisters().takeIf { it.size == 3 } ?: return null
    val answer = code.getOrNull(lookupAt + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
    val message = (answer as OneRegisterInstruction).registerA
    val at = lookupAt + 2
    if (at >= code.size) return null

    // The lookup's receiver comes straight from a static call on the account's session.
    val handed = code.getOrNull(lookupAt - 1)
    if (handed?.opcode != Opcode.MOVE_RESULT_OBJECT || (handed as OneRegisterInstruction).registerA != receiver) return null
    val staticAt = lookupAt - 2
    val sessionCall = code.getOrNull(staticAt)?.takeIf { it.opcode == Opcode.INVOKE_STATIC }?.call() ?: return null
    if (sessionCall.parameterTypes.map(Any::toString) != listOf(USER_SESSION)) return null
    val session = code[staticAt].namedRegisters().single()

    // From the static call to the hook the code runs straight through, and nothing in it rewrites
    // the account, the key or the id, so the hook reads what the lookup was handed.
    val targets = send.jumpTargets()
    if ((staticAt + 1..at).any { it in targets }) return null
    val kept = setOf(session, key, item)
    if (code.subList(staticAt + 1, at).any { instruction ->
            val written = (instruction as? OneRegisterInstruction)?.registerA
            instruction.opcode.setsRegister() && written != null &&
                (written in kept || instruction.opcode.setsWideRegister() && written + 1 in kept)
        }
    ) return null

    val createdIndex = send.parameterTypes.lastIndex
    send.requireParameterIntact(THREAD_SEEN_PATCH, createdIndex, listOf(at))
    val created = send.parameterRegisterNumber(createdIndex)

    // The sender bridge casts the message to the class Instagram's own handler reads the sender off.
    val classes = HashMap<String, ClassDef>()
    classDefForEach { classes[it.type] = it }
    if (!extends(lookup.returnType, marks.senderClass, classes)) return null
    val extension = classes[THREAD_SEEN] ?: return null
    requireHook(extension, "reacted", listOf(OBJECT, OBJECT, OBJECT, STRING, "I"), "V")

    val read = listOf(session, key, message, item, created)
    val scratch = send.freeLocalsAt(THREAD_SEEN_PATCH, at, read.count { it > 15 }, except = read)
    return ReactSeenTargets(send, at, session, key, message, item, created, scratch)
}

/**
 * Hands the extension the account, the chat key, the message reacted to, its id and whether the
 * reaction is added, once the sender has the message, so it can mark the chat seen up to it.
 */
internal fun writeReactSeen(found: ReactSeenTargets) {
    val scratch = found.scratch.iterator()
    val moves = StringBuilder()
    fun pass(register: Int, move: String): Int {
        if (register <= 15) return register
        val to = scratch.next()
        moves.append("$move${if (register > 255) "/16" else "/from16"} v$to, v$register\n")
        return to
    }
    val session = pass(found.session, "move-object")
    val key = pass(found.key, "move-object")
    val message = pass(found.message, "move-object")
    val item = pass(found.item, "move-object")
    val created = pass(found.created, "move")
    found.send.addInstructions(
        found.at,
        "$moves invoke-static { v$session, v$key, v$message, v$item, v$created }, $REACTED",
    )
}
