/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested people on profiles"

internal const val MUTUAL_SUGGESTIONS = "$PROFILE_SUGGESTIONS->mutualSuggestions(Ljava/util/List;)Ljava/util/List;"

/**
 * What the mutual followers list calls its adapter when it checks the adapter is set up. Kotlin
 * writes the property's name into that check, and only that list and its callbacks load it.
 */
internal const val MUTUAL_ADAPTER = "socialContextFollowListAdapter"

private const val LIST = "Ljava/util/List;"
private const val IS_EMPTY = "Ljava/util/List;->isEmpty()Z"

/** The mutual list adapter's read of its suggested accounts: its method, and the read's register. */
internal class MutualSuggestionsSite(val type: String, val name: String, val parameters: List<String>, val read: Int, val register: Int)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds where the mutual followers list (the mutual tab on someone else's follow lists) decides
 * whether to draw Suggested for you under the people you both follow (#302).
 *
 * The adapter is the type of the field each [MUTUAL_ADAPTER] check reads: a read of the field, an
 * `if-nez` on it, then the name loaded for the error. Every check has to agree on that one type. In
 * the adapter, the rows are rebuilt by one method, and its only list field read straight into an
 * `isEmpty()` is the suggested accounts': no accounts skips the section's header, its rows and its
 * See all, and the mutual followers above stay as they are. Fails before anything changes when the
 * checks don't agree, the read isn't there exactly once, or something jumps to the `isEmpty()`.
 */
internal fun BytecodePatchContext.findMutualSuggestions(): MutualSuggestionsSite {
    val adapters = mutableSetOf<String>()
    classesLoadingString(MUTUAL_ADAPTER).forEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.indices.filter { stringLoadedAt(code, it) == MUTUAL_ADAPTER }.forEach { at ->
                val start = if (code[at].opcode == Opcode.CONST_STRING || code[at].opcode == Opcode.CONST_STRING_JUMBO) at else at - 1
                val check = code.getOrNull(start - 1)?.takeIf { it.opcode == Opcode.IF_NEZ } ?: return@forEach
                val read = code.getOrNull(start - 2)?.takeIf { it.opcode == Opcode.IGET_OBJECT } ?: return@forEach
                if ((read as TwoRegisterInstruction).registerA != (check as OneRegisterInstruction).registerA) return@forEach
                adapters += ((read as ReferenceInstruction).reference as FieldReference).type
            }
        }
    }
    val adapter = adapters.singleOrNull() ?: refuse("expected one adapter behind the mutual list's $MUTUAL_ADAPTER checks, found ${adapters.sorted()}")
    val classDef = classDefByOrNull(adapter) ?: refuse("the mutual list's adapter $adapter isn't in this build")
    val reads = mutableListOf<MutualSuggestionsSite>()
    classDef.methods.forEach { method ->
        val code = method.implementation?.instructions?.toList() ?: return@forEach
        code.indices.forEach { at ->
            val read = code[at]
            if (read.opcode != Opcode.IGET_OBJECT) return@forEach
            val field = (read as ReferenceInstruction).reference as FieldReference
            if (field.definingClass != adapter || field.type != LIST) return@forEach
            val register = (read as TwoRegisterInstruction).registerA
            val next = code.getOrNull(at + 1) ?: return@forEach
            if (next.methodReference().toString() != IS_EMPTY || (next as? FiveRegisterInstruction)?.registerC != register) return@forEach
            if (at + 1 in method.jumpTargets()) refuse("something in $adapter->${method.name} jumps past its read of ${field.name}")
            reads += MutualSuggestionsSite(adapter, method.name, method.parameterTypes.map(CharSequence::toString), at, register)
        }
    }
    return reads.singleOrNull() ?: refuse("expected one read of the suggested accounts in $adapter, found ${reads.size}")
}

/** [findMutualSuggestions], or null with a warning, so the patch's other parts still go in. */
internal fun BytecodePatchContext.findMutualSuggestionsOrWarn(): MutualSuggestionsSite? = try {
    findMutualSuggestions()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding Suggested for you under the mutual followers list.")
    null
}

/**
 * Hands the suggested accounts the adapter just read to [MUTUAL_SUGGESTIONS] before its `isEmpty()`
 * and keeps the extension's answer in the same register. An empty list draws no section. The
 * adapter's own field keeps every account, so nothing else Instagram does with them changes.
 */
internal fun BytecodePatchContext.hideMutualSuggestions(site: MutualSuggestionsSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        site.read + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $MUTUAL_SUGGESTIONS
            move-result-object v${site.register}
        """,
    )
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
