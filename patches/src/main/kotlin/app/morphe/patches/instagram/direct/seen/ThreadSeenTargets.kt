/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireFreeAt
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val THREAD_SEEN = "$EXTENSION_PACKAGE/direct/ThreadSeen;"
internal const val HOLD_THREAD_SEEN = "$THREAD_SEEN->hold(Ljava/lang/Object;Ljava/lang/Object;)Z"

/** The query and root field of the GraphQL mutation that carries an ordinary chat's seen receipt. */
internal const val THREAD_SEEN_QUERY = "IGDirectItemSeenMutation"
internal const val THREAD_SEEN_ROOT = "xig_direct_item_seen"

/** The name Instagram's mutation queue files that receipt under. */
internal const val THREAD_SEEN_MUTATION = "send_thread_seen_marker"

/** The prefix of the queue key the receipt's sender gives each chat, one receipt waiting per chat. */
internal const val THREAD_SEEN_KEY = "mark_thread_seen-"

private const val JAVA_STRING = "Ljava/lang/String;"
private const val JAVA_OBJECT = "Ljava/lang/Object;"

/** The queue's handler for the receipt: the one method holding both names of its mutation. */
internal object ThreadSeenFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(THREAD_SEEN_QUERY, THREAD_SEEN_ROOT),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.size == 3 && method.parameterTypes.all { it.startsWith("L") } },
)

internal data class ThreadSeenTargets(
    val handler: MutableMethod,
    /** The account the handler keeps and sends the receipt for. */
    val account: FieldReference,
    val mutation: String,
    val complete: MethodReference,
    val selector: Method,
    val registry: Method,
    val creator: Method,
)

private fun refuse(why: String): Nothing = throw PatchException("$THREAD_SEEN_PATCH: $why")
private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
private fun Instruction.call() = visualReference() as? MethodReference
private fun Instruction.field() = visualReference() as? FieldReference
private fun Instruction.type() = (visualReference() as? TypeReference)?.type
private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.public() = AccessFlags.PUBLIC.isSet(accessFlags)
private fun Method.static() = AccessFlags.STATIC.isSet(accessFlags)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/**
 * Resolve the receipt's handler and everything the early completion relies on, before any edit:
 * the account it keeps, the one mutation class it handles, the queue's completion callback, the
 * registration that hands the handler that mutation, and the sender that queues it when a chat opens.
 */
internal fun BytecodePatchContext.findThreadSeen(): ThreadSeenTargets {
    val handler = uniqueMethod(THREAD_SEEN_PATCH, "chat receipt handler", ThreadSeenFingerprint)
    if (!handler.public() || handler.localRegisterCount() < 2) refuse("receipt handler needs public access and two locals")
    handler.requireFreeAt(THREAD_SEEN_PATCH, 0, listOf(0, 1))
    if (0 in handler.jumpTargets()) refuse("a jump or exception handler enters the receipt handler at its first instruction")
    val mutation = castMutation(handler)


    // The account the receipt goes out for: the one the handler keeps, which its provider builds it with.
    val account = (classDefByOrNull(handler.definingClass) ?: refuse("receipt handler class is missing")).fields
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == USER_SESSION }.toList()
        .one("account the receipt handler keeps")
    if (handler.visualCode().none { it.opcode == Opcode.IGET_OBJECT && it.field()?.toString() == account.toString() }) {
        refuse("receipt handler never reads the account it keeps")
    }

    val callback = classDefByOrNull(handler.parameterTypes[1].toString()) ?: refuse("receipt callback interface is missing")
    if (!AccessFlags.PUBLIC.isSet(callback.accessFlags) || !AccessFlags.INTERFACE.isSet(callback.accessFlags)) {
        refuse("receipt callback is not a public interface")
    }
    val complete = callback.methods.filter {
        it.public() && AccessFlags.ABSTRACT.isSet(it.accessFlags) && !it.static() && it.returnType == "V" &&
            it.parameterTypes.size == 2 && it.parameterTypes[0].startsWith("L") && it.parameterTypes[1] == JAVA_STRING
    }.one("native mutation completion method")
    // The interface's own helper finishes a task with no error and no message, as the hook does.
    // R8 moves that static helper off the interface in some builds (450's 385611395 and 385611400
    // keep it on another class), where it takes an Object and casts it to the interface first (#77).
    if (callback.methods.none { it.static() && completesWithTwoNulls(it, complete) } &&
        classesCallingInto(callback.type).none { owner -> owner.methods.any { it.completesCallbackWithTwoNulls(callback.type, complete) } }
    ) {
        refuse("the callback interface never completes a task with two nulls")
    }

    val base = handler.parameterTypes[2].toString()
    val selector = (classDefByOrNull(base)?.methods ?: emptyList()).filter { method ->
        method.definingClass == base && method.returnType == JAVA_STRING &&
            method.visualCode().any { it.visualString() == THREAD_SEEN_MUTATION }
    }.one("receipt mutation name selector")
    requireSelected(selector, mutation)

    val registry = classesHolding(THREAD_SEEN_MUTATION).flatMap { it.methods }.filter { method ->
        val body = method.visualCode()
        body.any { it.visualString() == THREAD_SEEN_MUTATION } &&
            body.any { it.opcode == Opcode.SGET_OBJECT && it.field()?.definingClass == handler.definingClass }
    }.one("receipt handler registration")
    requireBinding(registry, handler.definingClass) { classDefByOrNull(it) }

    val dispatch = classesHolding(DISPATCH_ANCHOR).flatMap { it.methods }.filter { method ->
        method.visualCode().any { it.visualString() == DISPATCH_ANCHOR } &&
            method.parameterTypes.map(Any::toString) == listOf(base) && method.returnType == "Z"
    }.one("native mutation dispatcher")
    val creator = classesHolding(THREAD_SEEN_KEY).flatMap { it.methods }.filter { method ->
        val body = method.visualCode()
        body.any { it.visualString() == THREAD_SEEN_KEY } &&
            body.any { it.opcode == Opcode.NEW_INSTANCE && it.type() == mutation }
    }.one("live receipt sender")
    val created = creator.visualCode()
    val createAt = created.indices.filter {
        created[it].opcode == Opcode.NEW_INSTANCE && created[it].type() == mutation
    }.one("live receipt allocation")
    val allocated = (created[createAt] as OneRegisterInstruction).registerA
    val sendAt = created.indices.filter {
        created[it].call()?.key() == dispatch.key() && created[it].namedRegisters().lastOrNull() == allocated
    }.one("live receipt dispatch call")
    requireOrigin(THREAD_SEEN_PATCH, creator, sendAt, allocated, createAt, "live receipt mutation", fromDefinition = true)

    classDefByOrNull(THREAD_SEEN)?.methods?.filter {
        it.name == "hold" && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(JAVA_OBJECT, JAVA_OBJECT) &&
            it.public() && it.static()
    }?.singleOrNull() ?: refuse("extension has no public static hold(Object, Object)Z")
    return ThreadSeenTargets(handler, account, mutation, complete, selector, registry, creator)
}

/** The handler's first act is to cast its mutation parameter, directly or through one copy. */
private fun castMutation(handler: Method): String {
    val code = handler.visualCode()
    val parameter = handler.parameterRegisterNumber(2)
    val first = code.firstOrNull()
    val cast = when {
        first?.opcode == Opcode.CHECK_CAST && (first as OneRegisterInstruction).registerA == parameter -> first
        first?.opcode in OBJECT_MOVES && (first as TwoRegisterInstruction).registerB == parameter &&
            code.getOrNull(1)?.opcode == Opcode.CHECK_CAST &&
            (code[1] as OneRegisterInstruction).registerA == first.registerA && 1 !in handler.jumpTargets() -> code[1]
        else -> null
    }
    return cast?.type() ?: refuse("receipt handler does not start by casting its mutation parameter")
}

/** [method] calls [complete] with one register for both arguments, set to null just before. */
private fun completesWithTwoNulls(method: Method, complete: Method): Boolean {
    val code = method.visualCode()
    val jumps = method.jumpTargets()
    return code.indices.any { at ->
        val arguments = code[at].namedRegisters()
        if (code[at].opcode !in setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE) ||
            code[at].call()?.key() != complete.key() || arguments.size != 3 || arguments[1] != arguments[2]
        ) return@any false
        val zero = (at - 1 downTo 0).firstOrNull { code[it].writes(arguments[1]) } ?: return@any false
        code[zero].opcode == Opcode.CONST_4 && (code[zero] as NarrowLiteralInstruction).narrowLiteral == 0 &&
            jumps.none { it in zero + 1..at }
    }
}

/**
 * [this] is the callback interface's completion helper moved to another class: a static (Object)V
 * whose first act is to cast its argument to [callback], and which [completesWithTwoNulls].
 */
private fun Method.completesCallbackWithTwoNulls(callback: String, complete: Method): Boolean {
    if (!static() || returnType != "V" || parameterTypes.map(Any::toString) != listOf(JAVA_OBJECT)) return false
    val first = visualCode().firstOrNull() ?: return false
    return first.opcode == Opcode.CHECK_CAST && first.type() == callback &&
        (first as OneRegisterInstruction).registerA == localRegisterCount() && completesWithTwoNulls(this, complete)
}

private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (destination == register || opcode.setsWideRegister() && destination + 1 == register)
}

/** The selector names the receipt for its own class and nothing else, as the visual receipt's does. */
private fun requireSelected(selector: Method, mutation: String) {
    val selected = selector.visualCode()
    val marker = selected.indices.filter { selected[it].visualString() == THREAD_SEEN_MUTATION }.one("receipt mutation name")
    if (marker < 2 || selected[marker - 2].opcode != Opcode.INSTANCE_OF || selected[marker - 2].type() != mutation ||
        (selected[marker - 2] as TwoRegisterInstruction).registerB != selector.localRegisterCount() ||
        selected[marker - 1].opcode != Opcode.IF_EQZ ||
        (selected[marker - 1] as OneRegisterInstruction).registerA != (selected[marker - 2] as TwoRegisterInstruction).registerA ||
        selected.getOrNull(marker + 1)?.opcode != Opcode.RETURN_OBJECT ||
        (selected[marker] as OneRegisterInstruction).registerA != (selected[marker + 1] as OneRegisterInstruction).registerA
    ) refuse("receipt mutation name is not selected by its own class")
}

/**
 * The registry's entry for [THREAD_SEEN_MUTATION] must wrap the provider read from the handler's
 * class, and that provider must build this handler. Registers are followed, not counted, since the
 * entries around it differ in how many values they load.
 */
private fun requireBinding(registry: Method, handler: String, classes: (String) -> ClassDef?) {
    val code = registry.visualCode()
    val marker = code.indices.filter { code[it].visualString() == THREAD_SEEN_MUTATION }.one("registry's receipt name")
    val describedAt = marker + 2
    val described = code.getOrNull(describedAt)
    if (code.getOrNull(marker + 1)?.opcode != Opcode.NEW_INSTANCE || described?.opcode != Opcode.INVOKE_DIRECT) {
        refuse("receipt registration does not describe its name")
    }
    val arguments = described.namedRegisters()
    val name = (code[marker] as OneRegisterInstruction).registerA
    if (arguments.size != 5 || arguments.first() != (code[marker + 1] as OneRegisterInstruction).registerA ||
        arguments.last() != name
    ) refuse("receipt registration describes another name")
    val wrapper = arguments[2]
    val wrapped = (marker - 1 downTo 0).firstOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && (code[it] as OneRegisterInstruction).registerA == wrapper
    } ?: refuse("receipt registration wraps no handler provider")
    val built = code.getOrNull(wrapped + 1)
    if (built?.opcode != Opcode.INVOKE_DIRECT || built.namedRegisters().size != 2 || built.namedRegisters()[0] != wrapper) {
        refuse("receipt registration does not build its provider wrapper")
    }
    val provided = built.namedRegisters()[1]
    val providerAt = (wrapped - 1 downTo 0).firstOrNull { code[it].writes(provided) }
        ?.takeIf { code[it].opcode == Opcode.SGET_OBJECT && code[it].field()?.definingClass == handler }
        ?: refuse("receipt registration wraps another handler's provider")
    requireOrigin(THREAD_SEEN_PATCH, registry, wrapped + 1, provided, providerAt, "receipt handler provider")
    requireOrigin(THREAD_SEEN_PATCH, registry, describedAt, wrapper, wrapped, "receipt handler wrapper")
    requireOrigin(THREAD_SEEN_PATCH, registry, describedAt, name, marker, "receipt registration name")

    val field = code[providerAt].field()!!.toString()
    val init = classes(handler)?.methods?.filter { it.name == "<clinit>" }?.one("handler provider initializer")
        ?: refuse("handler provider initializer is missing")
    val initCode = init.visualCode()
    if (initCode.size != 3 || initCode[0].opcode != Opcode.SGET_OBJECT || initCode[1].opcode != Opcode.SPUT_OBJECT ||
        initCode[2].opcode != Opcode.RETURN_VOID || initCode[1].field()?.toString() != field ||
        (initCode[0] as OneRegisterInstruction).registerA != (initCode[1] as OneRegisterInstruction).registerA
    ) refuse("handler provider initializer changes its provider")
    val provider = classes(initCode[0].field()!!.definingClass) ?: refuse("handler provider class is missing")
    val factory = provider.methods.filter {
        it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) && it.returnType == JAVA_OBJECT
    }.one("handler provider method")
    val body = factory.visualCode()
    val allocation = body.indices.filter {
        body[it].opcode == Opcode.NEW_INSTANCE && body[it].type() == handler
    }.one("provider's handler allocation")
    val register = (body[allocation] as OneRegisterInstruction).registerA
    val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) refuse("handler provider never returns its handler")
    returns.forEach { at ->
        if ((body[at] as OneRegisterInstruction).registerA != register) refuse("handler provider returns another object")
        requireOrigin(THREAD_SEEN_PATCH, factory, at, register, allocation, "handler provider result")
    }
}
