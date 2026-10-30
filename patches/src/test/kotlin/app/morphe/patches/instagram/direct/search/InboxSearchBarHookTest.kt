/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.search

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.notes.HideNotesRowHookTest
import app.morphe.patches.instagram.direct.notes.INBOX_DIFF
import app.morphe.patches.instagram.direct.notes.findNotesSections
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the search bar in Messages asks where Hide the notes row does, and HideNotesRowHookTest
 * checks that question on every build. The extension then drops the section whose constant is named
 * InboxSearchBar.SEARCH. So on each build the whole patch goes in with its status on, asking once,
 * and the inbox's section enum still names a constant that, or the switch would quietly do nothing.
 */
class InboxSearchBarHookTest {
    private val search = ExtensionDex.stringConstant(INBOX_SEARCH_BAR, "SEARCH")

    @Test
    fun eachDeclaredBuildHidesTheSearchBar() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hides(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHidesTheSearchBarToo() {
        for (base in Fixtures.otherBuilds()) hides(base, base.parentFile.name)
    }

    /** A build without the inbox's diff update fails under this patch's name, before its status goes on. */
    @Test
    fun aBuildWithoutTheInboxFailsUnderItsOwnName() {
        val classes = HideNotesRowHookTest().fixtureClasses(Fixtures.otherBuilds().first())
            .filterNot { c -> c.methods.any { m -> m.code().any { it.string() == INBOX_DIFF } } }
        val context = PatchContexts.of(classes + ExtensionDex.classDef(SETTINGS_STATUS))
        val failure = assertThrows(PatchException::class.java) { hideInboxSearchPatch.execute(context) }
        assertTrue(failure.message, failure.message!!.startsWith("Hide the search bar in Messages: "))
        assertEquals("the status stays off", 0, context.status("inboxSearch"))
    }

    private fun hides(bundle: File, label: String) {
        val classes: List<ClassDef> = (HideNotesRowHookTest().fixtureClasses(bundle) + FixtureDex.classesLoading(bundle, search)).distinctBy { it.type }
        val context = PatchContexts.of(classes + ExtensionDex.classDef(SETTINGS_STATUS))
        val sections = context.findNotesSections().sections

        hideInboxSearchPatch.execute(context)

        assertEquals("$label: status", 1, context.status("inboxSearch"))
        assertEquals("$label: the notes row's status is still off", 0, context.status("notesRow"))
        val asking = classes.flatMap { context.mutableClassDefBy(it.type).methods }
            .filter { method -> method.code().any { it.referenceText() == KEEP_SEARCH_SECTIONS } }
        assertEquals("$label: methods asking", 1, asking.size)
        val names = context.classDefBy(sections).methods.single { it.name == "<clinit>" }.code()
        assertTrue("$label: $sections names no $search", names.indices.any { context.stringLoadedAt(names, it) == search })
    }

    private fun BytecodePatchContext.status(name: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.first { it.name == name }.code().first() as NarrowLiteralInstruction).narrowLiteral

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
