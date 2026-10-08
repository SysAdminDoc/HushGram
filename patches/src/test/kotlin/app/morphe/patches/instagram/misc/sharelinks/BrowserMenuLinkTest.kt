/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.sharelinks

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page address the in-app browser's menu sends for Share and for Copy link goes through
 * LinkCleaner.browserLink in the main app's handler for those messages, on the path that read it
 * from the message and on the one that fell back to an empty address alike.
 */
class BrowserMenuLinkTest {
    @Test
    fun bothAddressesGoThroughTheLinkFilterBeforeTheyLeave() {
        val context = PatchContexts.of(listOf(handler()))

        assertNull(context.cleanBrowserMenu())

        val code = context.handlerCode()
        assertEquals(
            listOf(
                "const-string", "new-instance", "invoke-direct", "const-string", BROWSER_LINK, "move-result-object",
                "invoke-virtual", "move-result-object",
            ),
            code.drop(code.indexOfFirst { it.loads("android.intent.action.SEND") }).take(8).map(::shape),
        )
        assertEquals(
            listOf("iget-object", BROWSER_LINK, "move-result-object", COPY),
            code.drop(code.indices.last { code[it].loads("") } + 1).take(4).map(::shape),
        )
        for (hook in code.indices.filter { code[it].calls(BROWSER_LINK) }) {
            assertEquals("the address's register", 2, (code[hook] as RegisterRangeInstruction).startRegister)
            assertEquals("one register", 1, (code[hook] as RegisterRangeInstruction).registerCount)
            assertEquals("written back", 2, (code[hook + 1] as OneRegisterInstruction).registerA)
        }
        assertEquals("one call for each", 2, code.count { it.calls(BROWSER_LINK) })
    }

    @Test
    fun aHandlerThatIsntTheOneIsLeftAsItCame() {
        leftAlone("no handler", listOf(handler(copyFailure = "something else")))
        leftAlone("two handlers", listOf(handler(), handler(type = "Lfixture/OtherBrowserHandler;")))
        leftAlone("Share puts the address in the subject", listOf(handler(shareKey = "android.intent.extra.SUBJECT")))
        leftAlone("Share loads its key before something else", listOf(handler(beforePut = "const/4 v3, 0x0")))
        leftAlone("Copy link hands the address to a helper of another shape", listOf(handler(copy = "$CLIPBOARD->copy(Ljava/lang/String;)Z", copyArguments = "v2")))
        leftAlone("Copy link hands it over as the context", listOf(handler(copyArguments = "v2, v1")))
        leftAlone("a branch lands on the share's own call", listOf(handler(entry = "if-eqz p1, :put")))
    }

    /** browserLink is in the LinkCleaner the bundle ships, public and static, with exactly the descriptor the patch writes. */
    @Test
    fun theLinkFilterIsInTheExtension() {
        val cleaner = BROWSER_LINK.substringBefore("->")
        val method = ExtensionDex.classDef(cleaner).methods.singleOrNull {
            "$cleaner->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == BROWSER_LINK
        }
        assertNotNull("LinkCleaner declares no $BROWSER_LINK", method)
        assertTrue(AccessFlags.PUBLIC.isSet(method!!.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
    }

    /**
     * On every declared build's fixture: the one handler logging both failure lines gets the filter
     * once on each branch, right before Intent.putExtra takes the shared text and right before the
     * static clipboard helper takes the copied address, and nothing else in it moves.
     */
    @Test
    fun eachDeclaredBuildCleansBothMenuAddresses() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val handlers = FixtureDex.classesHolding(bundle, BROWSER_SHARE_FAILURE)
                assertEquals("${bundle.name}: classes logging the share's failure line", 1, handlers.size)
                val type = handlers.single().type
                val before = handlers.single().methods.single { it.name == "handleMessage" }.implementation!!.instructions.toList()
                val context = PatchContexts.of(handlers)

                assertNull(bundle.name, context.cleanBrowserMenu())

                val code = context.mutableClassDefBy(type).methods.single { it.name == "handleMessage" }.implementation!!.instructions.toList()
                assertEquals("${bundle.name}: two calls and their results added", before.size + 4, code.size)
                val hooks = code.indices.filter { code[it].calls(BROWSER_LINK) }
                assertEquals(bundle.name, 2, hooks.size)
                val (share, copy) = hooks
                for (hook in hooks) {
                    val register = (code[hook] as RegisterRangeInstruction).startRegister
                    assertEquals(bundle.name, 1, (code[hook] as RegisterRangeInstruction).registerCount)
                    assertEquals(bundle.name, Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
                    assertEquals(bundle.name, register, (code[hook + 1] as OneRegisterInstruction).registerA)
                    val fallback = (0 until hook).last { code[it].loads("") }
                    assertEquals("${bundle.name}: the empty address's register", register, (code[fallback] as OneRegisterInstruction).registerA)
                }
                assertTrue("${bundle.name}: Share's after its failure line", (0 until share).any { code[it].loads(BROWSER_SHARE_FAILURE) })
                val put = code[share + 2]
                assertEquals(bundle.name, "Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;", put.reference())
                assertEquals("${bundle.name}: the shared text is the address", (code[share] as RegisterRangeInstruction).startRegister, (put as FiveRegisterInstruction).registerE)
                assertTrue("${bundle.name}: its key", code[share - 1].loads("android.intent.extra.TEXT"))
                assertTrue("${bundle.name}: Copy link's after its failure line", (share until copy).any { code[it].loads(BROWSER_COPY_FAILURE) })
                val helper = code[copy + 2]
                assertEquals(bundle.name, Opcode.INVOKE_STATIC, helper.opcode)
                assertTrue(bundle.name, helper.reference().endsWith("(Landroid/content/Context;Ljava/lang/String;)Z"))
                assertEquals("${bundle.name}: the copied text is the address", (code[copy] as RegisterRangeInstruction).startRegister, (helper as FiveRegisterInstruction).registerD)
                assertEquals(
                    "${bundle.name}: the rest as it was",
                    before.map { it.opcode },
                    code.filterIndexed { index, _ -> index !in setOf(share, share + 1, copy, copy + 1) }.map { it.opcode },
                )
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun leftAlone(case: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.map { it.type }.associateWith { context.handlerCode(it).map(::shape) }
        assertNotNull(case, context.cleanBrowserMenu())
        before.forEach { (type, code) -> assertEquals(case, code, context.handlerCode(type).map(::shape)) }
    }

    private fun shape(instruction: Instruction): String = when {
        instruction.calls(BROWSER_LINK) -> BROWSER_LINK
        instruction.reference().startsWith("$CLIPBOARD->") -> COPY
        else -> instruction.opcode.name
    }

    private fun Instruction.calls(reference: String) = reference() == reference

    private fun Instruction.reference(): String = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString().orEmpty()

    private fun Instruction.loads(text: String) =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            ((this as ReferenceInstruction).reference as StringReference).string == text

    private fun BytecodePatchContext.handlerCode(type: String = HANDLER): List<Instruction> =
        mutableClassDefBy(type).methods.single { it.name == "handleMessage" }.implementation!!.instructions.toList()

    private companion object {
        const val HANDLER = "Lfixture/BrowserHandler;"
        const val CLIPBOARD = "Lfixture/Clipboard;"
        const val COPY = "copy"

        /**
         * The handler as 450 has it, cut down: message 0 is Copy link and anything else here is
         * Share. Each reads the address from the message, or logs its failure line and falls back
         * to an empty address, and the two paths meet right before the address is used. v0 to v3
         * are its locals, then the handler and the message.
         */
        fun handler(
            type: String = HANDLER,
            copyFailure: String = BROWSER_COPY_FAILURE,
            shareKey: String = "android.intent.extra.TEXT",
            beforePut: String = "",
            copy: String = "$CLIPBOARD->copy(Landroid/content/Context;Ljava/lang/String;)Z",
            copyArguments: String = "v1, v2",
            entry: String = "",
        ): ClassDef {
            val body = """
                $entry
                iget v2, p1, Landroid/os/Message;->what:I
                if-eqz v2, :copy
                iget-object v2, p1, Landroid/os/Message;->obj:Ljava/lang/Object;
                instance-of v0, v2, Ljava/lang/String;
                if-eqz v0, :share_failed
                check-cast v2, Ljava/lang/String;
                if-nez v2, :share
                :share_failed
                const-string v1, "UITaskHandler"
                const-string v0, "$BROWSER_SHARE_FAILURE"
                invoke-static { v1, v0 }, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
                const-string v2, ""
                :share
                const-string v0, "android.intent.action.SEND"
                new-instance v1, Landroid/content/Intent;
                invoke-direct { v1, v0 }, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
                const-string v0, "$shareKey"
                $beforePut
                ${if (":put" in entry) ":put" else ""}
                invoke-virtual { v1, v0, v2 }, Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                move-result-object v1
                const/4 v0, 0x0
                invoke-static { v1, v0 }, Landroid/content/Intent;->createChooser(Landroid/content/Intent;Ljava/lang/CharSequence;)Landroid/content/Intent;
                move-result-object v1
                iget-object v2, p0, $type->service:Landroid/content/Context;
                invoke-virtual { v2, v1 }, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                return-void
                :copy
                iget-object v2, p1, Landroid/os/Message;->obj:Ljava/lang/Object;
                instance-of v0, v2, Ljava/lang/String;
                if-eqz v0, :copy_failed
                check-cast v2, Ljava/lang/String;
                if-nez v2, :copied
                :copy_failed
                const-string v1, "UITaskHandler"
                const-string v0, "$copyFailure"
                invoke-static { v1, v0 }, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
                const-string v2, ""
                :copied
                iget-object v1, p0, $type->service:Landroid/content/Context;
                invoke-static { $copyArguments }, $copy
                return-void
            """.lines().filter { it.isNotBlank() }.joinToString("\n")
            val method = MutableMethod(
                ImmutableMethod(
                    type, "handleMessage", listOf(ImmutableMethodParameter("Landroid/os/Message;", null, null)), "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(6, emptyList(), null, null),
                ),
            ).apply { addInstructionsWithLabels(0, body.trimIndent()) }.let(ImmutableMethod::of)
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Landroid/os/Handler;", null, null, null, null, listOf(method))
        }
    }
}
