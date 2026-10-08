/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/MediaStoreWriter.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Writes a fetched file into the gallery of the device.
 *
 * <p>From Android 10 scoped storage is the only storage there is and MediaStore is the only way
 * in. Nothing there needs a permission: from API 29 an app can insert media that it creates. The
 * entry is created with {@code IS_PENDING} set, and it is published only after the last byte
 * arrives. So a failed fetch never leaves a playable looking file of the wrong length in the
 * gallery. A save that the system stops half way through the copy leaves a pending row; the next
 * start of Instagram removes it ({@link SaveLeftovers}), and the platform would after about a week.
 *
 * <p>Instagram 449 runs from Android 9, whose MediaStore has neither {@code RELATIVE_PATH} nor
 * {@code IS_PENDING}. There the file is written straight into the same folder under Movies,
 * Pictures or Music, under a hidden {@code .pending-} name the media scanner skips, renamed when the last
 * byte is in and then handed to the scanner so the gallery shows it. That needs
 * WRITE_EXTERNAL_STORAGE, which Instagram declares and asks for itself; without it the save fails
 * and the report says why. The hidden file is on the same list as a pending row, so a save the
 * system stops leaves nothing the next start doesn't remove.
 */
final class MediaStoreWriter implements Downloader.Sink {

    /** The source every gallery event carries in the diagnostic report. */
    private static final String SOURCE = "MediaStoreWriter";

    /** What a file written on Android 9 is called until it's finished, as MediaStore names one from 10. */
    static final String LEGACY_PENDING_PREFIX = ".pending-";

    /** Final names belong to the first completed save, including saves finishing together. */
    private static final Object LEGACY_COMMIT_LOCK = new Object();

    private final Context context;
    private final boolean video;

    /** A sound recording, such as a voice message, which goes to the phone's audio files. */
    private final boolean audio;

    /** What the save knows of the post, for the file name's tokens. Never null. */
    private final PostDetails details;

    private Uri item;
    private OutputStream stream;
    private String location;

    /** Android 9 only: the folder, the hidden file being written, and the name it takes when done. */
    private File legacyFolder;
    private File legacyWork;
    private File legacyFile;
    private String legacyMime;

    MediaStoreWriter(Context applicationContext, boolean video) {
        this(applicationContext, video, PostDetails.NONE);
    }

    /** A writer knowing the media's id on Instagram, or null, and nothing else of the post. */
    MediaStoreWriter(Context applicationContext, boolean video, String videoId) {
        this(applicationContext, video, PostDetails.of(videoId));
    }

    MediaStoreWriter(Context applicationContext, boolean video, PostDetails details) {
        this(applicationContext, video, false, details);
    }

    private MediaStoreWriter(Context applicationContext, boolean video, boolean audio, PostDetails details) {
        this.context = applicationContext;
        this.video = video;
        this.audio = audio;
        this.details = details == null ? PostDetails.NONE : details;
    }

    /** A writer for a sound recording, saved with the phone's audio files rather than its pictures. */
    static MediaStoreWriter forAudio(Context applicationContext, PostDetails details) {
        return new MediaStoreWriter(applicationContext, false, true, details);
    }

    /**
     * Where a sound recording goes: Recordings from Android 12, which made that folder for them, and
     * Music before it, the audio folder every older MediaStore takes.
     */
    static String audioDirectory() {
        return Build.VERSION.SDK_INT >= 31 ? Environment.DIRECTORY_RECORDINGS : Environment.DIRECTORY_MUSIC;
    }

    /**
     * {@code Movies/Instagram}, {@code Pictures/Instagram} or, for a recording, the audio folder's,
     * or the folder the person named in place of Instagram, for the message to the user.
     */
    String savedLocation() {
        return location;
    }

    /** Whether this phone's MediaStore is the one without RELATIVE_PATH and IS_PENDING. For tests too. */
    static boolean legacyStorage() {
        return Build.VERSION.SDK_INT < 29;
    }

    @Override
    public OutputStream open(String mimeFromServer) throws IOException {
        String mime = mime(mimeFromServer);
        String directory = audio ? audioDirectory()
            : video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        // One folder name for both kinds, so a story's photos and its videos land side by side.
        // It's read here, per file, and cleaned where it's read: a slash or a dot segment in the
        // setting can't turn this into a path of the setting's choosing.
        String leaf = SaveFolder.leaf(details);
        location = directory + "/" + leaf;
        if (legacyStorage()) return openLegacy(mime, directory, leaf);

        Uri collection = audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            : video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name(mime, collection));
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        // No leading slash. MediaStore checks this against its own directory names.
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, location);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        item = resolver.insert(collection, values);
        if (item == null) throw new IOException("the gallery refused a new entry");
        // Written down before any byte, so a process ended during the copy leaves a row the next
        // save can find and remove. See SaveLeftovers. A row the list couldn't hold goes again
        // before the copy starts: nothing would ever remove it.
        if (!SaveLeftovers.pending(context, item)) {
            Uri row = item;
            item = null;
            try {
                if (resolver.delete(row, null, null) <= 0) {
                    Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the gallery kept an unfinished "
                            + "entry that isn't on the list of pending rows; Android removes it after about a week", null);
                }
            } catch (Throwable t) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "could not remove an unfinished entry that isn't on the list of pending rows", t);
            }
            throw new IOException("the list of pending gallery rows could not hold the new entry");
        }

        stream = resolver.openOutputStream(item, "w");
        if (stream == null) throw new IOException("the gallery gave no way to write");

        return stream;
    }

    /**
     * Android 9: the hidden work file in [directory]/[leaf] of the shared storage, on the list of
     * leftovers before a byte goes in. Nothing here makes a gallery row; the scanner does, once the
     * file has its name.
     */
    private OutputStream openLegacy(String mime, String directory, String leaf) throws IOException {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("Android 9 writes to the gallery only with the storage permission, "
                + "and Instagram hasn't been given it");
        }
        File folder = new File(Environment.getExternalStoragePublicDirectory(directory), leaf);
        if (!folder.mkdirs() && !folder.isDirectory()) throw new IOException("the save folder could not be made");
        legacyFolder = folder;
        legacyMime = mime;

        File finished = unused(folder, name(mime, null));
        // The final name may be the same for concurrent saves. Reserve a different hidden file
        // atomically for each writer, so a second open or Cancel cannot touch the first one's bytes.
        File work = File.createTempFile(LEGACY_PENDING_PREFIX, ".tmp", folder);
        legacyWork = work;
        legacyFile = finished;
        if (!SaveLeftovers.pending(context, Uri.fromFile(work))) {
            abandon();
            throw new IOException("the list of pending gallery rows could not hold the new file");
        }
        try {
            stream = new FileOutputStream(work);
        } catch (IOException e) {
            abandon();
            throw e;
        }
        return stream;
    }

    @Override
    public void commit() throws IOException {
        close();

        if (legacyWork != null) {
            commitLegacy();
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (context.getContentResolver().update(item, values, null, null) != 1) {
            throw new IOException("the gallery did not publish the pending entry");
        }
        SaveLeftovers.settled(context, item);
    }

    /** Android 9: the finished file takes its name, never over another's, and the scanner is told. */
    private void commitLegacy() throws IOException {
        File work = legacyWork;
        synchronized (LEGACY_COMMIT_LOCK) {
            // Keep the choice and rename together: two completed copies may want the same name,
            // and renameTo can replace the first copy after both callers saw an absent target.
            if (legacyFile.exists()) legacyFile = unused(legacyFolder, legacyFile.getName());
            if (!work.renameTo(legacyFile)) throw new IOException("the finished file could not take its name");
            legacyWork = null;
        }
        SaveLeftovers.settled(context, Uri.fromFile(work));
        try {
            MediaScannerConnection.scanFile(context, new String[] { legacyFile.getPath() },
                new String[] { legacyMime }, null);
        } catch (Throwable t) {
            // The file is saved; the gallery finds it at its next scan.
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not tell the gallery about the file", t);
        }
    }

    @Override
    public void abandon() {
        try {
            close();
        } catch (Throwable ignored) {
            // A failed close must not keep the pending row from being removed.
        }

        if (legacyWork != null) {
            File work = legacyWork;
            legacyWork = null;
            if (!work.exists() || work.delete()) {
                SaveLeftovers.settled(context, Uri.fromFile(work));
            } else {
                // Left on the list, so the sweep when Instagram next starts tries again.
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "could not remove the unfinished file", null);
            }
            return;
        }

        if (item == null) return;
        Uri row = item;
        item = null;

        try {
            if (context.getContentResolver().delete(row, null, null) > 0) {
                SaveLeftovers.settled(context, row);
            } else {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "the gallery did not remove the unfinished entry", null);
            }
        } catch (Throwable t) {
            // Left on the list, so the sweep when Instagram next starts tries again.
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not remove the unfinished entry", t);
        }
    }

    /**
     * [name] in [folder], or the first of {@code name (1)} to {@code name (31)} nobody has, the way
     * MediaStore numbers a taken name from Android 10. Android 9 has no such numbering, and a
     * rename would replace the file already there.
     */
    static File unused(File folder, String name) throws IOException {
        File file = new File(folder, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String stem = dot <= 0 ? name : name.substring(0, dot);
        String suffix = dot <= 0 ? "" : name.substring(dot);
        for (int number = 1; number <= 31; number++) {
            file = new File(folder, stem + " (" + number + ")" + suffix);
            if (!file.exists()) return file;
        }
        throw new IOException("every numbered copy of the file name is already in the save folder");
    }

    private void close() throws IOException {
        if (stream == null) return;

        try {
            stream.close();
        } finally {
            stream = null;
        }
    }

    /**
     * The type to record, taken from the server.
     *
     * <p>The type decides the name, and never the other way round. Facebook's own save gets this
     * wrong: it writes AVIF bytes into a file called {@code .jpg}, which leaves the
     * gallery unable to draw a thumbnail for it. A copy of that behaviour copies the fault.
     */
    private String mime(String fromServer) {
        // A recording is fetched only once its first bytes show an MP4 container, and Meta calls
        // its sound files video/mp4 as often as audio/mp4, so the container names it.
        if (audio) return AUDIO_MP4;
        if (fromServer != null && EXTENSIONS.containsKey(fromServer)) {
            boolean isVideo = fromServer.startsWith("video/");
            // A video request that answers with a picture, or the reverse, is an error page or a
            // thumbnail. A save of it looks like success.
            if (isVideo == video) return fromServer;
        }

        return video ? "video/mp4" : "image/jpeg";
    }

    private String name(String mime, Uri collection) {
        String suffix = audio ? AUDIO_SUFFIX : EXTENSIONS.get(mime);
        if (suffix == null) suffix = video ? ".mp4" : ".jpg";

        Date now = new Date();
        if (FileNameTemplate.byPost()) {
            // Name saves by account and post time: a photo and a video alike, when the save knows both.
            String byPost = FileNameTemplate.postName(now, details, false);
            if (byPost != null) {
                String name = byPost + suffix;
                if (!inSaveFolder(collection, name)) return name;
                // Saved before. MediaStore would number it, up to (31), and then refuse the save.
                // A profile picture's name has the time of the save already, so it keeps it.
                String again = FileNameTemplate.postName(now, details, true);
                if (!byPost.equals(again)) Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "the file name was already in the save folder, so the time of the save went on the end");
                return again + suffix;
            }
            reportUnnamedByPost();
        }
        if (audio) {
            // As a photo is named, so the saved recordings sort by the time they were saved.
            return AUDIO_PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + suffix;
        }
        if (video) {
            // The person's template, read here, per file, and cleaned where it's read. As it
            // ships it's IG_VID_ and the date and time.
            String template = FileNameTemplate.current();
            reportMissingTokens(template);
            String name = FileNameTemplate.videoName(template, now, details) + suffix;
            if (!inSaveFolder(collection, name)) return name;
            // MediaStore would number it, up to (31), and then refuse the save.
            String taken = FileNameTemplate.takenVideoName(template, now, details);
            if (taken == null) return name;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "the file name was already in the save folder, so the time of the save went on the end");
            return taken + suffix;
        }

        // One naming for every photo, so the saved photos sort together by the time they were saved.
        // The locale has to be fixed. Under a Thai or an Arabic locale the default calendar
        // writes Buddhist years or Eastern Arabic digits into the file name.
        return FileNameTemplate.PHOTO_PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + suffix;
    }

    /**
     * What {@link #inSaveFolder} asks MediaStore: a file of one name in one folder. MediaStore keeps
     * a folder with a slash on the end, so both spellings are asked for. Package-private so a test
     * gallery can answer exactly this and nothing else.
     */
    static final String SAME_NAME_IN_FOLDER = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND ("
            + MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " = ?)";

    /**
     * Whether a file named [displayName] is already in the save folder of [collection]. Only
     * published files count, as MediaStore leaves pending and trashed ones out of a query unless
     * asked: their files carry other names until they're published. A file this app can't see, or a
     * lookup MediaStore refuses, counts as not there, and MediaStore's own numbering still keeps the
     * save. The report gets the kind of failure only, never the name, which can hold the poster's.
     * On Android 9 the folder is a folder on the shared storage, and a look at it answers.
     */
    private boolean inSaveFolder(Uri collection, String displayName) {
        if (legacyStorage()) return legacyFolder != null && new File(legacyFolder, displayName).exists();
        try (Cursor cursor = context.getContentResolver().query(collection,
                new String[] { MediaStore.MediaColumns._ID }, SAME_NAME_IN_FOLDER,
                new String[] { displayName, location + "/", location }, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable t) {
            String kind = t.getClass().getSimpleName();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "could not look for the file name in the save folder (" + kind + ")");
            return false;
        }
    }

    /**
     * One line in the report when Name saves by account and post time is on and this save doesn't
     * know who posted or when, so it keeps the name it would have had. Never the name itself.
     */
    private void reportUnnamedByPost() {
        String missing = !details.hasOwner() && !details.hasPosted() ? "the account or the post time"
            : !details.hasOwner() ? "the account" : "the post time";
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "saves are named by account and post time, and this save doesn't know " + missing
                    + ", so it keeps its usual name");
    }

    /**
     * One line in the report when [template] asks for something this save doesn't know, saying
     * what the name does about it: left out when the name still tells saves apart, and the date
     * and time on the end when it wouldn't, since MediaStore refuses a name once it has numbered
     * it 31 times. Never the id or the poster's name: the report is pasted into public issues.
     */
    private void reportMissingTokens(String template) {
        List<String> missing = new ArrayList<>();
        if (FileNameTemplate.usesVideoId(template) && !details.hasVideoId()) missing.add("the video id");
        if (FileNameTemplate.usesOwner(template) && !details.hasOwner()) missing.add("the poster");
        if (FileNameTemplate.usesPosted(template) && !details.hasPosted()) missing.add("the post date");
        if (missing.isEmpty()) return;

        boolean apart = FileNameTemplate.keepsApart(template, details.hasVideoId(), details.hasOwner(), details.hasPosted());
        String asked = missing.size() == 1 ? missing.get(0)
            : String.join(", ", missing.subList(0, missing.size() - 1)) + " and " + missing.get(missing.size() - 1);
        String has = missing.size() == 1 ? "none" : missing.size() == 2 ? "neither" : "none of them";
        String instead = !apart ? "the date and time go on the end"
            : missing.size() == 1 ? "it's left out" : "they're left out";
        final String line = "the file name asks for " + asked + " and this save has " + has + ", so " + instead;
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> line);
    }

    /**
     * The types worth recording, and the name that each one takes.
     *
     * <p>{@code MimeTypeMap} is not used. It answers nothing for {@code image/avif} on many
     * devices and its table is different from one ROM to the next.
     */
    private static final Map<String, String> EXTENSIONS = new HashMap<>();

    static {
        EXTENSIONS.put("video/mp4", ".mp4");
        EXTENSIONS.put("video/x-m4v", ".m4v");
        EXTENSIONS.put("video/quicktime", ".mov");
        EXTENSIONS.put("video/webm", ".webm");
        EXTENSIONS.put("video/3gpp", ".3gp");
        EXTENSIONS.put("image/jpeg", ".jpg");
        EXTENSIONS.put("image/png", ".png");
        EXTENSIONS.put("image/webp", ".webp");
        EXTENSIONS.put("image/heic", ".heic");
        EXTENSIONS.put("image/heif", ".heif");
        EXTENSIONS.put("image/avif", ".avif");
        EXTENSIONS.put("image/gif", ".gif");
    }

    /** A recording's type and name. Kept out of the table, which only ever names a picture or a video. */
    static final String AUDIO_MP4 = "audio/mp4";
    static final String AUDIO_SUFFIX = ".m4a";

    /** How a saved recording is named when nothing names it by its post, as photos take IG_IMG_. */
    static final String AUDIO_PREFIX = "IG_AUD_";
}
