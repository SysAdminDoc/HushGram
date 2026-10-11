/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.notes

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Hide Notes on profile pictures"
internal const val PROFILE_NOTES = "$EXTENSION_PACKAGE/profile/ProfileNotes;"
internal const val NOTES_OFF = "$PROFILE_NOTES->consumptionDisabled(I)Z"

/**
 * The `is_consumption_disabled` parameter of Instagram's `ig4a_profile_unship_direct_notes` config,
 * its own switch for taking Notes off profiles. On 450 it's read twice: by the profile screen,
 * which skips fetching the profile's Note when it's set, and by the builder of the profile header,
 * which drops the Note it was about to draw. Both answer a yes the same way, so a yes from the
 * extension leaves Instagram doing what it does for an account the server turned Notes off for.
 */
internal const val CONSUMPTION_DISABLED_FLAG = 0x8115c800017021L

/** The config's other parameter, `is_creation_disabled`, which the header builder reads too. */
internal const val CREATION_DISABLED_FLAG = 0x8115c800007020L

/** The profile screen, which keeps its name in Instagram's builds. */
internal const val PROFILE_FRAGMENT = "Lcom/instagram/profile/fragment/UserDetailFragment;"

/** The two profile kinds only the header builder names. */
internal const val SELF_PROFILE = "self_profile"
internal const val EXTERNAL_PROFILE = "external_profile"

/**
 * Takes Notes off profile pictures. Included in the default selection with its switch initially
 * off, so leaving Notes where they are remains the user's pick.
 *
 * It answers Instagram's own kill switch for Notes on profiles rather than hiding a view, so the
 * profile never fetches the Note and the header never draws it. The inbox's Notes tray and the
 * Notes composer read other flags and stay as they are.
 */
@Suppress("unused")
val hideProfileNotesPatch = bytecodePatch(
    name = "Hide Notes on profile pictures",
    description = "Takes the Notes bubble off profile pictures, yours included. Notes in Messages stay. Starts off. " +
        "Turn it on in HushGram settings > Profiles.",
    default = true,
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("profileNotes")
        hideProfileNotes()
        enableStatus("profileNotes")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Finds the two reads with [findProfileNotes] and has the extension answer both through [NOTES_OFF]. */
internal fun BytecodePatchContext.hideProfileNotes() {
    answerFlagLoads(findProfileNotes().map { it to NOTES_OFF })
}

/**
 * Finds the two reads of [CONSUMPTION_DISABLED_FLAG]. Each has to be the flag's own read, not shared
 * with another flag. One is in the profile screen, and the other in the one method that also reads
 * [CREATION_DISABLED_FLAG] and names both [SELF_PROFILE] and [EXTERNAL_PROFILE], the header
 * builder. Fails before anything changes when any of it isn't so, since that's an update this patch
 * hasn't seen.
 */
internal fun BytecodePatchContext.findProfileNotes(): List<FlagLoad> {
    val flag = CONSUMPTION_DISABLED_FLAG.toString(16)
    val reads = findFlagLoads(PATCH, CONSUMPTION_DISABLED_FLAG, "Z")
    if (reads.size != 2) refuse("expected two reads of the Notes consumption flag $flag, found ${reads.size}")
    reads.firstOrNull { it.shared }?.let { refuse("the read of $flag in ${it.type}->${it.name} is shared with another flag") }
    val screen = reads.filter { it.type == PROFILE_FRAGMENT }
    if (screen.size != 1) refuse("expected one read of $flag in $PROFILE_FRAGMENT, found ${screen.size}")
    val header = reads.single { it !== screen.single() }
    val method = classDefBy(header.type).methods.single {
        it.name == header.name && it.returnType == header.returnType && it.parameterTypes.map(CharSequence::toString) == header.parameters
    }
    val where = "${header.type}->${header.name}"
    if (!method.loads(CREATION_DISABLED_FLAG)) refuse("$where reads $flag but not the creation flag ${CREATION_DISABLED_FLAG.toString(16)}")
    if (SELF_PROFILE !in method.strings() || EXTERNAL_PROFILE !in method.strings()) {
        refuse("$where reads $flag but doesn't name both $SELF_PROFILE and $EXTERNAL_PROFILE")
    }
    return reads
}

private fun Method.loads(literal: Long): Boolean = implementation?.instructions?.any {
    it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == literal
} == true

private fun Method.strings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet() ?: emptySet()
