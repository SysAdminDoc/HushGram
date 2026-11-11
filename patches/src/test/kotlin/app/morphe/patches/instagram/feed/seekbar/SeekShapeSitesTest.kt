/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.seekbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The seek bar shape on story progress segments and on the seek bar of a video opened from a chat (#630). */
class SeekShapeSitesTest {
    /** The calls the patch writes are in the SeekShapes the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(SEEK_SHAPES).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}${it.shape()}" }
        for (hook in listOf(STORY_BAR_HOOK, STORY_SEGMENT_HOOK, CHAT_VIDEO_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * On 454.0.0.54.77's arm64 build 386014758 the bar draws ten rounded rectangles, four in onDraw,
     * three in A0A, two in A0B and one in A00, and the chat media viewer is 0GOB. Each build has its
     * own names for the viewer.
     */
    @Test
    fun eachBuildHooksTheStoryBarAndTheChatVideo() {
        for (bundle in Fixtures.files { it.extension == "apks" }) hooks(bundle, bundle.name)
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    /** A second run finds its own calls and leaves both alone rather than hooking twice. */
    @Test
    fun aSecondRunLeavesTheHooksAlone() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle))
        context.hookStoryBarShape(context.findStoryBarShape()!!)
        context.hookChatVideoShape(context.findChatVideoShape()!!)
        assertNull(context.findStoryBarShape())
        assertNull(context.findChatVideoShape())
    }

    /** A build without the bar or without the viewer goes in without that one and keeps the other. */
    @Test
    fun aBuildWithoutEitherGoesInWithoutIt() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val all = classes(bundle)
        val noBar = PatchContexts.of(all.filter { it.type != SEGMENTED_PROGRESS_BAR })
        assertNull(noBar.findStoryBarShape())
        assertNotNull("the viewer is still found without the bar", noBar.findChatVideoShape())
        val viewer = PatchContexts.of(all).findChatVideoShape()!!.method.definingClass
        val noViewer = PatchContexts.of(all.filter { it.type != viewer })
        assertNull(noViewer.findChatVideoShape())
        assertNotNull("the bar is still found without the viewer", noViewer.findStoryBarShape())
    }

    private fun hooks(bundle: File, label: String) {
        val classes = classes(bundle)
        val context = PatchContexts.of(classes)
        val story = context.findStoryBarShape() ?: throw AssertionError("$label: no story progress bar sites")
        val chat = context.findChatVideoShape() ?: throw AssertionError("$label: no chat video seek bar")
        context.hookStoryBarShape(story)
        context.hookChatVideoShape(chat)

        val stock = classes.single { it.type == SEGMENTED_PROGRESS_BAR }
        val patched = context.mutableClassDefBy(SEGMENTED_PROGRESS_BAR)
        val draws = stock.methods.associate { it.name to it.code().count { i -> i.called() == DRAW_ROUND_RECT } }.filterValues { it > 0 }
        assertTrue("$label: the bar draws a rounded rectangle", draws.values.sum() > 0)
        if (label.contains("386014758")) {
            assertEquals("$label: the bar's rounded rectangles", mapOf("onDraw" to 4, "A0A" to 3, "A0B" to 2, "A00" to 1), draws)
        }
        for (method in stock.methods) {
            val before = method.code()
            val after = patched.methods.single { it.name == method.name && it.shape() == method.shape() }
            val code = after.code()
            assertEquals("$label: ${method.name} keeps its registers", method.implementation?.registerCount, after.implementation?.registerCount)
            assertTrue("$label: ${method.name} draws no rounded rectangle itself", code.none { it.called() == DRAW_ROUND_RECT })
            val shift = if (method.name == "onDraw" && method.shape() == "(Landroid/graphics/Canvas;)V") 1 else 0
            assertEquals("$label: ${method.name} gains only the bar's call", before.size + shift, code.size)
            before.forEachIndexed { at, was ->
                val now = code[at + shift]
                if (was.called() == DRAW_ROUND_RECT) {
                    assertEquals("$label: ${method.name} hands the segment to the extension", STORY_SEGMENT_HOOK, now.called())
                    assertTrue("$label: ${method.name} calls it statically", now.opcode == Opcode.INVOKE_STATIC || now.opcode == Opcode.INVOKE_STATIC_RANGE)
                    assertEquals("$label: ${method.name} on the same registers", was.registers(), now.registers())
                } else {
                    assertEquals("$label: ${method.name} keeps the rest", was.opcode, now.opcode)
                }
            }
        }
        val onDraw = patched.methods.single { it.name == "onDraw" && it.shape() == "(Landroid/graphics/Canvas;)V" }
        val first = onDraw.code().first()
        assertEquals("$label: onDraw asks the shape first thing", STORY_BAR_HOOK, first.called())
        // onDraw(Canvas) is an instance method of two parameter registers, so the bar is the second last.
        assertEquals("$label: with the bar", listOf(onDraw.implementation!!.registerCount - 2), first.registers())

        val viewer = classes.single { it.type == chat.method.definingClass }.methods
            .single { it.name == chat.method.name && it.shape() == chat.method.shape() }.code()
        val hooked = context.mutableClassDefBy(chat.method.definingClass).methods
            .single { it.name == chat.method.name && it.shape() == chat.method.shape() }.code()
        val kept = viewer.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && it.fieldType() == "Landroidx/appcompat/widget/AppCompatSeekBar;" }
        assertTrue("$label: the viewer keeps a seek bar", kept >= 0)
        assertEquals("$label: the viewer gains one call", viewer.size + 1, hooked.size)
        assertEquals("$label: right after it keeps its seek bar", CHAT_VIDEO_HOOK, hooked[kept + 1].called())
        assertEquals("$label: the seek bar it kept", listOf((viewer[kept] as TwoRegisterInstruction).registerA), hooked[kept + 1].registers())
        assertEquals("$label: and nothing else moves", viewer[kept + 1].opcode, hooked[kept + 2].opcode)
        if (label.contains("386014758")) assertEquals("$label: the chat media viewer", "LX/0GOB;", chat.method.definingClass)
    }

    /** The progress bar, the classes loading the chat viewer's key and the string pools they ask. */
    private fun classes(bundle: File): List<ClassDef> {
        val found = FixtureDex.classes(bundle, setOf(SEGMENTED_PROGRESS_BAR)).values.toMutableList()
        found += FixtureDex.classesLoading(bundle, CHAT_VIDEO_KEY).filter { it.type != SEGMENTED_PROGRESS_BAR }
        return FixtureDex.withStringPools(bundle, found)
    }

    private fun Method.shape(): String = "(${parameterTypes.joinToString("")})$returnType"

    /** The code without alignment nops, which an insert can add or take away in front of a payload. */
    private fun Method.code(): List<Instruction> =
        implementation?.instructions?.filter { it.opcode != Opcode.NOP } ?: emptyList()

    private fun Instruction.called(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.fieldType(): String? = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.type

    private fun Instruction.registers(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}
