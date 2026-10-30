/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Stop background jobs" patch.
 *
 * <p>Instagram keeps working while you aren't using it: Meta's own push service (FBNS, over MQTT,
 * in the :mqtt and :fbns processes) and Firebase's push wake it all day, even with its
 * notifications turned off; a job prefetches the feed; and WorkManager wakes it for periodic
 * checks, one of which logs which other Meta apps you used. Each of the three groups here has its
 * own switch, and turning a group off works the way Instagram turns parts of itself on and off:
 * the group's services and receivers are set disabled in the package manager, so Android neither
 * starts nor wakes Instagram for them, its pending jobs are cancelled and its periodic work is
 * marked cancelled in WorkManager's database.
 *
 * <p>The push group only goes off while Android says Instagram's notifications are off, so
 * turning notifications back on brings push back the next time Instagram comes to the front.
 *
 * <p>Instagram turns some of these components on itself, and schedules jobs for them. Every one of
 * its component calls already goes through {@link AppIcon}, for Pick the app icon, which asks
 * {@link #keepsHeld} first, and the patch routes its {@code JobScheduler.schedule} and
 * {@code JobScheduler.enqueue} calls through {@link #schedule} and {@link #enqueue}: while a
 * component is held off, a request to turn it on is kept for later instead of applied, and a job
 * for it is answered as scheduled without scheduling it, since Android throws for a job whose
 * service is disabled. Everything else goes to Android unchanged. It also asks {@link #skipWork}
 * first in each of the methods that enqueue the periodic work.
 *
 * <p>What a component was before it was held off is saved, so it goes back to exactly that when
 * its switch goes off, HushGram is paused, or a build without this patch starts.
 */
public final class BackgroundWork {
    /** A set of components and periodic work that one switch turns off. */
    enum Group {
        /** Meta's push service and Firebase's, while Instagram's notifications are off. */
        PUSH,
        /** The background feed prefetch. */
        REFRESH,
        /** The family apps usage log and the startup tracking service. */
        TRACKING,
    }

    /** The services and receivers each group turns off, by the class names the manifest keeps. */
    static final Map<String, Group> COMPONENTS;

    /** The periodic work each group stops, by the unique name Instagram enqueues it with. */
    static final Map<String, Group> WORK_NAMES;

    /** The workers each group stops, by the class names WorkManager stores and builds them by. */
    static final Map<String, Group> WORKERS;

    static {
        Map<String, Group> components = new LinkedHashMap<>();
        components.put("com.facebook.rti.push.service.FbnsService", Group.PUSH);
        components.put("com.facebook.rti.pushv2.inapp.InappFbnsService", Group.PUSH);
        components.put("com.instagram.push.FbnsInitBroadcastReceiver", Group.PUSH);
        components.put("com.instagram.notifications.push.fbns.IgPushSdkFbnsReceiverShim", Group.PUSH);
        components.put("com.instagram.notifications.push.fcm.FcmListenerService", Group.PUSH);
        components.put("com.facebook.pushlite.tokenprovider.fcm.PushLiteFcmListenerService", Group.PUSH);
        components.put("com.instagram.notifications.push.fcm.RegisterPushPeriodicJobService", Group.PUSH);
        components.put("com.instagram.jobscheduler.bgfetch.scheduler.IgBgFetchSchedulerService", Group.REFRESH);
        components.put("com.instagram.jobscheduler.bgfetch.scheduler.IgBgFetchTaskLifeService", Group.REFRESH);
        components.put("com.instagram.util.offline.BackgroundPrefetchJobService", Group.REFRESH);
        components.put("com.instagram.util.startup.tracking.TaskLifeDetectingService", Group.TRACKING);
        COMPONENTS = Collections.unmodifiableMap(components);

        Map<String, Group> names = new LinkedHashMap<>();
        names.put("ig_emergency_push_periodic_fetch_work", Group.PUSH);
        names.put("FCMPushabilityCheckJob", Group.PUSH);
        names.put("FBNSPushabilityCheckJob", Group.PUSH);
        names.put("ig_family_app_last_used_states_logging_background_work", Group.TRACKING);
        WORK_NAMES = Collections.unmodifiableMap(names);

        Map<String, Group> workers = new LinkedHashMap<>();
        workers.put("com.instagram.emergencypush.EmergencyPushPeriodicFetchWorker", Group.PUSH);
        workers.put("com.facebook.pushlite.pushability_healthcheck.PushabilityCheckerWorker", Group.PUSH);
        workers.put("com.instagram.partneranalytics.igfamilyapplastusedstates.IGFamilyAppLastUsedStatesLogWorker",
                Group.TRACKING);
        WORKERS = Collections.unmodifiableMap(workers);
    }

    /** Where what each held component was is kept: its class name, and the state to put back. */
    static final String PREFERENCES = "hushgram_background_work";

    /** WorkManager's database, its WorkSpec states, and the one it is given here. */
    static final String WORK_DATABASE = "androidx.work.workdb";
    static final int WORK_ENQUEUED = 0;
    static final int WORK_BLOCKED = 4;
    static final int WORK_CANCELLED = 5;

    /** The components held off now, read from the saved list the first time a stand-in asks. */
    @Nullable
    private static volatile Set<String> held;
    /** The groups the last pass turned off, or -1 before the first pass: a bit per {@link Group}. */
    private static volatile int applied = -1;
    private static volatile boolean watching;

    private BackgroundWork() {
    }

    /** Whether [group] is off, given the switches and whether Instagram's notifications are on. Pure. */
    static boolean off(Group group, boolean push, boolean notificationsOn, boolean refresh, boolean tracking) {
        switch (group) {
            case PUSH:
                return push && !notificationsOn;
            case REFRESH:
                return refresh;
            case TRACKING:
                return tracking;
            default:
                return false;
        }
    }

    /** The groups that are off as a bit per {@link Group}, the way {@link #applied} keeps them. Pure. */
    static int groupsOff(boolean inBuild, boolean push, boolean notificationsOn, boolean refresh, boolean tracking) {
        if (!inBuild) return 0;
        int bits = 0;
        for (Group group : Group.values()) {
            if (off(group, push, notificationsOn, refresh, tracking)) bits |= 1 << group.ordinal();
        }
        return bits;
    }

    static boolean isOff(int bits, Group group) {
        return (bits & (1 << group.ordinal())) != 0;
    }

    /**
     * Called once the application has started, in every process. In the main process it watches
     * for Instagram coming to the front, since notifications may have been turned on or off while
     * it was away, and holds or lets go of the components the switches say. Never throws.
     */
    public static void start(Context context) {
        try {
            if (!Utils.isMainProcess()) return;
            if (SettingsStatus.backgroundWork() && context instanceof Application && !watching) {
                watching = true;
                ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
            }
            final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            Utils.runOnBackgroundThread(() -> apply(app, true));
        } catch (Throwable failure) {
            Logger.printException(() -> "Background jobs: could not start", failure);
        }
    }

    /** Applies a changed switch once the change is saved. For the settings screen's rows. */
    public static void switchChanged(Context context) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnMainThread(() -> Utils.runOnBackgroundThread(() -> apply(app, true)));
    }

    /**
     * Holds off each component whose group is off and lets go of each whose group isn't, cancels
     * the held ones' pending jobs, and marks their periodic work cancelled. With [force] false it
     * does nothing while the groups that are off haven't changed since the last pass. Off the main
     * thread. Never throws.
     */
    static void apply(Context context, boolean force) {
        apply(context, force, SettingsStatus.backgroundWork());
    }

    /** {@link #apply(Context, boolean)}, told whether the patch is in this build. */
    static synchronized void apply(Context context, boolean force, boolean inBuild) {
        try {
            // Unread switches would let go of everything held, only to hold it again a moment later.
            if (inBuild && !Utils.settingsReady()) return;
            int bits = groupsOff(inBuild,
                    inBuild && Settings.STOP_PUSH_WHEN_SILENT.get(), notificationsOn(context),
                    inBuild && Settings.STOP_BACKGROUND_REFRESH.get(),
                    inBuild && Settings.STOP_BACKGROUND_TRACKING.get());
            if (!force && bits == applied) return;
            if (inBuild) HookStatus.invoked(FamilyNames.BACKGROUND_WORK);
            SharedPreferences saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            PackageManager packages = context.getPackageManager();
            Set<String> nowHeld = new HashSet<>(saved.getAll().keySet());
            SharedPreferences.Editor edit = saved.edit();
            for (Map.Entry<String, Group> entry : COMPONENTS.entrySet()) {
                String name = entry.getKey();
                ComponentName component = new ComponentName(context.getPackageName(), name);
                boolean wanted = isOff(bits, entry.getValue());
                try {
                    if (wanted) {
                        if (!nowHeld.contains(name)) {
                            edit.putInt(name, packages.getComponentEnabledSetting(component));
                            nowHeld.add(name);
                        }
                        if (packages.getComponentEnabledSetting(component)
                                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                            packages.setComponentEnabledSetting(component,
                                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                        }
                        stopService(context, component);
                    } else if (nowHeld.contains(name)) {
                        int before = saved.getInt(name, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                        packages.setComponentEnabledSetting(component, before, PackageManager.DONT_KILL_APP);
                        edit.remove(name);
                        nowHeld.remove(name);
                    }
                } catch (Throwable failure) {
                    // Missing from this build, most likely. The others still go ahead.
                    Logger.printDebug(() -> "Background jobs: left " + name + " alone: " + failure);
                }
            }
            edit.apply();
            held = Collections.unmodifiableSet(new HashSet<>(nowHeld));
            cancelJobs(context, nowHeld);
            List<String> workers = new ArrayList<>();
            for (Map.Entry<String, Group> worker : WORKERS.entrySet()) {
                if (isOff(bits, worker.getValue())) workers.add(worker.getKey());
            }
            if (!workers.isEmpty() && bits != applied) cancelWork(context, workers);
            applied = bits;
            final int done = bits;
            Logger.printDebug(() -> "Background jobs: groups off " + Integer.toBinaryString(done)
                    + ", holding " + held.size() + " components");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_WORK, "apply", failure);
        }
    }

    /** Whether Android lets Instagram post notifications. True when it can't tell, so push stays. */
    static boolean notificationsOn(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager == null || manager.areNotificationsEnabled();
        } catch (Throwable failure) {
            return true;
        }
    }

    /** Stops a held service if it's running, so its process can go. Receivers have nothing to stop. */
    private static void stopService(Context context, ComponentName component) {
        if (!component.getClassName().contains("Service")) return;
        try {
            context.stopService(new Intent().setComponent(component));
        } catch (Throwable ignored) {
            // Not running, or not a service after all.
        }
    }

    /** Cancels Instagram's pending jobs that would run one of the [names] services. */
    private static void cancelJobs(Context context, Set<String> names) {
        if (names.isEmpty()) return;
        try {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            for (JobInfo job : jobs.getAllPendingJobs()) {
                ComponentName service = job.getService();
                if (service != null && names.contains(service.getClassName())) jobs.cancel(job.getId());
            }
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Background jobs: could not cancel pending jobs: " + failure);
        }
    }

    /**
     * Marks the enqueued periodic work of [workers] cancelled in WorkManager's database and cancels
     * the job it has waiting for each, the way WorkManager cancels work itself. Work that is running
     * is left to finish. The enqueue methods ask {@link #skipWork} first, so it isn't enqueued again
     * until the switch goes off.
     */
    static void cancelWork(Context context, List<String> workers) {
        File file = context.getDatabasePath(WORK_DATABASE);
        if (file == null || !file.isFile()) return;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)) {
            JobScheduler jobs = context.getSystemService(JobScheduler.class);
            String[] classes = workers.toArray(new String[0]);
            List<String> ids = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("SELECT id FROM WorkSpec WHERE state IN (" + WORK_ENQUEUED + ", "
                    + WORK_BLOCKED + ") AND worker_class_name IN (" + placeholders(classes.length) + ")", classes)) {
                while (cursor.moveToNext()) ids.add(cursor.getString(0));
            }
            for (String id : ids) {
                try (Cursor cursor = db.rawQuery("SELECT system_id FROM SystemIdInfo WHERE work_spec_id = ?",
                        new String[] {id})) {
                    while (cursor.moveToNext()) {
                        if (jobs != null) jobs.cancel(cursor.getInt(0));
                    }
                }
                db.execSQL("UPDATE WorkSpec SET state = " + WORK_CANCELLED + " WHERE id = ?", new Object[] {id});
            }
            Logger.printDebug(() -> "Background jobs: cancelled " + ids.size() + " periodic work items");
        } catch (Throwable failure) {
            // Busy, or a WorkManager that keeps its work differently: the next start tries again.
            Logger.printDebug(() -> "Background jobs: could not cancel periodic work: " + failure);
        }
    }

    static String placeholders(int count) {
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < count; i++) marks.append(i == 0 ? "?" : ", ?");
        return marks.toString();
    }

    /**
     * Asked first in each method that enqueues one of {@link #WORK_NAMES}, with that name. True
     * makes it return without enqueueing. False while its group is on, for a name it doesn't know,
     * or with the settings not ready. Never throws.
     */
    public static boolean skipWork(String name) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_WORK);
            Group group = WORK_NAMES.get(name);
            if (group == null || !Utils.settingsReady()) return false;
            Context context = Utils.getContext();
            return context != null && off(group, Settings.STOP_PUSH_WHEN_SILENT.get(), notificationsOn(context),
                    Settings.STOP_BACKGROUND_REFRESH.get(), Settings.STOP_BACKGROUND_TRACKING.get());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_WORK, "work", failure);
            return false;
        }
    }

    /** Stands in for each of Instagram's JobScheduler.schedule calls. A job for a held service isn't scheduled. */
    public static int schedule(JobScheduler jobs, JobInfo job) {
        if (heldService(job)) return JobScheduler.RESULT_SUCCESS;
        return jobs.schedule(job);
    }

    /** Stands in for each of Instagram's JobScheduler.enqueue calls, the same way as {@link #schedule}. */
    public static int enqueue(JobScheduler jobs, JobInfo job, JobWorkItem work) {
        if (heldService(job)) return JobScheduler.RESULT_SUCCESS;
        return jobs.enqueue(job, work);
    }

    /**
     * Asked by {@link AppIcon} for each of Instagram's calls turning a component on or off. True for a
     * request to turn on a component held off, which is kept as the state to put back instead of
     * applied. Never throws.
     */
    static boolean keepsHeld(@Nullable ComponentName component, int state) {
        try {
            if (component == null || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return false;
            if (!isHeld(component)) return false;
            HookStatus.invoked(FamilyNames.BACKGROUND_WORK);
            // The state Instagram asked for is the one to put back. Written from the main process
            // only, the one that writes the saved list.
            Context context = Utils.getContext();
            if (context != null && Utils.isMainProcess()) {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putInt(component.getClassName(), state).apply();
            }
            Logger.printDebug(() -> "Background jobs: kept " + component.getClassName() + " off");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_WORK, "component", failure);
            return false;
        }
    }

    private static boolean heldService(@Nullable JobInfo job) {
        try {
            if (job == null || !isHeld(job.getService())) return false;
            HookStatus.invoked(FamilyNames.BACKGROUND_WORK);
            Logger.printDebug(() -> "Background jobs: skipped a job for " + job.getService().getClassName());
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.BACKGROUND_WORK, "job", failure);
            return false;
        }
    }

    /** Whether [component] is one of Instagram's own that this holds off now. */
    static boolean isHeld(@Nullable ComponentName component) {
        if (component == null || !COMPONENTS.containsKey(component.getClassName())) return false;
        Context context = Utils.getContext();
        if (context == null || !context.getPackageName().equals(component.getPackageName())) return false;
        Set<String> current = held;
        if (current == null) {
            // Another process, or a call before the first pass: the saved list says.
            current = Collections.unmodifiableSet(new HashSet<>(
                    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getAll().keySet()));
            held = current;
        }
        return current.contains(component.getClassName());
    }

    /** Forgets what this process read and applied. For tests. */
    static void resetForTests() {
        held = null;
        applied = -1;
    }

    /** Looks again each time Instagram comes to the front, for notifications turned on or off meanwhile. */
    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            final Context app = activity.getApplicationContext();
            Utils.runOnBackgroundThread(() -> apply(app, false));
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
