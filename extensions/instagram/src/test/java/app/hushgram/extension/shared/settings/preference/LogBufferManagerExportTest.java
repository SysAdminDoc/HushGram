/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.hushgram.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Rule;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {30, 37})
public class LogBufferManagerExportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Before public void hostTarget() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
    }
    @Config(sdk = {28, 30, 37})
    @Test public void noExitOnRecordMeansNoLastExitSection() {
        // Android has kept no exit for this process yet, so the section has to be absent rather
        // than empty or guessed at.
        app.hushgram.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.hushgram.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.hushgram.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.hushgram.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");

        String report = LogBufferManager.buildExportText();
        assertNotEquals("nothing was reported at all", "", report);
        assertTrue("the report claimed to know why the process went away: " + report,
                report.indexOf("[LAST EXIT]") < 0);
    }

    @Test
    public void theReportSaysWhyTheProcessWentAwayLastTime() throws Exception {
        // A Java crash handler sees none of the ways the system ends an app. Android 17 kills one
        // that goes over a RAM-proportional limit and leaves only this behind.
        app.hushgram.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.hushgram.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.hushgram.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();

        android.app.ApplicationExitInfo exit =
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(13)
                .setTimestamp(1757260800000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                .setDescription("MemoryLimiter:AnonSwap")
                .build();

        // Instagram runs several processes, and the most recent record is routinely a background
        // helper the system reaped. Reporting that as why the app went away is worse than saying
        // nothing, so the newer of the two here must be passed over.
        android.app.ApplicationExitInfo helper = org.robolectric.shadows.ShadowActivityManager
                .ApplicationExitInfoBuilder.newBuilder()
                .setReason(10)
                .setTimestamp(1757260900000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName() + ":push")
                .setDescription("a background helper nobody asked about")
                .build();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(exit);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(helper);

        // Every report asked for carries the line, a healthy one with nothing else found included.
        String report = LogBufferManager.buildExportText();
        assertTrue("the report does not say why the process went away: " + report,
                report.contains("[LAST EXIT]"));
        assertTrue("the reason was not named: " + report, report.contains("reason: OTHER"));
        assertTrue("the description the kill carried was dropped: " + report,
                report.contains("MemoryLimiter:AnonSwap"));
        assertTrue("a background helper's exit was reported as the app's: " + report,
                report.indexOf("a background helper nobody asked about") < 0);
        java.text.SimpleDateFormat utc =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String when = utc.format(new java.util.Date(1757260800000L));
        assertTrue("the time it happened was left out or in another format: " + report,
                report.contains(when));
    }

    /**
     * Android 9 has no Downloads collection, so the report goes to Instagram's own folder on
     * shared storage, and the path handed back is the one a file manager shows.
     */
    @Config(manifest = Config.NONE, sdk = 28)
    @Test public void android9SavesTheReportInInstagramsOwnFolder() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String report = "MORPHE DIAGNOSTIC REPORT\nschema: 1\n";

        String saved = LogBufferManager.writeToFile(context, report);

        String folder = "Android/data/" + context.getPackageName() + "/files/"
                + Environment.DIRECTORY_DOWNLOADS + "/Morphe/";
        assertTrue(saved, saved.startsWith(folder + "morphe-diagnostics-") && saved.endsWith(".txt"));
        java.io.File file = new java.io.File(context.getExternalFilesDir(null),
                Environment.DIRECTORY_DOWNLOADS + "/Morphe/" + saved.substring(folder.length()));
        assertTrue(file.getPath(), file.isFile());
        assertEquals(report, new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Config(manifest = Config.NONE, sdk = {28, 29, 37})
    @Test public void reportRowAndChooserNameTheFolderThatTheWriterUses() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                    downloads.uriFor(1), new ByteArrayOutputStream());
        }
        String saved = LogBufferManager.writeToFile(context, "diagnostic report");
        String folder = saved.substring(0, saved.lastIndexOf('/'));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = new HushgramPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction()
                    .add(android.R.id.content, page).commitNow();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            android.preference.Preference export = page.findPreference("action_export_diagnostic_report");
            String summary = String.valueOf(export.getSummary());
            assertTrue(summary, summary.contains(folder));
            assertTrue(export.getOnPreferenceClickListener().onPreferenceClick(export));
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            android.widget.ListView choices = chooser.getListView();
            View save = choices.getAdapter().getView(1, null, choices);
            String description = textIn(save);
            assertTrue(description, description.contains(folder));
            chooser.dismiss();
        } finally {
            app.hushgram.extension.shared.Utils.awaitBackgroundTasksForTests();
        }
    }

    private static String textIn(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) text.append(' ').append(textIn(group.getChildAt(index)));
        }
        return text.toString();
    }

    /**
     * On Android 10 and newer the report goes through MediaStore into Download/Morphe. The path handed back is the name MediaStore gave the file, which differs
     * from the one asked for when a file of that name is already there.
     */
    @Test public void repeatedExportsEachGetTheirOwnDownloadsEntry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
        ByteArrayOutputStream firstBody = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBody = new ByteArrayOutputStream();
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        resolver.registerOutputStream(downloads.uriFor(1), firstBody);
        resolver.registerOutputStream(downloads.uriFor(2), secondBody);

        String report = "MORPHE DIAGNOSTIC REPORT\nschema: 1\n";
        String first = LogBufferManager.writeToFile(context, report);
        String second = LogBufferManager.writeToFile(context, report);

        String folder = Environment.DIRECTORY_DOWNLOADS + "/Morphe/";
        assertTrue(first, first.startsWith(folder + "morphe-diagnostics-") && first.endsWith(".txt"));
        assertTrue(second, second.startsWith(folder + "morphe-diagnostics-") && second.endsWith(".txt"));
        assertNotEquals(first, second);
        assertEquals(folder + downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME), first);
        assertEquals(folder + downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME), second);
        for (int id = 1; id <= 2; id++) {
            assertEquals("entry " + id + " was left pending", Integer.valueOf(0),
                    downloads.row(id).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertEquals(Environment.DIRECTORY_DOWNLOADS + "/Morphe",
                    downloads.row(id).getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        }
        assertEquals(report, firstBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals(report, secondBody.toString(StandardCharsets.UTF_8.name()));
    }

    /**
     * The file's place is a value set into a sentence, so the toast isolates it: a right-to-left
     * sentence then keeps "Download/Morphe/..." in the order it was written.
     */
    @Test public void theSavedPathIsIsolatedInItsToast() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(downloads.uriFor(1), new ByteArrayOutputStream());
        app.hushgram.extension.shared.Utils.setContext(context);
        app.hushgram.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.hushgram.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.hushgram.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");
        org.robolectric.shadows.ShadowToast.reset();

        LogBufferManager.exportToFile();
        String toast = null;
        long until = System.currentTimeMillis() + 20_000;
        while (toast == null && System.currentTimeMillis() < until) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
            if (toast == null) Thread.sleep(50);
        }

        assertNotNull("the export said nothing", toast);
        String saved = Environment.DIRECTORY_DOWNLOADS + "/Morphe/" + downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
        assertEquals("Full report saved to " + app.hushgram.extension.shared.L10n.isolate(saved), toast);
    }

    /**
     * Both exports end to end, with the redactor's credential corpus in every place a report takes
     * text from: buffered events, the saved crash and a section the bundle registers. No synthetic
     * secret reaches the clipboard or the saved file, and the build lines, timestamps and stack
     * frames a maintainer reads the report for arrive intact.
     */
    @Test public void neitherExportCarriesASyntheticCredential() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        app.hushgram.extension.shared.Utils.setContext(context);
        app.hushgram.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.hushgram.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        String[][] corpus = app.hushgram.extension.shared.diagnostics.DiagnosticRedactorTest.CREDENTIAL_CORPUS;
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder crash = new StringBuilder("complete: true\njava.io.IOException: 401\n");
        for (String[] row : corpus) {
            LogBufferManager.appendEvent(app.hushgram.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                    "Probe", "INFO", row[0]);
            lines.add(row[0]);
            crash.append(row[0]).append('\n');
        }
        String frame = "\tat app.hushgram.extension.instagram.download.Downloader.connect(Downloader.java:120)";
        crash.append(frame).append('\n');
        LogBufferManager.persistCrashReport(context, crash.toString());
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override public String title() {
                return "PROBE";
            }

            @Override public java.util.List<String> lines() {
                return lines;
            }
        });
        try {
            android.content.ClipboardManager clipboard = context.getSystemService(android.content.ClipboardManager.class);
            LogBufferManager.exportToClipboard();
            app.hushgram.extension.shared.Utils.awaitBackgroundTasksForTests();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            String copied = String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText());

            Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(downloads.uriFor(1), body);
            LogBufferManager.exportToFile();
            app.hushgram.extension.shared.Utils.awaitBackgroundTasksForTests();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            String saved = body.toString(StandardCharsets.UTF_8.name());

            String[][] exports = {{"clipboard", copied}, {"file", saved}};
            for (String[] export : exports) {
                String where = export[0];
                String text = export[1];
                assertTrue(where + " holds no report: " + text, text.startsWith("MORPHE DIAGNOSTIC REPORT\n"));
                for (String[] row : corpus) {
                    for (int i = 1; i < row.length; i++) {
                        assertTrue(row[i] + " reached the " + where + ":\n" + text, text.indexOf(row[i]) < 0);
                    }
                }
                assertTrue(where + " lost the app line: " + text,
                        text.contains("\napp: " + context.getPackageName() + " "));
                assertTrue(where + " lost the abi line: " + text, text.contains("\nabi: app "));
                assertTrue(where + " lost the report time: " + text,
                        java.util.regex.Pattern.compile("\ngenerated_utc: \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z\n")
                                .matcher(text).find());
                assertTrue(where + " lost the event times: " + text,
                        java.util.regex.Pattern.compile("\ndownloads \\| \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z \\| ")
                                .matcher(text).find());
                assertTrue(where + " lost the stack frame: " + text, text.contains(frame + "\n"));
                assertTrue(where + " lost the crash heading: " + text, text.contains("[LATEST JAVA CRASH]"));
                assertTrue(where + " lost the section: " + text, text.contains("[PROBE]"));
            }
        } finally {
            LogBufferManager.clearReportSectionsForTests();
            LogBufferManager.clearLogBuffer();
        }
    }

    /** MediaStore's Downloads table, as much of it as an export touches. */
    public static final class Downloads extends ContentProvider {
        private final Map<Long, ContentValues> rows = new HashMap<>();
        private long nextId = 1;

        Uri uriFor(long id) {
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
        }

        ContentValues row(long id) {
            ContentValues row = rows.get(id);
            assertTrue("no Downloads entry " + id, row != null);
            return row;
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            ContentValues row = new ContentValues(values);
            // MediaStore keeps names in a folder unique by numbering the newcomer.
            String name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            for (ContentValues other : rows.values()) {
                if (name.equals(other.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) {
                    row.put(MediaStore.MediaColumns.DISPLAY_NAME, name.replace(".txt", " (1).txt"));
                }
            }
            long id = nextId++;
            rows.put(id, row);
            return uriFor(id);
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(projection);
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row != null) {
                Object[] values = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) values[i] = row.get(projection[i]);
                cursor.addRow(values);
            }
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "text/plain";
        }
    }
}
