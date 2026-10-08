/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Instagram 449 has about 200,000 classes, and a walk over every one of them, reading each
 * method's instructions, takes a second or two on a desktop and many times that on a phone. The
 * patches used to make dozens of those walks, which is most of why patching in Manager was slow
 * (#60). The patcher indexes every class's string constants and literals once; these narrow a
 * search to the classes the index names, and the caller still checks each method itself, so what
 * a patch finds doesn't change.
 */

/**
 * The classes outside the extension whose code loads every one of [strings] as a string
 * constant, in the app's order. A method that holds all of them is in one of these classes.
 */
internal fun BytecodePatchContext.classesHolding(vararg strings: String): List<ClassDef> {
    require(strings.isNotEmpty()) { "no string to look for" }
    val sets = strings.map { string -> classDefByStrings(string) }
    val smallest = sets.minBy { it.size }
    val others = sets.filter { it !== smallest }.map { set -> set.mapTo(HashSet()) { it.type } }
    return smallest.filter { classDef -> !classDef.type.startsWith(EXTENSION_ROOT) && others.all { classDef.type in it } }
}

/**
 * The classes outside the extension whose code loads [value]: the ones [classesHolding] it, then
 * the ones asking a static pool of shared strings for it by number, as 450's Redex has it. Which
 * strings Redex pools differs from build to build of one version (#77), so a class holding a
 * string in one build can ask a pool for it in another, while the pool holds it in both.
 */
internal fun BytecodePatchContext.classesLoadingString(value: String): List<ClassDef> {
    val holders = classesHolding(value)
    val askers = holders.flatMap { holder ->
        holder.methods.mapNotNull { pool -> pool.numbersFor(value).takeIf { it.isNotEmpty() }?.let { pool to it } }
    }.flatMap { (pool, numbers) ->
        classesCalling(pool.definingClass, pool.name).filter { caller -> caller.methods.any { it.asksPool(pool, numbers) } }
    }
    return (holders + askers).distinctBy { it.type }
}

/**
 * The numbers this static `(I)String` pool answers [value] for, read off its switches the way
 * `pooledString` reads them: the first switch whose case for a number loads a string decides it.
 * Empty for any other method.
 */
private fun Method.numbersFor(value: String): Set<Int> {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != STRING || parameterTypes.map(Any::toString) != listOf("I")) {
        return emptySet()
    }
    val code = implementation?.instructions?.toList() ?: return emptySet()
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val answered = HashSet<Int>()
    val numbers = HashSet<Int>()
    for (switch in code.indices) {
        if (code[switch].opcode != Opcode.PACKED_SWITCH && code[switch].opcode != Opcode.SPARSE_SWITCH) continue
        val payload = code.getOrNull(address.indexOf(address[switch] + (code[switch] as OffsetInstruction).codeOffset)) as? SwitchPayload
            ?: continue
        for (case in payload.switchElements) {
            val load = code.getOrNull(address.indexOf(address[switch] + case.offset)) ?: continue
            if (load.opcode != Opcode.CONST_STRING && load.opcode != Opcode.CONST_STRING_JUMBO || !answered.add(case.key)) continue
            if (((load as ReferenceInstruction).reference as StringReference).string == value) numbers += case.key
        }
    }
    return numbers
}

/** Whether this method asks [pool] for one of [numbers], a constant loaded into the call's register right before it. */
private fun Method.asksPool(pool: Method, numbers: Set<Int>): Boolean {
    val code = implementation?.instructions?.toList() ?: return false
    return code.indices.any { at ->
        val call = code[at]
        val called = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        val number = code.getOrNull(at - 1) as? NarrowLiteralInstruction ?: return@any false
        call.opcode == Opcode.INVOKE_STATIC && called.definingClass == pool.definingClass && called.name == pool.name &&
            called.parameterTypes.map(Any::toString) == listOf("I") && called.returnType == STRING &&
            (number as OneRegisterInstruction).registerA == (call as FiveRegisterInstruction).registerC &&
            number.narrowLiteral in numbers
    }
}

private const val STRING = "Ljava/lang/String;"

/**
 * The classes outside the extension whose code loads [literal] (a `const` or `const-wide` value),
 * in the app's order. The patcher's literal index picks the classes to read; a class a patch has
 * changed since it indexed the app is always read.
 */
internal fun BytecodePatchContext.classesLoading(literal: Long): List<ClassDef> =
    classesMatching(Fingerprint(filters = listOf(literal(literal))))

/**
 * The classes outside the extension whose code calls [name] on [definingClass], in the app's
 * order. The patcher's index of the types each class refers to picks the classes to read; a class
 * a patch has changed since it indexed the app is always read.
 */
internal fun BytecodePatchContext.classesCalling(definingClass: String, name: String): List<ClassDef> =
    classesMatching(Fingerprint(filters = listOf(methodCall(definingClass = definingClass, name = name))))

/**
 * The classes outside the extension whose code calls any method of [definingClass], in the app's
 * order, by the same index of the types each class refers to.
 */
internal fun BytecodePatchContext.classesCallingInto(definingClass: String): List<ClassDef> =
    classesMatching(Fingerprint(filters = listOf(methodCall(definingClass = definingClass))))

/**
 * The classes outside the extension whose code makes a new instance of [type] (new-instance), in
 * the app's order. The index of the types each class refers to picks the classes to read.
 */
internal fun BytecodePatchContext.classesCreating(type: String): List<ClassDef> =
    classesMatching(Fingerprint(filters = listOf(newInstance(type))))

/**
 * The classes outside the extension whose code uses the field [name] of [definingClass] with
 * [opcode] (an iget or iput kind), in the app's order. The same index of the types each class
 * refers to picks the classes to read.
 */
internal fun BytecodePatchContext.classesAccessing(definingClass: String, name: String, opcode: Opcode): List<ClassDef> =
    classesMatching(Fingerprint(filters = listOf(fieldAccess(definingClass = definingClass, name = name, opcode = opcode))))

/**
 * The classes outside the extension whose code reads or writes the field [name] of [definingClass],
 * with any opcode, in the app's order, by the same index.
 */
internal fun BytecodePatchContext.classesTouching(definingClass: String, name: String): List<ClassDef> =
    classesMatching(Fingerprint(filters = listOf(fieldAccess(definingClass = definingClass, name = name, opcodes = null))))

/**
 * The types of the classes outside the extension holding a purge marker (see [markers]) named one
 * of [names]. A marker named X is a string ending in `_X`, so a class this leaves out holds none.
 * Filter a class walk by it to keep the app's order.
 */
internal fun BytecodePatchContext.typesMarked(vararg names: String): Set<String> {
    require(names.isNotEmpty()) { "no marker to look for" }
    return names.flatMapTo(HashSet()) { name -> classDefByStrings("_$name", StringComparisonType.ENDS_WITH).map { it.type } }
        .filterNotTo(HashSet()) { it.startsWith(EXTENSION_ROOT) }
}

/** The classes outside the extension holding a method [fingerprint] matches, in the app's order. */
private fun BytecodePatchContext.classesMatching(fingerprint: Fingerprint): List<ClassDef> =
    fingerprint.matchAllOrNull().orEmpty().map { it.originalMethod.definingClass }.distinct()
        .filterNot { it.startsWith(EXTENSION_ROOT) }
        .map { classDefBy(it) }
