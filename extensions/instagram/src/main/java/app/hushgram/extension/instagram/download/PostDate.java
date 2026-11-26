/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Date;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;

/**
 * Dates a saved photo or video by when its post went up (#792), so the gallery sorts saves by
 * the post rather than by the day they were saved.
 *
 * <p>Android's gallery reads the date from the file. For a JPEG it's the EXIF DateTimeOriginal,
 * which it takes for the phone's local time unless OffsetTimeOriginal says otherwise, so the time
 * goes in as UTC with an offset of +00:00. For an MP4 it's the movie header's creation time, and
 * the track and media headers get it too, for other players. An MKV gets it as its DateUTC, which
 * players and ffprobe read, though Android's own reader doesn't. Every file then gets it as its
 * modified time, which the gallery and every file manager read, and the gallery reads the file
 * again. When the gallery still has another date taken, it's asked to take the post's.
 *
 * <p>It runs after the save is published and after the source tags, and the modified time goes
 * last, since every write moves it. A file in a folder picked in settings gets the date inside it
 * only: a document there has no modified time an app can set. Fail open: the save already
 * succeeded, so a step that throws is logged and the file keeps what the steps before it wrote.
 * It reads only {@link Settings#SAVE_POST_DATE}, on the save worker, and never runs at
 * Instagram's startup.
 */
public final class PostDate {

    private PostDate() {}

    /** On only when the setting is on and ready. Never waits on settings. */
    static boolean on() {
        return Utils.settingsReady() && Settings.SAVE_POST_DATE.get();
    }

    /** Dates the file the save just published, when the switch is on and the save knows the post time. */
    static void apply(Context context, MediaStoreWriter writer, PostDetails details) {
        if (writer == null || details == null || !details.hasPosted() || !on()) return;
        writeTo(context, writer.publishedItem(), writer.savedMime(), details.posted);
    }

    /**
     * The dating itself, of the file published at [published] with MIME [mime]: a {@code file://}
     * or bare path on Android 9, the gallery's {@code content://} entry from Android 10, or a
     * picked folder's document. The switch is the caller's to check. Never throws.
     */
    static void writeTo(Context context, String published, String mime, Date posted) {
        if (context == null || published == null || posted == null || posted.getTime() <= 0) return;
        try {
            Uri uri = Uri.parse(published);
            String scheme = uri.getScheme();
            // A path from the root is a path whatever colon it holds: Uri reads one before the
            // first colon as a scheme.
            boolean bare = scheme == null || published.startsWith("/");
            boolean plain = bare || "file".equals(scheme);
            boolean gallery = !plain && MediaStore.AUTHORITY.equals(uri.getAuthority());
            String path = bare ? published : plain ? uri.getPath() : gallery ? fileOf(context, uri) : null;
            if (plain && (path == null || !new File(path).isFile())) return;

            String inside = inside(context, plain ? null : uri, path, mime, posted);
            // Last: every write above moved it to now.
            boolean touched = path != null && new File(path).setLastModified(posted.getTime());
            MediaSave.info(() -> "dated the saved file by its post: " + inside
                + (touched ? ", and its modified time" : path == null ? ", a picked folder's file keeping its modified time"
                    : ", though its modified time couldn't be set"));
            if (path != null && (plain || gallery)) rescan(context, path, mime, plain ? null : uri, posted, true);
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not date a saved file by its post", t);
        }
    }

    /** What went in the file itself, for the report: the EXIF date, the MP4's headers or the MKV's DateUTC. */
    private static String inside(Context context, Uri uri, String path, String mime, Date posted) {
        try {
            if ("image/jpeg".equals(mime)) {
                if (uri == null) {
                    ExifInterface exif = new ExifInterface(path);
                    PostMetadata.dates(exif, posted);
                    exif.saveAttributes();
                } else {
                    try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                        if (descriptor == null) return "no way into the photo";
                        ExifInterface exif = new ExifInterface(descriptor.getFileDescriptor());
                        PostMetadata.dates(exif, posted);
                        exif.saveAttributes();
                    }
                }
                return "the photo's EXIF date";
            }
            boolean mkv = MkvJoin.MIME.equals(mime);
            if (!mkv && !"video/mp4".equals(mime) && !"video/x-m4v".equals(mime)) return "nothing inside a " + mime;
            boolean wrote;
            if (uri == null) {
                try (RandomAccessFile file = new RandomAccessFile(path, "rw")) {
                    VideoMetadata.Store store = VideoMetadata.over(file.getChannel(), file.getChannel());
                    wrote = mkv ? MkvJoin.dated(store, posted) : VideoMetadata.dated(store, posted);
                }
            } else {
                try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                    if (descriptor == null) return "no way into the video";
                    // Android's streams over a descriptor don't own it, so it closes once, last.
                    try (FileInputStream in = new FileInputStream(descriptor.getFileDescriptor());
                         FileOutputStream out = new FileOutputStream(descriptor.getFileDescriptor())) {
                        VideoMetadata.Store store = VideoMetadata.over(in.getChannel(), out.getChannel());
                        wrote = mkv ? MkvJoin.dated(store, posted) : VideoMetadata.dated(store, posted);
                    }
                }
            }
            return !wrote ? "nothing inside, the video's layout not being one the date is written into"
                : mkv ? "the MKV's DateUTC" : "the MP4's movie and track times";
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not write the post time into a saved file", t);
            return "nothing inside, the write failing";
        }
    }

    /** The file behind the gallery's entry [uri], or null when the gallery doesn't say. */
    private static String fileOf(Context context, Uri uri) {
        try (Cursor row = context.getContentResolver().query(uri,
                new String[] { MediaStore.MediaColumns.DATA }, null, null, null)) {
            if (row == null || !row.moveToFirst() || row.isNull(0)) return null;
            String path = row.getString(0);
            return path == null || path.isEmpty() ? null : path;
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not find the saved file behind its gallery entry", t);
            return null;
        }
    }

    /**
     * The gallery reads [path] again, so it takes the dates just written, then checks them on the
     * scanner's thread. A scan Android started when the save's own write closed can finish after
     * this one with the time of that write, so a modified time read back wrong gets one more scan.
     */
    private static void rescan(Context context, String path, String mime, Uri entry, Date posted, boolean again) {
        try {
            MediaScannerConnection.scanFile(context, new String[] { path }, new String[] { mime },
                (scanned, found) -> checked(context, path, mime, entry != null ? entry : found, posted, again));
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not tell the gallery about the dated file", t);
        }
    }

    /** What the gallery has for [entry] now, and the date taken asked for when it has another. Never throws. */
    static void checked(Context context, String path, String mime, Uri entry, Date posted, boolean again) {
        try {
            if (entry == null) {
                MediaSave.info(() -> "the gallery has no entry for the dated file yet");
                return;
            }
            long[] dates = dates(context.getContentResolver(), entry);
            if (dates == null) return;
            long want = posted.getTime();
            boolean modified = dates[1] == want / 1000;
            if (!modified && again) {
                rescan(context, path, mime, entry, posted, false);
                return;
            }
            boolean taken = dates[0] / 1000 == want / 1000;
            if (!taken) taken = askTaken(context.getContentResolver(), entry, want);
            boolean takenNow = taken;
            MediaSave.info(() -> "the gallery dates the saved file " + (takenNow ? "taken at the post time" : "taken "
                + (dates[0] <= 0 ? "at no time" : "at another time")) + ", modified "
                + (modified ? "at the post time" : "at another time"));
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not read the gallery's dates for the dated file", t);
        }
    }

    /** The entry's date taken in milliseconds (0 when it has none) and modified time in seconds, or null. */
    private static long[] dates(ContentResolver resolver, Uri entry) {
        try (Cursor row = resolver.query(entry, new String[] {
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED }, null, null, null)) {
            if (row == null || !row.moveToFirst()) return null;
            return new long[] { row.isNull(0) ? 0 : row.getLong(0), row.isNull(1) ? 0 : row.getLong(1) };
        }
    }

    /**
     * Asks the gallery to take [millis] as the entry's date taken, for a file it reads none from,
     * an MKV's. True only when it reads back. The gallery can refuse an app this, and that's no failure.
     */
    private static boolean askTaken(ContentResolver resolver, Uri entry, long millis) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DATE_TAKEN, millis);
            resolver.update(entry, values, null, null);
            long[] dates = dates(resolver, entry);
            return dates != null && dates[0] / 1000 == millis / 1000;
        } catch (Throwable refused) {
            MediaSave.info(() -> "the gallery keeps its own date taken: " + refused.getClass().getSimpleName());
            return false;
        }
    }
}
