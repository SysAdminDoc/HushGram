/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Profile history (#611): each profile opened while the switch is on has its username, bio and
 * picture kept on this phone under the account's id, so the next time it's opened HushGram can tell
 * what changed, say so in a toast, and list the old ones.
 *
 * <p>It reads only what Instagram loaded to show the profile, so a private account shows up only
 * when you can see it. Nothing is fetched in the background and nothing is sent anywhere. Each
 * picture is copied when it's first seen, since Instagram's address for an old picture stops
 * working once it's replaced: the largest size the profile loaded, its full size one when Instagram
 * has it, kept byte for byte as it came. A copy kept smaller is replaced on a later visit that
 * comes with the full size. The list holds at most {@link #MAX_ACCOUNTS}
 * accounts and {@link #MAX_PER_FIELD} of each kind per account, in one small file in the app's own
 * storage, and it's left out of the settings backup. With its Save changed pictures switch on, a
 * picture that changed puts the old copy and the new one in the gallery, each once (#715).
 */
public final class ProfileHistory {
    /** What an entry keeps. */
    public enum Field { USERNAME, BIO, PICTURE }

    /** One value an account had, from when it was first seen with it. */
    public static final class Entry {
        public final long time;
        public final Field field;
        /** The username, the bio's text, or the picture's file name. */
        public final String value;
        /** The picture's address when it was seen, empty for the other fields. */
        public final String url;
        /** Whether this picture went to the gallery by itself once (#715). Read and set under the lock. */
        boolean inGallery;

        Entry(long time, Field field, String value, String url) {
            this.time = time;
            this.field = field;
            this.value = value;
            this.url = url;
        }
    }

    /** One account and what it had, oldest first. */
    public static final class Account {
        public final String id;
        public final List<Entry> entries;

        Account(String id, List<Entry> entries) {
            this.id = id;
            this.entries = entries;
        }

        /** The newest value of [field], or null when it was never seen. */
        @Nullable
        public Entry latest(Field field) {
            for (int at = entries.size() - 1; at >= 0; at--) {
                if (entries.get(at).field == field) return entries.get(at);
            }
            return null;
        }

        /** The username it had last. */
        public String username() {
            Entry name = latest(Field.USERNAME);
            return name == null ? "" : name.value;
        }
    }

    /** A value an account no longer has, for the history page. */
    public static final class Past {
        public final String id;
        /** The account's username now, which its profile opens by. */
        public final String username;
        public final Entry entry;
        /** When it was first seen with something else. */
        public final long until;

        Past(String id, String username, Entry entry, long until) {
            this.id = id;
            this.username = username;
            this.entry = entry;
            this.until = until;
        }
    }

    /** Reads what Instagram loaded for a profile. The patched {@link InstagramMedia}; tests swap it. */
    interface Reads {
        @Nullable String id(Object user);

        @Nullable String username(Object user);

        /** Null when this build can't read bios, so a bio is never taken for an empty one. */
        @Nullable String bio(Object user);

        /** The picture the profile shows. Its file name is what tells one picture from the next. */
        @Nullable String picture(Object user);

        /** The same picture's full size address, or null when Instagram didn't load one. */
        @Nullable String fullPicture(Object user);

        /** The full size picture's width in pixels, or 0 when it isn't known. */
        int fullPictureWidth(Object user);
    }

    static final Reads PATCHED = new Reads() {
        @Override public String id(Object user) {
            return ProfileExtras.userId(user);
        }

        @Override public String username(Object user) {
            return InstagramMedia.username(user);
        }

        @Override public String bio(Object user) {
            return InstagramMedia.biography(user);
        }

        @Override public String picture(Object user) {
            Object shown = InstagramMedia.profilePicture(user);
            return shown == null ? null : InstagramMedia.candidateUrl(shown);
        }

        @Override public String fullPicture(Object user) {
            Object full = InstagramMedia.fullSizeProfilePicture(user);
            return full == null ? null : InstagramMedia.profilePictureUrl(full);
        }

        @Override public int fullPictureWidth(Object user) {
            Object full = InstagramMedia.fullSizeProfilePicture(user);
            return full == null ? 0 : InstagramMedia.profilePictureWidth(full);
        }
    };

    static Reads reads = PATCHED;

    /** Fetches a picture into a file as it came. {@link PictureFetch#fetchInto}; tests swap it. */
    interface Fetch {
        boolean fetch(Context context, String url, File into);
    }

    static Fetch fetch = PictureFetch::fetchInto;

    /** Starts a save to the gallery. {@link MediaSave#startPicture}; tests swap it. */
    interface Gallery {
        void save(Context application, PostDetails details, MediaSave.Job job);
    }

    static Gallery gallery = MediaSave::startPicture;

    /** Shows a toast. {@link Utils#showToastLong}; tests swap it. */
    interface Say {
        void say(String text);
    }

    static Say say = Utils::showToastLong;

    static final int MAX_ACCOUNTS = 1000;
    static final int MAX_PER_FIELD = 20;

    private static final String DIRECTORY = "hushgram-profiles";
    private static final String FILE = "history.json";
    private static final String FAMILY = FamilyNames.PROFILE_PICTURE;

    private static final Object LOCK = new Object();
    /** By id, the account changed or seen last at the end. */
    private static final LinkedHashMap<String, Account> ACCOUNTS = new LinkedHashMap<>(16, 0.75f, true);
    private static File loadedFrom;
    /** Whether this process has looked for pictures no entry keeps any more (#683). */
    private static boolean swept;
    /** Whether the last {@link #load} read the list without a failure, so the sweep can trust it. */
    private static boolean readCleanly;
    /** How old a fetch's work file is before the sweep takes it for one a crash left behind. */
    static final long STALE_PART_MS = 60 * 60 * 1000L;

    private ProfileHistory() {
    }

    /** Whether the switch is on. Off until the settings are ready. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && Settings.PROFILE_HISTORY.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * A profile opened, with its User. Reads it here, on Instagram's thread, and notes it off that
     * thread, since the first note reads a file. Does nothing while the switch is off. Never throws.
     */
    public static void seen(@Nullable Object user) {
        try {
            if (user == null || !on()) return;
            String id = trimmed(reads.id(user));
            String username = trimmed(reads.username(user));
            if (id.isEmpty() || username.isEmpty()) return;
            // A profile reached from a post can come with no bio until the profile itself loads, so
            // an empty one isn't taken for a change.
            String bio = trimmed(reads.bio(user));
            String url = trimmed(reads.picture(user));
            String full = url.isEmpty() ? "" : trimmed(reads.fullPicture(user));
            int fullWidth = full.isEmpty() ? 0 : reads.fullPictureWidth(user);
            Context context = Utils.getContext();
            if (context == null) return;
            long now = System.currentTimeMillis();
            Runnable note = () -> {
                String told = record(context, id, username, bio.isEmpty() ? null : bio, url, full, fullWidth, now);
                if (told != null) say.say(told);
            };
            if (!Utils.runOnBackgroundThread(note)) HookStatus.counted(FAMILY, NOT_NOTED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "profile history", failure);
        }
    }

    static final String NOT_NOTED = "profile history not noted, no worker free";

    /**
     * Adds what changed since [id] was last seen, or everything on its first sighting, writes the file
     * and copies the picture it shows when there's no copy yet. Blocks on the file and the network:
     * call it on a worker thread.
     * Answers what to tell the user, or null when nothing changed or it's the first time.
     */
    @Nullable
    static String record(Context context, String id, String username, @Nullable String bio, String url, long now) {
        return record(context, id, username, bio, url, "", 0, now);
    }

    /**
     * As above, with the shown picture's full size address [full], [fullWidth] pixels across, empty
     * when Instagram didn't load one. That's the copy kept when there is one.
     */
    @Nullable
    static String record(Context context, String id, String username, @Nullable String bio, String url,
            String full, int fullWidth, long now) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        String picture = pictureName(url);
        List<Field> changed = new ArrayList<>();
        boolean added = false;
        String before;
        Entry shown = null;
        Entry replaced = null;
        boolean first;
        List<Entry> dropped = new ArrayList<>();
        synchronized (LOCK) {
            load(application);
            if (!swept) {
                swept = true;
                // A list that didn't read says nothing about which copies are still kept.
                if (readCleanly) sweepPictures(application);
            }
            Account account = ACCOUNTS.get(id);
            first = account == null;
            if (first) {
                account = new Account(id, new ArrayList<>());
                ACCOUNTS.put(id, account);
            }
            before = account.username();
            // A bio or a picture seen for the first time on a later visit, say after one reached from a
            // post that came with neither, is kept but never told as a change: there's nothing older.
            boolean hadBio = account.latest(Field.BIO) != null, hadPicture = account.latest(Field.PICTURE) != null;
            if (add(account, Field.USERNAME, username, "", now, dropped)) {
                added = true;
                changed.add(Field.USERNAME);
            }
            if (bio != null && add(account, Field.BIO, bio, "", now, dropped)) {
                added = true;
                if (hadBio) changed.add(Field.BIO);
            }
            if (!picture.isEmpty()) {
                Entry had = account.latest(Field.PICTURE);
                if (add(account, Field.PICTURE, picture, url, now, dropped)) {
                    added = true;
                    if (hadPicture) {
                        changed.add(Field.PICTURE);
                        if (account.entries.contains(had)) replaced = had;
                    }
                }
                shown = account.latest(Field.PICTURE);
            }
            if (added) {
                forgetDropped(application, account, dropped);
                while (ACCOUNTS.size() > MAX_ACCOUNTS) {
                    String oldest = ACCOUNTS.keySet().iterator().next();
                    forgetPictures(application, ACCOUNTS.remove(oldest));
                }
                save(application);
            }
        }
        if (shown != null) keepPicture(application, id, shown, url, full, fullWidth);
        if (shown != null && changed.contains(Field.PICTURE) && savesPictures()) {
            toGallery(application, id, username, replaced, shown);
        }
        return first || changed.isEmpty() ? null : told(changed, before);
    }

    /** Whether a changed picture goes to the gallery by itself (#715). Off until the settings are ready. Never throws. */
    static boolean savesPictures() {
        try {
            return Utils.settingsReady() && Settings.PROFILE_HISTORY_SAVE_PICTURES.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Puts the kept copies of [pictures] in the gallery, the picture a profile had and the one it has
     * now, each one once (#715): one already saved this way is left out, so the next change saves only
     * its new picture. A copy that isn't kept yet is fetched first. Blocks on the network. Never throws.
     */
    private static void toGallery(Context application, String id, String username, Entry... pictures) {
        for (Entry entry : pictures) {
            if (entry == null) continue;
            try {
                synchronized (LOCK) {
                    if (inGallery(id, entry)) continue;
                }
                File kept = keptPicture(application, id, entry);
                String mime = kept == null ? null : PictureFetch.mime(kept);
                if (mime == null) {
                    Logger.printDebug(() -> "A changed profile picture has no copy for the gallery: " + entry.value);
                    continue;
                }
                synchronized (LOCK) {
                    Account account = ACCOUNTS.get(id);
                    if (account == null || !account.entries.contains(entry) || inGallery(id, entry)) continue;
                    entry.inGallery = true;
                    save(application);
                }
                gallery.save(application, PostDetails.profilePicture(username),
                        (writer, progress) -> Downloader.publish(kept, mime, writer, progress));
            } catch (Throwable failure) {
                Logger.printDebug(() -> "A changed profile picture wasn't saved to the gallery: " + failure);
            }
        }
    }

    /**
     * Whether [entry]'s picture already went to the gallery, as itself or as an older entry for the
     * same picture, one the account had before and went back to. Call it under the lock.
     */
    private static boolean inGallery(String id, Entry entry) {
        if (entry.inGallery) return true;
        Account account = ACCOUNTS.get(id);
        if (account == null) return false;
        for (Entry other : account.entries) {
            if (other.inGallery && other.field == Field.PICTURE && other.value.equals(entry.value)) return true;
        }
        return false;
    }

    /**
     * Copies [entry]'s picture, the one the profile shows now, from [full] when Instagram loaded its
     * full size, else from [url]: the first time it's seen, again on a later visit when that fetch
     * failed while its address still works, and once a process when the copy kept is narrower than
     * [fullWidth], as one first seen from a post at the shown size is. Blocks on the network.
     */
    private static void keepPicture(Context application, String id, Entry entry, String url, String full, int fullWidth) {
        File kept = pictureFile(application, id, entry);
        int have = kept.isFile() ? PictureFetch.width(kept) : 0;
        if (have > 0) {
            if (full.isEmpty() || fullWidth <= have || !UPGRADES.add(kept.getName() + "@" + fullWidth)) return;
            fetchInto(application, id, entry, full, kept, have);
            return;
        }
        if (!full.isEmpty() && fetchInto(application, id, entry, full, kept, 0)) return;
        if (!url.isEmpty() && !url.equals(full)) fetchInto(application, id, entry, url, kept, 0);
    }

    /** The kept copies tried at a larger size in this process, by file and width, so each is tried once. */
    private static final Set<String> UPGRADES = Collections.synchronizedSet(new HashSet<>());

    /**
     * Fetches [source] and keeps it as [entry]'s copy [kept] when it's a picture wider than [have]
     * pixels and the entry is still in the list. Answers whether it was kept.
     */
    private static boolean fetchInto(Context application, String id, Entry entry, String source, File kept, int have) {
        File folder = kept.getParentFile();
        if (folder == null || (!folder.mkdirs() && !folder.isDirectory())) return false;
        File part = null;
        try {
            part = File.createTempFile(kept.getName(), ".part", folder);
            if (!fetch.fetch(application, source, part) || PictureFetch.width(part) <= have) return false;
            synchronized (LOCK) {
                Account account = ACCOUNTS.get(id);
                if (account == null || !account.entries.contains(entry)) return false;
                Files.move(part.toPath(), kept.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (IOException | RuntimeException failure) {
            Logger.printDebug(() -> "A profile history picture wasn't kept: " + failure);
            return false;
        } finally {
            if (part != null && part.exists() && !part.delete()) Logger.printDebug(() -> "A profile history work file stayed");
        }
    }

    /**
     * Adds [value] as [account]'s newest [field] unless it's already that, putting the oldest past
     * {@link #MAX_PER_FIELD} in [dropped]. Answers whether it was added.
     */
    private static boolean add(Account account, Field field, String value, String url, long now, List<Entry> dropped) {
        Entry latest = account.latest(field);
        if (latest != null && latest.value.equals(value)) return false;
        account.entries.add(new Entry(now, field, value, url));
        int kept = 0;
        for (int at = account.entries.size() - 1; at >= 0; at--) {
            if (account.entries.get(at).field == field && ++kept > MAX_PER_FIELD) dropped.add(account.entries.remove(at));
        }
        return true;
    }

    /**
     * Deletes the kept copies of the pictures in [dropped], the entries [account] no longer lists,
     * unless an entry it still lists is the same picture, which shares the file (#683).
     */
    private static void forgetDropped(Context application, Account account, List<Entry> dropped) {
        for (Entry entry : dropped) {
            if (entry.field != Field.PICTURE) continue;
            boolean shared = false;
            for (Entry still : account.entries) {
                if (still.field == Field.PICTURE && still.value.equals(entry.value)) shared = true;
            }
            File kept = pictureFile(application, account.id, entry);
            if (!shared && kept.exists() && !kept.delete()) Logger.printDebug(() -> "A profile history picture stayed: " + kept);
        }
    }

    /**
     * Deletes the copies in the pictures folder that no listed entry keeps, which earlier builds left
     * behind as entries fell past {@link #MAX_PER_FIELD} (#683), and fetch work files older than
     * {@link #STALE_PART_MS} that a crash left. Holds {@link #LOCK}, so no copy is moved in meanwhile.
     */
    private static void sweepPictures(Context application) {
        File[] files = pictures(application).listFiles();
        if (files == null || files.length == 0) return;
        Set<String> keep = new HashSet<>();
        for (Account account : ACCOUNTS.values()) {
            for (Entry entry : account.entries) {
                if (entry.field == Field.PICTURE) keep.add(pictureFile(application, account.id, entry).getName());
            }
        }
        long now = System.currentTimeMillis();
        int deleted = 0;
        for (File file : files) {
            String name = file.getName();
            boolean orphan = name.endsWith(".jpg") ? !keep.contains(name)
                    : name.endsWith(".part") && now - file.lastModified() > STALE_PART_MS;
            if (orphan && file.isFile() && file.delete()) deleted++;
        }
        int count = deleted;
        if (count > 0) Logger.printDebug(() -> "Profile history: deleted " + count + " pictures no entry keeps");
    }

    /** The toast for what changed: the old username first, since that's what's no longer shown. */
    static String told(List<Field> changed, String before) {
        if (changed.contains(Field.USERNAME)) return L10n.f("Used to be @%s", before);
        if (changed.contains(Field.PICTURE) && changed.contains(Field.BIO)) return L10n.t("New profile picture and bio");
        if (changed.contains(Field.PICTURE)) return L10n.t("New profile picture");
        return L10n.t("Their bio changed");
    }

    /**
     * The picture's file name off its address, which stays the same for one picture whatever size or
     * signature Instagram asks for it with. Empty for no address.
     */
    static String pictureName(String url) {
        if (url == null || url.isEmpty()) return "";
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? path : name;
    }

    /** Every value an account had before its newest, the one replaced last first. */
    public static List<Past> past(Context context) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        List<Past> found = new ArrayList<>();
        synchronized (LOCK) {
            load(application);
            for (Account account : ACCOUNTS.values()) {
                List<Entry> entries = account.entries;
                for (int at = 0; at < entries.size(); at++) {
                    Entry entry = entries.get(at);
                    for (int next = at + 1; next < entries.size(); next++) {
                        if (entries.get(next).field != entry.field) continue;
                        found.add(new Past(account.id, account.username(), entry, entries.get(next).time));
                        break;
                    }
                }
            }
        }
        Collections.sort(found, (a, b) -> Long.compare(b.until, a.until));
        return found;
    }

    /** The account with [id], or null when it was never seen. */
    @Nullable
    public static Account account(Context context, String id) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            load(application);
            Account account = ACCOUNTS.get(id);
            return account == null ? null : copy(account);
        }
    }

    /** How many accounts are kept. */
    public static int count(Context context) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            load(application);
            return ACCOUNTS.size();
        }
    }

    private static Account copy(Account account) {
        return new Account(account.id, Collections.unmodifiableList(new ArrayList<>(account.entries)));
    }

    /** Forgets every account and deletes the file and the pictures. Answers whether they're gone. */
    public static boolean clear(Context context) {
        if (context == null) return false;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                File file = file(application);
                ACCOUNTS.clear();
                UPGRADES.clear();
                loadedFrom = file;
                File work = new File(file.getParentFile(), FILE + ".tmp");
                if (work.exists() && !work.delete()) return false;
                boolean pictures = true;
                File[] kept = pictures(application).listFiles();
                if (kept != null) {
                    for (File picture : kept) if (picture.exists() && !picture.delete()) pictures = false;
                }
                return (!file.exists() || file.delete()) && pictures;
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "profile history clear", failure);
                return false;
            }
        }
    }

    private static File file(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), FILE);
    }

    /** The folder of the copied pictures, in the app's own storage, since an old one can't be fetched again. */
    static File pictures(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), "pictures");
    }

    static File pictureFile(Context application, String id, Entry entry) {
        String name = (id + "-" + entry.value).replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(pictures(application), name.endsWith(".jpg") ? name : name + ".jpg");
    }

    /**
     * [entry]'s picture, [size] pixels across or a little more: the copy kept on the phone, or else
     * fetched from its address and kept. Null when there's none. Blocks on the network: call it on a
     * worker thread. Never throws.
     */
    @Nullable
    public static Bitmap picture(Context context, String id, Entry entry, int size) {
        try {
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            File kept = keptPicture(application, id, entry);
            return kept == null ? null : PictureFetch.decode(kept, size);
        } catch (Throwable failure) {
            Logger.printDebug(() -> "A profile history picture wasn't shown: " + failure);
            return null;
        }
    }

    /**
     * The file holding [entry]'s copy, fetched from its address first when there's none yet. Null
     * when there's none. Blocks on the network.
     */
    @Nullable
    private static File keptPicture(Context application, String id, Entry entry) {
        File kept = pictureFile(application, id, entry);
        if (kept.isFile() && PictureFetch.width(kept) > 0) return kept;
        if (entry.url.isEmpty()) return null;
        return fetchInto(application, id, entry, entry.url, kept, 0) ? kept : null;
    }

    /**
     * Opens [change]'s old picture full screen from the copy kept on this phone, over the screen
     * [context] belongs to, with a Save that puts that copy in the gallery as it is. Says so when it
     * can't open. Answers whether it opened. Never throws.
     */
    public static boolean viewPicture(Context context, Past change) {
        try {
            if (context == null || change.entry.field != Field.PICTURE) return false;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            if (ProfilePictureViewer.openKept(context, () -> keptPicture(application, change.id, change.entry), activity -> {
                // The save says for itself when it fails.
                savePicture(activity, change);
                return true;
            })) {
                return true;
            }
            ProfilePictureViewer.notOpened(context);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "view profile history picture", failure);
            ProfilePictureViewer.notOpened(context);
            return false;
        }
    }

    /**
     * Saves [change]'s old picture to the gallery from the copy kept on this phone, byte for byte,
     * the way a profile picture save names and files it. Says Download failed when there's no copy.
     * Answers whether the save started. Never throws.
     */
    public static boolean savePicture(Context context, Past change) {
        try {
            if (context == null || change.entry.field != Field.PICTURE) return false;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            File kept = pictureFile(application, change.id, change.entry);
            String mime = kept.isFile() ? PictureFetch.mime(kept) : null;
            if (mime == null) {
                ProfilePicture.failed(application);
                return false;
            }
            gallery.save(application, PostDetails.profilePicture(change.username),
                    (writer, progress) -> Downloader.publish(kept, mime, writer, progress));
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "save profile history picture", failure);
            ProfilePicture.failed(context);
            return false;
        }
    }

    private static void forgetPictures(Context application, @Nullable Account account) {
        if (account == null) return;
        for (Entry entry : account.entries) {
            if (entry.field != Field.PICTURE) continue;
            File kept = pictureFile(application, account.id, entry);
            if (kept.exists() && !kept.delete()) Logger.printDebug(() -> "A profile history picture stayed: " + kept);
        }
    }

    private static void load(Context application) {
        File file = file(application);
        if (file.equals(loadedFrom)) return;
        loadedFrom = file;
        ACCOUNTS.clear();
        readCleanly = true;
        try {
            if (!file.isFile()) return;
            JSONArray accounts = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int a = 0; a < accounts.length() && ACCOUNTS.size() < MAX_ACCOUNTS; a++) {
                JSONObject account = accounts.optJSONObject(a);
                String id = account == null ? "" : account.optString("id");
                JSONArray entries = account == null ? null : account.optJSONArray("entries");
                if (id.isEmpty() || entries == null) continue;
                List<Entry> read = new ArrayList<>();
                for (int e = 0; e < entries.length(); e++) {
                    JSONObject entry = entries.optJSONObject(e);
                    if (entry == null) continue;
                    try {
                        Field field = Field.valueOf(entry.optString("field"));
                        String value = entry.optString("value");
                        if (field != Field.BIO && value.isEmpty()) continue;
                        Entry kept = new Entry(entry.optLong("time"), field, value, entry.optString("url"));
                        kept.inGallery = entry.optBoolean("gallery");
                        read.add(kept);
                    } catch (IllegalArgumentException skip) {
                        // An entry that isn't one of ours is left out.
                    }
                }
                if (!read.isEmpty()) ACCOUNTS.put(id, new Account(id, read));
            }
        } catch (IOException | JSONException | RuntimeException failure) {
            readCleanly = false;
            HookStatus.threw(FAMILY, "profile history read", failure);
        }
    }

    private static void save(Context application) {
        synchronized (LOCK) {
            try {
                File file = file(application);
                File folder = file.getParentFile();
                if (!folder.mkdirs() && !folder.isDirectory()) return;
                JSONArray accounts = new JSONArray();
                for (Map.Entry<String, Account> kept : ACCOUNTS.entrySet()) {
                    JSONArray entries = new JSONArray();
                    for (Entry entry : kept.getValue().entries) {
                        JSONObject row = new JSONObject().put("time", entry.time).put("field", entry.field.name())
                                .put("value", entry.value).put("url", entry.url);
                        if (entry.inGallery) row.put("gallery", true);
                        entries.put(row);
                    }
                    accounts.put(new JSONObject().put("id", kept.getKey()).put("entries", entries));
                }
                File work = new File(folder, FILE + ".tmp");
                Files.write(work.toPath(), accounts.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(work.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | JSONException | RuntimeException failure) {
                HookStatus.threw(FAMILY, "profile history write", failure);
            }
        }
    }

    private static String trimmed(@Nullable String text) {
        return text == null ? "" : text.trim();
    }

    /** Forgets what this process holds, as a new process would. */
    static void forgetForTests() {
        synchronized (LOCK) {
            ACCOUNTS.clear();
            loadedFrom = null;
            swept = false;
        }
        UPGRADES.clear();
        reads = PATCHED;
        fetch = PictureFetch::fetchInto;
        gallery = MediaSave::startPicture;
        say = Utils::showToastLong;
    }
}
