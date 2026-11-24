/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fullscreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.literal
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.feed.ambient.ROUNDED_LAYOUT
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val FILL_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->fill(Ljava/lang/Object;F)Z"
internal const val FIT_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->fit()Z"
internal const val STORY_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->story()Z"
internal const val WIDE_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->wide(F)Z"
internal const val STORY_BOUND_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->storyBound(Ljava/lang/Object;I)I"
internal const val STORY_FRAME_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->storyFrame(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val SHARES_POST_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->sharesPost(Ljava/lang/Object;)I"

/** The trace section Instagram's story item binder opens while it binds a story's picture or video. */
internal const val STORY_BIND_MEDIA = "ReelViewerItemBinder.bindMedia"

internal const val STORY_REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"
internal const val REEL_PRODUCT_TYPE = "Lcom/instagram/model/mediatype/ProductType;"

/** The name ProductType gives a reel, which a sticker sharing one carries. */
private const val CLIPS = "CLIPS"

/**
 * The server flag Instagram's reel item layout, which keeps a reel 9:16 on a wider screen, reads first
 * (0A78.A0p on 456's 386214574). Nothing else loads it.
 */
// 457 moved it from 456's 0x81120b001a64fe.
internal const val REEL_INSETS_FLAG = 0x8111e5001a649fL

/** 9:16 as a float's bits, the shape that layout compares the screen's and the item's with. */
private const val NINE_SIXTEENTHS = 0x3f100000

/** The trace section Instagram's story viewer opens while it fits a story to the screen's shape. */
internal const val STORY_ASPECT_RATIO = "ReelViewerMediaAspectRatioUtil.maybeAdjustLayoutBasedOnAspectRatio"

/** The server flag Instagram's reel crop decision reads, which nothing else loads. */
// 457 moved it from 456's 0x8413b7000d04e9.
internal const val REEL_CROP_FLAG = 0x84138d000d04deL

/** Where the viewer was opened from, which the decision takes as a parameter. */
internal const val REEL_VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"

/** 100 as a float's bits, what the decision multiplies its shape difference by to make a percentage. */
private const val HUNDRED = 0x42c80000

/**
 * The method that decides whether a reel's video fills the viewer (true) or fits inside it with bars
 * (false), 0Fc2.A02 on 456's 386214574: the boolean taking the viewer's source that loads
 * [REEL_CROP_FLAG].
 */
internal object ReelCropDecisionFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(literal(REEL_CROP_FLAG)),
    custom = { method, _ -> method.parameterTypes.any { it.toString() == REEL_VIEWER_SOURCE } },
)

/**
 * Where the decision turns its shape difference into a percentage: the one mul-float/2addr whose
 * second register a const/high16 of 100 right before it just wrote. Null when there isn't one.
 */
internal fun findCropPercentage(code: List<Instruction>): Int? = code.indices.filter { at ->
    val hundred = code.getOrNull(at - 1)
    code[at].opcode == Opcode.MUL_FLOAT_2ADDR && hundred?.opcode == Opcode.CONST_HIGH16 &&
        (hundred as NarrowLiteralInstruction).narrowLiteral == HUNDRED &&
        (hundred as OneRegisterInstruction).registerA == (code[at] as TwoRegisterInstruction).registerB
}.singleOrNull()

/**
 * Fill the screen (#288). The decision divides the difference between the viewer's aspect ratio and
 * the reel's by the reel's and multiplies it by 100 ([findCropPercentage]), then crops only below
 * thresholds Instagram's server sends. With Full screen Reels on the viewer is taller, so a 9:16 reel
 * often lands past them and shows bars, and hiding the comment bar does the same (#371). Right after
 * that multiply the decision asks [FILL_HOOK] with the viewer's source, since one comment bar switch
 * only covers reposts, and the percentage, and returns true (fill) when it says so. Otherwise
 * Instagram's own checks run as before.
 */
internal fun BytecodePatchContext.fillReelScreen() {
    val decision = uniqueMethod(FULL_SCREEN_BARS_PATCH, "reel crop decision", ReelCropDecisionFingerprint)
    val code = decision.implementation?.instructions?.toList().orEmpty()
    val multiply = findCropPercentage(code)
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel crop decision doesn't multiply a float by 100 once")
    val percent = (code[multiply] as TwoRegisterInstruction).registerA
    if (percent > 15) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel crop decision keeps its percentage in v$percent, past what an invoke names")
    }
    val source = decision.parameterTypes.indexOfFirst { it.toString() == REEL_VIEWER_SOURCE }
    if (source < 0) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel crop decision takes no $REEL_VIEWER_SOURCE")
    }
    val at = multiply + 1
    decision.requireParameterIntact(FULL_SCREEN_BARS_PATCH, source, listOf(at))
    val answer = decision.freeLocalsAt(FULL_SCREEN_BARS_PATCH, at, 1, except = listOf(percent)).first()
    decision.addInstructionsWithLabels(
        at,
        """
            move-object/from16 v$answer, v${decision.parameterRegisterNumber(source)}
            invoke-static { v$answer, v$percent }, $FILL_HOOK
            move-result v$answer
            if-eqz v$answer, :instagram
            return v$answer
        """,
        ExternalLabel("instagram", decision.getInstruction(at)),
    )
}

/**
 * Show the whole reel (piko #2183). Before the percentage exists the decision already answers true
 * for media Instagram lets crop, so the ask goes in at its first instruction: [FIT_HOOK] says yes
 * and the decision answers false (fit with bars), ahead of Instagram's crop and of [fillReelScreen].
 * The first local is free there, and nothing jumps back to it.
 */
internal fun BytecodePatchContext.fitReelScreen() {
    val decision = uniqueMethod(FULL_SCREEN_BARS_PATCH, "reel crop decision", ReelCropDecisionFingerprint)
    decision.requireLocals(FULL_SCREEN_BARS_PATCH, 1)
    if (0 in decision.jumpTargets()) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: something jumps back to the reel crop decision's first instruction")
    }
    decision.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $FIT_HOOK
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", decision.getInstruction(0)),
    )
}

/** The trace section Instagram's reel video binder opens while it binds a reel's video. */
internal const val REEL_BIND_TRACE = "SimpleVideoLayoutClipsComponent.onBind"

/** The purge flag Instagram's reel sizing names where it reads a post's shape, its width over its height. */
internal const val POST_WIDTH = "original_width"
internal const val POST_HEIGHT = "original_height"

internal const val REEL_FILL = "$EXTENSION_PACKAGE/reels/ReelFill;"
internal const val FIT_VIDEO_HOOK = "$REEL_FILL->fitVideo()Z"
internal const val FIT_BOX_HOOK = "$REEL_FILL->fitBox([Landroid/view/View;Ljava/lang/Integer;Ljava/lang/Integer;I)V"

private const val POST = "Lcom/instagram/feed/media/Media;"
private const val VIEW_ARRAY = "[Landroid/view/View;"
private const val LAYOUT_WIDTH = "Landroid/view/ViewGroup\$LayoutParams;->width:I"
private const val FRAME_GRAVITY = "Landroid/widget/FrameLayout\$LayoutParams;->gravity:I"

/**
 * Where the reel video binder sets its views' gravity: [gravity], the instruction right after it
 * keeps the [views] array (the cover and the video) it sets it on, the register holding the viewer's
 * [width] that it gave the video view, and the binder's [post] field.
 */
internal data class ReelVideoBox(val gravity: Int, val views: Int, val width: Int, val post: FieldReference)

/**
 * Finds the pieces of [ReelVideoBox] in the reel video binder [bind] (0FOl.invoke on 456's
 * 386214574). The width is what its one `LayoutParams.width` store writes, copied there from a
 * register written once, before it, and never again. The views are the array the first
 * filled-new-array of [VIEW_ARRAY] after that store makes, ahead of a `FrameLayout.LayoutParams`
 * gravity store.
 */
internal fun findReelVideoBox(bind: Method): ReelVideoBox {
    val code = bind.implementation?.instructions?.toList().orEmpty()
    fun refers(at: Int, to: String) = (code[at] as? ReferenceInstruction)?.reference?.toString() == to
    val stores = code.indices.filter { code[it].opcode == Opcode.IPUT && refers(it, LAYOUT_WIDTH) }
    val store = stores.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel video binder stores ${stores.size} widths, not one")
    val made = (store + 1 until code.size).firstOrNull {
        (code[it].opcode == Opcode.FILLED_NEW_ARRAY || code[it].opcode == Opcode.FILLED_NEW_ARRAY_RANGE) && refers(it, VIEW_ARRAY)
    }
    val result = made?.let { code.getOrNull(it + 1) }
    if (made == null || result?.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel video binder keeps no array of views after it sizes the video")
    }
    val gravity = made + 2
    if ((gravity until code.size).none { code[it].opcode == Opcode.IPUT && refers(it, FRAME_GRAVITY) }) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel video binder sets no gravity after it keeps its views")
    }
    val views = (result as OneRegisterInstruction).registerA
    val value = (code[store] as TwoRegisterInstruction).registerA
    val copy = (store - 1 downTo 0).firstOrNull { (code[it] as? OneRegisterInstruction)?.registerA == value && code[it].opcode.setsRegister() }
        ?.takeIf { code[it].opcode == Opcode.MOVE || code[it].opcode == Opcode.MOVE_FROM16 || code[it].opcode == Opcode.MOVE_16 }
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel video binder's width doesn't come from a move")
    val width = (code[copy] as TwoRegisterInstruction).registerB
    val writes = code.indices.filter {
        val written = (code[it] as? OneRegisterInstruction)?.registerA
        code[it].opcode.setsRegister() && written != null &&
            (written == width || (code[it].opcode.setsWideRegister() && written + 1 == width))
    }
    if (writes.size != 1 || writes.single() >= copy) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel video binder writes its width's v$width at ${writes.joinToString()}, not once before it")
    }
    val posts = code.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
        ((instruction as ReferenceInstruction).reference as FieldReference).takeIf { it.definingClass == bind.definingClass && it.type == POST }
    }.distinctBy { it.toString() }
    val post = posts.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel video binder reads ${posts.size} posts of its own, not one")
    return ReelVideoBox(gravity, views, width, post)
}

/**
 * A post's own getter for one of its sizes (Media.A6Z for [POST_WIDTH] and A6X for [POST_HEIGHT] on
 * 454): the one instance method on the post taking nothing and answering an Integer that opens with
 * the size's name. Instagram's binder reads the post's shape through its clips model instead, which a
 * post doesn't implement itself, so the hook asks the post for both sizes and divides them.
 */
internal fun BytecodePatchContext.findPostSize(size: String): MethodReference {
    val post = classDefByOrNull(POST) ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: no $POST")
    val found = post.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
            method.returnType == "Ljava/lang/Integer;" && loadsString(method, size)
    }
    return found.singleOrNull()?.let { ImmutableMethodReference(it.definingClass, it.name, it.parameterTypes, it.returnType) }
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: expected one getter on $POST naming \"$size\", found ${found.size}")
}

/**
 * Show the whole reel on a screen wider than a reel (#681). Instagram fits a reel only to the
 * viewer's width: its binder shortens the cover and the video for a post wider than the viewer, but
 * a post narrower than the viewer, a 9:16 reel on a 2:3 tablet, keeps the viewer's whole size, and
 * the video view fills itself and cuts the picture at the top and the bottom. With the crop decision
 * answering fit that's still what happened. So right before the binder sets the two views' gravity,
 * once it has sized them, it asks [FIT_VIDEO_HOOK], and a yes hands the views, the post's width and
 * height ([findPostSize]) and the viewer's width to [FIT_BOX_HOOK], which narrows a view wider than
 * the post at its height. The gravity then centers it. `this` still holds the binder there, three
 * locals up to v15 are free (four when the array sits past v15), and nothing jumps to where the
 * gravity starts.
 */
internal fun BytecodePatchContext.fitReelVideo() {
    val binds = classesLoadingString(REEL_BIND_TRACE).flatMap { it.methods }.filter { loadsString(it, REEL_BIND_TRACE) }
    val found = binds.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: expected one method opening \"$REEL_BIND_TRACE\", found ${binds.size}")
    val types = found.parameterTypes.map(CharSequence::toString)
    val bind = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.returnType == found.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
    if (AccessFlags.STATIC.isSet(bind.accessFlags)) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel video binder is static, so it holds no post")
    }
    val box = findReelVideoBox(bind)
    val postWidth = findPostSize(POST_WIDTH)
    val postHeight = findPostSize(POST_HEIGHT)
    if (box.gravity in bind.jumpTargets()) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: something jumps to where the reel video binder starts its gravity")
    }
    bind.requireThisIntact(FULL_SCREEN_BARS_PATCH, listOf(box.gravity))
    val wide = box.views > 15
    val self = bind.localRegisterCount()
    // The code reads these after it has written a borrowed register.
    val read = listOf(self, box.width, box.views)
    val free = bind.freeLocalsAt(FULL_SCREEN_BARS_PATCH, box.gravity, if (wide) 4 else 3, except = read)
    val post = free[0]
    val across = free[1]
    val width = free[2]
    val views = if (wide) free[3] else box.views
    bind.addInstructionsWithLabels(
        box.gravity,
        """
            invoke-static { }, $FIT_VIDEO_HOOK
            move-result v$post
            if-eqz v$post, :instagram
            move-object/from16 v$post, v$self
            iget-object v$post, v$post, ${box.post.definingClass}->${box.post.name}:${box.post.type}
            if-eqz v$post, :instagram
            invoke-virtual { v$post }, $postWidth
            move-result-object v$across
            invoke-virtual { v$post }, $postHeight
            move-result-object v$post
            move/from16 v$width, v${box.width}
            ${if (wide) "move-object/from16 v$views, v${box.views}" else ""}
            invoke-static { v$views, v$across, v$post, v$width }, $FIT_BOX_HOOK
        """,
        ExternalLabel("instagram", bind.getInstruction(box.gravity)),
    )
}

/**
 * Fill the screen on a screen wider than 9:16 (#491). On a foldable or a large screen, Instagram's
 * reel item layout (the render reading [REEL_INSETS_FLAG], 0A78.A0p on 456's 386214574) first checks the screen is
 * wider than 9:16, then compares the item's own shape with a constant a bit over 9:16 and lays the
 * reel out in a 9:16 column with insets at the sides (or a 9:16 box on a narrower item), so the crop
 * decision sees a 9:16 viewer and [fillReelScreen] has nothing to fill. The ask goes in front of that
 * constant with the item's shape, and a yes jumps to where the screen check sends a phone: the plain
 * whole-item layout, after which the crop decision fills the reel within its cap. The constant
 * writes its register, so nothing reads that register's old value, and nothing jumps to it.
 */
internal fun BytecodePatchContext.fillWideReelScreen() {
    val renders = classesLoading(REEL_INSETS_FLAG).flatMap { it.methods }.filter { method ->
        method.implementation?.instructions?.any {
            it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == REEL_INSETS_FLAG
        } == true
    }
    val found = renders.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: expected one method reading the reel item layout's flag, found ${renders.size}")
    val types = found.parameterTypes.map(CharSequence::toString)
    val render = mutableClassDefBy(found.definingClass).methods.single {
        it.name == found.name && it.returnType == found.returnType && it.parameterTypes.map(CharSequence::toString) == types
    }
    val code = render.implementation?.instructions?.toList().orEmpty()
    val wide = findWideReelCheck(code)
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel item layout doesn't compare the screen and then the item with 9:16")
    val plain = ControlFlow.of(render).normal[wide.screenBranch].first()
    if (wide.constant in render.jumpTargets()) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: something jumps to the reel item layout's 9:16 compare")
    }
    val answer = render.freeLocalsAt(
        FULL_SCREEN_BARS_PATCH, wide.constant, 1, targets = listOf(plain), highest = 255, except = listOf(wide.shape),
    ).first()
    render.addInstructionsWithLabels(
        wide.constant,
        """
            invoke-static/range { v${wide.shape} .. v${wide.shape} }, $WIDE_HOOK
            move-result v$answer
            if-nez v$answer, :plain
        """,
        ExternalLabel("plain", render.getInstruction(plain)),
    )
}

/**
 * Where the reel item layout checks shapes: [screenBranch] is the if-lez after the screen's shape is
 * compared with a 9:16 const/high16, and [constant] the first const after it a bit over 9:16 that a
 * cmpl-float compares with the float a div-float just put in [shape], the item's width over its height.
 */
internal data class WideReelCheck(val screenBranch: Int, val constant: Int, val shape: Int)

internal fun findWideReelCheck(code: List<Instruction>): WideReelCheck? {
    fun literal(at: Int): Int? = (code.getOrNull(at) as? NarrowLiteralInstruction)
        ?.takeIf { it.opcode == Opcode.CONST_HIGH16 || it.opcode == Opcode.CONST }?.narrowLiteral
    fun compares(at: Int, register: Int): ThreeRegisterInstruction? = (code.getOrNull(at) as? ThreeRegisterInstruction)
        ?.takeIf { it.opcode == Opcode.CMPL_FLOAT && it.registerC == register }
    fun branches(at: Int, register: Int) = code.getOrNull(at)?.let {
        it.opcode == Opcode.IF_LEZ && (it as OneRegisterInstruction).registerA == register
    } == true

    val screen = code.indices.firstOrNull { at ->
        literal(at) == NINE_SIXTEENTHS &&
            compares(at + 1, (code[at] as OneRegisterInstruction).registerA)?.let { branches(at + 2, it.registerA) } == true
    } ?: return null
    val constant = (screen + 3 until code.size).firstOrNull { at ->
        val value = literal(at)?.let(java.lang.Float::intBitsToFloat) ?: return@firstOrNull false
        val divide = code[at - 1] as? ThreeRegisterInstruction
        val compare = compares(at + 1, (code[at] as OneRegisterInstruction).registerA)
        value > 0.5625f && value < 0.6f && divide?.opcode == Opcode.DIV_FLOAT &&
            compare != null && compare.registerB == divide.registerA && branches(at + 2, compare.registerA)
    } ?: return null
    return WideReelCheck(screen + 2, constant, (code[constant - 1] as ThreeRegisterInstruction).registerA)
}

/**
 * The story layout's pieces: the tall-screen check, the whole-screen layout, and the other static
 * (holder) layout it calls, which sizes the frame to the whole room again behind a second check
 * (#618), A0A on 454. Null when the story layout calls no other one. And the check the sized
 * layout asks instead of the tall-screen one, given the width and height it lays out in.
 */
internal data class StoryLayout(val tall: MethodReference, val full: MethodReference, val reset: MethodReference?, val sizedTall: MethodReference)

/**
 * Finds the story layout (#436), 0BmY on 453 and 023G.A08 on 457. The method opening
 * [STORY_ASPECT_RATIO] asks a static `(Context, item)Z` check whether this story gets a card, then
 * lays the card out with a static method taking its [ROUNDED_LAYOUT] (the picture 9:16 with
 * rounded corners, the reply bar below it). The check is the call whose answer the last if-eqz before
 * the card call reads. The whole screen is the static `(holder)V` layout right after that check that
 * a yes skips. Behind a flag the same method hands everything to a sized layout, taking the holder and
 * a width and a height, and that one asks its own `(item, int, int)Z` check right before the whole
 * screen in the same way.
 */
internal fun BytecodePatchContext.findStoryLayout(): StoryLayout {
    val opening = classesLoadingString(STORY_ASPECT_RATIO).flatMap { it.methods }.filter { loadsString(it, STORY_ASPECT_RATIO) }
    val entry = opening.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: expected one method opening \"$STORY_ASPECT_RATIO\", found ${opening.size}")
    val owner = entry.definingClass
    fun ownCall(code: List<Instruction>, at: Int): MethodReference? = code.getOrNull(at)?.takeIf {
        it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE
    }?.let { ((it as ReferenceInstruction).reference as? MethodReference)?.takeIf { call -> call.definingClass == owner } }
    fun MethodReference.takes() = parameterTypes.map(CharSequence::toString)
    // The layouts a check skips with a yes: a static (one object)V call right behind an if-nez on the
    // answer of the call three instructions up, as check to layout.
    fun skipped(code: List<Instruction>): List<Pair<MethodReference, MethodReference>> = (3 until code.size).mapNotNull { at ->
        val call = ownCall(code, at)
        val skip = code[at - 1]
        val answer = code[at - 2]
        val check = ownCall(code, at - 3)
        if (call == null || check == null || call.returnType != "V" || call.takes().size != 1 || !call.takes()[0].startsWith("L") ||
            skip.opcode != Opcode.IF_NEZ || answer.opcode != Opcode.MOVE_RESULT ||
            (answer as OneRegisterInstruction).registerA != (skip as OneRegisterInstruction).registerA
        ) {
            null
        } else {
            check to call
        }
    }
    val code = entry.implementation?.instructions?.toList().orEmpty()

    val cards = code.indices.filter { at ->
        val call = ownCall(code, at)
        call != null && call.returnType == "V" && call.takes().firstOrNull() == CONTEXT && ROUNDED_LAYOUT in call.takes()
    }
    val cardAt = cards.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout calls ${cards.size} card layouts taking its $ROUNDED_LAYOUT, not one")
    val branch = (cardAt - 1 downTo 2).firstOrNull { code[it].opcode == Opcode.IF_EQZ }
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: nothing decides on the story card before it's laid out")
    val tall = ownCall(code, branch - 2)
    val answer = code[branch - 1]
    if (tall == null || tall.returnType != "Z" || tall.takes().size != 2 || tall.takes()[0] != CONTEXT ||
        answer.opcode != Opcode.MOVE_RESULT ||
        (answer as OneRegisterInstruction).registerA != (code[branch] as OneRegisterInstruction).registerA
    ) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the story card's if-eqz doesn't read a (Context, item) check")
    }
    // The layout also lays a (holder) shape out behind another check, so the whole screen is the one a yes skips.
    val fulls = skipped(code).filter { (check, _) -> check.toString() == tall.toString() }.map { it.second }.distinctBy { it.toString() }
    val full = fulls.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout calls ${fulls.size} (holder) layouts its tall-screen check skips, not one")
    val holder = full.takes()[0]
    val resets = code.indices.mapNotNull { ownCall(code, it) }.filter {
        it.returnType == "V" && it.takes() == listOf(holder) && it.toString() != full.toString()
    }.distinctBy { it.toString() }
    if (resets.size > 1) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout calls ${resets.size} other ($holder) layouts, not one")
    }

    val sizes = code.indices.mapNotNull { ownCall(code, it) }.filter {
        it.returnType == "V" && holder in it.takes() && it.takes().takeLast(2) == listOf("I", "I")
    }.distinctBy { it.toString() }
    val sized = sizes.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout hands ${sizes.size} sized ($holder, int, int) layouts on, not one")
    val sizedCode = classDefBy(owner).methods.singleOrNull {
        it.name == sized.name && it.parameterTypes.map(CharSequence::toString) == sized.takes() && it.returnType == sized.returnType &&
            AccessFlags.STATIC.isSet(it.accessFlags)
    }?.implementation?.instructions?.toList()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout's $sized isn't a static method of its class")
    val sizedChecks = skipped(sizedCode).filter { (check, call) ->
        call.toString() == full.toString() && check.returnType == "Z" && check.takes().size == 3 && check.takes().drop(1) == listOf("I", "I")
    }.map { it.first }.distinctBy { it.toString() }
    val sizedTall = sizedChecks.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the sized story layout skips the whole screen behind ${sizedChecks.size} (item, int, int) checks, not one")
    return StoryLayout(tall, full, resets.singleOrNull(), sizedTall)
}

/**
 * Stories fill the screen (#436). [STORY_HOOK] goes in at the first instruction of the tall-screen
 * check and of the sized layout's check, which then answer false. Every card layout Instagram makes
 * sits behind one of the two, so a story gets the whole screen. The tall-screen check's other askers
 * (the viewer fragment and its footer) then treat the screen as a shorter one too. A first local is
 * free in both, and nothing jumps back to either first instruction.
 */
internal fun BytecodePatchContext.fillStoryScreen() {
    val layout = findStoryLayout()
    val methods = mutableClassDefBy(layout.tall.definingClass).methods
    fun own(call: MethodReference) = methods.singleOrNull {
        it.name == call.name && it.returnType == call.returnType &&
            it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
            AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout's $call isn't a static method of its class")
    val checks = listOf("tall-screen check" to own(layout.tall), "sized layout's check" to own(layout.sizedTall))
    for ((what, method) in checks) {
        method.requireLocals(FULL_SCREEN_BARS_PATCH, 1)
        if (0 in method.jumpTargets()) {
            throw PatchException("$FULL_SCREEN_BARS_PATCH: something jumps back to the story $what's first instruction")
        }
    }
    for ((_, method) in checks) {
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $STORY_HOOK
                move-result v0
                if-eqz v0, :instagram
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("instagram", method.getInstruction(0)),
        )
    }
}

/** The story binder, with the declared parameter indexes of the story and of the item holder it binds to. */
internal data class StoryBinder(val method: Method, val item: Int, val holder: Int)

/**
 * Finds Instagram's story item binder (#618), 0DfT.A03 on 454: the static method opening
 * [STORY_BIND_MEDIA] that takes a [STORY_REEL_ITEM] and calls the story layout. The immersive catch-up
 * binder opens the same section but takes no story. The holder is the parameter whose class the
 * binder stores the story on.
 */
internal fun BytecodePatchContext.findStoryBinder(layout: StoryLayout): StoryBinder {
    val owner = layout.full.definingClass
    val binders = classesLoadingString(STORY_BIND_MEDIA).flatMap { it.methods }.filter { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.count { it.toString() == STORY_REEL_ITEM } == 1 &&
            loadsString(method, STORY_BIND_MEDIA) &&
            code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == owner }
    }
    val binder = binders.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: expected one story binder opening \"$STORY_BIND_MEDIA\", found ${binders.size}")
    val types = binder.parameterTypes.map(CharSequence::toString)
    val stores = binder.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (instruction.opcode != Opcode.IPUT_OBJECT) return@mapNotNull null
        ((instruction as ReferenceInstruction).reference as FieldReference).takeIf { it.type == STORY_REEL_ITEM }?.definingClass
    }.toSet()
    val holders = types.indices.filter { types[it] in stores }
    val holder = holders.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story binder stores its story on ${holders.size} of its parameters, not one")
    return StoryBinder(binder, types.indexOf(STORY_REEL_ITEM), holder)
}

/** How a story gets its stickers of one kind ([list]), and the kind ([kind]) of the stickers that share a post or reel. */
internal data class SharedPostStickers(val kind: FieldReference, val list: MethodReference)

/**
 * The stickers a story shares a post or reel with (#618), read the way Instagram's own shared-reel
 * check reads them. That check, ReelItem.A0w on 454, asks the story for its stickers of one kind
 * ([SharedPostStickers.list], DK9 with 01Mb.A0m on 454) and answers yes only when one of them is a
 * reel. A reshared feed post comes in the same list, so the binder asks about the list itself.
 */
internal fun BytecodePatchContext.findSharedPostStickers(): SharedPostStickers {
    val check = findSharedReelCheck()
    val code = check.implementation?.instructions?.toList().orEmpty()
    val calls = code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.definingClass == STORY_REEL_ITEM &&
            call.returnType == "Ljava/util/List;" && call.parameterTypes.size == 1
    }
    val at = calls.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the shared-reel check reads ${calls.size} sticker lists, not one")
    val list = (code[at] as ReferenceInstruction).reference as MethodReference
    val call = code[at] as FiveRegisterInstruction
    val self = check.implementation!!.registerCount - 1
    val loaded = code.getOrNull(at - 1)
    val kind = (loaded as? ReferenceInstruction)?.reference as? FieldReference
    if (call.registerCount != 2 || call.registerC != self || loaded == null || kind == null ||
        loaded.opcode != Opcode.SGET_OBJECT || (loaded as OneRegisterInstruction).registerA != call.registerD ||
        kind.type != list.parameterTypes[0].toString()
    ) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the shared-reel check doesn't ask the story for one kind of sticker")
    }
    return SharedPostStickers(
        ImmutableFieldReference(kind.definingClass, kind.name, kind.type),
        ImmutableMethodReference(list.definingClass, list.name, list.parameterTypes, list.returnType),
    )
}

/**
 * Instagram's own check of whether a story shares a reel (#618), ReelItem.A0w on 454: the story's
 * one no-argument boolean that reads the ProductType a reel has, which it compares a media sticker's
 * product type with. That constant is the field ProductType's static initializer stores right after
 * naming [CLIPS].
 */
private fun BytecodePatchContext.findSharedReelCheck(): Method {
    val productType = classDefByOrNull(REEL_PRODUCT_TYPE)
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: $REEL_PRODUCT_TYPE isn't in this Instagram")
    val setup = productType.methods.singleOrNull { it.name == "<clinit>" }?.implementation?.instructions?.toList().orEmpty()
    val named = setup.indices.filter { ((setup[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == CLIPS }
    val clips = named.singleOrNull()?.let { at ->
        (at + 1 until setup.size).firstNotNullOfOrNull { next ->
            val field = (setup[next] as? ReferenceInstruction)?.reference as? FieldReference
            field?.takeIf { setup[next].opcode == Opcode.SPUT_OBJECT && it.definingClass == REEL_PRODUCT_TYPE && it.type == REEL_PRODUCT_TYPE }
        }
    }?.toString() ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: ProductType names $CLIPS ${named.size} times, or keeps it nowhere")
    val item = classDefByOrNull(STORY_REEL_ITEM) ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: $STORY_REEL_ITEM isn't in this Instagram")
    val checks = item.methods.filter { method ->
        method.returnType == "Z" && method.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(method.accessFlags) && !AccessFlags.PRIVATE.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any {
                it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == clips
            } == true
    }
    return checks.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: $STORY_REEL_ITEM has ${checks.size} checks for a shared reel, not one")
}

/**
 * Stories lay out as they bind, and a shared reel stays whole (#618).
 *
 * Instagram's newer story viewer (its own server flag) skips the binder's layout and lays a story
 * out once the story's state has loaded, which for a reel shared to a story came about a second
 * later: the card and its reply bar showed first, then the whole screen. So the binder's first
 * instruction hands [SHARES_POST_HOOK] the story's stickers that share a post or reel
 * ([findSharedPostStickers]), then asks [STORY_BOUND_HOOK] with the holder and that answer, or no
 * when there's no story, and a yes lays out the whole screen there and then. Nothing jumps back to
 * that first instruction, and its first two locals hold nothing yet.
 *
 * Filling the screen cut the sides off a 9:16 reel or reshared post on a taller phone. The
 * whole-screen layout's one return now hands [STORY_FRAME_HOOK] the holder and the frame it sized,
 * the view whose layout params it sets, so the extension can size a shared post's frame back to
 * 9:16. Every branch that reached the return reaches the hook first. The story layout then runs a
 * second (holder) layout behind another check ([StoryLayout.reset]), which sizes the same frame to
 * the whole room again, so its one return hands the frame over too.
 */
internal fun BytecodePatchContext.layStoriesOutAsTheyBind() {
    val layout = findStoryLayout()
    val found = findStoryBinder(layout)
    val stickers = findSharedPostStickers()
    val binder = mutableClassDefBy(found.method.definingClass).methods.single {
        it.name == found.method.name && it.parameterTypes.map(CharSequence::toString) == found.method.parameterTypes.map(CharSequence::toString)
    }
    binder.requireLocals(FULL_SCREEN_BARS_PATCH, 2)
    if (0 in binder.jumpTargets()) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: something jumps back to the story binder's first instruction")
    }
    val item = binder.parameterRegisterNumber(found.item)
    val holder = binder.parameterRegisterNumber(found.holder)

    val reset = layout.reset
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout has no second whole-room layout behind its other check")
    val handOvers = listOf("whole-screen layout" to layout.full, "second whole-room layout" to reset).map { (what, call) ->
        frameHandOver(what, mutableClassDefBy(call.definingClass).methods.single {
            it.name == call.name && it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
                AccessFlags.STATIC.isSet(it.accessFlags)
        })
    }

    binder.addInstructionsWithLabels(
        0,
        """
            const/4 v1, 0x0
            if-eqz v$item, :ask
            move-object/from16 v0, v$item
            sget-object v1, ${stickers.kind}
            invoke-virtual { v0, v1 }, ${stickers.list}
            move-result-object v1
            invoke-static { v1 }, $SHARES_POST_HOOK
            move-result v1
            :ask
            move-object/from16 v0, v$holder
            invoke-static { v0, v1 }, $STORY_BOUND_HOOK
            move-result v1
            if-eqz v1, :instagram
            invoke-static { v0 }, ${layout.full}
        """,
        ExternalLabel("instagram", binder.getInstruction(0)),
    )
    handOvers.forEach { it() }
}

/**
 * Checks that [method], a static (holder) layout that sizes the story frame, can hand its holder and
 * frame to [STORY_FRAME_HOOK] in front of its one return, and answers the insert that does it.
 */
private fun frameHandOver(what: String, method: MutableMethod): () -> Unit {
    val frame = findStoryFrameGetter(method)
    val code = method.implementation?.instructions?.toList().orEmpty()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    val end = returns.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story's $what returns ${returns.size} times, not once")
    val holder = method.parameterRegisterNumber(0)
    if (holder > 15) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the story's $what keeps its holder in v$holder, past what an invoke names")
    }
    method.requireParameterIntact(FULL_SCREEN_BARS_PATCH, 0, listOf(end))
    val local = method.freeLocalsAt(FULL_SCREEN_BARS_PATCH, end, 1).first()
    return {
        method.addInstructionsAtControlFlowLabel(
            end,
            """
                ${frame.call} { v$holder }, ${frame.getter}
                move-result-object v$local
                invoke-static { v$holder, v$local }, $STORY_FRAME_HOOK
            """,
        )
    }
}

/** How the whole-screen layout gets the story frame from its holder: the invoke's mnemonic and the getter. */
internal data class StoryFrameGetter(val call: String, val getter: MethodReference)

/**
 * The getter the story's whole-screen layout reads the frame it sizes with, Co4 on 454: the
 * no-argument call on the holder whose answer the layout's one setLayoutParams is called on.
 */
internal fun findStoryFrameGetter(full: Method): StoryFrameGetter {
    val code = full.implementation?.instructions?.toList().orEmpty()
    val holder = full.parameterRegisterNumber(0)
    val sizes = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == SET_LAYOUT_PARAMS }
    val size = sizes.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story's whole-screen layout sets layout params ${sizes.size} times, not once")
    val frame = (code[size] as FiveRegisterInstruction).registerC
    val fetched = (size - 1 downTo 1).firstOrNull {
        code[it].opcode == Opcode.MOVE_RESULT_OBJECT && (code[it] as OneRegisterInstruction).registerA == frame
    } ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story's whole-screen layout sizes a frame it didn't fetch")
    val call = code[fetched - 1]
    val getter = (call as? ReferenceInstruction)?.reference as? MethodReference
    val mnemonic = when (call.opcode) {
        Opcode.INVOKE_INTERFACE -> "invoke-interface"
        Opcode.INVOKE_VIRTUAL -> "invoke-virtual"
        else -> null
    }
    if (mnemonic == null || getter == null || getter.parameterTypes.isNotEmpty() || !getter.returnType.startsWith("L") ||
        (call as FiveRegisterInstruction).registerCount != 1 || call.registerC != holder
    ) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the story's whole-screen layout doesn't get its frame from the holder")
    }
    return StoryFrameGetter(mnemonic, ImmutableMethodReference(getter.definingClass, getter.name, getter.parameterTypes, getter.returnType))
}

private const val SET_LAYOUT_PARAMS = "Landroid/view/View;->setLayoutParams(Landroid/view/ViewGroup\$LayoutParams;)V"

private const val CONTEXT = "Landroid/content/Context;"
