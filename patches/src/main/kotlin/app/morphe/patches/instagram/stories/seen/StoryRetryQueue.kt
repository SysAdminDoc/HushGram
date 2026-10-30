/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.pooledString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.iface.Annotation as DexAnnotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.CallSiteReference
import com.android.tools.smali.dexlib2.iface.reference.MethodHandleReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.MethodEncodedValue
import com.android.tools.smali.dexlib2.iface.value.MethodHandleEncodedValue
import java.util.IdentityHashMap

/** The native queue location before ownership changes, proved before any patch is written. */
internal class StoryRetryQueue(
    val owner: String,
    val run: String,
    val beforeClaim: Int,
    val loop: Int,
    val batch: Int,
    val scratch: Int,
)

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP = "Ljava/util/Map;"
private const val HASH_MAP = "Ljava/util/HashMap;"
private const val LINKED_MAP = "Ljava/util/LinkedHashMap;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val CLAIM_TAG = "null cannot be cast to non-null type T of com.instagram.store.PendingActionStore"
private const val DISK_PREFIX = "pending_reel_seen_states_"

private fun refuseQueue(detail: String): Nothing = throw PatchException("$PATCH: story retry queue $detail")

/**
 * 449's pending action loop takes a snapshot of keys, moves one pending item into the in-flight
 * map, builds its request, and attaches a callback. A held story takes the existing loop backedge
 * before claiming its key, without changing either map or allocating any native callback or request.
 * Selection runs again whenever the pending item is retried. No other store gets this null path.
 * These shapes are deliberately narrow: a changed ownership or loop refuses before mutation.
 */
internal fun BytecodePatchContext.findStoryRetryQueue(store: ClassDef, retry: StoreRetry, reader: Method, accountGetter: Method): StoryRetryQueue {
    if (!AccessFlags.FINAL.isSet(store.accessFlags) || store.interfaces.isNotEmpty()) refuseQueue("store isn't final without interfaces")
    val base = store.superclass?.let { classDefByOrNull(it) } ?: refuseQueue("has no native owner")
    if (!AccessFlags.PUBLIC.isSet(base.accessFlags) || !AccessFlags.ABSTRACT.isSet(base.accessFlags) || base.interfaces.isNotEmpty() ||
        base.superclass != OBJECT) {
        refuseQueue("owner isn't a public abstract class without interfaces")
    }
    val bridge = store.methods.single { it.name == retry.name && it.parameterTypes.map(Any::toString) == retry.parameters }
    val suffix = bridge.code().drop(retry.build + 1)
    if (bridge.implementation!!.tryBlocks.isNotEmpty() || suffix.size < 2 || suffix.first().opcode != Opcode.MOVE_RESULT_OBJECT ||
        suffix.last().opcode != Opcode.RETURN_OBJECT || suffix.first().namedRegisters() != suffix.last().namedRegisters() ||
        suffix.drop(1).dropLast(1).any { it.opcode != Opcode.NOP }) refuseQueue("bridge has cleanup or other work after its build")
    val build = base.methods.singleOrNull {
        it.name == bridge.name && it.parameterTypes.map(Any::toString) == retry.parameters && it.returnType == bridge.returnType &&
            AccessFlags.ABSTRACT.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuseQueue("bridge doesn't override its owner's request builder")
    if (retry.parameters != listOf(OBJECT) || !bridge.returnType.startsWith("L") ||
        !AccessFlags.PUBLIC.isSet(bridge.accessFlags) || !AccessFlags.PUBLIC.isSet(build.accessFlags)) refuseQueue("bridge has an unsupported type")

    val callers = mutableListOf<Pair<Method, Int>>()
    // Reading every method of the app was a measured share of patching (#173). A call to the bridge or
    // the builder names its class and name, which the patcher's index keeps, but a call site or method
    // handle isn't indexed. So code is read only in the classes the index names, the extension, classes
    // a patch replaced and any dex holding call sites or method handles. Declarations are read in all.
    // An Object owner only counts with the bridge's parameters and return type, which the index also
    // keeps; asked for Object and the name alone, it named most of the app.
    val calling = listOf(store.type, base.type).flatMapTo(HashSet()) { owner -> classesCalling(owner, bridge.name).map { it.type } }
    classesCalling(OBJECT, bridge.name, retry.parameters, bridge.returnType).mapTo(calling) { it.type }
    val indirect = IdentityHashMap<DexBackedDexFile, Boolean>()
    fun readsCode(type: ClassDef) = type.type in calling || type.type.startsWith(EXTENSION_ROOT) ||
        (type as? DexBackedClassDef)?.dexFile?.let { dex ->
            indirect.getOrPut(dex) { dex.callSiteSection.isNotEmpty() || dex.methodHandleSection.isNotEmpty() }
        } ?: true
    classDefForEach { type ->
        if (type.superclass == store.type) refuseQueue("final story store has a subclass ${type.type}")
        if (type.annotations.callsQueue(bridge, build) || type.fields.any {
                it.initialValue?.callsQueue(bridge, build) == true || it.annotations.callsQueue(bridge, build)
            }) refuseQueue("encoded story builder reference bypasses the protected queue")
        val reads = readsCode(type)
        for (method in type.methods) {
            if (method.annotations.callsQueue(bridge, build) || method.parameterAnnotations().any { it.callsQueue(bridge, build) }) {
                refuseQueue("encoded story builder reference bypasses the protected queue")
            }
            if (!reads) {
                method.executableBody()
                continue
            }
            for ((at, instruction) in method.code().withIndex()) {
                if (instruction.indirectlyCalls(bridge, build)) refuseQueue("indirect story builder reference bypasses the protected queue")
                val call = instruction.call() ?: continue
                if (call.same(bridge) || call.definingClass == OBJECT && call.name == bridge.name &&
                    call.parameterTypes.map(Any::toString) == retry.parameters && call.returnType == bridge.returnType) {
                    refuseQueue("concrete story builder has a caller outside the protected queue")
                }
                if (call.same(build)) callers += method to at
            }
        }
    }
    val (run, at) = callers.singleOrNull() ?: refuseQueue("expected one request-builder call, found ${callers.size}")
    if (run.definingClass != base.type || run.parameterTypes.isNotEmpty() || run.returnType != "V" ||
        AccessFlags.STATIC.isSet(run.accessFlags) || !AccessFlags.DECLARED_SYNCHRONIZED.isSet(run.accessFlags)) {
        refuseQueue("request builder isn't called by its owner's synchronized loop")
    }
    val code = Shape(run,
        Opcode.MOVE_OBJECT, Opcode.MONITOR_ENTER, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT,
        Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_4,
        Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.GOTO, Opcode.MONITOR_EXIT,
        Opcode.RETURN_VOID, Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW,
    )
    if (at != 20) refuseQueue("request builder moved within the loop")
    val self = run.localRegisterCount()
    val lock = code.reg(0)
    val iterator = code.reg(6)
    val key = code.reg(12)
    val item = code.reg(15)
    val request = code.reg(21)
    if (setOf(self, lock, iterator, key, item, request).size != 6 || code.reg(9) == iterator ||
        code.reg(18) in setOf(self, lock, iterator, key, item)) refuseQueue("loop aliases its live ownership registers")
    code.registers(0, lock, self)
    code.registers(1, lock)
    code.registers(2, self)
    code.registers(3, self)
    code.registers(5, code.reg(4))
    code.javaCall(5, "iterator", emptyList(), "Ljava/util/Iterator;")
    code.registers(7, iterator)
    requireIteratorCheck(code.call(7))
    code.registers(8, iterator)
    code.javaCall(8, "hasNext", emptyList(), "Z")
    code.registers(10, code.reg(9))
    code.branch(10, 34)
    code.registers(11, iterator)
    code.javaCall(11, "next", emptyList(), OBJECT)
    code.registers(13, key)
    if (code.reference(13)?.toString() != STRING) refuseQueue("key isn't a String")
    code.registers(14, self, key)
    code.registers(16, item)
    code.branch(16, 8)
    code.registers(17, self, key)
    code.registers(19, code.reg(18))
    code.branch(19, 8)
    code.registers(20, self, item)
    code.registers(27, request, code.reg(25))
    code.registers(28, self)
    if (code.call(28).definingClass != base.type || code.call(28).parameterTypes.isNotEmpty() || code.call(28).returnType != USER_SESSION) {
        refuseQueue("loop uses a foreign account getter")
    }
    if (!code.call(28).same(accountGetter) || store.methods.any { it.name == accountGetter.name &&
            it.parameterTypes.map(Any::toString) == accountGetter.parameterTypes.map(Any::toString) && it.returnType == USER_SESSION }) {
        refuseQueue("story selection doesn't use the loop's native account getter")
    }
    val account = base.requireAccountGetter(accountGetter)
    requireNativeUserId()
    val beforeBuild = bridge.code().take(retry.build).filter { it.opcode != Opcode.NOP }
    if (beforeBuild.map { it.opcode } != listOf(Opcode.CHECK_CAST, Opcode.INVOKE_STATIC, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT) ||
        beforeBuild[2].call()?.same(accountGetter) != true || beforeBuild[2].namedRegisters() != listOf(bridge.localRegisterCount()) ||
        beforeBuild[0].namedRegisters() != listOf(retry.batch) ||
        beforeBuild[1].namedRegisters() != listOf(retry.batch) ||
        bridge.code()[retry.build].namedRegisters() != listOf(retry.batch, beforeBuild[3].namedRegisters().single())) {
        refuseQueue("bridge doesn't build for its native account")
    }
    code.registers(32, code.reg(31), request)
    code.branch(33, 8)
    code.registers(34, lock)
    code.registers(37, lock)
    code.registers(38, code.reg(36))
    code.monitor(1, listOf(34, 37), 36, listOf(20, 21, 22))
    run.requireThisIntact(PATCH, listOf(0, 3, 14, 17, 20, 22, 28))
    if (17 in run.jumpTargets() || 20 in run.jumpTargets()) refuseQueue("a branch skips the cancellation site")
    if (self > 15 || key > 15 || item > 15) refuseQueue("operands exceed the cancellation instruction range")

    val claim = base.method(code.call(17))
    val move = Shape(claim,
        Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.IGET_OBJECT, Opcode.MONITOR_ENTER, Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.MONITOR_EXIT, Opcode.RETURN,
        Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_STRING, Opcode.INVOKE_STATIC,
        Opcode.INVOKE_INTERFACE, Opcode.MONITOR_EXIT, Opcode.CONST_4, Opcode.RETURN, Opcode.MOVE_EXCEPTION,
        Opcode.MONITOR_EXIT, Opcode.THROW,
    )
    claim.nativeMethod(listOf(STRING), "Z")
    val pending = move.field(4, LINKED_MAP, base.type)
    val inFlight = move.field(10, MAP, base.type)
    val monitor = move.field(2, OBJECT, base.type)
    val claimSelf = claim.localRegisterCount()
    val claimKey = claim.parameterRegisterNumber(0)
    if (setOf(move.reg(0), move.reg(2), move.reg(4), move.reg(6), claimSelf, claimKey).size != 6 ||
        move.reg(10) in setOf(move.reg(2), move.reg(4), move.reg(6), claimSelf, claimKey) ||
        move.reg(12) in setOf(move.reg(2), move.reg(10), move.reg(13), claimSelf, claimKey) ||
        move.reg(13) in setOf(move.reg(2), move.reg(10), claimSelf, claimKey)) refuseQueue("claim aliases its live ownership registers")
    move.registers(2, move.reg(2), claimSelf)
    move.registers(3, move.reg(2))
    move.registers(4, move.reg(4), claimSelf)
    move.registers(5, move.reg(4), claimKey)
    move.javaCall(5, "containsKey", listOf(OBJECT), "Z")
    move.registers(7, move.reg(6))
    move.branch(7, 10)
    move.literal(0, 0)
    move.registers(1, claimKey, move.reg(0))
    requireClaimAssertions(move.call(1), move.call(14))
    requireNonNullCheck(beforeBuild[1].call()!!, move.call(1))
    move.registers(9, move.reg(0))
    move.registers(10, move.reg(10), claimSelf)
    move.registers(11, move.reg(4), claimKey)
    move.javaCall(11, "remove", listOf(OBJECT), OBJECT)
    move.registers(14, move.reg(12), move.reg(13))
    move.registers(15, move.reg(10), claimKey, move.reg(12))
    move.javaCall(15, "put", listOf(OBJECT, OBJECT), OBJECT)
    move.literal(17, 1)
    move.registers(18, move.reg(17))
    move.monitor(3, listOf(8, 16, 20), 19, listOf(4, 5, 10, 11, 14, 15))
    if ((move.reference(13) as? StringReference)?.string != CLAIM_TAG) refuseQueue("claim lacks its native store marker")
    claim.requireParameterIntact(PATCH, 0, listOf(1, 5, 11, 15))
    claim.requireThisIntact(PATCH, listOf(2, 4, 10))
    base.requireOwnedMaps(pending, inFlight, monitor, account)
    base.requireCount(code.call(2), pending, inFlight, monitor)

    base.requireSnapshot(code.call(3), pending, monitor)
    base.requireLookup(code.call(14), pending, inFlight, monitor)
    requireDiskCleanup(store, reader, run, move.call(1))
    val scratch = run.freeLocalsAt(PATCH, 17, 1, targets = listOf(8)).single()
    return StoryRetryQueue(base.type, run.name, 17, 8, item, scratch)
}

/** Selection happens before the native builder, so a stock null request is never a cancellation signal. */
internal fun BytecodePatchContext.hookStoryRetryQueue(found: StorySeenTargets) {
    val queue = found.queue ?: return
    val run = mutableClassDefBy(queue.owner).methods.single { it.name == queue.run && it.parameterTypes.isEmpty() && it.returnType == "V" }
    val native = run.implementation!!.instructions[queue.beforeClaim]
    val next = run.implementation!!.instructions[queue.loop]
    run.addInstructionsWithLabels(queue.beforeClaim, """
        instance-of v${queue.scratch}, p0, ${found.store}
        if-eqz v${queue.scratch}, :native
        invoke-static { p0, v${queue.batch} }, $TO_RETRY
        move-result-object v${queue.batch}
        if-nez v${queue.batch}, :native
        goto :next
    """, ExternalLabel("native", native), ExternalLabel("next", next))
}

private fun ClassDef.requireOwnedMaps(pending: FieldReference, inFlight: FieldReference, monitor: FieldReference, account: FieldReference) {
    for (field in listOf(pending, inFlight, monitor, account)) {
        val declared = fields.singleOrNull { it.toString() == field.toString() } ?: refuseQueue("ownership field isn't declared")
        if (!AccessFlags.FINAL.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) refuseQueue("ownership field isn't final")
    }
    val constructor = methods.filter { it.name == "<init>" }.singleOrNull { it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) }
        ?: refuseQueue("has no unique account constructor")
    if (methods.count { it.name == "<init>" } != 1) refuseQueue("has another ownership constructor")
    val code = Shape(constructor, Opcode.INVOKE_DIRECT, Opcode.IPUT_OBJECT, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT,
        Opcode.IPUT_OBJECT, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.IPUT_OBJECT, Opcode.NEW_INSTANCE,
        Opcode.INVOKE_DIRECT, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID)
    if (constructor.implementation!!.tryBlocks.isNotEmpty() || !AccessFlags.CONSTRUCTOR.isSet(constructor.accessFlags) ||
        AccessFlags.NATIVE.isSet(constructor.accessFlags) || AccessFlags.ABSTRACT.isSet(constructor.accessFlags) ||
        AccessFlags.STATIC.isSet(constructor.accessFlags) || !AccessFlags.PUBLIC.isSet(constructor.accessFlags) ||
        code.call(0).toString() != "$OBJECT-><init>()V" || code.reference(1).toString() != account.toString()) {
        refuseQueue("constructor doesn't capture its native account")
    }
    code.registers(0, constructor.localRegisterCount())
    code.registers(1, constructor.parameterRegisterNumber(0), constructor.localRegisterCount())
    constructor.requireParameterIntact(PATCH, 0, listOf(1))
    for ((fresh, field, kind) in listOf(
        Triple(2, pending, LINKED_MAP), Triple(5, inFlight, HASH_MAP), Triple(8, monitor, OBJECT),
    )) {
        val start = fresh + 1
        val put = fresh + 2
        val register = code.reg(fresh)
        if ((code.reference(fresh) as? TypeReference)?.type != kind || code.call(start).toString() != "$kind-><init>()V" ||
            code.reference(put)?.toString() != field.toString()) refuseQueue("doesn't create its own native map and lock")
        code.registers(start, register)
        code.registers(put, register, constructor.localRegisterCount())
    }
    constructor.requireThisIntact(PATCH, listOf(0, 1, 4, 7, 10))
}

/** The account passed to the constructor is the account selection, retry and cleanup read. */
private fun ClassDef.requireAccountGetter(method: Method): FieldReference {
    if (method.definingClass != type || !AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags) ||
        AccessFlags.NATIVE.isSet(method.accessFlags) || AccessFlags.ABSTRACT.isSet(method.accessFlags) ||
        method.parameterTypes.isNotEmpty() || method.returnType != USER_SESSION || method.implementation?.tryBlocks?.isEmpty() != true) {
        refuseQueue("account getter isn't the native field read")
    }
    val code = Shape(method, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT)
    val field = code.field(0, USER_SESSION, type)
    code.registers(0, code.reg(0), method.localRegisterCount())
    code.registers(1, code.reg(0))
    if (code.reg(0) == method.localRegisterCount()) refuseQueue("account getter overwrites its receiver")
    return field
}

private fun BytecodePatchContext.requireNativeUserId() {
    val session = classDefByOrNull(USER_SESSION) ?: refuseQueue("has no native account type")
    if (!AccessFlags.FINAL.isSet(session.accessFlags)) refuseQueue("native account type isn't final")
    val getter = session.methods.singleOrNull { it.name == "getUserId" && it.parameterTypes.isEmpty() && it.returnType == STRING &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) &&
        !AccessFlags.NATIVE.isSet(it.accessFlags) && !AccessFlags.ABSTRACT.isSet(it.accessFlags) }
        ?: refuseQueue("has no native user ID getter")
    val code = Shape(getter, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT)
    val field = code.field(0, STRING, USER_SESSION)
    if (field.name != "userId" || getter.implementation!!.tryBlocks.isNotEmpty() || session.fields.singleOrNull { it.toString() == field.toString() }
            ?.let { AccessFlags.FINAL.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) } != true) {
        refuseQueue("user ID isn't its native final field")
    }
    code.registers(0, code.reg(0), getter.localRegisterCount())
    code.registers(1, code.reg(0))
    if (code.reg(0) == getter.localRegisterCount()) refuseQueue("user ID getter overwrites its receiver")
}

/** A fresh JDK snapshot iterator is non-null; its native assertion must immediately return. */
private fun BytecodePatchContext.requireIteratorCheck(reference: MethodReference) {
    val method = requireNonNullCheck(reference)
    // 450's Redex asks a pool of shared strings for the marker by number instead of loading it.
    val pooled = method.code().getOrNull(1)?.opcode != Opcode.CONST_STRING
    val code = if (pooled) Shape(method, Opcode.IF_NEZ, Opcode.CONST_16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.THROW, Opcode.RETURN_VOID)
    else Shape(method, Opcode.IF_NEZ, Opcode.CONST_STRING, Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT, Opcode.THROW, Opcode.RETURN_VOID)
    code.registers(0, method.parameterRegisterNumber(0))
    code.branch(0, if (pooled) 8 else 6)
    val marker = if (!pooled) (code.reference(1) as? StringReference)?.string else {
        code.registers(2, code.reg(1))
        val pool = code.call(2)
        if (pool.parameterTypes.map(Any::toString) != listOf("I") || pool.returnType != STRING) refuseQueue("iterator changed its native string pool")
        pooledString(pool, (method.code()[1] as NarrowLiteralInstruction).narrowLiteral)
    }
    if (marker != "INVOKE_RETURN") refuseQueue("iterator changed its native assertion marker")
}

/** The queue tests the batch before claim, so only this assertion's non-null branch can run. */
private fun BytecodePatchContext.requireNonNullCheck(reference: MethodReference, parameterCheck: MethodReference? = null): Method {
    val owner = classDefByOrNull(reference.definingClass) ?: refuseQueue("has no native non-null assertion")
    val method = owner.method(reference)
    if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || !AccessFlags.STATIC.isSet(method.accessFlags) ||
        AccessFlags.NATIVE.isSet(method.accessFlags) || AccessFlags.ABSTRACT.isSet(method.accessFlags) ||
        method.parameterTypes.map(Any::toString) != listOf(OBJECT) || method.returnType != "V" ||
        method.implementation?.tryBlocks?.isEmpty() != true || owner.methods.any { it.name == "<clinit>" } ||
        owner.superclass != OBJECT || owner.interfaces.isNotEmpty() || AccessFlags.INTERFACE.isSet(owner.accessFlags)) {
        refuseQueue("changed its native non-null assertion")
    }
    if (method.code().firstOrNull()?.opcode == Opcode.IF_NEZ) {
        method.requireNonNullReturn()
    } else {
        val wrapper = Shape(method, Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID)
        wrapper.literal(0, 0)
        wrapper.registers(1, method.parameterRegisterNumber(0), wrapper.reg(0))
        if (wrapper.reg(0) == method.parameterRegisterNumber(0) || wrapper.call(1).definingClass != owner.type ||
            parameterCheck != null && !wrapper.call(1).same(parameterCheck)) refuseQueue("non-null wrapper changed its native arguments")
        val check = owner.method(wrapper.call(1))
        if (!AccessFlags.PUBLIC.isSet(check.accessFlags) || !AccessFlags.STATIC.isSet(check.accessFlags) ||
            AccessFlags.NATIVE.isSet(check.accessFlags) || AccessFlags.ABSTRACT.isSet(check.accessFlags) ||
            check.parameterTypes.map(Any::toString) != listOf(OBJECT, "I") || check.returnType != "V" ||
            check.implementation?.tryBlocks?.isEmpty() != true) refuseQueue("non-null wrapper changed its native check")
        check.requireNonNullReturn()
    }
    return method
}

private fun Method.requireNonNullReturn() {
    val code = code()
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    if (code.firstOrNull()?.opcode != Opcode.IF_NEZ || code.first().namedRegisters() != listOf(parameterRegisterNumber(0)) ||
        code.lastOrNull()?.opcode != Opcode.RETURN_VOID ||
        (code.first() as OffsetInstruction).codeOffset != addresses[code.lastIndex]) {
        refuseQueue("non-null assertion doesn't return before any work")
    }
}

/** The ignored preselection result is a count, never an ownership operation. */
private fun ClassDef.requireCount(reference: MethodReference, pending: FieldReference, inFlight: FieldReference, monitor: FieldReference) {
    val method = method(reference)
    method.nativeMethod(emptyList(), "I")
    val code = Shape(method, Opcode.IGET_OBJECT, Opcode.MONITOR_ENTER, Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT, Opcode.ADD_INT_2ADDR,
        Opcode.MONITOR_EXIT, Opcode.RETURN, Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW)
    if (code.reference(0).toString() != monitor.toString() || code.reference(2).toString() != pending.toString() ||
        code.reference(5).toString() != inFlight.toString()) refuseQueue("count doesn't read its owned maps")
    if (setOf(method.localRegisterCount(), code.reg(0), code.reg(4), code.reg(7)).size != 4 || code.reg(4) == code.reg(5)) {
        refuseQueue("count aliases its native accumulator")
    }
    code.registers(0, code.reg(0), method.localRegisterCount())
    code.registers(1, code.reg(0))
    code.registers(2, code.reg(2), method.localRegisterCount())
    code.registers(3, code.reg(2))
    code.javaCall(3, "size", emptyList(), "I")
    code.registers(5, code.reg(5), method.localRegisterCount())
    code.registers(6, code.reg(5))
    code.javaCall(6, "size", emptyList(), "I")
    code.registers(8, code.reg(4), code.reg(7))
    code.registers(10, code.reg(4))
    code.monitor(1, listOf(9, 12), 11, listOf(2, 3, 5, 6))
    method.requireThisIntact(PATCH, listOf(0, 2, 5))
}

private fun ClassDef.requireSnapshot(reference: MethodReference, pending: FieldReference, monitor: FieldReference) {
    val method = method(reference)
    method.nativeMethod(emptyList(), ARRAY_LIST)
    val code = Shape(method, Opcode.IGET_OBJECT, Opcode.MONITOR_ENTER, Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.MONITOR_EXIT, Opcode.RETURN_OBJECT,
        Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW)
    if (code.reference(0).toString() != monitor.toString() || code.reference(2).toString() != pending.toString() ||
        code.reference(5).toString() != ARRAY_LIST || code.call(6).toString() != "$ARRAY_LIST-><init>(Ljava/util/Collection;)V") {
        refuseQueue("iteration isn't over an owned key snapshot")
    }
    code.registers(0, code.reg(0), method.localRegisterCount())
    code.registers(1, code.reg(0))
    code.registers(2, code.reg(2), method.localRegisterCount())
    code.registers(3, code.reg(2))
    code.javaCall(3, "keySet", emptyList(), "Ljava/util/Set;")
    code.registers(6, code.reg(5), code.reg(4))
    if (code.reg(5) == code.reg(4)) refuseQueue("snapshot overwrites the collection it copies")
    code.registers(8, code.reg(5))
    code.monitor(1, listOf(7, 10), 9, listOf(2, 3, 6))
    method.requireThisIntact(PATCH, listOf(0, 2))
}

private fun ClassDef.requireLookup(reference: MethodReference, pending: FieldReference, inFlight: FieldReference, monitor: FieldReference) {
    val method = method(reference)
    method.nativeMethod(listOf(STRING), OBJECT)
    val code = Shape(method, Opcode.IGET_OBJECT, Opcode.MONITOR_ENTER, Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT,
        Opcode.MONITOR_EXIT, Opcode.RETURN_OBJECT, Opcode.MOVE_EXCEPTION, Opcode.MONITOR_EXIT, Opcode.THROW)
    if (code.reference(0).toString() != monitor.toString() || code.reference(2).toString() != pending.toString() ||
        code.reference(6).toString() != inFlight.toString()) refuseQueue("lookup uses another store's maps")
    if (setOf(code.reg(0), code.reg(2), code.reg(4), method.localRegisterCount(), method.parameterRegisterNumber(0)).size != 5) {
        refuseQueue("lookup aliases its live ownership registers")
    }
    code.registers(0, code.reg(0), method.localRegisterCount())
    code.registers(1, code.reg(0))
    code.registers(2, code.reg(2), method.localRegisterCount())
    code.registers(3, code.reg(2), method.parameterRegisterNumber(0))
    code.javaCall(3, "containsKey", listOf(OBJECT), "Z")
    code.registers(5, code.reg(4))
    code.branch(5, 7)
    code.registers(6, code.reg(2), method.localRegisterCount())
    code.registers(7, code.reg(2), method.parameterRegisterNumber(0))
    code.javaCall(7, "get", listOf(OBJECT), OBJECT)
    code.registers(10, code.reg(8))
    code.monitor(1, listOf(9, 12), 11, listOf(2, 3, 6, 7))
    method.requireParameterIntact(PATCH, 0, listOf(3, 7))
    method.requireThisIntact(PATCH, listOf(0, 2, 6))
}

/**
 * 452's reader keys its disk copy with a private helper of the store ([requireDiskKey]): it reads
 * through the store's storage field with that key, runs the queue, then asks the helper again and
 * removes what it read. The receiver reaches both calls from its one field read.
 */
private fun BytecodePatchContext.requireDiskCleanup(store: ClassDef, reader: Method, run: Method, parameterCheck: MethodReference) {
    val code = reader.code()
    val at = code.indices.singleOrNull { code[it].call()?.same(run) == true } ?: refuseQueue("disk reader doesn't run the queue once")
    val self = reader.localRegisterCount()
    val cleanup = code.getOrNull(at + 3)?.call() ?: refuseQueue("disk reader no longer reaches its native cleanup after the loop")
    val storage = classDefByOrNull(cleanup.definingClass) ?: refuseQueue("disk cleanup has no native receiver class")
    val remove = storage.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map(Any::toString) == listOf(STRING) && method.returnType == "V" &&
            method.code().map { it.opcode } == listOf(Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.IGET_OBJECT, Opcode.NEW_INSTANCE,
                Opcode.INVOKE_DIRECT, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID)
    }.singleOrNull() ?: refuseQueue("disk reader no longer reaches its unique native cleanup")
    if (!cleanup.same(remove)) refuseQueue("disk reader no longer reaches its native cleanup after the loop")
    requireDiskRemoval(storage, remove, parameterCheck)
    val key = code.getOrNull(at + 1)?.call() ?: refuseQueue("disk cleanup has no native account key builder")
    requireDiskKey(store, key, run.code()[28].call()!!)
    val read = storage.methods.singleOrNull { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map(Any::toString) == listOf(STRING, "Z") && method.returnType == OBJECT
    } ?: refuseQueue("disk cleanup has no unique native read")
    if (code.size <= at + 3 || code[at].opcode != Opcode.INVOKE_VIRTUAL || code[at].namedRegisters() != listOf(self) ||
        code[at + 1].opcode != Opcode.INVOKE_DIRECT || !code[at + 1].call()!!.same(key) ||
        code[at + 2].opcode != Opcode.MOVE_RESULT_OBJECT || code[at + 3].opcode != Opcode.INVOKE_VIRTUAL ||
        !code[at + 3].call()!!.same(remove) ||
        code[at + 3].namedRegisters().lastOrNull() != code[at + 2].namedRegisters().singleOrNull() ||
        // A stock null disk read skips the queue and enters at the key, then still cleans up.
        reader.jumpTargets().any { it in at + 2..at + 3 }) refuseQueue("disk reader no longer reaches its native cleanup after the loop")
    if (code[at + 1].namedRegisters() != listOf(self)) refuseQueue("disk cleanup doesn't key by the store's own account")

    val cleanupRegisters = code[at + 3].namedRegisters()
    if (cleanupRegisters.size != 2) refuseQueue("disk cleanup doesn't use its account key")
    val receiver = cleanupRegisters.first()
    val receiverAt = (0 until at).singleOrNull { index ->
        code[index].opcode == Opcode.IGET_OBJECT && code[index].namedRegisters() == listOf(receiver, self) &&
            (code[index].reference() as? FieldReference)?.let { it.definingClass == reader.definingClass && it.type == storage.type } == true
    } ?: refuseQueue("disk cleanup doesn't use the store's native receiver")

    val readAt = (0 until at).singleOrNull { code[it].call()?.same(read) == true }
        ?: refuseQueue("disk cleanup has no unique native read")
    if (readAt < 3 || code[readAt].opcode != Opcode.INVOKE_VIRTUAL || code[readAt - 1].opcode != Opcode.CONST_4 ||
        (code[readAt - 1] as? NarrowLiteralInstruction)?.narrowLiteral != 1 ||
        code[readAt - 2].opcode != Opcode.MOVE_RESULT_OBJECT || code[readAt - 3].opcode != Opcode.INVOKE_DIRECT ||
        code[readAt - 3].call()?.same(key) != true || code[readAt - 3].namedRegisters() != listOf(self) ||
        code[readAt].namedRegisters() != listOf(receiver, code[readAt - 2].namedRegisters().single(), code[readAt - 1].namedRegisters().single()) ||
        code[readAt - 1].namedRegisters() == code[readAt - 2].namedRegisters() || reader.jumpTargets().any { it in readAt - 2..readAt }) {
        refuseQueue("disk cleanup no longer consumes the copy it read")
    }
    reader.requireOrigin(receiver, readAt, receiverAt)
    reader.requireOrigin(receiver, at + 3, receiverAt)
    reader.requireThisIntact(PATCH, listOf(at, at + 1, readAt - 3, receiverAt))
}

/** 452's parameter assertion, which builds its message in a StringBuilder of its own instead of calling a concat helper. */
private val PARAMETER_CHECK = listOf(Opcode.IF_NEZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.NEW_INSTANCE,
    Opcode.INVOKE_DIRECT, Opcode.CONST_STRING, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL,
    Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT,
    Opcode.INVOKE_STATIC, Opcode.THROW, Opcode.RETURN_VOID)

/**
 * Resolve the assertion helpers by the declared native bodies, never an obfuscated name. A build
 * may load the parameter message with const-string/jumbo where another has const-string, so the
 * bodies compare with a jumbo load read as a plain one, as [Shape] does.
 */
private fun BytecodePatchContext.requireClaimAssertions(parameter: MethodReference, present: MethodReference) {
    val owner = classDefByOrNull(parameter.definingClass) ?: refuseQueue("claim has no native assertions")
    val checkParameter = owner.methods.singleOrNull { AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes.map(Any::toString) == listOf(OBJECT, "I") && it.returnType == "V" &&
        it.code().map { instruction -> instruction.opcode.unjumbo() } == PARAMETER_CHECK &&
        it.code()[12].reference().toString() == "Ljava/lang/NullPointerException;" }
    val checkPresent = owner.methods.singleOrNull { AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes.map(Any::toString) == listOf(OBJECT, STRING) && it.returnType == "V" &&
        it.code().map { instruction -> instruction.opcode } == listOf(Opcode.IF_NEZ, Opcode.NEW_INSTANCE,
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC, Opcode.THROW, Opcode.RETURN_VOID) }
    if (checkParameter?.same(parameter) != true || checkPresent?.same(present) != true) refuseQueue("claim changed its native assertions")
    val parameterCode = Shape(checkParameter, *PARAMETER_CHECK.toTypedArray())
    val code = Shape(checkPresent, Opcode.IF_NEZ, Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC, Opcode.THROW, Opcode.RETURN_VOID)
    if (listOf(checkParameter, checkPresent).any { it.implementation!!.tryBlocks.isNotEmpty() }) refuseQueue("claim changes its assertion control flow")
    parameterCode.registers(0, checkParameter.parameterRegisterNumber(0))
    parameterCode.branch(0, 16)
    val builder = "Ljava/lang/StringBuilder;"
    val message = parameterCode.reg(3)
    if (parameterCode.call(1).toString() != "Ljava/lang/Integer;->toString(I)$STRING" ||
        parameterCode.reference(3).toString() != builder || parameterCode.call(4).toString() != "$builder-><init>()V" ||
        (parameterCode.reference(5) as? StringReference)?.string != "param at index = " ||
        parameterCode.call(6).toString() != "$builder->append($STRING)$builder" || parameterCode.call(7).toString() != parameterCode.call(6).toString() ||
        parameterCode.call(8).toString() !in setOf("$OBJECT->toString()$STRING", "$builder->toString()$STRING") ||
        parameterCode.call(13).toString() != "Ljava/lang/NullPointerException;-><init>($STRING)V") refuseQueue("claim changed its native parameter assertion")
    if (setOf(message, parameterCode.reg(2), parameterCode.reg(5)).size != 3) refuseQueue("claim changed its native parameter assertion")
    parameterCode.registers(1, checkParameter.parameterRegisterNumber(1))
    parameterCode.registers(4, message)
    parameterCode.registers(6, message, parameterCode.reg(5))
    parameterCode.registers(7, message, parameterCode.reg(2))
    parameterCode.registers(8, message)
    owner.methods.singleOrNull { it.same(parameterCode.call(10)) && AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes.map(Any::toString) == listOf(STRING) && it.returnType == STRING }
        ?: refuseQueue("claim changed its native assertion message")
    parameterCode.registers(10, parameterCode.reg(9))
    parameterCode.registers(13, parameterCode.reg(12), parameterCode.reg(11))
    parameterCode.registers(14, parameterCode.reg(12))
    parameterCode.registers(15, parameterCode.reg(12))
    code.registers(0, checkPresent.parameterRegisterNumber(0))
    code.branch(0, 5)
    if (code.reference(1).toString() != "Ljava/lang/NullPointerException;" ||
        code.call(2).toString() != "Ljava/lang/NullPointerException;-><init>($STRING)V") refuseQueue("claim changed its native null assertion")
    code.registers(2, code.reg(1), checkPresent.parameterRegisterNumber(1))
    val trim = owner.methods.singleOrNull { it.same(code.call(3)) && AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Throwable;") && it.returnType == "V" }
        ?: refuseQueue("claim changed its native assertion stack helper")
    if (!parameterCode.call(14).same(trim)) refuseQueue("claim changed its native assertion stack helper")
    code.registers(3, code.reg(1))
    code.registers(4, code.reg(1))
}

/**
 * The persisted key is precisely the pending-story prefix followed by the store's own account's
 * user ID, from a fresh StringBuilder. On 452 that's a private helper of the store asking the
 * loop's own [accountGetter], so nothing else can override it or key by another account.
 */
private fun requireDiskKey(store: ClassDef, reference: MethodReference, accountGetter: MethodReference) {
    val method = store.methods.singleOrNull { it.same(reference) }?.takeIf { reference.definingClass == store.type }
        ?: refuseQueue("disk cleanup has no native key builder")
    if (AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PRIVATE.isSet(method.accessFlags) ||
        method.parameterTypes.isNotEmpty() || method.returnType != STRING) refuseQueue("disk cleanup changes its native key builder")
    val code = Shape(method, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.IGET_OBJECT, Opcode.CONST_STRING,
        Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
        Opcode.RETURN_OBJECT)
    if (method.implementation!!.tryBlocks.isNotEmpty()) refuseQueue("disk cleanup changes its key control flow")
    if (!code.call(0).same(accountGetter)) refuseQueue("disk cleanup doesn't key by the store's native account")
    code.registers(0, method.localRegisterCount())
    if (code.reference(2).toString() != "$USER_SESSION->userId:$STRING") refuseQueue("disk cleanup doesn't key by the account's user ID")
    if ((code.reference(3) as? StringReference)?.string != DISK_PREFIX) refuseQueue("disk cleanup doesn't use the pending-story prefix")
    val builder = "Ljava/lang/StringBuilder;"
    if (code.reference(4).toString() != builder || code.call(5).toString() != "$builder-><init>($STRING)V" ||
        code.call(6).toString() != "$builder->append($STRING)$builder" ||
        code.call(7).toString() !in setOf("$OBJECT->toString()$STRING", "$builder->toString()$STRING")) refuseQueue("disk cleanup changes its native key builder")
    val id = code.reg(2)
    val prefix = code.reg(3)
    val key = code.reg(4)
    if (setOf(id, prefix, key).size != 3) refuseQueue("disk cleanup aliases its key parts")
    code.registers(2, id, code.reg(1))
    code.registers(5, key, prefix)
    code.registers(6, key, id)
    code.registers(7, key)
    code.registers(9, code.reg(8))
}

/** 449 schedules task 509, passing the same native storage and key to the backend's null-map write. */
private fun BytecodePatchContext.requireDiskRemoval(storage: ClassDef, method: Method, parameterCheck: MethodReference) {
    val code = Shape(method, Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.IGET_OBJECT, Opcode.NEW_INSTANCE,
        Opcode.INVOKE_DIRECT, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID)
    val task = (code.reference(3) as? TypeReference)?.type?.let { classDefByOrNull(it) } ?: refuseQueue("disk cleanup has no native task")
    val constructor = task.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(storage.type, STRING) }
        ?: refuseQueue("disk cleanup has no native task constructor")
    val run = task.methods.singleOrNull { it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuseQueue("disk cleanup has no native task body")
    val init = Shape(constructor, Opcode.IPUT_OBJECT, Opcode.CONST_16, Opcode.CONST_4, Opcode.CONST_4,
        Opcode.INVOKE_DIRECT, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID)
    if (setOf(init.reg(1), init.reg(2), init.reg(3), constructor.localRegisterCount(),
            constructor.parameterRegisterNumber(0), constructor.parameterRegisterNumber(1)).size != 6) refuseQueue("disk cleanup aliases its task arguments")
    init.literal(1, 509)
    init.literal(2, 3)
    init.literal(3, 0)
    val storageField = init.field(0, storage.type, task.type)
    val keyField = init.field(5, STRING, task.type)
    init.registers(0, constructor.parameterRegisterNumber(0), constructor.localRegisterCount())
    init.registers(5, constructor.parameterRegisterNumber(1), constructor.localRegisterCount())
    init.registers(4, constructor.localRegisterCount(), init.reg(1), init.reg(2), init.reg(3), init.reg(3))
    if (init.call(4).toString() != "${task.superclass}-><init>(IIZZ)V") refuseQueue("disk cleanup changes its native task identity")
    constructor.requireThisIntact(PATCH, listOf(0, 4, 5))
    constructor.requireParameterIntact(PATCH, 0, listOf(0))
    constructor.requireParameterIntact(PATCH, 1, listOf(5))

    val action = Shape(run, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
        Opcode.IGET_OBJECT, Opcode.CONST_4, Opcode.INVOKE_INTERFACE, Opcode.RETURN_VOID)
    val end = run.code().dropLast(1).sumOf { it.codeUnits }
    val handlerBlock = run.implementation!!.tryBlocks.singleOrNull()
    if (listOf(method, constructor).any { it.implementation!!.tryBlocks.isNotEmpty() } ||
        handlerBlock?.startCodeAddress != 0 || handlerBlock.codeUnitCount != end || handlerBlock.exceptionHandlers.singleOrNull()?.let {
            it.exceptionType == "Ljava/lang/IllegalStateException;" && it.handlerCodeAddress == end } != true) {
        refuseQueue("disk cleanup changes its task control flow")
    }
    if (action.reference(0).toString() != storageField.toString() || action.reference(3).toString() != keyField.toString()) {
        refuseQueue("disk cleanup changes its captured receiver or key")
    }
    val backendGetter = storage.methods.singleOrNull { it.same(action.call(1)) && AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes.map(Any::toString) == listOf(storage.type) }
        ?: refuseQueue("disk cleanup changes its native backend getter")
    val backend = classDefByOrNull(backendGetter.returnType) ?: refuseQueue("disk cleanup has no native backend")
    val erase = backend.methods.singleOrNull { it.parameterTypes.map(Any::toString) == listOf(STRING, MAP) && it.returnType == "V" }
        ?: refuseQueue("disk cleanup has no unique native null-map operation")
    if (!action.call(5).same(erase) || !AccessFlags.INTERFACE.isSet(backend.accessFlags)) refuseQueue("disk cleanup changes its native null-map operation")
    action.registers(0, action.reg(0), run.localRegisterCount())
    action.registers(1, action.reg(0))
    action.registers(3, action.reg(3), run.localRegisterCount())
    action.literal(4, 0)
    action.registers(5, action.reg(2), action.reg(3), action.reg(4))
    if (setOf(action.reg(2), action.reg(3), action.reg(4), run.localRegisterCount()).size != 4) refuseQueue("disk cleanup aliases its native arguments")
    run.requireThisIntact(PATCH, listOf(0, 3))

    code.literal(0, 0)
    if (setOf(code.reg(2), code.reg(3), method.localRegisterCount(), method.parameterRegisterNumber(0)).size != 4) {
        refuseQueue("disk cleanup aliases its captured task and executor")
    }
    code.registers(1, method.parameterRegisterNumber(0), code.reg(0))
    if (!code.call(1).same(parameterCheck) || !code.call(4).same(constructor)) refuseQueue("disk cleanup changes its native entry")
    code.registers(2, code.reg(2), method.localRegisterCount())
    val executor = (code.reference(2) as? FieldReference)?.takeIf { it.definingClass == storage.type }
        ?: refuseQueue("disk cleanup has a foreign executor")
    val executorClass = classDefByOrNull(executor.type) ?: refuseQueue("disk cleanup has no native executor")
    val submit = executorClass.methods.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes.map(Any::toString) == listOf(task.superclass) && it.returnType == "V" }
        ?: refuseQueue("disk cleanup has no unique native task submission")
    if (!code.call(5).same(submit)) refuseQueue("disk cleanup changes its native task submission")
    code.registers(4, code.reg(3), method.localRegisterCount(), method.parameterRegisterNumber(0))
    code.registers(5, code.reg(2), code.reg(3))
    method.requireThisIntact(PATCH, listOf(2, 4))
    method.requireParameterIntact(PATCH, 0, listOf(1, 4))
}

/** The named definition must reach the read on every normal or exceptional path, including loops. */
private fun Method.requireOrigin(register: Int, read: Int, definition: Int) {
    val flow = try { ControlFlow.of(this) } catch (_: IllegalArgumentException) { refuseQueue("disk reader has malformed control flow") }
    val seen = Array(flow.instructions.size) { mutableSetOf<Int>() }
    val todo = ArrayDeque<Pair<Int, Int>>()
    todo += 0 to -1
    while (todo.isNotEmpty()) {
        val (at, origin) = todo.removeFirst()
        if (!seen[at].add(origin)) continue
        val instruction = flow.instructions[at]
        val destination = (instruction as? OneRegisterInstruction)?.registerA
        val writes = instruction.opcode.setsRegister() && (destination == register || instruction.opcode.setsWideRegister() && destination == register - 1)
        val next = if (writes) at else origin
        flow.normal[at].forEach { todo += it to next }
        flow.exceptional[at].forEach { todo += it to origin }
    }
    if (seen[read] != setOf(definition)) refuseQueue("disk cleanup has changed receiver, prefix or account dataflow")
}

/** A string's load turns jumbo once the dex holds more strings than a short index reaches, as in 450. */
private fun Opcode.unjumbo(): Opcode = if (this == Opcode.CONST_STRING_JUMBO) Opcode.CONST_STRING else this

private class Shape(val method: Method, vararg expected: Opcode) {
    private val code = method.code()
    init {
        if (code.map { it.opcode.unjumbo() } != expected.toList()) {
            refuseQueue("${method.name} changed its native shape")
        }
    }
    fun reg(at: Int): Int = code[at].namedRegisters().first()
    fun literal(at: Int, value: Int) {
        if ((code[at] as? NarrowLiteralInstruction)?.narrowLiteral != value) refuseQueue("${method.name} changes its native literal at $at")
    }
    fun registers(at: Int, vararg expected: Int) {
        if (code[at].namedRegisters() != expected.toList()) refuseQueue("${method.name} changes ownership registers at $at")
    }
    fun reference(at: Int) = code[at].reference()
    fun call(at: Int): MethodReference = code[at].call() ?: refuseQueue("${method.name} lacks its native call at $at")
    fun javaCall(at: Int, name: String, parameters: List<String>, result: String) {
        val call = call(at)
        val owner = when (name) {
            "hasNext", "next" -> "Ljava/util/Iterator;"
            "iterator" -> "Ljava/util/AbstractCollection;"
            "containsKey", "remove", "keySet" -> "Ljava/util/AbstractMap;"
            "get", "put" -> MAP
            "size" -> if (code[at].opcode == Opcode.INVOKE_INTERFACE) MAP else "Ljava/util/AbstractMap;"
            else -> refuseQueue("unproved collection operation $name")
        }
        if (call.definingClass != owner || call.name != name || call.parameterTypes.map(Any::toString) != parameters ||
            call.returnType != result) refuseQueue("${method.name} changes its collection operation at $at")
    }
    fun field(at: Int, type: String, owner: String): FieldReference = (reference(at) as? FieldReference)
        ?.takeIf { it.type == type && it.definingClass == owner } ?: refuseQueue("${method.name} reads a foreign ownership field at $at")
    fun branch(at: Int, target: Int) {
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        if (addresses[at] + (code[at] as OffsetInstruction).codeOffset != addresses[target]) refuseQueue("${method.name} changes its loop at $at")
    }
    fun monitor(enter: Int, exits: List<Int>, handler: Int, protected: List<Int>) {
        val lock = code[enter].namedRegisters()
        val register = lock.single()
        if (code.drop(enter + 1).any { instruction ->
                val destination = (instruction as? OneRegisterInstruction)?.registerA
                instruction.opcode.setsRegister() && (destination == register || instruction.opcode.setsWideRegister() && destination == register - 1)
            }) refuseQueue("${method.name} overwrites its monitor")
        if (exits.any { code[it].namedRegisters() != lock } || code[handler].opcode != Opcode.MOVE_EXCEPTION ||
            code[handler + 1].opcode != Opcode.MONITOR_EXIT || code[handler + 2].opcode != Opcode.THROW ||
            code[handler + 2].namedRegisters() != code[handler].namedRegisters()) refuseQueue("${method.name} changes its lock cleanup")
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val blocks = method.implementation!!.tryBlocks
        if (blocks.isEmpty() || blocks.any { block -> block.exceptionHandlers.size != 1 ||
                block.exceptionHandlers.single().exceptionType != null || block.exceptionHandlers.single().handlerCodeAddress != addresses[handler] } ||
            protected.any { index -> blocks.none { addresses[index] >= it.startCodeAddress && addresses[index] < it.startCodeAddress + it.codeUnitCount } }) {
            refuseQueue("${method.name} lacks its native catch-all lock cleanup")
        }
    }
}

private fun Method.nativeMethod(parameters: List<String>, result: String) {
    if (!AccessFlags.PUBLIC.isSet(accessFlags) || !AccessFlags.FINAL.isSet(accessFlags) || AccessFlags.STATIC.isSet(accessFlags) ||
        AccessFlags.NATIVE.isSet(accessFlags) || AccessFlags.ABSTRACT.isSet(accessFlags) ||
        parameterTypes.map(Any::toString) != parameters || returnType != result) refuseQueue("$name isn't its native instance method")
}
private fun ClassDef.method(reference: MethodReference): Method = methods.singleOrNull { it.same(reference) }
    ?: refuseQueue("can't resolve ${reference.name} in the owner")
private fun Method.code(): List<Instruction> = executableBody()?.instructions?.toList() ?: emptyList()

/** This method's body, refusing one a native or abstract method carries, which never runs. */
private fun Method.executableBody(): MethodImplementation? {
    val body = implementation ?: return null
    if (AccessFlags.NATIVE.isSet(accessFlags) || AccessFlags.ABSTRACT.isSet(accessFlags)) refuseQueue("$name has no executable DEX body")
    return body
}
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.call() = reference() as? MethodReference
internal fun Instruction.indirectlyCalls(bridge: MethodReference, build: MethodReference) = when (val reference = reference()) {
    is MethodHandleReference -> (reference.memberReference as? MethodReference)?.isQueueBuilder(bridge, build) == true
    is CallSiteReference -> (reference.methodHandle.memberReference as? MethodReference)?.isQueueBuilder(bridge, build) == true ||
        reference.extraArguments.any { it.callsQueue(bridge, build) }
    else -> false
}
/**
 * Each parameter's annotations, as [Method.getParameters] pairs them. A method read from the app's
 * dex gives its sets directly, since getParameters also decodes every parameter's type and debug
 * name, which the declaration walk paid for on every method (#173).
 */
private fun Method.parameterAnnotations(): List<Set<DexAnnotation>> =
    (this as? DexBackedMethod)?.let { it.parameterAnnotations.take(it.parameterTypes.size) } ?: parameters.map { it.annotations }
private fun Iterable<DexAnnotation>.callsQueue(bridge: MethodReference, build: MethodReference): Boolean =
    any { annotation -> annotation.elements.any { it.value.callsQueue(bridge, build) } }

private fun EncodedValue.callsQueue(bridge: MethodReference, build: MethodReference): Boolean = when (this) {
    is MethodEncodedValue -> value.isQueueBuilder(bridge, build)
    is MethodHandleEncodedValue -> (value.memberReference as? MethodReference)?.isQueueBuilder(bridge, build) == true
    is ArrayEncodedValue -> value.any { it.callsQueue(bridge, build) }
    is AnnotationEncodedValue -> elements.any { it.value.callsQueue(bridge, build) }
    else -> false
}
private fun MethodReference.isQueueBuilder(bridge: MethodReference, build: MethodReference) = same(bridge) || same(build) ||
    definingClass == OBJECT && name == bridge.name && returnType == bridge.returnType &&
        parameterTypes.map(Any::toString) == bridge.parameterTypes.map(Any::toString)
private fun MethodReference.same(other: MethodReference) = definingClass == other.definingClass && name == other.name &&
    returnType == other.returnType && parameterTypes.map(Any::toString) == other.parameterTypes.map(Any::toString)
