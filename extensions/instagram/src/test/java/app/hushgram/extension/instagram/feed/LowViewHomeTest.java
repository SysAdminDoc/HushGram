/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.Function;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide reels with few views' Also on Home (#780): a Home post outside the switch's numbers goes
 * through the same per-item filter as the post type switches, so a page it empties ends the way
 * theirs does, and a read that throws keeps the post.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LowViewHomeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A feed item whose post is outside a number for [why], or inside every one when null. */
    private static final class Item {
        final String why;

        Item(String why) {
            this.why = why;
        }
    }

    private final ToIntFunction<Object> photo = item -> FeedSuggestions.PHOTO;
    private final ToIntFunction<Object> notLiked = item -> 0;
    private final ToIntFunction<Object> personal = item -> 0;
    private final Function<Object, String> nobody = item -> "someone";
    private final Function<Object, String> noCaption = item -> null;
    private final Function<Object, String> numbersOf = item -> ((Item) item).why;

    @Before
    public void start() {
        clear();
    }

    @After
    public void restore() {
        clear();
    }

    private void clear() {
        Settings.HIDE_LOW_VIEW_REELS.resetToDefault();
        Settings.HIDE_LOW_VIEW_HOME.resetToDefault();
        Settings.MIN_REEL_VIEWS.resetToDefault();
        Settings.MIN_REEL_LIKES.resetToDefault();
        Settings.REELS_NEWEST_FIRST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HiddenAccounts.resetForTests();
        FeedSuggestions.typesTookOut = false;
        FeedSuggestions.tookOut = false;
        FeedSuggestions.homePageLost = false;
        FeedSuggestions.pageLostEverything = false;
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private Object home(Item item) {
        return FeedSuggestions.homeItem(item, photo, notLiked, nobody, noCaption, personal, numbersOf);
    }

    private static void homeOn() {
        Settings.HIDE_LOW_VIEW_REELS.save(true);
        Settings.HIDE_LOW_VIEW_HOME.save(true);
    }

    @Test
    public void aPostOutsideTheNumbersLeavesHomeAndIsCountedByReason() {
        Item few = new Item("below the minimum");
        Item old = new Item("older than the maximum age");
        Item fine = new Item(null);

        assertNull(home(few));
        assertNull(home(old));
        assertSame(fine, home(fine));
        assertTrue(FeedSuggestions.typesTookOut);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FeedSuggestions.NUMBERS_REMOVED + " 2"));
        String counters = FeedFilterCounters.report().toString();
        assertTrue(counters, counters.contains("below the minimum 1"));
        assertTrue(counters, counters.contains("older than the maximum age 1"));
    }

    /** A page of Home's response that loses every post to the numbers counts as emptied. */
    @Test
    public void aPageItEmptiesCountsAsLost() {
        FeedSuggestions.homePageStarts();
        assertNull(home(new Item("below the minimum")));
        assertNull(home(new Item("shorter than the shortest")));
        FeedSuggestions.homePageParsed();
        assertTrue(FeedSuggestions.homePageLost);
        assertTrue(FeedSuggestions.pageLostEverything);
    }

    /** A post whose numbers can't be read stays, and the report says what threw. */
    @Test
    public void aReadThatThrowsKeepsThePost() {
        Item item = new Item("below the minimum");
        assertSame(item, FeedSuggestions.homeItem(item, photo, notLiked, nobody, noCaption, personal, ignored -> {
            throw new IllegalStateException("moved");
        }));
        assertFalse(FeedSuggestions.typesTookOut);
        assertTrue(HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString().contains("post numbers"));
    }

    /**
     * The real reader answers nothing until the switch and Also on Home are both on with a number
     * set, and nothing for an item it can't find a post in; paused, it answers nothing either.
     */
    @Test
    public void theRealReaderWaitsForBothSwitchesAndANumber() {
        Item item = new Item(null);
        assertNull("everything off", FeedSuggestions.numbersReason(item));
        assertFalse(FeedSuggestions.switchesTookOut());

        Settings.HIDE_LOW_VIEW_REELS.save(true);
        assertNull("Reels alone leaves Home", FeedSuggestions.numbersReason(item));

        homeOn();
        Settings.MIN_REEL_VIEWS.save(0);
        Settings.MIN_REEL_LIKES.save(0);
        Settings.REELS_NEWEST_FIRST.save(true);
        assertNull("no number set", FeedSuggestions.numbersReason(item));

        Settings.MIN_REEL_VIEWS.save(1000);
        assertNull("an item with no post stays", FeedSuggestions.numbersReason(item));
        assertSame(item, FeedSuggestions.homeItem(item, photo, notLiked, nobody, noCaption, personal,
                FeedSuggestions::numbersReason));

        FeedSuggestions.typesTookOut = true;
        assertTrue("Also on Home counts as a post switch for a page that came back empty",
                FeedSuggestions.switchesTookOut());
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull("paused", FeedSuggestions.numbersReason(item));
        assertFalse(FeedSuggestions.switchesTookOut());
    }
}
