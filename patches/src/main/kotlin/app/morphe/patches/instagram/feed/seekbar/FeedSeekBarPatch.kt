/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.IMAGE_INFO
import app.morphe.patches.instagram.download.PANDO_IMAGE_INFO
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.download.throughInterface
import app.morphe.patches.instagram.feed.tabswipe.stopTabSwipingPatch
import app.morphe.patches.instagram.feed.zoom.GESTURE_DETECTOR
import app.morphe.patches.instagram.feed.zoom.MEDIA_FRAME
import app.morphe.patches.instagram.feed.zoom.listenerOf
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Seek bar on feed videos"
internal const val FEED_SEEK_BAR = "$EXTENSION_PACKAGE/feed/FeedSeekBar;"
internal const val FEED_SEEK_READER = "$EXTENSION_PACKAGE/feed/FeedSeekReader;"
internal const val FEED_PROGRESS = "$FEED_SEEK_BAR->progress(Ljava/lang/Object;II)V"
internal const val FEED_DOUBLE_TAP_SKIP = "$EXTENSION_PACKAGE/feed/FeedDoubleTapSkip;"
internal const val FEED_DOUBLE_TAP_HOOK = "$FEED_DOUBLE_TAP_SKIP->doubleTap(Landroid/view/View;Landroid/view/MotionEvent;)Z"

/**
 * The report the feed's video player, IgFeedVideoPlayer, makes when a video's cover stays up while
 * it plays. Only its progress callback holds it.
 */
internal const val COVER_REPORT = "IgFeedVideoPlayer: Persistent Visible Cover Image"

internal const val SEEK_LISTENER = "Landroid/widget/SeekBar\$OnSeekBarChangeListener;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val VIEW = "Landroid/view/View;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val MAP = "Ljava/util/Map;"
internal const val FEED_MEDIA = "Lcom/instagram/feed/media/Media;"
internal const val SPRITESHEET_INFO = "Lcom/instagram/api/schemas/SpritesheetInfo;"

/** A post's picture sizes keep a reel's strip under these, read through the tree-backed class. */
internal const val SPRITE_CANDIDATES = "Lcom/instagram/api/schemas/SpriteSheetInfoCandidates;"
internal const val PANDO_SPRITE_CANDIDATES = "Lcom/instagram/api/schemas/ImmutablePandoSpriteSheetInfoCandidates;"

/** The name of the sprite images among the ones Instagram's JSON gives a video's picture strip, held by the method turning it into a map. */
internal const val SPRITE_URLS = "sprite_urls"

/** The reasons Instagram's feed scrubber pauses and plays its player with when a drag starts and lets go. */
internal const val SEEK_PAUSE_REASON = "seek"
internal const val SEEK_PLAY_REASON = "start"

/**
 * Puts a seek bar along the bottom of the video playing in a feed (#389). See FeedSeekBar in the
 * extension. In the default selection with its switch off.
 *
 * On 453.0.0.57.79's arm64 build 385914557 the feed's video player (05cP; 05oF and 05nU on the 32-bit
 * builds, so it's found by shape) hears where its video is through a progress callback (G3c), the one
 * (int position, int length, boolean) method holding [COVER_REPORT]. That callback reads the player's
 * current item (A01), the item's video holder (A0A, an interface) and the holder's view (CWW, the
 * video's cover image, which Instagram hides while the video plays but which shares the video's frame),
 * and asks the player's controller (A00, 03Nk) whether it's playing. The patch hands every callback to
 * the extension first thing, and fills the extension's stubs with those reads and the controller's
 * seek: the (int, boolean) call Instagram's own feed scrubber, a SeekBar listener, makes on that
 * controller when a drag lets go (A0T). The scrubber also pauses the player when a finger lands
 * (A0F, an instance (String) method, with [SEEK_PAUSE_REASON]) and plays it on right after the seek
 * (A03, a static (player, String, boolean, boolean, boolean) method, with [SEEK_PLAY_REASON], true,
 * false and false), and the extension's pause and play stubs make those same calls (#472): seeking
 * alone could leave the video stuck. Nothing else of Instagram's changes.
 *
 * The main tabs' pager takes a sideways drag on the bar whatever the bar asks its parents, so the
 * patch brings [stopTabSwipingPatch]'s hooks, and a finger on the bar holds the tabs through them
 * until it lifts, that patch's switch on or off.
 *
 * Double tap to skip on feed videos (#562) rides on the same player and stubs: the gesture listener
 * each feed post's media frame keeps (the one Long press to zoom finds) hands every double tap to
 * FeedDoubleTapSkip first, with the frame and the tap, and returns true on a yes, before Instagram
 * likes the post. See [findFeedDoubleTap].
 *
 * The seek bar shape also draws a story's progress segments and the seek bar of a video opened from
 * a chat (#630). See [findStoryBarShape] and [findChatVideoShape]; a build without either goes in
 * with that one left as Instagram draws it.
 */
@Suppress("unused")
val feedSeekBarPatch = bytecodePatch(
    name = "Seek bar on feed videos",
    description = "Shows a bar along the bottom of the video playing in your feed that you can drag to jump to any " +
        "point in it. Starts off. Turn it on in HushGram settings > Feed. Another switch there adds a small button " +
        "on the video that shows or hides the bar, and a choice under it makes the bar see-through or puts it on " +
        "frosted glass. Another puts the bar right on the video's bottom edge, where a scroll is less likely to catch " +
        "it. One more lets a double tap on the left or right side of the video go back or ahead 10 seconds, " +
        "while the middle still likes the post. Two more choose a shape for every seek bar, story progress segments " +
        "and videos in chats included, and show a preview above your finger while you drag one.",
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch, stopTabSwipingPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("feedSeekBar")
        val seek = findFeedSeek()
        val tap = findFeedDoubleTap()
        val sheet = findFeedSeekSheet(seek)
        val storyBar = findStoryBarShape()
        val chatVideo = findChatVideoShape()
        hookFeedSeek(seek)
        hookFeedSeekSheet(seek, sheet)
        hookFeedDoubleTap(tap)
        storyBar?.let { hookStoryBarShape(it) }
        chatVideo?.let { hookChatVideoShape(it) }
        enableStatus("feedSeekBar")
    }
}

/** The feed post gesture listener's double tap and the two reads the hook makes to reach the post's media frame (#562). */
internal class FeedDoubleTapSite(val tap: Method, val holderField: FieldReference, val frameField: FieldReference)

/**
 * The feed post's gesture listener is the one Long press to zoom finds: the listener kept by the one
 * class that makes a GestureDetector and also keeps a MediaFrameLayout and a Media. Its
 * onDoubleTap(MotionEvent) reads its holder and the holder's frame itself, once each, which is also
 * what proves the hook may read them. Nothing may jump back to its start, it needs two locals for
 * the hook to borrow before Instagram's code writes them, and it mustn't call the extension yet.
 */
internal fun BytecodePatchContext.findFeedDoubleTap(): FeedDoubleTapSite {
    val holders = classesCalling(GESTURE_DETECTOR, "<init>").filter { listenerOf(it) != null }
    val holder = holders.singleOrNull()
        ?: refuse("expected one class making a GestureDetector that keeps a media frame, a Media and a gesture listener, found ${holders.size}")
    val listener = listenerOf(holder)!!
    val taps = listener.methods.filter {
        it.name == "onDoubleTap" && !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null && it.shape() == "($MOTION_EVENT)Z"
    }
    val tap = taps.singleOrNull() ?: refuse("expected ${listener.type} to hold one onDoubleTap, found ${taps.size}")
    val where = "${listener.type}->${tap.name}"
    val code = tap.code()
    if (code.any { it.method()?.definingClass == FEED_DOUBLE_TAP_SKIP }) refuse("$where already calls $FEED_DOUBLE_TAP_SKIP")
    if (0 in tap.jumpTargets()) refuse("something in $where jumps back to its start")
    if (tap.localRegisterCount() < 2) refuse("$where has ${tap.localRegisterCount()} locals, and the hook borrows two")
    fun read(owner: String, type: String): FieldReference {
        val reads = code.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.field() }
            .filter { it.definingClass == owner && it.type == type }.distinctBy(Any::toString)
        return reads.singleOrNull() ?: refuse("expected $where to read one $type field of $owner, found ${reads.size}")
    }
    return FeedDoubleTapSite(tap, read(listener.type, holder.type), read(holder.type, MEDIA_FRAME))
}

/**
 * First thing in the double tap, in two locals nothing has written yet: read the holder and its frame,
 * ask [FEED_DOUBLE_TAP_HOOK] with the frame and the tap, and answer true on a yes. A no, or a listener
 * without its holder, goes on with Instagram's own double tap exactly as it was.
 */
internal fun BytecodePatchContext.hookFeedDoubleTap(site: FeedDoubleTapSite) {
    mutableClassDefBy(site.tap.definingClass).methods
        .single { it.name == site.tap.name && it.shape() == site.tap.shape() && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .apply {
            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, ${site.holderField}
                    if-eqz v0, :stock
                    iget-object v0, v0, ${site.frameField}
                    move-object/from16 v1, p1
                    invoke-static { v0, v1 }, $FEED_DOUBLE_TAP_HOOK
                    move-result v0
                    if-eqz v0, :stock
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("stock", getInstruction(0)),
            )
        }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The player and its progress callback, the reads the view stub makes, the seek the seek stub makes
 * and the player's pause and play the scrubber makes around it.
 */
internal class FeedSeekSites(
    val player: String,
    val progress: String,
    val item: FieldReference,
    val holder: FieldReference,
    val view: MethodReference,
    val controller: FieldReference,
    val seek: MethodReference,
    val pause: MethodReference,
    val play: MethodReference,
)

/**
 * Finds everything before anything changes:
 *
 * - the one instance (int, int, boolean) method holding [COVER_REPORT], the progress callback, in a
 *   public class, not yet calling the extension;
 * - the one no-argument View getter it calls on an interface, the holder's view, the one field of that
 *   interface's type it reads, the holder, and the one field of the player's own it reads whose type
 *   holds that holder, the current item;
 * - the one field of the player's own it reads whose type it then asks a no-argument boolean of, the
 *   controller;
 * - the one (int, boolean) method on the controller's type that any SeekBar listener's
 *   onStopTrackingTouch calls, the seek Instagram's feed scrubber makes;
 * - in the listeners making that seek, the one instance (String) method of the player's their
 *   onStartTrackingTouch calls, the pause, and the one static (player, String, boolean, boolean,
 *   boolean) method of the player's their onStopTrackingTouch calls, the play;
 * - all of it public, so the extension's stubs may reach it, and the stubs themselves.
 */
internal fun BytecodePatchContext.findFeedSeek(): FeedSeekSites {
    val callbacks = mutableListOf<Method>()
    val listeners = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        classDef.methods.filterTo(callbacks) {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == "(IIZ)V" && it.holds(COVER_REPORT)
        }
        if (SEEK_LISTENER in classDef.interfaces) listeners += classDef
    }
    val callback = callbacks.singleOrNull()
        ?: refuse("expected one instance (int, int, boolean) method holding $COVER_REPORT, found ${callbacks.size}")
    val player = classDefByOrNull(callback.definingClass) ?: refuse("this build has no ${callback.definingClass}")
    player.requirePublic()
    val code = callback.code()
    if (code.any { it.method()?.definingClass == FEED_SEEK_BAR }) refuse("${player.type}->${callback.name} already calls $FEED_SEEK_BAR")

    // 453.0.0.57.79's 385914510 keeps the holder in v17 and asks it with invoke-interface/range, so
    // every call here is taken in either form.
    val views = code.filter { it.opcode in INTERFACE_CALLS }.mapNotNull { it.method() }
        .filter { it.parameterTypes.isEmpty() && it.returnType == VIEW }.distinctBy(Any::toString)
    val view = views.singleOrNull() ?: refuse("expected ${player.type}->${callback.name} to ask one interface for a view, found ${views.size}")
    val reads = code.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.field() }.distinctBy(Any::toString)
    val holders = reads.filter { it.type == view.definingClass }
    val holder = holders.singleOrNull()
        ?: refuse("expected ${player.type}->${callback.name} to read one ${view.definingClass} field, found ${holders.size}")
    val items = reads.filter { it.definingClass == player.type && it.type == holder.definingClass }
    val item = items.singleOrNull()
        ?: refuse("expected ${player.type}->${callback.name} to read one ${holder.definingClass} field of the player's, found ${items.size}")

    val asked = code.filter { it.opcode in VIRTUAL_CALLS }.mapNotNull { it.method() }
        .filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }.map { it.definingClass }.toSet()
    val controllers = reads.filter { it.definingClass == player.type && it.type in asked }
    val controller = controllers.singleOrNull()
        ?: refuse("expected ${player.type}->${callback.name} to read one controller field of the player's, found ${controllers.size}")
    fun ClassDef.drag(name: String): List<Instruction> =
        methods.singleOrNull { it.name == name && it.shape() == "(Landroid/widget/SeekBar;)V" }?.code().orEmpty()
    fun List<Instruction>.seeksOn(): List<MethodReference> = filter { it.opcode in VIRTUAL_CALLS }.mapNotNull { it.method() }
        .filter { it.definingClass == controller.type && it.parameterTypes.map(Any::toString) == listOf("I", "Z") && it.returnType == "V" }
    val seeks = listeners.flatMap { it.drag("onStopTrackingTouch").seeksOn() }.distinctBy(Any::toString)
    val seek = seeks.singleOrNull()
        ?: refuse("expected one (int, boolean) call on ${controller.type} where a SeekBar drag lets go, found ${seeks.size}")
    val scrubbers = listeners.filter { listener -> listener.drag("onStopTrackingTouch").seeksOn().any { it.toString() == seek.toString() } }
    val pauses = scrubbers.flatMap { it.drag("onStartTrackingTouch") }.filter { it.opcode in VIRTUAL_CALLS }.mapNotNull { it.method() }
        .filter { it.definingClass == player.type && it.parameterTypes.map(Any::toString) == listOf(STRING) && it.returnType == "V" }
        .distinctBy(Any::toString)
    val pause = pauses.singleOrNull()
        ?: refuse("expected Instagram's feed scrubber to pause ${player.type} with one (String) call when a drag starts, found ${pauses.size}")
    val playShape = listOf(player.type, STRING, "Z", "Z", "Z")
    val plays = scrubbers.flatMap { it.drag("onStopTrackingTouch") }.filter { it.opcode in STATIC_CALLS }.mapNotNull { it.method() }
        .filter { it.definingClass == player.type && it.parameterTypes.map(Any::toString) == playShape && it.returnType == "V" }
        .distinctBy(Any::toString)
    val play = plays.singleOrNull()
        ?: refuse("expected Instagram's feed scrubber to play ${player.type} with one static call when a drag lets go, found ${plays.size}")
    player.methods.singleOrNull { it.name == pause.name && it.shape() == "($STRING)V" && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ?: refuse("${player.type}->${pause.name} isn't public")
    player.methods.singleOrNull {
        it.name == play.name && it.parameterTypes.map(Any::toString) == playShape && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("${player.type}->${play.name} isn't public")

    listOf(item, controller).forEach { player.requirePublicField(it) }
    (classDefByOrNull(holder.definingClass) ?: refuse("this build has no ${holder.definingClass}")).apply {
        requirePublic()
        requirePublicField(holder)
    }
    (classDefByOrNull(view.definingClass) ?: refuse("this build has no ${view.definingClass}")).requirePublic()
    (classDefByOrNull(controller.type) ?: refuse("this build has no ${controller.type}")).apply {
        requirePublic()
        methods.singleOrNull { it.name == seek.name && it.shape() == "(IZ)V" && AccessFlags.PUBLIC.isSet(it.accessFlags) }
            ?: refuse("${seek.definingClass}->${seek.name} isn't public")
    }

    val reader = classDefByOrNull(FEED_SEEK_READER) ?: refuse("the extension has no $FEED_SEEK_READER")
    STUBS.forEach { (name, shape) ->
        reader.methods.singleOrNull { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == shape }
            ?: refuse("$FEED_SEEK_READER has no static $name$shape")
    }
    return FeedSeekSites(player.type, callback.name, item, holder, view, controller, seek, pause, play)
}

/**
 * Where a feed video's post is kept, and how its picture strip is got and turned into a map.
 * [scrubber] is the chain of calls reading the strip kept with the post's picture sizes, or null
 * when this build's can't be told.
 */
internal class SeekSheetSites(val media: FieldReference, val getter: Method, val builder: Method, val scrubber: List<String>?)

/**
 * Finds what the preview needs to read a video's picture strip (#567), before anything changes:
 *
 * - the one Object field the progress callback reads and at once casts to a Media, the item's post;
 * - Media's one no-argument getter of the [SPRITESHEET_INFO];
 * - the one public static method holding [SPRITE_URLS] that turns a [SPRITESHEET_INFO] into a map under the names in
 *   the JSON Instagram sends, the way the app writes it out to its own cache;
 * - all of it public, so the extension's stubs may reach it, and the stubs themselves.
 *
 * Reels leave that getter's strip null and keep theirs with the post's picture sizes instead, under
 * `image_versions2.scrubber_spritesheet_info_candidates.default` (#567). Only the preview reads it, so
 * a build where that chain can't be told logs why and goes in with the post's own strip alone.
 */
internal fun BytecodePatchContext.findFeedSeekSheet(found: FeedSeekSites): SeekSheetSites {
    val player = classDefByOrNull(found.player) ?: refuse("this build has no ${found.player}")
    val code = player.methods.single { it.name == found.progress && it.shape() == "(IIZ)V" && !AccessFlags.STATIC.isSet(it.accessFlags) }.code()
    val posts = code.withIndex().filter { (at, instruction) ->
        instruction.opcode == Opcode.IGET_OBJECT && instruction.field()?.type == OBJECT &&
            code.getOrNull(at + 1)?.let { it.opcode == Opcode.CHECK_CAST && (it as ReferenceInstruction).reference.toString() == FEED_MEDIA } == true
    }.mapNotNull { it.value.field() }.distinctBy(Any::toString)
    val media = posts.singleOrNull() ?: refuse("expected ${found.player}->${found.progress} to read one Object field it casts to a Media, found ${posts.size}")
    (classDefByOrNull(media.definingClass) ?: refuse("this build has no ${media.definingClass}")).apply {
        requirePublic()
        requirePublicField(media)
    }

    val mediaClass = classDefByOrNull(FEED_MEDIA) ?: refuse("this build has no $FEED_MEDIA")
    mediaClass.requirePublic()
    val getters = mediaClass.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == "()$SPRITESHEET_INFO" }
    val getter = getters.singleOrNull() ?: refuse("expected $FEED_MEDIA to have one getter of its $SPRITESHEET_INFO, found ${getters.size}")
    if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("$FEED_MEDIA->${getter.name} isn't public")

    val builders = classesLoadingString(SPRITE_URLS).filter { AccessFlags.PUBLIC.isSet(it.accessFlags) }.flatMap { it.methods }.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.shape() == "($SPRITESHEET_INFO)$MAP" && loadsString(it, SPRITE_URLS)
    }
    val builder = builders.singleOrNull() ?: refuse("expected one public static method turning a $SPRITESHEET_INFO into a map, found ${builders.size}")

    val reader = classDefByOrNull(FEED_SEEK_READER) ?: refuse("the extension has no $FEED_SEEK_READER")
    SHEET_STUBS.forEach { (name, shape) ->
        reader.methods.singleOrNull { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == shape }
            ?: refuse("$FEED_SEEK_READER has no static $name$shape")
    }
    return SeekSheetSites(media, getter, builder, findScrubberSheet())
}

/** The calls from a post to the strip kept with its picture sizes, each on the one before's answer, or null after the log says why. */
private fun BytecodePatchContext.findScrubberSheet(): List<String>? = try {
    listOf(IMAGE_INFO, PANDO_IMAGE_INFO, SPRITE_CANDIDATES, PANDO_SPRITE_CANDIDATES).firstOrNull { classDefByOrNull(it) == null }
        ?.let { throw PatchException("$PATCH: this build has no $it") }
    val images = pandoGetter(PATCH, FEED_MEDIA, "image_versions2", IMAGE_INFO)
    if (!AccessFlags.PUBLIC.isSet(images.accessFlags)) throw PatchException("$PATCH: $FEED_MEDIA->${images.name} isn't public")
    listOf(
        "invoke-virtual {p0}, $FEED_MEDIA->${images.name}()$IMAGE_INFO",
        throughInterface(PATCH, IMAGE_INFO, PANDO_IMAGE_INFO, "scrubber_spritesheet_info_candidates", SPRITE_CANDIDATES),
        throughInterface(PATCH, SPRITE_CANDIDATES, PANDO_SPRITE_CANDIDATES, "default", SPRITESHEET_INFO),
    )
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. $PATCH goes in reading a video's pictures from the post alone.")
    null
}

/** The extension's stubs the picture strip takes, by name and shape. */
private val SHEET_STUBS = listOf("media" to "($OBJECT)$OBJECT", "sheet" to "($OBJECT)$MAP", "scrubberSheet" to "($OBJECT)$MAP")

/** Fills the stubs that read a video's picture strip. Each stub's own registers are its parameters, so the code uses those. */
internal fun BytecodePatchContext.hookFeedSeekSheet(found: FeedSeekSites, sites: SeekSheetSites) {
    val reader = mutableClassDefBy(FEED_SEEK_READER)
    fun stub(name: String): MutableMethod = reader.methods.single { it.name == name && it.shape() == SHEET_STUBS.toMap().getValue(name) }

    stub("media").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.player}
            iget-object p0, p0, ${found.item}
            if-eqz p0, :none
            check-cast p0, ${sites.media.definingClass}
            iget-object p0, p0, ${sites.media}
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
    stub("sheet").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $FEED_MEDIA
            invoke-virtual { p0 }, $FEED_MEDIA->${sites.getter.name}()$SPRITESHEET_INFO
            move-result-object p0
            if-eqz p0, :none
            invoke-static { p0 }, ${sites.builder.definingClass}->${sites.builder.name}($SPRITESHEET_INFO)$MAP
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
    val scrubber = sites.scrubber ?: return
    stub("scrubberSheet").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $FEED_MEDIA
            ${scrubber.joinToString("\n") { "$it\nmove-result-object p0\nif-eqz p0, :none" }}
            invoke-static { p0 }, ${sites.builder.definingClass}->${sites.builder.name}($SPRITESHEET_INFO)$MAP
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
}

private val INTERFACE_CALLS = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
private val STATIC_CALLS = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

/** The extension's stubs, by name and shape. */
private val STUBS = listOf(
    "view" to "($OBJECT)$VIEW",
    "seekTo" to "(${OBJECT}IZ)Z",
    "pause" to "($OBJECT$OBJECT)$OBJECT",
    "play" to "($OBJECT$OBJECT$OBJECT$OBJECT)$OBJECT",
)

/** Fills the stubs and hands the progress callback to the extension first thing. */
internal fun BytecodePatchContext.hookFeedSeek(found: FeedSeekSites) {
    val reader = mutableClassDefBy(FEED_SEEK_READER)
    fun stub(name: String): MutableMethod = reader.methods.single { it.name == name && it.shape() == STUBS.toMap().getValue(name) }

    // Each stub's own registers are its parameters, so the code below uses those.
    stub("view").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.player}
            iget-object p0, p0, ${found.item}
            if-eqz p0, :none
            iget-object p0, p0, ${found.holder}
            if-eqz p0, :none
            invoke-interface { p0 }, ${found.view}
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
    stub("seekTo").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.player}
            iget-object p0, p0, ${found.controller}
            if-eqz p0, :none
            invoke-virtual { p0, p1, p2 }, ${found.seek}
            const/4 p0, 0x1
            return p0
            :none
            const/4 p0, 0x0
            return p0
        """,
    )
    stub("pause").addInstructions(
        0,
        """
            check-cast p0, ${found.player}
            const-string p1, "$SEEK_PAUSE_REASON"
            invoke-virtual { p0, p1 }, ${found.pause}
            return-object p0
        """,
    )
    stub("play").addInstructions(
        0,
        """
            check-cast p0, ${found.player}
            const-string p1, "$SEEK_PLAY_REASON"
            const/4 p2, 0x1
            const/4 p3, 0x0
            invoke-static { p0, p1, p2, p3, p3 }, ${found.play}
            return-object p0
        """,
    )

    mutableClassDefBy(found.player).methods
        .single { it.name == found.progress && it.shape() == "(IIZ)V" && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .addInstructions(0, "invoke-static/range { p0 .. p2 }, $FEED_PROGRESS")
}

private fun ClassDef.requirePublic() {
    if (!AccessFlags.PUBLIC.isSet(accessFlags)) refuse("$type isn't public")
}

private fun ClassDef.requirePublicField(field: FieldReference) {
    val declared = fields.singleOrNull { it.name == field.name && it.type == field.type && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("$type has no field ${field.name}")
    if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("$type->${field.name} isn't public")
}

private fun Method.shape(): String = "(${parameterTypes.joinToString("")})$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Method.holds(string: String): Boolean = implementation?.instructions?.any { instruction ->
    (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((instruction as ReferenceInstruction).reference as StringReference).string == string
} ?: false
