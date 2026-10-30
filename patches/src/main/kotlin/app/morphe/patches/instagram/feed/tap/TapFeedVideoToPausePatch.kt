/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.tap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.ads.adDetailsGetter
import app.morphe.patches.instagram.feed.sound.FEED_CONTROLLER_STRING
import app.morphe.patches.instagram.feed.sound.writesReaching
import app.morphe.patches.instagram.media.taptoplay.findPlayInternal
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.cleanup.CLIPS_VIEWER_SOURCE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Member
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Tap a feed video to pause it"
internal const val FEED_TAP_PAUSE = "$EXTENSION_PACKAGE/feed/FeedTapPause;"
internal const val FEED_TAPPED = "$FEED_TAP_PAUSE->tapped(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val FEED_PRESSED = "$FEED_TAP_PAUSE->pressed(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"
internal const val FEED_RELEASED = "$FEED_TAP_PAUSE->released(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V"
internal const val FEED_OPENING = "$FEED_TAP_PAUSE->opening(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"

/** The extension class holding the stubs the patch fills, apart from the hook Instagram's tap calls. */
internal const val FEED_PLAYER_READER = "$EXTENSION_PACKAGE/feed/FeedPlayerReader;"

/** The reader's stubs the patch fills in, each (name, parameter count). */
internal val FEED_PLAYER_STUBS = listOf(
    "controllerOf" to 1, "controllerOfLauncher" to 1, "shownOf" to 1, "pageOf" to 2, "adDetailsOf" to 1, "playerOf" to 1,
    "playing" to 1, "pause" to 2, "start" to 4, "mark" to 2,
)

/** A string only the feed's tap handler loads, for the soft error it logs when a tap has nowhere to go. */
internal const val FEED_TAP_HANDLER_STRING = "FeedMediaTapHandlerV2-softError"

/** A string the feed's Reels launcher loads, the argument naming the profile a profile's feed belongs to. */
internal const val REELS_LAUNCHER_STRING = "ContextualFeedFragment.ARGUMENT_HOST_PROFILE_USER_ID"

/** Two names of the enum saying where the Reels launcher was asked from: a tap on a feed video, and a preview. */
internal const val VIDEO_TAP = "VIDEO_TAP"
internal const val FEED_PREVIEW = "FEED_PREVIEW"

/** The reason the feed video controller plays a video with when a tap asks for it. */
internal const val FEED_START_REASON = "start"

private const val MEDIA = "Lcom/instagram/feed/media/Media;"
private const val VIEW = "Landroid/view/View;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val LIST = "Ljava/util/List;"
private const val ENUM = "Ljava/lang/Enum;"

/**
 * How many parameters the tap handler takes: 450's (View, Media, point, holder, state, y, int,
 * boolean), or 451's, which adds one more object after y (on 385713073 LX/07Vc). The post and the
 * state the hook reads keep their places, the second and the fifth.
 */
private val HANDLER_PARAMETERS = 8..9

/** How far past its read of the playing item the controller's tap asks that item for its post. */
private const val ITEM_READ_REACH = 8

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * A tap on the video playing in a feed pauses it in place instead of opening it in Reels, and the
 * next tap plays it again.
 *
 * The text promises the play too since the S22 on 451 (385713073) showed a tap pausing and the next
 * one playing, through the tap handler both times (2026-10-15). The pause leaves the controller's playing item bound (only its bind and the player's stop
 * callback write it), so the next tap finds the same post and the controller's play resumes it: an
 * S25 run on 385611438 counted one tap left with nothing playing yet, one pause and one play, all in
 * place. Its fourth tap opened Reels without reaching the tap handler at all. That was a press held
 * past Instagram's hold delay, a server value, which the feed's gesture listener sends to the
 * handler class's press entry in place of the tap handler. The patch now guards that entry and its
 * release too (see [applyFeedTapPause]).
 *
 * Instagram 450 and 451 send every tap on a feed post through one handler, which decides between opening
 * the post in the Reels viewer, showing its tags, turning its sound on and a few more. Only with
 * TalkBack on does the feed video controller it ends in pause and play the video. That pause and
 * play is a short run of the controller's own tap: ask the player whether it's playing, then call
 * the controller's pause with Instagram's tap reason or its play with "start", and tell the post's
 * state which of the two it was.
 *
 * The patch asks the extension first thing in the handler, with the handler, the post and the
 * post's state. The extension reaches the controller the way the handler does, and when the post
 * tapped is the one the controller has playing, the test Instagram's own tap makes, it runs that
 * same pause or play and answers yes, and the handler returns. An ad, a photo, a post that isn't
 * the one playing, the switch off, Pause, a settings screen that isn't ready and anything that
 * throws all answer no, and the handler goes on exactly as it was. The speaker button has its own
 * listener and never reaches this handler.
 *
 * A press held past the hold delay goes to the handler class's press entry instead, which opens the
 * post in Reels (or plays it faster), and its end to the class's release. The extension takes such a
 * press on the playing video under the same checks, so the press entry returns, and its lift then
 * pauses or plays the video like a tap.
 *
 * On 451 a tap can reach Reels without the handler too: a phone run on 385713073 with the switch on
 * opened Reels and never asked. The video's overlays open Reels on their own (a finished feed reel's end
 * screen among them, which is what that run tapped), and a server setting can send Instagram's own tap
 * there. Every one of those
 * ends in the feed's one Reels launcher, so the patch asks the extension first thing there as well, with
 * where the launcher was asked from. A tap on the playing video (Instagram's VIDEO_TAP) pauses or plays
 * it under the same checks and the launcher returns; every other opening goes on to Reels, among them
 * the end screen of a finished feed reel (BACKGROUND_TAP for its backdrop, WATCH_AGAIN, WATCH_MORE_REELS).
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val tapFeedVideoToPausePatch = bytecodePatch(
    name = "Tap a feed video to pause it",
    description = "A tap on the video playing in a feed pauses it in place instead of opening it in Reels, and " +
        "another tap plays it again. Ads keep Instagram's own tap. Starts off. Turn it on in HushGram settings > Feed.",
) {
    // Manager's Feed group is full at the fifteen PatchCategoriesTest allows. The switch is still under Feed in settings.
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("feedTapPause")
        applyFeedTapPause(findFeedTap())
        enableStatus("feedTapPause")
    }
}

/**
 * The feed's tap handler and what the extension's stubs need to pause and play the video under a tap.
 *
 * @property moduleSupplier the handler's lazy field handing over the feed video module, read through
 *           [supplierValue], and [controllerField] the module's field holding its controller
 * @property playerField the controller's player, and [isPlaying] that player's own answer
 * @property itemField the controller's item for the video it has playing, and [shown] the post
 *           that item shows, a carousel's page when the post is a carousel
 * @property pageField the post state's page number, and [pager] Instagram's lookup of a post's page
 * @property play the controller's play, called with [FEED_START_REASON], and [pause] its pause,
 *           called with [reasonText] on 451, or on 450 with the name [reasonName] gives [reasonKey]
 * @property mark the post state's note of whether the video was playing when it was tapped
 * @property adDetails Media's getter for a sponsored post's ad details
 * @property press the handler class's press entry, which a press held past Instagram's hold delay
 *           reaches in place of [handler], and [release] the class's end of that press
 * @property launcher the feed's Reels launcher, which every way into Reels from a feed post ends in,
 *           and [launcherSupplier] its lazy field handing over the module, read through [launcherValue]
 */
internal class FeedTap(
    val handler: Method,
    val press: Method,
    val release: Method,
    val launcher: Method,
    val launcherSupplier: FieldReference,
    val launcherValue: MethodReference,
    val launcherIsInterface: Boolean,
    val moduleSupplier: FieldReference,
    val supplierValue: MethodReference,
    val supplierIsInterface: Boolean,
    val module: String,
    val controllerField: FieldReference,
    val controller: String,
    val playerField: FieldReference,
    val isPlaying: MethodReference,
    val itemField: FieldReference,
    val shown: MethodReference,
    val state: String,
    val pageField: FieldReference,
    val pager: MethodReference,
    val play: MethodReference,
    val pause: MethodReference,
    val reasonText: String?,
    val reasonKey: FieldReference?,
    val reasonName: MethodReference?,
    val mark: MethodReference,
    val adDetails: MethodReference,
)

/**
 * The handler is the one instance method returning void with the parameters (View, Media, x, holder,
 * state, y, int, boolean), 451 adding one more object before the int, that loads
 * [FEED_TAP_HANDLER_STRING] (see [HANDLER_PARAMETERS]). It hands a tap on a video to one
 * method of the feed video module, taking (Media, holder, state, a, b, int, boolean), which it gets
 * from a lazy field of its own, and that method hands it to one method of the feed video
 * controller, the class loading [FEED_CONTROLLER_STRING], taking (Media, c, holder, state, d, int,
 * boolean) and answering a boolean.
 *
 * The handler's class also holds the press entry and its release, each the one instance void method
 * of its shape there: (View, Media, point, state) and (Media, point, state, boolean), with the point
 * and the state the handler's own third and fifth parameter types.
 *
 * That controller tap holds everything else. It looks up the page of the post that was tapped from
 * the state's page number, and compares it with the post its playing item shows. It plays through
 * the controller's one static (controller, String, boolean, boolean, boolean) method, once, with
 * [FEED_START_REASON], false, true and false, and right after tells the state whether the player was
 * playing, which it asked the player, IgVideoPlayerImpl, just before. It pauses through the
 * controller's one instance (String) method, once, with the name a static method gives an Integer
 * constant. Each of those has to be there once, in that shape, and every class and member the
 * extension's stubs then name has to be public.
 */
internal fun BytecodePatchContext.findFeedTap(): FeedTap {
    val handlers = classesLoadingString(FEED_TAP_HANDLER_STRING).flatMap { it.methods }.filter { method ->
        val p = method.parameterTypes.map(Any::toString)
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.implementation != null &&
            p.size in HANDLER_PARAMETERS && p[0] == VIEW && p[1] == MEDIA && p.subList(2, p.size - 2).all { it.startsWith("L") } &&
            p[p.size - 2] == "I" && p[p.size - 1] == "Z" && loadsString(method, FEED_TAP_HANDLER_STRING)
    }
    val handler = handlers.singleOrNull()
        ?: refuse("expected one feed tap handler loading \"$FEED_TAP_HANDLER_STRING\", found ${handlers.size}")
    val handlerClass = classDefBy(handler.definingClass)
    val where = "${handler.definingClass}->${handler.name}"
    val point = handler.parameterTypes[2].toString()
    val holder = handler.parameterTypes[3].toString()
    val state = handler.parameterTypes[4].toString()
    if (handler.localRegisterCount() < HOOK_LOCALS) refuse("$where has ${handler.localRegisterCount()} local register(s), needs $HOOK_LOCALS")

    // A press held past the hold delay: the class's one (View, Media, point, state) entry and its one
    // (Media, point, state, boolean) release, 00G1.A06 and 00G1.A09 on 451's 385713073. On 453 the
    // entry takes the post's holder before the state too, 01Bd.A05 on 385914557; the class's
    // (View, Media, point, state, int) method there, A07, is the double-tap like, which isn't a press.
    fun instanceVoid(vararg parameters: String) = handlerClass.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.implementation != null &&
            it.parameterTypes.map(Any::toString) == parameters.toList()
    }
    val presses = instanceVoid(VIEW, MEDIA, point, state) + instanceVoid(VIEW, MEDIA, point, holder, state)
    val press = presses.singleOrNull()
        ?: refuse("expected one press entry ($VIEW, $MEDIA, $point[, $holder], $state) in ${handler.definingClass}, found ${presses.size}")
    val releases = instanceVoid(MEDIA, point, state, "Z")
    val release = releases.singleOrNull()
        ?: refuse("expected one press release ($MEDIA, $point, $state, boolean) in ${handler.definingClass}, found ${releases.size}")
    if (press.localRegisterCount() < PRESS_LOCALS) refuse("${press.definingClass}->${press.name} has ${press.localRegisterCount()} local register(s), needs $PRESS_LOCALS")
    if (release.localRegisterCount() < RELEASE_LOCALS) {
        refuse("${release.definingClass}->${release.name} has ${release.localRegisterCount()} local register(s), needs $RELEASE_LOCALS")
    }

    val controllers = classesLoadingString(FEED_CONTROLLER_STRING)
    val controllerClass = controllers.singleOrNull()
        ?: refuse("expected one class loading \"$FEED_CONTROLLER_STRING\", found ${controllers.size}")
    val controller = controllerClass.type

    // The handler's way to the controller: its lazy field, the module, the module's field.
    val handlerCode = handler.code()
    val moduleTaps = handlerCode.mapNotNull { it.methodReference() }.filter { call ->
        val p = call.parameterTypes.map(Any::toString)
        call.returnType == "V" && p.size == 7 && p[0] == MEDIA && p[1] == holder && p[2] == state && p[5] == "I" && p[6] == "Z"
    }.distinctBy { it.toString() }
    val moduleTap = moduleTaps.singleOrNull()
        ?: refuse("expected $where to hand a video's tap to one method taking ($MEDIA, $holder, $state, ...), found ${moduleTaps.size}")
    val module = moduleTap.definingClass
    val moduleClass = classDefByOrNull(module) ?: refuse("this build has no $module")
    val suppliers = handlerCode.indices.mapNotNull { at ->
        val field = handlerCode[at].fieldReference() ?: return@mapNotNull null
        if (handlerCode[at].opcode != Opcode.IGET_OBJECT || field.definingClass != handler.definingClass) return@mapNotNull null
        val asked = handlerCode.getOrNull(at + 1) ?: return@mapNotNull null
        val value = asked.methodReference() ?: return@mapNotNull null
        val result = handlerCode.getOrNull(at + 2) ?: return@mapNotNull null
        val cast = handlerCode.getOrNull(at + 3) ?: return@mapNotNull null
        if ((asked.opcode != Opcode.INVOKE_INTERFACE && asked.opcode != Opcode.INVOKE_VIRTUAL) || value.definingClass != field.type ||
            value.name != "getValue" || value.parameterTypes.isNotEmpty() || value.returnType != OBJECT ||
            (asked as FiveRegisterInstruction).registerC != (handlerCode[at] as TwoRegisterInstruction).registerA ||
            result.opcode != Opcode.MOVE_RESULT_OBJECT || cast.opcode != Opcode.CHECK_CAST ||
            ((cast as ReferenceInstruction).reference as TypeReference).type != module ||
            (cast as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA
        ) {
            return@mapNotNull null
        }
        Triple(field, value, asked.opcode == Opcode.INVOKE_INTERFACE)
    }.distinctBy { (field, value, _) -> "$field $value" }
    val (moduleSupplier, supplierValue, supplierIsInterface) = suppliers.singleOrNull()
        ?: refuse("expected one lazy field of ${handler.definingClass} handing over $module, found ${suppliers.size}")
    val supplierClass = classDefByOrNull(moduleSupplier.type) ?: refuse("this build has no ${moduleSupplier.type}")
    if (AccessFlags.INTERFACE.isSet(supplierClass.accessFlags) != supplierIsInterface) {
        refuse("$where asks ${moduleSupplier.type} for its value the wrong way for what it is")
    }
    val controllerFields = moduleClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == controller }
    val controllerField = controllerFields.singleOrNull()
        ?: refuse("expected $module to hold one $controller, found ${controllerFields.size}")
    val launcher = findReelsLauncher(state, module)
    val launcherClass = classDefBy(launcher.method.definingClass)
    val launcherSupplierClass = classDefByOrNull(launcher.supplier.type) ?: refuse("this build has no ${launcher.supplier.type}")
    if (AccessFlags.INTERFACE.isSet(launcherSupplierClass.accessFlags) != launcher.isInterface) {
        refuse("${launcher.method.definingClass}->${launcher.method.name} asks ${launcher.supplier.type} for its value the wrong way for what it is")
    }

    // The controller's own tap, which the module's tap hands over to.
    val moduleMethod = moduleClass.methods.singleOrNull { it.matches(moduleTap) && it.implementation != null }
        ?: refuse("$moduleTap has no body in this build")
    val taps = moduleMethod.code().mapNotNull { it.methodReference() }.filter { call ->
        val p = call.parameterTypes.map(Any::toString)
        call.definingClass == controller && call.returnType == "Z" && p.size == 7 && p[0] == MEDIA && p[2] == holder &&
            p[3] == state && p[5] == "I" && p[6] == "Z"
    }.distinctBy { it.toString() }
    val tapCall = taps.singleOrNull()
        ?: refuse("expected $module->${moduleTap.name} to hand the tap to one method of $controller, found ${taps.size}")
    val tap = controllerClass.methods.singleOrNull { it.matches(tapCall) && it.implementation != null }
        ?: refuse("$tapCall has no body in this build")
    val tapName = "$controller->${tap.name}"
    val code = tap.code()

    // Play: the controller's one static (controller, String, boolean, boolean, boolean), called once.
    val plays = controllerClass.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == listOf(controller, STRING, "Z", "Z", "Z")
    }
    val play = plays.singleOrNull() ?: refuse("expected one static play (String, boolean, boolean, boolean) in $controller, found ${plays.size}")
    val playCalls = code.indices.filter { code[it].opcode == Opcode.INVOKE_STATIC && code[it].methodReference()?.let { call -> play.matches(call) } == true }
    val playAt = playCalls.singleOrNull() ?: refuse("expected $tapName to play once, found ${playCalls.size}")
    val playCall = code[playAt] as FiveRegisterInstruction
    val reasons = writesReaching(tap, playAt, playCall.registerD).map { stringWrittenAt(code, it) }
    if (reasons != listOf(FEED_START_REASON)) refuse("$tapName plays with $reasons, not \"$FEED_START_REASON\"")
    val flags = listOf(playCall.registerE, playCall.registerF, playCall.registerG).map { constantsReaching(tap, code, playAt, it) }
    if (flags != listOf(setOf(0), setOf(1), setOf(0))) refuse("$tapName plays with the flags $flags, not false, true, false")

    // Right after, the state learns whether the player was playing, which the player was just asked.
    val player = findPlayInternal(PATCH).definingClass
    val playerClass = classDefBy(player)
    val marked = code.getOrNull(playAt + 1)
    val mark = marked?.methodReference()
    if (marked?.opcode != Opcode.INVOKE_VIRTUAL || mark == null || mark.definingClass != state || mark.returnType != "V" ||
        mark.parameterTypes.map(Any::toString) != listOf("Z")
    ) {
        refuse("$tapName doesn't tell $state whether the video was playing right after it plays")
    }
    val playingAsked = writesReaching(tap, playAt + 1, (marked as FiveRegisterInstruction).registerD).singleOrNull()
        ?.takeIf { it > 0 && code[it].opcode == Opcode.MOVE_RESULT }?.let { code[it - 1] }
    val isPlaying = playingAsked?.methodReference()
    if (playingAsked?.opcode != Opcode.INVOKE_VIRTUAL || isPlaying == null || isPlaying.definingClass != player ||
        isPlaying.returnType != "Z" || isPlaying.parameterTypes.isNotEmpty()
    ) {
        refuse("$tapName doesn't ask $player whether it's playing for what it tells $state")
    }
    val playerFields = controllerClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == player }
    val playerField = playerFields.singleOrNull() ?: refuse("expected $controller to hold one $player, found ${playerFields.size}")

    // Pause: the controller's one instance (String), called once with one reason: on 450 the name of an
    // Integer constant, on 451 a string of its own ("tapped" on 385713073).
    val pauses = controllerClass.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(STRING)
    }
    val pause = pauses.singleOrNull() ?: refuse("expected one pause (String) in $controller, found ${pauses.size}")
    val pauseCalls = code.indices.filter { code[it].opcode == Opcode.INVOKE_VIRTUAL && code[it].methodReference()?.let { call -> pause.matches(call) } == true }
    val pauseAt = pauseCalls.singleOrNull() ?: refuse("expected $tapName to pause once, found ${pauseCalls.size}")
    val reasonWrite = writesReaching(tap, pauseAt, (code[pauseAt] as FiveRegisterInstruction).registerD).singleOrNull()
    val reasonText = reasonWrite?.let { stringWrittenAt(code, it) }
    var reasonKey: FieldReference? = null
    var reasonName: MethodReference? = null
    if (reasonText == null) {
        val named = reasonWrite?.takeIf { it > 0 && code[it].opcode == Opcode.MOVE_RESULT_OBJECT }?.let { it - 1 }
        reasonName = named?.let { code[it].methodReference() }
        if (named == null || reasonName == null || code[named].opcode != Opcode.INVOKE_STATIC || reasonName.returnType != STRING ||
            reasonName.parameterTypes.map(Any::toString) != listOf(INTEGER)
        ) {
            refuse("$tapName doesn't pause with one string or the name of one of Instagram's reasons")
        }
        val keyed = writesReaching(tap, named, (code[named] as FiveRegisterInstruction).registerC).singleOrNull()
        reasonKey = keyed?.let { code[it].fieldReference() }
        if (keyed == null || reasonKey == null || code[keyed].opcode != Opcode.SGET_OBJECT || reasonKey.type != INTEGER) {
            refuse("$tapName doesn't name its pause reason from one constant")
        }
    } else if (reasonText == FEED_START_REASON) {
        refuse("$tapName pauses with the reason it plays with, \"$FEED_START_REASON\"")
    }

    // The post the playing item shows, and the page of the post that was tapped.
    val items = code.indices.mapNotNull { at ->
        val field = code[at].fieldReference() ?: return@mapNotNull null
        if (code[at].opcode != Opcode.IGET_OBJECT || field.definingClass != controller) return@mapNotNull null
        val register = (code[at] as OneRegisterInstruction).registerA
        for (k in at + 1..minOf(code.lastIndex, at + ITEM_READ_REACH)) {
            val call = code[k].methodReference()
            if (code[k].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.definingClass == field.type &&
                call.parameterTypes.isEmpty() && call.returnType == MEDIA && (code[k] as FiveRegisterInstruction).registerC == register
            ) {
                return@mapNotNull field to call
            }
            if (code[k].opcode.setsRegister() && (code[k] as? OneRegisterInstruction)?.registerA == register) break
        }
        null
    }.distinctBy { (field, call) -> "$field $call" }
    val (itemField, shown) = items.singleOrNull()
        ?: refuse("expected $tapName to ask one of its items for the post it shows, found ${items.size}")
    val itemClass = classDefByOrNull(itemField.type) ?: refuse("this build has no ${itemField.type}")
    val pages = code.indices.mapNotNull { at ->
        val field = code[at].fieldReference() ?: return@mapNotNull null
        val looked = code.getOrNull(at + 1) ?: return@mapNotNull null
        val pager = looked.methodReference() ?: return@mapNotNull null
        if (code[at].opcode != Opcode.IGET || field.definingClass != state || field.type != "I" || looked.opcode != Opcode.INVOKE_STATIC ||
            pager.parameterTypes.map(Any::toString) != listOf(OBJECT, "I") || !pager.returnType.startsWith("L") ||
            (looked as FiveRegisterInstruction).registerD != (code[at] as OneRegisterInstruction).registerA
        ) {
            return@mapNotNull null
        }
        // The post and the state are the tap's own, moved out of their parameters and nothing else.
        val post = writesReaching(tap, at + 1, (looked as FiveRegisterInstruction).registerC).singleOrNull()
        val from = writesReaching(tap, at, (code[at] as TwoRegisterInstruction).registerB).singleOrNull()
        if (post == null || from == null || !code[post].movesFrom(tap.parameterRegisterNumber(0)) ||
            !code[from].movesFrom(tap.parameterRegisterNumber(3))
        ) {
            return@mapNotNull null
        }
        field to pager
    }.distinctBy { (field, pager) -> "$field $pager" }
    val (pageField, pager) = pages.singleOrNull()
        ?: refuse("expected $tapName to look up the tapped post's page once, found ${pages.size}")
    val shownMethod = itemClass.methods.singleOrNull { it.matches(shown) && it.implementation != null }
        ?: refuse("$shown has no body in this build")
    if (shownMethod.code().none { it.opcode == Opcode.INVOKE_STATIC && it.methodReference()?.toString() == pager.toString() }) {
        refuse("$shown doesn't look its post up the way $tapName looks up the tapped one")
    }

    val adDetails = adDetailsGetter(PATCH)

    // The stubs run in the extension, so every class they cast to and every member they name must be
    // public, and declared where they name it: one found only on a superclass counts as unreachable.
    val stateClass = classDefByOrNull(state) ?: refuse("this build has no $state")
    val keyClass = reasonKey?.let { classDefByOrNull(it.definingClass) ?: refuse("this build has no ${it.definingClass}") }
    val nameClass = reasonName?.let { classDefByOrNull(it.definingClass) ?: refuse("this build has no ${it.definingClass}") }
    val pagerClass = classDefByOrNull(pager.definingClass) ?: refuse("this build has no ${pager.definingClass}")
    val unreachable = listOfNotNull(
        handlerClass.takeUnless { it.isPublic() }?.type,
        supplierClass.takeUnless { it.isPublic() }?.type,
        moduleClass.takeUnless { it.isPublic() }?.type,
        controllerClass.takeUnless { it.isPublic() }?.type,
        playerClass.takeUnless { it.isPublic() }?.type,
        itemClass.takeUnless { it.isPublic() }?.type,
        stateClass.takeUnless { it.isPublic() }?.type,
        keyClass?.takeUnless { it.isPublic() }?.type,
        nameClass?.takeUnless { it.isPublic() }?.type,
        pagerClass.takeUnless { it.isPublic() }?.type,
        "$moduleSupplier".takeUnless { handlerClass.declares(moduleSupplier) },
        "$supplierValue".takeUnless { supplierIsInterface || supplierClass.declares(supplierValue) },
        launcherClass.takeUnless { it.isPublic() }?.type,
        launcherSupplierClass.takeUnless { it.isPublic() }?.type,
        "${launcher.supplier}".takeUnless { launcherClass.declares(launcher.supplier) },
        "${launcher.value}".takeUnless { launcher.isInterface || launcherSupplierClass.declares(launcher.value) },
        "$module->${controllerField.name}".takeUnless { controllerField.isPublicMember() },
        "$controller->${playerField.name}".takeUnless { playerField.isPublicMember() },
        "$isPlaying".takeUnless { playerClass.declares(isPlaying) },
        "$itemField".takeUnless { controllerClass.declares(itemField) },
        "$shown".takeUnless { shownMethod.isPublicMember() },
        "$pageField".takeUnless { stateClass.declares(pageField) },
        "$pager".takeUnless { pagerClass.declares(pager, static = true) },
        "$controller->${play.name}".takeUnless { play.isPublicMember() },
        "$controller->${pause.name}".takeUnless { pause.isPublicMember() },
        reasonKey?.let { key -> "$key".takeUnless { keyClass!!.declares(key, static = true) } },
        reasonName?.let { name -> "$name".takeUnless { nameClass!!.declares(name, static = true) } },
        "$mark".takeUnless { stateClass.declares(mark) },
    )
    if (unreachable.isNotEmpty()) refuse("the extension can't reach ${unreachable.joinToString()}")

    val extension = classDefByOrNull(FEED_PLAYER_READER) ?: refuse("the extension has no $FEED_PLAYER_READER")
    FEED_PLAYER_STUBS.forEach { (name, count) ->
        extension.methods.singleOrNull { it.isStub(name, count) }
            ?: refuse("$FEED_PLAYER_READER has no static Object $name taking $count Object(s)")
    }

    return FeedTap(
        handler = handler,
        press = press,
        release = release,
        launcher = launcher.method,
        launcherSupplier = launcher.supplier,
        launcherValue = launcher.value,
        launcherIsInterface = launcher.isInterface,
        moduleSupplier = moduleSupplier,
        supplierValue = supplierValue,
        supplierIsInterface = supplierIsInterface,
        module = module,
        controllerField = controllerField.reference(),
        controller = controller,
        playerField = playerField.reference(),
        isPlaying = isPlaying,
        itemField = itemField,
        shown = shown,
        state = state,
        pageField = pageField,
        pager = pager,
        play = play.reference(),
        pause = pause.reference(),
        reasonText = reasonText,
        reasonKey = reasonKey,
        reasonName = reasonName,
        mark = mark,
        adDetails = adDetails.reference(),
    )
}

/** The feed's Reels launcher and its lazy field handing over the feed video module, read through [value]. */
internal class ReelsLauncher(val method: Method, val supplier: FieldReference, val value: MethodReference, val isInterface: Boolean)

/**
 * The feed's Reels launcher (00R0.A00 on 451's 385713073), the one way into the Reels viewer from a feed
 * post: the one instance void method of a class loading [REELS_LAUNCHER_STRING] that takes (View, entry
 * point, a, b, Media, [state], List, boolean, boolean) and asks a static method for the viewer's source,
 * the entry point being the enum naming [VIDEO_TAP] and [FEED_PREVIEW]. It reads [module], the one the
 * tap handler hands a tap to, from a lazy field of its own, which is how the extension reaches the
 * controller from there.
 */
private fun BytecodePatchContext.findReelsLauncher(state: String, module: String): ReelsLauncher {
    val launchers = classesLoadingString(REELS_LAUNCHER_STRING).flatMap { it.methods }.filter { method ->
        val p = method.parameterTypes.map(Any::toString)
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.implementation != null &&
            p.size == 9 && p[0] == VIEW && p[4] == MEDIA && p[5] == state && p[6] == LIST && p[7] == "Z" && p[8] == "Z" &&
            isEntryPoint(p[1]) && loadsString(method, REELS_LAUNCHER_STRING) &&
            method.code().any { it.opcode == Opcode.INVOKE_STATIC && it.methodReference()?.returnType == CLIPS_VIEWER_SOURCE }
    }
    val launcher = launchers.singleOrNull() ?: refuse(
        "expected one Reels launcher taking ($VIEW, the entry point naming $VIDEO_TAP, ..., $MEDIA, $state, $LIST, boolean, boolean), " +
            "found ${launchers.size}",
    )
    val where = "${launcher.definingClass}->${launcher.name}"
    if (launcher.localRegisterCount() < LAUNCHER_LOCALS) refuse("$where has ${launcher.localRegisterCount()} local register(s), needs $LAUNCHER_LOCALS")

    // Its way to the module: a lazy field of its own, asked for its value and cast to the module, with
    // moves in between on 451.
    val code = launcher.code()
    val suppliers = code.indices.mapNotNull { at ->
        val asked = code[at]
        val value = asked.methodReference() ?: return@mapNotNull null
        val result = code.getOrNull(at + 1) ?: return@mapNotNull null
        val cast = code.getOrNull(at + 2) ?: return@mapNotNull null
        if (asked.opcode !in ASKS || value.name != "getValue" || value.parameterTypes.isNotEmpty() || value.returnType != OBJECT ||
            result.opcode != Opcode.MOVE_RESULT_OBJECT || cast.opcode != Opcode.CHECK_CAST ||
            ((cast as ReferenceInstruction).reference as TypeReference).type != module ||
            (cast as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA
        ) {
            return@mapNotNull null
        }
        val receiver = (asked as? FiveRegisterInstruction)?.registerC ?: (asked as RegisterRangeInstruction).startRegister
        val field = fieldReadInto(launcher, code, at, receiver) ?: return@mapNotNull null
        if (field.definingClass != launcher.definingClass || field.type != value.definingClass) return@mapNotNull null
        Triple(field, value, asked.opcode == Opcode.INVOKE_INTERFACE || asked.opcode == Opcode.INVOKE_INTERFACE_RANGE)
    }.distinctBy { (field, value, _) -> "$field $value" }
    val (supplier, value, isInterface) = suppliers.singleOrNull()
        ?: refuse("expected one lazy field of ${launcher.definingClass} handing over $module, found ${suppliers.size}")
    return ReelsLauncher(launcher, supplier, value, isInterface)
}

/** Whether [type] is the enum saying where the Reels launcher was asked from, the one naming [VIDEO_TAP] and [FEED_PREVIEW]. */
private fun BytecodePatchContext.isEntryPoint(type: String): Boolean {
    val entryPoints = classDefByOrNull(type) ?: return false
    if (entryPoints.superclass != ENUM) return false
    val names = entryPoints.methods.singleOrNull { it.name == "<clinit>" } ?: return false
    return loadsString(names, VIDEO_TAP) && loadsString(names, FEED_PREVIEW)
}

/** The field an iget-object read into [register] for the instruction at [at], through up to a few moves, or null. */
private fun fieldReadInto(method: Method, code: List<Instruction>, at: Int, register: Int): FieldReference? {
    var from = at
    var into = register
    repeat(MOVES_FOLLOWED) {
        val write = writesReaching(method, from, into).singleOrNull() ?: return null
        val written = code[write]
        if (written.opcode == Opcode.IGET_OBJECT) return written.fieldReference()
        if (written.opcode !in OBJECT_MOVES) return null
        into = (written as TwoRegisterInstruction).registerB
        from = write
    }
    return null
}

private val ASKS = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private const val MOVES_FOLLOWED = 4

/** The locals each hook borrows first thing in its method, where none holds anything yet. */
private const val HOOK_LOCALS = 3
private const val PRESS_LOCALS = 3
private const val RELEASE_LOCALS = 4
private const val LAUNCHER_LOCALS = 4

/**
 * Fills the reader's stubs, then asks [FEED_TAPPED] first thing in the handler with the handler, the
 * post and the post's state. A yes returns from the handler, which answers nothing; a no goes on to
 * its first instruction.
 *
 * The press entry asks [FEED_PRESSED] first thing the same way, with the handler, the post and the
 * state, and returns on a yes, so a held press the extension takes neither opens Reels nor speeds the
 * video up. The release tells [FEED_RELEASED] first thing, with the handler, the post, the state and
 * whether the press ended in a lift, and then goes on: the lift of a press the extension took pauses
 * or plays the video, and the release only resets Instagram's hold, which a press entry that
 * returned never set.
 *
 * The Reels launcher asks [FEED_OPENING] first thing with the launcher, where it was asked from, the
 * post and the state, and returns on a yes, so a tap that came another way pauses or plays the video
 * in place of opening Reels.
 *
 * Each stub works in its own parameters: the build compiles a stub's `return NOT_PATCHED` into its
 * parameter's register, so it has no local to lend. The pause and the play take spare parameters
 * for the reason and the flags they load.
 */
internal fun BytecodePatchContext.applyFeedTapPause(found: FeedTap) {
    val extension = mutableClassDefBy(FEED_PLAYER_READER)
    fun stub(name: String): MutableMethod = extension.methods.single { it.isStub(name, FEED_PLAYER_STUBS.toMap().getValue(name)) }

    // The stubs first: the handler calls into the extension only once everything it asks is in place.
    val ask = if (found.supplierIsInterface) "invoke-interface" else "invoke-virtual"
    // Answers an Object: a handler whose module isn't made yet answers null.
    stub("controllerOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.handler.definingClass}
            iget-object p0, p0, ${found.moduleSupplier}
            $ask { p0 }, ${found.supplierValue}
            move-result-object p0
            if-eqz p0, :none
            check-cast p0, ${found.module}
            iget-object p0, p0, ${found.controllerField}
            :none
            return-object p0
        """,
    )
    // Answers an Object: the same controller, reached from the Reels launcher's own lazy field for the
    // module, and null while a launcher's module isn't made yet.
    val launcherAsk = if (found.launcherIsInterface) "invoke-interface" else "invoke-virtual"
    stub("controllerOfLauncher").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.launcher.definingClass}
            iget-object p0, p0, ${found.launcherSupplier}
            $launcherAsk { p0 }, ${found.launcherValue}
            move-result-object p0
            if-eqz p0, :none
            check-cast p0, ${found.module}
            iget-object p0, p0, ${found.controllerField}
            :none
            return-object p0
        """,
    )
    // Answers an Object: a controller with no item bound answers null.
    stub("shownOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.controller}
            iget-object p0, p0, ${found.itemField}
            if-eqz p0, :none
            invoke-virtual { p0 }, ${found.shown}
            move-result-object p0
            :none
            return-object p0
        """,
    )
    stub("pageOf").addInstructionsWithLabels(
        0,
        """
            check-cast p1, ${found.state}
            iget p1, p1, ${found.pageField}
            invoke-static { p0, p1 }, ${found.pager}
            move-result-object p0
            return-object p0
        """,
    )
    stub("adDetailsOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $MEDIA
            invoke-virtual { p0 }, ${found.adDetails}
            move-result-object p0
            return-object p0
        """,
    )
    stub("playerOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.controller}
            iget-object p0, p0, ${found.playerField}
            return-object p0
        """,
    )
    stub("playing").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.isPlaying.definingClass}
            invoke-virtual { p0 }, ${found.isPlaying}
            move-result p0
            invoke-static { p0 }, $BOOLEAN->valueOf(Z)$BOOLEAN
            move-result-object p0
            return-object p0
        """,
    )
    val reason = found.reasonText?.let { "const-string p1, \"$it\"" }
        ?: "sget-object p1, ${found.reasonKey}\ninvoke-static { p1 }, ${found.reasonName}\nmove-result-object p1"
    stub("pause").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.controller}
            $reason
            invoke-virtual { p0, p1 }, ${found.pause}
            return-object p0
        """,
    )
    stub("start").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.controller}
            const-string p1, "$FEED_START_REASON"
            const/4 p2, 0x0
            const/4 p3, 0x1
            invoke-static { p0, p1, p2, p3, p2 }, ${found.play}
            return-object p0
        """,
    )
    stub("mark").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.state}
            check-cast p1, $BOOLEAN
            invoke-virtual { p1 }, $BOOLEAN->booleanValue()Z
            move-result p1
            invoke-virtual { p0, p1 }, ${found.mark}
            return-object p0
        """,
    )
    mutable(found.handler).apply {
        addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                move-object/from16 v1, p2
                move-object/from16 v2, p5
                invoke-static { v0, v1, v2 }, $FEED_TAPPED
                move-result v0
                if-eqz v0, :stock
                return-void
            """,
            ExternalLabel("stock", getInstruction(0)),
        )
    }
    // The press entry is (View, Media, point[, holder], state): the post is p2 and the state its last
    // parameter, p4 or p5.
    mutable(found.press).apply {
        addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                move-object/from16 v1, p2
                move-object/from16 v2, p${found.press.parameterTypes.size}
                invoke-static { v0, v1, v2 }, $FEED_PRESSED
                move-result v0
                if-eqz v0, :stock
                return-void
            """,
            ExternalLabel("stock", getInstruction(0)),
        )
    }
    // The release is (Media, point, state, boolean): the post is p1, the state p3 and the lift p4.
    mutable(found.release).addInstructions(
        0,
        """
            move-object/from16 v0, p0
            move-object/from16 v1, p1
            move-object/from16 v2, p3
            move/from16 v3, p4
            invoke-static { v0, v1, v2, v3 }, $FEED_RELEASED
        """,
    )
    // The launcher is (View, entry point, a, b, Media, state, List, boolean, boolean): the entry point is
    // p2, the post p5 and the state p6.
    mutable(found.launcher).apply {
        addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                move-object/from16 v1, p2
                move-object/from16 v2, p5
                move-object/from16 v3, p6
                invoke-static { v0, v1, v2, v3 }, $FEED_OPENING
                move-result v0
                if-eqz v0, :stock
                return-void
            """,
            ExternalLabel("stock", getInstruction(0)),
        )
    }
}

/** The string the instruction at [at] writes: a const-string's, or a static pool's answer moved out right there. */
private fun BytecodePatchContext.stringWrittenAt(code: List<Instruction>, at: Int): String? =
    if (code[at].opcode == Opcode.MOVE_RESULT_OBJECT && at > 0) stringLoadedAt(code, at - 1) else stringLoadedAt(code, at)

/** The values every write that reaches [register] at [at] loads, or an empty set when one of them isn't a plain constant. */
private fun constantsReaching(method: Method, code: List<Instruction>, at: Int, register: Int): Set<Int> {
    val writes = writesReaching(method, at, register)
    if (writes.any { code[it].opcode != Opcode.CONST_4 && code[it].opcode != Opcode.CONST_16 }) return emptySet()
    return writes.mapTo(sortedSetOf()) { (code[it] as NarrowLiteralInstruction).narrowLiteral }
}

/** Whether [this] moves an object out of [register] and nothing else. */
private fun Instruction.movesFrom(register: Int): Boolean =
    (opcode == Opcode.MOVE_OBJECT || opcode == Opcode.MOVE_OBJECT_FROM16 || opcode == Opcode.MOVE_OBJECT_16) &&
        (this as TwoRegisterInstruction).registerB == register

private fun ClassDef.isPublic() = AccessFlags.PUBLIC.isSet(accessFlags)

private fun Member.isPublicMember() = AccessFlags.PUBLIC.isSet(accessFlags)

/** Whether [this] declares the public field [field] itself. */
private fun ClassDef.declares(field: FieldReference, static: Boolean = false): Boolean =
    type == field.definingClass && fields.any {
        it.name == field.name && it.type == field.type && it.isPublicMember() && AccessFlags.STATIC.isSet(it.accessFlags) == static
    }

/** Whether [this] declares the public method [method] itself. */
private fun ClassDef.declares(method: MethodReference, static: Boolean = false): Boolean =
    type == method.definingClass && methods.any {
        it.matches(method) && it.isPublicMember() && AccessFlags.STATIC.isSet(it.accessFlags) == static
    }

private fun Method.matches(call: MethodReference): Boolean =
    definingClass == call.definingClass && name == call.name && returnType == call.returnType &&
        parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)

private fun Method.isStub(name: String, count: Int) =
    this.name == name && AccessFlags.STATIC.isSet(accessFlags) && returnType == OBJECT && parameterTypes.map(Any::toString) == List(count) { OBJECT }

private fun Method.reference(): MethodReference =
    ImmutableMethodReference(definingClass, name, parameterTypes.map(Any::toString), returnType)

private fun Field.reference(): FieldReference = ImmutableFieldReference(definingClass, name, type)

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
