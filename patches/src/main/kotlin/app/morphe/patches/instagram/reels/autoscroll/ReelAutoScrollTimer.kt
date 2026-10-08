/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.autoscroll

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val AUTOSCROLL_EXPIRATION = "preference_clips_auto_scroll_expiration_timestamp"
internal const val AUTOSCROLL_EXPIRATION_GETTER = "getClipsAutoscrollExpirationTimestampMs(Lcom/instagram/preferences/user/UserPreferences;)J"
internal const val AUTOSCROLL_DURATION_TAP = "ClipsOptInAutoscrollPluginImpl_logDurationTap"
internal const val AUTO_SCROLL_TIMER_SET = "$REEL_AUTO_SCROLL->timerSet(J)V"

internal class TimerChoices(val method: MethodSite, val keeps: List<Kept>)

private fun refuseTimer(detail: String): Nothing = throw PatchException("Keep Reels auto scroll on: $detail")

/**
 * The completed duration callback saves the expiration timestamp, not the media id it also keeps.
 * Proves its captured preferences, each future-time expression and the setter's wide argument
 * through its boxing helper before any hook is written. The setter and zero-reset paths stay stock.
 */
internal fun BytecodePatchContext.findTimerChoices(check: Method, click: Method, prefs: String): TimerChoices {
    val timers = mutableListOf<ClassDef>()
    val callbacks = mutableListOf<Pair<ClassDef, Method>>()
    val holders = typesMarked(AUTOSCROLL_DURATION_TAP) + classesHolding(AUTOSCROLL_EXPIRATION).map { it.type }
    classDefForEach { type ->
        if (type.type !in holders || type.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        if (type.methods.any { it.name == "<clinit>" && it.timerCode().any { code -> code.timerString() == AUTOSCROLL_EXPIRATION } }) timers += type
        type.methods.filter { AUTOSCROLL_DURATION_TAP in it.markers() }.forEach { callbacks += type to it }
    }
    val timer = timers.singleOrNull() ?: refuseTimer("expected one expiration preference class, found ${timers.size}")
    if (timer.methods.none { it.name == "<clinit>" && it.timerCode().any { code -> code.timerString() == AUTOSCROLL_EXPIRATION_GETTER } }) {
        refuseTimer("${timer.type} doesn't name the expiration timestamp getter")
    }
    val setter = timer.methods.singleOrNull { it.concreteTimer() && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.timerParameters() == listOf(prefs, "J") }
        ?: refuseTimer("${timer.type} has no single static expiration setter taking ($prefs" + "J)V")
    val saved = proveExpirationSetter(setter, check)
    proveBoxedTimestamp(saved)
    // The cancellation callback holds the same log marker on 449, but never saves an expiration.
    val savingCallbacks = callbacks.filter { (_, method) -> method.timerCode().any { it.timerMethod()?.toString() == setter.toString() } }
    val (type, callback) = savingCallbacks.singleOrNull() ?: refuseTimer("expected one saving duration callback, found ${savingCallbacks.size}")
    if (callback.name != "invoke" || callback.timerParameters().isNotEmpty() || callback.returnType != "Ljava/lang/Object;" ||
        !callback.concreteTimer() || AccessFlags.STATIC.isSet(callback.accessFlags) || "Lkotlin/jvm/functions/Function0;" !in type.interfaces
    ) refuseTimer("${type.type} doesn't invoke a duration choice as Function0")
    val constructors = click.timerCode().mapNotNull { code -> code.timerMethod()?.takeIf {
        code.opcode in DIRECT_CALLS && it.definingClass == type.type && it.name == "<init>"
    } }
    if (constructors.size != 3 || constructors.map { it.toString() }.distinct().size != 1 ||
        click.timerCode().count { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == type.type } != 3
    ) refuseTimer("${click.definingClass}->${click.name} doesn't construct the three duration choices")
    val constructor = type.methods.singleOrNull { it.concreteTimer() && !AccessFlags.STATIC.isSet(it.accessFlags) && it.toString() == constructors.first().toString() }
        ?: refuseTimer("${type.type} has no declared duration constructor")
    val prefParameter = constructor.timerParameters().indices.filter { constructor.timerParameters()[it] == prefs }.singleOrNull()
        ?: refuseTimer("${type.type}'s duration constructor doesn't capture one $prefs")
    val constructorCode = constructor.timerCode()
    val prefRegister = constructor.parameterRegisterNumber(prefParameter)
    val selfRegister = constructor.implementation!!.registerCount - constructor.timerParameters().sumOf { p -> if (p == "J" || p == "D") 2 else 1 } - 1
    val captures = constructorCode.indices.filter {
        val instruction = constructorCode[it]
        instruction.opcode == Opcode.IPUT_OBJECT && (instruction as TwoRegisterInstruction).registerA == prefRegister &&
            instruction.registerB == selfRegister
    }
    val captureAt = captures.singleOrNull() ?: refuseTimer("${type.type}'s duration constructor doesn't keep its preferences")
    val constructorFlow = ControlFlow.of(constructor)
    if ((0 until captureAt).any { at ->
        val instruction = constructorCode[at]
        constructorFlow.normal[at] != listOf(at + 1) || instruction.opcode.name.startsWith("invoke") ||
            (instruction.opcode.setsRegister() && (instruction as OneRegisterInstruction).registerA.let {
                it == prefRegister || it == selfRegister ||
                    (instruction.opcode.setsWideRegister() && (it + 1 == prefRegister || it + 1 == selfRegister))
            })
    } || captureAt in constructor.jumpTargets()) refuseTimer("${type.type}'s duration constructor overwrites or bypasses its preferences")
    val captured = constructorCode[captureAt].timerField()!!
    if (captured.definingClass != type.type || type.fields.none { it.name == captured.name && it.type == captured.type && AccessFlags.FINAL.isSet(it.accessFlags) }) {
        refuseTimer("${type.type}'s duration preferences aren't its final field")
    }
    val code = callback.timerCode()
    val flow = ControlFlow.of(callback)
    val targets = callback.jumpTargets()
    val saves = code.indices.filter { code[it].timerMethod()?.toString() == setter.toString() }
    if (saves.size != 3) refuseTimer("${type.type}'s duration callback saves ${saves.size} timestamps, expected three")
    val kinds = mutableSetOf<Long>()
    val keeps = saves.map { at ->
        val args = code[at].timerArguments()
        if (code[at].opcode !in STATIC_CALLS || args.size != 3 || args[2] != args[1] + 1 || at + 1 >= code.size) {
            refuseTimer("${type.type}'s duration callback doesn't pass one wide expiration timestamp at $at")
        }
        val value = args[1]
        val max = code.getOrNull(at - 1)
        val readAt = if (max?.opcode == Opcode.CONST_WIDE && (max as WideLiteralInstruction).wideLiteral == Long.MAX_VALUE &&
            (max as OneRegisterInstruction).registerA == value
        ) {
            kinds += Long.MAX_VALUE
            at - 3
        } else {
            val add = code.getOrNull(at - 1) as? TwoRegisterInstruction
            val duration = code.getOrNull(at - 2)
            val amount = (duration as? WideLiteralInstruction)?.wideLiteral
            val result = code.getOrNull(at - 3)
            val clock = code.getOrNull(at - 4)
            if (add?.opcode != Opcode.ADD_LONG_2ADDR || add.registerA != value || duration?.opcode != Opcode.CONST_WIDE_32 ||
                amount !in setOf(3600000L, 86400000L) || (duration as OneRegisterInstruction).registerA != add.registerB ||
                result?.opcode != Opcode.MOVE_RESULT_WIDE || (result as OneRegisterInstruction).registerA != value ||
                clock?.opcode != Opcode.INVOKE_STATIC || clock.timerMethod()?.toString() != "Ljava/lang/System;->currentTimeMillis()J" ||
                clock.timerArguments().isNotEmpty() || setOf(args[0], value, value + 1, add.registerB, add.registerB + 1).size != 5
            ) refuseTimer("${type.type}'s duration callback doesn't save a proved future time at $at")
            kinds += amount!!
            at - 6
        }
        val read = code.getOrNull(readAt)
        val cast = code.getOrNull(readAt + 1)
        if (read?.opcode != Opcode.IGET_OBJECT || read.timerField()?.toString() != captured.toString() ||
            (read as TwoRegisterInstruction).registerA != args[0] || read.registerB != callback.implementation!!.registerCount - 1 ||
            cast?.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != args[0] ||
            (cast as ReferenceInstruction).reference.toString() != prefs || args[0] in value..value + 1 ||
            (readAt + 1..at).any { it in targets || flow.normal[it - 1] != listOf(it) }
        ) refuseTimer("${type.type}'s duration callback doesn't save its captured preferences' timestamp at $at")
        Kept(at + 1, value)
    }
    if (kinds != setOf(Long.MAX_VALUE, 3600000L, 86400000L)) refuseTimer("${type.type}'s duration choices aren't always, one hour and one day")
    return TimerChoices(MethodSite(type.type, callback.name, callback.timerParameters()), keeps)
}

/** The native setter passes its wide parameter through the descriptor and metadata the check reads. */
private fun proveExpirationSetter(setter: Method, check: Method): MethodReference {
    val code = setter.timerCode()
    val opcodes = listOf(Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.SGET_OBJECT, Opcode.SGET_OBJECT, Opcode.AGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID)
    if (code.map { it.opcode } != opcodes) refuseTimer("${setter.definingClass}'s expiration setter has an unproved body")
    val zero = (code[0] as OneRegisterInstruction).registerA
    val descriptor = code[2].timerField()!!
    val metadata = code[3].timerField()!!
    val d = (code[2] as OneRegisterInstruction).registerA
    val m = (code[3] as OneRegisterInstruction).registerA
    val element = code[4] as ThreeRegisterInstruction
    val prefs = setter.parameterRegisterNumber(0)
    val timestamp = setter.parameterRegisterNumber(1)
    val save = code[5].timerMethod()!!
    val reads = check.timerCode().filter { it.opcode == Opcode.SGET_OBJECT }.map { it.timerField()?.toString() }
    if ((code[0] as NarrowLiteralInstruction).narrowLiteral != 0 || code[1].timerMethod()?.parameterTypes != listOf("Ljava/lang/Object;", "I") ||
        code[1].timerMethod()?.returnType != "V" || code[1].timerArguments() != listOf(prefs, zero) ||
        descriptor.definingClass != setter.definingClass || metadata.definingClass != setter.definingClass ||
        descriptor.toString() !in reads || metadata.toString() !in reads || !metadata.type.startsWith("[L") ||
        element.registerA != m || element.registerB != m || element.registerC != zero ||
        save.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;", descriptor.type, metadata.type.drop(1), "J") ||
        save.returnType != "V" || code[5].timerArguments() != listOf(prefs, d, m, timestamp, timestamp + 1) ||
        setOf(zero, d, m, prefs, timestamp, timestamp + 1).size != 6
    ) refuseTimer("${setter.definingClass}'s expiration setter doesn't save its wide timestamp parameter")
    return save
}

private fun BytecodePatchContext.proveBoxedTimestamp(call: MethodReference) {
    val helper = classDefBy(call.definingClass).methods.singleOrNull { it.toString() == call.toString() }
        ?: refuseTimer("$call has no declared timestamp boxing helper")
    val code = helper.timerCode()
    if (!helper.concreteTimer() || !AccessFlags.STATIC.isSet(helper.accessFlags) || code.map { it.opcode } != listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.RETURN_VOID)) {
        refuseTimer("$call has an unproved timestamp boxing body")
    }
    val boxed = (code[1] as OneRegisterInstruction).registerA
    val wide = helper.parameterRegisterNumber(3)
    val save = code[2].timerMethod()!!
    if (code[0].timerMethod()?.toString() != "Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;" || code[0].timerArguments() != listOf(wide, wide + 1) ||
        save.definingClass != helper.timerParameters()[1] || save.returnType != "V" ||
        save.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;", "Ljava/lang/Object;", helper.timerParameters()[2]) ||
        code[2].timerArguments() != listOf(helper.parameterRegisterNumber(1), helper.parameterRegisterNumber(0), boxed, helper.parameterRegisterNumber(2)) ||
        boxed in helper.parameterRegisterNumber(0)..wide + 1
    ) refuseTimer("$call doesn't save the boxed timestamp parameter")
}

private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val DIRECT_CALLS = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
private fun Method.concreteTimer() = implementation != null && !AccessFlags.NATIVE.isSet(accessFlags) && !AccessFlags.ABSTRACT.isSet(accessFlags)
private fun Method.timerCode() = implementation?.instructions?.toList().orEmpty()
private fun Method.timerParameters() = parameterTypes.map(CharSequence::toString)
private fun Instruction.timerString() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.timerMethod() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.timerField() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.timerArguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
