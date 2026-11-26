/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.ghost

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Block likes and replies in Ghost mode"

internal const val GHOST_ACTIONS = "$EXTENSION_PACKAGE/settings/GhostActions;"

/** Asked first where a like starts: true holds it back, with a toast saying why. */
internal const val GHOST_LIKE = "$GHOST_ACTIONS->like()Z"

/** Asked first where a story reply starts: true holds it back, with a toast saying why. */
internal const val GHOST_REPLY = "$GHOST_ACTIONS->reply()Z"

/** Asked first in Copy link and where a share link is made: true holds it back, with a toast saying why. */
internal const val GHOST_SHARE = "$GHOST_ACTIONS->share()Z"

/** Asked first where the comment sheet posts a comment: true holds it back, with a toast saying why. */
internal const val GHOST_COMMENT = "$GHOST_ACTIONS->comment()Z"

/** What Copy link logs as it starts, in each of the steps a profile, a story, a post or a link's id copies through. */
internal const val COPY_LINK = "copy_link"

/** The comment sheet's view model and the repository whose jobs post a comment, names Redex keeps. */
internal const val COMMENT_VIEW_MODEL = "Lcom/instagram/comments/mvvm/viewmodel/CommentListViewModel;"
internal const val COMMENT_REPOSITORY = "Lcom/instagram/comments/mvvm/data/MediaCommentListRepository;"
internal const val COMMENT_POST_JOB = "Lcom/instagram/comments/mvvm/data/MediaCommentListRepository\$postComment\$1;"
internal const val COMMENT_DELAYED_POST_JOB = "Lcom/instagram/comments/mvvm/data/MediaCommentListRepository\$postDelayedComment\$1;"

/**
 * What the like path under a post counts the double tap hint with, which only its shared step loads.
 * The Like button, the double tap and the feed's other like paths all go through that step before the
 * post changes, so the hook goes there and not in the Like button's handler, where Ask before a like
 * keeps its own guard first.
 */
internal const val POST_DOUBLE_TAP_HINT = "used_double_tap_hint_impressions"

/** What a story's heart starts its flow with, and logs as it likes and unlikes. */
internal const val STORY_LIKE_FLOW = "story_viewer_like_button"
internal const val STORY_LIKE_TAPPED = "story_like_button_tapped"
internal const val STORY_UNLIKE_TAPPED = "story_unlike_button_tapped"

/** What a story's quick reaction and a typed story reply log as they go. */
internal const val GHOST_QUICK_REACTION = "reply_with_quick_reaction"
internal const val GHOST_TEXT_REPLY = "reply_with_text_message"

private const val MEDIA = "Lcom/instagram/feed/media/Media;"
private const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val VIEW = "Landroid/view/View;"
private const val STRING = "Ljava/lang/String;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val DIRECT_SHARE_TARGET = "Lcom/instagram/model/direct/DirectShareTarget;"
private const val CONTEXT = "Landroid/content/Context;"
private const val LOADER_MANAGER = "Landroidx/loader/app/LoaderManager;"

/**
 * While Ghost mode is on, a like, a story reply, a comment or a share link does nothing and a toast
 * says why (#876). Each hook goes in where the tap starts, before Instagram changes anything on
 * screen, so the heart never turns red and nothing is sent. Starts off.
 */
@Suppress("unused")
val blockGhostActionsPatch = bytecodePatch(
    name = "Block likes and replies in Ghost mode",
    description = "While Ghost mode is on, liking a post or story, replying to a story, posting a comment and " +
        "copying or sharing a link do nothing, and a message says why. Starts off. Turn it on in HushGram " +
        "settings > Ads and privacy.",
) {
    category("Ghost mode")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch, ghostModeEntryPatch)

    execute {
        requireStatusMethod("ghostActions")
        blockGhostActions(findGhostActions())
        enableStatus("ghostActions")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/**
 * The classic story viewer's heart: a case of a merged click listener. The hook goes at [at], right
 * after the case starts its trace in [trace], borrowing [scratch], and a yes jumps to [exit], the
 * case's end, where it ends the trace and returns.
 */
internal class StoryHeartCase(val method: MutableMethod, val at: Int, val scratch: Int, val exit: Int, val trace: Int)

/**
 * Where each hook goes: the methods it goes first in, which like or reply, and the classic heart's
 * case, found and checked before anything changes.
 */
internal class GhostActionTargets(
    val postLikeStep: MutableMethod,
    val storyHeart: StoryHeartCase,
    val storyLike: MutableMethod,
    val storyLikeStep: MutableMethod,
    val textReplies: List<MutableMethod>,
    val quickReaction: MutableMethod,
    val copyLinks: List<MutableMethod>,
    val linkFetches: List<MutableMethod>,
    val commentPosts: List<MutableMethod>,
) {
    /** The methods a hook goes first in, with the hook. */
    val atStart: List<Pair<MutableMethod, String>>
        get() = listOf(postLikeStep, storyLike, storyLikeStep).map { it to GHOST_LIKE } +
            (textReplies + quickReaction).map { it to GHOST_REPLY } +
            (copyLinks + linkFetches).map { it to GHOST_SHARE } +
            commentPosts.map { it to GHOST_COMMENT }
}

/**
 * Finds every place a hook goes, refusing the whole patch before anything changes if one of them
 * isn't as expected:
 *
 * - the step a post's Like button (03Ie.Fqr on 386314341), its double tap and the feed's other like
 *   paths share before the post changes: the one instance method taking an Activity and a post that
 *   loads [POST_DOUBLE_TAP_HINT] (0Sm8.A02);
 * - a story's heart in the classic viewer: the like case of the merged click listener loading
 *   [STORY_LIKE_FLOW] and [STORY_LIKE_TAPPED] (0Sy1.onClick case 30), whose heart turns red right
 *   after it hands the like on through an interface `(action, ReelItem, ...)V` call;
 * - a story's heart in the newer viewer: the one instance `()V` method loading [STORY_LIKE_FLOW] and
 *   [STORY_UNLIKE_TAPPED] (027v.A0T), and the like step it calls, an instance
 *   `(..., Media, action, ..., Integer, int)V` method (027t.A0P), which a super like goes through too;
 * - a typed story reply: each method returning nothing, Redex merges left out, that loads
 *   [GHOST_TEXT_REPLY] and calls the story reply send, a static call starting (UserSession, Media,
 *   DirectShareTarget) (020U.A06; 386314341's merged 0Nvz.invoke sends one too and stays unhooked);
 * - a quick reaction: the one method loading [GHOST_QUICK_REACTION] (069D.A00);
 * - Copy link: each method returning nothing that takes a LoaderManager and loads [COPY_LINK], Redex
 *   merges left out (0O0e.A00, A01, A04 and A05 and 0O0j.A00), each of which asks for a share link;
 * - the share links themselves: each static method returning nothing that takes a LoaderManager and
 *   schedules the link's request through a static `(Context, LoaderManager, request)int` call, in
 *   the one class Copy link hands its link to that way (0O0F's A00 to A05, which the share sheet's
 *   other ways out use too);
 * - a comment: each instance method of [COMMENT_VIEW_MODEL] returning nothing that takes an Activity
 *   first and posts, either through a [COMMENT_REPOSITORY] method that starts [COMMENT_POST_JOB] or
 *   by starting [COMMENT_DELAYED_POST_JOB] itself (FPC, FjJ for a GIF and A1P for a delayed post).
 */
internal fun BytecodePatchContext.findGhostActions(): GhostActionTargets {
    val extension = classDefByOrNull(GHOST_ACTIONS) ?: refuse("$GHOST_ACTIONS isn't in the extension")
    for (hook in listOf(GHOST_LIKE, GHOST_REPLY, GHOST_SHARE, GHOST_COMMENT)) {
        val name = hook.substringAfter("->").substringBefore("(")
        extension.methods.singleOrNull {
            it.name == name && it.isStatic() && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == "Z"
        } ?: refuse("the extension has no public static $hook")
    }

    val postLikeStep = startHook("post like's shared step", one("post like's shared step", POST_DOUBLE_TAP_HINT) { method ->
        !method.isStatic() && method.returnType == "V" && method.parameters().let { it.firstOrNull() == ACTIVITY && MEDIA in it }
    })

    val storyLike = startHook("newer story viewer's heart", one("newer story viewer's heart", STORY_UNLIKE_TAPPED) { method ->
        !method.isStatic() && method.returnType == "V" && method.parameterTypes.isEmpty() && loadsString(method, STORY_LIKE_FLOW)
    })
    val steps = storyLike.calls().filter { it.isStoryLikeStep() }.distinctBy { it.key() }
    val stepCall = steps.singleOrNull() ?: refuse(
        "expected ${storyLike.where()} to hand the like to one (..., Media, action, ..., Integer, int) step, found ${steps.size}",
    )
    val storyLikeStep = startHook("newer story viewer's like step", mutable(stepCall))

    val storyHeart = findStoryHeartCase()

    val reaction = startHook("quick reaction", one("quick reaction", GHOST_QUICK_REACTION) { true })
    val sends = reaction.calls().filter { it.isStoryReplySend() }.distinctBy { it.key() }
    val send = sends.singleOrNull()
        ?: refuse("expected ${reaction.where()} to hand a quick reaction to one story reply send, found ${sends.size}")
    val typed = classesLoadingString(GHOST_TEXT_REPLY).flatMap { owner ->
        owner.methods.filter { method ->
            !method.isPool() && !method.isMerged() && method.returnType == "V" && method.implementation != null &&
                loadsString(method, GHOST_TEXT_REPLY) && method.calls().any { it.key() == send.key() }
        }.map { owner to it }
    }
    if (typed.isEmpty()) refuse("no method loading \"$GHOST_TEXT_REPLY\" sends a typed story reply through ${send.key()}")
    val textReplies = typed.map { (owner, method) -> startHook("typed story reply", mutable(owner.type, method)) }

    val copies = classesLoadingString(COPY_LINK).flatMap { owner ->
        owner.methods.filter { method ->
            !method.isPool() && !method.isMerged() && method.returnType == "V" && method.implementation != null &&
                LOADER_MANAGER in method.parameters() && loadsString(method, COPY_LINK)
        }.map { owner to it }
    }
    if (copies.isEmpty()) refuse("no method taking a LoaderManager loads \"$COPY_LINK\"")
    val handedTo = copies.flatMap { (_, method) -> method.staticCalls().filter { it.returnType == "V" && LOADER_MANAGER in it.parameters() } }
        .map { it.definingClass }.distinct()
    val fetcherType = handedTo.singleOrNull()
        ?: refuse("expected Copy link to hand its link to one class through a static LoaderManager call, found ${handedTo.size}")
    val fetcher = classDefByOrNull(fetcherType) ?: refuse("$fetcherType isn't in this build")
    val fetches = fetcher.methods.filter { method ->
        method.isStatic() && method.returnType == "V" && method.implementation != null && LOADER_MANAGER in method.parameters() &&
            method.staticCalls().any { it.isLinkRequestSchedule() }
    }
    if (fetches.isEmpty()) refuse("$fetcherType schedules no share link's request")
    val copyKeys = copies.map { (_, method) -> method.key() }.toSet()
    val copyLinks = copies.map { (owner, method) -> startHook("Copy link step", mutable(owner.type, method)) }
    val linkFetches = fetches.filter { it.key() !in copyKeys }.map { startHook("share link step", mutable(fetcherType, it)) }

    val repository = classDefByOrNull(COMMENT_REPOSITORY) ?: refuse("$COMMENT_REPOSITORY isn't in this build")
    val postJobs = repository.methods.filter { it.creates(COMMENT_POST_JOB) }.map { it.key() }.toSet()
    if (postJobs.isEmpty()) refuse("no $COMMENT_REPOSITORY method starts $COMMENT_POST_JOB")
    val viewModel = classDefByOrNull(COMMENT_VIEW_MODEL) ?: refuse("$COMMENT_VIEW_MODEL isn't in this build")
    val posts = viewModel.methods.filter { method ->
        !method.isStatic() && !method.isMerged() && method.returnType == "V" && method.implementation != null &&
            method.parameters().firstOrNull() == ACTIVITY &&
            (method.calls().any { it.key() in postJobs } || method.creates(COMMENT_DELAYED_POST_JOB))
    }
    if (posts.isEmpty()) refuse("no $COMMENT_VIEW_MODEL method taking an Activity posts a comment")
    val commentPosts = posts.map { startHook("comment post", mutable(COMMENT_VIEW_MODEL, it)) }

    return GhostActionTargets(postLikeStep, storyHeart, storyLike, storyLikeStep, textReplies, reaction, copyLinks, linkFetches, commentPosts)
}

/**
 * Puts each hook in: first thing in each method of [found], answering with a return before the
 * method does anything, and in the classic heart's case right after its trace starts, answering with
 * a jump to the case's end.
 */
internal fun blockGhostActions(found: GhostActionTargets) {
    for ((method, hook) in found.atStart) {
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $hook
                move-result v0
                if-eqz v0, :instagram
                return-void
            """,
            ExternalLabel("instagram", method.getInstruction(0)),
        )
    }
    val heart = found.storyHeart
    heart.method.addInstructionsWithLabels(
        heart.at,
        """
            invoke-static { }, $GHOST_LIKE
            move-result v${heart.scratch}
            if-nez v${heart.scratch}, :ghost
        """,
        ExternalLabel("ghost", heart.method.getInstruction(heart.exit)),
    )
}

/**
 * The classic viewer's heart case, checked: the one `onClick(View)` of a click listener loading
 * [STORY_LIKE_FLOW] and [STORY_LIKE_TAPPED], holding [STORY_LIKE_FLOW] once. The case starts a trace
 * (a constant, a static `(int)int` call and its result) before it, hands the like on through an
 * interface `(action, ReelItem, ...)V` call after it, and ends with a constant handed with the
 * trace to a static `(int, int)V` call before it returns. Every way into the like call passes the
 * hook, the hook's register is free there and at the end, and the end reads nothing else.
 */
internal fun BytecodePatchContext.findStoryHeartCase(): StoryHeartCase {
    val what = "classic story viewer's heart"
    val listeners = classesLoadingString(STORY_LIKE_TAPPED).filter { CLICK_LISTENER in it.interfaces }.flatMap { owner ->
        owner.methods.filter { method ->
            method.name == "onClick" && !method.isStatic() && method.returnType == "V" && method.parameters() == listOf(VIEW) &&
                loadsString(method, STORY_LIKE_FLOW) && loadsString(method, STORY_LIKE_TAPPED)
        }.map { owner to it }
    }
    val (owner, listener) = listeners.singleOrNull() ?: refuse("expected one click listener for the $what, found ${listeners.size}")
    val where = "${owner.type}->onClick"
    val code = listener.implementation!!.instructions.toList()
    val flows = code.indices.filter { code[it].string() == STORY_LIKE_FLOW }
    val flow = flows.singleOrNull() ?: refuse("expected $where to load \"$STORY_LIKE_FLOW\" once, found ${flows.size}")

    val handOn = (flow + 1 until code.size).firstOrNull { at ->
        val call = code[at].method() ?: return@firstOrNull false
        code[at].opcode == Opcode.INVOKE_INTERFACE && call.returnType == "V" &&
            call.parameterTypes.map(Any::toString).let { it.size == 3 && it[1] == REEL_ITEM && it[0].startsWith("L") }
    } ?: refuse("$where hands the story's like on through no interface (action, ReelItem, ...) call after \"$STORY_LIKE_FLOW\"")

    val ends = (handOn + 1 until code.size).firstNotNullOfOrNull { at -> traceEnd(code, at)?.let { at to it } }
        ?: refuse("$where has no trace end after the like's hand-off")
    val (exit, end) = ends
    val endCall = end.method()!!
    val trace = (end as FiveRegisterInstruction).registerD

    val starts = (flow - 1 downTo 2).filter { at ->
        val result = code[at] as? OneRegisterInstruction
        val begin = code[at - 1].method()
        code[at].opcode == Opcode.MOVE_RESULT && result?.registerA == trace && code[at - 1].opcode == Opcode.INVOKE_STATIC &&
            begin != null && begin.definingClass == endCall.definingClass && begin.returnType == "I" &&
            begin.parameterTypes.map(Any::toString) == listOf("I") && code[at - 2].isIntConstant()
    }
    val start = starts.firstOrNull() ?: refuse("$where starts no trace in v$trace before the like")
    val at = start + 1
    if (at in listener.jumpTargets()) refuse("a jump in $where lands where the hook goes, right after the like's trace starts")
    if (reachesAvoiding(listener, handOn, at)) refuse("$where reaches the like's hand-off without passing the hook")
    val mutable = mutable(owner.type, listener)
    val scratch = mutable.freeLocalsAt("$PATCH ($what)", at, 1, targets = listOf(exit)).single()
    if (scratch == trace) refuse("$where has no free register for the hook besides the trace's")
    return StoryHeartCase(mutable, at, scratch, exit, trace)
}

/**
 * The trace end at [at]: a constant into a register, then (straight on, or through a goto) a static
 * `(int, int)V` call handing that register and the trace's on, then a return. Null for anything else.
 */
private fun traceEnd(code: List<Instruction>, at: Int): Instruction? {
    if (!code[at].isIntConstant()) return null
    val marker = (code[at] as OneRegisterInstruction).registerA
    var next = at + 1
    if (code.getOrNull(next)?.opcode in GOTOS) next = targetOf(code, next) ?: return null
    val call = code.getOrNull(next) ?: return null
    val called = call.method() ?: return null
    if (call.opcode != Opcode.INVOKE_STATIC || called.returnType != "V" || called.parameterTypes.map(Any::toString) != listOf("I", "I")) return null
    if ((call as FiveRegisterInstruction).registerC != marker) return null
    return call.takeIf { code.getOrNull(next + 1)?.opcode == Opcode.RETURN_VOID }
}

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/** The index a goto at [at] lands on. */
private fun targetOf(code: List<Instruction>, at: Int): Int? {
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val target = address[at] + (code[at] as OffsetInstruction).codeOffset
    return address.indexOf(target).takeIf { it in code.indices }
}

/** Whether a path from the method's start reaches [target] without running [avoid]. */
private fun reachesAvoiding(method: Method, target: Int, avoid: Int): Boolean {
    val flow = ControlFlow.of(method)
    val seen = BooleanArray(flow.instructions.size)
    val queue = ArrayDeque(listOf(0))
    while (queue.isNotEmpty()) {
        val at = queue.removeFirst()
        if (at == avoid || seen[at]) continue
        if (at == target) return true
        seen[at] = true
        flow.normal[at].forEach(queue::addLast)
        flow.exceptional[at].forEach(queue::addLast)
    }
    return false
}

/** The one method outside the extension loading [string] that [shaped] accepts, the pools left out. */
private fun BytecodePatchContext.one(what: String, string: String, shaped: (Method) -> Boolean): MutableMethod {
    val found = classesLoadingString(string).flatMap { owner ->
        owner.methods.filter { method ->
            !method.isPool() && method.implementation != null && loadsString(method, string) && shaped(method)
        }.map { owner to it }
    }
    val (owner, method) = found.singleOrNull() ?: refuse("expected one $what loading \"$string\", found ${found.size}")
    return mutable(owner.type, method)
}

/** [method] checked to take a hook first thing: it returns nothing, has a local, and nothing jumps to its start. */
private fun startHook(what: String, method: MutableMethod): MutableMethod {
    val where = method.where()
    if (method.returnType != "V") refuse("the $what $where returns ${method.returnType}, not nothing")
    if (method.localRegisterCount() < 1) refuse("the $what $where has no local for the hook's answer")
    if (0 in method.jumpTargets()) refuse("a jump in the $what $where lands on its first instruction, where the hook goes")
    return method
}

private fun BytecodePatchContext.mutable(owner: String, method: Method): MutableMethod {
    if (owner.startsWith(EXTENSION_ROOT)) refuse("$owner is the extension's own")
    val types = method.parameters()
    return mutableClassDefBy(owner).methods.single {
        it.name == method.name && it.returnType == method.returnType && it.parameters() == types
    }
}

private fun BytecodePatchContext.mutable(call: MethodReference): MutableMethod {
    val owner: ClassDef = classDefByOrNull(call.definingClass) ?: refuse("${call.definingClass} isn't in this build")
    val types = call.parameterTypes.map(Any::toString)
    val method = owner.methods.singleOrNull {
        it.name == call.name && it.returnType == call.returnType && it.parameters() == types && it.implementation != null
    } ?: refuse("${call.definingClass}->${call.name} has no body in its class")
    return mutable(owner.type, method)
}

/** A story like step: an instance call taking (..., Media, action, ..., Integer, int) and returning nothing. */
private fun MethodReference.isStoryLikeStep(): Boolean = returnType == "V" && parameterTypes.map(Any::toString).let {
    it.size == 6 && it[1] == MEDIA && it[2].startsWith("L") && it[4] == INTEGER && it[5] == "I"
}

/** The story reply send: a call starting (UserSession, Media, DirectShareTarget) and returning nothing. */
private fun MethodReference.isStoryReplySend(): Boolean = returnType == "V" && parameterTypes.map(Any::toString).let {
    it.size >= 3 && it[0] == USER_SESSION && it[1] == MEDIA && it[2] == DIRECT_SHARE_TARGET
}

/** A share link's request handed to the loader: a call taking (Context, LoaderManager, request) and answering an int. */
private fun MethodReference.isLinkRequestSchedule(): Boolean = returnType == "I" && parameters().let {
    it.size == 3 && it[0] == CONTEXT && it[1] == LOADER_MANAGER && it[2].startsWith("L")
}

private fun Method.calls(): List<MethodReference> =
    implementation?.instructions?.mapNotNull { it.method() } ?: emptyList()

private fun Method.staticCalls(): List<MethodReference> = implementation?.instructions
    ?.filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
    ?.mapNotNull { it.method() } ?: emptyList()

/** Whether this method makes a new [type]. */
private fun Method.creates(type: String): Boolean = implementation?.instructions?.any { instruction ->
    instruction.opcode == Opcode.NEW_INSTANCE && ((instruction as ReferenceInstruction).reference as? TypeReference)?.type == type
} == true

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.isIntConstant() = opcode in setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

private fun Method.isPool() = isStatic() && returnType == STRING && parameters() == listOf("I")

/** A Redex-merged method: it reads its own class's `$t` to pick which of the merged bodies runs. */
private fun Method.isMerged() = implementation?.instructions?.any { instruction ->
    ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.let { it.definingClass == definingClass && it.name == "\$t" } == true
} == true

private fun MethodReference.parameters() = parameterTypes.map(Any::toString)

private fun Method.where() = "$definingClass->$name"

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
