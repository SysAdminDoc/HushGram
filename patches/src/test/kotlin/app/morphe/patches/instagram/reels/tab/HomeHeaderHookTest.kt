/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Home header hook: where the header's state takes its list of buttons, and the stubs. */
class HomeHeaderHookTest {
    private val extensionType = HEADER_BUTTONS.substringBefore("->")

    /** The hook and both stubs are in the HomeHeader the bundle ships, public and static. */
    @Test
    fun theHookAndTheStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(extensionType).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HEADER_BUTTONS is not in the extension: $declared", HEADER_BUTTONS.substringAfter("->") in declared)
        val ghost = ExtensionDex.classDef(GHOST_BUTTON.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$GHOST_BUTTON is not in the extension: $ghost", GHOST_BUTTON.substringAfter("->") in ghost)
        assertTrue("the $HEADER_ICON_STUB stub is not in the extension: $declared", "$HEADER_ICON_STUB(Ljava/lang/Object;)I" in declared)
        assertTrue("the $HEADER_HEART_STUB stub is not in the extension: $declared", "$HEADER_HEART_STUB(Ljava/lang/Object;)I" in declared)
        for (stub in listOf(HEADER_ROW_STUB, HEADER_START_ROW_STUB, HEADER_TITLE_STUB)) {
            assertTrue("the $stub stub is not in the extension: $declared", "$stub(Ljava/lang/Object;)Ljava/lang/Object;" in declared)
        }
    }

    /**
     * The start row is the other row the draw method gives a minimum width: a field read straight
     * into the register widened counts, once, and a register written again in between doesn't.
     */
    @Test
    fun theRowsTheHeaderWidensAreTheFieldsReadIntoTheWidenedRegister() {
        val code = standCode(
            """
                iget-object v0, p0, $MAIN_FEED_ACTION_BAR->A0F:Landroid/widget/LinearLayout;
                iget-object v1, p0, $MAIN_FEED_ACTION_BAR->A0G:Landroid/widget/LinearLayout;
                iget-object v2, p0, $MAIN_FEED_ACTION_BAR->A0E:Landroid/widget/LinearLayout;
                move-object v2, v0
                const/4 v3, 0x0
                invoke-virtual { v0, v3 }, Landroid/view/View;->setMinimumWidth(I)V
                invoke-virtual { v1, v3 }, Landroid/view/View;->setMinimumWidth(I)V
                invoke-virtual { v2, v3 }, Landroid/view/View;->setMinimumWidth(I)V
                iget-object v2, p0, $MAIN_FEED_ACTION_BAR->A0E:Landroid/widget/LinearLayout;
                invoke-virtual { v2, v3 }, Landroid/view/View;->setVisibility(I)V
                invoke-virtual { v0, v3 }, Landroid/view/View;->setMinimumWidth(I)V
                return-void
            """,
            registers = 5,
        )
        assertEquals(listOf("A0F", "A0G"), widenedRows(code).map { it.name })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val none = PatchContexts.of(listOf(ExtensionDex.classDef(extensionType)))
        val missing = assertThrows(PatchException::class.java) { none.findHomeHeader() }
        assertTrue(missing.message, missing.message!!.contains("this Instagram build has no $MAIN_FEED_ACTION_BAR"))

        val bare = ImmutableClassDef(
            MAIN_FEED_ACTION_BAR, AccessFlags.PUBLIC.value, "Landroid/widget/FrameLayout;", null, null, null, emptyList(),
            listOf(stand("A08", listOf("Ljava/lang/Object;"))),
        )
        val context = PatchContexts.of(listOf(bare, ExtensionDex.classDef(extensionType)))
        val failure = assertThrows(PatchException::class.java) { context.findHomeHeader() }
        assertTrue(failure.message, failure.message!!.contains("expected one method in $MAIN_FEED_ACTION_BAR drawing the header from a state, found 0"))
    }

    /**
     * In each of the seven 450 builds the header's state constructor is found, hands its list to the
     * extension first thing and keeps its own code after, the image button and the heart are two
     * different types, the stubs read the icon and ask for the heart, and the row and title stubs
     * read the end row, the start row and the title view the logo is centered with.
     */
    @Test
    fun everyBuildHandsTheHeadersListToTheExtension() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the others", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val name = bundle.parentFile?.name ?: bundle.name
            val bar = FixtureDex.classes(bundle, setOf(MAIN_FEED_ACTION_BAR)).values.single()
            val related = bar.methods.flatMap { method -> method.parameterTypes.map { it.toString() } }.filter { it.startsWith("L") && !it.startsWith("Landroid/") } +
                bar.methods.flatMap { it.code() }.filter { it.opcode == Opcode.INSTANCE_OF }.map { (it.reference() as TypeReference).type }
            val classes = FixtureDex.classes(bundle, related.toSet()).values
            val context = PatchContexts.of((listOf(bar) + classes + ExtensionDex.classDef(extensionType) + ExtensionDex.classDef(GHOST_BUTTON.substringBefore("->"))).distinctBy { it.type })

            val hook = context.findHomeHeader()
            val before = hook.state.code().map { it.describe() }
            val drawBefore = hook.draw.code().map { it.describe() }
            val drawThis = hook.draw.localRegisterCount()

            context.hideHomeHeaderButtons(hook)

            assertNotEquals("$name: image button and heart", hook.image, hook.badge)
            val state = context.mutableClassDefBy(hook.state.definingClass).methods.single { it.name == "<init>" && it.parameterTypes.size == 5 }
            val code = state.code()
            assertEquals("$name: the hook", HEADER_BUTTONS, code[0].referenceText())
            assertEquals("$name: hands over the list", listOf(hook.list, 1), (code[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
            assertEquals("$name: the answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
            assertEquals("$name: the answer's register", hook.list, (code[1] as OneRegisterInstruction).registerA)
            assertTrue("$name: the list's register is reachable by move-result", hook.list <= 255)
            assertEquals("$name: the constructor's own code", before, code.drop(2).map { it.describe() })

            // The Ghost mode button's hook comes first in the method that draws the header, handed the header
            // itself (p0, past the locals) and nothing else, so it needs no register of its own.
            val draw = context.mutableClassDefBy(MAIN_FEED_ACTION_BAR).methods.single {
                it.name == hook.draw.name && it.parameterTypes.map(CharSequence::toString) == hook.draw.parameterTypes.map(CharSequence::toString)
            }.code()
            assertEquals("$name: the ghost button's hook", GHOST_BUTTON, draw[0].referenceText())
            assertEquals("$name: the ghost button's call", Opcode.INVOKE_STATIC_RANGE, draw[0].opcode)
            assertEquals("$name: the header is handed over", listOf(drawThis, 1), (draw[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
            assertEquals("$name: the draw method's own code", drawBefore, draw.drop(1).map { it.describe() })

            val iconMethod = context.mutableClassDefBy(extensionType).methods.single { it.name == HEADER_ICON_STUB }
            val icon = iconMethod.code()
            assertEquals("$name: icon stub", listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.IGET, Opcode.RETURN, Opcode.CONST_4, Opcode.RETURN), icon.map { it.opcode })
            assertEquals("$name: icon stub type", hook.image, (icon[0].reference() as TypeReference).type)
            assertEquals("$name: icon stub field", hook.icon, icon[3].referenceText())
            // The verifier rejected the whole class on a device when instance-of's int landed on the
            // button register the cast reads next, so the button keeps a register of its own.
            val button = iconMethod.implementation!!.registerCount - 1
            assertNotEquals("$name: instance-of keeps off the button's register", button, (icon[0] as OneRegisterInstruction).registerA)
            assertEquals("$name: instance-of asks about the button", button, (icon[0] as TwoRegisterInstruction).registerB)
            assertEquals("$name: the cast is on the button", button, (icon[2] as OneRegisterInstruction).registerA)
            assertEquals("$name: the read is from the button", button, (icon[3] as TwoRegisterInstruction).registerB)
            val heart = context.mutableClassDefBy(extensionType).methods.single { it.name == HEADER_HEART_STUB }.code()
            assertEquals("$name: heart stub", listOf(Opcode.INSTANCE_OF, Opcode.RETURN), heart.map { it.opcode })
            assertEquals("$name: heart stub type", hook.badge, (heart[0].reference() as TypeReference).type)

            // The end row is the header's LinearLayout field the draw method adds the heart's view to,
            // and the row stub reads it out of the header after asking the header's type.
            assertTrue("$name: the end row ${hook.row}", hook.row.startsWith("$MAIN_FEED_ACTION_BAR->") && hook.row.endsWith(":Landroid/widget/LinearLayout;"))
            assertReadStub(context, name, HEADER_ROW_STUB, hook.row)
            val badgeAdds = hook.draw.code().count { it.opcode == Opcode.CHECK_CAST && (it.reference() as TypeReference).type == TOASTING_BADGE }
            assertTrue("$name: the draw method casts the heart's view", badgeAdds >= 1)

            // The start row is the other LinearLayout the draw method gives a minimum width as
            // Instagram's newer header centers its logo, and the title view is the header's one
            // field of its type. Their stubs read them the way the end row's does.
            assertTrue("$name: the start row ${hook.start}", hook.start.startsWith("$MAIN_FEED_ACTION_BAR->") && hook.start.endsWith(":Landroid/widget/LinearLayout;"))
            assertNotEquals("$name: the start row isn't the end row", hook.row, hook.start)
            assertEquals("$name: the rows the header widens", setOf(hook.row, hook.start), widenedRows(hook.draw.code()).map { it.toString() }.toSet())
            assertEquals("$name: the title view", TITLE_VIEW, hook.title.substringAfterLast(':'))
            assertTrue("$name: the title view ${hook.title}", hook.title.startsWith("$MAIN_FEED_ACTION_BAR->"))
            assertReadStub(context, name, HEADER_START_ROW_STUB, hook.start)
            assertReadStub(context, name, HEADER_TITLE_STUB, hook.title)
        }
    }

    /** The [stub] reads [field] out of the header after asking the header's type, and answers null for anything else. */
    private fun assertReadStub(context: BytecodePatchContext, name: String, stub: String, field: String) {
        val method = context.mutableClassDefBy(extensionType).methods.single { it.name == stub }
        val code = method.code()
        assertEquals(
            "$name: $stub stub",
            listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT, Opcode.CONST_4, Opcode.RETURN_OBJECT),
            code.map { it.opcode },
        )
        assertEquals("$name: $stub stub type", MAIN_FEED_ACTION_BAR, (code[0].reference() as TypeReference).type)
        assertEquals("$name: $stub stub cast", MAIN_FEED_ACTION_BAR, (code[2].reference() as TypeReference).type)
        assertEquals("$name: $stub stub field", field, code[3].referenceText())
        // The stub's local never lands on the header's register, which it reads after instance-of writes.
        val header = method.implementation!!.registerCount - 1
        assertEquals("$name: the $stub stub has one local", 1, method.localRegisterCount())
        assertNotEquals("$name: $stub's instance-of keeps off the header's register", header, (code[0] as OneRegisterInstruction).registerA)
        assertEquals("$name: $stub's instance-of asks about the header", header, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$name: $stub's cast is on the header", header, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$name: $stub's read is from the header", header, (code[3] as TwoRegisterInstruction).registerB)
        assertNotEquals("$name: $stub's read lands off the header's register", header, (code[3] as OneRegisterInstruction).registerA)
        assertEquals("$name: the null answer", 0, (code[5] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$name: $stub answers what it read", (code[3] as OneRegisterInstruction).registerA, (code[4] as OneRegisterInstruction).registerA)
    }

    private fun standCode(smali: String, registers: Int): List<Instruction> {
        val mutable = MutableMethod(
            ImmutableMethod(
                MAIN_FEED_ACTION_BAR, "A08", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, smali)
        return ImmutableMethod.of(mutable).code()
    }

    private fun stand(name: String, parameters: List<String>): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                MAIN_FEED_ACTION_BAR, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(parameters.size + 1, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, "return-void")
        return ImmutableMethod.of(mutable)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is RegisterRangeInstruction) append(" {v$startRegister..${startRegister + registerCount}}")
        referenceText()?.let { append(" $it") }
    }
}
