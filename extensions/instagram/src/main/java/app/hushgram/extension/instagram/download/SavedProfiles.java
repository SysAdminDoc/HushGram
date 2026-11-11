/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;

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
import java.util.List;
import java.util.Locale;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Saved profiles (#629): Save profile in the menu on someone's profile puts that account on a list
 * kept on this phone, and the Saved profiles page in HushGram settings opens it again. The list is
 * HushGram's own, apart from Instagram's Favorites and Following, so an account you don't follow can
 * go on it and Instagram is told nothing.
 *
 * <p>Each account is kept by its id where this build reads one, else by its username, with the
 * username, name and picture address its profile showed. Opening a saved profile again brings those
 * up to date, so a renamed account keeps its place. Its picture is copied once, {@link #PICTURE_PX}
 * across, from Instagram's image servers, the way profile history copies one. The list holds at most
 * {@link #MAX_PROFILES} accounts in one small file in the app's own storage, newest first, and it's
 * left out of the settings backup and the diagnostic report.
 */
public final class SavedProfiles {
    /** One saved account. */
    public static final class Profile {
        /** Instagram's id for it, or empty when this build couldn't read one. */
        public final String id;
        public final String username;
        /** The name its profile shows, or empty. */
        public final String name;
        /** Its picture's address when it was last seen, or empty. */
        public final String url;
        /** When it was saved. */
        public final long time;

        Profile(String id, String username, String name, String url, long time) {
            this.id = id;
            this.username = username;
            this.name = name;
            this.url = url;
            this.time = time;
        }

        /** Whether this is the account with [otherId], or with [otherUsername] when either id is unknown. */
        boolean is(String otherId, String otherUsername) {
            if (!id.isEmpty() && !otherId.isEmpty()) return id.equals(otherId);
            return !username.isEmpty() && username.equalsIgnoreCase(otherUsername);
        }
    }

    /** Reads what Instagram loaded for a profile. The patched {@link InstagramMedia}; tests swap it. */
    interface Reads {
        @Nullable String id(Object user);

        @Nullable String username(Object user);

        @Nullable String name(Object user);

        @Nullable String picture(Object user);
    }

    static final Reads PATCHED = new Reads() {
        @Override public String id(Object user) {
            return ProfileExtras.userId(user);
        }

        @Override public String username(Object user) {
            return InstagramMedia.username(user);
        }

        @Override public String name(Object user) {
            return InstagramMedia.fullName(user);
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

    static final int MAX_PROFILES = 2000;
    static final int PICTURE_PX = 160;

    private static final String DIRECTORY = "hushgram-saved";
    private static final String FILE = "profiles.json";
    private static final String FAMILY = FamilyNames.PROFILE_PICTURE;
    static final String NOT_REFRESHED = "saved profile not refreshed, no worker free";
    static final String NOT_ADDED = "save profile row not added";

    private static final Object LOCK = new Object();
    /** Newest first. */
    private static final List<Profile> PROFILES = new ArrayList<>();
    private static File loadedFrom;

    private SavedProfiles() {
    }

    /** Whether the switch is on, which a pause answers off. Off until the settings are ready. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && Settings.SAVED_PROFILES.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * The row for the menu on [user]'s profile: Save profile, or Remove from saved when the account
     * is on the list. [username] is the one the menu read. Null while the switch is off or with no
     * username. The first call reads the list's file, which opening the profile has usually done
     * already ({@link #seen}). Never throws.
     */
    @Nullable
    static Row row(Context context, Object user, @Nullable String username) {
        try {
            if (context == null || user == null || !on()) return null;
            String named = trimmed(username);
            if (named.isEmpty()) return null;
            Profile shown = new Profile(trimmed(reads.id(user)), named, trimmed(reads.name(user)),
                    trimmed(reads.picture(user)), 0);
            return new Row(context, shown, find(context, shown.id, shown.username) != null);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "saved profiles row", failure);
            return null;
        }
    }

    /**
     * The menu row: it saves the account the menu was opened on, or with [saved] takes it off the
     * list, as its label said, and says which in a toast.
     */
    static final class Row implements View.OnClickListener {
        final Context context;
        final Profile profile;
        /** Whether the account was on the list when the menu opened. */
        final boolean saved;

        Row(Context context, Profile profile, boolean saved) {
            this.context = context;
            this.profile = profile;
            this.saved = saved;
        }

        String label() {
            return saved ? L10n.t(context, "Remove from saved") : L10n.t(context, "Save profile");
        }

        /** Instagram may tap it with no view, from a sheet drawn without one. */
        @Override public void onClick(View view) {
            try {
                if (!on()) return;
                if (saved) {
                    remove(context, profile.id, profile.username);
                    Utils.showToastShort(L10n.f(context, "Removed @%s from saved", profile.username));
                } else if (save(context, profile, System.currentTimeMillis())) {
                    Utils.showToastShort(L10n.f(context, "Saved @%s", profile.username));
                } else {
                    Utils.showToastShort(L10n.t(context, "Saved profiles is full."));
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, saved ? "remove saved profile" : "save profile", failure);
            }
        }
    }

    /**
     * A profile opened, with its User. When it's a saved account, its username, name and picture are
     * brought up to date off Instagram's thread, and either way the list is read before the profile's
     * menu asks for it. Does nothing while the switch is off. Never throws.
     */
    public static void seen(@Nullable Object user) {
        try {
            if (user == null || !on()) return;
            String id = trimmed(reads.id(user));
            String username = trimmed(reads.username(user));
            if (username.isEmpty()) return;
            String name = trimmed(reads.name(user));
            String url = trimmed(reads.picture(user));
            Context context = Utils.getContext();
            if (context == null) return;
            if (!Utils.runOnBackgroundThread(() -> refresh(context, id, username, name, url))) {
                HookStatus.counted(FAMILY, NOT_REFRESHED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "saved profiles", failure);
        }
    }

    /**
     * Brings the saved account that is [id] or [username] up to date with what its profile shows now,
     * and copies its picture when there's no copy yet. An empty name or address keeps the one saved,
     * since a profile reached from a post can come without them. Blocks on the file and the network:
     * call it on a worker thread. Answers whether anything saved changed.
     */
    static boolean refresh(Context context, String id, String username, String name, String url) {
        Context application = application(context);
        Profile before;
        Profile after;
        synchronized (LOCK) {
            load(application);
            int at = indexOf(id, username);
            if (at < 0) return false;
            before = PROFILES.get(at);
            after = new Profile(id.isEmpty() ? before.id : id, username, name.isEmpty() ? before.name : name,
                    url.isEmpty() ? before.url : url, before.time);
            PROFILES.set(at, after);
        }
        boolean changed = !after.id.equals(before.id) || !after.username.equals(before.username)
                || !after.name.equals(before.name)
                || !ProfileHistory.pictureName(after.url).equals(ProfileHistory.pictureName(before.url));
        if (changed) write(application);
        if (!pictureFile(application, before).equals(pictureFile(application, after))) forgetPicture(application, before);
        if (!after.url.isEmpty() && !pictureFile(application, after).isFile()) picture(application, after, PICTURE_PX);
        return changed;
    }

    /**
     * Puts [profile] first on the list, saved at [now], unless it's on the list already, then writes
     * the file and copies the picture off the main thread. Answers false only when the list is full.
     */
    static boolean save(Context context, Profile profile, long now) {
        Context application = application(context);
        Profile kept;
        synchronized (LOCK) {
            load(application);
            if (indexOf(profile.id, profile.username) >= 0) return true;
            if (PROFILES.size() >= MAX_PROFILES) return false;
            kept = new Profile(profile.id, profile.username, profile.name, profile.url, now);
            PROFILES.add(0, kept);
        }
        persist(application, () -> {
            if (!kept.url.isEmpty() && !pictureFile(application, kept).isFile()) picture(application, kept, PICTURE_PX);
        });
        return true;
    }

    /** Takes the account that is [id] or [username] off the list. Answers whether it was on it. */
    public static boolean remove(Context context, String id, String username) {
        Context application = application(context);
        Profile gone;
        synchronized (LOCK) {
            load(application);
            int at = indexOf(id, username);
            if (at < 0) return false;
            gone = PROFILES.remove(at);
        }
        persist(application, () -> forgetPicture(application, gone));
        return true;
    }

    /** Every saved account, the newest first. */
    public static List<Profile> all(Context context) {
        Context application = application(context);
        synchronized (LOCK) {
            load(application);
            return Collections.unmodifiableList(new ArrayList<>(PROFILES));
        }
    }

    /** The saved account that is [id] or [username], or null. */
    @Nullable
    static Profile find(Context context, String id, String username) {
        Context application = application(context);
        synchronized (LOCK) {
            load(application);
            int at = indexOf(id, username);
            return at < 0 ? null : PROFILES.get(at);
        }
    }

    /** The accounts of [profiles] whose username or name holds [query], an at sign before it or not, in their order. */
    public static List<Profile> matching(List<Profile> profiles, @Nullable String query) {
        String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (wanted.startsWith("@")) wanted = wanted.substring(1);
        if (wanted.isEmpty()) return profiles;
        List<Profile> kept = new ArrayList<>();
        for (Profile profile : profiles) {
            if (profile.username.toLowerCase(Locale.ROOT).contains(wanted)
                    || profile.name.toLowerCase(Locale.ROOT).contains(wanted)) {
                kept.add(profile);
            }
        }
        return kept;
    }

    /** Must hold LOCK. */
    private static int indexOf(String id, String username) {
        for (int at = 0; at < PROFILES.size(); at++) {
            if (PROFILES.get(at).is(id, username)) return at;
        }
        return -1;
    }

    /**
     * Writes the file off the main thread, then runs [after] there. With no worker free, the file is
     * written here and [after] is left out, so a change is never lost.
     */
    private static void persist(Context application, Runnable after) {
        if (!Utils.runOnBackgroundThread(() -> {
            write(application);
            after.run();
        })) {
            write(application);
        }
    }

    private static File file(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), FILE);
    }

    /** The folder of the copied pictures, in the app's own storage. */
    static File pictures(Context application) {
        return new File(new File(application.getFilesDir(), DIRECTORY), "pictures");
    }

    /** Where [profile]'s picture is kept: by the account and the picture, so a new picture gets a new file. */
    static File pictureFile(Context application, Profile profile) {
        String account = profile.id.isEmpty() ? "u_" + profile.username.toLowerCase(Locale.ROOT) : profile.id;
        String name = (account + "-" + ProfileHistory.pictureName(profile.url)).replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(pictures(application), name.endsWith(".jpg") ? name : name + ".jpg");
    }

    /**
     * [profile]'s picture, [size] pixels across or a little more: the copy kept on the phone, or else
     * fetched from its address and kept while the account is still saved with that picture. Null when
     * there's none. Blocks on the network: call it on a worker thread. Never throws.
     */
    @Nullable
    public static Bitmap picture(Context context, Profile profile, int size) {
        try {
            Context application = application(context);
            File kept = pictureFile(application, profile);
            if (kept.isFile()) {
                Bitmap bitmap = PictureFetch.decode(kept, size);
                if (bitmap != null) return bitmap;
            }
            if (profile.url.isEmpty()) return null;
            Bitmap fetched = fetch.fetch(application, profile.url, Math.max(size, PICTURE_PX));
            if (fetched == null) return null;
            synchronized (LOCK) {
                load(application);
                int at = indexOf(profile.id, profile.username);
                if (at >= 0 && pictureFile(application, PROFILES.get(at)).equals(kept)) keep(kept, fetched);
            }
            return fetched;
        } catch (Throwable failure) {
            Logger.printDebug(() -> "A saved profile picture wasn't shown: " + failure);
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

    /** Deletes [profile]'s picture unless an account still on the list uses that file. */
    private static void forgetPicture(Context application, Profile profile) {
        File kept = pictureFile(application, profile);
        synchronized (LOCK) {
            for (Profile saved : PROFILES) {
                if (pictureFile(application, saved).equals(kept)) return;
            }
            if (kept.exists() && !kept.delete()) Logger.printDebug(() -> "A saved profile picture stayed: " + kept);
        }
    }

    private static void load(Context application) {
        File file = file(application);
        if (file.equals(loadedFrom)) return;
        loadedFrom = file;
        PROFILES.clear();
        try {
            if (!file.isFile()) return;
            JSONArray profiles = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int at = 0; at < profiles.length() && PROFILES.size() < MAX_PROFILES; at++) {
                JSONObject profile = profiles.optJSONObject(at);
                if (profile == null) continue;
                String username = profile.optString("username").trim();
                if (username.isEmpty()) continue;
                PROFILES.add(new Profile(profile.optString("id").trim(), username, profile.optString("name"),
                        profile.optString("url"), profile.optLong("time")));
            }
        } catch (IOException | JSONException | RuntimeException failure) {
            HookStatus.threw(FAMILY, "saved profiles read", failure);
        }
    }

    private static void write(Context application) {
        synchronized (LOCK) {
            try {
                File file = file(application);
                File folder = file.getParentFile();
                if (!folder.mkdirs() && !folder.isDirectory()) return;
                JSONArray profiles = new JSONArray();
                for (Profile profile : PROFILES) {
                    profiles.put(new JSONObject().put("id", profile.id).put("username", profile.username)
                            .put("name", profile.name).put("url", profile.url).put("time", profile.time));
                }
                File work = new File(folder, FILE + ".tmp");
                Files.write(work.toPath(), profiles.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(work.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | JSONException | RuntimeException failure) {
                HookStatus.threw(FAMILY, "saved profiles write", failure);
            }
        }
    }

    private static Context application(Context context) {
        return context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    private static String trimmed(@Nullable String text) {
        return text == null ? "" : text.trim();
    }

    /** Forgets what this process holds, as a new process would, and puts back Instagram's reads. */
    static void forgetForTests() {
        synchronized (LOCK) {
            PROFILES.clear();
            loadedFrom = null;
        }
        reads = PATCHED;
        fetch = PictureFetch::fetch;
    }

    /** Deletes the list's file and pictures, for a test that starts from nothing. */
    static void clearForTests(Context context) {
        Context application = application(context);
        synchronized (LOCK) {
            PROFILES.clear();
            loadedFrom = null;
            File[] kept = pictures(application).listFiles();
            if (kept != null) {
                for (File picture : kept) {
                    if (!picture.delete()) Logger.printDebug(() -> "A saved profile picture stayed: " + picture);
                }
            }
            File file = file(application);
            if (file.exists() && !file.delete()) Logger.printDebug(() -> "The saved profiles file stayed: " + file);
        }
    }
}
