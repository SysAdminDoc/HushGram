/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.time

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val POST_TIME_PATCH = "Show a post's exact time"
internal const val POST_TIME = "$EXTENSION_PACKAGE/feed/PostTime;"
internal const val CONTEXT = "Landroid/content/Context;"
internal const val STRING = "Ljava/lang/String;"
internal const val OBJECT = "Ljava/lang/Object;"
internal const val LONG_BOX = "Ljava/lang/Long;"
internal const val RESOURCES = "Landroid/content/res/Resources;"
internal const val INTEGER = "Ljava/lang/Integer;"

/** The hooks that take the place of Instagram's call, for a time given as a double and as a long. */
internal const val TIME_AS_DOUBLE = "$POST_TIME->time($OBJECT${CONTEXT}D)$STRING"
internal const val TIME_AS_LONG = "$POST_TIME->time($OBJECT${CONTEXT}J)$STRING"

/**
 * The hook for a time Instagram writes from the resources rather than a context, on the static
 * call's own arguments: the resources, the unit, the formatter, the style, the time and a flag.
 * The flag is taken as an int, never a boolean: a register Instagram's code types as int fails
 * verification handed to a Z parameter, and the stub hands it on as Instagram's boolean.
 */
internal const val TIME_FROM_RESOURCES = "$POST_TIME->time($RESOURCES$OBJECT$OBJECT${INTEGER}DI)$STRING"

/** The stubs the patch fills with Instagram's own call, one for each of the three shapes. */
internal const val INSTAGRAM_TIME = "instagramTime"

/** The relative formatter's core writes a time more than a week back as a date with this pattern. */
internal const val LONG_AGO_PATTERN = "MMMM d"

/** What a feed post's footer builder traces as it builds the footer, the time with it. */
internal const val FEED_FOOTER = "CoalescedFooterUseCase#getUiState"

/**
 * What the builder of the line under a feed post's comments traces. 452 moved the post's second
 * time out of the footer builder into this one, which the footer builder calls.
 */
internal const val FEED_LABEL = "LabelBelowCommentUseCase#getUiState"

/** How Compose names the comment header (author, time and badges), less the line number. */
internal const val COMMENT_HEADER = "com.instagram.comments.mvvm.view.compose.CommentAuthorHeader ("

/** The test tag of a comment row's time, in the row's render. */
internal const val COMMENT_ROW_TIME = "row_comment_textview_time_ago"

/**
 * The core of Instagram's relative time formatter: a static method of the formatter's class taking
 * the resources, the unit to start from, the formatter itself, the style, the time and now as
 * doubles and four flags, which writes an old time as a date.
 */
internal object RelativeTimeFingerprint : Fingerprint(
    returnType = STRING,
    parameters = listOf("Landroid/content/res/Resources;", "L", "L", "Ljava/lang/Integer;", "D", "D", "Z", "Z", "Z", "Z"),
    strings = listOf(LONG_AGO_PATTERN),
    custom = { method, classDef ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes[2].toString() == classDef.type
    },
)

/**
 * Shows the date and time a post went up, under it in the feed, and when each comment was written,
 * instead of how long ago. In the default selection with its switch off: Instagram's "3 hours
 * ago" is its own design, so the date is the user's pick.
 */
@Suppress("unused")
val showPostTimePatch = bytecodePatch(
    name = "Show a post's exact time",
    description = "Shows the date and time a post went up, like Oct 2, 3:45 PM, instead of how long ago. It does " +
        "the same for comments. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("postTime")
        showPostTime(findPostTimes())
        enableStatus("postTime")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$POST_TIME_PATCH: $why")

/** How a call hands the time over: with a context as a double or a long, or with the resources. */
internal enum class TimeShape { DOUBLE, LONG, RESOURCES }

/** One call to the formatter that a post's or comment's time goes through, and what it called. */
internal class PostTimeSite(val method: Method, val index: Int, val called: MethodReference) {
    val shape get() = when {
        called.parameterTypes.first().toString() == RESOURCES -> TimeShape.RESOURCES
        called.parameterTypes.last().toString() == "J" -> TimeShape.LONG
        else -> TimeShape.DOUBLE
    }
}

/** The formatter, the calls taking its place, and the extension's three stubs. */
internal class PostTimes(
    val formatter: String,
    val feed: List<PostTimeSite>,
    val header: List<PostTimeSite>,
    val row: List<PostTimeSite>,
    val asDouble: MutableMethod,
    val asLong: MutableMethod,
    val fromResources: MutableMethod,
) {
    val sites get() = feed + header + row
}

/**
 * Finds every call this patch replaces and checks all of it before anything changes:
 *  - the relative formatter, the class of the one core method [RelativeTimeFingerprint] finds;
 *  - the feed: the one method loading [FEED_FOOTER] that calls the formatter, and the one loading
 *    [FEED_LABEL] that does, the same method on 453, whose calls are taken once;
 *  - the comment header: the one method holding a string starting with [COMMENT_HEADER]. Up to 451
 *    it called the formatter itself. From 452 its callers hand it the time's formatting as a
 *    lambda, so the calls are the formatter's static resources overload in the classes those
 *    callers create;
 *  - the comment row: the one class whose render loads [COMMENT_ROW_TIME] and that keeps one Long,
 *    the comment's time, and each call, wherever it is, whose time is that Long read and unboxed in
 *    straight code just before it.
 * A formatter call here is one answering a String from a context and a time, as a double or a long,
 * or from the resources, a unit, the formatter, a style, the time as a double and a flag.
 * Each shape must reach one formatting of Instagram's, which its stub is filled with.
 */
internal fun BytecodePatchContext.findPostTimes(): PostTimes {
    val core = uniqueMethod(POST_TIME_PATCH, "relative time formatter holding \"$LONG_AGO_PATTERN\"", RelativeTimeFingerprint)
    val formatter = core.definingClass

    fun Method.formatterCalls(): List<PostTimeSite> = code().mapIndexedNotNull { at, instruction ->
        val call = instruction.call() ?: return@mapIndexedNotNull null
        val shape = call.parameterTypes.map(CharSequence::toString)
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapIndexedNotNull null
        if (call.definingClass != formatter || call.returnType != STRING) return@mapIndexedNotNull null
        if (shape != listOf(CONTEXT, "D") && shape != listOf(CONTEXT, "J")) return@mapIndexedNotNull null
        PostTimeSite(this, at, call)
    }

    fun Method.resourcesCalls(): List<PostTimeSite> = code().mapIndexedNotNull { at, instruction ->
        val call = instruction.call() ?: return@mapIndexedNotNull null
        val shape = call.parameterTypes.map(CharSequence::toString)
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapIndexedNotNull null
        if (call.definingClass != formatter || call.returnType != STRING || shape.size != 6) return@mapIndexedNotNull null
        if (shape[0] != RESOURCES || !shape[1].startsWith("L") || shape[2] != formatter || shape.drop(3) != listOf(INTEGER, "D", "Z")) {
            return@mapIndexedNotNull null
        }
        PostTimeSite(this, at, call)
    }

    fun one(what: String, value: String): List<PostTimeSite> {
        val methods = classesLoadingString(value).flatMap { it.methods }.filter { loadsString(it, value) && it.formatterCalls().isNotEmpty() }
        val method = methods.singleOrNull()
            ?: refuse("expected one $what asking $formatter for a time, found ${methods.size}")
        return method.formatterCalls()
    }

    // 453 builds the label inside the footer builder, so both strings find one method and its calls once.
    val feed = (
        one("feed footer builder loading \"$FEED_FOOTER\"", FEED_FOOTER) +
            one("feed label builder loading \"$FEED_LABEL\"", FEED_LABEL)
        ).distinctBy { Triple(it.method.definingClass, it.method.name + it.method.parameterTypes + it.method.returnType, it.index) }
    val headers = classDefByStrings(COMMENT_HEADER, StringComparisonType.STARTS_WITH).filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .flatMap { it.methods }.filter { method -> method.code().any { (it.reference() as? StringReference)?.string?.startsWith(COMMENT_HEADER) == true } }
    val headerMethod = headers.singleOrNull()
        ?: refuse("expected one comment header holding \"$COMMENT_HEADER\", found ${headers.size}")
    val header = headerMethod.formatterCalls().ifEmpty {
        val callers = classesCalling(headerMethod.definingClass, headerMethod.name).flatMap { it.methods }.filter { it.calls(headerMethod) }
        val created = callers.flatMap { caller ->
            caller.code().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull { (it.reference() as? TypeReference)?.type }
        }.distinct().filter { it != formatter }
        created.mapNotNull { classDefByOrNull(it) }.flatMap { it.methods }.flatMap { it.resourcesCalls() }
    }
    if (header.isEmpty()) refuse("nothing asks $formatter for the time in ${headerMethod.definingClass}->${headerMethod.name} or the lambdas its callers make")

    // 450's 385611395 and 385611400 ask a string pool for the row's tag, so the class holding it
    // there is the pool and a static helper, neither of which keeps a time (#77).
    val rows = classesLoadingString(COMMENT_ROW_TIME).filter { row -> row.longFields().size == 1 }
    val row = rows.singleOrNull()
        ?: refuse("expected one comment row loading \"$COMMENT_ROW_TIME\" with one Long, found ${rows.size}")
    val time = row.longFields().single()
    val rowSites = classesAccessing(row.type, time.name, Opcode.IGET_OBJECT).flatMap { it.methods }
        .flatMap { method -> method.formatterCalls().filter { method.timeReadFrom(it.index, time) } }
    if (rowSites.isEmpty()) refuse("nothing asks $formatter for the time in ${row.type}->${time.name}")

    val all = feed + header + rowSites
    for (shape in all.groupBy { it.shape }.values) {
        val called = shape.map { it.called.toString() }.distinct()
        if (called.size != 1) refuse("the times of one shape reach ${called.size} formatting methods: ${called.joinToString()}")
    }

    val extension = mutableClassDefBy(POST_TIME)
    fun hook(reference: String) {
        if (extension.methods.none { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == reference &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ) refuse("the extension has no public static $reference")
    }
    hook(TIME_AS_DOUBLE)
    hook(TIME_AS_LONG)
    hook(TIME_FROM_RESOURCES)
    fun stub(vararg parameters: String): MutableMethod = extension.methods.singleOrNull {
        it.name == INSTAGRAM_TIME && it.returnType == STRING && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters.toList()
    } ?: refuse("$POST_TIME has no static $STRING $INSTAGRAM_TIME(${parameters.joinToString("")})")
    return PostTimes(
        formatter, feed, header, rowSites,
        stub(OBJECT, CONTEXT, "D"), stub(OBJECT, CONTEXT, "J"), stub(RESOURCES, OBJECT, OBJECT, INTEGER, "D", "Z"),
    )
}

/** The hook that takes the place of a call of this shape. */
private val TimeShape.hook get() = when (this) {
    TimeShape.DOUBLE -> TIME_AS_DOUBLE
    TimeShape.LONG -> TIME_AS_LONG
    TimeShape.RESOURCES -> TIME_FROM_RESOURCES
}

/**
 * Fills each stub with the formatting its shape's calls reached, then puts the matching hook in
 * place of every call, on the same registers: the formatter, the context and the time, or the
 * static call's resources, unit, formatter, style, time and flag.
 */
internal fun BytecodePatchContext.showPostTime(found: PostTimes) {
    for ((shape, sites) in found.sites.groupBy { it.shape }) {
        val called = sites.first().called
        when (shape) {
            TimeShape.DOUBLE, TimeShape.LONG -> (if (shape == TimeShape.LONG) found.asLong else found.asDouble).addInstructions(
                0,
                """
                    check-cast p0, ${found.formatter}
                    invoke-virtual { p0, p1, p2, p3 }, $called
                    move-result-object p0
                    return-object p0
                """,
            )
            TimeShape.RESOURCES -> found.fromResources.addInstructions(
                0,
                """
                    check-cast p1, ${called.parameterTypes[1]}
                    check-cast p2, ${found.formatter}
                    invoke-static/range { p0 .. p6 }, $called
                    move-result-object p0
                    return-object p0
                """,
            )
        }
    }
    for (site in found.sites) {
        val method = mutableClassDefBy(site.method.definingClass).methods.single {
            it.name == site.method.name && it.returnType == site.method.returnType &&
                it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString)
        }
        val call = method.implementation!!.instructions[site.index]
        val hook = site.shape.hook
        method.replaceInstruction(site.index, when (call) {
            is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
                .take(call.registerCount).joinToString(prefix = "invoke-static { ", postfix = " }, $hook") { "v$it" }
            is RegisterRangeInstruction ->
                "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }, $hook"
            else -> refuse("${method.definingClass}->${method.name} asks for a time in a form it can't replace")
        })
    }
}

/** The instance fields of this class holding a boxed Long. */
private fun ClassDef.longFields() = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LONG_BOX }

/**
 * Whether the time the formatter call at [call] hands over is [field]'s Long, read, unboxed and,
 * for a double, converted in the straight code just before the call, with nothing jumping in
 * between to bring another value.
 */
private fun Method.timeReadFrom(call: Int, field: FieldReference): Boolean {
    val code = code()
    val time = code[call].arguments().getOrNull(2) ?: return false
    var at = lastWrite(code, call, time, pair = true) ?: return false
    var unboxed = time
    if (code[at].opcode == Opcode.LONG_TO_DOUBLE) {
        val conversion = code[at] as TwoRegisterInstruction
        if (conversion.registerA != time) return false
        unboxed = conversion.registerB
        at = lastWrite(code, at, unboxed, pair = true) ?: return false
    }
    val result = code[at]
    if (result.opcode != Opcode.MOVE_RESULT_WIDE || (result as OneRegisterInstruction).registerA != unboxed || at == 0) return false
    val unbox = code[at - 1].call()
    if (unbox == null || unbox.name != "longValue" || unbox.returnType != "J" ||
        unbox.definingClass !in setOf("Ljava/lang/Number;", LONG_BOX)
    ) return false
    val boxed = code[at - 1].arguments().singleOrNull() ?: return false
    val readAt = lastWrite(code, at - 1, boxed, pair = false) ?: return false
    val read = code[readAt]
    val readField = (read as? ReferenceInstruction)?.reference as? FieldReference
    if (read.opcode != Opcode.IGET_OBJECT || readField == null || (read as TwoRegisterInstruction).registerA != boxed ||
        readField.definingClass != field.definingClass || readField.name != field.name || readField.type != field.type
    ) return false
    return jumpTargets().none { it in (readAt + 1)..call }
}

/**
 * The last instruction before [before] writing [register], or with [pair] the register pair
 * starting there, or null. A wide write covers its register and the next.
 */
private fun lastWrite(code: List<Instruction>, before: Int, register: Int, pair: Boolean): Int? =
    (before - 1 downTo 0).firstOrNull { at ->
        val instruction = code[at]
        val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return@firstOrNull false
        val written = if (instruction.opcode.setsWideRegister()) setOf(destination, destination + 1) else setOf(destination)
        val wanted = if (pair) setOf(register, register + 1) else setOf(register)
        instruction.opcode.setsRegister() && written.any { it in wanted }
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.call() = reference() as? MethodReference

/** Whether this method calls [target]. */
private fun Method.calls(target: Method) = code().any { instruction ->
    val call = instruction.call()
    call != null && call.definingClass == target.definingClass && call.name == target.name && call.returnType == target.returnType &&
        call.parameterTypes.map(CharSequence::toString) == target.parameterTypes.map(CharSequence::toString)
}

/** The registers an invoke hands over, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}
