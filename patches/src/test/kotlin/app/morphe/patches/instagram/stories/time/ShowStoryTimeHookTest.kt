/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.time

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowStoryTimeHookTest {
    private val contextType = "Landroid/content/Context;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val header = "Lfixture/StoryHeader;"
    private val formatter = "Lfixture/TimeFormatter;"
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val flagHex = RELATIVE_HEADER_FLAG.toString(16)
    private val hooks = setOf(TIME_LABEL, RELATIVE_HEADER)

    /** The hooks the patch writes are in the StoryTime the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_TIME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in hooks) assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
    }

    /**
     * The label asks first, right before the conversion, with the posted time, returns what it
     * answers and goes on to the conversion on null; the header's flag answer goes through the
     * extension right after its read. The item's other methods stay as they were.
     */
    @Test
    fun theLabelAsksFirstAndTheHeaderFlagIsAnswered() {
        val patched = PatchContexts.of(classes())

        patched.show()

        assertLabelAsks("stand-in", patched.mutableClassDefBy(STORY_ITEM).methods.single { it.name == "timeLabel" }, seconds = 4)
        assertFlagAnswered("stand-in", patched.mutableClassDefBy(header).methods.single { it.name == "build" })
        assertEquals("the item's other method was touched", 2, patched.mutableClassDefBy(STORY_ITEM).methods.single { it.name == "postedAt" }.code().size)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val label = "story item's time label taking a Context in this Instagram build"
        val where = "$STORY_ITEM->timeLabel"
        val cases = listOf(
            classes(labels = 0) to "expected exactly one $label, found none",
            classes(labels = 2) to "expected exactly one $label, found $where",
            classes(conversions = 0) to "expected $where to turn one long into a double, found 0",
            classes(conversions = 2) to "expected $where to turn one long into a double, found 2",
            classes(formatterTakes = "J") to "$where doesn't hand the double it makes straight to a formatter answering a String",
            classes(formatterAnswers = "Ljava/lang/CharSequence;") to "$where doesn't hand the double it makes straight to a formatter answering a String",
            classes(returnsFormatted = false) to "$where doesn't return what its formatter answers",
            classes(jumpToConversion = true) to "something in $where jumps to its long-to-double",
            classes(noFreeLocal = true) to "needs 1",
            classes(headerReads = 0) to "expected one read of the story header's flag $flagHex, found 0",
            classes(headerReads = 2) to "expected one read of the story header's flag $flagHex, found 2",
            classes(headerFlag = RELATIVE_HEADER_FLAG + 1) to "expected one read of the story header's flag $flagHex, found 0",
            classes(headerAsksLabel = false) to "reads $flagHex but never asks $where for the label",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.show() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() in hooks } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the story item's label asks first and the header's flag is answered.
     * On 449 three methods ask the item for its label: the story header's binder, the header's
     * builder (the one reading the flag) and your own story's viewer list.
     */
    @Test
    fun eachDeclaredBuildShowsTheExactTime() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                val askers = mutableListOf<Pair<String, String>>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type == STORY_ITEM || classDef.methods.any { it.loadsTheFlag() }) holders += ImmutableClassDef.of(classDef)
                        for (method in classDef.methods) {
                            method.code().mapNotNull { it.reference() as? MethodReference }
                                .filter { it.definingClass == STORY_ITEM && it.returnType == STRING && it.parameterTypes.map(CharSequence::toString) == listOf(contextType) }
                                .forEach { askers += "${classDef.type}->${method.name}" to it.name }
                        }
                    }
                }
                val context = PatchContexts.of(holders)

                val sites = context.findStoryTime()
                context.showStoryTime(sites)

                assertLabelAsks(bundle.name, context.mutableClassDefBy(STORY_ITEM).methods.single { it.name == sites.name && it.returnType == STRING }, sites.seconds)
                val builder = context.mutableClassDefBy(sites.header.type).methods.single {
                    it.name == sites.header.name && it.parameterTypes.map(CharSequence::toString) == sites.header.parameters
                }
                assertFlagAnswered(bundle.name, builder)
                val callers = askers.filter { it.second == sites.name }.map { it.first }.distinct()
                assertTrue("${bundle.name}: the header reading the flag asks for the label: $callers", "${sites.header.type}->${sites.header.name}" in callers)
                assertEquals("${bundle.name}: what asks the story item for its label: $callers", 3, callers.size)
                assertTrue(
                    "${bundle.name}: your own story's viewer list asks too: $callers",
                    callers.any { it.startsWith("Linstagram/features/stories/dashboard/fragment/ReelDashboardFragment;->") },
                )
                val written = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
                    .filter { method -> method.code().any { it.referenceText() in hooks } }
                assertEquals("${bundle.name}: methods hooked", 2, written.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * Right before the long-to-double: the range call with the posted time's pair, its answer in a
     * register that isn't the time's, a check that lands on the conversion, and the answer returned.
     * Only that check jumps to the conversion.
     */
    private fun assertLabelAsks(what: String, method: MutableMethod, seconds: Int) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == TIME_LABEL })
        val hook = code.indexOfFirst { it.referenceText() == TIME_LABEL }
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        val range = code[hook] as RegisterRangeInstruction
        assertEquals("$what: the posted time", listOf(seconds, 2), listOf(range.startRegister, range.registerCount))
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        val answer = (code[hook + 1] as OneRegisterInstruction).registerA
        assertTrue("$what: the answer's register v$answer is the time's", answer != seconds && answer != seconds + 1)
        assertEquals("$what: the check", Opcode.IF_EQZ, code[hook + 2].opcode)
        assertEquals("$what: the checked register", answer, (code[hook + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the return", Opcode.RETURN_OBJECT, code[hook + 3].opcode)
        assertEquals("$what: the returned register", answer, (code[hook + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: what follows", Opcode.LONG_TO_DOUBLE, code[hook + 4].opcode)
        assertEquals("$what: the converted pair", seconds, (code[hook + 4] as TwoRegisterInstruction).registerB)
        val jumps = method.implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>()
        assertEquals("$what: null goes on to the conversion", hook + 4, (method.implementation!!.instructions[hook + 2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: jumps landing on the conversion", 1, jumps.count { it.target.location.index == hook + 4 })
    }

    /** After the flag's read: the range call with the read's register, and its answer back in it. */
    private fun assertFlagAnswered(what: String, method: MutableMethod) {
        val code = method.code()
        val load = code.indexOfFirst { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == RELATIVE_HEADER_FLAG }
        val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == RELATIVE_HEADER })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", RELATIVE_HEADER, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    private fun BytecodePatchContext.show() = showStoryTime(findStoryTime())

    /**
     * A story item and a header shaped like Instagram 449's. The item's label reads its posted time
     * as a long into v4, adds the clock offset, turns it into a double in v0 for a formatter taking
     * Resources and the double, and returns the answer. The header's builder reads
     * [RELATIVE_HEADER_FLAG] through a cast config and, when it's off, asks the item for its label.
     */
    private fun classes(
        labels: Int = 1,
        conversions: Int = 1,
        formatterTakes: String = "D",
        formatterAnswers: String = STRING,
        returnsFormatted: Boolean = true,
        jumpToConversion: Boolean = false,
        noFreeLocal: Boolean = false,
        headerReads: Int = 1,
        headerFlag: Long = RELATIVE_HEADER_FLAG,
        headerAsksLabel: Boolean = true,
    ): List<ClassDef> {
        fun label(name: String): Method {
            if (noFreeLocal) {
                // Two locals, both holding the time the conversion reads: nothing left to borrow.
                return method(
                    STORY_ITEM, name, listOf(contextType), STRING, 2, static = false,
                    body = """
                        invoke-virtual {v2}, $STORY_ITEM->postedAt()J
                        move-result-wide v0
                        long-to-double v0, v0
                        invoke-static {v0, v1}, $formatter->relative(D)$STRING
                        move-result-object v0
                        return-object v0
                    """,
                )
            }
            val conversion = when (conversions) {
                0 -> "move-wide v0, v4"
                else -> List(conversions) { "long-to-double v0, v4" }.joinToString("\n")
            }
            return method(
                STORY_ITEM, name, listOf(contextType), STRING, 8, static = false,
                body = """
                    sget-object v7, $formatter->INSTANCE:$formatter
                    invoke-virtual {v9}, $contextType->getResources()Landroid/content/res/Resources;
                    move-result-object v6
                    invoke-virtual {v8}, $STORY_ITEM->postedAt()J
                    move-result-wide v4
                    ${if (jumpToConversion) "if-eqz v6, :convert" else "nop"}
                    sget-wide v2, $formatter->offset:J
                    add-long/2addr v4, v2
                    :convert
                    $conversion
                    invoke-virtual {v7, v6, v0, v1}, $formatter->relative(Landroid/content/res/Resources;$formatterTakes)$formatterAnswers
                    move-result-object v0
                    ${if (returnsFormatted) "nop" else "const-string v0, \"\""}
                    return-object v0
                """.lines().filter { it.trim() != "nop" }.joinToString("\n"),
            )
        }
        val itemMethods = (1..labels).map { label(if (it == 1) "timeLabel" else "timeLabel$it") } +
            method(STORY_ITEM, "postedAt", emptyList(), "J", 2, static = false, body = "const-wide/16 v0, 0x1\nreturn-wide v0")
        val item = classDef(STORY_ITEM, itemMethods)

        // The builder: static (Context, UserSession, ReelItem)String, six locals, p0 = v6, p1 = v7, p2 = v8.
        val reads = List(headerReads) {
            """
                invoke-static {v7}, Lfixture/Configs;->of($session)Ljava/lang/Object;
                move-result-object v3
                const-wide v4, 0x${headerFlag.toString(16)}L
                check-cast v3, $configs
                invoke-interface {v3, v4, v5}, $configs->read(J)Z
                move-result v4
                if-nez v4, :relative
            """.trimIndent()
        }.joinToString("\n")
        val asks = if (headerAsksLabel) "invoke-virtual {v8, v6}, $STORY_ITEM->timeLabel($contextType)$STRING" else "invoke-static {v6}, $formatter->plain($contextType)$STRING"
        val build = method(
            header, "build", listOf(contextType, session, STORY_ITEM), STRING, 6, static = true,
            body = listOf(
                reads,
                asks,
                "move-result-object v0",
                "return-object v0",
                ":relative",
                "invoke-static {v6}, $formatter->other($contextType)$STRING",
                "move-result-object v0",
                "return-object v0",
            ).filter { it.isNotBlank() }.joinToString("\n"),
        )
        return listOf(item, classDef(header, listOf(build)))
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        if (static) flags = flags or AccessFlags.STATIC.value
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods)

    private fun Method.loadsTheFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == RELATIVE_HEADER_FLAG
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private companion object {
        const val STRING = "Ljava/lang/String;"
    }
}
