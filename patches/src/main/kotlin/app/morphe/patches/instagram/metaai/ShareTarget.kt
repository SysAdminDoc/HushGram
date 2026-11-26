/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
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

internal const val MUSE_RECIPIENT = "$EXTENSION_PACKAGE/metaai/MetaAi;->museRecipient(Ljava/lang/Object;)Ljava/lang/Object;"

/** What Meta AI's share action calls its cell in the grid of people, in its checks and its logging. */
internal const val MUSE_GRID = "ig_sharesheet_muse_recipient_grid"

/** The view the share sheet's grid adapter names its New group button with, a string it alone loads. */
internal const val GRID_ADAPTER = "direct_share_sheet_new_group_pog_view"

private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"

/** The grid adapter's static setter for its Muse cell, and the class of Meta AI's share action it takes. */
internal class MuseRecipientSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val muse: String,
    val register: Int,
)

/**
 * The share sheet's grid of people to send to has one cell that isn't a person or a chat: Muse, or
 * Meta AI on accounts that name it so, which the grid holds as Meta AI's share action. That action's
 * class is the one naming Meta AI's target [HATCH_TARGET], as the bottom row's case does, and its
 * cell [MUSE_GRID]; 386314340 folds the check holding the cell's name into its callers, but the
 * action's logger keeps it. The grid's adapter, a class loading [GRID_ADAPTER], takes the action
 * through one static setter, (adapter, action, callback, boolean), and every way in goes through it:
 * the sheet handing the action over, the sheet closing, and each search, which matches the action's
 * name against what was typed. The setter tests the action first and keeps it in the adapter's field.
 */
internal fun BytecodePatchContext.findMuseRecipientSetter(): MuseRecipientSite {
    val setters = mutableListOf<Pair<ClassDef, Method>>()
    classesLoadingString(GRID_ADAPTER).forEach { adapter ->
        adapter.methods.forEach { method ->
            val parameters = method.parameterTypes.map(CharSequence::toString)
            if (AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && parameters.size == 4 &&
                parameters[0] == adapter.type && parameters[2] == FUNCTION1 && parameters[3] == "Z" && isMetaAiAction(parameters[1])
            ) {
                setters += adapter to method
            }
        }
    }
    val (adapter, setter) = setters.singleOrNull()
        ?: refuseShare("${setters.size} share sheet grid setters take Meta AI's share action, not one")
    val where = "${adapter.type}->${setter.name}"
    val muse = setter.parameterTypes[1].toString()
    val code = setter.implementation?.instructions?.toList().orEmpty()
    // A static method's four one-register parameters are its last four registers, the action second.
    val register = (setter.implementation?.registerCount ?: 0) - 3
    val first = code.firstOrNull()
    if (first == null || (first.opcode != Opcode.IF_NEZ && first.opcode != Opcode.IF_EQZ) ||
        (first as OneRegisterInstruction).registerA != register
    ) {
        refuseShare("$where doesn't test Meta AI's share action first")
    }
    val keeps = code.any { instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        instruction.opcode == Opcode.IPUT_OBJECT && field != null && field.definingClass == adapter.type && field.type == muse &&
            (instruction as TwoRegisterInstruction).registerA == register
    }
    if (!keeps) refuseShare("$where doesn't keep Meta AI's share action in the adapter")
    // The hook goes in front of the test, so nothing may jump there.
    if (0 in setter.jumpTargets()) refuseShare("a branch enters $where at its start")
    return MuseRecipientSite(adapter.type, setter.name, setter.parameterTypes.map(CharSequence::toString), muse, register)
}

/** Passes the share action the grid's setter is handed through MetaAi.museRecipient, before the setter reads it. */
internal fun BytecodePatchContext.holdMuseRecipient(site: MuseRecipientSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        0,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $MUSE_RECIPIENT
            move-result-object v${site.register}
            check-cast v${site.register}, ${site.muse}
        """,
    )
}

/** Whether [type] is Meta AI's share action: a class whose code names both [HATCH_TARGET] and [MUSE_GRID]. */
private fun BytecodePatchContext.isMetaAiAction(type: String): Boolean {
    if (!type.startsWith("L")) return false
    val classDef = classDefByOrNull(type) ?: return false
    return classDef.methods.any { loadsString(it, MUSE_GRID) } && classDef.methods.any { loadsString(it, HATCH_TARGET) }
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
