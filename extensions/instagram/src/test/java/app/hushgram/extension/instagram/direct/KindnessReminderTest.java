/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
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

/** When the banner reminding you to be respectful stays out of a chat (#588), and when Instagram keeps its own answer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KindnessReminderTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Settings.HIDE_KINDNESS_REMINDER.save(true);
    }

    @After
    public void restore() {
        Settings.HIDE_KINDNESS_REMINDER.resetToDefault();
        BaseSettings.PAUSED.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void onKeepsItOutOfAChatWithOnePerson() {
        assertEquals(0, KindnessReminder.mayShow(1));
    }

    @Test
    public void onKeepsItOutOfAPublicChat() {
        assertTrue("above what the banner needs", KindnessReminder.timesShown(0) >= 1);
    }

    @Test
    public void offLeavesInstagramsAnswer() {
        Settings.HIDE_KINDNESS_REMINDER.save(false);
        assertEquals(1, KindnessReminder.mayShow(1));
        assertEquals(0, KindnessReminder.timesShown(0));
    }

    @Test
    public void pausedLeavesInstagramsAnswer() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(1, KindnessReminder.mayShow(1));
        assertEquals(0, KindnessReminder.timesShown(0));
    }

    @Test
    public void aChatThatWouldNotShowItIsNotCounted() {
        assertEquals(0, KindnessReminder.mayShow(0));
        assertEquals(1, KindnessReminder.timesShown(1));
        assertEquals(5, KindnessReminder.timesShown(5));
        String report = HookStatus.report().toString();
        assertTrue(report, !report.contains(KindnessReminder.KEPT_OUT));
    }

    @Test
    public void eachBannerKeptOutIsCounted() {
        KindnessReminder.mayShow(1);
        KindnessReminder.timesShown(0);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FamilyNames.KINDNESS_REMINDER));
        assertTrue(report, report.contains(KindnessReminder.KEPT_OUT + " 2"));
    }
}
