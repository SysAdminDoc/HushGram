/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
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
        Settings.ANSWER_ACCOUNT_CHECKS.resetToDefault();
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

    /**
     * Stands in for Instagram's account authenticator as patched: hasFeatures takes HushGram's answer when
     * there is one, then returns Instagram's own null, which never answers.
     */
    public static final class Authenticator extends AbstractAccountAuthenticator {
        Authenticator(Context context) { super(context); }
        @Override public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) {
            Bundle answer = Heartbeat.answerAccountFeatures();
            if (answer != null) return answer;
            return null;
        }
        @Override public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) { return null; }
        @Override public Bundle addAccount(AccountAuthenticatorResponse response, String accountType, String authTokenType,
                String[] requiredFeatures, Bundle options) { return null; }
        @Override public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) { return null; }
        @Override public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String authTokenType,
                Bundle options) { return null; }
        @Override public String getAuthTokenLabel(String authTokenType) { return null; }
        @Override public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String authTokenType,
                Bundle options) { return null; }
    }

    /**
     * Asks the authenticator through its binder the way Android's account service does when it checks an
     * account for the device owner features, and returns what the service hears back.
     */
    private static List<String> askAccountFeatures() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(ACCOUNT_MANAGER_PERMISSION);
        List<String> heard = new ArrayList<>();
        Class<?> responseType = Class.forName("android.accounts.IAccountAuthenticatorResponse");
        Object response = Proxy.newProxyInstance(responseType.getClassLoader(), new Class<?>[] {responseType}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onResult":
                    heard.add("result " + ((Bundle) args[0]).getBoolean(AccountManager.KEY_BOOLEAN_RESULT, true));
                    return null;
                case "onError":
                    heard.add("error " + args[0]);
                    return null;
                case "onRequestContinued":
                    heard.add("continued");
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "response";
                default:
                    return null;
            }
        });
        Object transport = new Authenticator(context).getIBinder();
        Class.forName("android.accounts.IAccountAuthenticator").getMethod("hasFeatures", responseType, Account.class, String[].class)
                .invoke(transport, response, new Account("423361090", "www.instagram.com"), new String[] {DEVICE_OWNER_ALLOWED});
        return heard;
    }

    private static final String ACCOUNT_MANAGER_PERMISSION = "android.permission.ACCOUNT_MANAGER";
    private static final String DEVICE_OWNER_ALLOWED = "android.account.DEVICE_OR_PROFILE_OWNER_ALLOWED";

    /**
     * #341. Off to start, the check goes unanswered, as Instagram leaves it, so Android's account service waits
     * on the app. On, it hears at once that the account doesn't have the feature, and the other switches
     * don't answer it.
     */
    @Test
    public void theAccountCheckSwitchAnswersAtOnce() throws Exception {
        assertEquals(Boolean.FALSE, Settings.ANSWER_ACCOUNT_CHECKS.defaultValue);
        assertEquals(List.of(), askAccountFeatures());

        Settings.STOP_HEARTBEAT.save(true);
        Settings.STOP_UPLOAD_ALARM.save(true);
        Settings.STOP_BACKGROUND_FETCH.save(true);
        assertEquals("the other switches leave the check alone", List.of(), askAccountFeatures());

        Settings.ANSWER_ACCOUNT_CHECKS.save(true);
        assertEquals(List.of("result false"), askAccountFeatures());
        assertEquals(List.of(), HookStatus.missing());
    }

    @Test
    public void pausedOrUnreadyTheAccountCheckGoesUnanswered() throws Exception {
        Settings.ANSWER_ACCOUNT_CHECKS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(List.of(), askAccountFeatures());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertNull(Heartbeat.answerAccountFeatures()));
        assertEquals(List.of("result false"), askAccountFeatures());
    }
}
