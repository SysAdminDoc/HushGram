/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hook that has Home's title view show Instagram's logo in place of the picked feed's name. */
class TitleLogoHookTest {
    private val extensionType = KEEP_LOGO.substringBefore("->")

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(extensionType).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$KEEP_LOGO is not in the extension: $declared", KEEP_LOGO.substringAfter("->") in declared)
    }

    /** A build without the title view fails at patch time, saying so, before anything is written. */
    @Test
    fun aBuildWithoutTheTitleViewFailsBeforeAnythingChanges() {
        val none = PatchContexts.of(listOf(ExtensionDex.classDef(extensionType)))
        val failure = assertThrows(PatchException::class.java) { none.findTitleLabel() }
        assertTrue(failure.message, failure.message!!.contains("this Instagram build has no $TITLE_SWITCHER"))
    }

    @Test
    fun aTitleViewThatIsNotAViewAnimatorFailsThePatch() {
        val bare = ImmutableClassDef(
            TITLE_SWITCHER, AccessFlags.PUBLIC.value, "Landroid/widget/FrameLayout;", null, null, null, emptyList(), emptyList(),
        )
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(bare)).findTitleLabel() }
        assertTrue(failure.message, failure.message!!.contains("not a ViewAnimator"))
    }

    @Test
    fun aTitleViewWithNoNameMethodFailsThePatch() {
        val bare = ImmutableClassDef(
            TITLE_SWITCHER, AccessFlags.PUBLIC.value, "Landroid/widget/ViewAnimator;", null, null, null, emptyList(), emptyList(),
        )
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(bare)).findTitleLabel() }
        assertTrue(failure.message, failure.message!!.contains("showing the feed's name, found 0"))
    }

    /**
     * In each of the seven 450 builds the title view's name method and logo method are found, the
     * hook lands first in the name method, its answer and the logo call keep to registers the method
     * doesn't read as a parameter, and the method's own code follows untouched.
     */
    @Test
    fun everyBuildAsksTheExtensionBeforeShowingTheName() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the others", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val name = bundle.parentFile?.name ?: bundle.name
            val switcher = FixtureDex.classes(bundle, setOf(TITLE_SWITCHER)).values.single()
            val context = PatchContexts.of(listOf(switcher, ExtensionDex.classDef(extensionType)))

            val title = context.findTitleLabel()
            val original = context.mutableClassDefBy(TITLE_SWITCHER).methods
                .single { it.name == title.name && it.parameterTypes.map(CharSequence::toString) == title.parameters }
            val before = original.code().map { it.describe() }
            val locals = original.localRegisterCount()
            context.keepLogoInTitle(title)

            val method = context.mutableClassDefBy(TITLE_SWITCHER).methods
                .single { it.name == title.name && it.parameterTypes.map(CharSequence::toString) == title.parameters }
            val code = method.code()
            assertEquals(
                "$name: the injected opcodes",
                listOf(
                    Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.ADD_INT_LIT8, Opcode.IF_NEZ,
                    Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID,
                ),
                code.take(8).map { it.opcode },
            )
            assertEquals("$name: the hook", KEEP_LOGO, code[0].referenceText())
            // p0 is the view, p1 the arrow flag: both sit past the locals, and the answer lives in a local.
            val range = code[0] as RegisterRangeInstruction
            assertEquals("$name: the hook is handed this", listOf(locals, 1), listOf(range.startRegister, range.registerCount))
            assertTrue("$name: the answer stays in a local, not on a parameter register", title.free < locals)
            assertEquals("$name: the answer's register", title.free, (code[1] as OneRegisterInstruction).registerA)
            assertEquals("$name: the test's register", title.free, (code[2] as OneRegisterInstruction).registerA)
            // #413: a 2 (the logo already up) skips the logo call, so its fade doesn't start over.
            assertEquals("$name: one less", listOf(title.free, -1), listOf((code[3] as OneRegisterInstruction).registerA, (code[3] as NarrowLiteralInstruction).narrowLiteral))
            assertEquals("$name: the skip's register", title.free, (code[4] as OneRegisterInstruction).registerA)
            assertEquals("$name: the skip lands on the return", 7, code.targetOf(4))
            assertEquals("$name: the null and false", title.free, (code[5] as OneRegisterInstruction).registerA)
            assertEquals("$name: the constant is zero", 0, (code[5] as NarrowLiteralInstruction).narrowLiteral)
            val call = code[6] as FiveRegisterInstruction
            assertEquals(
                "$name: the logo call's registers",
                listOf(locals, title.free, title.free, title.free, locals + 1),
                listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG),
            )
            assertEquals(
                "$name: the logo call",
                "$TITLE_SWITCHER->${title.logo}(Ljava/lang/String;Ljava/lang/String;ZZ)V",
                code[6].referenceText(),
            )
            assertNotEquals("$name: the logo method is not the name method", title.name, title.logo)
            assertTrue("$name: every register the call names is within v15", listOf(call.registerC, call.registerG).all { it <= 15 })
            assertEquals("$name: the method's own code", before, code.drop(8).map { it.describe() })
            assertEquals("$name: the branch skips to the first original instruction", 8, code.targetOf(2))
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** The index of the instruction the branch at [at] jumps to. */
    private fun List<Instruction>.targetOf(at: Int): Int {
        val addresses = runningFold(0) { address, instruction -> address + instruction.codeUnits }
        return addresses.indexOf(addresses[at] + (this[at] as OffsetInstruction).codeOffset)
    }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is RegisterRangeInstruction) append(" {v$startRegister..${startRegister + registerCount}}")
        referenceText()?.let { append(" $it") }
    }
}
