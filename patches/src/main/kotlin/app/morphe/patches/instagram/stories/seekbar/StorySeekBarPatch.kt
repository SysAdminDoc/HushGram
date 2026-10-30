/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.media.taptoplay.PAUSE_STORY
import app.morphe.patches.instagram.media.taptoplay.REEL_VIEWER_FRAGMENT
import app.morphe.patches.instagram.media.taptoplay.RESUME_STORY
import app.morphe.patches.instagram.media.taptoplay.VIDEO_PLAYER_FIELD
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Seek bar on stories"
internal const val STORY_SEEK_BAR = "$EXTENSION_PACKAGE/stories/StorySeekBar;"
internal const val STORY_SEEK_READER = "$EXTENSION_PACKAGE/stories/StorySeekReader;"
internal const val SEEK_RESUMED = "$STORY_SEEK_BAR->resumed(Ljava/lang/Object;)V"
internal const val SEEK_PAUSED = "$STORY_SEEK_BAR->paused(Ljava/lang/Object;)V"

/** The string the viewer's own resume passes, which picks it out beside the resume holding [RESUME_STORY]. */
internal const val RESUME = "resume"

/**
 * The name Instagram's own story scrubber gives its controller, held by the methods that drag a
 * story's video. They clamp the new position to the video's length, the player's one int getter
 * they read beside the position.
 */
internal const val SCRUBBER = "reelScrubberController"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val VIEW = "Landroid/view/View;"

/**
 * Puts a seek bar over the bottom of a video story (#355). See StorySeekBar in the extension. In the
 * default selection with its switch off.
 *
 * On 452 the story viewer, ReelViewerFragment, plays a story's video through the player in its
 * public [VIDEO_PLAYER_FIELD] field, an interface (0NhG) that answers the video's position and length
 * and seeks it, the calls Instagram's own tap to skip on a long video makes. The patch fills the
 * extension's stubs with those calls and the viewer's own pause (the method holding [PAUSE_STORY],
 * which pauses the video and the story's timer) and resume (the one handing [RESUME] to the method
 * holding [RESUME_STORY] and calling nothing else), and right after the viewer's onResume and onPause hand on to its
 * superclass, tells the extension the viewer came and went. Nothing else of Instagram's changes.
 */
@Suppress("unused")
val storySeekBarPatch = bytecodePatch(
    name = "Seek bar on stories",
    description = "Shows a bar near the bottom of a video story that you can drag to jump to any point in the " +
        "video. Photos don't get one. Starts off. Turn it on in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storySeekBar")
        hookStorySeek(findStorySeek())
        enableStatus("storySeekBar")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * What the stubs call and where the viewer tells the extension: the player interface and its
 * method names, the viewer's static pause and its resume's name, and the viewer's onResume and
 * onPause, each of which hands on to its superclass once with `this`.
 */
internal class StorySeekSites(
    val viewer: String,
    val playerInterface: String,
    val position: String,
    val duration: String,
    val seekTo: String,
    val pause: String,
    val resume: String,
)

/**
 * Finds everything before anything changes:
 *
 * - the viewer's public [VIDEO_PLAYER_FIELD] field, of a public interface type, and the classes
 *   implementing that interface;
 * - its two (int) methods, the seek by, whose every implementation adds its argument to the
 *   position, and the seek to, which none does;
 * - its position, the no-argument int method whose every implementation makes a no-argument int
 *   call the same class's seek by makes, and its length, the one other int getter the methods
 *   holding [SCRUBBER] read (Bvx, which loads -1, answers only for a live story);
 * - the viewer's public static (viewer, String) method holding [PAUSE_STORY], and its public
 *   instance no-argument method loading [RESUME] whose only call is the static (viewer, String, boolean)
 *   one holding [RESUME_STORY] (on 452 F8S; Grw makes the same call and then posts more of its own);
 * - the viewer's own onResume and onPause, each with one call on to its superclass's, on `this`,
 *   whose next instruction nothing jumps to and where `this` is still the viewer.
 */
internal fun BytecodePatchContext.findStorySeek(): StorySeekSites {
    val viewer = classDefByOrNull(REEL_VIEWER_FRAGMENT) ?: refuse("this build has no $REEL_VIEWER_FRAGMENT")
    if (!AccessFlags.PUBLIC.isSet(viewer.accessFlags)) refuse("$REEL_VIEWER_FRAGMENT isn't public")
    val field = viewer.fields.singleOrNull { it.name == VIDEO_PLAYER_FIELD && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("$REEL_VIEWER_FRAGMENT has no $VIDEO_PLAYER_FIELD field")
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags)) refuse("$REEL_VIEWER_FRAGMENT->$VIDEO_PLAYER_FIELD isn't public")
    val playerInterface = classDefByOrNull(field.type)
        ?.takeIf { AccessFlags.INTERFACE.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ?: refuse("$REEL_VIEWER_FRAGMENT->$VIDEO_PLAYER_FIELD isn't a public interface")
    val players = mutableListOf<ClassDef>()
    val scrubbers = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (playerInterface.type in classDef.interfaces && !AccessFlags.ABSTRACT.isSet(classDef.accessFlags)) players += classDef
        classDef.methods.filterTo(scrubbers) { it.holds(SCRUBBER) }
    }
    if (players.isEmpty()) refuse("nothing implements ${playerInterface.type}")
    val where = playerInterface.type

    fun ClassDef.implementation(name: String, parameters: List<String>, returns: String): Method =
        methods.singleOrNull {
            it.name == name && it.returnType == returns && it.parameters() == parameters &&
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
        } ?: refuse("$type doesn't implement $where->$name")
    fun shaped(parameters: List<String>, returns: String) = playerInterface.methods
        .filter { it.parameters() == parameters && it.returnType == returns && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .map { it.name }

    val seeks = shaped(listOf("I"), "V")
    if (seeks.size != 2) refuse("expected two (int) methods on $where, a seek by and a seek to, found ${seeks.size}")
    fun adds(name: String) = players.map { player -> player.implementation(name, listOf("I"), "V").code().any(Instruction::adds) }
    val seekBy = seeks.singleOrNull { name -> adds(name).all { it } }
        ?: refuse("expected one (int) method on $where adding its argument in every player, found ${seeks.count { name -> adds(name).all { it } }}")
    val seekTo = seeks.single { it != seekBy }
    if (adds(seekTo).any { it }) refuse("$where->$seekTo adds to the position in some player, so it isn't a seek to")

    val readers = shaped(emptyList(), "I")
    val positionReads = players.associateWith { player ->
        player.implementation(seekBy, listOf("I"), "V").code().mapNotNull { it.method() }
            .filter { it.parameterTypes.isEmpty() && it.returnType == "I" }.map(Any::toString).toSet()
    }
    val positions = readers.filter { name ->
        players.all { player ->
            val reads = positionReads.getValue(player)
            player.implementation(name, emptyList(), "I").code().any { it.method()?.toString() in reads }
        }
    }
    val position = positions.singleOrNull()
        ?: refuse("expected one int getter on $where reading what $seekBy reads in every player, found ${positions.size}")
    if (scrubbers.isEmpty()) refuse("no method holds $SCRUBBER")
    val durations = scrubbers.flatMap { method ->
        method.code().filter { it.opcode == Opcode.INVOKE_INTERFACE }.mapNotNull { it.method() }
            .filter { it.definingClass == where && it.parameterTypes.isEmpty() && it.returnType == "I" && it.name in readers }
            .map { it.name }
    }.toSet() - position
    val duration = durations.singleOrNull()
        ?: refuse("expected one int getter on $where besides $position read where $SCRUBBER is, found ${durations.sorted()}")

    val pauses = viewer.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameters() == listOf(viewer.type, STRING) &&
            loadsString(it, PAUSE_STORY)
    }
    val pause = pauses.singleOrNull() ?: refuse("expected one static pause on $REEL_VIEWER_FRAGMENT holding $PAUSE_STORY, found ${pauses.size}")
    if (!AccessFlags.PUBLIC.isSet(pause.accessFlags)) refuse("$REEL_VIEWER_FRAGMENT->${pause.name} isn't public")
    val resumers = viewer.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameters() == listOf(viewer.type, STRING, "Z") &&
            RESUME_STORY.all { value -> loadsString(it, value) }
    }
    val resumer = resumers.singleOrNull() ?: refuse("expected one static resume on $REEL_VIEWER_FRAGMENT holding $RESUME_STORY, found ${resumers.size}")
    val resumes = viewer.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags) &&
            method.returnType == "V" && method.parameterTypes.isEmpty() && loadsString(method, RESUME) &&
            // Some 453 builds ask Redex's string pool for it, a static (int)String call.
            method.code().mapNotNull { it.method() }.filterNot { it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == STRING }
                .map(Any::toString) == listOf(resumer.reference())
    }
    val resume = resumes.singleOrNull()
        ?: refuse("expected one public no-argument method on $REEL_VIEWER_FRAGMENT handing $RESUME to ${resumer.name} and calling nothing else, found ${resumes.size}")

    listOf("onResume", "onPause").forEach { viewer.lifecycleSite(it) }
    val reader = classDefByOrNull(STORY_SEEK_READER) ?: refuse("the extension has no $STORY_SEEK_READER")
    STUBS.forEach { (name, shape) ->
        reader.methods.singleOrNull { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == shape }
            ?: refuse("$STORY_SEEK_READER has no static $name$shape")
    }
    return StorySeekSites(viewer.type, playerInterface.type, position, duration, seekTo, pause.reference(), resume.name)
}

/** The extension's stubs, by name and shape. */
private val STUBS = listOf(
    "view" to "($OBJECT)$VIEW",
    "position" to "($OBJECT)I",
    "duration" to "($OBJECT)I",
    "seekTo" to "(${OBJECT}I)Z",
    "pause" to "($OBJECT$STRING)Z",
    "resume" to "($OBJECT)Z",
)

/**
 * The index just past the viewer's one call on to its superclass's [name], whose first register is
 * `this`, refused when it already tells the extension, when something jumps there or when `this`
 * may hold something else by then.
 */
private fun ClassDef.lifecycleSite(name: String): Int {
    val method = methods.singleOrNull {
        it.name == name && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: refuse("$REEL_VIEWER_FRAGMENT has no $name() of its own")
    val code = method.code()
    if (code.any { it.method()?.definingClass == STORY_SEEK_BAR }) refuse("$REEL_VIEWER_FRAGMENT->$name already calls $STORY_SEEK_BAR")
    val self = method.localRegisterCount()
    val supers = code.indices.filter { at ->
        val called = code[at].method()
        (code[at].opcode == Opcode.INVOKE_SUPER || code[at].opcode == Opcode.INVOKE_SUPER_RANGE) && called != null &&
            called.name == name && called.parameterTypes.isEmpty() && called.returnType == "V"
    }
    val call = supers.singleOrNull() ?: refuse("expected $REEL_VIEWER_FRAGMENT->$name to call its superclass's once, found ${supers.size}")
    val first = (code[call] as? FiveRegisterInstruction)?.registerC
    if (first != self) refuse("$REEL_VIEWER_FRAGMENT->$name calls its superclass's on something other than this")
    val at = call + 1
    if (at >= code.size) refuse("$REEL_VIEWER_FRAGMENT->$name ends at its call to its superclass's")
    if (at in method.jumpTargets()) refuse("something in $REEL_VIEWER_FRAGMENT->$name jumps to just after its call to its superclass's")
    method.requireThisIntact(PATCH, listOf(at))
    return at
}

/** Fills the stubs and puts the two calls in, finding each lifecycle site again on the class as it is now. */
internal fun BytecodePatchContext.hookStorySeek(found: StorySeekSites) {
    val viewer = found.viewer
    val field = "$viewer->$VIDEO_PLAYER_FIELD:${found.playerInterface}"
    val player = found.playerInterface
    val reader = mutableClassDefBy(STORY_SEEK_READER)
    fun stub(name: String): MutableMethod = reader.methods.single { it.name == name && it.shape() == STUBS.toMap().getValue(name) }

    // Each stub's own registers are its parameters, so the code below uses those.
    stub("view").addInstructions(
        0,
        """
            check-cast p0, $viewer
            invoke-virtual { p0 }, $viewer->getView()$VIEW
            move-result-object p0
            return-object p0
        """,
    )
    listOf("position" to found.position, "duration" to found.duration).forEach { (name, getter) ->
        stub(name).addInstructionsWithLabels(
            0,
            """
                check-cast p0, $viewer
                iget-object p0, p0, $field
                if-eqz p0, :none
                invoke-interface { p0 }, $player->$getter()I
                move-result p0
                return p0
                :none
                const/4 p0, -0x1
                return p0
            """,
        )
    }
    stub("seekTo").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $viewer
            iget-object p0, p0, $field
            if-eqz p0, :none
            invoke-interface { p0, p1 }, $player->${found.seekTo}(I)V
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
            check-cast p0, $viewer
            invoke-static { p0, p1 }, ${found.pause}
            const/4 p0, 0x1
            return p0
        """,
    )
    stub("resume").addInstructions(
        0,
        """
            check-cast p0, $viewer
            invoke-virtual { p0 }, $viewer->${found.resume}()V
            const/4 p0, 0x1
            return p0
        """,
    )

    val target = mutableClassDefBy(viewer)
    listOf("onResume" to SEEK_RESUMED, "onPause" to SEEK_PAUSED).forEach { (name, hook) ->
        val at = target.lifecycleSite(name)
        target.methods.single { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
            .addInstructions(at, "invoke-static { p0 }, $hook")
    }
}

private fun Method.parameters(): List<String> = parameterTypes.map(Any::toString)

private fun Method.shape(): String = "(${parameters().joinToString("")})$returnType"

private fun Method.reference(): String = "$definingClass->$name${shape()}"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.adds(): Boolean =
    opcode == Opcode.ADD_INT || opcode == Opcode.ADD_INT_2ADDR || opcode == Opcode.ADD_INT_LIT8 || opcode == Opcode.ADD_INT_LIT16

private fun Method.holds(string: String): Boolean = implementation?.instructions?.any { instruction ->
    (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
        ((instruction as ReferenceInstruction).reference as StringReference).string == string
} ?: false
