/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
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
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the Messages badge (#734): the hook goes where the Messages tab's badge controller keeps the
 * unread count, ahead of its own test of it, and takes the write's label so the path that reads the
 * count from the badge store goes through it too.
 */
class MessagesBadgeHookTest {
    private val extensionType = BADGE_COUNT.substringBefore("->")
    private val parameters = listOf("I", "Ljava/lang/Long;")

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(extensionType).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$BADGE_COUNT is not in the extension: $declared", BADGE_COUNT.substringAfter("->") in declared)
    }

    /**
     * On a controller shaped like 456's the count's write is found, the hook goes in front of it,
     * and the goto that brings the stored count back to the write lands on the hook.
     */
    @Test
    fun theHookTakesTheWritesLabel() {
        val context = PatchContexts.of(listOf(button(), controller(STOCK), ExtensionDex.classDef(extensionType)))
        val hook = context.findMessagesBadge()
        assertEquals(CONTROLLER, hook.controller.definingClass)
        assertEquals("the write", 4, hook.store)
        assertEquals("the count's register", 1, hook.count)

        context.hideMessagesBadge(hook)

        val patched = context.mutableClassDefBy(CONTROLLER).methods.single { it.name == "A03" }
        val code = patched.code()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[4].opcode)
        assertEquals(BADGE_COUNT, code[4].referenceText())
        assertEquals(listOf(1, 1), (code[4] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals(Opcode.MOVE_RESULT, code[5].opcode)
        assertEquals(1, (code[5] as OneRegisterInstruction).registerA)
        assertEquals(listOf(Opcode.IPUT, Opcode.IF_LEZ), listOf(code[6].opcode, code[7].opcode))
        val back = patched.implementation!!.instructions.single { it.opcode == Opcode.GOTO } as BuilderOffsetInstruction
        assertEquals("the stored count's way back goes through the hook", 4, back.target.location.index)
        val read = patched.implementation!!.instructions.single { it.opcode == Opcode.IF_NEZ } as BuilderOffsetInstruction
        assertEquals(Opcode.CONST_4, code[read.target.location.index].opcode)
        assertEquals(setOf(4, 11, 13), ImmutableMethod.of(patched).jumpTargets())
    }

    /** Anything off the shape fails before anything is written, saying what it found. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val none = PatchContexts.of(listOf(ExtensionDex.classDef(extensionType)))
        val missing = assertThrows(PatchException::class.java) { none.findMessagesBadge() }
        assertTrue(missing.message, missing.message!!.contains("expected one tab button method making $BADGE_UPDATE from a count, found 0"))

        val uncalled = PatchContexts.of(listOf(button(), ExtensionDex.classDef(extensionType)))
        val noCall = assertThrows(PatchException::class.java) { uncalled.findMessagesBadge() }
        assertTrue(noCall.message, noCall.message!!.contains("found 0"))

        val jumpedPast = STOCK.replace("goto :store", "goto :test").replace("if-lez v1, :hide", ":test\nif-lez v1, :hide")
        val jumped = PatchContexts.of(listOf(button(), controller(jumpedPast), ExtensionDex.classDef(extensionType)))
        val past = assertThrows(PatchException::class.java) { jumped.findMessagesBadge() }
        assertTrue(past.message, past.message!!.contains("something jumps to the test of the badge count"))

        val elsewhere = PatchContexts.of(listOf(button(), controller(STOCK.replace("iput v1, p0,", "iput v1, v3,")), ExtensionDex.classDef(extensionType)))
        val other = assertThrows(PatchException::class.java) { elsewhere.findMessagesBadge() }
        assertTrue(other.message, other.message!!.contains("doesn't keep the count in v1 in its own int field"))

        val untested = PatchContexts.of(listOf(button(), controller(STOCK.replace("if-lez v1, :hide", "if-eqz v1, :hide")), ExtensionDex.classDef(extensionType)))
        val noTest = assertThrows(PatchException::class.java) { untested.findMessagesBadge() }
        assertTrue(noTest.message, noTest.message!!.contains("expected one test of the count in v1"))
    }

    /**
     * In each declared build and the other ABIs the tab button's count update has one caller, the
     * Messages tab's badge controller, the hook goes in front of its write of the count, every jump
     * keeps its place (the one to the write now lands on the hook) and the controller's own code is
     * all still there around it.
     */
    @Test
    fun everyBuildHandsTheMessagesCountToTheExtension() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the others", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val name = bundle.parentFile?.name ?: bundle.name
            val buttons = FixtureDex.classesCreating(bundle, setOf(BADGE_UPDATE))
            val updates = buttons.flatMap { it.methods }.filter { method ->
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                    method.parameterTypes.map(CharSequence::toString) == parameters &&
                    method.code().any { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as TypeReference).type == BADGE_UPDATE }
            }
            assertEquals("$name: the tab button's count update", 1, updates.size)
            val update = updates.single()
            // 457's tab button calls it itself too, showing again the count it was handed last.
            val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == update.definingClass } }) { method ->
                method.definingClass != update.definingClass && method.code().any { it.calls(update) }
            }
            assertEquals("$name: callers of the count update outside its class, in the whole app", 1, callers.size)
            val controllerType = callers.single().definingClass
            val controllerClass = FixtureDex.classes(bundle, setOf(controllerType)).values.single()
            val context = PatchContexts.of((buttons + controllerClass + ExtensionDex.classDef(extensionType)).distinctBy { it.type })

            val hook = context.findMessagesBadge()
            assertEquals("$name: the controller", controllerType, hook.controller.definingClass)
            val stock = hook.controller.code()
            val write = stock[hook.store]
            assertEquals("$name: the write", Opcode.IPUT, write.opcode)
            assertEquals("$name: the write keeps the count", hook.count, (write as TwoRegisterInstruction).registerA)
            assertEquals("$name: on the controller itself", hook.controller.localRegisterCount(), write.registerB)
            assertEquals("$name: the test", Opcode.IF_LEZ, stock[hook.store + 1].opcode)
            val call = stock.withIndex().single { it.value.calls(update) }
            val argument = when (val invoke = call.value) {
                is FiveRegisterInstruction -> invoke.registerD
                else -> (invoke as RegisterRangeInstruction).startRegister + 1
            }
            assertEquals("$name: the update gets the count", hook.count, argument)
            assertTrue("$name: the update comes after the test", call.index > hook.store + 1)
            val stockTargets = hook.controller.jumpTargets()
            assertTrue("$name: the stored count's path jumps to the write", hook.store in stockTargets)

            context.hideMessagesBadge(hook)

            val patched = context.mutableClassDefBy(controllerType).methods.single {
                it.name == hook.controller.name &&
                    it.parameterTypes.map(CharSequence::toString) == hook.controller.parameterTypes.map(CharSequence::toString)
            }
            val code = patched.code()
            assertEquals("$name: the hook", BADGE_COUNT, code[hook.store].referenceText())
            assertEquals("$name: the hook's call", Opcode.INVOKE_STATIC_RANGE, code[hook.store].opcode)
            assertEquals(
                "$name: hands over the count", listOf(hook.count, 1),
                (code[hook.store] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) },
            )
            assertEquals("$name: the answer", Opcode.MOVE_RESULT, code[hook.store + 1].opcode)
            assertEquals("$name: the answer's register", hook.count, (code[hook.store + 1] as OneRegisterInstruction).registerA)
            assertTrue("$name: move-result reaches the count's register", hook.count <= 255)
            assertEquals("$name: the write follows", Opcode.IPUT, code[hook.store + 2].opcode)
            assertEquals("$name: then the test", Opcode.IF_LEZ, code[hook.store + 3].opcode)
            assertEquals(
                "$name: every jump keeps its place, the write's now landing on the hook",
                stockTargets.map { if (it > hook.store) it + 2 else it }.toSet(),
                ImmutableMethod.of(patched).jumpTargets(),
            )
            assertEquals(
                "$name: the controller's own code",
                stock.map { it.describe() },
                (code.take(hook.store) + code.drop(hook.store + 2)).map { it.describe() },
            )
        }
    }

    private fun button(): ClassDef = ImmutableClassDef(
        BUTTON, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
        listOf(
            method(BUTTON, "A0B", parameters, 4, "new-instance v0, $BADGE_UPDATE\nreturn-void"),
            method(BUTTON, "A03", emptyList(), 1, "return-void"),
        ),
    )

    private fun controller(smali: String): ClassDef = ImmutableClassDef(
        CONTROLLER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
        listOf(method(CONTROLLER, "A03", listOf(EVENT), 6, smali)),
    )

    private fun method(type: String, name: String, parameters: List<String>, registers: Int, smali: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, smali)
        return ImmutableMethod.of(mutable)
    }

    private fun Instruction.calls(method: Method): Boolean {
        val called = reference() as? MethodReference ?: return false
        return opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
            called.definingClass == method.definingClass && called.name == method.name &&
            called.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    /** The instruction without its branch offset, a widened goto reading as a goto. */
    private fun Instruction.describe(): String = buildString {
        val kind = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode
        append("$kind")
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        if (this@describe is FiveRegisterInstruction) append(" {$registerCount: v$registerC v$registerD v$registerE v$registerF v$registerG}")
        if (this@describe is RegisterRangeInstruction) append(" {v$startRegister..${startRegister + registerCount}}")
        referenceText()?.let { append(" $it") }
    }

    private companion object {
        const val BUTTON = "LX/0btn;"
        const val CONTROLLER = "LX/0ctl;"
        const val EVENT = "LX/0evt;"

        /**
         * 456's controller in small: the count comes from the event, or from the badge store on the
         * other path, which jumps back to the write. Above zero it goes to the button, otherwise the
         * badge hides.
         */
        val STOCK = """
            iget-object v3, p0, $CONTROLLER->A01:$BUTTON
            iget-boolean v2, p0, $CONTROLLER->A02:Z
            if-nez v2, :read
            iget v1, p1, $EVENT->A00:I
            :store
            iput v1, p0, $CONTROLLER->A00:I
            if-lez v1, :hide
            const/4 v0, 0x0
            invoke-virtual { v3, v1, v0 }, $BUTTON->A0B(ILjava/lang/Long;)V
            return-void
            :read
            const/4 v1, 0x3
            goto :store
            :hide
            invoke-virtual { v3 }, $BUTTON->A03()V
            return-void
        """.trimIndent()
    }
}
