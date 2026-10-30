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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide Complete your profile, Hide suggested people on profiles' third switch (#159): the cards' check, found and answered first. */
class ProfileCompletionHookTest {
    private val tab = "Lfixture/ProfileTab;"
    private val user = "Lfixture/User;"
    private val binder = "Lfixture/CardBinder;"
    private val other = "Lfixture/OtherTab;"

    /** The hook the patch writes is in the ProfileSuggestions the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(COMPLETION_CARDS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMPLETION_CARDS is not in the extension: $declared", COMPLETION_CARDS.substringAfter("->") in declared)
    }

    /** The check asks the extension first, and a no runs everything it did before, untouched. */
    @Test
    fun theCheckAsksFirst() {
        val context = PatchContexts.of(classes())
        val before = classes().single { it.type == tab }.methods.single { it.name == "A02" }.code().map { it.opcode }

        val gate = context.findCompletionGate()
        context.hideCompletionCards(gate)

        assertEquals("$tab A02($user)Z", "${gate.type} ${gate.name}(${gate.parameters.joinToString("")})Z")
        val patched = context.mutableClassDefBy(tab).methods.single { it.name == "A02" }
        assertAsksFirst("stand-in", patched)
        assertEquals("the check after it", before, patched.code().drop(5).map { it.opcode })
        assertTrue("the other check", context.mutableClassDefBy(tab).methods.single { it.name == "A03" }.code().none { it.referenceText() == COMPLETION_CARDS })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(literals = listOf(COUNTS_ID, FIRST_COUNT)) to "expected one check deciding the Complete your profile cards, found 0",
            classes(most = 3000) to "expected one check deciding the Complete your profile cards, found 0",
            classes(twice = true) to "expected one check deciding the Complete your profile cards, found 2",
            classes(binds = false) to "$tab doesn't make the Complete your profile cards' binder",
            classes(binderShipped = false) to "$tab doesn't make the Complete your profile cards' binder",
            classes(loopsToStart = true) to "something jumps back to the first instruction of $tab->A02",
            classes(locals = 0) to "Hide suggested people on profiles: $tab->A02 has 0 local register(s), needs 1",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideCompletionCards(context.findCompletionGate())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == COMPLETION_CARDS } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build there's one check loading the four numbers, in a class whose
     * constructor makes the cards' binder, and it asks first. On 450's 385611438 that's
     * LX/0806;->A02(LX/0Ha3;)Z, which the profile's posts tabs call before adding the cards.
     */
    @Test
    fun eachDeclaredBuildAsksFirst() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == FIRST_COUNT } }) {
                        holders += ImmutableClassDef.of(classDef)
                    }
                }
            }
            val made = holders.flatMap { holder -> holder.methods.filter { it.name == "<init>" } }
                .flatMap { it.code() }
                .filter { it.opcode == Opcode.NEW_INSTANCE }
                .mapTo(HashSet()) { ((it as ReferenceInstruction).reference as TypeReference).type }
            val context = PatchContexts.of(holders + FixtureDex.classes(bundle, made - holders.map { it.type }.toSet()).values)

            val gate = context.findCompletionGate()
            context.hideCompletionCards(gate)

            assertEquals("${bundle.name}: what the check takes", 1, gate.parameters.size)
            val patched = context.mutableClassDefBy(gate.type).methods.single { it.name == gate.name && it.parameterTypes.map(CharSequence::toString) == gate.parameters }
            assertAsksFirst(bundle.name, patched)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == COMPLETION_CARDS } } },
            )
        }
    }

    /** First thing in the check: the call, its answer, a no going on to the check and a yes answering false. */
    private fun assertAsksFirst(what: String, method: Method) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == COMPLETION_CARDS })
        assertEquals("$what: the call", COMPLETION_CARDS, code[0].referenceText())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        val register = (code[1] as OneRegisterInstruction).registerA
        assertEquals("$what: a no", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: what it tests", register, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: a yes", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: answered", Opcode.RETURN, code[4].opcode)
        assertEquals("$what: what it answers", (code[3] as OneRegisterInstruction).registerA, (code[4] as OneRegisterInstruction).registerA)
    }

    /**
     * Shaped like 450's: the posts tab's helper reads your account's counts by an id, compares two
     * of them to 3500 and 6, and its constructor makes the binder that draws the cards. Another check
     * in the class loads only some of the numbers.
     */
    private fun classes(
        literals: List<Int> = listOf(COUNTS_ID, FIRST_COUNT, SECOND_COUNT),
        most: Int = MOST_OF_THE_FIRST,
        twice: Boolean = false,
        binds: Boolean = true,
        binderShipped: Boolean = true,
        loopsToStart: Boolean = false,
        locals: Int = 2,
    ): List<ClassDef> {
        // With no locals, v0 and v1 are the parameters: still code the patch reads, never run.
        fun check(name: String) = method(
            tab, name, listOf(user), "Z", locals, AccessFlags.PUBLIC.value,
            listOfNotNull(
                if (loopsToStart) ":start" else null,
                *literals.map { "const v1, $it" }.toTypedArray(),
                "const/16 v0, $most",
                "if-gt v1, v0, :no",
                if (loopsToStart) "if-eqz v1, :start" else null,
                "const/4 v0, 0x1",
                "return v0",
                ":no",
                "const/4 v0, 0x0",
                "return v0",
            ).joinToString("\n"),
        )
        val checks = listOfNotNull(
            check("A02"),
            if (twice) check("A04") else null,
            method(tab, "A03", listOf(user), "Z", 2, AccessFlags.PUBLIC.value, "const v1, $FIRST_COUNT\nconst/4 v0, 0x0\nreturn v0"),
        )
        val init = method(
            tab, "<init>", emptyList(), "V", 1, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            "new-instance v0, $binder\ninvoke-direct {v0}, $binder-><init>()V\nreturn-void",
        )
        val binderMethods = listOfNotNull(
            method(binder, "<init>", emptyList(), "V", 0, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, "return-void"),
            method(binder, "createView", listOf("I", "Landroid/view/ViewGroup;"), "Landroid/view/View;", 1, AccessFlags.PUBLIC.value, "const/4 v0, 0x0\nreturn-object v0"),
            if (binds) method(binder, "bindView", listOf("I", "Landroid/view/View;", "Ljava/lang/Object;", "Ljava/lang/Object;"), "V", 0, AccessFlags.PUBLIC.value, "return-void") else null,
        )
        val unrelated = method(other, "A00", listOf(user), "Z", 2, AccessFlags.PUBLIC.value, "const v1, $COUNTS_ID\nconst/4 v0, 0x0\nreturn v0")
        return listOfNotNull(
            classDef(tab, checks + init),
            if (binderShipped) classDef(binder, binderMethods) else null,
            classDef(other, listOf(unrelated)),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + parameters.size + if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
}
