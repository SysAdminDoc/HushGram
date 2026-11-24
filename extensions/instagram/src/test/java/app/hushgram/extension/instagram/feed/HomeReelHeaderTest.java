/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
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

    /**
     * A reel below the name on Home keeps its own shape (#788): 9:16 instead of Instagram's 4:5, a 7:10
     * video its 7:10, on the first page and the older posts after it.
     */
    @Test
    public void aTallReelKeepsItsOwnShape() {
        assertEquals(9f / 16f, HomeReelHeader.wholeReel(0.8f, 1080f / 1920f, HomeReelHeader.HOME_MODULE), 0.0001f);
        assertEquals(0.7f, HomeReelHeader.wholeReel(0.8f, 0.7f, HomeReelHeader.OLDER_MODULE), 0.0001f);
        assertEquals(9f / 16f, HomeReelHeader.wholeReel(0.78f, 1080f / 1920f, HomeReelHeader.HOME_MODULE), 0.0001f);

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HomeReelHeader.COUNT_WHOLE));
    }

    /** Media narrower than a reel stops at 9:16, so nothing runs taller than a reel would. */
    @Test
    public void nothingGoesNarrowerThanAReel() {
        assertEquals(9f / 16f, HomeReelHeader.wholeReel(0.8f, 0.25f, HomeReelHeader.HOME_MODULE), 0.0001f);
    }

    /**
     * Off, paused, in any feed but Home's, already as wide as Instagram's shape, or with no shape of its
     * own, Instagram's shape stays.
     */
    @Test
    public void everyOtherAnswerStays() {
        String home = HomeReelHeader.HOME_MODULE;
        assertEquals(1f, HomeReelHeader.wholeReel(1f, 1f, home), 0f);
        assertEquals(1.91f, HomeReelHeader.wholeReel(1.91f, 2.4f, home), 0f);
        assertEquals(16f / 9f, HomeReelHeader.wholeReel(16f / 9f, 16f / 9f, home), 0f);
        assertEquals(0.8f, HomeReelHeader.wholeReel(0.8f, 0.8f, home), 0f);
        assertEquals(0.8f, HomeReelHeader.wholeReel(0.8f, 0f, home), 0f);
        assertEquals(0.8f, HomeReelHeader.wholeReel(0.8f, -0.5f, home), 0f);
        assertEquals(0.8f, HomeReelHeader.wholeReel(0.8f, Float.NaN, home), 0f);
        assertTrue(Float.isNaN(HomeReelHeader.wholeReel(Float.NaN, 0.5625f, home)));
        for (String elsewhere : new String[] {"explore_popular_major_unit", "clips_viewer_clips_tab", "profile", "feed_contextual_profile", "", null}) {
            assertEquals(elsewhere, 0.8f, HomeReelHeader.wholeReel(0.8f, 0.5625f, elsewhere), 0f);
        }

        Settings.HOME_REELS_BELOW_NAME.save(false);
        assertEquals(0.8f, HomeReelHeader.wholeReel(0.8f, 0.5625f, home), 0f);
        Settings.HOME_REELS_BELOW_NAME.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0.8f, HomeReelHeader.wholeReel(0.8f, 0.5625f, home), 0f);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals(0.8f, HomeReelHeader.wholeReel(0.8f, 0.5625f, home), 0f));
    }

    /** A switch that throws keeps Instagram's shape and says which hook threw. */
    @Test
    public void aThrowingSwitchKeepsInstagramsShape() {
        assertEquals(0.8f, HomeReelHeader.wholeReel(0.8f, 0.5625f, HomeReelHeader.HOME_MODULE, () -> {
            throw new IllegalStateException("broken switch");
        }), 0f);
        String missing = HookStatus.missing(FamilyNames.HOME_REELS_BELOW_NAME).toString();
        assertTrue(missing, missing.contains(HomeReelHeader.SHAPE_HOOK));
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
