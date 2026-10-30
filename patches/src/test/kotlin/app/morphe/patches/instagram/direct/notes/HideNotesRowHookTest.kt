/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.notes

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.direct.search.KEEP_SEARCH_SECTIONS
import app.morphe.patches.instagram.direct.search.hideInboxSearch
import app.morphe.patches.instagram.metaai.INBOX_SECTION_MARKER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HideNotesRowHookTest {
    private val section = "Lfixture/Section;"
    private val enabled = "Lfixture/Enabled;"
    private val lookups = "Lfixture/Sections;"
    private val inbox = "Lfixture/Inbox;"
    private val modes = "Lfixture/Modes;"
    private val enabledInit = "$enabled-><init>([$section)V"

    /** The hooks both patches write are in the NotesRow and InboxSearchBar the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        for (hook in listOf(KEEP_SECTIONS, KEEP_SEARCH_SECTIONS)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Hide the search bar in Messages asks at the same place, alone, inline and in 452's factory. */
    @Test
    fun theSearchBarPatchAsksAtTheSamePlace() {
        val inline = PatchContexts.of(classes())
        inline.hideInboxSearch()
        val diff = inline.mutableClassDefBy(inbox).methods.single { it.name == "diff" }
        assertSectionsAsked("the stand-in", diff, register = 2, sections = section, hook = KEEP_SEARCH_SECTIONS)
        val keep = diff.code().indexOfFirst { it.referenceText() == KEEP_SEARCH_SECTIONS }
        assertEquals("the jump to the construction lands on the question", keep, diff.targetOf(diff.code().indexOfFirst { it.opcode == Opcode.IF_EQZ }))

        val factory = PatchContexts.of(classes(factories = 1))
        factory.hideInboxSearch()
        assertSectionsAsked("the factory", factory.mutableClassDefBy(modes).methods.single(), register = 1, sections = section, hook = KEEP_SEARCH_SECTIONS)
    }

    /**
     * With both patches in, in either order and in either shape, each question is asked once, both
     * sit right before the construction on the sections' register, and every jump lands on the first.
     */
    @Test
    fun bothPatchesTogetherAskBothQuestionsInEitherOrder() {
        val orders = listOf<(BytecodePatchContext) -> Unit>(
            { it.hide(); it.hideInboxSearch() },
            { it.hideInboxSearch(); it.hide() },
        )
        for ((at, order) in orders.withIndex()) {
            val inline = PatchContexts.of(classes())
            order(inline)
            val diff = inline.mutableClassDefBy(inbox).methods.single { it.name == "diff" }
            val first = assertBothAsked("order $at, the stand-in", diff, register = 2, sections = section)
            assertEquals("order $at: the jump to the construction lands on the first question", first, diff.targetOf(diff.code().indexOfFirst { it.opcode == Opcode.IF_EQZ }))

            val factory = PatchContexts.of(classes(factories = 1))
            order(factory)
            val made = factory.mutableClassDefBy(modes).methods.single()
            val firstInFactory = assertBothAsked("order $at, the factory", made, register = 1, sections = section)
            val modeJump = made.targetOf(made.code().indexOfFirst { it.opcode == Opcode.IF_EQZ })
            assertTrue("order $at: every mode reaches the first question", modeJump <= firstInFactory)
        }
    }

    /** A build the search bar patch can't read fails under its own name, and nothing is changed. */
    @Test
    fun theSearchBarPatchRefusesUnderItsOwnName() {
        for (classes in listOf(classes(diffs = 0), classes(constructions = 2), classes(readAfter = true))) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.hideInboxSearch() }
            assertTrue(failure.message, failure.message!!.startsWith("Hide the search bar in Messages: "))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    assertEquals("${original.type}->${method.name} changed", method.code().map { it.describe() }, now.getValue(method.key()).code().map { it.describe() })
                }
            }
        }
    }

    /**
     * The sections go through the question right before the set is built from them, and a jump to
     * the construction lands on the question, so no path builds the set from Instagram's own list.
     */
    @Test
    fun theSectionsAreAskedForAndEveryJumpStillLands() {
        val context = PatchContexts.of(classes())

        context.hide()

        val diff = context.mutableClassDefBy(inbox).methods.single { it.name == "diff" }
        assertSectionsAsked("the stand-in", diff, register = 2, sections = section)
        val keep = diff.code().indexOfFirst { it.referenceText() == KEEP_SECTIONS }
        val jump = diff.code().indexOfFirst { it.opcode == Opcode.IF_EQZ }
        assertEquals("the jump to the construction lands on the question", keep, diff.targetOf(jump))
    }

    /**
     * When the diff update hands the building to a static factory, as 452 does, the sections are
     * asked for in the factory, at the construction every mode jumps toward, and the diff update is left alone.
     */
    @Test
    fun aSetBuiltInAFactoryTheDiffUpdateCallsIsAskedForThere() {
        val context = PatchContexts.of(classes(factories = 1))

        context.hide()

        val factory = context.mutableClassDefBy(modes).methods.single()
        assertSectionsAsked("the factory", factory, register = 1, sections = section)
        val diff = context.mutableClassDefBy(inbox).methods.single { it.name == "diff" }
        assertTrue("the diff update is left alone", diff.code().none { it.referenceText() == KEEP_SECTIONS })
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val noSet = "to build one set from an array of the inbox's sections, found 0"
        val cases = listOf(
            classes(diffs = 0) to "inbox generator holding \"$INBOX_DIFF\" in this Instagram build, found none",
            classes(diffs = 2) to "inbox generator holding \"$INBOX_DIFF\" in this Instagram build, found Lfixture/",
            classes(notesNames = 0) to noSet,
            classes(notesNames = 2) to noSet,
            classes(sectionIsEnum = false) to noSet,
            classes(constructions = 2) to "to build one set from an array of the inbox's sections, found 2",
            classes(lookup = false) to "inbox section lookup holding \"$INBOX_SECTION_MARKER\" in this Instagram build, found none",
            classes(lookupTakes = "Lfixture/Other;") to "doesn't look a section up by $section",
            classes(lookupStatic = false) to "doesn't look a section up by $section",
            classes(bitsField = false) to "$enabled doesn't fill a Ljava/util/BitSet; from the sections it's given",
            classes(fillsBits = false) to "$enabled doesn't fill a Ljava/util/BitSet; from the sections it's given",
            classes(checksBits = false) to "$lookups never checks a section against $enabled",
            classes(readAfter = true) to "reads v2, the sections, after building $enabled",
            classes(factories = 2) to "to call one method building the inbox's sections, found 2",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hide() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    val after = now.getValue(method.key())
                    assertEquals("$expected: ${original.type}->${method.name} changed", method.code().map { it.describe() }, after.code().map { it.describe() })
                }
            }
        }
    }

    /** In each declared build the inbox's sections are found and asked for, once, and the stock code around them is kept. */
    @Test
    fun eachDeclaredBuildAsksForTheSections() {
        forEachFixture { builds(it, it.name) }
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildAsksForTheSectionsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** The classes of [bundle] the patches read: the holders of their markers, the factory the diff update calls and the set's class. */
    internal fun fixtureClasses(bundle: File): List<ClassDef> {
        val holders = listOf(INBOX_DIFF, INBOX_SECTION_MARKER, NOTES_SECTION)
            .flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
        val diffs = holders.flatMap { it.methods }.filter { method -> method.code().any { it.referenceText() == INBOX_DIFF } }
        // 452 builds the set in a static factory the diff update calls, so the classes it calls come along.
        val called = diffs.flatMap { it.code() }
            .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
            .mapNotNull { (it.reference() as? MethodReference)?.definingClass }.toSet()
        val factories = FixtureDex.classes(bundle, called).values
        val sets = (diffs + factories.flatMap { it.methods }).flatMap { it.code() }
            .mapNotNull { (it.reference() as? MethodReference)?.takeIf { call -> call.name == "<init>" && call.parameterTypes.size == 1 } }
            .map { it.definingClass }.toSet()
        return (holders + factories + FixtureDex.classes(bundle, sets).values).distinctBy { it.type }
    }

    /**
     * The notes row's question alone, the search bar's alone, and both in either order, each on a
     * fresh copy of the build, with the stock code around the questions kept.
     */
    private fun builds(bundle: File, label: String) {
        val classes = fixtureClasses(bundle)
        val context = PatchContexts.of(classes)
        val site = context.findNotesSections()
        val stock = context.mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }
        val original = NeutralNativePath(stock)

        context.hide()

        val written = classes.flatMap { context.mutableClassDefBy(it.type).methods }
            .filter { method -> method.code().any { it.referenceText() == KEEP_SECTIONS } }
        assertEquals("$label: methods asking", 1, written.size)
        val diff = written.single()
        val keep = diff.code().indexOfFirst { it.referenceText() == KEEP_SECTIONS }
        assertSectionsAsked(label, diff, site.register, site.sections)
        original.assertPreserved(label, diff, (keep..keep + 2).toSet())

        val search = PatchContexts.of(classes)
        search.hideInboxSearch()
        val searched = search.mutableClassDefBy(site.type).methods.single { it.key() == stock.key() }
        assertSectionsAsked("$label, search bar", searched, site.register, site.sections, hook = KEEP_SEARCH_SECTIONS)
        val asked = searched.code().indexOfFirst { it.referenceText() == KEEP_SEARCH_SECTIONS }
        original.assertPreserved("$label, search bar", searched, (asked..asked + 2).toSet())

        for ((at, order) in listOf<(BytecodePatchContext) -> Unit>({ it.hide(); it.hideInboxSearch() }, { it.hideInboxSearch(); it.hide() }).withIndex()) {
            val both = PatchContexts.of(classes)
            order(both)
            val chained = both.mutableClassDefBy(site.type).methods.single { it.key() == stock.key() }
            val first = assertBothAsked("$label, both in order $at", chained, site.register, site.sections)
            original.assertPreserved("$label, both in order $at", chained, (first..first + 5).toSet())
        }
    }

    private fun forEachFixture(check: (java.io.File) -> Unit) {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The question on the sections' register, its answer back into it, a cast to the array type the
     * set takes, then the construction of the set from that register, with nothing jumping in between.
     */
    private fun assertSectionsAsked(what: String, diff: MutableMethod, register: Int, sections: String, hook: String = KEEP_SECTIONS) {
        val code = diff.code()
        val asks = code.indices.filter { code[it].referenceText() == hook }
        assertEquals("$what: asks", 1, asks.size)
        val keep = asks.single()
        assertEquals("$what: hands over the sections", listOf(register), code[keep].arguments())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[keep + 1].opcode)
        assertEquals("$what: the answer's register", register, (code[keep + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[keep + 2].opcode)
        assertEquals("$what: the cast's register", register, (code[keep + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast's type", "[$sections", ((code[keep + 2] as ReferenceInstruction).reference as TypeReference).type)
        val made = code[keep + 3].reference() as MethodReference
        assertEquals("$what: the construction", "<init>", made.name)
        assertEquals("$what: the construction's array", listOf("[$sections"), made.parameterTypes.map(CharSequence::toString))
        assertEquals("$what: the construction gets the answer", register, code[keep + 3].arguments().getOrNull(1))
        assertFalse("$what: a jump lands between the question and the construction", (keep + 1..keep + 3).any { it in diff.jumpTargets() })
    }

    /**
     * Both questions once each, back to back on the sections' register, each answer cast back to
     * the array type, then the construction from that register, with nothing jumping in after the
     * first question. Answers where the first question is.
     */
    private fun assertBothAsked(what: String, method: MutableMethod, register: Int, sections: String): Int {
        val code = method.code()
        val asks = listOf(KEEP_SECTIONS, KEEP_SEARCH_SECTIONS).associateWith { hook -> code.indices.filter { code[it].referenceText() == hook } }
        for ((hook, at) in asks) assertEquals("$what: asks $hook", 1, at.size)
        val first = asks.values.minOf { it.single() }
        assertEquals("$what: the questions sit back to back", setOf(first, first + 3), asks.values.map { it.single() }.toSet())
        for (keep in listOf(first, first + 3)) {
            assertEquals("$what: hands over the sections", listOf(register), code[keep].arguments())
            assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[keep + 1].opcode)
            assertEquals("$what: the answer's register", register, (code[keep + 1] as OneRegisterInstruction).registerA)
            assertEquals("$what: the cast", Opcode.CHECK_CAST, code[keep + 2].opcode)
            assertEquals("$what: the cast's register", register, (code[keep + 2] as OneRegisterInstruction).registerA)
            assertEquals("$what: the cast's type", "[$sections", ((code[keep + 2] as ReferenceInstruction).reference as TypeReference).type)
        }
        val made = code[first + 6].reference() as MethodReference
        assertEquals("$what: the construction", "<init>", made.name)
        assertEquals("$what: the construction's array", listOf("[$sections"), made.parameterTypes.map(CharSequence::toString))
        assertEquals("$what: the construction gets the answer", register, code[first + 6].arguments().getOrNull(1))
        assertFalse("$what: a jump lands after the first question", (first + 1..first + 6).any { it in method.jumpTargets() })
        return first
    }

    private fun BytecodePatchContext.hide() = hideNotesRow(findNotesSections())

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The section enum, whose setup names the notes row among others; the set of sections the
     * inbox may show, filling a bit set; the inbox's section lookup and its check of each section
     * against the set; and the generator's diff update, which builds the set from a new array.
     */
    private fun classes(
        diffs: Int = 1,
        notesNames: Int = 1,
        sectionIsEnum: Boolean = true,
        constructions: Int = 1,
        lookup: Boolean = true,
        lookupTakes: String = section,
        lookupStatic: Boolean = true,
        bitsField: Boolean = true,
        fillsBits: Boolean = true,
        checksBits: Boolean = true,
        readAfter: Boolean = false,
        factories: Int = 0,
    ): List<ClassDef> {
        val names = (0 until notesNames).joinToString("\n") { "const-string v0, \"$NOTES_SECTION\"" }
        val sectionSetup = method(section, "<clinit>", emptyList(), "V", 1, static = true, body = """
            const-string v0, "SEARCH_BAR"
            $names
            const-string v0, "THREADS"
            return-void
        """)
        val sectionClass = classDef(section, listOf(sectionSetup), superclass = if (sectionIsEnum) "Ljava/lang/Enum;" else "Ljava/lang/Object;")

        val fill = if (fillsBits) "invoke-virtual { v0, v1 }, Ljava/util/BitSet;->set(I)V" else "invoke-virtual { v0 }, Ljava/util/BitSet;->clear()V"
        val enabledInitMethod = method(enabled, "<init>", listOf("[$section"), "V", 2, body = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            new-instance v0, Ljava/util/BitSet;
            invoke-direct { v0 }, Ljava/util/BitSet;-><init>()V
            const/4 v1, 0x0
            $fill
            return-void
        """)
        val enabledClass = classDef(enabled, listOf(enabledInitMethod),
            fields = if (bitsField) listOf("bits" to "Ljava/util/BitSet;") else listOf("count" to "I"))

        val lookupMethods = mutableListOf<Method>()
        if (lookup) {
            lookupMethods += method(lookups, "find", listOf(lookupTakes, "Ljava/util/Map;"), "Ljava/lang/Object;", 1, static = lookupStatic, body = """
                const-string v0, "$INBOX_SECTION_MARKER"
                const/4 v0, 0x0
                return-object v0
            """)
        }
        lookupMethods += method(lookups, "build", listOf("Ljava/lang/Object;", enabled), "Z", 2, static = true, body = """
            iget-object v0, p1, $enabled->bits:Ljava/util/BitSet;
            const/4 v1, 0x0
            ${if (checksBits) "invoke-virtual { v0, v1 }, Ljava/util/BitSet;->get(I)Z" else "invoke-virtual { v0 }, Ljava/util/BitSet;->isEmpty()Z"}
            move-result v0
            return v0
        """)
        val lookupClass = classDef(lookups, lookupMethods)

        val inboxClasses = (0 until maxOf(diffs, 1)).map { copy ->
            val type = if (copy == 0) inbox else "Lfixture/OtherInbox;"
            val methods = mutableListOf<Method>()
            if (diffs > 0 && (copy == 0 || diffs > 1)) {
                val second = if (constructions < 2) "" else """
                    new-instance v3, $enabled
                    invoke-direct { v3, v2 }, $enabledInit
                """.trimIndent()
                methods += if (factories > 0) {
                    val calls = (0 until factories).joinToString("\n") { "invoke-static { p0 }, $modes->forMode$it(Ljava/lang/Object;)$enabled" }
                    method(type, "diff", listOf(type), "V", 1, static = true, body = """
                        const-string v0, "$INBOX_DIFF"
                        $calls
                        return-void
                    """)
                } else {
                    method(type, "diff", listOf(type), "V", 4, static = true, body = """
                        const-string v0, "$INBOX_DIFF"
                        const/4 v1, 0x1
                        new-array v2, v1, [$section
                        new-instance v3, $enabled
                        if-eqz p0, :make
                        const/4 v1, 0x2
                        :make
                        invoke-direct { v3, v2 }, $enabledInit
                        $second
                        ${if (readAfter) "invoke-static { v2 }, $lookups->keep([$section)V" else "const/4 v2, 0x0"}
                        return-void
                    """)
                }
            }
            classDef(type, methods)
        }
        // 452's shape: a static factory the diff update calls, every mode jumping to its one construction.
        val factoryClasses = if (factories == 0) emptyList() else listOf(
            classDef(modes, (0 until factories).map { copy ->
                method(modes, "forMode$copy", listOf("Ljava/lang/Object;"), enabled, 3, static = true, body = """
                    const/4 v0, 0x1
                    new-array v1, v0, [$section
                    if-eqz p0, :make
                    const/4 v0, 0x2
                    new-array v1, v0, [$section
                    :make
                    new-instance v2, $enabled
                    invoke-direct { v2, v1 }, $enabledInit
                    return-object v2
                """)
            }),
        )
        return listOf(sectionClass, enabledClass, lookupClass) + inboxClasses + factoryClasses
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        if (name == "<clinit>" || name == "<init>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, superclass: String = "Ljava/lang/Object;", fields: List<Pair<String, String>> = emptyList()): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value, null, null, null) },
            methods,
        )

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = when (val reference = reference()) {
        is com.android.tools.smali.dexlib2.iface.reference.StringReference -> reference.string
        null -> null
        else -> reference.toString()
    }

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, registers, literal and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        if (this@describe is NarrowLiteralInstruction) append(" #$narrowLiteral")
        referenceText()?.let { append(" $it") }
    }
}
