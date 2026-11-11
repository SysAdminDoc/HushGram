/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.comments

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide Reply on comments (#619): in every build the composable drawing the line under a comment
 * asks CommentReply.hidden right before it draws the Reply text button and right before it calls
 * the reply icon's composable, goes on past the call on a yes, and keeps the rest of its code. In
 * every declared build the comment row a comments sheet builds as components asks it right before
 * the one add both kinds of Reply reach, and skips the add on a yes.
 */
class HideCommentReplyTest {
    @Test
    fun theHookIsInTheExtension() {
        val type = COMMENT_REPLY_HIDDEN.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$type->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMMENT_REPLY_HIDDEN is not in the extension: $declared", COMMENT_REPLY_HIDDEN in declared)
    }

    @Test
    fun eachDeclaredBuildTakesReplyOff() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                takesReplyOff(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildTakesReplyOff() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            takesReplyOff(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
    }

    /** Without the comment line nothing else has its shape: the find stops, and the warning route answers null. */
    @Test
    fun withoutTheCommentLineTheFindStops() {
        val version = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.first()
        val bundle = Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.first()
        val classes = lineClasses(bundle)
        val line = PatchContexts.of(classes).findCommentReply().type
        val without = classes.filter { it.type != line }
        val failure = runCatching { PatchContexts.of(without).findCommentReply() }.exceptionOrNull()
        assertTrue("the find went on: $failure", failure is PatchException)
        assertTrue(failure!!.message!!, failure.message!!.startsWith("Hide comments: ") && failure.message!!.contains("found 0"))
        assertNull(PatchContexts.of(without).findCommentReplyOrWarn())
    }

    @Test
    fun eachDeclaredBuildTakesReplyOffTheRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                takesReplyOffTheRow(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** Without the comment row nothing else has its shape: the find stops, and the warning route answers null. */
    @Test
    fun withoutTheRowTheFindStops() {
        val version = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.first()
        val bundle = Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.first()
        val classes = lineClasses(bundle)
        val row = PatchContexts.of(classes).findCommentRowReply().type
        val without = classes.filter { it.type != row }
        val failure = runCatching { PatchContexts.of(without).findCommentRowReply() }.exceptionOrNull()
        assertTrue("the find went on: $failure", failure is PatchException)
        assertTrue(failure!!.message!!, failure.message!!.startsWith("Hide comments: ") && failure.message!!.contains("found 0"))
        assertNull(PatchContexts.of(without).findCommentRowReplyOrWarn())
    }

    /**
     * In front of the row's one Reply add: the no-argument hook, its answer in a register the add
     * doesn't read, and an if-nez on it landing right after the add. The hook takes the add's label,
     * so the goto bringing the text button's component lands on the hook and nothing jumps past it.
     * Nothing else changes.
     */
    private fun takesReplyOffTheRow(bundle: File, label: String) {
        val context = PatchContexts.of(lineClasses(bundle))
        val plan = context.findCommentRowReply()
        val row = context.mutableClassDefBy(plan.type).methods.single {
            it.name == plan.name && it.parameterTypes.map(CharSequence::toString) == plan.parameters
        }
        val stock = row.code()
        assertEquals("$label: the add", plan.add, stock[plan.addAt].referenceText())
        val add = stock[plan.addAt].reference() as MethodReference
        assertEquals("$label: the add answers nothing and takes one component", "V" to 1, add.returnType to add.parameterTypes.size)
        val jumpsIn = stock.jumpsInto(plan.addAt)
        assertTrue("$label: the text button's goto lands on the add", jumpsIn > 0)

        context.hideCommentRowReply(plan)

        val code = row.code()
        val hooks = code.indices.filter { code[it].referenceText() == COMMENT_REPLY_HIDDEN }
        assertEquals("$label: one hook, on the add's label", listOf(plan.addAt), hooks)
        val at = hooks.single()
        assertEquals("$label: the hook", Opcode.INVOKE_STATIC to 0, code[at].opcode to (code[at] as FiveRegisterInstruction).registerCount)
        assertEquals("$label: its answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
        val answer = (code[at + 1] as OneRegisterInstruction).registerA
        assertEquals("$label: the branch reads the answer", Opcode.IF_NEZ to answer, code[at + 2].opcode to (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$label: a yes goes on past the add", at + 4, (code[at + 2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: the add follows", plan.add, code[at + 3].referenceText())
        assertTrue("$label: the add doesn't read v$answer", answer !in code[at + 3].arguments())
        assertEquals("$label: what jumped to the add lands on the hook", jumpsIn to 0, code.jumpsInto(at) to code.jumpsInto(at + 3))
        val kept = code.filterIndexed { index, _ -> index !in at..at + 2 }
        assertEquals("$label: the row's own code is kept", stock.shape(), kept.shape())
    }

    private fun List<Instruction>.jumpsInto(at: Int) = count { (it as? BuilderOffsetInstruction)?.target?.location?.index == at }

    /** The classes loading either reply tag, the pools they ask included. */
    private fun lineClasses(bundle: File): List<ClassDef> =
        (FixtureDex.classesLoading(bundle, COMMENT_REPLY_TAG) + FixtureDex.classesLoading(bundle, COMMENT_THREADS_REPLY_TAG))
            .distinctBy { it.type }

    /**
     * Before each of the two calls: the no-argument hook, its answer moved into a register the call
     * doesn't read, and an if-nez on it landing on the instruction right after the call. The button
     * call is the static void one that takes the modifier the reply tag went into; the icon call's
     * target loads the newer reply tag. Nothing else changes.
     */
    private fun takesReplyOff(bundle: File, label: String) {
        val context = PatchContexts.of(lineClasses(bundle))
        val plan = context.findCommentReply()
        val line = context.mutableClassDefBy(plan.type).methods.single {
            it.name == plan.name && it.parameterTypes.map(CharSequence::toString) == plan.parameters
        }
        val stock = line.code()
        assertTrue("$label: two different calls", plan.button != plan.icon && plan.buttonAt != plan.iconAt)
        assertEquals("$label: the button call", plan.button, stock[plan.buttonAt].referenceText())
        assertEquals("$label: the icon call", plan.icon, stock[plan.iconAt].referenceText())
        val button = stock[plan.buttonAt].reference() as MethodReference
        assertEquals("$label: the button draws and answers nothing", "V", button.returnType)
        assertTrue("$label: the button takes an onClick", "Lkotlin/jvm/functions/Function0;" in button.parameterTypes.map(CharSequence::toString))
        val icon = stock[plan.iconAt].reference() as MethodReference
        assertEquals("$label: the icon draws and answers nothing", "V", icon.returnType)

        context.hideCommentReply(plan)

        val code = line.code()
        val hooks = code.indices.filter { code[it].referenceText() == COMMENT_REPLY_HIDDEN }
        assertEquals("$label: one hook per call", 2, hooks.size)
        val guarded = hooks.map { at ->
            val ask = code[at] as FiveRegisterInstruction
            assertEquals("$label: the hook at $at", Opcode.INVOKE_STATIC to 0, code[at].opcode to ask.registerCount)
            assertEquals("$label: its answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
            val answer = (code[at + 1] as OneRegisterInstruction).registerA
            assertEquals("$label: the branch reads the answer", Opcode.IF_NEZ to answer, code[at + 2].opcode to (code[at + 2] as OneRegisterInstruction).registerA)
            assertEquals("$label: a yes goes on past the call", at + 4, (code[at + 2] as BuilderOffsetInstruction).target.location.index)
            assertTrue("$label: the call doesn't read v$answer", answer !in code[at + 3].arguments())
            code[at + 3].referenceText()
        }
        assertEquals("$label: the button and the icon are guarded", setOf(plan.button, plan.icon), guarded.toSet())
        val kept = code.filterIndexed { at, _ -> hooks.none { at in it..it + 2 } }
        assertEquals("$label: the line's own code is kept", stock.shape(), kept.shape())
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction -> List(registerCount) { startRegister + it }
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    /** Opcodes and references without padding, with a widened goto read as a goto. */
    private fun List<Instruction>.shape() = filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }
        .map { kind(it.opcode) to it.referenceText() }

    private fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
}
