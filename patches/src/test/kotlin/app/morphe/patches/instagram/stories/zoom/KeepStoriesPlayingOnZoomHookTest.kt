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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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
 * returns early when it says to keep playing, and a build where any of the three can't be placed
 * fails the patch unchanged.
 */
class KeepStoriesPlayingOnZoomHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_ZOOM_PLAY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$STORY_ZOOM_PLAY->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(KEEP_PLAYING, ZOOM_PAUSED, ZOOM_RESUMED)) {
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
        val pinch = code(context, found.pinch)
        assertEquals("$label: the pinch pause asks first", KEEP_PLAYING, reference(pinch[0]))
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, pinch[1].opcode)
        assertEquals("$label: the answer is tested", Opcode.IF_EQZ, pinch[2].opcode)
        assertEquals("$label: and returns", Opcode.RETURN_VOID, pinch[3].opcode)
        assertTrue("$label: Instagram's own pause follows", pinch.size > 4)

        for ((type, original) in before) {
            val now = context.mutableClassDefBy(type).methods
            for (method in original.methods) {
                val hooked = listOf(found.pause, found.resume, found.pinch).any { it.is_(method) }
                if (hooked) continue
                val changed = now.single { it.sameSignatureAs(method) }
                assertEquals("$label: ${method.name} changed", method.code().map(::text), changed.code().map(::text))
            }
        }
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
