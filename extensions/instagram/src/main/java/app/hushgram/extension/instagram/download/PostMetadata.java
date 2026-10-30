/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import android.content.ContentResolver;
import android.content.Context;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;

/**
 * Writes where a saved photo came from into the photo itself: the account that posted it, when the
 * post went up and a link to the post. It goes in the EXIF a gallery or exiftool reads, never over
 * the picture. Only a JPEG is touched, after the save has already published the file, so a failure
 * here never costs the photo: the save already succeeded and this is best effort on top.
 *
 * <p>The link is the post's web address, built from the media's id the same way Instagram builds a
 * shortcode: the id is read as a number and written in a positional base 64 over
 * {@code A-Za-z0-9-_}, most significant digit first, so id 0 is {@code A}. That is the mapping the
 * whole ecosystem uses, checked here against known id/shortcode pairs in the test.
 *
 * <p>The write reads only {@link Settings#SAVE_PHOTO_METADATA}, on the save worker thread, after
 * the file is on disk. It never runs at Instagram's startup.
 */
public final class PostMetadata {

    private PostMetadata() {}

    /** Instagram's shortcode alphabet, a positional base 64. Index 0..63 maps to the character. */
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length());

    /** The EXIF date form, "yyyy:MM:dd HH:mm:ss". The post time is written in UTC, so it's one fixed value. */
    private static final String EXIF_DATE = "yyyy:MM:dd HH:mm:ss";

    /**
     * The media's id as a shortcode, or null when the id isn't a plain number. Reads the id as a
     * number and writes it in base 64 over {@link #ALPHABET}, most significant digit first; id 0 is
     * the alphabet's first character.
     */
    static String shortcode(String pkDigits) {
        if (pkDigits == null) return null;
        String digits = pkDigits.trim();
        if (digits.isEmpty()) return null;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        BigInteger pk;
        try {
            pk = new BigInteger(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        if (pk.signum() == 0) return String.valueOf(ALPHABET.charAt(0));
        StringBuilder out = new StringBuilder();
        while (pk.signum() > 0) {
            BigInteger[] quotientAndRemainder = pk.divideAndRemainder(BASE);
            out.append(ALPHABET.charAt(quotientAndRemainder[1].intValue()));
            pk = quotientAndRemainder[0];
        }
        return out.reverse().toString();
    }

    /** The post's web address, or null when the save doesn't know a numeric media id. */
    static String permalink(PostDetails details) {
        if (details == null || !details.hasVideoId()) return null;
        String code = shortcode(details.videoId);
        if (code == null) return null;
        return "https://www.instagram.com/p/" + code + "/";
    }

    private static String stamp(Date posted) {
        SimpleDateFormat format = new SimpleDateFormat(EXIF_DATE, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(posted);
    }

    /**
     * Sets what the save knows onto [exif]: the poster as Artist, the post time as the three EXIF
     * dates and the post link as Image Description. Leaves out anything the save doesn't know, and
     * returns whether it set anything, so a save that knows nothing doesn't rewrite the file.
     */
    static boolean apply(ExifInterface exif, PostDetails details) {
        if (exif == null || details == null) return false;
        boolean wrote = false;
        if (details.hasOwner()) {
            exif.setAttribute(ExifInterface.TAG_ARTIST, details.owner);
            wrote = true;
        }
        if (details.hasPosted()) {
            String stamp = stamp(details.posted);
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp);
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp);
            exif.setAttribute(ExifInterface.TAG_DATETIME, stamp);
            wrote = true;
        }
        String link = permalink(details);
        if (link != null) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, link);
            wrote = true;
        }
        return wrote;
    }

    /** On only when the setting is on and ready. Never waits on settings. */
    static boolean on() {
        return Utils.settingsReady() && Settings.SAVE_PHOTO_METADATA.get();
    }

    private static boolean isJpeg(String mime) {
        // The platform ExifInterface only writes JPEG on Android 9. A PNG or WebP save is left alone.
        return "image/jpeg".equals(mime);
    }

    private static boolean wanted(PostDetails details) {
        return details != null && (details.hasOwner() || details.hasPosted() || details.hasVideoId());
    }

    /**
     * Writes the post's details into the JPEG the save just published, when the switch is on.
     * Reads the published address and the MIME off the writer, so it tags exactly the file that
     * was saved. A video or a non-JPEG photo is left untouched.
     */
    static void tag(Context context, MediaStoreWriter writer, PostDetails details) {
        if (writer == null || !on()) return;
        writeTo(context, writer.publishedItem(), writer.savedMime(), details);
    }

    /**
     * The write itself, against the published address [published] of MIME [mime]. The switch is the
     * caller's to check. On Android 9 the address is a {@code file://} the save owns; from Android
     * 10 it's the {@code content://} the save inserted, opened read-write. Fail open: the photo is
     * already saved, so a metadata write that throws is logged and swallowed, never raised.
     */
    static void writeTo(Context context, String published, String mime, PostDetails details) {
        if (context == null || published == null || !isJpeg(mime) || !wanted(details)) return;
        try {
            Uri uri = Uri.parse(published);
            String scheme = uri.getScheme();
            if (scheme == null || "file".equals(scheme)) {
                String path = scheme == null ? published : uri.getPath();
                if (path == null) return;
                ExifInterface exif = new ExifInterface(path);
                if (apply(exif, details)) {
                    exif.saveAttributes();
                    MediaSave.info(() -> "tagged the saved photo with where it came from");
                }
                return;
            }
            ContentResolver resolver = context.getContentResolver();
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw")) {
                if (descriptor == null) return;
                ExifInterface exif = new ExifInterface(descriptor.getFileDescriptor());
                if (apply(exif, details)) {
                    exif.saveAttributes();
                    MediaSave.info(() -> "tagged the saved photo with where it came from");
                }
            }
        } catch (Throwable t) {
            MediaSave.failure(() -> "could not tag a saved photo with where it came from", t);
        }
    }
}
