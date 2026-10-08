/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The key the MQTT client's settings keep its analytics address under. */
internal const val ANALYTICS_KEY = "analytics_endpoint"

/** Two keys only the MQTT client's settings constructor reads, which pick it out. */
internal val MQTT_SETTINGS_STRINGS = listOf("php_sandbox_host_name", "mqtt-mini.facebook.com")

/**
 * Sends the analytics address the MQTT client's settings read to [endpoint] right after it's read.
 * The settings are a JSONObject the server hands over, read by one constructor, and the address is
 * the value under [ANALYTICS_KEY], with Facebook's Graph logging address as the fallback. Instagram
 * 449 doesn't load the key itself: it asks Redex's pool of shared strings for it by number, so the
 * number is read back through the pool's switch to be sure it's this key.
 *
 * Answers null when the call went in, or why not.
 */
internal fun BytecodePatchContext.wrapMqttAnalyticsEndpoint(endpoint: String): String? {
    val constructors = mutableListOf<Method>()
    classesHolding(*MQTT_SETTINGS_STRINGS.toTypedArray()).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.name == "<init>" && method.parameterTypes.map(Any::toString) == listOf("Lorg/json/JSONObject;") &&
                MQTT_SETTINGS_STRINGS.all { it in method.strings() }
            ) {
                constructors += method
            }
        }
    }
    val settings = constructors.singleOrNull()
        ?: return "expected one constructor reading the MQTT client's settings, found ${constructors.size}"
    // Read from the copy that gets changed, so an earlier target's change to it can't shift the index.
    val mutable = mutableClassDefBy(settings.definingClass).methods.single {
        it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf("Lorg/json/JSONObject;")
    }
    val code = mutable.implementation!!.instructions.toList()
    val reads = code.indices.filter { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        call?.definingClass == "Lorg/json/JSONObject;" && call.name == "optString" && call.parameterTypes.size == 2 &&
            code[index] is Instruction35c && keyOf(code, index) == ANALYTICS_KEY
    }
    val read = reads.singleOrNull()
        ?: return "${settings.definingClass}->${settings.name} reads $ANALYTICS_KEY ${reads.size} times, expected once"
    val value = code.getOrNull(read + 1)
    if (value?.opcode != Opcode.MOVE_RESULT_OBJECT) return "${settings.definingClass} doesn't keep $ANALYTICS_KEY's value"
    val register = (value as OneRegisterInstruction).registerA
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val landsInside = code.indices.any { index ->
        val jump = code[index] as? OffsetInstruction ?: return@any false
        (jump.opcode.name.startsWith("if-") || jump.opcode.name.startsWith("goto")) &&
            address.indexOf(address[index] + jump.codeOffset) in read + 1..read + 2
    }
    if (landsInside) return "${settings.definingClass} jumps to where $ANALYTICS_KEY's value is kept, with no value read"
    mutable.addInstructionsAtControlFlowLabel(
        read + 2,
        """
            invoke-static/range { v$register .. v$register }, $endpoint
            move-result-object v$register
        """,
    )
    return null
}

/**
 * The key a `JSONObject.optString(key, fallback)` call at [index] reads, when it's a string the
 * method loads itself or one it asks a pool of shared strings for by a constant number; else null.
 */
private fun BytecodePatchContext.keyOf(code: List<Instruction>, index: Int): String? {
    val keyRegister = (code[index] as Instruction35c).registerD
    val set = (index - 1 downTo 0).firstOrNull { code[it].writes(keyRegister) } ?: return null
    val loaded = code[set]
    if (loaded.opcode == Opcode.CONST_STRING || loaded.opcode == Opcode.CONST_STRING_JUMBO) {
        return ((loaded as ReferenceInstruction).reference as StringReference).string
    }
    if (loaded.opcode != Opcode.MOVE_RESULT_OBJECT || set == 0) return null
    val pool = (code[set - 1] as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (code[set - 1].opcode != Opcode.INVOKE_STATIC || pool.parameterTypes.map(Any::toString) != listOf("I") ||
        pool.returnType != "Ljava/lang/String;"
    ) {
        return null
    }
    val numberRegister = (code[set - 1] as Instruction35c).registerC
    val number = (set - 2 downTo 0).firstOrNull { code[it].writes(numberRegister) }
        ?.let { code[it] as? NarrowLiteralInstruction }?.narrowLiteral ?: return null
    return pooledString(pool, number)
}

/** The string the static pool method [pool] answers for [number], read off its switch, or null. */
internal fun BytecodePatchContext.pooledString(pool: MethodReference, number: Int): String? {
    val method = classDefByOrNull(pool.definingClass)?.methods?.singleOrNull {
        it.name == pool.name && it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == pool.returnType
    } ?: return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    code.indices.filter { code[it].opcode == Opcode.PACKED_SWITCH || code[it].opcode == Opcode.SPARSE_SWITCH }.forEach { switch ->
        val payload = code.getOrNull(address.indexOf(address[switch] + (code[switch] as OffsetInstruction).codeOffset)) as? SwitchPayload
            ?: return@forEach
        val case = payload.switchElements.firstOrNull { it.key == number } ?: return@forEach
        val load = code.getOrNull(address.indexOf(address[switch] + case.offset)) ?: return@forEach
        if (load.opcode == Opcode.CONST_STRING || load.opcode == Opcode.CONST_STRING_JUMBO) {
            return ((load as ReferenceInstruction).reference as StringReference).string
        }
    }
    return null
}

/**
 * Whether [method] loads [value] itself or, as 450's Redex has it, asks a static pool of shared
 * strings for it by a constant number loaded right before the call.
 */
internal fun BytecodePatchContext.loadsString(method: Method, value: String): Boolean {
    if (value in method.strings()) return true
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.indices.any { at ->
        val call = code[at]
        val pool = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        val number = code.getOrNull(at - 1) as? NarrowLiteralInstruction ?: return@any false
        call.opcode == Opcode.INVOKE_STATIC && pool.parameterTypes.map(Any::toString) == listOf("I") &&
            pool.returnType == "Ljava/lang/String;" &&
            (number as OneRegisterInstruction).registerA == (call as Instruction35c).registerC &&
            pooledString(pool, number.narrowLiteral) == value
    }
}

/**
 * The string [code] loads at [at]: a const-string's, or, as 450's Redex has it, a static pool's
 * asked for it by a constant number loaded right before the call. Null for anything else. Which
 * strings Redex pools differs from build to build of one version (#77).
 */
internal fun BytecodePatchContext.stringLoadedAt(code: List<Instruction>, at: Int): String? {
    val load = code[at]
    if (load.opcode == Opcode.CONST_STRING || load.opcode == Opcode.CONST_STRING_JUMBO) {
        return ((load as ReferenceInstruction).reference as StringReference).string
    }
    val pool = (load as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val number = code.getOrNull(at - 1) as? NarrowLiteralInstruction ?: return null
    if (load.opcode != Opcode.INVOKE_STATIC || pool.parameterTypes.map(Any::toString) != listOf("I") ||
        pool.returnType != "Ljava/lang/String;" || (number as OneRegisterInstruction).registerA != (load as Instruction35c).registerC
    ) {
        return null
    }
    return pooledString(pool, number.narrowLiteral)
}

private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}?.toSet().orEmpty()

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val first = (this as? OneRegisterInstruction)?.registerA ?: return false
    return first == register || (opcode.setsWideRegister() && first + 1 == register)
}
