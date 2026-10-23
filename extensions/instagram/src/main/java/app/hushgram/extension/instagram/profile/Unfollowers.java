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
 * about as many accounts as your profile's follower count says ({@link #full}). A list cut short,
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
    private static final Map<String, Long> taken = new HashMap<>();

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

        /** Whether you follow [user], as the row says, or null when it doesn't. */
        @Nullable
        default Boolean following(Object user) {
            return null;
        }
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

        @Override
        public Boolean following(Object user) {
            return FriendshipStatus.friendshipFollowing(user);
        }
    };

    /**
     * What a list taken is handed to, with whether you follow each account and the profile's
     * follower count: written off the main thread on the phone, at once in tests.
     */
    interface Keeper {
        void keep(String viewer, Map<String, String> followers, Map<String, Boolean> following, int count, long when);
    }

    /**
     * How long a whole list has to stay as it is before it's kept. At the end of a long list
     * Instagram builds the rows more than once: a list of 1,150 that a moment later held all 1,157
     * was kept first and named 7 people as gone (S23 Ultra, 2026-10-23).
     */
    static final long SETTLE_MS = 2500;

    /** The newest whole list waiting to settle, by account. Main thread only. */
    private static final Map<String, Runnable> settling = new HashMap<>();

    private static final Keeper STORED = (viewer, followers, following, count, when) -> {
        Context context = Utils.getContext();
        if (context == null) return;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Runnable keep = new Runnable() {
            @Override
            public void run() {
                synchronized (settling) {
                    // A newer list came in meanwhile, and it waits in turn.
                    if (settling.get(viewer) != this) return;
                    settling.remove(viewer);
                }
                if (!Utils.runOnBackgroundThread(() -> take(application, viewer, followers, following, count, when))) {
                    forgetTaken(viewer);
                }
            }
        };
        synchronized (settling) {
            settling.put(viewer, keep);
        }
        Utils.runOnMainThreadDelayed(keep, SETTLE_MS);
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
            Boolean more = parts.hasMore(list);
            List<?> loaded = (List<?>) users;
            Object account = parts.ownerAccount(list);
            Integer count = account == null ? null : parts.followerCount(account);
            // Check now scrolls this list while it's your own Followers list and has more to load.
            FollowersCheck.ownFollowers(more, loaded.size(), count);
            // Only a list with nothing more to load, and never on a guess.
            if (!Boolean.FALSE.equals(more)) return false;
            if (count == null || !full(loaded.size(), count)) {
                Logger.printDebug(() -> "Recent unfollowers: " + loaded.size() + " of " + count + " followers loaded, not taken");
                return false;
            }
            Map<String, String> followers = new LinkedHashMap<>();
            Map<String, Boolean> following = new HashMap<>();
            for (Object user : loaded) {
                String id = parts.id(user);
                // A row that can't be told apart makes the whole list untrustworthy.
                if (id == null || id.isEmpty()) return false;
                String name = parts.username(user);
                followers.put(id, name == null ? "" : name);
                Boolean follow = parts.following(user);
                if (follow != null) following.put(id, follow);
            }
            // A rebuild of the list taken already isn't taken again, unless Check now asked for it.
            if (!firstTake(viewer, followers) && !FollowersCheck.running()) return false;
            FollowersCheck.handedOver();
            keeper.keep(viewer, followers, following, count, now);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SEEN, failure);
            return false;
        }
    }

    /**
     * Whether [loaded] accounts are your whole Followers list by your profile's [count] of them. It
     * may lag behind a follow or an unfollow, and a few accounts can be counted that Instagram doesn't
     * list, so up to one in twenty may be missing. Under twenty followers none may: there a search or
     * a short list would list real followers as gone. No followers at all is a whole list of none.
     */
    static boolean full(int loaded, int count) {
        if (loaded < 0 || count < 0) return false;
        return loaded >= count - count / 20;
    }

    /** Whether [followers] isn't the list last taken for [viewer] in this run, and marks it taken. */
    private static boolean firstTake(String viewer, Map<String, String> followers) {
        long signature = followers.size();
        for (String id : followers.keySet()) signature = signature * 31 + id.hashCode();
        synchronized (taken) {
            Long last = taken.put(viewer, signature);
            return last == null || last != signature;
        }
    }

    private static void forgetTaken(String viewer) {
        synchronized (taken) {
            taken.remove(viewer);
        }
    }

    /** Forgets which lists were taken in this run, so the next full one is taken again. */
    static void forgetTaken() {
        synchronized (taken) {
            taken.clear();
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

    /** {@link #take} for a list whose rows said nothing about whether you follow them. */
    public static void take(Context application, String viewer, Map<String, String> followers, long when) {
        take(application, viewer, followers, Collections.emptyMap(), followers.size(), when);
    }

    /**
     * Compares the list taken with the one kept for [viewer], keeps the result and adds the check to
     * the follower history ({@link FollowerHistory}), whose changes then go to the screen or the
     * notification ({@link FollowersCheck#taken}). Never throws.
     */
    public static void take(Context application, String viewer, Map<String, String> followers,
                            Map<String, Boolean> following, int count, long when) {
        FollowerHistory.Change change = null;
        synchronized (LOCK) {
            try {
                Record before = read(file(application, hash(viewer)));
                Record after = compare(before, followers, when);
                change = FollowerHistory.record(application, viewer, before, followers, following, count, when);
                write(file(application, hash(viewer)), after);
                writeLast(application, hash(viewer));
                int listed = after.left.size();
                Logger.printDebug(() -> "Recent unfollowers: took " + followers.size() + " followers, " + listed + " listed as gone");
            } catch (Throwable failure) {
                forgetTaken(viewer);
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SAVE, failure);
            }
        }
        if (change != null) FollowersCheck.taken(application, change);
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

    /** The hash naming the last account whose list was taken, or null. */
    @Nullable
    static String lastHash(Context application) throws IOException {
        return readLast(application);
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
