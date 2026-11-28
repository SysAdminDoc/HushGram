/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.swipereply

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stop swipe up to reply's second switch, Stop story side taps (#887): the story viewer's tap
 * forward and tap back handlers ask the extension first and return on a no, the rest of each stays
 * Instagram's, the contract rules pick those two methods alone, and a viewer where either handler
 * can't be told apart fails the patch unchanged.
 */
class StorySideTapsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_TAPS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$STORY_TAPS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("StoryTaps declares no $KEEPS_TAP: $declared", KEEPS_TAP in declared)
    }

    @Test
    fun eachDeclaredBuildGuardsBothTaps() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                guards(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildGuardsBothTapsToo() {
        val others = Fixtures.otherBuilds()
        assertTrue("no other build to check", others.isNotEmpty())
        for (base in others) guards(base, base.parentFile.name)
    }

    @Test
    fun aViewerWithoutTheTapBackHandlerFailsThePatch() =
        refuses("loading \"$TAP_BACK\", found 0", listOf(viewer(handler("A00", TAP_FORWARD))))

    @Test
    fun aViewerWithTwoTapForwardHandlersFailsThePatch() =
        refuses("loading \"$TAP_FORWARD\", found 2", listOf(viewer(handler("A00", TAP_FORWARD), handler("A01", TAP_FORWARD), handler("A02", TAP_BACK))))

    @Test
    fun oneHandlerHoldingBothStringsFailsThePatch() =
        refuses("share $STORY_VIEWER->A00", listOf(viewer(handler("A00", TAP_FORWARD, TAP_BACK))))

    @Test
    fun aBranchOntoAHandlersFirstInstructionFailsThePatch() =
        refuses("lands on its first instruction", listOf(viewer(handler("A00", TAP_FORWARD, loops = true), handler("A01", TAP_BACK))))

    /** A handler of the shape that's static, or takes primitive floats, isn't a tap handler. */
    @Test
    fun otherShapesAreLeftOut() {
        val static = handler("A03", TAP_FORWARD, flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)
        val primitive = handler("A04", TAP_FORWARD, parameters = listOf("F", "F"))
        val context = PatchContexts.of(listOf(viewer(handler("A00", TAP_FORWARD), handler("A01", TAP_BACK), static, primitive)))
        assertEquals(TapHandlers("A00", "A01"), context.findTapHandlers())
    }

    private fun guards(bundle: File, label: String) {
        val classes = listOf(TAP_FORWARD, TAP_BACK).flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes))
        val found = context.findTapHandlers()
        val stockViewer = classes.single { it.type == STORY_VIEWER }

        // What scripts/injected-mutation-contracts.txt picks each guarded method by is that one method's alone.
        for ((mark, name) in listOf(TAP_FORWARD to found.forward, TAP_BACK to found.back)) {
            val shaped = classes.flatMap { c ->
                c.methods.filter { m ->
                    !AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "V" &&
                        m.parameterTypes.map(CharSequence::toString) == TAP_PARAMETERS && m.code().any { it.string() == mark }
                }
            }
            assertEquals("$label: the \"$mark\" rule's method", listOf("$STORY_VIEWER->$name"), shaped.map { "${it.definingClass}->${it.name}" })
        }

        context.stopSideTaps(found)

        val viewer = context.mutableClassDefBy(STORY_VIEWER)
        for (name in listOf(found.forward, found.back)) {
            val stock = stockViewer.methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == TAP_PARAMETERS }.code()
            val patched = viewer.methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == TAP_PARAMETERS }.code()
            assertEquals("$label $name: asks first", KEEPS_TAP, patched[0].referenceText())
            assertEquals("$label $name: asks once", 1, patched.count { it.referenceText() == KEEPS_TAP })
            assertEquals("$label $name: reads the answer", Opcode.MOVE_RESULT, patched[1].opcode)
            val answer = (patched[1] as OneRegisterInstruction).registerA
            assertEquals("$label $name: a yes branches", Opcode.IF_NEZ, patched[2].opcode)
            assertEquals("$label $name: on the answer", answer, (patched[2] as OneRegisterInstruction).registerA)
            assertEquals("$label $name: a yes goes to Instagram's first instruction", 4, patched.target(2))
            assertEquals("$label $name: a no returns", Opcode.RETURN_VOID, patched[3].opcode)
            assertEquals("$label $name: Instagram's code follows as it was", stock.map(::kind), patched.drop(4).map(::kind))
        }
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.stopSideTaps(context.findTapHandlers()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { m -> m.code().map(::kind) }
            assertEquals("${classDef.type} changed", classDef.methods.map { m -> m.code().map(::kind) }, after)
        }
    }

    /** An instruction's opcode and what it names, with every goto read as one. */
    private fun kind(instruction: Instruction): String {
        val opcode = when (instruction.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> instruction.opcode
        }
        return "$opcode ${instruction.referenceText().orEmpty()}"
    }

    /** The index of the instruction the branch at [at] jumps to. */
    private fun List<Instruction>.target(at: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        return address.indexOf(address[at] + (this[at] as OffsetInstruction).codeOffset)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.filter { it.opcode != Opcode.NOP }.orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private companion object {
        fun viewer(vararg methods: Method): ClassDef = ImmutableClassDef(
            STORY_VIEWER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList(),
        )

        /** A viewer method loading [marks], shaped like a tap handler unless told otherwise; [loops] ends it with a goto back to its start. */
        fun handler(
            name: String,
            vararg marks: String,
            flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            parameters: List<String> = TAP_PARAMETERS,
            loops: Boolean = false,
        ): Method {
            val loads = marks.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) }
            val end = if (loops) ImmutableInstruction10t(Opcode.GOTO, -marks.size * 2) else ImmutableInstruction10x(Opcode.RETURN_VOID)
            return ImmutableMethod(
                STORY_VIEWER, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
                ImmutableMethodImplementation(4, loads + end, null, null),
            )
        }
    }
}
