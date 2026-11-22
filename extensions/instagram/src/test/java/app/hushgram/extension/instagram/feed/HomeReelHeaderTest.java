/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Reels on Home below the name (#788): Home's tall reel cards get the plain post layout. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HomeReelHeaderTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HOME_REELS_BELOW_NAME.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HOME_REELS_BELOW_NAME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void startsOff() {
        Settings.HOME_REELS_BELOW_NAME.resetToDefault();
        assertFalse(HomeReelHeader.belowName("feed_timeline"));
    }

    /** Both of Home's pages get the name above the video, and the hook says it ran. */
    @Test
    public void homeAndItsOlderPostsGetTheNameAbove() {
        assertTrue(HomeReelHeader.belowName("feed_timeline"));
        assertTrue(HomeReelHeader.belowName("feed_timeline_older"));

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HomeReelHeader.COUNT_BELOW));
        assertTrue(HookStatus.missing(FamilyNames.HOME_REELS_BELOW_NAME).toString(),
                HookStatus.missing(FamilyNames.HOME_REELS_BELOW_NAME).isEmpty());
    }

    /** Explore, search, profiles, the Reels tab and an unknown module keep Instagram's own layout. */
    @Test
    public void everyOtherPlaceKeepsInstagramsLayout() {
        for (String module : new String[] {"explore_popular", "clips_viewer_clips_tab", "profile", "search_typeahead",
                "feed_contextual_chain", "FEED_TIMELINE", "", null}) {
            assertFalse(String.valueOf(module), HomeReelHeader.belowName(module));
        }
    }

    /** Off, paused or before the settings are read, Home keeps Instagram's layout too. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsLayout() {
        Settings.HOME_REELS_BELOW_NAME.save(false);
        assertFalse(HomeReelHeader.belowName("feed_timeline"));
        Settings.HOME_REELS_BELOW_NAME.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(HomeReelHeader.belowName("feed_timeline"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(HomeReelHeader.belowName("feed_timeline")));
        assertTrue(HomeReelHeader.belowName("feed_timeline"));
    }

    /** A switch that throws keeps Instagram's layout and says which hook threw. */
    @Test
    public void aThrowingSwitchKeepsInstagramsLayoutAndIsReported() {
        assertFalse(HomeReelHeader.belowName("feed_timeline", () -> {
            throw new IllegalStateException("broken switch");
        }));

        String missing = HookStatus.missing(FamilyNames.HOME_REELS_BELOW_NAME).toString();
        assertTrue(missing, missing.contains(HomeReelHeader.HOOK));
    }
}
