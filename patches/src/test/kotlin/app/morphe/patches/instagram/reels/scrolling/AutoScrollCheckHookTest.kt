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
import app.morphe.patches.instagram.reels.autoscroll.SCROLLER_SHOULD_SCROLL_FLAG
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
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
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
    private val state = "Lfixture/ViewerState;"
    private val flag = "0x${SCROLLER_SHOULD_SCROLL_FLAG.toString(16)}"

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

    /**
     * A scroller keeping the viewer's state, whose config is final, hands the extension the way to it
     * (#804), and the bridge reads it off the scroller, answering none while the state isn't there.
     */
    @Test
    fun aScrollerKeepingTheViewersStateGetsTheConfigBridge() {
        val context = PatchContexts.of(listOf(scrollerClass(state = "kept"), stateClass(fixed = true), extension()))
        val found = context.findAutoScrollCheck()
        assertEquals("$scroller->state:$state", found.config?.holder)
        assertEquals("$state->config:$VIEWER_CONFIG", found.config?.config)
        val stock = context.check(found).code()

        context.holdAutoScroll(found)

        assertHeld(context.check(found), found, stock, "fixture")
        assertScrollerBridge("fixture", context, found)
    }

    /**
     * A scroller without one public way to a final config, or one that isn't public, keeps the
     * bridge's stub, so the extension goes by the source. The check still asks with the scroller.
     */
    @Test
    fun aScrollerWithoutOneWayToTheConfigKeepsTheStub() {
        val cases = listOf(
            "no state" to listOf(scrollerClass(), extension()),
            "config set later" to listOf(scrollerClass(state = "kept"), stateClass(fixed = false), extension()),
            "private scroller" to listOf(scrollerClass(state = "kept", publicClass = false), stateClass(fixed = true), extension()),
        )
        for ((case, classes) in cases) {
            val context = PatchContexts.of(classes)
            val found = context.findAutoScrollCheck()
            assertEquals("$case: no way to the config", null, found.config)
            val stock = context.check(found).code()

            context.holdAutoScroll(found)

            assertHeld(context.check(found), found, stock, case)
            assertScrollerBridge(case, context, found)
        }
    }

    /** A check whose scroller sits past v15 copies it into a second local for the call. */
    @Test
    fun aScrollerPastV15IsCopiedForTheCall() {
        val context = PatchContexts.of(listOf(scrollerClass(locals = 17)))
        val found = context.findAutoScrollCheck()
        val stock = context.check(found).code()

        context.holdAutoScroll(found)

        val method = context.check(found)
        assertEquals("the scroller's register", 17, method.implementation!!.registerCount - 1)
        assertHeld(method, found, stock, "past v15")
    }

    /** Anything off about the scroller stops the patch before it writes, saying what. */
    @Test
    fun aScrollerThatIsntWhatTheHookExpectsFails() {
        val one = "expected one method loading the scroll check's flag $flag"
        val shape = "isn't a static method taking its own class and answering a boolean"
        val cases = listOf(
            listOf(scrollerClass(marked = 0)) to "$one, found 0",
            listOf(scrollerClass(marked = 2)) to "$one, found 2",
            listOf(scrollerClass(static = false)) to shape,
            listOf(scrollerClass(returns = "I")) to shape,
            listOf(scrollerClass(takes = listOf(scroller, "Z"))) to shape,
            listOf(scrollerClass(loopsToStart = true)) to "jumps back to its start",
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
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.loadsFlag() }) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        assertEquals("$label: the classes loading $flag", 1, found.size)
        // And the classes of the scroller's fields, one of them the viewer's state holding its config (#804).
        val held = found.values.single().fields
            .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type.startsWith("L") }.map { it.type }.toSet() - found.keys
        val context = PatchContexts.of(
            found.values.toList() + FixtureDex.classes(bundle, held).values.map { ImmutableClassDef.of(it) } + extension(),
        )
        val check = context.findAutoScrollCheck()
        assertEquals("$label: the scroller", found.keys.single(), check.scroller)
        assertTrue("$label: ${check.source}", check.source.startsWith("${check.scroller}->") && check.source.endsWith(":$VIEWER_SOURCE"))
        val config = check.config
        assertTrue("$label: the scroller keeps one public way to the viewer's config", config != null)
        assertTrue("$label: ${config!!.holder}", config.holder.startsWith("${check.scroller}->"))
        assertTrue("$label: ${config.config}", config.config.endsWith(":$VIEWER_CONFIG"))
        val stock = context.check(check).code()

        context.holdAutoScroll(check)

        assertHeld(context.check(check), check, stock, label)
        assertScrollerBridge(label, context, check)
    }

    /**
     * The bridge the extension reads the viewer's config through, off the scroller: the scroller
     * cast, its state, none while the state isn't there, else the state's config. Without a way to
     * the config the stub stays as shipped.
     */
    private fun assertScrollerBridge(label: String, context: BytecodePatchContext, check: AutoScrollCheck) {
        val code = context.mutableClassDefBy(REEL_SCROLLING).methods.single { it.name == SCROLLER_CONFIG }.code()
        val path = check.config
        if (path == null) {
            val stub = ExtensionDex.classDef(REEL_SCROLLING).methods.single { it.name == SCROLLER_CONFIG }.code()
            assertEquals("$label: the stub stays", stub.map { it.opcode }, code.map { it.opcode })
            return
        }
        assertEquals(
            "$label: the bridge",
            listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN_OBJECT, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT),
            code.map { it.opcode },
        )
        assertEquals(
            "$label: the scroller, its state, then the state's config",
            listOf(check.scroller, path.holder, null, null, null, path.config, null), code.map { it.referenceText() },
        )
        val bridge = context.mutableClassDefBy(REEL_SCROLLING).methods.single { it.name == SCROLLER_CONFIG }
        assertEquals("$label: a state goes on to its config", 5, (bridge.implementation!!.instructions[2] as BuilderOffsetInstruction).target.location.index)
        assertTrue("$label: all in the parameter's register", code.all { instruction ->
            when (instruction) {
                is TwoRegisterInstruction -> instruction.registerA == 0 && instruction.registerB == 0
                is OneRegisterInstruction -> instruction.registerA == 0
                else -> true
            }
        })
    }

    private fun extension(): ClassDef = ImmutableClassDef.of(ExtensionDex.classDef(REEL_SCROLLING))

    /** The viewer's state a scroller keeps, holding its config, [fixed] final as on 457's LX/0BwL;->A0a. */
    private fun stateClass(fixed: Boolean): ClassDef = ImmutableClassDef(
        state, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
        listOf(
            ImmutableField(
                state, "config", VIEWER_CONFIG, AccessFlags.PUBLIC.value or (if (fixed) AccessFlags.FINAL.value else 0), null, null, null,
            ),
        ),
        null,
    )

    /**
     * The hook comes first: the scroller from its parameter into v0, a copy of it in v1 when its
     * register is past v15, its source, the call on the source and the scroller, its answer, a jump
     * on non-zero to Instagram's own first instruction, and a false return. Instagram's code follows
     * unchanged.
     */
    private fun assertHeld(method: MutableMethod, check: AutoScrollCheck, stock: List<Instruction>, label: String) {
        val code = method.code()
        val self = method.implementation!!.registerCount - 1
        val copied = if (self > 15) 1 else 0
        val passed = if (copied == 1) 1 else self
        assertEquals("$label: the scroller's register", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals(listOf(0, self), (code[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        if (copied == 1) {
            assertEquals("$label: the scroller's copy", Opcode.MOVE_OBJECT_FROM16, code[1].opcode)
            assertEquals(listOf(1, self), (code[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        }
        val at = copied
        assertEquals("$label: its source", Opcode.IGET_OBJECT, code[at + 1].opcode)
        assertEquals(check.source, code[at + 1].referenceText())
        assertEquals(listOf(0, 0), (code[at + 1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$label: the call", Opcode.INVOKE_STATIC, code[at + 2].opcode)
        assertEquals(AUTO_SCROLL_CHECK_HOOK, code[at + 2].referenceText())
        assertEquals(
            "$label: on the source and the scroller", listOf(0, passed),
            (code[at + 2] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD).take(it.registerCount) },
        )
        assertEquals("$label: its answer", Opcode.MOVE_RESULT, code[at + 3].opcode)
        assertEquals(0, (code[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$label: on non-zero, Instagram's check", Opcode.IF_NEZ, code[at + 4].opcode)
        assertEquals(0, (code[at + 4] as OneRegisterInstruction).registerA)
        assertEquals(at + 7, (method.implementation!!.instructions[at + 4] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: on 0, false", Opcode.CONST_4, code[at + 5].opcode)
        assertEquals(listOf(0, 0), listOf((code[at + 5] as OneRegisterInstruction).registerA, (code[at + 5] as NarrowLiteralInstruction).narrowLiteral))
        assertEquals(Opcode.RETURN, code[at + 6].opcode)
        assertEquals(0, (code[at + 6] as OneRegisterInstruction).registerA)
        assertEquals("$label: Instagram's check after the hook", stock.kinds(), code.drop(at + 7).kinds())
        assertEquals("$label: the first of Instagram's own", stock.first().string(), code[at + 7].string())
        assertEquals("$label: nothing else calls the hook", 1, code.count { it.referenceText() == AUTO_SCROLL_CHECK_HOOK })
    }

    private fun BytecodePatchContext.check(found: AutoScrollCheck): MutableMethod =
        mutableClassDefBy(found.scroller).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == listOf(found.scroller)
        }

    private fun Method.loadsFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == SCROLLER_SHOULD_SCROLL_FLAG
    }

    /**
     * A scroller like Instagram's: a static check loading [SCROLLER_SHOULD_SCROLL_FLAG] that takes the
     * scroller and answers whether to move on, and a constructor keeping the viewer's source. The flag
     * is a wide constant, so the check always has a local to borrow: its only parameter is one register.
     */
    private fun scrollerClass(
        marked: Int = 1,
        static: Boolean = true,
        returns: String = "Z",
        takes: List<String> = listOf(scroller),
        loopsToStart: Boolean = false,
        sources: Int = 1,
        staticSource: Boolean = false,
        stored: Boolean = true,
        namesake: Boolean = false,
        state: String = "none",
        publicClass: Boolean = true,
        locals: Int = 2,
    ): ClassDef {
        val methods = mutableListOf<Method>()
        val body = when {
            loopsToStart -> """
                :start
                const-wide v0, ${flag}L
                iget-boolean v1, p0, $scroller->moving:Z
                if-eqz v1, :start
                return v1
            """
            // A scroller past v15 is read through a copy, as a field read names only v0 to v15.
            locals > 15 -> """
                const-wide v0, ${flag}L
                move-object/from16 v2, p0
                iget-boolean v1, v2, $scroller->moving:Z
                return v1
            """
            else -> """
                const-wide v0, ${flag}L
                iget-boolean v1, p0, $scroller->moving:Z
                return v1
            """
        }
        val markedBody = if (marked == 0) body.replace("${flag}L", "0x810d8c00a24cbaL") else body
        methods += method("shouldScroll", takes, returns, locals, static, markedBody)
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
        if (state == "kept") fields += ImmutableField(scroller, "state", this.state, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
        val flags = if (publicClass) AccessFlags.PUBLIC.value or AccessFlags.FINAL.value else AccessFlags.FINAL.value
        return ImmutableClassDef(scroller, flags, "Ljava/lang/Object;", null, null, null, fields, methods)
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
