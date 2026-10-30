/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore.recent

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Show all recent searches"
internal const val SHOWN_HOOK = "$RECENT_SEARCHES->shown(I)I"

/** The analytics name of the Recent group on the search screen, and the row that opens the rest. */
internal const val NULL_STATE_RECENT = "null_state_recent"
internal const val SHOW_MORE = "show_more_recents"

private const val MATH_MIN = "Ljava/lang/Math;->min(II)I"

/**
 * Lists every recent search under the search bar, not just the first few, behind a switch under
 * Explore that starts off.
 *
 * Instagram keeps the whole recent list on the phone and trims it to a small count (three, or eight
 * with one of its flags) in the method that builds the empty search screen, with a Show more row
 * when something was cut. The patch hands that count to the extension right after Instagram works it
 * out, and the extension answers with no limit while the switch is on. The list isn't fetched in
 * pages, so nothing is faked. See all and the x on each row are Instagram's and stay as they are.
 */
@Suppress("unused")
val showAllRecentSearchesPatch = bytecodePatch(
    name = "Show all recent searches",
    description = "Lists every recent search under the search bar instead of the first few. See all and the x on " +
        "each row work as before. Starts off. Turn it on in HushGram settings > Explore.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("allRecentSearches")
        showAllRecents(findRecentLimits())
        enableStatus("allRecentSearches")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Where Instagram's count of recent searches to draw lands: the method, and the instruction after its move-result. */
internal class RecentLimitSite(val type: String, val name: String, val parameters: List<String>, val after: Int, val register: Int)

/**
 * Finds each method building the empty search screen's Recent group: those loading both
 * [NULL_STATE_RECENT] and [SHOW_MORE]. In each, the count is the last call to [MATH_MIN] before the
 * group is named, and the hook goes right after its move-result. Instagram has an older and a newer
 * builder in the same app, so one or two are expected. Fails before anything changes when there are
 * none, when one has no such call, or when something jumps to where the hook would go (a jump keeps
 * its target on the instruction it was written for, so the hook could be skipped).
 */
internal fun BytecodePatchContext.findRecentLimits(): List<RecentLimitSite> {
    val holders = classesLoadingString(SHOW_MORE).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, SHOW_MORE) && loadsString(it, NULL_STATE_RECENT) }
    }
    if (holders.isEmpty() || holders.size > 2) refuse("expected one or two builders of the search screen's Recent group, found ${holders.size}")
    return holders.map { method ->
        val where = "${method.definingClass}->${method.name}"
        val code = method.implementation?.instructions?.toList().orEmpty()
        val named = code.indexOfFirst { (it as? ReferenceInstruction)?.reference.let { ref -> ref is StringReference && ref.string == NULL_STATE_RECENT } }
        if (named < 0) refuse("$where doesn't name $NULL_STATE_RECENT")
        val min = code.indices.lastOrNull { i ->
            i < named && code[i].opcode == Opcode.INVOKE_STATIC &&
                ((code[i] as ReferenceInstruction).reference as? MethodReference)?.toString() == MATH_MIN
        } ?: refuse("$where has no $MATH_MIN before $NULL_STATE_RECENT")
        val result = code.getOrNull(min + 1)
        if (result == null || result.opcode != Opcode.MOVE_RESULT) refuse("$where doesn't take the answer of $MATH_MIN")
        val after = min + 2
        if (after in method.jumpTargets()) refuse("$where has a jump to just after its count of recent searches")
        RecentLimitSite(
            method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString),
            after, (result as OneRegisterInstruction).registerA,
        )
    }
}

/** Passes each count through [SHOWN_HOOK], which hands back the same count or no limit. */
internal fun BytecodePatchContext.showAllRecents(sites: List<RecentLimitSite>) {
    for (site in sites) {
        val method = mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }
        val v = site.register
        method.addInstructions(
            site.after,
            """
                invoke-static/range { v$v .. v$v }, $SHOWN_HOOK
                move-result v$v
            """,
        )
    }
}
