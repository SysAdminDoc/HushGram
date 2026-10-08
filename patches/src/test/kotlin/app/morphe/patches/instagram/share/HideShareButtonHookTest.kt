/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.share

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.comments.COMMENTS_ENABLED_LABEL
import app.morphe.patches.instagram.feed.comments.COMMENTS_FEED_STATE
import app.morphe.patches.instagram.feed.comments.COMMENT_COUNT_LABEL
import app.morphe.patches.instagram.feed.comments.findFeedCommentState
import app.morphe.patches.instagram.feed.comments.prepareFeedComments
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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

/**
 * Feed's action-row state: the flags its toString prints after "isShareEnabled=" and
 * "shouldShowShareCountInUfi=" go through ShareButton right before the state's constructor stores
 * them. Reels: the check holding the shouldShowShareButton marker asks ShareButton first and
 * answers no when it says to hide.
 */
class HideShareButtonHookTest {
    @Test
    fun theButtonAndItsCountAreHookedWhereTheConstructorStoresThem() {
        val context = PatchContexts.of(listOf(state()))

        val found = context.findFeedShareState()
        assertEquals(STATE, found.type)
        assertEquals("each found through copies", listOf("share", "shareCount"), found.flags.map { it.name })
        assertEquals(listOf(2 to 0, 6 to 0), found.writes.map { it.at to it.value })
        assertTrue("nothing reads the value after its write", found.writes.all { it.answer == it.value })
        context.prepareFeedShare(found)()

        val code = context.constructor()
        assertEquals(2, code.count { it.calls(SHARE_FEED_STATE) })
        assertHooked("button", code, "share", SHARE_FEED_STATE)
        assertHooked("count", code, "shareCount", SHARE_FEED_STATE)
        assertEquals("the other flags are left alone", 0,
            code.count { it.calls(REPOSTS_FEED_STATE) || it.calls(COMMENTS_FEED_STATE) })
    }

    /** Hide comments and Hide the Repost button hook the same constructor, and in any order all six writes are hooked. */
    @Test
    fun besideTheOtherRowHooksEveryOrderHooksEveryWrite() {
        val steps = mapOf<String, (BytecodePatchContext) -> Unit>(
            "share" to { it.prepareFeedShare(it.findFeedShareState())() },
            "comments" to { it.prepareFeedComments(it.findFeedCommentState())() },
            "repost" to { it.hideFeedState(it.findFeedRepostState()) },
        )
        val orders = listOf(
            listOf("share", "comments", "repost"),
            listOf("comments", "share", "repost"),
            listOf("repost", "comments", "share"),
        )
        for (order in orders) {
            val context = PatchContexts.of(listOf(state()))
            order.forEach { steps.getValue(it)(context) }
            val code = context.constructor()
            val case = order.joinToString(" then ")
            for (hook in listOf(SHARE_FEED_STATE, COMMENTS_FEED_STATE, REPOSTS_FEED_STATE)) {
                assertEquals(case, 2, code.count { it.calls(hook) })
            }
            assertHooked(case, code, "share", SHARE_FEED_STATE)
            assertHooked(case, code, "shareCount", SHARE_FEED_STATE)
            assertHooked(case, code, "comments", COMMENTS_FEED_STATE)
            assertHooked(case, code, "commentCount", COMMENTS_FEED_STATE)
            assertHooked(case, code, "repost", REPOSTS_FEED_STATE)
            assertHooked(case, code, "repostCount", REPOSTS_FEED_STATE)
        }
    }

    @Test
    fun noStateOrTwoFailThePatch() {
        assertRefused("no state", PatchContexts.of(listOf(state(shareLabel = ", isSharingEnabled="))))
        assertRefused("two states", PatchContexts.of(listOf(state(), state(type = OTHER_STATE))))
    }

    @Test
    fun flagsThatArentTheRowsOwnFailThePatch() {
        for ((case, classDef) in listOf(
            "no count label" to state(countLabel = ", shareCount="),
            "a count from a constant" to state(countRead = "const/4 v1, 0x0"),
            "one flag for both" to state(countRead = "iget-boolean v1, p0, $STATE->share:Z"),
            "a button flag that isn't final" to state(shareFlags = AccessFlags.PUBLIC.value),
            "a count never set" to state(constructor = STORES.replace("iput-boolean v0, p0, $STATE->shareCount:Z", "nop")),
        )) assertRefused(case, PatchContexts.of(listOf(classDef)))
    }

    @Test
    fun theReelsCheckAnswersNoFirstWhenTheHookSaysToHide() {
        val context = PatchContexts.of(listOf(reels()))
        val check = context.findReelsShareCheck()
        assertEquals(REELS, check.definingClass)
        assertEquals("check", check.name)
        context.guardReelsShareCheck(check)

        val code = context.reelsCheck().implementation!!.instructions.toList()
        assertGuardFirst("fixture", code)
        val marker = code[5]
        assertEquals("the check's own code follows the guard", Opcode.CONST_STRING, marker.opcode)
        assertEquals(1, code.count { it.calls(HIDE_SHARE_IN_REELS) })
    }

    @Test
    fun aReelsCheckThatIsntTheOneFailsThePatch() {
        for ((case, classes) in listOf(
            "no marker" to listOf(reels(marker = "android_purge_26_q3_UfiUseCase_shouldShowSaveButton")),
            "two holders" to listOf(reels(), reels(type = OTHER_REELS)),
            "an instance method" to listOf(reels(flags = AccessFlags.PUBLIC.value)),
            "a check answering an object" to listOf(reels(result = "Ljava/lang/Boolean;")),
            "no viewer config" to listOf(reels(config = "Ljava/lang/String;")),
            "no local register" to listOf(reels(registers = 2)),
        )) {
            val context = PatchContexts.of(classes)
            assertThrows(case, PatchException::class.java) { context.findReelsShareCheck() }
            for (type in listOf(REELS, OTHER_REELS)) {
                val methods = runCatching { context.classDefBy(type).methods }.getOrDefault(emptyList())
                for (method in methods) {
                    assertTrue("$case: $type->${method.name} changed",
                        method.implementation!!.instructions.none { it.calls(HIDE_SHARE_IN_REELS) })
                }
            }
        }
    }

    /**
     * On each declared build the Feed state is found by its label, both Share flags are hooked right
     * before their one write each beside Hide comments' and Hide the Repost button's hooks, and the
     * Reels check is the one static check holding the marker, guarded first.
     */
    @Test
    fun eachDeclaredBuildTakesTheShareButtonOff() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val marked = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it.endsWith("_$REELS_SHOULD_SHOW_SHARE") } }) {
                    REELS_SHOULD_SHOW_SHARE in it.markers()
                }.mapTo(HashSet()) { it.definingClass }
                assertEquals("${bundle.name}: one class holds the Reels marker", 1, marked.size)
                val classes = FixtureDex.classesHolding(bundle, SHARE_ENABLED_LABEL) + FixtureDex.classes(bundle, marked).values
                val context = PatchContexts.of(classes)

                val found = context.findFeedShareState()
                assertEquals("${bundle.name}: the button and its count", 2, found.flags.size)
                assertEquals("${bundle.name}: each set once", 2, found.writes.size)
                assertTrue("${bundle.name}: in place", found.writes.all { it.answer == it.value })
                val reels = context.findReelsShareCheck()
                context.prepareFeedShare(found)()
                context.guardReelsShareCheck(reels)
                val comments = context.findFeedCommentState()
                assertEquals("${bundle.name}: the same state", found.type, comments.type)
                context.prepareFeedComments(comments)()
                val repost = context.findFeedRepostState()
                assertEquals("${bundle.name}: the same state", found.type, repost.type)
                context.hideFeedState(repost)

                val code = context.mutableClassDefBy(found.type).methods.single { it.name == "<init>" }
                    .implementation!!.instructions.toList()
                assertEquals(bundle.name, 2, code.count { it.calls(SHARE_FEED_STATE) })
                for (write in found.writes) {
                    assertHooked(bundle.name, code, write.field.name, SHARE_FEED_STATE, write.value, write.answer)
                }
                for (write in comments.writes) assertHooked(bundle.name, code, write.field.name, COMMENTS_FEED_STATE, write.value, write.answer)
                for (write in repost.writes) assertHooked(bundle.name, code, write.field.name, REPOSTS_FEED_STATE)

                val guarded = context.mutableClassDefBy(reels.definingClass).methods.single {
                    it.name == reels.name && it.parameterTypes.map(Any::toString) == reels.parameterTypes.map(Any::toString) &&
                        it.returnType == "Z"
                }.implementation!!.instructions.toList()
                assertGuardFirst(bundle.name, guarded)
                assertEquals(bundle.name, 1, guarded.count { it.calls(HIDE_SHARE_IN_REELS) })
                val marker = (guarded[5] as? ReferenceInstruction)?.reference as? StringReference
                assertTrue("${bundle.name}: the marker follows the guard", marker?.string?.endsWith("_$REELS_SHOULD_SHOW_SHARE") == true)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The guard asks the hook, and on yes answers 0, before the check's first instruction, where a no goes. */
    private fun assertGuardFirst(what: String, code: List<Instruction>) {
        assertTrue("$what: the hook first", code[0].calls(HIDE_SHARE_IN_REELS))
        assertEquals(what, Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals(what, 0, (code[0] as FiveRegisterInstruction).registerCount)
        assertEquals(what, Opcode.MOVE_RESULT, code[1].opcode)
        val register = (code[1] as OneRegisterInstruction).registerA
        assertEquals(what, Opcode.IF_EQZ, code[2].opcode)
        assertEquals(what, register, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: a no goes on to the check", 5, code.target(2))
        assertEquals(what, Opcode.CONST_4, code[3].opcode)
        assertEquals("$what: the answer is no", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(what, Opcode.RETURN, code[4].opcode)
        assertEquals("$what: the answer returned", (code[3] as OneRegisterInstruction).registerA,
            (code[4] as OneRegisterInstruction).registerA)
        assertFalse("$what: the guard is there once", code.drop(1).any { it.calls(HIDE_SHARE_IN_REELS) })
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
        assertThrows(case, PatchException::class.java) { context.findFeedShareState() }
        for (type in listOf(STATE, OTHER_STATE)) {
            val methods = runCatching { context.classDefBy(type).methods }.getOrDefault(emptyList())
            for (method in methods) {
                assertTrue("$case: $type->${method.name} changed",
                    method.implementation!!.instructions.none { it.calls(SHARE_FEED_STATE) })
            }
        }
    }

    private fun Instruction.calls(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    private fun BytecodePatchContext.constructor(): List<Instruction> =
        mutableClassDefBy(STATE).methods.single { it.name == "<init>" }.implementation!!.instructions.toList()

    private fun BytecodePatchContext.reelsCheck(): Method = mutableClassDefBy(REELS).methods.single { it.name == "check" }

    private companion object {
        const val STATE = "Lfixture/FeedRowState;"
        const val OTHER_STATE = "Lfixture/OtherRowState;"
        const val REELS = "Lfixture/ReelsUfi;"
        const val OTHER_REELS = "Lfixture/OtherReelsUfi;"
        const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"
        const val MARKER = "android_purge_26_q3_UfiUseCase_shouldShowShareButton"
        const val APPEND_STRING = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;"
        const val APPEND_BOOLEAN = "Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;"
        val FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value

        /** As 450 writes them: each flag moved out of its parameter and stored, the register used again. */
        val STORES = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            move v0, p1
            iput-boolean v0, p0, $STATE->share:Z
            move v0, p2
            iput-boolean v0, p0, $STATE->repost:Z
            move v0, p3
            iput-boolean v0, p0, $STATE->shareCount:Z
            move v0, p4
            iput-boolean v0, p0, $STATE->repostCount:Z
            move v0, p5
            iput-boolean v0, p0, $STATE->comments:Z
            move v0, p6
            iput-boolean v0, p0, $STATE->commentCount:Z
            return-void
        """

        /**
         * The state: the Share flags, the Repost flags and the comment flags, a constructor storing
         * them, and a toString that prints each after its label. As 450's does, it reads the Share
         * flags through a copy of this, keeps each in another register, and copies it back right
         * before appending it.
         */
        fun state(
            type: String = STATE,
            shareLabel: String = SHARE_ENABLED_LABEL,
            countLabel: String = SHARE_COUNT_LABEL,
            countRead: String = "iget-boolean v1, p0, $type->shareCount:Z",
            shareFlags: Int = FINAL,
            constructor: String = STORES,
        ): ClassDef {
            val printer = """
                move-object v8, p0
                iget-boolean v1, v8, $type->share:Z
                move v4, v1
                $countRead
                move v5, v1
                iget-boolean v2, p0, $type->repost:Z
                iget-boolean v3, p0, $type->repostCount:Z
                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct { v0 }, Ljava/lang/StringBuilder;-><init>()V
                const-string v6, "$shareLabel"
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
                iget-boolean v7, p0, $type->comments:Z
                const-string v6, "$COMMENTS_ENABLED_LABEL"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                invoke-virtual { v0, v7 }, $APPEND_BOOLEAN
                iget-boolean v7, p0, $type->commentCount:Z
                const-string v6, "$COMMENT_COUNT_LABEL"
                invoke-virtual { v0, v6 }, $APPEND_STRING
                invoke-virtual { v0, v7 }, $APPEND_BOOLEAN
                invoke-virtual { v0 }, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
            val methods = listOf(
                method(type, "<init>", List(6) { "Z" }, "V", 8,
                    AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, constructor.replace(STATE, type)),
                method(type, "toString", emptyList(), "Ljava/lang/String;", 10, AccessFlags.PUBLIC.value, printer),
            )
            val fields = listOf(
                ImmutableField(type, "share", "Z", shareFlags, null, null, null),
                ImmutableField(type, "shareCount", "Z", FINAL, null, null, null),
                ImmutableField(type, "repost", "Z", FINAL, null, null, null),
                ImmutableField(type, "repostCount", "Z", FINAL, null, null, null),
                ImmutableField(type, "comments", "Z", FINAL, null, null, null),
                ImmutableField(type, "commentCount", "Z", FINAL, null, null, null),
            )
            return ImmutableClassDef(type, FINAL, "Ljava/lang/Object;", null, null, null, fields, methods)
        }

        /**
         * The Reels check as 450 has it: static, taking the viewer's config among its parameters,
         * loading its marker first and answering a boolean. [registers] counts its two parameters.
         */
        fun reels(
            type: String = REELS,
            marker: String = MARKER,
            flags: Int = STATIC,
            result: String = "Z",
            config: String = VIEWER_CONFIG,
            registers: Int = 4,
        ): ClassDef {
            val static = AccessFlags.STATIC.isSet(flags)
            val first = if (static) "p0" else "p1"
            val answer = if (result == "Z") "return v0" else "return-object v0"
            // With no local, the marker goes into a parameter's register, which the check no longer needs.
            val body = if (registers <= 2) """
                const-string p0, "$marker"
                invoke-static { p0 }, Lfixture/Purge;->mark(Ljava/lang/String;)V
                const/4 p0, 0x0
                return p0
            """ else """
                const-string v0, "$marker"
                invoke-static { v0 }, Lfixture/Purge;->mark(Ljava/lang/String;)V
                if-eqz $first, :no
                const/4 v0, 0x1
                $answer
                :no
                const/4 v0, 0x0
                $answer
            """
            val method = method(type, "check", listOf(config, "Ljava/lang/Object;"), result,
                if (static) registers else registers + 1, flags, body)
            return ImmutableClassDef(type, FINAL, "Ljava/lang/Object;", null, null, null, null, listOf(method))
        }

        fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, flags: Int,
                   body: String): Method = MutableMethod(ImmutableMethod(owner, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
            addInstructionsWithLabels(0, body.trimIndent())
        }.let(ImmutableMethod::of)
    }
}
