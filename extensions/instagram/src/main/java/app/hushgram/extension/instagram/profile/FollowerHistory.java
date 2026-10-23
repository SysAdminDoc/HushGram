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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Follower history: a dated log, kept on the phone, of who unfollowed you, who followed you again
 * and who started following you. It's built from the same full Followers lists Recent unfollowers
 * takes ({@link Unfollowers#take}), so it asks Instagram for nothing either.
 *
 * <p>Each list taken is compared with the one taken before it. Anyone missing is logged as gone,
 * with the two checks they left between, since a list only says who's there when you look. Anyone
 * new is logged as a new follower, or as back when they'd left before. The first list taken only
 * sets the starting point.
 *
 * <p>A list counts as whole with up to one in twenty of the profile's count missing
 * ({@link Unfollowers#full}), and Instagram sometimes leaves out a row it still counts. So a list
 * that came up short remembers it did, and anyone it logged as gone who's in the next list again is
 * taken off the log rather than logged as back: they most likely never left.
 *
 * <p>Whether you follow each follower is kept from the list too, as Instagram's rows say it, so the
 * log can say who you still follow among those who left. It's what the rows said at that check.
 *
 * <p>Each account keeps its own file beside its Followers list, named by the same hash of its ID.
 */
public final class FollowerHistory {
    static final String READ = "follower history read";
    static final String SAVE = "follower history save";

    /** How many changes are kept, newest first. */
    static final int MAX_EVENTS = 2000;

    private static final Object LOCK = new Object();

    private FollowerHistory() {
    }

    /** What changed for one account at one check. */
    public enum Kind {
        /** They were in the last list and aren't in this one. */
        LEFT,
        /** They're in this list after leaving before. */
        BACK,
        /** They're in this list for the first time since the history began. */
        NEW
    }

    /** One change: who, what, the check that found it and the check before it. */
    public static final class Event {
        public final Kind kind;
        public final String id;
        public final String username;
        /** When the check that found it was made. */
        public final long when;
        /** When the check before it was made, or 0 when that isn't known. */
        public final long since;
        /** Whether you followed them at the check that found it, or null when the row didn't say. */
        @Nullable
        public final Boolean youFollow;

        Event(Kind kind, String id, String username, long when, long since, @Nullable Boolean youFollow) {
            this.kind = kind;
            this.id = id;
            this.username = username == null ? "" : username;
            this.when = when;
            this.since = since;
            this.youFollow = youFollow;
        }
    }

    /** An account's history: its changes, newest first, and what its checks found. */
    public static final class Log {
        public static final Log NONE = new Log(Collections.emptyList(), 0, 0, 0, false, 0, Collections.emptySet(), false);

        public final List<Event> events;
        /** When the last whole list was taken, or 0 before the first. */
        public final long checked;
        /** When the list before it was taken, or 0. */
        public final long previous;
        /** How many followers the last list held. */
        public final int followers;
        /** Whether the last list held fewer accounts than the profile's count said. */
        public final boolean partial;
        /** When the history was last opened, so the changes after it are new to you. */
        public final long seen;
        /** The followers you followed at the last check, by ID. */
        public final Set<String> youFollow;
        /** Whether the last list's rows said who you follow, so someone missing from [youFollow] isn't followed. */
        public final boolean followKnown;

        Log(List<Event> events, long checked, long previous, int followers, boolean partial, long seen,
            Set<String> youFollow, boolean followKnown) {
            this.events = Collections.unmodifiableList(events);
            this.checked = checked;
            this.previous = previous;
            this.followers = followers;
            this.partial = partial;
            this.seen = seen;
            this.youFollow = Collections.unmodifiableSet(youFollow);
            this.followKnown = followKnown;
        }

        /** How many changes of [kind] there are, or every change for null. */
        public int count(@Nullable Kind kind) {
            if (kind == null) return events.size();
            int count = 0;
            for (Event event : events) if (event.kind == kind) count++;
            return count;
        }

        /** How many people left after the history was last opened. */
        public int unseenLeft() {
            int count = 0;
            for (Event event : events) {
                if (event.when <= seen) break;
                if (event.kind == Kind.LEFT) count++;
            }
            return count;
        }

        Log seenAt(long when) {
            return new Log(events, checked, previous, followers, partial, Math.max(seen, when), youFollow, followKnown);
        }
    }

    /** The changes one check added, for the screen and the notification. */
    public static final class Change {
        public static final Change NONE = new Change(Collections.emptyList(), false, 0);

        /** Newest first, as they went on the log. */
        public final List<Event> added;
        /** Whether this was the first list, which only sets the starting point. */
        public final boolean first;
        /** How many earlier unfollows this list showed were rows Instagram had left out. */
        public final int undone;

        Change(List<Event> added, boolean first, int undone) {
            this.added = Collections.unmodifiableList(added);
            this.first = first;
            this.undone = undone;
        }

        public int count(Kind kind) {
            int count = 0;
            for (Event event : added) if (event.kind == kind) count++;
            return count;
        }
    }

    /** What [log] and the check that comes with it become: the log after, and what it added. */
    static final class Update {
        final Log log;
        final Change change;

        Update(Log log, Change change) {
            this.log = log;
            this.change = change;
        }
    }

    /**
     * What [log] becomes with the list [after] taken at [when], whose profile counted [count]
     * followers. [before] is the list the last check took, at [beforeChecked], or 0 for none.
     * [following] says, for the accounts in [after], whether you follow them.
     */
    static Update update(Log log, Map<String, String> before, long beforeChecked, Map<String, String> after,
                         Map<String, Boolean> following, int count, long when) {
        Set<String> youFollow = new HashSet<>();
        for (Map.Entry<String, Boolean> each : following.entrySet()) {
            if (Boolean.TRUE.equals(each.getValue()) && after.containsKey(each.getKey())) youFollow.add(each.getKey());
        }
        boolean partial = after.size() < count;
        boolean followKnown = !following.isEmpty();
        if (beforeChecked <= 0) {
            Log first = new Log(new ArrayList<>(log.events), when, 0, after.size(), partial, log.seen, youFollow, followKnown);
            return new Update(first, new Change(Collections.emptyList(), true, 0));
        }

        // Unfollows the last list logged that this one shows were rows that list left out.
        Set<String> undone = new HashSet<>();
        if (log.partial && log.checked == beforeChecked) {
            for (Event event : log.events) {
                if (event.when != beforeChecked) break;
                if (event.kind == Kind.LEFT && after.containsKey(event.id)) undone.add(event.id);
            }
        }
        Set<String> leftBefore = new HashSet<>();
        for (Event event : log.events) {
            if (event.kind == Kind.LEFT && !(undone.contains(event.id) && event.when == beforeChecked)) leftBefore.add(event.id);
        }

        List<Event> added = new ArrayList<>();
        for (Map.Entry<String, String> was : before.entrySet()) {
            if (after.containsKey(was.getKey())) continue;
            added.add(new Event(Kind.LEFT, was.getKey(), was.getValue(), when, beforeChecked,
                    log.youFollow.contains(was.getKey()) ? Boolean.TRUE : known(log, was.getKey())));
        }
        for (Map.Entry<String, String> is : after.entrySet()) {
            String id = is.getKey();
            if (before.containsKey(id) || undone.contains(id)) continue;
            Kind kind = leftBefore.contains(id) ? Kind.BACK : Kind.NEW;
            added.add(new Event(kind, id, is.getValue(), when, beforeChecked, following.get(id)));
        }

        List<Event> events = new ArrayList<>(added.subList(0, Math.min(added.size(), MAX_EVENTS)));
        for (Event event : log.events) {
            if (events.size() >= MAX_EVENTS) break;
            if (event.kind == Kind.LEFT && event.when == beforeChecked && undone.contains(event.id)) continue;
            events.add(event);
        }
        Log next = new Log(events, when, beforeChecked, after.size(), partial, log.seen, youFollow, followKnown);
        return new Update(next, new Change(added, false, undone.size()));
    }

    /**
     * Whether you followed [id] by the last check, when it was a follower then and isn't in [log]'s
     * set: false when that check's rows said who you follow, else null, since they didn't say.
     */
    @Nullable
    private static Boolean known(Log log, String id) {
        return log.checked > 0 && log.followKnown ? Boolean.FALSE : null;
    }

    /**
     * Adds the check that took [after] at [when] to [viewer]'s history, beside [before], the list it
     * replaces. Answers what it added. Never throws.
     */
    static Change record(Context application, String viewer, Unfollowers.Record before, Map<String, String> after,
                         Map<String, Boolean> following, int count, long when) {
        synchronized (LOCK) {
            try {
                File file = file(application, Unfollowers.hash(viewer));
                Log log = read(file);
                if (log.events.isEmpty() && log.checked == 0 && !before.left.isEmpty()) log = carriedOver(before);
                Update update = update(log, before.followers, before.checked, after, following, count, when);
                write(file, update.log);
                return update.change;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SAVE, failure);
                return Change.NONE;
            }
        }
    }

    /** The unfollowers Recent unfollowers listed before this history began, as its first changes. */
    static Log carriedOver(Unfollowers.Record record) {
        List<Event> events = new ArrayList<>();
        for (Unfollowers.Left left : record.left) {
            events.add(new Event(Kind.LEFT, left.id, left.username, left.when, 0, null));
        }
        return new Log(events, 0, 0, 0, false, 0, Collections.emptySet(), false);
    }

    /**
     * The history of the account to show: the one signed in when HushGram has seen which that is,
     * else the last account whose list was taken. {@link Log#NONE} when there's none. Never throws.
     */
    public static Log shown(Context context) {
        if (context == null) return Log.NONE;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                String hash = shownHash(application);
                if (hash == null) return Log.NONE;
                Log log = read(file(application, hash));
                if (log.events.isEmpty() && log.checked == 0) {
                    // Nothing logged yet: what Recent unfollowers listed shows meanwhile.
                    Unfollowers.Record record = Unfollowers.read(Unfollowers.file(application, hash));
                    if (!record.left.isEmpty()) log = carriedOver(record);
                    if (record.checked > 0) {
                        log = new Log(new ArrayList<>(log.events), record.checked, 0, record.followers.size(),
                                false, log.seen, Collections.emptySet(), false);
                    }
                }
                return log;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, READ, failure);
                return Log.NONE;
            }
        }
    }

    /** Notes that the history was opened at [when], so its changes up to then aren't new. Never throws. */
    public static void markSeen(Context context, long when) {
        if (context == null) return;
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            try {
                String hash = shownHash(application);
                if (hash == null) return;
                File file = file(application, hash);
                Log log = read(file);
                if (log.seen >= when) return;
                write(file, log.seenAt(when));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, SAVE, failure);
            }
        }
    }

    @Nullable
    private static String shownHash(Context application) throws Exception {
        String signedIn = HiddenAccounts.signedInAccount();
        if (signedIn != null && !signedIn.isEmpty()) return Unfollowers.hash(signedIn);
        String last = Unfollowers.lastHash(application);
        return last == null || last.isEmpty() ? null : last;
    }

    static File file(Context application, String hash) {
        return new File(new File(application.getFilesDir(), Unfollowers.DIRECTORY), hash + ".history.tsv");
    }

    /**
     * Reads a kept history: a {@code checked} line with when the last list was taken, the one
     * before it, how many it held, whether it came up short and whether its rows said who you
     * follow; a {@code seen} line; a {@code y}
     * line per follower you followed then; and an {@code e} line per change. A file that isn't there
     * is {@link Log#NONE}.
     */
    static Log read(File file) throws IOException {
        if (!file.isFile()) return Log.NONE;
        List<Event> events = new ArrayList<>();
        Set<String> youFollow = new HashSet<>();
        long checked = 0;
        long previous = 0;
        int followers = 0;
        boolean partial = false;
        boolean followKnown = false;
        long seen = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                try {
                    if (fields.length == 6 && fields[0].equals("checked")) {
                        checked = Long.parseLong(fields[1]);
                        previous = Long.parseLong(fields[2]);
                        followers = Integer.parseInt(fields[3]);
                        partial = fields[4].equals("1");
                        followKnown = fields[5].equals("1");
                    } else if (fields.length == 2 && fields[0].equals("seen")) {
                        seen = Long.parseLong(fields[1]);
                    } else if (fields.length == 2 && fields[0].equals("y") && !fields[1].isEmpty()) {
                        youFollow.add(fields[1]);
                    } else if (fields.length == 7 && fields[0].equals("e") && !fields[2].isEmpty()
                            && events.size() < MAX_EVENTS) {
                        events.add(new Event(Kind.valueOf(fields[1]), fields[2], fields[3], Long.parseLong(fields[4]),
                                Long.parseLong(fields[5]), follow(fields[6])));
                    }
                } catch (IllegalArgumentException skipped) {
                    // A line written by hand, cut short or from a newer kind of change is left out.
                }
            }
        }
        return new Log(events, checked, previous, followers, partial, seen, youFollow, followKnown);
    }

    /** Writes [log] beside [file], then puts it in its place, so a cut-off write leaves the old one. */
    static void write(File file, Log log) throws IOException {
        File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("the followers folder could not be made");
        }
        File work = new File(folder, file.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(work);
             Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            out.write("checked\t" + log.checked + "\t" + log.previous + "\t" + log.followers + "\t"
                    + (log.partial ? "1" : "0") + "\t" + (log.followKnown ? "1" : "0") + "\n");
            out.write("seen\t" + log.seen + "\n");
            for (String id : log.youFollow) out.write("y\t" + clean(id) + "\n");
            for (Event each : log.events) {
                out.write("e\t" + each.kind.name() + "\t" + clean(each.id) + "\t" + clean(each.username) + "\t"
                        + each.when + "\t" + each.since + "\t" + follow(each.youFollow) + "\n");
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

    private static String follow(@Nullable Boolean follow) {
        return follow == null ? "-" : follow ? "1" : "0";
    }

    @Nullable
    private static Boolean follow(String field) {
        if (field.equals("1")) return Boolean.TRUE;
        if (field.equals("0")) return Boolean.FALSE;
        return null;
    }

    /** [value] with no tab or line break, which would split its line. */
    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** The changes in [log] as CSV rows, with a header row, newest first, for Export. */
    public static String csv(Log log) {
        StringBuilder out = new StringBuilder("change,username,id,found,previous check,you followed them\n");
        Map<Kind, String> words = new HashMap<>();
        words.put(Kind.LEFT, "unfollowed");
        words.put(Kind.BACK, "followed again");
        words.put(Kind.NEW, "new follower");
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT);
        for (Event event : log.events) {
            out.append(words.get(event.kind)).append(',')
                    .append(csvField(event.username)).append(',')
                    .append(csvField(event.id)).append(',')
                    .append(iso.format(new java.util.Date(event.when))).append(',')
                    .append(event.since > 0 ? iso.format(new java.util.Date(event.since)) : "").append(',')
                    .append(event.youFollow == null ? "" : event.youFollow ? "yes" : "no").append('\n');
        }
        return out.toString();
    }

    private static String csvField(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
