/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** When Start Home on Following turns on the remembered feed, and what it answers for the saved pick. */
@RunWith(RobolectricTestRunner.class)
public class FollowingFeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.START_ON_FOLLOWING.save(true);
    }

    @After
    public void switchBack() {
        Settings.START_ON_FOLLOWING.resetToDefault();
    }

    @Test
    public void theFlagIsOnWhileTheSwitchIsOn() {
        assertTrue(FollowingFeed.flag(0));
        assertTrue(FollowingFeed.flag(1));
    }

    @Test
    public void withNothingPickedHomeStartsOnFollowing() {
        assertEquals("FOLLOWING", FollowingFeed.saved(null));
        assertEquals("FOLLOWING", FollowingFeed.saved(""));
    }

    @Test
    public void aPickIsKept() {
        assertEquals("BLENDED_FOR_YOU", FollowingFeed.saved("BLENDED_FOR_YOU"));
        assertEquals("FOLLOWING", FollowingFeed.saved("FOLLOWING"));
    }

    @Test
    public void withTheSwitchOffInstagramsAnswersStand() {
        Settings.START_ON_FOLLOWING.save(false);
        try {
            assertFalse(FollowingFeed.flag(0));
            assertTrue(FollowingFeed.flag(1));
            assertNull(FollowingFeed.saved(null));
            assertEquals("", FollowingFeed.saved(""));
            assertEquals("BLENDED_FOR_YOU", FollowingFeed.saved("BLENDED_FOR_YOU"));
        } finally {
            Settings.START_ON_FOLLOWING.save(true);
        }
    }

    @Test
    public void onlyFollowingSendsAForYouPickToFollowing() {
        Settings.ONLY_FOLLOWING.save(true);
        try {
            assertEquals("FOLLOWING", FollowingFeed.saved("BLENDED_FOR_YOU"));
            assertEquals("FOLLOWING", FollowingFeed.saved("BLENDED"));
            assertEquals("FAVORITES", FollowingFeed.saved("FAVORITES"));
            assertEquals("FOLLOWING", FollowingFeed.saved(null));
        } finally {
            Settings.ONLY_FOLLOWING.resetToDefault();
        }
    }

    /** The picker's list, shaped like 449's: items holding one feed type constant, For you first. */
    @Test
    public void onlyFollowingTakesForYouOutOfThePicker() {
        List<Item> feeds = picker(Feed.BLENDED_FOR_YOU, Feed.FOLLOWING, Feed.FAVORITES);
        FollowingFeed.limitPicker(feeds);
        assertEquals(3, feeds.size());

        Settings.ONLY_FOLLOWING.save(true);
        try {
            FollowingFeed.limitPicker(feeds);
            assertEquals(Arrays.asList(Feed.FOLLOWING, Feed.FAVORITES), types(feeds));

            List<Item> home = picker(Feed.BLENDED, Feed.FOLLOWING);
            FollowingFeed.limitPicker(home);
            assertEquals(Arrays.asList(Feed.FOLLOWING), types(home));

            // Nothing would be left, so Instagram's list stands.
            List<Item> alone = picker(Feed.BLENDED_FOR_YOU);
            FollowingFeed.limitPicker(alone);
            assertEquals(Arrays.asList(Feed.BLENDED_FOR_YOU), types(alone));

            Settings.START_ON_FOLLOWING.save(false);
            List<Item> off = picker(Feed.BLENDED_FOR_YOU, Feed.FOLLOWING);
            FollowingFeed.limitPicker(off);
            assertEquals(2, off.size());
        } finally {
            Settings.ONLY_FOLLOWING.resetToDefault();
            Settings.START_ON_FOLLOWING.save(true);
        }
    }

    @Test
    public void anItemWithoutOneFeedTypeIsKept() throws Exception {
        assertNull(FollowingFeed.feedName(new Object()));
        assertNull(FollowingFeed.feedName(null));
        assertEquals("FAVORITES", FollowingFeed.feedName(new Item(Feed.FAVORITES)));
    }

    private enum Feed { BLENDED, BLENDED_FOR_YOU, FOLLOWING, FAVORITES }

    /** A picker item: its view state, then its feed type, as 449's are. */
    private static final class Item {
        @SuppressWarnings("unused") Object state;
        final Feed type;

        Item(Feed type) {
            this.type = type;
        }
    }

    private static List<Item> picker(Feed... types) {
        List<Item> items = new ArrayList<>();
        for (Feed type : types) items.add(new Item(type));
        return items;
    }

    private static List<Feed> types(List<Item> items) {
        List<Feed> types = new ArrayList<>();
        for (Item item : items) types.add(item.type);
        return types;
    }
}
