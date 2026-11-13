/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.savemenu

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedStateWrite
import app.morphe.patches.instagram.feed.comments.COMMENTS_ENABLED_LABEL
import app.morphe.patches.instagram.feed.comments.COMMENTS_FEED_STATE
import app.morphe.patches.instagram.feed.comments.COMMENT_COUNT_LABEL
import app.morphe.patches.instagram.feed.comments.findFeedCommentState
import app.morphe.patches.instagram.feed.comments.prepareFeedComments
import app.morphe.patches.instagram.share.REPOSTS_FEED_STATE
import app.morphe.patches.instagram.share.findFeedRepostState
import app.morphe.patches.instagram.share.hideFeedState
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the Like button (#668): the flag Feed's action-row state prints after
 * "shouldShowLikeCountInUfi=" goes through FeedSaveRow right before the state's constructor stores
 * it, so the row is laid out with no like count, beside the hooks Hide comments and Hide the Repost
 * button put in the same constructor.
 */
class FeedLikeCountHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SAVE_ROW).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$LIKE_COUNT_STATE is not in the extension: $declared", LIKE_COUNT_STATE.substringAfter("->") in declared)
    }

    @Test
    fun theCountIsHookedWhereTheConstructorStoresIt() {
        val context = PatchContexts.of(listOf(state()))

        val found = context.findFeedLikeCount()
        assertEquals(STATE, found.type)
        assertEquals(listOf(2 to 0), found.writes.map { it.at to it.value })
        assertTrue("before the write, nothing is changed", context.constructor().none { it.calls(LIKE_COUNT_STATE) })
        found.write()

        val code = context.constructor()
        assertEquals(1, code.count { it.calls(LIKE_COUNT_STATE) })
        assertHooked("count", code, "likeCount", LIKE_COUNT_STATE)
        assertEquals("the comment flags are left alone", 0, code.count { it.calls(COMMENTS_FEED_STATE) })
    }

    /** Hide comments hooks the same constructor, and whichever goes first, every write is hooked. */
    @Test
    fun withHideCommentsEitherOrderHooksEveryWrite() {
        for (likeFirst in listOf(true, false)) {
            val context = PatchContexts.of(listOf(state()))
            if (likeFirst) {
                context.findFeedLikeCount().write()
                context.prepareFeedComments(context.findFeedCommentState())()
            } else {
                context.prepareFeedComments(context.findFeedCommentState())()
                context.findFeedLikeCount().write()
            }
            val code = context.constructor()
            val case = if (likeFirst) "like count first" else "comments first"
            assertEquals(case, 1, code.count { it.calls(LIKE_COUNT_STATE) })
            assertEquals(case, 2, code.count { it.calls(COMMENTS_FEED_STATE) })
            assertHooked(case, code, "likeCount", LIKE_COUNT_STATE)
            assertHooked(case, code, "comments", COMMENTS_FEED_STATE)
            assertHooked(case, code, "commentCount", COMMENTS_FEED_STATE)
        }
    }

    @Test
    fun aCountThatIsntTheRowsOwnLeavesTheStateAlone() {
        for ((case, classDef) in listOf(
            "no count label" to state(likeLabel = ", likeCount="),
            "a count from a constant" to state(likeRead = "const/4 v1, 0x0"),
            "a count that isn't final" to state(likeFlags = AccessFlags.PUBLIC.value),
            "a count never set" to state(constructor = STORES.replace("iput-boolean v0, p0, $STATE->likeCount:Z", "nop")),
        )) {
            val context = PatchContexts.of(listOf(classDef))
            assertThrows(case, PatchException::class.java) { context.findFeedLikeCount() }
            for (method in context.classDefBy(STATE).methods) {
                assertTrue("$case: ${method.name} changed", method.implementation!!.instructions.none { it.calls(LIKE_COUNT_STATE) })
            }
        }
    }

    /**
     * On each build of 454 the state is found by its label and the like count is hooked right before
     * each write, with Hide comments' and Hide the Repost button's hooks beside it in the same state.
     */
    @Test
    fun eachBuildLeavesTheLikeCountOutBesideTheOtherHooks() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            builds(bundle, "${bundle.parentFile.name}/${bundle.name}")
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun builds(bundle: File, label: String) {
        val states = FixtureDex.classesHolding(bundle, LIKE_COUNT_LABEL)
        val context = PatchContexts.of(states + FixtureDex.classesCreating(bundle, states.mapTo(HashSet()) { it.type }))
        val found = context.findFeedLikeCount()
        assertTrue("$label: the count is set", found.writes.isNotEmpty())
        found.write()
        val comments = context.findFeedCommentState()
        assertEquals("$label: the same state as Hide comments", found.type, comments.type)
        context.prepareFeedComments(comments)()
        val repost = context.findFeedRepostState()
        assertEquals("$label: the same state as Hide the Repost button", found.type, repost.type)
        context.hideFeedState(repost)

        for ((method, writes) in found.writes.groupBy { Triple(it.owner, it.name, it.parameters) }) {
            val code = context.mutableClassDefBy(method.first).methods.single {
                it.name == method.second && it.parameterTypes.map(CharSequence::toString) == method.third
            }.implementation!!.instructions.toList()
            assertEquals("$label: ${method.second}", writes.size, code.count { it.calls(LIKE_COUNT_STATE) })
            for (write in writes) assertHooked(label, code, write, LIKE_COUNT_STATE)
            for (write in comments.writes.filter { it.owner == method.first && it.name == method.second }) {
                assertHooked(label, code, write, COMMENTS_FEED_STATE)
            }
            for (write in repost.writes.filter { it.owner == method.first && it.name == method.second }) {
                assertHooked(label, code, write.field.name, REPOSTS_FEED_STATE)
            }
        }
    }

    private fun assertHooked(what: String, code: List<Instruction>, write: FeedStateWrite, hook: String) =
        assertHooked(what, code, write.field.name, hook, write.value, write.answer)

    /** The one write of [name] stores [answer], right after [hook] takes [value] and answers into [answer]. */
    private fun assertHooked(what: String, code: List<Instruction>, name: String, hook: String, value: Int = 0, answer: Int = 0) {
        val stores = code.indices.filter { at ->
            val stored = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
            code[at].opcode == Opcode.IPUT_BOOLEAN && stored?.name == name
        }
        assertEquals("$what: one write of $name", 1, stores.size)
        val at = stores.single()
        val call = code[at - 2]
        assertTrue("$what: $name's call before the write", call.calls(hook))
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, call.opcode)
        call as FiveRegisterInstruction
        assertEquals("$what: the call's one argument", 1, call.registerCount)
        if (hook == LIKE_COUNT_STATE || hook == COMMENTS_FEED_STATE) {
            assertEquals("$what: the call reads the value", value, call.registerC)
        }
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
        val answered = (code[at - 1] as OneRegisterInstruction).registerA
        if (hook == LIKE_COUNT_STATE || hook == COMMENTS_FEED_STATE) assertEquals("$what: the answer's register", answer, answered)
        assertEquals("$what: the write stores the answer", answered, (code[at] as TwoRegisterInstruction).registerA)
    }

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun BytecodePatchContext.constructor(): List<Instruction> =
        mutableClassDefBy(STATE).methods.single { it.name == "<init>" }.implementation!!.instructions.toList()

    private companion object {
        const val STATE = "Lfixture/FeedRowState;"
        const val APPEND_STRING = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"
        const val APPEND_BOOLEAN = "Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;"
        val FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        /** Each flag moved out of its parameter and stored, the register used again. */
        val STORES = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            move v0, p1
            iput-boolean v0, p0, $STATE->likeCount:Z
            move v0, p2
            iput-boolean v0, p0, $STATE->comments:Z
            move v0, p3
            iput-boolean v0, p0, $STATE->commentCount:Z
            return-void
        """

        /** The state: the like count and the comment flags, a constructor storing them, a toString printing each. */
        fun state(
            likeLabel: String = LIKE_COUNT_LABEL,
            likeRead: String = "iget-boolean v1, p0, $STATE->likeCount:Z",
            likeFlags: Int = FINAL,
            constructor: String = STORES,
        ): ClassDef {
            val printer = """
                $likeRead
                iget-boolean v2, p0, $STATE->comments:Z
                iget-boolean v3, p0, $STATE->commentCount:Z
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct { v0 }, Ljava/lang/StringBuilder;-><init>()V
                const-string v6, "$likeLabel"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                invoke-virtual { v0, v1 }, $APPEND_BOOLEAN
                const-string v6, "$COMMENTS_ENABLED_LABEL"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                invoke-virtual { v0, v2 }, $APPEND_BOOLEAN
                const-string v6, "$COMMENT_COUNT_LABEL"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                invoke-virtual { v0, v3 }, $APPEND_BOOLEAN
                invoke-virtual { v0 }, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
            val methods = listOf(
                method("<init>", listOf("Z", "Z", "Z"), "V", 5, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, constructor),
                method("toString", emptyList(), "Ljava/lang/String;", 9, AccessFlags.PUBLIC.value, printer),
            )
            val fields = listOf(
                ImmutableField(STATE, "likeCount", "Z", likeFlags, null, null, null),
                ImmutableField(STATE, "comments", "Z", FINAL, null, null, null),
                ImmutableField(STATE, "commentCount", "Z", FINAL, null, null, null),
            )
            return ImmutableClassDef(STATE, FINAL, "Ljava/lang/Object;", null, null, null, fields, methods)
        }

        fun method(name: String, parameters: List<String>, result: String, registers: Int, flags: Int, body: String): Method =
            MutableMethod(ImmutableMethod(STATE, name, parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags,
                null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.trimIndent())
            }.let(ImmutableMethod::of)
    }
}
