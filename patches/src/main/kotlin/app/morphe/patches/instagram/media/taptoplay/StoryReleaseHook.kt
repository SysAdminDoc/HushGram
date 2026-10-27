/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.taptoplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

internal const val RESUME_HELD_STORY = "$TAP_TO_PLAY->resumeHeldStory(ILjava/lang/Object;)Z"

/** The extension class holding the story player's stub, apart from the start gate. */
internal const val STORY_PLAYER_READER = "$EXTENSION_PACKAGE/media/StoryPlayerReader;"
internal const val GROOT_OF = "grootOf"

/** The story viewer and its video player field, both under the names Redex leaves them. */
internal const val REEL_VIEWER_FRAGMENT = "Linstagram/features/stories/fragment/ReelViewerFragment;"
internal const val VIDEO_PLAYER_FIELD = "mVideoPlayer"

/** What the viewer's pause and resume log, which pick each one out. */
internal const val PAUSE_STORY = "pause_story"
internal val RESUME_STORY = listOf("resume_story_request_skipped", "story_resumed")

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * The story player's resume and what the extension needs to read beside it.
 *
 * @property resume the player's resume, (String, boolean)
 * @property read the index of its read of the flag that says a resume should start the story, and
 *           [flag] the register it reads it into, which the branch after it tests
 * @property groot the player's IgGrootPlayer field
 */
internal class StoryRelease(
    val resume: Method,
    val read: Int,
    val flag: Int,
    val groot: FieldReference,
)

/**
 * The story viewer's video player starts a story on a resume only when its flag says the story
 * played before a pause, and a press sets that flag only for a story that's playing. A story the
 * gate held never played, so the release of a press and hold never started it. The flag the resume
 * reads now goes past the extension first, with the player, and the extension's stub reads the
 * player's IgGrootPlayer, the one the gate held. The hook goes between the read and the branch
 * testing it, so Instagram's checks after the branch (a story still preparing waits) still hold.
 */
internal fun BytecodePatchContext.hookStoryRelease(found: StoryRelease) {
    mutableClassDefBy(STORY_PLAYER_READER).methods.single { it.isGrootStub() }.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.resume.definingClass}
            iget-object p0, p0, ${found.groot}
            return-object p0
        """,
    )
    mutable(found.resume).addInstructions(
        found.read + 1,
        """
            invoke-static { v${found.flag}, p0 }, $RESUME_HELD_STORY
            move-result v${found.flag}
        """,
    )
}

/**
 * The story player's resume. The viewer's [VIDEO_PLAYER_FIELD] field names the player interface;
 * the viewer's method holding [PAUSE_STORY] makes its one (String) call on it, the pause, and the one
 * holding [RESUME_STORY] its one (String, boolean) call, the resume. The player is the one class
 * implementing that interface with exactly one field of [grootType], IgGrootPlayer; another one plays
 * through IgVideoPlayerImpl, whose resume reaches the start gate by itself. The flag is the one
 * boolean field of the player's own that its resume reads on `this` and branches on straight after,
 * and writes too, and that its pause writes.
 */
internal fun BytecodePatchContext.findStoryRelease(grootType: String): StoryRelease {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the story release: $detail")
    val fragment = classDefByOrNull(REEL_VIEWER_FRAGMENT) ?: refuse("this build has no $REEL_VIEWER_FRAGMENT")
    val playerInterface = fragment.fields.singleOrNull { it.name == VIDEO_PLAYER_FIELD }?.type
        ?: refuse("$REEL_VIEWER_FRAGMENT has no $VIDEO_PLAYER_FIELD field")

    fun callOn(strings: List<String>, parameters: List<String>, what: String): String {
        val holders = fragment.methods.filter { method -> strings.all { loadsString(method, it) } }
        val holder = holders.singleOrNull() ?: refuse("expected one $REEL_VIEWER_FRAGMENT method holding $strings, found ${holders.size}")
        val calls = holder.code().mapNotNull { it.methodReference() }.filter {
            it.definingClass == playerInterface && it.returnType == "V" && it.parameterTypes.map(Any::toString) == parameters
        }.distinctBy { it.name }
        return calls.singleOrNull()?.name ?: refuse("expected one $what on $playerInterface in ${holder.name}, found ${calls.size}")
    }
    val pauseName = callOn(listOf(PAUSE_STORY), listOf(STRING), "pause")
    val resumeName = callOn(RESUME_STORY, listOf(STRING, "Z"), "resume")

    val players = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (playerInterface in classDef.interfaces &&
            classDef.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == grootType } == 1
        ) players += classDef
    }
    val player = players.singleOrNull() ?: refuse("expected one $playerInterface holding one $grootType, found ${players.size}")
    fun implemented(name: String, parameters: List<String>) = player.methods.singleOrNull {
        it.name == name && it.returnType == "V" && it.parameterTypes.map(Any::toString) == parameters && it.implementation != null
    } ?: refuse("${player.type} doesn't implement $name$parameters")
    val pause = implemented(pauseName, listOf(STRING))
    val resume = implemented(resumeName, listOf(STRING, "Z"))
    if (AccessFlags.STATIC.isSet(resume.accessFlags)) refuse("${player.type}->$resumeName is static")

    val code = resume.code()
    val self = resume.localRegisterCount()
    val pauseWrites = pause.code().filter { it.opcode == Opcode.IPUT_BOOLEAN }.mapNotNull { it.fieldReference()?.toString() }.toSet()
    val reads = code.indices.filter { index ->
        val read = code[index]
        val field = read.fieldReference()?.takeIf { it.definingClass == player.type } ?: return@filter false
        val registers = read as? TwoRegisterInstruction ?: return@filter false
        val register = registers.registerA
        val branch = code.getOrNull(index + 1)
        read.opcode == Opcode.IGET_BOOLEAN && registers.registerB == self &&
            branch?.opcode == Opcode.IF_EQZ && (branch as OneRegisterInstruction).registerA == register &&
            code.any { it.opcode == Opcode.IPUT_BOOLEAN && it.fieldReference()?.toString() == field.toString() } &&
            field.toString() in pauseWrites
    }
    val read = reads.singleOrNull() ?: refuse("expected one flag ${player.type}->$resumeName reads, branches on and clears, found ${reads.size}")
    // Both come from the read's four-bit registers, so the hook's invoke can name them.
    val flag = (code[read] as TwoRegisterInstruction).registerA
    resume.requireThisIntact(PATCH, listOf(read + 1))

    val groot = player.fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == grootType }
    // The stub runs in the extension, so the player and the field it reads must be public.
    val unreachable = listOfNotNull(
        player.type.takeUnless { AccessFlags.PUBLIC.isSet(player.accessFlags) },
        "${player.type}->${groot.name}".takeUnless { AccessFlags.PUBLIC.isSet(groot.accessFlags) },
    )
    if (unreachable.isNotEmpty()) refuse("the extension can't reach ${unreachable.joinToString()}")
    val extension = classDefByOrNull(STORY_PLAYER_READER) ?: refuse("the extension has no $STORY_PLAYER_READER")
    extension.methods.singleOrNull { it.isGrootStub() } ?: refuse("$STORY_PLAYER_READER has no static Object $GROOT_OF taking an Object")

    return StoryRelease(resume, read, flag, ImmutableFieldReference(player.type, groot.name, groot.type))
}

private fun Method.isGrootStub() =
    name == GROOT_OF && AccessFlags.STATIC.isSet(accessFlags) && returnType == OBJECT && parameterTypes.map(Any::toString) == listOf(OBJECT)

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
