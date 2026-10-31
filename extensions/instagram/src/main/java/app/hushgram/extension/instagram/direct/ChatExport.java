/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Saves an exported chat (#247) as a text file in Downloads, in a HushGram folder. Android 10 and
 * newer put it in the public Download folder through MediaStore. Android 9 has no folder this app
 * may write to without a permission it never asks for, so the file goes in the app's own Download
 * folder instead.
 */
public final class ChatExport {
    static final String FOLDER = "HushGram";
    private static final String MIME = "text/plain";
    /** The most characters of a chat's name that go in the file name, so a toast naming it fits. */
    static final int TITLE_CHARS = 14;

    private ChatExport() {
    }

    /** "HushGram chat Sam 2026-10-30 1405.txt": the chat's name, cleaned for a file name, and the time. */
    static String fileName(String title, long millis) {
        String clean = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        if (clean.length() > TITLE_CHARS) clean = clean.substring(0, TITLE_CHARS).trim();
        while (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1).trim();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(new Date(millis));
        return "HushGram chat " + (clean.isEmpty() ? "" : clean + " ") + stamp + ".txt";
    }

    /** Whether this phone keeps the file in the app's own folder rather than the public Download folder. */
    static boolean appFolderOnly() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /**
     * Writes [text] as [name]. True once the file is saved; false (after logging why) when it isn't,
     * with nothing left behind.
     */
    static boolean save(Context context, String name, String text) {
        try {
            // Spelled out rather than appFolderOnly() so lint sees the API 29 guard.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) saveInAppFolder(context, name, text);
            else saveInDownloads(context, name, text);
            return true;
        } catch (Throwable t) {
            if (t instanceof java.nio.channels.ClosedByInterruptException) Thread.currentThread().interrupt();
            HookStatus.threw(FamilyNames.CHAT_HISTORY, "chat export", t);
            return false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void saveInDownloads(Context context, String name, String text) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) throw new IOException("MediaStore gave no place for the chat file");
        boolean done = false;
        try {
            try (OutputStream out = resolver.openOutputStream(item)) {
                if (out == null) throw new IOException("MediaStore opened no stream for the chat file");
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            ContentValues finished = new ContentValues();
            finished.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(item, finished, null, null) < 1) throw new IOException("the chat file stayed pending");
            done = true;
        } finally {
            if (!done) {
                try {
                    resolver.delete(item, null, null);
                } catch (Throwable ignored) {
                    // Nothing more can be done for a half-written entry.
                }
            }
        }
    }

    private static void saveInAppFolder(Context context, String name, String text) throws IOException {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(context.getFilesDir(), "Download");
        File folder = new File(base, FOLDER);
        if (!folder.mkdirs() && !folder.isDirectory()) throw new IOException("no folder for the chat file");
        File file = new File(folder, name);
        File part = new File(folder, name + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw failure;
        }
        if (file.exists() && !file.delete()) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("an older chat file of that name stays");
        }
        if (!part.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("the chat file couldn't be finished");
        }
    }
}
