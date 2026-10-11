/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.notes

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
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide Notes on profile pictures: the two reads of Instagram's Notes-off flag on profiles, found and answered. */
class HideProfileNotesHookTest {
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val header = "Lfixture/ProfileHeaderBuilder;"
    private val flagHex = CONSUMPTION_DISABLED_FLAG.toString(16)

    /** The hook the patch writes is in the ProfileNotes the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(NOTES_OFF.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$NOTES_OFF is not in the extension: $declared", NOTES_OFF.substringAfter("->") in declared)
    }

    /** Both reads get their answer passed through the extension, and nothing else changes. */
    @Test
    fun bothReadsAreAnswered() {
        val context = PatchContexts.of(classes())
        val before = classes().sumOf { c -> c.methods.sumOf { it.code().size } }

        context.hideProfileNotes()

        val patched = listOf(PROFILE_FRAGMENT, header).map { type -> context.mutableClassDefBy(type).methods.single { it.name == "A00" } }
        patched.forEach { assertAnswered("stand-in", it) }
        assertEquals("what else was added", before + 4, patched.sumOf { it.code().size })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(screenReads = 0) to "expected two reads of the Notes consumption flag $flagHex, found 1",
            classes(extraRead = true) to "expected two reads of the Notes consumption flag $flagHex, found 3",
            classes(flag = CONSUMPTION_DISABLED_FLAG + 1) to "expected two reads of the Notes consumption flag $flagHex, found 0",
            classes(shared = true) to "is shared with another flag",
            classes(screen = "Lfixture/NotTheProfileScreen;") to "expected one read of $flagHex in $PROFILE_FRAGMENT, found 0",
            classes(creation = false) to "reads $flagHex but not the creation flag",
            classes(strings = listOf(SELF_PROFILE)) to "doesn't name both $SELF_PROFILE and $EXTERNAL_PROFILE",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hideProfileNotes() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == NOTES_OFF } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each of the seven 450 builds the flag is read twice, once by the profile screen and once by
     * the one method that also reads the creation flag and names both profile kinds, and both are answered.
     */
    @Test
    fun eachBuildHidesNotesOnBothReads() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("the seven 450 builds: ${bundles.map { it.name }}", 7, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { it.loads(CONSUMPTION_DISABLED_FLAG) }) holders += ImmutableClassDef.of(classDef)
                }
            }
            assertEquals("${bundle.name}: classes loading $flagHex: ${holders.map { it.type }}", 2, holders.size)
            val context = PatchContexts.of(holders)

            val reads = context.findProfileNotes()
            context.hideProfileNotes()

            assertEquals("${bundle.name}: reads", 2, reads.size)
            assertTrue("${bundle.name}: the profile screen", reads.any { it.type == PROFILE_FRAGMENT })
            for (read in reads) {
                val patched = context.mutableClassDefBy(read.type).methods.single {
                    it.name == read.name && it.parameterTypes.map(CharSequence::toString) == read.parameters
                }
                assertAnswered(bundle.name, patched)
            }
        }
    }

    /** Right after the flag's move-result: the range call with that register, and its answer back in it. */
    private fun assertAnswered(what: String, method: Method) {
        val code = method.code()
        val load = code.indexOfFirst { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == CONSUMPTION_DISABLED_FLAG }
        assertTrue("$what: no load of $flagHex", load >= 0)
        val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == NOTES_OFF })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", NOTES_OFF, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * Shaped like 450's: the profile screen and the header builder each read the flag through a cast
     * config, and the builder also reads the creation flag and names both profile kinds.
     */
    private fun classes(
        screenReads: Int = 1,
        extraRead: Boolean = false,
        flag: Long = CONSUMPTION_DISABLED_FLAG,
        shared: Boolean = false,
        screen: String = PROFILE_FRAGMENT,
        creation: Boolean = true,
        strings: List<String> = listOf(SELF_PROFILE, EXTERNAL_PROFILE),
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        fun read(literal: Long, label: String) = """
            invoke-static {p0}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v2
            ${if (shared) "if-eqz v2, :cast$label" else ""}
            const-wide v0, 0x${literal.toString(16)}L
            ${if (shared) ":cast$label" else ""}
            check-cast v2, $configs
            invoke-interface {v2, v0, v1}, $configs->read(J)Z
            move-result v0
        """.trimIndent()
        val screenBody = List(screenReads) { read(flag, "s$it") }.joinToString("\n") + "\nreturn v0"
        val headerBody = listOf(
            read(flag, "h"),
            if (creation) read(CREATION_DISABLED_FLAG, "c") else "",
            strings.joinToString("\n") { "const-string v0, \"$it\"" },
            if (extraRead) read(flag, "x") else "",
        ).filter { it.isNotBlank() }.joinToString("\n") + "\nreturn v0"
        return listOf(
            classDef(screen, listOf(method(screen, "A00", listOf("Ljava/lang/Object;"), "Z", 3, static, screenBody))),
            classDef(header, listOf(method(header, "A00", listOf("Ljava/lang/Object;"), "Z", 3, static, headerBody))),
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

    private fun Method.loads(literal: Long): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == literal
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
