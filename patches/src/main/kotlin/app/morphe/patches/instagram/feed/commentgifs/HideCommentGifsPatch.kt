/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.commentgifs

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.comment.COMMENT_MEDIA_KEY
import app.morphe.patches.instagram.misc.comment.GIPHY
import app.morphe.patches.instagram.misc.comment.STRING
import app.morphe.patches.instagram.misc.comment.arguments
import app.morphe.patches.instagram.misc.comment.call
import app.morphe.patches.instagram.misc.comment.classPool
import app.morphe.patches.instagram.misc.comment.code
import app.morphe.patches.instagram.misc.comment.discovering
import app.morphe.patches.instagram.misc.comment.field
import app.morphe.patches.instagram.misc.comment.hashGetter
import app.morphe.patches.instagram.misc.comment.matches
import app.morphe.patches.instagram.misc.comment.one
import app.morphe.patches.instagram.misc.comment.parameters
import app.morphe.patches.instagram.misc.comment.refuse
import app.morphe.patches.instagram.misc.comment.requirePublic
import app.morphe.patches.instagram.misc.comment.treeBacked
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
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
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val COMMENT_GIFS_STATUS = "commentGifs"

private const val PATCH = "Hide GIFs in comments"

internal const val COMMENT_GIFS = "$EXTENSION_PACKAGE/feed/CommentGifs;"
internal const val HIDING = "$COMMENT_GIFS->hiding()Z"
internal const val DROPS_COMMENT = "$COMMENT_GIFS->dropsComment(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Z"
internal const val KEPT_GIF = "$COMMENT_GIFS->keptGif(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val HIDES_MEDIA = "$COMMENT_GIFS->hidesMedia(Ljava/lang/Object;)Z"
internal const val COMPOSER_TYPES = "$COMMENT_GIFS->composerTypes(Ljava/util/Set;)Ljava/util/Set;"

/** Only the comment row builder (`LX/0JCn;->A00` on 454's 386014758) loads both. */
internal const val ROW_MISSING_INFO = "photo_comment_child_missing_overlay_info"
internal const val ROW_SOURCE_MEDIA = " sourceMediaId="

/** What Instagram's tree calls a comment's GIF, on the comment and on a sticker's media. */
private const val GIF_FIELD = "giphy_media_info"
private const val TEXT_FIELD = "text"

private const val MEDIUM = "Lcom/instagram/common/gallery/Medium;"
internal const val KINDS_ENUM_PARENT = "Ljava/lang/Enum;"
internal const val KINDS_SET = "Ljava/util/Set;"

/** The names on the enum of what the comment box's picker offers. */
internal const val AVATAR_KIND = "AVATAR"
private const val GIF_KIND = "GIF"

/** How far before an enum read a picker kinds field may be read, in the code that tests one against the other. */
private const val KIND_READ_REACH = 3

/** Registers the row hook takes before the builder's own code starts. */
private const val ROW_HOOK_REGISTERS = 4

/** The row builder, and what its hooks read. Plain text and numbers only, so keeping it holds none of the app's code. */
internal class CommentRowPlan(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val comment: Int,
    val gif: String,
    val text: String,
    val info: String,
    val infoMedia: String,
    val mediaGif: String,
    val gifResultAt: Int,
    val gifRegister: Int,
    val mediaCtorAt: Int,
    val mediaRegister: Int,
    val stringRegister: Int,
    val freeRegister: Int,
)

/** The picker state's constructor and where it keeps its set of kinds. */
internal class ComposerPlan(val type: String, val parameters: List<String>, val field: String, val writeAt: Int, val register: Int)

private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.text() = "$definingClass->$name:$type"

private fun Method.abstractGetterOf(returns: String) =
    parameterTypes.isEmpty() && returnType == returns && AccessFlags.ABSTRACT.isSet(accessFlags)

private fun ClassDef.requireInterface() {
    if (!AccessFlags.INTERFACE.isSet(accessFlags)) refuse("$type isn't an interface")
}

/**
 * Finds the comment row builder and everything its hooks read: the raw comment it's handed, that
 * comment's GIF, words and media info getters, each proved on the tree-backed comment by the key it
 * reads, the media a sticker comes through, and the registers the hooks use. Every refusal happens
 * here, so the hooks that follow can't stop half way.
 */
internal fun BytecodePatchContext.findCommentRow(): CommentRowPlan = discovering(PATCH) {
    val classes = classPool()
    val builder = classesLoadingString(ROW_MISSING_INFO).flatMap { it.methods }
        .filter { loadsString(it, ROW_MISSING_INFO) && loadsString(it, ROW_SOURCE_MEDIA) }
        .one("comment row builder")
    val where = "${builder.definingClass}->${builder.name}"
    val types = builder.parameterTypes.map(Any::toString)
    val raw = types.distinct().mapNotNull { classes[it] }
        .filter { type -> type.methods.any { it.abstractGetterOf(GIPHY) } }
        .one("raw comment among $where's parameters")
    raw.requireInterface()
    val comment = types.indices.filter { types[it] == raw.type }.one("raw comment parameter of $where")

    val gif = raw.methods.filter { it.abstractGetterOf(GIPHY) }.one("raw comment's GIF getter")
    val tree = classes.values.filter { raw.type in it.interfaces && treeBacked(it, classes) }.one("tree comment")
    if (!gif.matches(hashGetter(tree, GIF_FIELD, GIPHY, classes))) refuse("${gif.text()} doesn't read $GIF_FIELD")

    val treeText = hashGetter(tree, TEXT_FIELD, STRING, classes)
    val text = raw.methods.filter { it.matches(treeText) && AccessFlags.ABSTRACT.isSet(it.accessFlags) }.one("raw comment's text getter")

    val treeInfo = hashGetter(tree, COMMENT_MEDIA_KEY, null, classes)
    val info = raw.methods.filter { it.matches(treeInfo) && AccessFlags.ABSTRACT.isSet(it.accessFlags) }.one("raw comment's $COMMENT_MEDIA_KEY getter")
    val infoType = classes[info.returnType] ?: refuse("no class for ${info.returnType}")
    requirePublic(infoType)
    infoType.requireInterface()
    val infoMedia = infoType.methods.filter { it.abstractGetterOf(MEDIA) }.one("$COMMENT_MEDIA_KEY's media getter")

    val mediaType = classes[MEDIA] ?: refuse("no class for $MEDIA")
    requirePublic(mediaType)
    val mediaGif = hashGetter(mediaType, GIF_FIELD, GIPHY, classes)
    if (!AccessFlags.PUBLIC.isSet(mediaGif.accessFlags)) refuse("${mediaGif.text()} isn't public")

    val code = builder.code()
    val targets = builder.jumpTargets()
    if (0 in targets) refuse("$where jumps to its first instruction")
    if (builder.localRegisterCount() < ROW_HOOK_REGISTERS) refuse("$where has fewer than $ROW_HOOK_REGISTERS locals")

    val gifCall = code.indices.filter { at ->
        code[at].call()?.let { it.definingClass == raw.type && it.matches(gif) } == true
    }.one("call to ${gif.text()} in $where")
    val gifResult = code.getOrNull(gifCall + 1)
    if (gifResult?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where drops the GIF getter's answer")
    if (gifCall + 2 in targets) refuse("$where jumps to right after its GIF read")

    val ctor = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_DIRECT && code[at].call()?.let { call ->
            call.name == "<init>" && call.parameters().let { MEDIUM in it && MEDIA in it && STRING in it }
        } == true
    }.one("constructor of a row's media in $where")
    if (ctor in targets) refuse("$where jumps to its row media's constructor")
    if (ctor <= gifCall + 1) refuse("$where builds a row's media before reading its GIF")
    val parameters = code[ctor].call()!!.parameters()
    val arguments = code[ctor].arguments()
    val free = try {
        builder.getFreeRegisterProvider(ctor, 1, arguments).getFreeRegister4Bit()
    } catch (full: IllegalStateException) {
        refuse("no free register before a row's media: ${full.message}")
    } catch (full: IllegalArgumentException) {
        refuse("no free register before a row's media: ${full.message}")
    }
    CommentRowPlan(
        builder.definingClass, builder.name, types, builder.returnType, comment,
        gif.text(), text.text(), info.text(), infoMedia.text(), mediaGif.text(),
        gifCall + 1, (gifResult as OneRegisterInstruction).registerA,
        ctor, arguments[parameters.indexOf(MEDIA) + 1], arguments[parameters.indexOf(STRING) + 1], free,
    )
}

/** The enum of the comment box's picker kinds: two constants, named [AVATAR_KIND] and [GIF_KIND]. */
private fun BytecodePatchContext.pickerKinds(): ClassDef = classesLoadingString(AVATAR_KIND).filter { type ->
    type.superclass == KINDS_ENUM_PARENT &&
        type.methods.any { it.name == "<clinit>" && loadsString(it, AVATAR_KIND) && loadsString(it, GIF_KIND) } &&
        type.fields.count { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type.type } == 2
}.one("enum of the comment box's picker kinds")

/**
 * Finds the set of kinds the comment box's state holds: the one Set field read right before an
 * enum constant is, in the code that asks the set for it, and the state's constructor that writes it.
 */
internal fun BytecodePatchContext.findComposer(): ComposerPlan = discovering(PATCH) {
    val kinds = pickerKinds()
    val constants = kinds.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == kinds.type }
    val readers = constants.flatMap { classesAccessing(kinds.type, it.name, Opcode.SGET_OBJECT) }.distinctBy { it.type }
    val fields = readers.flatMap { it.methods }.flatMap { method ->
        val code = method.code()
        code.indices.filter { code[it].opcode == Opcode.SGET_OBJECT && code[it].field()?.definingClass == kinds.type }.flatMap { read ->
            (maxOf(0, read - KIND_READ_REACH) until read).mapNotNull { at ->
                code[at].takeIf { it.opcode == Opcode.IGET_OBJECT }?.field()?.takeIf { it.type == KINDS_SET }
            }
        }
    }.distinctBy { it.text() }
    val field = fields.one("set of picker kinds next to ${kinds.type}'s reads")
    val state = classPool()[field.definingClass] ?: refuse("no class for ${field.definingClass}")
    val constructor = state.methods.filter { method ->
        method.name == "<init>" && method.code().any { it.opcode == Opcode.IPUT_OBJECT && it.field()?.text() == field.text() }
    }.one("constructor of ${state.type} writing ${field.text()}")
    val code = constructor.code()
    val write = code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].field()?.text() == field.text() }.one("write of ${field.text()}")
    if (write in constructor.jumpTargets()) refuse("${state.type}'s constructor jumps to its write of ${field.text()}")
    ComposerPlan(state.type, constructor.parameterTypes.map(Any::toString), field.text(), write, (code[write] as TwoRegisterInstruction).registerA)
}

internal fun BytecodePatchContext.findCommentRowOrWarn(): CommentRowPlan? = try {
    findCommentRow()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in leaving comment rows alone.")
    null
}

internal fun BytecodePatchContext.findComposerOrWarn(): ComposerPlan? = try {
    findComposer()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in leaving the comment box's button alone.")
    null
}

/**
 * Writes the row's three hooks, highest index first so the plan's indexes stay good. A sticker's media
 * and its string go before the media's constructor, a kept comment's GIF right after the builder reads
 * it, and at the very top a comment with nothing but a picture gets no row.
 */
internal fun BytecodePatchContext.hideCommentRowGifs(plan: CommentRowPlan) = discovering(PATCH) {
    val method = mutableClassDefBy(plan.type).methods.single {
        it.name == plan.name && it.returnType == plan.returnType && it.parameterTypes.map(CharSequence::toString) == plan.parameters
    }
    if (method.getInstruction<OneRegisterInstruction>(plan.gifResultAt).opcode != Opcode.MOVE_RESULT_OBJECT ||
        method.getInstruction(plan.mediaCtorAt).opcode != Opcode.INVOKE_DIRECT
    ) {
        refuse("comment row builder changed before it could be hooked")
    }
    method.addInstructionsWithLabels(
        plan.mediaCtorAt,
        """
            if-eqz v${plan.mediaRegister}, :go
            invoke-virtual { v${plan.mediaRegister} }, ${plan.mediaGif}
            move-result-object v${plan.freeRegister}
            invoke-static { v${plan.freeRegister} }, $HIDES_MEDIA
            move-result v${plan.freeRegister}
            if-eqz v${plan.freeRegister}, :go
            const/4 v${plan.mediaRegister}, 0x0
            const/4 v${plan.stringRegister}, 0x0
        """,
        ExternalLabel("go", method.getInstruction(plan.mediaCtorAt)),
    )
    method.addInstructions(
        plan.gifResultAt + 1,
        """
            invoke-static/range { v${plan.gifRegister} .. v${plan.gifRegister} }, $KEPT_GIF
            move-result-object v${plan.gifRegister}
            check-cast v${plan.gifRegister}, $GIPHY
        """,
    )
    val comment = method.parameterRegisterNumber(plan.comment)
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDING
            move-result v0
            if-eqz v0, :keep
            move-object/from16 v0, v$comment
            invoke-interface { v0 }, ${plan.gif}
            move-result-object v1
            invoke-interface { v0 }, ${plan.info}
            move-result-object v2
            const/4 v3, 0x0
            if-eqz v2, :read
            invoke-interface { v2 }, ${plan.infoMedia}
            move-result-object v2
            if-eqz v2, :read
            invoke-virtual { v2 }, ${plan.mediaGif}
            move-result-object v3
            :read
            invoke-interface { v0 }, ${plan.text}
            move-result-object v0
            invoke-static { v1, v3, v0 }, $DROPS_COMMENT
            move-result v0
            if-eqz v0, :keep
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("keep", method.getInstruction(0)),
    )
}

/** Hands the box's picker kinds to [COMPOSER_TYPES] right before the state keeps them. */
internal fun BytecodePatchContext.hideComposerGifs(plan: ComposerPlan) = discovering(PATCH) {
    val constructor = mutableClassDefBy(plan.type).methods.single {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == plan.parameters
    }
    if (constructor.getInstruction(plan.writeAt).opcode != Opcode.IPUT_OBJECT) {
        refuse("${plan.type}'s constructor changed before its picker kinds could be hooked")
    }
    constructor.addInstructions(
        plan.writeAt,
        """
            invoke-static/range { v${plan.register} .. v${plan.register} }, $COMPOSER_TYPES
            move-result-object v${plan.register}
        """,
    )
}

/**
 * Takes GIFs and stickers out of a post's comments and off the comment box (#511). See CommentGifs in
 * the extension. In the default selection with its switch off.
 */
@Suppress("unused")
val hideCommentGifsPatch = bytecodePatch(
    name = "Hide GIFs in comments",
    description = "Takes GIFs and stickers out of comments, and the GIF button off the comment box. " +
        "A comment with words keeps them. One that's only a GIF goes, along with its replies. Starts off. " +
        "Turn it on in HushGram settings > Comments.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(COMMENT_GIFS_STATUS)
        findCommentRowOrWarn()?.let { hideCommentRowGifs(it) }
        findComposerOrWarn()?.let { hideComposerGifs(it) }
        enableStatus(COMMENT_GIFS_STATUS)
    }
}
