/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyFeedEndTest {
    private val adapter = "Lfixture/MainFeedAdapter;"
    private val feed = "Lfixture/Feed;"
    private val ended = "$feed->ended:Z"

    private val following = "Lfixture/FeedState;"

    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_ENDED.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(FEED_ENDED, HOME_FEED_READ, END_CARD_RULE, MORE_AFTER_FOLLOWING, "$FEED_SUGGESTIONS->$FEED_EMPTY_STUB(Ljava/lang/Object;)I")) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The row's show check asks the extension whether its feed pages as Following's right after
     * comparing the source, and about a next page right after asking the question isLoading()
     * shares with it. Nothing else it asks goes past the extension.
     */
    @Test
    fun theEndCardRulesQuestionsGoPastTheExtension() {
        val context = PatchContexts.of(listOf(followingState(), feedNames()))

        context.endFollowingAtItsCard()

        val shows = context.classDefBy(following).methods.single { it.name == "shows" }.implementation!!.instructions.toList()
        for ((question, hook) in listOf("Ljava/lang/String;->equals(Ljava/lang/Object;)Z" to END_CARD_RULE,
            "$following->hasMore()Z" to MORE_AFTER_FOLLOWING)) {
            val asked = shows.indexOfFirst { it.calls(question) }
            val answer = (shows[asked + 1] as OneRegisterInstruction).registerA
            val call = shows[asked + 2]
            assertTrue("$hook right after the answer", call.calls(hook))
            assertEquals("the answer", answer, (call as RegisterRangeInstruction).startRegister)
            assertEquals(Opcode.MOVE_RESULT, shows[asked + 3].opcode)
            assertEquals(answer, (shows[asked + 3] as OneRegisterInstruction).registerA)
            assertEquals("$hook once", 1, shows.count { it.calls(hook) })
        }
        val loading = context.classDefBy(following).methods.single { it.name == "isLoading" }.implementation!!.instructions
        assertTrue("isLoading is left", loading.none { it.calls(MORE_AFTER_FOLLOWING) })
    }

    @Test
    fun anIsLoadingSharingNoQuestionFailsThePatch() {
        val context = PatchContexts.of(listOf(followingState(shared = false), feedNames()))
        assertThrows(PatchException::class.java) { context.endFollowingAtItsCard() }
    }

    /**
     * The flag is the last boolean read before the loading row, not an earlier one, and every read
     * of it in the adapter goes past the extension, the one in its empty check too.
     */
    @Test
    fun everyReadOfTheFlagGoesPastTheExtension() {
        val context = PatchContexts.of(listOf(adapter()))

        val end = context.findFeedEnd()
        assertEquals(adapter, end.adapter)
        assertEquals(ended, end.flag.toString())
        assertEquals("$feed->isEmpty()Z", end.empty.toString())
        context.endEmptiedFeed(end)

        for (name in listOf("buildModels", "isEmptyFeed", "branchesToTheRead")) {
            val code = context.code(name)
            val read = code.indexOfFirst { it.reads(ended) }
            val hook = code[read + 1]
            assertTrue("$name: right after the read", hook.calls(FEED_ENDED))
            val flag = (code[read] as TwoRegisterInstruction).registerA
            assertEquals("$name: the flag", flag, (hook as RegisterRangeInstruction).startRegister)
            assertEquals(Opcode.MOVE_RESULT, code[read + 2].opcode)
            assertEquals(flag, (code[read + 2] as OneRegisterInstruction).registerA)
            assertEquals("$name: hooked once", 1, code.count { it.calls(FEED_ENDED) })
            val handed = code[read - 1]
            assertTrue("$name: the feed handed over right before the read", handed.calls(HOME_FEED_READ))
            assertEquals("$name: the feed", (code[read] as TwoRegisterInstruction).registerB, (handed as RegisterRangeInstruction).startRegister)
            assertEquals("$name: handed over once", 1, code.count { it.calls(HOME_FEED_READ) })
        }
        val other = context.code("buildModels").indexOfFirst { it.reads("$feed->other:Z") }
        assertTrue("the other flag is left", !context.code("buildModels")[other + 1].calls(FEED_ENDED))
        assertTrue("the other flag is left", !context.code("buildModels")[other - 1].calls(HOME_FEED_READ))

        // A branch to the read hands the feed over too.
        val branching = context.code("branchesToTheRead")
        val jump = branching.indexOfFirst { it.opcode == Opcode.IF_NEZ }
        assertTrue("the branch lands on the hand-over", branching[target(branching, jump)].calls(HOME_FEED_READ))
    }

    /**
     * The extension's stub asks the feed object the question the adapter asks right after the flag,
     * so feedEnded knows whether the feed is empty. A question the extension can't reach leaves the
     * stub as it was.
     */
    @Test
    fun theStubAsksTheFeedWhetherItsEmpty() {
        val context = PatchContexts.of(listOf(adapter(), feedClass(), ExtensionDex.classDef(FEED_SUGGESTIONS)))
        context.endEmptiedFeed(context.findFeedEnd())

        val stub = context.classDefBy(FEED_SUGGESTIONS).methods.single { it.name == FEED_EMPTY_STUB }.implementation!!.instructions.toList()
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.RETURN), stub.take(4).map { it.opcode })
        assertEquals(feed, ((stub[0] as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.TypeReference).type)
        assertTrue(stub[1].calls("$feed->isEmpty()Z"))
        val p0 = (stub[0] as OneRegisterInstruction).registerA
        assertEquals("the body uses p0 alone", setOf(p0), listOf(stub[2], stub[3]).map { (it as OneRegisterInstruction).registerA }.toSet())

        for (hidden in listOf(feedClass(publicClass = false), feedClass(publicCheck = false))) {
            val closed = PatchContexts.of(listOf(adapter(), hidden, ExtensionDex.classDef(FEED_SUGGESTIONS)))
            val before = closed.classDefBy(FEED_SUGGESTIONS).methods.single { it.name == FEED_EMPTY_STUB }.implementation!!.instructions.toList()
            val end = closed.findFeedEnd()
            closed.endEmptiedFeed(end)
            assertFalse(closed.tellFeedEmptiness(end))
            val after = closed.classDefBy(FEED_SUGGESTIONS).methods.single { it.name == FEED_EMPTY_STUB }.implementation!!.instructions.toList()
            assertEquals("the stub is left as it was", before.map { it.opcode }, after.map { it.opcode })
        }
    }

    @Test
    fun aBuilderThatDoesntAskWhetherTheFeedIsEmptyFailsThePatch() {
        val context = PatchContexts.of(listOf(adapter(asks = false)))
        assertThrows(PatchException::class.java) { context.findFeedEnd() }
    }

    @Test
    fun twoBuildersFailThePatch() {
        val context = PatchContexts.of(listOf(adapter(), adapter("Lfixture/SecondAdapter;")))
        assertThrows(PatchException::class.java) { context.findFeedEnd() }
    }

    /**
     * In each build of the declared version, the declared bundle and the other builds of it, the home
     * feed adapter reads its flag twice, in its model builder and its empty check, and both reads go
     * past the extension with the feed handed over first. The feed's empty check is public, so the
     * extension's stub can ask it. On 450's 385611438 that's LX/01ls reading LX/01mK;->A02:Z in A1A
     * and A1J, and asking LX/048B;->A0H()Z.
     */
    @Test
    fun eachBuildEndsTheEmptiedFeed() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        val others = Fixtures.otherBuilds()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        assertTrue("other builds of the declared version were not read", others.isNotEmpty())
        var checked = 0
        for (bundle in bundles + others) {
            val where = "${bundle.parentFile.name}/${bundle.name}"
            val classes = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { it.holds(BUILD_MODELS) || it.holds(FOLLOWING_FEED) }) classes += ImmutableClassDef.of(classDef)
                }
            }
            val found = PatchContexts.of(classes.distinctBy { it.type }).findFeedEnd()
            // The feed's class and its supers, for the stub's empty check.
            val feedTypes = generateSequence(found.empty.definingClass) { type ->
                FixtureDex.classes(bundle, setOf(type))[type]?.superclass?.takeIf { it.startsWith("LX/") }
            }.toSet()
            val feedClasses = FixtureDex.classes(bundle, feedTypes).values.map { ImmutableClassDef.of(it) }
            val all = (classes + feedClasses + ExtensionDex.classDef(FEED_SUGGESTIONS)).distinctBy { it.type }
            val context = PatchContexts.of(all)

            val end = context.findFeedEnd()
            context.endEmptiedFeed(end)

            val reads = context.classDefBy(end.adapter).methods.flatMap { method ->
                val code = method.implementation?.instructions?.toList().orEmpty()
                code.indices.filter { code[it].reads(end.flag.toString()) }.map { code to it }
            }
            assertEquals("$where: ${end.adapter} reads ${end.flag}", 2, reads.size)
            for ((code, at) in reads) {
                assertTrue("$where: right after the read", code[at + 1].calls(FEED_ENDED))
                assertEquals(Opcode.MOVE_RESULT, code[at + 2].opcode)
                assertTrue("$where: the feed handed over right before the read", code[at - 1].calls(HOME_FEED_READ))
                assertEquals("$where: the feed", (code[at] as TwoRegisterInstruction).registerB, (code[at - 1] as RegisterRangeInstruction).startRegister)
            }
            val stub = context.classDefBy(FEED_SUGGESTIONS).methods.single { it.name == FEED_EMPTY_STUB }.implementation!!.instructions.toList()
            assertTrue("$where: the stub asks ${end.empty}", stub[1].calls(end.empty.toString()))

            context.endFollowingAtItsCard()
            for (hook in listOf(END_CARD_RULE, MORE_AFTER_FOLLOWING)) {
                val asked = classes.map { it.type }.distinct().flatMap { type ->
                    context.classDefBy(type).methods.flatMap { method ->
                        val code = method.implementation?.instructions?.toList().orEmpty()
                        code.indices.filter { code[it].calls(hook) }.map { Triple(method, code, it) }
                    }
                }
                assertEquals("$where: $hook asked once", 1, asked.size)
                val (shows, code, at) = asked.single()
                assertTrue("$where: in the show check", shows.holds(FOLLOWING_FEED))
                assertEquals(Opcode.MOVE_RESULT, code[at - 1].opcode)
                val question = (code[at - 2] as ReferenceInstruction).reference as MethodReference
                val expected = if (hook == END_CARD_RULE) "Ljava/lang/String;" else shows.definingClass
                assertEquals("$where: what $hook answers", expected, question.definingClass)
                assertEquals(Opcode.MOVE_RESULT, code[at + 1].opcode)
            }
            checked++
        }
        assertEquals("every build was checked", bundles.size + others.size, checked)
    }

    /** The index of the instruction the branch at [at] lands on. */
    private fun target(code: List<Instruction>, at: Int): Int {
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        return address.indexOf(address[at] + (code[at] as OffsetInstruction).codeOffset)
    }

    private fun Instruction.calls(reference: String) =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

    private fun Instruction.reads(reference: String) =
        ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == reference

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    private fun BytecodePatchContext.code(name: String): List<Instruction> =
        classDefBy(adapter).methods.single { it.name == name }.implementation!!.instructions.toList()

    /**
     * The builder reads another flag first, then the one that ends the feed, asks the feed whether
     * it's empty, and asks again before adding the loading row. Its empty check reads the flag too.
     */
    private fun adapter(type: String = adapter, asks: Boolean = true) = classDef(type, listOf(
        method(type, "buildModels", "V", registers = 4, body = """
            const-string v0, "$BUILD_MODELS"
            iget-object v1, v3, $type->feed:$feed
            iget-boolean v2, v1, $feed->other:Z
            iget-boolean v0, v1, $ended
            if-eqz v0, :loading
            ${if (asks) "invoke-virtual { v1 }, $feed->isEmpty()Z\nmove-result v0" else "const/4 v0, 0x1"}
            if-eqz v0, :loading
            return-void
            :loading
            invoke-virtual { v1 }, $feed->isEmpty()Z
            move-result v0
            if-eqz v0, :done
            const-string v0, "$SHIMMER_KEY"
            :done
            return-void
        """),
        method(type, "isEmptyFeed", "Z", registers = 3, body = """
            iget-object v1, v2, $type->feed:$feed
            iget-boolean v0, v1, $ended
            return v0
        """),
        method(type, "branchesToTheRead", "Z", registers = 3, body = """
            iget-object v1, v2, $type->feed:$feed
            if-nez v1, :read
            const/4 v0, 0x0
            return v0
            :read
            iget-boolean v1, v1, $ended
            return v1
        """),
    ))

    /** The feed itself, with its empty check, public unless a test says otherwise. */
    private fun feedClass(publicClass: Boolean = true, publicCheck: Boolean = true): ClassDef {
        val check = method(feed, "isEmpty", "Z", registers = 2, body = "const/4 v0, 0x1\nreturn v0")
        val flags = if (publicCheck) check.accessFlags else AccessFlags.PRIVATE.value
        val declared = ImmutableMethod(feed, check.name, emptyList<ImmutableMethodParameter>(), "Z", flags, null, null, check.implementation)
        return ImmutableClassDef(feed, if (publicClass) AccessFlags.PUBLIC.value else 0, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(declared))
    }

    /**
     * The load more row's state: its show check holds the Following feed's name and asks whether
     * there's a next page and whether paging failed, and isLoading() asks whether it's busy and
     * (unless [shared] is false) whether there's a next page.
     */
    private fun followingState(shared: Boolean = true) = classDef(following, listOf(
        method(following, "shows", "Z", registers = 2, body = """
            const-string v0, "$FOLLOWING_FEED"
            invoke-virtual { v0, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :done
            invoke-virtual { v1 }, $following->hasMore()Z
            move-result v0
            if-nez v0, :done
            invoke-virtual { v1 }, $following->failed()Z
            move-result v0
            :done
            return v0
        """),
        method(following, "isLoading", "Z", registers = 2, body = """
            invoke-virtual { v1 }, $following->busy()Z
            move-result v0
            ${if (shared) "invoke-virtual { v1 }, $following->hasMore()Z\nmove-result v0" else ""}
            return v0
        """),
        method(following, "hasMore", "Z", registers = 2, body = "const/4 v0, 0x1\nreturn v0"),
        method(following, "failed", "Z", registers = 2, body = "const/4 v0, 0x0\nreturn v0"),
        method(following, "busy", "Z", registers = 2, body = "const/4 v0, 0x0\nreturn v0"),
    ))

    /** A static table of feed names holding the Following feed's too, which isn't the show check. */
    private fun feedNames() = classDef("Lfixture/FeedNames;", listOf(
        method("Lfixture/FeedNames;", "name", "Ljava/lang/String;", registers = 1, static = true, body = """
            const-string v0, "$FOLLOWING_FEED"
            return-object v0
        """),
    ))

    private fun method(type: String, name: String, returnType: String, registers: Int, body: String, static: Boolean = false): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                type, name, emptyList<ImmutableMethodParameter>(), returnType,
                AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)
}
