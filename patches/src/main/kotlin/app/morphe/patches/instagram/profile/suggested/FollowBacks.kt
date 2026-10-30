/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested people on profiles"

internal const val FOLLOW_BACKS = "$PROFILE_SUGGESTIONS->followBacks(Ljava/util/List;)Ljava/util/List;"
internal const val SUGGESTION_SECTION = "$PROFILE_SUGGESTIONS->hidesSuggestionSection(Ljava/lang/String;)Z"

/**
 * The type the newer notifications reply gives People you don't follow back when it sends them as one
 * of its suggestion sections. It's the server's name for the section, so Instagram keeps it.
 */
internal const val FOLLOW_BACK_TYPE = "FOLLOW_BACK"

/**
 * The section the notifications' list builder falls back to when the server names none. Only that
 * builder loads it.
 */
internal const val FALLBACK_SECTION = "FRIENDING_UNIT_FALLBACK"

private const val STRING = "Ljava/lang/String;"
private const val IS_EMPTY = "Ljava/util/List;->isEmpty()Z"
private const val HASH_CODE = "Ljava/lang/String;->hashCode()I"

/** How far past the type's hashCode its switch may compare it with FOLLOW_BACK's hash. */
private const val SWITCH_REACH = 12

/**
 * The key of the People you don't follow back accounts in the reply your notifications load. Instagram
 * keeps it: it's the name of the field in the server's answer, and only the notifications' parser reads it.
 */
internal const val FOLLOW_BACKS_KEY = "follow_backs"

private const val PARSE = "unsafeParseFromJson"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

/** The parser's write of the accounts: its method, the iput-object, the register it writes and the field. */
internal class FollowBacksSite(val type: String, val parameters: List<String>, val store: Int, val register: Int, val field: String)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds where the notifications' parser (`LX/081e` on 385611438) keeps the accounts it reads under
 * [FOLLOW_BACKS_KEY]: the one [PARSE] answering an object that loads the key, and after the key the
 * new ArrayList it fills and the iput-object writing that list into a [LIST] field, before the next
 * key loads. Nothing after the write may read the list's register, since the hook can swap what it
 * holds. Fails before anything changes when there isn't exactly one such write, since that's an
 * update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findFollowBacks(): FollowBacksSite {
    val loads = classesLoadingString(FOLLOW_BACKS_KEY).flatMap { classDef ->
        classDef.methods.filter { it.name == PARSE && it.returnType == OBJECT && it.parameterTypes.size == 1 }
            .flatMap { method ->
                val code = method.implementation?.instructions?.toList().orEmpty()
                code.indices.filter { stringLoadedAt(code, it) == FOLLOW_BACKS_KEY }.map { method to it }
            }
    }
    val (parser, at) = loads.singleOrNull()
        ?: refuse("expected one parser loading \"$FOLLOW_BACKS_KEY\" once, found ${loads.size} loads")
    val where = "${parser.definingClass}->$PARSE"
    val code = parser.implementation!!.instructions.toList()
    val end = (at + 1 until code.size).firstOrNull { stringLoadedAt(code, it) != null } ?: code.size

    val made = (at + 1 until end).firstOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && ((code[it] as ReferenceInstruction).reference as TypeReference).type == ARRAY_LIST
    } ?: refuse("$where doesn't make a list for \"$FOLLOW_BACKS_KEY\"")
    val register = (code[made] as OneRegisterInstruction).registerA
    val stores = (made + 1 until end).filter {
        val field = (code[it] as? ReferenceInstruction)?.reference as? FieldReference
        code[it].opcode == Opcode.IPUT_OBJECT && field?.type == LIST && (code[it] as TwoRegisterInstruction).registerA == register
    }
    val store = stores.singleOrNull()
        ?: refuse("expected one write of the \"$FOLLOW_BACKS_KEY\" list in $where, found ${stores.size}")
    if (parser.readsAfter(store, register).isNotEmpty()) refuse("$where reads v$register after keeping \"$FOLLOW_BACKS_KEY\"")
    val field = (code[store] as ReferenceInstruction).reference as FieldReference
    return FollowBacksSite(
        parser.definingClass, parser.parameterTypes.map(CharSequence::toString), store, register,
        "${field.definingClass}->${field.name}:${field.type}",
    )
}

/**
 * Hands the list to [FOLLOW_BACKS] right before the parser keeps it, on the write's own label, since
 * the parser's loop over the accounts branches straight there. A null answer leaves the field as it
 * is when the reply has no such key, and the notifications then build no People you don't follow
 * back section at all. Notifications about new followers come under other keys and stay.
 */
internal fun BytecodePatchContext.hideFollowBacks(site: FollowBacksSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == PARSE && it.returnType == OBJECT && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val store = method.getInstruction(site.store)
    val field = (store as? ReferenceInstruction)?.reference as? FieldReference
    if (store.opcode != Opcode.IPUT_OBJECT || "${field?.definingClass}->${field?.name}:${field?.type}" != site.field) {
        refuse("${site.type}->$PARSE changed before its \"$FOLLOW_BACKS_KEY\" list could be hooked")
    }
    method.addInstructionsAtControlFlowLabel(
        site.store,
        """
            invoke-static/range { v${site.register} .. v${site.register} }, $FOLLOW_BACKS
            move-result-object v${site.register}
        """,
    )
}

/**
 * Where the notifications' list builder decides to draw a suggestion section the server sent: its
 * method, the load of [FOLLOW_BACK_TYPE] the hook goes in front of, the register holding the
 * section's type, the register of the rows check's answer, and where that check skips the section.
 */
internal class FollowBackSectionSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val at: Int,
    val section: Int,
    val answer: Int,
    val skip: Int,
)

/**
 * Finds the notifications' list builder (`LX/0BYR.A00` on 385611438), the one method loading
 * [FALLBACK_SECTION], and in it the one load of [FOLLOW_BACK_TYPE]. Right before that load the builder
 * has checked the section has rows: List.isEmpty, its answer, and an if-nez past the section when
 * there are none. Nothing else jumps to the load, so it runs only for a section with rows. After it
 * the builder switches on the section's type, a String.hashCode compared with FOLLOW_BACK's hash, to
 * pick the header, and that hashCode's register is the one the hook hands over.
 *
 * The hook reads that register at the load, so it has to hold a String there on every path, or ART
 * rejects the whole builder class and the notifications screen crashes. A straight line from the load
 * to hashCode proves nothing once a branch in between can loop back and write it, so [holdsStringOnEveryPath]
 * walks every path into the load instead. Fails before anything changes when any of that isn't so.
 */
internal fun BytecodePatchContext.findFollowBackSection(): FollowBackSectionSite {
    val builders = classesLoadingString(FALLBACK_SECTION).flatMap { classDef ->
        classDef.methods.filter { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices.any { stringLoadedAt(code, it) == FALLBACK_SECTION }
        }
    }
    val builder = builders.singleOrNull()
        ?: refuse("expected one method loading \"$FALLBACK_SECTION\", found ${builders.size}")
    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.implementation!!.instructions.toList()
    val loads = code.indices.filter { stringLoadedAt(code, it) == FOLLOW_BACK_TYPE }
    val at = loads.singleOrNull() ?: refuse("expected $where to load \"$FOLLOW_BACK_TYPE\" once, found ${loads.size}")

    val check = code.getOrNull(at - 3)
    val answer = code.getOrNull(at - 2)
    val branch = code.getOrNull(at - 1)
    val checked = check != null && check.methodText() == IS_EMPTY && check.namedRegisters().size == 1 &&
        answer is OneRegisterInstruction && answer.opcode == Opcode.MOVE_RESULT &&
        branch is OneRegisterInstruction && branch.opcode == Opcode.IF_NEZ && branch.registerA == answer.registerA
    if (!checked) refuse("$where doesn't skip a section without rows right before loading \"$FOLLOW_BACK_TYPE\"")
    val result = (answer as OneRegisterInstruction).registerA
    if (at in builder.jumpTargets()) refuse("$where jumps to its \"$FOLLOW_BACK_TYPE\" load")
    val flow = ControlFlow.of(builder)
    val skip = flow.normal[at - 1].first()

    val hash = (at + 1 until code.size).firstOrNull { code[it].methodText() == HASH_CODE }
        ?: refuse("$where doesn't switch on the section's type after loading \"$FOLLOW_BACK_TYPE\"")
    val literal = FOLLOW_BACK_TYPE.hashCode()
    if ((hash + 1 until minOf(code.size, hash + SWITCH_REACH)).none { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == literal }) {
        refuse("$where's switch on the section's type doesn't compare it with \"$FOLLOW_BACK_TYPE\"")
    }
    val section = code[hash].namedRegisters().single()
    if (!builder.holdsStringOnEveryPath(flow, at, section)) {
        refuse("$where's v$section doesn't hold a String on every path into its \"$FOLLOW_BACK_TYPE\" load")
    }
    return FollowBackSectionSite(
        builder.definingClass, builder.name, builder.parameterTypes.map(CharSequence::toString), builder.returnType,
        at, section, result, skip,
    )
}

/**
 * [findFollowBackSection], or null with a warning in the patch log when the build isn't one it can
 * read, so the rest of the patch still goes in, the older reply's hook included.
 */
internal fun BytecodePatchContext.findFollowBackSectionOrWarn(): FollowBackSectionSite? = try {
    findFollowBackSection()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding the newer notifications' People you don't follow back section.")
    null
}

/** How many register copies [holdsStringOnEveryPath] follows back before it gives up. */
private const val COPY_DEPTH = 4

/**
 * Whether [register] holds a String, or nothing, on entry to [index] on every path into it. Walks back
 * over every edge, a branch's, a goto's, a switch's and a handler's, to each write that can reach
 * [index], and takes only a const-string, a check-cast to String, a read of a String field, the result
 * of a call answering a String, or a move-object from a register that holds a String there by the same
 * test. A path that
 * gets back to the method's start without a write needs a String parameter in that register. A handler
 * gets what was there before the instruction that threw, so a write is skipped on that edge.
 */
private fun Method.holdsStringOnEveryPath(flow: ControlFlow, index: Int, register: Int, depth: Int = COPY_DEPTH): Boolean {
    if (depth < 0) return false
    val code = flow.instructions
    val into = Array(code.size) { mutableListOf<Pair<Int, Boolean>>() }
    code.indices.forEach { from ->
        flow.normal[from].forEach { into[it] += from to false }
        flow.exceptional[from].forEach { into[it] += from to true }
    }
    val queued = BooleanArray(code.size)
    val pending = ArrayDeque(listOf(index))
    queued[index] = true
    val writes = sortedSetOf<Int>()
    var fromStart = false
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == 0) fromStart = true
        for ((from, thrown) in into[at]) {
            if (!thrown && code[from].writes(register)) {
                writes += from
            } else if (!queued[from]) {
                queued[from] = true
                pending += from
            }
        }
    }
    if (fromStart && parameterType(register) != STRING) return false
    return writes.all { at ->
        val write = code[at]
        when (write.opcode) {
            Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> true
            Opcode.CHECK_CAST -> ((write as ReferenceInstruction).reference as TypeReference).type == STRING
            Opcode.MOVE_RESULT_OBJECT -> ((code.getOrNull(at - 1) as? ReferenceInstruction)?.reference as? MethodReference)?.returnType == STRING
            Opcode.IGET_OBJECT, Opcode.SGET_OBJECT -> ((write as ReferenceInstruction).reference as FieldReference).type == STRING
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 ->
                holdsStringOnEveryPath(flow, at, (write as TwoRegisterInstruction).registerB, depth - 1)
            else -> false
        }
    }
}

/** Whether this instruction writes [register], a wide write into the one below it included. */
private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (destination == register || (opcode.setsWideRegister() && destination + 1 == register))
}

/** The declared type of the parameter held in [register] on entry, or null when it holds none. */
private fun Method.parameterType(register: Int): String? {
    val wide = setOf("J", "D")
    val types = parameterTypes.map(CharSequence::toString)
    // Parameters fill the last registers, after this for an instance method.
    var next = implementation!!.registerCount - types.sumOf { if (it in wide) 2 else 1 }
    for (type in types) {
        if (next == register) return type
        next += if (type in wide) 2 else 1
    }
    return null
}

/**
 * Asks [SUGGESTION_SECTION] about the section right after the rows check passes, before the header
 * goes in, with its type: People you don't follow back's, Suggested for you's or another (#161, #112).
 * A yes takes the rows check's own way past the section, with its answer register holding the same
 * nonzero it would for no rows, so no header, rows or footer are added and nothing is left for a gap.
 * A no leaves that register 0, as the check left it. Follow notifications go on as they were.
 */
internal fun BytecodePatchContext.hideFollowBackSection(site: FollowBackSectionSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val code = method.implementation!!.instructions.toList()
    if (stringLoadedAt(code, site.at) != FOLLOW_BACK_TYPE || code[site.at - 1].opcode != Opcode.IF_NEZ) {
        refuse("${site.type}->${site.name} changed before its \"$FOLLOW_BACK_TYPE\" section could be hooked")
    }
    method.addInstructionsWithLabels(
        site.at,
        """
            invoke-static/range { v${site.section} .. v${site.section} }, $SUGGESTION_SECTION
            move-result v${site.answer}
            if-nez v${site.answer}, :skip
        """,
        ExternalLabel("skip", method.getInstruction(site.skip)),
    )
}

private fun Instruction?.methodText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
