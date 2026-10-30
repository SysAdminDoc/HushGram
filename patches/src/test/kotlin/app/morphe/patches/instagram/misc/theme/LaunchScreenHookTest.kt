/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HushGram launch screen: both of the main screen's answers on whether Instagram's own logo
 * animation follows Android's launch screen go through LaunchScreen.instagramLogo, and anything the
 * patch can't find exactly fails it before a change (#5).
 */
class LaunchScreenHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(LAUNCH_SCREEN).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$INSTAGRAM_LOGO is not in the extension: $declared", INSTAGRAM_LOGO in declared)
    }

    @Test
    fun bothAnswersGoThroughTheExtension() {
        val context = PatchContexts.of(listOf(activity(), extension()))

        val found = context.findLogoAnswers()
        assertEquals("A0p", found.method.name)
        context.skipInstagramLogo()

        assertHooked("stand-in", context.method("A0p"))
        assertEquals("the other method is left alone", 0, context.method("onCreate", listOf("Landroid/os/Bundle;")).hooks().size)
    }

    @Test
    fun anythingItCantFindExactlyFailsThePatch() {
        for ((case, classes) in listOf(
            "no main screen" to listOf(extension()),
            "no method loading the extra" to listOf(activity(extra = "extra_other"), extension()),
            "two methods loading the extra" to listOf(activity(secondHolder = true), extension()),
            "no extra read" to listOf(activity(reads = 0), extension()),
            "two extra reads" to listOf(activity(reads = 2), extension()),
            "a read of another extra" to listOf(activity(otherKey = true), extension()),
            "no check" to listOf(activity(gates = 0), extension()),
            "two checks" to listOf(activity(gates = 2), extension()),
            "a check whose answer isn't taken in" to listOf(activity(takesGate = false), extension()),
            "no hook in the extension" to listOf(activity()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findLogoAnswers() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("HushGram launch screen:"))
        }
    }

    /** A refusal comes before any change: the main screen keeps every instruction it had. */
    @Test
    fun aRefusalChangesNothing() {
        val stub = activity()
        val context = PatchContexts.of(listOf(stub))
        val before = context.method("A0p").code().map { it.opcode }
        assertThrows(PatchException::class.java) { context.skipInstagramLogo() }
        assertEquals(before, context.method("A0p").code().map { it.opcode })
    }

    /** The declared build and the other builds of 452: both answers go through the extension. */
    @Test
    fun everyBuildPassesBothAnswersThrough() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        assertTrue("no fixture of a declared build", declared.isNotEmpty())
        val others = Fixtures.otherBuilds()
        assertEquals("the other builds: ${others.map { it.parentFile.name }}", Fixtures.OTHER_BUILDS, others.size)
        for (bundle in declared + others) {
            val label = if (bundle.extension == "apks") bundle.name else bundle.parentFile.name
            val context = PatchContexts.of(FixtureDex.classesHolding(bundle, POST_SPLASH_EXTRA) + extension())
            val found = context.findLogoAnswers()
            val name = found.method.name
            val shape = found.method.parameterTypes.map(Any::toString)
            assertTrue("$label: the method takes the session: $shape", USER_SESSION in shape && shape.last() == "Z")
            assertTrue("$label: the check answers with an Integer on 452", found.gateBoxed)
            context.skipInstagramLogo()
            assertHooked("$label ${found.method.definingClass}->$name", context.method(found.method.definingClass, name, shape))
        }
    }

    /**
     * Two hooks: the one after the extra's read and the one after the check, each right after the
     * move-result taking that answer in, passing the answer alone and writing its own back over it.
     */
    private fun assertHooked(what: String, method: MutableMethod) {
        val code = method.code()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() in setOf(INSTAGRAM_LOGO, INSTAGRAM_LOGO_MODE) }
        assertEquals("$what: the hooks", 2, hooks.size)
        val calls = hooks.map { at ->
            val call = code[at]
            assertEquals("$what: the hook", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            val register = (call as RegisterRangeInstruction).startRegister
            assertEquals("$what: the answer alone", 1, call.registerCount)
            assertTrue("$what: the answer taken in first", code[at - 1].opcode in setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT))
            assertEquals("$what: that answer passed", register, (code[at - 1] as OneRegisterInstruction).registerA)
            assertEquals("$what: the hook's answer taken in", code[at - 1].opcode, code[at + 1].opcode)
            assertEquals("$what: written over Instagram's", register, (code[at + 1] as OneRegisterInstruction).registerA)
            (code[at - 2] as ReferenceInstruction).reference as MethodReference
        }
        assertEquals("$what: after the extra's read", 1, calls.count { it.name == "getBooleanExtra" })
        assertEquals("$what: after the check", listOf(CONTEXT, USER_SESSION), calls.single { it.name != "getBooleanExtra" }.parameterTypes.map(Any::toString))
    }

    private fun BytecodePatchContext.method(name: String, shape: List<String> = listOf(USER_SESSION, "Z")): MutableMethod =
        method(MAIN_ACTIVITY, name, shape)

    private fun BytecodePatchContext.method(type: String, name: String, shape: List<String>): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map(Any::toString) == shape }

    private fun Method.hooks() = code().filter { (it as? ReferenceInstruction)?.reference?.toString() == INSTAGRAM_LOGO }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val CONTEXT = "Landroid/content/Context;"
        const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
        const val INTENT = "Landroid/content/Intent;"
        const val GATE = "Lfixture/Gate;->A00(Landroid/content/Context;Lcom/instagram/common/session/UserSession;)Z"

        fun extension(): ClassDef = ExtensionDex.classDef(LAUNCH_SCREEN)

        /**
         * The main screen, with a method shaped like 451's: it reads the extra, takes the overlay
         * when it's set, and otherwise asks the check. Each flag breaks it one way.
         */
        fun activity(
            extra: String = POST_SPLASH_EXTRA,
            secondHolder: Boolean = false,
            reads: Int = 1,
            otherKey: Boolean = false,
            gates: Int = 1,
            takesGate: Boolean = true,
        ): ClassDef {
            val read = """
                invoke-virtual { v3, v1, v2 }, $INTENT->getBooleanExtra(Ljava/lang/String;Z)Z
                move-result v0
            """
            val gate = if (takesGate) {
                """
                    invoke-static { p0, p1 }, $GATE
                    move-result v0
                """
            } else {
                """
                    invoke-static { p0, p1 }, $GATE
                """
            }
            val body = """
                invoke-virtual { p0 }, Landroid/app/Activity;->getIntent()$INTENT
                move-result-object v3
                const/4 v2, 0x0
                const-string v1, "$extra"
                ${if (otherKey) "const-string v1, \"extra_other\"" else ""}
                ${read.repeat(reads)}
                invoke-virtual { v3, v1 }, $INTENT->removeExtra(Ljava/lang/String;)V
                if-nez v0, :overlay
                ${gate.repeat(gates)}
                if-eqz v0, :done
                :overlay
                const/4 v0, 0x1
                :done
                return-void
            """
            val other = if (secondHolder) {
                """
                    const-string v0, "$extra"
                    invoke-virtual { v3, v1, v2 }, $INTENT->getBooleanExtra(Ljava/lang/String;Z)Z
                    return-void
                """
            } else {
                """
                    return-void
                """
            }
            return ImmutableClassDef(
                MAIN_ACTIVITY, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Landroid/app/Activity;",
                null, null, null, null,
                listOf(
                    instanceMethod("A0p", listOf(USER_SESSION, "Z"), 7, body),
                    instanceMethod("onCreate", listOf("Landroid/os/Bundle;"), 2, other),
                ),
            )
        }

        fun instanceMethod(name: String, parameters: List<String>, registers: Int, body: String): Method = ImmutableMethod.of(
            MutableMethod(
                ImmutableMethod(
                    MAIN_ACTIVITY, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(registers, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body.trimIndent().lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")) },
        )
    }
}
