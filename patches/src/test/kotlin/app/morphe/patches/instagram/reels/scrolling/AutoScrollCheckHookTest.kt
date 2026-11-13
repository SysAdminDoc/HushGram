/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.scrolling

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.reels.autoscroll.SCROLLER_SHOULD_SCROLL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Play only the reel you opened (#656) answers first in the Reels auto scroller's check of whether
 * to move on, with the source the scroller keeps, and the check runs as Instagram wrote it on a 1.
 */
class AutoScrollCheckHookTest {
    private val scroller = "Lfixture/Scroller;"
    private val marker = "android_purge_26_q2_$SCROLLER_SHOULD_SCROLL"

    /** The hook the patch writes is in the ReelScrolling the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_SCROLLING).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$AUTO_SCROLL_CHECK_HOOK is not in the extension: $declared", AUTO_SCROLL_CHECK_HOOK.substringAfter("->") in declared)
    }

    /** The check asks the extension first, with the source its scroller keeps, and returns false on a 0. */
    @Test
    fun theCheckAsksFirstWithTheScrollersSource() {
        val context = PatchContexts.of(listOf(scrollerClass()))
        val found = context.findAutoScrollCheck()
        assertEquals(scroller, found.scroller)
        assertEquals("shouldScroll", found.name)
        assertEquals("$scroller->source:$VIEWER_SOURCE", found.source)
        val stock = context.check(found).code()

        context.holdAutoScroll(found)

        assertHeld(context.check(found), found, stock, "fixture")
    }

    /** Anything off about the scroller stops the patch before it writes, saying what. */
    @Test
    fun aScrollerThatIsntWhatTheHookExpectsFails() {
        val one = "expected one method marked $SCROLLER_SHOULD_SCROLL"
        val shape = "isn't a static method taking its own class and answering a boolean"
        val cases = listOf(
            listOf(scrollerClass(marked = 0)) to "$one, found 0",
            listOf(scrollerClass(marked = 2)) to "$one, found 2",
            listOf(scrollerClass(static = false)) to shape,
            listOf(scrollerClass(returns = "I")) to shape,
            listOf(scrollerClass(takes = listOf(scroller, "Z"))) to shape,
            listOf(scrollerClass(loopsToStart = true)) to "jumps back to its start",
            listOf(scrollerClass(noLocals = true)) to "has no local register to borrow",
            listOf(scrollerClass(sources = 0)) to "expected one $VIEWER_SOURCE field in $scroller, found 0",
            listOf(scrollerClass(sources = 2)) to "expected one $VIEWER_SOURCE field in $scroller, found 2",
            listOf(scrollerClass(staticSource = true)) to "expected one $VIEWER_SOURCE field in $scroller, found 0",
            listOf(scrollerClass(stored = false)) to "no constructor of $scroller keeps the viewer's source",
            listOf(scrollerClass(namesake = true)) to "has 2 methods named shouldScroll taking only itself",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findAutoScrollCheck() }
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("Stop Reels scrolling: ") && failure.message!!.contains(expected))
            for (method in context.mutableClassDefBy(scroller).methods) {
                assertTrue("$expected: ${method.name} was written", method.code().none { it.referenceText() == AUTO_SCROLL_CHECK_HOOK })
            }
        }
    }

    /** On the declared build the hook goes first in the scroller's check, reading the source its constructor keeps. */
    @Test
    fun eachDeclaredBuildHoldsItsAutoScroll() {
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
    fun eachOtherBuildHoldsItsAutoScrollToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val found = mutableMapOf<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.stringSection.none { it.endsWith("_$SCROLLER_SHOULD_SCROLL") }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { SCROLLER_SHOULD_SCROLL in it.markers() }) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        assertEquals("$label: the classes holding $SCROLLER_SHOULD_SCROLL", 1, found.size)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, found.values))
        val check = context.findAutoScrollCheck()
        assertEquals("$label: the scroller", found.keys.single(), check.scroller)
        assertTrue("$label: ${check.source}", check.source.startsWith("${check.scroller}->") && check.source.endsWith(":$VIEWER_SOURCE"))
        val stock = context.check(check).code()

        context.holdAutoScroll(check)

        assertHeld(context.check(check), check, stock, label)
    }

    /**
     * The seven instructions of the hook come first: the scroller from its parameter into v0, its
     * source, the call, its answer, a jump on non-zero to Instagram's own first instruction, and a
     * false return. Instagram's code follows unchanged.
     */
    private fun assertHeld(method: MutableMethod, check: AutoScrollCheck, stock: List<Instruction>, label: String) {
        val code = method.code()
        val self = method.implementation!!.registerCount - 1
        assertEquals("$label: the scroller's register", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals(listOf(0, self), (code[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$label: its source", Opcode.IGET_OBJECT, code[1].opcode)
        assertEquals(check.source, code[1].referenceText())
        assertEquals(listOf(0, 0), (code[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$label: the call", Opcode.INVOKE_STATIC, code[2].opcode)
        assertEquals(AUTO_SCROLL_CHECK_HOOK, code[2].referenceText())
        assertEquals(listOf(0), (code[2] as FiveRegisterInstruction).let { listOf(it.registerC).take(it.registerCount) })
        assertEquals("$label: its answer", Opcode.MOVE_RESULT, code[3].opcode)
        assertEquals(0, (code[3] as OneRegisterInstruction).registerA)
        assertEquals("$label: on non-zero, Instagram's check", Opcode.IF_NEZ, code[4].opcode)
        assertEquals(0, (code[4] as OneRegisterInstruction).registerA)
        assertEquals(7, (method.implementation!!.instructions[4] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: on 0, false", Opcode.CONST_4, code[5].opcode)
        assertEquals(listOf(0, 0), listOf((code[5] as OneRegisterInstruction).registerA, (code[5] as NarrowLiteralInstruction).narrowLiteral))
        assertEquals(Opcode.RETURN, code[6].opcode)
        assertEquals(0, (code[6] as OneRegisterInstruction).registerA)
        assertEquals("$label: Instagram's check after the hook", stock.kinds(), code.drop(7).kinds())
        assertEquals("$label: the first of Instagram's own", stock.first().string(), code[7].string())
        assertEquals("$label: nothing else calls the hook", 1, code.count { it.referenceText() == AUTO_SCROLL_CHECK_HOOK })
    }

    private fun BytecodePatchContext.check(found: AutoScrollCheck): MutableMethod =
        mutableClassDefBy(found.scroller).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == listOf(found.scroller)
        }

    /**
     * A scroller like Instagram's: a static check marked [SCROLLER_SHOULD_SCROLL] that takes the
     * scroller and answers whether to move on, and a constructor keeping the viewer's source.
     */
    private fun scrollerClass(
        marked: Int = 1,
        static: Boolean = true,
        returns: String = "Z",
        takes: List<String> = listOf(scroller),
        loopsToStart: Boolean = false,
        noLocals: Boolean = false,
        sources: Int = 1,
        staticSource: Boolean = false,
        stored: Boolean = true,
        namesake: Boolean = false,
    ): ClassDef {
        val methods = mutableListOf<Method>()
        val body = when {
            noLocals -> """
                const-string p0, "$marker"
                const/4 p0, 0x0
                return p0
            """
            loopsToStart -> """
                :start
                const-string v0, "$marker"
                iget-boolean v1, p0, $scroller->moving:Z
                if-eqz v1, :start
                return v1
            """
            else -> """
                const-string v0, "$marker"
                iget-boolean v1, p0, $scroller->moving:Z
                return v1
            """
        }
        val markedBody = if (marked == 0) body.replace(marker, "not a marker") else body
        methods += method("shouldScroll", takes, returns, if (noLocals) 0 else 2, static, markedBody)
        if (marked == 2) methods += method("shouldScrollToo", listOf(scroller), "Z", 2, true, body)
        if (namesake) methods += method("shouldScroll", listOf(scroller), "V", 0, true, "return-void")
        methods += method("<init>", listOf(VIEWER_SOURCE), "V", 0, false, """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            ${if (stored) "iput-object p1, p0, $scroller->source:$VIEWER_SOURCE" else ""}
            return-void
        """)
        val fields = mutableListOf(ImmutableField(scroller, "moving", "Z", AccessFlags.PUBLIC.value, null, null, null))
        val sourceFlags = AccessFlags.PUBLIC.value or (if (staticSource) AccessFlags.STATIC.value else 0)
        if (sources >= 1) fields += ImmutableField(scroller, "source", VIEWER_SOURCE, sourceFlags, null, null, null)
        if (sources >= 2) fields += ImmutableField(scroller, "other", VIEWER_SOURCE, sourceFlags, null, null, null)
        return ImmutableClassDef(scroller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)
    }

    private fun method(name: String, parameters: List<String>, returns: String, registers: Int, static: Boolean, body: String): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        if (name == "<init>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val mutable = MutableMethod(
            ImmutableMethod(
                scroller, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    /** Opcodes with NOPs left out and every goto read as one kind, since an insert can widen a goto. */
    private fun List<Instruction>.kinds(): List<Opcode> = filter { it.opcode != Opcode.NOP }.map {
        when (it.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> it.opcode
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? =
        ((this as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string
}
