/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val SHARE_TARGET = "$EXTENSION_PACKAGE/metaai/MetaAi;->shareTarget(I)Z"

/** The name the share sheet gives Meta AI's target, the one that shows as Muse on some accounts. */
internal const val HATCH_TARGET = "hatch"

/** Meta AI's target name and three more that only the share sheet's target builder compares on 450. */
internal val SHARE_ROW_NAMES = listOf(HATCH_TARGET, "whatsapp_status", "add_to_audio_note", "screenshot_preview")

private const val EQUALS = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"

/**
 * The share sheet's comparison of a target name with [HATCH_TARGET]: the method, and the index and
 * register of the move-result that takes the answer, just ahead of its if-eqz.
 */
internal class ShareTargetSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val moveResult: Int,
    val register: Int,
)

/**
 * The share sheet builds the targets in its bottom row from a list of names, one switch case per
 * name it knows. Meta AI's case compares the name with "hatch", and a no there goes on to the next
 * name the way an unknown one does, so the row is built without that target. The builder is the one
 * method loading [SHARE_ROW_NAMES], each itself or from a pool of shared strings: 450's 385611400
 * asks a pool for add_to_audio_note there (#77). Its case loads "hatch" itself on every build.
 * Another check in it, which moves an existing hatch target in the row, compares the other way
 * round and isn't the case.
 */
internal fun BytecodePatchContext.findShareTargetCheck(): ShareTargetSite {
    val builders = mutableListOf<Pair<ClassDef, Method>>()
    classesHolding(HATCH_TARGET).forEach { classDef ->
        classDef.methods.forEach { method ->
            if (!method.isStringPool() && HATCH_TARGET in method.targetStrings() && SHARE_ROW_NAMES.all { loadsString(method, it) }) {
                builders += classDef to method
            }
        }
    }
    val (classDef, method) = builders.singleOrNull()
        ?: refuseShare("${builders.size} methods hold ${SHARE_ROW_NAMES.joinToString(", ")}, not one")
    val where = "${classDef.type}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val cases = code.indices.filter { at ->
        val name = ((code[at] as? ReferenceInstruction)?.reference as? StringReference)?.string
        if (name != HATCH_TARGET || at + 3 >= code.size) return@filter false
        val hatch = (code[at] as OneRegisterInstruction).registerA
        val compare = code[at + 1].targetArguments()
        val result = code[at + 2] as? OneRegisterInstruction
        val test = code[at + 3] as? OneRegisterInstruction
        code[at + 1].opcode == Opcode.INVOKE_VIRTUAL && code[at + 1].targetMethod()?.toString() == EQUALS &&
            compare.size == 2 && compare[1] == hatch && compare[0] != hatch &&
            code[at + 2].opcode == Opcode.MOVE_RESULT && code[at + 3].opcode == Opcode.IF_EQZ &&
            result != null && test != null && test.registerA == result.registerA
    }
    val case = cases.singleOrNull() ?: refuseShare("$where compares ${cases.size} target names with \"$HATCH_TARGET\", not one")
    // The answer goes through the hook between its move-result and the test, so nothing may jump in there.
    val targets = method.jumpTargets()
    if ((case + 1..case + 3).any { it in targets }) refuseShare("a branch enters $where's \"$HATCH_TARGET\" case")
    return ShareTargetSite(
        classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
        case + 2, (code[case + 2] as OneRegisterInstruction).registerA,
    )
}

/** Passes the answer of the share sheet's "hatch" comparison through MetaAi.shareTarget. */
internal fun BytecodePatchContext.holdShareTarget(site: ShareTargetSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        site.moveResult + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $SHARE_TARGET
            move-result v${site.register}
        """,
    )
}

private fun Method.targetStrings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet() ?: emptySet()

/** A static (int)String pool of shared strings, which holds names without building anything. */
private fun Method.isStringPool(): Boolean = AccessFlags.STATIC.isSet(accessFlags) && returnType == "Ljava/lang/String;" &&
    parameterTypes.map(CharSequence::toString) == listOf("I")

private fun Instruction.targetMethod(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.targetArguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun refuseShare(detail: String): Nothing = throw PatchException("Hide Meta AI: $detail")
