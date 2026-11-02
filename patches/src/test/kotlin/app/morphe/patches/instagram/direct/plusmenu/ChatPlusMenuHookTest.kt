/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.plusmenu

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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat plus menu switches (#448): the composer's button list builder hands the menu's list and
 * the button map to the extension where its loop over the menu's buttons exits. Checked on every
 * declared build and every other build of the version: exactly one builder and loop are found, the
 * hook goes in once, on the loop's exit label, and nothing else moves.
 */
class ChatPlusMenuHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PLUS_MENU).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$MENU_HOOK is not in the extension: $declared", MENU_HOOK.substringAfter("->") in declared)
    }

    @Test
    fun withoutTheComposerThePatchRefuses() {
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(ExtensionDex.classDef(PLUS_MENU))).findPlusMenu()
        }
        assertTrue(refusal.message.orEmpty(), refusal.message.orEmpty().contains("composer button enums"))
    }

    @Test
    fun eachDeclaredBuildTakesTheHook() {
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
    fun eachOtherBuildTakesTheHookToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** The button enum, and every class that reads one of its constants. */
    private fun holders(bundle: File): List<ClassDef> {
        val enums = mutableSetOf<String>()
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                val names = candidate.methods.filter { it.name == "<clinit>" }
                    .flatMap { it.code() }.mapNotNull { it.string() }
                if (candidate.superclass == "Ljava/lang/Enum;" && names.containsAll(PLUS_BUTTON_NAMES)) enums += candidate.type
            }
        }
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (candidate in dex.classes) {
                val keep = candidate.type in enums || candidate.methods.any { method ->
                    method.code().any { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass in enums }
                }
                if (keep) kept += ImmutableClassDef.of(candidate)
            }
        }
        return kept
    }

    private fun builds(bundle: File, label: String) {
        val kept = holders(bundle)
        val context = PatchContexts.of(kept + ExtensionDex.classDef(PLUS_MENU))
        val site = context.findPlusMenu()
        val holder = kept.single { it.type == site.type }
        val own = holder.methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }.code()
        context.handPlusMenu(site)

        val patched = context.mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }
        val code = patched.code()
        assertEquals("$label: two more instructions", own.size + 2, code.size)
        val hook = code.indexOfFirst { it.reference() == MENU_HOOK }
        assertEquals("$label: one hook", 1, code.count { it.reference() == MENU_HOOK })
        assertEquals("$label: the hook is where the loop exits", site.at + 1, hook)
        assertEquals("$label: the map moves first", Opcode.MOVE_OBJECT_FROM16, code[site.at].opcode)
        assertEquals("$label: into the spare register", site.scratch, (code[site.at] as OneRegisterInstruction).registerA)
        assertEquals("$label: the original instruction follows", own[site.at].opcode, code[hook + 1].opcode)
        assertEquals("$label: nothing before the new code moved", own.take(site.at).map { it.opcode }, code.take(site.at).map { it.opcode })
        assertEquals("$label: nothing after it moved", own.drop(site.at).map { it.opcode }, code.drop(hook + 1).map { it.opcode })
        assertTrue("$label: the list and the spare register are low", site.list < 16 && site.scratch < 16)
        // The register handed on as the map still holds the builder's Map where the loop exits, not a
        // copy the loop's test has written over since.
        val writer = (site.at - 1 downTo 0).first { own[it].writes(site.map) }
        assertEquals("$label: the map register was last set by a call's answer", Opcode.MOVE_RESULT_OBJECT, own[writer].opcode)
        assertEquals("$label: a call answering a Map", "Ljava/util/Map;",
            ((own[writer - 1] as ReferenceInstruction).reference as MethodReference).returnType)
    }

    private fun Instruction.writes(register: Int): Boolean {
        val written = (this as? OneRegisterInstruction)?.registerA ?: return false
        return opcode.setsRegister() && written == register || opcode.setsWideRegister() && written + 1 == register
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
