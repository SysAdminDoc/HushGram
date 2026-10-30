/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShareSheetActionsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FILTER_ACTIONS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FILTER_ACTIONS is not in the extension: $declared", FILTER_ACTIONS.substringAfter("->") in declared)
    }

    /** The names come back from the picker, and the filter takes them before the builder reads them. */
    @Test
    fun theNamesAreFilteredRightAfterThePickerAnswers() {
        val context = PatchContexts.of(rowClasses())

        val site = context.findActionList()
        assertEquals("A00", site.name)
        assertEquals(4, site.moveResult)
        context.filterActionList(site)

        assertFiltered("stand-in", context.mutableClassDefBy(BUILDER).methods.single { it.name == "A00" }, site.moveResult)
    }

    /**
     * Two builders asking would leave one row unfiltered, so the picker is refused. The patch then
     * goes in without it, the group buttons still hidden.
     */
    @Test
    fun aSecondBuilderLeavesThePickerOut() {
        val context = PatchContexts.of(rowClasses(second = true))
        assertThrows(PatchException::class.java) { context.findActionList() }
        assertNull(context.findActionListOrWarn())
    }

    @Test
    fun noBuilderLeavesThePickerOut() {
        val context = PatchContexts.of(rowClasses(builds = false))
        assertThrows(PatchException::class.java) { context.findActionList() }
        assertNull(context.findActionListOrWarn())
    }

    /** A call the picker answers to something that isn't the names list (no copy_link in its class) isn't the row. */
    @Test
    fun aPickerWithoutCopyLinkLeavesThePickerOut() {
        val context = PatchContexts.of(rowClasses(picksCopyLink = false))
        assertThrows(PatchException::class.java) { context.findActionList() }
        assertNull(context.findActionListOrWarn())
    }

    @Test
    fun eachDeclaredBuildFiltersTheRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildFiltersTheRowToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = FixtureDex.classesHolding(bundle, ACTION_NAMES[2]).map { ImmutableClassDef.of(it) }
        val pickers = holders.flatMap { it.methods }
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.returnType == "Ljava/util/List;" && it.parameterTypes.size == 6 }
            .map { it.definingClass }.toSet()
        // Redex pools some strings differently in each build (#77), so the pools the classes ask come along.
        val kept = (holders + FixtureDex.classes(bundle, pickers).values.map { ImmutableClassDef.of(it) }).distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, kept))

        val site = context.findActionList()
        context.filterActionList(site)

        val method = context.mutableClassDefBy(site.type).methods
            .single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
        assertFiltered("$label ${site.type}->${site.name}", method, site.moveResult)
        // Download hides the button under both names it goes by (ShareSheetActions.ALSO_CALLED), so both
        // must still be cases of the builder's switch on the name's hash.
        val cases = method.implementation!!.instructions.filterIsInstance<SwitchPayload>()
            .flatMap { payload -> payload.switchElements.map { it.key } }.toSet()
        for (name in listOf("download_media", "clips_download")) {
            assertTrue("$label: the builder has no case for $name", name.hashCode() in cases)
        }
    }

    /** The picker's answer is taken, then handed to the filter, whose answer takes its place; one call in all. */
    private fun assertFiltered(what: String, method: Method, moveResult: Int) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == FILTER_ACTIONS })
        assertEquals("$what: the names", Opcode.MOVE_RESULT_OBJECT, code[moveResult].opcode)
        val register = (code[moveResult] as OneRegisterInstruction).registerA
        val call = code[moveResult + 1]
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$what: the call's method", FILTER_ACTIONS, (call as ReferenceInstruction).reference.toString())
        assertEquals("$what: the call's register", register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, call.registerCount)
        val back = code[moveResult + 2]
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, back.opcode)
        assertEquals("$what: into the same register", register, (back as OneRegisterInstruction).registerA)
    }

    private companion object {
        const val BUILDER = "Lfixture/RowBuilder;"
        const val BUILDER_2 = "Lfixture/RowBuilderToo;"
        const val PICKER = "Lfixture/RowPicker;"
        const val GEN_AI = "Lcom/instagram/direct/model/genai/GenAIParamsMetadata;"
        val PUBLIC = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun parameters(vararg types: String) = types.map { ImmutableMethodParameter(it, null, null) }

        fun builder(type: String, picksCopyLink: Boolean = true): ClassDef {
            val pick = ImmutableMethodReference(
                PICKER, "A03", listOf(GEN_AI, "Ljava/lang/Object;", "Ljava/lang/String;", "Z", "Z", "Z"), "Ljava/util/List;",
            )
            val code = ACTION_NAMES.mapIndexed { i, name -> ImmutableInstruction21c(Opcode.CONST_STRING, i, ImmutableStringReference(name)) } +
                listOf(
                    ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 0, 7, pick),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 9),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                )
            // The builder: three names loaded, then the picker asked, so the move-result is the fifth instruction.
            val method = ImmutableMethod(
                type, "A00", parameters("Ljava/lang/Object;"), "V", PUBLIC, null, null,
                ImmutableMethodImplementation(16, code, null, null),
            )
            return ImmutableClassDef(type, PUBLIC, "Ljava/lang/Object;", null, null, null, null, listOf(method))
        }

        fun picker(copyLink: Boolean): ClassDef {
            val code = listOfNotNull(
                if (copyLink) ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("copy_link")) else null,
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val method = ImmutableMethod(
                PICKER, "A03", parameters(GEN_AI, "Ljava/lang/Object;", "Ljava/lang/String;", "Z", "Z", "Z"), "Ljava/util/List;", PUBLIC, null, null,
                ImmutableMethodImplementation(8, code, null, null),
            )
            return ImmutableClassDef(PICKER, PUBLIC, "Ljava/lang/Object;", null, null, null, null, listOf(method))
        }

        fun rowClasses(second: Boolean = false, builds: Boolean = true, picksCopyLink: Boolean = true): List<ClassDef> =
            listOfNotNull(
                if (builds) builder(BUILDER) else null,
                if (second) builder(BUILDER_2) else null,
                picker(picksCopyLink),
            )
    }
}
