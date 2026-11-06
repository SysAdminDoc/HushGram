/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Show the Friends tab on Reels (#499) asks the extension where Instagram has just said a viewer gets
 * no tabs at the top. These hold that place on the real build: the no is the far side of the check
 * after the friends button's answer, and the hook sits in the no's own stretch of code, before its
 * jump on to the read of the viewer's source.
 */
class FriendsTabRowFixtureTest {
    @Test
    fun theExtensionTakesTheSourceAndAnswersWithABoolean() {
        val method = ExtensionDex.classDef(TOP_BAR).methods.single { it.name == "friendsTabAnyway" }
        assertEquals(listOf("Ljava/lang/Object;"), method.parameterTypes.map(Any::toString))
        assertEquals("Z", method.returnType)
        assertTrue("public", AccessFlags.PUBLIC.isSet(method.accessFlags))
        assertTrue("static", AccessFlags.STATIC.isSet(method.accessFlags))
        assertEquals(FRIENDS_ROW_HOOK, "$TOP_BAR->friendsTabAnyway(Ljava/lang/Object;)Z")
    }

    @Test
    fun eachDeclaredBuildAsksAtTheNo() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                asksAtTheNo(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksAtTheNoToo() {
        val builds = Fixtures.otherBuilds()
        for (apk in builds) asksAtTheNo(apk, apk.parentFile.name)
        assertTrue("no other build was read", builds.isNotEmpty())
    }

    private fun asksAtTheNo(bundle: File, label: String) {
        val classes = rowClasses(bundle)
        val context = PatchContexts.of(classes)
        val site = context.findFriendsRow()
        val before = site.method.code()

        // Stock: the no, then a jump to the read of the source that the yes falls into.
        val no = before[site.noAt]
        assertEquals("$label: no is zero", 0, (no as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: into the flag", site.flag, (no as OneRegisterInstruction).registerA)
        val flow = ControlFlow.of(site.method)
        assertEquals("$label: then the jump", Opcode.GOTO, kind(before[site.noAt + 1]))
        val readAt = flow.normal[site.noAt + 1].single()
        val yes = before[readAt - 1]
        assertEquals("$label: yes is one", 1, (yes as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$label: into the same flag", site.flag, (yes as OneRegisterInstruction).registerA)
        val read = before[readAt]
        assertEquals("$label: the source's read", Opcode.IGET_OBJECT, read.opcode)
        read as TwoRegisterInstruction
        assertEquals("$label: into the free register", site.source, read.registerA)
        assertEquals("$label: off the config", site.config, read.registerB)
        assertEquals("$label: the field", site.sourceField, (read as ReferenceInstruction).reference.toString())

        context.keepFriendsRow(site)
        val after = context.mutableClassDefBy(site.method.definingClass).methods.single {
            it.name == site.method.name && it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString)
        }.code()

        assertEquals("$label: three instructions added", before.size + 3, after.size)
        val load = after[site.noAt + 1]
        assertEquals("$label: reads the source", Opcode.IGET_OBJECT, load.opcode)
        load as TwoRegisterInstruction
        assertEquals("$label: into the free register", site.source, load.registerA)
        assertEquals("$label: off the config", site.config, load.registerB)
        val hook = after[site.noAt + 2]
        assertEquals("$label: the call", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$label: the hook", FRIENDS_ROW_HOOK, (hook as ReferenceInstruction).reference.toString())
        hook as RegisterRangeInstruction
        assertEquals("$label: with the source", site.source, hook.startRegister)
        assertEquals("$label: and nothing else", 1, hook.registerCount)
        val answer = after[site.noAt + 3]
        assertEquals("$label: takes the answer", Opcode.MOVE_RESULT, answer.opcode)
        assertEquals("$label: into the flag", site.flag, (answer as OneRegisterInstruction).registerA)
        assertEquals("$label: then the jump", Opcode.GOTO, kind(after[site.noAt + 4]))

        val rest = after.filterIndexed { at, _ -> at !in site.noAt + 1..site.noAt + 3 }
        assertEquals("$label: the rest as it was", before.map(::kind), rest.map(::kind))

        // The same classes without the one building the top bar state: nowhere to ask, so the patch fails.
        val without = PatchContexts.of(classes.filter { it.type != site.method.definingClass })
        val failure = assertThrows(PatchException::class.java) { without.findFriendsRow() }
        assertTrue("$label: ${failure.message}", failure.message!!.contains("found 0"))
    }

    /** The patcher may widen a jump the insert pushes past its reach, which changes nothing. */
    private fun kind(instruction: Instruction): Opcode = when (instruction.opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> instruction.opcode
    }

    private fun rowClasses(bundle: File): List<ClassDef> =
        (FixtureDex.classesLoading(bundle, FRIEND_LANE_BUTTON) + FixtureDex.classesLoading(bundle, TOP_BAR_STATE))
            .associateBy { it.type }.values.toList()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val TOP_BAR = "Lapp/hushgram/extension/instagram/reels/ReelsTopBar;"
    }
}
