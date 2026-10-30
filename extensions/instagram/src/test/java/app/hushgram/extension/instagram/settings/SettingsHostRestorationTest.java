/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.Preference;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.EnumSet;

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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SettingsHostRestorationTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private final SettingsEntry.OpenWhenResumed callbacks = new SettingsEntry.OpenWhenResumed();
    private static final String[] PICKERS = {"hushgram_export_configuration", "hushgram_import_configuration"};

    /** Instagram 449 drops this state in both internal-onCreate branches before its super call. */
    public static class Host extends Activity {
        @Override public String getPackageName() { return "com.instagram.android"; }
        @Override public void onCreate(Bundle state) {
            if (state != null) SettingsEntry.removeFrameworkState(state, "android:fragments");
            super.onCreate(state);
        }
    }

    @Before public void setup() {
        Application app = RuntimeEnvironment.getApplication();
        app.getApplicationInfo().targetSdkVersion = 37;
        app.registerActivityLifecycleCallbacks(callbacks);
        SettingsEntry.onClosedByUser();
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(callbacks);
        SettingsEntry.onClosedByUser();
        Settings.HIDE_ADS.resetToDefault();
        ConfigurationBackup.forgetUndo();
        HushgramPreferenceFragment.importFeedback = null;
        PatchFamily.inBuildForTests = null;
    }

    @Test public void everyCancelledPickerReturnsToItsRestoredPagePastTheLaunchWindow() throws Exception {
        for (String key : PICKERS) {
            try (ActivityController<Host> host = open()) {
                HushgramPreferenceFragment old = page(host.get());
                Preference row = old.findPreference(key);
                assertNotNull(key, row);
                assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
                ShadowActivity.IntentForResult picked = shadowOf(host.get()).getNextStartedActivityForResult();
                assertNotNull(picked);
                host.recreate();
                ShadowLooper.idleMainLooper();
                HushgramPreferenceFragment restored = page(host.get());
                assertNotSame(old, restored);
                assertFalse(restored.findPreference(key).isEnabled());
                shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_CANCELED, null);
                ShadowLooper.idleMainLooper();
                assertTrue(key, restored.findPreference(key).isEnabled());
                assertEquals(1, dialog(host.get()).getChildFragmentManager().getFragments().size());
                Utils.awaitBackgroundTasksForTests();
            }
        }
    }

    @Test public void restoredExportReceivesItsOriginalResultOnceThroughTheHost() throws Exception {
        try (ActivityController<Host> host = open()) {
            Preference row = page(host.get()).findPreference(PICKERS[0]);
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            ShadowActivity.IntentForResult picked = shadowOf(host.get()).getNextStartedActivityForResult();
            host.recreate();
            ShadowLooper.idleMainLooper();
            Uri uri = Uri.parse("content://restored-documents/settings.json");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            shadowOf(host.get().getContentResolver()).registerOutputStream(uri, output);
            Intent result = new Intent().setData(uri);
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, result);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue("the restored request never wrote its document", output.size() > 0);
            byte[] first = output.toByteArray();
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, result);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertArrayEquals("a duplicate result wrote twice", first, output.toByteArray());
            assertTrue(page(host.get()).findPreference(PICKERS[0]).isEnabled());
        }
    }

    @Test public void ordinaryHostStateStillDropsFrameworkFragments() {
        try (ActivityController<Host> host = Robolectric.buildActivity(Host.class).setup()) {
            host.get().getFragmentManager().beginTransaction().add(new Fragment(), "ordinary").commitNow();
            host.recreate();
            assertNull(host.get().getFragmentManager().findFragmentByTag("ordinary"));
            assertNull(host.get().getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG));
        }
    }

    @Test public void restoredConfigurationImportAppliesOnceAndRejectsADuplicateResult() throws Exception {
        Settings.HIDE_ADS.save(true);
        try (ActivityController<Host> host = open()) {
            Preference row = page(host.get()).findPreference(PICKERS[1]);
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            ShadowActivity.IntentForResult picked = shadowOf(host.get()).getNextStartedActivityForResult();
            host.recreate();
            ShadowLooper.idleMainLooper();
            Uri uri = Uri.parse("content://restored-documents/import.json");
            shadowOf(host.get().getContentResolver()).registerInputStream(uri, new ByteArrayInputStream(
                    ConfigurationBackupTest.file(ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false))));
            Intent result = new Intent().setData(uri);
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, result);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertFalse(Settings.HIDE_ADS.get());
            Settings.HIDE_ADS.save(true);
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, result);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue("a duplicate import changed the later choice", Settings.HIDE_ADS.get());
            assertTrue(page(host.get()).findPreference(PICKERS[1]).isEnabled());
        }
    }

    @Test public void pendingPickerSurvivesSavedStateWithoutTheProcessOpenRequest() throws Exception {
        Bundle saved = new Bundle();
        ShadowActivity.IntentForResult picked;
        ActivityController<Host> old = open();
        Preference export = page(old.get()).findPreference(PICKERS[0]);
        export.getOnPreferenceClickListener().onPreferenceClick(export);
        picked = shadowOf(old.get()).getNextStartedActivityForResult();
        old.pause().saveInstanceState(saved).stop().destroy();
        Utils.awaitBackgroundTasksForTests();
        SettingsEntry.onClosedByUser();
        try (ActivityController<Host> restored = Robolectric.buildActivity(Host.class)
                .create(saved).start().resume().visible()) {
            ShadowLooper.idleMainLooper();
            assertFalse(page(restored.get()).findPreference(PICKERS[0]).isEnabled());
            shadowOf(restored.get()).receiveResult(picked.intent, Activity.RESULT_CANCELED, null);
            ShadowLooper.idleMainLooper();
            assertTrue(page(restored.get()).findPreference(PICKERS[0]).isEnabled());
        }
    }

    @Test public void aDismissedDialogDoesNotReturnAfterRecreation() {
        try (ActivityController<Host> host = open()) {
            SettingsEntry.onClosedByUser();
            dialog(host.get()).dismiss();
            host.get().getFragmentManager().executePendingTransactions();
            host.recreate();
            ShadowLooper.idleMainLooper();
            assertNull(host.get().getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG));
        }
    }

    @Test public void aResultDeliveredBeforeResumeStillReachesTheRestoredChild() throws Exception {
        Bundle saved = new Bundle();
        ActivityController<Host> old = open();
        Preference export = page(old.get()).findPreference(PICKERS[0]);
        export.getOnPreferenceClickListener().onPreferenceClick(export);
        ShadowActivity.IntentForResult picked = shadowOf(old.get()).getNextStartedActivityForResult();
        old.pause().saveInstanceState(saved).stop().destroy();
        Utils.awaitBackgroundTasksForTests();
        SettingsEntry.onClosedByUser();
        try (ActivityController<Host> restored = Robolectric.buildActivity(Host.class).create(saved).start()) {
            Uri uri = Uri.parse("content://restored-documents/before-resume.json");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            shadowOf(restored.get().getContentResolver()).registerOutputStream(uri, output);
            shadowOf(restored.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(uri));
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue(output.size() > 0);
            restored.resume().visible();
            ShadowLooper.idleMainLooper();
            assertTrue(page(restored.get()).findPreference(PICKERS[0]).isEnabled());
        }
    }

    private static ActivityController<Host> open() {
        ActivityController<Host> host = Robolectric.buildActivity(Host.class,
                new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true)).setup().visible();
        ShadowLooper.idleMainLooper();
        assertNotNull(dialog(host.get()));
        SystemClock.sleep(35_000);
        return host;
    }

    private static SettingsDialog dialog(Activity activity) {
        SettingsDialog dialog = (SettingsDialog) activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG);
        assertNotNull("the recreated host lost settings", dialog);
        return dialog;
    }

    private static HushgramPreferenceFragment page(Activity activity) {
        HushgramPreferenceFragment page = (HushgramPreferenceFragment)
                dialog(activity).getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertNotNull("the restored dialog lost its page", page);
        return page;
    }
}
