/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.feed.tabswipe.stopTabSwipingPatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
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

/**
 * The report the feed's video player, IgFeedVideoPlayer, makes when a video's cover stays up while
 * it plays. Only its progress callback holds it.
 */
internal const val COVER_REPORT = "IgFeedVideoPlayer: Persistent Visible Cover Image"

internal const val SEEK_LISTENER = "Landroid/widget/SeekBar\$OnSeekBarChangeListener;"
private const val OBJECT = "Ljava/lang/Object;"
private const val VIEW = "Landroid/view/View;"

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
 * controller when a drag lets go (A0T). Nothing else of Instagram's changes.
 *
 * The main tabs' pager takes a sideways drag on the bar whatever the bar asks its parents, so the
 * patch brings [stopTabSwipingPatch]'s hooks, and a finger on the bar holds the tabs through them
 * until it lifts, that patch's switch on or off.
 */
@Suppress("unused")
val feedSeekBarPatch = bytecodePatch(
    name = "Seek bar on feed videos",
    description = "Shows a bar along the bottom of the video playing in your feed that you can drag to jump to any " +
        "point in it. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch, stopTabSwipingPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("feedSeekBar")
        hookFeedSeek(findFeedSeek())
        enableStatus("feedSeekBar")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The player and its progress callback, the reads the view stub makes and the seek the seek stub makes. */
internal class FeedSeekSites(
    val player: String,
    val progress: String,
    val item: FieldReference,
    val holder: FieldReference,
    val view: MethodReference,
    val controller: FieldReference,
    val seek: MethodReference,
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
 * - all of it public, so the extension's stubs may reach it, and the stubs themselves.
 */
internal fun BytecodePatchContext.findFeedSeek(): FeedSeekSites {
    val callbacks = mutableListOf<Method>()
    val releases = mutableListOf<Method>()
    classDefForEach { classDef ->
        classDef.methods.filterTo(callbacks) {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == "(IIZ)V" && it.holds(COVER_REPORT)
        }
        if (SEEK_LISTENER in classDef.interfaces) {
            classDef.methods.filterTo(releases) { it.name == "onStopTrackingTouch" && it.shape() == "(Landroid/widget/SeekBar;)V" }
        }
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
    val seeks = releases.flatMap { release ->
        release.code().filter { it.opcode in VIRTUAL_CALLS }.mapNotNull { it.method() }
            .filter { it.definingClass == controller.type && it.parameterTypes.map(Any::toString) == listOf("I", "Z") && it.returnType == "V" }
    }.distinctBy(Any::toString)
    val seek = seeks.singleOrNull()
        ?: refuse("expected one (int, boolean) call on ${controller.type} where a SeekBar drag lets go, found ${seeks.size}")

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
    return FeedSeekSites(player.type, callback.name, item, holder, view, controller, seek)
}

private val INTERFACE_CALLS = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/** The extension's stubs, by name and shape. */
private val STUBS = listOf(
    "view" to "($OBJECT)$VIEW",
    "seekTo" to "(${OBJECT}IZ)Z",
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
