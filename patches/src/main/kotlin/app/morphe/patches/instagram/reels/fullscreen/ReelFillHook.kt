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
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsConstString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.uniqueMethod
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal const val FILL_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->fill(Ljava/lang/Object;F)Z"
internal const val FIT_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->fit()Z"
internal const val STORY_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->story()Z"
internal const val WIDE_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->wide(F)Z"
internal const val STORY_BOUND_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->storyBound(Ljava/lang/Object;I)I"
internal const val STORY_FRAME_HOOK = "$EXTENSION_PACKAGE/reels/ReelFill;->storyFrame(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The trace section Instagram's story item binder opens while it binds a story's picture or video. */
internal const val STORY_BIND_MEDIA = "ReelViewerItemBinder.bindMedia"

internal const val STORY_REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"
internal const val REEL_PRODUCT_TYPE = "Lcom/instagram/model/mediatype/ProductType;"

/** The name ProductType gives a reel, which a sticker sharing one carries. */
private const val CLIPS = "CLIPS"

/** The purge flag Instagram's reel item layout, which keeps a reel 9:16 on a wider screen, names first. */
internal const val REEL_INSETS = "android_purge_26_q2_ClipsItemInsetsComponent_render"

/** 9:16 as a float's bits, the shape that layout compares the screen's and the item's with. */
private const val NINE_SIXTEENTHS = 0x3f100000

/** The trace section Instagram's story viewer opens while it fits a story to the screen's shape. */
internal const val STORY_ASPECT_RATIO = "ReelViewerMediaAspectRatioUtil.maybeAdjustLayoutBasedOnAspectRatio"

/** The purge flag Instagram's reel crop decision names first thing. */
internal const val SHOULD_CROP = "android_purge_26_q2_ClipsMediaResizingUtils_shouldCropDynamic"

/** The one it names where it works out how far the viewer's shape is from the reel's. */
internal const val CROP_PERCENTAGE = "android_purge_26_q2_ClipsMediaResizingUtils_getCropPercentage"

/** Where the viewer was opened from, which the decision takes as a parameter. */
internal const val REEL_VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"

/**
 * The method that decides whether a reel's video fills the viewer (true) or fits inside it with bars
 * (false), 08JU.A02 on 452. It's the one method naming both purge flags.
 */
internal object ReelCropDecisionFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string(SHOULD_CROP)),
    custom = { method, _ -> method.loadsConstString(CROP_PERCENTAGE) },
)

/**
 * Fill the screen (#288). After the crop percentage name, the decision divides the difference
 * between the viewer's aspect ratio and the reel's by the reel's and multiplies it by 100, then
 * crops only below thresholds Instagram's server sends. With Full screen Reels on the viewer is
 * taller, so a 9:16 reel often lands past them and shows bars, and hiding the comment bar does the
 * same (#371). Right after that multiply the decision asks [FILL_HOOK] with the viewer's source, since
 * one comment bar switch only covers reposts, and the percentage, and returns true (fill) when it
 * says so. Otherwise Instagram's own checks run as before.
 */
internal fun BytecodePatchContext.fillReelScreen() {
    val decision = uniqueMethod(FULL_SCREEN_BARS_PATCH, "reel crop decision", ReelCropDecisionFingerprint)
    val code = decision.implementation?.instructions?.toList().orEmpty()
    val named = code.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == CROP_PERCENTAGE
    }
    val multiply = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.MUL_FLOAT_2ADDR }
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the reel crop decision multiplies no float after $CROP_PERCENTAGE")
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

/**
 * Fill the screen on a screen wider than 9:16 (#491). On a foldable or a large screen, Instagram's
 * reel item layout (the render opening [REEL_INSETS], 05DP.A0o on 453) first checks the screen is
 * wider than 9:16, then compares the item's own shape with a constant a bit over 9:16 and lays the
 * reel out in a 9:16 column with insets at the sides (or a 9:16 box on a narrower item), so the crop
 * decision sees a 9:16 viewer and [fillReelScreen] has nothing to fill. The ask goes in front of that
 * constant with the item's shape, and a yes jumps to where the screen check sends a phone: the plain
 * whole-item layout, after which the crop decision fills the reel within its cap. The constant
 * writes its register, so nothing reads that register's old value, and nothing jumps to it.
 */
internal fun BytecodePatchContext.fillWideReelScreen() {
    val renders = classesLoadingString(REEL_INSETS).flatMap { it.methods }.filter { loadsString(it, REEL_INSETS) }
    val found = renders.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: expected one method opening \"$REEL_INSETS\", found ${renders.size}")
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

/** The story layout's pieces: the tall-screen check, the card layout and the whole-screen layout. */
internal data class StoryLayout(val tall: MethodReference, val card: MethodReference, val full: MethodReference)

/**
 * Finds the story layout (#436), 0BmY on 453 and 0Bw2 on 454. The method opening [STORY_ASPECT_RATIO]
 * asks a static `(Context, item)Z` check whether this story gets a card, lays the card out with a static
 * `(Context, holder, int, int)V` method (the picture 9:16 with rounded corners, the reply bar below
 * it) and the whole screen with a static `(holder)V` one, the call right after the check that a yes
 * skips. The check is the call whose answer the last if-eqz before the card call reads.
 */
internal fun BytecodePatchContext.findStoryLayout(): StoryLayout {
    val opening = classesLoadingString(STORY_ASPECT_RATIO).flatMap { it.methods }.filter { loadsString(it, STORY_ASPECT_RATIO) }
    val entry = opening.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: expected one method opening \"$STORY_ASPECT_RATIO\", found ${opening.size}")
    val owner = entry.definingClass
    val code = entry.implementation?.instructions?.toList().orEmpty()
    fun ownCall(at: Int): MethodReference? = code[at].takeIf {
        it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE
    }?.let { ((it as ReferenceInstruction).reference as? MethodReference)?.takeIf { call -> call.definingClass == owner } }
    fun MethodReference.takes() = parameterTypes.map(CharSequence::toString)

    val cards = code.indices.filter { at ->
        val call = ownCall(at)
        call != null && call.returnType == "V" && call.takes().size == 4 && call.takes()[0] == CONTEXT &&
            call.takes()[2] == "I" && call.takes()[3] == "I"
    }
    val cardAt = cards.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout calls ${cards.size} (Context, holder, int, int) layouts, not one")
    val card = ownCall(cardAt)!!
    val holder = card.takes()[1]
    val branch = (cardAt - 1 downTo 2).firstOrNull { code[it].opcode == Opcode.IF_EQZ }
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: nothing decides on the story card before it's laid out")
    val tall = ownCall(branch - 2)
    val answer = code[branch - 1]
    if (tall == null || tall.returnType != "Z" || tall.takes().size != 2 || tall.takes()[0] != CONTEXT ||
        answer.opcode != Opcode.MOVE_RESULT ||
        (answer as OneRegisterInstruction).registerA != (code[branch] as OneRegisterInstruction).registerA
    ) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the story card's if-eqz doesn't read a (Context, item) check")
    }
    // 454 also lays a ($holder) shape out behind another check, so the whole screen is the one a yes skips.
    val fulls = (3 until code.size).filter { at ->
        val call = ownCall(at)
        val skip = code[at - 1]
        val answer = code[at - 2]
        call != null && call.returnType == "V" && call.takes() == listOf(holder) &&
            skip.opcode == Opcode.IF_NEZ && answer.opcode == Opcode.MOVE_RESULT &&
            (answer as OneRegisterInstruction).registerA == (skip as OneRegisterInstruction).registerA &&
            ownCall(at - 3)?.toString() == tall.toString()
    }.map { ownCall(it)!! }.distinctBy { it.toString() }
    val full = fulls.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout calls ${fulls.size} ($holder) layouts its tall-screen check skips, not one")
    return StoryLayout(tall, card, full)
}

/**
 * Stories fill the screen (#436). [STORY_HOOK] goes in at the first instruction of the tall-screen
 * check, which then answers false, and of the card layout, which then lays out the whole screen
 * instead and returns. The check's other askers (the viewer fragment and its footer) then treat the
 * screen as a shorter one too, and the card layout's other callers get the whole screen. A first
 * local is free in both, and nothing jumps back to either first instruction.
 */
internal fun BytecodePatchContext.fillStoryScreen() {
    val layout = findStoryLayout()
    val methods = mutableClassDefBy(layout.card.definingClass).methods
    fun own(call: MethodReference) = methods.singleOrNull {
        it.name == call.name && it.returnType == call.returnType &&
            it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) &&
            AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story layout's $call isn't a static method of its class")
    val tall = own(layout.tall)
    val card = own(layout.card)
    for ((what, method) in listOf("tall-screen check" to tall, "card layout" to card)) {
        method.requireLocals(FULL_SCREEN_BARS_PATCH, 1)
        if (0 in method.jumpTargets()) {
            throw PatchException("$FULL_SCREEN_BARS_PATCH: something jumps back to the story $what's first instruction")
        }
    }
    val holder = card.parameterRegisterNumber(1)
    if (holder > 15) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the story card layout keeps its holder in v$holder, past what an invoke names")
    }
    tall.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $STORY_HOOK
            move-result v0
            if-eqz v0, :instagram
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("instagram", tall.getInstruction(0)),
    )
    card.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $STORY_HOOK
            move-result v0
            if-eqz v0, :instagram
            invoke-static { v$holder }, ${layout.full}
            return-void
        """,
        ExternalLabel("instagram", card.getInstruction(0)),
    )
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

/**
 * Instagram's own check of whether a story shares a reel (#618), ReelItem.A0w on 454: the story's
 * one no-argument boolean that reads the ProductType a reel has, which it compares a media sticker's
 * product type with. That constant is the field ProductType's static initializer stores right after
 * naming [CLIPS].
 */
internal fun BytecodePatchContext.findSharedReelCheck(): MethodReference {
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
    val check = checks.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: $STORY_REEL_ITEM has ${checks.size} checks for a shared reel, not one")
    return ImmutableMethodReference(check.definingClass, check.name, check.parameterTypes, check.returnType)
}

/**
 * Stories lay out as they bind, and a shared reel stays whole (#618).
 *
 * Instagram's newer story viewer (its own server flag) skips the binder's layout and lays a story
 * out once the story's state has loaded, which for a reel shared to a story came about a second
 * later: the card and its reply bar showed first, then the whole screen. So the binder's first
 * instruction asks [STORY_BOUND_HOOK] with the holder and the answer of Instagram's own shared-reel
 * check ([findSharedReelCheck]), or no when there's no story, and a yes lays out the whole screen
 * there and then. Nothing jumps back to that first instruction, and its first two locals hold
 * nothing yet.
 *
 * Filling the screen cut a 9:16 reel's sides off on a taller phone. The whole-screen layout's one
 * return now hands [STORY_FRAME_HOOK] the holder and the frame it sized, the view whose layout
 * params it sets, so the extension can size a shared reel's frame back to 9:16. Every branch that
 * reached the return reaches the hook first.
 */
internal fun BytecodePatchContext.layStoriesOutAsTheyBind() {
    val layout = findStoryLayout()
    val found = findStoryBinder(layout)
    val shared = findSharedReelCheck()
    val binder = mutableClassDefBy(found.method.definingClass).methods.single {
        it.name == found.method.name && it.parameterTypes.map(CharSequence::toString) == found.method.parameterTypes.map(CharSequence::toString)
    }
    binder.requireLocals(FULL_SCREEN_BARS_PATCH, 2)
    if (0 in binder.jumpTargets()) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: something jumps back to the story binder's first instruction")
    }
    val item = binder.parameterRegisterNumber(found.item)
    val holder = binder.parameterRegisterNumber(found.holder)

    val full = mutableClassDefBy(layout.full.definingClass).methods.single {
        it.name == layout.full.name && it.parameterTypes.map(CharSequence::toString) == layout.full.parameterTypes.map(CharSequence::toString) &&
            AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val frame = findStoryFrameGetter(full)
    val code = full.implementation?.instructions?.toList().orEmpty()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    val end = returns.singleOrNull()
        ?: throw PatchException("$FULL_SCREEN_BARS_PATCH: the story's whole-screen layout returns ${returns.size} times, not once")
    val fullHolder = full.parameterRegisterNumber(0)
    if (fullHolder > 15) {
        throw PatchException("$FULL_SCREEN_BARS_PATCH: the story's whole-screen layout keeps its holder in v$fullHolder, past what an invoke names")
    }
    full.requireParameterIntact(FULL_SCREEN_BARS_PATCH, 0, listOf(end))
    val local = full.freeLocalsAt(FULL_SCREEN_BARS_PATCH, end, 1).first()

    binder.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, v$holder
            const/4 v1, 0x0
            if-eqz v$item, :ask
            invoke-virtual/range { v$item .. v$item }, $shared
            move-result v1
            :ask
            invoke-static { v0, v1 }, $STORY_BOUND_HOOK
            move-result v1
            if-eqz v1, :instagram
            invoke-static { v0 }, ${layout.full}
        """,
        ExternalLabel("instagram", binder.getInstruction(0)),
    )
    full.addInstructionsAtControlFlowLabel(
        end,
        """
            ${frame.call} { v$fullHolder }, ${frame.getter}
            move-result-object v$local
            invoke-static { v$fullHolder, v$local }, $STORY_FRAME_HOOK
        """,
    )
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
