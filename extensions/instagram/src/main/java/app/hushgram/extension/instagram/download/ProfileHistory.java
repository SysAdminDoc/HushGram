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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * picture is copied at {@link #PICTURE_PX} across when it's first seen, since Instagram's address
 * for an old picture stops working once it's replaced. The list holds at most {@link #MAX_ACCOUNTS}
 * accounts and {@link #MAX_PER_FIELD} of each kind per account, in one small file in the app's own
 * storage, and it's left out of the settings backup.
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

        @Nullable String picture(Object user);
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
    };

    static Reads reads = PATCHED;

    /** Fetches a picture. {@link PictureFetch}; tests swap it. */
    interface Fetch {
        @Nullable Bitmap fetch(Context context, String url, int size);
    }

    static Fetch fetch = PictureFetch::fetch;

    /** Shows a toast. {@link Utils#showToastLong}; tests swap it. */
    interface Say {
        void say(String text);
    }

    static Say say = Utils::showToastLong;

    static final int MAX_ACCOUNTS = 1000;
    static final int MAX_PER_FIELD = 20;
    static final int PICTURE_PX = 320;

    private static final String DIRECTORY = "hushgram-profiles";
    private static final String FILE = "history.json";
    private static final String FAMILY = FamilyNames.PROFILE_PICTURE;

    private static final Object LOCK = new Object();
    /** By id, the account changed or seen last at the end. */
    private static final LinkedHashMap<String, Account> ACCOUNTS = new LinkedHashMap<>(16, 0.75f, true);
    private static File loadedFrom;

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
            Context context = Utils.getContext();
            if (context == null) return;
            long now = System.currentTimeMillis();
            Runnable note = () -> {
                String told = record(context, id, username, bio.isEmpty() ? null : bio, url, now);
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
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        String picture = pictureName(url);
        List<Field> changed = new ArrayList<>();
        String before;
        Entry shown = null;
        boolean first;
        synchronized (LOCK) {
            load(application);
            Account account = ACCOUNTS.get(id);
            first = account == null;
            if (first) {
                account = new Account(id, new ArrayList<>());
                ACCOUNTS.put(id, account);
            }
            before = account.username();
            if (add(account, Field.USERNAME, username, "", now)) changed.add(Field.USERNAME);
            if (bio != null && add(account, Field.BIO, bio, "", now)) changed.add(Field.BIO);
            if (!picture.isEmpty()) {
                if (add(account, Field.PICTURE, picture, url, now)) changed.add(Field.PICTURE);
                shown = account.latest(Field.PICTURE);
            }
            if (!changed.isEmpty()) {
                while (ACCOUNTS.size() > MAX_ACCOUNTS) {
                    String oldest = ACCOUNTS.keySet().iterator().next();
                    forgetPictures(application, ACCOUNTS.remove(oldest));
                }
                save(application);
            }
        }
        // The copy is made the first time the picture is seen, and again on a later visit when that
        // fetch failed, while its address still works.
        if (shown != null && !pictureFile(application, id, shown).isFile()) picture(application, id, shown, PICTURE_PX);
        return first || changed.isEmpty() ? null : told(changed, before);
    }

    /** Adds [value] as [account]'s newest [field] unless it's already that. Answers whether it was added. */
    private static boolean add(Account account, Field field, String value, String url, long now) {
        Entry latest = account.latest(field);
        if (latest != null && latest.value.equals(value)) return false;
        account.entries.add(new Entry(now, field, value, url));
        int kept = 0;
        for (int at = account.entries.size() - 1; at >= 0; at--) {
            if (account.entries.get(at).field == field && ++kept > MAX_PER_FIELD) account.entries.remove(at);
        }
        return true;
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
            File kept = pictureFile(application, id, entry);
            if (kept.isFile()) {
                Bitmap bitmap = PictureFetch.decode(kept, size);
                if (bitmap != null) return bitmap;
            }
            if (entry.url.isEmpty()) return null;
            Bitmap fetched = fetch.fetch(application, entry.url, Math.max(size, PICTURE_PX));
            if (fetched == null) return null;
            synchronized (LOCK) {
                Account account = ACCOUNTS.get(id);
                if (account != null && account.entries.contains(entry)) keep(kept, fetched);
            }
            return fetched;
        } catch (Throwable failure) {
            Logger.printDebug(() -> "A profile history picture wasn't shown: " + failure);
            return null;
        }
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
                        read.add(new Entry(entry.optLong("time"), field, value, entry.optString("url")));
                    } catch (IllegalArgumentException skip) {
                        // An entry that isn't one of ours is left out.
                    }
                }
                if (!read.isEmpty()) ACCOUNTS.put(id, new Account(id, read));
            }
        } catch (IOException | JSONException | RuntimeException failure) {
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
                        entries.put(new JSONObject().put("time", entry.time).put("field", entry.field.name())
                                .put("value", entry.value).put("url", entry.url));
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
        }
        reads = PATCHED;
        fetch = PictureFetch::fetch;
        say = Utils::showToastLong;
    }
}
