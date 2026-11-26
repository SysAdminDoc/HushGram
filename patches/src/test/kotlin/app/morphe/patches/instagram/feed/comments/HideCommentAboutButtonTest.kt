/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.comments

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.share.BUTTON_ROLE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
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
 * Hide the (i) in comments (#869): the row above a comment sheet's comments hands what its (i)
 * explains to CommentAboutButton.disclosure right after reading it, and its own null test leaves the
 * (i) out on a null. A build whose row reads it some other way refuses before anything changes, and
 * Hide comments goes in without the (i) part.
 */
class HideCommentAboutButtonTest {
    private val row = "Lfixture/HeaderRow;"
    private val explanation = "Lfixture/Explanation;"
    private val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
    private val aboutRow = "Lfixture/AboutRow;"

    @Test
    fun theHookIsInTheExtension() {
        val type = COMMENT_ABOUT_BUTTON_DISCLOSURE.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$type->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMMENT_ABOUT_BUTTON_DISCLOSURE is not in the extension: $declared", COMMENT_ABOUT_BUTTON_DISCLOSURE in declared)
    }

    @Test
    fun theRowAsksBeforeItTestsWhatItExplains() {
        val context = context()
        val plan = context.findCommentAbout()
        assertEquals(row, plan.type)
        assertEquals("$row->explanation:$explanation", plan.field)
        assertEquals(explanation, plan.explanation)
        val stock = context.rowCode(plan)
        context.hideCommentAbout(plan)
        assertAsks("fixture", context.rowCode(plan), stock, plan)
    }

    @Test
    fun aRowItCantReadRefusesAndTheRestGoesIn() {
        refuses("makes no data class of two strings", context(viewModel = false))
        refuses("makes no data class of two strings", context(fields = 3))
        refuses("makes no data class of two strings", context(superclass = "Ljava/lang/Object;"))
        refuses("found 0", context(rows = 0))
        refuses("found 2", context(rows = 2))
        refuses("found 0", context(buttonRole = false))
        refuses("found 0", context(writeBetween = true))
        refuses("jumps to right after its read", context(jumpOntoTheHook = true))
        val empty = PatchContexts.of(emptyList<ClassDef>())
        assertThrows(PatchException::class.java) { empty.findCommentAbout() }
        assertNull(empty.findCommentAboutOrWarn())
    }

    /** A row changed between the find and the hook refuses rather than hooking something else. */
    @Test
    fun aRowThatChangedRefusesTheHook() {
        val plan = context().findCommentAbout()
        val moved = CommentAboutPlan(plan.type, plan.name, plan.parameters, plan.returnType, plan.readAt + 1, plan.field,
            plan.explanation, plan.register)
        val failure = assertThrows(PatchException::class.java) { context().hideCommentAbout(moved) }
        assertTrue(failure.message, failure.message!!.startsWith("Hide comments: ") && "changed before its (i)" in failure.message!!)
    }

    @Test
    fun declaredBuildsAskBeforeTheButton() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var count = 0
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            builds(bundle)
            count++
        }
        assertTrue("no fixture of a declared build", count > 0)
    }

    @Test
    fun otherBuildsAskBeforeTheButtonToo() {
        for (bundle in Fixtures.otherBuilds()) builds(bundle)
    }

    /**
     * The classes loading the button role, pools included, the view model and what its methods
     * answer: one row reads the (i)'s explanation once, and it's hooked there.
     */
    private fun builds(bundle: File) {
        val name = bundle.parentFile.name + "/" + bundle.name
        val loading = FixtureDex.classesLoading(bundle, BUTTON_ROLE)
        val model = FixtureDex.classes(bundle, setOf(COMMENT_LIST_VIEW_MODEL)).values
        val answered = model.flatMap { it.methods }.map { it.returnType }.filter { it.startsWith("L") }.toSet()
        val classes = (loading + model + FixtureDex.classes(bundle, answered).values).distinctBy { it.type }
        val context = PatchContexts.of(classes)
        val plan = try {
            context.findCommentAbout()
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        val stock = context.rowCode(plan)
        context.hideCommentAbout(plan)
        assertAsks(name, context.rowCode(plan), stock, plan)
    }

    /**
     * Right after the read: the hook, handed the explanation's register alone, its answer back in
     * that register and cast to the explanation's type, then the row's own code. One hook, nothing
     * jumps onto it, the rest as it was.
     */
    private fun assertAsks(what: String, code: List<Instruction>, stock: List<Instruction>, plan: CommentAboutPlan) {
        assertEquals("$what: the read", Opcode.IGET_OBJECT to plan.field, stock[plan.readAt].opcode to text(stock[plan.readAt]))
        val hooks = code.indices.filter { text(code[it]) == COMMENT_ABOUT_BUTTON_DISCLOSURE }
        assertEquals("$what: one hook, right after the read", listOf(plan.readAt + 1), hooks)
        val call = code[plan.readAt + 1] as RegisterRangeInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC_RANGE, code[plan.readAt + 1].opcode)
        assertEquals("$what: of the explanation alone", 1 to plan.register, call.registerCount to call.startRegister)
        val answer = code[plan.readAt + 2]
        assertEquals("$what: its answer in the explanation's register", Opcode.MOVE_RESULT_OBJECT to plan.register,
            answer.opcode to (answer as OneRegisterInstruction).registerA)
        val cast = code[plan.readAt + 3]
        assertEquals("$what: cast back to the explanation", Triple(Opcode.CHECK_CAST, plan.register, plan.explanation),
            Triple(cast.opcode, (cast as OneRegisterInstruction).registerA, text(cast)))
        val test = (plan.readAt + 4..plan.readAt + 6).first { code[it].opcode == Opcode.IF_EQZ }
        assertEquals("$what: then the row's own test of it", plan.register, (code[test] as OneRegisterInstruction).registerA)
        assertEquals("$what: nothing jumps onto the hook", 0,
            code.count { (it as? BuilderOffsetInstruction)?.target?.location?.index in plan.readAt + 1..plan.readAt + 3 })
        val kept = code.filterIndexed { at, _ -> at !in plan.readAt + 1..plan.readAt + 3 }
        assertEquals("$what: the row's own code is kept", stock.shape(), kept.shape())
    }

    @Test
    fun theRowHookIsInTheExtension() {
        val type = COMMENT_ABOUT_ROW_ADD.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$type->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$COMMENT_ABOUT_ROW_ADD is not in the extension: $declared", COMMENT_ABOUT_ROW_ADD in declared)
    }

    /** The reel row with an (i) (#869): the list's add of it goes to the extension, in its place. */
    @Test
    fun theReelRowsAddGoesToTheExtension() {
        val context = rowContext()
        val plan = context.findCommentAboutRow()
        assertEquals("build", plan.name)
        assertEquals(listOf(0, 1), listOf(plan.list, plan.row))
        val stock = context.modelCode(plan)
        context.hideCommentAboutRow(plan)
        assertAddsThroughTheExtension("fixture", context.modelCode(plan), stock, plan)
        assertEquals("the branch past the row still lands right after the hook", plan.addAt + 1,
            context.modelCode(plan).firstNotNullOf { (it as? BuilderOffsetInstruction)?.target?.location?.index })
    }

    @Test
    fun aReelRowItCantFollowRefusesAndTheRestGoesIn() {
        refusesRow("$COMMENT_LIST_VIEW_MODEL method loading \"$XAR_INFO_ROW\", found 0", rowContext(builders = 0))
        refusesRow("$COMMENT_LIST_VIEW_MODEL method loading \"$XAR_INFO_ROW\", found 2", rowContext(builders = 2))
        refusesRow("found 2", rowContext(keys = 2))
        refusesRow("no row made after", rowContext(rowMade = false))
        refusesRow("no add of the row", rowContext(added = false))
        refusesRow("doesn't go straight", rowContext(branchBetween = true))
        refusesRow("doesn't go straight", rowContext(rowWrittenBetween = true))
        refusesRow("something jumps between", rowContext(jumpBetween = true))
        refusesRow("something jumps between", rowContext(jumpOntoTheAdd = true))
        refusesRow("reads what the add of the row answered", rowContext(resultRead = true))
        val empty = PatchContexts.of(emptyList<ClassDef>())
        assertThrows(PatchException::class.java) { empty.findCommentAboutRow() }
        assertNull(empty.findCommentAboutRowOrWarn())
    }

    /** A view model changed between the find and the hook refuses rather than replacing another call. */
    @Test
    fun aReelRowThatMovedRefusesTheHook() {
        val plan = rowContext().findCommentAboutRow()
        val moved = CommentAboutRowPlan(plan.name, plan.parameters, plan.returnType, plan.addAt - 1, plan.add, plan.list, plan.row)
        val failure = assertThrows(PatchException::class.java) { rowContext().hideCommentAboutRow(moved) }
        assertTrue(failure.message, failure.message!!.startsWith("Hide comments: ") && "changed before its" in failure.message!!)
    }

    @Test
    fun declaredBuildsAddTheReelRowThroughTheExtension() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var count = 0
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            rowBuilds(bundle)
            count++
        }
        assertTrue("no fixture of a declared build", count > 0)
    }

    @Test
    fun otherBuildsAddTheReelRowThroughTheExtensionToo() {
        for (bundle in Fixtures.otherBuilds()) rowBuilds(bundle)
    }

    /** The classes loading the row's key, pools included, and the view model: one add, hooked in place. */
    private fun rowBuilds(bundle: File) {
        val name = bundle.parentFile.name + "/" + bundle.name
        val loading = FixtureDex.classesLoading(bundle, XAR_INFO_ROW)
        val model = FixtureDex.classes(bundle, setOf(COMMENT_LIST_VIEW_MODEL)).values
        val context = PatchContexts.of((loading + model).distinctBy { it.type })
        val plan = try {
            context.findCommentAboutRow()
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        val stock = context.modelCode(plan)
        context.hideCommentAboutRow(plan)
        assertAddsThroughTheExtension(name, context.modelCode(plan), stock, plan)
    }

    /**
     * In the add's place: one static call of the hook with the list and the row, in that order, and
     * nothing reading an answer after it. Every other instruction is the view model's own, and one
     * add fewer of anything is left.
     */
    private fun assertAddsThroughTheExtension(what: String, code: List<Instruction>, stock: List<Instruction>, plan: CommentAboutRowPlan) {
        assertEquals("$what: the add", plan.add, text(stock[plan.addAt]))
        assertEquals("$what: it read the list and the row", listOf(plan.list, plan.row), arguments(stock[plan.addAt]))
        val hooks = code.indices.filter { text(code[it]) == COMMENT_ABOUT_ROW_ADD }
        assertEquals("$what: one hook, where the add was", listOf(plan.addAt), hooks)
        val opcode = if (stock[plan.addAt] is RegisterRangeInstruction) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC
        assertEquals("$what: a static call", opcode, code[plan.addAt].opcode)
        assertEquals("$what: of the list and the row", listOf(plan.list, plan.row), arguments(code[plan.addAt]))
        assertTrue("$what: nothing reads an answer", code[plan.addAt + 1].opcode !in RESULTS)
        assertEquals("$what: as long as before", stock.size, code.size)
        val kept = code.filterIndexed { at, _ -> at != plan.addAt }
        assertEquals("$what: the rest is the view model's own", stock.filterIndexed { at, _ -> at != plan.addAt }.shape(), kept.shape())
        assertEquals("$what: one add of it fewer", stock.count { text(it) == plan.add } - 1, code.count { text(it) == plan.add })
    }

    private fun refusesRow(reason: String, context: BytecodePatchContext) {
        val failure = assertThrows(PatchException::class.java) { context.findCommentAboutRow() }
        assertTrue(failure.message, failure.message!!.startsWith("Hide comments: "))
        assertTrue(failure.message, reason in failure.message!!)
        assertNull(context.findCommentAboutRowOrWarn())
    }

    private fun BytecodePatchContext.modelCode(plan: CommentAboutRowPlan): List<Instruction> =
        mutableClassDefBy(COMMENT_LIST_VIEW_MODEL).methods.single {
            it.name == plan.name && it.parameterTypes.map(Any::toString) == plan.parameters
        }.instructions()

    /**
     * Shaped like 457's CommentListViewModel.A07 around the row: a branch past it, the key, a
     * StringBuilder making the row's id from it, the row made and filled, a Redex store fence, then
     * the list's add of the row with its answer left alone, and a second add of something else.
     */
    private fun rowContext(
        builders: Int = 1,
        keys: Int = 1,
        rowMade: Boolean = true,
        added: Boolean = true,
        branchBetween: Boolean = false,
        rowWrittenBetween: Boolean = false,
        jumpBetween: Boolean = false,
        jumpOntoTheAdd: Boolean = false,
        resultRead: Boolean = false,
    ): BytecodePatchContext {
        val methods = List(builders) { index ->
            val body = buildString {
                appendLine("if-eqz p1, :after")
                if (jumpBetween) appendLine("if-nez p1, :fill")
                if (jumpOntoTheAdd) appendLine("if-nez p1, :add")
                appendLine("const-string/jumbo v2, \"$XAR_INFO_ROW\"")
                if (keys == 2) appendLine("const-string/jumbo v2, \"$XAR_INFO_ROW\"")
                appendLine("new-instance v1, Ljava/lang/StringBuilder;")
                appendLine("invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V")
                appendLine("invoke-virtual {v1}, Ljava/lang/Object;->toString()Ljava/lang/String;")
                appendLine("move-result-object v2")
                if (rowMade) appendLine("new-instance v1, $aboutRow")
                appendLine("invoke-direct {v1, v2}, $aboutRow-><init>(Ljava/lang/String;)V")
                if (jumpBetween) appendLine(":fill")
                appendLine("iput-object p1, v1, $aboutRow->A01:Ljava/lang/String;")
                if (branchBetween) appendLine("if-eqz p1, :done")
                if (rowWrittenBetween) appendLine("move-object v1, p1")
                appendLine("const/4 v2, 0x0")
                appendLine("sput v2, Lredex/\$StoreFenceHelper;->DUMMY_VOLATILE:I")
                if (jumpOntoTheAdd) appendLine(":add")
                appendLine(if (added) "invoke-virtual {v0, v1}, Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z"
                    else "invoke-virtual {v0}, Ljava/util/AbstractCollection;->clear()V")
                if (resultRead) appendLine("move-result v2")
                appendLine(":after")
                appendLine("invoke-virtual {v0, p1}, Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z")
                if (branchBetween) appendLine(":done")
                appendLine("return-object v0")
            }
            method(COMMENT_LIST_VIEW_MODEL, if (index == 0) "build" else "build$index",
                listOf("Ljava/util/ArrayList;", "Ljava/lang/String;"), "Ljava/util/ArrayList;", 3,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
                "move-object v0, p0\n$body")
        }
        val helper = method(COMMENT_LIST_VIEW_MODEL, "A0h", listOf("Ljava/lang/Object;"), "Z", 1,
            AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, "const/4 v0, 0x1\nreturn v0")
        return PatchContexts.of(listOf(classDef(COMMENT_LIST_VIEW_MODEL, methods + helper)))
    }

    private fun arguments(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> List(instruction.registerCount) { instruction.startRegister + it }
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        else -> emptyList()
    }

    private fun refuses(reason: String, context: BytecodePatchContext) {
        val failure = assertThrows(PatchException::class.java) { context.findCommentAbout() }
        assertTrue(failure.message, failure.message!!.startsWith("Hide comments: "))
        assertTrue(failure.message, reason in failure.message!!)
        assertNull(context.findCommentAboutOrWarn())
    }

    private fun BytecodePatchContext.rowCode(plan: CommentAboutPlan): List<Instruction> =
        mutableClassDefBy(plan.type).methods.single { it.name == plan.name && it.parameterTypes.map(Any::toString) == plan.parameters }.instructions()

    /**
     * Shaped like 457's 0TQj.A0p: a flag first sends the row to a text of its own, else it reads what
     * its (i) explains (the branch lands on that read), loads the button role and skips the (i) on a
     * null. The view model makes the explanation, a data class of two strings, in a method of its own.
     */
    private fun context(
        rows: Int = 1,
        buttonRole: Boolean = true,
        fields: Int = 2,
        superclass: String = DATA_CLASS_SUPER,
        viewModel: Boolean = true,
        writeBetween: Boolean = false,
        jumpOntoTheHook: Boolean = false,
    ): BytecodePatchContext {
        val classes = mutableListOf<ClassDef>()
        repeat(rows) { index ->
            val type = if (index == 0) row else "Lfixture/HeaderRow$index;"
            val body = buildString {
                appendLine("iget-boolean v0, p0, $type->plain:Z")
                appendLine("if-eqz v0, :read")
                if (jumpOntoTheHook) appendLine("if-nez v0, :after")
                appendLine("return-object p1")
                appendLine(":read")
                appendLine("iget-object v0, p0, $type->explanation:$explanation")
                if (jumpOntoTheHook) appendLine(":after")
                appendLine(if (buttonRole) "const-string v1, \"$BUTTON_ROLE\"" else "const-string v1, \"android.widget.TextView\"")
                if (writeBetween) appendLine("const/4 v0, 0x0")
                appendLine("if-eqz v0, :end")
                appendLine("invoke-virtual {v1}, Ljava/lang/Object;->hashCode()I")
                appendLine(":end")
                appendLine("return-object v1")
            }
            classes += classDef(
                type,
                listOf(method(type, "render", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;", 2, final, body)),
                listOf(
                    ImmutableField(type, "plain", "Z", final, null, null, null),
                    ImmutableField(type, "explanation", explanation, final, null, null, null),
                ),
            )
        }
        classes += classDef(
            explanation, emptyList(),
            List(fields) { ImmutableField(explanation, "A0$it", "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null, null) },
            superclass,
        )
        if (viewModel) {
            classes += classDef(
                COMMENT_LIST_VIEW_MODEL,
                listOf(method(COMMENT_LIST_VIEW_MODEL, "A06", listOf("Ljava/lang/Object;"), explanation, 1,
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, "new-instance v0, $explanation\nreturn-object v0")),
            )
        }
        return PatchContexts.of(classes)
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val words = parameters.size + parameters.count { it == "J" || it == "D" }
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + words
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList(),
                         superclass: String = "Ljava/lang/Object;"): ClassDef =
        ImmutableClassDef(type, final, superclass, emptyList(), null, null, fields, methods)

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun text(instruction: Instruction): String =
        (instruction as? ReferenceInstruction)?.reference?.toString() ?: "${instruction.opcode}"

    /** Opcodes and references without padding, with a widened goto read as a goto. */
    private fun List<Instruction>.shape() = filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }
        .map { (if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode) to text(it) }

    private companion object {
        val RESULTS = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE)
    }
}
