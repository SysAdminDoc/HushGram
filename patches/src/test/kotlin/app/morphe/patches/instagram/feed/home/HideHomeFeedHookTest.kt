/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.home

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns
import app.morphe.patches.instagram.feed.FeedItemStandIns.assertFilteredBeforeReturn
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.feed.reels.FILTER
import app.morphe.patches.instagram.feed.reels.filterParsedFeedItems
import app.morphe.patches.instagram.feed.suggested.emptiedFeedEndPatch
import app.morphe.patches.instagram.feed.suggested.hideSuggestedPostsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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

    /** Two parse helpers, or a Home response that reads no item, fail the patch before anything changes. */
    @Test
    fun aMissingOrDoubledReadFailsThePatchUnchanged() {
        for (built in listOf(classes(helpers = 2), classes(homeReadsItem = false))) {
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
     * In each declared build, every read of Home's feed response and Home's store answers through
     * HomeFeed, and every other method reading through the feed item helper is left as it was:
     * among them Explore's chain of posts, the contextual ad feed and the shop ad feed, which an
     * empty answer would leave without posts.
     */
    @Test
    fun eachDeclaredBuildFiltersOnlyHomesReads() {
        for (fixture in FeedItemStandIns.fixtures()) {
            val name = fixture.bundle.name
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
            val context = PatchContexts.of(classes)
            val home = callers.filter { it.holds("pull_to_refresh_window_ms") || (it.definingClass in made && it.parameterTypes.map(Any::toString) == listOf("[B")) }
            assertEquals("$name: Home's response and store", 2, home.size)
            val homeReads = home.sumOf { m -> m.instructions().count { it.calls(helperRef) } }

            assertEquals(name, homeReads, context.filterHomeFeedItems())

            for (caller in callers) {
                val after = context.mutableClassDefBy(caller.definingClass).methods.single {
                    it.name == caller.name && it.parameterTypes.map(Any::toString) == caller.parameterTypes.map(Any::toString) &&
                        it.returnType == caller.returnType
                }
                if (caller in home) {
                    assertFilteredAfterRead("$name ${caller.definingClass}", after, helperRef)
                } else {
                    assertEquals("$name: ${caller.definingClass}->${caller.name} was touched", caller.instructions().size, after.instructions().size)
                    assertFalse(after.instructions().any { it.calls(HOME_FEED_FILTER) })
                }
            }
            for (other in listOf("chain_pagination_token", "XDTContextualAdMediaResponse", "XDTShopEverythingAdMediaResponse")) {
                assertTrue("$name: no reader holding $other among ${callers.map { it.definingClass }}", callers.any { it.holds(other) && it !in home })
            }
            val helperAfter = context.mutableClassDefBy(fixture.itemType).methods.single {
                it.name == fixture.helper.name && it.parameterTypes.map(Any::toString) == fixture.helper.parameterTypes.map(Any::toString)
            }
            assertEquals("$name: the helper was touched", fixture.helper.instructions().size, helperAfter.instructions().size)
        }
    }

    /** Each read of the helper in [method] is kept and then answered through HomeFeed into the same register. */
    private fun assertFilteredAfterRead(what: String, method: Method, helperRef: String = helper.toString()) {
        val code = method.instructions()
        val reads = code.indices.filter { code[it].calls(helperRef) }
        assertTrue("$what reads no item", reads.isNotEmpty())
        for (at in reads) {
            assertEquals("$what: the item kept", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
            val kept = (code[at + 1] as OneRegisterInstruction).registerA
            assertEquals(
                "$what: the filter's opcodes",
                listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
                code.subList(at + 2, at + 5).map { it.opcode },
            )
            assertTrue("$what: the filter called", code[at + 2].calls(HOME_FEED_FILTER))
            assertEquals("$what: the register kept", kept, (code[at + 3] as OneRegisterInstruction).registerA)
            assertEquals("$what: the register cast", kept, (code[at + 4] as OneRegisterInstruction).registerA)
        }
    }

    private fun Instruction.calls(reference: String): Boolean =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

    private fun Method.holds(string: String): Boolean =
        instructions().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string }
}
