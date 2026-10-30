/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;

import app.hushgram.extension.instagram.misc.BackgroundWork.Group;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which of Instagram's components and jobs Stop background jobs holds off, and that it puts each back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BackgroundWorkTest {
    private static final String FBNS = "com.facebook.rti.push.service.FbnsService";
    private static final String PREFETCH = "com.instagram.jobscheduler.bgfetch.scheduler.IgBgFetchSchedulerService";
    private static final String TASK_LIFE = "com.instagram.util.startup.tracking.TaskLifeDetectingService";
    private static final String OTHER = "com.instagram.publisher.HeartbeatJobService";
    private static final String FAMILY_WORKER =
            "com.instagram.partneranalytics.igfamilyapplastusedstates.IGFamilyAppLastUsedStatesLogWorker";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private PackageManager packages;
    private JobScheduler jobs;

    @Before
    public void prepare() {
        restore();
        context = RuntimeEnvironment.getApplication();
        packages = context.getPackageManager();
        jobs = context.getSystemService(JobScheduler.class);
        notifications(true);
    }

    @After
    public void restore() {
        Settings.STOP_PUSH_WHEN_SILENT.resetToDefault();
        Settings.STOP_BACKGROUND_REFRESH.resetToDefault();
        Settings.STOP_BACKGROUND_TRACKING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        if (context != null) {
            context.getSharedPreferences(BackgroundWork.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
            context.getDatabasePath(BackgroundWork.WORK_DATABASE).delete();
        }
        BackgroundWork.resetForTests();
    }

    private void notifications(boolean on) {
        shadowOf(context.getSystemService(NotificationManager.class)).setNotificationsEnabled(on);
    }

    private ComponentName component(String name) {
        return new ComponentName(context.getPackageName(), name);
    }

    private int state(String name) {
        return packages.getComponentEnabledSetting(component(name));
    }

    private JobInfo job(int id, String service) {
        return new JobInfo.Builder(id, component(service)).setMinimumLatency(60_000L).build();
    }

    @Test
    public void everySwitchStartsOff() {
        assertEquals(Boolean.FALSE, Settings.STOP_PUSH_WHEN_SILENT.defaultValue);
        assertEquals(Boolean.FALSE, Settings.STOP_BACKGROUND_REFRESH.defaultValue);
        assertEquals(Boolean.FALSE, Settings.STOP_BACKGROUND_TRACKING.defaultValue);
        BackgroundWork.apply(context, true, true);
        for (String name : BackgroundWork.COMPONENTS.keySet()) {
            assertEquals(name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(name));
        }
    }

    /** Push goes off only while notifications are off, and each other group with its own switch alone. */
    @Test
    public void eachGroupAnswersToItsOwnSwitch() {
        assertTrue(BackgroundWork.off(Group.PUSH, true, false, false, false));
        assertFalse(BackgroundWork.off(Group.PUSH, true, true, false, false));
        assertFalse(BackgroundWork.off(Group.PUSH, false, false, true, true));
        assertTrue(BackgroundWork.off(Group.REFRESH, false, true, true, false));
        assertFalse(BackgroundWork.off(Group.REFRESH, true, false, false, true));
        assertTrue(BackgroundWork.off(Group.TRACKING, false, true, false, true));
        assertFalse(BackgroundWork.off(Group.TRACKING, true, false, true, false));
        assertEquals(0, BackgroundWork.groupsOff(false, true, false, true, true));
    }

    @Test
    public void pushIsHeldOffOnlyWhileNotificationsAreOff() {
        Settings.STOP_PUSH_WHEN_SILENT.save(true);
        BackgroundWork.apply(context, true, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(FBNS));

        notifications(false);
        BackgroundWork.apply(context, false, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(FBNS));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(PREFETCH));

        notifications(true);
        BackgroundWork.apply(context, false, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(FBNS));
    }

    /** A component Instagram had turned on goes back to on, not to the manifest's off. */
    @Test
    public void aHeldComponentGoesBackToWhatItWas() {
        packages.setComponentEnabledSetting(component(PREFETCH), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        Settings.STOP_BACKGROUND_REFRESH.save(true);
        BackgroundWork.apply(context, true, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(PREFETCH));

        Settings.STOP_BACKGROUND_REFRESH.save(false);
        BackgroundWork.apply(context, true, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(PREFETCH));
        assertTrue(context.getSharedPreferences(BackgroundWork.PREFERENCES, Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test
    public void pausedOrWithoutThePatchEverythingGoesBack() {
        Settings.STOP_BACKGROUND_TRACKING.save(true);
        BackgroundWork.apply(context, true, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(TASK_LIFE));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        BackgroundWork.apply(context, true, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(TASK_LIFE));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        BackgroundWork.apply(context, true, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(TASK_LIFE));
        // A later build patched without it lets go of what this one held.
        BackgroundWork.resetForTests();
        BackgroundWork.apply(context, true, false);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(TASK_LIFE));
    }

    /** Instagram turning a held component on is kept for later, and the state it asked for is the one put back. */
    @Test
    public void instagramCantTurnAHeldComponentBackOn() {
        Settings.STOP_BACKGROUND_REFRESH.save(true);
        BackgroundWork.apply(context, true, true);
        AppIcon.setComponentEnabledSetting(packages, component(PREFETCH),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(PREFETCH));

        AppIcon.setComponentEnabledSetting(packages, component(OTHER),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(OTHER));

        Settings.STOP_BACKGROUND_REFRESH.save(false);
        BackgroundWork.apply(context, true, true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(PREFETCH));
    }

    @Test
    public void aJobForAHeldServiceIsAnsweredButNotScheduled() {
        assertEquals(JobScheduler.RESULT_SUCCESS, BackgroundWork.schedule(jobs, job(7, PREFETCH)));
        assertEquals(1, jobs.getAllPendingJobs().size());

        Settings.STOP_BACKGROUND_REFRESH.save(true);
        BackgroundWork.apply(context, true, true);
        // The one already pending is cancelled with the hold.
        assertEquals(0, jobs.getAllPendingJobs().size());
        assertEquals(JobScheduler.RESULT_SUCCESS, BackgroundWork.schedule(jobs, job(8, PREFETCH)));
        assertEquals(0, jobs.getAllPendingJobs().size());
        assertEquals(JobScheduler.RESULT_SUCCESS, BackgroundWork.schedule(jobs, job(9, OTHER)));
        assertEquals(1, jobs.getAllPendingJobs().size());
    }

    @Test
    public void enqueueMethodsAskByTheirUniqueName() {
        assertFalse(BackgroundWork.skipWork("ig_family_app_last_used_states_logging_background_work"));
        Settings.STOP_BACKGROUND_TRACKING.save(true);
        assertTrue(BackgroundWork.skipWork("ig_family_app_last_used_states_logging_background_work"));
        assertFalse(BackgroundWork.skipWork("FCMPushabilityCheckJob"));

        Settings.STOP_PUSH_WHEN_SILENT.save(true);
        assertFalse(BackgroundWork.skipWork("FCMPushabilityCheckJob"));
        notifications(false);
        assertTrue(BackgroundWork.skipWork("FCMPushabilityCheckJob"));
        assertTrue(BackgroundWork.skipWork("ig_emergency_push_periodic_fetch_work"));
        assertFalse(BackgroundWork.skipWork("some_other_work"));
        SettingsContextRule.withoutContext(() -> assertFalse(BackgroundWork.skipWork("FCMPushabilityCheckJob")));
    }

    /** Enqueued work of a stopped worker is cancelled with its job; running work and other workers stay. */
    @Test
    public void periodicWorkIsCancelledInWorkManagersDatabase() {
        File file = context.getDatabasePath(BackgroundWork.WORK_DATABASE);
        file.getParentFile().mkdirs();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE WorkSpec (id TEXT NOT NULL PRIMARY KEY, state INTEGER NOT NULL, "
                    + "worker_class_name TEXT NOT NULL)");
            db.execSQL("CREATE TABLE SystemIdInfo (work_spec_id TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, "
                    + "system_id INTEGER NOT NULL, PRIMARY KEY(work_spec_id, generation))");
            db.execSQL("INSERT INTO WorkSpec VALUES ('a', 0, '" + FAMILY_WORKER + "')");
            db.execSQL("INSERT INTO WorkSpec VALUES ('b', 1, '" + FAMILY_WORKER + "')");
            db.execSQL("INSERT INTO WorkSpec VALUES ('c', 0, 'com.instagram.SomeOtherWorker')");
            db.execSQL("INSERT INTO SystemIdInfo VALUES ('a', 0, 41)");
        }
        jobs.schedule(new JobInfo.Builder(41, component("androidx.work.impl.background.systemjob.SystemJobService"))
                .setMinimumLatency(60_000L).build());

        BackgroundWork.cancelWork(context, Arrays.asList(FAMILY_WORKER));

        assertEquals(0, jobs.getAllPendingJobs().size());
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = db.rawQuery("SELECT id, state FROM WorkSpec ORDER BY id", null)) {
            StringBuilder states = new StringBuilder();
            while (cursor.moveToNext()) states.append(cursor.getString(0)).append(cursor.getInt(1));
            assertEquals("a5b1c0", states.toString());
        }
    }

    @Test
    public void noDatabaseIsNothingToCancel() {
        BackgroundWork.cancelWork(context, Arrays.asList(FAMILY_WORKER));
        assertFalse(context.getDatabasePath(BackgroundWork.WORK_DATABASE).exists());
        assertEquals("?, ?, ?", BackgroundWork.placeholders(3));
    }
}
