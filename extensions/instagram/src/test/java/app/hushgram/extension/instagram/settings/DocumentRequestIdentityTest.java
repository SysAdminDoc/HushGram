/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;

import java.io.ByteArrayOutputStream;
import java.util.EnumSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/** Android's result must identify the picker invocation, not just the kind of document. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class DocumentRequestIdentityTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String[] PICKERS = {"hushgram_export_configuration", "hushgram_import_configuration",
            "hushgram_export_overrides", "hushgram_validate_overrides", "hushgram_import_overrides"};
    private static final Uri OLD = Uri.parse("content://documents/earlier.json");
    private static final Uri CURRENT = Uri.parse("content://documents/current.json");

    @Before public void setup() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        Settings.ALLOW_OVERRIDE_IMPORT.save(true);
    }

    @After public void restore() throws Exception {
        finish();
        PatchFamily.inBuildForTests = null;
    }

    @Test public void anEarlierCancellationCannotConsumeAnyNewerPicker() throws Exception {
        for (String key : PICKERS) {
            try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
                HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
                ShadowActivity.IntentForResult earlier = pick(page, host.get(), key);
                result(page, earlier, Activity.RESULT_CANCELED, null);
                ShadowActivity.IntentForResult current = pick(page, host.get(), key);
                result(page, earlier, Activity.RESULT_CANCELED, null);
                assertFalse(key + " lost its current picker", page.findPreference(key).isEnabled());
                result(page, current, Activity.RESULT_CANCELED, null);
                assertTrue(key, page.findPreference(key).isEnabled());
            }
        }
    }

    @Test public void anEarlierSuccessCannotWriteTheWrongDocumentOrFinishTheCurrentExport() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            ByteArrayOutputStream oldOutput = new ByteArrayOutputStream(), currentOutput = new ByteArrayOutputStream();
            shadowOf(host.get().getContentResolver()).registerOutputStream(OLD, oldOutput);
            shadowOf(host.get().getContentResolver()).registerOutputStream(CURRENT, currentOutput);
            ShadowActivity.IntentForResult earlier = pick(page, host.get(), PICKERS[0]);
            result(page, earlier, Activity.RESULT_CANCELED, null);
            ShadowActivity.IntentForResult current = pick(page, host.get(), PICKERS[0]);
            result(page, earlier, Activity.RESULT_OK, OLD);
            assertEquals("an earlier result wrote the wrong file", 0, oldOutput.size());
            assertFalse(page.findPreference(PICKERS[0]).isEnabled());
            result(page, current, Activity.RESULT_OK, CURRENT);
            assertTrue(currentOutput.size() > 0);
            assertEquals("HushGram settings exported.", page.findPreference(PICKERS[0]).getSummary());
        }
    }

    @Test public void aCompletedExportCannotCancelTheNextOne() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            shadowOf(host.get().getContentResolver()).registerOutputStream(OLD, output);
            ShadowActivity.IntentForResult earlier = pick(page, host.get(), PICKERS[0]);
            result(page, earlier, Activity.RESULT_OK, OLD);
            assertTrue(output.size() > 0);
            ShadowActivity.IntentForResult current = pick(page, host.get(), PICKERS[0]);
            result(page, earlier, Activity.RESULT_CANCELED, null);
            assertFalse(page.findPreference(PICKERS[0]).isEnabled());
            result(page, current, Activity.RESULT_CANCELED, null);
            assertEquals("Settings export canceled.", page.findPreference(PICKERS[0]).getSummary());
        }
    }

    @Test public void recreationKeepsTheCurrentInvocationAndRejectsItsPredecessor() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            ShadowActivity.IntentForResult earlier = pick(page, host.get(), PICKERS[0]);
            result(page, earlier, Activity.RESULT_CANCELED, null);
            ShadowActivity.IntentForResult current = pick(page, host.get(), PICKERS[0]);
            host.recreate();
            ShadowLooper.idleMainLooper();
            HushgramPreferenceFragment restored = (HushgramPreferenceFragment) host.get()
                    .getFragmentManager().findFragmentById(android.R.id.content);
            assertNotSame(page, restored);
            result(restored, earlier, Activity.RESULT_CANCELED, null);
            assertFalse(restored.findPreference(PICKERS[0]).isEnabled());
            result(restored, current, Activity.RESULT_CANCELED, null);
            assertTrue(restored.findPreference(PICKERS[0]).isEnabled());
        }
    }

    @Test public void processRestorationDoesNotReuseACancelledPickersCode() throws Exception {
        java.lang.reflect.Field sequence = HushgramPreferenceFragment.class.getDeclaredField("documentSequence");
        sequence.setAccessible(true);
        int startupSequence = sequence.getInt(null);
        Bundle saved = new Bundle();
        ShadowActivity.IntentForResult earlier;
        ActivityController<Activity> old = Robolectric.buildActivity(Activity.class).setup();
        HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(old);
        earlier = pick(page, old.get(), PICKERS[0]);
        result(page, earlier, Activity.RESULT_CANCELED, null);
        old.pause().saveInstanceState(saved).stop().destroy();
        SettingsContextRule.restartExportProcessForTests();
        sequence.setInt(null, startupSequence);
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).create(saved).start().resume()) {
            page = (HushgramPreferenceFragment) host.get().getFragmentManager().findFragmentById(android.R.id.content);
            ShadowActivity.IntentForResult current = pick(page, host.get(), PICKERS[0]);
            result(page, earlier, Activity.RESULT_CANCELED, null);
            assertFalse(page.findPreference(PICKERS[0]).isEnabled());
            result(page, current, Activity.RESULT_CANCELED, null);
            assertTrue(page.findPreference(PICKERS[0]).isEnabled());
        }
    }

    private static ShadowActivity.IntentForResult pick(HushgramPreferenceFragment page, Activity host, String key) {
        Preference row = page.findPreference(key);
        assertNotNull(key, row);
        assertTrue(key, row.isEnabled());
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowActivity.IntentForResult picked = shadowOf(host).getNextStartedActivityForResult();
        assertNotNull(picked);
        return picked;
    }

    private static void result(HushgramPreferenceFragment page, ShadowActivity.IntentForResult picked,
                               int result, Uri uri) throws Exception {
        page.onActivityResult(picked.requestCode, result, uri == null ? null : new Intent().setData(uri));
        finish();
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}
