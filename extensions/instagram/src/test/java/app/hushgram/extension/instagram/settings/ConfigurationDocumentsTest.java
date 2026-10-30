/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.os.Build;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.accessibility.AccessibilityManager;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ConfigurationDocumentsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final Uri DOCUMENT = Uri.parse("content://test-documents/settings.json");

    @Before public void setup() {
        HushgramPreferenceFragment.importFeedback = null;
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        ConfigurationBackup.forgetUndo();
        Settings.HIDE_ADS.resetToDefault();
        Settings.DATE_SETTINGS_EXPORT.resetToDefault();
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        HushgramPreferenceFragment.importFeedback = null;
        ConfigurationBackup.forgetUndo();
        Settings.HIDE_ADS.resetToDefault();
        Settings.DATE_SETTINGS_EXPORT.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test @Config(sdk = {28, 29, 37})
    public void deadlineTextAndExpirySurvivePauseWithoutExtendingUndo() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            AccessibilityManager manager = activity.get().getSystemService(AccessibilityManager.class);
            for (int recommendation : new int[]{10_000, 30_000, 120_000}) {
                if (Build.VERSION.SDK_INT >= 29) Shadows.shadowOf(manager).setInteractiveUiTimeout(recommendation);
                Settings.HIDE_ADS.save(true);
                ConfigurationBackup.restore(ConfigurationBackupTest.file(
                        ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false)));
                HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
                Preference undo = page.findPreference("hushgram_undo_configuration");
                assertEquals("Choose a settings file. All valid choices apply together, and ones this version "
                        + "doesn't know are skipped. The Undo row shows how long you can undo.",
                        page.findPreference("hushgram_import_configuration").getSummary().toString());
                long token = ConfigurationBackup.undoToken();
                long deadline = ConfigurationBackup.undoDeadline(token);
                assertDeadlineLabel(undo, deadline, ". Restarting Instagram discards Undo.");
                activity.pause().stop();
                SystemClock.sleep(5_000);
                activity.start().resume();
                assertEquals(deadline, ConfigurationBackup.undoDeadline(token));
                assertDeadlineLabel(undo, deadline, ". Restarting Instagram discards Undo.");
                SystemClock.sleep(deadline - SystemClock.elapsedRealtime());
                ShadowLooper.idleMainLooper();
                assertFalse(undo.isEnabled());
                assertEquals("No settings import to undo.", undo.getSummary().toString());
                activity.get().getFragmentManager().beginTransaction().remove(page).commitNow();
            }
        }
    }

    /** The displayed time has second precision and Java's wall clock runs during rendering. */
    static void assertDeadlineLabel(Preference row, long deadline, String suffix) {
        long expected = System.currentTimeMillis() + deadline - SystemClock.elapsedRealtime();
        DateFormat format = DateFormat.getTimeInstance(DateFormat.MEDIUM, L10n.locale(row.getContext()));
        String actual = row.getSummary().toString();
        for (int boundary : new int[]{-1_000, 0, 1_000}) {
            if (actual.equals("Undo is available until " + format.format(new Date(expected + boundary)) + suffix)) return;
        }
        fail("Deadline label differs by more than one display second: " + actual);
    }

    @Test @Config(sdk = {28, 29, 37})
    public void undoKeepsAFrameworkSwitchChangedAwayAndBack() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            ConfigurationBackup.restore(ConfigurationBackupTest.file(
                    ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false)));
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            SwitchPreference control = (SwitchPreference) page.findPreference(Settings.HIDE_ADS.key);
            control.setChecked(true);
            ShadowLooper.idleMainLooper();
            control.setChecked(false);
            ShadowLooper.idleMainLooper();
            Preference undo = page.findPreference("hushgram_undo_configuration");
            undo.getOnPreferenceClickListener().onPreferenceClick(undo);
            finish();
            assertFalse(Settings.HIDE_ADS.savedValue());
            assertFalse(control.isChecked());
            assertEquals("Restored 0 settings. Kept 1 newer choices.",
                    page.findPreference("hushgram_import_configuration").getSummary().toString());
            assertFalse(undo.isEnabled());
        }
    }

    @Test public void exportUsesCreateDocumentAndWritesTypedSettingsOnTheWorker() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Shadows.shadowOf(activity.get().getContentResolver()).registerOutputStream(DOCUMENT, output);
            ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_export_configuration");
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picked.intent.getAction());
            assertEquals("application/json", picked.intent.getType());
            assertTrue(picked.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertEquals("HushGram-settings.json", picked.intent.getStringExtra(Intent.EXTRA_TITLE));
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            JSONObject file = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            assertEquals(1, file.getInt("schema"));
            assertTrue(file.getJSONObject("settings").has(Settings.HIDE_ADS.key));
            assertTrue(page.findPreference("hushgram_export_configuration").isEnabled());
        }
    }

    @Test public void datedExportUsesTheLocalClockAndWesternDigits() {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals("HushGram-settings-1970-01-01_00-00-00.json",
                    ConfigurationBackup.fileName(new Date(0), true));
            assertEquals("HushGram-settings-1970-01-01_00-00-01.json",
                    ConfigurationBackup.fileName(new Date(1_000), true));
            Locale.setDefault(Locale.forLanguageTag("th-TH"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertEquals("HushGram-settings-1969-12-31_19-00-00.json",
                    ConfigurationBackup.fileName(new Date(0), true));
            assertEquals("HushGram-settings.json", ConfigurationBackup.fileName(new Date(0), false));
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }

    @Test public void datedPickerKeepsItsSavedChoiceDuringPauseAndExportsAnImportableFile() throws Exception {
        assertEquals(Boolean.FALSE, Settings.DATE_SETTINGS_EXPORT.defaultValue);
        Settings.DATE_SETTINGS_EXPORT.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            SwitchPreference option = (SwitchPreference) page.findPreference(Settings.DATE_SETTINGS_EXPORT.key);
            assertNotNull(option);
            assertEquals("Date in backup file names", option.getTitle().toString());
            assertTrue(option.isChecked());
            assertFalse("the export must use the saved choice rather than the paused hook value",
                    Settings.DATE_SETTINGS_EXPORT.get());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Shadows.shadowOf(activity.get().getContentResolver()).registerOutputStream(DOCUMENT, output);
            ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_export_configuration");
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picked.intent.getAction());
            assertEquals("application/json", picked.intent.getType());
            assertTrue(picked.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            assertTrue(picked.intent.getStringExtra(Intent.EXTRA_TITLE).matches(
                    "HushGram-settings-[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}\\.json"));
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            JSONObject exported = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            assertEquals("HushGram", exported.getString("project"));
            assertEquals(1, exported.getInt("schema"));
            assertTrue(exported.getJSONObject("settings").getJSONObject(Settings.DATE_SETTINGS_EXPORT.key).getBoolean("value"));
            Settings.DATE_SETTINGS_EXPORT.save(false);
            ConfigurationBackup.restore(output.toByteArray());
            assertTrue(Settings.DATE_SETTINGS_EXPORT.savedValue());
        } finally {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
        }
    }

    @Test public void cancelledPickerAndInvalidFileLeaveSettingsAndUndoUntouched() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_import_configuration");
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_CANCELED, null);
            assertTrue(page.findPreference("hushgram_import_configuration").isEnabled());
            assertTrue(Settings.HIDE_ADS.savedValue());
            picked = pick(page, activity.get(), "hushgram_import_configuration");
            Shadows.shadowOf(activity.get().getContentResolver()).registerInputStream(DOCUMENT,
                    new ByteArrayInputStream("invalid".getBytes(StandardCharsets.UTF_8)));
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            assertTrue(Settings.HIDE_ADS.savedValue());
            assertFalse(page.findPreference("hushgram_undo_configuration").isEnabled());
        }
    }

    @Test public void importRefreshesExistingControlsAndUndoRestoresThemWithoutRebuilding() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            SwitchPreference control = (SwitchPreference) page.findPreference(Settings.HIDE_ADS.key);
            Shadows.shadowOf(activity.get().getContentResolver()).registerInputStream(DOCUMENT,
                    new ByteArrayInputStream(ConfigurationBackupTest.file(ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false))));
            ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_import_configuration");
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            assertFalse(Settings.HIDE_ADS.savedValue());
            assertFalse(control.isChecked());
            assertSame(control, page.findPreference(Settings.HIDE_ADS.key));
            assertTrue(page.findPreference("hushgram_import_configuration").getSummary().toString().contains("Imported"));
            Preference undo = page.findPreference("hushgram_undo_configuration");
            assertTrue(undo.isEnabled());
            undo.getOnPreferenceClickListener().onPreferenceClick(undo);
            finish();
            assertTrue(Settings.HIDE_ADS.savedValue());
            assertTrue(control.isChecked());
            assertFalse(undo.isEnabled());
        }
    }

    // A file that changes a choice waiting on a restart owes Restart now, as flipping its row does.
    // It owed nothing before: the row's listener read the value after the save and took the new
    // one for what Instagram runs. An Undo back to that value owes nothing again.
    @Test public void importOwesRestartNowAndUndoBackToTheRunningValueClearsIt() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.META_AI);
        boolean running = Settings.HIDE_META_AI_SEARCH.savedValue();
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            assertTrue(Settings.HIDE_META_AI_SEARCH.rebootApp);
            assertNotNull("the row's listener is part of what this proves", page.findPreference(Settings.HIDE_META_AI_SEARCH.key));
            assertFalse(RestartNow.owed());
            Shadows.shadowOf(activity.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(
                    ConfigurationBackupTest.file(ConfigurationBackupTest.entry(Settings.HIDE_META_AI_SEARCH.key, "boolean", !running))));
            ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_import_configuration");
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            assertEquals(!running, Settings.HIDE_META_AI_SEARCH.savedValue());
            assertTrue(AbstractPreferenceFragment.restartPending.contains(Settings.HIDE_META_AI_SEARCH.key));
            assertTrue(RestartNow.owed());
            assertTrue(page.findPreference("hushgram_import_configuration").getSummary().toString()
                    .endsWith("Restart Instagram to apply these choices."));
            Preference undo = page.findPreference("hushgram_undo_configuration");
            undo.getOnPreferenceClickListener().onPreferenceClick(undo);
            finish();
            assertEquals(running, Settings.HIDE_META_AI_SEARCH.savedValue());
            assertFalse(RestartNow.owed());
            assertEquals("back on what's running, the receipt asks for no restart", "Settings restored.",
                    page.findPreference("hushgram_import_configuration").getSummary().toString());
        } finally {
            Settings.HIDE_META_AI_SEARCH.resetToDefault();
        }
    }

    @Test public void staleUndoAfterExpiryNeverStartsAnImportOrChangesSettings() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            ConfigurationBackup.restore(ConfigurationBackupTest.file(ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false)));
            page.onResume();
            Preference undo = page.findPreference("hushgram_undo_configuration");
            assertTrue(undo.isEnabled());
            SystemClock.sleep(ConfigurationBackup.UNDO_WINDOW_MS + 1);
            undo.getOnPreferenceClickListener().onPreferenceClick(undo);
            finish();
            assertFalse(Settings.HIDE_ADS.savedValue());
            assertFalse(undo.isEnabled());
            assertNull(Shadows.shadowOf(activity.get()).getNextStartedActivityForResult());
        }
    }

    @Test public void nestedSettingsDialogReceivesTheSystemPickerResult() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = new SettingsDialog();
            dialog.show(activity.get().getFragmentManager(), "hushgram_settings");
            activity.get().getFragmentManager().executePendingTransactions();
            ShadowLooper.idleMainLooper();
            HushgramPreferenceFragment page = (HushgramPreferenceFragment)
                    dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
            assertNotNull(page);
            Shadows.shadowOf(activity.get().getContentResolver()).registerInputStream(DOCUMENT,
                    new ByteArrayInputStream(ConfigurationBackupTest.file(ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false))));
            ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_import_configuration");
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            assertFalse(Settings.HIDE_ADS.savedValue());
            assertTrue(page.findPreference("hushgram_undo_configuration").isEnabled());
        }
    }

    @Test public void completeRestartFeedbackStaysReadableAfterTheToastExpires() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.META_AI);
        Settings.HIDE_META_AI_SEARCH.resetToDefault();
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            Shadows.shadowOf(activity.get().getContentResolver()).registerInputStream(DOCUMENT,
                    new ByteArrayInputStream(ConfigurationBackupTest.file(ConfigurationBackupTest.entry(Settings.HIDE_META_AI_SEARCH.key, "boolean", false))));
            ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_import_configuration");
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            SystemClock.sleep(11_000);
            ShadowLooper.idleMainLooper();
            assertTrue(page.findPreference("hushgram_import_configuration").getSummary().toString()
                    .contains("Restart Instagram to apply these choices."));
        } finally {
            Settings.HIDE_META_AI_SEARCH.resetToDefault();
        }
    }

    @Test public void aProviderCloseFailureLeavesSettingsAndUndoUntouched() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            byte[] valid = ConfigurationBackupTest.file(ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false));
            Shadows.shadowOf(activity.get().getContentResolver()).registerInputStream(DOCUMENT,
                    new ByteArrayInputStream(valid) {
                        @Override public void close() throws java.io.IOException {
                            throw new java.io.IOException("controlled provider close failure");
                        }
                    });
            ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_import_configuration");
            Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            assertTrue(Settings.HIDE_ADS.savedValue());
            assertFalse(ConfigurationBackup.canUndo());
            assertEquals("Couldn't use that settings file. Your settings haven't changed.",
                    page.findPreference("hushgram_import_configuration").getSummary().toString());
        }
    }

    @Test public void theCompleteReceiptSurvivesClosingAndReopeningSettings() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.META_AI);
        Settings.HIDE_META_AI_SEARCH.resetToDefault();
        String receipt;
        try {
            try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
                HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
                Shadows.shadowOf(activity.get().getContentResolver()).registerInputStream(DOCUMENT,
                        new ByteArrayInputStream(ConfigurationBackupTest.file(
                                ConfigurationBackupTest.entry(Settings.HIDE_META_AI_SEARCH.key, "boolean", false))));
                ShadowActivity.IntentForResult picked = pick(page, activity.get(), "hushgram_import_configuration");
                Shadows.shadowOf(activity.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
                finish();
                receipt = page.findPreference("hushgram_import_configuration").getSummary().toString();
                assertTrue(receipt.contains("Imported 1 settings."));
                assertFalse("no skipped clause when nothing was skipped", receipt.contains("Skipped"));
                assertTrue(receipt.contains("Restart Instagram to apply these choices."));
            }
            try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
                HushgramPreferenceFragment reopened = DownloadSettingsTest.pageIn(activity);
                assertEquals(receipt, reopened.findPreference("hushgram_import_configuration").getSummary().toString());
            }
        } finally { Settings.HIDE_META_AI_SEARCH.resetToDefault(); }
    }

    private static ShadowActivity.IntentForResult pick(HushgramPreferenceFragment page, Activity activity, String key) {
        Preference row = page.findPreference(key);
        assertTrue(row.isEnabled());
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        assertFalse(row.isEnabled());
        ShadowActivity.IntentForResult intent = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(intent);
        return intent;
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}
