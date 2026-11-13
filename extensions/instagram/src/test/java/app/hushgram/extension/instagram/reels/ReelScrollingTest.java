/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which pagers the hooks hold still, that the pull-down layout lets touches by, and that they fail open. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelScrollingTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** Stands in for ClipsViewerSource, whose constants the hook reads by name. */
    private enum Source {
        CLIPS_TAB, CLIPS_TAB_HOMECOMING, DIRECT, FEED_TIMELINE, FEED_TIMELINE_FOLLOWING, FEED_TIMELINE_POST_TAP,
        CLIPS_NETEGO, CLIPS_TRENDING_IN_FEED_UNIT,
        PROFILE, SELF_PROFILE, FEED_CONTEXTUAL_PROFILE, REPOSTS_GRID, TAGGED_MEDIA_VIEWER,
        EXPLORE_POPULAR_MAJOR_UNIT, EXPLORE_VIDEO_CHAINING, SERP_TOP_CLIPS_UNIT, KEYWORD, HASHTAG, LOCATION,
        SONG, AUDIO_PAGE_CHAIN, AUDIO_NETEGO_PAGE,
    }

    private final Object reels = new Object();
    private final Object other = new Object();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_REELS_SCROLLING.save(true);
        ReelScrolling.forget();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.STOP_REELS_SCROLLING.resetToDefault();
        Settings.PLAY_ONE_REEL.resetToDefault();
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ReelScrolling.forget();
        HookStatus.clear();
    }

    /** On, a Reels pager's swipes go off as the viewer sets it up and stay off, and a pull lets the touch by. */
    @Test
    public void withTheSwitchOnAReelsPagerStaysPut() {
        assertEquals("turned off at setup", 0, ReelScrolling.pager(reels, Source.CLIPS_TAB));
        assertEquals("Instagram turning it back on", 0, ReelScrolling.userInput(reels, 1));
        assertEquals("Instagram turning it off", 0, ReelScrolling.userInput(reels, 0));
        assertEquals("a pull", 0, ReelScrolling.pull(null));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.REEL_SCROLLING));
    }

    /** Every other pager in Instagram takes what it's given, and a missing pager is left to Instagram. */
    @Test
    public void otherPagersAreLeftAlone() {
        ReelScrolling.pager(reels, Source.CLIPS_TAB);
        assertEquals("another pager", 1, ReelScrolling.userInput(other, 1));
        assertEquals("another pager", 0, ReelScrolling.userInput(other, 0));
        assertEquals("no pager", 1, ReelScrolling.userInput(null, 1));
        assertEquals("no pager to turn off", 1, ReelScrolling.pager(null, Source.CLIPS_TAB));
    }

    /** A pager set up while the switch was off is held once it's on. */
    @Test
    public void aPagerSetUpWhileOffIsHeldOnceOn() {
        Settings.STOP_REELS_SCROLLING.save(false);
        assertEquals(1, ReelScrolling.pager(reels, Source.CLIPS_TAB));
        assertEquals(1, ReelScrolling.userInput(reels, 1));

        Settings.STOP_REELS_SCROLLING.save(true);
        assertEquals(0, ReelScrolling.userInput(reels, 1));
    }

    /** The switch starts off, and off, paused or asked before the settings are read, Reels scroll and pull as before. */
    @Test
    public void offPausedAndUnreadyKeepReelsScrolling() {
        Settings.STOP_REELS_SCROLLING.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.STOP_REELS_SCROLLING.defaultValue);
        assertStock("off");

        Settings.STOP_REELS_SCROLLING.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertStock("paused");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertStock("unready"));

        assertEquals(0, ReelScrolling.pager(reels, Source.CLIPS_TAB));
        assertEquals(0, ReelScrolling.pull(null));
    }

    /** Nothing picked, every viewer is stopped, whichever source it was opened from, as before these switches. */
    @Test
    public void withNoSourcePickedEveryViewerIsStopped() {
        for (Source source : Source.values()) {
            Object pager = new Object();
            assertEquals(source + " at setup", 0, ReelScrolling.pager(pager, source));
            assertEquals(source + " turned back on", 0, ReelScrolling.userInput(pager, 1));
        }
        assertEquals("a source that couldn't be read", 0, ReelScrolling.pager(new Object(), null));
        assertEquals("a source that isn't one", 0, ReelScrolling.pager(new Object(), "PROFILE"));
    }

    /** Each switch lets its own viewers scroll and leaves the Reels tab and the other two stopped. */
    @Test
    public void eachSwitchLetsOnlyItsOwnViewersThrough() {
        assertLetThrough(Settings.REEL_SCROLL_FROM_PROFILE, Source.PROFILE, Source.SELF_PROFILE, Source.FEED_CONTEXTUAL_PROFILE,
                Source.REPOSTS_GRID, Source.TAGGED_MEDIA_VIEWER);
        assertLetThrough(Settings.REEL_SCROLL_FROM_SEARCH, Source.EXPLORE_POPULAR_MAJOR_UNIT, Source.EXPLORE_VIDEO_CHAINING,
                Source.SERP_TOP_CLIPS_UNIT, Source.KEYWORD, Source.HASHTAG, Source.LOCATION);
        assertLetThrough(Settings.REEL_SCROLL_FROM_AUDIO, Source.SONG, Source.AUDIO_PAGE_CHAIN, Source.AUDIO_NETEGO_PAGE);
    }

    private void assertLetThrough(BooleanSetting setting, Source... letThrough) {
        java.util.List<Source> own = java.util.Arrays.asList(letThrough);
        setting.save(true);
        try {
            for (Source source : Source.values()) {
                Object pager = new Object();
                int expected = own.contains(source) ? 1 : 0;
                assertEquals(setting.key + " " + source + " at setup", expected, ReelScrolling.pager(pager, source));
                assertEquals(setting.key + " " + source + " turned back on", expected, ReelScrolling.userInput(pager, 1));
                assertEquals(setting.key + " " + source + " turned off stays off", 0, ReelScrolling.userInput(pager, 0));
            }
        } finally {
            setting.resetToDefault();
        }
    }

    /** A let-through viewer's own pull-down layout works, and the one around the Reels tab's pager stays out of the way. */
    @Test
    public void onlyTheLayoutAroundALetThroughPagerPulls() {
        Settings.REEL_SCROLL_FROM_SEARCH.save(true);
        try {
            FrameLayout tab = new FrameLayout(RuntimeEnvironment.getApplication());
            FrameLayout search = new FrameLayout(RuntimeEnvironment.getApplication());
            ReelScrolling.pager(inside(tab), Source.CLIPS_TAB);
            ReelScrolling.pager(inside(search), Source.HASHTAG);
            assertEquals("the Reels tab's layout", 0, ReelScrolling.pull(tab));
            assertEquals("the hashtag viewer's layout", 1, ReelScrolling.pull(search));

            Settings.REEL_SCROLL_FROM_SEARCH.save(false);
            assertEquals("switched off, it stops again", 0, ReelScrolling.pull(search));
        } finally {
            Settings.REEL_SCROLL_FROM_SEARCH.resetToDefault();
        }
    }

    /** A pager a level or two inside the layout, as the viewer's is. */
    private static View inside(FrameLayout layout) {
        FrameLayout between = new FrameLayout(layout.getContext());
        View pager = new View(layout.getContext());
        between.addView(pager);
        layout.addView(between);
        return pager;
    }

    /** With Stop Reels scrolling off the three switches change nothing. */
    @Test
    public void theSwitchesMeanNothingWithTheMainOneOff() {
        Settings.STOP_REELS_SCROLLING.save(false);
        Settings.REEL_SCROLL_FROM_PROFILE.save(true);
        try {
            assertEquals(1, ReelScrolling.pager(reels, Source.CLIPS_TAB));
            assertEquals(1, ReelScrolling.pager(other, Source.PROFILE));
            assertEquals(1, ReelScrolling.userInput(reels, 1));
            assertEquals(1, ReelScrolling.pull(null));
        } finally {
            Settings.REEL_SCROLL_FROM_PROFILE.resetToDefault();
        }
    }

    /** A switch that throws leaves the pager and the layout to Instagram and says which hook threw. */
    @Test
    public void aThrowingSwitchKeepsReelsScrollingAndIsReported() {
        assertEquals(1, ReelScrolling.pager(reels, Source.CLIPS_TAB, THROWS));
        assertEquals(1, ReelScrolling.userInput(reels, 1, THROWS));
        assertEquals(0, ReelScrolling.userInput(reels, 0, THROWS));
        assertEquals(1, ReelScrolling.pull(null, THROWS));

        String missing = HookStatus.missing(FamilyNames.REEL_SCROLLING).toString();
        for (String part : new String[] {ReelScrolling.PAGER, ReelScrolling.INPUT, ReelScrolling.PULL}) {
            assertTrue(missing, missing.contains("'" + part + "'"));
        }
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** Sources Play only the reel you opened covers among those above: posts on Home, a chat and two suggested units. */
    private static java.util.List<Source> playedAlone() {
        return java.util.Arrays.asList(Source.FEED_TIMELINE, Source.FEED_TIMELINE_FOLLOWING, Source.FEED_TIMELINE_POST_TAP,
                Source.DIRECT, Source.CLIPS_NETEGO, Source.CLIPS_TRENDING_IN_FEED_UNIT);
    }

    /** On its own, Play only the reel you opened holds viewers from Home, a chat and a suggested unit, swipe and auto scroll both, and nothing else. */
    @Test
    public void playOneReelHoldsViewersFromHomeChatsAndSuggestedUnits() {
        Settings.STOP_REELS_SCROLLING.save(false);
        Settings.PLAY_ONE_REEL.save(true);
        for (Source source : Source.values()) {
            Object pager = new Object();
            int expected = playedAlone().contains(source) ? 0 : 1;
            assertEquals(source + " at setup", expected, ReelScrolling.pager(pager, source));
            assertEquals(source + " turned back on", expected, ReelScrolling.userInput(pager, 1));
            assertEquals(source + " turned off stays off", 0, ReelScrolling.userInput(pager, 0));
            assertEquals(source + " auto scroll", expected, ReelScrolling.autoScroll(source));
        }
        assertEquals("a source that couldn't be read", 1, ReelScrolling.pager(new Object(), null));
        assertEquals("a source that isn't one", 1, ReelScrolling.pager(new Object(), "FEED_TIMELINE"));
        assertEquals("auto scroll with no source", 1, ReelScrolling.autoScroll(null));
        assertEquals("auto scroll with a source that isn't one", 1, ReelScrolling.autoScroll("DIRECT"));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.REEL_SCROLLING));
    }

    /** With Stop Reels scrolling on too, its let-through switches still free their own viewers and Play only the reel you opened still holds its own. */
    @Test
    public void playOneReelHoldsItsOwnViewersBesideTheStop() {
        Settings.PLAY_ONE_REEL.save(true);
        Settings.REEL_SCROLL_FROM_PROFILE.save(true);
        try {
            assertEquals("the Reels tab, stopped as before", 0, ReelScrolling.pager(new Object(), Source.CLIPS_TAB));
            assertEquals("a profile, let through", 1, ReelScrolling.pager(new Object(), Source.PROFILE));
            assertEquals("a post on Home", 0, ReelScrolling.pager(new Object(), Source.FEED_TIMELINE));

            Settings.STOP_REELS_SCROLLING.save(false);
            assertEquals("the Reels tab, the stop off", 1, ReelScrolling.pager(new Object(), Source.CLIPS_TAB));
            assertEquals("a post on Home, the stop off", 0, ReelScrolling.pager(new Object(), Source.FEED_TIMELINE));
        } finally {
            Settings.REEL_SCROLL_FROM_PROFILE.resetToDefault();
        }
    }

    /** Auto scroll in the Reels tab, or from a profile or Search, moves on as Instagram decides, whichever switches are on. */
    @Test
    public void theReelsTabsAutoScrollIsLeftAlone() {
        Settings.PLAY_ONE_REEL.save(true);
        for (Source source : new Source[] {Source.CLIPS_TAB, Source.CLIPS_TAB_HOMECOMING, Source.PROFILE, Source.HASHTAG, Source.SONG}) {
            assertEquals(source + " with the stop on", 1, ReelScrolling.autoScroll(source));
        }
        Settings.PLAY_ONE_REEL.save(false);
        for (Source source : playedAlone()) {
            assertEquals(source + " with Play only the reel you opened off", 1, ReelScrolling.autoScroll(source));
        }
    }

    /** With Swipe through a chat's reels on, a chat's viewer keeps its swipe and auto scroll, and the rest stay held. */
    @Test
    public void aChatsReelsKeepTheirSwipeWhileSwipeThroughAChatsReelsIsOn() {
        Settings.STOP_REELS_SCROLLING.save(false);
        Settings.PLAY_ONE_REEL.save(true);
        Settings.SWIPE_CHAT_REELS.save(true);
        Object chat = new Object();
        assertEquals("a chat's viewer at setup", 1, ReelScrolling.pager(chat, Source.DIRECT));
        assertEquals("a chat's viewer turned back on", 1, ReelScrolling.userInput(chat, 1));
        assertEquals("a chat's auto scroll", 1, ReelScrolling.autoScroll(Source.DIRECT));
        assertEquals("a post on Home", 0, ReelScrolling.pager(new Object(), Source.FEED_TIMELINE));
        assertEquals("a suggested unit's auto scroll", 0, ReelScrolling.autoScroll(Source.CLIPS_NETEGO));

        Settings.SWIPE_CHAT_REELS.save(false);
        assertEquals("switched off, the chat's viewer is held", 0, ReelScrolling.userInput(chat, 1));
        assertEquals("switched off, a chat's auto scroll is held", 0, ReelScrolling.autoScroll(Source.DIRECT));
    }

    /** The pull-down layout around a viewer Play only the reel you opened holds lets touches by, and the Reels tab's pulls. */
    @Test
    public void playOneReelPullsOnlyAroundItsOwnViewers() {
        Settings.STOP_REELS_SCROLLING.save(false);
        Settings.PLAY_ONE_REEL.save(true);
        FrameLayout tab = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout home = new FrameLayout(RuntimeEnvironment.getApplication());
        ReelScrolling.pager(inside(tab), Source.CLIPS_TAB);
        ReelScrolling.pager(inside(home), Source.FEED_TIMELINE_POST_TAP);
        assertEquals("the Reels tab's layout", 1, ReelScrolling.pull(tab));
        assertEquals("the layout around a post on Home's viewer", 0, ReelScrolling.pull(home));
        assertEquals("no layout", 1, ReelScrolling.pull(null));

        Settings.PLAY_ONE_REEL.save(false);
        assertEquals("switched off, it pulls again", 1, ReelScrolling.pull(home));
    }

    /** The switch starts off, and off, paused or asked before the settings are read, those viewers scroll and auto scroll as before. */
    @Test
    public void playOneReelOffPausedAndUnreadyHoldsNothing() {
        Settings.STOP_REELS_SCROLLING.save(false);
        assertEquals(Boolean.FALSE, Settings.PLAY_ONE_REEL.defaultValue);
        assertPlayedAloneFree("off");

        Settings.PLAY_ONE_REEL.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertPlayedAloneFree("paused");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertPlayedAloneFree("unready"));

        assertEquals("on again", 0, ReelScrolling.autoScroll(Source.FEED_TIMELINE));
    }

    private void assertPlayedAloneFree(String what) {
        for (Source source : playedAlone()) {
            Object pager = new Object();
            assertEquals(what + ": " + source + " at setup", 1, ReelScrolling.pager(pager, source));
            assertEquals(what + ": " + source + " turned back on", 1, ReelScrolling.userInput(pager, 1));
            assertEquals(what + ": " + source + " auto scroll", 1, ReelScrolling.autoScroll(source));
        }
    }

    /** An auto scroll check that throws lets Instagram decide and says which hook threw. */
    @Test
    public void aThrowingAutoScrollCheckLetsInstagramDecideAndIsReported() {
        assertEquals(1, ReelScrolling.autoScroll(Source.FEED_TIMELINE, THROWS));
        String missing = HookStatus.missing(FamilyNames.REEL_SCROLLING).toString();
        assertTrue(missing, missing.contains("'" + ReelScrolling.AUTO_SCROLL + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    private void assertStock(String what) {
        assertEquals(what + ": the pager at setup", 1, ReelScrolling.pager(reels, Source.CLIPS_TAB));
        assertEquals(what + ": Instagram turning it on", 1, ReelScrolling.userInput(reels, 1));
        assertEquals(what + ": Instagram turning it off", 0, ReelScrolling.userInput(reels, 0));
        assertEquals(what + ": a pull", 1, ReelScrolling.pull(null));
    }
}
