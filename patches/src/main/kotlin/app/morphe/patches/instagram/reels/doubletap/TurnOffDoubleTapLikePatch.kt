/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.doubletap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Turn off double tap to like"
private const val DOUBLE_TAP_LIKE = "$EXTENSION_PACKAGE/reels/DoubleTapLike;"
internal const val HOLD_BACK_POST = "$DOUBLE_TAP_LIKE->holdBackPost()Z"
internal const val LIKE_ACTION = "$DOUBLE_TAP_LIKE->likeAction(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val HOLD_BACK_COMMENT = "$DOUBLE_TAP_LIKE->holdBackComment()Z"
internal const val HOLD_BACK_MESSAGE = "$DOUBLE_TAP_LIKE->holdBackMessage()Z"

/** The report the feed's onDoubleTapMedia files when it has no activity: the one string it holds. */
internal const val FEED_DOUBLE_TAP = "DefaultMediaHolderGestureDetectorDelegateImpl#onDoubleTapMedia called with null activity"

/**
 * How many kinds of post must hand a double tap to the feed's like: the single photo and at least
 * two more, as a carousel and a video do. 449 has seven.
 */
private const val MIN_LIKE_DELEGATES = 3

/** The first two parameters of the feed's double-tap like: the post's view, then the post. */
private val DOUBLE_TAP_LIKE_STARTS = listOf("Landroid/view/View;", "Lcom/instagram/feed/media/Media;")

/**
 * What a comment row's double tap loads. One kind files [FB_COMMENT_DOUBLE_TAP] for a comment that
 * came from Facebook, which it doesn't like; the other names the like it files, [LIKE_COMMENT] or
 * [UNLIKE_COMMENT].
 */
internal const val FB_COMMENT_DOUBLE_TAP = "fb_comment_double_tap"
internal const val LIKE_COMMENT = "like_comment"
internal const val UNLIKE_COMMENT = "unlike_comment"
private const val GESTURE_LISTENER = "Landroid/view/GestureDetector\$SimpleOnGestureListener;"

/**
 * What a chat's double tap on a message loads: the count of double-tap tips it has shown, then the
 * reaction's source. The chat's gesture listeners and its Compose message list both call it.
 */
internal const val MESSAGE_TIP_COUNT = "should_show_like_direct_message_count"
internal const val MESSAGE_DOUBLE_TAP = "double_tap"
private const val MESSAGE_ID = "Lcom/instagram/model/direct/messageid/MessageIdentifier;"

/** The Reels gesture handler's double tap, and its setter for the action a double tap likes through. */
internal const val HANDLE_DOUBLE_TAP = "GestureActionHandler_handleDoubleTapMedia"
internal const val SET_LIKE_ACTION = "GestureActionHandler_setOnLikeMediaAction"

/**
 * Keeps a double tap on a post or a reel from liking it. See DoubleTapLike in the extension for
 * where each double tap likes and what the hooks ask.
 *
 * Off in the default selection, as Hushfacebook's is: a double tap to like is a gesture people use on
 * purpose, so taking it away is a choice to make. Picked, its switch starts on.
 */
@Suppress("unused")
val turnOffDoubleTapLikePatch = bytecodePatch(
    name = "Turn off double tap to like",
    description = "Stops a double tap on a post or a reel from liking it, and the heart doesn't show. Switches for " +
        "comments and chat messages start off. A single tap still does what it did, and the Like button still likes.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        // Both anchors are found and checked, and SettingsStatus is confirmed to carry the switch's
        // method, before either hook changes an instruction.
        requireStatusMethod("doubleTapLike")
        turnOffDoubleTapLikes()
        enableStatus("doubleTapLike")
    }
}

/** Finds and checks both double taps, then hooks them. A build that fails any check is left as it was. */
internal fun BytecodePatchContext.turnOffDoubleTapLikes() {
    val found = findDoubleTaps()
    holdBackPostDoubleTap(found)
    emptyReelLikeAction(found)
    holdBackCommentDoubleTaps(found)
    holdBackMessageDoubleTap(found)
}

/**
 * The double taps: the feed's double-tap like, which [post] (the single photo's delegate) calls like
 * every other kind of post's, the Reels handler's read of its like action at [read], each comment
 * row's double tap in [comments], and the chat's double tap on a message in [message].
 */
internal class DoubleTaps(
    val post: Method,
    val like: Method,
    val reel: Method,
    val read: Int,
    val action: FieldReference,
    val comments: List<Method>,
    val message: Method,
)

internal fun BytecodePatchContext.findDoubleTaps(): DoubleTaps {
    val posts = mutableListOf<Method>()
    val reels = mutableListOf<Method>()
    val setters = mutableListOf<Method>()
    val listeners = mutableListOf<Method>()
    val messages = mutableListOf<Method>()
    // Every method called with a post's view and the post, and the methods that call it.
    val likeShapes = mutableMapOf<String, MethodReference>()
    val likeCallers = mutableMapOf<String, MutableMap<String, Method>>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            val code = method.code()
            if (code.any { it.stringLoaded() == FEED_DOUBLE_TAP }) posts += method
            val markers = method.markers()
            if (HANDLE_DOUBLE_TAP in markers) reels += method
            if (SET_LIKE_ACTION in markers) setters += method
            if (classDef.superclass == GESTURE_LISTENER && method.isDoubleTapListener()) listeners += method
            if (code.any { it.stringLoaded() == MESSAGE_TIP_COUNT } && code.any { it.stringLoaded() == MESSAGE_DOUBLE_TAP }) messages += method
            code.mapNotNull { it.likeShapedCall() }.forEach { call ->
                likeShapes.putIfAbsent(call.text(), call)
                likeCallers.getOrPut(call.text()) { mutableMapOf() }[method.text()] = method
            }
        }
    }
    val post = posts.singleOrNull() ?: refuse("expected one feed double tap holding \"$FEED_DOUBLE_TAP\", found ${posts.size}")

    // Each kind of post (a photo, a carousel, a video, a map, ...) has its own gesture delegate,
    // and each hands a double tap to one shared method that plays the heart and likes. The single
    // photo's delegate is the one with a string to find it by, so the shared method is the one it
    // calls with the post's view and the post.
    val likeCalls = post.code().mapNotNull { it.likeShapedCall() }.distinctBy { it.text() }
    val likeCall = likeCalls.singleOrNull()
        ?: refuse("the feed double tap ${post.definingClass}->${post.name} calls ${likeCalls.size} methods with a view and the post, expected one")
    val like = classDefByOrNull(likeCall.definingClass)?.methods?.singleOrNull { it.text() == likeCall.text() }
        ?: refuse("the feed's double-tap like ${likeCall.text()} isn't in the app")
    if (AccessFlags.STATIC.isSet(like.accessFlags) || AccessFlags.ABSTRACT.isSet(like.accessFlags) || like.implementation == null) {
        refuse("the feed's double-tap like ${like.text()} isn't an instance method with a body")
    }
    // Each delegate is handed what the like takes after the view, the post among it. Fewer than the
    // photo's and two other kinds of post's calling the like means the others like somewhere the
    // guard doesn't reach, and so does a delegate calling a second method shaped exactly like it.
    val carried = like.parameterTypes.drop(1).map(Any::toString)
    val carries = { method: Method -> method.parameterTypes.map(Any::toString).toMutableList().let { left -> carried.all(left::remove) } }
    val delegates = likeCallers[like.text()].orEmpty().values.count(carries)
    if (delegates < MIN_LIKE_DELEGATES) {
        refuse("the feed's double-tap like ${like.text()} is called by $delegates posts' delegates, expected at least $MIN_LIKE_DELEGATES, so other posts like elsewhere")
    }
    val likeParameters = like.parameterTypes.map(Any::toString)
    likeShapes.values.firstOrNull { other ->
        other.text() != like.text() && other.parameterTypes.map(Any::toString) == likeParameters &&
            likeCallers[other.text()].orEmpty().values.any(carries)
    }?.let { refuse("a post's delegate calls ${it.text()}, a second like shaped as ${like.text()} is, so its posts like where the guard doesn't reach") }
    // The guard borrows v0 at index 0.
    if (like.localRegisterCount() < 1) refuse("the feed's double-tap like ${like.text()} has no local register")

    val reel = reels.singleOrNull() ?: refuse("expected one method marked $HANDLE_DOUBLE_TAP, found ${reels.size}")
    val setter = setters.singleOrNull() ?: refuse("expected one method marked $SET_LIKE_ACTION, found ${setters.size}")
    if (setter.definingClass != reel.definingClass) refuse("$SET_LIKE_ACTION isn't in ${reel.definingClass}")
    val written = setter.code().filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.fieldReference() }
        .filter { it.definingClass == reel.definingClass }.distinctBy { it.toString() }
    val action = written.singleOrNull() ?: refuse("$SET_LIKE_ACTION writes ${written.size} fields of ${reel.definingClass}, expected one")

    // The handler reads its like action once and skips the like when it's null.
    val code = reel.code()
    val reads = code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT && code[it].fieldReference()?.toString() == action.toString() }
    val read = reads.singleOrNull() ?: refuse("${reel.definingClass}->${reel.name} reads its like action ${reads.size} times, expected once")
    val register = (code[read] as TwoRegisterInstruction).registerA
    val check = code.getOrNull(read + 1)
    if (check?.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != register) {
        refuse("${reel.definingClass}->${reel.name} doesn't check its like action for null straight after reading it")
    }
    // Each comment row's double tap likes the comment, and the guard borrows v0 at index 0.
    val comments = listeners.filter { isCommentDoubleTap(it) }
    if (comments.isEmpty()) {
        refuse("no comment row's double tap loads \"$FB_COMMENT_DOUBLE_TAP\", or both \"$LIKE_COMMENT\" and \"$UNLIKE_COMMENT\"")
    }
    comments.firstOrNull { it.localRegisterCount() < 1 }?.let { refuse("the comment double tap ${it.definingClass}->onDoubleTap has no local register") }
    val message = findMessageDoubleTap(messages)
    return DoubleTaps(post, like, reel, read, action, comments, message)
}

/**
 * The chat's double tap on a message: the one method loading [MESSAGE_TIP_COUNT] and
 * [MESSAGE_DOUBLE_TAP], returning nothing, that hands one message to a reaction through an
 * interface. Other methods read the tip count to show the tip, and they load no source.
 */
private fun findMessageDoubleTap(messages: List<Method>): Method {
    val message = messages.singleOrNull()
        ?: refuse("expected one message double tap loading \"$MESSAGE_TIP_COUNT\" and \"$MESSAGE_DOUBLE_TAP\", found ${messages.size}")
    val name = "${message.definingClass}->${message.name}"
    if (message.returnType != "V" || message.implementation == null) refuse("the message double tap $name doesn't return nothing")
    // Its reaction: an interface call handed the message's id, the only one the method makes.
    val reactions = message.code().mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@mapNotNull null
        ((instruction as ReferenceInstruction).reference as? MethodReference)
            ?.takeIf { call -> call.returnType == "V" && call.parameterTypes.any { it.toString() == MESSAGE_ID } }
    }
    if (reactions.size != 1) refuse("the message double tap $name hands a message's id to ${reactions.size} reactions, expected one")
    // The guard borrows v0 at index 0.
    if (message.localRegisterCount() < 1) refuse("the message double tap $name has no register of its own")
    return message
}

/**
 * First thing in the feed's double-tap like: return while the switch holds it back, before the
 * heart and the like. The delegates still run the rest of their double tap, such as the photo's
 * product tags. The Like button likes through other code.
 */
private fun BytecodePatchContext.holdBackPostDoubleTap(found: DoubleTaps) {
    mutable(found.like).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $HOLD_BACK_POST
                move-result v0
                if-eqz v0, :tap
                return-void
            """,
            ExternalLabel("tap", getInstruction(0)),
        )
    }
}

/**
 * First thing in each comment row's double tap: return false while the switch holds it back, as the
 * row does itself for a comment it can't like, before the like and its haptic tap.
 */
private fun BytecodePatchContext.holdBackCommentDoubleTaps(found: DoubleTaps) {
    for (comment in found.comments) mutable(comment).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $HOLD_BACK_COMMENT
                move-result v0
                if-eqz v0, :tap
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("tap", getInstruction(0)),
        )
    }
}

/**
 * First thing in the chat's double tap on a message: return while the switch holds it back, before
 * the tip count and the reaction. Whoever called it has already taken the double tap, so nothing
 * else happens. A long press opens the reactions through other code.
 */
private fun BytecodePatchContext.holdBackMessageDoubleTap(found: DoubleTaps) {
    mutable(found.message).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $HOLD_BACK_MESSAGE
                move-result v0
                if-eqz v0, :tap
                return-void
            """,
            ExternalLabel("tap", getInstruction(0)),
        )
    }
}

/**
 * After the Reels handler reads its like action, the extension's answer in its place: the action,
 * or null while the switch holds the double tap back. The range form passes the register whatever
 * its number.
 */
private fun BytecodePatchContext.emptyReelLikeAction(found: DoubleTaps) {
    val method = mutable(found.reel)
    val register = (method.getInstruction(found.read) as TwoRegisterInstruction).registerA
    method.addInstructions(
        found.read + 1,
        """
            invoke-static/range { v$register .. v$register }, $LIKE_ACTION
            move-result-object v$register
            check-cast v$register, ${found.action.type}
        """,
    )
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

/** A gesture listener's onDoubleTap(MotionEvent), as each comment row's double tap is. */
private fun Method.isDoubleTapListener(): Boolean =
    name == "onDoubleTap" && returnType == "Z" && !AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/MotionEvent;")

/**
 * A comment row's double tap: a gesture listener's onDoubleTap that loads [FB_COMMENT_DOUBLE_TAP],
 * or both [LIKE_COMMENT] and [UNLIKE_COMMENT], itself or from a pool of shared strings. Redex asks
 * a pool for [LIKE_COMMENT] in 450's x86 build (385611439), where the others load it themselves
 * (#95), and that row went unguarded.
 */
internal fun BytecodePatchContext.isCommentDoubleTap(method: Method): Boolean {
    val code = method.code()
    val strings = code.indices.mapNotNullTo(HashSet()) { stringLoadedAt(code, it) }
    return FB_COMMENT_DOUBLE_TAP in strings || (LIKE_COMMENT in strings && UNLIKE_COMMENT in strings)
}

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/** A call to a method taking a post's view and the post first and returning nothing, or null. */
private fun Instruction.likeShapedCall(): MethodReference? =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { call ->
        call.returnType == "V" && call.parameterTypes.take(2).map(CharSequence::toString) == DOUBLE_TAP_LIKE_STARTS
    }

private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
