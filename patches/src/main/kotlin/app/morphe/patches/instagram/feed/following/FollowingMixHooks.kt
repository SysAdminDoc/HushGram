/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val MIX = "Mix in Following"
private const val MIX_CLASS = "$EXTENSION_PACKAGE/feed/FollowingMix;"
internal const val MIX_CURSOR = "$MIX_CLASS->cursor(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;"
internal const val MIX_SOURCE_SET = "$MIX_CLASS->sourceSet(I)I"
internal const val MIX_SOURCE = "$MIX_CLASS->source(Ljava/lang/String;)Ljava/lang/String;"
internal const val MIX_NEXT_CURSOR = "$MIX_CLASS->nextCursor(Ljava/lang/String;)Ljava/lang/String;"
internal const val MIX_MORE = "$MIX_CLASS->moreAvailable(I)I"
internal const val MIX_REWOUND = "$MIX_CLASS->rewound(Ljava/lang/String;)Ljava/lang/String;"

/** The trace name Home's fetcher runs its prefetch under, loaded by its page method. */
internal const val HOME_FETCH = "AsyncPrefetch-ConnectToPrefetch"

/** The mark Home's reply handler logs once a page came back from the network. */
internal const val HOME_REPLY = "SHELL_RESPONSE_RECEIVED_FROM_NETWORK"

/** What Home's delivery logs for a page with nothing new, just before it goes back to the request's cursor. */
internal const val HOME_NOTHING_NEW = "No new items delivered"

/** How far apart the request's flag and paging source writes may sit. */
private const val SOURCE_REACH = 10

/** How far after the cursor write the reply's "more" write may sit. */
private const val WRITE_REACH = 8

/** How far before the delivery's write of the cursor its read of the request's may sit. */
private const val REWIND_REACH = 14

/**
 * Home's fetcher's page method: where it has the cursor ([merge], [cursorRegister]) with the head
 * flag in [headRegister] and itself in [fetcherRegister], and where the request it built takes the
 * flag ([setAt], [setRegister]) and the paging source ([sourceAt], [sourceRegister]) read from
 * [feedField].
 */
internal class MixFetch(
    val method: Method,
    val merge: Int,
    val cursorRegister: Int,
    val headRegister: Int,
    val fetcherRegister: Int,
    val feedField: FieldReference,
    val stateCursor: FieldReference,
    val requestType: String,
    val setAt: Int,
    val setRegister: Int,
    val sourceAt: Int,
    val sourceRegister: Int,
)

/** Where Home's reply handler writes the next page's cursor ([cursorAt]) and whether there's more ([moreAt]). */
internal class MixReply(
    val method: Method,
    val cursorAt: Int,
    val cursorRegister: Int,
    val moreAt: Int,
    val moreRegister: Int,
)

/** Where Home's delivery, given nothing new, branches on the request's cursor before writing it back. */
internal class MixRewind(val method: Method, val at: Int, val register: Int)

internal class MixTargets(val fetch: MixFetch, val reply: MixReply, val rewind: MixRewind)

/**
 * Finds [MixTargets], refusing anything it can't pin down to one:
 *
 * - The fetcher's page method, a method loading [HOME_FETCH]. A load more reads the cursor from
 *   the fetcher's state when it was handed none, and a head load asks with none, both landing on
 *   one instruction. Further down, the request it built takes the fetcher's "carry the paging
 *   source" flag and then its paging source, each read from the fetcher and written to the request.
 * - The reply handler, a method loading [HOME_REPLY]: its one write of the state's cursor, and the
 *   state's boolean written right after it, whether there's more.
 * - The delivery, a method loading [HOME_NOTHING_NEW]: the request's String it reads, checks for
 *   null and writes back as the state's cursor.
 */
internal fun BytecodePatchContext.findFollowingMix(): MixTargets {
    val fetch = findMixFetch()
    val reply = findMixReply(fetch)
    val rewind = findMixRewind(fetch, reply)
    return MixTargets(fetch, reply, rewind)
}

/** [findFollowingMix] and [mixInFollowing], or a warning and no mix where a build doesn't have what it needs. */
internal fun BytecodePatchContext.mixInFollowingOrWarn(): Boolean = try {
    mixInFollowing(findFollowingMix())
    true
} catch (refused: PatchException) {
    patchLog.warning("${refused.message}. Mix in Following isn't in this build, the other switches are.")
    false
}

internal fun BytecodePatchContext.findMixFetch(): MixFetch {
    val methods = classesLoadingString(HOME_FETCH).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, HOME_FETCH) }
    }
    if (methods.isEmpty()) refuseMix("no method loads $HOME_FETCH")
    val found = methods.mapNotNull { method -> fetchIn(method) }
    return found.singleOrNull() ?: refuseMix(
        "${found.size} of ${methods.size} method(s) loading $HOME_FETCH read a cursor and mark a request the way Home's fetcher does",
    )
}

private fun fetchIn(method: Method): MixFetch? {
    val code = method.code()
    if (code.isEmpty()) return null
    val flow = ControlFlow.of(method)
    val merges = code.indices.mapNotNull { at -> cursorMerge(method, code, flow, at) }
    val merge = merges.singleOrNull() ?: return null
    val sources = code.indices.mapNotNull { at -> sourceWrites(method, code, at, merge.fetcherRegister) }
    val source = sources.singleOrNull() ?: return null
    if (source.setAt <= merge.at) return null
    val targets = method.jumpTargets()
    if (source.setAt in targets || source.sourceAt in targets) return null
    return MixFetch(
        method, merge.at, merge.cursorRegister, merge.headRegister, merge.fetcherRegister, source.feedField,
        merge.stateCursor, source.requestType, source.setAt, source.setRegister, source.sourceAt, source.sourceRegister,
    )
}

private class CursorMerge(
    val at: Int,
    val cursorRegister: Int,
    val headRegister: Int,
    val fetcherRegister: Int,
    val stateCursor: FieldReference,
)

/**
 * The page method's cursor read at [at], when it's the load more's: `if-nez vCursorParameter,
 * :merge`, then `invoke-static {vFetcher}` for the state, its `move-result-object`, the read of the
 * state's String cursor and a `goto :merge`. The instruction before the merge is the head load's
 * `const/4 vCursor, 0`, and the one before that branches to the load more on a zero head flag.
 */
private fun cursorMerge(method: Method, code: List<Instruction>, flow: ControlFlow, at: Int): CursorMerge? {
    val read = code[at]
    if (read.opcode != Opcode.IGET_OBJECT || at < 3 || at + 1 >= code.size) return null
    val field = read.field() ?: return null
    if (field.type != STRING_TYPE) return null
    read as TwoRegisterInstruction
    val jump = code[at + 1]
    if (jump.opcode != Opcode.GOTO && jump.opcode != Opcode.GOTO_16 && jump.opcode != Opcode.GOTO_32) return null
    val merge = flow.normal[at + 1].singleOrNull() ?: return null
    val result = code[at - 1]
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || (result as OneRegisterInstruction).registerA != read.registerB) return null
    val getter = code[at - 2]
    val state = (getter as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (getter.opcode != Opcode.INVOKE_STATIC || state.returnType != field.definingClass ||
        state.parameterTypes.map(Any::toString) != listOf(method.definingClass)
    ) {
        return null
    }
    val fetcher = (getter as FiveRegisterInstruction).registerC
    val handed = code[at - 3]
    if (handed.opcode != Opcode.IF_NEZ || flow.normal[at - 3].firstOrNull() != merge) return null
    if (merge < 2 || merge >= code.size || code[merge] is OffsetInstruction) return null
    val none = code[merge - 1]
    if (none.opcode != Opcode.CONST_4 || (none as OneRegisterInstruction).registerA != read.registerA ||
        (none as NarrowLiteralInstruction).narrowLiteral != 0
    ) {
        return null
    }
    val head = code[merge - 2]
    if (head.opcode != Opcode.IF_EQZ || flow.normal[merge - 2].firstOrNull() != at - 3) return null
    val headRegister = (head as OneRegisterInstruction).registerA
    if (read.registerA > 15 || headRegister > 15 || fetcher > 15) return null
    return CursorMerge(merge, read.registerA, headRegister, fetcher, field)
}

private class SourceWrites(
    val setAt: Int,
    val setRegister: Int,
    val sourceAt: Int,
    val sourceRegister: Int,
    val feedField: FieldReference,
    val requestType: String,
)

/**
 * The request's flag write at [at] + 1, when it's the fetcher's: `iget-boolean vX, vFetcher` of
 * the fetcher's own field, then `iput-boolean vX, vRequest`, and a few instructions on
 * `iget-object vY, vFetcher` of the fetcher's String then `iput-object vY, vRequest` of the
 * request's String.
 */
private fun sourceWrites(method: Method, code: List<Instruction>, at: Int, fetcher: Int): SourceWrites? {
    val flag = code[at]
    if (flag.opcode != Opcode.IGET_BOOLEAN || at + 1 >= code.size) return null
    flag as TwoRegisterInstruction
    if (flag.registerB != fetcher || flag.field()?.definingClass != method.definingClass) return null
    val set = code[at + 1]
    if (set.opcode != Opcode.IPUT_BOOLEAN || (set as TwoRegisterInstruction).registerA != flag.registerA) return null
    val request = set.field()?.definingClass ?: return null
    val found = (at + 3..minOf(at + 1 + SOURCE_REACH, code.size - 1)).mapNotNull { put ->
        val write = code[put]
        val read = code[put - 1]
        if (write.opcode != Opcode.IPUT_OBJECT || read.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        write as TwoRegisterInstruction
        read as TwoRegisterInstruction
        val written = write.field() ?: return@mapNotNull null
        val feed = read.field() ?: return@mapNotNull null
        if (written.definingClass != request || written.type != STRING_TYPE || write.registerB != set.registerB) return@mapNotNull null
        if (feed.definingClass != method.definingClass || feed.type != STRING_TYPE || read.registerB != fetcher) return@mapNotNull null
        if (read.registerA != write.registerA) return@mapNotNull null
        SourceWrites(at + 1, set.registerA, put, write.registerA, feed, request)
    }
    return found.singleOrNull()
}

internal fun BytecodePatchContext.findMixReply(fetch: MixFetch): MixReply {
    val methods = classesLoadingString(HOME_REPLY).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, HOME_REPLY) }
    }
    if (methods.isEmpty()) refuseMix("no method loads $HOME_REPLY")
    val found = methods.mapNotNull { method ->
        val code = method.code()
        val writes = code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].field().sameAs(fetch.stateCursor) }
        val cursorAt = writes.singleOrNull() ?: return@mapNotNull null
        val moreAt = (cursorAt + 1..minOf(cursorAt + WRITE_REACH, code.size - 1)).firstOrNull {
            code[it].opcode == Opcode.IPUT_BOOLEAN && code[it].field()?.definingClass == fetch.stateCursor.definingClass
        } ?: return@mapNotNull null
        val targets = method.jumpTargets()
        if (cursorAt in targets || moreAt in targets) return@mapNotNull null
        MixReply(
            method, cursorAt, (code[cursorAt] as TwoRegisterInstruction).registerA,
            moreAt, (code[moreAt] as TwoRegisterInstruction).registerA,
        )
    }
    return found.singleOrNull() ?: refuseMix(
        "${found.size} of ${methods.size} method(s) loading $HOME_REPLY write the state's cursor once, then whether there's more",
    )
}

internal fun BytecodePatchContext.findMixRewind(fetch: MixFetch, reply: MixReply): MixRewind {
    val methods = classesLoadingString(HOME_NOTHING_NEW).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, HOME_NOTHING_NEW) && it.key() != reply.method.key() }
    }
    if (methods.isEmpty()) refuseMix("no method loads $HOME_NOTHING_NEW")
    val found = methods.flatMap { method ->
        val code = method.code()
        if (code.isEmpty()) return@flatMap emptyList()
        val flow = ControlFlow.of(method)
        val targets = method.jumpTargets()
        code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].field().sameAs(fetch.stateCursor) }
            .mapNotNull { write ->
                val value = (code[write] as TwoRegisterInstruction).registerA
                (maxOf(0, write - REWIND_REACH) until write - 1).firstNotNullOfOrNull { read ->
                    val load = code[read]
                    val check = code[read + 1]
                    val field = load.field()
                    if (load.opcode != Opcode.IGET_OBJECT || field?.definingClass != fetch.requestType ||
                        field.type != STRING_TYPE || (load as TwoRegisterInstruction).registerA != value
                    ) {
                        return@firstNotNullOfOrNull null
                    }
                    if (check.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != value) {
                        return@firstNotNullOfOrNull null
                    }
                    if ((flow.normal[read + 1].firstOrNull() ?: -1) <= write || read + 1 in targets) {
                        return@firstNotNullOfOrNull null
                    }
                    MixRewind(method, read + 1, value)
                }
            }
    }
    return found.singleOrNull() ?: refuseMix(
        "${found.size} place(s) in ${methods.size} method(s) loading $HOME_NOTHING_NEW write the request's cursor back",
    )
}

/**
 * Hooks [targets]: [MIX_CURSOR] where the fetcher has its cursor, [MIX_SOURCE_SET] and
 * [MIX_SOURCE] before the request takes its flag and paging source, [MIX_NEXT_CURSOR] and
 * [MIX_MORE] before the reply's writes, and [MIX_REWOUND] before the delivery checks the cursor it
 * goes back to. Later indexes first within each method, so the earlier ones hold.
 */
internal fun BytecodePatchContext.mixInFollowing(targets: MixTargets) {
    val fetch = targets.fetch
    val fetcher = mutable(fetch.method)
    // Found before anything goes in, since a refusal leaves the method as it was.
    val feed = fetch.method.freeLocalsAt(
        "$MIX: cursor",
        fetch.merge,
        1,
        except = listOf(fetch.cursorRegister, fetch.headRegister, fetch.fetcherRegister),
    ).single()
    val reply = mutable(targets.reply.method)
    val rewind = mutable(targets.rewind.method)
    fetcher.addInstructions(
        fetch.sourceAt,
        """
            invoke-static/range { v${fetch.sourceRegister} .. v${fetch.sourceRegister} }, $MIX_SOURCE
            move-result-object v${fetch.sourceRegister}
        """,
    )
    fetcher.addInstructions(
        fetch.setAt,
        """
            invoke-static/range { v${fetch.setRegister} .. v${fetch.setRegister} }, $MIX_SOURCE_SET
            move-result v${fetch.setRegister}
        """,
    )
    fetcher.addInstructionsAtControlFlowLabel(
        fetch.merge,
        """
            iget-object v$feed, v${fetch.fetcherRegister}, ${fetch.feedField.descriptor()}
            invoke-static { v${fetch.cursorRegister}, v${fetch.headRegister}, v$feed }, $MIX_CURSOR
            move-result-object v${fetch.cursorRegister}
        """,
    )
    val answer = targets.reply
    reply.addInstructions(
        answer.moreAt,
        """
            invoke-static/range { v${answer.moreRegister} .. v${answer.moreRegister} }, $MIX_MORE
            move-result v${answer.moreRegister}
        """,
    )
    reply.addInstructions(
        answer.cursorAt,
        """
            invoke-static/range { v${answer.cursorRegister} .. v${answer.cursorRegister} }, $MIX_NEXT_CURSOR
            move-result-object v${answer.cursorRegister}
        """,
    )
    val back = targets.rewind
    rewind.addInstructions(
        back.at,
        """
            invoke-static/range { v${back.register} .. v${back.register} }, $MIX_REWOUND
            move-result-object v${back.register}
        """,
    )
}

private const val STRING_TYPE = "Ljava/lang/String;"

private fun refuseMix(detail: String): Nothing = throw PatchException("$MIX: $detail")

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun FieldReference?.sameAs(other: FieldReference): Boolean =
    this != null && definingClass == other.definingClass && name == other.name && type == other.type

private fun FieldReference.descriptor(): String = "$definingClass->$name:$type"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun Method.key(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }
