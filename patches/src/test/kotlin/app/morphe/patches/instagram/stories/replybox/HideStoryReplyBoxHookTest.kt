/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.replybox

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.media.taptoplay.REEL_VIEWER_FRAGMENT
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HideStoryReplyBoxHookTest {
    /** The call the patch writes into the viewer is in the StoryReplyBox the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_REPLY_BOX).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$REPLY_BOX_RESUMED is not in the extension: $declared", REPLY_BOX_RESUMED.substringAfter("->") in declared)
    }

    /** On each declared build the patch fills the view stub and tells the extension right after onResume hands on to the superclass. */
    @Test
    fun eachDeclaredBuildHooksTheViewer() {
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
    fun eachOtherBuildHooksTheViewerToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** A second run finds its own call and refuses instead of telling the extension twice. */
    @Test
    fun aSecondRunRefuses() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle))
        context.hookStoryReplyBox(context.findStoryReplyBox())
        val failure = assertThrows(PatchException::class.java) { context.findStoryReplyBox() }
        assertTrue(failure.message, failure.message!!.contains("already calls $STORY_REPLY_BOX"))
    }

    /** A build without the viewer, or without the extension's stub, fails before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val all = classes(bundle)
        val cases = listOf(
            all.filter { it.type != STORY_REPLY_BOX_READER } to "the extension has no $STORY_REPLY_BOX_READER",
            all.filter { it.type != REEL_VIEWER_FRAGMENT } to "this build has no $REEL_VIEWER_FRAGMENT",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findStoryReplyBox() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            if (classes.any { it.type == REEL_VIEWER_FRAGMENT }) {
                val viewer = context.mutableClassDefBy(REEL_VIEWER_FRAGMENT).methods
                assertTrue("$expected: something was written", viewer.none { m -> m.code().any { it.method()?.definingClass == STORY_REPLY_BOX } })
            }
        }
    }

    private fun builds(bundle: File, label: String) {
        val classes = classes(bundle)
        val context = PatchContexts.of(classes)
        context.hookStoryReplyBox(context.findStoryReplyBox())

        val stock = classes.single { it.type == REEL_VIEWER_FRAGMENT }.resume().code()
        val hooked = context.mutableClassDefBy(REEL_VIEWER_FRAGMENT).resume().code()
        assertEquals("$label: onResume grows by the one call", stock.size + 1, hooked.size)
        val at = hooked.indexOfFirst { it.method()?.toString() == REPLY_BOX_RESUMED }
        assertTrue("$label: onResume calls $REPLY_BOX_RESUMED", at > 0)
        assertEquals("$label: onResume calls it once", 1, hooked.count { it.method()?.toString() == REPLY_BOX_RESUMED })
        val before = hooked[at - 1]
        assertTrue("$label: the call sits right after the superclass's", before.opcode == Opcode.INVOKE_SUPER || before.opcode == Opcode.INVOKE_SUPER_RANGE)
        assertEquals("$label: the superclass call", "onResume", before.method()?.name)

        val stub = context.mutableClassDefBy(STORY_REPLY_BOX_READER).methods.single { it.name == "view" }.code()
        assertEquals("$label: the stub is filled", Opcode.CHECK_CAST, stub.first().opcode)
        assertEquals("$label: the stub reads the viewer's view", "getView", stub.mapNotNull { it.method() }.single().name)
    }

    /** The viewer and the extension's stub. */
    private fun classes(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.firstOrNull { it.type == REEL_VIEWER_FRAGMENT }?.let { found += ImmutableClassDef.of(it) }
        }
        if (found.isEmpty()) error("${bundle.name} has no $REEL_VIEWER_FRAGMENT")
        return FixtureDex.withStringPools(bundle, found) + ExtensionDex.classDef(STORY_REPLY_BOX_READER)
    }

    private fun ClassDef.resume(): Method =
        methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
}
