/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.abovecomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.reel.rewriteStub
import app.morphe.patches.instagram.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.instagram.media.taptoplay.TOUCH as TAP_CLOCK
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Control the reel above comments"

internal const val REEL_ABOVE_COMMENTS = "$EXTENSION_PACKAGE/reels/ReelAboveComments;"
internal const val SHEET_MOVED = "$REEL_ABOVE_COMMENTS->sheetMoved(Ljava/lang/Object;)V"
internal const val SHEET_TAPPED = "$REEL_ABOVE_COMMENTS->tapped(ILjava/lang/Object;Landroid/view/MotionEvent;)Z"
internal const val SHEET_TOUCH = "$REEL_ABOVE_COMMENTS->touch(Landroid/app/Activity;Landroid/view/MotionEvent;)V"

/**
 * The event the comments sheet's onBottomSheetSingleTapConfirmed logs for a tap on the audio button,
 * the one tap it keeps. No other method of Instagram's loads it.
 */
internal const val SHEET_AUDIO_TAP = "partial_modal_sheet_audio_control_button_tapped"

/** The trace the Reels player controller's pauseCurrentPlayer starts with. No other method of Instagram's loads it. */
internal const val PLAYER_PAUSE_TRACE = "ClipsVideoPlayerController.pauseCurrentPlayer pauseReason="

/** The reasons Instagram's own tap on a reel pauses and plays it with. */
internal const val TAP_PAUSE_REASON = "single_tap_paused"
internal const val TAP_RESUME_REASON = "resume"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val VIEW = "Landroid/view/View;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val FIND_VIEW_BY_ID = "Landroid/view/View;->findViewById(I)Landroid/view/View;"

/**
 * With a reel's comments open, a tap on the reel shown above them pauses or plays it and a hold plays
 * it at 2x, and the comments stay open. See the extension's ReelAboveComments for the rules. Asked
 * for in #665.
 *
 * Instagram 454's watch-and-comment manager runs the comments sheet over a reel: it shrinks the reel
 * into the space above the sheet, and its onBottomSheetSingleTapConfirmed answers each tap the sheet's
 * gesture detector confirms outside the sheet, keeping only a tap on the audio button and closing the
 * sheet on any other. Each of its answers goes through the extension first, which keeps a tap on the
 * reel and pauses or plays it. Its onBottomSheetHeightChanged tells the extension which manager's sheet
 * is moving, and IgFragmentActivity's dispatchTouchEvent hands it every touch, where a hold on the reel
 * is read. The extension's stubs are filled with the manager's reel view, its open and closing flags
 * and its player controller, and the controller's own current-player check, pause, resume and speed
 * calls, with the arguments Instagram's tap on a full-screen reel uses.
 *
 * The tap callback is the one method loading the audio button's tap event and the controller's pause
 * the one loading its trace; the rest are the one method of their shape in the manager or the
 * controller. Everything is found and checked before anything changes, so a build that differs stops
 * the patch naming what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val reelAboveCommentsPatch = bytecodePatch(
    name = "Control the reel above comments",
    description = "With a reel's comments open, a tap on the reel above them pauses or plays it and a hold plays it " +
        "at 2x, and the comments stay open. Starts off. Turn it on in HushGram settings > Reels.",
) {
    // Reels and Playback are at the limit, and the tap lands on a reel with its comments open.
    category("Comments")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelAboveComments")
        reelAboveComments()
        enableStatus("reelAboveComments")
    }
}

/** Finds and checks every hook and stub, then writes them. A build that fails any check is left as it was. */
internal fun BytecodePatchContext.reelAboveComments() {
    val found = findReelAboveComments()
    val stubs = reelAboveCommentsStubs()
    applyReelAboveComments(found)
    stubs.fill(found)
}

/** Every place the patch changes, and what the stubs reach. */
internal class ReelAboveCommentsHooks(
    /** The manager's onBottomSheetHeightChanged, where the sheet-moved hook goes first. */
    val heightChanged: Method,
    /** onBottomSheetSingleTapConfirmed, and the index of each of its returns. */
    val tapConfirmed: Method,
    val returns: List<Int>,
    /** IgFragmentActivity's dispatchTouchEvent. */
    val touch: Method,
    /** The manager's type, its reel view, its open and closing flags, and its player controller. */
    val manager: String,
    val media: FieldReference,
    val open: FieldReference,
    val dismissing: FieldReference,
    val controllerField: FieldReference,
    /** The controller's current player getter, its playing check, pause, resume and speed setter. */
    val current: MethodReference,
    val isPlaying: MethodReference,
    val pause: MethodReference,
    val resume: MethodReference,
    val speed: MethodReference,
)

internal fun BytecodePatchContext.findReelAboveComments(): ReelAboveCommentsHooks {
    // The manager: the one method loading the audio button's tap event is its onBottomSheetSingleTapConfirmed.
    val tapConfirmed = loader(SHEET_AUDIO_TAP, "onBottomSheetSingleTapConfirmed")
    tapConfirmed.requireShape("onBottomSheetSingleTapConfirmed", listOf(MOTION_EVENT), "Z")
    val manager = tapConfirmed.definingClass
    val managerClass = classDefByOrNull(manager) ?: refuse("the manager $manager isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(managerClass.accessFlags)) refuse("the manager $manager isn't public, so the extension can't reach it")
    val callbacks = managerClass.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
    fun callback(what: String, matches: (Method) -> Boolean): Method {
        val found = callbacks.filter(matches)
        return found.singleOrNull() ?: refuse("expected the manager $manager to have one $what, found ${found.size}")
    }
    // onBottomSheetHeightChanged is its one instance (I)V method.
    val heightChanged = callback("instance (I)V method, its onBottomSheetHeightChanged") {
        it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("I")
    }
    if (0 in heightChanged.jumpTargets()) refuse("${heightChanged.text()} jumps back to its first instruction")

    // The controller: the one method loading pauseCurrentPlayer's trace is its pause, and resumeCurrentPlayer
    // and setPlaybackSpeed are its one instance method of their shape.
    val pause = loader(PLAYER_PAUSE_TRACE, "pauseCurrentPlayer")
    pause.requireShape("pauseCurrentPlayer", listOf(STRING, "Z", "Z"), "V")
    val controller = pause.definingClass
    val controllerClass = classDefByOrNull(controller) ?: refuse("the controller $controller isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(controllerClass.accessFlags)) refuse("the controller $controller isn't public, so the extension can't reach it")
    fun controllerMethod(what: String, parameters: List<String>, returns: String): Method {
        val found = controllerClass.methods.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returns && it.parameterTypes.map(Any::toString) == parameters
        }
        return found.singleOrNull()
            ?: refuse("expected the controller $controller to have one instance (${parameters.joinToString("")})$returns method, its $what, found ${found.size}")
    }
    val resume = controllerMethod("resumeCurrentPlayer", listOf(STRING, "J", "Z", "Z", "Z"), "V")
    val speed = controllerMethod("setPlaybackSpeed", listOf(FUNCTION1, "F"), "V")
    listOf(speed, pause, resume).forEach { method ->
        if (method.definingClass != controller) refuse("${method.text()} isn't in the controller $controller")
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags)) refuse("${method.text()} isn't public, so the extension can't call it")
    }

    // The manager plays its reel through its one controller field.
    val controllerFields = managerClass.fields.filter { it.type == controller && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val controllerField = controllerFields.singleOrNull()
        ?: refuse("the manager $manager has ${controllerFields.size} $controller fields, expected one")
    if (!AccessFlags.PUBLIC.isSet(controllerField.accessFlags)) refuse("$manager->${controllerField.name} isn't public, so the extension can't read it")

    // onFullyOpened, the manager's one ()V method pausing, pauses a playing reel through the controller's
    // current player and its playing check.
    val fullyOpened = callback("()V method pausing through ${pause.text()}, its onFullyOpened") { method ->
        method.returnType == "V" && method.parameterTypes.isEmpty() &&
            method.code().any { it.opcode == Opcode.INVOKE_VIRTUAL && it.methodReference()?.text() == pause.text() }
    }
    val openedCalls = fullyOpened.code().filter { it.opcode == Opcode.INVOKE_VIRTUAL }.mapNotNull { it.methodReference() }
        .filter { it.definingClass == controller }
    val playingChecks = openedCalls.filter { it.returnType == "Z" && it.parameterTypes.size == 1 }.distinctBy { it.text() }
    val isPlaying = playingChecks.singleOrNull()
        ?: refuse("${fullyOpened.text()} makes ${playingChecks.size} one-argument boolean calls on the controller, expected one: its playing check")
    val playerType = isPlaying.parameterTypes.single().toString()
    val getters = openedCalls.filter { it.parameterTypes.isEmpty() && it.returnType == playerType }.distinctBy { it.text() }
    val current = getters.singleOrNull()
        ?: refuse("${fullyOpened.text()} makes ${getters.size} calls on the controller returning $playerType, expected one: its current player")
    listOf(isPlaying, current).forEach { call ->
        val declared = controllerClass.methods.singleOrNull {
            it.name == call.name && it.returnType == call.returnType &&
                it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
        } ?: refuse("the controller $controller doesn't declare ${call.text()}")
        if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
            refuse("${call.text()} isn't a public instance method, so the extension can't call it")
        }
    }

    // onBottomSheetHeightChanged stores the scaled reel view it finds first.
    val media = heightChanged.mediaField(manager)
    // The open flag is the first one it reads, which it sets.
    val heightCode = heightChanged.code()
    val open = heightCode.firstOrNull { it.opcode == Opcode.IGET_BOOLEAN && it.fieldReference()?.definingClass == manager }
        ?.fieldReference() ?: refuse("${heightChanged.text()} reads no flag of the manager")
    if (heightCode.none { it.writesField(open) }) refuse("${heightChanged.text()} reads ${open.name} but never sets it")
    // onBottomSheetDismissStarted, the one callback taking the dismissal's reason, an enum, first, sets
    // the closing flag first.
    val dismissStarted = callback("method taking an enum first and returning V, its onBottomSheetDismissStarted") { method ->
        method.returnType == "V" && method.parameterTypes.firstOrNull()?.toString()
            ?.let { type -> classDefByOrNull(type)?.let { AccessFlags.ENUM.isSet(it.accessFlags) } } == true
    }
    val dismissing = dismissStarted.code().firstOrNull { it.opcode == Opcode.IPUT_BOOLEAN && it.fieldReference()?.definingClass == manager }
        ?.fieldReference() ?: refuse("${dismissStarted.text()} sets no flag of the manager")
    if (dismissing.name == open.name) refuse("${dismissStarted.text()} sets the open flag ${open.name} first")
    // onBottomSheetDismissed, the one ()V callback clearing it, clears the open flag too.
    val dismissed = callback("()V method clearing ${dismissing.name}, its onBottomSheetDismissed") { method ->
        method.returnType == "V" && method.parameterTypes.isEmpty() && method.code().any { it.writesField(dismissing) }
    }
    if (dismissed.code().none { it.writesField(open) }) refuse("${dismissed.text()} doesn't clear ${open.name}")
    listOf(media, open, dismissing).forEach { field ->
        val declared = managerClass.fields.singleOrNull { it.name == field.name && it.type == field.type }
            ?: refuse("the manager $manager doesn't declare ${field.name}:${field.type}")
        if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
            refuse("$manager->${field.name} isn't a public instance field, so the extension can't read it")
        }
    }

    // Each answer of onBottomSheetSingleTapConfirmed goes through the hook, which names its return register, this and the event.
    val tapCode = tapConfirmed.code()
    val returns = tapCode.indices.filter { tapCode[it].opcode == Opcode.RETURN }
    if (returns.isEmpty()) refuse("${tapConfirmed.text()} has no return")
    val self = tapConfirmed.localRegisterCount()
    val event = tapConfirmed.parameterRegisterNumber(0)
    val high = (returns.map { (tapCode[it] as OneRegisterInstruction).registerA } + self + event).filter { it > 15 }
    if (high.isNotEmpty()) refuse("${tapConfirmed.text()} holds its answer, this or the event in ${high.joinToString { "v$it" }}, past v15")
    tapConfirmed.requireThisIntact(PATCH, returns)
    tapConfirmed.requireParameterIntact(PATCH, 0, returns)

    // IgFragmentActivity's own dispatchTouchEvent, which every touch passes through first.
    val activity = classDefByOrNull(FRAGMENT_ACTIVITY) ?: refuse("this build has no $FRAGMENT_ACTIVITY")
    val touches = activity.methods.filter {
        it.name == "dispatchTouchEvent" && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT) &&
            it.implementation != null
    }
    val touch = touches.singleOrNull() ?: refuse("expected $FRAGMENT_ACTIVITY to have one dispatchTouchEvent of its own, found ${touches.size}")

    return ReelAboveCommentsHooks(
        heightChanged, tapConfirmed, returns, touch, manager, media, open, dismissing, controllerField,
        current, isPlaying, pause, resume, speed,
    )
}

/**
 * The sheet-moved hook goes first in onBottomSheetHeightChanged, where nothing jumps back to. Each
 * return of onBottomSheetSingleTapConfirmed takes the tap hook ahead of it with its label, so every
 * path to it runs the hook, the later one first so the earlier index still points where it did. The
 * touch hook goes first in dispatchTouchEvent, or right after Tap to play's TapClock when that's
 * already there. It never answers, so the hooks after it see every touch as before.
 */
internal fun BytecodePatchContext.applyReelAboveComments(found: ReelAboveCommentsHooks) {
    mutable(found.heightChanged).addInstructions(0, "invoke-static/range { p0 .. p0 }, $SHEET_MOVED")

    val tap = mutable(found.tapConfirmed)
    val self = found.tapConfirmed.localRegisterCount()
    val event = found.tapConfirmed.parameterRegisterNumber(0)
    found.returns.sortedDescending().forEach { at ->
        val answer = (found.tapConfirmed.code()[at] as OneRegisterInstruction).registerA
        tap.addInstructionsAtControlFlowLabel(
            at,
            """
                invoke-static { v$answer, v$self, v$event }, $SHEET_TAPPED
                move-result v$answer
            """,
        )
    }

    val touch = mutable(found.touch)
    val first = touch.code().firstOrNull()?.methodReference()
    val at = if (first != null && first.text() == TAP_CLOCK) 1 else 0
    touch.addInstructions(at, "invoke-static/range { p0 .. p1 }, $SHEET_TOUCH")
}

/** The extension's stubs, found before anything changes, and the step that fills them. */
internal class ReelAboveCommentsStubs(
    private val extension: MutableClass,
    private val mediaView: MutableMethod,
    private val sheetOpen: MutableMethod,
    private val controllerOf: MutableMethod,
    private val playingNow: MutableMethod,
    private val pausePlayer: MutableMethod,
    private val resumePlayer: MutableMethod,
    private val setPlayerSpeed: MutableMethod,
) {
    fun fill(found: ReelAboveCommentsHooks) {
        val manager = found.manager
        val controller = found.speed.definingClass
        rewriteStub(
            extension, mediaView, 0,
            """
                check-cast p0, $manager
                iget-object p0, p0, ${found.media.text()}
                return-object p0
            """,
        )
        rewriteStub(
            extension, sheetOpen, 1,
            """
                check-cast p0, $manager
                iget-boolean v0, p0, ${found.open.text()}
                if-eqz v0, :shut
                iget-boolean v0, p0, ${found.dismissing.text()}
                if-nez v0, :shut
                const/4 v0, 0x1
                return v0
                :shut
                const/4 v0, 0x0
                return v0
            """,
        )
        rewriteStub(
            extension, controllerOf, 0,
            """
                check-cast p0, $manager
                iget-object p0, p0, ${found.controllerField.text()}
                return-object p0
            """,
        )
        rewriteStub(
            extension, playingNow, 1,
            """
                check-cast p0, $controller
                invoke-virtual { p0 }, ${found.current.text()}
                move-result-object v0
                if-eqz v0, :none
                invoke-virtual { p0, v0 }, ${found.isPlaying.text()}
                move-result v0
                return v0
                :none
                const/4 v0, -0x1
                return v0
            """,
        )
        rewriteStub(
            extension, pausePlayer, 2,
            """
                check-cast p0, $controller
                const-string v0, "$TAP_PAUSE_REASON"
                const/4 v1, 0x1
                invoke-virtual { p0, v0, v1, v1 }, ${found.pause.text()}
                return-void
            """,
        )
        rewriteStub(
            extension, resumePlayer, 7,
            """
                move-object v0, p0
                check-cast v0, $controller
                const-string v1, "$TAP_RESUME_REASON"
                const-wide/16 v2, 0x0
                const/4 v4, 0x1
                const/4 v5, 0x0
                const/4 v6, 0x0
                invoke-virtual/range { v0 .. v6 }, ${found.resume.text()}
                return-void
            """,
        )
        rewriteStub(
            extension, setPlayerSpeed, 1,
            """
                check-cast p0, $controller
                const/4 v0, 0x0
                invoke-virtual { p0, v0, p1 }, ${found.speed.text()}
                return-void
            """,
        )
    }
}

internal fun BytecodePatchContext.reelAboveCommentsStubs(): ReelAboveCommentsStubs {
    val extension = mutableClassDefBy(REEL_ABOVE_COMMENTS)
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: refuse("$REEL_ABOVE_COMMENTS has no static $returns $name(${parameters.joinToString("")})")
    return ReelAboveCommentsStubs(
        extension,
        mediaView = stub("mediaView", listOf(OBJECT), VIEW),
        sheetOpen = stub("sheetOpen", listOf(OBJECT), "I"),
        controllerOf = stub("controllerOf", listOf(OBJECT), OBJECT),
        playingNow = stub("playingNow", listOf(OBJECT), "I"),
        pausePlayer = stub("pausePlayer", listOf(OBJECT), "V"),
        resumePlayer = stub("resumePlayer", listOf(OBJECT), "V"),
        setPlayerSpeed = stub("setPlayerSpeed", listOf(OBJECT, "F"), "V"),
    )
}

/**
 * The view field [this], onBottomSheetHeightChanged, stores the scaled reel in: its first
 * findViewById's answer, moved straight into a [manager] View field.
 */
private fun Method.mediaField(manager: String): FieldReference {
    val code = code()
    val find = code.indices.firstOrNull { code[it].methodReference()?.text() == FIND_VIEW_BY_ID }
        ?: refuse("${text()} finds no view")
    val moved = code.getOrNull(find + 1) as? OneRegisterInstruction
    val store = code.getOrNull(find + 2) as? TwoRegisterInstruction
    if (moved == null || store == null || moved.opcode != Opcode.MOVE_RESULT_OBJECT || store.opcode != Opcode.IPUT_OBJECT) {
        refuse("${text()} doesn't store the first view it finds straight into a field")
    }
    val field = store.fieldReference()
    if (field == null || field.definingClass != manager || field.type != VIEW ||
        store.registerA != moved.registerA || store.registerB != localRegisterCount()
    ) {
        refuse("${text()} stores the first view it finds somewhere other than a View field of the manager")
    }
    return field
}

/**
 * The one instance method of Instagram's loading [value], which is [what]. A static string pool
 * answering for it holds it too, so only instance methods count.
 */
private fun BytecodePatchContext.loader(value: String, what: String): Method {
    val found = classesLoadingString(value).flatMap { it.methods }
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && loadsString(it, value) }
    return found.singleOrNull() ?: refuse("expected one instance method loading \"$value\", its $what, found ${found.size}")
}

/** Throws unless [this], Instagram's [what], is an instance method taking [parameters] and returning [returns]. */
private fun Method.requireShape(what: String, parameters: List<String>, returns: String) {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != returns || parameterTypes.map(Any::toString) != parameters) {
        refuse("${text()}, its $what, isn't an instance (${parameters.joinToString("")})$returns method")
    }
}

private fun Instruction.writesField(field: FieldReference): Boolean =
    opcode == Opcode.IPUT_BOOLEAN && fieldReference()?.let { it.definingClass == field.definingClass && it.name == field.name } == true

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun MethodReference.text(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.text(): String = "$definingClass->$name:$type"
