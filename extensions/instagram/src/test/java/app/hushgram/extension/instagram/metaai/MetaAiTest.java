/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.metaai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.FrameLayout;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import app.hushgram.extension.instagram.feed.FeedSuggestions;
import app.hushgram.extension.instagram.reels.FeedReels;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Hide Meta AI answers for Meta AI's search flags and which feed items it takes out. */
@RunWith(RobolectricTestRunner.class)
public class MetaAiTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Shaped like Instagram 449's feed item kinds, a few of them. */
    enum Kind {
        MEDIA, AD, CLIPS_NETEGO, EXPLORE_STORY, THREADS_IN_FEED_UNIT, VIBES_IN_FEED_UNIT,
        HATCH_IMMERSIVE_IN_FEED_UNIT, MEMU_IN_FEED_UNIT
    }

    /** The item's other enum on 449: why the feed was fetched. */
    enum Fetch { COLD_START, PULL_TO_REFRESH }

    enum ComposerButton { META_AI_DISCOVERY, META_AI_INVOCATION, META_AI_VOICE, CAMERA, STICKERS, VOICE_MESSAGE, SEND }

    static final class Item {
        Fetch fetch = Fetch.COLD_START;
        Kind kind;

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    /** Instagram's answer comes in as an int, and any value but zero is yes. */
    @Test
    public void aSearchFlagAnswersOffWhileTheSwitchIsOn() {
        assertFalse(MetaAi.searchFlag(1));
        assertFalse(MetaAi.searchFlag(0));
        assertFalse(MetaAi.searchFlag(-1));
    }

    /** With the switch off Instagram's own answer stands, either way. */
    @Test
    public void withTheSwitchOffInstagramDecides() {
        Settings.HIDE_META_AI_SEARCH.save(false);
        try {
            assertTrue(MetaAi.searchFlag(1));
            assertTrue(MetaAi.searchFlag(0xff));
            assertFalse(MetaAi.searchFlag(0));
        } finally {
            Settings.HIDE_META_AI_SEARCH.save(true);
        }
    }

    /** Meta AI's share target is left out only with its own switch, which starts off, and never while paused. */
    @Test
    public void theShareTargetFollowsItsOwnSwitch() {
        assertFalse("off to start", Settings.HIDE_META_AI_SHARE_TARGET.get());
        assertTrue(MetaAi.shareTarget(1));
        assertFalse(MetaAi.shareTarget(0));
        Settings.HIDE_META_AI_SHARE_TARGET.save(true);
        try {
            assertFalse(MetaAi.shareTarget(1));
            assertFalse(MetaAi.shareTarget(-1));
            assertFalse(MetaAi.shareTarget(0));
            Settings.HIDE_META_AI_SEARCH.save(false);
            assertFalse("the search switch doesn't matter", MetaAi.shareTarget(1));
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertTrue("paused, Instagram decides", MetaAi.shareTarget(1));
        } finally {
            PauseForTests.resume();
            Settings.HIDE_META_AI_SEARCH.save(true);
            Settings.HIDE_META_AI_SHARE_TARGET.resetToDefault();
        }
    }

    /** The Muse card is left out only with its own switch, which starts on, and never while paused. */
    @Test
    public void theMuseBannerFollowsItsOwnSwitch() {
        assertTrue("on to start", Settings.HIDE_META_AI_PROFILE_BANNER.get());
        assertTrue(MetaAi.museBanner());
        Settings.HIDE_META_AI_PROFILE_BANNER.save(false);
        try {
            assertFalse(MetaAi.museBanner());
        } finally {
            Settings.HIDE_META_AI_PROFILE_BANNER.resetToDefault();
        }
        Settings.HIDE_META_AI_SEARCH.save(false);
        try {
            assertTrue("the search switch doesn't matter", MetaAi.museBanner());
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertFalse("paused, Instagram decides", MetaAi.museBanner());
        } finally {
            PauseForTests.resume();
            Settings.HIDE_META_AI_SEARCH.save(true);
        }
    }

    /** The search switch also leaves the results page's Ask a follow-up bar out, and only that switch. */
    @Test
    public void theFollowUpBarFollowsTheSearchSwitch() {
        View stub = new View(RuntimeEnvironment.getApplication());
        assertNull(MetaAi.followUpBar(stub));
        assertNull(MetaAi.followUpBar(null));
        Settings.HIDE_META_AI_POSTS.save(false);
        try {
            assertNull(MetaAi.followUpBar(stub));
        } finally {
            Settings.HIDE_META_AI_POSTS.save(true);
        }
        Settings.HIDE_META_AI_SEARCH.save(false);
        try {
            assertSame(stub, MetaAi.followUpBar(stub));
        } finally {
            Settings.HIDE_META_AI_SEARCH.save(true);
        }
    }

    /** Home's Meta AI button is left out with the search switch; every other name comes back as it was. */
    @Test
    public void onlyHomesMetaAiButtonIsLeftOut() {
        assertNull(MetaAi.homeButton("meta_ai"));
        for (String name : new String[] {"direct", "news", "menu", "hatch", "", null}) {
            assertSame(name, name, MetaAi.homeButton(name));
        }
        Settings.HIDE_META_AI_SEARCH.save(false);
        try {
            assertSame("meta_ai", "meta_ai", MetaAi.homeButton("meta_ai"));
        } finally {
            Settings.HIDE_META_AI_SEARCH.save(true);
        }
    }

    @Test
    public void onlyMetaAiComposerButtonsAnswerHidden() {
        for (ComposerButton button : ComposerButton.values()) {
            assertEquals(button.name(), !MetaAi.COMPOSER_BUTTONS.contains(button.name()), MetaAi.composerButton(button, 1));
            assertFalse(button.name(), MetaAi.composerButton(button, 0));
        }
        assertTrue(MetaAi.composerButton(null, 1));
        assertTrue(MetaAi.composerButton("META_AI_DISCOVERY", 1));
        assertTrue(MetaAi.composerButton(new Object(), 1));
        assertTrue(MetaAi.composerButton(ComposerButton.SEND, -1));
    }

    @Test
    public void theComposerUsesTheSearchSwitchAndKeepsTheNativeFlagWhenOff() {
        Settings.HIDE_META_AI_POSTS.save(false);
        try {
            assertFalse(MetaAi.composerButton(ComposerButton.META_AI_INVOCATION, -1));
        } finally {
            Settings.HIDE_META_AI_POSTS.save(true);
        }
        Settings.HIDE_META_AI_SEARCH.save(false);
        try {
            for (ComposerButton button : ComposerButton.values()) {
                assertTrue(button.name(), MetaAi.composerButton(button, 1));
                assertFalse(button.name(), MetaAi.composerButton(button, 0));
            }
        } finally {
            Settings.HIDE_META_AI_SEARCH.save(true);
        }
    }

    @Test
    public void theOptionalInboxRowUsesTheSearchSwitchAndKeepsItsIdentityWhenOff() {
        Object row = new Object();
        assertNull(MetaAi.inboxRow(row));
        assertNull(MetaAi.inboxRow(null));
        Settings.HIDE_META_AI_POSTS.save(false);
        try {
            assertNull(MetaAi.inboxRow(row));
        } finally {
            Settings.HIDE_META_AI_POSTS.save(true);
        }
        Settings.HIDE_META_AI_SEARCH.save(false);
        try {
            assertSame(row, MetaAi.inboxRow(row));
            assertNull(MetaAi.inboxRow(null));
        } finally {
            Settings.HIDE_META_AI_SEARCH.save(true);
        }
    }

    /** About this reel stays until its own switch is on, which starts off; the other switches don't touch it. */
    @Test
    public void aboutThisReelGoesOnlyWithItsOwnSwitch() {
        Object summary = new Object();
        assertFalse(Settings.HIDE_ABOUT_THIS_REEL.get());
        assertSame(summary, MetaAi.aboutThisReel(summary));
        assertNull(MetaAi.aboutThisReel(null));
        Settings.HIDE_ASK_META_AI.save(true);
        try {
            assertSame("the Ask switch leaves the summary", summary, MetaAi.aboutThisReel(summary));
        } finally {
            Settings.HIDE_ASK_META_AI.save(false);
        }
        Settings.HIDE_ABOUT_THIS_REEL.save(true);
        try {
            assertNull(MetaAi.aboutThisReel(summary));
            assertNull(MetaAi.aboutThisReel(null));
            Settings.HIDE_META_AI_SEARCH.save(false);
            Settings.HIDE_META_AI_POSTS.save(false);
            assertNull("the search and posts switches don't bring it back", MetaAi.aboutThisReel(summary));
        } finally {
            Settings.HIDE_ABOUT_THIS_REEL.save(false);
            Settings.HIDE_META_AI_SEARCH.save(true);
            Settings.HIDE_META_AI_POSTS.save(true);
        }
    }

    /** Whether the summary row ends up holding the Ask Meta AI box after the hook's call. */
    private static boolean addsTheBox() {
        FrameLayout row = new FrameLayout(RuntimeEnvironment.getApplication());
        View box = new View(RuntimeEnvironment.getApplication());
        MetaAi.askMetaAiBox(row, box);
        return row.getChildCount() == 1 && row.getChildAt(0) == box;
    }

    /** The Ask Meta AI box is added as Instagram adds it until its own switch is on. */
    @Test
    public void theAskBoxGoesOnlyWithItsOwnSwitch() {
        assertFalse(Settings.HIDE_ASK_META_AI.get());
        assertTrue(addsTheBox());
        Settings.HIDE_ABOUT_THIS_REEL.save(true);
        try {
            assertTrue("hiding About this reel answers its factory, not the box", addsTheBox());
        } finally {
            Settings.HIDE_ABOUT_THIS_REEL.save(false);
        }
        Settings.HIDE_ASK_META_AI.save(true);
        try {
            assertFalse(addsTheBox());
        } finally {
            Settings.HIDE_ASK_META_AI.save(false);
        }
    }

    /** Paused, both answer as Instagram does. */
    @Test
    public void pausedAboutThisReelAndItsAskBoxStay() {
        Object summary = new Object();
        Settings.HIDE_ABOUT_THIS_REEL.save(true);
        Settings.HIDE_ASK_META_AI.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            assertSame(summary, MetaAi.aboutThisReel(summary));
            assertTrue(addsTheBox());
        } finally {
            PauseForTests.resume();
            Settings.HIDE_ABOUT_THIS_REEL.save(false);
            Settings.HIDE_ASK_META_AI.save(false);
        }
    }

    @Test
    public void everyMetaAiUnitIsTakenOut() {
        for (String name : MetaAi.FEED_UNITS) {
            assertNull(name, MetaAi.filter(new Item(Kind.valueOf(name))));
        }
    }

    /** Posts, ads, the reels row, suggestions and Threads' units are the other patches' to take. */
    @Test
    public void everythingElseStays() {
        for (Kind kind : new Kind[] {Kind.MEDIA, Kind.AD, Kind.CLIPS_NETEGO, Kind.EXPLORE_STORY, Kind.THREADS_IN_FEED_UNIT}) {
            Item item = new Item(kind);
            assertSame(kind.name(), item, MetaAi.filter(item));
        }
        Item unset = new Item(null);
        assertSame(unset, MetaAi.filter(unset));
        assertNull(MetaAi.filter(null));
    }

    @Test
    public void withThePostsSwitchOffMetaAiUnitsStay() {
        Settings.HIDE_META_AI_POSTS.save(false);
        try {
            Item memu = new Item(Kind.MEMU_IN_FEED_UNIT);
            assertSame(memu, MetaAi.filter(memu));
            assertFalse(MetaAi.searchFlag(1));
        } finally {
            Settings.HIDE_META_AI_POSTS.save(true);
        }
    }

    /**
     * With Hide Reels in the feed and Hide suggested posts in too, the three filters answer in any
     * order: an item comes out when any of them takes it.
     */
    @Test
    public void besideTheOtherFeedFiltersAnyOrderAnswersTheSame() {
        Settings.HIDE_FEED_REELS.save(true);
        try {
            for (Kind kind : Kind.values()) {
                Item item = new Item(kind);
                Object metaAiLast = MetaAi.filter(FeedSuggestions.filter(FeedReels.filter(item)));
                Object metaAiFirst = FeedReels.filter(FeedSuggestions.filter(MetaAi.filter(item)));
                boolean kept = kind == Kind.MEDIA || kind == Kind.AD;
                if (kept) {
                    assertSame(kind.name(), item, metaAiLast);
                    assertSame(kind.name(), item, metaAiFirst);
                } else {
                    assertNull(kind.name(), metaAiLast);
                    assertNull(kind.name(), metaAiFirst);
                }
            }
        } finally {
            Settings.HIDE_FEED_REELS.resetToDefault();
        }
    }

    @Test
    public void theReportCountsTheMetaAiUnits() {
        FeedFilterCounters.snapshotAndClear();
        MetaAi.filter(new Item(Kind.VIBES_IN_FEED_UNIT));
        MetaAi.filter(new Item(Kind.MEDIA));
        List<String> report = FeedFilterCounters.report();
        assertTrue(report.toString(), report.toString().contains(MetaAi.ROUTE));
        assertTrue(report.toString(), report.toString().contains("VIBES_IN_FEED_UNIT"));
    }
}
