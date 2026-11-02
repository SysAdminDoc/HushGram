/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.zoom

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.stories.mentions.HOLDS_STORY
import app.morphe.patches.instagram.stories.mentions.STORY_MENTIONS
import app.morphe.patches.instagram.stories.mentions.findStoryHold
import app.morphe.patches.instagram.stories.mentions.hookStoryHold
import app.morphe.patches.instagram.stories.mentions.storyHoldStubs
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The story viewer's pause and resume report to the extension, the pinch's pause asks it first and
 * resumes the story and returns early when it says to keep playing, the resume's drag check goes
 * through the extension, and a build where any of the three can't be placed fails the patch
 * unchanged.
 */
class KeepStoriesPlayingOnZoomHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_ZOOM_PLAY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$STORY_ZOOM_PLAY->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(KEEP_PLAYING, ZOOM_PAUSED, ZOOM_RESUMED, PINCH_RESUMED, DRAGGING)) {
            assertTrue("StoryZoomPlay declares no $hook: $declared", hook in declared)
        }
    }

    @Test
    fun aBuildWithoutThePinchPauseFailsThePatch() =
        refuses("one run() loading \"$PINCH_REASON\"", listOf(pinchRunner(reason = "paused_for_other")))

    @Test
    fun aBuildWithoutTheViewerFailsThePatch() =
        refuses("no $VIEWER loads \"$PAUSE_MARK\"", listOf(pinchRunner()))

    @Test
    fun eachDeclaredBuildHooksThePinchPause() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksThePinchPauseToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /**
     * See who a story mentions puts its hold first thing in the same resume (#403). Whichever patch
     * goes in first, the drag hook still lands right after the drag check's answer, even with the
     * places counted before the hold went in.
     */
    @Test
    fun theMentionsHoldInTheResumeLeavesTheDragHookOnItsCheck() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        assertTrue("no declared build has a fixture", bundles.isNotEmpty())
        for (bundle in bundles) for (holdFirst in listOf(true, false)) {
            val label = "${bundle.name}, ${if (holdFirst) "the hold" else "the zoom"} first"
            val classes = listOf(PINCH_REASON, PAUSE_MARK, RESUME_MARK)
                .flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes) + ExtensionDex.classDef(STORY_MENTIONS))
            val found = context.findStoryZoomPause()
            val hold = context.findStoryHold()
            if (holdFirst) context.hookStoryHold(hold, context.storyHoldStubs())
            context.keepPlayingOnZoom(found)
            if (!holdFirst) context.hookStoryHold(hold, context.storyHoldStubs())

            val resume = code(context, found.resume)
            assertEquals("$label: one guard", 1, resume.count { reference(it) == HOLDS_STORY })
            assertEquals("$label: one report", 1, resume.count { reference(it) == ZOOM_RESUMED })
            assertEquals("$label: one drag hook", 1, resume.count { reference(it) == DRAGGING })
            val drag = resume.indexOfFirst { reference(it) == DRAGGING }
            val asked = (resume[drag - 2] as ReferenceInstruction).reference as MethodReference
            assertEquals("$label: right after Instagram's own ()Z check", "Z", asked.returnType)
            assertTrue("$label: which takes nothing", asked.parameterTypes.isEmpty())
            assertEquals("$label: and its answer", Opcode.MOVE_RESULT, resume[drag - 1].opcode)
            val answer = (resume[drag - 1] as OneRegisterInstruction).registerA
            assertEquals("$label: is what the hook is handed", answer, (resume[drag] as RegisterRangeInstruction).startRegister)
        }
    }

    private fun builds(bundle: File, label: String) {
        val classes = listOf(PINCH_REASON, PAUSE_MARK, RESUME_MARK)
            .flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes))

        val found = context.findStoryZoomPause()
        val before = classes.associateBy { it.type }
        context.keepPlayingOnZoom(found)

        val pause = code(context, found.pause)
        assertEquals("$label: pause starts by reporting its reason", ZOOM_PAUSED, reference(pause[0]))
        assertEquals("$label: pause", Opcode.INVOKE_STATIC_RANGE, pause[0].opcode)
        val resume = code(context, found.resume)
        assertEquals("$label: resume starts by reporting", ZOOM_RESUMED, reference(resume[0]))
        assertEquals("$label: the resume's drag check is answered once", 1, resume.count { reference(it) == DRAGGING })
        val drag = resume.indexOfFirst { reference(it) == DRAGGING }
        val asked = (resume[drag - 2] as ReferenceInstruction).reference as MethodReference
        assertEquals("$label: right after Instagram's own ()Z check", "Z", asked.returnType)
        assertEquals("$label: and its answer", Opcode.MOVE_RESULT, resume[drag - 1].opcode)
        val answer = (resume[drag - 1] as OneRegisterInstruction).registerA
        assertEquals("$label: is what the hook is handed", answer, (resume[drag] as RegisterRangeInstruction).startRegister)
        assertEquals("$label: and the hook's answer replaces it", Opcode.MOVE_RESULT, resume[drag + 1].opcode)
        assertEquals("$label: in the same register", answer, (resume[drag + 1] as OneRegisterInstruction).registerA)
        val pinch = code(context, found.pinch)
        assertEquals("$label: the pinch pause asks first", KEEP_PLAYING, reference(pinch[0]))
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, pinch[1].opcode)
        assertEquals("$label: the answer is tested", Opcode.IF_EQZ, pinch[2].opcode)
        assertEquals("$label: a yes reads the viewer", found.viewerField, reference(pinch[3]))
        assertEquals("$label: and resumes it as lifting a finger does", "\"resume\"", text(pinch[4]))
        assertEquals("$label: with true", 1, (pinch[5] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: through the viewer's resume", found.resume.descriptor, reference(pinch[6]))
        assertEquals("$label: then says the pinch's resume is done", PINCH_RESUMED, reference(pinch[7]))
        assertEquals("$label: and returns", Opcode.RETURN_VOID, pinch[8].opcode)
        assertTrue("$label: Instagram's own pause follows", pinch.size > 9)

        for ((type, original) in before) {
            val now = context.mutableClassDefBy(type).methods
            for (method in original.methods) {
                val hooked = listOf(found.pause, found.resume, found.pinch).any { it.is_(method) }
                if (hooked) continue
                val changed = now.single { it.sameSignatureAs(method) }
                assertEquals("$label: ${method.name} changed", method.code().map(::text), changed.code().map(::text))
            }
        }

        // The extension counts only this reason as a story held still, so the build has to send it.
        val holders = FixtureDex.classesLoading(bundle, HOLD)
        val holdContext = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))
        val holds = holders.flatMap { it.methods }.filter { method ->
            holdContext.loadsString(method, HOLD) && method.code().any { pausesTheViewer(it) }
        }
        assertTrue("$label: nothing pauses the viewer as \"$HOLD\"", holds.isNotEmpty())
    }

    /** A call on the viewer taking just a reason, as its pauses do. */
    private fun pausesTheViewer(instruction: Instruction): Boolean {
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return called.definingClass == VIEWER && called.returnType == "V" &&
            called.parameterTypes.map(CharSequence::toString).let { it == listOf(STRING) || it == listOf(VIEWER, STRING) }
    }

    private fun code(context: BytecodePatchContext, at: Located): List<Instruction> =
        context.mutableClassDefBy(at.type).methods.single { at.is_(it) }.code()

    private fun Located.is_(method: Method) = type == method.definingClass && name == method.name &&
        returnType == method.returnType && parameters == method.parameterTypes.map(CharSequence::toString)

    private fun reference(instruction: Instruction): String? = (instruction as? ReferenceInstruction)?.reference?.toString()

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.keepPlayingOnZoom(context.findStoryZoomPause()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { it.code().map(::text) }
            assertEquals("${classDef.type} changed", classDef.methods.map { it.code().map(::text) }, after)
        }
    }

    /** The code without the alignment nops dexlib2 adds or drops around a payload. */
    private fun Method.code(): List<Instruction> = implementation?.instructions?.filter { it.opcode != Opcode.NOP } ?: emptyList()

    private fun Method.sameSignatureAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.toString()
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode} $reference"
    }

    private companion object {
        const val VIEWER = "Linstagram/features/stories/fragment/ReelViewerFragment;"
        const val STRING = "Ljava/lang/String;"

        /** StoryZoomPlay.HOLD: the reason the viewer is paused with while a story is held still. */
        const val HOLD = "long_pressed"

        /** A runnable whose run() loads the reason a pinch pauses with. */
        fun pinchRunner(reason: String = PINCH_REASON): ClassDef = ImmutableClassDef(
            "Lfixture/PinchPause;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    "Lfixture/PinchPause;", "run", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                    ImmutableMethodImplementation(
                        3,
                        listOf(
                            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(reason)),
                            ImmutableInstruction10x(Opcode.RETURN_VOID),
                        ),
                        null, null,
                    ),
                ),
            ),
        )
    }
}
