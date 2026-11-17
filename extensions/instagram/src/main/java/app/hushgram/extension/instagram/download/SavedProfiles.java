/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
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
import java.util.TreeSet;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.SavedProfilesPage;
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
 * {@link #MAX_PROFILES} accounts in one small file in the app's own storage, newest first. A settings
 * file carries it and an import adds it back ({@link #backup}, {@link #merge}), as its reporter asked;
 * the diagnostic report never holds it.
 *
 * <p>An account can sit in any number of folders, up to {@link #MAX_FOLDERS}, named by the user
 * (#687). A folder is only a name its accounts share, so it's there while something is in it
 * ({@link #folders}). The file and a settings file keep an account's folders under "folders" and
 * its first one under "folder" too, which is all a HushGram from before #687 reads.
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
        /** The folders it's in, as {@link #folderSet} keeps them, empty for none. Never changes. */
        public final List<String> folders;
        /** When it was saved. */
        public final long time;

        Profile(String id, String username, String name, String url, long time) {
            this(id, username, name, url, Collections.emptyList(), time);
        }

        Profile(String id, String username, String name, String url, String folder, long time) {
            this(id, username, name, url, Collections.singletonList(folder), time);
        }

        Profile(String id, String username, String name, String url, List<String> folders, long time) {
            this.id = id;
            this.username = username;
            this.name = name;
            this.url = url;
            this.folders = folderSet(folders);
            this.time = time;
        }

        /** This account in [others] folders instead of its own. */
        public Profile in(List<String> others) {
            return new Profile(id, username, name, url, others, time);
        }

        /** Whether it's in folder [folder], whatever its case. */
        public boolean isIn(@Nullable String folder) {
            if (folder == null || folder.isEmpty()) return false;
            for (String own : folders) {
                if (own.equalsIgnoreCase(folder)) return true;
            }
            return false;
        }

        /** This account with folder [from] called [to] instead, or taken out of it when [to] is empty. */
        public Profile renamed(String from, String to) {
            if (!isIn(from)) return this;
            List<String> kept = new ArrayList<>();
            for (String own : folders) {
                if (!own.equalsIgnoreCase(from)) kept.add(own);
            }
            kept.add(to);
            Profile renamed = in(kept);
            return renamed.folders.equals(folders) ? this : renamed;
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

    public static final int MAX_PROFILES = 2000;
    static final int PICTURE_PX = 160;
    /** The longest name and picture address a settings file may give an account. */
    static final int MAX_NAME = 256;
    static final int MAX_URL = 2048;
    /** The longest folder name, which a chip on the page shows whole. */
    public static final int MAX_FOLDER = 30;
    /** The most folders one account sits in, so a settings file can't give one thousands. */
    static final int MAX_FOLDERS = 30;

    private static final String DIRECTORY = "hushgram-saved";
    private static final String FILE = "profiles.json";
    private static final String FAMILY = FamilyNames.PROFILE_PICTURE;
    static final String NOT_REFRESHED = "saved profile not refreshed, no worker free";
    static final String NOT_ADDED = "save profile row not added";
    static final String OPEN_NOT_ADDED = "saved profiles row not added";
    static final String NO_SCREEN = "saved profiles not opened, no screen";

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
                    return;
                }
                // Once there are folders, Save asks which ones, so each account goes where it's wanted.
                List<String> folders = folders(all(context));
                Activity activity = folders.isEmpty() ? null : ProfilePictureViewer.activityOf(context);
                if (activity == null || activity.isFinishing()) {
                    saveIn(Collections.emptyList());
                } else {
                    SavedProfilesPage.chooseFolders(activity, L10n.t(context, "Save to folder"), folders,
                            Collections.emptyList(), L10n.t(context, "Save"), this::saveIn);
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, saved ? "remove saved profile" : "save profile", failure);
            }
        }

        /** Saves the account in [folders], or in none when there are none, and says where. */
        void saveIn(List<String> folders) {
            try {
                if (!save(context, profile.in(folders), System.currentTimeMillis())) {
                    Utils.showToastShort(L10n.t(context, "Saved profiles is full."));
                    return;
                }
                // The folders as the list spells them, which a name typed in another case joins.
                Profile kept = find(context, profile.id, profile.username);
                List<String> in = kept == null ? folderSet(folders) : kept.folders;
                if (in.isEmpty()) {
                    Utils.showToastShort(L10n.f(context, "Saved @%s", profile.username));
                } else if (in.size() == 1) {
                    Utils.showToastShort(L10n.f(context, "Saved @%1$s to %2$s", profile.username, in.get(0)));
                } else {
                    Utils.showToastShort(L10n.f(context, "Saved @%1$s to %2$d folders", profile.username, in.size()));
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "save profile", failure);
            }
        }
    }

    /**
     * The row under Save profile that opens Your saved profiles over Instagram, so the list is a tap
     * away from any profile (#629). Null while the switch is off or nothing is saved. Never throws.
     */
    @Nullable
    static OpenRow openRow(Context context) {
        try {
            if (context == null || !on() || !any(context)) return null;
            return new OpenRow(context);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "saved profiles open row", failure);
            return null;
        }
    }

    /** The menu row that opens the Saved profiles page over the screen the menu was opened on. */
    static final class OpenRow implements View.OnClickListener {
        final Context context;

        OpenRow(Context context) {
            this.context = context;
        }

        /** Instagram may tap it with no view, from a sheet drawn without one. */
        @Override public void onClick(View view) {
            try {
                if (!on()) return;
                Activity activity = ProfilePictureViewer.activityOf(context);
                if (activity == null) activity = Utils.getActivity();
                if (activity == null || activity.isFinishing()) {
                    HookStatus.counted(FAMILY, NO_SCREEN);
                    return;
                }
                SavedProfilesPage.openOver(activity);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "open saved profiles", failure);
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
                    url.isEmpty() ? before.url : url, before.folders, before.time);
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
     * Puts [profile] first on the list, in its folders and saved at [now], unless it's on the list
     * already, then writes the file and copies the picture off the main thread. Answers false only
     * when the list is full.
     */
    static boolean save(Context context, Profile profile, long now) {
        Context application = application(context);
        Profile kept;
        synchronized (LOCK) {
            load(application);
            if (indexOf(profile.id, profile.username) >= 0) return true;
            if (PROFILES.size() >= MAX_PROFILES) return false;
            kept = new Profile(profile.id, profile.username, profile.name, profile.url, spelled(profile.folders), now);
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

    /** Whether anything is saved. */
    static boolean any(Context context) {
        Context application = application(context);
        synchronized (LOCK) {
            load(application);
            return !PROFILES.isEmpty();
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

    /**
     * [typed] as a folder name: spaces and line breaks run together as one space, control characters
     * dropped, trimmed, and cut to {@link #MAX_FOLDER} characters without splitting one. Empty means
     * no folder.
     */
    public static String folderName(@Nullable CharSequence typed) {
        if (typed == null) return "";
        String text = typed.toString();
        StringBuilder name = new StringBuilder();
        boolean space = false;
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = name.length() > 0;
                continue;
            }
            if (Character.isISOControl(point)) continue;
            if (name.length() + (space ? 1 : 0) + Character.charCount(point) > MAX_FOLDER) break;
            if (space) name.append(' ');
            space = false;
            name.appendCodePoint(point);
        }
        return name.toString();
    }

    /**
     * [names] as one account's folders: each cleaned by {@link #folderName}, the empty ones left out,
     * each once whatever its case (spelled as it first comes), alphabetically, and no more than
     * {@link #MAX_FOLDERS} of them. Never changes.
     */
    public static List<String> folderSet(@Nullable List<? extends CharSequence> names) {
        if (names == null || names.isEmpty()) return Collections.emptyList();
        TreeSet<String> kept = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (CharSequence typed : names) {
            String name = folderName(typed);
            if (!name.isEmpty() && kept.size() < MAX_FOLDERS) kept.add(name);
        }
        return kept.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(kept));
    }

    /** The folders [profiles] are in, each once whatever its case, alphabetically. */
    public static List<String> folders(List<Profile> profiles) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Profile profile : profiles) names.addAll(profile.folders);
        return new ArrayList<>(names);
    }

    /** The accounts of [profiles] in [folder], whatever its case, or all of them for null, in their order. */
    public static List<Profile> inFolder(List<Profile> profiles, @Nullable String folder) {
        if (folder == null) return profiles;
        List<Profile> kept = new ArrayList<>();
        for (Profile profile : profiles) {
            if (profile.isIn(folder)) kept.add(profile);
        }
        return kept;
    }

    /**
     * Puts the saved account that is [id] or [username] in [folders] and no others, each cleaned by
     * {@link #folderName}, or in none when there are none. A name that differs only in case from a
     * folder in use is that folder. Answers the account as it's saved now, or null when it isn't saved.
     */
    @Nullable
    public static Profile putIn(Context context, String id, String username, List<String> folders) {
        Context application = application(context);
        Profile moved;
        synchronized (LOCK) {
            load(application);
            int at = indexOf(id, username);
            if (at < 0) return null;
            List<String> names = spelled(folders);
            if (PROFILES.get(at).folders.equals(names)) return PROFILES.get(at);
            moved = PROFILES.get(at).in(names);
            PROFILES.set(at, moved);
        }
        persist(application, () -> { });
        return moved;
    }

    /**
     * Puts every account in folder [from] in [to] instead, cleaned by {@link #folderName}: a rename,
     * or with [to] empty, the folder taken away and its accounts kept. Renamed onto another folder's
     * name, the two become that one. An account's other folders stay as they are. Answers the folder
     * they're in now, empty for none.
     */
    public static String renameFolder(Context context, String from, String to) {
        Context application = application(context);
        String name;
        boolean moved = false;
        synchronized (LOCK) {
            load(application);
            name = existing(folderName(to), from);
            for (int at = 0; at < PROFILES.size(); at++) {
                Profile profile = PROFILES.get(at);
                Profile renamed = profile.renamed(from, name);
                if (renamed == profile) continue;
                PROFILES.set(at, renamed);
                moved = true;
            }
        }
        if (moved) persist(application, () -> { });
        return name;
    }

    /** Must hold LOCK. [names] as {@link #folderSet} keeps them, each spelled as a folder on the list is. */
    private static List<String> spelled(List<String> names) {
        List<String> spelled = new ArrayList<>();
        for (String name : folderSet(names)) spelled.add(existing(name, null));
        return folderSet(spelled);
    }

    /** Must hold LOCK. How folder [name] is spelled on the list, other than folder [except], or [name] when none is. */
    private static String existing(String name, @Nullable String except) {
        if (name.isEmpty()) return name;
        for (Profile profile : PROFILES) {
            for (String folder : profile.folders) {
                if (folder.equalsIgnoreCase(name) && (except == null || !folder.equalsIgnoreCase(except))) return folder;
            }
        }
        return name;
    }

    /**
     * The list for a settings file (#629), newest first: each account's id, username, name, folders
     * and when it was saved, and with [pictures] its picture's address, which Instagram's servers
     * keep answering for a few days. Empty when nothing is saved.
     */
    public static JSONArray backup(Context context, boolean pictures) throws JSONException {
        JSONArray profiles = new JSONArray();
        for (Profile profile : all(context)) {
            JSONObject entry = new JSONObject().put("id", profile.id).put("username", profile.username)
                    .put("name", profile.name).put("time", profile.time);
            putFolders(entry, profile);
            if (pictures && !profile.url.isEmpty()) entry.put("url", profile.url);
            profiles.put(entry);
        }
        return profiles;
    }

    /** Writes [profile]'s folders into [entry]: all under "folders", the first under "folder" for an older HushGram. */
    private static void putFolders(JSONObject entry, Profile profile) throws JSONException {
        if (profile.folders.isEmpty()) return;
        entry.put("folder", profile.folders.get(0)).put("folders", new JSONArray(profile.folders));
    }

    /**
     * The folders [entry] names: the text ones under "folders" and the one under "folder", as
     * {@link #folderSet} keeps them. Anything else in either place is left out.
     */
    private static List<String> foldersOf(JSONObject entry) {
        List<String> names = new ArrayList<>();
        JSONArray listed = entry.optJSONArray("folders");
        for (int at = 0; listed != null && at < listed.length() && names.size() < MAX_FOLDERS * 2; at++) {
            Object name = listed.opt(at);
            if (name instanceof String) names.add((String) name);
        }
        Object first = entry.opt("folder");
        if (first instanceof String) names.add((String) first);
        return folderSet(names);
    }

    /**
     * The account [entry] of a settings file describes, or null when the list couldn't have saved it:
     * a username Instagram wouldn't give, an id or a time of another shape, or a name over
     * {@link #MAX_NAME} characters or with control characters in it. A picture address that isn't
     * Meta's media servers, or a folder that isn't text, is dropped and the account kept, and a
     * folder name is cleaned as a typed one is. A file from before #687 names one folder, and a
     * newer one names them all. Fields this build doesn't know are left alone, so a file from a
     * newer HushGram still brings its accounts.
     */
    @Nullable
    public static Profile fromBackup(JSONObject entry) {
        Object username = entry.opt("username");
        if (!(username instanceof String) || !((String) username).matches("[A-Za-z0-9._]{1,30}")) return null;
        String id = text(entry, "id");
        String name = text(entry, "name");
        String url = text(entry, "url");
        List<String> folders = foldersOf(entry);
        if (id == null || !id.matches("[A-Za-z0-9_]{0,64}") || name == null || name.length() > MAX_NAME
                || url == null) return null;
        for (int at = 0; at < name.length(); at++) {
            if (Character.isISOControl(name.charAt(at))) return null;
        }
        Object time = entry.opt("time");
        if (time != null && !(time instanceof Integer) && !(time instanceof Long)) return null;
        long saved = time == null ? 0 : ((Number) time).longValue();
        if (saved < 0) return null;
        if (url.length() > MAX_URL || MediaUrlPolicy.shapeRefusal(url) != null) url = "";
        return new Profile(id, (String) username, name, url, folders, saved);
    }

    /** [entry]'s text under [key], empty when it has none, or null when it's something else. */
    @Nullable
    private static String text(JSONObject entry, String key) {
        if (!entry.has(key)) return "";
        Object value = entry.opt(key);
        return value instanceof String ? (String) value : null;
    }

    /** What {@link #merge} changed, for Undo: the accounts it added and the ones here it put in more folders. */
    public static final class Merged {
        public final List<Profile> added = new ArrayList<>();
        /** Each as {@link #merge} left it. */
        public final List<Profile> filed = new ArrayList<>();
        /** The folders each of {@link #filed} was in before, in the same order. */
        final List<List<String>> filedFrom = new ArrayList<>();

        public boolean isEmpty() {
            return added.isEmpty() && filed.isEmpty();
        }
    }

    /**
     * Puts the accounts of [profiles], read from a settings file, on the list where they aren't on it
     * already, while there's room, each with the time it was first saved and its folders, and keeps
     * the list newest first. An account already here keeps its own folders and goes into the file's
     * too (#629, #687). Their pictures are fetched when the list shows them. Undo takes back what it
     * changed ({@link #takeBack}).
     */
    public static Merged merge(Context context, List<Profile> profiles) {
        Context application = application(context);
        Merged merged = new Merged();
        synchronized (LOCK) {
            load(application);
            for (Profile profile : profiles) {
                // Spelled as the folder on this phone is, so "Friends" and "friends" stay one folder.
                List<String> folders = spelled(profile.folders);
                int here = indexOf(profile.id, profile.username);
                if (here >= 0) {
                    Profile before = PROFILES.get(here);
                    List<String> both = new ArrayList<>(before.folders);
                    both.addAll(folders);
                    Profile filed = before.in(both);
                    if (filed.folders.equals(before.folders)) continue;
                    PROFILES.set(here, filed);
                    merged.filed.add(filed);
                    merged.filedFrom.add(before.folders);
                    continue;
                }
                if (PROFILES.size() >= MAX_PROFILES) continue;
                Profile kept = profile.in(folders);
                PROFILES.add(kept);
                merged.added.add(kept);
            }
            if (merged.isEmpty()) return merged;
            // A stable sort, so accounts saved at the same moment keep their order.
            Collections.sort(PROFILES, (one, other) -> Long.compare(other.time, one.time));
        }
        persist(application, () -> { });
        return merged;
    }

    /**
     * Takes what [merged] changed back, each account only while it's still as {@link #merge} left
     * it: the ones added go off the list and the ones filed go back to the folders they were in.
     * Undo after an import. Answers how many went off.
     */
    public static int takeBack(Context context, Merged merged) {
        Context application = application(context);
        List<Profile> gone = new ArrayList<>();
        boolean unfiled = false;
        synchronized (LOCK) {
            load(application);
            for (Profile profile : merged.added) {
                int at = indexOf(profile.id, profile.username);
                if (at >= 0 && PROFILES.get(at).time == profile.time) gone.add(PROFILES.remove(at));
            }
            for (int filed = 0; filed < merged.filed.size(); filed++) {
                Profile profile = merged.filed.get(filed);
                int at = indexOf(profile.id, profile.username);
                if (at < 0 || PROFILES.get(at).time != profile.time || !PROFILES.get(at).folders.equals(profile.folders)) continue;
                PROFILES.set(at, PROFILES.get(at).in(merged.filedFrom.get(filed)));
                unfiled = true;
            }
        }
        if (gone.isEmpty() && !unfiled) return 0;
        persist(application, () -> {
            for (Profile profile : gone) forgetPicture(application, profile);
        });
        return gone.size();
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
                        profile.optString("url"), foldersOf(profile), profile.optLong("time")));
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
                    JSONObject entry = new JSONObject().put("id", profile.id).put("username", profile.username)
                            .put("name", profile.name).put("url", profile.url).put("time", profile.time);
                    putFolders(entry, profile);
                    profiles.put(entry);
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
