/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.util.BitSet

internal const val KNOWN = "$FOLLOWING_LIST->known($OBJECT$OBJECT)$OBJECT"
internal const val ANSWERED = "$FOLLOWING_LIST->answered($OBJECT$OBJECT$OBJECT)V"

/** A follow list's analytics name the method fetching a page of the list loads. */
internal const val NON_RECIP_FOLLOWERS = "non_recip_followers"

/** The key of the batch answer's statuses, which its parser loads. */
internal const val FRIENDSHIP_STATUSES = "friendship_statuses"

/**
 * Where the patch has Instagram ask the server whether each row of your own Following list follows
 * you, and hands the answers to [FOLLOWING_LIST] (#40). Instagram caches a friendship status for
 * every account it has drawn anywhere, and the list skipped asking about a row whose cached status
 * already had an answer, however it got there. The list's own batch request already asks for
 * followed_by, so other lists' requests are left as they are.
 */
internal class FollowAnswers(
    /** The page fetch, where it branches on a row's cached friendship, and the registers of that and the list. */
    val fetch: Site,
    val known: Int,
    val friendship: Int,
    val friendshipType: String,
    val list: Int,
    /** The list's state holder, and its fields of the list's data and the signed-in account. */
    val listType: String,
    val listData: FieldReference,
    val listSession: FieldReference,
    /** The answer's parser, the place right after it checks the account is cached, and its registers there. */
    val parser: Site,
    val answered: Int,
    val user: Int,
    val status: Int,
    val session: Int,
    /** The status record's field of followed_by. */
    val followedBy: FieldReference,
)

internal class Site(val type: String, val name: String, val parameters: List<String>) {
    override fun toString() = "$type->$name"
}

private fun Site(classDef: ClassDef, method: Method) =
    Site(classDef.type, method.name, method.parameterTypes.map(CharSequence::toString))

/**
 * Finds the two places, before anything changes:
 * - the one method loading [NON_RECIP_FOLLOWERS] that asks a [USER] for its [RELATIONSHIP] and,
 *   when there's none, goes straight to adding the row to a collection: the rows to ask about. The
 *   list is the one register there written once, before everything else, from a field whose class
 *   keeps one [FOLLOW_LIST_DATA] and one [USER_SESSION];
 * - the one method loading [FRIENDSHIP_STATUSES] that hands each status to a static field parser
 *   loading [FOLLOWED_BY] and writing it to a Boolean field of the status. The hook goes right after
 *   it checks that the account the status is about, from a `(UserSession, String)` lookup, is cached.
 *
 * Fails when any of them isn't there, or there's more than one, since that's an update this patch
 * hasn't seen, and when a register the hook reads might hold something else where it goes.
 */
internal fun BytecodePatchContext.findFollowAnswers(): FollowAnswers {
    val fetches = mutableListOf<Pair<ClassDef, Method>>()
    val parsers = mutableListOf<Pair<ClassDef, Method>>()
    val holders = (classesHolding(NON_RECIP_FOLLOWERS) + classesHolding(FRIENDSHIP_STATUSES)).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.holdsString(NON_RECIP_FOLLOWERS)) fetches += classDef to method
            if (method.holdsString(FRIENDSHIP_STATUSES)) parsers += classDef to method
        }
    }

    val pages = fetches.mapNotNull { (classDef, method) -> unknownRow(method)?.let { Triple(classDef, method, it) } }
    val (fetchClass, fetch, known) = pages.singleOrNull()
        ?: refuse("expected one method loading $NON_RECIP_FOLLOWERS to ask about rows with no $RELATIONSHIP, found ${pages.size}")
    val fetchWhere = "${fetchClass.type}->${fetch.name}"
    val fetchCode = fetch.implementation!!.instructions.toList()
    val friendship = (fetchCode[known] as OneRegisterInstruction).registerA
    val friendshipType = fetchCode[known - 2].methodReference()!!.returnType
    if (known in fetch.jumpTargets()) refuse("$fetchWhere has no place right after asking for a row's $RELATIONSHIP that only that leads to")
    val holder = listHolder(fetchClass, fetch, known)
    if (maxOf(friendship, holder.register) > 15) refuse("$fetchWhere keeps the row's $RELATIONSHIP or the list past v15")

    val answers = parsers.flatMap { (classDef, method) -> statusParses(method).map { Triple(classDef, method, it) } }
    val (parserClass, parser, parse) = answers.singleOrNull()
        ?: refuse("expected one method loading $FRIENDSHIP_STATUSES to parse each status's $FOLLOWED_BY, found ${answers.size}")
    val answer = answerSite(parserClass, parser, parse)
    // The stub reads followed_by off the status from the extension, outside Instagram's packages.
    val statusClass = classDefByOrNull(parse.followedBy.definingClass)
        ?: refuse("${parse.followedBy.definingClass}, the friendship status, isn't in this build")
    if (!AccessFlags.PUBLIC.isSet(statusClass.accessFlags)) refuse("${statusClass.type} isn't public, so the extension can't reach it")
    if (statusClass.fields.none { it.name == parse.followedBy.name && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
        refuse("${parse.followedBy} isn't public")
    }

    return FollowAnswers(
        Site(fetchClass, fetch), known, friendship, friendshipType, holder.register, holder.type, holder.data, holder.session,
        Site(parserClass, parser), answer.at, answer.user, parse.status, answer.session, parse.followedBy,
    )
}

/**
 * Where [method] branches straight to adding a row to a collection when a [USER] it asked has no
 * [RELATIONSHIP]: the `if-eqz` right after the getter's move-result, or null.
 */
private fun unknownRow(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val sites = code.indices.filter { at ->
        val asked = code[at].methodReference()
        val kept = code.getOrNull(at + 1) as? OneRegisterInstruction
        val branch = code.getOrNull(at + 2)
        asked != null && asked.definingClass == USER && asked.parameterTypes.isEmpty() && asked.returnType == RELATIONSHIP &&
            kept != null && code[at + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            branch?.opcode == Opcode.IF_EQZ && (branch as OneRegisterInstruction).registerA == kept.registerA &&
            addsAt(method, code, at + 2)
    }
    return sites.singleOrNull()?.let { it + 2 }
}

/** Whether the branch at [at] goes to a collection's `add(Object)`. */
private fun addsAt(method: Method, code: List<Instruction>, at: Int): Boolean {
    val target = ControlFlow.of(method).normal[at].firstOrNull() ?: return false
    if (code[at] !is OffsetInstruction) return false
    val added = code.getOrNull(target)?.methodReference() ?: return false
    return added.name == "add" && added.parameterTypes.map(CharSequence::toString) == listOf(OBJECT) && added.returnType == "Z"
}

private class ListHolder(val register: Int, val type: String, val data: FieldReference, val session: FieldReference)

/**
 * The register holding the list's state at [at]: one written by reading a field whose class keeps
 * one [FOLLOW_LIST_DATA] and one [USER_SESSION], and holding that value on every way to [at].
 * Instagram's fetch reads other objects of that shape too (the row binder, say), into registers it
 * has reused by the time it asks about a row.
 */
private fun BytecodePatchContext.listHolder(classDef: ClassDef, method: Method, at: Int): ListHolder {
    val where = "${classDef.type}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val holders = code.indices.mapNotNull { index ->
        val read = code[index].fieldReference() ?: return@mapNotNull null
        if (code[index].opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        val heldClass = classDefByOrNull(read.type) ?: return@mapNotNull null
        val data = heldClass.instanceFields(FOLLOW_LIST_DATA).singleOrNull() ?: return@mapNotNull null
        val session = heldClass.instanceFields(USER_SESSION).singleOrNull() ?: return@mapNotNull null
        if (!method.holdsAt((code[index] as OneRegisterInstruction).registerA, index, at)) return@mapNotNull null
        Triple(index, heldClass, data to session)
    }
    val (write, heldClass, fields) = holders.singleOrNull()
        ?: refuse("expected $where to hold one follow list keeping a $FOLLOW_LIST_DATA and a $USER_SESSION where it asks about a row, found ${holders.size}")
    if (!AccessFlags.PUBLIC.isSet(heldClass.accessFlags)) refuse("${heldClass.type} isn't public, so the extension can't reach it")
    return ListHolder((code[write] as OneRegisterInstruction).registerA, heldClass.type, fields.first, fields.second)
}

private class StatusParse(val at: Int, val status: Int, val followedBy: FieldReference)

/**
 * The static calls in [method] handing a status to a field parser `(parser, status, String)V` that
 * loads [FOLLOWED_BY] and right after writes a Boolean field of the status, each with its field.
 */
private fun BytecodePatchContext.statusParses(method: Method): List<StatusParse> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        val called = code[at].methodReference() ?: return@mapNotNull null
        val parameters = called.parameterTypes.map(CharSequence::toString)
        if (code[at].opcode != Opcode.INVOKE_STATIC || called.returnType != "V" || parameters.size != 3 || parameters[2] != STRING) {
            return@mapNotNull null
        }
        val status = parameters[1]
        val parser = classDefByOrNull(called.definingClass)?.methods?.singleOrNull {
            it.name == called.name && it.parameterTypes.map(CharSequence::toString) == parameters && it.returnType == "V"
        } ?: return@mapNotNull null
        val field = followedByField(parser, status) ?: return@mapNotNull null
        StatusParse(at, code[at].namedRegisters()[1], field)
    }
}

/** The Boolean field of [status] that [parser] writes first after loading [FOLLOWED_BY], when it's the same each time. */
private fun followedByField(parser: Method, status: String): FieldReference? {
    val code = parser.implementation?.instructions?.toList() ?: return null
    val fields = code.indices.filter { code[it].loadsString(FOLLOWED_BY) }.map { loaded ->
        (loaded + 1 until code.size).map { code[it] }.firstOrNull { it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.RETURN_VOID }
            ?.fieldReference()?.takeIf { it.definingClass == status && it.type == "Ljava/lang/Boolean;" }
    }
    return fields.distinctBy { it?.toString() }.singleOrNull()
}

private class AnswerSite(val at: Int, val user: Int, val session: Int)

/**
 * Right after [method] checks, past [parse], that the account a status is about is cached: the
 * `if-eqz` on the result of its one `(UserSession, String)` lookup of a [USER]. The account, the
 * status and the session the lookup was handed must each hold the same value there.
 */
private fun BytecodePatchContext.answerSite(classDef: ClassDef, method: Method, parse: StatusParse): AnswerSite {
    val where = "${classDef.type}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val lookups = code.indices.filter { at ->
        val called = code[at].methodReference()
        code[at].opcode == Opcode.INVOKE_STATIC && called != null && called.returnType == USER &&
            called.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION, STRING) &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }
    val lookup = lookups.singleOrNull() ?: refuse("expected $where to look one cached $USER up by its ID, found ${lookups.size}")
    if (lookup > parse.at) refuse("$where looks the account up after parsing its status")
    val user = (code[lookup + 1] as OneRegisterInstruction).registerA
    val session = code[lookup].namedRegisters()[0]

    val check = (parse.at + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.IF_EQZ && (code[it] as OneRegisterInstruction).registerA == user
    } ?: refuse("$where doesn't check the account is cached after parsing its status")
    val at = check + 1
    if (at >= code.size || at in method.jumpTargets()) refuse("$where has no place right after its check of the account that only the check leads to")

    val statusWrite = (parse.at - 1 downTo 0).firstOrNull { code[it].writes(parse.status) }
        ?: refuse("$where parses a status it never wrote")
    val sessionWrite = (lookup - 1 downTo 0).firstOrNull { code[it].writes(session) }
    val held = listOf(
        "the account" to method.holdsAt(user, lookup + 1, at),
        "the status" to (method.holdsAt(parse.status, statusWrite, parse.at) && method.holdsAt(parse.status, statusWrite, at)),
        "the session" to (method.holdsAt(session, sessionWrite, lookup) && method.holdsAt(session, sessionWrite, at)),
    )
    held.firstOrNull { !it.second }?.let { refuse("$where may not hold ${it.first} right after its check of the account") }
    if (maxOf(user, parse.status, session) > 15) refuse("$where keeps the account, the status or the session past v15")
    return AnswerSite(at, user, session)
}

/**
 * Whether [register] holds the value written at [write] (or held on entry, for null) at [at]: no
 * way to [at] starts at the method's start or another write of [register] without passing [write].
 * A write that throws leaves the value it would have replaced.
 */
private fun Method.holdsAt(register: Int, write: Int?, at: Int): Boolean {
    val flow = ControlFlow.of(this)
    val writes = flow.instructions.indices.filter { flow.instructions[it].writes(register) }.toSet()
    val reached = BitSet()
    val pending = ArrayDeque<Int>()
    fun visit(next: List<Int>) = next.forEach { if (!reached[it]) { reached.set(it); pending += it } }
    if (write != null) visit(listOf(0))
    writes.filter { it != write }.forEach { visit(flow.normal[it]) }
    while (pending.isNotEmpty()) {
        val index = pending.removeFirst()
        if (index == at) return false
        if (index !in writes) visit(flow.normal[index])
        visit(flow.exceptional[index])
    }
    return true
}

/**
 * Puts the two hooks in:
 * - where the page fetch branches on a row's cached friendship, [KNOWN] on it and the list, the
 *   answer cast back to the getter's type;
 * - right after the parser's check that the account is cached, [ANSWERED] on the account, its
 *   status and the session.
 */
internal fun BytecodePatchContext.askFollowAnswers(found: FollowAnswers) {
    fun method(site: Site) = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    method(found.fetch).addInstructions(
        found.known,
        """
            invoke-static { v${found.friendship}, v${found.list} }, $KNOWN
            move-result-object v${found.friendship}
            check-cast v${found.friendship}, ${found.friendshipType}
        """,
    )
    method(found.parser).addInstructions(
        found.answered,
        "invoke-static { v${found.user}, v${found.status}, v${found.session} }, $ANSWERED",
    )
}
