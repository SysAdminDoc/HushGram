/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.savemenu

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.reel.code
import app.morphe.patches.instagram.download.video.FeedMenu
import app.morphe.patches.instagram.download.video.downloadRow
import app.morphe.patches.instagram.download.video.findFeedMenu
import app.morphe.patches.instagram.download.video.helperAt
import app.morphe.patches.instagram.download.video.mutable
import app.morphe.patches.instagram.download.video.postBridge
import app.morphe.patches.instagram.feed.FeedStateWrite
import app.morphe.patches.instagram.feed.back.enumConstantName
import app.morphe.patches.instagram.feed.comments.COMMENT_COUNT_LABEL
import app.morphe.patches.instagram.feed.feedRowState
import app.morphe.patches.instagram.feed.flagWrites
import app.morphe.patches.instagram.feed.headerline.branchesOnto
import app.morphe.patches.instagram.feed.holdsString
import app.morphe.patches.instagram.feed.isToString
import app.morphe.patches.instagram.feed.prepareFlagWrites
import app.morphe.patches.instagram.feed.printedFlag
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.classesReadingType
import app.morphe.patches.instagram.misc.extension.classesTouching
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.filterReturnAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.share.REPOST_COUNT_LABEL
import app.morphe.patches.instagram.share.SHARE_COUNT_LABEL
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val FEED_SAVE_STATUS = "feedSave"
internal const val SAVE_ROW = "$EXTENSION_PACKAGE/feed/FeedSaveRow;"
internal const val OFFER_SAVE = "$SAVE_ROW->offer(Ljava/lang/Object;Ljava/util/ArrayList;)V"
internal const val ALLOW_SAVE = "$SAVE_ROW->allow(Ljava/util/List;)Ljava/util/List;"
internal const val SETTLE_SAVE = "$SAVE_ROW->settle(Ljava/lang/Object;)V"
internal const val LIKE_COUNT_STATE = "$SAVE_ROW->likeCountState(I)Z"
internal const val COUNT_STATE = "$SAVE_ROW->countState(I)Z"
internal const val LIKES_ROW = "$SAVE_ROW->likesRow(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val LIKE_BUTTON = "$SAVE_ROW->likeButton(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * Strings only the builder of a feed post's row of buttons loads, both of them, as the names of the
 * lambdas it hands the Comment and Share buttons (D#877).
 */
internal const val COMMENT_COMPONENT = "getCommentComponent"
internal const val SHARE_COMPONENT = "getShareComponent"

/** The name of the click area Instagram wraps the Like button in, a constant of its click areas' enum. */
internal const val LIKE_CLICK_AREA = "LIKE_LINK"

/** The label Feed's action-row state prints whether the row shows the like count under (#668). */
internal const val LIKE_COUNT_LABEL = ", shouldShowLikeCountInUfi="

/**
 * The labels the same state prints whether the row shows the comment, share and repost counts
 * under (#762), with what each is called in a warning.
 */
internal val OTHER_COUNT_LABELS = listOf(
    COMMENT_COUNT_LABEL to "comment count",
    SHARE_COUNT_LABEL to "share count",
    REPOST_COUNT_LABEL to "repost count",
)

/** The label a feed post's media state prints the likes row's own state under, "Liked by" and all (#762). */
internal const val LIKES_ROW_LABEL = ", likesRowUiState="

/** Instagram's own Save and Unsave options, which its feed menu handles a tap on itself. */
internal const val SAVE_OPTION = "$OPTION->SAVE:$OPTION"
internal const val UNSAVE_OPTION = "$OPTION->UNSAVE:$OPTION"

private const val PATCH = "Save in the post menu"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val OBJECT = "Ljava/lang/Object;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * How Instagram asks whether a post is saved: [lookup], a static (Media) method answering the post's
 * saved state, and [test], a static method of that state answering yes when it's saved.
 */
internal class SavedCheck(val lookup: MethodReference, val test: MethodReference)

/**
 * Finds [SavedCheck] where Instagram's older post menu picks Unsave over Save: the lookup and the test
 * run back to back and a no from the test jumps past the Unsave it loads. Every such pick in the app
 * has to agree, and both methods have to be public so the extension can call them.
 */
internal fun BytecodePatchContext.findSavedCheck(): SavedCheck {
    val found = LinkedHashMap<String, SavedCheck>()
    for (classDef in classesTouching(OPTION, "UNSAVE")) {
        for (method in classDef.methods) {
            val code = method.code()
            for (at in 5 until code.size) {
                if (code[at].opcode != Opcode.SGET_OBJECT || code[at].referenceText() != UNSAVE_OPTION) continue
                val start = at - 5
                val lookup = code[start].staticCall() ?: continue
                val test = code[start + 2].staticCall() ?: continue
                if (lookup.parameterTypes.map(Any::toString) != listOf(MEDIA) || !lookup.returnType.startsWith("L")) continue
                if (test.parameterTypes.map(Any::toString) != listOf(lookup.returnType) || test.returnType != "Z") continue
                if (code[start + 1].opcode != Opcode.MOVE_RESULT_OBJECT || code[start + 3].opcode != Opcode.MOVE_RESULT ||
                    code[start + 4].opcode != Opcode.IF_EQZ
                ) continue
                val state = (code[start + 1] as OneRegisterInstruction).registerA
                val answer = (code[start + 3] as OneRegisterInstruction).registerA
                if (code[start + 2].firstArgument() != state || (code[start + 4] as OneRegisterInstruction).registerA != answer) continue
                found["$lookup $test"] = SavedCheck(lookup, test)
            }
        }
    }
    val check = found.values.singleOrNull() ?: refuse(
        if (found.isEmpty()) "found no place Instagram picks Unsave for a saved post"
        else "the places Instagram picks Unsave disagree on how a saved post is told: ${found.keys}",
    )
    for (call in listOf(check.lookup, check.test)) {
        val owner = classDefByOrNull(call.definingClass) ?: refuse("${call.definingClass} isn't in the app")
        val target = owner.methods.firstOrNull {
            it.name == call.name && it.returnType == call.returnType && it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
        } ?: refuse("$call isn't in the app")
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(target.accessFlags) ||
            !AccessFlags.STATIC.isSet(target.accessFlags)
        ) refuse("$call isn't a public static method the extension can call")
    }
    return check
}

/**
 * Save in the post menu's hooks, found: the feed menu Download any video adds its rows to (found by
 * the same finders, so it shows with Download left out), its short menu's list of kept options,
 * the saved check and the row's stubs in the extension's FeedSaveRow. Nothing is changed until
 * [write]. The builder adds Instagram's own Save or Unsave to some posts after anyone else's rows,
 * so a settle call before each of its returns takes the added row out again where that happened.
 */
internal class FeedSaveRowHooks(
    private val context: BytecodePatchContext,
    private val menu: FeedMenu,
    private val kept: Method,
    private val check: SavedCheck,
    private val row: MutableClass,
    private val postOf: MutableMethod,
    private val adder: MutableMethod,
    private val saveOf: MutableMethod,
    private val unsaveOf: MutableMethod,
    private val savedOf: MutableMethod,
) {
    fun write() = with(context) {
        val others = menu.others
        // The short list's last return first, so the ones before it stay where they were.
        val short = mutable(kept)
        val code = short.code()
        for (index in code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.reversed()) {
            val list = (code[index] as OneRegisterInstruction).registerA
            short.addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static { v$list }, $ALLOW_SAVE
                    move-result-object v$list
                """,
            )
        }
        val builder = mutable(menu.builder)
        val built = builder.code()
        val inserts = built.indices.filter { built[it].opcode == Opcode.RETURN_OBJECT }.map { index ->
            val rows = (built[index] as OneRegisterInstruction).registerA
            index to { "invoke-static/range { v$rows .. v$rows }, $SETTLE_SAVE" }
        } + (others.at to {
            // The menu's helper, read off the builder's lambda into a register free there by then.
            val (held, load) = menu.helperAt(builder, others.at, listOf(others.rows))
            "$load\ninvoke-static { v$held, v${others.rows} }, $OFFER_SAVE"
        })
        // The highest index first, so the ones below it stay where they were.
        for ((index, call) in inserts.sortedByDescending { it.first }) {
            builder.addInstructionsAtControlFlowLabel(index, call())
        }
        menu.postBridge(postOf)
        saveOf.addInstructions(0, "sget-object v0, $SAVE_OPTION\nreturn-object v0")
        unsaveOf.addInstructions(0, "sget-object v0, $UNSAVE_OPTION\nreturn-object v0")
        savedOf.addInstructions(
            0,
            """
                check-cast p0, $MEDIA
                invoke-static { p0 }, ${check.lookup}
                move-result-object p0
                invoke-static { p0 }, ${check.test}
                move-result p0
                return p0
            """,
        )
        row.methods.remove(adder)
        row.methods.add(downloadRow(adder, menu, all = true))
    }
}

/** Finds [FeedSaveRowHooks], or stops the patch saying what moved. Nothing is changed here. */
internal fun BytecodePatchContext.findFeedSaveRow(): FeedSaveRowHooks {
    val menu = try {
        findFeedMenu().also { it.helperAt(mutable(it.builder), it.others.at, listOf(it.others.rows)) }
    } catch (moved: PatchException) {
        throw PatchException(moved.message.orEmpty().replace("Download any video:", "$PATCH:"))
    }
    val shortList = menu.shortLists.singleOrNull() ?: refuse(
        "expected one list of the options the short feed menu keeps, found " +
            if (menu.shortLists.isEmpty()) "none" else menu.shortLists.joinToString { "${it.definingClass}->${it.name}" },
    )
    if (menu.builder.code().none { it.opcode == Opcode.RETURN_OBJECT }) {
        refuse("${menu.builder.definingClass}->${menu.builder.name}, the feed menu's builder, never hands back its rows")
    }
    val returns = shortList.code().filter { it.opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) refuse("${shortList.definingClass}->${shortList.name} never returns its list")
    returns.map { (it as OneRegisterInstruction).registerA }.firstOrNull { it > 15 }?.let {
        refuse("${shortList.definingClass}->${shortList.name} returns its list in v$it, out of an invoke's reach")
    }
    for (option in listOf("SAVE", "UNSAVE")) {
        if (classDefBy(OPTION).fields.none { it.name == option && it.type == OPTION && AccessFlags.STATIC.isSet(it.accessFlags) }) {
            refuse("$OPTION has no $option option")
        }
    }
    val check = findSavedCheck()
    val row = mutableClassDefBy(SAVE_ROW)
    fun stub(name: String, returns: String, vararg parameters: String) = row.methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns &&
            it.parameterTypes.map(Any::toString) == parameters.toList()
    } ?: refuse("$SAVE_ROW has no static $returns $name(${parameters.joinToString()})")
    return FeedSaveRowHooks(
        this, menu, shortList, check, row,
        stub("menuMedia", OBJECT, OBJECT),
        stub("addRow", "V", OBJECT, ARRAY_LIST, OBJECT, CHAR_SEQUENCE),
        stub("saveOption", OBJECT),
        stub("unsaveOption", OBJECT),
        stub("saved", "I", OBJECT),
    )
}

/**
 * Feed's action-row state prints whether the row shows the like count after [LIKE_COUNT_LABEL], a
 * final boolean of its own its constructors set, and the like button's component draws no count
 * when it's false. Every write of it, found here and changed only when the answer is called, goes
 * through [LIKE_COUNT_STATE], so a state built while Hide the Like button is on lays the row out
 * with no count at all (#668). Hidden as a view instead, the count left its space and its tap.
 */
internal fun BytecodePatchContext.findFeedLikeCount(): FeedLikeCount =
    findFeedCount(LIKE_COUNT_LABEL, "like count", LIKE_COUNT_STATE)

/**
 * The flag Feed's action-row state prints after [label], every write of it found, and [hook] put
 * right before each when [FeedLikeCount.write] is called. [what] names the flag in a refusal.
 */
internal fun BytecodePatchContext.findFeedCount(label: String, what: String, hook: String): FeedLikeCount {
    val state = feedRowState(PATCH, label)
    val flag = state.methods.single { it.isToString() }.printedFlag(PATCH, state.type, label)
    val writes = flagWrites(PATCH, mutableClassDefBy(state.type), flag).ifEmpty {
        refuse("${state.type}'s constructor never sets what it prints as \"$label\"")
    }
    return FeedLikeCount(state.type, writes, prepareFlagWrites(PATCH, state.type, what, writes, hook))
}

/**
 * Hide counts on posts (#762): the comment, share and repost count flags of the same state, each
 * passed through [COUNT_STATE] right before the constructor stores it, so a state built while the
 * switch is on lays the row out with the buttons and no numbers. The like count goes through
 * [LIKE_COUNT_STATE], which answers for this switch too. Hide comments, Hide the Share button and
 * Hide the Repost button may have hooked the same writes already, and the hook goes in after theirs.
 * Each flag is found right before it's hooked, since a hook in the constructor moves the writes
 * after it, and one that moved only leaves its own count showing, with a warning. Answers how many
 * of the three were hooked.
 */
internal fun BytecodePatchContext.hideFeedCounts(): Int = OTHER_COUNT_LABELS.count { (label, what) ->
    try {
        findFeedCount(label, what, COUNT_STATE).write()
        true
    } catch (refusal: PatchException) {
        patchLog.warning("${refusal.message}. $PATCH goes in leaving the $what on feed posts.")
        false
    }
}

/**
 * The likes row under a feed post, "Liked by" someone "and others" or a number of likes (#762): the
 * render method of each component that draws it from a state of its own, found by that state's
 * type, which a post's media state prints after [LIKES_ROW_LABEL]. On 456 that's the row with
 * Instagram's likes test tag and the newer one with friends' pictures beside the line. Each
 * component's class keeps the state in a field and its render method, the one taking one argument
 * and answering a type the class is, reads it. Nothing is changed until [LikesRows.write].
 */
internal fun BytecodePatchContext.findLikesRows(): LikesRows {
    val state = likesRowState()
    val renders = classesReadingType(state).mapNotNull { classDef ->
        val own = classDef.fields.filter { it.type == state && !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.name }
        if (own.isEmpty()) return@mapNotNull null
        val ancestors = ancestorsOf(classDef)
        classDef.methods.singleOrNull { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 1 &&
                method.returnType in ancestors && method.readsOwnField(classDef.type, state, own)
        }?.let { classDef.type to it }
    }
    if (renders.isEmpty()) refuse("no component draws the likes row from its own $state")
    val methods = renders.map { (type, method) ->
        mutableClassDefBy(type).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }
    }
    for (method in methods) {
        if (method.implementation?.instructions?.none { it.opcode == Opcode.RETURN_OBJECT } != false) {
            refuse("${method.definingClass}->${method.name}, a likes row's render method, never hands back its row")
        }
    }
    return LikesRows(methods)
}

/** The type of the likes row's state: what the one media state printing [LIKES_ROW_LABEL] prints after it. */
internal fun BytecodePatchContext.likesRowState(): String {
    val holders = classesHolding(LIKES_ROW_LABEL).filter { classDef ->
        classDef.methods.any { it.isToString() && it.holdsString(LIKES_ROW_LABEL) }
    }
    val holder = holders.singleOrNull()
        ?: refuse("expected one media state printing \"$LIKES_ROW_LABEL\", found ${holders.size}")
    return printedObjectType(holder, LIKES_ROW_LABEL)
}

/** The likes rows' render methods, each of whose returns passes through [LIKES_ROW] when [write] is called. */
internal class LikesRows(val methods: List<MutableMethod>) {
    fun write() {
        for (method in methods) {
            val code = method.implementation!!.instructions
            // Locations follow their instruction as code goes in ahead of it, so they're taken first.
            val returns = code.filter { it.opcode == Opcode.RETURN_OBJECT }.map { it.location }
            for (location in returns.asReversed()) method.filterReturnAt(PATCH, location.index, LIKES_ROW, method.returnType)
        }
    }
}

/** [findLikesRows], or null with a warning, so Save and the other switches still go in. */
internal fun BytecodePatchContext.findLikesRowsOrWarn(): LikesRows? = try {
    findLikesRows()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in leaving the \"Liked by\" line under feed posts.")
    null
}

/**
 * Where the builder of a feed post's row of buttons adds the Like button (D#877). The builder is the
 * one method loading both [COMMENT_COMPONENT] and [SHARE_COMPONENT]. In it the Like button is the
 * component handed to a static wrapper along with the [LIKE_CLICK_AREA] constant of Instagram's click
 * areas, and the wrapper's answer is checked for null and added to the row's left-hand buttons, the
 * comment and share ones after it. [at] is that null check, which nothing else jumps to, and
 * [register] holds the answer, of [type]. Nothing is changed until [FeedLikeButton.write].
 */
internal class FeedLikeButton(val method: MutableMethod, val at: Int, val register: Int, val type: String) {
    /**
     * Hands the wrapped Like button through [LIKE_BUTTON] right before Instagram's own null check, so a
     * null answer skips the add and the comment button takes its place.
     */
    fun write() = method.addInstructions(at, """
        invoke-static/range { v$register .. v$register }, $LIKE_BUTTON
        move-result-object v$register
        check-cast v$register, $type
    """.trimIndent())
}

internal fun BytecodePatchContext.findFeedLikeButton(): FeedLikeButton {
    val builders = classesLoadingString(COMMENT_COMPONENT).flatMap { owner ->
        owner.methods.filter { loadsString(it, COMMENT_COMPONENT) && loadsString(it, SHARE_COMPONENT) }.map { owner to it }
    }
    val (owner, builder) = builders.singleOrNull()
        ?: refuse("expected one method loading \"$COMMENT_COMPONENT\" and \"$SHARE_COMPONENT\", found ${builders.size}")
    val code = builder.code()
    val sites = code.indices.mapNotNull { likeAddAt(code, it) }
    val (at, register, type) = sites.singleOrNull()
        ?: refuse("${owner.type}->${builder.name} adds the $LIKE_CLICK_AREA button to its row ${sites.size} times")
    branchesOnto(code, at).takeIf { it.isNotEmpty() }?.let {
        refuse("${owner.type}->${builder.name} jumps onto the Like button's null check from $it")
    }
    val method = mutableClassDefBy(owner.type).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
    }
    return FeedLikeButton(method, at, register, type)
}

/**
 * The null check, answer register and type of a Like button added at [load]: a load of the click areas'
 * [LIKE_CLICK_AREA] constant handed first to a static call within a few instructions, its object
 * answer moved into a register, checked for null with the check jumping past the very next
 * instruction, and that instruction adding the answer to a collection. Null for anything else.
 */
private fun BytecodePatchContext.likeAddAt(code: List<Instruction>, load: Int): Triple<Int, Int, String>? {
    val area = code[load]
    if (area.opcode != Opcode.SGET_OBJECT) return null
    val field = (area as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.FieldReference
    if (field.type != field.definingClass) return null
    val enum = classDefByOrNull(field.definingClass) ?: return null
    if (enumConstantName(enum, field) != LIKE_CLICK_AREA) return null
    val loaded = (area as OneRegisterInstruction).registerA
    val wrap = (load + 1..minOf(code.size - 1, load + WRAP_REACH)).firstOrNull { at ->
        code[at].staticCall() != null && code[at].firstArgument() == loaded
    } ?: return null
    val wrapper = code[wrap].staticCall()!!
    if (!wrapper.returnType.startsWith("L")) return null
    val answer = code.getOrNull(wrap + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
    val register = (answer as OneRegisterInstruction).registerA
    val check = code.getOrNull(wrap + 2)?.takeIf { it.opcode == Opcode.IF_EQZ } ?: return null
    if ((check as OneRegisterInstruction).registerA != register) return null
    val add = code.getOrNull(wrap + 3) ?: return null
    val added = (add as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (add.opcode != Opcode.INVOKE_VIRTUAL && add.opcode != Opcode.INVOKE_INTERFACE) return null
    if (added.name != "add" || added.parameterTypes.map(CharSequence::toString) != listOf(OBJECT)) return null
    val item = when (add) {
        is FiveRegisterInstruction -> add.registerD.takeIf { add.registerCount == 2 }
        is RegisterRangeInstruction -> (add.startRegister + 1).takeIf { add.registerCount == 2 }
        else -> null
    }
    if (item != register) return null
    if (branchesOnto(code, wrap + 4).none { it == wrap + 2 }) return null
    return Triple(wrap + 2, register, wrapper.returnType)
}

/** How many instructions after the click area's load the wrapper's call may come, its arguments copied in between. */
private const val WRAP_REACH = 8

/** [findFeedLikeButton], or null with a warning, so Save and the other switches still go in. */
internal fun BytecodePatchContext.findFeedLikeButtonOrWarn(): FeedLikeButton? = try {
    findFeedLikeButton()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in leaving a gap where a hidden Like button was.")
    null
}

/**
 * The type of the object [holder]'s toString prints after [label]: the field of its own whose value
 * the next `append(Object)` hands over. 456 reads every field into a register at the top and copies
 * it back right before appending it, so the value is followed back through those copies.
 */
private fun printedObjectType(holder: com.android.tools.smali.dexlib2.iface.ClassDef, label: String): String {
    val code = holder.methods.single { it.isToString() }.code()
    val loads = code.indices.filter {
        (code[it].opcode == Opcode.CONST_STRING || code[it].opcode == Opcode.CONST_STRING_JUMBO) &&
            ((code[it] as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.StringReference).string == label
    }
    val load = loads.singleOrNull() ?: refuse("${holder.type}->toString loads \"$label\" ${loads.size} times")
    // The label's own append, then the value's.
    val appends = (load + 1 until minOf(code.size, load + 5)).filter { code[it].appendedObject() != null }
    val append = appends.firstOrNull { code[it].appendedObject()!!.second == "Ljava/lang/Object;" }
        ?: refuse("${holder.type}->toString prints something other than an object after \"$label\"")
    var register = code[append].appendedObject()!!.first
    var at = append
    repeat(COPY_REACH) {
        val writer = (at - 1 downTo 0).firstOrNull { code[it].writesRegister(register) }
            ?: refuse("${holder.type}->toString prints \"$label\" from a value it never loads")
        val write = code[writer]
        when (write.opcode) {
            Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16 -> {
                register = (write as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerB
                at = writer
            }
            Opcode.IGET_OBJECT -> {
                val field = (write as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.FieldReference
                if (field.definingClass != holder.type || !field.type.startsWith("L")) {
                    refuse("${holder.type}->toString prints \"$label\" from something other than an object field of its own")
                }
                return field.type
            }
            else -> refuse("${holder.type}->toString prints \"$label\" from something other than a field")
        }
    }
    refuse("${holder.type}->toString copies what it prints as \"$label\" more than $COPY_REACH times")
}

private const val COPY_REACH = 4
private const val STRING_BUILDER = "Ljava/lang/StringBuilder;"

/** The register and parameter type this hands to `StringBuilder.append`, or null when it's no such call. */
private fun Instruction.appendedObject(): Pair<Int, String>? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val called = (this as ReferenceInstruction).reference as MethodReference
    if (called.definingClass != STRING_BUILDER || called.name != "append" || called.parameterTypes.size != 1) return null
    val register = when (this) {
        is FiveRegisterInstruction -> registerD
        is RegisterRangeInstruction -> startRegister + 1
        else -> return null
    }
    return register to called.parameterTypes.single().toString()
}

private fun Instruction.writesRegister(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/** [classDef]'s type and every superclass's the app holds. */
private fun BytecodePatchContext.ancestorsOf(classDef: com.android.tools.smali.dexlib2.iface.ClassDef): Set<String> {
    val types = linkedSetOf(classDef.type)
    var superclass = classDef.superclass
    while (superclass != null && types.add(superclass)) superclass = classDefByOrNull(superclass)?.superclass
    return types
}

/** Whether this reads one of [owner]'s fields [names] holding a [type]. */
private fun Method.readsOwnField(owner: String, type: String, names: List<String>): Boolean =
    implementation?.instructions?.any { instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference
        instruction.opcode == Opcode.IGET_OBJECT && field != null && field.definingClass == owner && field.type == type &&
            field.name in names
    } == true

/** The like count flag's writes in Feed's action-row state of [type], hooked when [write] is called. */
internal class FeedLikeCount(val type: String, val writes: List<FeedStateWrite>, private val change: () -> Unit) {
    fun write() = change()
}

/** [findFeedLikeCount], or null with a warning, so Save and the Like button's switch still go in. */
internal fun BytecodePatchContext.findFeedLikeCountOrWarn(): FeedLikeCount? = try {
    findFeedLikeCount()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in leaving the like count beside a hidden Like button.")
    null
}

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

private fun Instruction.staticCall(): MethodReference? =
    takeIf { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        ?.let { (it as? ReferenceInstruction)?.reference as? MethodReference }

private fun Instruction.firstArgument(): Int? = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> null
}

/**
 * Save, or Remove from saved on a post you saved, in the menu behind a feed post's three dots (#569):
 * Instagram's own options, which its menu already handles, added where anyone else's rows start and
 * kept by the short menu. The same patch lets the extension take the Save button off feed posts,
 * and the Like button with its count (#668), the count through the row's own state and the button
 * through the row's builder (D#877), so the row is laid out without them. Hide counts on posts takes the like, comment, share and repost counts off
 * with the buttons kept, and Hide Liked by the likes row under a post (#762). In the default
 * selection with every switch off.
 */
@Suppress("unused")
val saveInPostMenuPatch = bytecodePatch(
    name = "Save in the post menu",
    description = "Adds Save to the menu behind the three dots on posts in your feed, and switches that take the " +
        "Save button, the Like button, the counts or the \"Liked by\" line off them. Starts off. Turn them on in " +
        "HushGram settings > Feed.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(FEED_SAVE_STATUS)
        val row = findFeedSaveRow()
        val likeCount = findFeedLikeCountOrWarn()
        val likesRows = findLikesRowsOrWarn()
        val likeButton = findFeedLikeButtonOrWarn()
        row.write()
        likeCount?.write()
        likeButton?.write()
        hideFeedCounts()
        likesRows?.write()
        enableStatus(FEED_SAVE_STATUS)
    }
}
