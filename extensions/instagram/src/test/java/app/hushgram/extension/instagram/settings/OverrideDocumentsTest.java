/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Looper;
import android.preference.Preference;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.misc.DeveloperOptions;
import app.hushgram.extension.instagram.misc.OverrideExchange;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = OverrideDocumentsTest.NativeReader.class)
@SuppressWarnings("deprecation")
public class OverrideDocumentsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final Uri DOCUMENT = Uri.parse("content://override-documents/snapshot.json");
    private static final byte[] NATIVE = "{\"123:config\":[\"0: enabled: true\"]}".getBytes(StandardCharsets.UTF_8);
    private ActivityController<HostActivity> host;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        HushgramPreferenceFragment.overrideExportFeedback = null;
        HushgramPreferenceFragment.overrideValidationFeedback = null;
        NativeReader.file = null;
        NativeReader.available = true;
        NativeReader.calls = 0;
        NativeReader.fail = false;
        NativeReader.worker = false;
        HostActivity.noPicker = false;
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        PackageInfo info = new PackageInfo();
        info.packageName = "com.instagram.android";
        info.versionName = "449.0.0.52.84";
        info.setLongVersionCode(385511871);
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = info.packageName;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(info);
    }

    @After public void close() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        if (host != null) host.close();
        PatchFamily.inBuildForTests = null;
        HushgramPreferenceFragment.overrideExportFeedback = null;
        HushgramPreferenceFragment.overrideValidationFeedback = null;
    }

    @Test public void installedDeveloperPatchOffersReadOnlyDocumentActions() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DEVELOPER_OPTIONS);
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            assertNotNull(page.findPreference("hushgram_export_overrides"));
            assertNotNull(page.findPreference("hushgram_validate_overrides"));
            assertNull(page.findPreference("hushgram_import_overrides"));
        }
    }

    @Test public void missingDeveloperPatchHasNoOverrideDocumentActions() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            assertNull(page.findPreference("hushgram_export_overrides"));
            assertNull(page.findPreference("hushgram_validate_overrides"));
        }
    }

    private void openHost(String store) throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DEVELOPER_OPTIONS);
        host = Robolectric.buildActivity(HostActivity.class).setup();
        NativeReader.file = new File(host.get().getFilesDir(), store + "/session-store/mc_overrides.json");
        assertTrue(NativeReader.file.getParentFile().mkdirs() || NativeReader.file.getParentFile().isDirectory());
        Files.write(NativeReader.file.toPath(), NATIVE);
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(host.get().getFragmentManager(), SettingsEntry.DIALOG_TAG);
        host.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    private ShadowActivity.IntentForResult pick(boolean validating) {
        Preference row = page.findPreference(validating ? "hushgram_validate_overrides" : "hushgram_export_overrides");
        assertTrue(row.isEnabled());
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        return Shadows.shadowOf(host.get()).getNextStartedActivityForResult();
    }

    private void result(ShadowActivity.IntentForResult picked, int status, Uri uri) throws Exception {
        Shadows.shadowOf(host.get()).receiveResult(picked.intent, status, uri == null ? null : new Intent().setData(uri));
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private byte[] validFile() throws Exception { return OverrideExchange.export(OverrideExchange.capture(host.get())); }
    private void unchanged() throws Exception {
        assertArrayEquals(NATIVE, Files.readAllBytes(NativeReader.file.toPath()));
        assertTrue(page.findPreference("hushgram_export_overrides").isEnabled());
        assertTrue(page.findPreference("hushgram_validate_overrides").isEnabled());
    }

    @Test public void exportUsesTheResolvedSessionStoreAndClosesTheDocumentBeforeSuccess() throws Exception {
        openHost("mobileconfig_qce");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean[] closed = {false};
        Shadows.shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, new java.io.FilterOutputStream(output) {
            @Override public void close() throws IOException { super.close(); closed[0] = true; }
        });
        ShadowActivity.IntentForResult picked = pick(false);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picked.intent.getAction());
        assertEquals("application/json", picked.intent.getType());
        assertTrue(picked.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("HushGram-overrides.json", picked.intent.getStringExtra(Intent.EXTRA_TITLE));
        result(picked, Activity.RESULT_OK, DOCUMENT);
        assertTrue(closed[0]);
        assertTrue(NativeReader.worker);
        JSONObject file = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        assertEquals(new JSONObject(new String(NATIVE, StandardCharsets.UTF_8)).toString(), file.getJSONObject("overrides").toString());
        assertFalse(output.toString(StandardCharsets.UTF_8.name()).contains("session-store"));
        assertFalse(output.toString(StandardCharsets.UTF_8.name()).contains("mobileconfig_qce"));
        assertTrue(HushgramPreferenceFragment.overrideExportFeedback.startsWith("Overrides exported"));
        unchanged();
    }

    @Test public void changedValidFileIsValidatedOnlyAndLeavesNativeBytesUntouched() throws Exception {
        openHost("mobileconfig");
        JSONObject file = new JSONObject(new String(validFile(), StandardCharsets.UTF_8));
        file.getJSONObject("overrides").put("123:config", new JSONArray().put("0: enabled: false"));
        Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT,
                new ByteArrayInputStream(file.toString().getBytes(StandardCharsets.UTF_8)));
        ShadowActivity.IntentForResult picked = pick(true);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
        result(picked, Activity.RESULT_OK, DOCUMENT);
        assertEquals("Checked 1 overrides only. Nothing was applied.", HushgramPreferenceFragment.overrideValidationFeedback);
        assertTrue(page.findPreference("hushgram_validate_overrides").getSummary().toString().contains("Nothing was applied"));
        unchanged();
    }

    @Test public void cancellationWrongUriAndMissingPickerKeepControlsUsableWithoutNativeReads() throws Exception {
        openHost("mobileconfig");
        result(pick(true), Activity.RESULT_CANCELED, null);
        assertEquals(0, NativeReader.calls);
        result(pick(false), Activity.RESULT_OK, Uri.parse("file:///unused.json"));
        assertEquals(0, NativeReader.calls);
        HostActivity.noPicker = true;
        assertNull(pick(true));
        assertTrue(HushgramPreferenceFragment.overrideValidationFeedback.contains("no file picker"));
        unchanged();
    }

    @Test public void malformedOversizedAndBuildMismatchedDocumentsAreRefusedWithoutWrites() throws Exception {
        openHost("mobileconfig");
        JSONObject mismatched = new JSONObject(new String(validFile(), StandardCharsets.UTF_8));
        mismatched.getJSONObject("host").put("code", 1);
        for (byte[] bytes : new byte[][]{ "bad".getBytes(StandardCharsets.UTF_8), new byte[OverrideExchange.MAX_BYTES + 1], mismatched.toString().getBytes(StandardCharsets.UTF_8) }) {
            Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(bytes));
            result(pick(true), Activity.RESULT_OK, DOCUMENT);
            assertTrue(HushgramPreferenceFragment.overrideValidationFeedback.startsWith("Couldn't validate"));
            unchanged();
        }
    }

    @Test public void inputReadAndCloseFailuresCannotReportValidatedSuccess() throws Exception {
        openHost("mobileconfig");
        byte[] file = validFile();
        for (boolean closing : new boolean[]{false, true}) {
            Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(DOCUMENT,
                    new FilterInputStream(new ByteArrayInputStream(file)) {
                        @Override public int read(byte[] buffer, int start, int count) throws IOException {
                            if (!closing) throw new IOException("controlled read failure");
                            return super.read(buffer, start, count);
                        }
                        @Override public void close() throws IOException { throw new IOException("controlled close failure"); }
                    });
            result(pick(true), Activity.RESULT_OK, DOCUMENT);
            assertTrue(HushgramPreferenceFragment.overrideValidationFeedback.startsWith("Couldn't validate"));
            unchanged();
        }
    }

    @Test public void outputWriteAndCloseFailuresCannotReportExportedSuccess() throws Exception {
        openHost("mobileconfig");
        for (boolean closing : new boolean[]{false, true}) {
            Shadows.shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, new OutputStream() {
                @Override public void write(int value) throws IOException { if (!closing) throw new IOException("controlled write failure"); }
                @Override public void close() throws IOException { throw new IOException("controlled close failure"); }
            });
            result(pick(false), Activity.RESULT_OK, DOCUMENT);
            assertTrue(HushgramPreferenceFragment.overrideExportFeedback.contains("may be incomplete"));
            unchanged();
        }
    }

    @Test public void unsupportedSessionNativeFailureAndRelativePathRefuseBeforeDocumentOutput() throws Exception {
        openHost("mobileconfig");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, output);
        NativeReader.available = false;
        result(pick(false), Activity.RESULT_OK, DOCUMENT);
        NativeReader.available = true;
        NativeReader.fail = true;
        result(pick(false), Activity.RESULT_OK, DOCUMENT);
        NativeReader.fail = false;
        File actual = NativeReader.file;
        NativeReader.file = new File("relative/mc_overrides.json");
        result(pick(false), Activity.RESULT_OK, DOCUMENT);
        NativeReader.file = actual;
        assertEquals(0, output.size());
        unchanged();
    }

    @Test public void absentNativeFileExportsEmptyValuesWithoutCreatingIt() throws Exception {
        openHost("mobileconfig_qce");
        assertTrue(NativeReader.file.delete());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Shadows.shadowOf(host.get().getContentResolver()).registerOutputStream(DOCUMENT, output);
        result(pick(false), Activity.RESULT_OK, DOCUMENT);
        assertFalse(NativeReader.file.exists());
        assertEquals(0, new JSONObject(output.toString(StandardCharsets.UTF_8.name())).getJSONObject("overrides").length());
    }

    public static class HostActivity extends Activity {
        static boolean noPicker;
        @Override public String getPackageName() { return "com.instagram.android"; }
        @Override public void startActivityForResult(Intent intent, int code, android.os.Bundle options) {
            if (noPicker) throw new ActivityNotFoundException("controlled missing picker");
            super.startActivityForResult(intent, code, options);
        }
        @Override public void startActivityFromFragment(android.app.Fragment fragment, Intent intent, int code, android.os.Bundle options) {
            if (noPicker) throw new ActivityNotFoundException("controlled missing picker");
            super.startActivityFromFragment(fragment, intent, code, options);
        }
    }

    @Implements(value = DeveloperOptions.class, isInAndroidSdk = false)
    public static class NativeReader {
        static File file;
        static boolean available, fail, worker;
        static int calls;
        private static final Object MANAGER = new Object();
        @Implementation protected static Object getOverrideStoreNative(Object activity) {
            calls++;
            worker = Looper.myLooper() != Looper.getMainLooper();
            if (fail) throw new IllegalStateException("controlled native failure");
            return available ? MANAGER : null;
        }
        @Implementation protected static File getOverrideFileNative(Object manager) { assertSame(MANAGER, manager); return file; }
        @Implementation protected static java.util.List<?> getOverrideSchemaNative(Object manager) {
            assertSame(MANAGER, manager);
            return Collections.singletonList(new OverrideExchange.Parameter(123, 0, "config", "enabled", 1, 1000));
        }
        @Implementation protected static OverrideExchange.Parameter getOverrideParameterNative(Object parameter) { return (OverrideExchange.Parameter) parameter; }
    }
}
