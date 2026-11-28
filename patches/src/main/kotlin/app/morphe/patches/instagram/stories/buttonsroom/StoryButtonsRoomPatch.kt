/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.buttonsroom

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.REEL_VIEWER_FRAGMENT
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep story buttons below the story"
internal const val STORY_BUTTONS_ROOM = "$EXTENSION_PACKAGE/stories/StoryButtonsRoom;"
internal const val STORY_BUTTONS_ROOM_READER = "$EXTENSION_PACKAGE/stories/StoryButtonsRoomReader;"
internal const val BUTTONS_ROOM_RESUMED = "$STORY_BUTTONS_ROOM->resumed(Ljava/lang/Object;)V"

/** Instagram's rounded card, the story card on a story's page among others, and the extension's calls in its dispatchDraw (#902). */
internal const val ROUNDED_CARD = "Lcom/instagram/ui/widget/roundedcornerlayout/RoundedCornerFrameLayout;"
internal const val CARD_DRAW_START = "$STORY_BUTTONS_ROOM->drawStart(Ljava/lang/Object;Ljava/lang/Object;)I"
internal const val CARD_STRIP_DRAWN = "$STORY_BUTTONS_ROOM->stripDrawn(Ljava/lang/Object;Ljava/lang/Object;I)I"
internal const val CARD_DRAWN = "$STORY_BUTTONS_ROOM->cardDrawn(Ljava/lang/Object;I)V"
internal const val HEADER_TOUCH = "$STORY_BUTTONS_ROOM->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)Z"

private const val LIFECYCLE = "onResume"
private const val VIEW = "Landroid/view/View;"
private const val VIEW_STUB = "(Ljava/lang/Object;)$VIEW"
private const val DRAW = "dispatchDraw"
private const val CANVAS = "Landroid/graphics/Canvas;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"

/**
 * Moves the reply box and buttons along the bottom of a story down off the story when a large font
 * or display size makes them cover it (#598). See StoryButtonsRoom in the extension. In the default
 * selection with its switch on.
 *
 * The patch fills the extension's one stub with the story viewer's (ReelViewerFragment's) own view
 * and, right after the viewer's onResume hands on to its superclass, tells the extension the viewer
 * came back. Everything else happens in the extension, on the viewer's window. Nothing of
 * Instagram's changes besides that one call.
 *
 * Its second switch, Move story controls off the story (#902), rides on the same call: the extension
 * also moves each story's header up into the black space above the card and the reply box down to
 * the bottom. Two more calls carry the header up there. The card, RoundedCornerFrameLayout, draws
 * its children into a node clipped to its rounded box, so first thing in its dispatchDraw the
 * extension is asked whether this card's header is up; for such a card the children are drawn once
 * into the strip above it before the card draws again as it did, clipped to its own box. And since
 * the story viewer never hands a touch above the card to the header, IgFragmentActivity's
 * dispatchTouchEvent offers every touch to the extension first, as the mentions pill's does.
 */
@Suppress("unused")
val storyButtonsRoomPatch = bytecodePatch(
    name = "Keep story buttons below the story",
    description = "With a large font or display size, the reply box and buttons along the bottom of a story move " +
        "down into the empty space under it instead of covering the story. Starts on. A second switch moves the name " +
        "and progress bar up into the black space above a story and the reply box down to the bottom, and starts " +
        "off. Both are in HushGram settings > Stories.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyButtonsRoom")
        val sites = findStoryButtonsRoom()
        hookStoryButtonsRoom(sites)
        enableStatus("storyButtonsRoom")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** What the patch writes into: the story viewer's type and the call the card's dispatchDraw makes to its superclass's. */
internal class StoryButtonsRoomSites(val viewer: String, val cardSuper: String)

/**
 * Finds, before anything changes, the public viewer, its own onResume with one call on to its
 * superclass's on `this` whose next instruction nothing jumps to, the extension's stub, the card's
 * dispatchDraw with its call to its superclass's, and IgFragmentActivity's dispatchTouchEvent.
 */
internal fun BytecodePatchContext.findStoryButtonsRoom(): StoryButtonsRoomSites {
    val viewer = classDefByOrNull(REEL_VIEWER_FRAGMENT) ?: refuse("this build has no $REEL_VIEWER_FRAGMENT")
    if (!AccessFlags.PUBLIC.isSet(viewer.accessFlags)) refuse("$REEL_VIEWER_FRAGMENT isn't public")
    viewer.resumeSite()
    val reader = classDefByOrNull(STORY_BUTTONS_ROOM_READER) ?: refuse("the extension has no $STORY_BUTTONS_ROOM_READER")
    reader.methods.singleOrNull { it.name == "view" && AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == VIEW_STUB }
        ?: refuse("$STORY_BUTTONS_ROOM_READER has no static view$VIEW_STUB")
    val card = classDefByOrNull(ROUNDED_CARD) ?: refuse("this build has no $ROUNDED_CARD")
    val cardSuper = card.cardSuper()
    val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: refuse("this build has no $FRAGMENT_ACTIVITY")
    activity.headerTouch()
    return StoryButtonsRoomSites(viewer.type, cardSuper)
}

/**
 * The card's own dispatchDraw(Canvas), refused when it isn't final (drawing the card again from
 * inside it would run a subclass's override twice), when it already tells the extension, when
 * something jumps back to its first instruction or when it has no local for the hook's answer.
 */
private fun ClassDef.cardDraw(): Method {
    val draws = methods.filter { isCardDraw(it) && it.implementation != null }
    val draw = draws.singleOrNull() ?: refuse("expected $ROUNDED_CARD to have one $DRAW($CANVAS)V of its own, found ${draws.size}")
    if (!AccessFlags.FINAL.isSet(draw.accessFlags) && !AccessFlags.FINAL.isSet(accessFlags)) {
        refuse("$ROUNDED_CARD->$DRAW isn't final, so drawing the card again could run a subclass's twice")
    }
    if (draw.code().any { it.method()?.definingClass == STORY_BUTTONS_ROOM }) refuse("$ROUNDED_CARD->$DRAW already calls $STORY_BUTTONS_ROOM")
    if (draw.localRegisterCount() < 1) refuse("$ROUNDED_CARD->$DRAW has no local register for the hook's answer")
    if (0 in draw.jumpTargets()) refuse("something in $ROUNDED_CARD->$DRAW jumps back to its first instruction")
    return draw
}

/** The superclass's dispatchDraw the card's own calls, once, which the patch's strip pass calls too. */
private fun ClassDef.cardSuper(): String {
    val supers = cardDraw().code().filter { it.opcode == Opcode.INVOKE_SUPER || it.opcode == Opcode.INVOKE_SUPER_RANGE }
        .mapNotNull { it.method() }
        .filter { it.name == DRAW && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(CANVAS) }
    val called = supers.singleOrNull() ?: refuse("expected $ROUNDED_CARD->$DRAW to call its superclass's once, found ${supers.size}")
    return "${called.definingClass}->${called.name}($CANVAS)V"
}

/**
 * IgFragmentActivity's own dispatchTouchEvent, which every touch on an Instagram screen passes
 * through first, refused when it already tells the extension or has no local for the hook's answer.
 */
private fun ClassDef.headerTouch(): Method {
    val touches = methods.filter { isHeaderTouch(it) && it.implementation != null }
    val touch = touches.singleOrNull() ?: refuse("expected $FRAGMENT_ACTIVITY to have one dispatchTouchEvent of its own, found ${touches.size}")
    if (touch.code().any { it.method()?.definingClass == STORY_BUTTONS_ROOM }) refuse("$FRAGMENT_ACTIVITY->dispatchTouchEvent already calls $STORY_BUTTONS_ROOM")
    if (touch.localRegisterCount() < 1) refuse("$FRAGMENT_ACTIVITY->dispatchTouchEvent has no local register for the hook's answer")
    return touch
}

private fun isCardDraw(method: Method): Boolean =
    method.name == DRAW && method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf(CANVAS) &&
        !AccessFlags.STATIC.isSet(method.accessFlags)

private fun isHeaderTouch(method: Method): Boolean =
    method.name == "dispatchTouchEvent" && method.returnType == "Z" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT)

/**
 * The index just past the viewer's one call on to its superclass's onResume, whose first register
 * is `this`, refused when it already tells the extension, when something jumps there or when `this`
 * may hold something else by then.
 */
private fun ClassDef.resumeSite(): Int {
    val method = methods.singleOrNull {
        it.name == LIFECYCLE && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: refuse("$REEL_VIEWER_FRAGMENT has no $LIFECYCLE() of its own")
    val code = method.code()
    if (code.any { it.method()?.definingClass == STORY_BUTTONS_ROOM }) refuse("$REEL_VIEWER_FRAGMENT->$LIFECYCLE already calls $STORY_BUTTONS_ROOM")
    val self = method.localRegisterCount()
    val supers = code.indices.filter { at ->
        val called = code[at].method()
        (code[at].opcode == Opcode.INVOKE_SUPER || code[at].opcode == Opcode.INVOKE_SUPER_RANGE) && called != null &&
            called.name == LIFECYCLE && called.parameterTypes.isEmpty() && called.returnType == "V"
    }
    val call = supers.singleOrNull() ?: refuse("expected $REEL_VIEWER_FRAGMENT->$LIFECYCLE to call its superclass's once, found ${supers.size}")
    val first = (code[call] as? FiveRegisterInstruction)?.registerC
    if (first != self) refuse("$REEL_VIEWER_FRAGMENT->$LIFECYCLE calls its superclass's on something other than this")
    val at = call + 1
    if (at >= code.size) refuse("$REEL_VIEWER_FRAGMENT->$LIFECYCLE ends at its call to its superclass's")
    if (at in method.jumpTargets()) refuse("something in $REEL_VIEWER_FRAGMENT->$LIFECYCLE jumps to just after its call to its superclass's")
    method.requireThisIntact(PATCH, listOf(at))
    return at
}

/** Fills the stub and puts the calls in, finding each site again on the classes as they are now. */
internal fun BytecodePatchContext.hookStoryButtonsRoom(sites: StoryButtonsRoomSites) {
    val viewer = sites.viewer
    // The stub's own register is its parameter, so the code below uses that.
    mutableClassDefBy(STORY_BUTTONS_ROOM_READER).methods.single { it.name == "view" && it.shape() == VIEW_STUB }.addInstructions(
        0,
        """
            check-cast p0, $viewer
            invoke-virtual { p0 }, $viewer->getView()$VIEW
            move-result-object p0
            return-object p0
        """,
    )
    val target = mutableClassDefBy(viewer)
    val at = target.resumeSite()
    target.methods.single { it.name == LIFECYCLE && it.parameterTypes.isEmpty() && it.returnType == "V" }
        .addInstructions(at, "invoke-static { p0 }, $BUTTONS_ROOM_RESUMED")
    hookCardDraw(mutableClassDefBy(ROUNDED_CARD).methods.single { isCardDraw(it) }, sites.cardSuper)
    hookHeaderTouch(mutableClassDefBy(FRAGMENT_ACTIVITY).methods.single { isHeaderTouch(it) })
}

/**
 * First thing in the card's dispatchDraw, asks the extension whether this card's header is up. When
 * it answers 0, the card draws as it did. Otherwise the canvas is clipped to the strip above the
 * card: the card's children draw into it through [cardSuper], the same call the card's own code
 * makes, then the card draws itself again through its own dispatchDraw, which the extension answers
 * 0 for, clipped to its box, and the canvas is put back.
 */
internal fun hookCardDraw(draw: MutableMethod, cardSuper: String) {
    draw.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0, p1 }, $CARD_DRAW_START
            move-result v0
            if-eqz v0, :instagram
            invoke-super { p0, p1 }, $cardSuper
            invoke-static { p0, p1, v0 }, $CARD_STRIP_DRAWN
            move-result v0
            invoke-virtual { p0, p1 }, $ROUNDED_CARD->$DRAW($CANVAS)V
            invoke-static { p1, v0 }, $CARD_DRAWN
            return-void
        """,
        ExternalLabel("instagram", draw.getInstruction(0)),
    )
}

/**
 * First thing in [touch], hands the activity and the event to [HEADER_TOUCH]. When it answers true
 * the touch was on a header up above its card and is answered as handled. Otherwise the code after
 * it runs as it was. Tap to play reads every touch here too and is held first, so when its call is
 * already in, this one goes right after it.
 */
internal fun hookHeaderTouch(touch: MutableMethod) {
    val first = touch.getInstruction(0).method()
    val clock = first != null &&
        "${first.definingClass}->${first.name}(${first.parameterTypes.joinToString("")})${first.returnType}" == TAP_CLOCK
    val at = if (clock) 1 else 0
    touch.addInstructionsWithLabels(
        at,
        """
            invoke-static/range { p0 .. p1 }, $HEADER_TOUCH
            move-result v0
            if-eqz v0, :instagram
            return v0
        """,
        ExternalLabel("instagram", touch.getInstruction(at)),
    )
}

private fun Method.shape(): String = "(${parameterTypes.joinToString("")})$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
