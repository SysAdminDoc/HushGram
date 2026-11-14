/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.comment

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.ControlFlow
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val COMMENT_GIF_PICK = "$EXTENSION_PACKAGE/comment/CommentGifPick;"
internal const val GIF_PICK_STATUS = "commentGifPick"
internal const val GIF_PICKED = "$COMMENT_GIF_PICK->picked($STRING$OBJECT)I"
private const val GALLERY_MEDIUM = "Lcom/instagram/common/gallery/Medium;"
/** The name the comment box gives the still it makes of a picked picture. */
private const val COMMENT_IMAGE = "comment_image_"
private const val BITMAP = "Landroid/graphics/Bitmap;"
private val pickGotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/**
 * Send saved GIFs as GIFs (#402). The comment box's gallery pick reads the picked file's path, scales
 * the file into a still, saves that as comment_image_*.jpg and hands it to [pick], the screen's attach.
 * The hook goes at [hookAt], right after the path read that feeds the scaling, and hands the extension
 * the path and the screen ([path] and [screen], copied into [spares]). A 1 back means the extension
 * sends the GIF itself, and the pick goes straight to [exit], the method's shared Unit return, with
 * nothing attached. All of it holds plain references, never the app's classes or methods.
 */
internal data class GifPickPlan(
    val method: MethodReference, val hookAt: Int, val path: Int, val screen: Int, val spares: List<Int>,
    val exit: Int, val pick: MethodReference,
)

/** The gallery pick's hook, or null after the patch log says why, so the GIF reply still goes in. */
internal fun BytecodePatchContext.gifPickOrNull(reply: GifReplyPlan, classes: Map<String, ClassDef>): GifPickPlan? = try {
    findGifPick(reply, classes)
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. Save comment photo goes in without Send saved GIFs as GIFs.")
    null
}

private fun BytecodePatchContext.findGifPick(reply: GifReplyPlan, classes: Map<String, ClassDef>): GifPickPlan {
    if (COMMENT_GIF_PICK !in classes) refuse("missing extension boundary $COMMENT_GIF_PICK")
    val screen = reply.select.definingClass
    val screenClass = classes[screen] ?: refuse("missing native class $screen")

    // The one method that makes the comment box's still and hands the comment screen what it made.
    fun attaches(at: Int, code: List<Instruction>) =
        code[at].opcode == Opcode.INVOKE_VIRTUAL && code[at].call()?.let {
            it.definingClass == screen && it.returnType == "V" && it.parameters() == listOf(GALLERY_MEDIUM)
        } == true
    val method = classesLoadingString(COMMENT_IMAGE).flatMap { it.methods }.filter { method ->
        method.code().let { code -> code.indices.any { attaches(it, code) } } && loadsString(method, COMMENT_IMAGE)
    }.one("comment box's gallery pick")
    val code = method.code()
    val pickAt = code.indices.filter { attaches(it, code) }.one("gallery pick's attach call")
    val pick = code[pickAt].call()!!
    screenClass.methods.filter { it.matches(pick) && it.publicInstance() }.one("comment screen's public attach")

    // The scaling reads the picked file's path out of its Medium right before it's called.
    val scaleAt = code.indices.filter { at -> code[at].opcode == Opcode.INVOKE_STATIC && code[at].call()?.let {
        it.returnType == BITMAP && it.parameters() == listOf(STRING, "I", "I", "I")
    } == true }.one("gallery pick's scaling of the picked file")
    if (scaleAt > pickAt) refuse("gallery pick attaches before it scales the picked file")
    val path = code[scaleAt].arguments().first()
    val readAt = (scaleAt - 1 downTo 0).firstOrNull { code[it].writes(path) } ?: refuse("gallery pick scales a path it never read")
    if (code[readAt].opcode != Opcode.IGET_OBJECT ||
        code[readAt].field()?.let { it.definingClass == GALLERY_MEDIUM && it.type == STRING } != true) {
        refuse("gallery pick scales something other than the picked file's path")
    }
    val hookAt = readAt + 1
    val targets = method.jumpTargets()
    if ((hookAt..scaleAt).any { it in targets }) refuse("a branch enters the gallery pick between its path read and its scaling")

    // The attach's screen is cast once, before the path is read, and every way to the hook passes that cast.
    val screenRegister = code[pickAt].arguments().first()
    val castAt = (pickAt - 1 downTo 0).firstOrNull { code[it].writes(screenRegister) } ?: refuse("gallery pick never sets its screen")
    if (code[castAt].opcode != Opcode.CHECK_CAST || (code[castAt].reference() as? TypeReference)?.type != screen || castAt >= readAt) {
        refuse("gallery pick doesn't cast its screen before it reads the path")
    }
    val flow = ControlFlow.of(method)
    val entersAround = code.indices.any { from ->
        from !in castAt until hookAt && (flow.normal[from] + flow.exceptional[from]).any { it in castAt + 1..hookAt }
    }
    if (entersAround) refuse("gallery pick can reach its path read without casting its screen")

    // Right after the attach, the pick goes to the Unit return every one of its cases shares. Redex
    // renames kotlin.Unit on some ABIs (LX/00h7;->A00 on 454's armeabi-v7a), so the Unit is known by
    // its shape: a static field holding its own class, returned as it's read.
    if (code.getOrNull(pickAt + 1)?.opcode !in pickGotos) refuse("gallery pick doesn't leave right after it attaches")
    val exit = flow.normal[pickAt + 1].single()
    val unit = code[exit]
    val back = code.getOrNull(exit + 1)
    if (unit.opcode != Opcode.SGET_OBJECT || unit.field()?.let { it.type == it.definingClass } != true ||
        back?.opcode != Opcode.RETURN_OBJECT || (back as OneRegisterInstruction).registerA != (unit as OneRegisterInstruction).registerA) {
        refuse("gallery pick leaves somewhere other than its Unit return")
    }

    val spares = try {
        method.getFreeRegisterProvider(hookAt, 2, path, screenRegister).let { provider -> List(2) { provider.getFreeRegister4Bit() } }
    } catch (failure: IllegalStateException) {
        refuse("gallery pick has no free registers for the hook: ${failure.message}")
    } catch (failure: IllegalArgumentException) {
        refuse("gallery pick has no free registers for the hook: ${failure.message}")
    }
    stub(COMMENT_GIF_PICK, "picked", listOf(STRING, OBJECT), "I")
    stub(GIF_REPLY_NATIVE, "clearPick", listOf(OBJECT), "V")
    return GifPickPlan(ImmutableMethodReference.of(method), hookAt, path, screenRegister, spares, exit,
        ImmutableMethodReference.of(pick))
}

/** Only called after [gifPickOrNull] found every step and checked both bridges. */
internal fun BytecodePatchContext.applyGifPick(plan: GifPickPlan) {
    // Attaching nothing closes the gallery and leaves the comment box without a picture, as a failed pick does.
    replace(stub(GIF_REPLY_NATIVE, "clearPick", listOf(OBJECT), "V"), 2, """
        check-cast p0, ${plan.pick.definingClass}
        const/4 v0, 0x0
        invoke-virtual { p0, v0 }, ${plan.pick}
        return-void
    """)
    val method = mutableClassDefBy(plan.method.definingClass).methods.single { it.matches(plan.method) }
    if (!method.getInstruction(plan.hookAt - 1).writes(plan.path)) refuse("gallery pick changed before its hook went in")
    val (path, screen) = plan.spares
    method.addInstructionsWithLabels(plan.hookAt, """
        move-object/from16 v$path, v${plan.path}
        move-object/from16 v$screen, v${plan.screen}
        invoke-static { v$path, v$screen }, $GIF_PICKED
        move-result v$path
        if-nez v$path, :sent
    """.trimIndent(), ExternalLabel("sent", method.getInstruction(plan.exit)))
}
