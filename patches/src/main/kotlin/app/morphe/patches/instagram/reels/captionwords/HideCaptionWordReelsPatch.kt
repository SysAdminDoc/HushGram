/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.captionwords

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.authorBridges
import app.morphe.patches.instagram.download.captionBridges
import app.morphe.patches.instagram.feed.commentgifs.ROW_MISSING_INFO
import app.morphe.patches.instagram.feed.commentgifs.ROW_SOURCE_MEDIA
import app.morphe.patches.instagram.misc.comment.GIPHY
import app.morphe.patches.instagram.misc.comment.classPool
import app.morphe.patches.instagram.misc.comment.commentUserGetter
import app.morphe.patches.instagram.misc.comment.discovering
import app.morphe.patches.instagram.misc.comment.hashGetter
import app.morphe.patches.instagram.misc.comment.matches
import app.morphe.patches.instagram.misc.comment.one
import app.morphe.patches.instagram.misc.comment.treeBacked
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

private const val PATCH = "Hide reels by caption words"
internal const val CAPTION_WORD_REELS = "$EXTENSION_PACKAGE/reels/CaptionWordReels;"
internal const val CAPTION_PAGE_FILTER = "$CAPTION_WORD_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"
internal const val COMMENTS_ON = "$CAPTION_WORD_REELS->commentsOn()Z"
internal const val DROPS_WORDS_COMMENT = "$CAPTION_WORD_REELS->dropsComment(Ljava/lang/String;Ljava/lang/Object;)Z"

/** What Instagram's tree calls a comment's words. */
private const val COMMENT_TEXT_FIELD = "text"

/** Registers the comment hook takes before the row builder's own code starts. */
private const val COMMENT_HOOK_REGISTERS = 3

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"

/**
 * Leaves reels whose caption holds a word or phrase the user typed out of the Reels tab. Reels the
 * user went to see stay. In the default selection with its switch off and its list empty, so
 * nothing changes until the user picks words. Asked for in #192.
 */
@Suppress("unused")
val hideCaptionWordReelsPatch = bytecodePatch(
    name = "Hide reels by caption words",
    description = "Leaves out reels whose caption holds a word or phrase from your list, or is in a language " +
        "you don't keep. Also on Home does the same for Home's posts, Also in comments hides matching " +
        "comments, and Also in search takes matching rows out of the Explore grid and search results. Starts off. " +
        "Turn it on and type the words or languages in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("captionWordReels")
        val page = findReelsPage(PATCH)
        val caption = findCaptionWordReels()
        val comments = findCommentWordsOrWarn()
        val search = findSearchSectionPages()
        hideCaptionWordReels(page, caption)
        comments?.let { hideCommentWords(it) }
        search.forEach { filterSearchSections(it) }
        enableStatus("captionWordReels")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Checks the extension has the page filter and the item stub, then finds how a post's caption is
 * read: the caption bridges Details' Copy caption uses, Media's caption and that comment's text.
 * Answers the writer for those bridges, and for the author's, Media's user and its username, which
 * the list's @names match (#807). Fails before anything changes when the caption can't be told,
 * since without it there's nothing to match. Without the author the @names match captions only.
 */
internal fun BytecodePatchContext.findCaptionWordReels(): () -> Unit {
    val extension = classDefByOrNull(CAPTION_WORD_REELS) ?: refuse("the extension has no $CAPTION_WORD_REELS")
    if (extension.methods.none { it.isStaticStub("page", listOf(LIST, STRING), LIST) }) {
        refuse("the extension has no static page(List, String)List")
    }
    if (extension.methods.none { it.isStaticStub("media", listOf(OBJECT), OBJECT) }) {
        refuse("the extension has no static media(Object)Object to fill")
    }
    val caption = captionBridges(PATCH, "$PATCH can't read a reel's caption") ?: refuse("can't read a post's caption in this Instagram build")
    val author = authorBridges(PATCH, "$PATCH goes in without @names, so they only match captions")
    return {
        caption()
        author?.invoke()
    }
}

/**
 * Fills the extension's item stub, writes the caption bridges (left as they are when Download any
 * video wrote them first), then hands each Reels page to [CAPTION_PAGE_FILTER] where the page and
 * its fallback meet.
 */
internal fun BytecodePatchContext.hideCaptionWordReels(page: ReelsPageSites, writeCaption: () -> Unit) {
    fillReelsItemMedia(CAPTION_WORD_REELS, page)
    writeCaption()
    filterReelsPage(page, CAPTION_PAGE_FILTER)
}

/**
 * The comment row builder, the raw comment parameter it's handed, and that comment's words and author
 * getters (#807). Plain text and numbers only.
 */
internal class CommentWordsPlan(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val comment: Int,
    val text: String,
    val user: String,
)

private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Finds the comment row builder Hide GIFs in comments hooks too (#807). It builds one row for each
 * comment in a post's comments, a reply included, and answering null leaves that comment out, which
 * every caller already allows. Also finds the raw comment it's handed and that comment's words and
 * author getters, read off the comment's classes rather than the builder's code, so the GIF patch's
 * hook in the builder, before or after this one, doesn't change what's found. Refuses when any of it
 * can't be told.
 */
internal fun BytecodePatchContext.findCommentWords(): CommentWordsPlan = discovering(PATCH) {
    val classes = classPool()
    val builder = classesLoadingString(ROW_MISSING_INFO).flatMap { it.methods }
        .filter { loadsString(it, ROW_MISSING_INFO) && loadsString(it, ROW_SOURCE_MEDIA) }
        .one("comment row builder")
    val where = "${builder.definingClass}->${builder.name}"
    val types = builder.parameterTypes.map(Any::toString)
    val raw = types.distinct().mapNotNull { classes[it] }.filter { type ->
        AccessFlags.INTERFACE.isSet(type.accessFlags) && type.methods.any { method ->
            method.parameterTypes.isEmpty() && method.returnType == GIPHY && AccessFlags.ABSTRACT.isSet(method.accessFlags)
        }
    }.one("raw comment among $where's parameters")
    val comment = types.indices.filter { types[it] == raw.type }.one("raw comment parameter of $where")
    val tree = classes.values.filter { raw.type in it.interfaces && treeBacked(it, classes) }.one("tree comment")
    val treeText = hashGetter(tree, COMMENT_TEXT_FIELD, STRING, classes)
    val text = raw.methods.filter { it.matches(treeText) && AccessFlags.ABSTRACT.isSet(it.accessFlags) }
        .one("raw comment's text getter")
    val user = commentUserGetter(raw, tree)
    if (0 in builder.jumpTargets()) refuse("$where jumps to its first instruction")
    if (builder.localRegisterCount() < COMMENT_HOOK_REGISTERS) refuse("$where has fewer than $COMMENT_HOOK_REGISTERS locals")
    CommentWordsPlan(builder.definingClass, builder.name, types, builder.returnType, comment, text.text(), user.text())
}

internal fun BytecodePatchContext.findCommentWordsOrWarn(): CommentWordsPlan? = try {
    findCommentWords()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in leaving comments alone.")
    null
}

/**
 * Asks [DROPS_WORDS_COMMENT] first thing in the row builder, with the comment's words and author, and
 * answers no row for a comment it drops. [COMMENTS_ON] comes first, so with Also in comments off
 * nothing is read.
 */
internal fun BytecodePatchContext.hideCommentWords(plan: CommentWordsPlan) = discovering(PATCH) {
    val method = mutableClassDefBy(plan.type).methods.single {
        it.name == plan.name && it.returnType == plan.returnType && it.parameterTypes.map(CharSequence::toString) == plan.parameters
    }
    val comment = method.parameterRegisterNumber(plan.comment)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $COMMENTS_ON
            move-result v0
            if-eqz v0, :keep
            move-object/from16 v0, v$comment
            invoke-interface { v0 }, ${plan.text}
            move-result-object v1
            invoke-interface { v0 }, ${plan.user}
            move-result-object v2
            invoke-static { v1, v2 }, $DROPS_WORDS_COMMENT
            move-result v0
            if-eqz v0, :keep
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("keep", method.getInstruction(0)),
    )
}
