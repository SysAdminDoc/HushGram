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
 * Hide Suggested for you on follow lists, on the friending center's Follow requests page that a follow
 * request notification opens (#814): the list's read of its page and its lookup of each unit's name are
 * found and handed to the extension, on every build, and Instagram's own code is kept.
 */
class FriendingRequestsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FRIENDING_UNIT.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(FRIENDING_UNIT, FRIENDING_CENTER_MODE)) {
            assertTrue("not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The page is kept right after the list reads it, and each unit's name goes through the extension
     * before the lookup. The empty header's row model, which keys its row too, isn't taken for the list.
     */
    @Test
    fun thePageAndEachUnitAreHandedOver() {
        val context = PatchContexts.of(listOf(list(), namer(), rowModel()))
        val before = list().methods.single().code()

        val site = context.findFriendingRequests()
        assertEquals("the page's read", 0, site.mode)
        assertEquals("its register", 0, site.modeRegister)
        assertEquals("the unit lookup", LOOKUP, site.lookup)
        assertEquals("the unit's register", 3, site.unitRegister)
        context.hideFriendingRequests(site)

        assertHooked("stand-in", before, context.mutableClassDefBy(LIST).methods.single().code(), site)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aListThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            listOf(list(withHeader = false), namer()) to "expected one list loading",
            listOf(list(), list(type = "Lfixture/OtherList;"), namer()) to "expected one list loading",
            listOf(list(), namer(knowsRequests = false)) to "doesn't look its units up once",
            listOf(list(skipsUnknown = false), namer()) to "doesn't look its units up once",
            listOf(list(jumpToLookup = true), namer()) to "jumps to its unit lookup",
            listOf(list(comparesPage = false), namer()) to "doesn't compare one page mode",
            listOf(list(readsPageFirst = false), namer()) to "doesn't read its page mode before",
            listOf(list(jumpPastPage = true), namer()) to "jumps past its read of the page mode",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findFriendingRequests() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertNull("$expected: warned and went on", context.findFriendingRequestsOrWarn())
            for (type in classes.map { it.type }) {
                assertTrue(
                    "$expected: something was written",
                    context.mutableClassDefBy(type).methods.all { method -> method.code().none { it.referenceText() in HOOKS } },
                )
            }
        }
    }

    @Test
    fun eachDeclaredBuildHidesThePagesSuggestions() {
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

    /** On a real build the list is hooked once, at its page read and its one unit lookup, and nothing else is. */
    private fun builds(bundle: File, label: String) {
        val holders = (FixtureDex.classesLoading(bundle, EMPTY_REQUESTS_HEADER) + FixtureDex.classesLoading(bundle, FOLLOW_REQUESTS_PAGE))
            .distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))
        val site = context.findFriendingRequests()
        val method = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
        val before = method.code()
        assertTrue("$label: the page is read before its units are looked up", site.mode < site.lookup)

        context.hideFriendingRequests(site)

        assertHooked(label, before, method.code(), site)
        for (hook in HOOKS) {
            assertEquals(
                "$label: calls to $hook", 1,
                holders.map { it.type }.distinct().sumOf { type ->
                    context.mutableClassDefBy(type).methods.sumOf { m -> m.code().count { it.referenceText() == hook } }
                },
            )
        }
    }

    /**
     * The page goes to the extension right after its read, from the read's own register; the unit's
     * name goes to the extension and back into its register, then the list's own lookup takes it.
     */
    private fun assertHooked(label: String, before: List<Instruction>, after: List<Instruction>, site: FriendingRequestsSite) {
        val keep = after[site.mode + 1]
        assertEquals("$label: the page's hook", FRIENDING_CENTER_MODE, keep.referenceText())
        assertEquals("$label: handed the page", site.modeRegister, (keep as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, keep.registerCount)

        val unit = after[site.lookup + 1]
        assertEquals("$label: the unit's hook", FRIENDING_UNIT, unit.referenceText())
        assertEquals("$label: handed the name", site.unitRegister, (unit as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, unit.registerCount)
        assertEquals("$label: the answer", Opcode.MOVE_RESULT_OBJECT, after[site.lookup + 2].opcode)
        assertEquals("$label: back in its register", site.unitRegister, (after[site.lookup + 2] as OneRegisterInstruction).registerA)
        assertEquals("$label: then the lookup", before[site.lookup].referenceText(), after[site.lookup + 3].referenceText())

        val added = setOf(site.mode + 1, site.lookup + 1, site.lookup + 2)
        val kept = after.filterIndexed { at, _ -> at !in added }.filter { it.opcode != Opcode.NOP }.map { it.kind() }
        assertEquals("$label: Instagram's own code is kept", before.filter { it.opcode != Opcode.NOP }.map { it.kind() }, kept)
    }

    /**
     * A stand-in for the friending center's list: it reads its page and compares it with Suggested for
     * you, looks up a unit's name and skips the unit when the lookup doesn't know it, then on the
     * Follow requests page with nothing on it keys the empty header.
     */
    private fun list(
        type: String = LIST,
        withHeader: Boolean = true,
        skipsUnknown: Boolean = true,
        jumpToLookup: Boolean = false,
        comparesPage: Boolean = true,
        readsPageFirst: Boolean = true,
        jumpPastPage: Boolean = false,
    ): ClassDef {
        val header = if (withHeader) EMPTY_REQUESTS_HEADER else "item_key_follow_requests_header"
        val body = listOfNotNull(
            if (readsPageFirst) "iget-object v0, p0, $type->page:Ljava/lang/String;" else "const-string v0, \"\"",
            if (jumpPastPage) ":page" else null,
            "const-string v1, \"suggested_users\"",
            "invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "move-result v2",
            "iget-object v3, p0, $type->unit:Ljava/lang/String;",
            if (jumpToLookup) ":lookup" else null,
            "invoke-static { v3 }, $NAMER->A00(Ljava/lang/String;)Ljava/lang/Integer;",
            "move-result-object v3",
            if (skipsUnknown) "if-eqz v3, :done" else "if-nez v2, :done",
            if (jumpToLookup) "if-eqz v2, :lookup" else null,
            if (jumpPastPage) "if-eqz v2, :page" else null,
            if (comparesPage) "iget-object v0, p0, $type->page:Ljava/lang/String;" else "const-string v0, \"\"",
            "const-string v1, \"$FOLLOW_REQUESTS_PAGE\"",
            "invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "move-result v2",
            "if-eqz v2, :done",
            "const-string v1, \"$header\"",
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
            ImmutableField(type, "page", "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null, null),
            ImmutableField(type, "unit", "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null, null),
        )
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, listOf(ImmutableMethod.of(method)))
    }

    /** A stand-in for the lookup naming the units the list knows: an Integer for a name it knows, null otherwise. */
    private fun namer(knowsRequests: Boolean = true): ClassDef {
        val name = if (knowsRequests) FOLLOW_REQUESTS_PAGE else "follow_back"
        val method = MutableMethod(
            ImmutableMethod(
                NAMER, "A00", listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)), "Ljava/lang/Integer;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        )
        method.addInstructionsWithLabels(
            0,
            """
                const-string v0, "$name"
                invoke-virtual { p0, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :unknown
                const/4 v0, 0x1
                invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v0
                return-object v0
                :unknown
                const/4 v0, 0x0
                return-object v0
            """.trimIndent(),
        )
        return ImmutableClassDef(NAMER, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(method)))
    }

    /** A stand-in for the empty header's row model, whose constructor keys its row like the list does. */
    private fun rowModel(): ClassDef {
        val type = "Lfixture/EmptyRequestsHeader;"
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
                const-string v0, "$EMPTY_REQUESTS_HEADER"
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
        const val LIST = "Lfixture/FriendingCenterList;"
        const val NAMER = "Lfixture/FriendingUnits;"
        val HOOKS = listOf(FRIENDING_UNIT, FRIENDING_CENTER_MODE)

        /** Where the stand-in looks up its unit: iget, const-string, invoke, move-result, iget, then the lookup. */
        const val LOOKUP = 5
    }
}
