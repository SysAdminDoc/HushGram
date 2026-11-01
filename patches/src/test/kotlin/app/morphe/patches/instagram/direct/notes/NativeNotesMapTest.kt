/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.notes

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Map bubble's hooks on each of 453's eight builds: the composables' list, the views' list and the
 * first-run tip each read the same answer, each test of it gets the hook right in front, and nothing
 * else in those methods moves.
 */
class NativeNotesMapTest {
    @Test fun everyBuildAsksBeforeAddingTheMapBubble() {
        val checked = mutableListOf<String>()
        for (bundle in bundles()) {
            val name = bundle.absolutePath
            val context = PatchContexts.of(slice(bundle) + ExtensionDex.classDef(NOTES_ROW))
            val sites = context.findNotesMapSites()
            assertEquals("$name: three different places ask", 3, sites.map { it.type to it.name }.toSet().size)
            for (site in sites) {
                val builder = context.mutableClassDefBy(site.type).methods.single {
                    it.name == site.name && it.parameterTypes.map(Any::toString) == site.parameters
                }
                val stock = NeutralNativePath(builder)
                val before = builder.code()
                val test = before[site.test].opcode
                assertEquals("$name: the answer is read right before the test", Opcode.IGET_BOOLEAN, before[site.test - 1].opcode)

                context.hideNotesMap(site)

                val hooked = context.mutableClassDefBy(site.type).methods.single {
                    it.name == site.name && it.parameterTypes.map(Any::toString) == site.parameters
                }
                val after = hooked.code()
                assertEquals("$name: one hook in ${site.type}", 1, after.count { it.referenceText() == MAP_SHOWN })
                assertEquals("$name: the hook is right before the test", MAP_SHOWN, after[site.test].referenceText())
                assertEquals("$name: it's given the answer", site.register, (after[site.test] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: and its answer replaces it", Opcode.MOVE_RESULT, after[site.test + 1].opcode)
                assertEquals("$name: in the same register", site.register, (after[site.test + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the test follows", test, after[site.test + 2].opcode)
                assertEquals("$name: on that register", site.register, (after[site.test + 2] as OneRegisterInstruction).registerA)
                stock.assertPreserved(name, hooked, setOf(site.test, site.test + 1))
            }
            checked += name
        }
        for (code in BUILDS) assertTrue("no fixture of build $code among $checked", checked.any { it.contains("-$code") })
    }

    /** A build without the bubble's content refuses under the patch's name before anything changes. */
    @Test fun aBuildWithoutTheBubblesContentRefuses() {
        val bundle = bundles().first()
        val classes = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, NOTES_TRAY_ITEMS))
        val refused = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findNotesMap() }
        assertTrue(refused.message, refused.message!!.startsWith("Hide the notes row: "))
    }

    private companion object {
        val BUILDS = listOf(385914557, 385914503, 385914510, 385914517, 385914518, 385914546, 385914556, 385914558)

        fun slice(bundle: File): List<ClassDef> {
            val markers = listOf(NOTES_TRAY_ITEMS, NOTES_MAP_CONTENT, NOTES_MAP_ROW).flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }
            val answer = PatchContexts.of(FixtureDex.withStringPools(bundle, markers)).findNotesMap().answer
            val readers = FixtureDex.methodsWhere(bundle, { true }) { method ->
                method.implementation?.instructions?.any {
                    val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                    it.opcode == Opcode.IGET_BOOLEAN && field?.definingClass == answer.definingClass && field.name == answer.name
                } == true
            }.map { it.definingClass }.toSet()
            return FixtureDex.withStringPools(bundle, (markers + FixtureDex.classes(bundle, readers).values).distinctBy { it.type })
        }

        fun bundles(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
                Fixtures.otherBuilds()
        }

        fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

        fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
    }
}
