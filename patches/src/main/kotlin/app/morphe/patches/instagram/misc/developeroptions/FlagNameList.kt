/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
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

internal const val FLAG_NAMES = "$EXTENSION_PACKAGE/misc/FlagNames;"
/** Each takes a MetaConfig row's schema entry and the label Instagram made, and answers the label to show. */
internal const val FLAG_PARAMETER_LABEL = "$FLAG_NAMES->parameter(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"
internal const val FLAG_CONFIG_LABEL = "$FLAG_NAMES->config(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"

/** The SettingsStatus method that says Import flag names went in, which a build can lack. */
internal const val FLAG_NAMES_STATUS = "flagNames"

/** Instagram's own name loader logs these from the schema getter, which builds each entry. */
internal const val NAME_LOADER = "MobileConfigIdNameMappingLoader"
internal const val NAME_FAILURE = "failed to parse and get namedParamsMapList, name is null"

/** A MetaConfig row: its ID, its parameter label, then its config label. */
internal const val ROW_CONSTRUCTOR = "(JLjava/lang/String;Ljava/lang/String;)V"
private const val LIST = "Ljava/util/List;"

/** The bridge stubs that read a schema entry's config number and parameter index. */
private val FLAG_STUBS = listOf("getFlagConfigNative", "getFlagIndexNative")

/**
 * Where the list builder makes one row: the new-instance the hook goes in front of, the register
 * holding the row's schema entry, and the registers holding its parameter and config labels.
 */
internal class FlagRow(val at: Int, val entry: Int, val parameter: Int, val config: Int)

/** The list builder and its rows, with the stub bodies assembled and nothing yet changed. */
internal class FlagNameList(val builder: String, val rows: List<FlagRow>, val stubs: PreparedStubs)

/**
 * The hook Import flag names needs, or null after the patch log says why. Only the flag names use
 * it, so a build where Instagram's MetaConfig list moved keeps the rest of Open developer options.
 * Nothing here changes the app.
 */
internal fun BytecodePatchContext.flagNamesOrWarn(): FlagNameList? = try {
    findFlagNameList()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. The rest of Open developer options goes in without Import flag names.")
    null
}

/**
 * Finds Instagram's MetaConfig list builder and the rows it makes. The schema entry is the record
 * Instagram's name loader builds, and its config number, parameter index and both names are the
 * constructor roles [constructorFields] proves. The builder is the one static ()List reading the
 * entry's names that makes rows of [ROW_CONSTRUCTOR], and every row it makes must trace back to one
 * entry, both labels made from that entry's names or "_" and its numbers, with nothing jumping in
 * between. A row that doesn't trace refuses the lot.
 */
internal fun BytecodePatchContext.findFlagNameList(): FlagNameList {
    val getters = mutableListOf<Method>()
    classesLoadingString(NAME_LOADER).forEach { clazz ->
        clazz.methods.filterTo(getters) { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() && method.returnType.startsWith("L") &&
                loadsString(method, NAME_LOADER) && loadsString(method, NAME_FAILURE)
        }
    }
    val getter = getters.one("MetaConfig schema getter")
    val constructor = getter.code().mapNotNull { it.method() }.filter {
        it.name == "<init>" && it.parameterTypes.map(Any::toString) == RECORD_ARGS && it.returnType == "V"
    }.distinctBy(Any::toString).one("MetaConfig schema entry constructor")
    val entry = classDefByOrNull(constructor.definingClass) ?: refuse("missing MetaConfig schema entry ${constructor.definingClass}")
    val fields = constructorFields(entry, constructor)
    val roles = FlagFields(configName = fields[0], name = fields[1], index = fields[2], config = fields[8])

    val builders = mutableListOf<Method>()
    classesAccessing(entry.type, roles.name.name, Opcode.IGET_OBJECT).forEach { clazz ->
        clazz.methods.filterTo(builders) { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() && method.returnType == LIST &&
                method.code().any { it.opcode == Opcode.INVOKE_DIRECT && it.method()?.isRow() == true }
        }
    }
    val builder = builders.one("MetaConfig list builder")
    val rows = flagRows(builder, roles)
    return FlagNameList(builder.reference(), rows, prepareFlagStubs(entry.type, roles))
}

/** The schema entry's fields the rows' labels come from, by their constructor roles. */
internal class FlagFields(val configName: FieldReference, val name: FieldReference, val index: FieldReference, val config: FieldReference)

/** Every row [builder] makes, each traced to its entry and labels. */
internal fun flagRows(builder: Method, roles: FlagFields): List<FlagRow> {
    val code = builder.code()
    val flow = try {
        ControlFlow.of(builder)
    } catch (unreadable: IllegalArgumentException) {
        refuse("the MetaConfig list builder's branches don't read")
    }
    val targets = builder.jumpTargets()
    val calls = code.indices.filter { code[it].opcode == Opcode.INVOKE_DIRECT && code[it].method()?.isRow() == true }
    if (calls.isEmpty()) refuse("the MetaConfig list builder makes no rows")
    if (calls.map { code[it].method().toString() }.distinct().size != 1) refuse("the MetaConfig list builder makes rows of more than one kind")
    return calls.map { call -> flagRow(code, flow, targets, call, roles) }
}

/**
 * One row: `new-instance vR` then `invoke-direct {vR, id, id', vP, vC}`. Going back from there, the
 * parameter label comes from an iget of the entry's name (or Instagram's fallback for it, made from
 * the index read off that same entry), and the config label from the config name read off the
 * entry after it (or its fallback, made from the config number). From the parameter label's iget to
 * the new-instance, nothing writes the entry, nothing but a fallback's result writes a label once
 * it's read, every path passes the config name's read, nothing outside jumps in, no handler starts,
 * and nothing at all jumps to the new-instance, where the hook goes in.
 *
 * The row's constructor takes the labels as strings, so they're strings at the new-instance on
 * every path the verifier sees, and the entry register holds the entry the names came from.
 */
private fun flagRow(code: List<Instruction>, flow: ControlFlow, targets: Set<Int>, call: Int, roles: FlagFields): FlagRow {
    val at = call - 1
    val arguments = code[call].arguments()
    val made = code.getOrNull(at)?.takeIf { it.opcode == Opcode.NEW_INSTANCE } as? OneRegisterInstruction
    if (arguments.size != 5 || made == null || made.registerA != arguments[0]) refuse("a MetaConfig row isn't made right after its new-instance")
    val row = arguments[0]
    val parameter = arguments[3]
    val config = arguments[4]
    val parameterRead = (at - 1 downTo 0).firstOrNull { code[it].reads(roles.name) && (code[it] as TwoRegisterInstruction).registerA == parameter }
        ?: refuse("a MetaConfig row's parameter label isn't read from its entry")
    val entry = (code[parameterRead] as TwoRegisterInstruction).registerB
    val configRead = (parameterRead + 1 until at).lastOrNull { code[it].reads(roles.configName) && (code[it] as TwoRegisterInstruction).registerA == config }
        ?: refuse("a MetaConfig row's config label isn't read after its parameter label")
    if ((code[configRead] as TwoRegisterInstruction).registerB != entry) refuse("a MetaConfig row's labels come from two entries")
    if (setOf(row, entry, parameter, config).size != 4) refuse("a MetaConfig row's registers overlap")
    if (maxOf(entry, parameter, config) > 15) refuse("a MetaConfig row's registers are past v15")
    val window = parameterRead + 1 until at
    // The fallbacks read the entry's own index and then its config number, one each.
    val index = window.filter { code[it].reads(roles.index) }
    val number = window.filter { code[it].reads(roles.config) }
    if (index.size != 1 || number.size != 1 || index[0] > configRead || number[0] < configRead ||
        (code[index[0]] as TwoRegisterInstruction).registerB != entry || (code[number[0]] as TwoRegisterInstruction).registerB != entry
    ) refuse("a MetaConfig row's fallback labels aren't made from its entry's numbers")
    for (step in window) {
        val instruction = code[step]
        val written = instruction.written()
        val result = instruction.opcode == Opcode.MOVE_RESULT_OBJECT
        if (entry in written) refuse("a MetaConfig row's entry is overwritten before the row is made")
        // Before its own read the config label's register is free for the parameter fallback's use.
        if (parameter in written && (step > configRead || !result)) refuse("a MetaConfig row's parameter label is set by something other than its fallback")
        if (config in written && step > configRead && !result) refuse("a MetaConfig row's config label is set by something other than its fallback")
        if (flow.exceptional[step].any { it in window || it == at }) refuse("a handler starts inside a MetaConfig row")
        if (step < configRead && flow.normal[step].any { it in configRead + 1..at }) refuse("a path to a MetaConfig row skips its config label")
    }
    flow.normal.forEachIndexed { from, next ->
        if (from !in parameterRead until at && next.any { it in window || it == at }) refuse("something jumps into a MetaConfig row")
    }
    if (at in targets) refuse("something jumps to a MetaConfig row's new-instance")
    return FlagRow(at, entry, parameter, config)
}

/** The two bridge stubs' bodies: the entry's own config number or index, or -1 for anything else. */
private fun BytecodePatchContext.prepareFlagStubs(entry: String, roles: FlagFields): PreparedStubs {
    val hooks = classDefByOrNull(FLAG_NAMES) ?: refuse("missing extension class $FLAG_NAMES")
    for (hook in listOf(FLAG_PARAMETER_LABEL, FLAG_CONFIG_LABEL)) {
        val name = hook.substringAfter("->").substringBefore("(")
        if (hooks.methods.none { it.name == name && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;", "Ljava/lang/String;") &&
                it.returnType == "Ljava/lang/String;" && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ) refuse("missing extension hook $hook")
    }
    val bridge = classDefByOrNull(OVERRIDE_BRIDGE) ?: refuse("missing extension class $OVERRIDE_BRIDGE")
    val stubs = FLAG_STUBS.map { name ->
        bridge.methods.filter { it.name == name && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
            it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) }.one("extension $name bridge")
    }
    val bodies = listOf(roles.config, roles.index).map { field ->
        2 to """
            instance-of v0, p0, $entry
            if-eqz v0, :other
            check-cast p0, $entry
            iget v0, p0, $field
            return v0
            :other
            const/4 v0, -0x1
            return v0
        """.trimIndent()
    }
    return prepareStubs(stubs, bodies, ::refuse)
}

/**
 * Puts the hooks in: in front of each row's new-instance, the parameter label and then the config
 * label go through [FlagNames], which answers each one back. Rows go in from the last up, so the
 * places found for the others don't move. Only called once [findFlagNameList] traced every row.
 */
internal fun BytecodePatchContext.applyFlagNameList(list: FlagNameList) {
    val owner = list.builder.substringBefore("->")
    val builder = mutableClassDefBy(owner).methods.single { it.reference() == list.builder }
    for (row in list.rows.sortedByDescending { it.at }) {
        builder.addInstructions(
            row.at,
            """
                invoke-static { v${row.entry}, v${row.parameter} }, $FLAG_PARAMETER_LABEL
                move-result-object v${row.parameter}
                invoke-static { v${row.entry}, v${row.config} }, $FLAG_CONFIG_LABEL
                move-result-object v${row.config}
            """.trimIndent(),
        )
    }
    putStubs(list.stubs)
}

private fun MethodReference.isRow() = name == "<init>" && returnType == "V" &&
    parameterTypes.joinToString("", "(", ")V") == ROW_CONSTRUCTOR
private fun Method.reference() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.reads(field: FieldReference) = opcode in setOf(Opcode.IGET, Opcode.IGET_OBJECT) &&
    ((this as ReferenceInstruction).reference as? FieldReference)?.toString() == field.toString()
private fun Instruction.written(): Set<Int> {
    if (!opcode.setsRegister()) return emptySet()
    val register = (this as? OneRegisterInstruction)?.registerA ?: return emptySet()
    return if (opcode.setsWideRegister()) setOf(register, register + 1) else setOf(register)
}
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> refuse("unreadable MetaConfig row arguments")
}
private fun <T> List<T>.one(part: String): T = singleOrNull() ?: refuse("expected one $part, found $size")
private fun refuse(detail: String): Nothing = throw PatchException("Open developer options: $detail")
