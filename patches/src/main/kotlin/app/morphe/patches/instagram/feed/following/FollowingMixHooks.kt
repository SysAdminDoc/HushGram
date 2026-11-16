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
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
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

/** How far after the cursor write the reply's "more" write may sit. */
private const val WRITE_REACH = 8

/** How far before the delivery's write of the cursor its read of the request's may sit. */
private const val REWIND_REACH = 14

/** How many moves a constructor argument may pass through on its way from the builder's field read. */
private const val MOVE_REACH = 4

/**
 * Home's fetcher's page method: where it has the cursor ([merge], [cursorRegister]) with the head
 * flag in [headRegister] and itself in [fetcherRegister]. Its request builder ([builder]) makes the
 * [requestType] at [buildAt], handing the constructor the fetcher's "carry the paging source" flag
 * in [setRegister] and the paging source, read from [feedField], in [sourceRegister].
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
    val builder: Method,
    val buildAt: Int,
    val setRegister: Int,
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
 *   one instruction. Further down it calls the fetcher's request builder, whose one call of the
 *   request's constructor takes the fetcher's "carry the paging source" flag and its paging source,
 *   each read from the fetcher's own field.
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
        classDef.methods.filter { loadsString(it, HOME_FETCH) }.map { classDef to it }
    }
    if (methods.isEmpty()) refuseMix("no method loads $HOME_FETCH")
    val found = methods.mapNotNull { (classDef, method) -> fetchIn(classDef, method) }
    return found.singleOrNull() ?: refuseMix(
        "${found.size} of ${methods.size} method(s) loading $HOME_FETCH read a cursor and mark a request the way Home's fetcher does",
    )
}

private fun fetchIn(classDef: ClassDef, method: Method): MixFetch? {
    val code = method.code()
    if (code.isEmpty()) return null
    val flow = ControlFlow.of(method)
    val merges = code.indices.mapNotNull { at -> cursorMerge(method, code, flow, at) }
    val merge = merges.singleOrNull() ?: return null
    val builds = (merge.at + 1 until code.size).mapNotNull { at ->
        val builder = requestBuilder(classDef, code[at], merge.fetcherRegister) ?: return@mapNotNull null
        requestIn(builder)?.let { builder to it }
    }
    val (builder, build) = builds.singleOrNull() ?: return null
    return MixFetch(
        method, merge.at, merge.cursorRegister, merge.headRegister, merge.fetcherRegister, build.feedField,
        merge.stateCursor, builder.returnType, builder, build.at, build.setRegister, build.sourceRegister,
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

/**
 * The fetcher's own method [call] runs on [fetcher], when it could be the request builder: an
 * `invoke-direct` of a method of [classDef] other than a constructor, answering an object other
 * than a String.
 */
private fun requestBuilder(classDef: ClassDef, call: Instruction, fetcher: Int): Method? {
    if (call.opcode != Opcode.INVOKE_DIRECT && call.opcode != Opcode.INVOKE_DIRECT_RANGE) return null
    val called = (call as ReferenceInstruction).reference as? MethodReference ?: return null
    if (called.definingClass != classDef.type || called.name == "<init>") return null
    if (!called.returnType.startsWith("L") || called.returnType == STRING_TYPE) return null
    if (call.namedRegisters().firstOrNull() != fetcher) return null
    return classDef.methods.firstOrNull {
        it.name == called.name && it.returnType == called.returnType &&
            it.parameterTypes.map(Any::toString) == called.parameterTypes.map(Any::toString)
    }
}

private class RequestBuild(val at: Int, val setRegister: Int, val sourceRegister: Int, val feedField: FieldReference)

/**
 * The request [builder] makes: its one `invoke-direct` of its answer type's constructor, nothing
 * branching onto it, where exactly one boolean argument and exactly one String argument hold what
 * the builder read from its own fields off `this`. Those are the "carry the paging source" flag
 * and the paging source. Any moves on the way back to the read are followed.
 */
private fun requestIn(builder: Method): RequestBuild? {
    if (AccessFlags.STATIC.isSet(builder.accessFlags)) return null
    val code = builder.code()
    val calls = code.indices.filter { at ->
        val call = code[at]
        val called = (call as? ReferenceInstruction)?.reference as? MethodReference
        (call.opcode == Opcode.INVOKE_DIRECT || call.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            called?.definingClass == builder.returnType && called.name == "<init>"
    }
    val at = calls.singleOrNull() ?: return null
    if (at in builder.jumpTargets()) return null
    val flow = ControlFlow.of(builder)
    val self = builder.localRegisterCount()
    if (code.any { it.writes(self) }) return null
    val constructor = (code[at] as ReferenceInstruction).reference as MethodReference
    val arguments = code[at].namedRegisters()
    var slot = 1
    val flags = mutableListOf<Pair<Int, FieldReference>>()
    val sources = mutableListOf<Pair<Int, FieldReference>>()
    for (type in constructor.parameterTypes.map(Any::toString)) {
        val register = arguments.getOrNull(slot) ?: return null
        slot += if (type == "J" || type == "D") 2 else 1
        val wanted = when (type) {
            "Z" -> Opcode.IGET_BOOLEAN
            STRING_TYPE -> Opcode.IGET_OBJECT
            else -> continue
        }
        val readAt = origin(code, flow, at, register)?.at ?: continue
        val read = code[readAt]
        val field = read.field() ?: continue
        if (read.opcode != wanted || field.definingClass != builder.definingClass || field.type != type) continue
        if (origin(code, flow, readAt, (read as TwoRegisterInstruction).registerB) != Origin(null, self)) continue
        if (register > 255) return null
        (if (type == "Z") flags else sources) += register to field
    }
    val flag = flags.singleOrNull() ?: return null
    val source = sources.singleOrNull() ?: return null
    return RequestBuild(at, flag.first, source.first, source.second)
}

/** Where a register's value came from: the instruction that wrote it, or null and the register for a parameter. */
private data class Origin(val at: Int?, val register: Int)

/**
 * The one write whose value [register] holds when the instruction at [at] runs, followed back
 * through moves, or the register itself when it holds a parameter nothing writes. Null when more
 * than one write can reach, or a write on some paths and the parameter on others.
 */
private fun origin(code: List<Instruction>, flow: ControlFlow, at: Int, register: Int, moves: Int = MOVE_REACH): Origin? {
    val reaching = writesReaching(code, flow, register, at) ?: return null
    if (reaching == setOf(ENTRY)) return if (code.none { it.writes(register) }) Origin(null, register) else null
    val write = reaching.singleOrNull()?.takeIf { it != ENTRY } ?: return null
    val instruction = code[write]
    if (instruction.opcode in MOVES && moves > 0) {
        return origin(code, flow, write, (instruction as TwoRegisterInstruction).registerB, moves - 1)
    }
    return Origin(write, register)
}

/** Stands for the method's start among [writesReaching]'s answers. */
private const val ENTRY = -1

/**
 * The writes of [register] that reach the instruction at [at] over every branch and handler, with
 * [ENTRY] for a path from the method's start that writes nothing. A wide write covers both halves,
 * and an instruction that throws never writes, so its handlers see what it saw. Null when nothing
 * reaches [at].
 */
private fun writesReaching(code: List<Instruction>, flow: ControlFlow, register: Int, at: Int): Set<Int>? {
    val into = arrayOfNulls<MutableSet<Int>>(code.size)
    into[0] = mutableSetOf(ENTRY)
    val pending = ArrayDeque(listOf(0))
    fun hand(next: Int, values: Set<Int>) {
        val target = into[next] ?: mutableSetOf<Int>().also { into[next] = it }
        if (target.addAll(values)) pending.addLast(next)
    }
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        val state = into[index]!!
        val out: Set<Int> = if (code[index].writes(register)) setOf(index) else state
        flow.normal[index].forEach { hand(it, out) }
        flow.exceptional[index].forEach { hand(it, state) }
    }
    return into[at]
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private val MOVES = setOf(
    Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
    Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16,
)

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
 * [MIX_SOURCE] right before the request builder hands the request's constructor its flag and
 * paging source, [MIX_NEXT_CURSOR] and [MIX_MORE] before the reply's writes, and [MIX_REWOUND]
 * before the delivery checks the cursor it goes back to. Later indexes first within each method,
 * so the earlier ones hold.
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
    val builder = mutable(fetch.builder)
    val reply = mutable(targets.reply.method)
    val rewind = mutable(targets.rewind.method)
    builder.addInstructions(
        fetch.buildAt,
        """
            invoke-static/range { v${fetch.setRegister} .. v${fetch.setRegister} }, $MIX_SOURCE_SET
            move-result v${fetch.setRegister}
            invoke-static/range { v${fetch.sourceRegister} .. v${fetch.sourceRegister} }, $MIX_SOURCE
            move-result-object v${fetch.sourceRegister}
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
