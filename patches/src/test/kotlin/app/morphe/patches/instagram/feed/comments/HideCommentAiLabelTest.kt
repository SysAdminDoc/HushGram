/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.comments

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide AI labels on comments (#787): the component building a comment's name line hands its AI label
 * flag to CommentAiLabel.shown right after reading it, and its own test of the flag skips the dot and
 * the label on a no. A build whose line reads the label some other way refuses before anything
 * changes, and Hide comments goes in without the AI label part.
 */
class HideCommentAiLabelTest {
    private val line = "Lfixture/CommentLine;"
    private val label = "Lfixture/AiLabel;"
    private val labels = "Lfixture/AiLabels;"
    private val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

    @Test
    fun theHookIsInTheExtension() {
        val type = COMMENT_AI_LABEL_SHOWN.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$type->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMMENT_AI_LABEL_SHOWN is not in the extension: $declared", COMMENT_AI_LABEL_SHOWN in declared)
    }

    @Test
    fun theNameLineAsksBeforeItTestsItsFlag() {
        val context = context()
        val plan = context.findCommentAiLabel()
        assertEquals(line, plan.type)
        assertEquals("$line->ai:Z", plan.flag)
        val stock = context.lineCode(plan)
        context.hideCommentAiLabel(plan)
        assertAsks("fixture", context.lineCode(plan), stock, plan)
    }

    @Test
    fun aLineItCantReadRefusesAndTheRestGoesIn() {
        refuses("found 0", context(lines = 0))
        refuses("found 2", context(lines = 2))
        refuses("found 0", context(timeTag = false))
        refuses("found 0", context(reads = 2))
        refuses("found 0", context(getterTakesTheLabel = false))
        refuses("found 0", context(sameEnd = false))
        refuses("jumps to the test of its AI label flag", context(jumpOntoTheTest = true))
        val empty = PatchContexts.of(emptyList<ClassDef>())
        assertThrows(PatchException::class.java) { empty.findCommentAiLabel() }
        assertNull(empty.findCommentAiLabelOrWarn())
    }

    /** A line changed between the find and the hook refuses rather than hooking something else. */
    @Test
    fun aLineThatChangedRefusesTheHook() {
        val plan = context().findCommentAiLabel()
        val moved = CommentAiLabelPlan(plan.type, plan.name, plan.parameters, plan.returnType, plan.readAt + 1, plan.flag, plan.register)
        val failure = assertThrows(PatchException::class.java) { context().hideCommentAiLabel(moved) }
        assertTrue(failure.message, failure.message!!.startsWith("Hide comments: ") && "changed before its AI label" in failure.message!!)
    }

    @Test
    fun declaredBuildsAskBeforeTheLabel() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var count = 0
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            builds(bundle)
            count++
        }
        assertTrue("no fixture of a declared build", count > 0)
    }

    @Test
    fun otherBuildsAskBeforeTheLabelToo() {
        for (bundle in Fixtures.otherBuilds()) builds(bundle)
    }

    /** The classes loading the name tag, pools included: one name line reads the label once, and it's hooked there. */
    private fun builds(bundle: File) {
        val name = bundle.parentFile.name + "/" + bundle.name
        val context = PatchContexts.of(FixtureDex.classesLoading(bundle, COMMENT_USERNAME_TAG))
        val plan = try {
            context.findCommentAiLabel()
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        val stock = context.lineCode(plan)
        context.hideCommentAiLabel(plan)
        assertAsks(name, context.lineCode(plan), stock, plan)
    }

    /**
     * Right after the read: the hook, handed the flag's register alone, its answer back in that
     * register, then the line's own test of it. One hook, nothing jumps onto it, the rest as it was.
     */
    private fun assertAsks(what: String, code: List<Instruction>, stock: List<Instruction>, plan: CommentAiLabelPlan) {
        assertEquals("$what: the read", Opcode.IGET_BOOLEAN to plan.flag, stock[plan.readAt].opcode to text(stock[plan.readAt]))
        assertEquals("$what: its test", Opcode.IF_EQZ to plan.register, stock[plan.readAt + 1].opcode to (stock[plan.readAt + 1] as OneRegisterInstruction).registerA)
        val hooks = code.indices.filter { text(code[it]) == COMMENT_AI_LABEL_SHOWN }
        assertEquals("$what: one hook, right after the read", listOf(plan.readAt + 1), hooks)
        val call = code[plan.readAt + 1] as RegisterRangeInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC_RANGE, code[plan.readAt + 1].opcode)
        assertEquals("$what: of the flag alone", 1 to plan.register, call.registerCount to call.startRegister)
        val answer = code[plan.readAt + 2]
        assertEquals("$what: its answer in the flag's register", Opcode.MOVE_RESULT to plan.register, answer.opcode to (answer as OneRegisterInstruction).registerA)
        assertEquals("$what: then the line's own test", Opcode.IF_EQZ to plan.register, code[plan.readAt + 3].opcode to (code[plan.readAt + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: nothing jumps onto the hook", 0, code.count { (it as? BuilderOffsetInstruction)?.target?.location?.index == plan.readAt + 1 })
        val kept = code.filterIndexed { at, _ -> at != plan.readAt + 1 && at != plan.readAt + 2 }
        assertEquals("$what: the line's own code is kept", stock.shape(), kept.shape())
    }

    private fun refuses(reason: String, context: BytecodePatchContext) {
        val failure = assertThrows(PatchException::class.java) { context.findCommentAiLabel() }
        assertTrue(failure.message, failure.message!!.startsWith("Hide comments: "))
        assertTrue(failure.message, reason in failure.message!!)
        assertNull(context.findCommentAiLabelOrWarn())
    }

    private fun BytecodePatchContext.lineCode(plan: CommentAiLabelPlan): List<Instruction> =
        mutableClassDefBy(plan.type).methods.single { it.name == plan.name && it.parameterTypes.map(Any::toString) == plan.parameters }.instructions()

    /**
     * Shaped like 456.1's 04G4.A0p: the name line loads the name tag, reads its AI label flag and the
     * label, both null-tested to past the label, adds a dot, asks the label's words, falls back to a
     * string of its own, and later loads the time tag.
     */
    private fun context(
        lines: Int = 1,
        timeTag: Boolean = true,
        reads: Int = 1,
        getterTakesTheLabel: Boolean = true,
        sameEnd: Boolean = true,
        jumpOntoTheTest: Boolean = false,
    ): BytecodePatchContext {
        val classes = mutableListOf<ClassDef>()
        repeat(lines) { index ->
            val type = if (index == 0) line else "Lfixture/CommentLine$index;"
            val getter = if (getterTakesTheLabel) {
                "invoke-static {v2}, $labels->text($label)Ljava/lang/String;"
            } else {
                "invoke-static {v0}, $labels->text(Ljava/lang/Object;)Ljava/lang/String;"
            }
            // One read of the flag and the label, both tested to past the label at :end<n>.
            fun read(n: Int, flagEnd: String) = """
                iget-boolean v1, p0, $type->ai:Z
                ${if (jumpOntoTheTest && n == 0) ":test" else ""}
                if-eqz v1, $flagEnd
                iget-object v2, p0, $type->label:$label
                if-eqz v2, :end$n
                invoke-virtual {v0}, Ljava/lang/Object;->hashCode()I
                $getter
                move-result-object v3
                if-nez v3, :end$n
                const-string v3, "AI creator"
                :end$n
            """
            val body = buildString {
                appendLine("const-string v0, \"$COMMENT_USERNAME_TAG\"")
                if (jumpOntoTheTest) appendLine("if-nez v0, :test")
                repeat(reads) { n -> appendLine(read(n, if (sameEnd || n > 0) ":end$n" else ":other")) }
                if (!sameEnd) appendLine("invoke-virtual {v0}, Ljava/lang/Object;->hashCode()I\n:other")
                if (timeTag) appendLine("const-string v0, \"$COMMENT_TIME_TAG\"")
                appendLine("return-object v0")
            }
            classes += classDef(
                type,
                listOf(method(type, "build", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;", 4, final, body)),
                listOf(
                    ImmutableField(type, "ai", "Z", final, null, null, null),
                    ImmutableField(type, "label", label, final, null, null, null),
                ),
            )
        }
        classes += classDef(
            labels,
            listOf(
                method(labels, "text", listOf(label), "Ljava/lang/String;", 1, final or AccessFlags.STATIC.value, "const-string v0, \"AI-generated profile\"\nreturn-object v0"),
                method(labels, "text", listOf("Ljava/lang/Object;"), "Ljava/lang/String;", 1, final or AccessFlags.STATIC.value, "const/4 v0, 0x0\nreturn-object v0"),
            ),
        )
        return PatchContexts.of(classes)
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val words = parameters.size + parameters.count { it == "J" || it == "D" }
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + words
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, final, "Ljava/lang/Object;", emptyList(), null, null, fields, methods)

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun text(instruction: Instruction): String =
        (instruction as? ReferenceInstruction)?.reference?.toString() ?: "${instruction.opcode}"

    /** Opcodes and references without padding, with a widened goto read as a goto. */
    private fun List<Instruction>.shape() = filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }
        .map { (if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode) to text(it) }
}
