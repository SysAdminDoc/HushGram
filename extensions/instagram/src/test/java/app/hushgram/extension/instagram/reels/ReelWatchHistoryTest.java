/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;

/** When a watched reel stays out of the batch Instagram posts to clips/write_seen_state/. */
@RunWith(RobolectricTestRunner.class)
public class ReelWatchHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void switchOn() {
        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(true);
    }

    @After
    public void switchBack() {
        Settings.DONT_SEND_REEL_WATCH_HISTORY.resetToDefault();
    }

    @Test
    public void withTheSwitchOnAWatchedReelIsHeldBackAndCounted() {
        FeedFilterCounters.snapshotAndClear();
        assertTrue(ReelWatchHistory.holdBack());
        List<String> report = FeedFilterCounters.report();
        assertTrue(report.toString(), report.toString().contains(ReelWatchHistory.ROUTE));
        assertTrue(report.toString(), report.toString().contains(ReelWatchHistory.LEFT_OUT));
    }

    @Test
    public void withTheSwitchOffTheReelGoesIn() {
        Settings.DONT_SEND_REEL_WATCH_HISTORY.save(false);
        try {
            assertFalse(ReelWatchHistory.holdBack());
        } finally {
            Settings.DONT_SEND_REEL_WATCH_HISTORY.save(true);
        }
    }
}
