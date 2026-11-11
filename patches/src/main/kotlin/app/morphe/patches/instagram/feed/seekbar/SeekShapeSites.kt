/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val SEEK_SHAPES = "$EXTENSION_PACKAGE/feed/SeekShapes;"
internal const val STORY_BAR_HOOK = "$SEEK_SHAPES->storyBar(Landroid/view/View;)V"
internal const val STORY_SEGMENT_HOOK =
    "$SEEK_SHAPES->storySegment(Landroid/graphics/Canvas;Landroid/graphics/RectF;FFLandroid/graphics/Paint;)V"
internal const val CHAT_VIDEO_HOOK = "$SEEK_SHAPES->chatVideo(Landroid/widget/SeekBar;)V"

/** The story viewer's progress bar, a View drawing each segment as a rounded rectangle. Not renamed by Redex. */
internal const val SEGMENTED_PROGRESS_BAR = "Lcom/instagram/ui/widget/segmentedprogressbar/SegmentedProgressBar;"
internal const val DRAW_ROUND_RECT = "Landroid/graphics/Canvas;->drawRoundRect(Landroid/graphics/RectF;FFLandroid/graphics/Paint;)V"

/** The argument key the media viewer opened from a chat reads its thread with, held by its onCreateView. */
internal const val CHAT_VIDEO_KEY = "sparse_media_viewer_reply_thread_key"
private const val SEEK_BAR = "Landroidx/appcompat/widget/AppCompatSeekBar;"
private const val CREATE_VIEW_SHAPE = "(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;"
private const val SHAPES_PATCH = "Seek bar on feed videos"

/** The story progress bar's onDraw, which takes [STORY_BAR_HOOK] first, and each method's drawRoundRect calls by index. */
internal class StoryBarSites(val onDraw: Method, val draws: Map<Method, List<Int>>)

/** The chat media viewer's onCreateView and the index right after it keeps the seek bar it built. */
internal class ChatVideoSite(val method: Method, val after: Int, val register: Int)

/**
 * The seek bar shape for the story progress segments (#630): [SEGMENTED_PROGRESS_BAR], its public
 * onDraw(Canvas) with nothing jumping to its start, and every Canvas.drawRoundRect its methods make,
 * one or more. On 454.0.0.54.77 those are onDraw's four and its helpers A0A's three, A0B's two and
 * A00's one, all in 35c form. Null after the log says why, so the feed's seek bar goes in without it.
 */
internal fun BytecodePatchContext.findStoryBarShape(): StoryBarSites? = try {
    val bar = classDefByOrNull(SEGMENTED_PROGRESS_BAR) ?: shapeRefuse("this build has no $SEGMENTED_PROGRESS_BAR")
    val onDraw = bar.methods.singleOrNull {
        it.name == "onDraw" && !AccessFlags.STATIC.isSet(it.accessFlags) && it.shapeOf() == "(Landroid/graphics/Canvas;)V"
    } ?: shapeRefuse("$SEGMENTED_PROGRESS_BAR has no onDraw(Canvas)")
    if (bar.methods.any { m -> m.codeOf().any { it.calledOf()?.definingClass == SEEK_SHAPES } }) {
        shapeRefuse("$SEGMENTED_PROGRESS_BAR already calls $SEEK_SHAPES")
    }
    if (0 in onDraw.jumpTargets()) shapeRefuse("something in $SEGMENTED_PROGRESS_BAR->onDraw jumps back to its start")
    val draws = bar.methods.associateWith { m ->
        m.codeOf().withIndex().filter { (_, it) -> it.isRoundRectDraw() }.map { it.index }
    }.filterValues { it.isNotEmpty() }
    if (draws.isEmpty()) shapeRefuse("$SEGMENTED_PROGRESS_BAR draws no rounded rectangle")
    StoryBarSites(onDraw, draws)
} catch (missing: PatchException) {
    patchLog.warning("${missing.message}. $SHAPES_PATCH goes in leaving story progress segments as Instagram draws them.")
    null
}

/**
 * Each of the story progress bar's rounded rectangles goes to [STORY_SEGMENT_HOOK] on the same
 * registers, which makes that same call while the shape is standard, and its onDraw asks
 * [STORY_BAR_HOOK] first thing with the bar.
 */
internal fun BytecodePatchContext.hookStoryBarShape(sites: StoryBarSites) {
    val bar = mutableClassDefBy(SEGMENTED_PROGRESS_BAR)
    for ((stock, indices) in sites.draws) {
        val method = bar.methods.single { it.name == stock.name && it.shapeOf() == stock.shapeOf() }
        val code = stock.codeOf()
        for (index in indices) method.replaceInstruction(index, staticCallOn(code[index], STORY_SEGMENT_HOOK))
    }
    bar.methods.single { it.name == sites.onDraw.name && it.shapeOf() == sites.onDraw.shapeOf() }
        .addInstructions(0, "invoke-static/range { p0 .. p0 }, $STORY_BAR_HOOK")
}

/**
 * The seek bar shape for a video opened from a chat (#630): among the classes loading [CHAT_VIDEO_KEY],
 * the one instance onCreateView(LayoutInflater, ViewGroup, Bundle) that loads it and keeps one
 * [SEEK_BAR] it built in a field, with nothing jumping to the instruction after that, and not yet
 * calling the extension. On 454.0.0.54.77's 386014758 that's 0GOB's, keeping it in 0NHs.A0C. Null
 * after the log says why, so the feed's seek bar goes in without it.
 */
internal fun BytecodePatchContext.findChatVideoShape(): ChatVideoSite? = try {
    val methods = classesLoadingString(CHAT_VIDEO_KEY).flatMap { it.methods }.filter {
        it.name == "onCreateView" && !AccessFlags.STATIC.isSet(it.accessFlags) && it.shapeOf() == CREATE_VIEW_SHAPE &&
            it.codeOf().count { i -> i.keepsSeekBar() } == 1 && loadsString(it, CHAT_VIDEO_KEY)
    }
    val method = methods.singleOrNull()
        ?: shapeRefuse("expected one onCreateView loading $CHAT_VIDEO_KEY that keeps a $SEEK_BAR, found ${methods.size}")
    val code = method.codeOf()
    val where = "${method.definingClass}->${method.name}"
    if (code.any { it.calledOf()?.definingClass == SEEK_SHAPES }) shapeRefuse("$where already calls $SEEK_SHAPES")
    val kept = code.indexOfFirst { it.keepsSeekBar() }
    if (kept + 1 >= code.size || (kept + 1) in method.jumpTargets()) shapeRefuse("something in $where jumps to just after it keeps its seek bar")
    ChatVideoSite(method, kept + 1, (code[kept] as TwoRegisterInstruction).registerA)
} catch (missing: PatchException) {
    patchLog.warning("${missing.message}. $SHAPES_PATCH goes in leaving the seek bar of a video opened from a chat as Instagram draws it.")
    null
}

/** Right after the chat media viewer keeps its seek bar, hands it to [CHAT_VIDEO_HOOK]. */
internal fun BytecodePatchContext.hookChatVideoShape(site: ChatVideoSite) {
    mutableClassDefBy(site.method.definingClass).methods
        .single { it.name == site.method.name && it.shapeOf() == CREATE_VIEW_SHAPE && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .addInstructions(site.after, "invoke-static/range { v${site.register} .. v${site.register} }, $CHAT_VIDEO_HOOK")
}

/** The static [hook] in place of the virtual [call], on the very registers it had. */
private fun staticCallOn(call: Instruction, hook: String): String = when (call) {
    is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
        .take(call.registerCount).joinToString(prefix = "invoke-static { ", postfix = " }, $hook") { "v$it" }
    is RegisterRangeInstruction ->
        "invoke-static/range { v${call.startRegister} .. v${call.startRegister + call.registerCount - 1} }, $hook"
    else -> throw PatchException("$SHAPES_PATCH: a rounded rectangle drawn in a form it can't move")
}

private fun Instruction.isRoundRectDraw(): Boolean =
    (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_VIRTUAL_RANGE) && calledOf()?.toString() == DRAW_ROUND_RECT

private fun Instruction.keepsSeekBar(): Boolean =
    opcode == Opcode.IPUT_OBJECT && ((this as ReferenceInstruction).reference as? FieldReference)?.type == SEEK_BAR

private fun shapeRefuse(detail: String): Nothing = throw PatchException("$SHAPES_PATCH: $detail")

private fun Method.shapeOf(): String = "(${parameterTypes.joinToString("")})$returnType"

private fun Method.codeOf(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.calledOf(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
