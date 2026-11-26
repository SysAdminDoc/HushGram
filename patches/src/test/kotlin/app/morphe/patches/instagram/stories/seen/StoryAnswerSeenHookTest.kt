/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.stories.seen.StoryAnswerFixtures.COMPOSER
import app.morphe.patches.instagram.stories.seen.StoryAnswerFixtures.FLUSHER
import app.morphe.patches.instagram.stories.seen.StoryAnswerFixtures.FLUSH_BY_COUNT
import app.morphe.patches.instagram.stories.seen.StoryAnswerFixtures.LIKE_BUTTON
import app.morphe.patches.instagram.stories.seen.StoryAnswerFixtures.LIKE_STORE
import app.morphe.patches.instagram.stories.seen.StoryAnswerFixtures.MEDIA_TYPE
import app.morphe.patches.instagram.stories.seen.StoryAnswerFixtures.REACTION
import app.morphe.patches.instagram.stories.seen.StoryAnswerFixtures.REPLY_SENDER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Mark a story seen when you like or reply (#876): where its three hooks go and what its stubs reach. */
class StoryAnswerSeenHookTest {
    /** The hooks are public and static in the extension the bundle ships, none takes a boolean or shares its name, and the stubs are there to fill. */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val methods = ExtensionDex.classDef(STORY_ANSWER_SEEN).methods
        fun key(method: Method) = "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
        for (hook in ANSWER_HOOKS) {
            val method = methods.singleOrNull { key(it) == hook.substringAfter("->") }
                ?: throw AssertionError("$hook is not in the extension")
            assertTrue("$hook is public static", AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags))
            assertFalse("$hook takes a boolean", method.parameterTypes.any { it.toString() == "Z" })
            assertEquals("$hook is overloaded", 1, methods.count { it.name == method.name })
        }
        for (stub in listOf("flush(Ljava/lang/Object;)V", "likeSession(Ljava/lang/Object;)Ljava/lang/Object;", "storyMediaId(Ljava/lang/Object;)Ljava/lang/String;")) {
            val method = methods.singleOrNull { key(it) == stub } ?: throw AssertionError("$stub is not in the extension")
            assertTrue("$stub is static", AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }

    /**
     * The like store hands the like over right before it names a like's request, with this and the
     * story's id, and an unlike goes on as it was. The story reply send hands its account and story
     * over first. The viewer's flush by count hands its flusher over at its one return. The quick
     * reaction, the typed reply, the reply send's twin and the flusher stay as they were.
     */
    @Test
    fun theLikeTheReplyAndTheViewerAreHooked() {
        val classes = standIns()
        val context = PatchContexts.of(classes)

        context.holdBackStoryViews()

        fun stock(type: String, name: String) = classes.single { it.type == type }.methods.single { it.name == name }
        fun patched(type: String, name: String) = context.mutableClassDefBy(type).methods.single { it.name == name }
        assertLikeHooked("stand-in", stock(LIKE_STORE, "A0S"), patched(LIKE_STORE, "A0S"), likeId = 3)
        assertReplyHooked("stand-in", stock(REPLY_SENDER, "A15"), patched(REPLY_SENDER, "A15"))
        assertViewerHooked("stand-in", stock(FLUSH_BY_COUNT, "<init>"), patched(FLUSH_BY_COUNT, "<init>"))
        for ((type, name) in listOf(LIKE_BUTTON to "Fqq", REPLY_SENDER to "A16", REACTION to "A00", COMPOSER to "A06", FLUSHER to "A00", FLUSHER to "<init>")) {
            assertEquals("$type->$name was touched", 0, patched(type, name).answerCode().count { it.answerText() in ANSWER_HOOKS })
        }
        assertEquals("one call of each hook", ANSWER_HOOKS.associateWith { 1 }, hookCalls(context).groupingBy { it.substringAfter(' ') }.eachCount())
    }

    /** The stubs reach the viewer's flush, the like store's account and the story's id, each on one way out. */
    @Test
    fun theStubsReachTheFlushTheAccountAndTheStorysId() {
        val context = PatchContexts.of(standIns())
        context.holdBackStoryViews()

        assertStubsFilled(context, FLUSHER, "$FLUSHER->A00()V", "$LIKE_STORE->A0J()$USER_SESSION", "$MEDIA_TYPE->getId()Ljava/lang/String;")
    }

    @Test
    fun aBuildWithoutTheLikeBuilderFails() {
        assertRefused(standIns(like = false), "expected exactly one story like request builder in this Instagram build, found none")
        assertRefused(standIns(likeStatic = true), "is static, but the story like store's builder is an instance method")
    }

    /**
     * The like button's handler loads both like names too, as 457's does through a pool, and is left
     * out because it calls the builder. Two loaders where neither calls the other can't be told apart.
     */
    @Test
    fun theLikeButtonsHandlerIsntTakenForTheBuilder() {
        assertRefused(standIns(likeButtonCalls = false), "found $LIKE_STORE->A0S, $LIKE_BUTTON->Fqq")
    }

    @Test
    fun aLikeBuilderTheHookCantSitInFails() {
        assertRefused(standIns(likeLoads = 2), "to hold \"$STORY_LIKE_SEND\" once, found 2")
        assertRefused(standIns(likeBranchesToSend = true), "lands on its load of \"$STORY_LIKE_SEND\"")
        assertRefused(standIns(likeOverwritesThis = true), "writes over this (v2)")
        assertRefused(standIns(likeOverwritesId = true), "writes over parameter 3 (v6)")
        assertRefused(standIns(likeLocals = 14), "past v15")
    }

    @Test
    fun aBuildWithoutOneStoryReplySendFails() {
        assertRefused(standIns(reaction = false), "expected exactly one story quick reaction in this Instagram build, found none")
        assertRefused(standIns(reactionSenders = 2), "to one story reply send, found $REPLY_SENDER->A15(")
        assertRefused(standIns(typedCallsSender = false), "no method logging \"$STORY_TEXT_REPLY\" sends a typed story reply")
        assertRefused(standIns(senderBranchesToStart = true), "lands on its first instruction")
    }

    @Test
    fun aBuildWithoutTheViewersFlushFails() {
        assertRefused(standIns(flush = false), "expected exactly one story viewer's flush of its views in this Instagram build, found none")
        assertRefused(standIns(flushIsConstructor = false), "isn't a constructor taking the account and the viewer's flusher")
        assertRefused(standIns(flushReturns = 2), "to return once, found 2")
        assertRefused(standIns(flushOverwritesFlusher = true), "writes over parameter 1 (v3)")
        assertRefused(standIns(flusherSends = false), "to have one ()V handing the viewer's views to")
        assertRefused(standIns(flusherPublic = false), "isn't public for the extension to call")
    }

    @Test
    fun anExtensionWithoutTheClassFails() = assertRefused(standIns(extension = false), "$STORY_ANSWER_SEEN isn't in the extension")

    /** On each declared build the three hooks land once, where they belong, and the stubs are filled. */
    @Test
    fun eachDeclaredBuildHooksTheLikeTheReplyAndTheViewer() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksTheLikeTheReplyAndTheViewerToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = StorySeenHookTest().keptFixtureClasses(bundle)
        val byType = classes.associateBy { it.type }
        val found = PatchContexts.of(classes).let { it.findStoryAnswers(it.findStorySeen()) }
        assertFalse("$label: the like builder is static", AccessFlags.STATIC.isSet(found.like.accessFlags))
        assertEquals("$label: the story's id", "Ljava/lang/String;", found.like.parameterTypes[found.likeId].toString())
        assertTrue("$label: the like builder names an unlike too", FixtureDex.loads(found.like, STORY_UNLIKE_SEND, byType))
        val likeKey = "${found.like.definingClass}->${found.like.name}(${found.like.parameterTypes.joinToString("")})${found.like.returnType}"
        val handlers = classes.flatMap { it.methods }.filter { method ->
            val pool = AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/String;" &&
                method.parameterTypes.map(Any::toString) == listOf("I")
            !pool && "${method.definingClass}->${method.name}" != "${found.like.definingClass}->${found.like.name}" &&
                FixtureDex.loads(method, STORY_LIKE_SEND, byType) && FixtureDex.loads(method, STORY_UNLIKE_SEND, byType)
        }
        assertTrue(
            "$label: the like button's handler names both too and calls the builder, so it was left out",
            handlers.isNotEmpty() && handlers.all { method -> method.answerCode().any { it.answerText() == likeKey } },
        )
        assertEquals("$label: the reply send's start", STORY_REPLY_SHAPE, found.reply.parameterTypes.map(Any::toString).take(STORY_REPLY_SHAPE.size))
        assertTrue("$label: the reply send names a story", FixtureDex.loads(found.reply, "reel", byType))
        assertEquals("$label: the flush by count", listOf(USER_SESSION, found.flusher), found.viewer.parameterTypes.map(Any::toString))

        val context = PatchContexts.of(classes)
        context.holdBackStoryViews()
        fun stock(method: Method) = byType.getValue(method.definingClass).methods.single { it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) }
        fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single { it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) }
        assertLikeHooked(label, stock(found.like), patched(found.like), found.likeId)
        assertReplyHooked(label, stock(found.reply), patched(found.reply))
        assertViewerHooked(label, stock(found.viewer), patched(found.viewer))
        val calls = hookCalls(context)
        assertEquals(
            "$label: the hooks are in the like builder, the reply send and the flush by count, and nowhere else",
            setOf(
                "${found.like.definingClass}->${found.like.name} $STORY_LIKED",
                "${found.reply.definingClass}->${found.reply.name} $STORY_ANSWERED",
                "${found.viewer.definingClass}->${found.viewer.name} $STORY_VIEWER_OPENED",
            ),
            calls.toSet(),
        )
        assertEquals("$label: one call of each hook", 3, calls.size)
        assertStubsFilled(
            context, found.flusher, "${found.flusher}->${found.flush}()V",
            "${found.likeSession.definingClass}->${found.likeSession.name}()$USER_SESSION", found.mediaId,
        )
    }

    // ---- assertions ----------------------------------------------------------------------------

    /** The hook goes in right before the like's request name, handing over this and the story's id, and the rest is as it was. */
    private fun assertLikeHooked(what: String, stock: Method, patched: Method, likeId: Int) {
        val stockCode = stock.answerCode()
        val at = stockCode.indexOfFirst { it.answerString() == STORY_LIKE_SEND }
        val code = patched.answerCode()
        assertEquals("$what: the like hook", STORY_LIKED, code[at].answerText())
        val call = code[at] as FiveRegisterInstruction
        assertEquals("$what: two registers", 2, call.registerCount)
        assertEquals("$what: this and the story's id", listOf(patched.localRegisterCount(), patched.parameterRegisterNumber(likeId)), listOf(call.registerC, call.registerD))
        assertEquals("$what: the request's name comes next", STORY_LIKE_SEND, code[at + 1].answerString())
        assertEquals("$what: one call", 1, code.count { it.answerText() == STORY_LIKED })
        assertKept(what, stockCode, code, at)
        assertNoBranchPast(what, code, at)
    }

    /** The reply send starts with the hook, handing over the account and the story, then runs as it was. */
    private fun assertReplyHooked(what: String, stock: Method, patched: Method) {
        val code = patched.answerCode()
        assertEquals("$what: the reply hook", STORY_ANSWERED, code[0].answerText())
        assertEquals("$what: a range call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        val call = code[0] as RegisterRangeInstruction
        assertEquals("$what: the account and the story", patched.parameterRegisterNumber(0) to 2, call.startRegister to call.registerCount)
        assertEquals("$what: the story follows the account", patched.parameterRegisterNumber(1), call.startRegister + 1)
        assertEquals("$what: one call", 1, code.count { it.answerText() == STORY_ANSWERED })
        assertKept(what, stock.answerCode(), code, 0)
        assertNoBranchPast(what, code, 0)
    }

    /** The flush by count hands its flusher over right before its one return. */
    private fun assertViewerHooked(what: String, stock: Method, patched: Method) {
        val stockCode = stock.answerCode()
        val at = stockCode.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        val code = patched.answerCode()
        assertEquals("$what: the viewer hook", STORY_VIEWER_OPENED, code[at].answerText())
        val call = code[at] as FiveRegisterInstruction
        assertEquals("$what: one register", 1, call.registerCount)
        assertEquals("$what: the flusher", patched.parameterRegisterNumber(1), call.registerC)
        assertEquals("$what: the return comes next", Opcode.RETURN_VOID, code[at + 1].opcode)
        assertEquals("$what: one call", 1, code.count { it.answerText() == STORY_VIEWER_OPENED })
        assertKept(what, stockCode, code, at)
        assertNoBranchPast(what, code, at)
    }

    /** [code] is [stock] with one instruction put in at [at], read without the nops a payload's alignment moves. */
    private fun assertKept(what: String, stock: List<Instruction>, code: List<Instruction>, at: Int) {
        fun Instruction.kind() = when (opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> opcode
        }
        val kept = code.filterIndexed { index, _ -> index != at }.filter { it.opcode != Opcode.NOP }.map { it.kind() to it.answerText() }
        assertEquals("$what: the rest is as it was", stock.filter { it.opcode != Opcode.NOP }.map { it.kind() to it.answerText() }, kept)
    }

    /** No branch lands right past the hook at [at], so every way to the instruction it went in front of runs it. */
    private fun assertNoBranchPast(what: String, code: List<Instruction>, at: Int) {
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for ((index, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction || instruction.opcode in SWITCHES) continue
            assertTrue("$what: the branch at $index skips the hook", addresses[index] + instruction.codeOffset != addresses[at + 1])
        }
    }

    private fun assertStubsFilled(context: BytecodePatchContext, flusher: String, flush: String, likeSession: String, mediaId: String) {
        for ((stub, wanted) in listOf(
            "flush" to listOf(flusher, flush),
            "likeSession" to listOf(likeSession.substringBefore("->"), likeSession),
            "storyMediaId" to listOf(MEDIA_TYPE, mediaId),
        )) {
            val code = context.mutableClassDefBy(STORY_ANSWER_SEEN).methods.single { it.name == stub }.answerCode()
            val reads = code.mapNotNull { it.answerText() }
            assertTrue("$stub reads $reads", reads.containsAll(wanted))
            val out = code.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT || it.opcode == Opcode.RETURN_VOID }
            assertTrue("$stub: no way out", out > 0)
            assertTrue("$stub: a branch before its return", code.take(out).none { it is OffsetInstruction })
        }
    }

    /** The patch refuses [classes] saying [why], and nothing calls a hook of either side or has a filled stub. */
    private fun assertRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.holdBackStoryViews() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$PATCH: ") && why in refusal.message!!)
        context.classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                assertEquals("${classDef.type}->${method.name} calls a hook", 0, method.answerCode().count { it.answerText() in ALL_HOOKS })
            }
        }
        fun Method.key() = "$name(${parameterTypes.joinToString("")})$returnType"
        for (type in listOf(STORY_SEEN, STORY_SEEN_BUTTON, STORY_ANSWER_SEEN)) {
            val stock = ExtensionDex.classDef(type).methods.associate { it.key() to it.implementation?.instructions?.count() }
            context.classDefByOrNull(type)?.methods?.forEach {
                assertEquals("$type ${it.key()} was filled", stock[it.key()], it.implementation?.instructions?.count())
            }
        }
    }

    private fun hookCalls(context: BytecodePatchContext): List<String> {
        val calls = mutableListOf<String>()
        context.classDefForEach { classDef ->
            for (method in classDef.methods) {
                method.answerCode().filter { it.answerText() in ANSWER_HOOKS }.forEach { calls += "${classDef.type}->${method.name} ${it.answerText()}" }
            }
        }
        return calls
    }

    private fun standIns(
        like: Boolean = true,
        likeLoads: Int = 1,
        likeBranchesToSend: Boolean = false,
        likeOverwritesThis: Boolean = false,
        likeOverwritesId: Boolean = false,
        likeLocals: Int = 2,
        likeStatic: Boolean = false,
        likeButtonCalls: Boolean = true,
        reaction: Boolean = true,
        reactionSenders: Int = 1,
        typedCallsSender: Boolean = true,
        senderBranchesToStart: Boolean = false,
        flush: Boolean = true,
        flushIsConstructor: Boolean = true,
        flushReturns: Int = 1,
        flushOverwritesFlusher: Boolean = false,
        flusherSends: Boolean = true,
        flusherPublic: Boolean = true,
        extension: Boolean = true,
    ): List<ClassDef> = StorySeenHookTest().standIns(answers = false) + StoryAnswerFixtures.standIns(
        STORE, BATCH, "A0O",
        like = like, likeLoads = likeLoads, likeBranchesToSend = likeBranchesToSend, likeOverwritesThis = likeOverwritesThis,
        likeOverwritesId = likeOverwritesId, likeLocals = likeLocals, likeStatic = likeStatic,
        likeButtonCalls = likeButtonCalls, reaction = reaction,
        reactionSenders = reactionSenders, typedCallsSender = typedCallsSender, senderBranchesToStart = senderBranchesToStart,
        flush = flush, flushIsConstructor = flushIsConstructor, flushReturns = flushReturns,
        flushOverwritesFlusher = flushOverwritesFlusher, flusherSends = flusherSends, flusherPublic = flusherPublic,
        extension = extension,
    )

    private fun Method.answerCode(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.answerText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.answerString(): String? =
        if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) ((this as ReferenceInstruction).reference as? StringReference)?.string else null

    private companion object {
        const val STORE = "Lfixture/PendingReelSeenStateStore;"
        const val BATCH = "Lfixture/PendingReelSeenState;"
        val SWITCHES = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)
        val ANSWER_HOOKS = setOf(STORY_LIKED, STORY_ANSWERED, STORY_VIEWER_OPENED)
        val ALL_HOOKS = ANSWER_HOOKS + setOf(TO_SEND, TO_RETRY, BIND_BUTTON)
    }
}
