/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.likes

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowHiddenLikeCountsHookTest {
    // Its switch starts off, so simple mode picks it (DefaultSelectionPolicyTest).
    @Test fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(showHiddenLikeCountsPatch.default)
    }

    @Test fun theKeysAreTheFieldNamesHashes() {
        assertEquals(-1301662067, LIKES_HIDDEN_KEY)
        assertEquals(-792455577, LIKE_COUNT_KEY)
        assertEquals("the extension reads the flag by the same key", LIKES_HIDDEN_KEY,
            ExtensionDex.intConstant(HIDDEN_LIKE_COUNTS, "LIKES_HIDDEN_KEY"))
    }

    @Test fun theFlagAndTheCountGoThroughTheExtensionAndNothingElseChanges() {
        val input = LikeFixture.classes()
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        context.applyHiddenLikeCounts(context.findHiddenLikeCounts())

        assertDecisionHook(context.mutableClassDefBy(LikeFixture.DECIDER).methods.single(), LikeFixture.decider().code())
        assertCountHook(context.mutableClassDefBy(LikeFixture.COUNTER).methods.single(), LikeFixture.counter().code(), 8, 2, 0)
        assertStub(context.mutableClassDefBy(HIDDEN_LIKE_COUNTS).methods.single { it.name == TREE_FLAG_STUB },
            "${LikeFixture.TREE}->CtD(I)Ljava/lang/Boolean;")
        assertStub(context.mutableClassDefBy(HIDDEN_LIKE_COUNTS).methods.single { it.name == TREE_COUNT_STUB },
            "${LikeFixture.TREE}->CtN(I)Ljava/lang/Integer;")
        val rows = context.mutableClassDefBy(LikeFixture.ROWS).methods.sortedBy { it.name }
        assertEquals(3, rows.size)
        val originals = LikeFixture.rows().methods.sortedBy { it.name }
        rows.zip(originals).forEach { (row, original) -> assertRowHook(row, original.code(), 2, 2, 0) }
        for (candidate in input.filter {
            it.type != LikeFixture.DECIDER && it.type != LikeFixture.COUNTER && it.type != LikeFixture.ROWS && it.type != HIDDEN_LIKE_COUNTS
        }) {
            assertEquals("${candidate.type} changed", before[candidate.type], snapshot(context.mutableClassDefBy(candidate.type).methods))
        }
    }

    @Test fun everyRowIsHookedOnceWithItsOwnTreeAndFlag() {
        val context = PatchContexts.of(LikeFixture.classes())
        val anchors = context.findHiddenLikeCounts()
        assertEquals(3, anchors.rows.size)
        assertEquals(listOf(2), anchors.rows.map { it.at }.distinct())
        assertEquals("${LikeFixture.TREE}->CtN(I)Ljava/lang/Integer;", anchors.countRead.toString())
    }

    @Test fun aMissingExtensionIsRefused() = refuses("the extension has no $HIDDEN_LIKE_COUNTS",
        LikeFixture.classes().filter { it.type != HIDDEN_LIKE_COUNTS })
    @Test fun aPostModelWithoutTheCountGetterIsRefused() = refuses("for $LIKE_COUNT_FIELD",
        LikeFixture.classes().map { if (it.type == POST_MODEL) LikeFixture.post(countGetter = false) else it })
    @Test fun noLikeCountReaderIsRefused() = refuses("found none",
        LikeFixture.classes().map { if (it.type == POST_MODEL) LikeFixture.post(callsCounter = false) else it })
    @Test fun tooFewRowsAreRefused() = refuses("only 2 like rows ask",
        LikeFixture.classes().map { if (it.type == LikeFixture.ROWS) LikeFixture.rows(count = 2) else it })
    @Test fun rowsAskingTwoDecidersAreRefused() = refuses("ask one method whether to hide the count, found 2",
        LikeFixture.classes().map { if (it.type == LikeFixture.ROWS) LikeFixture.rows(otherDecider = true) else it })
    @Test fun aDeciderTestingSomethingElseFirstIsRefused() = refuses("doesn't test the flag first",
        LikeFixture.classes().map { if (it.type == LikeFixture.DECIDER) LikeFixture.deciderClass(tested = 3) else it })
    @Test fun aDeciderAnsweringShownForTheFlagIsRefused() = refuses("doesn't answer hidden at once",
        LikeFixture.classes().map { if (it.type == LikeFixture.DECIDER) LikeFixture.deciderClass(answer = 0) else it })
    @Test fun aBranchLandingAfterTheCountReadIsRefused() = refuses("lands right after its like count read",
        LikeFixture.classes().map { if (it.type == LikeFixture.COUNTER) LikeFixture.counterClass(branchAfterRead = true) else it })
    @Test fun aBranchLandingAfterARowsFlagReadIsRefused() = refuses("lands right after its flag read",
        LikeFixture.classes().map { if (it.type == LikeFixture.ROWS) LikeFixture.rows(branchAfterRead = true) else it })
    @Test fun aCounterWithoutABooleanReadIsRefused() = refuses("makes 0 kinds of boolean read",
        LikeFixture.classes().map { if (it.type == LikeFixture.COUNTER) LikeFixture.counterClass(flagRead = false) else it })

    /** Refused for [reason], with every class as it was. */
    private fun refuses(reason: String, input: List<ClassDef>) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val refusal = assertThrows(PatchException::class.java) { context.applyHiddenLikeCounts(context.findHiddenLikeCounts()) }
        assertTrue(refusal.message, refusal.message!!.startsWith("$HIDDEN_LIKE_COUNTS_PATCH: ") && reason in refusal.message!!)
        input.forEach { assertEquals("${it.type} was edited before refusal", before[it.type], snapshot(context.mutableClassDefBy(it.type).methods)) }
    }

    companion object {
        /** The flag goes through the extension as an int and comes back in its own register, then [original] runs as it was. */
        internal fun assertDecisionHook(method: Method, original: List<Instruction>) {
            val code = method.code()
            val flag = method.implementation!!.registerCount - 1
            assertEquals(1, code.count { it.reference() == HIDDEN_DECISION })
            assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT), code.take(2).map { it.opcode })
            assertEquals(HIDDEN_DECISION, code[0].reference())
            assertEquals("the hook reads the flag", listOf(flag), code[0].namedRegisters())
            assertEquals("the answer goes back in the flag", listOf(flag), code[1].namedRegisters())
            assertEquals("two instructions come in front, nothing else", original.size + 2, code.size)
            assertEquals(original.map { it.shape() }, code.drop(2).map { it.shape() })
        }

        /**
         * The reader hands its tree and the count it read to the extension right after the read, at
         * [countAt] + 1, and nothing else changes.
         */
        internal fun assertCountHook(method: Method, original: List<Instruction>, countAt: Int, tree: Int, count: Int) {
            val code = method.code()
            assertEquals(1, code.count { it.reference() == SAW_LIKE_COUNT })
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[countAt].opcode)
            assertEquals(listOf(count), code[countAt].namedRegisters())
            assertEquals(LIKE_COUNT_KEY, (code[countAt - 2] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.INVOKE_STATIC, code[countAt + 1].opcode)
            assertEquals(SAW_LIKE_COUNT, code[countAt + 1].reference())
            assertEquals("the tree and the count", listOf(tree, count), code[countAt + 1].namedRegisters())
            assertEquals(original.size + 1, code.size)
            assertEquals(original.map { it.shape() }, (code.take(countAt + 1) + code.drop(countAt + 2)).map { it.shape() })
        }

        /**
         * The row hands its tree and the flag it just read to the extension right after keeping it,
         * at [flagAt] + 1, and nothing else changes.
         */
        internal fun assertRowHook(method: Method, original: List<Instruction>, flagAt: Int, tree: Int, flag: Int) {
            val code = method.code()
            assertEquals(1, code.count { it.reference() == ROW_READ })
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[flagAt].opcode)
            assertEquals(listOf(flag), code[flagAt].namedRegisters())
            assertEquals(Opcode.INVOKE_STATIC, code[flagAt + 1].opcode)
            assertEquals(ROW_READ, code[flagAt + 1].reference())
            assertEquals("the tree and the flag", listOf(tree, flag), code[flagAt + 1].namedRegisters())
            assertEquals(original.size + 1, code.size)
            assertEquals(original.map { it.shape() }, (code.take(flagAt + 1) + code.drop(flagAt + 2)).map { it.shape() })
        }

        /** The stub reads the flag off the tree it's given, by the key it's given, with [read]. */
        internal fun assertStub(method: Method, read: String) {
            val code = method.code()
            assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                code.take(4).map { it.opcode })
            val p0 = method.implementation!!.registerCount - 2
            assertEquals(read.substringBefore("->"), code[0].reference())
            assertEquals(listOf(p0), code[0].namedRegisters())
            assertEquals(read, code[1].reference())
            assertEquals("the tree and the key", listOf(p0, p0 + 1), code[1].namedRegisters())
            assertEquals(listOf(p0), code[2].namedRegisters())
            assertEquals(listOf(p0), code[3].namedRegisters())
        }

        internal fun snapshot(methods: Iterable<Method>) = methods.map { method -> method.toString() to method.code().map { it.shape() } }

        internal fun Method.code() = implementation?.instructions?.toList().orEmpty()
        internal fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
        private fun Instruction.shape() = Triple(opcode, reference(), namedRegisters())
    }
}

/**
 * Stand-ins in 450's shapes: the post model's getters, three like rows asking the decider after
 * reading the flag, the decider, and the like count reader. The extension is the real one.
 */
internal object LikeFixture {
    const val TREE = "Lfixture/Tree;"
    const val ROWS = "Lfixture/LikeRows;"
    const val DECIDER = "Lfixture/LikeDecider;"
    const val COUNTER = "Lfixture/LikeCounter;"
    private const val HOLDER = "Lfixture/Holder;"
    private const val SELF = "Lfixture/Self;"
    private const val OBJECT = "Ljava/lang/Object;"
    private const val BOOLEAN = "Ljava/lang/Boolean;"
    private const val INTEGER = "Ljava/lang/Integer;"
    private const val SESSION = "Lcom/instagram/common/session/UserSession;"
    private const val STRING = "Ljava/lang/String;"
    private val DECIDE = listOf(SESSION, STRING, "Z")
    private val flagRead = ImmutableMethodReference(TREE, "CtD", listOf("I"), BOOLEAN)
    private val countRead = ImmutableMethodReference(TREE, "CtN", listOf("I"), INTEGER)

    fun classes(): List<ClassDef> = listOf(post(), rows(), deciderClass(), counterClass(), ExtensionDex.classDef(HIDDEN_LIKE_COUNTS))

    /** The post model: a getter for each field, holding its key, and the like count getter calling the reader. */
    fun post(countGetter: Boolean = true, callsCounter: Boolean = true): ClassDef {
        fun getter(name: String, key: Int, read: ImmutableMethodReference, returns: String) = method(POST_MODEL, name, emptyList(), returns, 2, listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, key),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 1, 0, 0, 0, 0, read),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ), AccessFlags.PUBLIC.value)
        val methods = mutableListOf(getter("A4G", LIKES_HIDDEN_KEY, flagRead, BOOLEAN))
        if (countGetter) methods += getter("A6J", LIKE_COUNT_KEY, countRead, INTEGER)
        methods += method(POST_MODEL, "A0B", emptyList(), "I", 2, if (callsCounter) listOf(
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(COUNTER, "A00", listOf(HOLDER), "I")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        ) else listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        ), AccessFlags.PUBLIC.value)
        return clazz(POST_MODEL, methods)
    }

    /** [count] like rows, each reading the flag off the tree it's given and asking the decider with it. */
    fun rows(count: Int = 3, otherDecider: Boolean = false, branchAfterRead: Boolean = false): ClassDef = clazz(ROWS, (0 until count).map { index ->
        val decider = if (otherDecider && index == 0) "Lfixture/OtherDecider;" else DECIDER
        method(ROWS, "row$index", listOf(TREE, SESSION, STRING), "Z", 5, (if (branchAfterRead && index == 0) listOf(
            ImmutableInstruction21t(Opcode.IF_EQZ, 3, 9),
        ) else emptyList()) + listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, LIKES_HIDDEN_KEY),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 2, 0, 0, 0, 0, flagRead),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(BOOLEAN, "booleanValue", emptyList(), "Z")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 1, 3, 4, 0, 0, ImmutableMethodReference(decider, "A06", DECIDE, "Z")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        ), AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)
    })

    /**
     * 450's decider: hidden at once when the flag ([tested]) is set, shown when the post is yours,
     * hidden otherwise. One local, this in v1, the session, the id and the flag in v2 to v4.
     */
    fun decider(tested: Int = 4, answer: Int = 1): Method = method(DECIDER, "A06", DECIDE, "Z", 5, listOf(
        ImmutableInstruction21t(Opcode.IF_NEZ, tested, 8),
        ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 2, 3, 0, 0, 0, ImmutableMethodReference(SELF, "A05", listOf(SESSION, STRING), "Z")),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
        ImmutableInstruction21t(Opcode.IF_NEZ, 0, 4),
        ImmutableInstruction11n(Opcode.CONST_4, 0, answer),
        ImmutableInstruction11x(Opcode.RETURN, 0),
        ImmutableInstruction11n(Opcode.CONST_4, 0, 1 - answer),
        ImmutableInstruction11x(Opcode.RETURN, 0),
    ), AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)

    fun deciderClass(tested: Int = 4, answer: Int = 1): ClassDef = clazz(DECIDER, listOf(decider(tested, answer)))

    /**
     * 450's like count reader: the tree in v2, a boolean read of its own on it, then `like_count`
     * read into v0 at instruction 7 and kept at 8, its null test at 9, the count answered from v3.
     */
    fun counter(branchAfterRead: Boolean = false, flagRead: Boolean = true): Method = method(COUNTER, "A00", listOf(HOLDER), "I", 5, listOf(
        ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 4, ImmutableFieldReference(HOLDER, "A01", TREE)),
        ImmutableInstruction31i(Opcode.CONST, 0, -539271266),
        if (flagRead) ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 2, 0, 0, 0, 0, this.flagRead)
        else ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 0, 0, 0, 0, ImmutableMethodReference(HOLDER, "lightweight", listOf("I"), BOOLEAN)),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
        ImmutableInstruction21t(Opcode.IF_NEZ, 1, if (branchAfterRead) 9 else 15),
        ImmutableInstruction31i(Opcode.CONST, 0, LIKE_COUNT_KEY),
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 2, 0, 0, 0, 0, countRead),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, 6),
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, ImmutableMethodReference("Ljava/lang/Number;", "intValue", emptyList(), "I")),
        ImmutableInstruction11x(Opcode.MOVE_RESULT, 3),
        ImmutableInstruction11x(Opcode.RETURN, 3),
    ), AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value)

    fun counterClass(branchAfterRead: Boolean = false, flagRead: Boolean = true): ClassDef =
        clazz(COUNTER, listOf(counter(branchAfterRead, flagRead)))

    private fun clazz(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null, methods)

    private fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, code: List<Instruction>, flags: Int): Method =
        ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, code, null, null))
}
