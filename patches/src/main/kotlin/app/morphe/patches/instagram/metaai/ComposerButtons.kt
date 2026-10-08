/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoading
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val COMPOSER_BUTTON = "$EXTENSION_PACKAGE/metaai/MetaAi;->composerButton(Ljava/lang/Object;I)Z"
internal val COMPOSER_BUTTON_NAMES = listOf("META_AI_DISCOVERY", "META_AI_INVOCATION", "META_AI_VOICE")

/** Instagram 450's row_thread_composer_meta_ai_discover, invocation and voice ids. */
internal val COMPOSER_BUTTON_IDS = listOf(0x7f0b386b, 0x7f0b386c, 0x7f0b386d)
private const val VIEW = "Landroid/view/View;"
private const val FIND_VIEW = "$VIEW->findViewById(I)$VIEW"

internal class ComposerVisibilitySite(val type: String, val name: String, val parameters: List<String>)

/**
 * Discover the composer through its three optional resource lookups and the button enum's names.
 * Then require the native visibility dispatcher: it asks its own nullable view getter, skips an
 * absent view, branches on the incoming show flag, and sends 0 or 8 to the same visibility call.
 * Its callback receives that same flag. No instruction changes until every check has passed.
 */
internal fun BytecodePatchContext.findComposerButtonVisibility(): ComposerVisibilitySite {
    val enums = classesHolding(*COMPOSER_BUTTON_NAMES.toTypedArray()).filter { candidate ->
        candidate.superclass == "Ljava/lang/Enum;" && candidate.methods.any { method ->
            method.name == "<clinit>" && method.code().mapNotNull { it.string() }.containsAll(COMPOSER_BUTTON_NAMES)
        }
    }
    val buttonEnum = enums.singleOrNull() ?: refuseComposer("${enums.size} composer button enums, not one")
    val loading = COMPOSER_BUTTON_IDS.map { id -> classesLoading(id.toLong()).mapTo(HashSet()) { it.type } }
    val lookups = classesLoading(COMPOSER_BUTTON_IDS.first().toLong()).filter { candidate -> loading.all { candidate.type in it } }
        .flatMap { candidate -> candidate.methods.map { candidate to it } }.filter { (_, method) ->
        method.parameterTypes.isEmpty() && method.returnType == "Ljava/lang/Object;" &&
            method.code().mapNotNull { it.literal() }.containsAll(COMPOSER_BUTTON_IDS)
    }
    val (lookupClass, lookup) = lookups.singleOrNull() ?: refuseComposer("${lookups.size} optional composer lookup methods, not one")
    requireOptionalLookups(lookup)
    val candidates = classesCalling(lookupClass.type, "<init>").filter { candidate ->
        candidate.methods.any { method ->
            method.name == "<init>" && method.code().any { instruction ->
                instruction.methodReference()?.let { it.definingClass == lookupClass.type && it.name == "<init>" } == true
            }
        }
    }.flatMap { candidate ->
        candidate.methods.filter { method -> isVisibilityDispatcher(candidate, method, buttonEnum.type) }
            .map { candidate to it }
    }
    val (controller, method) = candidates.singleOrNull()
        ?: refuseComposer("${candidates.size} native composer visibility dispatchers, not one")
    return ComposerVisibilitySite(controller.type, method.name, method.parameterTypes.map(CharSequence::toString))
}

/** One hook, before the native getter. The app keeps its own hiding, inflation and spacing logic. */
internal fun BytecodePatchContext.holdComposerButtons(site: ComposerVisibilitySite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(0, """
        invoke-static/range { p2 .. p3 }, $COMPOSER_BUTTON
        move-result p3
    """)
}

private fun requireOptionalLookups(method: Method) {
    val code = method.code()
    for (id in COMPOSER_BUTTON_IDS) {
        val loads = code.indices.filter { code[it].literal() == id }
        val load = loads.singleOrNull() ?: refuseComposer("composer view $id is loaded ${loads.size} times, not once")
        val result = code.getOrNull(load + 2) as? OneRegisterInstruction
        val zero = code.getOrNull(load + 3) as? OneRegisterInstruction
        val test = code.getOrNull(load + 4) as? OneRegisterInstruction
        val target = code.target(load + 4)
        if (code.getOrNull(load + 1)?.methodReference()?.toString() != FIND_VIEW ||
            code[load + 1].arguments().lastOrNull() != (code[load] as OneRegisterInstruction).registerA ||
            code.getOrNull(load + 2)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
            code.getOrNull(load + 3)?.literal() != 0 || code.getOrNull(load + 4)?.opcode != Opcode.IF_EQZ ||
            result == null || zero == null || test?.registerA != result.registerA ||
            target == null || code[target].opcode != Opcode.RETURN_OBJECT ||
            (code[target] as OneRegisterInstruction).registerA != zero.registerA) {
            refuseComposer("composer view $id isn't an optional lookup returning null when absent")
        }
    }
}

private fun isVisibilityDispatcher(owner: ClassDef, method: Method, buttonEnum: String): Boolean {
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
        parameters.size != 3 || parameters[1] != buttonEnum || parameters[2] != "Z") return false
    val code = method.code()
    val call = code.firstOrNull()?.methodReference() ?: return false
    val firstParameter = (method.implementation?.registerCount ?: return false) - 4
    val incoming = (firstParameter..firstParameter + 3).toList()
    val getter = owner.methods.singleOrNull {
        it.name == call.name && it.parameterTypes.map(CharSequence::toString) == parameters && it.returnType == VIEW
    } ?: return false
    if (code[0].opcode != Opcode.INVOKE_DIRECT || call.definingClass != owner.type || call.returnType != VIEW ||
        call.parameterTypes.map(CharSequence::toString) != parameters || code[0].arguments() != incoming ||
        getter.code().none { it.methodReference()?.let { ref -> ref.name == "getView" && ref.parameterTypes.isEmpty() && ref.returnType == VIEW } == true }) return false
    val result = (code.getOrNull(1) as? OneRegisterInstruction)?.registerA ?: return false
    val skip = code.target(2) ?: return false
    val hide = code.target(3) ?: return false
    if (code.getOrNull(1)?.opcode != Opcode.MOVE_RESULT_OBJECT || code.getOrNull(2)?.opcode != Opcode.IF_EQZ ||
        (code[2] as OneRegisterInstruction).registerA != result || code[skip].opcode != Opcode.RETURN_VOID ||
        code.getOrNull(3)?.opcode != Opcode.IF_EQZ || (code[3] as OneRegisterInstruction).registerA != incoming[3]) return false
    val setters = code.indices.filter { index ->
        val ref = code[index].methodReference()
        code[index].opcode == Opcode.INVOKE_STATIC && ref?.returnType == "V" &&
            ref.parameterTypes.map(CharSequence::toString) == listOf(VIEW, "I", "I") &&
            code[index].arguments().firstOrNull() == result
    }
    val setter = setters.singleOrNull() ?: return false
    val visibility = code[setter].arguments()[1]
    if (code.getOrNull(setter - 2)?.literal() != 0 || (code[setter - 2] as? OneRegisterInstruction)?.registerA != visibility ||
        code[hide].literal() != 8 || (code[hide] as? OneRegisterInstruction)?.registerA != visibility ||
        code.getOrNull(hide + 1)?.opcode !in listOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) ||
        code.target(hide + 1) != setter - 1) return false
    return code.drop(setter + 1).count { instruction ->
        val ref = instruction.methodReference()
        ref?.returnType == "V" && ref.parameterTypes.map(CharSequence::toString) == parameters &&
            instruction.arguments().drop(1) == incoming.drop(1)
    } == 1
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.literal(): Int? = (this as? NarrowLiteralInstruction)?.narrowLiteral
private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun List<Instruction>.target(index: Int): Int? {
    val branch = getOrNull(index) as? OffsetInstruction ?: return null
    val address = take(index).sumOf { it.codeUnits } + branch.codeOffset
    var current = 0
    for (candidate in indices) {
        if (current == address) return candidate
        current += this[candidate].codeUnits
    }
    return null
}

private fun refuseComposer(detail: String): Nothing = throw PatchException("Hide Meta AI: $detail")
