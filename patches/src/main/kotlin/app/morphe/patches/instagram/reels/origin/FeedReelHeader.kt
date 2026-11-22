/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.origin

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val FEED_OPENING = "$REEL_ORIGIN->openingFromFeed(Ljava/lang/Object;)V"

/** What a feed post's header tap names the profile it opens, the entry point Instagram logs. */
internal const val FEED_HEADER = "media_username"
internal const val POST_AUTHOR = "Lcom/instagram/user/model/User;"
internal const val AUTHOR_LOOKUPS = "Lcom/instagram/feed/media/MediaExtKt;"

/** How many instructions past its read the post's media may reach Instagram's look-up of its author. */
internal const val AUTHOR_LOOKUP_REACH = 8

/** A header tap's read of its post's media: the method, the read's index, and the register it reads into. */
internal class FeedHeaderTap(val type: String, val name: String, val parameters: List<String>, val read: Int, val register: Int)

private fun refuseTap(why: String): Nothing = throw PatchException("$REEL_ORIGIN_PATCH: $why")

/**
 * Every feed post's header tap (#795): the methods loading [FEED_HEADER] that read a post's Media out
 * of the tap's event, whose class also carries the post's User, the method reading that too, and
 * hand the Media to MediaExtKt's look-up of the post's author a few instructions on. 456 has two, the
 * main feed's (07p6) and the other feeds' (08NA). Each reads it once, and nothing jumps to just past
 * the read, where the hook goes. The extension has openingFromFeed.
 */
internal fun BytecodePatchContext.findFeedHeaderTaps(): List<FeedHeaderTap> {
    val methods = classesLoadingString(FEED_HEADER).flatMap { it.methods }.filter { loadsString(it, FEED_HEADER) }
    val taps = methods.mapNotNull { method ->
        val code = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val reads = code.indices.filter { at -> isHeaderRead(code, at) }
        if (reads.isEmpty()) return@mapNotNull null
        val where = "${method.definingClass}->${method.name}"
        val read = reads.singleOrNull() ?: refuseTap("$where reads a post's media for its header ${reads.size} times, not once")
        if (read + 1 !in code.indices) refuseTap("$where ends on its read of the post's media")
        if (read + 1 in method.jumpTargets()) refuseTap("something in $where jumps to just past its read of the post's media")
        FeedHeaderTap(
            method.definingClass, method.name, method.parameterTypes.map(Any::toString), read,
            (code[read] as TwoRegisterInstruction).registerA,
        )
    }
    if (taps.isEmpty()) refuseTap("no method loading $FEED_HEADER reads a post's $MEDIA beside its $POST_AUTHOR")
    classDefByOrNull(REEL_ORIGIN)?.methods?.singleOrNull {
        it.name == "openingFromFeed" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuseTap("extension has no public static openingFromFeed(Ljava/lang/Object;)V")
    return taps
}

/** [findFeedHeaderTaps], or none after the patch log says why: the patch still goes in for reels opened from Reels. */
internal fun BytecodePatchContext.findFeedHeaderTapsOrWarn(): List<FeedHeaderTap> = try {
    findFeedHeaderTaps()
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. $REEL_ORIGIN_PATCH goes in for reels opened from Reels alone.")
    emptyList()
}

/**
 * Hands the post's media to the extension right after the tap reads it. The call reads only the
 * register the read just wrote and returns nothing, so nothing of Instagram's changes. The read is
 * found again on the method being written, where another patch may have put code in front of it.
 */
internal fun BytecodePatchContext.hookFeedHeaderTap(tap: FeedHeaderTap) {
    val method = mutableClassDefBy(tap.type).methods.single {
        it.name == tap.name && it.parameterTypes.map(Any::toString) == tap.parameters
    }
    val code = method.implementation?.instructions?.toList() ?: refuseTap("${tap.type}->${tap.name} has no body")
    val read = code.indices.singleOrNull { at -> isHeaderRead(code, at) }
        ?: refuseTap("${tap.type}->${tap.name} no longer reads the post's media once")
    if (read + 1 in method.jumpTargets()) refuseTap("something in ${tap.type}->${tap.name} jumps to just past its read of the post's media")
    val register = (code[read] as TwoRegisterInstruction).registerA
    method.addInstructions(read + 1, "invoke-static/range { v$register .. v$register }, $FEED_OPENING")
}

/**
 * Whether [code] at [at] reads a post's Media out of a header tap's event: an iget-object of a Media
 * field whose class has a User field the method reads too, with the Media handed to MediaExtKt's
 * look-up of a User within [AUTHOR_LOOKUP_REACH] instructions.
 */
private fun isHeaderRead(code: List<Instruction>, at: Int): Boolean {
    val read = code[at]
    if (read.opcode != Opcode.IGET_OBJECT) return false
    val field = (read as ReferenceInstruction).reference as FieldReference
    if (field.type != MEDIA) return false
    val register = (read as TwoRegisterInstruction).registerA
    val readsUser = code.any { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as FieldReference).let { it.definingClass == field.definingClass && it.type == POST_AUTHOR }
    }
    if (!readsUser) return false
    return (at + 1..minOf(at + AUTHOR_LOOKUP_REACH, code.lastIndex)).any { next ->
        val call = (code[next] as? ReferenceInstruction)?.reference as? MethodReference
        (code[next].opcode == Opcode.INVOKE_STATIC || code[next].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call != null && call.definingClass == AUTHOR_LOOKUPS && call.returnType == POST_AUTHOR && register in code[next].namedRegisters()
    }
}
