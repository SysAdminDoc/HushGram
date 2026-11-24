/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;
import android.text.format.DateUtils;

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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Follow dates in your lists (#847): since when each account on your own Followers list has followed
 * you, and since when you've followed each account on your own Following list.
 *
 * <p>Instagram doesn't say when a follow started, so the dates are HushGram's own, going forward.
 * Each time your own Followers or Following list builds its rows with every account loaded, as many
 * as your profile's count says ({@link Unfollowers#full}), the list is taken a moment later, once it
 * has stopped changing ({@link #SETTLE_MS}). The first list taken for an account and kind only marks
 * who was there already, with no date. After that, anyone new to the list is dated the day the list
 * was taken, and anyone no longer in a list that's complete is forgotten, so a follow that comes back
 * gets a new date. Nothing is asked of Instagram.
 *
 * <p>A dated row says Since and the day before the name, like Doesn't follow you does
 * ({@link FollowingList#row}). Each account keeps a file per list in the same folder as Recent
 * unfollowers, named by a hash of its ID, so Clear unfollower list clears these too.
 */
public final class FollowDates {
    static final String SEEN = "follow dates seen";
    static final String SAVE = "follow dates save";
    static final String READ = "follow dates read";

    /** How long a whole list must stand unchanged before it's taken. A list that keeps changing is still loading. */
    static final long SETTLE_MS = Unfollowers.SETTLE_MS;

    /** One account's dates for one list: when the first list was taken, and since when for each account in it. */
    static final class Dates {
        static final Dates NONE = new Dates(0, Collections.emptyMap());

        /** When the first list was taken, or 0 before then. */
        final long started;
        /** By account ID, since when, or 0 for an account that was there when the first list was taken. */
        final Map<String, Long> since;

        Dates(long started, Map<String, Long> since) {
            this.started = started;
            this.since = Collections.unmodifiableMap(since);
        }
    }

    /** Where {@link #seen} gets what it needs from Instagram's objects. Tests hand in their own. */
    interface Parts {
        @Nullable
        Object binder(Object list);

        @Nullable
        Object kind(Object binder);

        @Nullable
        String owner(Object binder);

        @Nullable
        String viewer(Object binder);

        @Nullable
        Boolean hasMore(Object list);

        @Nullable
        Object ownerAccount(Object list);

        @Nullable
        Integer followerCount(Object user);

        @Nullable
        Integer followingCount(Object user);

        @Nullable
        String id(Object user);
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
            return Unfollowers.listHasMore(list);
        }

        @Override
        public Object ownerAccount(Object list) {
            return Unfollowers.listOwner(list);
        }

        @Override
        public Integer followerCount(Object user) {
            return Unfollowers.followerCount(user);
        }

        @Override
        public Integer followingCount(Object user) {
            return Unfollowers.followingCount(user);
        }

        @Override
        public String id(Object user) {
            return FriendshipStatus.userId(user);
        }
    };

    /** What a list taken is handed to: kept off the main thread once it settles on the phone, at once in tests. */
    interface Keeper {
        void keep(String viewer, String kind, List<String> ids, boolean complete, long when, long generation);
    }

    /** The dates read this run, by account hash and list. */
    private static final Map<String, Dates> cache = new HashMap<>();

    /** The last list taken this run and its generation, by account and list. */
    private static final Map<String, List<String>> taken = new HashMap<>();
    private static final Map<String, Long> generations = new HashMap<>();
    private static long nextGeneration;

    private static final Object LOCK = new Object();

    static final Keeper STORED = (viewer, kind, ids, complete, when, generation) -> {
        Context context = Utils.getContext();
        if (context == null) return;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Unfollowers.later.after(() -> {
            if (!current(viewer, kind, generation)) return;
            Utils.runOnBackgroundThread(() -> {
                if (current(viewer, kind, generation)) take(application, viewer, kind, ids, complete, when);
            });
        }, SETTLE_MS);
    };

    private FollowDates() {
    }

    /**
     * Called from {@link FollowingList#ordered} each time a follow list builds its rows. Takes the
     * list when it's your own Followers or Following list in full and the switch is on. Never throws,
     * and never changes Instagram's list.
     */
    public static void seen(Object users, Object list) {
        seen(users, list, PATCHED, FollowDates::switchedOn, STORED, System.currentTimeMillis());
    }

    /** Whether the list was taken. */
    static boolean seen(Object users, Object list, Parts parts, BooleanSupplier on, Keeper keeper, long now) {
        try {
            if (!(users instanceof List) || list == null || !on.getAsBoolean()) return false;
            Object binder = parts.binder(list);
            if (binder == null) return false;
            String kind = kindOf(parts.kind(binder));
            if (kind == null) return false;
            String viewer = parts.viewer(binder);
            if (viewer == null || viewer.isEmpty() || !viewer.equals(parts.owner(binder))) return false;
            if (!Boolean.FALSE.equals(parts.hasMore(list))) return false;
            List<?> loaded = (List<?>) users;
            Object account = parts.ownerAccount(list);
            Integer count = account == null ? null
                    : Unfollowers.FOLLOWERS_KIND.equals(kind) ? parts.followerCount(account) : parts.followingCount(account);
            if (count == null || !Unfollowers.full(loaded.size(), count)) return false;
            List<String> ids = new ArrayList<>(loaded.size());
            Set<String> distinct = new HashSet<>();
            for (Object user : loaded) {
                String id = user == null ? null : parts.id(user);
                // A row that can't be told apart makes the whole list untrustworthy.
                if (id == null || id.isEmpty() || !distinct.add(id)) return false;
                ids.add(id);
            }
            long generation;
            String key = viewer + "/" + kind;
            synchronized (taken) {
                if (ids.equals(taken.get(key))) return false;
                taken.put(key, ids);
                generation = ++nextGeneration;
                generations.put(key, generation);
            }
            keeper.keep(viewer, kind, Collections.unmodifiableList(ids), ids.size() == count, now, generation);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SEEN, failure);
            return false;
        }
    }

    /** The list's name as its file has it, FOLLOWERS or FOLLOWING, or null for any other list. */
    @Nullable
    static String kindOf(@Nullable Object kind) {
        if (!(kind instanceof Enum)) return null;
        String name = ((Enum<?>) kind).name();
        if (Unfollowers.FOLLOWERS_KIND.equals(name)) return Unfollowers.FOLLOWERS_KIND;
        return FollowingList.FOLLOWING_KINDS.contains(name) ? "FOLLOWING" : null;
    }

    private static boolean current(String viewer, String kind, long generation) {
        synchronized (taken) {
            Long active = generations.get(viewer + "/" + kind);
            return active != null && active == generation;
        }
    }

    /**
     * What [before] becomes with the list [ids] taken at [when]: the first list marks who was there,
     * undated, and after that anyone new is dated [when]. A [complete] list, as many as the count says,
     * also forgets anyone no longer in it; one a few short keeps them, since they may only be unlisted.
     */
    static Dates update(Dates before, List<String> ids, boolean complete, long when) {
        Map<String, Long> since = new LinkedHashMap<>();
        boolean first = before.started <= 0;
        for (String id : ids) {
            Long was = before.since.get(id);
            since.put(id, was != null ? was : first ? 0L : when);
        }
        if (!complete) {
            for (Map.Entry<String, Long> was : before.since.entrySet()) since.putIfAbsent(was.getKey(), was.getValue());
        }
        return new Dates(first ? when : before.started, since);
    }

    private static void take(Context application, String viewer, String kind, List<String> ids, boolean complete, long when) {
        synchronized (LOCK) {
            try {
                if (!switchedOn()) return;
                String hash = Unfollowers.hash(viewer);
                File file = file(application, hash, kind);
                Dates after = update(read(file), ids, complete, when);
                write(file, after);
                synchronized (cache) {
                    cache.put(hash + "/" + kind, after);
                }
                int counted = 0;
                for (long each : after.since.values()) if (each > 0) counted++;
                int dated = counted;
                Logger.printDebug(() -> "Follow dates: took " + ids.size() + " in " + kind + ", " + dated + " dated");
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SAVE, failure);
            }
        }
    }

    /**
     * Since when, in words, for a row of a follow list: Since and the day, when the list is your own
     * Followers or Following list, the switch is on and the account's follow is dated. Null otherwise.
     * Never throws.
     */
    @Nullable
    public static String label(@Nullable Object kind, @Nullable String owner, @Nullable String viewer, @Nullable Object user) {
        return label(kind, owner, viewer, user, PATCHED, FollowDates::switchedOn, System.currentTimeMillis());
    }

    @Nullable
    static String label(@Nullable Object kind, @Nullable String owner, @Nullable String viewer, @Nullable Object user,
                        Parts parts, BooleanSupplier on, long now) {
        try {
            if (user == null || !on.getAsBoolean()) return null;
            String list = kindOf(kind);
            if (list == null || viewer == null || viewer.isEmpty() || !viewer.equals(owner)) return null;
            Context context = Utils.getContext();
            if (context == null) return null;
            String id = parts.id(user);
            if (id == null) return null;
            Long since = dates(context, viewer, list).since.get(id);
            return since == null || since <= 0 ? null : L10n.f("Since %s", day(context, since, now));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, READ, failure);
            return null;
        }
    }

    /** [when] as a short day, with the year only when it isn't this year's. */
    static String day(Context context, long when, long now) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(when);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH;
        flags |= then.get(Calendar.YEAR) == today.get(Calendar.YEAR) ? DateUtils.FORMAT_NO_YEAR : DateUtils.FORMAT_SHOW_YEAR;
        return DateUtils.formatDateTime(context, when, flags);
    }

    /** [viewer]'s dates for [kind], read once a run and kept up to date as lists are taken. */
    static Dates dates(Context context, String viewer, String kind) throws Exception {
        String hash = Unfollowers.hash(viewer);
        String key = hash + "/" + kind;
        synchronized (cache) {
            Dates known = cache.get(key);
            if (known != null) return known;
        }
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Dates read;
        synchronized (LOCK) {
            read = read(file(application, hash, kind));
        }
        synchronized (cache) {
            Dates known = cache.get(key);
            if (known != null) return known;
            cache.put(key, read);
            return read;
        }
    }

    /** Forgets what this run read and took, as a restart does, for tests and after a clear. */
    static void forget() {
        synchronized (cache) {
            cache.clear();
        }
        synchronized (taken) {
            taken.clear();
            generations.clear();
        }
    }

    static File file(Context application, String hash, String kind) {
        String name = Unfollowers.FOLLOWERS_KIND.equals(kind) ? ".followers-since.tsv" : ".following-since.tsv";
        return new File(new File(application.getFilesDir(), Unfollowers.DIRECTORY), hash + name);
    }

    /** Reads [file]: a {@code started} line, then an {@code s} line per account with its ID and since when. */
    static Dates read(File file) throws IOException {
        if (!file.isFile()) return Dates.NONE;
        long started = 0;
        Map<String, Long> since = new LinkedHashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                try {
                    if (fields.length == 2 && fields[0].equals("started")) started = Long.parseLong(fields[1]);
                    else if (fields.length == 3 && fields[0].equals("s") && !fields[1].isEmpty()) since.put(fields[1], Long.parseLong(fields[2]));
                } catch (NumberFormatException skipped) {
                    // A line cut short is left out; the rest still counts.
                }
            }
        }
        return new Dates(started, since);
    }

    /** Writes [dates] beside [file], then puts it in its place, so a cut-off write leaves the old one. */
    static void write(File file, Dates dates) throws IOException {
        File folder = file.getParentFile();
        if (folder != null && !folder.mkdirs() && !folder.isDirectory()) throw new IOException("the followers folder could not be made");
        File work = new File(folder, file.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(work);
             Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            out.write("started\t" + dates.started + "\n");
            for (Map.Entry<String, Long> each : dates.since.entrySet()) {
                out.write("s\t" + each.getKey().replace('\t', ' ').replace('\n', ' ') + "\t" + each.getValue() + "\n");
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

    /** Whether the switch is on: off while paused and before the settings are read. */
    static boolean switchedOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.FOLLOW_DATES.get();
    }
}
