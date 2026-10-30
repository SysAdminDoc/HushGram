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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested people on profiles"

internal const val FOLLOW_LIST = "$PROFILE_SUGGESTIONS->followList(I)I"
internal const val SUGGESTED_TAB = "$PROFILE_SUGGESTIONS->suggestedTab(I)I"

/**
 * The argument the followers and following list reads to know whether to fetch the accounts it
 * suggests under the list. Instagram keeps the key: it's the name of the fragment's argument.
 */
internal const val FETCH_SUGGESTED = "FollowListFragment.ShouldFetchSuggestedUsers"

/**
 * The argument the pager over someone else's follow lists (mutual, followers, following) reads to
 * know whether to add its Suggested tab (#302). Instagram keeps this key too.
 */
internal const val HAS_SIMILAR_ACCOUNTS = "UnifiedFollowFragment.HAS_SIMILAR_ACCOUNTS"

/** The reads of a boolean argument the follow lists make, either spelling of it, with a default or without. */
private val GET_BOOLEAN = setOf(
    "Landroid/os/BaseBundle;->getBoolean(Ljava/lang/String;Z)Z",
    "Landroid/os/Bundle;->getBoolean(Ljava/lang/String;Z)Z",
    "Landroid/os/BaseBundle;->getBoolean(Ljava/lang/String;)Z",
    "Landroid/os/Bundle;->getBoolean(Ljava/lang/String;)Z",
)

/** A follow list's read of a boolean argument: its method, and the move-result holding the answer. */
internal class FollowListSite(val type: String, val name: String, val parameters: List<String>, val result: Int, val register: Int)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the one place that reads [FETCH_SUGGESTED] out of a fragment's arguments: the key loaded
 * (or asked of Redex's string pool, which differs by build), the next call a `getBoolean` handed
 * that key, and its move-result right after. Putting the key into arguments is a `putBoolean`, so
 * the places opening the list don't count. Fails before anything changes when there isn't exactly
 * one such read, or something jumps past its answer, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findFollowListSuggestions(): FollowListSite = findArgumentRead(FETCH_SUGGESTED)

/**
 * Finds the pager's one read of [HAS_SIMILAR_ACCOUNTS] the way [findFollowListSuggestions] finds
 * its key. The places opening the pager put the key or ask whether it's there, and neither counts.
 */
internal fun BytecodePatchContext.findSuggestedTab(): FollowListSite = findArgumentRead(HAS_SIMILAR_ACCOUNTS)

/** [findSuggestedTab], or null with a warning, so the patch's other parts still go in. */
internal fun BytecodePatchContext.findSuggestedTabOrWarn(): FollowListSite? = try {
    findSuggestedTab()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding the Suggested tab on other people's follow lists.")
    null
}

private fun BytecodePatchContext.findArgumentRead(argument: String): FollowListSite {
    val reads = mutableListOf<FollowListSite>()
    classesLoadingString(argument).forEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            code.indices.filter { stringLoadedAt(code, it) == argument }.forEach { at ->
                val key = keyRegister(code, at) ?: return@forEach
                val call = (at + 1 until code.size).firstOrNull { code[it].methodReference() != null } ?: return@forEach
                if (code[call].methodReference().toString() !in GET_BOOLEAN) return@forEach
                if ((code[call] as? FiveRegisterInstruction)?.registerD != key) return@forEach
                val result = code.getOrNull(call + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) refuse("${classDef.type}->${method.name} reads $argument into nothing")
                if (call + 2 in method.jumpTargets()) refuse("something in ${classDef.type}->${method.name} jumps past its read of $argument")
                reads += FollowListSite(
                    classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), call + 1,
                    (result as OneRegisterInstruction).registerA,
                )
            }
        }
    }
    return reads.singleOrNull() ?: refuse("expected one read of the follow list's $argument, found ${reads.size}")
}

/**
 * Hands the follow list's answer to [FOLLOW_LIST] right after its move-result and puts the
 * extension's answer back in the same register. A 0 means the list never asks for suggestions, so
 * no Suggested for you section is fetched or drawn under it.
 */
internal fun BytecodePatchContext.hideFollowListSuggestions(site: FollowListSite) = answer(site, FOLLOW_LIST)

/**
 * Hands the pager's answer to [SUGGESTED_TAB] the same way. A 0 means the pager never adds the
 * Suggested tab, and a list opened on that tab starts on its first tab, as Instagram does when the
 * tab it was asked for isn't there.
 */
internal fun BytecodePatchContext.hideSuggestedTab(site: FollowListSite) = answer(site, SUGGESTED_TAB)

private fun BytecodePatchContext.answer(site: FollowListSite, hook: String) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method.addInstructions(
        site.result + 1,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $hook
            move-result v${site.register}
        """,
    )
}

/** The register holding the key loaded at [at]: a const-string's, or the move-result after a pool's answer. */
private fun keyRegister(code: List<Instruction>, at: Int): Int? = when (code[at].opcode) {
    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> (code[at] as OneRegisterInstruction).registerA
    else -> code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT }?.let { (it as OneRegisterInstruction).registerA }
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
