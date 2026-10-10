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

/** The Home header hook: where the header's state takes its list of buttons, and the two stubs. */
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
     * different types, and the stubs read the icon and ask for the heart.
     */
    @Test
    fun everyBuildHandsTheHeadersListToTheExtension() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the six others", 7, bundles.size)
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
        }
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
