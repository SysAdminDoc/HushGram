/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide Suggested for you on follow lists, on the Follow requests screen's own list (#814): the check on
 * whether it has no suggested people is found and answered right before the branch past their rows,
 * whatever the reply carried, on every build.
 */
class RequestSectionHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REQUEST_SUGGESTIONS_EMPTY.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("not in the extension: $declared", REQUEST_SUGGESTIONS_EMPTY.substringAfter("->") in declared)
    }

    /**
     * The suggested people's check is answered, the requests' own check above it is left alone, and a
     * suggested person's row model, which keys its row too, isn't taken for the list.
     */
    @Test
    fun theSuggestedPeoplesCheckIsAnswered() {
        val context = PatchContexts.of(listOf(list(), rowModel()))
        val before = list().methods.single().code()

        val site = context.findRequestSection()
        assertEquals("the suggested people's move-result", SUGGESTED_RESULT, site.result)
        assertEquals("its register", 1, site.register)
        context.hideRequestSection(site)

        assertHooked("stand-in", before, context.mutableClassDefBy(LIST).methods.single().code(), site)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aListThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            listOf(list(withKey = false)) to "expected one list keying rows",
            listOf(list(), list(type = "Lfixture/OtherList;")) to "expected one list keying rows",
            listOf(list(keyBeforeCheck = true)) to "doesn't skip its",
            listOf(list(jumpToCheck = true)) to "jumps to the check",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findRequestSection() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertNull("$expected: warned and went on", context.findRequestSectionOrWarn())
            for (type in classes.map { it.type }) {
                assertTrue(
                    "$expected: something was written",
                    context.mutableClassDefBy(type).methods.single().code().none { it.referenceText() == REQUEST_SUGGESTIONS_EMPTY },
                )
            }
        }
    }

    @Test
    fun eachDeclaredBuildHidesTheSection() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHidesTheSectionToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** On a real build the list is the one that also keys the follow requests' own rows, and only it is hooked, once. */
    private fun builds(bundle: File, label: String) {
        val holders = FixtureDex.classesLoading(bundle, SUGGESTED_USER_ROW)
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))
        val site = context.findRequestSection()
        val method = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
        val before = method.code()
        assertTrue("$label: ${site.type} isn't the follow requests' list", context.loadsString(method, FOLLOW_REQUEST_ROW))

        context.hideRequestSection(site)

        assertHooked(label, before, method.code(), site)
        assertEquals(
            "$label: methods hooked", 1,
            holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == REQUEST_SUGGESTIONS_EMPTY } } },
        )
    }

    /** The list's isEmpty answer goes through the hook, back into its register, then the list's own branch reads it. */
    private fun assertHooked(label: String, before: List<Instruction>, after: List<Instruction>, site: FollowListSite) {
        val call = after[site.result + 1]
        assertEquals("$label: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$label: the hook", REQUEST_SUGGESTIONS_EMPTY, call.referenceText())
        assertEquals("$label: what it's handed", site.register, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, call.registerCount)
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, after[site.result + 2].opcode)
        assertEquals("$label: back in its register", site.register, (after[site.result + 2] as OneRegisterInstruction).registerA)
        assertEquals("$label: then the branch", Opcode.IF_NEZ, after[site.result + 3].opcode)
        assertEquals("$label: reading it", site.register, (after[site.result + 3] as OneRegisterInstruction).registerA)
        val kept = (after.take(site.result + 1) + after.drop(site.result + 3)).filter { it.opcode != Opcode.NOP }.map { it.kind() }
        assertEquals("$label: Instagram's own code is kept", before.filter { it.opcode != Opcode.NOP }.map { it.kind() }, kept)
    }

    /**
     * A stand-in for the Follow requests screen's list: it checks its requests, keys their rows, then
     * checks its suggested people and keys theirs, each check jumping past its rows when empty.
     */
    private fun list(
        type: String = LIST,
        withKey: Boolean = true,
        keyBeforeCheck: Boolean = false,
        jumpToCheck: Boolean = false,
    ): ClassDef {
        val key = if (withKey) SUGGESTED_USER_ROW else "item_key_suggested_users_header"
        val body = listOfNotNull(
            "iget-object v0, p0, $type->requests:Ljava/util/List;",
            "invoke-interface { v0 }, Ljava/util/List;->isEmpty()Z",
            "move-result v1",
            if (jumpToCheck) "if-nez v1, :check" else "if-nez v1, :suggested",
            "const-string v2, \"$FOLLOW_REQUEST_ROW\"",
            ":suggested",
            if (keyBeforeCheck) "const-string v2, \"$key\"" else "iget-object v0, p0, $type->suggested:Ljava/util/List;",
            "invoke-interface { v0 }, Ljava/util/List;->isEmpty()Z",
            "move-result v1",
            ":check",
            "if-nez v1, :done",
            if (keyBeforeCheck) "nop" else "const-string v2, \"$key\"",
            ":done",
            "return-object v0",
        ).joinToString("\n")
        val method = MutableMethod(
            ImmutableMethod(
                type, "invokeSuspend", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, ImmutableMethodImplementation(5, emptyList(), null, null),
            ),
        )
        method.addInstructionsWithLabels(0, body)
        val fields = listOf(
            ImmutableField(type, "requests", "Ljava/util/List;", AccessFlags.PUBLIC.value, null, null, null),
            ImmutableField(type, "suggested", "Ljava/util/List;", AccessFlags.PUBLIC.value, null, null, null),
        )
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, listOf(ImmutableMethod.of(method)))
    }

    /** A stand-in for a suggested person's row model, whose constructor keys its row like the list does. */
    private fun rowModel(): ClassDef {
        val type = "Lfixture/SuggestedRow;"
        val method = MutableMethod(
            ImmutableMethod(
                type, "<init>", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        )
        method.addInstructionsWithLabels(
            0,
            """
                invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                const-string v0, "$SUGGESTED_USER_ROW"
                return-void
            """.trimIndent(),
        )
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(method)))
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** An instruction's kind, a widened goto read as the goto it was. */
    private fun Instruction.kind(): Opcode = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    private companion object {
        const val LIST = "Lfixture/FollowRequests;"

        /** Where the stand-in's suggested people's isEmpty answer is: iget, invoke, move-result, if-nez, const-string, iget, invoke, then it. */
        const val SUGGESTED_RESULT = 7
    }
}
