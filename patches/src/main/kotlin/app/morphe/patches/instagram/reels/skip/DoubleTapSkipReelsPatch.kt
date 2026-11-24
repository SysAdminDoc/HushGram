/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.skip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Double tap to skip on Reels"
internal const val REEL_SKIP = "$EXTENSION_PACKAGE/reels/ReelSkip;"
internal const val SKIP = "$REEL_SKIP->skip(I)Z"
internal const val SECONDS = "$REEL_SKIP->seconds(F)F"
internal const val SECONDS_DOUBLE = "$REEL_SKIP->secondsDouble(D)D"
internal const val EDGE = "$REEL_SKIP->edge(F)F"
internal const val EDGE_DOUBLE = "$REEL_SKIP->edgeDouble(D)D"

/**
 * Whether a reel gets double tap to skip, read by DoubleTapToSkipUseCase_getUiState for each reel
 * (08LX.A0R on 456's 386214574; 455 had 0x81123c00016519, 454 0x811254000164c9). Off on the accounts that don't have it,
 * which is most of them.
 */
// 457 moved it from 456's 0x81121700016542.
internal const val SKIP_FLAG = 0x8111f1000164e2L

/**
 * How far a double tap skips, in seconds: read where the gesture navigator works out a double tap
 * (ClipsGestureNavigator_onDoubleTapToSkip) and again by the use case. 455 had 0x84123c00020472, and
 * the edge below 0x84123c00000471. 454 had 0x84125400020474 and 0x84125400000473.
 */
// 457 moved it from 456's 0x8412170002047c.
internal const val SECONDS_FLAG = 0x8411f10002046fL

/** How wide the strip along each edge is, as a share of the reel's width. Read by the navigator alone. */
// 457 moved it from 456's 0x8412170000047b.
internal const val EDGE_FLAG = 0x8411f10000046eL

/** What a fraction is read as: a float on some 454 builds, a double on others. */
private val FRACTION = setOf("F", "D")

/** Told the way each double tap skip went, so the arrows can show it (#691). */
internal const val SKIPPED = "$REEL_SKIP->skipped(Ljava/lang/Object;)V"

/**
 * Told whether a reel is longer than a skip. Instagram likes a reel that isn't, wherever it's double
 * tapped, so with the switch on a double tap meant to skip a short reel liked it instead.
 */
internal const val LONGER = "$REEL_SKIP->longer(I)I"

/** The names the way a skip went keeps through Redex, which the extension tells the sides by. */
internal val SKIP_WAYS = listOf("FORWARD", "BACKWARD")

/**
 * The gesture navigator's double tap case, the one method reading [EDGE_FLAG] (0JfN.invoke on 456's
 * 386214574, 0IwA.invoke on 386214562; 455 also traced ClipsGestureNavigator_doubleTapToSkipDirection
 * there, which 456 dropped), found by [type], [name] and its [parameters], and the two fields, one
 * per way, it reads after the seek ([fields], as references).
 */
internal class SkipTurns(val type: String, val name: String, val parameters: List<String>, val returnType: String, val fields: Set<String>)

/**
 * Turns on Instagram's own double tap to skip on Reels, which it keeps behind a server flag, and
 * answers its two numbers: 10 seconds a skip, and strips a third of the reel wide unless
 * Instagram's own leave a middle for the like. Read on each reel and each double tap, so a change
 * shows on the next reel. Instagram shows nothing when it skips, so the way each skip went also goes
 * to the extension, which flashes the feed's arrows on that side (#691). Instagram likes a reel no
 * longer than the skip on any double tap, so the extension answers that check too, and a double tap
 * on a strip skips a short reel to its start or end. In the default selection with its switch off.
 */
@Suppress("unused")
val doubleTapSkipReelsPatch = bytecodePatch(
    name = "Double tap to skip on Reels",
    description = "A double tap on the left side of a reel goes back 10 seconds and on the right side goes ahead " +
        "10 seconds, with Instagram's own skip. A double tap in the middle still likes it. Starts off. " +
        "Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelDoubleTapSkip")
        val turns = findSkipTurns()
        skipReels()
        showSkipTurns(turns)
        skipShortReels(turns)
        enableStatus("reelDoubleTapSkip")
    }
}

/** Finds every read with [findReelSkip] and has the extension answer each. */
internal fun BytecodePatchContext.skipReels() {
    answerFlagLoads(findReelSkip())
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the gesture navigator says which way a double tap skipped: the one method reading
 * [EDGE_FLAG], which only the navigator's double tap case reads, and in it, on the way on from that
 * read, one read each of two constants of an enum naming [SKIP_WAYS]. Each read is the navigator's
 * answer, read right after it seeks, and nothing may jump onto it. The navigator is a merged lambda
 * whose other cases the flag's read never reaches. Its check of a reel's length, [lengthCheck], has
 * to be there too. Fails before anything changes when any of it isn't so.
 */
internal fun BytecodePatchContext.findSkipTurns(): SkipTurns {
    val holders = classesLoading(EDGE_FLAG).flatMap { it.methods }.filter { it.edgeLoads().isNotEmpty() }
    val method = holders.singleOrNull() ?: refuse(
        "expected one method reading ${EDGE_FLAG.toString(16)}, the strip a double tap skips by, found " +
            if (holders.isEmpty()) "none" else holders.joinToString { "${it.definingClass}->${it.name}" },
    )
    val reads = turnReads(method)
    val fields = reads.map { it.second }
    if (reads.size != 2 || fields.toSet().size != 2) {
        refuse("expected ${method.definingClass}->${method.name} to read the two ways a skip goes once each, found $fields")
    }
    lengthCheck(method)
    return SkipTurns(method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType, fields.toSet())
}

/** Every instruction in [method] on the way on from its read of [EDGE_FLAG]. */
private fun reachedFromEdge(method: Method): Set<Int> {
    val edge = method.edgeLoads()
    if (edge.isEmpty()) refuse("${method.definingClass}->${method.name} doesn't read ${EDGE_FLAG.toString(16)} itself")
    val flow = ControlFlow.of(method)
    val reached = mutableSetOf<Int>()
    val pending = ArrayDeque(edge)
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (reached.add(at)) pending += flow.normal[at]
    }
    return reached
}

/**
 * Where the navigator, on the way on from its read of [EDGE_FLAG], compares the reel's length with
 * the skip (a cmp-long) and gives the double tap to the like when the reel isn't longer (an if-lez
 * on the compare's answer, right after it): the if-lez's index. There has to be one, and nothing may
 * jump onto the if-lez, past the hook.
 */
private fun lengthCheck(method: Method): Int {
    val code = method.implementation?.instructions?.toList() ?: refuse("${method.definingClass}->${method.name} has no body")
    val checks = reachedFromEdge(method).sorted().filter { at ->
        code[at].opcode == Opcode.CMP_LONG && at + 1 < code.size && code[at + 1].opcode == Opcode.IF_LEZ &&
            (code[at + 1] as OneRegisterInstruction).registerA == (code[at] as OneRegisterInstruction).registerA
    }
    val at = checks.singleOrNull()
        ?: refuse("expected ${method.definingClass}->${method.name} to compare a reel's length with the skip once, found ${checks.size}")
    if (at + 1 in method.jumpTargets()) {
        refuse("${method.definingClass}->${method.name} jumps onto its check of a reel's length, so a short reel would still be liked")
    }
    return at + 1
}

/** The navigator method [turns] names, as the patch writes it. */
private fun BytecodePatchContext.turnMethod(turns: SkipTurns) = mutableClassDefBy(turns.type).methods.single {
    it.name == turns.name && it.returnType == turns.returnType && it.parameterTypes.map(CharSequence::toString) == turns.parameters
}

/** Where this method loads [EDGE_FLAG], which answering the flag leaves in place. */
private fun Method.edgeLoads(): List<Int> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter {
        code[it].opcode == Opcode.CONST_WIDE && (code[it] as WideLiteralInstruction).wideLiteral == EDGE_FLAG
    }
}

/**
 * Each read in [method], on the way on from its read of [EDGE_FLAG], of a constant of an enum
 * whose initializer names [SKIP_WAYS], as its index and field. One something jumps onto fails.
 */
private fun BytecodePatchContext.turnReads(method: Method): List<Pair<Int, String>> {
    val code = method.implementation?.instructions?.toList() ?: refuse("${method.definingClass}->${method.name} has no body")
    val reached = reachedFromEdge(method)
    val ways = mutableMapOf<String, Boolean>()
    fun isWay(type: String): Boolean = ways.getOrPut(type) {
        val enum = classDefByOrNull(type) ?: return@getOrPut false
        enum.superclass == "Ljava/lang/Enum;" && enum.methods.any { initializer ->
            initializer.name == "<clinit>" && SKIP_WAYS.all { loadsString(initializer, it) }
        }
    }
    val jumpedOnto = method.jumpTargets()
    return reached.sorted().mapNotNull { at ->
        if (code[at].opcode != Opcode.SGET_OBJECT) return@mapNotNull null
        val field = (code[at] as ReferenceInstruction).reference as FieldReference
        if (field.type != field.definingClass || !isWay(field.type)) return@mapNotNull null
        if (at in jumpedOnto) refuse("${method.definingClass}->${method.name} jumps onto its read of ${field.name}, so the arrows would be skipped")
        at to "${field.definingClass}->${field.name}:${field.type}"
    }
}

/**
 * Hands the way each skip went to [SKIPPED], just before the navigator reads it for its answer,
 * reading it once more into the same register. Run after the flags are answered, which writes into
 * the same method, so the reads are found again there.
 */
internal fun BytecodePatchContext.showSkipTurns(turns: SkipTurns) {
    val method = turnMethod(turns)
    val reads = turnReads(method)
    if (reads.map { it.second }.toSet() != turns.fields || reads.size != 2) {
        refuse("${turns.type}->${turns.name} no longer reads ${turns.fields} once each after the flags were answered")
    }
    val code = method.implementation!!.instructions.toList()
    reads.sortedByDescending { it.first }.forEach { (at, field) ->
        val register = (code[at] as OneRegisterInstruction).registerA
        method.addInstructions(
            at,
            """
                sget-object v$register, $field
                invoke-static/range { v$register .. v$register }, $SKIPPED
            """,
        )
    }
}

/**
 * Hands the navigator's compare of a reel's length with the skip to [LONGER] right before the
 * if-lez that gives a reel no longer than the skip the like, its answer back in the same register,
 * so with the switch on a double tap on a strip skips a short reel too. Found again in the method
 * the earlier steps wrote into.
 */
internal fun BytecodePatchContext.skipShortReels(turns: SkipTurns) {
    val method = turnMethod(turns)
    val at = lengthCheck(method)
    val register = (method.implementation!!.instructions.elementAt(at) as OneRegisterInstruction).registerA
    method.addInstructions(
        at,
        """
            invoke-static/range { v$register .. v$register }, $LONGER
            move-result v$register
        """,
    )
}

/**
 * Every read of [SKIP_FLAG], [SECONDS_FLAG] and [EDGE_FLAG], each with the hook that answers it.
 * Each flag has to be read at least once, and the edge where the seconds are read too, which is
 * where a double tap is worked out. Fails before anything changes when any of it isn't so, since
 * that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findReelSkip(): List<Pair<FlagLoad, String>> {
    val skips = findFlagLoads(PATCH, SKIP_FLAG, "Z")
    val seconds = findFlagLoads(PATCH, SECONDS_FLAG, FRACTION)
    val edges = findFlagLoads(PATCH, EDGE_FLAG, FRACTION)
    for ((loads, flag) in listOf(skips to SKIP_FLAG, seconds to SECONDS_FLAG, edges to EDGE_FLAG)) {
        if (loads.isEmpty()) refuse("nothing reads ${flag.toString(16)}")
    }
    fun FlagLoad.where() = Triple(type, name, parameters)
    val secondsRead = seconds.map { it.where() }.toSet()
    edges.forEach { edge ->
        if (edge.where() !in secondsRead) {
            refuse("${edge.type}->${edge.name} reads ${EDGE_FLAG.toString(16)} without ${SECONDS_FLAG.toString(16)}")
        }
    }
    return skips.map { it to SKIP } +
        seconds.map { it to if (it.answer == "D") SECONDS_DOUBLE else SECONDS } +
        edges.map { it to if (it.answer == "D") EDGE_DOUBLE else EDGE }
}
