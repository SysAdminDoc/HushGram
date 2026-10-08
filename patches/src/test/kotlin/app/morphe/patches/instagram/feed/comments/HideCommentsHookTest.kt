/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.comments

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.share.REPOSTS_FEED_STATE
import app.morphe.patches.instagram.share.REPOST_COUNT_LABEL
import app.morphe.patches.instagram.share.REPOST_ENABLED_LABEL
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

/**
 * Feed's action-row state: the flags its toString prints after "isCommentsEnabled=" and
 * "shouldShowCommentCountInUfi=" go through CommentsButton right before the state's constructor
 * stores them, so a row drawn again from the same state still has no Comment button or count.
 */
class HideCommentsHookTest {
    @Test
    fun theButtonAndItsCountAreHookedWhereTheConstructorStoresThem() {
        val context = PatchContexts.of(listOf(state()))

        val found = context.findFeedCommentState()
        assertEquals(STATE, found.type)
        assertEquals("each found through 450's copies", listOf("comments", "commentCount"), found.flags.map { it.name })
        assertEquals(listOf(2 to 0, 6 to 0), found.writes.map { it.at to it.value })
        assertTrue("nothing reads the value after its write", found.writes.all { it.answer == it.value })
        context.prepareFeedComments(found)()

        val code = context.constructor()
        assertEquals(2, code.count { it.calls(COMMENTS_FEED_STATE) })
        assertHooked("button", code, "comments", COMMENTS_FEED_STATE)
        assertHooked("count", code, "commentCount", COMMENTS_FEED_STATE)
        assertEquals("the Repost flags are left alone", 0, code.count { it.calls(REPOSTS_FEED_STATE) })
    }

    /** Hide the Repost button hooks the same constructor, and whichever goes first, all four writes are hooked. */
    @Test
    fun withHideTheRepostButtonEitherOrderHooksEveryWrite() {
        for (commentsFirst in listOf(true, false)) {
            val context = PatchContexts.of(listOf(state()))
            if (commentsFirst) {
                context.prepareFeedComments(context.findFeedCommentState())()
                context.hideFeedState(context.findFeedRepostState())
            } else {
                context.hideFeedState(context.findFeedRepostState())
                context.prepareFeedComments(context.findFeedCommentState())()
            }
            val code = context.constructor()
            val case = if (commentsFirst) "comments first" else "Repost first"
            assertEquals(case, 2, code.count { it.calls(COMMENTS_FEED_STATE) })
            assertEquals(case, 2, code.count { it.calls(REPOSTS_FEED_STATE) })
            assertHooked(case, code, "comments", COMMENTS_FEED_STATE)
            assertHooked(case, code, "commentCount", COMMENTS_FEED_STATE)
            assertHooked(case, code, "repost", REPOSTS_FEED_STATE)
            assertHooked(case, code, "repostCount", REPOSTS_FEED_STATE)
        }
    }

    @Test
    fun noStateOrTwoFailThePatch() {
        assertRefused("no state", PatchContexts.of(listOf(state(commentsLabel = ", isCommentEnabled="))))
        assertRefused("two states", PatchContexts.of(listOf(state(), state(type = OTHER_STATE))))
    }

    @Test
    fun flagsThatArentTheRowsOwnFailThePatch() {
        for ((case, classDef) in listOf(
            "no count label" to state(countLabel = ", commentCount="),
            "a count from a constant" to state(countRead = "const/4 v1, 0x0"),
            "one flag for both" to state(countRead = "iget-boolean v1, p0, $STATE->comments:Z"),
            "a button flag that isn't final" to state(commentsFlags = AccessFlags.PUBLIC.value),
            "a count never set" to state(constructor = STORES.replace("iput-boolean v0, p0, $STATE->commentCount:Z", "nop")),
        )) assertRefused(case, PatchContexts.of(listOf(classDef)))
    }

    /**
     * On each declared build the state is found by its label, both comment flags are hooked right
     * before their one write each, and Hide the Repost button's hooks land beside them in the same
     * constructor.
     */
    @Test
    fun eachDeclaredBuildTakesTheCommentButtonOffBesideTheRepostHooks() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val context = PatchContexts.of(FixtureDex.classesHolding(bundle, COMMENTS_ENABLED_LABEL))
                val found = context.findFeedCommentState()
                assertEquals("${bundle.name}: the button and its count", 2, found.flags.size)
                assertEquals("${bundle.name}: each set once", 2, found.writes.size)
                assertTrue("${bundle.name}: in place", found.writes.all { it.answer == it.value })
                context.prepareFeedComments(found)()
                val repost = context.findFeedRepostState()
                assertEquals("${bundle.name}: the same state", found.type, repost.type)
                context.hideFeedState(repost)

                val code = context.mutableClassDefBy(found.type).methods.single { it.name == "<init>" }
                    .implementation!!.instructions.toList()
                assertEquals(bundle.name, 2, code.count { it.calls(COMMENTS_FEED_STATE) })
                assertEquals(bundle.name, 2, code.count { it.calls(REPOSTS_FEED_STATE) })
                for (write in found.writes) {
                    assertHooked(bundle.name, code, write.field.name, COMMENTS_FEED_STATE, write.value, write.answer)
                }
                for (write in repost.writes) assertHooked(bundle.name, code, write.field.name, REPOSTS_FEED_STATE)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

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
        assertEquals("$what: the call reads the value", value, call.registerC)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
        assertEquals("$what: the answer's register", answer, (code[at - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the write stores the answer", answer, (code[at] as TwoRegisterInstruction).registerA)
    }

    private fun assertRefused(case: String, context: BytecodePatchContext) {
        assertThrows(case, PatchException::class.java) { context.findFeedCommentState() }
        for (type in listOf(STATE, OTHER_STATE)) {
            val methods = runCatching { context.classDefBy(type).methods }.getOrDefault(emptyList())
            for (method in methods) {
                assertTrue("$case: $type->${method.name} changed",
                    method.implementation!!.instructions.none { it.calls(COMMENTS_FEED_STATE) })
            }
        }
    }

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun BytecodePatchContext.constructor(): List<Instruction> =
        mutableClassDefBy(STATE).methods.single { it.name == "<init>" }.implementation!!.instructions.toList()

    private companion object {
        const val STATE = "Lfixture/FeedRowState;"
        const val OTHER_STATE = "Lfixture/OtherRowState;"
        const val APPEND_STRING = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"
        const val APPEND_BOOLEAN = "Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;"
        val FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        /** As 450 writes them: each flag moved out of its parameter and stored, the register used again. */
        val STORES = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            move v0, p1
            iput-boolean v0, p0, $STATE->comments:Z
            move v0, p2
            iput-boolean v0, p0, $STATE->repost:Z
            move v0, p3
            iput-boolean v0, p0, $STATE->commentCount:Z
            move v0, p4
            iput-boolean v0, p0, $STATE->repostCount:Z
            return-void
        """

        /**
         * The state: the comment flags and the Repost flags, a constructor storing them, and a
         * toString that prints each after its label. As 450's does, it reads the comment flags
         * through a copy of this, keeps each in another register, and copies it back right before
         * appending it.
         */
        fun state(
            type: String = STATE,
            commentsLabel: String = COMMENTS_ENABLED_LABEL,
            countLabel: String = COMMENT_COUNT_LABEL,
            countRead: String = "iget-boolean v1, p0, $type->commentCount:Z",
            commentsFlags: Int = FINAL,
            constructor: String = STORES,
        ): ClassDef {
            val printer = """
                move-object v7, p0
                iget-boolean v1, v7, $type->comments:Z
                move v4, v1
                $countRead
                move v5, v1
                iget-boolean v2, p0, $type->repost:Z
                iget-boolean v3, p0, $type->repostCount:Z
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct { v0 }, Ljava/lang/StringBuilder;-><init>()V
                const-string v6, "$commentsLabel"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                move v1, v4
                invoke-virtual { v0, v1 }, $APPEND_BOOLEAN
                const-string v6, "$countLabel"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                move v1, v5
                invoke-virtual { v0, v1 }, $APPEND_BOOLEAN
                const-string v6, "$REPOST_ENABLED_LABEL"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                invoke-virtual { v0, v2 }, $APPEND_BOOLEAN
                const-string v6, "$REPOST_COUNT_LABEL"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                invoke-virtual { v0, v3 }, $APPEND_BOOLEAN
                invoke-virtual { v0 }, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
            val methods = listOf(
                method(type, "<init>", listOf("Z", "Z", "Z", "Z"), "V", 6,
                    AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, constructor.replace(STATE, type)),
                method(type, "toString", emptyList(), "Ljava/lang/String;", 9, AccessFlags.PUBLIC.value, printer),
            )
            val fields = listOf(
                ImmutableField(type, "comments", "Z", commentsFlags, null, null, null),
                ImmutableField(type, "commentCount", "Z", FINAL, null, null, null),
                ImmutableField(type, "repost", "Z", FINAL, null, null, null),
                ImmutableField(type, "repostCount", "Z", FINAL, null, null, null),
            )
            return ImmutableClassDef(type, FINAL, "Ljava/lang/Object;", null, null, null, fields, methods)
        }

        fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, flags: Int,
                   body: String): Method = MutableMethod(ImmutableMethod(owner, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, body.trimIndent())
        }.let(ImmutableMethod::of)
    }
}
