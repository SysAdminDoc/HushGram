/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.blend

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val BLEND_VISITS_PATCH = "Don't report Blend visits"
internal const val BLEND_VISITS = "$EXTENSION_PACKAGE/direct/BlendVisits;"
internal const val QUIET_VISIT = "$BLEND_VISITS->quiet(I)Z"

/** The endpoint a Blend's reels load from when you open it. */
internal const val BLEND_MEDIAS = "clips/get_blend_medias/"

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
        quietBlendVisit(findBlendRequest())
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

/** The registers a call reads, in order. */
private fun Instruction.callRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}
