/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Recent unfollowers, a switch of the "Show if a profile follows you" patch (#208): who has left your
 * Followers list since you last looked at all of it.
 *
 * <p>Nothing is asked of Instagram for it. Each time Instagram builds the rows of a follow list from
 * the accounts it has loaded so far, the patch hands that list here ({@link FollowingList#ordered}).
 * It's only taken when it's your own Followers list, Instagram has no more of it to load, and it holds
 * exactly as many distinct accounts as your profile's follower count says ({@link #full}). A list cut short,
 * still loading, filtered by a search or stopped by a failed page, is never taken, so it can't make
 * anyone look like they left. A list taken is compared with the last one taken for your account:
 * anyone missing from the new one is kept as a recent unfollower, with when it was first seen
 * missing, and anyone who follows you again drops off. Accounts are told apart by their ID, so a new
 * username isn't an unfollow.
 *
 * <p>Each account keeps its own file in Instagram's own storage on the phone, named by a hash of its
 * ID ({@link #file}), written off the main thread. Clear unfollower list deletes them all.
 */
public final class Unfollowers {
    static final String SEEN = "unfollowers seen";
    static final String SAVE = "unfollowers save";
    static final String READ = "unfollowers read";

    /** Instagram's name for a Followers list. */
    static final String FOLLOWERS_KIND = "FOLLOWERS";

    static final String DIRECTORY = "hushgram-followers";
    /** The name of the file naming the last account whose list was taken, by its hash. */
    static final String LAST = "last";

    /** How many unfollowers are kept, newest first. */
    static final int MAX_KEPT = 200;

    private static final Object LOCK = new Object();

    /** The last list taken this run, by account, so a rebuild of the same rows isn't taken again. */
    private static final Map<String, String> taken = new HashMap<>();
    private static final Map<String, Long> generations = new HashMap<>();
    private static long nextGeneration;

    private Unfollowers() {
    }

    /** Someone who left your Followers list: their ID, their username then, and when it was seen. */
    public static final class Left {
        public final String id;
        public final String username;
        public final long when;

        Left(String id, String username, long when) {
            this.id = id;
            this.username = username;
            this.when = when;
        }
    }

    /** An account's last full Followers list, by ID to username, when it was taken, and who has left. */
    public static final class Record {
        public static final Record NONE = new Record(Collections.emptyMap(), 0, Collections.emptyList());

        public final Map<String, String> followers;
        /** When the list was taken, or 0 when no full list has been seen yet. */
        public final long checked;
        /** Newest first. */
        public final List<Left> left;

        Record(Map<String, String> followers, long checked, List<Left> left) {
            this.followers = Collections.unmodifiableMap(followers);
            this.checked = checked;
            this.left = Collections.unmodifiableList(left);
        }
    }

    /** Where {@link #seen} gets what it needs from Instagram's objects. Tests hand in their own. */
    interface Parts {
        /** The row binder of a follow list's state holder [list], which knows the kind of list and whose it is. */
        @Nullable
        Object binder(Object list);

        @Nullable
        Object kind(Object binder);

        @Nullable
        String owner(Object binder);

        @Nullable
        String viewer(Object binder);

        /** Whether [list] has more accounts to load, or null when it can't be told. */
        @Nullable
        Boolean hasMore(Object list);

        /** The account whose list [list] is. */
        @Nullable
        Object ownerAccount(Object list);

        @Nullable
        Integer followerCount(Object user);

        @Nullable
        String id(Object user);

        @Nullable
        String username(Object user);
    }

    static final Parts PATCHED = new Parts() {
        @Override
        public Object binder(Object list) {
            return FollowingList.orderBinder(list);
        }

        @Override
        public Object kind(Object binder) {
            return FollowingList.listKind(binder);
        }

        @Override
        public String owner(Object binder) {
            return FollowingList.listOwnerId(binder);
        }

        @Override
        public String viewer(Object binder) {
            return FollowingList.viewerId(binder);
        }

        @Override
        public Boolean hasMore(Object list) {
            return listHasMore(list);
        }

        @Override
        public Object ownerAccount(Object list) {
            return listOwner(list);
        }

        @Override
        public Integer followerCount(Object user) {
            return Unfollowers.followerCount(user);
        }

        @Override
        public String id(Object user) {
            return FriendshipStatus.userId(user);
        }

        @Override
        public String username(Object user) {
            return Unfollowers.username(user);
        }
    };

    /** What a list taken is handed to: written off the main thread on the phone, at once in tests. */
    interface Keeper {
        void keep(String viewer, Map<String, String> followers, long when, long generation);
    }

    static final Keeper STORED = (viewer, followers, when, generation) -> {
        Context context = Utils.getContext();
        if (context == null) {
            forgetTaken(viewer, generation);
            return;
        }
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!Utils.runOnBackgroundThread(() -> take(application, viewer, followers, when, generation))) {
            forgetTaken(viewer, generation);
        }
    };

    /**
     * Called from {@link FollowingList#ordered} each time a follow list builds its rows, with the
     * accounts it has loaded and its state holder. Takes the list when it's your own Followers list in
     * full and the switch is on. Never throws, and never changes Instagram's list.
     */
    public static void seen(Object users, Object list) {
        seen(users, list, PATCHED, Unfollowers::switchedOn, STORED, System.currentTimeMillis());
    }

    /** Whether the list was taken. */
    static boolean seen(Object users, Object list, Parts parts, BooleanSupplier on, Keeper keeper, long now) {
        try {
            if (!(users instanceof List) || list == null || !on.getAsBoolean()) return false;
            Object binder = parts.binder(list);
            if (binder == null) return false;
            Object kind = parts.kind(binder);
            if (!(kind instanceof Enum) || !FOLLOWERS_KIND.equals(((Enum<?>) kind).name())) return false;
            String viewer = parts.viewer(binder);
            if (viewer == null || viewer.isEmpty() || !viewer.equals(parts.owner(binder))) return false;
            // Only a list with nothing more to load, and never on a guess.
            if (!Boolean.FALSE.equals(parts.hasMore(list))) return false;
            List<?> loaded = (List<?>) users;
            Object account = parts.ownerAccount(list);
            Integer count = account == null ? null : parts.followerCount(account);
            if (count == null || !full(loaded.size(), count)) {
                Logger.printDebug(() -> "Recent unfollowers: " + loaded.size() + " of " + count + " followers loaded, not taken");
                return false;
            }
            Map<String, String> followers = new LinkedHashMap<>();
            for (Object user : loaded) {
                String id = parts.id(user);
                // A row that can't be told apart makes the whole list untrustworthy.
                if (id == null || id.isEmpty()) return false;
                String name = parts.username(user);
                if (followers.put(id, name == null ? "" : name) != null) return false;
            }
            if (!viewer.equals(parts.viewer(binder)) || !viewer.equals(parts.owner(binder))
                    || !Boolean.FALSE.equals(parts.hasMore(list))
                    || !count.equals(parts.followerCount(account))) return false;
            long generation = firstTake(viewer, followers);
            if (generation == 0) return false;
            try {
                keeper.keep(viewer, Collections.unmodifiableMap(followers), now, generation);
            } catch (Throwable failure) {
                forgetTaken(viewer, generation);
                throw failure;
            }
            return current(viewer, generation);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SEEN, failure);
            return false;
        }
    }

    /**
     * Whether [loaded] accounts are your whole Followers list by your profile's [count] of them. It
     * may lag or count accounts Instagram doesn't list. In either case, wait for the counts to agree
     * rather than treating the discrepancy as departures. No followers is a whole list of none.
     */
    static boolean full(int loaded, int count) {
        return count >= 0 && loaded == count;
    }

    /** Whether [followers] isn't the list last taken for [viewer] in this run, and marks it taken. */
    private static long firstTake(String viewer, Map<String, String> followers) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String id : new TreeSet<>(followers.keySet())) {
            for (String value : new String[]{id, followers.get(id)}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (bytes.length >>> shift));
                digest.update(bytes);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        String signature = hex.toString();
        synchronized (taken) {
            if (signature.equals(taken.get(viewer))) return 0;
            taken.put(viewer, signature);
            long generation = ++nextGeneration;
            generations.put(viewer, generation);
            return generation;
        }
    }

    private static boolean current(String viewer, long generation) {
        synchronized (taken) {
            Long active = generations.get(viewer);
            return active != null && active == generation;
        }
    }

    private static void forgetTaken(String viewer, long generation) {
        synchronized (taken) {
            if (generation == 0 || current(viewer, generation)) {
                taken.remove(viewer);
                generations.remove(viewer);
            }
        }
    }

    /** Forgets which lists were taken in this run, so the next full one is taken again. */
    static void forgetTaken() {
        synchronized (taken) {
            taken.clear();
            generations.clear();
        }
    }

    /**
     * What [before] becomes with the list [followers] taken at [when]: anyone in the last list who
     * isn't in this one has left, newest first ahead of those who left before, and anyone who follows
     * you again is no longer listed. The first list taken has nothing to be compared with.
     */
    static Record compare(Record before, Map<String, String> followers, long when) {
        List<Left> left = new ArrayList<>();
        if (before.checked > 0) {
            for (Map.Entry<String, String> was : before.followers.entrySet()) {
                if (!followers.containsKey(was.getKey())) left.add(new Left(was.getKey(), was.getValue(), when));
            }
        }
        for (Left earlier : before.left) {
            if (left.size() >= MAX_KEPT) break;
            if (followers.containsKey(earlier.id)) continue;
            boolean listed = false;
            for (Left each : left) listed |= each.id.equals(earlier.id);
            if (!listed) left.add(earlier);
        }
        if (left.size() > MAX_KEPT) left = new ArrayList<>(left.subList(0, MAX_KEPT));
        return new Record(new LinkedHashMap<>(followers), when, left);
    }

    /** Compares the list taken with the one kept for [viewer], and keeps the result. Never throws. */
    public static void take(Context application, String viewer, Map<String, String> followers, long when) {
        take(application, viewer, followers, when, 0);
    }

    private static void take(Context application, String viewer, Map<String, String> followers, long when, long generation) {
        synchronized (LOCK) {
            if (generation != 0) {
                if (!current(viewer, generation)) return;
                if (!switchedOn()) {
                    forgetTaken(viewer, generation);
                    return;
                }
            }
            try {
                Record before = read(file(application, hash(viewer)));
                Record after = compare(before, followers, when);
                write(file(application, hash(viewer)), after);
                writeLast(application, hash(viewer));
                int listed = after.left.size();
                Logger.printDebug(() -> "Recent unfollowers: took " + followers.size() + " followers, " + listed + " listed as gone");
            } catch (Throwable failure) {
                forgetTaken(viewer, generation);
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SAVE, failure);
            }
        }
    }

    /**
     * The record of the account to show in settings: the one signed in when HushGram has seen which
     * that is, else the last account whose list was taken. {@link Record#NONE} when there's none.
     * Never throws.
     */
    public static Record shown(Context context) {
        if (context == null) return Record.NONE;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                String signedIn = HiddenAccounts.signedInAccount();
                String hash = signedIn != null && !signedIn.isEmpty() ? hash(signedIn) : readLast(application);
                if (hash == null || hash.isEmpty()) return Record.NONE;
                return read(file(application, hash));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, READ, failure);
                return Record.NONE;
            }
        }
    }

    /** Deletes every account's kept list and unfollowers. Answers whether they're gone. Never throws. */
    public static boolean clear(Context context) {
        if (context == null) return false;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                forgetTaken();
                File folder = new File(application.getFilesDir(), DIRECTORY);
                File[] files = folder.listFiles();
                boolean cleared = true;
                if (files != null) {
                    for (File each : files) cleared &= each.delete() || !each.exists();
                }
                return cleared && (!folder.exists() || folder.delete() || !folder.exists());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "unfollowers clear", failure);
                return false;
            }
        }
    }

    /** 16 hex digits of a SHA-256 of [viewer]'s ID, which names its file, never the ID itself. */
    static String hash(String viewer) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("hushgram followers " + viewer.trim()).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16)).append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return hex.toString();
    }

    static File file(Context application, String hash) {
        return new File(new File(application.getFilesDir(), DIRECTORY), hash + ".tsv");
    }

    /**
     * Reads a kept record: a {@code checked} line with when the list was taken, an {@code f} line
     * per follower with its ID and username, and a {@code u} line per unfollower with when. A file
     * that isn't there is {@link Record#NONE}.
     */
    static Record read(File file) throws IOException {
        if (!file.isFile()) return Record.NONE;
        Map<String, String> followers = new LinkedHashMap<>();
        List<Left> left = new ArrayList<>();
        long checked = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                try {
                    if (fields.length == 2 && fields[0].equals("checked")) {
                        checked = Long.parseLong(fields[1]);
                    } else if (fields.length == 3 && fields[0].equals("f") && !fields[1].isEmpty()) {
                        followers.put(fields[1], fields[2]);
                    } else if (fields.length == 4 && fields[0].equals("u") && !fields[1].isEmpty() && left.size() < MAX_KEPT) {
                        left.add(new Left(fields[1], fields[2], Long.parseLong(fields[3])));
                    }
                } catch (NumberFormatException skipped) {
                    // A line written by hand or cut short is left out; the rest still counts.
                }
            }
        }
        return new Record(followers, checked, left);
    }

    /** Writes [record] beside [file], then puts it in its place, so a cut-off write leaves the old one. */
    static void write(File file, Record record) throws IOException {
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("the followers folder could not be made");
        }
        File work = new File(folder, file.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(work);
             Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            out.write("checked\t" + record.checked + "\n");
            for (Map.Entry<String, String> each : record.followers.entrySet()) {
                out.write("f\t" + clean(each.getKey()) + "\t" + clean(each.getValue()) + "\n");
            }
            for (Left each : record.left) {
                out.write("u\t" + clean(each.id) + "\t" + clean(each.username) + "\t" + each.when + "\n");
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
    }

    private static void writeLast(Context application, String hash) throws IOException {
        File last = new File(new File(application.getFilesDir(), DIRECTORY), LAST);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(last), StandardCharsets.UTF_8)) {
            out.write(hash);
        }
    }

    @Nullable
    private static String readLast(Context application) throws IOException {
        File last = new File(new File(application.getFilesDir(), DIRECTORY), LAST);
        if (!last.isFile()) return null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(last), StandardCharsets.UTF_8))) {
            String hash = in.readLine();
            return hash == null ? null : hash.trim();
        }
    }

    /** [value] with no tab or line break, which would split its line. */
    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Whether the switch is on: off while paused and before the settings are read. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.TRACK_UNFOLLOWERS.get();
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: whether a follow list's state holder [list] has more accounts to load, or null. */
    @Nullable
    public static Boolean listHasMore(Object list) {
        return null;
    }

    /** Filled in by the patch: the account whose list the state holder [list] is, or null. Answers an Object. */
    @Nullable
    public static Object listOwner(Object list) {
        return null;
    }

    /** Filled in by the patch: [user]'s follower count, or null. */
    @Nullable
    public static Integer followerCount(Object user) {
        return null;
    }

    /** Filled in by the patch: [user]'s username, or null. */
    @Nullable
    public static String username(Object user) {
        return null;
    }
}
