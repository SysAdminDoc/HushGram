/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.bubbles

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide activity bubbles: every read of Instagram's own activity bubble setting, and the check's read
 * of whether the account has it, hands its value to the extension right after. Anything the patch
 * can't tell apart fails it before an instruction changes.
 */
class HideActivityBubblesHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val hide = ExtensionDex.classDef(ACTIVITY_BUBBLES).methods.single {
            it.name == "hide" && it.parameterTypes.map(CharSequence::toString) == listOf("I")
        }
        assertEquals("Z", hide.returnType)
        assertTrue(AccessFlags.PUBLIC.isSet(hide.accessFlags) && AccessFlags.STATIC.isSet(hide.accessFlags))
    }

    @Test
    fun everyReadHandsItsValueOn() {
        val context = PatchContexts.of(listOf(holder(), reader()))
        val setting = context.findBubbleSetting()
        assertEquals("the check", "$HOLDER->check", "${setting.check.definingClass}->${setting.check.name}")
        assertEquals("has the setting", "has", setting.has.name)
        assertEquals("the setting", "hide", setting.hide.name)
        assertEquals("the reads", setOf("check" to 2, "check" to 4, "build" to 1), setting.reads.map { it.method.name to it.index }.toSet())

        context.hideActivityBubbles(setting)

        val check = context.method(HOLDER, "check")
        assertHooked("check", check, setOf("has", "hide"))
        assertEquals("check: the test stays right after the hook", Opcode.IF_EQZ, check.code()[5].opcode)
        assertEquals("check: without the setting it still leaves through Instagram's return", check.code().lastIndex, landsOn(check.code(), 5))
        assertEquals("check: the setting is still returned", Opcode.RETURN, check.code()[9].opcode)
        val build = context.method(READER, "build")
        assertHooked("reader", build, setOf("hide"))
        assertEquals("reader: the skip stays right after the hook", Opcode.IF_NEZ, build.code()[4].opcode)
    }

    @Test
    fun aMissingOrDoubledHolderFailsThePatch() {
        refuses("found 0", listOf(holder(experiment = false), reader()))
        refuses("found 2", listOf(holder(), holder(type = "Lfixture/OtherSettings;"), reader()))
    }

    @Test
    fun aCheckOfAnotherShapeFailsThePatch() {
        refuses("2 static (UserSession, ...)Z checks", listOf(holder(checks = 2), reader()))
        refuses("reads 3 of", listOf(holder(flags = listOf("has", "other", "hide")), reader()))
        refuses("reads has twice", listOf(holder(flags = listOf("has", "has")), reader()))
        refuses("doesn't test has", listOf(holder(test = Opcode.IF_NEZ), reader()))
        refuses("doesn't return hide", listOf(holder(returnsHide = false), reader()))
    }

    @Test
    fun aJumpOntoTheInstructionAfterAReadFailsThePatch() {
        refuses("jumps onto the instruction after its read at 2", listOf(holder(), reader(jump = true)))
    }

    /** In each declared build: the check's two reads and the feed's and Reels' reads of the setting. */
    @Test
    fun eachDeclaredBuildHandsEveryReadOn() {
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

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildHandsEveryReadOnToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /**
     * The class checking the experiment, found first, names the setting; then every class reading
     * the setting comes along, so the patch sees all of Instagram's reads.
     */
    private fun builds(bundle: File, label: String) {
        val pooled = FixtureDex.classesLoading(bundle, BUBBLE_EXPERIMENT)
        // A build that inlined the holder's constructor makes the holder somewhere else; bring it along.
        val made = pooled.flatMap { it.methods }.flatMap(::inlinedConstructions).toSet()
        val loading = (pooled + FixtureDex.classes(bundle, made).values).distinctBy { it.type }
        val first = PatchContexts.of(loading).findBubbleSetting()
        val holder = first.check.definingClass
        val hide = first.hide.name
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.fieldSection.none { it.definingClass == holder && it.name == hide }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.type != holder && classDef.methods.any { method -> method.code().any { it.reads(holder, setOf(hide)) } }) {
                    readers += ImmutableClassDef.of(classDef)
                }
            }
        }
        val context = PatchContexts.of((loading + readers).distinctBy { it.type })
        val setting = context.findBubbleSetting()
        val checkName = signature(setting.check)
        val outside = setting.reads.filter { signature(it.method) != checkName }
        assertTrue("$label: ${outside.size} reads outside the check, not the feed's and Reels'", outside.size >= 2)
        val methods = setting.reads.map { it.method }.distinctBy(::signature)
        val before = methods.associate { signature(it) to it.code().filter { op -> op.opcode != Opcode.NOP }.map(::text) }

        context.hideActivityBubbles(setting)

        for (method in methods) {
            val name = signature(method)
            val after = context.method(method)
            val flags = if (name == checkName) setOf(setting.has.name, hide) else setOf(hide)
            assertHooked("$label $name", after, flags, holder)
            assertEquals("$label $name: the rest stays", before.getValue(name), withoutHooks(after.code()).filter { it.opcode != Opcode.NOP }.map(::text))
        }
        assertEquals("$label: one hook per read", setting.reads.size,
            methods.sumOf { method -> context.method(method).code().count { it.referenceText() == HIDE_BUBBLES } })
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.hideActivityBubbles(context.findBubbleSetting()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** Each read of [flags] is followed by the hook, handed the read's register, and its answer back in that register. */
    private fun assertHooked(what: String, method: Method, flags: Set<String>, holder: String = HOLDER) {
        val code = method.code()
        val reads = code.indices.filter { code[it].reads(holder, flags) }
        assertTrue("$what: no read", reads.isNotEmpty())
        for (at in reads) {
            val register = (code[at] as TwoRegisterInstruction).registerA
            assertEquals("$what: the hook after the read at $at", HIDE_BUBBLES, code[at + 1].referenceText())
            val call = code[at + 1] as RegisterRangeInstruction
            assertEquals("$what: the hook takes the read's register", register to 1, call.startRegister to call.registerCount)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at + 2].opcode)
            assertEquals("$what: the answer goes back in the read's register", register, (code[at + 2] as OneRegisterInstruction).registerA)
        }
        assertEquals("$what: one hook per read", reads.size, code.count { it.referenceText() == HIDE_BUBBLES })
    }

    /** The code with each hook and the move-result taking its answer left out. */
    private fun withoutHooks(code: List<Instruction>): List<Instruction> {
        val kept = mutableListOf<Instruction>()
        var at = 0
        while (at < code.size) {
            if (code[at].referenceText() == HIDE_BUBBLES) at += 2 else kept += code[at++]
        }
        return kept
    }

    /** The index of the instruction the branch at [at] lands on. */
    private fun landsOn(code: List<Instruction>, at: Int): Int {
        val offsets = code.runningFold(0) { sum, instruction -> sum + instruction.codeUnits }
        return offsets.indexOf(offsets[at] + (code[at] as OffsetInstruction).codeOffset)
    }

    private fun Instruction.reads(holder: String, flags: Set<String>): Boolean {
        val field = (this as? ReferenceInstruction)?.reference as? FieldReference ?: return false
        return opcode == Opcode.IGET_BOOLEAN && field.definingClass == holder && field.name in flags
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method =
        mutableClassDefBy(type).methods.single { it.name == name }

    private fun BytecodePatchContext.method(method: Method): Method = mutableClassDefBy(method.definingClass).methods.single {
        signature(it) == signature(method)
    }

    private fun signature(method: Method): String =
        "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name // opcode-name-ok
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference" // opcode-name-ok
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private companion object {
        const val HOLDER = "Lfixture/BubbleSettings;"
        const val READER = "Lfixture/FeedBubbles;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"

        fun flag(type: String, name: String) = ImmutableFieldReference(type, name, "Z")

        /**
         * Shaped like the class holding the account's activity bubble setting: its constructor checks
         * the experiment, and its static check reads [flags] from the shared instance, the first into
         * v2 tested by [test] to leave with false, the last returned. Three locals and the three
         * parameters (session, item, module name).
         */
        fun holder(
            type: String = HOLDER,
            experiment: Boolean = true,
            checks: Int = 1,
            flags: List<String> = listOf("has", "hide"),
            test: Opcode = Opcode.IF_EQZ,
            returnsHide: Boolean = true,
        ): ClassDef {
            val made = if (experiment) {
                ImmutableMethod(type, "<init>", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                    ImmutableMethodImplementation(2, listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(BUBBLE_EXPERIMENT)),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ), null, null))
            } else {
                ImmutableMethod(type, "<init>", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                    ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null))
            }
            val middle = flags.drop(1).dropLast(1)
            val code = mutableListOf<ImmutableInstruction>(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 1, ImmutableFieldReference(type, "instance", type)),
                ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 2, 1, flag(type, flags.first())),
                // Past the reads after it and the return, onto the last return.
                ImmutableInstruction21t(test, 2, 2 + 2 * middle.size + 2 + 1),
            )
            for (name in middle) code += ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 2, 1, flag(type, name))
            code += ImmutableInstruction22c(Opcode.IGET_BOOLEAN, if (returnsHide) 0 else 2, 1, flag(type, flags.last()))
            code += ImmutableInstruction11x(Opcode.RETURN, 0)
            code += ImmutableInstruction11x(Opcode.RETURN, 0)
            val parameters = listOf(SESSION, "Ljava/lang/Object;", "Ljava/lang/String;").map { ImmutableMethodParameter(it, null, null) }
            val access = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
            val methods = List(checks) { n ->
                ImmutableMethod(type, if (n == 0) "check" else "check$n", parameters, "Z", access, null, null,
                    ImmutableMethodImplementation(6, code, null, null))
            }
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(made) + methods)
        }

        /**
         * Shaped like a bubble builder: reads the setting from the shared instance and skips the
         * friend pictures when it's on. With [jump], a test of the instance first jumps onto the
         * instruction right after the read.
         */
        fun reader(jump: Boolean = false): ClassDef {
            val code = mutableListOf<ImmutableInstruction>(ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(HOLDER, "instance", HOLDER)))
            if (jump) code += ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4)
            code += ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 1, 0, flag(HOLDER, "hide"))
            code += ImmutableInstruction21t(Opcode.IF_NEZ, 1, 3)
            code += ImmutableInstruction11n(Opcode.CONST_4, 1, 0)
            code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            val build = ImmutableMethod(READER, "build", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "V",
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(4, code, null, null))
            return ImmutableClassDef(READER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(build))
        }
    }
}
