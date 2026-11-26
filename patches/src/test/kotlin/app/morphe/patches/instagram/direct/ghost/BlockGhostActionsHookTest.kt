/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.ghost

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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Block likes and replies in Ghost mode asks GhostActions first where each like and story reply
 * starts, returning before Instagram does anything when it says so, and in the classic story
 * viewer's heart case jumps to the case's end.
 */
class BlockGhostActionsHookTest {
    @Test
    fun eachDeclaredBuildAsksFirstWhereALikeOrReplyStarts() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildAsksFirstToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    @Test
    fun theStandInsGetEachHookWhereItGoes() {
        val context = PatchContexts.of(world())
        val found = context.findGhostActions()
        blockGhostActions(found)

        assertEquals("$STEP->A02", found.postLikeStep.where())
        assertEquals("$STORY->A0T", found.storyLike.where())
        assertEquals("$STORY_STEP->A0P", found.storyLikeStep.where())
        assertEquals(listOf("$COMPOSER->A06"), found.textReplies.map { it.where() })
        assertEquals("$REACTION->A00", found.quickReaction.where())
        assertEquals(listOf(GHOST_LIKE, GHOST_LIKE, GHOST_LIKE, GHOST_REPLY, GHOST_REPLY), found.atStart.map { it.second })
        for ((method, hook) in found.atStart) {
            val first = if (method.definingClass == STORY_STEP) Opcode.CONST_4 else Opcode.CONST_STRING
            startsWith(method, hook, first, method.where())
        }

        val heart = found.storyHeart
        assertEquals("$VIEWER->onClick", heart.method.where())
        assertEquals("right after the trace starts", 3, heart.at)
        assertEquals("the case's end", 6, heart.exit)
        assertEquals(1, heart.trace)
        assertEquals("the lowest local nothing reads before the end writes it", 0, heart.scratch)
        val code = heart.method.implementation!!.instructions.toList()
        assertEquals(listOf(Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT), code.take(3).map { it.opcode })
        assertTrue(code[3].calls(GHOST_LIKE))
        assertEquals(Opcode.MOVE_RESULT, code[4].opcode)
        assertEquals(0, (code[4] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, code[5].opcode)
        assertEquals(0, (code[5] as OneRegisterInstruction).registerA)
        assertEquals("a yes jumps to the end's constant", 9, (code[5] as BuilderOffsetInstruction).target.location.index)
        assertEquals(listOf(Opcode.CONST_STRING, Opcode.CONST_STRING, Opcode.INVOKE_INTERFACE, Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID),
            code.drop(6).map { it.opcode })
        assertEquals(1, code.count { it.calls(GHOST_LIKE) })
    }

    /** 386314341's merged 0Nvz.invoke loads the typed reply's string and sends one too: it stays as it was. */
    @Test
    fun aMergedLambdaSendingATypedReplyIsLeftAlone() {
        val classes = world(extra = listOf(mergedReply()))
        val context = PatchContexts.of(classes)
        val before = context.code(MERGED)
        val found = context.findGhostActions()
        blockGhostActions(found)

        assertEquals(listOf("$COMPOSER->A06"), found.textReplies.map { it.where() })
        assertEquals(before, context.code(MERGED))
    }

    @Test
    fun aPlaceThatIsntTheOneFailsTheWholePatchUnchanged() {
        refuses("no extension", "isn't in the extension", world().filter { it.type != GHOST_ACTIONS })
        refuses("a static like step", "post like's shared step",
            world(step = likeStep(flags = STATIC, registers = 3)))
        refuses("a jump onto the like step's first instruction", "lands on its first instruction",
            world(step = likeStep(body = ":top\nconst-string v0, \"$POST_DOUBLE_TAP_HINT\"\nif-eqz v2, :top\nreturn-void")))
        refuses("a like step with no local", "no local", world(step = likeStep(registers = 3)))
        refuses("a newer heart handing on to two steps", "found 2",
            world(story = storyLike(steps = listOf(STORY_STEP, OTHER_STEP)), extra = listOf(storyStep(OTHER_STEP))))
        refuses("a step that isn't in the build", "isn't in this build", world().filter { it.type != STORY_STEP })
        refuses("a heart case reaching the like around the hook", "without passing the hook", world(viewer = viewer(around = true)))
        refuses("a heart case with no trace end", "no trace end", world(viewer = viewer(end = false)))
        refuses("a heart case with no trace start", "starts no trace", world(viewer = viewer(start = false)))
        refuses("a heart case whose only local besides the trace is read after the hook", "local register", world(viewer = busyViewer()))
        refuses("a typed reply sending some other way", "sends a typed story reply", world(composer = composer(send = OTHER_SEND)))
    }

    private fun refuses(case: String, why: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to context.code(it.type) }
        val failure = assertThrows(case, PatchException::class.java) { blockGhostActions(context.findGhostActions()) }
        assertTrue("$case: ${failure.message}", failure.message!!.contains(why))
        for ((type, code) in before) assertEquals("$case: $type unchanged", code, context.code(type))
    }

    /**
     * Every class loading one of the strings the patch finds its places by, the pools they ask, and
     * the newer viewer's like step's class: each place takes its hook once, first thing or right
     * after the classic heart's trace starts, and Instagram's own code follows.
     */
    private fun builds(bundle: File, label: String) {
        val strings = setOf(POST_DOUBLE_TAP_HINT, STORY_LIKE_FLOW, STORY_LIKE_TAPPED, STORY_UNLIKE_TAPPED,
            GHOST_QUICK_REACTION, GHOST_TEXT_REPLY)
        val loading = FixtureDex.classesLoadingAny(bundle, strings)
        val steps = loading.flatMap { it.methods }.flatMap { it.calls() }.filter { call ->
            call.returnType == "V" && call.parameterTypes.map(Any::toString).let { it.size == 6 && it[1] == MEDIA && it[4] == INTEGER && it[5] == "I" }
        }.map { it.definingClass }.toSet()
        val taken = FixtureDex.classes(bundle, steps).values
        val classes = (loading + taken).distinctBy { it.type } + ExtensionDex.classDef(GHOST_ACTIONS)
        val stock = classes.associateBy { it.type }
        val context = PatchContexts.of(classes)
        val found = context.findGhostActions()
        val firsts = found.atStart.map { (method, _) -> stock.getValue(method.definingClass).body(method).first().opcode }
        val heartStock = stock.getValue(found.storyHeart.method.definingClass).body(found.storyHeart.method)
        blockGhostActions(found)

        for ((index, entry) in found.atStart.withIndex()) startsWith(entry.first, entry.second, firsts[index], "$label ${entry.first.where()}")
        val heart = found.storyHeart
        val code = heart.method.implementation!!.instructions.toList()
        assertEquals("$label: the case runs as it did up to the hook", heartStock.take(heart.at).map { it.opcode }, code.take(heart.at).map { it.opcode })
        assertTrue("$label: the heart asks", code[heart.at].calls(GHOST_LIKE))
        assertEquals(label, Opcode.MOVE_RESULT, code[heart.at + 1].opcode)
        assertEquals(label, heart.scratch, (code[heart.at + 1] as OneRegisterInstruction).registerA)
        assertEquals(label, Opcode.IF_NEZ, code[heart.at + 2].opcode)
        assertEquals(label, heart.scratch, (code[heart.at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$label: a yes jumps to the case's end", heart.exit + 3, (code[heart.at + 2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: the end is the trace's constant", heartStock[heart.exit].opcode, code[heart.exit + 3].opcode)
        assertNotEquals(label, heart.trace, heart.scratch)
        assertTrue("$label: the scratch is a nibble's", heart.scratch <= 15)
        assertEquals(label, heartStock.drop(heart.at).map { it.opcode.kind() }, code.drop(heart.at + 3).map { it.opcode.kind() })
        assertEquals(label, 1, code.count { it.calls(GHOST_LIKE) })

        println("$label: likes at ${listOf(found.postLikeStep, found.storyLike, found.storyLikeStep).map { it.where() }}, " +
            "heart case ${heart.method.where()} at ${heart.at} (exit ${heart.exit}, trace v${heart.trace}, scratch v${heart.scratch}), " +
            "replies at ${(found.textReplies + found.quickReaction).map { it.where() }}")
    }

    /** [method] starts with [hook], its answer in v0, a no going on to Instagram's own [first] and a yes returning. */
    private fun startsWith(method: MutableMethod, hook: String, first: Opcode, label: String) {
        val code = method.implementation!!.instructions.toList()
        assertTrue("$label asks first", code[0].calls(hook))
        assertEquals(label, listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.subList(1, 4).map { it.opcode })
        assertEquals(label, 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(label, 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$label: a no goes on to Instagram's code", 4, (code[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: Instagram's own code follows", first, code[4].opcode)
        assertEquals(label, 1, code.count { it.calls(GHOST_LIKE) || it.calls(GHOST_REPLY) })
    }

    private fun BytecodePatchContext.code(type: String): List<List<Opcode>>? = classDefByOrNull(type)?.let {
        mutableClassDefBy(type).methods.map { method -> method.implementation?.instructions?.map { it.opcode } ?: emptyList() }
    }

    private fun ClassDef.body(method: Method): List<Instruction> = methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }.implementation!!.instructions.toList()

    /** A goto an insert widens reads as the goto it was. */
    private fun Opcode.kind(): Opcode = if (this == Opcode.GOTO_16 || this == Opcode.GOTO_32) Opcode.GOTO else this

    private fun Instruction.calls(reference: String): Boolean {
        val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return "${called.definingClass}->${called.name}(${called.parameterTypes.joinToString("")})${called.returnType}" == reference
    }

    private fun Method.calls(): List<MethodReference> =
        implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference } ?: emptyList()

    private fun Method.where() = "$definingClass->$name"

    private companion object {
        const val MEDIA = "Lcom/instagram/feed/media/Media;"
        const val INTEGER = "Ljava/lang/Integer;"
        const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val TARGET = "Lcom/instagram/model/direct/DirectShareTarget;"
        const val STEP = "Lfixture/LikeStep;"
        const val STORY = "Lfixture/StoryLike;"
        const val STORY_STEP = "Lfixture/StoryStep;"
        const val OTHER_STEP = "Lfixture/OtherStep;"
        const val VIEWER = "Lfixture/Viewer;"
        const val REACTION = "Lfixture/Reaction;"
        const val COMPOSER = "Lfixture/Composer;"
        const val MERGED = "Lfixture/Merged;"
        const val TRACE = "Lfixture/Trace;"
        const val LIKER = "Lfixture/Liker;->like(Ljava/lang/Object;${REEL_ITEM}Ljava/lang/Object;)V"
        const val SEND = "Lfixture/Sender;->A15($SESSION$MEDIA$TARGET)V"
        const val OTHER_SEND = "Lfixture/Sender;->A16($SESSION$MEDIA$TARGET)V"
        val PUBLIC = AccessFlags.PUBLIC.value
        val STATIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val STEP_PARAMETERS = listOf("Lfixture/Owner;", MEDIA, "Lfixture/Action;", "Ljava/lang/Object;", INTEGER, "I")

        /** The stand-in places, each as 386314341 shapes it, and the extension. */
        fun world(
            step: ClassDef = likeStep(),
            story: ClassDef = storyLike(),
            viewer: ClassDef = viewer(),
            composer: ClassDef = composer(),
            extra: List<ClassDef> = emptyList(),
        ): List<ClassDef> =
            listOf(step, story, storyStep(STORY_STEP), viewer, reaction(), composer) + extra + ExtensionDex.classDef(GHOST_ACTIONS)

        /** The shared like step: an instance `(Activity, Media)V` counting the double tap hint, one local. */
        fun likeStep(registers: Int = 4, flags: Int = PUBLIC, body: String = "const-string v0, \"$POST_DOUBLE_TAP_HINT\"\nreturn-void"): ClassDef =
            classOf(STEP, listOf(method(STEP, "A02", listOf("Landroid/app/Activity;", MEDIA), "V", registers, flags, body)))

        /** The newer viewer's heart: an instance `()V` loading both its strings and handing the like to [steps]. */
        fun storyLike(steps: List<String> = listOf(STORY_STEP)): ClassDef = classOf(STORY, listOf(
            method(STORY, "A0T", emptyList(), "V", 8, PUBLIC,
                "const-string v0, \"$STORY_LIKE_FLOW\"\nconst-string v0, \"$STORY_UNLIKE_TAPPED\"\n" +
                    steps.joinToString("") { "invoke-virtual/range { v0 .. v6 }, $it->A0P(${STEP_PARAMETERS.joinToString("")})V\n" } +
                    "return-void"),
        ))

        fun storyStep(type: String): ClassDef =
            classOf(type, listOf(method(type, "A0P", STEP_PARAMETERS, "V", 8, PUBLIC, "const/4 v0, 0x0\nreturn-void")))

        /**
         * The classic viewer's merged click listener, its like case alone: it starts a trace in v1,
         * loads both strings, hands the like on and ends the trace before it returns. [around] adds
         * a way to the hand-off past the hook, and [end] and [start] leave the trace's end or start out.
         */
        fun viewer(around: Boolean = false, end: Boolean = true, start: Boolean = true): ClassDef {
            val body = buildString {
                if (around) append("if-eqz v7, :hand\n")
                if (start) append("const/4 v0, 0x1\ninvoke-static { v0 }, $TRACE->A00(I)I\nmove-result v1\n") else append("const/4 v1, 0x1\n")
                append("const-string v2, \"$STORY_LIKE_FLOW\"\n")
                append("const-string v2, \"$STORY_LIKE_TAPPED\"\n")
                if (around) append(":hand\n")
                append("invoke-interface { v2, v3, v4, v5 }, $LIKER\n")
                if (end) append("const/4 v0, 0x2\ninvoke-static { v0, v1 }, $TRACE->A01(II)V\n")
                append("return-void")
            }
            return listener(8, body)
        }

        /** The like case with two locals, the trace's and one the case reads after the hand-off, its strings in the View's register. */
        fun busyViewer(): ClassDef = listener(4, """
            const/4 v0, 0x1
            invoke-static { v0 }, $TRACE->A00(I)I
            move-result v1
            const-string v3, "$STORY_LIKE_FLOW"
            const-string v3, "$STORY_LIKE_TAPPED"
            invoke-interface { v3, v2, v2, v2 }, $LIKER
            move-object v3, v0
            const/4 v0, 0x2
            invoke-static { v0, v1 }, $TRACE->A01(II)V
            return-void
        """)

        fun listener(registers: Int, body: String): ClassDef = ImmutableClassDef(VIEWER, PUBLIC, "Ljava/lang/Object;",
            listOf("Landroid/view/View\$OnClickListener;"), null, null, null,
            listOf(method(VIEWER, "onClick", listOf("Landroid/view/View;"), "V", registers, PUBLIC, body)))

        fun reaction(): ClassDef = classOf(REACTION, listOf(
            method(REACTION, "A00", emptyList(), "V", 3, STATIC, "const-string v0, \"$GHOST_QUICK_REACTION\"\ninvoke-static { v0, v1, v2 }, $SEND\nreturn-void"),
        ))

        fun composer(send: String = SEND): ClassDef = classOf(COMPOSER, listOf(
            method(COMPOSER, "A06", emptyList(), "V", 3, STATIC, "const-string v0, \"$GHOST_TEXT_REPLY\"\ninvoke-static { v0, v1, v2 }, $send\nreturn-void"),
        ))

        /** A Redex-merged lambda: it reads its `$t` to pick a body, one of which sends a typed reply, and returns Object. */
        fun mergedReply(): ClassDef = classOf(MERGED, listOf(
            method(MERGED, "invoke", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;", 6, PUBLIC,
                "iget v0, v3, $MERGED->\$t:I\nconst-string v1, \"$GHOST_TEXT_REPLY\"\ninvoke-static { v1, v1, v1 }, $SEND\nconst/4 v0, 0x0\nreturn-object v0"),
        ))

        fun classOf(type: String, methods: List<Method>): ClassDef =
            ImmutableClassDef(type, PUBLIC, "Ljava/lang/Object;", null, null, null, null, methods)

        fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method =
            MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
                addInstructionsWithLabels(0, body.trimIndent())
            }.let(ImmutableMethod::of)
    }
}
