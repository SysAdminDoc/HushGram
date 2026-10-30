/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.direct.ChatAccounts;
import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * What Skip what's already saved remembers: the saves that reached the gallery while it was on, so
 * the same post, page, reel or story isn't saved twice by mistake (#150).
 *
 * <p>A save is known by its key ({@link #key}): the account in front when it was asked for, as a
 * scrambled form of its id the way {@code ResumePoints} keeps one, the media's own id on Instagram
 * (never an address or a file name), its page in a carousel, and what was saved of it: the video, the
 * photo, the picture a cover save keeps, or a recording. So a carousel's pages, a video and its cover,
 * and two of your accounts each keep their own. Beside the key goes the gallery entry the save wrote,
 * the folder it went to, who posted and when, and when it was saved.
 *
 * <p>Only a save the gallery published is written down, never one that failed or was cancelled.
 * Before a save is skipped the gallery is asked whether that entry is still there and still in its
 * folder, so a file you deleted or moved is forgotten and saved again. Anything that goes wrong on the
 * way answers "not saved", and the save goes ahead as it would with the switch off.
 *
 * <p>Told it was skipped, a second ask for the same thing within {@link #AGAIN_MS} saves it anyway,
 * which is how a copy you want on purpose gets made, without a dialog.
 *
 * <p>The list is a small file in HushGram's private folder holding at most {@link #MAX_ENTRIES}
 * saves, the oldest going first. It isn't a setting, so a settings backup leaves it out, and nothing
 * of it goes in the diagnostic report, which only counts skips. With the switch off nothing is read or
 * written, and Clear download history deletes the file. A damaged file is read only within fixed
 * budgets, its bad lines skipped, and is written afresh at the next save.
 */
public final class DownloadHistory {

    private DownloadHistory() {}

    /** The folder in Instagram's private files, and the file in it. */
    static final String DIRECTORY = "hushgram-downloads";
    static final String FILE = "history.tsv";

    /**
     * How many saves are remembered. The oldest goes first. The file is read on a save's own thread,
     * once per process, so a full list (a few megabytes) never holds the screen up (#150).
     */
    static final int MAX_ENTRIES = 15_000;

    /** How long after a skip a second ask for the same thing saves it anyway. */
    static final long AGAIN_MS = 30_000L;

    /** The account part of a key when no account is known: one list for the phone. */
    static final String UNKNOWN_ACCOUNT = "-";

    /** The report's counts, under the feed download's family. Fixed text, never a media id. */
    static final String COUNT_SKIPPED = "save skipped, already saved";
    static final String COUNT_AGAIN = "saved again after a skip";
    static final String COUNT_GONE = "saved file gone, saving again";
    static final String COUNT_SAVING = "save skipped, already being saved";

    private static final String FAMILY = FamilyNames.VIDEO_DOWNLOAD;

    /** Lines read from the file at most, so a damaged file can't hold a save up for long. */
    private static final int MAX_LINES_READ = 4 * MAX_ENTRIES;

    /**
     * The longest line read. A save's line is a few hundred characters, so a longer one is damage,
     * skipped as it's read and never held whole (#150).
     */
    static final int MAX_LINE_CHARS = 2048;

    /**
     * Characters read from the file at most. A full file, twice the list's lines at 500 characters
     * each, fits, and a damaged or swollen one stops here (#150).
     */
    static final long MAX_CHARS_READ = 16L * 1024 * 1024;

    /** What the file's reader puts in place of bytes that aren't UTF-8. */
    private static final char NOT_UTF8 = (char) 0xFFFD;

    /** What a save kept of a media item. One letter each in a key. */
    enum Kind {
        VIDEO('v'), PHOTO('p'), PICTURE('c'), AUDIO('a');

        final char code;

        Kind(char code) {
            this.code = code;
        }
    }

    /** One remembered save. */
    static final class Entry {
        /** The gallery entry the save published, a content:// or, on Android 9, a file:// address. */
        final String item;
        /** The folder it went to, like Movies/Instagram, or null. */
        final String folder;
        /** Who posted it, or null. */
        final String owner;
        /** When the post went up, in milliseconds, or 0. */
        final long posted;
        /** When it was saved, in milliseconds. */
        final long savedAt;

        Entry(String item, String folder, String owner, long posted, long savedAt) {
            this.item = item;
            this.folder = folder;
            this.owner = owner;
            this.posted = posted;
            this.savedAt = savedAt;
        }
    }

    private static final Object LOCK = new Object();
    /** The keys of saves running now that {@link #saved} let through, under {@link #LOCK}. */
    private static final Set<String> SAVING = new HashSet<>();

    /** Oldest first. Read from {@link #loadedFrom} the first time it's needed. */
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<>();

    /** The file the list was read from, or null before it's read. */
    private static File loadedFrom;

    /** Lines in the file, removals included, so it's rewritten once it has grown well past the list. */
    private static int lines;

    /** Keys a save was just skipped for, and until when a second ask saves them anyway. */
    private static final Map<String, Long> AGAIN = new HashMap<>();

    /** Reads the id of the account in front. Tests put a fake in. */
    static volatile Supplier<String> accounts = DownloadHistory::accountInFront;

    /**
     * The account in front, by the screen's own session when "Lock your messages" is in the build,
     * else the one Home was made for. Null or empty when neither is known.
     */
    private static String accountInFront() {
        String id = ChatAccounts.active();
        if (id == null || id.isEmpty()) id = HiddenAccounts.signedInAccount();
        return id;
    }

    /** Whether Skip what's already saved is on. Never throws: off until the settings are ready. */
    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_SKIP_SAVED.get();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "skip saved switch", failure);
            return false;
        }
    }

    /**
     * The account part of a key for the account in front: 16 hex digits of a SHA-256 of its id,
     * never the id itself, or {@link #UNKNOWN_ACCOUNT} when no account is known. Never throws.
     */
    static String owner() {
        try {
            String id = accounts.get();
            if (id == null || id.trim().isEmpty()) return UNKNOWN_ACCOUNT;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("hushgram downloads " + id.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16)).append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable failure) {
            return UNKNOWN_ACCOUNT;
        }
    }

    /**
     * The account part a save asked for now keeps, or null with the switch off, when nothing is
     * remembered or skipped. Read on the tap, so it's the account in front when the save was asked for.
     */
    static String ownerIfOn() {
        return on() ? owner() : null;
    }

    /**
     * The key of [kind] of the media [details] names, for the account part [owner], or null when
     * [owner] is (the switch is off), or the save doesn't know the media's own id on Instagram. A
     * profile picture has none, and neither does a comment's photo or a voice message.
     */
    static String key(String owner, PostDetails details, Kind kind) {
        if (owner == null || details == null || kind == null || details.profile || !details.hasVideoId()) return null;
        return owner + '/' + details.videoId + '/' + details.page + '/' + kind.code;
    }

    /**
     * Whether [key] was saved before and its file is still where the save put it, so this save is
     * skipped. A second ask within {@link #AGAIN_MS} of a skip answers no, once, and that save goes
     * ahead. An entry whose file is gone or was moved is forgotten. A null key answers no. Never
     * throws: anything that fails answers no.
     *
     * <p>A save answered no holds [key] until it calls {@link #release}, and another save of it
     * meanwhile, a double tap's second say, answers yes: two saves of one thing at once would both
     * pass before either was written down.
     */
    static boolean saved(Context application, String key) {
        if (application == null || key == null) return false;
        synchronized (LOCK) {
            if (!SAVING.add(key)) {
                HookStatus.counted(FAMILY, COUNT_SAVING);
                return true;
            }
        }
        boolean skip = savedBefore(application, key);
        if (skip) release(key);
        return skip;
    }

    /** Lets go of [key], which a save {@link #saved} answered no for held. Null does nothing. */
    static void release(String key) {
        if (key == null) return;
        synchronized (LOCK) {
            SAVING.remove(key);
        }
    }

    /** {@link #release} for each of [keys]. */
    static void release(Collection<String> keys) {
        for (String key : keys) release(key);
    }

    private static boolean savedBefore(Context application, String key) {
        try {
            Entry entry;
            synchronized (LOCK) {
                Long until = AGAIN.remove(key);
                if (until != null && now() <= until) {
                    HookStatus.counted(FAMILY, COUNT_AGAIN);
                    return false;
                }
                load(application);
                entry = ENTRIES.get(key);
            }
            if (entry == null) return false;
            // Outside the lock: it asks the gallery.
            if (present(application, entry)) {
                HookStatus.counted(FAMILY, COUNT_SKIPPED);
                return true;
            }
            forget(application, key, entry);
            HookStatus.counted(FAMILY, COUNT_GONE);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "download history check", failure);
            return false;
        }
    }

    /** Each of [keys] was just skipped: a second ask within {@link #AGAIN_MS} saves it anyway. */
    static void offerAgain(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        synchronized (LOCK) {
            long now = now();
            AGAIN.values().removeIf(until -> until < now);
            for (String key : keys) {
                if (key != null) AGAIN.put(key, now + AGAIN_MS);
            }
        }
    }

    /**
     * Writes down a save the gallery published: [key], from {@link #key}, the entry [writer]
     * published and [details]'s poster and post time. Nothing for a null key or a writer that
     * published nothing, so a failed or cancelled save never is. Never throws.
     */
    static void record(Context application, String key, MediaStoreWriter writer, PostDetails details) {
        if (application == null || key == null || writer == null) return;
        try {
            String item = writer.publishedItem();
            if (item == null) return;
            remember(application, key, item, writer.savedLocation(), details);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "download history write", failure);
        }
    }

    /** {@link #record}'s write, for an entry [item] in [folder]. Throws on a write that fails. */
    static void remember(Context application, String key, String item, String folder, PostDetails details)
            throws IOException {
        PostDetails known = details == null ? PostDetails.NONE : details;
        Entry entry = new Entry(item, folder, known.owner, known.posted == null ? 0 : known.posted.getTime(),
                System.currentTimeMillis());
        synchronized (LOCK) {
            load(application);
            ENTRIES.remove(key);
            ENTRIES.put(key, entry);
            dropOldest();
            if (lines + 1 > 2 * MAX_ENTRIES) rewrite(application);
            else append(application, line(key, entry));
        }
    }

    /**
     * Forgets every save on this phone, for every account, and deletes the file. Answers whether
     * it's gone. Never throws.
     */
    public static boolean clear(Context context) {
        if (context == null) return false;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                File file = file(application);
                ENTRIES.clear();
                AGAIN.clear();
                lines = 0;
                loadedFrom = file;
                File work = new File(file.getParentFile(), FILE + ".tmp");
                if (work.exists() && !work.delete()) return false;
                return !file.exists() || file.delete();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "download history clear", failure);
                return false;
            }
        }
    }

    /** How many saves are remembered. */
    static int count(Context application) {
        synchronized (LOCK) {
            load(application);
            return ENTRIES.size();
        }
    }

    /** Whether [key] is on the list, without asking the gallery. */
    static boolean remembers(Context application, String key) {
        synchronized (LOCK) {
            load(application);
            return ENTRIES.containsKey(key);
        }
    }

    /** Forgets what's in memory, so the next ask reads the file again, and every waiting second ask. */
    static void forgetForTests() {
        synchronized (LOCK) {
            ENTRIES.clear();
            AGAIN.clear();
            SAVING.clear();
            lines = 0;
            loadedFrom = null;
        }
        accounts = DownloadHistory::accountInFront;
    }

    // ---------------------------------------------------------------- the gallery

    /**
     * Whether [entry]'s file is still in the gallery, in the folder the save put it. On Android 9
     * the save wrote a file, and the file answers. Throws when the gallery can't be asked.
     */
    static boolean present(Context application, Entry entry) {
        Uri item = Uri.parse(entry.item);
        if ("file".equals(item.getScheme())) {
            // All after "file:", not getPath(), which reads a path with no slash in front (a Windows
            // test host's C:\...) as the authority and answers an empty one.
            String path = item.getSchemeSpecificPart();
            if (path != null && path.startsWith("//")) path = path.substring(2);
            return path != null && !path.isEmpty() && new File(path).isFile();
        }
        if (!"content".equals(item.getScheme())) return false;
        // A save into a folder picked in settings: the folder's own app answers for its file.
        if (DocumentsContract.isTreeUri(item)) return SaveTree.exists(application, item);
        try (Cursor row = application.getContentResolver().query(item,
                new String[] { MediaStore.MediaColumns.RELATIVE_PATH }, null, null, null)) {
            if (row == null || !row.moveToFirst()) return false;
            String folder = row.isNull(0) ? null : row.getString(0);
            return folder == null || entry.folder == null || trimmed(folder).equalsIgnoreCase(trimmed(entry.folder));
        }
    }

    /** A folder without the slash MediaStore keeps on its end. */
    private static String trimmed(String folder) {
        String text = folder.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    // ---------------------------------------------------------------- the file

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    static File file(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), FILE);
    }

    /** Drops [key] when it's still [gone], the entry found missing, and not a save made since. */
    private static void forget(Context application, String key, Entry gone) {
        synchronized (LOCK) {
            try {
                load(application);
                if (ENTRIES.get(key) != gone) return;
                ENTRIES.remove(key);
                if (lines + 1 > 2 * MAX_ENTRIES) rewrite(application);
                else append(application, "-\t" + key);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "download history forget", failure);
            }
        }
    }

    /**
     * Reads the file once, or again when it's another file (another test's). Keeps what it read on a
     * failure. A file found damaged, with a line too long, not UTF-8 or cut short, or more of it than
     * {@link #MAX_CHARS_READ}, is written afresh from what was read at the next save or forget, so
     * what's added after it can be read back (#150).
     */
    private static void load(Context application) {
        File file = file(application);
        if (file.equals(loadedFrom)) return;
        ENTRIES.clear();
        lines = 0;
        loadedFrom = file;
        if (!file.isFile()) return;
        try (Reader in = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Lines each = new Lines(in);
            String line;
            while (lines < MAX_LINES_READ && (line = each.next()) != null) {
                lines++;
                read(line);
                dropOldest();
            }
            if (each.damaged) lines = Math.max(lines, 2 * MAX_ENTRIES);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "download history read", failure);
        }
    }

    /** Drops the oldest saves past {@link #MAX_ENTRIES}. */
    private static void dropOldest() {
        if (ENTRIES.size() <= MAX_ENTRIES) return;
        Iterator<String> oldest = ENTRIES.keySet().iterator();
        while (ENTRIES.size() > MAX_ENTRIES && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * The file's lines, read within {@link #MAX_LINE_CHARS} and {@link #MAX_CHARS_READ}, so a damaged
     * file is never held whole. A line too long or holding bytes that aren't UTF-8 is skipped as it's
     * read, and so is a last line the file ends inside, a write cut short.
     */
    private static final class Lines {
        private final Reader in;
        private final char[] buffer = new char[8192];
        private final StringBuilder line = new StringBuilder();
        private int at;
        private int end;
        private long read;
        /** Whether a line was skipped, or reading stopped at the budget with more to read. */
        boolean damaged;

        Lines(Reader in) {
            this.in = in;
        }

        /** The next whole line, or null at the file's end or the budget. */
        String next() throws IOException {
            line.setLength(0);
            boolean skipping = false;
            while (true) {
                if (at == end && !fill()) {
                    if (line.length() > 0 || skipping) damaged = true;
                    return null;
                }
                char c = buffer[at++];
                if (c == '\n') {
                    if (!skipping) return line.toString();
                    damaged = true;
                    skipping = false;
                    line.setLength(0);
                } else if (!skipping) {
                    if (line.length() == MAX_LINE_CHARS || c == NOT_UTF8) skipping = true;
                    else line.append(c);
                }
            }
        }

        /** Reads on. False at the file's end or the budget. */
        private boolean fill() throws IOException {
            if (read >= MAX_CHARS_READ) {
                if (in.read() != -1) damaged = true;
                return false;
            }
            int count = in.read(buffer, 0, (int) Math.min(buffer.length, MAX_CHARS_READ - read));
            if (count <= 0) return false;
            read += count;
            at = 0;
            end = count;
            return true;
        }
    }

    /** One line of the file: a save ({@code +}) or one forgotten ({@code -}). Anything else is skipped. */
    private static void read(String line) {
        String[] fields = line.split("\t", 8);
        if (fields.length == 2 && fields[0].equals("-")) {
            ENTRIES.remove(fields[1]);
            return;
        }
        if (fields.length != 7 || !fields[0].equals("+") || fields[1].isEmpty() || fields[3].isEmpty()) return;
        try {
            Entry entry = new Entry(fields[3], empty(fields[4]), empty(fields[5]), Long.parseLong(fields[6]),
                    Long.parseLong(fields[2]));
            ENTRIES.remove(fields[1]);
            ENTRIES.put(fields[1], entry);
        } catch (NumberFormatException damaged) {
            // A damaged line is a save forgotten, never a reason to stop reading.
        }
    }

    private static String empty(String field) {
        return field.isEmpty() ? null : field;
    }

    /** A save's line: {@code +}, the key, when it was saved, the entry, the folder, the poster and the post time. */
    private static String line(String key, Entry entry) {
        return "+\t" + key + '\t' + entry.savedAt + '\t' + field(entry.item) + '\t' + field(entry.folder) + '\t'
                + field(entry.owner) + '\t' + entry.posted;
    }

    /** A value as one field: no tab or line break can split the line. */
    private static String field(String value) {
        if (value == null) return "";
        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            clean.append(c == '\t' || c == '\n' || c == '\r' ? ' ' : c);
        }
        return clean.toString();
    }

    interface Append { OutputStream open(File file) throws IOException; }
    static volatile Append appendForTests;

    private static void append(Context application, String line) throws IOException {
        File file = file(application);
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("the history folder could not be made");
        }
        try {
            try (Writer out = new OutputStreamWriter(appendForTests == null ? new FileOutputStream(file, true)
                    : appendForTests.open(file), StandardCharsets.UTF_8)) {
                out.write(line);
                out.write('\n');
            }
            lines++;
        } catch (IOException | RuntimeException failure) {
            // A failed write or close may have left a partial tail in the already-loaded file.
            // Rewrite from the retained entries before any later save or forget appends to it.
            lines = Math.max(lines, 2 * MAX_ENTRIES);
            throw failure;
        }
    }

    /** The whole list written afresh beside the file, then put in its place. */
    private static void rewrite(Context application) throws IOException {
        File file = file(application);
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("the history folder could not be made");
        }
        File work = new File(folder, FILE + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(work);
             Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Entry> each : ENTRIES.entrySet()) {
                out.write(line(each.getKey(), each.getValue()));
                out.write('\n');
            }
            out.flush();
            stream.getFD().sync();
        }
        try {
            Files.move(work.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            work.delete();
            throw failure;
        }
        lines = ENTRIES.size();
    }
}
