/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settingsrows

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rows in Instagram's settings (#235): each method making a settings row asks the extension first
 * with its row model and hands it each row it makes, and the stubs read the row ids, the title and
 * Instagram's renderer. Checked on every declared build and every other build of the version.
 */
class HideSettingsRowsHookTest {
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(SETTINGS_ROWS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val wanted = listOf(
            ROW_HIDDEN.substringAfter("->"), ROW_BUILT.substringAfter("->"),
            "idOfModel(Ljava/lang/Object;)Ljava/lang/Object;", "idOfRow(Ljava/lang/Object;)Ljava/lang/Object;",
            "titleOfRow(Ljava/lang/Object;)Ljava/lang/Object;", "text($RESOURCES" + "Ljava/lang/Object;)Ljava/lang/String;",
        )
        for (method in wanted) assertTrue("$method is not in the extension: $declared", method in declared)
    }

    @Test
    fun withoutTheRowThePatchRefuses() {
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_ROWS))).findSettingsRows()
        }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("expected one row class"))
    }

    @Test
    fun eachDeclaredBuildHidesSettingsRows() {
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
    fun eachOtherBuildHidesSettingsRowsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val rows = FixtureDex.classesLoading(bundle, ROW_TEXT)
        val uiState = FixtureDex.classes(bundle, setOf(UI_STATE)).values
        assertTrue("$label: no $UI_STATE", uiState.isNotEmpty())
        val parameters = uiState.flatMap { holder -> holder.methods.flatMap { it.parameterTypes.map(Any::toString) } }
        // The row's toString can ask a Redex string pool for its ", label=" (385914510 does), so those come too.
        val pools = rows.flatMap { row -> row.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() } }.mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                ?.takeIf { it.parameterTypes.map(Any::toString) == listOf("I") && it.returnType == "Ljava/lang/String;" }?.definingClass
        }
        val firstTypes = (parameters + rows.flatMap { row -> row.fields.map { it.type } } + pools + FBT_MODEL + FBT_TOKEN)
            .filter { it.startsWith("L") }.toSet()
        val first = FixtureDex.classes(bundle, firstTypes).values
        // The id is an interface, so the fields of the row models are read too.
        val fieldTypes = first.flatMap { model -> model.fields.map { it.type } }.filter { it.startsWith("L") }.toSet()
        val renderers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val renders = classDef.methods.any { method ->
                    method.returnType == "Ljava/lang/String;" && method.parameterTypes.map(Any::toString) == listOf(RESOURCES, FBT_MODEL) &&
                        method.implementation?.instructions?.any { instruction ->
                            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == FBT_TOKEN
                        } == true
                }
                if (renders) renderers += ImmutableClassDef.of(classDef)
            }
        }
        val kept = rows + uiState + first + FixtureDex.classes(bundle, fieldTypes).values + renderers
        val context = PatchContexts.of(kept.distinctBy { it.type } + ExtensionDex.classDef(SETTINGS_ROWS))

        val found = context.findSettingsRows()
        context.hideSettingsRows(found)

        assertTrue("$label: no builder", found.builders.isNotEmpty())
        for (builder in found.builders) {
            val method = builder.method
            val code = method.implementation!!.instructions.toList()
            val where = "$label ${method.name}"
            assertEquals("$where: the ask", ROW_HIDDEN, (code[0] as ReferenceInstruction).reference.toString())
            val ask = code[0] as RegisterRangeInstruction
            assertEquals("$where: one register", 1, ask.registerCount)
            val at = method.parameterTypes.map(Any::toString).indexOf(found.model)
            assertEquals("$where: the row model", method.implementation!!.registerCount - method.parameterTypes.size + at, ask.startRegister)
            assertEquals(
                "$where: answer null on a yes",
                listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                code.subList(1, 5).map { it.opcode },
            )
            val built = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == ROW_BUILT }
            assertEquals("$where: one row handed on", 1, built.size)
            val made = code[built.single() - 1]
            val constructor = (made as ReferenceInstruction).reference as MethodReference
            assertEquals("$where: right after the row is made", "${found.row}-><init>", "${constructor.definingClass}->${constructor.name}")
            assertEquals("$where: the row made", (made as RegisterRangeInstruction).startRegister, (code[built.single()] as RegisterRangeInstruction).startRegister)
        }
        assertTrue("$label: the id is an interface", AccessFlags.INTERFACE.isSet(context.classDefBy(found.modelId.type).accessFlags))
        assertEquals("$label: one id type", found.modelId.type, found.rowId.type)
        assertEquals("$label: the title", FBT_MODEL, found.rowTitle.type)

        val stubs = context.mutableClassDefBy(SETTINGS_ROWS).methods
        fun read(name: String) = stubs.single { it.name == name }.implementation!!.instructions
            .first { it.opcode == Opcode.IGET_OBJECT }.let { (it as ReferenceInstruction).reference.toString() }
        assertEquals("$label: the model's id", found.modelId.toString(), read("idOfModel"))
        assertEquals("$label: the row's id", found.rowId.toString(), read("idOfRow"))
        assertEquals("$label: the row's title", found.rowTitle.toString(), read("titleOfRow"))
        val text = stubs.single { it.name == "text" }.implementation!!.instructions
            .first { it.opcode == Opcode.INVOKE_STATIC }.let { (it as ReferenceInstruction).reference as MethodReference }
        assertEquals("$label: the renderer", found.renderer.definingClass, text.definingClass)
        assertEquals("$label: the renderer's name", found.renderer.name, text.name)
    }
}
