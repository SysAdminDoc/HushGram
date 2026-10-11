/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import app.hushgram.extension.shared.WorkerPoolForTests;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;
import app.hushgram.extension.shared.settings.preference.LogBufferManagerExportTest.Downloads;
import app.hushgram.extension.shared.settings.preference.ExportStatus;

/** Export outcomes belong to the process, even after the page that launched them closes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ExportFeedbackTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final String CONFIGURATION = "hushgram_export_configuration";
    private static final String DIAGNOSTICS = "action_export_diagnostic_report";
    private static final Uri DOCUMENT = Uri.parse("content://private-documents/private-token/settings.json");

    @Before public void setup() { PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS); }

    @After public void restore() throws Exception {
        finish();
        PatchFamily.inBuildForTests = null;
    }

    @Test public void aSuccessfulConfigurationExportKeepsItsReceiptAfterReopening() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, output);
            ShadowActivity.IntentForResult picked = pick(page, host.get());
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            assertTrue(output.size() > 0);
            assertEquals("HushGram settings exported.", page.findPreference(CONFIGURATION).getSummary());
            host.get().getFragmentManager().beginTransaction().remove(page).commitNow();
            page = DownloadSettingsTest.pageIn(host);
            assertEquals("HushGram settings exported.", page.findPreference(CONFIGURATION).getSummary());
        }
    }

    @Test public void cancellationHasItsOwnRetainedOutcome() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            ShadowActivity.IntentForResult picked = pick(page, host.get());
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_CANCELED, null);
            finish();
            assertEquals("Settings export canceled.", page.findPreference(CONFIGURATION).getSummary());
            host.get().getFragmentManager().beginTransaction().remove(page).commitNow();
            page = DownloadSettingsTest.pageIn(host);
            assertEquals("Settings export canceled.", page.findPreference(CONFIGURATION).getSummary());
            assertTrue(page.findPreference(CONFIGURATION).isEnabled());
        }
    }

    @Test public void aCloseFailureNeverPublishesSuccessOrPrivateDetails() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, new ByteArrayOutputStream() {
                @Override public void close() throws IOException { throw new IOException(DOCUMENT.toString()); }
            });
            ShadowActivity.IntentForResult picked = pick(page, host.get());
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            String summary = page.findPreference(CONFIGURATION).getSummary().toString();
            assertEquals("Couldn't export HushGram settings. Try another file.", summary);
            assertFalse(summary.contains("private-token"));
            assertTrue(page.findPreference(CONFIGURATION).isEnabled());
        }
    }

    @Test public void queueRejectionKeepsAReadableFailureAndAllowsAnotherTry() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            ShadowActivity.IntentForResult picked = pick(page, host.get());
            try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
                shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
                assertEquals("Couldn't start that. Try again.",
                        page.findPreference(CONFIGURATION).getSummary());
                assertTrue(page.findPreference(CONFIGURATION).isEnabled());
            }
        }
    }

    @Test public void aRunningExportSurvivesClosingAndDisablesTheReopenedAction() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            ByteArrayOutputStream output = new ByteArrayOutputStream() {
                @Override public synchronized void write(byte[] bytes, int start, int count) {
                    writing.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("writer wasn't released"); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                    super.write(bytes, start, count);
                }
            };
            shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, output);
            ShadowActivity.IntentForResult picked = pick(page, host.get());
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            host.get().getFragmentManager().beginTransaction().remove(page).commitNow();
            page = DownloadSettingsTest.pageIn(host);
            assertFalse("reopening allowed a second export", page.findPreference(CONFIGURATION).isEnabled());
            assertEquals("Exporting HushGram settings...", page.findPreference(CONFIGURATION).getSummary());
            release.countDown();
            finish();
            assertTrue(output.size() > 0);
            assertEquals("HushGram settings exported.", page.findPreference(CONFIGURATION).getSummary());
            assertTrue(page.findPreference(CONFIGURATION).isEnabled());
        } finally { release.countDown(); }
    }

    @Test public void clipboardSuccessRemainsOnTheDiagnosticRowAfterReopening() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            LogBufferManager.exportToClipboard();
            finish();
            assertEquals("Diagnostic report copied to the clipboard.", page.findPreference(DIAGNOSTICS).getSummary());
            host.get().getFragmentManager().beginTransaction().remove(page).commitNow();
            page = DownloadSettingsTest.pageIn(host);
            assertEquals("Diagnostic report copied to the clipboard.", page.findPreference(DIAGNOSTICS).getSummary());
        }
    }

    @Test public void aDiagnosticFileReceiptDoesNotExposeItsPath() throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
            shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerOutputStream(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, 1), new ByteArrayOutputStream());
        }
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            LogBufferManager.exportToFile();
            finish();
            assertEquals("Diagnostic report saved.", page.findPreference(DIAGNOSTICS).getSummary());
            host.get().getFragmentManager().beginTransaction().remove(page).commitNow();
            page = DownloadSettingsTest.pageIn(host);
            assertEquals("Diagnostic report saved.", page.findPreference(DIAGNOSTICS).getSummary());
            assertTrue(page.findPreference(DIAGNOSTICS).isEnabled());
        }
    }

    @Test public void diagnosticStorageFailureKeepsAPrivateReadableOutcome() throws Exception {
        Context application = RuntimeEnvironment.getApplication();
        if (Build.VERSION.SDK_INT >= 29) {
            Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
            shadowOf(application.getContentResolver()).registerOutputStream(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, 1), new ByteArrayOutputStream() {
                        @Override public synchronized void write(byte[] bytes, int start, int count) {
                            throw new SecurityException("private-token");
                        }
                    });
        } else {
            File unavailable = File.createTempFile("private-token", ".file", application.getCacheDir());
            Utils.setContext(new ContextWrapper(application) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getExternalFilesDir(String type) { return unavailable; }
            });
        }
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            LogBufferManager.exportToFile();
            finish();
            assertEquals("The diagnostic report couldn't be saved. Try again.", page.findPreference(DIAGNOSTICS).getSummary());
            assertTrue(page.findPreference(DIAGNOSTICS).isEnabled());
        } finally { Utils.setContext(application); }
    }

    @Test public void bothDiagnosticChoicesRetainQueueRejection() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
                for (boolean clipboard : new boolean[]{true, false}) {
                    if (clipboard) LogBufferManager.exportToClipboard();
                    else LogBufferManager.exportToFile();
                    ShadowLooper.idleMainLooper();
                    assertEquals("Couldn't start the report export. Try again shortly.", page.findPreference(DIAGNOSTICS).getSummary());
                    assertTrue(page.findPreference(DIAGNOSTICS).isEnabled());
                }
            }
        }
    }

    public static class NoPickerHost extends Activity {
        @Override public void startActivityFromFragment(Fragment fragment, Intent intent, int request, Bundle options) {
            throw new ActivityNotFoundException("private provider detail");
        }
    }

    @Test public void anUnavailablePickerLeavesARecoverableStatus() throws Exception {
        try (ActivityController<NoPickerHost> host = Robolectric.buildActivity(NoPickerHost.class).setup()) {
            HushgramPreferenceFragment page = new HushgramPreferenceFragment();
            host.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
            Preference row = page.findPreference(CONFIGURATION);
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            finish();
            assertEquals("This phone has no file picker. Your settings haven't changed.", row.getSummary());
            assertTrue(row.isEnabled());
            assertFalse(ExportStatus.CONFIGURATION.active());
        }
    }

    @Test public void revokedWriteAccessAndDuplicateResultsCannotClaimSuccess() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, new ByteArrayOutputStream() {
                @Override public synchronized void write(byte[] bytes, int start, int count) {
                    throw new SecurityException(DOCUMENT.toString());
                }
            });
            ShadowActivity.IntentForResult picked = pick(page, host.get());
            Intent result = new Intent().setData(DOCUMENT);
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, result);
            finish();
            assertEquals("Couldn't export HushGram settings. Try another file.", page.findPreference(CONFIGURATION).getSummary());
            ByteArrayOutputStream later = new ByteArrayOutputStream();
            shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, later);
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, result);
            finish();
            assertEquals(0, later.size());
            assertEquals("Couldn't export HushGram settings. Try another file.", page.findPreference(CONFIGURATION).getSummary());
        }
    }

    @Test public void closingAPendingPickerCancelsItButAnOldResultCannotReplaceANewerOutcome() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment old = DownloadSettingsTest.pageIn(host);
            ShadowActivity.IntentForResult first = pick(old, host.get());
            host.get().getFragmentManager().beginTransaction().remove(old).commitNow();
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            assertEquals("Settings export canceled.", page.findPreference(CONFIGURATION).getSummary());
            ShadowActivity.IntentForResult second = pick(page, host.get());
            shadowOf(host.get()).receiveResult(second.intent, Activity.RESULT_CANCELED, null);
            String message = page.findPreference(CONFIGURATION).getSummary().toString();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, output);
            old.onActivityResult(first.requestCode & 0xffff, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            assertEquals(0, output.size());
            assertEquals(message, page.findPreference(CONFIGURATION).getSummary());
        }
    }

    @Test public void restartingTheProcessDropsReceiptsAndRejectsOldCompletions() {
        ExportStatus status = ExportStatus.DIAGNOSTICS;
        String old = status.begin("old operation", false);
        assertNull(status.begin("duplicate", false));
        SettingsContextRule.restartExportProcessForTests();
        assertNull(status.state());
        String current = status.begin("current operation", false);
        assertFalse(status.finish(old, "old result"));
        assertEquals("current operation", status.state().message);
        assertTrue(status.finish(current, "current result"));
        assertFalse(status.finish(current, "late failure"));
        SettingsContextRule.restartExportProcessForTests();
        assertNull(status.state());
    }

    @Test public void aPendingPickerCanFinishAfterTheProcessReceiptsAreLost() throws Exception {
        Bundle saved = new Bundle();
        ActivityController<Activity> old = Robolectric.buildActivity(Activity.class).setup();
        HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(old);
        ShadowActivity.IntentForResult picked = pick(page, old.get());
        old.pause().saveInstanceState(saved).stop().destroy();
        SettingsContextRule.restartExportProcessForTests();
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).create(saved).start().resume()) {
            page = (HushgramPreferenceFragment) host.get().getFragmentManager().findFragmentById(android.R.id.content);
            assertNotNull(page);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, output);
            shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
            finish();
            assertTrue(output.size() > 0);
            assertEquals("HushGram settings exported.", page.findPreference(CONFIGURATION).getSummary());
        }
    }

    private static ShadowActivity.IntentForResult pick(HushgramPreferenceFragment page, Activity host) {
        Preference row = page.findPreference(CONFIGURATION);
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowActivity.IntentForResult request = shadowOf(host).getNextStartedActivityForResult();
        assertNotNull(request);
        return request;
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}
