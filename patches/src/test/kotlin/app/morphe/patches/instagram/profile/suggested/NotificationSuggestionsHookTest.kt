/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide suggestions in notifications, Hide suggested people on profiles' fifth switch (#181): the row parser's kind and the reply's lists, found and answered. */
class NotificationSuggestionsHookTest {
    private val rowParser = "Lfixture/RowParser;"
    private val row = "Lfixture/Row;"
    private val reply = "Lfixture/Reply;"
    private val inbox = "Lfixture/Inbox;"
    private val json = "Lfixture/Json;"
    private val nameField = "$row->name:Ljava/lang/String;"

    /** The hooks the patch writes are in the ProfileSuggestions the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(NOTIFICATION_PARSED.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(NOTIFICATION_PARSED, KEEP_NOTIFICATION, FEATURED_ROWS, FEATURED_HEADER)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * In each declared build the row parser hands its row and the kind's name to the extension right
     * before its one return of the row, on the return's own label, and the reply asks about each row
     * right before each list's null check. On 451's 385713073 that's LX/02F9 returning v1 with the name
     * in LX/02G1.A0D, and LX/01DS filling new_stories and old_stories.
     */
    @Test
    fun eachDeclaredBuildMarksAndDropsTheRows() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        val lists = mutableMapOf<String, Int>()
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == NOTIF_NAME_KEY || it.string() == OLD_ROWS_KEY } }) {
                        holders += ImmutableClassDef.of(classDef)
                    }
                }
            }
            val context = PatchContexts.of(holders)

            val rows = context.findNotificationRows()
            val reply = context.findNotificationLists(rows)
            val featured = context.findFeaturedSectionOrWarn(reply)
            assertNotNull("${bundle.name}: the Featured section", featured)
            context.noteNotificationRows(rows)
            context.keepNotificationRows(reply)
            context.hideFeaturedSection(reply)

            assertMarked(bundle.name, context.parser(rows.type, rows.parameters), rows)
            assertDropped(bundle.name, context.parser(reply.type, reply.parameters), rows.type, reply.checks.size)
            assertFeaturedHidden(bundle.name, context.parser(reply.type, reply.parameters), featured!!)
            lists[bundle.name] = reply.checks.size
        }
        // New and old rows and three more lists of the same rows, as the contract's sites 5 holds.
        assertTrue("the reply's lists of rows in each build: $lists", lists.values.all { it == 5 })
    }

    /**
     * On the stand-in the call goes in right before the return, on its label, so the branch that skips
     * the last optional field reaches it too, and each list's null check gets the answer.
     */
    @Test
    fun theStandInIsMarkedAndDropped() {
        val context = PatchContexts.of(classes())
        val rowsBefore = context.parser(rowParser, listOf(json)).code().size
        val repliesBefore = context.parser(reply, listOf(json)).code().size

        val rows = context.findNotificationRows()
        val lists = context.findNotificationLists(rows)
        context.noteNotificationRows(rows)
        context.keepNotificationRows(lists)

        assertEquals("the row's register", 1, rows.row)
        assertEquals("the name's register", 2, rows.name)
        assertEquals("the name's field", nameField, rows.nameField)
        val parser = context.parser(rowParser, listOf(json))
        assertMarked("stand-in", parser, rows)
        assertEquals("what else went in the row parser", rowsBefore + 2, parser.code().size)
        val call = parser.code().indexOfFirst { it.referenceText() == NOTIFICATION_PARSED }
        val skip = parser.code().indexOfLast { it.opcode == Opcode.IF_EQZ }
        assertEquals("the optional field's skip lands on the read of the name", call - 1, parser.targetOf(skip))

        val replies = context.parser(reply, listOf(json))
        assertDropped("stand-in", replies, rowParser, 2)
        assertEquals("what else went in the reply", repliesBefore + 4, replies.code().size)
        // The stand-in has no Featured section, so that switch alone stays out and nothing more is written.
        assertNull("no Featured section", context.findFeaturedSectionOrWarn(lists))
        assertFalse(replies.code().any { it.referenceText() == FEATURED_ROWS || it.referenceText() == FEATURED_HEADER })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(rowParsers = 0) to "expected one row parser loading \"$NOTIF_NAME_KEY\" and \"$STORY_TYPE_KEY\", found 0",
            classes(rowParsers = 2) to "expected one row parser loading \"$NOTIF_NAME_KEY\" and \"$STORY_TYPE_KEY\", found 2",
            classes(nameType = "Ljava/lang/CharSequence;") to "doesn't read a String right after \"$NOTIF_NAME_KEY\"",
            classes(keptTwice = true) to "expected one write of \"$NOTIF_NAME_KEY\" into the row in $rowParser->unsafeParseFromJson, found 2",
            classes(madeByFactory = true) to "keeps \"$NOTIF_NAME_KEY\" in something other than a new $row",
            classes(jumpIn = true) to "into the stretch between making the row and handing it back",
            classes(rewritten = true) to "writes v1 again before handing the row back",
            // The row made in the parser's last register, its own parameter's, after the keys are read.
            classes(rowRegister = 11) to "has no free register next to v11",
            classes(replies = false) to "expected one reply parser loading \"$NEW_ROWS_KEY\" and \"$OLD_ROWS_KEY\", found 0",
            classes(oneList = true) to "reads the row parser 1 times, expected one for each list",
            classes(check = "if-nez") to "isn't a parse with a null check after it",
            classes(jumpToCheck = true) to "$reply->unsafeParseFromJson jumps to the null check at",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                val rows = context.findNotificationRows()
                context.findNotificationLists(rows)
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertNull("$expected: the patch goes on without the switch", context.findNotificationSuggestionsOrWarn())
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == NOTIFICATION_PARSED || it.referenceText() == KEEP_NOTIFICATION } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
        assertNotNull("the stand-in itself is read", PatchContexts.of(classes()).findNotificationSuggestionsOrWarn())
    }

    /** The read of the name off the row, the range call with the row and the name, then the parser's own return of the row. */
    private fun assertMarked(what: String, method: MutableMethod, site: NotificationRowSite) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == NOTIFICATION_PARSED })
        val call = code.indexOfFirst { it.referenceText() == NOTIFICATION_PARSED }
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[call].opcode)
        assertEquals("$what: the row", site.row, (code[call] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the row and the name", 2, (code[call] as RegisterRangeInstruction).registerCount)
        val read = code[call - 1]
        assertEquals("$what: the read of the name", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$what: into", site.name, (read as TwoRegisterInstruction).registerA)
        assertEquals("$what: off the row", site.row, read.registerB)
        assertEquals("$what: the name's field", site.nameField, (read as ReferenceInstruction).reference.fieldText())
        assertEquals("$what: the return", Opcode.RETURN_OBJECT, code[call + 1].opcode)
        assertEquals("$what: of the row", site.row, (code[call + 1] as OneRegisterInstruction).registerA)
        val targets = method.jumpTargets()
        assertFalse("$what: a jump skips the read of the name", call in targets || call + 1 in targets)
    }

    /** Each read of the row parser parses a row, hands it to the extension, takes the answer back and null checks it. */
    private fun assertDropped(what: String, method: MutableMethod, rowType: String, lists: Int) {
        val code = method.code()
        val calls = code.indices.filter { code[it].referenceText() == KEEP_NOTIFICATION }
        assertEquals("$what: hooks", lists, calls.size)
        val targets = method.jumpTargets()
        for (call in calls) {
            val register = (code[call] as RegisterRangeInstruction).startRegister
            assertEquals("$what: the call at $call", Opcode.INVOKE_STATIC_RANGE, code[call].opcode)
            assertEquals("$what: one register at $call", 1, (code[call] as RegisterRangeInstruction).registerCount)
            assertEquals("$what: the parsed row at $call", Opcode.MOVE_RESULT_OBJECT, code[call - 1].opcode)
            assertEquals("$what: its register at $call", register, (code[call - 1] as OneRegisterInstruction).registerA)
            assertEquals("$what: the parse at $call", "parseFromJsonParser", ((code[call - 2] as ReferenceInstruction).reference as MethodReference).name)
            val instance = (code[call - 3] as ReferenceInstruction).reference as FieldReference
            assertEquals("$what: the row parser at $call", rowType, instance.definingClass)
            assertEquals("$what: the answer at $call", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
            assertEquals("$what: back in the row's register at $call", register, (code[call + 1] as OneRegisterInstruction).registerA)
            assertEquals("$what: the null check at $call", Opcode.IF_EQZ, code[call + 2].opcode)
            assertEquals("$what: of the answer at $call", register, (code[call + 2] as OneRegisterInstruction).registerA)
            assertFalse("$what: a jump lands in the call at $call", call in targets || call + 1 in targets || call + 2 in targets)
        }
    }

    /**
     * The Featured rows' list and its header each go to the extension right before the reply keeps
     * them, the answer back in the same register, the header's cast back to its type, and nothing
     * jumps past a call into the store. The list's field is the one the reply names [FEATURED_ROWS_KEY]
     * by at the end, and its loop's way out now lands on the call.
     */
    private fun assertFeaturedHidden(what: String, method: MutableMethod, site: FeaturedSite) {
        val code = method.code()
        val targets = method.jumpTargets()
        val rows = code.indices.single { code[it].referenceText() == FEATURED_ROWS }
        assertEquals("$what: the rows' call", Opcode.INVOKE_STATIC_RANGE, code[rows].opcode)
        assertEquals("$what: the rows' register", site.rows, (code[rows] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the rows' answer", site.rows, (code[rows + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: kept", Opcode.IPUT_OBJECT, code[rows + 2].opcode)
        assertEquals("$what: kept from the answer", site.rows, (code[rows + 2] as TwoRegisterInstruction).registerA)
        val field = (code[rows + 2] as ReferenceInstruction).reference.fieldText()
        val tag = code.indices.single { code[it].string() == FEATURED_ROWS_KEY && code[it - 1].opcode == Opcode.IGET_OBJECT }
        assertEquals("$what: the field named $FEATURED_ROWS_KEY", field, (code[tag - 1] as ReferenceInstruction).reference.fieldText())
        assertTrue("$what: the loop's way out lands on the rows' call", rows in targets)
        assertFalse("$what: a jump lands past the rows' call", rows + 1 in targets || rows + 2 in targets)

        val header = code.indices.single { code[it].referenceText() == FEATURED_HEADER }
        assertEquals("$what: the header's register", site.header, (code[header] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the header's answer", Opcode.MOVE_RESULT_OBJECT, code[header + 1].opcode)
        assertEquals("$what: cast back", Opcode.CHECK_CAST, code[header + 2].opcode)
        assertEquals("$what: to the header's type", site.headerType, ((code[header + 2] as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.TypeReference).type)
        assertEquals("$what: the header kept", Opcode.IPUT_OBJECT, code[header + 3].opcode)
        assertEquals("$what: kept from the answer", site.header, (code[header + 3] as TwoRegisterInstruction).registerA)
        val key = code.indices.single { code[it].string() == FEATURED_HEADER_KEY }
        assertTrue("$what: the header's store follows its key", header in key + 1..key + 16)
        assertFalse("$what: a jump lands past the header's call", (header + 1..header + 3).any { it in targets })
    }

    /**
     * Shaped like 451's: the row parser loops over the row's keys keeping each value in a register,
     * then makes the row, writes the values into it, skips an optional one when it's null and returns
     * the row. The reply loops over its keys and fills a list for new and old rows, reading the row
     * parser's instance, parsing a row with it and skipping a null.
     */
    private fun classes(
        rowParsers: Int = 1,
        nameType: String = "Ljava/lang/String;",
        keptTwice: Boolean = false,
        madeByFactory: Boolean = false,
        jumpIn: Boolean = false,
        rewritten: Boolean = false,
        rowRegister: Int = 1,
        replies: Boolean = true,
        oneList: Boolean = false,
        check: String = "if-eqz",
        jumpToCheck: Boolean = false,
    ): List<ClassDef> {
        val r = "v$rowRegister"
        val rowBody = listOfNotNull(
            "const/4 v8, 0x0",
            "const/4 v7, 0x0",
            "const/4 v3, 0x0",
            if (jumpIn) "if-nez v7, :out" else null,
            ":next",
            "invoke-virtual {p1}, $json->name()Ljava/lang/String;",
            "move-result-object v1",
            "if-eqz v1, :build",
            "const-string v0, \"${if (rowParsers == 0) "notif_title" else NOTIF_NAME_KEY}\"",
            "invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "move-result v0",
            "if-eqz v0, :type",
            "invoke-static {p1}, $json->text($json)$nameType",
            "move-result-object v8",
            "goto :next",
            ":type",
            "const-string v0, \"$STORY_TYPE_KEY\"",
            "invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "move-result v0",
            "if-eqz v0, :next",
            "invoke-virtual {p1}, $json->number()I",
            "move-result v3",
            "goto :next",
            ":build",
            if (madeByFactory) "invoke-static {}, $row->make()$row" else "new-instance $r, $row",
            if (madeByFactory) "move-result-object $r" else "invoke-direct {$r}, $row-><init>()V",
            "iput v3, $r, $row->type:I",
            "iput-object v8, $r, $nameField",
            if (keptTwice) "iput-object v8, $r, $row->title:Ljava/lang/String;" else null,
            "if-eqz v7, :out",
            if (rewritten) "move-object $r, v7" else "iput-object v7, $r, $row->text:Ljava/lang/String;",
            ":out",
            "return-object $r",
        ).joinToString("\n")
        val rowParserClasses = List(rowParsers.coerceAtLeast(1)) { n ->
            val type = if (n == 0) rowParser else "Lfixture/RowParser$n;"
            val parse = method(type, "unsafeParseFromJson", listOf(json), "Ljava/lang/Object;", 10, AccessFlags.PUBLIC.value, rowBody)
            classDef(type, "Lfixture/Parser;", listOf(parse), instance = true)
        }

        fun list(name: String, field: String, parses: Boolean): String = listOfNotNull(
            "new-instance v3, Ljava/util/ArrayList;",
            "invoke-direct {v3}, Ljava/util/ArrayList;-><init>()V",
            if (jumpToCheck && name == "new") "if-nez v1, :${name}Check" else null,
            ":${name}Item",
            "invoke-virtual {p1}, $json->more()Z",
            "move-result v0",
            "if-eqz v0, :${name}Kept",
            if (parses) "sget-object v0, $rowParser->INSTANCE:$rowParser" else "const/4 v0, 0x0",
            if (parses) "invoke-virtual {v0, p1}, Lfixture/Parser;->parseFromJsonParser($json)Ljava/lang/Object;"
            else "invoke-static {p1}, Lfixture/Other;->parse($json)Ljava/lang/Object;",
            "move-result-object v0",
            if (jumpToCheck && name == "new") ":${name}Check" else null,
            "${if (name == "old") check else "if-eqz"} v0, :${name}Item",
            "invoke-virtual {v3, v0}, Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z",
            "goto :${name}Item",
            ":${name}Kept",
            "iput-object v3, v6, $field",
            "goto :next",
        ).joinToString("\n")
        val replyBody = listOf(
            "new-instance v6, $inbox",
            "invoke-direct {v6}, $inbox-><init>()V",
            ":next",
            "invoke-virtual {p1}, $json->name()Ljava/lang/String;",
            "move-result-object v1",
            "if-eqz v1, :done",
            "const-string v0, \"$NEW_ROWS_KEY\"",
            "invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "move-result v0",
            "if-eqz v0, :old",
            list("new", "$inbox->newRows:Ljava/util/List;", parses = true),
            ":old",
            "const-string v0, \"${if (replies) OLD_ROWS_KEY else "older_stories"}\"",
            "invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "move-result v0",
            "if-eqz v0, :next",
            list("old", "$inbox->oldRows:Ljava/util/List;", parses = !oneList),
            ":done",
            "return-object v6",
        ).joinToString("\n")
        val parse = method(reply, "unsafeParseFromJson", listOf(json), "Ljava/lang/Object;", 7, AccessFlags.PUBLIC.value, replyBody)
        return rowParserClasses + classDef(reply, "Lfixture/Parser;", listOf(parse))
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

    /** The class, with its own static instance when it's a row parser, the way 451's parsers keep theirs. */
    private fun classDef(type: String, superclass: String, methods: List<Method>, instance: Boolean = false): ClassDef {
        val fields = if (instance) {
            listOf(ImmutableField(type, "INSTANCE", type, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null, null))
        } else {
            emptyList()
        }
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, fields, methods)
    }

    private fun BytecodePatchContext.parser(type: String, parameters: List<String>): MutableMethod =
        mutableClassDefBy(type).methods.single {
            it.name == "unsafeParseFromJson" && it.returnType == "Ljava/lang/Object;" && it.parameterTypes.map(CharSequence::toString) == parameters
        }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun com.android.tools.smali.dexlib2.iface.reference.Reference.fieldText(): String =
        (this as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" }
}
