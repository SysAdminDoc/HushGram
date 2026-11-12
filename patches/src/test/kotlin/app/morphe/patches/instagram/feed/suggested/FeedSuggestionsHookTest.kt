/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.feed.FeedItemStandIns
import app.morphe.patches.instagram.feed.FeedItemStandIns.assertFilteredBeforeReturn
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.feed.reels.FILTER
import app.morphe.patches.instagram.feed.reels.REEL_UNITS
import app.morphe.patches.instagram.feed.reels.filterParsedFeedItems
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSuggestionsHookTest {
    /** The hook the patch writes is in the FeedSuggestions the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val type = SUGGESTIONS_FILTER.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SUGGESTIONS_FILTER is not in the extension: $declared", SUGGESTIONS_FILTER.substringAfter("->") in declared)
    }

    @Test
    fun theParseHelperAnswersThroughTheFilter() {
        val context = PatchContexts.of(classes())

        context.filterSuggestedFeedItems()

        val patched = context.mutableClassDefBy(FeedItemStandIns.ITEM)
        assertFilteredBeforeReturn("helper", patched.methods.single { it.name == "A02" }, SUGGESTIONS_FILTER)
        assertEquals("the other static helper was touched", 2, patched.methods.single { it.name == "A01" }.instructions().size)
    }

    /** With Hide Reels in the feed in too, the filter of the patch applied second takes the first one's answer. */
    @Test
    fun afterHideReelsInTheFeedBothFiltersAnswer() {
        val context = PatchContexts.of(classes())

        context.filterParsedFeedItems()
        context.filterSuggestedFeedItems()

        val helper = context.mutableClassDefBy(FeedItemStandIns.ITEM).methods.single { it.name == "A02" }
        assertFilteredBeforeReturn("helper", helper, FILTER, SUGGESTIONS_FILTER)
    }

    @Test
    fun appliedBeforeHideReelsInTheFeedBothFiltersAnswer() {
        val context = PatchContexts.of(classes())

        context.filterSuggestedFeedItems()
        context.filterParsedFeedItems()

        val helper = context.mutableClassDefBy(FeedItemStandIns.ITEM).methods.single { it.name == "A02" }
        assertFilteredBeforeReturn("helper", helper, SUGGESTIONS_FILTER, FILTER)
    }

    @Test
    fun aKindEnumMissingASuggestionUnitFailsThePatch() {
        val context = PatchContexts.of(
            classes(kindNames = listOf("MEDIA", "AD", SUGGESTED_POST) + ACCOUNT_UNITS.drop(1) + THREADS_UNITS + SURVEY_UNITS + SHOPPING_UNITS),
        )
        assertThrows(PatchException::class.java) { context.filterSuggestedFeedItems() }
    }

    @Test
    fun aKindEnumMissingTheSuggestedPostFailsThePatch() {
        val context = PatchContexts.of(classes(kindNames = listOf("MEDIA", "AD") + ACCOUNT_UNITS + THREADS_UNITS + SURVEY_UNITS + SHOPPING_UNITS))
        assertThrows(PatchException::class.java) { context.filterSuggestedFeedItems() }
    }

    @Test
    fun aKindEnumMissingAThreadsUnitFailsThePatch() {
        val context = PatchContexts.of(
            classes(kindNames = listOf("MEDIA", "AD", SUGGESTED_POST) + ACCOUNT_UNITS + THREADS_UNITS.dropLast(1) + SURVEY_UNITS + SHOPPING_UNITS),
        )
        assertThrows(PatchException::class.java) { context.filterSuggestedFeedItems() }
    }

    @Test
    fun aKindEnumMissingTheSurveyFailsThePatch() {
        val context = PatchContexts.of(
            classes(kindNames = listOf("MEDIA", "AD", SUGGESTED_POST) + ACCOUNT_UNITS + THREADS_UNITS + SHOPPING_UNITS),
        )
        assertThrows(PatchException::class.java) { context.filterSuggestedFeedItems() }
    }

    @Test
    fun aKindEnumMissingAShoppingUnitFailsThePatch() {
        val context = PatchContexts.of(
            classes(kindNames = listOf("MEDIA", "AD", SUGGESTED_POST) + ACCOUNT_UNITS + THREADS_UNITS + SURVEY_UNITS +
                SHOPPING_UNITS.drop(1)),
        )
        assertThrows(PatchException::class.java) { context.filterSuggestedFeedItems() }
    }

    @Test
    fun anotherEnumNamingASuggestionUnitFailsThePatch() {
        val context = PatchContexts.of(classes(fetchNames = listOf("COLD_START", "SUGGESTED_USERS")))
        assertThrows(PatchException::class.java) { context.filterSuggestedFeedItems() }
    }

    /**
     * In each declared build the item's kind enum names every suggestion unit, the suggested post
     * and Threads' units, and the parse helper answers through both filters when both patches are in.
     */
    @Test
    fun eachDeclaredBuildFiltersTheParsedFeedItem() {
        for (fixture in FeedItemStandIns.fixtures()) {
            val context = PatchContexts.of(fixture.classes)

            context.filterParsedFeedItems()
            context.filterSuggestedFeedItems()

            val before = fixture.helper
            val after = context.mutableClassDefBy(fixture.itemType).methods.single {
                it.name == before.name && it.parameterTypes.map(Any::toString) == before.parameterTypes.map(Any::toString)
            }
            val returns = before.instructions().count { it.opcode == Opcode.RETURN_OBJECT }
            assertEquals("${fixture.bundle.name}: helper size", before.instructions().size + 6 * returns, after.instructions().size)
            assertFilteredBeforeReturn(fixture.bundle.name, after, FILTER, SUGGESTIONS_FILTER)
        }
    }

    private fun classes(
        kindNames: List<String> = listOf("MEDIA", "AD", SUGGESTED_POST) + REEL_UNITS + ACCOUNT_UNITS + THREADS_UNITS + SURVEY_UNITS + SHOPPING_UNITS,
        fetchNames: List<String> = listOf("COLD_START", "PULL_TO_REFRESH"),
    ) = FeedItemStandIns.classes(kindNames, fetchNames)
}
