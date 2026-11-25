/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.feed.back.enumConstantName
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val RESTYLE_BYLINE = "$EXTENSION_PACKAGE/metaai/MetaAi;->restyleByline(Ljava/lang/Object;)Z"
internal const val RESTYLE_PILL = "$EXTENSION_PACKAGE/metaai/MetaAi;->restylePill()Z"

/** What every Edits credit's gate name starts with, which the gate tests first. Instagram 457 keeps it. */
internal const val EDITS_GATE_PREFIX = "EDITS_"

/** The name the byline hands that gate for Meta AI's restyle credit. Instagram 457 keeps it. */
internal const val RESTYLE_GATE = "EDITS_AI_RESTYLE"

/** What the byline's Edits credit gate takes: the session and the credit's name. */
internal val RESTYLE_GATE_PARAMETERS = listOf("Lcom/instagram/common/session/UserSession;", "Ljava/lang/String;")

/** The creation tool Meta AI's restyle is, by the constant's name, which Redex leaves its enum. */
internal const val RESTYLE_TOOL = "BASEL_MODIFY"


/** Where one of a reel's restyle credit checks is: its class, name and parameters. */
internal class RestyleCreditCheck(val type: String, val name: String, val parameters: List<String>)

/**
 * A reel's byline picks one line from a list of kinds, and before Meta AI's restyle credit (the
 * BSL_RESTYLE_ATTRIBUTION kind, built by 0B0N.A0R on 457's 386314341) it asks one gate whether to
 * leave the credit out, handing it the credit's name, EDITS_AI_RESTYLE: 0JqL.A00, an instance
 * method taking (session, name), which tests the name starts with [EDITS_GATE_PREFIX] and answers
 * from Instagram's own server flags. A yes skips the credit, and the byline goes on to the kinds
 * after it, as it does when Instagram's flag holds the credit back. The class has a second gate
 * of the same shape (A01, asked by another screen), so the one this patch takes is the gate a
 * method loading [RESTYLE_GATE] calls. Fails unless exactly one is, since that's an update this
 * patch hasn't seen.
 */
internal fun BytecodePatchContext.findRestyleBylineGate(): RestyleCreditCheck {
    val gates = classesLoadingString(EDITS_GATE_PREFIX).flatMap { it.methods }
        .filter { it.isRestyleBylineGate() && loadsString(it, EDITS_GATE_PREFIX) }
        .associateBy { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
    val asked = classesLoadingString(RESTYLE_GATE).flatMap { it.methods }.filter { loadsString(it, RESTYLE_GATE) }
        .flatMap { asker ->
            asker.implementation?.instructions?.mapNotNull { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.toString()?.let(gates::get)
            }.orEmpty()
        }.distinctBy { "${it.definingClass}->${it.name}" }
    val gate = asked.singleOrNull()
        ?: throw PatchException("Hide Meta AI: ${asked.size} gates are asked with $RESTYLE_GATE whether a reel's byline leaves an Edits credit out (instance, ${RESTYLE_GATE_PARAMETERS.joinToString()}), not one")
    return RestyleCreditCheck(gate.definingClass, gate.name, gate.parameterTypes.map(CharSequence::toString))
}

/** An instance method taking [RESTYLE_GATE_PARAMETERS], answering a boolean, that calls String.startsWith. */
internal fun Method.isRestyleBylineGate(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "Z") return false
    if (parameterTypes.map(CharSequence::toString) != RESTYLE_GATE_PARAMETERS) return false
    val code = implementation?.instructions?.toList() ?: return false
    return code.any { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == "Ljava/lang/String;" && called.name == "startsWith"
    }
}

/**
 * A reel's pill row picks one pill, and Meta AI's restyle credit pill (the
 * BASEL_CREATIVE_TOOL_ATTRIBUTION kind, built by 0An0.A0R on 457's 386314341) goes in only when one
 * static check answers yes: 01Zx.A0D, taking the reel's media and the session, which looks for
 * the restyle tool ([RESTYLE_TOOL] of the creation tool enum) among the tools the reel was made
 * with and then asks a server flag. It reads no other constant of that enum, which tells it from
 * the checks for Meta AI's other tools. Three enums load the name on 457 (the creation tools, an
 * editor tool list and a table that keeps it as a value), so every enum with a constant built as
 * [RESTYLE_TOOL] is read. Fails unless exactly one static method of that shape reads such a
 * constant and nothing else of its enum.
 */
internal fun BytecodePatchContext.findRestylePillCheck(): RestyleCreditCheck {
    val restyle = classesLoadingString(RESTYLE_TOOL).filter { tool ->
        AccessFlags.ENUM.isSet(tool.accessFlags) && tool.methods.any { it.name == "<clinit>" && loadsString(it, RESTYLE_TOOL) }
    }.mapNotNull { tool ->
        tool.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == tool.type }
            .singleOrNull { enumConstantName(tool, it) == RESTYLE_TOOL }?.let { tool.type to it.name }
    }.toMap()
    if (restyle.isEmpty()) throw PatchException("Hide Meta AI: no enum has a constant built as $RESTYLE_TOOL")
    // A new fingerprint each time: it searches the patcher's current run, and a test makes several.
    val matches = Fingerprint(returnType = "Z", custom = { method, _ -> method.isRestylePillCheck(restyle) })
        .matchAllOrNull().orEmpty().map { it.originalMethod }
    val check = matches.singleOrNull()
        ?: throw PatchException("Hide Meta AI: ${matches.size} static checks read only $RESTYLE_TOOL of ${restyle.keys.joinToString()} for a reel's pill (media, session), not one")
    return RestyleCreditCheck(check.definingClass, check.name, check.parameterTypes.map(CharSequence::toString))
}

/**
 * A static method taking two parameters, the session second, answering a boolean, that reads one
 * of [restyle]'s enums only for the constant named with it (enum type to field name).
 */
internal fun Method.isRestylePillCheck(restyle: Map<String, String>): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "Z") return false
    val parameters = parameterTypes.map(CharSequence::toString)
    if (parameters.size != 2 || parameters[1] != RESTYLE_GATE_PARAMETERS[0]) return false
    val code = implementation?.instructions?.toList() ?: return false
    val read = code.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.SGET_OBJECT) return@mapNotNull null
        ((instruction as ReferenceInstruction).reference as FieldReference).takeIf { it.definingClass in restyle && it.type == it.definingClass }
    }
    return read.isNotEmpty() && read.all { restyle[it.definingClass] == it.name }
}

/**
 * Makes the byline's gate answer yes from its first instruction for Meta AI's restyle credit
 * while Hide Meta AI restyle credits is on: the answer Instagram's own flag gives when it holds
 * the credit back, so the byline moves on to its next kind. The extension answers no for every
 * other name, and with the switch off or paused, so those take the gate's own path. The name is
 * read at the method's entry, before anything can reuse its register, and the method's first local
 * is free there. Nothing jumps back to its first instruction.
 */
internal fun BytecodePatchContext.holdRestyleByline(gate: RestyleCreditCheck) {
    val method = restyleMethod(gate)
    val name = method.parameterRegister(RESTYLE_GATE_PARAMETERS.indexOf("Ljava/lang/String;"))
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { $name .. $name }, $RESTYLE_BYLINE
            move-result v0
            if-eqz v0, :gate
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("gate", method.getInstruction(0)),
    )
}

/**
 * Makes the pill's check answer no from its first instruction while Hide Meta AI restyle credits
 * is on: the answer a reel made without the restyle tool gets, so the pill row picks as it would
 * for one. With the switch off or paused the check takes its own path. The method's first local
 * is free at its entry, and nothing jumps back to its first instruction.
 */
internal fun BytecodePatchContext.holdRestylePill(check: RestyleCreditCheck) {
    val method = restyleMethod(check)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $RESTYLE_PILL
            move-result v0
            if-eqz v0, :check
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("check", method.getInstruction(0)),
    )
}

/** The mutable method [check] names, refused when it has no local to borrow or something jumps to its start. */
private fun BytecodePatchContext.restyleMethod(check: RestyleCreditCheck): MutableMethod {
    val method = mutableClassDefBy(check.type).methods.single {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == check.parameters
    }
    method.requireLocals("Hide Meta AI", 1)
    if (0 in method.jumpTargets()) throw PatchException("Hide Meta AI: something jumps back to ${check.type}->${check.name}'s first instruction")
    return method
}
