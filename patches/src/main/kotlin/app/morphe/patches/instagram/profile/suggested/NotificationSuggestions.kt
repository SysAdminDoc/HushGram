/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested people on profiles"

internal const val NOTIFICATION_PARSED = "$PROFILE_SUGGESTIONS->notificationParsed(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val KEEP_NOTIFICATION = "$PROFILE_SUGGESTIONS->keepNotification(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val FEATURED_ROWS = "$PROFILE_SUGGESTIONS->featuredRows(Ljava/util/List;)Ljava/util/List;"
internal const val FEATURED_HEADER = "$PROFILE_SUGGESTIONS->featuredHeader(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val SKIPS_FEATURED_ROW = "$PROFILE_SUGGESTIONS->skipsFeaturedRow(Ljava/lang/String;)Z"
internal const val FEATURED_DOT = "$PROFILE_SUGGESTIONS->featuredDot(II)I"

/** The reply's keys of the Featured section at the top of the notifications (#259): its rows, and its header. */
internal const val FEATURED_ROWS_KEY = "priority_stories"
internal const val FEATURED_HEADER_KEY = "priority_header"

/**
 * The keys of a notification row's kind in the server's reply: the name of the kind, and its number.
 * They're field names of the reply, so Instagram keeps them, and only the row parser loads both.
 */
internal const val NOTIF_NAME_KEY = "notif_name"
internal const val STORY_TYPE_KEY = "story_type"

/** Keys only the reply holding the notification rows loads, in the parser that fills its lists. */
internal const val NEW_ROWS_KEY = "new_stories"
internal const val OLD_ROWS_KEY = "old_stories"

private const val PARSE = "unsafeParseFromJson"
private const val PARSE_PARSER = "parseFromJsonParser"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/** How far after the name key's load its value's read may land. */
private const val NAME_READ_REACH = 6

/** How far after the Featured header's key the reply may keep the header. */
private const val HEADER_KEEP_REACH = 16

private const val LIST = "Ljava/util/List;"

/**
 * The row parser's last steps: its class and parameters, where it hands the row back, the register
 * holding the row there, a free register next to it, and the field the parser keeps the kind's name in.
 */
internal class NotificationRowSite(
    val type: String,
    val parameters: List<String>,
    val returnAt: Int,
    val row: Int,
    val name: Int,
    val nameField: String,
)

/** The reply parser's adds of a parsed row to its lists: its class, parameters, and each null check's index and register. */
internal class NotificationListSite(val type: String, val parameters: List<String>, val checks: List<Pair<Int, Int>>, val rowParser: String)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the notifications' row parser (`LX/02F9` on 385713073): the one [PARSE] answering an object
 * that loads both [NOTIF_NAME_KEY] and [STORY_TYPE_KEY]. After the name key it reads the value into a
 * register, which it later writes into a String field of the row it makes, and it hands that row back
 * with one return-object. The hook reads the field back right before that return, so the row's register
 * has to hold the row it made on every path there: nothing outside the stretch from the row's
 * new-instance to the return may jump into it, and nothing in it may write the register. Fails before
 * anything changes when any of that isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findNotificationRows(): NotificationRowSite {
    val parsers = classesLoadingString(NOTIF_NAME_KEY).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == PARSE && method.returnType == OBJECT && method.parameterTypes.size == 1 &&
                loadsString(method, NOTIF_NAME_KEY) && loadsString(method, STORY_TYPE_KEY)
        }
    }
    val parser = parsers.singleOrNull()
        ?: refuse("expected one row parser loading \"$NOTIF_NAME_KEY\" and \"$STORY_TYPE_KEY\", found ${parsers.size}")
    val where = "${parser.definingClass}->$PARSE"
    val code = parser.implementation!!.instructions.toList()

    val key = code.indices.filter { stringLoadedAt(code, it) == NOTIF_NAME_KEY }.singleOrNull()
        ?: refuse("$where doesn't load \"$NOTIF_NAME_KEY\" once")
    val read = (key + 1 until minOf(code.size, key + NAME_READ_REACH)).firstOrNull {
        code[it].opcode == Opcode.MOVE_RESULT_OBJECT && (code[it - 1].reference() as? MethodReference)?.returnType == STRING
    } ?: refuse("$where doesn't read a String right after \"$NOTIF_NAME_KEY\"")
    val value = (code[read] as OneRegisterInstruction).registerA

    val keeps = code.indices.filter {
        val field = code[it].reference() as? FieldReference
        code[it].opcode == Opcode.IPUT_OBJECT && field?.type == STRING && (code[it] as TwoRegisterInstruction).registerA == value
    }
    val keep = keeps.singleOrNull() ?: refuse("expected one write of \"$NOTIF_NAME_KEY\" into the row in $where, found ${keeps.size}")
    val field = code[keep].reference() as FieldReference
    val row = (code[keep] as TwoRegisterInstruction).registerB

    val made = (keep downTo 0).firstOrNull { code[it].writes(row) }
        ?: refuse("$where doesn't make the row it keeps \"$NOTIF_NAME_KEY\" in")
    if (code[made].opcode != Opcode.NEW_INSTANCE || (code[made].reference() as TypeReference).type != field.definingClass) {
        refuse("$where keeps \"$NOTIF_NAME_KEY\" in something other than a new ${field.definingClass}")
    }
    val returns = code.indices.filter {
        code[it].opcode == Opcode.RETURN_OBJECT && (code[it] as OneRegisterInstruction).registerA == row && it > keep
    }
    val returnAt = returns.singleOrNull() ?: refuse("expected one return of the row after $where keeps \"$NOTIF_NAME_KEY\", found ${returns.size}")
    val flow = ControlFlow.of(parser)
    val stretch = made + 1..returnAt
    val from = code.indices.firstOrNull { at ->
        at !in made..returnAt && (flow.normal[at] + flow.exceptional[at]).any { it in stretch }
    }
    if (from != null) refuse("$where jumps from $from into the stretch between making the row and handing it back")
    if (stretch.any { it < returnAt && code[it].writes(row) }) refuse("$where writes v$row again before handing the row back")

    // Every register but the row's is free at a return: the next one up holds the name for the call.
    val name = row + 1
    if (name >= parser.implementation!!.registerCount || name > 15) refuse("$where has no free register next to v$row")
    return NotificationRowSite(
        parser.definingClass, parser.parameterTypes.map(CharSequence::toString), returnAt, row, name,
        "${field.definingClass}->${field.name}:${field.type}",
    )
}

/**
 * Hands the row and its kind's name to [NOTIFICATION_PARSED] right before the parser gives the row
 * back, on the return's own label so the branches that skip the last optional fields run it too.
 */
internal fun BytecodePatchContext.noteNotificationRows(site: NotificationRowSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == PARSE && it.returnType == OBJECT && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val at = method.implementation!!.instructions[site.returnAt]
    if (at.opcode != Opcode.RETURN_OBJECT || (at as OneRegisterInstruction).registerA != site.row) {
        refuse("${site.type}->$PARSE changed before its rows could be hooked")
    }
    method.addInstructionsAtControlFlowLabel(
        site.returnAt,
        """
            iget-object v${site.name}, v${site.row}, ${site.nameField}
            invoke-static/range { v${site.row} .. v${site.name} }, $NOTIFICATION_PARSED
        """,
    )
}

/**
 * Finds the reply parser that fills the notifications' lists (`LX/01DS` on 385713073): the one [PARSE]
 * answering an object that loads [NEW_ROWS_KEY] and [OLD_ROWS_KEY]. Each list's loop reads the row
 * parser's instance, parses a row with it, moves the answer into a register and skips it when it's
 * null. Every read of the row parser's instance there has to be one of those, with nothing jumping to
 * the null check, or it's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findNotificationLists(rows: NotificationRowSite): NotificationListSite {
    val replies = classesLoadingString(OLD_ROWS_KEY).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == PARSE && method.returnType == OBJECT && method.parameterTypes.size == 1 &&
                loadsString(method, NEW_ROWS_KEY) && loadsString(method, OLD_ROWS_KEY)
        }
    }
    val reply = replies.singleOrNull()
        ?: refuse("expected one reply parser loading \"$NEW_ROWS_KEY\" and \"$OLD_ROWS_KEY\", found ${replies.size}")
    val where = "${reply.definingClass}->$PARSE"
    val code = reply.implementation!!.instructions.toList()
    val targets = reply.jumpTargets()
    val reads = code.indices.filter {
        code[it].opcode == Opcode.SGET_OBJECT && (code[it].reference() as FieldReference).let { field ->
            field.definingClass == rows.type && field.type == rows.type
        }
    }
    if (reads.size < 2) refuse("$where reads the row parser ${reads.size} times, expected one for each list")
    val checks = reads.map { at ->
        val instance = (code[at] as OneRegisterInstruction).registerA
        val parse = code.getOrNull(at + 1)
        val call = parse?.reference() as? MethodReference
        val parsed = parse?.opcode == Opcode.INVOKE_VIRTUAL && call?.name == PARSE_PARSER && call.returnType == OBJECT &&
            (parse as FiveRegisterInstruction).registerC == instance
        val moved = code.getOrNull(at + 2)
        val check = code.getOrNull(at + 3)
        if (!parsed || moved?.opcode != Opcode.MOVE_RESULT_OBJECT || check?.opcode != Opcode.IF_EQZ ||
            (check as OneRegisterInstruction).registerA != (moved as OneRegisterInstruction).registerA
        ) {
            refuse("$where's read of the row parser at $at isn't a parse with a null check after it")
        }
        if (at + 3 in targets) refuse("$where jumps to the null check at ${at + 3}")
        at + 3 to (moved as OneRegisterInstruction).registerA
    }
    return NotificationListSite(reply.definingClass, reply.parameterTypes.map(CharSequence::toString), checks, rows.type)
}

/**
 * Hands each parsed row to [KEEP_NOTIFICATION] right before the list's null check, the answer back in
 * the row's register. A null from it takes the list's own way for a row that didn't parse, so the
 * row is never added and nothing is left for a gap. Later checks first, so the earlier indexes hold.
 */
internal fun BytecodePatchContext.keepNotificationRows(site: NotificationListSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == PARSE && it.returnType == OBJECT && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    // Other hooks may have inserted instructions in this parser since the lists were found.
    val code = method.implementation!!.instructions.toList()
    val checks = code.indices.mapNotNull { at ->
        val load = code.getOrNull(at) ?: return@mapNotNull null
        val field = (load as? ReferenceInstruction)?.reference as? FieldReference
        if (load.opcode != Opcode.SGET_OBJECT || field?.definingClass != site.rowParser || field.type != site.rowParser) return@mapNotNull null
        val instance = (load as OneRegisterInstruction).registerA
        val parse = code.getOrNull(at + 1) ?: return@mapNotNull null
        val call = parse.reference() as? MethodReference
        if (parse.opcode != Opcode.INVOKE_VIRTUAL || call?.name != PARSE_PARSER || call.returnType != OBJECT ||
            (parse as FiveRegisterInstruction).registerC != instance) return@mapNotNull null
        val moved = code.getOrNull(at + 2) ?: return@mapNotNull null
        val check = code.getOrNull(at + 3) ?: return@mapNotNull null
        if (moved.opcode != Opcode.MOVE_RESULT_OBJECT || check.opcode != Opcode.IF_EQZ ||
            (check as OneRegisterInstruction).registerA != (moved as OneRegisterInstruction).registerA) return@mapNotNull null
        at + 3 to (moved as OneRegisterInstruction).registerA
    }
    if (checks.size != site.checks.size) refuse("${site.type}->$PARSE changed before its lists could be hooked: found ${checks.size} checks, expected ${site.checks.size}")
    for ((at, register) in checks.sortedByDescending { it.first }) {
        method.addInstructions(
            at,
            """
                invoke-static/range { v$register .. v$register }, $KEEP_NOTIFICATION
                move-result-object v$register
            """,
        )
    }
}

/**
 * Where the reply keeps its Featured section: the store of its rows' list and of its header, each with
 * the register it stores, and the header's type.
 */
internal class FeaturedSite(val rowsAt: Int, val rows: Int, val headerAt: Int, val header: Int, val headerType: String)

/**
 * Finds where the notifications' reply keeps its Featured section (#259), on 452's 385810270 the
 * stores into `LX/01Z1.A0V` and `A0G`. Once it has read every key, the reply names each list of rows
 * it kept, reading the list's field right before loading its key, so the field read before
 * [FEATURED_ROWS_KEY] is the Featured rows', and the one store into that field is where the list is
 * kept, at the end of its loop. The header is the reply's first object store after it compares the
 * key with [FEATURED_HEADER_KEY], with no goto between, into a field nothing else stores into. Reads
 * [reply] as it stands, so the hook can read it again after the row hooks moved its indexes.
 */
internal fun BytecodePatchContext.featuredSectionIn(reply: Method): FeaturedSite {
    val where = "${reply.definingClass}->$PARSE"
    val code = reply.implementation!!.instructions.toList()
    val tags = code.indices.filter { at ->
        val read = code.getOrNull(at - 1)
        stringLoadedAt(code, at) == FEATURED_ROWS_KEY && read?.opcode == Opcode.IGET_OBJECT &&
            (read.reference() as FieldReference).type == LIST
    }
    val tag = tags.singleOrNull() ?: refuse("expected one read of a list right before \"$FEATURED_ROWS_KEY\" in $where, found ${tags.size}")
    val rowsField = (code[tag - 1].reference() as FieldReference).text()
    val rowsAt = code.indices.filter { code[it].stores(rowsField) }.singleOrNull()
        ?: refuse("expected one store of the Featured rows in $where")
    val store = code[rowsAt] as TwoRegisterInstruction

    val key = code.indices.filter { stringLoadedAt(code, it) == FEATURED_HEADER_KEY }.singleOrNull()
        ?: refuse("$where doesn't load \"$FEATURED_HEADER_KEY\" once")
    val headerAt = (key + 1 until minOf(code.size, key + HEADER_KEEP_REACH)).asSequence()
        .takeWhile { code[it].opcode !in GOTOS }
        .firstOrNull { code[it].opcode == Opcode.IPUT_OBJECT && (code[it] as TwoRegisterInstruction).registerB == store.registerB }
        ?: refuse("$where doesn't keep the Featured header right after \"$FEATURED_HEADER_KEY\"")
    val headerField = code[headerAt].reference() as FieldReference
    if (code.indices.count { code[it].stores(headerField.text()) } != 1) refuse("$where keeps the Featured header more than once")
    if (!headerField.type.startsWith("L")) refuse("$where keeps the Featured header as a ${headerField.type}")
    val header = (code[headerAt] as TwoRegisterInstruction).registerA
    if (store.registerA > 255 || header > 255) refuse("$where keeps the Featured section past v255")
    return FeaturedSite(rowsAt, store.registerA, headerAt, header, headerField.type)
}

/** The Featured section in the reply [lists] were found in, or null with a warning, so the rest still goes in. */
internal fun BytecodePatchContext.findFeaturedSectionOrWarn(lists: NotificationListSite): FeaturedSite? = try {
    featuredSectionIn(classDefBy(lists.type).methods.single { it.isReply(lists) })
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without hiding Featured in notifications.")
    null
}

/**
 * Hands the Featured rows' list to [FEATURED_ROWS] and the header to [FEATURED_HEADER] right before
 * the reply keeps each, the answer back in the same register, on each store's own label so the loop's
 * way out runs it too. The header's answer is cast back to its type, a null passing as the reply's own
 * no header. Reads the reply again first, since the row hooks went in ahead of it.
 */
internal fun BytecodePatchContext.hideFeaturedSection(lists: NotificationListSite) {
    val method = mutableClassDefBy(lists.type).methods.single { it.isReply(lists) }
    val site = featuredSectionIn(method)
    val rows = site.rowsAt to """
        invoke-static/range { v${site.rows} .. v${site.rows} }, $FEATURED_ROWS
        move-result-object v${site.rows}
    """
    val header = site.headerAt to """
        invoke-static/range { v${site.header} .. v${site.header} }, $FEATURED_HEADER
        move-result-object v${site.header}
        check-cast v${site.header}, ${site.headerType}
    """
    for ((at, smali) in listOf(rows, header).sortedByDescending { it.first }) {
        method.addInstructionsAtControlFlowLabel(at, smali)
    }
}

private fun Method.isReply(lists: NotificationListSite) =
    name == PARSE && returnType == OBJECT && parameterTypes.map(CharSequence::toString) == lists.parameters

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

private fun FieldReference.text() = "$definingClass->$name:$type"

private fun Instruction.stores(field: String) =
    opcode == Opcode.IPUT_OBJECT && (reference() as FieldReference).text() == field

/**
 * The row parser and the reply's lists, or null with a warning in the patch log when the build isn't
 * one they can be read in, so the rest of the patch still goes in. Both or neither: a row marked with
 * nothing to drop it, or a drop with nothing marked, would only count.
 */
internal fun BytecodePatchContext.findNotificationSuggestionsOrWarn(): Pair<NotificationRowSite, NotificationListSite>? = try {
    val rows = findNotificationRows()
    rows to findNotificationLists(rows)
} catch (refusal: PatchException) {
    patchLog.warning(
        "${refusal.message}. $PATCH goes in without hiding suggestions in notifications.",
    )
    null
}

private fun BytecodePatchContext.loadsString(method: Method, value: String): Boolean {
    val code = method.implementation?.instructions?.toList().orEmpty()
    return code.indices.any { stringLoadedAt(code, it) == value }
}

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/** Whether this instruction writes [register], a wide write into the one below it included. */
private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    if (this is RegisterRangeInstruction) return false
    return opcode.setsRegister() && (destination == register || (opcode.setsWideRegister() && destination + 1 == register))
}

/**
 * Where the list builder tests a row's section tag against [FEATURED_ROWS_KEY]: the const-string, the
 * register the tag is read into (free there, the read writes it), the row it is read from, the tag's
 * field, and the loop's next-row label the branch right before already goes to when a row built nothing.
 */
internal class FeaturedRowSite(val type: String, val method: Method, val at: Int, val tag: Int, val row: Int, val field: String, val next: Int)

/**
 * Finds the notifications' list builder (#259): the one method that loads [FEATURED_ROWS_KEY], reads a
 * String field of a row and compares the two with `String.equals`. Rows tagged with the Featured key
 * can come from the reply, the cache or a later page, so this one place covers them all. The branch
 * right before it has to go to the loop's `Iterator.hasNext()`, which is where a skipped row goes.
 */
internal fun BytecodePatchContext.findFeaturedRowSkip(): FeaturedRowSite {
    val sites = classesLoadingString(FEATURED_ROWS_KEY).flatMap { classDef ->
        classDef.methods.mapNotNull { method -> featuredRowTest(method) }
    }
    return sites.singleOrNull() ?: refuse("expected one list builder testing a row against \"$FEATURED_ROWS_KEY\", found ${sites.size}")
}

private fun BytecodePatchContext.featuredRowTest(method: Method): FeaturedRowSite? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val hits = code.indices.filter { at ->
        stringLoadedAt(code, at) == FEATURED_ROWS_KEY && code.getOrNull(at + 1)?.opcode == Opcode.IGET_OBJECT &&
            (code[at + 1].reference() as FieldReference).type == STRING &&
            (code.getOrNull(at + 2)?.reference() as? MethodReference)?.let { it.name == "equals" && it.definingClass == STRING } == true
    }
    val at = hits.singleOrNull() ?: return null
    val read = code[at + 1] as TwoRegisterInstruction
    val before = code.getOrNull(at - 1) ?: return null
    if (before.opcode != Opcode.IF_EQZ && before.opcode != Opcode.IF_NEZ) return null
    val next = ControlFlow.of(method).normal[at - 1].firstOrNull() ?: return null
    val step = (code[next].reference() as? MethodReference) ?: return null
    if ((code[next].opcode != Opcode.INVOKE_INTERFACE && code[next].opcode != Opcode.INVOKE_INTERFACE_RANGE) || step.name != "hasNext") return null
    if (read.registerA > 15 || read.registerB > 255) return null
    return FeaturedRowSite(method.definingClass, method, at, read.registerA, read.registerB, (code[at + 1].reference() as FieldReference).text(), next)
}

/** The builder's site, or null with a warning, so the rest of the patch (and the reply hook) still goes in. */
internal fun BytecodePatchContext.findFeaturedRowSkipOrWarn(): FeaturedRowSite? = try {
    findFeaturedRowSkip()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without skipping Featured rows in the list builder.")
    null
}

/**
 * Asks [SKIPS_FEATURED_ROW] about each row's section tag as the builder reads it and, on a yes, goes
 * to the loop's next row before the row is drawn. The tag's register is free there: it is written by
 * the read and the builder's own compare reads it again after.
 */
internal fun BytecodePatchContext.skipFeaturedRows(site: FeaturedRowSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.method.name && it.parameterTypes == site.method.parameterTypes
    }
    val tag = site.tag
    method.addInstructionsAtControlFlowLabel(
        site.at,
        """
            iget-object v$tag, v${site.row}, ${site.field}
            invoke-static { v$tag }, $SKIPS_FEATURED_ROW
            move-result v$tag
            if-nez v$tag, :next
        """,
        ExternalLabel("next", method.getInstruction(site.next)),
    )
}

private const val DOT_KEY = "activity_feed_dot_badge"
private val ACTIVITY_KEYS = listOf("comments", "likes", "comment_likes", "relationships", "usertags")
private const val BADGE_REACH = 4

/**
 * Where the badge counts are built (#189): the first count's boxing, where the hook goes in, the
 * dot's register, the five activity counts' registers, and the free register the sum is made in.
 */
internal class BadgeSite(val type: String, val method: Method, val at: Int, val dot: Int, val activity: List<Int>, val temp: Int)

/**
 * Finds the method that builds the account's badge counts from the server's numbers (LX/04rE.EN9 on
 * 453): the one loading "activity_feed_dot_badge" with "likes", "comments", "comment_likes",
 * "relationships" and "usertags". Each count is boxed right before its key is loaded, so the register
 * of the boxed int is the count's. The hook goes in before the first boxing, where every count is
 * still an int, and sums into the register the first key is loaded into, which nothing reads or
 * writes before that load.
 */
internal fun BytecodePatchContext.findBadgeCounts(): BadgeSite {
    val methods = classesLoadingString(DOT_KEY).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name != "<clinit>" &&
            (listOf(DOT_KEY) + ACTIVITY_KEYS).all { loadsString(method, it) }
        }
    }
    val method = methods.singleOrNull()
        ?: refuse("expected one method building the badge counts from \"$DOT_KEY\" and ${ACTIVITY_KEYS.joinToString()}, found ${methods.size}: ${methods.joinToString { "${it.definingClass}->${it.name}${it.parameterTypes}" }}")
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val boxed = (listOf(DOT_KEY) + ACTIVITY_KEYS).map { key ->
        val load = code.indices.filter { stringLoadedAt(code, it) == key }.singleOrNull()
            ?: refuse("$where doesn't load \"$key\" once")
        val move = code.getOrNull(load - 1)
        val box = code.getOrNull(load - 2)
        val call = (box?.reference() as? MethodReference)
        if (move?.opcode != Opcode.MOVE_RESULT_OBJECT || box == null || box.opcode !in BOXING || call?.name != "valueOf" ||
            call.definingClass != "Ljava/lang/Integer;"
        ) {
            refuse("$where doesn't box the count right before \"$key\"")
        }
        val register = when (box) {
            is FiveRegisterInstruction -> box.registerC
            is RegisterRangeInstruction -> box.startRegister
            else -> refuse("$where boxes \"$key\" in a way this hook can't read")
        }
        Triple(key, load - 2, register)
    }
    val dot = boxed.first().third
    val activity = boxed.drop(1).map { it.third }
    if ((activity + dot).any { it > 255 }) refuse("$where keeps the badge counts past v255")
    if (dot > 15) refuse("$where keeps the dot's count past v15")
    val at = boxed.minOf { it.second }
    val firstLoad = code.indices.first { stringLoadedAt(code, it) == boxed[1].first }
    val temp = (code[firstLoad] as OneRegisterInstruction).registerA
    if (temp > 15 || temp == dot || temp in activity) refuse("$where has no free register for the badge sum")
    val between = at until firstLoad
    if (between.any { code[it].touches(temp) }) refuse("$where uses v$temp before it loads \"${boxed[1].first}\"")
    for ((key, boxAt, register) in boxed) {
        if ((at until boxAt).any { code[it].writes(register) }) refuse("$where writes the count of \"$key\" before boxing it")
    }
    return BadgeSite(method.definingClass, method, at, dot, activity, temp)
}

private val BOXING = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/** Whether this instruction reads or writes [register]. */
private fun Instruction.touches(register: Int): Boolean = when (this) {
    is FiveRegisterInstruction -> (registerCount > 0 && registerC == register) || (registerCount > 1 && registerD == register) ||
        (registerCount > 2 && registerE == register) || (registerCount > 3 && registerF == register) ||
        (registerCount > 4 && registerG == register)
    is RegisterRangeInstruction -> register in startRegister until startRegister + registerCount
    is TwoRegisterInstruction -> registerA == register || registerB == register
    is OneRegisterInstruction -> registerA == register
    else -> false
}

/** The badge site, or null with a warning, so the rest of the patch still goes in. */
internal fun BytecodePatchContext.findBadgeCountsOrWarn(): BadgeSite? = try {
    findBadgeCounts()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in without keeping Featured off the heart's dot.")
    null
}

/**
 * Hands the dot's count and the sum of the five activity counts to [FEATURED_DOT] before the first
 * is boxed, and keeps the answer as the dot's count: zero when the dot has nothing but Featured
 * behind it and the switch is on, the count as it came otherwise.
 */
internal fun BytecodePatchContext.keepFeaturedOffTheDot(site: BadgeSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.method.name && it.parameterTypes == site.method.parameterTypes
    }
    method.addInstructionsAtControlFlowLabel(
        site.at,
        """
            add-int v${site.temp}, v${site.activity.first()}, v${site.activity[1]}
            ${site.activity.drop(2).joinToString("\n") { "add-int v${site.temp}, v${site.temp}, v$it" }}
            invoke-static { v${site.dot}, v${site.temp} }, $FEATURED_DOT
            move-result v${site.dot}
        """,
    )
}
