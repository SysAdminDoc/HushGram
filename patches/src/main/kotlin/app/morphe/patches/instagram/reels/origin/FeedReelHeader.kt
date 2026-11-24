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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val FEED_OPENING = "$REEL_ORIGIN->openingFromFeed(Ljava/lang/Object;)V"

/** What a feed post's header tap names the profile it opens, the entry point Instagram logs. */
internal const val FEED_HEADER = "media_username"
internal const val POST_AUTHOR = "Lcom/instagram/user/model/User;"
internal const val AUTHOR_LOOKUPS = "Lcom/instagram/feed/media/MediaExtKt;"

/** A header tap's read of its post's media: the method, the read's index, and the register it reads into. */
internal class FeedHeaderTap(val type: String, val name: String, val parameters: List<String>, val read: Int, val register: Int)

private fun refuseTap(why: String): Nothing = throw PatchException("$REEL_ORIGIN_PATCH: $why")

/**
 * Every feed post's header tap (#795): the methods loading [FEED_HEADER] that read a post's Media out
 * of the tap's event and hand it to MediaExtKt's look-up of the post's author ([headerReads]). 456.1
 * has two (0BSh, 0H6x) and 457 has two (05YX, 09Jh), whose event no longer carries the post's User.
 * A merged tap building its media from a tree instead (456.1's 0BRD.A08, 457's 08Zy.A0O) isn't one.
 * Each reads it once, and nothing jumps to just past the read, where the hook goes. The extension
 * has openingFromFeed.
 */
internal fun BytecodePatchContext.findFeedHeaderTaps(): List<FeedHeaderTap> {
    val methods = classesLoadingString(FEED_HEADER).flatMap { it.methods }.filter { loadsString(it, FEED_HEADER) }
    val taps = methods.mapNotNull { method ->
        val code = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val reads = headerReads(code)
        if (reads.isEmpty()) return@mapNotNull null
        val where = "${method.definingClass}->${method.name}"
        val (read, register) = reads.singleOrNull() ?: refuseTap("$where reads a post's media for its header ${reads.size} times, not once")
        if (read + 1 !in code.indices) refuseTap("$where ends on its read of the post's media")
        if (read + 1 in method.jumpTargets()) refuseTap("something in $where jumps to just past its read of the post's media")
        FeedHeaderTap(method.definingClass, method.name, method.parameterTypes.map(Any::toString), read, register)
    }
    if (taps.isEmpty()) refuseTap("no method loading $FEED_HEADER reads a post's $MEDIA it looks its $POST_AUTHOR up by")
    requireFeedOpening()
    return taps
}

/** The extension's openingFromFeed, which every feed hook calls. */
private fun BytecodePatchContext.requireFeedOpening() {
    classDefByOrNull(REEL_ORIGIN)?.methods?.singleOrNull {
        it.name == "openingFromFeed" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuseTap("extension has no public static openingFromFeed(Ljava/lang/Object;)V")
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
    val (read, register) = headerReads(code).singleOrNull()
        ?: refuseTap("${tap.type}->${tap.name} no longer reads the post's media once")
    if (read + 1 in method.jumpTargets()) refuseTap("something in ${tap.type}->${tap.name} jumps to just past its read of the post's media")
    method.addInstructions(read + 1, "invoke-static/range { v$register .. v$register }, $FEED_OPENING")
}

/**
 * The label Instagram's systrace gives the feed header's profile open. A tap on a post's name or
 * picture that opens the author's profile goes through the methods carrying it (00QE.A06 and its
 * static twin 00QE.A04 on 456), each taking the post's Media.
 */
internal const val FEED_PROFILE_OPEN = "DefaultMediaHeaderViewBinderDelegateImpl.gotoUserProfile"

/** A feed header's profile open: the method, and the parameter register holding the post's media. */
internal class FeedProfileOpen(val type: String, val name: String, val parameters: List<String>, val media: Int)

/**
 * Every method labelled [FEED_PROFILE_OPEN], each with the one Media parameter it hands on (#795).
 * The name tap on a feed post reaches them, not the header events [findFeedHeaderTaps] reads, on
 * the main feed of 456 at least. The extension has openingFromFeed.
 */
internal fun BytecodePatchContext.findFeedProfileOpens(): List<FeedProfileOpen> {
    val methods = classesLoadingString(FEED_PROFILE_OPEN).flatMap { it.methods }.filter { loadsString(it, FEED_PROFILE_OPEN) }
    if (methods.isEmpty()) refuseTap("no method loads $FEED_PROFILE_OPEN")
    val opens = methods.map { method ->
        val where = "${method.definingClass}->${method.name}"
        val types = method.parameterTypes.map(Any::toString)
        val media = types.withIndex().filter { it.value == MEDIA }
        val at = media.singleOrNull()?.index ?: refuseTap("$where takes ${media.size} of $MEDIA, not one")
        val first = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        val register = first + types.take(at).sumOf { if (it == "J" || it == "D") 2 else 1 }
        FeedProfileOpen(method.definingClass, method.name, types, register)
    }
    requireFeedOpening()
    return opens
}

/** [findFeedProfileOpens], or none after the patch log says why: the header taps and Reels still get theirs. */
internal fun BytecodePatchContext.findFeedProfileOpensOrWarn(): List<FeedProfileOpen> = try {
    findFeedProfileOpens()
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. $REEL_ORIGIN_PATCH goes in without the feed's name taps.")
    emptyList()
}

/**
 * Hands the post's media to the extension as the profile open starts. The call reads only the
 * parameter, which nothing has written yet, and returns nothing, so nothing of Instagram's changes.
 */
internal fun BytecodePatchContext.hookFeedProfileOpen(open: FeedProfileOpen) {
    val method = mutableClassDefBy(open.type).methods.single {
        it.name == open.name && it.parameterTypes.map(Any::toString) == open.parameters
    }
    method.addInstructions(0, "invoke-static/range { p${open.media} .. p${open.media} }, $FEED_OPENING")
}

/**
 * Where a header tap reads the post's Media it looks the author up by, as the read's index and its
 * register: each MediaExtKt look-up answering a [POST_AUTHOR] from a [MEDIA] hands on a Media
 * register, which is traced back through MediaExtKt's own Media to Media steps to an iget-object of a
 * Media field. A Media some other call answers isn't a header read. The code is read in order, which
 * these straight runs of Instagram's are.
 */
private fun headerReads(code: List<Instruction>): List<Pair<Int, Int>> = code.indices.mapNotNull { at ->
    val call = code[at].lookup()?.takeIf { it.returnType == POST_AUTHOR } ?: return@mapNotNull null
    mediaArgument(code[at], call)?.let { mediaSource(code, at, it) }
}.distinct()

/** Where the Media in [register], handed on at [at], comes from, as [headerReads] traces it, or null. */
private tailrec fun mediaSource(code: List<Instruction>, at: Int, register: Int): Pair<Int, Int>? {
    val writer = (at - 1 downTo 0).firstOrNull { code[it].opcode.setsRegister() && code[it].namedRegisters().firstOrNull() == register }
        ?: return null
    val written = code[writer]
    if (written.opcode == Opcode.IGET_OBJECT) {
        return if (((written as ReferenceInstruction).reference as FieldReference).type == MEDIA) writer to register else null
    }
    if (written.opcode != Opcode.MOVE_RESULT_OBJECT || writer == 0) return null
    val step = code[writer - 1].lookup()?.takeIf { it.returnType == MEDIA } ?: return null
    val handed = mediaArgument(code[writer - 1], step) ?: return null
    return mediaSource(code, writer - 1, handed)
}

/** A static call to one of MediaExtKt's look-ups, or null. */
private fun Instruction.lookup(): MethodReference? {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return null
    return ((this as ReferenceInstruction).reference as? MethodReference)?.takeIf { it.definingClass == AUTHOR_LOOKUPS }
}

/** The register [call] hands its one [MEDIA] in, or null when it takes none or several. */
private fun mediaArgument(instruction: Instruction, call: MethodReference): Int? {
    val types = call.parameterTypes.map(Any::toString)
    val at = types.indexOf(MEDIA).takeIf { it >= 0 && types.lastIndexOf(MEDIA) == it } ?: return null
    return instruction.namedRegisters().getOrNull(types.take(at).sumOf { if (it == "J" || it == "D") 2 else 1 })
}
