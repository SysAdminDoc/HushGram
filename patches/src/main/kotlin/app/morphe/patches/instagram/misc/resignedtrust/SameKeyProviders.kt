/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.resignedtrust

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val PROVIDER_BASE = "Lcom/facebook/secure/content/base/AbstractContentProviderDelegate;"
internal const val SAME_KEY_PROVIDER = "Lcom/facebook/secure/content/delegate/SameKeyContentProviderDelegate;"
internal const val TRUSTED_CALLER_PROVIDER = "Lcom/facebook/secure/content/delegate/TrustedCallerContentProviderDelegate;"
internal const val LOGGED_IN_USERS = "Lcom/instagram/contentprovider/users/impl/IgLoggedInUsersContentProvider\$Impl;"
internal val sameKeyProviderTypes = setOf(PROVIDER_BASE, SAME_KEY_PROVIDER, TRUSTED_CALLER_PROVIDER, LOGGED_IN_USERS)

private const val CONTEXT = "Landroid/content/Context;"
private const val SAME_KEY_CALLER = "Lapp/hushgram/extension/instagram/misc/InstagramSignature;->" +
    "isSameKeyFamilyProviderCaller(Landroid/content/Context;)Z"

internal data class ProviderDecision(val method: Method, val index: Int, val context: Int, val result: Int)

private fun needProviderShape(condition: Boolean, detail: String) {
    if (!condition) throw PatchException("Restore trust on re-signed builds: $detail")
}

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

/**
 * Follow the kept provider roles and their policy getters, rather than an obfuscated method name.
 * Both guards must uniquely use the same native policy checker. A changed role or decision shape
 * stops patching, so no other provider or generic caller check can become a target by accident.
 */
internal fun sameKeyProviderDecisions(classes: Iterable<ClassDef>): List<ProviderDecision> {
    val pool = classes.toList()
    fun role(type: String): ClassDef {
        val matches = pool.filter { it.type == type }
        needProviderShape(matches.size == 1, "expected one provider role $type, found ${matches.size}")
        return matches.single()
    }
    val base = role(PROVIDER_BASE)
    val sameKey = role(SAME_KEY_PROVIDER)
    val trusted = role(TRUSTED_CALLER_PROVIDER)
    val users = role(LOGGED_IN_USERS)
    needProviderShape(sameKey.superclass == trusted.type && trusted.superclass == base.type,
        "the SameKey provider hierarchy changed")
    needProviderShape(users.superclass == sameKey.type && pool.count { it.superclass == sameKey.type } == 1,
        "expected only the logged-in users provider to inherit SameKey")

    // 449: SameKey's own final getters return a SameKey field. 450's Redex folds them into the
    // TrustedCaller getters, whose last arm casts the delegate to SameKey and returns that field.
    fun Instruction.readsSameKey(returns: String) = opcode == Opcode.IGET_OBJECT &&
        fieldRef()?.let { it.definingClass == sameKey.type && it.type == returns } == true
    val own = sameKey.methods.filter { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            AccessFlags.FINAL.isSet(method.accessFlags) && code.size == 2 &&
            code[0].readsSameKey(method.returnType) && code[1].opcode == Opcode.RETURN_OBJECT &&
            (code[0] as TwoRegisterInstruction).registerA == (code[1] as OneRegisterInstruction).registerA
    }.toList()
    val folded = trusted.methods.filter { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        val at = code.size - 4
        method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) && at >= 0 &&
            code[at].opcode == Opcode.MOVE_OBJECT && code[at + 1].opcode == Opcode.CHECK_CAST &&
            ((code[at + 1] as ReferenceInstruction).reference as? TypeReference)?.type == sameKey.type &&
            code[at + 2].readsSameKey(method.returnType) && code[at + 3].opcode == Opcode.RETURN_OBJECT &&
            (code[at + 2] as TwoRegisterInstruction).registerA == (code[at + 3] as OneRegisterInstruction).registerA &&
            code.take(at).none { (it as? ReferenceInstruction)?.reference.let { ref -> (ref as? TypeReference)?.type == sameKey.type } }
    }.toList()
    val getters = own.ifEmpty { folded }
    needProviderShape(getters.size == 2 && getters.map { it.returnType }.distinct().size == 1 && (own.isEmpty() || folded.isEmpty()),
        "expected two SameKey policy getters")
    val policy = getters.first().returnType
    val fields = getters.map { getter ->
        getter.implementation!!.instructions.single { it.readsSameKey(policy) }.fieldRef()!!.toString()
    }.toSet()
    needProviderShape(fields.size == 2, "the SameKey policy getters share a field")
    val constructors = sameKey.methods.filter { it.name == "<init>" }.toList()
    needProviderShape(constructors.size == 1, "expected one SameKey provider constructor")
    val constructor = constructors.single().implementation?.instructions?.toList().orEmpty()
    val singletons = constructor.filter { it.opcode == Opcode.SGET_OBJECT }
    val assignments = constructor.filter { it.opcode == Opcode.IPUT_OBJECT }
    needProviderShape(singletons.size == 1 && assignments.size == 2 &&
        assignments.map { it.fieldRef().toString() }.toSet() == fields &&
        assignments.all { (it as TwoRegisterInstruction).registerA == (singletons.single() as OneRegisterInstruction).registerA },
        "the SameKey provider no longer initializes both policies from one singleton")

    val targets = getters.map { getter ->
        val matches = base.methods.flatMap { method ->
            if (method.returnType != "V" || method.parameterTypes.isNotEmpty() ||
                AccessFlags.STATIC.isSet(method.accessFlags)) return@flatMap emptyList()
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.withIndex().mapNotNull { (index, instruction) ->
                val checker = instruction.methodRef() ?: return@mapNotNull null
                if (instruction.opcode !in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) ||
                    checker.returnType != "Z" || checker.parameterTypes.map(CharSequence::toString) != listOf(CONTEXT, policy) ||
                    index < 4 || index + 2 >= code.size) return@mapNotNull null
                val contextCall = code[index - 4].methodRef()
                val policyCall = code[index - 2].methodRef()
                if (contextCall?.definingClass != "Landroid/content/ContentProvider;" ||
                    contextCall.name != "getContext" || contextCall.returnType != CONTEXT ||
                    contextCall.parameterTypes.isNotEmpty() ||
                    policyCall?.definingClass != trusted.type || policyCall.name != getter.name ||
                    policyCall.returnType != policy || policyCall.parameterTypes.isNotEmpty() ||
                    code[index - 3].opcode != Opcode.MOVE_RESULT_OBJECT ||
                    code[index - 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
                    code[index + 1].opcode != Opcode.MOVE_RESULT ||
                    code[index + 2].opcode !in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ)) return@mapNotNull null
                val args = instruction.arguments()
                val contextRegister = (code[index - 3] as OneRegisterInstruction).registerA
                val policyRegister = (code[index - 1] as OneRegisterInstruction).registerA
                val result = (code[index + 1] as OneRegisterInstruction).registerA
                if (args != listOf(contextRegister, policyRegister) ||
                    (code[index + 2] as OneRegisterInstruction).registerA != result ||
                    result !in 0..15 || result == contextRegister ||
                    result == method.implementation!!.registerCount - 1) return@mapNotNull null
                ProviderDecision(method, index + 2, contextRegister, result)
            }
        }
        needProviderShape(matches.size == 1, "expected one guard for each SameKey policy, found ${matches.size}")
        matches.single()
    }
    needProviderShape(targets.map { it.method.name }.distinct().size == 2 && targets.map {
        it.method.implementation!!.instructions.toList()[it.index - 2].methodRef().toString()
    }.distinct().size == 1, "the two provider guards no longer share one native policy checker")
    return targets
}

/** Keep a native yes. After a native no, only the kept SameKey delegate can ask for current keys. */
internal fun MutableMethod.repairSameKeyProviderDecision(target: ProviderDecision) {
    val v = target.result
    val context = target.context
    addInstructionsWithLabels(
        target.index,
        """
            if-nez v$v, :nativePolicy
            move-object/from16 v$v, p0
            instance-of v$v, v$v, $SAME_KEY_PROVIDER
            if-eqz v$v, :nativePolicy
            invoke-static/range { v$context .. v$context }, $SAME_KEY_CALLER
            move-result v$v
        """,
        ExternalLabel("nativePolicy", getInstruction(target.index)),
    )
}
