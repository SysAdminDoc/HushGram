/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;

/**
 * Takes the hidden data out of a saved photo (#699). Instagram's photos carry Meta's FBMD tag in
 * their IPTC block (the JPEG's APP13 segment), an id that can tie a file back to where it was
 * downloaded, and a photo can also hold EXIF and XMP (APP1: camera, place, time) and comments.
 * Those three kinds of segment are dropped. Everything the picture needs stays byte for byte: the
 * frame, the tables, the scan, the JFIF header, the color profile (APP2) and Adobe's color marker
 * (APP14).
 *
 * <p>It runs right after the save publishes the file and before {@link PostMetadata}, so the
 * source tags still go in when that switch is on. Fail open: the photo is already saved, so a
 * file this can't read or rewrite is left as it was.
 */
public final class PhotoScrub {
    private PhotoScrub() {}

    /** Larger files are left alone; a photo save is a few megabytes at most. */
    static final int MAX_BYTES = 64 * 1024 * 1024;

    private static final int APP1 = 0xE1;
    private static final int APP13 = 0xED;
    private static final int COM = 0xFE;
    private static final int SOS = 0xDA;
    private static final int EOI = 0xD9;

    /** On only when the setting is on and ready. Never waits on settings. */
    static boolean on() {
        return Utils.settingsReady() && Settings.SAVE_PHOTO_SCRUB.get();
    }

    /**
     * [jpeg] without its APP1, APP13 and comment segments, or null when it isn't a JPEG this can
     * read to its scan or holds none of them. Every other byte is copied as it was, in order, and
     * everything from the scan on is copied whole.
     */
    static byte[] strip(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);
        out.write(jpeg, 0, 2);
        boolean dropped = false;
        int at = 2;
        while (true) {
            if (at + 1 >= jpeg.length || (jpeg[at] & 0xFF) != 0xFF) return null;
            // Any number of 0xFF fill bytes may come before a marker.
            int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++;
                continue;
            }
            if (marker == EOI) {
                out.write(jpeg, at, jpeg.length - at);
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                out.write(jpeg, at, 2);
                at += 2;
                continue;
            }
            if (at + 3 >= jpeg.length) return null;
            int length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
            if (length < 2 || at + 2 + length > jpeg.length) return null;
            if (marker == SOS) {
                out.write(jpeg, at, jpeg.length - at);
                break;
            }
            if (marker == APP1 || marker == APP13 || marker == COM) {
                dropped = true;
            } else {
                out.write(jpeg, at, 2 + length);
            }
            at += 2 + length;
        }
        return dropped ? out.toByteArray() : null;
    }

    /** Strips the JPEG the save just published, when the switch is on. Any other file is left untouched. */
    static void scrub(Context context, MediaStoreWriter writer) {
        if (writer == null || !on()) return;
        scrubAt(context, writer.publishedItem(), writer.savedMime());
    }

    /**
     * The rewrite itself, against the published address [published] of MIME [mime]: a
     * {@code file://} path the save owns on Android 9, the {@code content://} it inserted from
     * Android 10. Reads the whole file, strips it, and writes it back only when something came out.
     * A write that fails part way puts the original bytes back.
     */
    static void scrubAt(Context context, String published, String mime) {
        if (context == null || published == null || !"image/jpeg".equals(mime)) return;
        try {
            // A plain path is a file however it parses: a drive letter would read as a scheme.
            boolean plain = new File(published).isAbsolute();
            Uri uri = Uri.parse(published);
            String scheme = plain ? null : uri.getScheme();
            if (scheme == null || "file".equals(scheme)) {
                String path = scheme == null ? published : uri.getPath();
                if (path == null) return;
                File file = new File(path);
                if (file.length() > MAX_BYTES) return;
                byte[] original;
                try (InputStream in = new FileInputStream(file)) {
                    original = readAll(in);
                }
                byte[] clean = strip(original);
                if (clean == null) return;
                rewrite(() -> new FileOutputStream(file, false), original, clean);
                MediaSave.info(() -> "took the hidden data out of the saved photo");
                PostMetadata.rescan(context, path, mime);
                return;
            }
            ContentResolver resolver = context.getContentResolver();
            byte[] original;
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
                if (descriptor == null || descriptor.getStatSize() > MAX_BYTES) return;
            }
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) return;
                original = readAll(in);
            }
            byte[] clean = strip(original);
            if (clean == null) return;
            rewrite(() -> resolver.openOutputStream(uri, "wt"), original, clean);
            MediaSave.info(() -> "took the hidden data out of the saved photo");
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not take the hidden data out of a saved photo", t);
        }
    }

    interface Opener {
        OutputStream open() throws IOException;
    }

    /** Writes [clean] through [opener]; when that throws, writes [original] back and rethrows. */
    static void rewrite(Opener opener, byte[] original, byte[] clean) throws IOException {
        try (OutputStream out = opener.open()) {
            if (out == null) throw new IOException("no output stream");
            out.write(clean);
        } catch (IOException failure) {
            try (OutputStream out = opener.open()) {
                if (out != null) out.write(original);
            } catch (IOException ignored) {
                // The first failure is the one worth reporting.
            }
            throw failure;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) throw new IOException("photo larger than " + MAX_BYTES + " bytes");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
