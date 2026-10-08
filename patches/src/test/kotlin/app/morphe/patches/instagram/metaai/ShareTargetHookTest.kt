/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

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
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide Meta AI in the share sheet: the answer of the target builder's "hatch" check goes through the hook. */
class ShareTargetHookTest {
    private val builder = "Lfixture/ShareSheetTargets;"
    private val other = "Lfixture/HatchSettings;"
    private val parameters = listOf("Lfixture/ShareSheet;", "Ljava/lang/Object;")

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SHARE_TARGET.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SHARE_TARGET is not in the extension: $declared", SHARE_TARGET.substringAfter("->") in declared)
    }

    /** Only the case's answer is asked about; the later check that moves a hatch target and other classes stay. */
    @Test
    fun theHatchCaseAsks() {
        val classes = classes()
        val context = PatchContexts.of(classes)

        context.holdShareTarget(context.findShareTargetCheck())

        val code = context.mutableClassDefBy(builder).methods.single().code()
        assertAsked("the stand-in", code)
        assertEquals("one ask", 1, code.count { it.referenceText() == SHARE_TARGET })
        assertTrue(
            "a class that isn't the builder was touched",
            context.mutableClassDefBy(other).methods.single().code().none { it.referenceText() == SHARE_TARGET },
        )
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(names = SHARE_ROW_NAMES - "whatsapp_status") to "0 methods hold",
            classes(builders = 2) to "2 methods hold",
            classes(cases = 0) to "compares 0 target names",
            classes(cases = 2) to "compares 2 target names",
            classes(enters = true) to "a branch enters",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.findShareTargetCheck() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.flatMap { owner -> context.mutableClassDefBy(owner.type).methods }
                .filter { method -> method.code().any { it.referenceText() == SHARE_TARGET } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** A builder asking a pool of shared strings for one of its names, as 450's 385611400 does (#77), is still the builder. */
    @Test
    fun aBuilderAskingAPoolForANameAsks() {
        val context = PatchContexts.of(classes(pooled = "add_to_audio_note") + pool("add_to_audio_note"))

        context.holdShareTarget(context.findShareTargetCheck())

        assertAsked("the pooled stand-in", context.mutableClassDefBy(builder).methods.single().code())
    }

    /** A pooled name the pool doesn't answer, or no pool at all, leaves no builder. */
    @Test
    fun aPooledNameThatIsntTheRowsLeavesNoBuilder() {
        for (classes in listOf(classes(pooled = "add_to_audio_note") + pool("something_else"), classes(pooled = "add_to_audio_note"))) {
            val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findShareTargetCheck() }
            assertTrue(failure.message, failure.message!!.contains("0 methods hold"))
        }
    }

    /**
     * In each declared build, and in every other build of a declared version, the case is the share
     * sheet's switch arm for "hatch", whose no goes where an unknown name does, and the hook sits
     * between its answer and its test. 385611400 asks a pool for one of the builder's names (#77).
     */
    @Test
    fun eachBuildAsksInItsHatchCase() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val declared = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.map { version to it }
        }
        for ((version, bundle) in declared) {
            asksInItsHatchCase(bundle, bundle.name)
            checked += version
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) asksInItsHatchCase(base, base.parentFile.name)
    }

    private fun asksInItsHatchCase(bundle: java.io.File, label: String) {
        val holders = FixtureDex.withStringPools(bundle, FixtureDex.classesHolding(bundle, HATCH_TARGET))
        val context = PatchContexts.of(holders)
        val site = context.findShareTargetCheck()
        val original = holders.single { it.type == site.type }.methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }.code()
        // 450's builder switches on each name's hash, and the case is the switch's arm for "hatch".
        val hatch = HATCH_TARGET.hashCode()
        val switches = original.indices.filter { index ->
            original[index].opcode == Opcode.SPARSE_SWITCH &&
                (original[target(original, index)] as SwitchPayload).switchElements.any { it.key == hatch }
        }
        assertEquals("$label: switches with a hatch arm", 1, switches.size)
        val switch = switches.single()
        val arm = (original[target(original, switch)] as SwitchPayload).switchElements.single { it.key == hatch }
        assertEquals("$label: the case is the hatch arm", site.moveResult - 2, at(original, address(original, switch) + arm.offset))
        // The case's no is where the switch sends a name it doesn't know.
        assertTrue("$label: a goto follows the switch", original[switch + 1] is OffsetInstruction)
        assertEquals("$label: a no skips the name", target(original, switch + 1), target(original, site.moveResult + 1))

        context.holdShareTarget(site)

        val code = context.mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }.code()
        assertAsked(label, code)
        assertEquals("$label: one ask", 1, code.count { it.referenceText() == SHARE_TARGET })
    }

    /** "hatch", the name compared with it, the answer, the ask on the answer's register, the answer back, then the test. */
    private fun assertAsked(what: String, code: List<Instruction>) {
        val at = code.indexOfFirst { it.referenceText() == SHARE_TARGET }
        assertTrue("$what: no ask", at >= 2)
        assertEquals("$what: the name check", "Ljava/lang/String;->equals(Ljava/lang/Object;)Z", code[at - 2].referenceText())
        assertEquals("$what: the case's string", HATCH_TARGET, code[at - 3].string())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
        val register = (code[at - 1] as OneRegisterInstruction).registerA
        val ask = code[at] as RegisterRangeInstruction
        assertEquals("$what: the ask takes the answer", listOf(register, 1), listOf(ask.startRegister, ask.registerCount))
        assertEquals("$what: the answer back", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: in the same register", register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: then the test", Opcode.IF_EQZ, code[at + 2].opcode)
        assertEquals("$what: of that register", register, (code[at + 2] as OneRegisterInstruction).registerA)
    }

    // ---- stand-ins shaped like Instagram 450's ------------------------------------------------

    /**
     * The share sheet's target builder holds its names and compares one with "hatch" in Meta AI's
     * case; a later check, which moves a hatch target already in the row, compares the other way
     * round. Another class names hatch in a setting of its own. With [pooled], the builder asks
     * [POOL] for that one name by number instead of loading it.
     */
    private fun classes(
        names: List<String> = SHARE_ROW_NAMES,
        builders: Int = 1,
        cases: Int = 1,
        enters: Boolean = false,
        pooled: String? = null,
    ): List<ClassDef> {
        val strings = names.filter { it != HATCH_TARGET }.joinToString("\n") { name ->
            if (name == pooled) {
                "const/16 v0, $POOLED\ninvoke-static { v0 }, $POOL->A00(I)Ljava/lang/String;\nmove-result-object v0"
            } else {
                "const-string v0, \"$name\""
            }
        }
        val case = """
            const-string v1, "$HATCH_TARGET"
            ${if (enters) ":compare" else ""}
            invoke-virtual { v2, v1 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v2
            if-eqz v2, :skip
        """
        val body = """
            $strings
            const/4 v1, 0x0
            const/4 v2, 0x0
            const/4 v3, 0x0
            ${if (enters) "if-eqz v3, :compare" else ""}
            ${List(cases) { case }.joinToString("\n")}
            const/4 v0, 0x0
            :skip
            const-string v1, "$HATCH_TARGET"
            invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v1
            const/4 v0, 0x0
            return-object v0
        """
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        val builderClasses = (0 until builders).map { copy ->
            val type = if (copy == 0) builder else "Lfixture/OtherShareSheetTargets;"
            classDef(type, listOf(method(type, "targets", parameters, "Ljava/util/ArrayList;", 6, body, flags)))
        }
        val otherClass = classDef(other, listOf(method(other, "name", emptyList(), "Ljava/lang/String;", 2, """
            const-string v0, "$HATCH_TARGET"
            return-object v0
        """)))
        return builderClasses + otherClass
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    /** A pool of shared strings as 450's Redex writes it: a static (int)String switch answering [answer] for [POOLED]. p0 is v1. */
    private fun pool(answer: String): ClassDef = classDef(POOL, listOf(ImmutableMethod(
        POOL, "A00", listOf(ImmutableMethodParameter("I", null, null)), "Ljava/lang/String;",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(2, listOf(
            ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 8),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(answer)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(POOLED, 5))),
        ), null, null),
    )))

    private companion object {
        const val POOL = "Lfixture/Strings;"
        const val POOLED = 0x566
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    /** The index a branch, a goto or a switch's payload reference at [index] lands on. */
    private fun target(code: List<Instruction>, index: Int): Int =
        at(code, address(code, index) + (code[index] as OffsetInstruction).codeOffset)

    /** The code address of the instruction at [index]. */
    private fun address(code: List<Instruction>, index: Int): Int = code.take(index).sumOf { it.codeUnits }

    /** The index of the instruction at code [address]. */
    private fun at(code: List<Instruction>, address: Int): Int {
        var current = 0
        for (candidate in code.indices) {
            if (current == address) return candidate
            current += code[candidate].codeUnits
        }
        throw AssertionError("no instruction at $address")
    }
}
