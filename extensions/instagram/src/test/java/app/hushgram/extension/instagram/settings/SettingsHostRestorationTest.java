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
import java.nio.file.Files;
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
import app.hushgram.extension.instagram.misc.OverrideExchange;
import app.hushgram.extension.instagram.misc.OverrideImportTest;
import app.hushgram.extension.instagram.misc.OverrideImportTest.NativeTable;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SettingsHostRestorationTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private final SettingsEntry.OpenWhenResumed callbacks = new SettingsEntry.OpenWhenResumed();
    private static final String[] PICKERS = {"hushgram_export_configuration", "hushgram_import_configuration",
            "hushgram_export_overrides", "hushgram_validate_overrides", "hushgram_import_overrides"};

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
        app.getApplicationInfo().targetSdkVersion = 36;
        app.registerActivityLifecycleCallbacks(callbacks);
        SettingsEntry.onClosedByUser();
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(callbacks);
        SettingsEntry.onClosedByUser();
        Settings.ALLOW_OVERRIDE_IMPORT.resetToDefault();
        Settings.HIDE_ADS.resetToDefault();
        ConfigurationBackup.forgetUndo();
        HushgramPreferenceFragment.importFeedback = null;
        HushgramPreferenceFragment.overrideExportFeedback = null;
        HushgramPreferenceFragment.overrideValidationFeedback = null;
        HushgramPreferenceFragment.overrideImportFeedback = null;
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

    @Test @Config(sdk = {28, 37}, shadows = NativeTable.class)
    public void everyOverridePickerCompletesAfterRecreationWithoutChangingTheSession() throws Exception {
        OverrideImportTest.install();
        for (int index = 2; index < PICKERS.length; index++) {
            NativeTable.reset();
            try (ActivityController<Host> host = open()) {
                NativeTable.file = OverrideImportTest.store(host.get(), "mobileconfig");
                byte[] original = Files.readAllBytes(NativeTable.file.toPath());
                byte[] document = OverrideExchange.export(OverrideExchange.capture(host.get()));
                Preference row = page(host.get()).findPreference(PICKERS[index]);
                row.getOnPreferenceClickListener().onPreferenceClick(row);
                ShadowActivity.IntentForResult picked = shadowOf(host.get()).getNextStartedActivityForResult();
                host.recreate();
                ShadowLooper.idleMainLooper();
                Uri uri = Uri.parse("content://restored-documents/overrides.json");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (index == 2) shadowOf(host.get().getContentResolver()).registerOutputStream(uri, output);
                else shadowOf(host.get().getContentResolver()).registerInputStream(uri, new ByteArrayInputStream(document));
                int captures = NativeTable.captures;
                Intent result = new Intent().setData(uri);
                shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, result);
                Utils.awaitBackgroundTasksForTests();
                ShadowLooper.idleMainLooper();
                assertTrue("the original operation was not delivered", NativeTable.captures > captures);
                if (index == 2) assertArrayEquals(document, output.toByteArray());
                else if (index == 3) assertEquals("Checked 3 overrides only. Nothing was applied.",
                        HushgramPreferenceFragment.overrideValidationFeedback);
                else assertEquals("This file matches the current overrides. Nothing changed.",
                        HushgramPreferenceFragment.overrideImportFeedback);
                captures = NativeTable.captures;
                shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, result);
                Utils.awaitBackgroundTasksForTests();
                ShadowLooper.idleMainLooper();
                assertEquals("duplicate result reached the session again", captures, NativeTable.captures);
                assertEquals(0, NativeTable.writes);
                assertArrayEquals(original, Files.readAllBytes(NativeTable.file.toPath()));
                assertTrue(page(host.get()).findPreference(PICKERS[index]).isEnabled());
            }
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
