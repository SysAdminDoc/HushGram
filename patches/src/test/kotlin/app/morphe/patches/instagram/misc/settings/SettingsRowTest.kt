/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HushGram settings row, put in front of each view Instagram's settings screen answers. */
class SettingsRowTest {
    private val getArguments = "Landroidx/fragment/app/Fragment;->getArguments()Landroid/os/Bundle;"
    private val withSettingsRow = "$ENTRY->withSettingsRow(Landroid/os/Bundle;Landroid/view/View;)Landroid/view/View;"

    /** The hook the patch writes is in the SettingsEntry the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(ENTRY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$ENTRY->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("SettingsEntry declares no public static $withSettingsRow", withSettingsRow in declared)
    }

    /**
     * A branch that jumped to the return lands on the first injected instruction, which took the
     * return's place, so the view it returns gets the row too.
     */
    @Test
    fun aReturnABranchJumpsToGetsTheRowToo() {
        val context = PatchContexts.of(listOf(SettingsPatchHosts.settingsScreen(branched = true)))

        context.addSettingsRow()

        val createView = context.mutableClassDefBy(SettingsPatchHosts.SETTINGS_SCREEN).methods.single { it.name == "onCreateView" }
        val instructions = createView.implementation!!.instructions
        assertEquals(listOf(Opcode.NEW_INSTANCE, Opcode.IF_EQZ, Opcode.NEW_INSTANCE) + injected(), instructions.map { it.opcode })
        val branch = instructions[1] as BuilderOffsetInstruction
        assertEquals("the branch skips the row", 3, branch.target.location.index)
        assertInjectedAt(createView, 3, view = 1)
    }

    @Test
    fun withoutTheSettingsScreenThePatchFails() {
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(SettingsPatchHosts.all().filter { it.type != SettingsPatchHosts.SETTINGS_SCREEN }).addSettingsRow()
        }
        assertTrue(failure.message, failure.message!!.contains("expected a settings screen factory in this Instagram build, found none"))
    }

    /**
     * In each declared build the factory is found on Instagram's own SettingsScreenFragment, by the
     * name Redex keeps for it, and every view its onCreateView answers goes through the hook with
     * `this` read from p0 and the view's own register.
     */
    @Test
    fun eachDeclaredBuildGetsTheRowOnItsSettingsScreen() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val candidates = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (dex.stringSection.none { it == "new_settings_session" }) return@forEach
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.instructions().any { it.string() == "new_settings_session" } }) {
                            candidates += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                // 449's factory is a static on the screen itself; 450's makes the screen from
                // another class, so the classes the factory makes come along.
                val factories = candidates.flatMap { it.methods }.filter { method ->
                    method.instructions().any { it.string() == "screen_id" } &&
                        method.instructions().any { it.string() == "new_settings_session" }
                }
                val made = factories.flatMap { method ->
                    method.instructions().filter { it.opcode == Opcode.NEW_INSTANCE }
                        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                }.toSet()
                candidates += FixtureDex.classes(bundle, made - candidates.map { it.type }.toSet()).values
                val context = PatchContexts.of(candidates)

                context.addSettingsRow()

                val screen = candidates.filter { it.type in made }.single { classDef -> classDef.methods.any { it.name == "onCreateView" } }
                val originalName = screen.staticFields.single { it.name == "__redex_internal_original_name" }.initialValue
                assertEquals("${bundle.name}: the screen found", "SettingsScreenFragment", (originalName as StringEncodedValue).value)

                val before = screen.methods.single { it.name == "onCreateView" }.instructions()
                val returns = before.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
                assertTrue("${bundle.name}: onCreateView returns nothing", returns.isNotEmpty())
                val after = context.mutableClassDefBy(screen.type).methods.single { it.name == "onCreateView" }
                assertEquals("${bundle.name}: instruction count", before.size + 4 * returns.size, after.instructions().size)
                returns.forEachIndexed { shift, (index, instruction) ->
                    assertInjectedAt(after, index + 4 * shift, (instruction as OneRegisterInstruction).registerA)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun injected() = listOf(
        Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT,
    )

    /** The five instructions from [at]: getArguments on p0, the hook with the view, and the return. */
    private fun assertInjectedAt(method: Method, at: Int, view: Int) {
        val code = method.instructions().drop(at).take(5)
        val where = "${method.definingClass}->onCreateView at $at"
        assertEquals(where, injected(), code.map { it.opcode })
        val self = method.implementation!!.registerCount - 4
        val (arguments, moveArguments, hook, moveView, returned) = code
        assertEquals("$where: this", self, (arguments as RegisterRangeInstruction).startRegister)
        assertEquals("$where: this, once", 1, arguments.registerCount)
        assertEquals(where, getArguments, (arguments as ReferenceInstruction).reference.toString())
        val bundle = (moveArguments as OneRegisterInstruction).registerA
        assertTrue("$where: the arguments went into a parameter's register", bundle < self)
        assertEquals(where, withSettingsRow, (hook as ReferenceInstruction).reference.toString())
        val hookRegisters = (hook as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD).take(it.registerCount) }
        assertEquals("$where: the hook's arguments", listOf(bundle, view), hookRegisters)
        assertEquals("$where: the view taken back", view, (moveView as OneRegisterInstruction).registerA)
        assertEquals("$where: the view returned", view, (returned as OneRegisterInstruction).registerA)
    }

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
