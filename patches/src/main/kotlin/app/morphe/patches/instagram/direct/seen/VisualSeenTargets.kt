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
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireFreeAt
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val VISUAL_SEEN = "$EXTENSION_PACKAGE/direct/VisualSeen;"
internal const val HOLD_VISUAL_SEEN = "$VISUAL_SEEN->hold()Z"
internal const val VISUAL_ENDPOINT = "direct_v2/visual_threads/%s/item_seen/"
internal const val VISUAL_KIND = "raven_media"
internal const val VISUAL_MUTATION = "send_visual_item_seen_marker"
internal const val SUCCESS_ANCHOR = "Item ID doesn't exist in session scoped API callback."
internal const val DISPATCH_ANCHOR = "MutationManager.dispatch"
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/** Voice receipts share the endpoint, so both native strings are required. */
internal object VisualSeenFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(VISUAL_ENDPOINT, VISUAL_KIND),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.size == 3 && method.parameterTypes.all { it.startsWith("L") } },
)

internal data class VisualSeenTargets(
    val handler: MutableMethod,
    val mutation: String,
    val complete: MethodReference,
    val success: Method,
    val callbackWriter: Method,
    val registry: Method,
    val creator: Method,
    val dispatch: Method,
    val selector: Method,
)

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")
private fun <T> List<T>.one(what: String): T = singleOrNull() ?: refuse("expected one $what, found $size")
internal fun Method.visualCode() = implementation?.instructions?.toList().orEmpty()
internal fun Instruction.visualReference() = (this as? ReferenceInstruction)?.reference
internal fun Instruction.visualString() = (visualReference() as? StringReference)?.string
private fun Instruction.call() = visualReference() as? MethodReference
private fun Instruction.field() = visualReference() as? FieldReference
private fun Instruction.type() = (visualReference() as? TypeReference)?.type
private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.public() = AccessFlags.PUBLIC.isSet(accessFlags)

/** Resolve the visual-only handler and every API the early completion will call before editing. */
internal fun BytecodePatchContext.findVisualSeen(): VisualSeenTargets {
    val handler = uniqueMethod(PATCH, "visual receipt handler", VisualSeenFingerprint)
    if (!handler.public() || handler.localRegisterCount() < 2) refuse("visual handler needs public access and two locals")
    handler.requireFreeAt(PATCH, 0, listOf(0, 1))
    if (0 in handler.jumpTargets()) refuse("a jump or exception handler enters the visual handler at its first instruction")
    val code = handler.visualCode()
    val mutation = code.firstOrNull()?.takeIf {
        it.opcode == Opcode.CHECK_CAST && (it as? OneRegisterInstruction)?.registerA == handler.parameterRegisterNumber(2)
    }?.type() ?: refuse("visual handler does not start by casting its mutation parameter")

    val callbackType = handler.parameterTypes[1].toString()
    val callback = classDefByOrNull(callbackType) ?: refuse("visual callback interface is missing")
    if (!AccessFlags.PUBLIC.isSet(callback.accessFlags) || !AccessFlags.INTERFACE.isSet(callback.accessFlags)) {
        refuse("visual callback is not a public interface")
    }
    val complete = callback.methods.filter {
        it.public() && AccessFlags.ABSTRACT.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.returnType == "V" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0].startsWith("L") && it.parameterTypes[1] == STRING
    }.one("native mutation completion method")

    val factories = code.indices.filter { at ->
        val call = code[at].call()
        code[at].opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) &&
            call?.parameterTypes?.map(Any::toString) == listOf(USER_SESSION, callbackType) && call.returnType.startsWith("L")
    }
    val factoryAt = factories.one("native response callback factory")
    val factoryRef = code[factoryAt].call()!!
    if (code[factoryAt].namedRegisters().getOrNull(1) != handler.parameterRegisterNumber(1)) {
        refuse("response factory is not handed the visual handler's callback")
    }
    handler.requireParameterIntact(PATCH, 1, listOf(factoryAt))
    val nativeCallback = classDefByOrNull(factoryRef.returnType) ?: refuse("native response callback class is missing")
    val success = nativeCallback.methods.filter { method ->
        method.visualCode().any { it.visualString() == SUCCESS_ANCHOR } &&
            method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags)
    }.one("native success callback")
    val successCode = success.visualCode()
    val doneAt = successCode.indices.filter { successCode[it].call()?.key() == complete.key() }.one("native success completion call")
    if (successCode[doneAt].opcode !in setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)) {
        refuse("native success completion is not an interface call")
    }
    val receiver = successCode[doneAt].namedRegisters().first()
    val fieldAt = successCode.indices.filter { at ->
        val ins = successCode[at]
        ins.opcode == Opcode.IGET_OBJECT && ins.field()?.definingClass == nativeCallback.type &&
            ins.field()?.type == callbackType && (ins as TwoRegisterInstruction).registerA == receiver &&
            ins.registerB == success.localRegisterCount()
    }.one("success callback's stored completion receiver")
    success.requireThisIntact(PATCH, listOf(fieldAt))
    requireOrigin(success, doneAt, receiver, fieldAt, "native success callback receiver")
    val stored = successCode[fieldAt].field()!!
    val callbackField = stored.toString()
    val writer = classesAccessing(stored.definingClass, stored.name, Opcode.IPUT_OBJECT).flatMap { it.methods }.filter { method ->
        method.returnType == nativeCallback.type && AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.visualCode().any { it.opcode == Opcode.IPUT_OBJECT && it.field()?.toString() == callbackField }
    }.one("native callback writer")
    val writerCode = writer.visualCode()
    val writeAt = writerCode.indices.filter {
        writerCode[it].opcode == Opcode.IPUT_OBJECT && writerCode[it].field()?.toString() == callbackField
    }.one("native callback assignment")
    val callbackParam = writer.parameterTypes.indices.filter { writer.parameterTypes[it] == callbackType }.one("callback writer parameter")
    if ((writerCode[writeAt] as TwoRegisterInstruction).registerA != writer.parameterRegisterNumber(callbackParam)) {
        refuse("native callback writer stores something other than its callback parameter")
    }
    writer.requireParameterIntact(PATCH, callbackParam, listOf(writeAt))
    val allocatedAt = writerCode.indices.filter {
        writerCode[it].opcode == Opcode.NEW_INSTANCE && writerCode[it].type() == nativeCallback.type
    }.one("native response callback allocation")
    val allocated = (writerCode[allocatedAt] as OneRegisterInstruction).registerA
    if ((writerCode[writeAt] as TwoRegisterInstruction).registerB != allocated) {
        refuse("native callback writer assigns another object's callback")
    }
    requireOrigin(writer, writeAt, allocated, allocatedAt, "native response callback object")
    val returned = writerCode.indices.filter { writerCode[it].opcode == Opcode.RETURN_OBJECT }
    if (returned.isEmpty()) refuse("native callback writer never returns its callback")
    returned.forEach { at ->
        if ((writerCode[at] as OneRegisterInstruction).registerA != allocated) {
            refuse("native callback writer returns another object")
        }
        requireOrigin(writer, at, allocated, allocatedAt, "native response callback result")
    }
    val factory = (classDefByOrNull(factoryRef.definingClass)?.methods ?: emptyList()).filter { it.key() == factoryRef.key() }.one("response factory body")
    if (factory.key() != writer.key()) {
        val factoryCode = factory.visualCode()
        val forwardAt = factoryCode.indices.filter { factoryCode[it].call()?.key() == writer.key() }.one("response factory forwarding call")
        val forwards = factoryCode[forwardAt]
        if (forwards.namedRegisters().getOrNull(callbackParam) != factory.parameterRegisterNumber(1)) {
            refuse("response factory does not forward its callback")
        }
        factory.requireParameterIntact(PATCH, 1, listOf(forwardAt))
    }

    val registry = classesHolding(VISUAL_MUTATION).flatMap { it.methods }.filter { method ->
        val body = method.visualCode()
        body.any { it.visualString() == VISUAL_MUTATION } && body.any {
            it.opcode == Opcode.SGET_OBJECT && it.field()?.definingClass == handler.definingClass
        }
    }.one("visual handler registration")
    requireRegistration(registry, handler.definingClass) { classDefByOrNull(it) }
    val selector = (classDefByOrNull(handler.parameterTypes[2].toString())?.methods ?: emptyList()).filter { method ->
        method.definingClass == handler.parameterTypes[2].toString() && method.returnType == STRING &&
            method.visualCode().any { it.visualString() == VISUAL_MUTATION }
    }.one("visual mutation name selector")
    val selected = selector.visualCode()
    val marker = selected.indices.filter { selected[it].visualString() == VISUAL_MUTATION }.one("visual mutation name")
    if (marker < 2 || selected[marker - 2].opcode != Opcode.INSTANCE_OF || selected[marker - 2].type() != mutation ||
        (selected[marker - 2] as TwoRegisterInstruction).registerB != selector.localRegisterCount() ||
        selected[marker - 1].opcode != Opcode.IF_EQZ ||
        (selected[marker - 1] as OneRegisterInstruction).registerA != (selected[marker - 2] as TwoRegisterInstruction).registerA ||
        selected.getOrNull(marker + 1)?.opcode != Opcode.RETURN_OBJECT ||
        (selected[marker] as OneRegisterInstruction).registerA != (selected[marker + 1] as OneRegisterInstruction).registerA
    ) refuse("visual mutation name is not selected by its own class")

    val dispatch = classesHolding(DISPATCH_ANCHOR).flatMap { it.methods }.filter { it.visualCode().any { ins -> ins.visualString() == DISPATCH_ANCHOR } &&
        it.parameterTypes.map(Any::toString) == listOf(handler.parameterTypes[2].toString()) && it.returnType == "Z"
    }.one("native mutation dispatcher")
    // 450 dispatches through a static (UserSession, mutation) helper that only looks up the
    // session's manager and hands it the mutation it was given.
    val helpers = classesCalling(dispatch.definingClass, dispatch.name).flatMap { it.methods }.filter { method ->
        val body = method.visualCode()
        val at = body.indices.filter { body[it].call()?.key() == dispatch.key() }
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && body.size <= 6 &&
            method.parameterTypes.map(Any::toString) == listOf(USER_SESSION, dispatch.parameterTypes.single().toString()) &&
            at.size == 1 && body[at.single()].namedRegisters().lastOrNull() == method.parameterRegisterNumber(1)
    }
    helpers.forEach { helper ->
        helper.requireParameterIntact(PATCH, 1, listOf(helper.visualCode().indexOfFirst { it.call()?.key() == dispatch.key() }))
    }
    val sends = (helpers.map { it.key() } + dispatch.key()).toSet()
    val senders = (helpers + dispatch).flatMap { classesCalling(it.definingClass, it.name) }.distinctBy { it.type }
    val creator = senders.flatMap { it.methods }.filter { method -> method.visualCode().any { it.opcode == Opcode.NEW_INSTANCE && it.type() == mutation } &&
        method.visualCode().any { it.call()?.key() in sends }
    }.one("live visual mutation creator")
    val created = creator.visualCode()
    val createAt = created.indices.filter { created[it].opcode == Opcode.NEW_INSTANCE && created[it].type() == mutation }.one("live visual mutation allocation")
    val sendAt = created.indices.filter { created[it].call()?.key() in sends &&
        created[it].namedRegisters().lastOrNull() == (created[createAt] as OneRegisterInstruction).registerA
    }.one("live visual mutation dispatch call")
    // The viewer's replay branch joins this dispatcher with a different mutation. Only paths
    // from this visual allocation must preserve its object; those unrelated creators stay native.
    requireOrigin(creator, sendAt, (created[createAt] as OneRegisterInstruction).registerA, createAt, "live visual mutation", fromDefinition = true)

    classDefByOrNull(VISUAL_SEEN)?.methods?.filter {
        it.name == "hold" && it.returnType == "Z" && it.parameterTypes.isEmpty() && it.public() && AccessFlags.STATIC.isSet(it.accessFlags)
    }?.singleOrNull() ?: refuse("extension has no public static hold()Z")
    return VisualSeenTargets(handler, mutation, complete, success, writer, registry, creator, dispatch, selector)
}

/** The registry's visual descriptor must use the provider that actually returns this handler. */
private fun requireRegistration(registry: Method, handler: String, classes: (String) -> ClassDef?) {
    val code = registry.visualCode()
    val marker = code.indices.filter { code[it].visualString() == VISUAL_MUTATION }.one("registry's visual name")
    val providerAt = marker - 5
    if (providerAt < 0 || code[providerAt].opcode != Opcode.SGET_OBJECT || code[providerAt].field()?.definingClass != handler ||
        code[providerAt + 1].opcode != Opcode.NEW_INSTANCE || code[providerAt + 2].opcode != Opcode.INVOKE_DIRECT ||
        code.getOrNull(marker + 2)?.opcode != Opcode.INVOKE_DIRECT
    ) refuse("visual registration does not bind its handler provider")
    if (registry.jumpTargets().any { it > providerAt && it <= marker + 2 }) refuse("a jump enters the visual registration")
    val provided = (code[providerAt] as OneRegisterInstruction).registerA
    val wrapper = (code[providerAt + 1] as OneRegisterInstruction).registerA
    if (code[providerAt + 2].namedRegisters() != listOf(wrapper, provided) ||
        code[marker + 2].namedRegisters().getOrNull(2) != wrapper ||
        code[marker + 2].namedRegisters().lastOrNull() != (code[marker] as OneRegisterInstruction).registerA
    ) refuse("visual registration substitutes its handler provider")
    val field = code[providerAt].field()!!.toString()
    val init = classes(handler)?.methods?.filter { it.name == "<clinit>" }?.one("handler provider initializer")
        ?: refuse("handler provider initializer is missing")
    val initCode = init.visualCode()
    if (initCode.size != 3 || initCode[0].opcode != Opcode.SGET_OBJECT || initCode[1].opcode != Opcode.SPUT_OBJECT ||
        initCode[2].opcode != Opcode.RETURN_VOID ||
        initCode[1].field()?.toString() != field ||
        (initCode[0] as OneRegisterInstruction).registerA != (initCode[1] as OneRegisterInstruction).registerA
    ) refuse("handler provider initializer changes its provider")
    val provider = classes(initCode[0].field()!!.definingClass) ?: refuse("handler provider class is missing")
    val factory = provider.methods.filter { it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) && it.returnType == OBJECT }.one("handler provider method")
    val body = factory.visualCode()
    val allocation = body.indices.filter { body[it].opcode == Opcode.NEW_INSTANCE && body[it].type() == handler }.one("provider's handler allocation")
    val register = (body[allocation] as OneRegisterInstruction).registerA
    val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) refuse("handler provider never returns its handler")
    returns.forEach { at ->
        if ((body[at] as OneRegisterInstruction).registerA != register) refuse("handler provider returns another object")
        requireOrigin(factory, at, register, allocation, "handler provider result")
    }
}

private fun requireOrigin(method: Method, at: Int, register: Int, definition: Int, what: String, fromDefinition: Boolean = false) =
    requireOrigin(PATCH, method, at, register, definition, what, fromDefinition)

/**
 * A value must reach its use on every normal and exceptional path, including wide-half writes.
 * A refusal names [patch], the patch whose check it was.
 */
internal fun requireOrigin(
    patch: String,
    method: Method,
    at: Int,
    register: Int,
    definition: Int,
    what: String,
    fromDefinition: Boolean = false,
) {
    fun refuse(why: String): Nothing = throw PatchException("$patch: $why")
    val flow = ControlFlow.of(method)
    val pending = ArrayDeque<Pair<Int, Boolean>>()
    val visited = mutableSetOf<Pair<Int, Boolean>>()
    pending += (if (fromDefinition) definition else 0) to false
    var reached = false
    while (pending.isNotEmpty()) {
        val state = pending.removeFirst()
        if (!visited.add(state)) continue
        val (index, intact) = state
        if (index == at) {
            reached = true
            if (!intact) refuse("$what can be replaced or bypassed")
        }
        val instruction = flow.instructions[index]
        val destination = (instruction as? OneRegisterInstruction)?.registerA
        val writes = instruction.opcode.setsRegister() && destination != null &&
            (destination == register || instruction.opcode.setsWideRegister() && destination + 1 == register)
        val next = when {
            index == definition -> true
            instruction.opcode == Opcode.CHECK_CAST -> intact
            writes -> false
            else -> intact
        }
        flow.normal[index].forEach { pending += it to next }
        flow.exceptional[index].forEach { pending += it to intact }
    }
    if (!reached) refuse("$what is unreachable")
}
