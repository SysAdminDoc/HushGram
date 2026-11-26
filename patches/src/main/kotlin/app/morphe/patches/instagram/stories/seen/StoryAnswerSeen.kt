/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.mediaIdCall
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val STORY_ANSWER_SEEN = "$EXTENSION_PACKAGE/stories/StoryAnswerSeen;"
internal const val STORY_LIKED = "$STORY_ANSWER_SEEN->liked(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val STORY_ANSWERED = "$STORY_ANSWER_SEEN->answered(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val STORY_VIEWER_OPENED = "$STORY_ANSWER_SEEN->viewerOpened(Ljava/lang/Object;)V"

/**
 * The names the story like store gives a like's request and an unlike's. Its request builder loads
 * both, and so does the story like button's handler that calls it, through a Redex pool.
 */
internal const val STORY_LIKE_SEND = "send_story_like"
internal const val STORY_UNLIKE_SEND = "unsend_story_like"

/** What a quick reaction from a story's reply bar logs as it goes, and what a typed story reply logs. */
internal const val STORY_QUICK_REACTION = "reply_with_quick_reaction"
internal const val STORY_TEXT_REPLY = "reply_with_text_message"

/** The setting the story viewer's flush of its views reads how many stories it waits for under. */
internal const val STORY_SEEN_FLUSH = "STORIES_SEEN_STATE_FLUSH"

/** What a story reply's send starts with: the account, the story, who it goes to and a flag. */
internal val STORY_REPLY_SHAPE = listOf(
    USER_SESSION,
    "Lcom/instagram/feed/media/Media;",
    "Lcom/instagram/model/direct/DirectShareTarget;",
    "Ljava/lang/Boolean;",
)

private const val ANSWER_OBJECT = "Ljava/lang/Object;"
private const val ANSWER_STRING = "Ljava/lang/String;"
private val ANSWER_STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

private fun refuseAnswer(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * What Mark a story seen when you like or reply (#876) works on, found before anything changes.
 *
 * [like] is the story like store's builder of a like's or an unlike's request (on [likeStore]), and
 * [likeAt] the instruction loading [STORY_LIKE_SEND], on the like's branch alone; the hook goes right
 * before it with `this` and parameter [likeId], the story's id. [likeSession] is the store's getter
 * of the account it's for. [reply] is the static send a typed story reply and a quick reaction both
 * go through, taking the account and the story first. [viewer] is the constructor of the story
 * viewer's flush that waits for a number of stories, [viewerAt] its one return, and its second
 * parameter the viewer's [flusher], whose instance method [flush] hands the viewer's batch of views
 * to the store's send. [mediaId] is Media's id getter, as an invoke-virtual takes it.
 */
internal class StoryAnswerTargets(
    val like: MutableMethod,
    val likeAt: Int,
    val likeId: Int,
    val likeStore: String,
    val likeSession: Method,
    val reply: MutableMethod,
    val viewer: MutableMethod,
    val viewerAt: Int,
    val flusher: String,
    val flush: String,
    val mediaId: String,
    val stubs: StoryAnswerStubs,
)

/** The extension's stubs the patch fills in for Mark a story seen when you like or reply. */
internal class StoryAnswerStubs(
    val flush: MutableMethod,
    val likeSession: MutableMethod,
    val storyMediaId: MutableMethod,
)

/**
 * Finds the three places Mark a story seen when you like or reply hooks, or refuses naming what's
 * wrong. Each is found by the strings Instagram logs there and checked by its shape:
 *
 * - the story like store's request builder: of the methods loading both [STORY_LIKE_SEND] and
 *   [STORY_UNLIKE_SEND], the one calling none of the others (02ps.A0S on 386314341, where the like
 *   button's handler 07V6.Fqq loads both through a pool and calls it), an instance method holding
 *   [STORY_LIKE_SEND] once, where no branch lands, with `this` and its first String parameter, the
 *   story's id, still there and in registers a nibble can name;
 * - the send of a story reply: the one static call shaped [STORY_REPLY_SHAPE] in the one method
 *   logging [STORY_QUICK_REACTION] (02yd.A15 on 386314341, called from 069D.A00), which a method
 *   logging [STORY_TEXT_REPLY] calls too, with no branch to its first instruction;
 * - the story viewer's flusher: the one method loading [STORY_SEEN_FLUSH] is the constructor of the
 *   viewer's flush that waits for a number of stories (06W8 on 386314341), taking the account and the
 *   flusher that flushes now (06W7), which has one public instance `()V` handing the viewer's batch to
 *   [found]'s send. The constructor returns once, where no branch lands, with the flusher still in
 *   its parameter's register.
 */
internal fun BytecodePatchContext.findStoryAnswers(found: StorySeenTargets): StoryAnswerTargets {
    val like = storyLikeBuilder()
    val likeWhere = "${like.definingClass}->${like.name}"
    if (AccessFlags.STATIC.isSet(like.accessFlags)) refuseAnswer("$likeWhere is static, but the story like store's builder is an instance method")
    val likeCode = like.answerCode()
    val sends = likeCode.indices.filter { likeCode[it].answerString() == STORY_LIKE_SEND }
    val likeAt = sends.singleOrNull()
        ?: refuseAnswer("expected $likeWhere to hold \"$STORY_LIKE_SEND\" once, found ${sends.size}")
    if (likeAt in like.jumpTargets()) refuseAnswer("a branch in $likeWhere lands on its load of \"$STORY_LIKE_SEND\", where the hook goes")
    val likeId = like.parameterTypes.map(Any::toString).indexOf(ANSWER_STRING)
    if (likeId < 0) refuseAnswer("$likeWhere takes no String, the story's id")
    like.requireThisIntact(PATCH, listOf(likeAt))
    like.requireParameterIntact(PATCH, likeId, listOf(likeAt))
    if (like.localRegisterCount() > 15 || like.parameterRegisterNumber(likeId) > 15) {
        refuseAnswer("$likeWhere keeps this or the story's id past v15, which the hook's call can't name")
    }
    val likeClass = classDefByOrNull(like.definingClass) ?: refuseAnswer("${like.definingClass} isn't in this build")
    val likeSession = sessionGetter(likeClass)

    val reaction = methodLoading("story quick reaction", STORY_QUICK_REACTION)
    val sendsTo = reaction.answerCode().filter { it.opcode in ANSWER_STATIC_CALLS }.mapNotNull { it.answerCall() }
        .filter { it.isStoryReplySend() }.distinctBy { it.answerKey() }
    val replyCall = sendsTo.singleOrNull() ?: refuseAnswer(
        "expected ${reaction.definingClass}->${reaction.name} to hand a quick reaction to one story reply send, found " +
            if (sendsTo.isEmpty()) "none" else sendsTo.joinToString { it.answerKey() },
    )
    val typed = classesLoadingString(STORY_TEXT_REPLY).flatMap { owner ->
        owner.methods.filter { method ->
            !method.answerPool() && loadsString(method, STORY_TEXT_REPLY) &&
                method.answerCode().any { it.opcode in ANSWER_STATIC_CALLS && it.answerCall()?.answerKey() == replyCall.answerKey() }
        }
    }
    if (typed.isEmpty()) refuseAnswer("no method logging \"$STORY_TEXT_REPLY\" sends a typed story reply through ${replyCall.answerKey()}")
    classDefByOrNull(replyCall.definingClass) ?: refuseAnswer("${replyCall.definingClass} isn't in this build")
    val reply = mutableClassDefBy(replyCall.definingClass).methods.singleOrNull {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.answerKey() == replyCall.answerKey()
    } ?: refuseAnswer("${replyCall.answerKey()}, the story reply send, isn't a static method of its class")
    if (0 in reply.jumpTargets()) refuseAnswer("a branch in ${replyCall.answerKey()} lands on its first instruction, where the hook goes")

    val viewer = methodLoading("story viewer's flush of its views", STORY_SEEN_FLUSH)
    val viewerWhere = "${viewer.definingClass}->${viewer.name}"
    val viewerParameters = viewer.parameterTypes.map(Any::toString)
    if (viewer.name != "<init>" || viewerParameters.size != 2 || viewerParameters[0] != USER_SESSION ||
        !viewerParameters[1].startsWith("L") || viewerParameters[1] == USER_SESSION
    ) {
        refuseAnswer("$viewerWhere, which loads \"$STORY_SEEN_FLUSH\", isn't a constructor taking the account and the viewer's flusher")
    }
    val flusher = viewerParameters[1]
    val flusherClass = classDefByOrNull(flusher) ?: refuseAnswer("$flusher, the story viewer's flusher, isn't in this build")
    val flushes = flusherClass.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() && method.returnType == "V" &&
            method.answerCode().any { instruction ->
                instruction.answerCall()?.let {
                    it.definingClass == found.store && it.name == found.send && it.returnType == "V" &&
                        it.parameterTypes.map(Any::toString) == listOf(found.batch)
                } == true
            }
    }
    val flush = flushes.singleOrNull()
        ?: refuseAnswer("expected $flusher to have one ()V handing the viewer's views to ${found.store}->${found.send}, found ${flushes.size}")
    if (!AccessFlags.PUBLIC.isSet(flusherClass.accessFlags) || AccessFlags.INTERFACE.isSet(flusherClass.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(flush.accessFlags)
    ) {
        refuseAnswer("$flusher->${flush.name}, the story viewer's flush, isn't public for the extension to call")
    }
    val viewerCode = viewer.answerCode()
    val returns = viewerCode.indices.filter { viewerCode[it].opcode == Opcode.RETURN_VOID }
    val viewerAt = returns.singleOrNull() ?: refuseAnswer("expected $viewerWhere to return once, found ${returns.size}")
    if (viewerAt in viewer.jumpTargets()) refuseAnswer("a branch in $viewerWhere lands on its return, where the hook goes")
    viewer.requireParameterIntact(PATCH, 1, listOf(viewerAt))
    if (viewer.parameterRegisterNumber(1) > 15) refuseAnswer("$viewerWhere keeps the flusher past v15, which the hook's call can't name")

    return StoryAnswerTargets(
        like = like,
        likeAt = likeAt,
        likeId = likeId,
        likeStore = like.definingClass,
        likeSession = likeSession,
        reply = reply,
        viewer = viewer,
        viewerAt = viewerAt,
        flusher = flusher,
        flush = flush.name,
        mediaId = mediaIdCall(PATCH),
        stubs = storyAnswerStubs(),
    )
}

/**
 * The story like store's request builder: of the methods loading both [STORY_LIKE_SEND] and
 * [STORY_UNLIKE_SEND], directly or through a pool, the one that calls none of the others. The like
 * button's handler loads both names too (07V6.Fqq on 386314341, 081r.Fqy on 386314286) and calls
 * the builder, so it's left out.
 */
private fun BytecodePatchContext.storyLikeBuilder(): MutableMethod {
    val loading = classesLoadingString(STORY_LIKE_SEND).flatMap { owner ->
        owner.methods.filter { method ->
            !method.answerPool() && loadsString(method, STORY_LIKE_SEND) && loadsString(method, STORY_UNLIKE_SEND)
        }
    }
    val keys = loading.map { it.answerKey() }.toSet()
    val builders = loading.filter { method ->
        val own = method.answerKey()
        method.answerCode().none { instruction -> instruction.answerCall()?.answerKey()?.let { it != own && it in keys } == true }
    }
    val builder = builders.singleOrNull() ?: refuseAnswer(
        "expected exactly one story like request builder in this Instagram build, found " +
            if (builders.isEmpty()) "none" else builders.joinToString { "${it.definingClass}->${it.name}" },
    )
    return mutableClassDefBy(builder.definingClass).methods.single { it.answerKey() == builder.answerKey() }
}

/** Whether [this] is shaped like a story reply's send: [STORY_REPLY_SHAPE] first, answering nothing. */
private fun MethodReference.isStoryReplySend(): Boolean {
    val parameters = parameterTypes.map(Any::toString)
    return returnType == "V" && parameters.size >= STORY_REPLY_SHAPE.size && parameters.subList(0, STORY_REPLY_SHAPE.size) == STORY_REPLY_SHAPE
}

private fun BytecodePatchContext.storyAnswerStubs(): StoryAnswerStubs {
    val answer = classDefByOrNull(STORY_ANSWER_SEEN)?.let { mutableClassDefBy(STORY_ANSWER_SEEN) }
        ?: refuseAnswer("$STORY_ANSWER_SEEN isn't in the extension")
    for (hook in listOf(STORY_LIKED, STORY_ANSWERED, STORY_VIEWER_OPENED)) {
        answer.methods.singleOrNull {
            "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook.substringAfter("->") &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: refuseAnswer("$STORY_ANSWER_SEEN has no public static hook ${hook.substringAfter("->")}")
    }
    fun stub(name: String, returns: String) = answer.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(ANSWER_OBJECT)
    } ?: refuseAnswer("$STORY_ANSWER_SEEN has no static $returns $name($ANSWER_OBJECT)")
    return StoryAnswerStubs(
        flush = stub("flush", "V"),
        likeSession = stub("likeSession", ANSWER_OBJECT),
        storyMediaId = stub("storyMediaId", ANSWER_STRING),
    )
}

/**
 * Hands the extension a like right before the like store names its request, with the store and
 * the story's id; a story reply or quick reaction first thing in their send, with the account and
 * the story; and each story viewer's flusher as the viewer builds it. Then fills in the stubs:
 * the flusher's flush, the like store's account and Media's id. Each hook is one call, which the
 * extension never throws out of, so what Instagram does next is as it was.
 */
internal fun hookStoryAnswers(found: StoryAnswerTargets) {
    found.like.addInstructions(
        found.likeAt,
        "invoke-static { p0, ${found.like.parameterRegister(found.likeId)} }, $STORY_LIKED",
    )
    found.reply.addInstructions(0, "invoke-static/range { p0 .. p1 }, $STORY_ANSWERED")
    found.viewer.addInstructions(found.viewerAt, "invoke-static { ${found.viewer.parameterRegister(1)} }, $STORY_VIEWER_OPENED")

    found.stubs.flush.addInstructions(
        0,
        """
            check-cast p0, ${found.flusher}
            invoke-virtual { p0 }, ${found.flusher}->${found.flush}()V
            return-void
        """,
    )
    val owner = found.likeSession.definingClass
    found.stubs.likeSession.addInstructions(
        0,
        """
            check-cast p0, $owner
            invoke-virtual { p0 }, $owner->${found.likeSession.name}()$USER_SESSION
            move-result-object p0
            return-object p0
        """,
    )
    found.stubs.storyMediaId.addInstructions(
        0,
        """
            check-cast p0, ${STORY_REPLY_SHAPE[1]}
            invoke-virtual { p0 }, ${found.mediaId}
            move-result-object p0
            return-object p0
        """,
    )
}

private fun Method.answerCode(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.answerCall(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.answerString(): String? =
    if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) ((this as ReferenceInstruction).reference as? StringReference)?.string else null

private fun MethodReference.answerKey(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** A static `(I)String` method: the shape of Redex's pools of shared strings. */
private fun Method.answerPool() = AccessFlags.STATIC.isSet(accessFlags) && returnType == ANSWER_STRING &&
    parameterTypes.map(Any::toString) == listOf("I")
