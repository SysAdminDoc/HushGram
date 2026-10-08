/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.privacy

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesHolding
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal fun refuse(patch: String, detail: String): Nothing = throw PatchException("$patch: $detail")

internal fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

internal fun Method.describe(): String = "$definingClass->$name"

internal fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

internal fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

internal fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Every method outside the extension that loads all of [strings], in the app's order. */
internal fun BytecodePatchContext.methodsHolding(vararg strings: String): List<Method> =
    classesHolding(*strings).flatMap { classDef ->
        classDef.methods.filter { method ->
            val held = method.code().mapNotNull { it.stringLoaded() }.toSet()
            strings.all { it in held }
        }
    }

/** The one method [candidates] holds, or a refusal naming what wasn't found exactly once. */
internal fun <T> List<T>.exactlyOne(patch: String, what: String): T =
    singleOrNull() ?: refuse(patch, "expected one $what, found $size")

internal fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }
