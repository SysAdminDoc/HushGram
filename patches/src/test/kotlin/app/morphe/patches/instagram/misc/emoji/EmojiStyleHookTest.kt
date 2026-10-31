/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.emoji

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Emoji style: first thing in EmojiCompat's process, the strategy it was handed goes through
 * EmojiStyle.replaceStrategy and the answer replaces it, and a process the patch can't tell the
 * strategy of fails the patch before a change. In front of the typeface read in EmojiCompat's
 * span's draw, the span goes to EmojiFonts.drawn with the paint it draws with, and draw returns
 * when the extension drew the emoji (#166).
 */
class EmojiStyleHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        for ((type, hook) in listOf(EMOJI_STYLE to REPLACE_STRATEGY, EMOJI_FONTS to SPAN_DRAWN)) {
            val declared = ExtensionDex.classDef(type).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook in declared)
        }
    }

    @Test
    fun processAsksForTheStrategyFirst() {
        val context = PatchContexts.of(listOf(process(), extension()))

        val found = context.findEmojiProcess()
        assertEquals(PROCESS, found.method.definingClass)
        assertEquals("p4", found.strategy)
        context.askReplaceStrategy()

        assertAsks("stand-in", context.mutableClassDefBy(PROCESS).methods.single { it.name == "A04" }, 18)
    }

    @Test
    fun aProcessItCantTellTheStrategyOfFailsThePatch() {
        for ((case, classes) in listOf(
            "two methods holding the checks" to listOf(process(), process(type = "Lfixture/OtherCompat;"), extension()),
            "a static process" to listOf(process(static = true), extension()),
            "a most emoji count still taken" to listOf(process(counted = true), extension()),
            "the start not checked for being negative" to listOf(process(startChecked = false), extension()),
            "the strategy never compared" to listOf(process(compared = false), extension()),
            "a jump onto the start" to listOf(process(loop = true), extension()),
            "no hook in the extension" to listOf(process()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findEmojiProcess() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Emoji style:"))
        }
    }

    /** A refusal comes before any change: process keeps every instruction it had. */
    @Test
    fun aRefusalChangesNothing() {
        val stub = process(compared = false)
        val context = PatchContexts.of(listOf(stub, extension()))
        val before = stub.process().instructions().map { it.opcode }
        assertThrows(PatchException::class.java) { context.askReplaceStrategy() }
        assertEquals(before, context.classDefBy(PROCESS).process().instructions().map { it.opcode })
    }

    @Test
    fun theSpanDrawsThroughTheExtension() {
        val context = PatchContexts.of(listOf(process(), span(), extension(), fonts()))

        context.styleEmoji()

        assertAsks("stand-in", context.mutableClassDefBy(PROCESS).methods.single { it.name == "A04" }, 18)
        assertDraws("stand-in", context.mutableClassDefBy(SPAN).methods.single(), 3, 6)
    }

    @Test
    fun aSpanDrawItCantHookFailsThePatch() {
        for ((case, classes) in listOf(
            "no EmojiCompat get" to listOf(process(gets = 0), span(), extension(), fonts()),
            "two EmojiCompat gets" to listOf(process(gets = 2), span(), extension(), fonts()),
            "two span draws" to listOf(process(), span(), span(type = "Lfixture/OtherSpan;"), extension(), fonts()),
            "a static draw" to listOf(process(), span(static = true), extension(), fonts()),
            "the typeface read twice" to listOf(process(), span(readTwice = true), extension(), fonts()),
            "a jump onto the typeface read" to listOf(process(), span(jumpOnto = true), extension(), fonts()),
            "a branch after the read" to listOf(process(), span(branchAfter = true), extension(), fonts()),
            "the characters drawn before the read" to listOf(process(), span(drawnBefore = true), extension(), fonts()),
            "a try block over the draw" to listOf(process(), span(tried = true), extension(), fonts()),
            "the paint parameter used after the read" to listOf(process(), span(paintAfter = true), extension(), fonts()),
            "this written before the read" to listOf(process(), span(thisWritten = true), extension(), fonts()),
            "no hook in the extension" to listOf(process(), span(), extension()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) {
                PatchContexts.of(classes).findEmojiSpanDraw(PROCESS)
            }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Emoji style:"))
        }
    }

    /** The draw is found before process changes, so a draw the patch can't hook leaves process as it was. */
    @Test
    fun aSpanRefusalChangesNothing() {
        val stub = process()
        val context = PatchContexts.of(listOf(stub, span(drawnBefore = true), extension(), fonts()))
        val before = stub.process().instructions().map { it.opcode }
        assertThrows(PatchException::class.java) { context.styleEmoji() }
        assertEquals(before, context.classDefBy(PROCESS).process().instructions().map { it.opcode })
    }

    /** In each declared build EmojiCompat's process is found, its strategy is its last number, and it asks first. */
    @Test
    fun eachDeclaredBuildAsksFirst() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildAsksFirstToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = FixtureDex.classesHolding(bundle, RANGE_CHECK)
        // The span classes: those with a draw shaped like ReplacementSpan's, in a dex that draws characters.
        val draws = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == DRAW_CHARACTERS } }) {
            it.name == "draw" && it.parameterTypes.map(Any::toString) == SPAN_DRAW
        }
        val spans = FixtureDex.classes(bundle, draws.map { it.definingClass }.toSet()).values
        val context = PatchContexts.of((holders + spans).distinctBy { it.type } + extension() + fonts())

        val found = context.findEmojiProcess()
        assertEquals("${label}: the strategy", "p4", found.strategy)
        val type = found.method.definingClass
        val name = found.method.name
        val register = found.method.implementation!!.registerCount - 1
        val first = found.method.instructions().first().opcode
        val draw = context.findEmojiSpanDraw(type)
        val span = draw.method.definingClass
        val read = draw.method.instructions()[draw.at].opcode
        context.styleEmoji()

        val method = context.mutableClassDefBy(type).methods.single { it.name == name && it.holds(RANGE_CHECK) }
        assertAsks("${label} $type->$name", method, register)
        assertEquals("${label}: process's own start follows", first, method.instructions()[2].opcode)
        val drawn = context.mutableClassDefBy(span).methods.single {
            it.name == "draw" && it.parameterTypes.map(Any::toString) == SPAN_DRAW
        }
        assertDraws("${label} $span->draw", drawn, draw.at, draw.paint)
        assertEquals("${label}: the typeface read follows", read, drawn.instructions()[draw.at + 5].opcode)
    }

    /** The hook first, on [register] alone, and its answer written back to [register]. */
    private fun assertAsks(what: String, method: Method, register: Int) {
        val code = method.instructions()
        val hooks = code.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == REPLACE_STRATEGY }
        assertEquals("$what: hooks", listOf(0), hooks.map { it.index })
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        val asked = code[0] as RegisterRangeInstruction
        assertEquals("$what: the strategy asked about", register, asked.startRegister)
        assertEquals("$what: the strategy alone", 1, asked.registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer replaces the strategy", register, (code[1] as OneRegisterInstruction).registerA)
    }

    /**
     * At [at], the paint in [paint] goes to the paint parameter, the span and all ten registers from
     * `this` go to the hook, its answer goes back to the paint parameter, and draw returns on yes or
     * goes on to its typeface read on no.
     */
    private fun assertDraws(what: String, method: Method, at: Int, paint: Int) {
        val code = method.instructions()
        val hooks = code.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == SPAN_DRAWN }
        assertEquals("$what: hooks", listOf(at + 1), hooks.map { it.index })
        val self = method.implementation!!.registerCount - SPAN_DRAW.size - 1
        val last = self + SPAN_DRAW.size
        assertEquals("$what: the paint taken", Opcode.MOVE_OBJECT_FROM16, code[at].opcode)
        assertEquals("$what: the paint drawn with, into the paint parameter", listOf(last, paint), code[at].namedRegisters())
        assertEquals("$what: the hook", Opcode.INVOKE_STATIC_RANGE, code[at + 1].opcode)
        assertEquals("$what: this and every parameter", (self..last).toList(), code[at + 1].namedRegisters())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at + 2].opcode)
        assertEquals("$what: the answer in the paint parameter", last, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[at + 3].opcode)
        assertEquals("$what: the answer tested", last, (code[at + 3] as OneRegisterInstruction).registerA)
        // Falling through reaches the return at at + 4, so the branch is what lands on at + 5.
        assertEquals("$what: no goes on to the typeface read", setOf(at + 4, at + 5), ControlFlow.of(method).normal[at + 3].toSet())
        assertEquals("$what: yes returns", Opcode.RETURN_VOID, code[at + 4].opcode)
        val read = (code[at + 5] as ReferenceInstruction).reference.toString()
        assertTrue("$what: the typeface read follows, not $read", read.endsWith(";->getTypeface()Landroid/graphics/Typeface;"))
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.holds(value: String) = instructions().any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    }

    private fun ClassDef.process(): Method = methods.single { it.name == "A04" }

    private companion object {
        const val PROCESS = "Lfixture/EmojiCompat;"
        const val SPAN = "Lfixture/EmojiSpan;"
        const val TYPEFACE = "Landroid/graphics/Paint;->getTypeface()Landroid/graphics/Typeface;"

        fun extension(): ClassDef = ExtensionDex.classDef(EMOJI_STYLE)

        fun fonts(): ClassDef = ExtensionDex.classDef(EMOJI_FONTS)

        /**
         * An instance (CharSequence, int, int, int)CharSequence shaped like 450's 00Zx.A04: it checks
         * the start and the end for being negative and the range against the text, copies the
         * strategy and compares it with replace-all. Nineteen registers put the strategy, p4, in v18.
         * Each flag breaks one part of that. Its class also has [gets] static methods answering it,
         * as EmojiCompat's get does.
         */
        fun process(
            type: String = PROCESS,
            static: Boolean = false,
            counted: Boolean = false,
            startChecked: Boolean = true,
            compared: Boolean = true,
            loop: Boolean = false,
            gets: Int = 1,
        ): ClassDef {
            val parameters = listOf("Ljava/lang/CharSequence;", "I", "I") + (if (counted) listOf("I") else emptyList()) + "I"
            // A static process has no this, so its parameters start at p0.
            fun p(index: Int) = "p${index + if (static) 0 else 1}"
            val body = """
                :start
                const-string v0, "start cannot be negative"
                ${if (startChecked) "if-ltz ${p(1)}, :bad" else ""}
                const-string v0, "end cannot be negative"
                if-ltz ${p(2)}, :bad
                ${if (counted) "if-ltz ${p(3)}, :bad" else ""}
                const-string v0, "$RANGE_CHECK"
                const-string v0, "$LENGTH_CHECK"
                move/from16 v1, ${p(parameters.size - 1)}
                const/4 v0, 0x1
                ${if (compared) "if-eq v1, v0, :all" else "if-lez v1, :all"}
                :all
                ${if (loop) "if-nez v1, :start" else ""}
                return-object ${p(0)}
                :bad
                new-instance v0, Ljava/lang/IllegalArgumentException;
                invoke-direct { v0 }, Ljava/lang/IllegalArgumentException;-><init>()V
                throw v0
            """
            val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            val registers = 14 + (if (static) 0 else 1) + parameters.size
            val method = ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, "A04", parameters.map { ImmutableMethodParameter(it, null, null) }, "Ljava/lang/CharSequence;",
                        flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent()) },
            )
            val getters = (0 until gets).map { number ->
                ImmutableMethod.of(
                    MutableMethod(
                        ImmutableMethod(
                            type, "A0$number", emptyList(), type, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                            null, null, ImmutableMethodImplementation(1, emptyList(), null, null),
                        ),
                    ).apply { addInstructions(0, "const/4 v0, 0x0\nreturn-object v0") },
                )
            }
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
                null, null, null, null, listOf(method) + getters)
        }

        /**
         * A span's draw shaped like 453's 00ae.draw with the indicator check and the rasterizer
         * inlined: it asks EmojiCompat's get, copies its paint (p9, v17) to v6, reads its typeface
         * at 3, draws two characters with it and puts the typeface back. Eight locals put this in
         * v8. Each flag breaks one part of that.
         */
        fun span(
            type: String = SPAN,
            static: Boolean = false,
            readTwice: Boolean = false,
            jumpOnto: Boolean = false,
            branchAfter: Boolean = false,
            drawnBefore: Boolean = false,
            tried: Boolean = false,
            paintAfter: Boolean = false,
            thisWritten: Boolean = false,
        ): ClassDef {
            val characters = """
                move-object/from16 v0, v9
                const/4 v2, 0x2
                new-array v1, v2, [C
                const/4 v2, 0x0
                const/4 v3, 0x2
                move/from16 v4, v13
                move/from16 v5, v15
                int-to-float v5, v5
                invoke-virtual/range { v0 .. v6 }, $DRAW_CHARACTERS
            """
            val body = """
                invoke-static { }, $PROCESS->A00()$PROCESS
                move-result-object v7
                move-object/from16 v6, v17
                ${if (thisWritten) "const/4 v8, 0x0" else ""}
                ${if (jumpOnto) "if-eqz v7, :read" else ""}
                ${if (drawnBefore) characters else ""}
                :read
                invoke-virtual { v6 }, $TYPEFACE
                move-result-object v7
                ${if (readTwice) "invoke-virtual { v6 }, $TYPEFACE" else ""}
                ${if (branchAfter) "if-eqz v7, :done" else ""}
                ${if (drawnBefore) "" else characters}
                ${if (paintAfter) "move-object/from16 v7, v17" else ""}
                invoke-virtual { v6, v7 }, Landroid/graphics/Paint;->setTypeface(Landroid/graphics/Typeface;)Landroid/graphics/Typeface;
                :done
                return-void
            """
            val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            val written = MutableMethod(
                ImmutableMethod(
                    type, "draw", SPAN_DRAW.map { ImmutableMethodParameter(it, null, null) }, "V",
                    flags, null, null, ImmutableMethodImplementation(18, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body.lines().joinToString("\n") { it.trim() }.trim()) }
            var method = ImmutableMethod.of(written)
            if (tried) {
                // The typeface read and everything after it in a try block whose handler returns.
                val code = method.implementation!!.instructions.toList() + ImmutableInstruction10x(Opcode.RETURN_VOID)
                val read = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == TYPEFACE }
                val start = code.take(read).sumOf { it.codeUnits }
                val handler = code.dropLast(1).sumOf { it.codeUnits }
                method = ImmutableMethod(type, "draw", method.parameters, "V", flags, null, null,
                    ImmutableMethodImplementation(18, code, listOf(ImmutableTryBlock(start, handler - start,
                        listOf(ImmutableExceptionHandler("Ljava/lang/RuntimeException;", handler)))), null))
            }
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Landroid/text/style/ReplacementSpan;",
                null, null, null, null, listOf(method))
        }
    }
}
