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
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.util.BitSet

private const val PATCH = "Hide Notes on profile pictures"
internal const val PROFILE_NOTES = "$EXTENSION_PACKAGE/profile/ProfileNotes;"
internal const val NOTES_OFF = "$PROFILE_NOTES->consumptionDisabled(I)Z"
internal const val PROMPT_OFF = "$PROFILE_NOTES->creationDisabled(I)Z"

/**
 * The `is_consumption_disabled` parameter of Instagram's `ig4a_profile_unship_direct_notes` config,
 * its own switch for taking Notes off profiles. On 450 it's read twice: by the profile screen,
 * which skips fetching the profile's Note when it's set, and by the builder of the profile header,
 * which drops the Note it was about to draw. Both answer a yes the same way, so a yes from the
 * extension leaves Instagram doing what it does for an account the server turned Notes off for.
 * Updates renumber the config, so this was 0x8115b700017011 on 451, 0x8115a300016fe2 on 452 and
 * 0x811581000170d9 on 453.
 */
internal const val CONSUMPTION_DISABLED_FLAG = 0x811534000171f9L

/**
 * The config's other parameter, `is_creation_disabled`. The header builder reads it once, for your
 * own profile only, to decide whether the bubble on your picture offers a new Note ("Start your
 * first note...") when there's no Note to draw. Nothing else holds that prompt back: a yes on
 * [CONSUMPTION_DISABLED_FLAG] drops a Note of your own and leaves the prompt in its place. With
 * both a yes, the builder has nothing for the bubble and leaves it out. 0x8115b700007010 on 451,
 * 0x8115a300006fe1 on 452, 0x811581000070d8 on 453.
 */
internal const val CREATION_DISABLED_FLAG = 0x811534000071f8L

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
 * profile never fetches the Note and the header never draws it, and the header's switch for the
 * prompt to post one, so your own picture doesn't offer one either. The inbox's Notes tray and the
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

/**
 * Finds the Notes reads with [findProfileNotes] and the prompt's with [findNotesPrompt], and has the
 * extension answer the Notes reads through [NOTES_OFF] and the prompt's through [PROMPT_OFF]. All of
 * them go in one pass, since two of them are in the same method.
 */
internal fun BytecodePatchContext.hideProfileNotes() {
    val notes = findProfileNotes()
    val prompt = findNotesPrompt(notes)
    answerFlagLoads(notes.map { it to NOTES_OFF } + (prompt to PROMPT_OFF))
}

/**
 * Finds the two reads of [CONSUMPTION_DISABLED_FLAG]. Each has to be the flag's own read, not shared
 * with another flag. One is in the profile screen, and the other in the one method that also reads
 * [CREATION_DISABLED_FLAG] and names both [SELF_PROFILE] and [EXTERNAL_PROFILE], the header
 * builder. Fails before anything changes when any of it isn't so, since that's an update this patch
 * hasn't seen.
 *
 * 454 reads the flag a third time, in a lambda of the header's controller that answers whether the
 * picture's Note bubble shows: on 386014758, LX/0250;->invoke casts its receiver to LX/0GZP, which
 * keeps the header builder LX/0GbX in a field, and answers the flag's no. That read, at most one, is
 * answered the same way and comes last in the list.
 */
internal fun BytecodePatchContext.findProfileNotes(): List<FlagLoad> {
    val flag = CONSUMPTION_DISABLED_FLAG.toString(16)
    val all = findFlagLoads(PATCH, CONSUMPTION_DISABLED_FLAG, "Z")
    val headers = all.filter { it.type != PROFILE_FRAGMENT && readingMethod(it).loads(CREATION_DISABLED_FLAG) }.mapTo(HashSet()) { it.type }
    val controller = all.filter { it.type != PROFILE_FRAGMENT && it.type !in headers && castsToAHolderOf(it, headers) }
    if (controller.size > 1) refuse("expected at most one read of $flag through the profile header's controller, found ${controller.size}")
    val reads = all.filter { read -> controller.none { it === read } }
    if (reads.size != 2) refuse("expected two reads of the Notes consumption flag $flag, found ${reads.size}")
    all.firstOrNull { it.shared }?.let { refuse("the read of $flag in ${it.type}->${it.name} is shared with another flag") }
    val screen = reads.filter { it.type == PROFILE_FRAGMENT }
    if (screen.size != 1) refuse("expected one read of $flag in $PROFILE_FRAGMENT, found ${screen.size}")
    val header = reads.single { it !== screen.single() }
    val method = readingMethod(header)
    val where = "${header.type}->${header.name}"
    if (!method.loads(CREATION_DISABLED_FLAG)) refuse("$where reads $flag but not the creation flag ${CREATION_DISABLED_FLAG.toString(16)}")
    if (SELF_PROFILE !in method.strings() || EXTERNAL_PROFILE !in method.strings()) {
        refuse("$where reads $flag but doesn't name both $SELF_PROFILE and $EXTERNAL_PROFILE")
    }
    return reads + controller
}

/**
 * Finds the header builder's read of [CREATION_DISABLED_FLAG], the one gate on your own profile's
 * prompt to post a Note. The builder is the method of the [notes] read that isn't the profile
 * screen's and also loads that flag, as [findProfileNotes] found it. It has to read the flag once,
 * and the read has to be the flag's own. A read of the flag anywhere else stays as it is. Fails
 * before anything changes when any of it isn't so.
 */
internal fun BytecodePatchContext.findNotesPrompt(notes: List<FlagLoad>): FlagLoad {
    val flag = CREATION_DISABLED_FLAG.toString(16)
    val header = notes.filter { it.type != PROFILE_FRAGMENT }.let { others ->
        others.singleOrNull { readingMethod(it).loads(CREATION_DISABLED_FLAG) } ?: others.first()
    }
    val where = "${header.type}->${header.name}"
    val reads = findFlagLoads(PATCH, CREATION_DISABLED_FLAG, "Z").filter {
        it.type == header.type && it.name == header.name && it.parameters == header.parameters
    }
    if (reads.size != 1) refuse("expected one read of the Notes creation flag $flag in $where, found ${reads.size}")
    if (reads.single().shared) refuse("the read of $flag in $where is shared with another flag")
    return reads.single()
}

private fun BytecodePatchContext.readingMethod(read: FlagLoad): Method = classDefBy(read.type).methods.single {
    it.name == read.name && it.returnType == read.returnType && it.parameterTypes.map(CharSequence::toString) == read.parameters
}

/**
 * Whether some way to [read]'s load passes a check-cast to a class keeping one of [headers] in an
 * instance field, the header's controller. A merged lambda's other cases don't lead to the load.
 */
private fun BytecodePatchContext.castsToAHolderOf(read: FlagLoad, headers: Set<String>): Boolean {
    if (headers.isEmpty()) return false
    val flow = ControlFlow.of(readingMethod(read))
    val code = flow.instructions
    val predecessors = Array(code.size) { mutableListOf<Int>() }
    code.indices.forEach { from -> (flow.normal[from] + flow.exceptional[from]).forEach { to -> predecessors[to] += from } }
    val reached = BitSet()
    val pending = ArrayDeque(listOf(read.loadAt))
    while (pending.isNotEmpty()) {
        predecessors[pending.removeFirst()].forEach { if (!reached[it]) { reached.set(it); pending += it } }
    }
    return code.indices.any { at ->
        reached[at] && code[at].opcode == Opcode.CHECK_CAST &&
            classDefByOrNull(((code[at] as ReferenceInstruction).reference as TypeReference).type)?.fields?.any { field ->
                !AccessFlags.STATIC.isSet(field.accessFlags) && field.type in headers
            } == true
    }
}

private fun Method.loads(literal: Long): Boolean = implementation?.instructions?.any {
    it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == literal
} == true

private fun Method.strings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet() ?: emptySet()
