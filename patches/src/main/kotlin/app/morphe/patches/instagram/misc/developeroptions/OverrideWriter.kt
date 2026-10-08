/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesHolding
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

/** Instagram's own override editor store logs these from its typed put. */
internal const val DEBUG_STORE = "QuickExperimentDebugStore"
internal const val PUT_FAILURE = "[putOverriddenParameter] MobileConfig failed to find "

/** Instagram's own bug import refuses with this when its manager isn't the native one. */
internal const val RUNTIME_NOT_READY = "MobileConfig xplat runtime is not ready yet"

internal const val MANAGER_IMPL = "Lcom/facebook/mobileconfig/MobileConfigManagerHolderImpl;"
internal const val TABLE_IMPL = "Lcom/facebook/mobileconfig/MobileConfigOverridesTableHolder;"
private const val USER = "Lcom/instagram/common/session/UserSession;"
private const val TABLE_FACTORY = "getOrCreateOverridesTable"

/** The value types Instagram's typed put writes, and the native writer each one reaches. */
internal val OVERRIDE_VALUES = linkedMapOf(
    "Z" to "updateOverrideForBool", "J" to "updateOverrideForInt",
    "D" to "updateOverrideForDouble", "Ljava/lang/String;" to "updateOverrideForString",
)

/**
 * The decoder's type codes and the value each one's typed writer takes, in the order the
 * extension's OverrideImport.write switches on them. The patch proves Instagram's put sends each
 * code to this writer before any stub changes.
 */
internal val DECODED_VALUES = linkedMapOf(1 to "Z", 2 to "J", 3 to "Ljava/lang/String;", 4 to "D")

/**
 * Native entry points the writer never reaches: string imports whose argument is a report or a
 * user to fetch from, whole-table wipes, and the reload nothing in Instagram calls.
 */
internal val FORBIDDEN_WRITES = setOf(
    "importOverridesFromUser", "importOverridesFromBug", "loadOverridesFromBugAndSaveResponse",
    "removeAllOverrides", "clearOverrides", "reload", "deleteManagerDirs", "removeOverridesForQEUniverse",
    "updateOverrideForQE", "clearCurrentUserData",
)

/** The extension's writer stubs: name to (parameters, return type). Each is static and found once. */
internal val WRITER_STUBS = linkedMapOf(
    "getOverrideTableNative" to ("Ljava/lang/Object;" to "Ljava/lang/Object;"),
    "setOverrideBooleanNative" to ("Ljava/lang/Object;JI" to "I"),
    "setOverrideLongNative" to ("Ljava/lang/Object;JJ" to "I"),
    "setOverrideDoubleNative" to ("Ljava/lang/Object;JD" to "I"),
    "setOverrideStringNative" to ("Ljava/lang/Object;JLjava/lang/String;" to "I"),
    "removeOverrideNative" to ("Ljava/lang/Object;J" to "I"),
    "getOverrideTypeNative" to ("J" to "I"),
)

/** How the typed put reads a parameter ID's type code. The type stub reads it the same way. */
internal sealed interface TypeDecoder {
    /** 449: a public static (J)I call. */
    data class Call(val method: String) : TypeDecoder

    /** 450: the ID shifted right [shift] bits and masked with [mask], in the put itself. */
    data class Bits(val shift: Int, val mask: Int) : TypeDecoder
}

/** Where the typed put has the type code: the parameter ID's register pair, the code's register, and the instruction after. */
private class DecodeSite(val id: List<Int>, val result: Int, val next: Int)

internal data class OverrideWriter(
    val delegate: String, val gate: String, val tableGetter: String, val managerGetter: String, val table: String,
    val updates: Map<String, String>, val remove: String, val decoder: TypeDecoder,
    /** Decoder code to the typed writer Instagram's put sends it to. */
    val dispatch: Map<Int, String>,
    /** The filled stubs, assembled while finding. */
    val stubs: PreparedStubs,
)

/**
 * Replacement stub bodies, assembled while their anchors are found, so putting them in can't fail
 * after any other stub or hook has changed.
 */
internal class PreparedStubs(val stubs: List<Method>, val replacements: List<MutableMethod>)

/** Swaps prepared bodies in. Nothing here assembles or refuses. */
internal fun BytecodePatchContext.putStubs(prepared: PreparedStubs) {
    val owner = mutableClassDefBy(OVERRIDE_BRIDGE)
    prepared.stubs.forEach { owner.methods.remove(it) }
    owner.methods.addAll(prepared.replacements)
}

/** Builds each body from smali, refusing through [refuse] when one doesn't assemble. */
internal fun prepareStubs(stubs: List<Method>, bodies: List<Pair<Int, String>>, refuse: (String) -> Nothing): PreparedStubs {
    val replacements = stubs.zip(bodies).map { (old, body) ->
        runCatching {
            ImmutableMethod(old.definingClass, old.name, old.parameters, old.returnType, old.accessFlags, old.annotations,
                old.hiddenApiRestrictions, ImmutableMethodImplementation(body.first, emptyList(), null, null)).toMutable().apply {
                addInstructionsWithLabels(0, body.second)
            }
        }.getOrElse { refuse("${old.name} doesn't assemble") }
    }
    return PreparedStubs(stubs, replacements)
}

/**
 * Resolves the writer Instagram's own override editor uses: the typed put and remove of its debug
 * store, the table its store factory takes from the session [model]'s manager, and the native
 * table class behind it. Every check runs before any stub changes, and nothing here reaches a
 * string import, a wipe or a reload.
 */
internal fun BytecodePatchContext.findOverrideWriter(model: String): OverrideWriter {
    val puts = mutableListOf<Method>()
    classesHolding(DEBUG_STORE, PUT_FAILURE).forEach { clazz ->
        clazz.methods.filterTo(puts) { it.texts().containsAll(listOf(DEBUG_STORE, PUT_FAILURE)) }
    }
    val put = puts.only("typed override put")
    val store = put.definingClass
    val putCode = put.implementation!!.instructions.toList()

    // The four typed puts are interface calls on one table type, each made once.
    val updateCalls = putCode.filter { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE }
        .mapNotNull { it.reference() }.filter { it.name == "updateOverrideForParam" }
    val table = updateCalls.map { it.definingClass }.distinct().only("override table interface")
    val updates = OVERRIDE_VALUES.keys.associateWith { value ->
        updateCalls.filter { it.parameterTypes.map(Any::toString) == listOf("J", value) && it.returnType == "V" }
            .only("typed put for $value").toString()
    }
    if (updateCalls.size != OVERRIDE_VALUES.size) writerRefuse("typed put has unexpected override calls")
    if (putCode.any { it.reference()?.name in FORBIDDEN_WRITES }) writerRefuse("typed put reaches a bulk or string import")
    val calls = putCode.filter { it.opcode == Opcode.INVOKE_STATIC }.mapNotNull { it.reference() }
        .filter { it.parameterTypes.map(Any::toString) == listOf("J") && it.returnType == "I" }
        .distinctBy(Any::toString)
    val (decoder, site) = if (calls.isNotEmpty()) {
        val call = calls.only("override type decoder")
        publicStatic(call)
        TypeDecoder.Call(call.toString()) to callSite(putCode, call)
    } else inlineDecoder(putCode)
    val dispatch = put.requireDispatch(putCode, site, updates)

    // The interface declares what the store calls, plus the remove its reset calls.
    val tableClass = writerClass(table)
    if (!AccessFlags.INTERFACE.isSet(tableClass.accessFlags) || !AccessFlags.PUBLIC.isSet(tableClass.accessFlags)) {
        writerRefuse("override table isn't a public interface")
    }
    val remove = "$table->removeOverrideForParam(J)V"
    for (reference in updates.values + remove) {
        if (tableClass.methods.none { it.toString() == reference }) writerRefuse("override table doesn't declare its typed writer")
    }
    val removers = writerClass(store).methods.filter { method ->
        method.implementation?.instructions?.any { it.reference()?.toString() == remove } == true
    }
    val remover = removers.only("typed override remove")
    if (remover.implementation!!.instructions.count { it.reference()?.toString() == remove } != 1) writerRefuse("typed remove isn't a single call")

    // Behind the interface sits the native table, each typed put a plain hop to its native writer.
    val native = writerClass(TABLE_IMPL)
    if (!AccessFlags.PUBLIC.isSet(native.accessFlags) || table !in native.interfaces) {
        writerRefuse("native override table doesn't implement the store's table")
    }
    for ((value, writer) in OVERRIDE_VALUES) {
        val target = "$TABLE_IMPL->$writer(J$value)V"
        if (native.methods.none { it.toString() == target && it.isNativeInstance() }) writerRefuse("native table has no $writer")
        val bridge = native.methods.filter { it.toString() == "$TABLE_IMPL->updateOverrideForParam(J$value)V" }
            .only("native typed put for $value")
        val code = bridge.implementation?.instructions?.toList().orEmpty()
        if (code.size != 2 || code[0].opcode != Opcode.INVOKE_VIRTUAL || code[0].reference()?.toString() != target ||
            code[1].opcode != Opcode.RETURN_VOID) writerRefuse("native typed put doesn't go straight to $writer")
    }
    if (native.methods.none { it.toString() == "$TABLE_IMPL->removeOverrideForParam(J)V" && it.isNativeInstance() }) {
        writerRefuse("native table has no native remove")
    }

    // The session manager's table: the base class names it, the native manager makes the native table.
    val manager = writerClass(MANAGER_IMPL)
    val base = manager.superclass ?: writerRefuse("native manager has no base")
    val tableGetter = "$base->$TABLE_FACTORY()$table"
    if (writerClass(base).methods.none { it.toString() == tableGetter && it.isPublicInstance() }) writerRefuse("manager base has no table getter")
    val ownGetter = manager.methods.filter { it.name == TABLE_FACTORY && it.parameterTypes.isEmpty() && it.returnType == table }
        .only("native manager table getter")
    if (!ownGetter.isPublicInstance() || !AccessFlags.PUBLIC.isSet(manager.accessFlags)) writerRefuse("native manager table getter isn't public")
    if (ownGetter.implementation?.instructions?.none { instruction ->
            instruction.reference()?.let { it.definingClass == MANAGER_IMPL && it.parameterTypes.isEmpty() && it.returnType == TABLE_IMPL } == true
        } != false) writerRefuse("native manager doesn't make the native table")

    // Instagram's store factory takes that table from the session manager's delegate.
    val delegate = storeFactory(store, model, base, tableGetter, putCode)
    val gates = writerClass(delegate.returnType).methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(Any::toString) == listOf(base) && method.returnType == MANAGER_IMPL
    }
    val gate = gates.only("native manager gate")
    if (gate.implementation?.instructions?.none { it.opcode == Opcode.INSTANCE_OF &&
            ((it as ReferenceInstruction).reference as? TypeReference)?.type == MANAGER_IMPL } != false) {
        writerRefuse("native manager gate doesn't check the native manager")
    }
    var gateUsers = 0
    classesHolding(RUNTIME_NOT_READY).forEach { clazz ->
        gateUsers += clazz.methods.count { method ->
            RUNTIME_NOT_READY in method.texts() && method.implementation!!.instructions.any { it.reference()?.toString() == gate.toString() }
        }
    }
    if (gateUsers == 0) writerRefuse("no native-ready check uses the manager gate")
    val found = OverrideWriter(delegate.toString(), gate.toString(), tableGetter, ownGetter.toString(), table, updates, remove,
        decoder, dispatch, PreparedStubs(emptyList(), emptyList()))
    return found.copy(stubs = prepareStubs(writerStubs(), writerBodies(found, model), ::writerRefuse))
}

/** 449's decoder call: the one ID it reads and the move-result that keeps its code. */
private fun callSite(code: List<Instruction>, decoder: MethodReference): DecodeSite {
    val call = code.indices.filter { code[it].opcode == Opcode.INVOKE_STATIC && code[it].reference()?.toString() == decoder.toString() }
        .only("override type decoder call")
    val id = code[call].arguments()
    if (id.size != 2 || id[1] != id[0] + 1) writerRefuse("override type decoder doesn't read one parameter ID")
    val result = (code.getOrNull(call + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction)?.registerA
        ?: writerRefuse("typed put doesn't keep the decoder's code")
    return DecodeSite(id, result, call + 2)
}

/**
 * 450's decoder, written into the put: a shift count, the ID shifted right by it, a mask, the
 * shifted ID masked in place, and that cut to an int. The type stub repeats it, so the mask must
 * fit an int.
 */
private fun inlineDecoder(code: List<Instruction>): Pair<TypeDecoder, DecodeSite> {
    val sites = (4 until code.size).filter { at ->
        val shift = code[at - 4]; val shifted = code[at - 3]; val mask = code[at - 2]; val masked = code[at - 1]; val cut = code[at]
        cut.opcode == Opcode.LONG_TO_INT && masked.opcode == Opcode.AND_LONG_2ADDR && shifted.opcode == Opcode.USHR_LONG &&
            mask.opcode in WIDE_CONSTANTS && shift.opcode in NARROW_CONSTANTS &&
            (cut as TwoRegisterInstruction).registerB == (masked as TwoRegisterInstruction).registerA &&
            masked.registerB == (mask as OneRegisterInstruction).registerA &&
            (shifted as ThreeRegisterInstruction).registerA == masked.registerA &&
            shifted.registerC == (shift as OneRegisterInstruction).registerA
    }
    val at = sites.only("override type decoder")
    val mask = (code[at - 2] as WideLiteralInstruction).wideLiteral
    if (mask !in 0..Int.MAX_VALUE.toLong()) writerRefuse("override type decoder's mask doesn't fit an int")
    val id = (code[at - 3] as ThreeRegisterInstruction).registerB
    return TypeDecoder.Bits((code[at - 4] as NarrowLiteralInstruction).narrowLiteral, mask.toInt()) to
        DecodeSite(listOf(id, id + 1), (code[at] as OneRegisterInstruction).registerA, at + 1)
}

private val NARROW_CONSTANTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)
private val WIDE_CONSTANTS = setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE)

/**
 * Proves the typed put branches on the decoder's code. Walking the put from the decoder's result,
 * with only that code known, code k reaches exactly one typed writer, the one [DECODED_VALUES]
 * names for k, and that writer gets the same parameter ID the decoder read. Returns code to writer.
 */
private fun Method.requireDispatch(code: List<Instruction>, site: DecodeSite, updates: Map<String, String>): Map<Int, String> {
    val flow = PutFlow(this, code)
    return DECODED_VALUES.entries.associate { (decoded, value) ->
        val writer = updates.getValue(value)
        if (flow.writersReached(site.next, site.result, decoded, site.id) != setOf(writer)) {
            writerRefuse("typed put doesn't send decoder code $decoded only to its $value writer")
        }
        decoded to writer
    }
}

/**
 * Follows the put's branches with the registers holding known integer constants. A branch whose
 * operands aren't both known goes both ways, and an instruction inside a try block may also go to
 * its handlers, so the writers reached are never fewer than on the device.
 */
private class PutFlow(method: Method, private val code: List<Instruction>) {
    private val addresses = IntArray(code.size + 1).also { address ->
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    }
    private val indexAt = code.indices.associateBy { addresses[it] }
    private val tries = method.implementation!!.tryBlocks.map { block ->
        Triple(block.startCodeAddress, block.startCodeAddress + block.codeUnitCount, block.exceptionHandlers.map { index(it.handlerCodeAddress) })
    }
    private val leaving = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE, Opcode.THROW)
    private val payloads = setOf(Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD)

    private data class Point(val index: Int, val known: Map<Int, Int>, val idKept: Boolean)

    private fun index(address: Int) = indexAt[address] ?: writerRefuse("typed put branches outside its code")

    fun writersReached(start: Int, register: Int, decoded: Int, id: List<Int>): Set<String> {
        val reached = mutableSetOf<String>()
        val seen = mutableSetOf<Point>()
        val pending = ArrayDeque(listOf(Point(start, mapOf(register to decoded), true)))
        while (pending.isNotEmpty()) {
            val point = pending.removeLast()
            if (!seen.add(point)) continue
            if (seen.size > 100_000) writerRefuse("typed put flow is too large to follow")
            val instruction = code.getOrNull(point.index) ?: writerRefuse("typed put runs past its code")
            if (instruction.opcode in payloads) writerRefuse("typed put runs into its data")
            val called = instruction.reference()
            if (called?.name == "updateOverrideForParam") {
                val arguments = instruction.arguments()
                reached += if (point.idKept && arguments.getOrNull(1) == id[0] && arguments.getOrNull(2) == id[1]) called.toString()
                    else "a write to another parameter"
            }
            if (instruction.opcode.canThrow()) {
                val address = addresses[point.index]
                tries.filter { address >= it.first && address < it.second }.flatMap { it.third }
                    .forEach { pending += Point(it, point.known, point.idKept) }
            }
            val known = point.known.toMutableMap()
            var idKept = point.idKept
            fun clobber(written: Int) { known.remove(written); if (written in id) idKept = false }
            when {
                instruction is NarrowLiteralInstruction && instruction is OneRegisterInstruction && !instruction.opcode.setsWideRegister() -> {
                    clobber(instruction.registerA); known[instruction.registerA] = instruction.narrowLiteral
                }
                instruction.opcode == Opcode.MOVE || instruction.opcode == Opcode.MOVE_FROM16 || instruction.opcode == Opcode.MOVE_16 -> {
                    val move = instruction as TwoRegisterInstruction
                    val value = known[move.registerB]
                    clobber(move.registerA); if (value != null) known[move.registerA] = value
                }
                instruction.opcode.setsRegister() -> {
                    val written = (instruction as OneRegisterInstruction).registerA
                    clobber(written); if (instruction.opcode.setsWideRegister()) clobber(written + 1)
                }
            }
            val next = Point(point.index + 1, known, idKept)
            fun at(address: Int) = Point(index(address), known, idKept)
            val here = addresses[point.index]
            when (instruction.opcode) {
                in leaving -> Unit
                Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> pending += at(here + (instruction as OffsetInstruction).codeOffset)
                Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                    val payload = code[index(here + (instruction as OffsetInstruction).codeOffset)] as? SwitchPayload
                        ?: writerRefuse("typed put has an unreadable switch")
                    val value = known[(instruction as OneRegisterInstruction).registerA]
                    val cases = payload.switchElements.filter { value == null || it.key == value }
                    cases.forEach { pending += at(here + it.offset) }
                    if (value == null || cases.isEmpty()) pending += next
                }
                else -> {
                    if (instruction !is OffsetInstruction) { pending += next; continue }
                    val taken = taken(instruction, known)
                    if (taken != false) pending += at(here + instruction.codeOffset)
                    if (taken != true) pending += next
                }
            }
        }
        return reached
    }

    /** Whether a conditional branch is taken: true or false when its operands are known, null when either way. */
    private fun taken(instruction: Instruction, known: Map<Int, Int>): Boolean? {
        val (left, right) = when (instruction.opcode) {
            Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE -> (instruction as TwoRegisterInstruction)
                .let { known[it.registerA] to known[it.registerB] }
            Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ ->
                known[(instruction as OneRegisterInstruction).registerA] to 0
            else -> writerRefuse("typed put has an unknown branch")
        }
        if (left == null || right == null) return null
        return when (instruction.opcode) {
            Opcode.IF_EQ, Opcode.IF_EQZ -> left == right
            Opcode.IF_NE, Opcode.IF_NEZ -> left != right
            Opcode.IF_LT, Opcode.IF_LTZ -> left < right
            Opcode.IF_GE, Opcode.IF_GEZ -> left >= right
            Opcode.IF_GT, Opcode.IF_GTZ -> left > right
            else -> left <= right
        }
    }
}

/**
 * The one method that builds the debug store for a session. It asks the session [model] for its
 * manager delegate, asks that delegate for [tableGetter] and keeps the result in the field the
 * store's put reads. Returns the delegate accessor.
 */
private fun BytecodePatchContext.storeFactory(store: String, model: String, base: String, tableGetter: String,
                                              putCode: List<Instruction>): MethodReference {
    val factories = mutableListOf<Method>()
    val creating = classesCreating(store).mapTo(HashSet()) { it.type }
    classDefForEach { clazz ->
        if (clazz.type !in creating || clazz.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        clazz.methods.filterTo(factories) { method ->
            method.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE &&
                ((it as ReferenceInstruction).reference as? TypeReference)?.type == store } == true
        }
    }
    val factory = factories.only("override store factory")
    if (!AccessFlags.STATIC.isSet(factory.accessFlags) || factory.parameterTypes.map(Any::toString) != listOf(USER) ||
        factory.returnType != store) writerRefuse("override store factory doesn't build the store for a session")
    val code = factory.implementation!!.instructions.toList()
    val delegateIndex = code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_VIRTUAL && code[index].reference()?.let {
            it.definingClass == model && it.parameterTypes.isEmpty() && writerSuper(it.returnType) == base
        } == true
    }.only("session manager delegate call")
    val delegate = code[delegateIndex].reference()!!
    val owner = writerClass(model)
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || owner.methods.none { it.toString() == delegate.toString() && it.isPublicInstance() }) {
        writerRefuse("session manager delegate isn't public")
    }
    val delegateResult = (code.getOrNull(delegateIndex + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)
        ?.registerA ?: writerRefuse("session manager delegate isn't kept")
    val tableIndex = code.indices.filter { code[it].reference()?.toString() == tableGetter }.only("store factory table call")
    if (tableIndex <= delegateIndex + 1 || code[tableIndex].arguments().singleOrNull() != delegateResult) {
        writerRefuse("store factory asks another object for the table")
    }
    factory.requireStraight(code, delegateIndex + 1, tableIndex, delegateResult)
    val tableResult = (code.getOrNull(tableIndex + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)
        ?.registerA ?: writerRefuse("store factory doesn't keep the table")
    val kept = (code.getOrNull(tableIndex + 2)?.takeIf { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == tableResult }
        as? ReferenceInstruction)?.reference as? FieldReference ?: writerRefuse("store factory doesn't keep the table")
    if (putCode.none { it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference.toString() == kept.toString() }) {
        writerRefuse("typed put reads another table")
    }
    return delegate
}

/** Nothing between the delegate's result and the table call may write it, leave, or be jumped into. */
private fun Method.requireStraight(code: List<Instruction>, from: Int, to: Int, register: Int) {
    val addresses = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> addresses[index + 1] = addresses[index] + instruction.codeUnits }
    fun inside(address: Int) = address > addresses[from] && address <= addresses[to]
    val leaving = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE, Opcode.THROW)
    for (index in from + 1 until to) {
        val instruction = code[index]
        if (instruction is OffsetInstruction || instruction.opcode in leaving) writerRefuse("store factory table flow isn't straight-line")
        val written = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        if (instruction.opcode.setsRegister() && (written == register || (instruction.opcode.setsWideRegister() && written + 1 == register))) {
            writerRefuse("store factory overwrites the delegate before the table call")
        }
    }
    for ((index, instruction) in code.withIndex()) {
        if (instruction !is OffsetInstruction) continue
        val target = addresses[index] + instruction.codeOffset
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            val payload = addresses.indexOf(target).takeIf { it in code.indices }?.let { code[it] as? SwitchPayload }
                ?: writerRefuse("store factory has an unreadable switch")
            if (payload.switchElements.any { inside(addresses[index] + it.offset) }) writerRefuse("a switch enters the store factory table flow")
        } else if (inside(target)) writerRefuse("another branch enters the store factory table flow")
    }
    if (implementation!!.tryBlocks.any { block -> block.exceptionHandlers.any { inside(it.handlerCodeAddress) } }) {
        writerRefuse("an exception handler enters the store factory table flow")
    }
}

/**
 * The writer stubs' bodies, in [WRITER_STUBS] order. Each setter refuses anything but the native
 * table, then makes the same typed call Instagram's editor makes and answers 1. The table stub
 * answers null unless the gate unwraps the session's manager to the native one, and asks that
 * native manager, not the delegate, for its table, so the Java table, whose writers throw, is
 * never reached.
 */
private fun writerBodies(writer: OverrideWriter, model: String): List<Pair<Int, String>> {
    fun setter(arguments: String, reference: String, vararg prelude: String) = listOf(
        "instance-of v0, p0, $TABLE_IMPL", "if-eqz v0, :unavailable", "check-cast p0, ${writer.table}",
    ) + prelude + listOf(
        "invoke-interface { $arguments }, $reference", "const/4 v0, 0x1", "return v0",
        ":unavailable", "const/4 v0, 0x0", "return v0",
    )
    val bodies = mapOf(
        "getOverrideTableNative" to (2 to listOf(
            "instance-of v0, p0, $model", "if-eqz v0, :unavailable", "check-cast p0, $model",
            "invoke-virtual { p0 }, ${writer.delegate}", "move-result-object v0", "if-eqz v0, :unavailable",
            "invoke-static { v0 }, ${writer.gate}", "move-result-object v1", "if-eqz v1, :unavailable",
            "check-cast v1, $MANAGER_IMPL", "invoke-virtual { v1 }, ${writer.managerGetter}", "move-result-object v0",
            "instance-of v1, v0, $TABLE_IMPL", "if-eqz v1, :unavailable", "return-object v0",
            ":unavailable", "const/4 v0, 0x0", "return-object v0",
        )),
        "setOverrideBooleanNative" to (2 to setter("p0, p1, p2, v1", writer.updates.getValue("Z"),
            "const/4 v1, 0x0", "if-eqz p3, :write", "const/4 v1, 0x1", ":write")),
        "setOverrideLongNative" to (1 to setter("p0, p1, p2, p3, p4", writer.updates.getValue("J"))),
        "setOverrideDoubleNative" to (1 to setter("p0, p1, p2, p3, p4", writer.updates.getValue("D"))),
        "setOverrideStringNative" to (1 to setter("p0, p1, p2, p3", writer.updates.getValue("Ljava/lang/String;"))),
        "removeOverrideNative" to (1 to setter("p0, p1, p2", writer.remove)),
        "getOverrideTypeNative" to (1 to when (val decoder = writer.decoder) {
            is TypeDecoder.Call -> listOf("invoke-static { p0, p1 }, ${decoder.method}", "move-result v0", "return v0")
            is TypeDecoder.Bits -> listOf(
                "const v0, ${decoder.shift}", "ushr-long p0, p0, v0", "long-to-int p0, p0",
                "const v0, ${decoder.mask}", "and-int/2addr v0, p0", "return v0",
            )
        }),
    )
    return WRITER_STUBS.map { (name, shape) ->
        val (locals, body) = bodies.getValue(name)
        val parameters = Regex("L[^;]+;|.").findAll(shape.first).sumOf { if (it.value == "J" || it.value == "D") 2L else 1L }.toInt()
        locals + parameters to body.joinToString("\n")
    }
}

/** Puts in the writer bodies [findOverrideWriter] assembled. */
internal fun BytecodePatchContext.fillOverrideWriter(writer: OverrideWriter) = putStubs(writer.stubs)

private fun BytecodePatchContext.writerStubs(): List<Method> {
    val bridge = writerClass(OVERRIDE_BRIDGE)
    return WRITER_STUBS.map { (name, shape) ->
        bridge.methods.filter { method ->
            method.name == name && method.parameterTypes.joinToString("") == shape.first &&
                method.returnType == shape.second && AccessFlags.STATIC.isSet(method.accessFlags)
        }.only("extension $name bridge")
    }
}

private fun BytecodePatchContext.writerClass(type: String): ClassDef = runCatching { classDefBy(type) }.getOrNull()
    ?: writerRefuse("missing native writer class")
private fun BytecodePatchContext.writerSuper(type: String): String? = runCatching { classDefBy(type) }.getOrNull()?.superclass
private fun BytecodePatchContext.publicStatic(reference: MethodReference) {
    val owner = writerClass(reference.definingClass)
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || owner.methods.none {
            it.toString() == reference.toString() && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        }) writerRefuse("override type decoder isn't public static")
}
private fun Method.isNativeInstance() = AccessFlags.NATIVE.isSet(accessFlags) && AccessFlags.PUBLIC.isSet(accessFlags) &&
    !AccessFlags.STATIC.isSet(accessFlags)
private fun Method.isPublicInstance() = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags)
private fun Method.texts() = implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> writerRefuse("unreadable native writer call arguments")
}
private fun <T> List<T>.only(part: String): T = singleOrNull() ?: writerRefuse("expected one $part, found $size")
private fun writerRefuse(detail: String): Nothing = throw PatchException("Open developer options: $detail")
