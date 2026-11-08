/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shipped patch carries one category from the compact taxonomy Morphe Manager groups by.
 *
 * <p>Manager 1.30.0 sections the patch list by category when the reader asks it to, and a patch
 * with none lands in an ungrouped remainder at the bottom. The generated catalog is what the
 * README and the site read, so it is held here as well as the bundle: a declaration without a
 * category, a misspelt one, or a catalog generated before the declarations landed all fail.
 */
class PatchCategoriesTest {
    /**
     * One name per group, mostly the HushGram settings section the patch's switch sits in, and
     * Hushfeed's names where they fit. Ghost mode is the settings card that turns those seven
     * switches on together.
     */
    private val taxonomy = setOf(
        "Ads", "Ghost mode", "Privacy", "Messages", "Feed", "Stories", "Reels", "Playback", "Interaction",
        "Profiles", "Interface", "Downloads", "Updates", "Fixes", "Settings",
    )

    /** Interface once held 43 patches. A group past this is too long to scan on a phone. Feed, Interface and Reels are at the limit. Keep Home's top bar (#408) and Keep the Reels top bar (#411) took Feed and Reels past it, so Start feed videos with sound and Keep the reel speed moved to Playback. Explore without personalization (#311) went to Privacy beside Don't save recent searches rather than take Interface past it. Ambient light (#416) and Carousel arrows (#417) took Feed to 18, so Ask before a like and Long press to zoom moved to Interaction beside Change the like animation; their switches stay on the Feed settings page. Hide reels with few views took Reels to 17 and Home header text (#454) took Interface there, so Control taps and volume on Reels moved to Playback, where its switches sit, and Hide rows in Instagram's settings moved to Settings. Stories is at the limit too, so Speed button on stories (#469) went to Playback beside Keep the reel speed; its switch stays on the Stories settings page. Hide activity bubbles (#475) would have taken Feed to 17, so it went to Interaction, since the bubbles show a friend's like, comment or reshare; its switch sits on the Feed settings page. Phone layout on big screens (#478) went to Fixes rather than take Interface past it, since it fixes the layout Instagram picks on a foldable or tablet; its switch sits on the Layout settings page. Preview chats (#477) took Messages to the limit. Hide the reply box on stories (#486) took Stories to 17, so Start stories with sound went to Playback beside Start feed videos with sound; its switch stays on the Stories settings page. Keep playing in the background (#497) took Playback to the limit. Swipe through a chat's photos and posts (#350) would have taken Messages to 17, so it went to Interaction, since it's a gesture on what you opened; its switch sits on the Messages settings page. Double tap to skip on Reels (#554) and Hold for 2x on feed videos (#552) took Reels and Playback to 17, so Swipe left to open the profile went to Profiles, where it leads, Hide suggested accounts in Reels went there beside Hide suggested people on profiles, and Turn off HDR brightness boosts went to Fixes; their switches stay where they were. That left Reels room for Hide the 2x popup on Reels (#552). Search comments (#560) took Interaction to 17, so Skip the collections tray went to Fixes, since it keeps a tray from getting in the way of a save. Split the notes row (#558) would have taken Messages to 17, so Lock your messages went to Privacy, since it covers your chats from anyone holding the phone. Their switches stay where they were. Hide lives in Reels (#566) took Hide paid partnership Reels's place, which went to Ads, since those reels are paid for. Hide Made with Edits (#570) went to Ads too, since the label sells Meta's Edits app. Save in the post menu (#569) went to Fixes beside Skip the collections tray, since it moves Save away from stray taps. Watch again in the feed (#568) went to Fixes as well, since it keeps a replay from throwing you into Reels. Their switches sit on the Feed and Reels settings pages. */
    private val largestGroup = 16

    private fun shippedPatches() = run {
        val catalog = File("../patches-list.json").takeIf { it.isFile } ?: File("patches-list.json")
        assertTrue("could not find the patch list from ${File(".").absolutePath}", catalog.isFile)
        JsonParser.parseString(catalog.readText()).asJsonObject.getAsJsonArray("patches")
            .map { it.asJsonObject }
    }

    @Test
    fun `every shipped patch has one category from the taxonomy`() {
        val patches = shippedPatches()
        assertTrue("the catalog holds no patches", patches.size >= 6)
        val missing = patches.filter { patch ->
            val category = patch.get("category")
            category == null || category.isJsonNull || category.asString.isBlank()
        }.map { it.get("name").asString }
        assertEquals("patches shipped with no category", emptyList<String>(), missing.sorted())
        val unknown = patches.filter { it.get("category").asString !in taxonomy }
            .map { "${it.get("name").asString} (${it.get("category").asString})" }
        assertEquals("patches shipped with a category outside the taxonomy",
            emptyList<String>(), unknown.sorted())
    }

    /**
     * The catalog is generated by a task nothing runs on its own, so a declaration changed
     * after the last generation would pass the check above against stale data. The count of
     * each category across the declarations has to be the count the catalog carries.
     */
    @Test
    fun `the catalog carries the categories the declarations carry`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        assertTrue("could not find the patch sources from ${File(".").absolutePath}", root.isDirectory)
        val declared = root.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> Regex("category\\(\"([^\"]*)\"\\)").findAll(file.readText()).map { it.groupValues[1] } }
            .groupingBy { it }.eachCount()
        val shipped = shippedPatches().groupingBy { it.get("category").asString }.eachCount()
        assertEquals("regenerate the catalog: :patches:generatePatchesList", declared.toSortedMap(), shipped.toSortedMap())
    }

    @Test
    fun `the catalog order is stable and every group is used`() {
        val patches = shippedPatches()
        val names = patches.map { it.get("name").asString }
        // Natural string order, capitals first: Hide ads sorts before HushGram settings.
        assertEquals("the generator sorts by name, so a diff only ever shows real changes",
            names.sorted(), names)
        val used = patches.map { it.get("category").asString }.toSet()
        assertEquals("a taxonomy name no patch uses is a heading Manager would never show",
            emptyList<String>(), (taxonomy - used).sorted())
    }

    @Test
    fun `no group runs too long to scan`() {
        val crowded = shippedPatches().groupingBy { it.get("category").asString }.eachCount()
            .filterValues { it > largestGroup }
        assertEquals("split a group past $largestGroup patches", emptyMap<String, Int>(), crowded)
    }
}
