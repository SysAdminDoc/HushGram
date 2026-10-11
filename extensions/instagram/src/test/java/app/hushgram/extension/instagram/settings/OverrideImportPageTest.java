/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.TwoStatePreference;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.misc.OverrideExchange;
import app.hushgram.extension.instagram.misc.OverrideImportTest;
import app.hushgram.extension.instagram.misc.OverrideImportTest.NativeTable;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = OverrideImportTest.NativeTable.class)
@SuppressWarnings("deprecation")
public class OverrideImportPageTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final Uri DOCUMENT = Uri.parse("content://override-documents/import.json");
    private ActivityController<OverrideDocumentsTest.HostActivity> host;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        NativeTable.reset();
        clearFeedback();
        OverrideDocumentsTest.HostActivity.noPicker = false;
        OverrideImportTest.useTestTiming();
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        OverrideImportTest.install();
    }

    @After public void close() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        if (host != null) host.close();
        PatchFamily.inBuildForTests = null;
        PatchFamily.overrideExchangeForTests = null;
        PatchFamily.overrideImportForTests = null;
        PatchFamily.flagNamesForTests = null;
        Settings.ALLOW_OVERRIDE_IMPORT.resetToDefault();
        OverrideImportTest.restoreTiming();
        clearFeedback();
    }

    private static void clearFeedback() {
        HushgramPreferenceFragment.overrideImportFeedback = null;
        HushgramPreferenceFragment.overrideRestoreFeedback = null;
        HushgramPreferenceFragment.overrideDiscardFeedback = null;
        HushgramPreferenceFragment.overrideResetFeedback = null;
        HushgramPreferenceFragment.overrideExportFeedback = null;
        HushgramPreferenceFragment.overrideValidationFeedback = null;
    }

    private void openHost() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DEVELOPER_OPTIONS);
        host = Robolectric.buildActivity(OverrideDocumentsTest.HostActivity.class).setup();
        NativeTable.file = OverrideImportTest.store(host.get(), "mobileconfig");
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(host.get().getFragmentManager(), SettingsEntry.DIALOG_TAG);
        host.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    private void result(ShadowActivity.IntentForResult picked, int status, Uri uri) throws Exception {
        Shadows.shadowOf(host.get()).receiveResult(picked.intent, status, uri == null ? null : new Intent().setData(uri));
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private void click(String key) {
        Preference row = page.findPreference(key);
        assertTrue(key, row.isEnabled());
        row.getOnPreferenceClickListener().onPreferenceClick(row);
    }

    private byte[] changed() throws Exception {
        JSONObject file = new JSONObject(new String(OverrideExchange.export(OverrideExchange.capture(host.get())), StandardCharsets.UTF_8));
        file.getJSONObject("overrides").put("123:config", new JSONArray().put("0: enabled: false").put("1: limit: 5"));
        NativeTable.captures = 0;
        return file.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test public void importAndRestoreRowsExistOnlyWhileTheirSwitchIsOn() throws Exception {
        openHost();
        TwoStatePreference allow = (TwoStatePreference) page.findPreference(Settings.ALLOW_OVERRIDE_IMPORT.key);
        assertFalse(allow.isChecked());
        assertNull(page.findPreference("hushgram_import_overrides"));
        assertNull(page.findPreference("hushgram_restore_overrides"));
        assertNull(page.findPreference("hushgram_discard_overrides"));
        allow.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.ALLOW_OVERRIDE_IMPORT.get());
        assertNotNull(page.findPreference("hushgram_import_overrides"));
        assertNotNull(page.findPreference("hushgram_restore_overrides"));
        assertNotNull(page.findPreference("hushgram_discard_overrides"));
        page.searchSettings("restore previous");
        assertNotNull(page.getPreferenceScreen().findPreference("hushgram_restore_overrides"));
        page.searchSettings("");
        allow.setChecked(false);
        ShadowLooper.idleMainLooper();
        assertNull(page.findPreference("hushgram_import_overrides"));
        assertNull(page.findPreference("hushgram_restore_overrides"));
        assertNull(page.findPreference("hushgram_discard_overrides"));
        assertEquals(0, NativeTable.captures);
    }

    @Test public void aBuildWithoutTheWriterLeavesOnlyImportOut() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        PatchFamily.overrideImportForTests = false;
        openHost();
        assertNotNull(page.findPreference("hushgram_open_overrides"));
        assertNotNull(page.findPreference("hushgram_export_overrides"));
        assertNotNull(page.findPreference("hushgram_validate_overrides"));
        for (String key : new String[]{Settings.ALLOW_OVERRIDE_IMPORT.key, "hushgram_import_overrides",
                "hushgram_restore_overrides", "hushgram_discard_overrides", "hushgram_reset_overrides"}) {
            assertNull(key, page.findPreference(key));
        }
    }

    @Test public void theFlagNamesSwitchSitsBesideImportAndRemoveAndIsOnToStart() throws Exception {
        openHost();
        assertNotNull(page.findPreference(Settings.USE_FLAG_NAMES.key));
        assertNotNull(page.findPreference("hushgram_import_flag_names"));
        assertNotNull(page.findPreference("hushgram_remove_flag_names"));
        assertTrue(Settings.USE_FLAG_NAMES.get());
    }

    @Test public void aBuildWithoutTheMetaConfigListHookHasNoFlagNameRows() throws Exception {
        PatchFamily.flagNamesForTests = false;
        openHost();
        assertNotNull(page.findPreference("hushgram_open_overrides"));
        for (String key : new String[]{Settings.USE_FLAG_NAMES.key, "hushgram_import_flag_names", "hushgram_remove_flag_names"}) {
            assertNull(key, page.findPreference(key));
        }
    }

    @Test public void aBuildWithoutTheReaderKeepsTheLongPressAndTheEntriesOnly() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        PatchFamily.overrideExchangeForTests = false;
        openHost();
        assertNotNull(page.findPreference(Settings.OPEN_DEVELOPER_OPTIONS.key));
        assertNotNull(page.findPreference("hushgram_open_overrides"));
        assertNotNull(page.findPreference("hushgram_open_whitehat"));
        for (String key : new String[]{"hushgram_export_overrides", "hushgram_validate_overrides",
                Settings.ALLOW_OVERRIDE_IMPORT.key, "hushgram_import_overrides", "hushgram_restore_overrides",
                "hushgram_discard_overrides", "hushgram_reset_overrides"}) {
            assertNull(key, page.findPreference(key));
        }
        assertFalse(PatchFamily.overrideImportInBuild());
        assertEquals(0, NativeTable.captures);
    }

    @Test public void anImportFromThePickerAppliesAndRestorePutsTheSavedCopyBack() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        byte[] file = changed();
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(file));
        click("hushgram_import_overrides");
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(host.get()).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
        assertEquals("application/json", picked.intent.getType());
        result(picked, Activity.RESULT_OK, DOCUMENT);
        assertEquals("Imported 1 override changes. Restart Instagram to apply them.", HushgramPreferenceFragment.overrideImportFeedback);
        assertTrue(new String(Files.readAllBytes(NativeTable.file.toPath()), StandardCharsets.UTF_8).contains("0: enabled: false"));
        assertEquals(1, NativeTable.writes);

        click("hushgram_restore_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Previous overrides restored. Restart Instagram to apply them.", HushgramPreferenceFragment.overrideRestoreFeedback);
        assertTrue(new String(Files.readAllBytes(NativeTable.file.toPath()), StandardCharsets.UTF_8).contains("0: enabled: true"));
        assertTrue(page.findPreference("hushgram_import_overrides").isEnabled());
        assertTrue(page.findPreference("hushgram_restore_overrides").isEnabled());
    }

    @Test public void resetTakesTheOverridesAwayAndRestorePutsThemBack() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        NativeTable.captures = 0;
        click("hushgram_reset_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Removed 2 overrides. Restart Instagram to go back to its own settings.",
                HushgramPreferenceFragment.overrideResetFeedback);
        // The null override can't be put back, so it stays.
        java.util.Map<String, String> left = new java.util.TreeMap<>();
        left.put("456:other/1/nullable", "__NULL_VALUE__");
        assertEquals(left, OverrideImportTest.semantic(NativeTable.file));

        click("hushgram_reset_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        // Nothing left to take away, and the copy saved for Restore is still the one from before.
        assertEquals("There are no overrides to reset. Nothing changed.", HushgramPreferenceFragment.overrideResetFeedback);

        click("hushgram_restore_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals(OverrideImportTest.original(), OverrideImportTest.semantic(NativeTable.file));
    }

    @Test public void anAppliedImportWithFailedCleanupReportsBothTheChangeAndItsRecovery() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        String backup = OverrideImportTest.saved(host.get(), ".json").getName();
        boolean[] failed = {false};
        OverrideImportTest.storageObserver = (boundary, file) -> {
            if (!failed[0] && "moved".equals(boundary) && file.getName().equals(backup)) {
                failed[0] = true;
                throw new java.io.IOException("controlled promotion failure");
            }
        };
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(changed()));
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_OK, DOCUMENT);
        assertTrue(failed[0]);
        assertEquals("Imported 1 override changes. Restart Instagram to apply them. "
                + "Recovery cleanup didn't finish. Use Restore previous overrides or Discard saved overrides.",
                HushgramPreferenceFragment.overrideImportFeedback);
        assertEquals(1, NativeTable.writes);
        assertTrue(OverrideImportTest.saved(host.get(), ".armed").isFile());

        OverrideImportTest.storageObserver = null;
        click("hushgram_restore_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Previous overrides restored. Restart Instagram to apply them.", HushgramPreferenceFragment.overrideRestoreFeedback);
        assertEquals(OverrideImportTest.original(), OverrideImportTest.semantic(NativeTable.file));
        assertFalse(OverrideImportTest.saved(host.get(), ".armed").exists());
    }

    @Test public void aFailedDiscardReportsIncompleteCleanupAndItsRowCanRetry() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        java.io.File backup = OverrideImportTest.saved(host.get(), ".json");
        Files.createDirectories(backup.getParentFile().toPath());
        Files.write(backup.toPath(), changed());
        OverrideImportTest.storageObserver = (boundary, file) -> {
            if ("beforeDelete".equals(boundary) && file.equals(backup)) {
                throw new java.io.IOException("controlled deletion failure");
            }
        };
        click("hushgram_discard_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Couldn't finish discarding the saved copies. Try Discard saved overrides again. Instagram's "
                + "overrides haven't changed.",
                HushgramPreferenceFragment.overrideDiscardFeedback);
        assertArrayEquals(OverrideImportTest.NATIVE, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(0, NativeTable.writes);
        assertTrue(backup.isFile());

        OverrideImportTest.storageObserver = null;
        click("hushgram_discard_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Discarded the saved copy. Imports can run again, and Instagram's overrides haven't changed.",
                HushgramPreferenceFragment.overrideDiscardFeedback);
        assertFalse(backup.exists());
        assertFalse(OverrideImportTest.saved(host.get(), ".armed").exists());
    }

    @Test public void cancelledMalformedAndUnsavedRequestsLeaveTheNativeStoreByteIdentical() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        byte[] before = Files.readAllBytes(NativeTable.file.toPath());
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_CANCELED, null);
        assertEquals(0, NativeTable.captures);
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_OK, Uri.parse("file:///unused.json"));
        assertEquals(0, NativeTable.captures);
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT,
                new ByteArrayInputStream("{\"project\":\"HushGram-overrides\"}".getBytes(StandardCharsets.UTF_8)));
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_OK, DOCUMENT);
        assertTrue(HushgramPreferenceFragment.overrideImportFeedback.startsWith("Couldn't import overrides."));
        click("hushgram_restore_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Couldn't restore overrides. There's no saved copy for this account and Instagram version. "
                + "Nothing changed.",
                HushgramPreferenceFragment.overrideRestoreFeedback);
        click("hushgram_discard_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("There's no saved copy to discard. Nothing changed.", HushgramPreferenceFragment.overrideDiscardFeedback);
        assertArrayEquals(before, Files.readAllBytes(NativeTable.file.toPath()));
        assertEquals(0, NativeTable.writes);
        assertEquals(0, NativeTable.tableCalls);
    }

    @Test public void anArmedStoreNamesBothWaysOutAndDiscardLetsImportsRunAgain() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        java.io.File marker = OverrideImportTest.saved(host.get(), ".armed");
        Files.createDirectories(marker.getParentFile().toPath());
        Files.write(marker.toPath(), "1".getBytes(StandardCharsets.UTF_8));
        byte[] file = changed();
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(file));
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_OK, DOCUMENT);
        assertEquals("An earlier import still needs Restore previous overrides, or Discard saved overrides if Restore can't run. "
                + "Nothing changed.", HushgramPreferenceFragment.overrideImportFeedback);
        assertEquals(0, NativeTable.writes);

        click("hushgram_discard_overrides");
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Discarded the saved copy. Imports can run again, and Instagram's overrides haven't changed.",
                HushgramPreferenceFragment.overrideDiscardFeedback);
        assertFalse(marker.exists());
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(file));
        click("hushgram_import_overrides");
        result(Shadows.shadowOf(host.get()).getNextStartedActivityForResult(), Activity.RESULT_OK, DOCUMENT);
        assertEquals("Imported 1 override changes. Restart Instagram to apply them.", HushgramPreferenceFragment.overrideImportFeedback);
        assertTrue(page.findPreference("hushgram_discard_overrides").isEnabled());
    }

    @Test public void aSwitchTurnedOffWhileThePickerIsOpenReadsNeitherTheFileNorTheStore() throws Exception {
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
        openHost();
        byte[] before = Files.readAllBytes(NativeTable.file.toPath());
        ByteArrayInputStream document = new ByteArrayInputStream(changed());
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT, document);
        click("hushgram_import_overrides");
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(host.get()).getNextStartedActivityForResult();
        Settings.ALLOW_OVERRIDE_IMPORT.save(false);
        result(picked, Activity.RESULT_OK, DOCUMENT);
        assertEquals("Allow importing overrides is off or HushGram is paused. Nothing changed.",
                HushgramPreferenceFragment.overrideImportFeedback);
        assertTrue(document.available() > 0);
        assertEquals(0, NativeTable.captures);
        assertEquals(0, NativeTable.writes);
        assertArrayEquals(before, Files.readAllBytes(NativeTable.file.toPath()));
    }
}
