/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Looper;
import android.preference.Preference;
import android.preference.SwitchPreference;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.WorkerPoolForTests;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.FailingStore;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.hushgram.extension.shared.settings.preference.SharedPrefCategory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/** Status-card recovery must stay usable while the process's hooks are paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 29, 37})
@SuppressWarnings("deprecation")
public class PauseRecoveryTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void pausedHost() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.DEBUG.save(false);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ShadowToast.reset();
    }

    @After public void restore() throws Exception {
        finish();
        PauseForTests.resume();
        BaseSettings.PAUSED.resetToDefault();
        BaseSettings.SAFE_MODE.resetToDefault();
        AbstractPreferenceFragment.restartPending.clear();
        SettingsEntry.onClosedByUser();
        ScreenColors.shown = null;
        PatchFamily.inBuildForTests = null;
    }

    @Test public void recoveryStaysBelowSearchAndSupportStaysLastWhenTheFullBuildIsFiltered() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            for (boolean pages : new boolean[]{false, true}) {
                Settings.CATEGORY_PAGES.save(pages);
                page.searchSettings("");
                Preference status = statusCard(page);
                page.searchSettings("zzzz no settings");
                assertSame(status, page.getPreferenceScreen().getPreference(2));
                assertNull(page.findPreference(HushgramPreferenceFragment.SUPPORT_ROW_KEY));
                page.searchSettings("coffee");
                assertSame(status, statusCard(page));
                page.searchSettings("");
                assertSame(status, statusCard(page));
            }
        }
    }

    @Test public void aWorkerCommitDisablesBothRecoveryInputsAndIgnoresDuplicateTaps() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            Preference status = statusCard(page);
            Preference pause = page.findPreference(BaseSettings.PAUSED.key);
            assertTrue(status.isEnabled());
            assertTrue(pause.isEnabled());
            try (ObservedStore store = new ObservedStore()) {
                tap(status);
                assertTrue("recovery never reached its commit", store.entered.await(5, TimeUnit.SECONDS));
                assertNotSame("recovery committed on the UI thread", Looper.getMainLooper().getThread(), store.caller.get());
                assertFalse(status.isEnabled());
                assertFalse(pause.isEnabled());
                page.updateUIAvailability();
                assertFalse("preference refresh re-enabled Pause during recovery", pause.isEnabled());
                tap(status);
                assertEquals("duplicate recovery opened a second editor", 1, store.edits.get());
                store.release.countDown();
                finish();
                assertFalse(BaseSettings.PAUSED.savedValue());
                assertFalse(((SwitchPreference) pause).isChecked());
                assertTrue(status.isEnabled());
                assertTrue(pause.isEnabled());
                assertEquals("HushGram turns back on when Instagram restarts.", status.getSummary());
                assertTrue("recovery cannot change this process's frozen pause", HushgramPause.isPaused());
            }
        }
    }

    /** Turning HushGram back on from the card offers Restart now, since that's what applies it (#146). */
    @Test public void turningBackOnOffersRestartNow() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            assertFalse("still paused at the next start, a restart applies nothing", page.restartActionAvailable());
            tap(statusCard(page));
            finish();
            assertFalse(BaseSettings.PAUSED.savedValue());
            assertTrue(page.restartActionAvailable());
        }
    }

    /** Flipping Pause on offers Restart now right away; flipping it back off owes nothing again (#146). */
    @Test public void flippingPauseOffersRestartNow() throws Exception {
        PauseForTests.resume();
        BaseSettings.PAUSED.save(false);
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            SwitchPreference pause = (SwitchPreference) page.findPreference(BaseSettings.PAUSED.key);
            assertFalse("nothing owed yet", page.restartActionAvailable());
            pause.setChecked(true);
            finish();
            assertTrue(BaseSettings.PAUSED.savedValue());
            assertTrue("the next start pauses, so the screen offers the restart", page.restartActionAvailable());
            pause.setChecked(false);
            finish();
            assertFalse("back where it started, nothing is owed", page.restartActionAvailable());
        }
    }

    @Test public void aFailedCommitKeepsTheSavedPauseAndRestoresTheRetryControls() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            Preference status = statusCard(page);
            SwitchPreference pause = (SwitchPreference) page.findPreference(BaseSettings.PAUSED.key);
            try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_FALSE)) {
                tap(status);
                finish();
                assertTrue(BaseSettings.PAUSED.savedValue());
                assertTrue(pause.isChecked());
                assertTrue(status.isEnabled());
                assertTrue(pause.isEnabled());
                assertTrue(String.valueOf(status.getSummary()).contains("Tap to turn it back on."));
                assertEquals("Couldn't turn HushGram back on. Try again.", ShadowToast.getTextOfLatestToast());
            }
            tap(status);
            finish();
            assertFalse(BaseSettings.PAUSED.savedValue());
            assertFalse(pause.isChecked());
        }
    }

    @Test public void aFailedRollbackShowsThePauseValueThatActuallySurvived() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            Preference status = statusCard(page);
            SwitchPreference pause = (SwitchPreference) page.findPreference(BaseSettings.PAUSED.key);
            try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_FALSE, FailingStore.Fault.LOST)) {
                tap(status);
                finish();
                assertFalse(BaseSettings.PAUSED.savedValue());
                assertFalse(Setting.preferences.preferences.getBoolean(BaseSettings.PAUSED.key, false));
                assertFalse(pause.isChecked());
                assertTrue(status.isEnabled());
                assertTrue(pause.isEnabled());
                assertEquals("HushGram turns back on when Instagram restarts.", status.getSummary());
            }
        }
    }

    @Test public void aFullWorkerQueueLeavesPauseSavedAndBothControlsReadyToRetry() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            Preference status = statusCard(page);
            SwitchPreference pause = (SwitchPreference) page.findPreference(BaseSettings.PAUSED.key);
            try (WorkerPoolForTests ignored = WorkerPoolForTests.fill()) {
                ShadowToast.reset();
                tap(status);
                assertTrue(BaseSettings.PAUSED.savedValue());
                assertTrue(pause.isChecked());
                assertTrue(status.isEnabled());
                assertTrue(pause.isEnabled());
                assertEquals("Couldn't turn HushGram back on. Try again.", ShadowToast.getTextOfLatestToast());
            }
            tap(status);
            finish();
            assertFalse(BaseSettings.PAUSED.savedValue());
            assertFalse(pause.isChecked());
        }
    }

    @Test public void recoveryFinishesAfterTeardownWithoutUpdatingDetachedControlsOrAcceptingALateTap() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            Preference status = statusCard(page);
            Preference pause = page.findPreference(BaseSettings.PAUSED.key);
            try (ObservedStore store = new ObservedStore()) {
                tap(status);
                assertTrue(store.entered.await(5, TimeUnit.SECONDS));
                assertFalse(status.isEnabled());
                assertFalse(pause.isEnabled());
                host.get().getFragmentManager().beginTransaction().remove(page).commitNow();
                assertNull(page.getView());
                ShadowToast.reset();
                store.release.countDown();
                finish();
                assertFalse(BaseSettings.PAUSED.savedValue());
                assertFalse("completion updated a detached status card", status.isEnabled());
                assertFalse("completion updated a detached Pause control", pause.isEnabled());
                assertNull(ShadowToast.getTextOfLatestToast());
                tap(status);
                finish();
                assertEquals("a stale status tap retried recovery after teardown", 1, store.edits.get());
            }
        }
    }

    private static Preference statusCard(HushgramPreferenceFragment page) {
        android.preference.PreferenceScreen screen = page.getPreferenceScreen();
        Preference status = screen.findPreference("hushgram_status");
        assertNotNull(status);
        assertEquals("HushGram is paused", status.getTitle());
        Preference support = page.findPreference(HushgramPreferenceFragment.SUPPORT_ROW_KEY);
        assertNotNull(support);
        assertSame("Support must remain after the status card and every section", support,
                screen.getPreference(screen.getPreferenceCount() - 1));
        return status;
    }

    private static void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    /** Hold a worker's commit at a known boundary. A broken UI-thread commit never waits. */
    private static final class ObservedStore implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> caller = new AtomicReference<>();
        final AtomicInteger edits = new AtomicInteger();
        final Field field = SharedPrefCategory.class.getDeclaredField("preferences");
        final Object original;

        ObservedStore() throws Exception {
            field.setAccessible(true);
            original = field.get(Setting.preferences);
            SharedPreferences target = (SharedPreferences) original;
            field.set(Setting.preferences, Proxy.newProxyInstance(target.getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return call(method, target, args);
                        edits.incrementAndGet();
                        SharedPreferences.Editor editor = target.edit();
                        return Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, edit, values) -> {
                                    if (edit.getName().equals("commit")) {
                                        caller.compareAndSet(null, Thread.currentThread());
                                        entered.countDown();
                                        if (Looper.myLooper() != Looper.getMainLooper()
                                                && !release.await(5, TimeUnit.SECONDS)) {
                                            throw new AssertionError("recovery commit was not released");
                                        }
                                    }
                                    Object result = call(edit, editor, values);
                                    return result instanceof SharedPreferences.Editor ? editProxy : result;
                                });
                    }));
        }

        @Override public void close() throws Exception {
            release.countDown();
            Utils.awaitBackgroundTasksForTests();
            field.set(Setting.preferences, original);
        }

        private static Object call(Method method, Object target, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
