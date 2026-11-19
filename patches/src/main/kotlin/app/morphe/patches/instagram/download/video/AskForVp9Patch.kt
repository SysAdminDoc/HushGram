/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val VP9_PATCH = "Ask for VP9 instead of AV1"
internal const val VP9_VIDEO = "$EXTENSION_PACKAGE/download/Vp9Video;"
internal const val HARDWARE_AV1 = "$VP9_VIDEO->hardwareAv1(Ljava/lang/Object;)Ljava/lang/Object;"

/** The keys the device status map reports the phone's hardware AV1 decoders under (#546). */
internal const val HW_AV1 = "hw_av1_dec"
internal const val HW_AV1_10BIT = "10bit_hw_av1_dec"
internal val AV1_KEYS = listOf(HW_AV1, HW_AV1_10BIT)

private const val OBJECT = "Ljava/lang/Object;"
private const val MAP = "Ljava/util/Map;"
private val PUTS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)

@Suppress("unused")
val askForVp9Patch = bytecodePatch(
    name = "Ask for VP9 instead of AV1",
    description = "Tells Instagram your phone has no AV1 decoder, which takes a restart. A post or reel that " +
        "still comes as AV1 is saved as VP9 from Instagram's website when it offers one. Stories can still be " +
        "AV1, and Convert to keep the full size turns those into H.264. Starts off. Turn it on in HushGram " +
        "settings > Downloads.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("vp9Video")
        askForVp9(findDeviceStatus())
        enableStatus("vp9Video")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$VP9_PATCH: $why")

/** Where the device status map takes one AV1 decoder answer: the put's index and the register holding the answer. */
internal class Av1Put(val key: String, val at: Int, val value: Int)

/** The method building the device status map Instagram sends with its video requests, and its two AV1 puts. */
internal class DeviceStatus(val method: MutableMethod, val puts: List<Av1Put>)

/**
 * The method building the device status map (hw_av1_dec, hw_vp9_dec, hw_avc_dec, chip_name...)
 * that Instagram sends as the device_status parameter of its reels, feed and story requests, and
 * from which the server picks the codec of the video it serves. Proved before anything changes:
 * one method loads both AV1 keys and returns a map, each key is loaded once and handed straight
 * to a put(key, value) on the map, and nothing jumps onto either put, where the hook goes.
 */
internal fun BytecodePatchContext.findDeviceStatus(): DeviceStatus {
    val builders = classesLoadingString(HW_AV1).flatMap { it.methods }.filter { method -> AV1_KEYS.all { loadsString(method, it) } }
    val found = builders.singleOrNull()
        ?: refuse("expected one method loading \"$HW_AV1\" and \"$HW_AV1_10BIT\", found ${builders.size}")
    val types = found.parameterTypes.map(CharSequence::toString)
    val method = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.returnType == found.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
    val where = "${method.definingClass}->${method.name}"
    if (!isAMap(method.returnType)) refuse("$where returns ${method.returnType}, not a map")
    val code = method.implementation!!.instructions.toList()
    val targets = method.jumpTargets()
    val puts = AV1_KEYS.map { key ->
        val put = putOf(code, key, where)
        if (put.at in targets) refuse("something jumps onto the put of \"$key\" in $where")
        put
    }
    return DeviceStatus(method, puts)
}

/** The JDK's maps an app's map class may extend or implement; none of them is in the app's dex. */
private val JDK_MAPS = setOf(
    MAP, "Ljava/util/AbstractMap;", "Ljava/util/HashMap;", "Ljava/util/LinkedHashMap;", "Ljava/util/TreeMap;",
    "Ljava/util/SortedMap;", "Ljava/util/NavigableMap;", "Ljava/util/concurrent/ConcurrentMap;",
)

/**
 * Whether [type] is a map: one of the JDK's, or an app class whose superclasses or interfaces lead
 * to one. Some builds of a version return the app's own immutable map class (#546).
 */
private fun BytecodePatchContext.isAMap(type: String): Boolean {
    val seen = mutableSetOf<String>()
    val waiting = ArrayDeque(listOf(type))
    while (waiting.isNotEmpty()) {
        val next = waiting.removeFirst()
        if (next in JDK_MAPS) return true
        if (!seen.add(next) || seen.size > 32) continue
        val classDef = classDefByOrNull(next) ?: continue
        classDef.superclass?.let(waiting::add)
        waiting.addAll(classDef.interfaces)
    }
    return false
}

/** The put [key] goes into: right after the key's load (a const-string, or a pool call and its move-result). */
private fun BytecodePatchContext.putOf(code: List<Instruction>, key: String, where: String): Av1Put {
    val loads = code.indices.filter { stringLoadedAt(code, it) == key }
    val load = loads.singleOrNull() ?: refuse("$where loads \"$key\" ${loads.size} times, not once")
    val pooled = code[load].opcode != Opcode.CONST_STRING && code[load].opcode != Opcode.CONST_STRING_JUMBO
    val keyAt = if (pooled) load + 1 else load
    val keyLoad = code.getOrNull(keyAt)
    if (pooled && keyLoad?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where doesn't keep the pooled \"$key\"")
    val register = (keyLoad as OneRegisterInstruction).registerA
    val at = keyAt + 1
    val put = code.getOrNull(at)
    val call = (put as? ReferenceInstruction)?.reference as? MethodReference
    val registers = put?.callRegisters().orEmpty()
    if (put == null || put.opcode !in PUTS || call == null || call.name != "put" || call.returnType != OBJECT ||
        call.parameterTypes.map(CharSequence::toString) != listOf(OBJECT, OBJECT) || registers.size != 3 || registers[1] != register
    ) {
        refuse("$where doesn't put \"$key\" in the map right after loading it")
    }
    if (registers[2] == register || registers[2] == registers[0]) refuse("$where puts \"$key\" with itself or the map as its answer")
    return Av1Put(key, at, registers[2])
}

/**
 * Each AV1 answer goes through [HARDWARE_AV1] right before its put, the later put first so the
 * earlier index still holds. The switch off, the extension hands back what Instagram worked out.
 */
internal fun askForVp9(status: DeviceStatus) {
    for (put in status.puts.sortedByDescending { it.at }) {
        val value = "v${put.value}"
        status.method.addInstructions(
            put.at,
            """
                invoke-static/range { $value .. $value }, $HARDWARE_AV1
                move-result-object $value
            """,
        )
    }
}

/** The registers a call reads, in order. */
private fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}
