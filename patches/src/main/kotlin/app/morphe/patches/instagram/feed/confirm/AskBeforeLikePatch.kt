/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.confirm

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method

private const val LIKE_PATCH = "Ask before a like"

internal const val LIKE_CONFIRM = "$EXTENSION_PACKAGE/feed/LikeConfirm;"
internal const val HOLD_LIKE =
    "$LIKE_CONFIRM->hold(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;I)Z"

internal const val HOLD_REEL_LIKE = "$LIKE_CONFIRM->holdReel(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;" +
    "Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;" +
    "Ljava/lang/Object;II)Z"

internal const val HOLD_COMMENT_LIKE = "$LIKE_CONFIRM->holdComment(Ljava/lang/Object;Ljava/lang/Object;" +
    "Ljava/lang/Boolean;Ljava/lang/String;Ljava/lang/String;)Z"

/** The extension's stubs the patch fills: the handler's screen, and a second call to each handler. */
internal const val CONTEXT_OF_STUB = "contextOf"
internal const val LIKE_AGAIN_STUB = "likeAgain"
internal const val LIKE_REEL_AGAIN_STUB = "likeReelAgain"
internal const val LIKE_COMMENT_AGAIN_STUB = "likeCommentAgain"

/** The comment list's store, which the class holding the list's like entry keeps (#614). */
internal const val COMMENT_LIST_REPOSITORY = "Lcom/instagram/comments/mvvm/data/MediaCommentListRepository;"

/** The marker the Like button's handler on a reel files as it starts, which nothing else loads (#603). */
internal const val REEL_LIKE_TAP = "android_purge_26_q3_LikeActionHandler_handleLikeButtonTap"

internal const val MEDIA = "Lcom/instagram/feed/media/Media;"
internal const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
internal const val GET_CONTEXT = "$FRAGMENT->getContext()Landroid/content/Context;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val VIEW = "Landroid/view/View;"

/**
 * The Like button's handler: an instance method taking the post, its position, the module name, a
 * callback and an index, which likes or unlikes the post and files "like_media" for a like. A
 * double tap goes through other code.
 */
internal object LikeButtonFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(MEDIA, "L", "Ljava/lang/String;", FUNCTION0, "I"),
    strings = listOf("like_media"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) && !method.definingClass.startsWith(EXTENSION_ROOT) },
)

/**
 * The Like button under a post, on a reel and beside a comment waits for a question while the switch
 * is on. Included in the default selection with its switch off, so asking is the user's pick.
 */
@Suppress("unused")
val askBeforeLikePatch = bytecodePatch(
    name = "Ask before a like",
    description = "Asks you to confirm before the Like button under a post, on a reel or on a comment likes or " +
        "unlikes it, so a stray tap doesn't do it for you. Starts off. Turn it on in HushGram settings > Feed.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("askBeforeLike")
        val reels = findReelLikeButton()
        val comments = findCommentLikeButton()
        askBeforeLike()
        askBeforeReelLike(reels)
        askBeforeCommentLike(comments)
        enableStatus("askBeforeLike")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$LIKE_PATCH: $why")

/** The Like button's handler, the fragment its class keeps, and the extension's two stubs. */
internal class LikeButtonTargets(
    val handler: MutableMethod,
    val fragment: Field,
    val contextOf: MutableMethod,
    val likeAgain: MutableMethod,
)

/**
 * Finds the Like button's handler and checks it before anything changes: it's the one method of
 * its shape filing like_media, it and its class and the post's position class are public, its class
 * keeps one public fragment, the app's Fragment has getContext, it has a local for the hook's answer
 * and nothing jumps to its first instruction.
 */
internal fun BytecodePatchContext.findLikeButton(): LikeButtonTargets {
    val handler = uniqueMethod(LIKE_PATCH, "Like button handler filing like_media", LikeButtonFingerprint)
    val owner = classDefByOrNull(handler.definingClass) ?: refuse("${handler.definingClass} isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(handler.accessFlags)) {
        refuse("the Like button handler ${handler.definingClass}->${handler.name} isn't public")
    }
    val position = handler.parameterTypes[1].toString()
    val positionClass = classDefByOrNull(position)
    if (positionClass == null || !AccessFlags.PUBLIC.isSet(positionClass.accessFlags)) {
        refuse("the Like button handler takes $position, which isn't a public class in the app")
    }
    val fragments = owner.fields.filter { it.type == FRAGMENT && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val fragment = fragments.singleOrNull() ?: refuse("${owner.type} keeps ${fragments.size} fragments, not one")
    if (!AccessFlags.PUBLIC.isSet(fragment.accessFlags)) refuse("${owner.type}->${fragment.name} isn't public")
    if (classDefByOrNull(FRAGMENT)?.publicInstance("getContext", emptyList(), "Landroid/content/Context;") == null) {
        refuse("the app's Fragment has no public getContext()")
    }
    if (handler.localRegisterCount() < 1) refuse("${owner.type}->${handler.name} has no local for the answer")
    if (0 in handler.jumpTargets()) refuse("a jump or exception handler enters ${owner.type}->${handler.name} at its first instruction")

    val extension = classDefByOrNull(LIKE_CONFIRM) ?: refuse("the extension has no $LIKE_CONFIRM")
    extension.publicStatic(HOLD_LIKE) ?: refuse("the extension has no public static $HOLD_LIKE")
    val stubs = mutableClassDefBy(LIKE_CONFIRM)
    return LikeButtonTargets(
        handler = handler,
        fragment = fragment,
        contextOf = stubs.staticStub(CONTEXT_OF_STUB, listOf(OBJECT), "Landroid/content/Context;", 1)
            ?: refuse("$LIKE_CONFIRM has no static Context $CONTEXT_OF_STUB(Object)"),
        likeAgain = stubs.staticStub(LIKE_AGAIN_STUB, listOf(OBJECT, OBJECT, OBJECT, "Ljava/lang/String;", OBJECT, "I"), "V", 6)
            ?: refuse("$LIKE_CONFIRM has no static void $LIKE_AGAIN_STUB taking what the handler takes"),
    )
}

/**
 * Fills the stubs with the handler's screen (its fragment's context) and a call back into the
 * handler, then asks the extension first in the handler, with its own arguments. A yes returns
 * before Instagram does anything. The context stub works in its parameter's register alone, since a
 * compiled `return null` with an unused parameter may have no other.
 */
internal fun BytecodePatchContext.askBeforeLike() {
    val found = findLikeButton()
    val handler = found.handler
    val owner = handler.definingClass
    val position = handler.parameterTypes[1].toString()
    found.contextOf.addInstructions(
        0,
        """
            check-cast p0, $owner
            iget-object p0, p0, $owner->${found.fragment.name}:$FRAGMENT
            if-nez p0, :attached
            const/4 p0, 0x0
            return-object p0
            :attached
            invoke-virtual { p0 }, $GET_CONTEXT
            move-result-object p0
            return-object p0
        """,
    )
    found.likeAgain.addInstructions(
        0,
        """
            check-cast p0, $owner
            check-cast p1, $MEDIA
            check-cast p2, $position
            check-cast p4, $FUNCTION0
            invoke-virtual/range { p0 .. p5 }, $owner->${handler.name}($MEDIA${position}Ljava/lang/String;${FUNCTION0}I)V
            return-void
        """,
    )
    handler.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p5 }, $HOLD_LIKE
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", handler.getInstruction(0)),
    )
}

/** The Like button's handler on a reel and the extension's stub calling it again. */
internal class ReelLikeTargets(val handler: MutableMethod, val likeAgain: MutableMethod)

/**
 * Finds the Like button's handler on a reel and checks it before anything changes: the one method
 * filing [REEL_LIKE_TAP], public in a public class, taking the button, three of Instagram's public
 * classes, four strings, a callback and two booleans, with a local for the hook's answer and nothing
 * jumping to its first instruction.
 */
internal fun BytecodePatchContext.findReelLikeButton(): ReelLikeTargets {
    val found = classesLoadingString(REEL_LIKE_TAP).flatMap { it.methods }.filter { loadsString(it, REEL_LIKE_TAP) }
    val tap = found.singleOrNull() ?: refuse("expected one method loading \"$REEL_LIKE_TAP\", found ${found.size}")
    val owner = classDefByOrNull(tap.definingClass) ?: refuse("${tap.definingClass} isn't in the app")
    val where = "${owner.type}->${tap.name}"
    if (AccessFlags.STATIC.isSet(tap.accessFlags)) refuse("$where is static")
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(tap.accessFlags)) refuse("$where isn't public")
    val types = tap.parameterTypes.map(CharSequence::toString)
    if (tap.returnType != "V" || types.size != 11 || types[0] != VIEW || types.subList(4, 8).any { it != STRING } ||
        types[8] != FUNCTION1 || types[9] != "Z" || types[10] != "Z"
    ) {
        refuse("$where doesn't take the button, three objects, four strings, a callback and two booleans")
    }
    for (type in types.subList(1, 4)) {
        val classDef = classDefByOrNull(type)
        if (classDef == null || !AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("$where takes $type, which isn't a public class in the app")
    }
    val handler = mutableClassDefBy(owner.type).methods.single {
        it.name == tap.name && it.returnType == tap.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
    if (handler.localRegisterCount() < 1) refuse("$where has no local for the answer")
    if (0 in handler.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")

    val extension = classDefByOrNull(LIKE_CONFIRM) ?: refuse("the extension has no $LIKE_CONFIRM")
    extension.publicStatic(HOLD_REEL_LIKE) ?: refuse("the extension has no public static $HOLD_REEL_LIKE")
    val stub = mutableClassDefBy(LIKE_CONFIRM).staticStub(
        LIKE_REEL_AGAIN_STUB, List(5) { OBJECT } + List(4) { STRING } + listOf(OBJECT, "Z", "Z"), "V", 12,
    ) ?: refuse("$LIKE_CONFIRM has no static void $LIKE_REEL_AGAIN_STUB taking what the reel handler takes")
    return ReelLikeTargets(handler, stub)
}

/**
 * Fills the stub with a call back into the reel's handler, then asks the extension first in the
 * handler, with its own arguments, its booleans going in as ints. A yes returns before Instagram
 * does anything. The question finds its screen through the button the handler is handed.
 */
internal fun askBeforeReelLike(found: ReelLikeTargets) {
    val handler = found.handler
    val owner = handler.definingClass
    val types = handler.parameterTypes.map(CharSequence::toString)
    found.likeAgain.addInstructions(
        0,
        """
            check-cast p0, $owner
            check-cast p1, $VIEW
            check-cast p2, ${types[1]}
            check-cast p3, ${types[2]}
            check-cast p4, ${types[3]}
            check-cast p9, $FUNCTION1
            invoke-virtual/range { p0 .. p11 }, $owner->${handler.name}(${types.joinToString("")})V
            return-void
        """,
    )
    handler.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p11 }, $HOLD_REEL_LIKE
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", handler.getInstruction(0)),
    )
}

/** The comment list's like entry and the extension's stub calling it again. */
internal class CommentLikeTargets(val handler: MutableMethod, val likeAgain: MutableMethod)

/**
 * Finds the comment list's like entry and checks it before anything changes: the one instance
 * method, in a class keeping the comment list's store, taking one of Instagram's public classes, a
 * Boolean and two strings and returning nothing, public in a public class, with a local for the
 * hook's answer and nothing jumping to its first instruction. The heart beside a comment hands it
 * TRUE, a double tap FALSE.
 */
internal fun BytecodePatchContext.findCommentLikeButton(): CommentLikeTargets {
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        if (classDef.fields.none { it.type == COMMENT_LIST_REPOSITORY && !AccessFlags.STATIC.isSet(it.accessFlags) }) return@classDefForEach
        classDef.methods.filterTo(found) { it.commentLikeShaped() }
    }
    val entry = found.singleOrNull()
        ?: refuse("expected one (object, Boolean, String, String) method beside $COMMENT_LIST_REPOSITORY, found ${found.size}")
    val owner = classDefByOrNull(entry.definingClass) ?: refuse("${entry.definingClass} isn't in the app")
    val where = "${owner.type}->${entry.name}"
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(entry.accessFlags)) refuse("$where isn't public")
    val state = entry.parameterTypes[0].toString()
    val stateClass = classDefByOrNull(state)
    if (stateClass == null || !AccessFlags.PUBLIC.isSet(stateClass.accessFlags)) refuse("$where takes $state, which isn't a public class in the app")
    val types = entry.parameterTypes.map(CharSequence::toString)
    val handler = mutableClassDefBy(owner.type).methods.single {
        it.name == entry.name && it.returnType == entry.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
    if (handler.localRegisterCount() < 1) refuse("$where has no local for the answer")
    if (0 in handler.jumpTargets()) refuse("a jump or exception handler enters $where at its first instruction")

    val extension = classDefByOrNull(LIKE_CONFIRM) ?: refuse("the extension has no $LIKE_CONFIRM")
    extension.publicStatic(HOLD_COMMENT_LIKE) ?: refuse("the extension has no public static $HOLD_COMMENT_LIKE")
    val stub = mutableClassDefBy(LIKE_CONFIRM).staticStub(LIKE_COMMENT_AGAIN_STUB, listOf(OBJECT, OBJECT, BOOLEAN, STRING, STRING), "V", 5)
        ?: refuse("$LIKE_CONFIRM has no static void $LIKE_COMMENT_AGAIN_STUB taking what the comment like entry takes")
    return CommentLikeTargets(handler, stub)
}

private fun Method.commentLikeShaped(): Boolean = returnType == "V" && implementation != null &&
    !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.map(CharSequence::toString).let {
        it.size == 4 && it[0].startsWith("L") && it[0] != BOOLEAN && it[0] != STRING && it[1] == BOOLEAN &&
            it[2] == STRING && it[3] == STRING
    }

/**
 * Fills the stub with a call back into the comment list's like entry, then asks the extension first
 * in the entry, with its own arguments. A yes returns before Instagram does anything. A double tap's
 * FALSE goes through unasked, in the extension.
 */
internal fun askBeforeCommentLike(found: CommentLikeTargets) {
    val handler = found.handler
    val owner = handler.definingClass
    val types = handler.parameterTypes.map(CharSequence::toString)
    found.likeAgain.addInstructions(
        0,
        """
            check-cast p0, $owner
            check-cast p1, ${types[0]}
            invoke-virtual/range { p0 .. p4 }, $owner->${handler.name}(${types.joinToString("")})V
            return-void
        """,
    )
    handler.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p4 }, $HOLD_COMMENT_LIKE
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", handler.getInstruction(0)),
    )
}
