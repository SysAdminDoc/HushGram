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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** #302: Suggested for you under the mutual followers list, its adapter's read found and answered. */
class MutualSuggestionsHookTest {
    private val fragment = "Lfixture/MutualList;"
    private val adapter = "Lfixture/MutualAdapter;"
    private val other = "Lfixture/OtherAdapter;"
    private val isEmpty = "Ljava/util/List;->isEmpty()Z"

    /** The hook the patch writes is in the ProfileSuggestions the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(MUTUAL_SUGGESTIONS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$MUTUAL_SUGGESTIONS is not in the extension: $declared", MUTUAL_SUGGESTIONS.substringAfter("->") in declared)
    }

    /** The suggested accounts' read goes through the extension; the mutual followers' read and the fragment stay. */
    @Test
    fun theSuggestedAccountsReadIsAnswered() {
        val context = PatchContexts.of(classes())
        val before = classes().single { it.type == adapter }.methods.single().code().size

        context.hideMutualSuggestions(context.findMutualSuggestions())

        val patched = context.mutableClassDefBy(adapter).methods.single()
        assertAnswered("stand-in", patched)
        assertEquals("what else was added", before + 2, patched.code().size)
        assertTrue("the fragment", context.mutableClassDefBy(fragment).methods.single().code().none { it.referenceText() == MUTUAL_SUGGESTIONS })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(adapters = 2) to "expected one adapter behind the mutual list's $MUTUAL_ADAPTER checks, found [$adapter, $other]",
            classes(checks = false) to "expected one adapter behind the mutual list's $MUTUAL_ADAPTER checks, found []",
            classes(reads = 0) to "expected one read of the suggested accounts in $adapter, found 0",
            classes(reads = 2) to "expected one read of the suggested accounts in $adapter, found 2",
            classes(jumpIn = true) to "something in $adapter->A0C jumps past its read of suggested",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideMutualSuggestions(context.findMutualSuggestions())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == MUTUAL_SUGGESTIONS } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build every check of the mutual list's adapter reads one field type, and that
     * adapter's rows read the suggested accounts straight into `isEmpty()` once. On 452.1 that's
     * LX/0N6E->A0C()V, its A0H list, and the hook lands right after the read, before the check.
     */
    @Test
    fun eachDeclaredBuildHidesTheSection() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == MUTUAL_ADAPTER } }) holders += ImmutableClassDef.of(classDef)
                }
            }
            // The adapter loads no string of its own, so it comes in by the types the holders read.
            val read = holders.flatMap { it.methods }.flatMap { it.code() }
                .filter { it.opcode == Opcode.IGET_OBJECT }
                .map { ((it as ReferenceInstruction).reference as FieldReference).type }.toSet()
            val classes = holders.toMutableList()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.type in read && holders.none { it.type == classDef.type }) classes += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(classes)

            val site = context.findMutualSuggestions()
            val before = classes.single { it.type == site.type }.methods
                .single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }.code().size
            context.hideMutualSuggestions(site)

            assertEquals("${bundle.name}: the rows", "()", "(${site.parameters.joinToString("")})")
            val patched = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.isEmpty() }
            assertEquals("${bundle.name}: what else was added", before + 2, patched.code().size)
            assertAnswered(bundle.name, patched)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                classes.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == MUTUAL_SUGGESTIONS } } },
            )
        }
    }

    /** Right after a read of one of the adapter's lists: the range call with its register, the answer back in it, then `isEmpty()` on it. */
    private fun assertAnswered(what: String, method: Method) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == MUTUAL_SUGGESTIONS })
        val call = code.indexOfFirst { it.referenceText() == MUTUAL_SUGGESTIONS }
        val read = code[call - 1]
        assertEquals("$what: the read", Opcode.IGET_OBJECT, read.opcode)
        val field = (read as ReferenceInstruction).reference as FieldReference
        assertEquals("$what: the read's owner", method.definingClass, field.definingClass)
        assertEquals("$what: the read's type", "Ljava/util/List;", field.type)
        val register = (read as TwoRegisterInstruction).registerA
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[call].opcode)
        assertEquals("$what: what it's handed", register, (code[call] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
        assertEquals("$what: the register", register, (code[call + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the check", isEmpty, code[call + 2].referenceText())
        assertEquals("$what: what it checks", register, (code[call + 2] as FiveRegisterInstruction).registerC)
    }

    /**
     * Shaped like 452.1's: the mutual list checks its adapter is set before using it, naming it for
     * the error, and the adapter's rows count the mutual followers, then skip the suggested section
     * when its list is empty.
     */
    private fun classes(adapters: Int = 1, checks: Boolean = true, reads: Int = 1, jumpIn: Boolean = false): List<ClassDef> {
        val check = { field: String, type: String ->
            listOf(
                "iget-object v0, p0, $fragment->$field:$type",
                "if-nez v0, :set$field",
                "const-string v1, \"$MUTUAL_ADAPTER\"",
                "invoke-static {v1}, Lfixture/Checks;->fail(Ljava/lang/String;)V",
                ":set$field",
            ).joinToString("\n")
        }
        val onCreate = method(
            fragment, "onCreate", listOf("Landroid/os/Bundle;"), "V", 2,
            listOfNotNull(
                if (checks) check("adapter", adapter) else "const-string v1, \"$MUTUAL_ADAPTER\"",
                if (checks && adapters == 2) check("other", other) else null,
                "return-void",
            ).joinToString("\n"),
        )
        val rows = method(
            adapter, "A0C", emptyList(), "V", 2,
            listOfNotNull(
                "iget-object v0, p0, $adapter->mutuals:Ljava/util/List;",
                if (reads == 2) "invoke-interface {v0}, $isEmpty" else "invoke-interface {v0}, Ljava/util/List;->size()I",
                "move-result v1",
                if (jumpIn) "if-eqz v1, :check" else null,
                "iget-object v0, p0, $adapter->suggested:Ljava/util/List;",
                if (jumpIn) ":check" else null,
                if (reads == 0) "invoke-interface {v0}, Ljava/util/List;->size()I" else "invoke-interface {v0}, $isEmpty",
                "move-result v1",
                "if-nez v1, :done",
                "invoke-interface {v0}, Ljava/util/List;->size()I",
                ":done",
                "return-void",
            ).joinToString("\n"),
        )
        val lists = listOf("mutuals", "suggested").map { ImmutableField(adapter, it, "Ljava/util/List;", AccessFlags.PUBLIC.value, null, null, null) }
        return listOf(
            classDef(fragment, listOf(onCreate)),
            classDef(adapter, listOf(rows), lists),
            classDef(other, emptyList()),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(registers + parameters.size + 1, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, fields, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
