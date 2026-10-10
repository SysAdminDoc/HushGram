/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When Stop background wake-ups skips Instagram's heartbeat and upload alarms, and when it leaves them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HeartbeatTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private AlarmManager alarms;
    private PendingIntent upload;

    @Before
    public void prepare() {
        restore();
        Context context = RuntimeEnvironment.getApplication();
        alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        upload = PendingIntent.getBroadcast(context, 0, new Intent("action_batch_upload"), PendingIntent.FLAG_IMMUTABLE);
    }

    @After
    public void restore() {
        Settings.STOP_HEARTBEAT.resetToDefault();
        Settings.STOP_UPLOAD_ALARM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private int uploadAlarms() {
        Heartbeat.setUploadAlarm(alarms, AlarmManager.ELAPSED_REALTIME_WAKEUP, 300_000L, upload);
        return shadowOf(alarms).getScheduledAlarms().size();
    }

    /** Both switches start off, so a build with the patch keeps Instagram's alarms until they're turned on. */
    @Test
    public void offToStartBothAlarmsAreSet() {
        assertEquals(Boolean.FALSE, Settings.STOP_HEARTBEAT.defaultValue);
        assertEquals(Boolean.FALSE, Settings.STOP_UPLOAD_ALARM.defaultValue);
        assertFalse(Heartbeat.stop());
        assertEquals(1, uploadAlarms());
        assertEquals(List.of(FamilyNames.STOP_HEARTBEAT + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    @Test
    public void eachSwitchStopsOnlyItsOwnAlarm() {
        Settings.STOP_HEARTBEAT.save(true);
        assertTrue(Heartbeat.stop());
        assertEquals(1, uploadAlarms());

        Settings.STOP_HEARTBEAT.save(false);
        Settings.STOP_UPLOAD_ALARM.save(true);
        assertFalse(Heartbeat.stop());
        alarms.cancel(upload);
        assertEquals(0, uploadAlarms());
    }

    @Test
    public void pausedAndUnreadyLeaveBothAlarms() {
        Settings.STOP_HEARTBEAT.save(true);
        Settings.STOP_UPLOAD_ALARM.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Heartbeat.stop());
        assertEquals(1, uploadAlarms());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(Heartbeat.stop()));
        assertTrue(Heartbeat.stop());
    }
}
