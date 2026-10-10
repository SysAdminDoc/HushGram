/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import instagram.features.feed.mainfeed.actionbar.MainFeedActionBar;
import org.robolectric.RuntimeEnvironment;

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
        Settings.LOGO_ON_FOLLOWING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private static View titleInHomeHeader() {
        Context context = RuntimeEnvironment.getApplication();
        MainFeedActionBar bar = new MainFeedActionBar(context);
        FrameLayout row = new FrameLayout(context);
        View title = new View(context);
        row.addView(title);
        bar.addView(row);
        return title;
    }

    @Test
    public void theLogoSwitchStartsOffAndNeedsStartOnFollowing() {
        org.junit.Assert.assertEquals(Boolean.FALSE, Settings.LOGO_ON_FOLLOWING.defaultValue);
        assertTrue(Settings.LOGO_ON_FOLLOWING.rebootApp);
        Settings.LOGO_ON_FOLLOWING.save(true);
        assertTrue(Settings.LOGO_ON_FOLLOWING.isAvailable());
        Settings.START_ON_FOLLOWING.save(false);
        assertFalse(Settings.LOGO_ON_FOLLOWING.isAvailable());
    }

    @Test
    public void theLogoStandsInForTheNameOnlyInHomesHeaderWithBothSwitchesOn() {
        View title = titleInHomeHeader();
        assertEquals("off to start", 0, FollowingFeed.keepLogo(title));
        Settings.LOGO_ON_FOLLOWING.save(true);
        HookStatus.clear();
        assertEquals(1, FollowingFeed.keepLogo(title));
        assertEquals(
                java.util.Arrays.asList(FamilyNames.FOLLOWING_FEED + ": invoked 1, 0 found, 0 missing. Counted: "
                        + FollowingFeed.LOGO_KEPT + " 1"),
                HookStatus.report());

        assertEquals("a title view outside Home's header", 0,
                FollowingFeed.keepLogo(new View(RuntimeEnvironment.getApplication())));
        assertEquals(0, FollowingFeed.keepLogo(null));
        assertEquals(0, FollowingFeed.keepLogo("not a view"));
        Settings.START_ON_FOLLOWING.save(false);
        assertEquals("Start Home on Following off", 0, FollowingFeed.keepLogo(title));
    }

    @Test
    public void pausedAndUnreadyShowTheName() {
        Settings.LOGO_ON_FOLLOWING.save(true);
        View title = titleInHomeHeader();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, FollowingFeed.keepLogo(title));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(1, FollowingFeed.keepLogo(title));
        SettingsContextRule.withoutContext(() -> assertEquals(0, FollowingFeed.keepLogo(title)));
    }

    @Test
    public void theHeaderIsFoundOnlyWithinAFewParents() {
        Context context = RuntimeEnvironment.getApplication();
        MainFeedActionBar bar = new MainFeedActionBar(context);
        FrameLayout top = bar;
        for (int level = 0; level < 8; level++) {
            FrameLayout inner = new FrameLayout(context);
            top.addView(inner);
            top = inner;
        }
        View deep = new View(context);
        top.addView(deep);
        assertFalse(FollowingFeed.underBar(deep, FollowingFeed.HOME_BAR));
        assertTrue(FollowingFeed.underBar(titleInHomeHeader(), FollowingFeed.HOME_BAR));
    }

    @Test
    public void homesHeaderKeepsTheNameInstagramsLayoutsGiveIt() {
        assertEquals(MainFeedActionBar.class.getName(), FollowingFeed.HOME_BAR);
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
