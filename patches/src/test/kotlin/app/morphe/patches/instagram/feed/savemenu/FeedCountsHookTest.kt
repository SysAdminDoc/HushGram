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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.comments.COMMENTS_ENABLED_LABEL
import app.morphe.patches.instagram.feed.comments.COMMENTS_FEED_STATE
import app.morphe.patches.instagram.feed.comments.COMMENT_COUNT_LABEL
import app.morphe.patches.instagram.feed.comments.findFeedCommentState
import app.morphe.patches.instagram.feed.comments.prepareFeedComments
import app.morphe.patches.instagram.feed.feedRowState
import app.morphe.patches.instagram.feed.isToString
import app.morphe.patches.instagram.feed.printedFlag
import app.morphe.patches.instagram.share.REPOST_COUNT_LABEL
import app.morphe.patches.instagram.share.SHARE_COUNT_LABEL
import app.morphe.patches.instagram.share.findFeedRepostState
import app.morphe.patches.instagram.share.findFeedShareState
import app.morphe.patches.instagram.share.hideFeedState
import app.morphe.patches.instagram.share.prepareFeedShare
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide counts on posts (#762): the comment, share and repost count flags Feed's action-row state
 * prints go through FeedSaveRow right before the state's constructor stores them, after the hooks
 * Hide comments, Hide the Share button and Hide the Repost button put on the same writes.
 */
class FeedCountsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(SAVE_ROW).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(COUNT_STATE, LIKES_ROW)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun eachCountIsHookedWhereTheConstructorStoresIt() {
        val context = PatchContexts.of(listOf(state()))

        assertEquals(3, context.hideFeedCounts())

        val code = context.constructor()
        assertEquals(3, code.count { it.calls(COUNT_STATE) })
        for (name in listOf("commentCount", "shareCount", "repostCount")) assertHooked(name, code, name, COUNT_STATE)
        assertEquals("the like count is left to its own hook", 0, code.count { it.calls(LIKE_COUNT_STATE) })
        assertWriteFollows(code, "comments", "a flag that isn't a count")
    }

    /** Hide comments hooks the comment count first, and the count hook reads its answer. */
    @Test
    fun afterHideCommentsTheCountHookReadsItsAnswer() {
        val context = PatchContexts.of(listOf(state()))
        context.prepareFeedComments(context.findFeedCommentState())()
        assertEquals(3, context.hideFeedCounts())

        val code = context.constructor()
        assertEquals(2, code.count { it.calls(COMMENTS_FEED_STATE) })
        val store = code.indices.single { code[it].stores("commentCount") }
        assertTrue("the count hook last", code[store - 2].calls(COUNT_STATE))
        assertTrue("Hide comments' hook before it", code[store - 4].calls(COMMENTS_FEED_STATE))
        val commentsAnswer = (code[store - 3] as OneRegisterInstruction).registerA
        assertEquals("the count hook takes Hide comments' answer", commentsAnswer, (code[store - 2] as FiveRegisterInstruction).registerC)
        assertEquals("the write stores the count hook's answer",
            (code[store - 1] as OneRegisterInstruction).registerA, (code[store] as TwoRegisterInstruction).registerA)
    }

    /** A state that stopped printing one count leaves only that count, and the others go in. */
    @Test
    fun aCountThatMovedLeavesOnlyItself() {
        val context = PatchContexts.of(listOf(state(shareLabel = ", shareCount=")))

        assertEquals(2, context.hideFeedCounts())

        val code = context.constructor()
        assertEquals(2, code.count { it.calls(COUNT_STATE) })
        assertHooked("comment count", code, "commentCount", COUNT_STATE)
        assertHooked("repost count", code, "repostCount", COUNT_STATE)
        assertWriteFollows(code, "shareCount", "the count that moved")
    }

    /**
     * On each build the three counts are hooked right before each write, after the hooks Hide
     * comments, Hide the Share button and Hide the Repost button put there.
     */
    @Test
    fun eachBuildHooksEveryCountAfterTheOtherHooks() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            builds(bundle, "${bundle.parentFile.name}/${bundle.name}")
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    private fun builds(bundle: File, label: String) {
        val states = FixtureDex.classesHolding(bundle, LIKE_COUNT_LABEL)
        val context = PatchContexts.of(states + FixtureDex.classesCreating(bundle, states.mapTo(HashSet()) { it.type }))
        context.findFeedLikeCount().write()
        context.prepareFeedComments(context.findFeedCommentState())()
        context.prepareFeedShare(context.findFeedShareState())()
        context.hideFeedState(context.findFeedRepostState())

        assertEquals("$label: every count is hooked", 3, context.hideFeedCounts())

        val state = context.feedRowState("test", COMMENT_COUNT_LABEL)
        val toString = state.methods.single { it.isToString() }
        val owners = (states + FixtureDex.classesCreating(bundle, setOf(state.type))).map { it.type }.toSet()
        for (countLabel in listOf(COMMENT_COUNT_LABEL, SHARE_COUNT_LABEL, REPOST_COUNT_LABEL)) {
            val flag = toString.printedFlag("test", state.type, countLabel)
            var writes = 0
            for (owner in owners) for (method in context.mutableClassDefBy(owner).methods) {
                val code = method.implementation?.instructions?.toList() ?: continue
                for (at in code.indices) {
                    val stored = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
                    if (code[at].opcode != Opcode.IPUT_BOOLEAN || stored?.name != flag.name || stored.definingClass != flag.definingClass) continue
                    writes++
                    assertTrue("$label: $countLabel's hook right before ${method.name}'s write", code[at - 2].calls(COUNT_STATE))
                    assertEquals("$label: $countLabel's answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
                    assertEquals("$label: $countLabel's write stores the answer",
                        (code[at - 1] as OneRegisterInstruction).registerA, (code[at] as TwoRegisterInstruction).registerA)
                }
            }
            assertTrue("$label: $countLabel is written", writes > 0)
        }
    }

    /** The one write of [name] stores [hook]'s answer, the call right before it taking one value. */
    private fun assertHooked(what: String, code: List<Instruction>, name: String, hook: String) {
        val stores = code.indices.filter { code[it].stores(name) }
        assertEquals("$what: one write of $name", 1, stores.size)
        val at = stores.single()
        val call = code[at - 2]
        assertTrue("$what: $name's call before the write", call.calls(hook))
        assertEquals("$what: the call's one argument", 1, (call as FiveRegisterInstruction).registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at - 1].opcode)
        assertEquals("$what: the write stores the answer",
            (code[at - 1] as OneRegisterInstruction).registerA, (code[at] as TwoRegisterInstruction).registerA)
    }

    /** The write of [name] is left as it was: right after the move that loads its value. */
    private fun assertWriteFollows(code: List<Instruction>, name: String, what: String) {
        val at = code.indices.single { code[it].stores(name) }
        assertEquals("$what is left alone", Opcode.MOVE, code[at - 1].opcode)
    }

    private fun Instruction.stores(name: String) = opcode == Opcode.IPUT_BOOLEAN &&
        ((this as ReferenceInstruction).reference as FieldReference).name == name

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun BytecodePatchContext.constructor(): List<Instruction> =
        mutableClassDefBy(STATE).methods.single { it.name == "<init>" }.implementation!!.instructions.toList()

    private companion object {
        const val STATE = "Lfixture/FeedRowState;"
        const val APPEND_STRING = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"
        const val APPEND_BOOLEAN = "Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;"
        val FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val FLAGS = listOf("likeCount", "comments", "commentCount", "shareCount", "repostCount")

        /** The state: its flags, a constructor storing each from its parameter, a toString printing each. */
        fun state(shareLabel: String = SHARE_COUNT_LABEL): ClassDef {
            val labels = listOf(LIKE_COUNT_LABEL, COMMENTS_ENABLED_LABEL, COMMENT_COUNT_LABEL, shareLabel, REPOST_COUNT_LABEL)
            val stores = buildString {
                appendLine("invoke-direct { p0 }, Ljava/lang/Object;-><init>()V")
                FLAGS.forEachIndexed { at, name ->
                    appendLine("move v0, p${at + 1}")
                    appendLine("iput-boolean v0, p0, $STATE->$name:Z")
                }
                appendLine("return-void")
            }
            val printer = buildString {
                appendLine("new-instance v0, Ljava/lang/StringBuilder;")
                appendLine("invoke-direct { v0 }, Ljava/lang/StringBuilder;-><init>()V")
                FLAGS.zip(labels).forEach { (name, label) ->
                    appendLine("iget-boolean v1, p0, $STATE->$name:Z")
                    appendLine("const-string v2, \"$label\"")
                    appendLine("invoke-virtual { v0, v2 }, $APPEND_STRING")
                    appendLine("invoke-virtual { v0, v1 }, $APPEND_BOOLEAN")
                }
                appendLine("invoke-virtual { v0 }, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;")
                appendLine("move-result-object v0")
                appendLine("return-object v0")
            }
            val methods = listOf(
                method("<init>", FLAGS.map { "Z" }, "V", 7, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, stores),
                method("toString", emptyList(), "Ljava/lang/String;", 4, AccessFlags.PUBLIC.value, printer),
            )
            val fields = FLAGS.map { ImmutableField(STATE, it, "Z", FINAL, null, null, null) }
            return ImmutableClassDef(STATE, FINAL, "Ljava/lang/Object;", null, null, null, fields, methods)
        }

        fun method(name: String, parameters: List<String>, result: String, registers: Int, flags: Int, body: String): Method =
            MutableMethod(ImmutableMethod(STATE, name, parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags,
                null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body)
            }.let(ImmutableMethod::of)
    }
}
