/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The viewing history (#228): a list, kept only on this phone, of the posts, reels, stories and
 * profiles that came up on screen, newest first, so something seen while scrolling can be found
 * again. It's off until the switch is on, and off stops recording at once.
 *
 * <p>It reads only what Instagram already loaded for the screen: the media's id and who posted it,
 * or the profile's name. It asks Instagram for nothing, sends nothing anywhere and reads nothing
 * about other accounts. The list holds at most {@link #MAX_ENTRIES} rows in one small file in the
 * app's own storage, and it's left out of the settings backup.
 */
public final class ViewingHistory {
    /** What an entry is. */
    public enum Kind { POST, REEL, STORY, PROFILE }

    /** One row of the list. */
    public static final class Row {
        public final long time;
        public final Kind kind;
        /** The media's id, or the profile's name. */
        public final String id;
        /** Who posted it, or the profile's name; empty when unknown. */
        public final String owner;

        Row(long time, Kind kind, String id, String owner) {
            this.time = time;
            this.kind = kind;
            this.id = id;
            this.owner = owner;
        }
    }

    /** The most rows kept. The oldest go first. */
    static final int MAX_ENTRIES = 500;

    /** The same thing coming up again within this long isn't a new row. */
    static final long SAME_AGAIN_MS = 60_000L;

    private static final String DIRECTORY = "hushgram-viewing";
    private static final String FILE = "history.tsv";
    private static final String FAMILY = FamilyNames.VIDEO_DOWNLOAD;

    private static final Object LOCK = new Object();
    /** Newest first. */
    private static final ArrayList<Row> ROWS = new ArrayList<>();
    private static File loadedFrom;

    private ViewingHistory() {
    }

    /** Whether the switch is on. Off until the settings are ready. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && Settings.VIEWING_HISTORY.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    // What the patched binders hand over. Each does nothing while the switch is off and never throws.

    /** A post's media came up in the feed. */
    public static void post(@Nullable Object media) {
        media(Kind.POST, media);
    }

    /** A reel's media came up in Reels. */
    public static void reel(@Nullable Object media) {
        media(Kind.REEL, media);
    }

    /** A story's media came up in the story viewer. */
    public static void story(@Nullable Object media) {
        media(Kind.STORY, media);
    }

    /** A profile opened, with its User. */
    public static void profile(@Nullable Object user) {
        try {
            if (user == null || !on()) return;
            String name = InstagramMedia.username(user);
            if (name == null || name.trim().isEmpty()) return;
            add(Utils.getContext(), Kind.PROFILE, name.trim(), name.trim(), System.currentTimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "viewing history profile", failure);
        }
    }

    private static void media(Kind kind, @Nullable Object media) {
        try {
            if (media == null || !on()) return;
            String id = InstagramMedia.mediaId(media);
            if (id == null || id.trim().isEmpty()) return;
            Object user = InstagramMedia.owner(media);
            String owner = user == null ? null : InstagramMedia.username(user);
            add(Utils.getContext(), kind, id.trim(), owner == null ? "" : owner.trim(), System.currentTimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "viewing history " + kind.name().toLowerCase(java.util.Locale.ROOT), failure);
        }
    }

    /**
     * Puts a row at the top unless the same thing was put there within {@link #SAME_AGAIN_MS}, drops
     * the oldest past {@link #MAX_ENTRIES} and writes the file off the calling thread. The same
     * thing seen again later moves up to the top, so it shows once.
     */
    static void add(@Nullable Context context, Kind kind, String id, String owner, long now) {
        if (context == null) return;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            load(application);
            for (int at = 0; at < ROWS.size(); at++) {
                Row row = ROWS.get(at);
                if (row.kind != kind || !row.id.equals(id)) continue;
                if (at == 0 && now - row.time < SAME_AGAIN_MS) return;
                ROWS.remove(at);
                break;
            }
            ROWS.add(0, new Row(now, kind, id, owner));
            while (ROWS.size() > MAX_ENTRIES) ROWS.remove(ROWS.size() - 1);
        }
        Utils.runOnBackgroundThread(() -> save(application));
    }

    /** The rows, newest first. */
    public static List<Row> entries(Context context) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            load(application);
            return Collections.unmodifiableList(new ArrayList<>(ROWS));
        }
    }

    /** How many rows are kept. */
    public static int count(Context context) {
        return entries(context).size();
    }

    /** Forgets every row and deletes the file. Answers whether it's gone. */
    public static boolean clear(Context context) {
        if (context == null) return false;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                File file = file(application);
                ROWS.clear();
                loadedFrom = file;
                File work = new File(file.getParentFile(), FILE + ".tmp");
                if (work.exists() && !work.delete()) return false;
                return !file.exists() || file.delete();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "viewing history clear", failure);
                return false;
            }
        }
    }

    private static File file(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), FILE);
    }

    private static void load(Context application) {
        File file = file(application);
        if (file.equals(loadedFrom)) return;
        loadedFrom = file;
        ROWS.clear();
        try {
            if (!file.isFile()) return;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] part = line.split("\t", -1);
                if (part.length != 4) continue;
                try {
                    Kind kind = Kind.valueOf(part[1]);
                    if (part[2].isEmpty()) continue;
                    ROWS.add(new Row(Long.parseLong(part[0]), kind, part[2], part[3]));
                } catch (IllegalArgumentException skip) {
                    // A line that isn't one of ours is left out.
                }
                if (ROWS.size() >= MAX_ENTRIES) break;
            }
        } catch (IOException | RuntimeException failure) {
            HookStatus.threw(FAMILY, "viewing history read", failure);
        }
    }

    private static void save(Context application) {
        synchronized (LOCK) {
            try {
                File file = file(application);
                File folder = file.getParentFile();
                if (!folder.mkdirs() && !folder.isDirectory()) return;
                StringBuilder text = new StringBuilder();
                for (Row row : ROWS) {
                    text.append(row.time).append('\t').append(row.kind.name()).append('\t')
                            .append(clean(row.id)).append('\t').append(clean(row.owner)).append('\n');
                }
                File work = new File(folder, FILE + ".tmp");
                Files.write(work.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(work.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException failure) {
                HookStatus.threw(FAMILY, "viewing history write", failure);
            }
        }
    }

    private static String clean(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Forgets what this process holds, as a new process would. */
    public static void forgetForTests() {
        synchronized (LOCK) {
            ROWS.clear();
            loadedFrom = null;
        }
    }
}
