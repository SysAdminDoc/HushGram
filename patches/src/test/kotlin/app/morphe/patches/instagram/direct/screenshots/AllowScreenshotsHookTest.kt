/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.screenshots

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Format
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Allow screenshots: every window flag call in Instagram goes through the extension, and Instagram's
 * secure window helper asks it first before it marks a window. Anything the patch can't tell apart
 * fails it before an instruction changes.
 */
class AllowScreenshotsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(SCREENSHOT_BLOCK).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in WINDOW_FLAG_STAND_INS.values + LIFT_BLOCK) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun theHelperAsksFirstAndEveryFlagCallMoves() {
        val context = PatchContexts.of(listOf(helper(), caller(), extensionCaller()))
        val secure = context.findSecureWindow()
        assertEquals("the helper's mark and the caller's three", 4, context.routeWindowFlags())
        liftScreenshotBlock(secure)
        assertHooked("stand-in", context, HELPER)
        val clear = context.mutableClassDefBy(HELPER).methods.single { it.name == "clear" }
        assertEquals("the clearing is left alone", "Landroid/view/Window;->clearFlags(I)V", clear.instructions().calls().single())

        val moved = context.mutableClassDefBy(CALLER).methods.single().instructions()
        assertEquals(WINDOW_FLAG_STAND_INS.values.toList() + WINDOW_FLAG_STAND_INS.getValue(SET_WINDOW_FLAGS), moved.calls())
        assertEquals("the window comes first", listOf(1, 0, 0), (moved[0] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE) })
        assertEquals(listOf(1, 0), (moved[1] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals(Opcode.INVOKE_STATIC_RANGE, moved[2].opcode)
        assertEquals(listOf(16, 3), (moved[2] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        val own = context.mutableClassDefBy(EXTENSION_CALLER).methods.single().instructions()
        assertEquals("the extension's own call stays", listOf(SET_WINDOW_FLAGS), own.calls())
    }

    @Test
    fun aMissingOrDoubledMarkFailsThePatch() {
        refuses("found none", listOf(helper(mark = false), caller()))
        refuses("Lfixture/OtherHelper;->mark", listOf(helper(), helper("Lfixture/OtherHelper;"), caller()))
        refuses("found none", listOf(helper(static = true), caller()))
    }

    @Test
    fun aMarkWithoutALocalOrWithAJumpBackFailsThePatch() {
        refuses("needs 1", listOf(helper(registers = 3), caller()))
        refuses("jumps back", listOf(helper(loop = true), caller()))
    }

    @Test
    fun noFlagCallToMoveFailsThePatch() {
        val context = PatchContexts.of(listOf(extensionCaller()))
        val refusal = assertThrows(PatchException::class.java) { context.routeWindowFlags() }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("found no Window.setFlags"))
    }

    /** A call in a form the patch can't move fails it before any call has moved, wherever it is. */
    @Test
    fun aCallItCantMoveFailsThePatchBeforeAnyMoves() {
        val context = PatchContexts.of(listOf(helper(), caller(), oddCaller()))
        val screen = caller().methods.single().instructions().map(::text)
        val refusal = assertThrows(PatchException::class.java) { context.routeWindowFlags() }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("$ODD_CALLER->show calls window flags in a form it can't move"))
        assertEquals("a call moved", screen, context.mutableClassDefBy(CALLER).methods.single().instructions().map(::text))
        val mark = context.mutableClassDefBy(HELPER).methods.single { it.name == "mark" }
        assertEquals("the helper's mark moved", listOf(SET_WINDOW_FLAGS), mark.instructions().calls())
    }

    /**
     * In each declared build: the helper's one mark asks first, and every window flag call outside
     * the extension, wherever it is, moves to a stand-in.
     */
    @Test
    fun eachDeclaredBuildMovesEveryFlagCall() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == "setFlags" || it == "addFlags" } }) {
                    it.windowFlagCalls().isNotEmpty()
                }
                val marks = callers.filter {
                    it.returnType == "V" && it.setsSecureFlag() && !AccessFlags.STATIC.isSet(it.accessFlags) &&
                        it.parameterTypes.map(CharSequence::toString) == SHAPE
                }
                assertEquals("${bundle.name}: ${marks.map { "${it.definingClass}->${it.name}" }}", 1, marks.size)
                val types = callers.map { it.definingClass }.toSet()
                val context = PatchContexts.of(FixtureDex.classes(bundle, types).values.toList())
                val secure = context.findSecureWindow()
                assertEquals("${bundle.name}: every call moves", callers.sumOf { it.windowFlagCalls().size }, context.routeWindowFlags())
                liftScreenshotBlock(secure)
                assertHooked(bundle.name, context, secure.definingClass)
                for (type in types) {
                    for (method in context.mutableClassDefBy(type).methods) {
                        assertEquals("${bundle.name}: $type->${method.name} still calls window flags", emptyList<Int>(), method.windowFlagCalls())
                    }
                }
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) {
            val secure = context.findSecureWindow()
            context.routeWindowFlags()
            liftScreenshotBlock(secure)
        }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    private fun assertHooked(what: String, context: BytecodePatchContext, helper: String) {
        val secure = context.mutableClassDefBy(helper).methods.single {
            it.parameterTypes.map(CharSequence::toString) == SHAPE && !AccessFlags.STATIC.isSet(it.accessFlags) &&
                WINDOW_FLAG_STAND_INS.getValue(SET_WINDOW_FLAGS) in it.instructions().calls()
        }
        val code = secure.instructions()
        assertEquals("$what: the call", LIFT_BLOCK, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the branch's target", 4, (secure.implementation!!.instructions.toList()[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: the early return", Opcode.RETURN_VOID, code[3].opcode)
        val calls = context.mutableClassDefBy(helper).methods.sumOf { method -> method.instructions().calls().count { it == LIFT_BLOCK } }
        assertEquals("$what: lift calls in the helper", 1, calls)
    }

    private fun List<Instruction>.calls() = mapNotNull { (it as? ReferenceInstruction)?.reference?.toString()?.takeIf { ref -> "->" in ref && "(" in ref } }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name
        else -> "${instruction.opcode.name} $reference"
    }

    private companion object {
        const val HELPER = "Lfixture/SecureWindows;"
        const val CALLER = "Lfixture/Screen;"
        const val EXTENSION_CALLER = "Lapp/hushgram/extension/instagram/direct/ScreenshotBlock;"
        const val ODD_CALLER = "Lfixture/OddScreen;"
        val SHAPE = listOf("Landroid/view/Window;", "Ljava/lang/String;")

        fun windowCall(name: String, registers: List<Int>) = ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, registers.size, registers[0], registers.getOrElse(1) { 0 }, registers.getOrElse(2) { 0 }, 0, 0,
            ImmutableMethodReference("Landroid/view/Window;", name, List(registers.size - 1) { "I" }, "V"),
        )

        /** Puts FLAG_SECURE in v0 and hands it to [flags] on the window, after a nop or a jump back to the start. */
        fun code(registers: Int, flags: String, loop: Boolean) = ImmutableMethodImplementation(
            registers,
            listOf(
                ImmutableInstruction21s(Opcode.CONST_16, 0, FLAG_SECURE.toInt()),
                if (loop) ImmutableInstruction10t(Opcode.GOTO, -2) else ImmutableInstruction10x(Opcode.NOP),
                if (flags == "setFlags") windowCall(flags, listOf(registers - 2, 0, 0)) else windowCall(flags, listOf(registers - 2, 0)),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
            null, null,
        )

        /** Shaped like Instagram's helper: a (window, reason) mark using setFlags and a clear using clearFlags. */
        fun helper(type: String = HELPER, mark: Boolean = true, static: Boolean = false, registers: Int = 5, loop: Boolean = false): ClassDef {
            val access = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            fun method(name: String, flags: String, regs: Int, jump: Boolean, isStatic: Boolean) = ImmutableMethod(
                type, name, SHAPE.map { ImmutableMethodParameter(it, null, null) }, "V",
                access or (if (isStatic) AccessFlags.STATIC.value else 0), null, null, code(regs, flags, jump),
            )
            val methods = mutableListOf(method("clear", "clearFlags", 5, false, false))
            if (mark) methods += method("mark", "setFlags", if (static) registers - 1 else registers, loop, static)
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
        }

        /** A screen setting window flags three ways: setFlags, addFlags and a ranged setFlags past v15. */
        fun caller(): ClassDef {
            val code = ImmutableMethodImplementation(
                20,
                listOf(
                    windowCall("setFlags", listOf(1, 0, 0)),
                    windowCall("addFlags", listOf(1, 0)),
                    ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 16, 3, ImmutableMethodReference("Landroid/view/Window;", "setFlags", listOf("I", "I"), "V")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                null, null,
            )
            val show = ImmutableMethod(CALLER, "show", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, code)
            return ImmutableClassDef(CALLER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(show))
        }

        /** A setFlags call that's neither a five-register nor a range call, which has no stand-in form. */
        class OddCall : ImmutableInstruction(Opcode.INVOKE_VIRTUAL), ReferenceInstruction {
            override fun getFormat(): Format = Format.Format35c
            override fun getReference(): Reference = ImmutableMethodReference("Landroid/view/Window;", "setFlags", listOf("I", "I"), "V")
            override fun getReferenceType(): Int = ReferenceType.METHOD
        }

        /** A screen whose one window flag call is an [OddCall]. */
        fun oddCaller(): ClassDef {
            val code = ImmutableMethodImplementation(3, listOf(OddCall(), ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
            val show = ImmutableMethod(ODD_CALLER, "show", emptyList(), "V", AccessFlags.PUBLIC.value, null, null, code)
            return ImmutableClassDef(ODD_CALLER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(show))
        }

        /** The extension's own stand-in, which calls the real setFlags and must keep doing so. */
        fun extensionCaller(): ClassDef {
            val code = ImmutableMethodImplementation(3, listOf(windowCall("setFlags", listOf(0, 1, 2)), ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
            val setFlags = ImmutableMethod(
                EXTENSION_CALLER, "setFlags", listOf("Landroid/view/Window;", "I", "I").map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, code,
            )
            return ImmutableClassDef(EXTENSION_CALLER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(setFlags))
        }
    }
}
