/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.blend

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.direct.ghost.ghostModeEntryPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

internal const val BLEND_VISITS_PATCH = "Don't report Blend visits"
internal const val BLEND_VISITS = "$EXTENSION_PACKAGE/direct/BlendVisits;"
internal const val QUIET_VISIT = "$BLEND_VISITS->quiet(I)Z"
internal const val SKIP_VISIT_TIME = "$BLEND_VISITS->skipsVisitTime()Z"

/** The endpoint a Blend's reels load from when you open it. */
internal const val BLEND_MEDIAS = "clips/get_blend_medias/"

/**
 * The name of the mutation sending the time you last opened a Blend, which only the step building
 * its request loads (#474). Instagram 456 dropped the repository's purge marker and inlines its
 * sender into the two places that open a Blend, so the hook goes where the request is built.
 */
internal const val BLEND_LAST_VISIT = "UpdateBlendLastVisitedTime"

private const val OBJECT = "Ljava/lang/Object;"

/** (name, flag): how the request builder takes a boolean query parameter, the visit flag among them. */
internal val FLAG_PARAMETER = listOf("Ljava/lang/String;", "Z")

@Suppress("unused")
val dontReportBlendVisitsPatch = bytecodePatch(
    name = "Don't report Blend visits",
    description = "Stops Instagram from telling a chat that you opened its Blend. Ghost mode, at the top of Ads " +
        "and privacy, turns it on with the others. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Ghost mode")
    dependsOn(settingsPatch, instagramExtensionPatch, ghostModeEntryPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("blendVisits")
        val request = findBlendRequest()
        val visitTime = findBlendVisitTime()
        quietBlendVisit(request)
        skipBlendVisitTime(visitTime)
        enableStatus("blendVisits")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$BLEND_VISITS_PATCH: $why")

/** The method building a Blend's reels request, and the register holding its visit flag on entry. */
internal class BlendRequest(val method: MutableMethod, val flag: Int)

/**
 * The static method building the request for a Blend's reels ([BLEND_MEDIAS]), whose one boolean
 * says whether to leave the "visited the Blend" message out of the Blend's chat
 * (force_disable_visitation_xma). Proved before anything changes: the flag is read once, as the
 * value of a (name, boolean) query parameter, and is still the caller's there; nothing jumps back
 * to the first instruction, where the hook goes.
 */
internal fun BytecodePatchContext.findBlendRequest(): BlendRequest {
    val requests = classesLoadingString(BLEND_MEDIAS).flatMap { it.methods }.filter { loadsString(it, BLEND_MEDIAS) }
    val found = requests.singleOrNull() ?: refuse("expected one method loading \"$BLEND_MEDIAS\", found ${requests.size}")
    val types = found.parameterTypes.map(CharSequence::toString)
    val request = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.returnType == found.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
    val where = "${request.definingClass}->${request.name}"
    if (!AccessFlags.STATIC.isSet(request.accessFlags)) refuse("$where isn't static")
    val flags = types.indices.filter { types[it] == "Z" }
    val flag = flags.singleOrNull() ?: refuse("$where takes ${flags.size} booleans, not one")
    val register = request.parameterRegisterNumber(flag)
    val code = request.implementation!!.instructions.toList()
    val reads = code.indices.filter { register in code[it].callRegisters() }
    val read = reads.singleOrNull() ?: refuse("$where reads its boolean in ${reads.size} calls, not one")
    val call = (code[read] as ReferenceInstruction).reference as? MethodReference
        ?: refuse("$where puts its boolean in ${(code[read] as ReferenceInstruction).reference}, not a call")
    if (call.parameterTypes.map(CharSequence::toString) != FLAG_PARAMETER || call.returnType != "V" ||
        code[read].callRegisters().last() != register
    ) {
        refuse("$where hands its boolean to $call, not as a (name, boolean) parameter")
    }
    request.requireParameterIntact(BLEND_VISITS_PATCH, flag, listOf(read))
    if (0 in request.jumpTargets()) refuse("something jumps back to $where's first instruction")
    return BlendRequest(request, register)
}

/** The flag Instagram asked for goes through [QUIET_VISIT] first, which answers true while the switch is on. */
internal fun quietBlendVisit(request: BlendRequest) {
    val flag = "v${request.flag}"
    request.method.addInstructions(
        0,
        """
            invoke-static/range { $flag .. $flag }, $QUIET_VISIT
            move-result $flag
        """,
    )
}

/**
 * Where the visit time's request is built: the step [method] (a coroutine's, answering an object),
 * the load of [BLEND_LAST_VISIT] at [at] into [register], and [done], the field the step reads to
 * answer that it's finished (Kotlin's Unit).
 */
internal class BlendVisitTime(val method: MutableMethod, val at: Int, val register: Int, val done: FieldReference)

/**
 * The step building the request that sends the time you last opened a Blend ([BLEND_LAST_VISIT]),
 * which is what shows the chat that you looked even with the visit message left out (#474). On 456
 * it's one case of a coroutine's invokeSuspend (`LX/0Smk;` on 386214574), started by the two places
 * that open a Blend. Proved before anything changes: one method loads the name, it answers an
 * object, it loads the name once as a literal, nothing jumps onto that load, and it answers one
 * singleton read right before a return when it's done.
 */
internal fun BytecodePatchContext.findBlendVisitTime(): BlendVisitTime {
    val senders = classesLoadingString(BLEND_LAST_VISIT).sortedBy { it.type }.flatMap { it.methods }
        .filter { loadsString(it, BLEND_LAST_VISIT) }
    val found = senders.singleOrNull() ?: refuse("expected one method loading \"$BLEND_LAST_VISIT\", found ${senders.size}")
    val types = found.parameterTypes.map(CharSequence::toString)
    val sender = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.returnType == found.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
    val where = "${sender.definingClass}->${sender.name}"
    if (sender.returnType != OBJECT) refuse("$where returns ${sender.returnType}, not an object")
    val code = sender.implementation!!.instructions.toList()
    val loads = code.indices.filter {
        (code[it].opcode == Opcode.CONST_STRING || code[it].opcode == Opcode.CONST_STRING_JUMBO) &&
            ((code[it] as ReferenceInstruction).reference as StringReference).string == BLEND_LAST_VISIT
    }
    val at = loads.singleOrNull() ?: refuse("$where loads \"$BLEND_LAST_VISIT\" as a literal ${loads.size} times, not once")
    if (at in sender.jumpTargets()) refuse("something jumps onto $where's load of \"$BLEND_LAST_VISIT\"")
    val finished = code.indices.drop(1).mapNotNull { next ->
        val read = code[next - 1]
        val field = (read as? ReferenceInstruction)?.reference as? FieldReference
        if (read.opcode != Opcode.SGET_OBJECT || field == null || field.type != field.definingClass ||
            code[next].opcode != Opcode.RETURN_OBJECT ||
            (code[next] as OneRegisterInstruction).registerA != (read as OneRegisterInstruction).registerA
        ) {
            null
        } else {
            ImmutableFieldReference(field.definingClass, field.name, field.type)
        }
    }.distinct()
    val done = finished.singleOrNull() ?: refuse("$where answers ${finished.size} singletons when it's done, not one")
    return BlendVisitTime(sender, at, (code[at] as OneRegisterInstruction).registerA, done)
}

/**
 * Right before the request's name loads, the step asks [SKIP_VISIT_TIME] and on a yes answers that
 * it's done, so the time never goes out. The name's register is free there, since the load writes
 * over it next.
 */
internal fun skipBlendVisitTime(site: BlendVisitTime) {
    val answer = "v${site.register}"
    site.method.addInstructionsWithLabels(
        site.at,
        """
            invoke-static { }, $SKIP_VISIT_TIME
            move-result $answer
            if-eqz $answer, :send
            sget-object $answer, ${site.done}
            return-object $answer
        """,
        ExternalLabel("send", site.method.getInstruction(site.at)),
    )
}

/** The registers a call reads, in order. */
private fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}
