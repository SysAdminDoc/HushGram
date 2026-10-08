/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.confirm

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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Like button's handler asks LikeConfirm.hold first and returns while it holds the tap, and the
 * extension's stubs reach the handler's screen and call it again.
 */
class AskBeforeLikeHookTest {
    @Test
    fun theLikeButtonAsksFirstAndItsStubsReachTheHandler() {
        val context = PatchContexts.of(listOf(handler(), position(), fragment(), extension()))
        context.askBeforeLike()

        val code = context.handlerCode()!!
        assertEquals(listOf(HOLD_LIKE, "move-result", "if-eqz", "return-void", "const-string"), code.take(5).map(::shape))
        val hold = code[0] as RegisterRangeInstruction
        assertEquals("hold takes the handler and its five values", 6, hold.registerCount)
        assertEquals("starting at the handler itself", HANDLER_REGISTERS - 6, hold.startRegister)
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("once", 1, code.count { it.calls(HOLD_LIKE) })

        val screen = context.stub(CONTEXT_OF_STUB)
        assertEquals(listOf("check-cast", "iget-object", "if-nez", "const/4", "return-object", "invoke-virtual", "move-result-object", "return-object"),
            screen.take(8).map { it.opcode.name })
        assertEquals("$HANDLER->A00:$FRAGMENT", screen[1].referenced())
        assertEquals(GET_CONTEXT, screen[5].referenced())

        val again = context.stub(LIKE_AGAIN_STUB)
        assertEquals(listOf("check-cast", "check-cast", "check-cast", "check-cast", "invoke-virtual/range", "return-void"),
            again.take(6).map { it.opcode.name })
        assertEquals(listOf(HANDLER, MEDIA, POSITION, FUNCTION0), again.take(4).map { it.referenced() })
        assertEquals("$HANDLER->onLike($MEDIA${POSITION}Ljava/lang/String;${FUNCTION0}I)V", again[4].referenced())
        assertEquals(6, (again[4] as RegisterRangeInstruction).registerCount)
    }

    @Test
    fun aLikeButtonThatIsntTheOneFailsThePatchUnchanged() {
        refuses("no handler filing like_media", listOf(handler(body = "return-void"), position(), fragment(), extension()))
        refuses("a static handler", listOf(handler(static = true), position(), fragment(), extension()))
        refuses("two handlers", listOf(handler(twice = true), position(), fragment(), extension()))
        refuses("a handler class that isn't public", listOf(handler(classFlags = AccessFlags.FINAL.value), position(), fragment(), extension()))
        refuses("a position that isn't in the app", listOf(handler(), fragment(), extension()))
        refuses("a position that isn't public", listOf(handler(), position(flags = 0), fragment(), extension()))
        refuses("no fragment kept", listOf(handler(fragments = emptyList()), position(), fragment(), extension()))
        refuses("two fragments kept", listOf(handler(fragments = listOf("A00", "A01")), position(), fragment(), extension()))
        refuses("a fragment that isn't public", listOf(handler(fieldFlags = AccessFlags.FINAL.value), position(), fragment(), extension()))
        refuses("no Fragment in the app", listOf(handler(), position(), extension()))
        refuses("a Fragment without getContext", listOf(handler(), position(), fragment(getter = "requireContext"), extension()))
        refuses("no register of its own", listOf(handler(registers = 6, body = "const-string p3, \"like_media\"\nreturn-void"), position(), fragment(), extension()))
        refuses("a jump onto its first instruction", listOf(handler(body = ":top\nconst-string v0, \"like_media\"\nif-eqz p5, :top\nreturn-void"), position(), fragment(), extension()))
        refuses("no extension", listOf(handler(), position(), fragment()))
    }

    /**
     * On every declared build's fixture: the one Like button handler filing like_media takes the
     * hold first and once, and the stubs reach its fragment and call it again.
     */
    @Test
    fun eachDeclaredBuildAsksBeforeALike() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holding = FixtureDex.withStringPools(bundle, FixtureDex.classesHolding(bundle, "like_media"))
                val handler = holding.flatMap { it.methods }.single {
                    it.likeShaped() && !AccessFlags.STATIC.isSet(it.accessFlags) &&
                        it.implementation?.instructions?.any { instruction -> instruction.referencedOrNull() == "like_media" } == true
                }
                val others = FixtureDex.classes(bundle, setOf(handler.parameterTypes[1].toString(), FRAGMENT)).values
                val context = PatchContexts.of((holding + others).distinctBy { it.type } + extension())
                context.askBeforeLike()

                val code = context.mutableClassDefBy(handler.definingClass).methods.single {
                    it.name == handler.name && it.parameterTypes.map(Any::toString) == handler.parameterTypes.map(Any::toString)
                }.implementation!!.instructions.toList()
                assertTrue("${bundle.name}: hold first", code[0].calls(HOLD_LIKE))
                assertEquals(bundle.name, handler.implementation!!.registerCount - 6, (code[0] as RegisterRangeInstruction).startRegister)
                assertEquals(bundle.name, listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.subList(1, 4).map { it.opcode })
                assertEquals(bundle.name, 1, code.count { it.calls(HOLD_LIKE) })
                assertTrue("${bundle.name}: the stub reaches the fragment", context.stub(CONTEXT_OF_STUB)[1].referenced().startsWith("${handler.definingClass}->"))
                assertTrue("${bundle.name}: and calls the handler again", context.stub(LIKE_AGAIN_STUB)[4].referenced().startsWith("${handler.definingClass}->${handler.name}("))
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun refuses(case: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = context.handlerCode()?.map(::shape)
        assertThrows(case, PatchException::class.java) { context.askBeforeLike() }
        assertEquals(case, before, context.handlerCode()?.map(::shape))
    }

    private fun shape(instruction: Instruction): String =
        if (instruction.calls(HOLD_LIKE)) HOLD_LIKE else instruction.opcode.name

    private fun Instruction.calls(reference: String) = referencedOrNull() == reference

    private fun Instruction.referencedOrNull(): String? = when (val reference = (this as? ReferenceInstruction)?.reference) {
        null -> null
        is StringReference -> reference.string
        else -> reference.toString()
    }

    private fun Instruction.referenced(): String = referencedOrNull()!!

    private fun Method.likeShaped(): Boolean = returnType == "V" &&
        parameterTypes.map(Any::toString).let { it.size == 5 && it[0] == MEDIA && it[2] == "Ljava/lang/String;" && it[3] == FUNCTION0 && it[4] == "I" }

    private fun BytecodePatchContext.handlerCode(): List<Instruction>? =
        classDefByOrNull(HANDLER)?.let { mutableClassDefBy(HANDLER).methods.first { it.name == "onLike" }.implementation!!.instructions.toList() }

    private fun BytecodePatchContext.stub(name: String): List<Instruction> =
        mutableClassDefBy(LIKE_CONFIRM).methods.single { it.name == name }.implementation!!.instructions.toList()

    private companion object {
        const val HANDLER = "Lfixture/LikeHandler;"
        const val POSITION = "Lfixture/Position;"
        const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
        const val HANDLER_REGISTERS = 8
        val PUBLIC = AccessFlags.PUBLIC.value
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun extension(): ClassDef = ExtensionDex.classDef(LIKE_CONFIRM)

        /**
         * The Like button's handler as 450 has it: an instance method taking the post, its position,
         * the module name, a callback and an index, filing like_media, in a class keeping the screen's
         * fragment. v0 and v1 are its locals.
         */
        fun handler(
            body: String = "const-string v0, \"like_media\"\ninvoke-static { v0 }, Lfixture/Log;->file(Ljava/lang/String;)V\nreturn-void",
            static: Boolean = false,
            twice: Boolean = false,
            classFlags: Int = PUBLIC_FINAL,
            fragments: List<String> = listOf("A00"),
            fieldFlags: Int = PUBLIC_FINAL,
            registers: Int = HANDLER_REGISTERS,
        ): ClassDef {
            val parameters = listOf(MEDIA, POSITION, "Ljava/lang/String;", FUNCTION0, "I")
            val flags = if (static) PUBLIC_FINAL or AccessFlags.STATIC.value else PUBLIC_FINAL
            val methods = mutableListOf(method(HANDLER, "onLike", parameters, "V", registers, flags, body))
            if (twice) methods += method(HANDLER, "onLikeAgain", parameters, "V", registers, flags, body)
            val fields = fragments.map { ImmutableField(HANDLER, it, FRAGMENT, fieldFlags, null, null, null) }
            return ImmutableClassDef(HANDLER, classFlags, "Ljava/lang/Object;", null, null, null, fields, methods)
        }

        fun position(flags: Int = PUBLIC_FINAL): ClassDef =
            ImmutableClassDef(POSITION, flags, "Ljava/lang/Object;", null, null, null, null, null)

        fun fragment(getter: String = "getContext"): ClassDef = ImmutableClassDef(
            FRAGMENT, PUBLIC, "Ljava/lang/Object;", null, null, null, null,
            listOf(method(FRAGMENT, getter, emptyList(), "Landroid/content/Context;", 2, PUBLIC, "const/4 v0, 0x0\nreturn-object v0")),
        )

        fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method =
            MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.trimIndent())
            }.let(ImmutableMethod::of)
    }
}
