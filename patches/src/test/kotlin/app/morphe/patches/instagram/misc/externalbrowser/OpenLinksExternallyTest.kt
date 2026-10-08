/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram.misc.externalbrowser

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Open links in external browser puts its redirect: after the browser activity's own super
 * call, picked by name and prototype rather than by coming first, and on Instagram's in-app browser.
 */
class OpenLinksExternallyTest {
    private val bundle = "Landroid/os/Bundle;"
    private val intentType = "Landroid/content/Intent;"
    private val superOnCreate = "Lfixture/BaseActivity;->onCreate($bundle)V"
    private val superOnNewIntent = "Lfixture/BaseActivity;->onNewIntent($intentType)V"
    private val getResources = "Landroid/view/ContextThemeWrapper;->getResources()Landroid/content/res/Resources;"
    private val getIntent = "Landroid/app/Activity;->getIntent()Landroid/content/Intent;"

    private fun method(
        name: String,
        parameter: String,
        smali: String,
        registers: Int = 4,
        owner: String = "Lfixture/BrowserActivity;",
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            owner,
            name,
            listOf(ImmutableMethodParameter(parameter, null, null)),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun onCreate(smali: String) = method("onCreate", bundle, smali)

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.call get() = ((this as ReferenceInstruction).reference as MethodReference).toString()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    /** The redirect the patch writes is in the ExternalBrowser the bundle ships, public and static. */
    @Test
    fun `the redirect is in the extension`() {
        val declared = ExtensionDex.classDef(REDIRECT.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$REDIRECT is not in the extension: $declared", REDIRECT.substringAfter("->") in declared)
    }

    /** The two super calls a browser activity can make, in the other order. */
    private val resourcesFirst = """
        invoke-super { p0 }, $getResources
        move-result-object v0
        invoke-super { p0, p1 }, $superOnCreate
        return-void
    """

    @Test
    fun `the host's super call is picked in either order`() {
        assertEquals(2, onCreate(resourcesFirst).ownSuperCallIndex())
        assertEquals(
            0,
            onCreate(
                """
                    invoke-super { p0, p1 }, $superOnCreate
                    invoke-super { p0 }, $getResources
                    move-result-object v0
                    return-void
                """,
            ).ownSuperCallIndex(),
        )
        assertEquals(
            0,
            onCreate("invoke-super/range { p0 .. p1 }, $superOnCreate\nreturn-void").ownSuperCallIndex(),
        )
    }

    /** A redirect after the first super call here would land between that call and its move-result. */
    @Test
    fun `the redirect goes after super onCreate when another super call comes first`() {
        val method = onCreate(resourcesFirst)
        method.hookRedirect(intentFromActivity = true)
        val body = method.body()
        assertEquals(getResources, body[0].call)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, body[1].opcode)
        assertEquals(superOnCreate, body[2].call)
        assertEquals(getIntent, body[3].call)
        assertEquals(REDIRECT, body[5].call)
    }

    /**
     * The redirect names p0, and onNewIntent's p1, in 4-bit operands. The patcher's smali compiler
     * leaves out a call whose register doesn't fit, without a word, so a method keeping them above
     * v15 would lose the redirect call and keep the move-result after it.
     */
    @Test
    fun `a redirect whose parameters sit above v15 stops the patch`() {
        val highest = method("onCreate", bundle, "invoke-super/range { p0 .. p1 }, $superOnCreate\nreturn-void", registers = 17)
        highest.hookRedirect(intentFromActivity = true)
        assertEquals("p0 in v15 still fits", REDIRECT, highest.body()[3].call)

        val above = method("onCreate", bundle, "invoke-super/range { p0 .. p1 }, $superOnCreate\nreturn-void", registers = 20)
        val refused = assertThrows(PatchException::class.java) { above.hookRedirect(intentFromActivity = true) }
        assertTrue(refused.message, refused.message.orEmpty().contains("v18"))

        // onNewIntent names its intent, p1, as well: v16 here.
        val newIntent = method(
            "onNewIntent",
            intentType,
            "invoke-super/range { p0 .. p1 }, $superOnNewIntent\nreturn-void",
            registers = 17,
        )
        assertThrows(PatchException::class.java) { newIntent.hookRedirect(intentFromActivity = false) }
    }

    /** The registers the redirect call at [index] passes, and the one its answer goes in. */
    private fun MutableMethod.redirectAt(index: Int): Pair<List<Int>, Int> {
        val body = body()
        assertEquals(REDIRECT, body[index].call)
        val call = body[index] as FiveRegisterInstruction
        val answer = body[index + 1] as OneRegisterInstruction
        return listOf(call.registerC, call.registerD) to answer.registerA
    }

    /**
     * The redirect sits right after the super call, in the middle of the method. A local the rest
     * of onCreate still reads there must not be taken, or the code after the redirect would read
     * the redirect's answer in place of its own value.
     */
    @Test
    fun `the redirect borrows a local nothing reads after the super call`() {
        val method = onCreate(
            """
                const/4 v0, 0x1
                invoke-super { p0, p1 }, $superOnCreate
                invoke-static { v0 }, Lfixture/Log;->note(I)V
                return-void
            """,
        )
        method.hookRedirect(intentFromActivity = true)
        // Two locals, p0 in v2: the intent and the answer go in v1.
        assertEquals(listOf(2, 1) to 1, method.redirectAt(4))

        val full = method(
            "onCreate", bundle,
            """
                const/4 v0, 0x1
                invoke-super { p0, p1 }, $superOnCreate
                invoke-static { v0 }, Lfixture/Log;->note(I)V
                return-void
            """,
            registers = 3,
        )
        val refused = assertThrows(PatchException::class.java) { full.hookRedirect(intentFromActivity = true) }
        assertTrue(refused.message, refused.message.orEmpty().startsWith("Open links in external browser:"))
    }

    /**
     * With a trace section the redirect jumps to the const that loads the close's label. v1 holds
     * the section there, so the redirect can't borrow it; v0 is written before it's read, so it can.
     */
    @Test
    fun `the redirect jumps to the trace section's close and leaves its marker alone`() {
        val method = method(
            "onCreate", bundle,
            """
                const v2, 0x7b
                invoke-static { v2 }, Lfixture/Trace;->begin(I)I
                move-result v1
                invoke-super { p0, p1 }, $superOnCreate
                const/4 v0, 0x1
                invoke-static { v0 }, Lfixture/Log;->note(I)V
                const v2, 0x7c
                invoke-static { v2, v1 }, Lfixture/Trace;->end(II)V
                return-void
            """,
            registers = 5,
        )
        assertEquals("the close's const", 6, method.traceCloseIndex(3))
        method.hookRedirect(intentFromActivity = true)
        // Three locals, p0 in v3: v1 holds the section up to the close, and v0 is written before
        // anything reads it, so the redirect takes v0.
        assertEquals(listOf(3, 0) to 0, method.redirectAt(6))
        val body = method.body()
        assertEquals(Opcode.IF_NEZ, body[8].opcode)
        val jump = body[8] as BuilderOffsetInstruction
        assertEquals("the jump lands on the close's const", Opcode.CONST, body[jump.target.location.index].opcode)
        assertEquals("Lfixture/Trace;->end(II)V", body[jump.target.location.index + 1].call)
    }

    /** A close the redirect can't reach with its own label loaded stops the patch, naming it. */
    @Test
    fun `a trace close fed any other way stops the patch`() {
        val method = method(
            "onCreate", bundle,
            """
                const v2, 0x7b
                invoke-static { v2 }, Lfixture/Trace;->begin(I)I
                move-result v1
                invoke-super { p0, p1 }, $superOnCreate
                const/4 v0, 0x0
                move v2, v0
                invoke-static { v2, v1 }, Lfixture/Trace;->end(II)V
                return-void
            """,
            registers = 5,
        )
        val refused = assertThrows(PatchException::class.java) { method.hookRedirect(intentFromActivity = true) }
        assertTrue(refused.message, refused.message.orEmpty().contains("without a const"))
        assertTrue("something was written", method.body().none { it.referenceText() == REDIRECT })
    }

    /**
     * Only a call before the super call opens a section. An int answered after it is some other
     * value, and the redirect returns as in a method with no section.
     */
    @Test
    fun `an int call after the super call is no trace section`() {
        val method = method(
            "onNewIntent", intentType,
            """
                invoke-super { p0, p1 }, $superOnNewIntent
                const/4 v0, 0x1
                invoke-static { v0 }, Lfixture/Counter;->next(I)I
                move-result v0
                invoke-static { v0 }, Lfixture/Counter;->done(I)V
                return-void
            """,
        )
        assertEquals(null, method.traceCloseIndex(0))
        method.hookRedirect(intentFromActivity = false)
        val body = method.body()
        assertEquals(REDIRECT, body[1].call)
        assertEquals(Opcode.IF_EQZ, body[3].opcode)
        assertEquals(Opcode.RETURN_VOID, body[4].opcode)
        assertTrue("the kept path lands after the return", 5 in method.jumpTargets())
    }

    /**
     * onNewIntent's redirect reads p1 as the new intent. A method that had put something else there
     * and handed the super call a copy would pass the redirect the other value.
     */
    @Test
    fun `the super call has to pass the method's own parameters`() {
        val copied = method(
            "onNewIntent", intentType,
            """
                move-object v0, p1
                const/4 p1, 0x0
                invoke-super { p0, v0 }, $superOnNewIntent
                return-void
            """,
        )
        val refused = assertThrows(PatchException::class.java) { copied.hookRedirect(intentFromActivity = false) }
        assertTrue(refused.message, refused.message.orEmpty().contains("passes v2, v0, not its own v2, v3"))

        // onCreate reads only p0 after the super call, so its bundle may come from anywhere.
        val bundleCopy = onCreate(
            """
                move-object v0, p1
                invoke-super { p0, v0 }, $superOnCreate
                return-void
            """,
        )
        bundleCopy.hookRedirect(intentFromActivity = true)
        assertEquals(listOf(2, 0) to 0, bundleCopy.redirectAt(4))
    }

    /**
     * Some builds keep onCreate's parameters above v15 and hand the super call copies in low locals
     * (450's 385611395 keeps p0 in v17 and passes v8). The redirect reads the activity from the
     * copy, and never borrows the copy's register for the intent, even when nothing reads it later (#77).
     */
    @Test
    fun `a super call handed copies of the parameters gets the redirect on the copies`() {
        val kept = method(
            "onCreate", bundle,
            """
                move-object/from16 v11, p1
                move-object/from16 v8, p0
                invoke-super { v8, v11 }, $superOnCreate
                invoke-static { v8 }, Lfixture/Log;->keep(Ljava/lang/Object;)V
                return-void
            """,
            registers = 19,
        )
        kept.hookRedirect(intentFromActivity = true)
        assertEquals(getIntent, kept.body()[3].call)
        assertEquals("the activity's intent is read off the copy", 8, (kept.body()[3] as FiveRegisterInstruction).registerC)
        assertEquals(listOf(8, 0) to 0, kept.redirectAt(5))

        val dropped = method(
            "onCreate", bundle,
            """
                move-object/from16 v1, p1
                move-object/from16 v0, p0
                invoke-super { v0, v1 }, $superOnCreate
                return-void
            """,
            registers = 19,
        )
        dropped.hookRedirect(intentFromActivity = true)
        assertEquals("v0 is the activity, so the intent goes in v1", listOf(0, 1) to 1, dropped.redirectAt(5))
    }

    /** A copy the patch can't be sure still holds the parameter at the super call stops the patch. */
    @Test
    fun `a copy that may hold something else stops the patch`() {
        val cases = mapOf(
            "the parameter written on the way" to "move-object/from16 v0, p0\nconst/16 p0, 0x0",
            "a branch on the way" to "move-object/from16 v0, p0\nif-eqz v0, :next\n:next",
            "no copy" to "new-instance v0, Lfixture/BrowserActivity;",
        )
        for ((case, prologue) in cases) {
            val method = method(
                "onCreate", bundle,
                "$prologue\nmove-object/from16 v1, p1\ninvoke-super { v0, v1 }, $superOnCreate\nreturn-void",
                registers = 19,
            )
            val refused = assertThrows(case, PatchException::class.java) { method.hookRedirect(intentFromActivity = true) }
            assertTrue("$case: ${refused.message}", refused.message.orEmpty().contains("or copies of them"))
            assertTrue("$case: something was written", method.body().none { it.referenceText() == REDIRECT })
        }
    }

    @Test
    fun `no super call of the host's own shape stops the patch`() {
        val otherPrototype = onCreate(
            """
                const/4 v0, 0x0
                invoke-super { p0, p1, v0 }, Lfixture/BaseActivity;->onCreate(${bundle}Landroid/os/PersistableBundle;)V
                return-void
            """,
        )
        val refused = assertThrows(PatchException::class.java) { otherPrototype.ownSuperCallIndex() }
        assertTrue(refused.message, refused.message.orEmpty().contains("super.onCreate"))
        assertTrue(refused.message, refused.message.orEmpty().contains("found 0"))

        assertThrows(PatchException::class.java) {
            onCreate("invoke-super { p0 }, $getResources\nmove-result-object v0\nreturn-void").ownSuperCallIndex()
        }
    }

    @Test
    fun `two super calls of the host's shape stop the patch`() {
        val twice = onCreate(
            """
                invoke-super { p0, p1 }, $superOnCreate
                invoke-super { p0, p1 }, $superOnCreate
                return-void
            """,
        )
        val refused = assertThrows(PatchException::class.java) { twice.ownSuperCallIndex() }
        assertTrue(refused.message, refused.message.orEmpty().contains("found 2"))
    }

    @Test
    fun `the lifecycle method is picked by name and parameter, and a missing one is named`() {
        val create = onCreate("return-void")
        val overload = method("onCreate", "Landroid/os/PersistableBundle;", "return-void")
        val newIntent = method("onNewIntent", intentType, "return-void")
        assertSame(create, lifecycleMethod(listOf(overload, create, newIntent), "Lfixture/BrowserActivity;", "onCreate", bundle))

        val refused = assertThrows(PatchException::class.java) {
            lifecycleMethod(listOf(overload, newIntent), "Lfixture/BrowserActivity;", "onCreate", bundle)
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("onCreate($bundle)"))
        assertTrue(refused.message, refused.message.orEmpty().contains("Lfixture/BrowserActivity;"))
    }

    /** A browser whose onNewIntent can't be hooked keeps its onCreate as it was too. */
    @Test
    fun `a browser the patch can't read fails before anything is written`() {
        val cases = listOf(
            browser(onNewIntentBody = "return-void") to "call to super.onNewIntent",
            // p1 in v16: only the range form of the super call can name it.
            browser(onNewIntentBody = "invoke-super/range { p0 .. p1 }, $superOnNewIntent\nreturn-void", onNewIntentRegisters = 17)
                to "above v15",
            browser(withOnNewIntent = false) to "onNewIntent($intentType)",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.openLinksExternally() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = context.mutableClassDefBy(IN_APP_BROWSER).methods
                .filter { method -> method.implementation?.instructions.orEmpty().any { it.referenceText() == REDIRECT } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }

        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).openLinksExternally() }
        assertTrue(missing.message, missing.message!!.contains("in-app browser has a new name"))
    }

    private fun browser(
        onNewIntentBody: String = "invoke-super { p0, p1 }, $superOnNewIntent\nreturn-void",
        onNewIntentRegisters: Int = 3,
        withOnNewIntent: Boolean = true,
    ): List<ClassDef> {
        val onCreate = method(
            "onCreate", bundle, "invoke-super { p0, p1 }, $superOnCreate\nreturn-void", owner = IN_APP_BROWSER,
        )
        val methods = mutableListOf(ImmutableMethod.of(onCreate))
        if (withOnNewIntent) {
            methods += ImmutableMethod.of(
                method("onNewIntent", intentType, onNewIntentBody, registers = onNewIntentRegisters, owner = IN_APP_BROWSER),
            )
        }
        return listOf(
            ImmutableClassDef(
                IN_APP_BROWSER, AccessFlags.PUBLIC.value, "Lfixture/BaseActivity;", null, null, null, emptyList(), methods,
            ),
        )
    }

    /**
     * In each declared build the in-app browser's onCreate asks right after its super call and, on
     * a redirect, jumps to the const that labels its trace section's close; its onNewIntent asks
     * right after its super call and returns.
     */
    @Test
    fun `each declared build gets both hooks where they belong`() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (apks in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                getsBothHooks(apks, apks.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The same in the other arm64 builds of each declared version, two of which hand onCreate's
     * super call copies of its parameters (#77). There the intent is read off the copy of `this`.
     */
    @Test
    fun `each other build gets both hooks where they belong`() {
        for (apk in Fixtures.otherBuilds()) getsBothHooks(apk, apk.parentFile.name)
    }

    private fun getsBothHooks(apks: File, label: String) {
        val browser = FixtureDex.classes(apks, setOf(IN_APP_BROWSER)).values.single()
        val context = PatchContexts.of(listOf(browser))

        context.openLinksExternally()

        val methods = context.mutableClassDefBy(IN_APP_BROWSER).methods
        val onCreate = methods.single { it.name == "onCreate" && it.parameterTypes.map(CharSequence::toString) == listOf(bundle) }
        val create = onCreate.body()
        val superCall = create.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER && it.call.endsWith("->onCreate($bundle)V") }
        assertEquals("$label: onCreate reads its intent after super", getIntent, create[superCall + 1].call)
        assertEquals("$label: off what super got as this", (create[superCall] as FiveRegisterInstruction).registerC,
            (create[superCall + 1] as FiveRegisterInstruction).registerC)
        assertEquals("$label: onCreate asks", REDIRECT, create[superCall + 3].call)
        assertEquals("$label: onCreate asks once", 1, create.count { it.referenceText() == REDIRECT })
        assertEquals(Opcode.IF_NEZ, create[superCall + 5].opcode)
        val handled = (create[superCall + 5] as BuilderOffsetInstruction).target.location.index
        assertEquals("$label: the jump lands on the close's const", Opcode.CONST, create[handled].opcode)
        assertEquals("$label: then the close", Opcode.INVOKE_STATIC, create[handled + 1].opcode)
        assertEquals("$label: then the return", Opcode.RETURN_VOID, create[handled + 2].opcode)

        val onNewIntent = methods.single { it.name == "onNewIntent" }
        val renewed = onNewIntent.body()
        val newSuper = renewed.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER && it.call.endsWith("->onNewIntent($intentType)V") }
        assertEquals("$label: onNewIntent asks", REDIRECT, renewed[newSuper + 1].call)
        assertEquals("$label: onNewIntent asks once", 1, renewed.count { it.referenceText() == REDIRECT })
        assertEquals(Opcode.IF_EQZ, renewed[newSuper + 3].opcode)
        assertEquals(Opcode.RETURN_VOID, renewed[newSuper + 4].opcode)
        assertTrue("$label: the kept path lands after the return", newSuper + 5 in onNewIntent.jumpTargets())
    }
}
