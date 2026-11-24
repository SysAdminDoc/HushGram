/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.sound

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val SONG_PATCH = "Start feed videos with sound (songs)"
internal const val FEED_START_SONG_WITH_SOUND = "$FEED_SOUND->startSongWithSound(Ljava/lang/Object;)I"

/** A string only the feed's music controller loads, in the reports it files about a song. */
internal const val FEED_MUSIC_STRING = "FeedMusicController"

private const val SONG_MEDIA = "Lcom/instagram/feed/media/Media;"
private const val SONG_SESSION = "Lcom/instagram/common/session/UserSession;"

private fun refuseSong(detail: String): Nothing = throw PatchException("$SONG_PATCH: $detail")

/**
 * Where a photo post's song decides whether it plays (#762): the music controller's static method
 * that runs when such a post comes into view, the index of its `if-eqz` on the account audio state's
 * answer, the register holding that answer, the register holding the controller, the controller's
 * user session field, the audio state's factory and its `(Z)Z` reader, and the one local the hook
 * borrows for the state.
 */
internal class FeedSongSite(
    val visible: Method,
    val index: Int,
    val answer: Int,
    val controller: Int,
    val session: FieldReference,
    val factory: MethodReference,
    val reader: MethodReference,
    val state: Int,
)

/**
 * Instagram 457's feed music controller is a class loading [FEED_MUSIC_STRING] with one static method
 * returning void that takes (Media, x, the controller, float), the post and how much of it shows. That
 * method takes the account audio state through [factory], the one the Reels audio toggle flips, asks it
 * `(Z)Z` with false, which answers the state or false when it was never set, and plays the song only on
 * a true. The call shape is exact: factory, move-result-object, const/4 0, the reader on that state,
 * move-result, if-eqz. The controller keeps its user session in one instance field.
 */
internal fun BytecodePatchContext.findFeedSongSite(factory: MethodReference): FeedSongSite {
    val stateType = factory.returnType
    val found = mutableListOf<Pair<Method, Int>>()
    for (owner in classesLoadingString(FEED_MUSIC_STRING)) {
        for (method in owner.methods) {
            val p = method.parameterTypes.map(Any::toString)
            if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" || method.implementation == null ||
                p.size != 4 || p[0] != SONG_MEDIA || p[2] != owner.type || p[3] != "F"
            ) {
                continue
            }
            val code = method.songCode()
            code.indices.filter { asksTheState(code, it, factory.toString(), stateType) }.forEach { found += method to it }
        }
    }
    val (visible, at) = found.singleOrNull()
        ?: refuseSong("expected one music controller method reading the account audio state $stateType once, found ${found.size}")
    val code = visible.songCode()
    val where = "${visible.definingClass}->${visible.name}"
    val index = at + 5
    val answer = (code[at + 4] as OneRegisterInstruction).registerA
    if (answer > 15) refuseSong("$where keeps the state's answer in v$answer, past what the hook can load a constant into")
    if (index in visible.jumpTargets()) refuseSong("$where jumps onto its test of the state's answer")
    visible.requireParameterIntact(SONG_PATCH, 2, listOf(index))
    val controller = visible.parameterRegisterNumber(2)

    val owner = classDefByOrNull(visible.definingClass) ?: refuseSong("$where isn't in the app")
    val sessions = owner.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == SONG_SESSION }
    val session = sessions.singleOrNull()
        ?: refuseSong("expected ${owner.type} to keep one $SONG_SESSION field, found ${sessions.size}")
    val reader = (code[at + 3] as ReferenceInstruction).reference as MethodReference
    val state = visible.freeLocalsAt(SONG_PATCH, index, 1, except = listOf(answer)).single()
    return FeedSongSite(visible, index, answer, controller, ImmutableFieldReference.of(session), factory, reader, state)
}

/** Whether [code] takes the audio state through [factory] at [at] and asks its `(Z)Z` reader with false, as described above. */
private fun asksTheState(code: List<Instruction>, at: Int, factory: String, stateType: String): Boolean {
    if (at + 5 >= code.size || code[at].opcode != Opcode.INVOKE_STATIC || code[at].songReference()?.toString() != factory) return false
    val made = code[at + 1] as? OneRegisterInstruction ?: return false
    val zero = code[at + 2]
    val ask = code[at + 3] as? FiveRegisterInstruction ?: return false
    val reader = code[at + 3].songReference()
    val answer = code[at + 4] as? OneRegisterInstruction ?: return false
    val test = code[at + 5] as? OneRegisterInstruction ?: return false
    return code[at + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        zero.opcode == Opcode.CONST_4 && (zero as NarrowLiteralInstruction).narrowLiteral == 0 &&
        code[at + 3].opcode == Opcode.INVOKE_VIRTUAL && reader != null && reader.definingClass == stateType &&
        reader.returnType == "Z" && reader.parameterTypes.map(Any::toString) == listOf("Z") &&
        ask.registerCount == 2 && ask.registerC == made.registerA && ask.registerD == (zero as OneRegisterInstruction).registerA &&
        code[at + 4].opcode == Opcode.MOVE_RESULT && code[at + 5].opcode == Opcode.IF_EQZ && test.registerA == answer.registerA
}

/**
 * In front of the music controller's test of the state's answer: when the answer is false, take the
 * account's audio state again from the controller's session and ask it the same reader with true. A
 * true there means nobody set the state, since the false answer was the default; only then ask
 * [FEED_START_SONG_WITH_SOUND] with the controller, and its yes is the answer the test reads, so the
 * song plays and Instagram's own play turns the state on. A state set off, a missing session and a no
 * all leave the answer false, Instagram's own.
 */
internal fun BytecodePatchContext.applyFeedSong(site: FeedSongSite) {
    val method = mutableClassDefBy(site.visible.definingClass).methods.single {
        it.name == site.visible.name && it.parameterTypes.map(Any::toString) == site.visible.parameterTypes.map(Any::toString) &&
            it.returnType == site.visible.returnType
    }
    val a = site.answer
    val s = site.state
    method.addInstructionsWithLabels(
        site.index,
        """
            if-nez v$a, :stock
            move-object/from16 v$s, v${site.controller}
            iget-object v$s, v$s, ${site.session}
            if-eqz v$s, :stock
            invoke-static { v$s }, ${site.factory}
            move-result-object v$s
            const/4 v$a, 0x1
            invoke-virtual { v$s, v$a }, ${site.reader}
            move-result v$a
            if-eqz v$a, :stock
            invoke-static/range { v${site.controller} .. v${site.controller} }, $FEED_START_SONG_WITH_SOUND
            move-result v$a
        """,
        ExternalLabel("stock", method.getInstruction(site.index)),
    )
}

private fun Method.songCode(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.songReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
