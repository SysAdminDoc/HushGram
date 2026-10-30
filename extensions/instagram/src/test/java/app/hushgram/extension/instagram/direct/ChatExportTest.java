/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.shared.SettingsContextRule;

/** The text file an exported chat is saved as (#247): its name, and where it lands on each Android. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ChatExportTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private Downloads downloads;
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();

    /** MediaStore's Downloads table, as much of it as a save touches. */
    public static final class Downloads extends ContentProvider {
        final Map<Long, ContentValues> rows = new HashMap<>();
        final List<Uri> inserts = new ArrayList<>();
        boolean refuseUpdate;
        private long nextId = 1;

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            long id = nextId++;
            rows.put(id, new ContentValues(values));
            Uri item = ContentUris.withAppendedId(uri, id);
            inserts.add(item);
            return item;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            return null;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            if (refuseUpdate) return 0;
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "text/plain";
        }
    }

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        if (Build.VERSION.SDK_INT >= 29) {
            downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
            Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, 1), written);
        }
    }

    @After public void clean() {
        File folder = new File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), ChatExport.FOLDER);
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    @Test public void theNameHasTheChatAndTheTime() {
        assertEquals("HushGram chat Sam 2026-10-30 1405.txt", ChatExport.fileName("Sam", at(2026, 10, 30, 14, 5)));
    }

    @Test public void aNameWithNoChatTitleHasOnlyTheTime() {
        assertEquals("HushGram chat 2026-10-30 1405.txt", ChatExport.fileName(null, at(2026, 10, 30, 14, 5)));
        assertEquals("HushGram chat 2026-10-30 1405.txt", ChatExport.fileName("  ", at(2026, 10, 30, 14, 5)));
    }

    @Test public void aTitleLosesWhatAFileNameCantHoldAndIsCut() {
        assertEquals("HushGram chat a b c d 2026-01-02 0304.txt",
                ChatExport.fileName("a/b\\c:d", at(2026, 1, 2, 3, 4)));
        String name = ChatExport.fileName("A very long group chat name indeed", at(2026, 1, 2, 3, 4));
        assertEquals("HushGram chat A very long gr 2026-01-02 0304.txt", name);
        assertFalse(ChatExport.fileName("What?*\"<>|", at(2026, 1, 2, 3, 4)).matches(".*[?*\"<>|].*"));
        assertEquals("HushGram chat Sam 2026-01-02 0304.txt", ChatExport.fileName("Sam...", at(2026, 1, 2, 3, 4)));
        assertTrue(name.length() <= 50);
    }

    @Test public void theTextIsSavedWhereThisAndroidKeepsDownloads() throws Exception {
        String text = "Chat with Sam\n1 message\n\n2026-01-02 03:04 Sam: héllo\n";
        assertTrue(ChatExport.save(context, "HushGram chat Sam 2026-01-02 0304.txt", text));
        if (Build.VERSION.SDK_INT >= 29) {
            assertFalse(ChatExport.appFolderOnly());
            assertEquals(1, downloads.inserts.size());
            ContentValues row = downloads.rows.get(1L);
            assertNotNull(row);
            assertEquals("HushGram chat Sam 2026-01-02 0304.txt", row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
            assertEquals("text/plain", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
            assertEquals("Download/HushGram", row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            assertEquals("the file is released when it is whole", 0, (int) row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertEquals(text, new String(written.toByteArray(), StandardCharsets.UTF_8));
        } else {
            assertTrue(ChatExport.appFolderOnly());
            File file = new File(new File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                    ChatExport.FOLDER), "HushGram chat Sam 2026-01-02 0304.txt");
            assertTrue(file.isFile());
            assertEquals(text, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            assertFalse("no half-written file stays", new File(file.getPath() + ".part").exists());
        }
    }

    @Test public void aSecondSaveOfTheSameNameReplacesTheFirstOnTheAppFolder() throws Exception {
        if (Build.VERSION.SDK_INT >= 29) return;
        assertTrue(ChatExport.save(context, "same.txt", "one"));
        assertTrue(ChatExport.save(context, "same.txt", "two"));
        File file = new File(new File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                ChatExport.FOLDER), "same.txt");
        assertEquals("two", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void aFileMediaStoreWontFinishIsRemovedAndReportedAsNotSaved() {
        if (Build.VERSION.SDK_INT < 29) return;
        downloads.refuseUpdate = true;
        assertFalse(ChatExport.save(context, "x.txt", "text"));
        assertTrue("nothing half-saved stays", downloads.rows.isEmpty());
    }
}
