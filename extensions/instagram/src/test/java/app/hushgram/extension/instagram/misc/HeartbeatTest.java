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
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
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

import java.util.ArrayList;
import java.util.Collections;
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
        Settings.STOP_BACKGROUND_FETCH.resetToDefault();
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

    /** Stand-ins for Instagram's prefetch job service and some other job service of the app's. */
    public static final class PrefetchService extends JobService {
        @Override public boolean onStartJob(JobParameters params) { return true; }
        @Override public boolean onStopJob(JobParameters params) { return false; }
    }

    public static final class OtherService extends JobService {
        @Override public boolean onStartJob(JobParameters params) { return true; }
        @Override public boolean onStopJob(JobParameters params) { return false; }
    }

    private JobScheduler queue(int prefetchId, int otherId) {
        Context context = RuntimeEnvironment.getApplication();
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        jobs.cancelAll();
        jobs.schedule(new JobInfo.Builder(prefetchId, new ComponentName(context, PrefetchService.class))
                .setMinimumLatency(1_800_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        jobs.schedule(new JobInfo.Builder(otherId, new ComponentName(context, OtherService.class))
                .setMinimumLatency(60_000L).build());
        return jobs;
    }

    private static List<Integer> pending(JobScheduler jobs) {
        List<Integer> ids = new ArrayList<>();
        for (JobInfo job : jobs.getAllPendingJobs()) ids.add(job.getId());
        Collections.sort(ids);
        return ids;
    }

    /**
     * Off, the scheduler queues as Instagram would and a queued job runs. On, the scheduler queues nothing,
     * cancels the prefetch jobs already queued while leaving the app's other jobs alone, and a prefetch job
     * that still starts ends at once.
     */
    @Test
    public void theFetchSwitchStopsOnlyThePrefetchJobs() {
        assertEquals(Boolean.FALSE, Settings.STOP_BACKGROUND_FETCH.defaultValue);
        JobScheduler jobs = queue(2131434895, 7931);
        assertFalse(Heartbeat.stopBackgroundFetch(PrefetchService.class));
        assertFalse(Heartbeat.skipBackgroundFetch());
        assertEquals(List.of(7931, 2131434895), pending(jobs));

        Settings.STOP_HEARTBEAT.save(true);
        Settings.STOP_UPLOAD_ALARM.save(true);
        assertFalse("the alarm switches leave the prefetch alone", Heartbeat.stopBackgroundFetch(PrefetchService.class));

        Settings.STOP_HEARTBEAT.save(false);
        Settings.STOP_UPLOAD_ALARM.save(false);
        Settings.STOP_BACKGROUND_FETCH.save(true);
        assertTrue(Heartbeat.stopBackgroundFetch(PrefetchService.class));
        assertEquals(List.of(7931), pending(jobs));
        assertTrue(Heartbeat.skipBackgroundFetch());
        assertFalse(Heartbeat.stop());
        assertEquals(1, uploadAlarms());
    }

    @Test
    public void pausedUnreadyOrWithoutAServiceTheFetchSwitchHoldsUp() {
        Settings.STOP_BACKGROUND_FETCH.save(true);
        JobScheduler jobs = queue(2131434895, 7931);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Heartbeat.stopBackgroundFetch(PrefetchService.class));
        assertFalse(Heartbeat.skipBackgroundFetch());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertFalse(Heartbeat.stopBackgroundFetch(PrefetchService.class));
            assertFalse(Heartbeat.skipBackgroundFetch());
        });
        assertEquals(List.of(7931, 2131434895), pending(jobs));

        assertTrue(Heartbeat.stopBackgroundFetch(null));
        assertEquals(List.of(7931, 2131434895), pending(jobs));
        assertEquals(List.of(), HookStatus.missing());
    }
}
