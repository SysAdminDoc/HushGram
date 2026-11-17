/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When the posts you looked at in Home stay out of the request Instagram sends to feed/timeline/ (#664). */
@RunWith(RobolectricTestRunner.class)
public class FeedViewInfoTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for the record a Home request reads; the hook never looks inside it. */
    private final Object record = new Object();

    @Before
    public void switchOn() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.DONT_SEND_FEED_VIEW_HISTORY.save(true);
        HookStatus.clear();
    }

    @After
    public void switchBack() {
        Settings.DONT_SEND_FEED_VIEW_HISTORY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void itStartsOff() {
        Settings.DONT_SEND_FEED_VIEW_HISTORY.resetToDefault();
        assertSame("off to start, the record goes in", record, FeedViewInfo.held(record));
    }

    @Test
    public void withTheSwitchOnTheRecordIsLeftOutAndCounted() {
        FeedFilterCounters.snapshotAndClear();
        assertNull(FeedViewInfo.held(record));
        List<String> report = FeedFilterCounters.report();
        assertTrue(report.toString(), report.toString().contains(FeedViewInfo.ROUTE));
        assertTrue(report.toString(), report.toString().contains(FeedViewInfo.LEFT_OUT));
        assertTrue("the hook counts as reached: " + HookStatus.report(),
                HookStatus.report().toString().contains(FamilyNames.FEED_VIEW_HISTORY));
    }

    @Test
    public void withTheSwitchOffTheRecordGoesIn() {
        Settings.DONT_SEND_FEED_VIEW_HISTORY.save(false);
        assertSame(record, FeedViewInfo.held(record));
    }

    @Test
    public void pausedTheRecordGoesIn() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(record, FeedViewInfo.held(record));
    }

    /** Before the settings are read, as when Instagram builds a request before its onCreate, nothing changes. */
    @Test
    public void beforeTheSettingsAreReadyTheRecordGoesIn() {
        SettingsContextRule.withoutContext(() -> assertSame("no context", record, FeedViewInfo.held(record)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame("pause undecided", record, FeedViewInfo.held(record)));
        assertNull("back on", FeedViewInfo.held(record));
    }

    /** A request with no record stays without one, and the hook still counts as reached. */
    @Test
    public void noRecordStaysNone() {
        assertNull(FeedViewInfo.held(null));
        assertTrue("the hook counts as reached: " + HookStatus.report(),
                HookStatus.report().toString().contains(FamilyNames.FEED_VIEW_HISTORY));
    }
}
