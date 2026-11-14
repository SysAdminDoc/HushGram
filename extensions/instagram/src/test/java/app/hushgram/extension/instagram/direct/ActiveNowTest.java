/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

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

/** What Shorter Active now (#683) answers for Instagram's Active now limit. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ActiveNowTest {
    /** Instagram 454's own limit: 390 seconds. */
    private static final long STOCK = 390_000L;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHORT_ACTIVE_NOW.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SHORT_ACTIVE_NOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheLimitIsAMinute() {
        assertEquals(60_000L, ActiveNow.window(STOCK));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ActiveNow.STEP + " 1"));
    }

    @Test
    public void aLimitAlreadyAMinuteOrLessStaysAndIsNotCounted() {
        assertEquals(60_000L, ActiveNow.window(60_000L));
        assertEquals(30_000L, ActiveNow.window(30_000L));
        String report = HookStatus.report().toString();
        assertFalse("only a changed limit is counted: " + report, report.contains(ActiveNow.STEP + " "));
    }

    @Test
    public void offToStartAndOffKeepsInstagramsLimit() {
        Settings.SHORT_ACTIVE_NOW.resetToDefault();
        assertFalse(Settings.SHORT_ACTIVE_NOW.get());
        assertEquals(STOCK, ActiveNow.window(STOCK));
    }

    @Test
    public void pausedUnreadyOrThrowingKeepInstagramsLimit() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(STOCK, ActiveNow.window(STOCK));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals(STOCK, ActiveNow.window(STOCK)));

        assertEquals(STOCK, ActiveNow.window(STOCK, () -> {
            throw new IllegalStateException("settings went away");
        }));
        assertFalse(HookStatus.missing(FamilyNames.SHORT_ACTIVE_NOW).isEmpty());
    }
}
