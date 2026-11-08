/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The viewing history (#228): a list, kept only on this phone, of the posts, reels, stories and
 * profiles that came up on screen, newest first, so something seen while scrolling can be found
 * again. It's off until the switch is on, and off stops recording at once.
 *
 * <p>It reads only what Instagram already loaded for the screen: the media's id, who posted it and
 * the address of a small copy of its picture, or the profile's name and picture. It sends nothing
 * anywhere and reads nothing about other accounts. The history page fetches a row's picture from
 * Meta's media servers, as Instagram does, and keeps a copy in the app's cache. The list holds at
 * most {@link #MAX_ENTRIES} rows in one small file in the app's own storage, and it's left out of
 * the settings backup.
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
        /** The address of a small copy of its picture, or of the profile's picture; empty when unknown. */
        public final String picture;

        Row(long time, Kind kind, String id, String owner) {
            this(time, kind, id, owner, "");
        }

        Row(long time, Kind kind, String id, String owner, String picture) {
            this.time = time;
            this.kind = kind;
            this.id = id;
            this.owner = owner;
            this.picture = picture;
        }
    }

    /** Reads a picture's sizes off what Instagram loaded. The patched {@link InstagramMedia}; tests swap it. */
    interface Sizes {
        @Nullable Object versions(Object media);

        @Nullable List<?> pages(Object media);

        @Nullable List<?> candidates(Object versions);

        @Nullable String url(Object candidate);

        int width(Object candidate);

        @Nullable Object profilePicture(Object user);
    }

    static final Sizes PATCHED = new Sizes() {
        @Override public Object versions(Object media) {
            return InstagramMedia.imageVersions(media);
        }

        @Override public List<?> pages(Object media) {
            return InstagramMedia.carouselMedia(media);
        }

        @Override public List<?> candidates(Object versions) {
            return InstagramMedia.imageCandidates(versions);
        }

        @Override public String url(Object candidate) {
            return InstagramMedia.candidateUrl(candidate);
        }

        @Override public int width(Object candidate) {
            return InstagramMedia.candidateWidth(candidate);
        }

        @Override public Object profilePicture(Object user) {
            return InstagramMedia.profilePicture(user);
        }
    };

    static Sizes sizes = PATCHED;

    /** Fetches a picture. {@link PictureFetch}; tests swap it. */
    interface Fetch {
        @Nullable Bitmap fetch(Context context, String url, int size);
    }

    static Fetch fetch = PictureFetch::fetch;

    /** The size a row's picture is chosen at: the smallest Instagram lists that's at least this wide. */
    static final int THUMB_PX = 160;

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
            add(Utils.getContext(), Kind.PROFILE, name.trim(), name.trim(), profilePicture(user), System.currentTimeMillis());
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
            add(Utils.getContext(), kind, id.trim(), owner == null ? "" : owner.trim(), picture(media), System.currentTimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "viewing history " + kind.name().toLowerCase(java.util.Locale.ROOT), failure);
        }
    }

    /**
     * The address of [media]'s picture at about {@link #THUMB_PX} across: a reel's or a story's
     * cover, a post's photo, or a carousel's first page. Empty when Instagram listed none. Never throws.
     */
    static String picture(@Nullable Object media) {
        try {
            if (media == null) return "";
            Object versions = sizes.versions(media);
            if (versions == null) {
                List<?> pages = sizes.pages(media);
                Object first = pages == null || pages.isEmpty() ? null : pages.get(0);
                if (first != null) versions = sizes.versions(first);
            }
            return versions == null ? "" : smallest(sizes.candidates(versions));
        } catch (Throwable failure) {
            return "";
        }
    }

    /** The address of the picture [user]'s profile shows, or empty. Never throws. */
    static String profilePicture(@Nullable Object user) {
        try {
            Object shown = user == null ? null : sizes.profilePicture(user);
            String url = shown == null ? null : sizes.url(shown);
            return url == null ? "" : url.trim();
        } catch (Throwable failure) {
            return "";
        }
    }

    /**
     * Of [candidates], the address of the narrowest at least {@link #THUMB_PX} across, else the
     * widest, else the last one listed when none says how wide it is.
     */
    static String smallest(@Nullable List<?> candidates) {
        if (candidates == null) return "";
        String fits = null;
        int fitsWidth = 0;
        String widest = null;
        int widestWidth = 0;
        String last = null;
        for (Object candidate : candidates) {
            if (candidate == null) continue;
            String url = sizes.url(candidate);
            if (url == null || url.trim().isEmpty()) continue;
            url = url.trim();
            int width = sizes.width(candidate);
            last = url;
            if (width >= THUMB_PX && (fits == null || width < fitsWidth)) {
                fits = url;
                fitsWidth = width;
            }
            if (width > widestWidth) {
                widest = url;
                widestWidth = width;
            }
        }
        if (fits != null) return fits;
        if (widest != null) return widest;
        return last == null ? "" : last;
    }

    static void add(@Nullable Context context, Kind kind, String id, String owner, long now) {
        add(context, kind, id, owner, "", now);
    }

    /**
     * Puts a row at the top unless the same thing was put there within {@link #SAME_AGAIN_MS}, drops
     * the oldest past {@link #MAX_ENTRIES} and writes the file off the calling thread. The same
     * thing seen again later moves up to the top, so it shows once, with the newer picture address
     * when it came with one, since Instagram's addresses stop working after a while.
     */
    static void add(@Nullable Context context, Kind kind, String id, String owner, String picture, long now) {
        if (context == null) return;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        List<Row> dropped = new ArrayList<>();
        synchronized (LOCK) {
            load(application);
            String keptPicture = picture;
            for (int at = 0; at < ROWS.size(); at++) {
                Row row = ROWS.get(at);
                if (row.kind != kind || !row.id.equals(id)) continue;
                if (at == 0 && now - row.time < SAME_AGAIN_MS) return;
                if (keptPicture.isEmpty()) keptPicture = row.picture;
                ROWS.remove(at);
                break;
            }
            ROWS.add(0, new Row(now, kind, id, owner, keptPicture));
            while (ROWS.size() > MAX_ENTRIES) dropped.add(ROWS.remove(ROWS.size() - 1));
        }
        Utils.runOnBackgroundThread(() -> {
            save(application);
            for (Row gone : dropped) forgetPicture(application, gone);
        });
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
                boolean pictures = forgetPictures(application);
                return (!file.exists() || file.delete()) && pictures;
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "viewing history clear", failure);
                return false;
            }
        }
    }

    /**
     * Takes [kind] [id] off the list, writes the file and deletes its picture off the calling
     * thread. Answers whether it was on the list.
     */
    public static boolean remove(@Nullable Context context, Kind kind, String id) {
        if (context == null) return false;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Row gone = null;
        synchronized (LOCK) {
            load(application);
            for (int at = 0; at < ROWS.size(); at++) {
                Row row = ROWS.get(at);
                if (row.kind != kind || !row.id.equals(id)) continue;
                gone = ROWS.remove(at);
                break;
            }
        }
        if (gone == null) return false;
        Row removed = gone;
        Utils.runOnBackgroundThread(() -> {
            save(application);
            forgetPicture(application, removed);
        });
        return true;
    }

    private static File file(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), FILE);
    }

    /** The folder of the rows' pictures, in the app's cache, which Android may empty when space runs low. */
    static File pictures(Context application) {
        return new File(application.getCacheDir(), DIRECTORY);
    }

    /** Where [row]'s picture is kept once fetched. */
    static File pictureFile(Context application, Row row) {
        String name = row.kind.name().toLowerCase(java.util.Locale.ROOT) + "-" + row.id.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(pictures(application), name + ".jpg");
    }

    /**
     * [row]'s picture, [size] pixels across or a little more: the copy kept on the phone, or else
     * fetched from its address and kept, while the row is still in the list. Instagram's addresses
     * stop working after some days, so the copy is what keeps an older row's picture. Null when
     * there's none. Blocks on the network: call it on a worker thread. Never throws.
     */
    @Nullable
    public static Bitmap picture(Context context, Row row, int size) {
        try {
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            File kept = pictureFile(application, row);
            if (kept.isFile()) {
                Bitmap bitmap = PictureFetch.decode(kept, size);
                if (bitmap != null) return bitmap;
            }
            if (row.picture.isEmpty()) return null;
            Bitmap fetched = fetch.fetch(application, row.picture, size);
            if (fetched == null) return null;
            synchronized (LOCK) {
                if (holds(row)) keep(kept, fetched);
            }
            return fetched;
        } catch (Throwable failure) {
            Logger.printDebug(() -> "A viewing history picture wasn't shown: " + failure);
            return null;
        }
    }

    private static boolean holds(Row wanted) {
        for (Row row : ROWS) {
            if (row.kind == wanted.kind && row.id.equals(wanted.id)) return true;
        }
        return false;
    }

    private static void keep(File file, Bitmap bitmap) throws IOException {
        File folder = file.getParentFile();
        if (!folder.mkdirs() && !folder.isDirectory()) return;
        File work = new File(folder, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(work)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return;
        }
        Files.move(work.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void forgetPicture(Context application, Row row) {
        File kept = pictureFile(application, row);
        if (kept.exists() && !kept.delete()) Logger.printDebug(() -> "A viewing history picture stayed: " + kept);
    }

    /** Deletes every kept picture. Answers whether they're gone. */
    private static boolean forgetPictures(Context application) {
        File[] kept = pictures(application).listFiles();
        if (kept == null) return true;
        boolean gone = true;
        for (File file : kept) {
            if (file.exists() && !file.delete()) gone = false;
        }
        return gone;
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
                // Four columns before the pictures came (#228), five since.
                if (part.length != 4 && part.length != 5) continue;
                try {
                    Kind kind = Kind.valueOf(part[1]);
                    if (part[2].isEmpty()) continue;
                    ROWS.add(new Row(Long.parseLong(part[0]), kind, part[2], part[3], part.length == 5 ? part[4] : ""));
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
                            .append(clean(row.id)).append('\t').append(clean(row.owner)).append('\t')
                            .append(clean(row.picture)).append('\n');
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
