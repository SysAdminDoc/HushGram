/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.home

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.CLIPS_NETEGO
import app.morphe.patches.instagram.feed.FeedItemStandIns
import app.morphe.patches.instagram.feed.FeedItemStandIns.assertFilteredBeforeReturn
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.feed.reels.FILTER
import app.morphe.patches.instagram.feed.reels.filterParsedFeedItems
import app.morphe.patches.instagram.feed.suggested.HOME_TYPES_FILTER
import app.morphe.patches.instagram.feed.suggested.emptiedFeedEndPatch
import app.morphe.patches.instagram.feed.suggested.hideSuggestedPostsPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide the home feed: every item Home reads, from its feed response and from its store of the last
 * run, goes through HomeFeed whatever its kind, while the feed item helper and the other feeds that
 * read through it (Explore's chain of posts, the shop and ad feeds) stay as they are. The feed end
 * hook it shares with Hide suggested posts goes in once.
 */
class HideHomeFeedHookTest {
    private val json = "Lfixture/JsonParser;"
    private val response = "Lfixture/HomeFeedResponse;"
    private val store = "Lfixture/HomeFeedStore;"
    private val chain = "Lfixture/ExploreChainResponse;"
    private val helper = ImmutableMethodReference(FeedItemStandIns.ITEM, "A02", listOf(json), FeedItemStandIns.ITEM)

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, vararg code: Instruction) =
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, code.toList(), null, null),
        )

    private fun type(owner: String, vararg methods: Method) =
        ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    /** A parser of [owner] loading [strings], reading one item through the helper and answering it. */
    private fun reader(owner: String, name: String, parameter: String, returns: String, vararg strings: String, readsItem: Boolean = true) =
        type(
            owner,
            method(
                owner, name, listOf(parameter), returns, 3,
                *(strings.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                    (if (readsItem) listOf(
                        ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, helper),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                    ) else emptyList()) +
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)).toTypedArray(),
            ),
        )

    /** The feed item stand-ins plus Home's response, Home's store and Explore's chain, each reading through the helper. */
    private fun classes(homeReadsItem: Boolean = true, helpers: Int = 1, kinds: List<String> = listOf("MEDIA")): List<ClassDef> =
        FeedItemStandIns.classes(kindNames = kinds, helpers = helpers) + listOf(
            reader(response, "unsafeParseFromJson", json, "Ljava/lang/Object;", "feed_items", "pull_to_refresh_window_ms", readsItem = homeReadsItem),
            reader(store, "A00", "[B", FeedItemStandIns.ITEM),
            type(FEED_MEDIA_CACHE, method(FEED_MEDIA_CACHE, "<init>", emptyList(), "V", 1,
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(store)),
                ImmutableInstruction10x(Opcode.RETURN_VOID))),
            reader(chain, "unsafeParseFromJson", json, "Ljava/lang/Object;", "chain_pagination_token", "more_available"),
        )

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HOME_FEED_FILTER.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOME_FEED_FILTER is not in the extension: $declared", HOME_FEED_FILTER.substringAfter("->") in declared)
    }

    /** Home's two reads answer through the filter; the helper and Explore's chain don't. */
    @Test
    fun onlyHomesReadsAnswerThroughTheFilter() {
        val context = PatchContexts.of(classes())

        assertEquals(2, context.filterHomeFeedItems())

        assertFilteredAfterRead("response", context.mutableClassDefBy(response).methods.single())
        assertFilteredAfterRead("store", context.mutableClassDefBy(store).methods.single())
        val chained = context.mutableClassDefBy(chain).methods.single().instructions()
        assertEquals("Explore's chain was touched", 5, chained.size)
        assertFalse(chained.any { it.calls(HOME_FEED_FILTER) })
        val item = context.mutableClassDefBy(FeedItemStandIns.ITEM)
        assertEquals("the helper was touched", 5, item.methods.single { it.name == "A02" }.instructions().size)
    }

    /** After Hide Reels in the feed, its filter stays at the helper's return and Home's sits on Home's reads. */
    @Test
    fun afterHideReelsInTheFeedEachFilterKeepsItsPlace() {
        val context = PatchContexts.of(classes(kinds = listOf("MEDIA", "AD", "CLIPS_NETEGO",
            "IMMERSIVE_SEGUE_ITEM", "VIBES_IN_FEED_UNIT", "HATCH_IMMERSIVE_IN_FEED_UNIT")))

        context.filterParsedFeedItems()
        context.filterHomeFeedItems()

        val helper = context.mutableClassDefBy(FeedItemStandIns.ITEM).methods.single { it.name == "A02" }
        assertFilteredBeforeReturn("helper", helper, FILTER)
        assertFalse(helper.instructions().any { it.calls(HOME_FEED_FILTER) })
        assertFilteredAfterRead("response", context.mutableClassDefBy(response).methods.single())
    }

    /**
     * Home's store reads an item on two paths, and the first jumps to right after the second's read,
     * as Instagram's does. Each read gets its filter, and the jump lands past the second filter, so
     * the first path's item goes through the filter once.
     */
    @Test
    fun aJumpPastAReadSkipsThatReadsFilter() {
        val read = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, helper),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        )
        // Code units: a read is 3 + 1 and the goto 1, so the goto at unit 4 lands on the return at 9.
        val twoPaths = type(
            store,
            method(
                store, "A00", listOf("[B"), FeedItemStandIns.ITEM, 3,
                *(read + ImmutableInstruction10t(Opcode.GOTO, 5) + read + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
                    .toTypedArray(),
            ),
        )
        val context = PatchContexts.of(classes().filter { it.type != store } + twoPaths)

        assertEquals(3, context.filterHomeFeedItems())

        val code = context.mutableClassDefBy(store).methods.single().instructions()
        assertFilteredAfterRead("store", context.mutableClassDefBy(store).methods.single())
        val jump = code.indexOfFirst { it.opcode == Opcode.GOTO }
        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        val landing = addresses.indexOf(addresses[jump] + (code[jump] as OffsetInstruction).codeOffset)
        assertEquals("the jump lands on the return, past the second filter", Opcode.RETURN_OBJECT, code[landing].opcode)
        assertEquals("the second filter is right before the return", Opcode.CHECK_CAST, code[landing - 1].opcode)
    }

    /** The helper's own read as Redex inlines it: the item parser's instance, its parse, the answer kept in v0 and cast. */
    private val inlinedRead = listOf(
        ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(FeedItemStandIns.PARSER, "A00", FeedItemStandIns.PARSER)),
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, 0, 2, 0, 0, 0,
            ImmutableMethodReference(FeedItemStandIns.PARSER, "parseFromJsonParser", listOf(json), "Ljava/lang/Object;"),
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(FeedItemStandIns.ITEM)),
    )

    /**
     * Home's store as 454 has it: Redex inlined the helper's read on the second path, and the first
     * path calls the helper and jumps to right after the copy's cast. Both reads get the filter, and
     * the jump lands past the second, so each path's item goes through it once.
     */
    @Test
    fun theStoresInlinedReadIsFilteredToo() {
        val read = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, helper),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        )
        // Code units: the read is 3 + 1 and the goto 1, the copy 2 + 3 + 1 + 2, so the goto at unit 4 lands on the return at 13.
        val twoPaths = type(
            store,
            method(
                store, "A00", listOf("[B"), FeedItemStandIns.ITEM, 3,
                *(read + ImmutableInstruction10t(Opcode.GOTO, 9) + inlinedRead + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
                    .toTypedArray(),
            ),
        )
        val context = PatchContexts.of(classes().filter { it.type != store } + twoPaths)

        assertEquals(3, context.filterHomeFeedItems())

        val patched = context.mutableClassDefBy(store).methods.single()
        assertEquals("the store's two reads", 2, itemsKept(patched, helper.toString(), FeedItemStandIns.ITEM).size)
        assertFilteredAfterRead("store", patched)
        val code = patched.instructions()
        val jump = code.indexOfFirst { it.opcode == Opcode.GOTO }
        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        val landing = addresses.indexOf(addresses[jump] + (code[jump] as OffsetInstruction).codeOffset)
        assertEquals("the jump lands on the return, past the copy's filter", Opcode.RETURN_OBJECT, code[landing].opcode)
        assertTrue("the copy's filter is right before the return", code[landing - 3].calls(HOME_FEED_FILTER))
    }

    /** Home's store with a copy of the helper's read whose answer it drops. */
    private fun withUnkeptCopy(): List<ClassDef> = classes().filter { it.type != store } + type(
        store,
        method(
            store, "A00", listOf("[B"), FeedItemStandIns.ITEM, 3,
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, helper),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            *inlinedRead.take(2).toTypedArray(),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ),
    )

    /**
     * Two parse helpers, a Home response that reads no item, or a store copy of the helper's read
     * whose item it drops, fail the patch before anything changes.
     */
    @Test
    fun aMissingOrDoubledReadFailsThePatchUnchanged() {
        for (built in listOf(classes(helpers = 2), classes(homeReadsItem = false), withUnkeptCopy())) {
            val context = PatchContexts.of(built)
            val before = built.associate { it.type to it.methods.sumOf { m -> m.instructions().size } }

            assertThrows(PatchException::class.java) { context.filterHomeFeedItems() }

            val after = built.associate { c -> c.type to context.classDefBy(c.type).methods.sumOf { it.instructions().size } }
            assertEquals(before, after)
        }
    }

    /** Both patches that empty Home lean on one feed end patch, so its hook goes in once. */
    @Test
    fun theFeedEndIsSharedWithHideSuggestedPosts() {
        assertTrue(emptiedFeedEndPatch in hideHomeFeedPatch.dependencies)
        assertTrue(emptiedFeedEndPatch in hideSuggestedPostsPatch.dependencies)
    }

    /**
     * In each declared build, every read of Home's feed response and Home's store, the store's copy
     * of the helper's read included, answers through FeedSuggestions.homeItem and HomeFeed, and every
     * other method reading through the feed item helper is left as it was: among them Explore's
     * chain of posts, the contextual ad feed and the shop ad feed, which an empty answer would leave
     * without posts.
     */
    @Test
    fun eachDeclaredBuildFiltersOnlyHomesReads() {
        for (fixture in FeedItemStandIns.fixtures()) builds(fixture, fixture.bundle.name)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildFiltersOnlyHomesReadsToo() {
        for (base in Fixtures.otherBuilds()) builds(otherBuild(base), base.parentFile.name)
    }

    /** [FeedItemStandIns.fixtures] for one of the other builds, whose base APK is not a bundle. */
    private fun otherBuild(base: File): FeedItemStandIns.Fixture {
        val holders = FixtureDex.classesHolding(base, "clips_netego")
        val made = holders.flatMap { it.methods }.flatMap { it.instructions() }
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
            .toSet()
        val candidates = FixtureDex.classes(base, made)
        val itemType = candidates.values.single { classDef -> classDef.fields.any { it.type == CLIPS_NETEGO } }.type
        val fieldTypes = candidates.getValue(itemType).fields.map { it.type }.toSet()
        val classes = (holders + candidates.values + FixtureDex.classes(base, fieldTypes).values).distinctBy { it.type }
        return FeedItemStandIns.Fixture(base, classes, itemType)
    }

    private fun builds(fixture: FeedItemStandIns.Fixture, name: String) {
        val helperRef = fixture.helper.let { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val callers = FixtureDex.methodsWhere(
            fixture.bundle,
            { dex -> dex.methodSection.any { it.toString() == helperRef } },
            { method -> method.instructions().any { it.calls(helperRef) } },
        )
        val cache = FixtureDex.classes(fixture.bundle, setOf(FEED_MEDIA_CACHE)).values
        val made = cache.flatMap { it.methods }.flatMap { it.instructions() }
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }.toSet()
        val classes = (fixture.classes + cache + FixtureDex.classes(fixture.bundle, callers.map { it.definingClass }.toSet() + made).values)
            .distinctBy { it.type }
        // The string pools come along so a reader whose name Redex pooled can be told by it.
        val context = PatchContexts.of(FixtureDex.withStringPools(fixture.bundle, classes))
        val home = callers.filter { it.holds("pull_to_refresh_window_ms") || (it.definingClass in made && it.parameterTypes.map(Any::toString) == listOf("[B")) }
        assertEquals("$name: Home's response and store", 2, home.size)
        val storeRead = home.single { it.parameterTypes.map(Any::toString) == listOf("[B") }
        // The store reads its item on two paths: on 454 one calls the helper and one is Redex's copy of it.
        assertEquals("$name: the store's two reads", 2, itemsKept(storeRead, helperRef, fixture.itemType).size)
        val homeReads = home.sumOf { itemsKept(it, helperRef, fixture.itemType).size }

        assertEquals(name, homeReads, context.filterHomeFeedItems())
        // Hide suggested posts' type switches filter the same reads, ahead of Hide the home feed's.
        assertEquals(name, homeReads, context.findHomeFeedReads("test").filterWith(HOME_TYPES_FILTER))

        for (caller in callers) {
            val after = context.mutableClassDefBy(caller.definingClass).methods.single {
                it.name == caller.name && it.parameterTypes.map(Any::toString) == caller.parameterTypes.map(Any::toString) &&
                    it.returnType == caller.returnType
            }
            if (caller in home) {
                assertFilteredAfterRead(
                    "$name ${caller.definingClass}", after, helperRef, fixture.itemType, listOf(HOME_TYPES_FILTER, HOME_FEED_FILTER),
                )
            } else {
                assertEquals("$name: ${caller.definingClass}->${caller.name} was touched", caller.instructions().size, after.instructions().size)
                assertFalse(after.instructions().any { it.calls(HOME_FEED_FILTER) || it.calls(HOME_TYPES_FILTER) })
            }
        }
        // Which strings Redex pools differs from build to build: 385611395, 385611400 and 385611440
        // ask a string pool for the shop ad feed's "XDTShopEverythingAdMediaResponse", which
        // 385611438 loads itself, so each reader is told by the string it loads either way.
        for (other in listOf("chain_pagination_token", "XDTContextualAdMediaResponse", "XDTShopEverythingAdMediaResponse")) {
            assertTrue(
                "$name: no reader holding $other among ${callers.map { it.definingClass }}",
                callers.any { context.loadsString(it, other) && it !in home },
            )
        }
        val helperAfter = context.mutableClassDefBy(fixture.itemType).methods.single {
            it.name == fixture.helper.name && it.parameterTypes.map(Any::toString) == fixture.helper.parameterTypes.map(Any::toString)
        }
        assertEquals("$name: the helper was touched", fixture.helper.instructions().size, helperAfter.instructions().size)
    }

    /**
     * Where [method] keeps an item it read, in order, as the index after it and the register: each
     * call to the helper [helperRef] and the move-result after it, and each parseFromJsonParser whose
     * answer is kept and cast to [itemType], which is a copy of the helper's read Redex inlined.
     */
    private fun itemsKept(method: Method, helperRef: String, itemType: String): List<Pair<Int, Int>> {
        val code = method.instructions()
        val calls = code.indices.filter { code[it].calls(helperRef) }.map { at ->
            assertEquals("${method.definingClass}: the item kept", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
            at + 2 to (code[at + 1] as OneRegisterInstruction).registerA
        }
        val copies = code.indices.filter { at ->
            val kept = code.getOrNull(at + 1)
            val cast = code.getOrNull(at + 2)
            code[at].opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
                ((code[at] as ReferenceInstruction).reference as MethodReference).name == "parseFromJsonParser" &&
                kept?.opcode == Opcode.MOVE_RESULT_OBJECT && cast?.opcode == Opcode.CHECK_CAST &&
                ((cast as ReferenceInstruction).reference as TypeReference).type == itemType &&
                (cast as OneRegisterInstruction).registerA == (kept as OneRegisterInstruction).registerA
        }.map { at -> at + 3 to (code[at + 1] as OneRegisterInstruction).registerA }
        return (calls + copies).sortedBy { it.first }
    }

    /** Each item [method] reads, as [itemsKept] finds them, is then answered through [filters], in that order, into its register. */
    private fun assertFilteredAfterRead(
        what: String,
        method: Method,
        helperRef: String = helper.toString(),
        itemType: String = FeedItemStandIns.ITEM,
        filters: List<String> = listOf(HOME_FEED_FILTER),
    ) {
        val code = method.instructions()
        val reads = itemsKept(method, helperRef, itemType)
        assertTrue("$what reads no item", reads.isNotEmpty())
        for ((after, kept) in reads) {
            filters.forEachIndexed { n, filter ->
                val at = after + 3 * n
                assertEquals(
                    "$what: the filter's opcodes at $at",
                    listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
                    code.subList(at, at + 3).map { it.opcode },
                )
                assertTrue("$what: $filter called at $at", code[at].calls(filter))
                assertEquals("$what: the register passed", kept, (code[at] as RegisterRangeInstruction).startRegister)
                assertEquals("$what: the register kept", kept, (code[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$what: the register cast", kept, (code[at + 2] as OneRegisterInstruction).registerA)
            }
        }
    }

    private fun Instruction.calls(reference: String): Boolean =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

    private fun Method.holds(string: String): Boolean =
        instructions().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string }
}
