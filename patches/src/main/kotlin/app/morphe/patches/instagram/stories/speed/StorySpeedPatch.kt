/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.speed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.ads.adDetailsGetter
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.media.taptoplay.REEL_VIEWER_FRAGMENT
import app.morphe.patches.instagram.media.taptoplay.VIDEO_PLAYER_FIELD
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.mentions.REEL_VIEW_GROUP
import app.morphe.patches.instagram.stories.mentions.StoryPages
import app.morphe.patches.instagram.stories.mentions.findStoryPages
import app.morphe.patches.instagram.stories.time.STORY_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Speed button on stories"
internal const val STORY_SPEED = "$EXTENSION_PACKAGE/stories/StorySpeed;"
internal const val STORY_SPEED_READER = "$EXTENSION_PACKAGE/stories/StorySpeedReader;"
internal const val SPEED_PLAYING = "$STORY_SPEED->playing(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val SPEED_BOUND = "$STORY_SPEED->bound(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The slowest and fastest speeds Instagram's story players take, as float bits (0.25 and 4.0). */
internal const val SLOWEST = 0x3e800000
internal const val FASTEST = 0x40800000

private const val OBJECT = "Ljava/lang/Object;"
private const val VIEW = "Landroid/view/View;"

/**
 * Puts a speed button in a video story's header (#469). See StorySpeed in the extension. In the
 * default selection with its switch off.
 *
 * On 453 the story viewer, ReelViewerFragment, plays a story's video through the player in its public
 * [VIDEO_PLAYER_FIELD] field, an interface (0NeG) with one speed setter (HMO), which both players
 * implementing it take only between a quarter and four times. The viewer hears each story's video
 * start in its own callback (GZ0, taking the story and the page it plays in), which ends with
 * Instagram's own speed call for the few stories it plays at another speed. Right before each of that
 * callback's returns, the patch hands the viewer, the story and the page to the extension, so the kept
 * speed goes on after Instagram's. Each of the viewer's binders tells the extension when it puts a story
 * into a page, so a button left from a video hides on a photo. The stubs set the speed through the
 * player, tell a page from other holders, read a page's media view and read whether a story is an ad.
 */
@Suppress("unused")
val storySpeedPatch = bytecodePatch(
    name = "Speed button on stories",
    description = "Puts a speed button at the top of a video story. Tap it to play the story at 1.5x or 2x, and " +
        "later video stories keep that speed. Photos and ads play as usual. Starts off. Turn it on in HushGram " +
        "settings > Stories.",
) {
    // Manager's Stories group is full at 16, and this is about how a video plays, so it sits with
    // Keep the reel speed under Playback. Its switch is still on the Stories settings page.
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storySpeed")
        hookStorySpeed(findStorySpeed())
        enableStatus("storySpeed")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * What the patch found: the viewer, its player interface and that interface's speed setter, the
 * callback for a story's video starting, the viewer's binders and page, and Media's getter of its ad
 * details with what it answers.
 */
internal class StorySpeedSites(
    val viewer: String,
    val playerInterface: String,
    val setter: String,
    val playing: String,
    val playingParameters: List<String>,
    val pages: StoryPages,
    val adDetails: String,
    val adDetailsType: String,
)

/**
 * Finds everything before anything changes:
 *
 * - the viewer's public [VIDEO_PLAYER_FIELD] field, of a public interface type with one (float)
 *   method, the speed setter, which every class implementing the interface either bounds by [SLOWEST]
 *   and [FASTEST] or hands on to another player, and at least one bounds;
 * - the viewer's one instance method taking a story first and an object second that calls that setter,
 *   the callback for a story's video starting, whose `this`, story and page are still in their own
 *   registers at each of its returns;
 * - the viewer's binders and page through [findStoryPages], and Media's ad details getter;
 * - the extension's stubs, by name and shape.
 */
internal fun BytecodePatchContext.findStorySpeed(): StorySpeedSites {
    val viewer = classDefByOrNull(REEL_VIEWER_FRAGMENT) ?: refuse("this build has no $REEL_VIEWER_FRAGMENT")
    if (!AccessFlags.PUBLIC.isSet(viewer.accessFlags)) refuse("$REEL_VIEWER_FRAGMENT isn't public")
    if (viewer.methods.any { method -> method.code().any { it.method()?.definingClass == STORY_SPEED } }) {
        refuse("$REEL_VIEWER_FRAGMENT already calls $STORY_SPEED")
    }
    val field = viewer.fields.singleOrNull { it.name == VIDEO_PLAYER_FIELD && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("$REEL_VIEWER_FRAGMENT has no $VIDEO_PLAYER_FIELD field")
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags)) refuse("$REEL_VIEWER_FRAGMENT->$VIDEO_PLAYER_FIELD isn't public")
    val playerInterface = classDefByOrNull(field.type)
        ?.takeIf { AccessFlags.INTERFACE.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ?: refuse("$REEL_VIEWER_FRAGMENT->$VIDEO_PLAYER_FIELD isn't a public interface")
    val where = playerInterface.type
    val setters = playerInterface.methods.filter {
        it.parameters() == listOf("F") && it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val setter = setters.singleOrNull()?.name ?: refuse("expected one (float) method on $where, its speed setter, found ${setters.size}")
    val setterReference = "$where->$setter(F)V"

    val players = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (where in classDef.interfaces && !AccessFlags.ABSTRACT.isSet(classDef.accessFlags)) players += classDef
    }
    if (players.isEmpty()) refuse("nothing implements $where")
    var bounded = 0
    for (player in players) {
        val own = player.methods.singleOrNull {
            it.name == setter && it.parameters() == listOf("F") && it.returnType == "V" &&
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
        } ?: refuse("${player.type} doesn't implement $where->$setter")
        val code = own.code()
        when {
            code.any { it.loads(SLOWEST) } && code.any { it.loads(FASTEST) } -> bounded++
            code.any { it.method()?.toString() == setterReference } -> {}
            else -> refuse("${player.type}->$setter neither bounds the speed to a quarter and four times nor hands it to another player, so it isn't a speed setter")
        }
    }
    if (bounded == 0) refuse("no player bounds $where->$setter to a quarter and four times")

    val callbacks = viewer.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameters().size >= 2 &&
            method.parameters()[0] == STORY_ITEM && method.parameters()[1].startsWith("L") &&
            method.code().any { it.opcode == Opcode.INVOKE_INTERFACE && it.method()?.toString() == setterReference }
    }
    val callback = callbacks.singleOrNull()
        ?: refuse("expected one method on $REEL_VIEWER_FRAGMENT taking a story and its page that sets the speed, found ${callbacks.size}")
    val returns = callback.returns()
    if (returns.isEmpty()) refuse("$REEL_VIEWER_FRAGMENT->${callback.name} never returns")
    callback.requireThisIntact(PATCH, returns)
    callback.requireParameterIntact(PATCH, 0, returns)
    callback.requireParameterIntact(PATCH, 1, returns)

    val pages = findStoryPages(PATCH)
    val adDetails = adDetailsGetter(PATCH)
    val closed = listOfNotNull(
        STORY_ITEM.takeUnless { AccessFlags.PUBLIC.isSet(classDefBy(STORY_ITEM).accessFlags) },
        MEDIA.takeUnless { AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) },
    )
    if (closed.isNotEmpty()) refuse("${closed.joinToString()} isn't public, so the extension can't reach it")

    val reader = classDefByOrNull(STORY_SPEED_READER) ?: refuse("the extension has no $STORY_SPEED_READER")
    STUBS.forEach { (name, shape) ->
        reader.methods.singleOrNull { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == shape }
            ?: refuse("$STORY_SPEED_READER has no static $name$shape")
    }
    return StorySpeedSites(
        viewer.type, where, setter, callback.name, callback.parameters(), pages, adDetails.name, adDetails.returnType,
    )
}

/** The extension's stubs, by name and shape. */
private val STUBS = listOf(
    "setSpeed" to "(${OBJECT}F)Z",
    "isPage" to "($OBJECT)Z",
    "pageView" to "($OBJECT)$VIEW",
    "ad" to "($OBJECT)Z",
)

/**
 * Fills the stubs, puts the call to [SPEED_PLAYING] in front of each of the callback's returns, on
 * the label any branch to it carries, and the call to [SPEED_BOUND] right after each binder's put.
 */
internal fun BytecodePatchContext.hookStorySpeed(found: StorySpeedSites) {
    val viewer = found.viewer
    val player = found.playerInterface
    val page = found.pages.page
    val reader = mutableClassDefBy(STORY_SPEED_READER)
    fun stub(name: String): MutableMethod = reader.methods.single { it.name == name && it.shape() == STUBS.toMap().getValue(name) }

    // Each stub's own registers are its parameters, so the code below uses those.
    stub("setSpeed").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $viewer
            iget-object p0, p0, $viewer->$VIDEO_PLAYER_FIELD:$player
            if-nez p0, :player
            const/4 p0, 0x0
            return p0
            :player
            invoke-interface { p0, p1 }, $player->${found.setter}(F)V
            const/4 p0, 0x1
            return p0
        """,
    )
    stub("isPage").addInstructions(
        0,
        """
            instance-of p0, p0, $page
            return p0
        """,
    )
    stub("pageView").addInstructions(
        0,
        """
            check-cast p0, $page
            iget-object p0, p0, $page->${found.pages.view}:$REEL_VIEW_GROUP
            return-object p0
        """,
    )
    stub("ad").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $STORY_ITEM
            iget-object p0, p0, $STORY_ITEM->${found.pages.media}:$MEDIA
            if-eqz p0, :none
            invoke-virtual { p0 }, $MEDIA->${found.adDetails}()${found.adDetailsType}
            move-result-object p0
            if-eqz p0, :none
            const/4 p0, 0x1
            return p0
            :none
            const/4 p0, 0x0
            return p0
        """,
    )

    val callback = mutableClassDefBy(viewer).methods.single {
        it.name == found.playing && it.parameters() == found.playingParameters && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    // From the last return up, so each index found before the first insert still points at its return.
    // The story and the page are the first two parameters, after this, so p0 to p2 are the three.
    for (at in callback.returns().sortedDescending()) {
        callback.addInstructionsAtControlFlowLabel(at, "invoke-static/range { p0 .. p2 }, $SPEED_PLAYING")
    }

    for (bind in found.pages.binds) {
        val method = mutableClassDefBy(bind.type).methods.single {
            it.name == bind.name && it.parameters() == bind.parameters
        }
        // Found again: See who a story mentions may have put its own call after the same put by now.
        val code = method.code()
        val puts = code.indices.filter { index ->
            code[index].opcode == Opcode.IPUT_OBJECT && code[index].field()?.let { it.type == STORY_ITEM && it.definingClass == page } == true
        }
        val put = puts.singleOrNull() ?: refuse("expected ${bind.type}->${bind.name} to put one story into its page, found ${puts.size}")
        method.addInstructions(put + 1, "invoke-static { v${bind.page}, v${bind.item} }, $SPEED_BOUND")
    }
}

private fun Method.parameters(): List<String> = parameterTypes.map(Any::toString)

private fun Method.shape(): String = "(${parameters().joinToString("")})$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.returns(): List<Int> = code().let { code -> code.indices.filter { code[it].opcode == Opcode.RETURN_VOID } }

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.loads(value: Int): Boolean =
    (opcode == Opcode.CONST || opcode == Opcode.CONST_HIGH16 || opcode == Opcode.CONST_16 || opcode == Opcode.CONST_4) &&
        (this as NarrowLiteralInstruction).narrowLiteral == value
