/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoC/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Track who unfollows you" switch of the "Show if a profile follows you" patch.
 *
 * <p>Instagram never says who stops following you, so this works it out: each time you scroll your own
 * Followers list to the end, the accounts on it are saved on this phone, and the next time, anyone
 * saved who isn't on the list any more is kept in a list of people who unfollowed you. Nothing leaves
 * the phone and nothing is asked of Instagram that scrolling the list doesn't ask.
 *
 * <p>It rides on the two hooks of {@link FollowingList}: the one that hands over every account the
 * list has loaded so far ({@link #loaded}), and the one that hands over each row as it's drawn
 * ({@link #rowBound}), which gives an account its username and tells when the list's last account is on screen. A
 * list isn't taken as read until its last account has been drawn and {@link #SETTLE_MS} have passed with
 * nothing more arriving, since a page that is still loading looks like an end. A list far shorter than the saved one is taken
 * as one that wasn't scrolled through ({@link #incomplete}) and neither compared nor saved. Anything
 * thrown leaves the saved followers as they were.
 */
public final class Unfollowers {
    static final String LOADED = "unfollowers loaded";
    static final String ROW = "unfollowers row";
    static final String COMMIT = "unfollowers commit";

    /** How long the list has to sit at its end, with no more accounts arriving, before it counts as read. */
    static final long SETTLE_MS = 2500;
    /** How many quiet waits in a row a list needs before it is taken as read when its header gives no count. */
    static final int QUIET_WAITS = 3;
    /** A scan with fewer than this share of the count in the list's header isn't taken as the whole list. */
    static final double HEADER_SHARE = 0.97;
    /** A scan with fewer than this share of the saved followers isn't taken as the whole list. */
    static final double COMPLETE_SHARE = 0.5;
    /** Below this many saved followers a short scan is believed, since losing most of a few is possible. */
    static final int GUARD_FROM = 20;

    /** Instagram's names for your Followers list, which it keeps. */
    static final Set<String> FOLLOWER_KINDS = Collections.unmodifiableSet(new HashSet<>(
            java.util.Arrays.asList("FOLLOWERS", "FOLLOWERS_SIMPLIFIED")));

    /** What an Instagram username looks like, which tells it from a name or a label on a row. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._]{1,30}");

    /** "1,072 followers": a plain count, since an abbreviated one like 12.5K can't be compared. */
    private static final Pattern HEADER =
            Pattern.compile("([0-9][0-9.,\u00a0\u202f ]*)\\s+followers?", Pattern.CASE_INSENSITIVE);

    private static final String DATABASE = "hushgram_followers.db";

    private static final Object LOCK = new Object();
    /** The accounts the list being read has loaded, with the username a row gave each, or null. */
    private static final Map<String, String> scan = new LinkedHashMap<>();
    private static WeakReference<Object> scanList = new WeakReference<>(null);
    private static boolean settling;
    private static int generation;
    /** The last account the list has loaded, and whether its row has been drawn: the list has been scrolled to its end. */
    private static WeakReference<View> lastRow = new WeakReference<>(null);
    private static String lastId;
    private static boolean lastShown;
    private static volatile Store store;

    private Unfollowers() {
    }

    /** One account that stopped following you. */
    public static final class Entry {
        public final String id;
        public final String username;
        public final long leftAt;

        Entry(String id, String username, long leftAt) {
            this.id = id;
            this.username = username;
            this.leftAt = leftAt;
        }
    }

    /** What comparing a finished scan with the saved followers found. */
    static final class Result {
        /** Saved followers the scan doesn't have, in the saved order. */
        final List<String> gone;
        /** There were no saved followers: the scan is the first, and nothing is compared. */
        final boolean first;
        /** The scan is too short beside the saved followers to be the whole list. */
        final boolean incomplete;

        Result(List<String> gone, boolean first, boolean incomplete) {
            this.gone = gone;
            this.first = first;
            this.incomplete = incomplete;
        }
    }

    /** Compares the followers saved with the ones a scan found. Pure, so it can be tested. */
    static Result compare(Map<String, String> saved, Map<String, String> found) {
        if (saved.isEmpty()) return new Result(Collections.emptyList(), true, false);
        if (incomplete(saved.size(), found.size())) return new Result(Collections.emptyList(), false, true);
        List<String> gone = new ArrayList<>();
        for (String id : saved.keySet()) {
            if (!found.containsKey(id)) gone.add(id);
        }
        return new Result(gone, false, false);
    }

    /** Whether a scan of [found] accounts is too short beside [saved] saved ones to be the whole list. */
    static boolean incomplete(int saved, int found) {
        return saved >= GUARD_FROM && found < saved * COMPLETE_SHARE;
    }

    /** The count in a header like "1,072 followers", or -1 when the text isn't one, or the count is abbreviated. */
    static int headerCount(@Nullable CharSequence text) {
        if (text == null) return -1;
        java.util.regex.Matcher match = HEADER.matcher(text.toString().trim());
        if (!match.matches()) return -1;
        String digits = match.group(1).replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) return -1;
        return Integer.parseInt(digits);
    }

    /** Whether [found] accounts is the whole list the header counts [total] of: true when the header gave none. */
    static boolean complete(int found, int total) {
        return total <= 0 || found >= total * HEADER_SHARE;
    }

    /** Whether the list is one of your own Followers lists: its kind is one and you own it. */
    static boolean ownFollowersList(@Nullable Object kind, @Nullable String owner, @Nullable String viewer) {
        if (!(kind instanceof Enum)) return false;
        if (!FOLLOWER_KINDS.contains(((Enum<?>) kind).name())) return false;
        return owner != null && !owner.isEmpty() && owner.equals(viewer);
    }

    /** The username in a row's texts: the first one that is shaped like one. Null when none is. */
    @Nullable
    static String username(List<CharSequence> texts) {
        for (CharSequence text : texts) {
            if (text == null) continue;
            String trimmed = text.toString().trim();
            if (USERNAME.matcher(trimmed).matches()) return trimmed;
        }
        return null;
    }

    static boolean on() {
        return Utils.settingsReady() && Settings.TRACK_UNFOLLOWERS.get();
    }

    /**
     * Handed every account the list [list] has loaded so far, each time it builds its rows. Starts a
     * new scan when it's a different list from the last one, and holds the read back, since more is
     * arriving. Never throws.
     */
    static void loaded(@Nullable Object users, @Nullable Object list, FollowingList.Reader reader,
                       @Nullable Object binder) {
        try {
            if (!(users instanceof List) || list == null || binder == null || !on()) return;
            String viewer = reader.viewerId(binder);
            if (!ownFollowersList(reader.listKind(binder), reader.listOwnerId(binder), viewer)) return;
            HookStatus.invoked(FamilyNames.FRIENDSHIP_STATUS);
            synchronized (LOCK) {
                if (scanList.get() != list) {
                    scan.clear();
                    scanList = new WeakReference<>(list);
                    lastId = null;
                    lastShown = false;
                }
                generation++;
                String last = null;
                for (Object user : (List<?>) users) {
                    String id = FriendshipStatus.userId(user);
                    if (id == null || id.isEmpty()) continue;
                    last = id;
                    if (!scan.containsKey(id)) scan.put(id, null);
                }
                if (last != null && !last.equals(lastId)) {
                    lastId = last;
                    lastShown = false;
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, LOADED, failure);
        }
    }

    /**
     * Handed each row of a follow list as it's drawn. On a row of your own Followers list it notes the
     * account's username, and when the list can't scroll any further, starts the wait for it to settle.
     * Never throws.
     */
    static void rowBound(@Nullable Object binder, @Nullable View row, @Nullable Object user,
                         FollowingList.Reader reader) {
        try {
            if (binder == null || row == null || user == null || !on()) return;
            String viewer = reader.viewerId(binder);
            if (!ownFollowersList(reader.listKind(binder), reader.listOwnerId(binder), viewer)) return;
            String id = FriendshipStatus.userId(user);
            if (id == null || id.isEmpty()) return;
            String name = username(texts(row));
            int mine;
            synchronized (LOCK) {
                if (name != null) scan.put(id, name);
                else if (!scan.containsKey(id)) scan.put(id, null);
                // Its last account on screen means the list was scrolled to its end. Any page still to come
                // arrives within the wait and starts it over.
                if (!id.equals(lastId)) return;
                lastShown = true;
                lastRow = new WeakReference<>(row);
                if (settling) return;
                settling = true;
                mine = generation;
            }
            Utils.runOnMainThreadDelayed(() -> settle(mine, 1), SETTLE_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, ROW, failure);
        }
    }

    /** The texts of a row's text views, top to bottom. */
    private static List<CharSequence> texts(View row) {
        List<CharSequence> found = new ArrayList<>();
        collect(row, found, 0);
        return found;
    }

    private static void collect(View view, List<CharSequence> found, int depth) {
        if (depth > 8) return;
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            found.add(((TextView) view).getText());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found, depth + 1);
        }
    }

    /**
     * The wait is over. Reads the scan when nothing arrived since it began, the list's last account is on
     * screen, and the list holds the count its header gives, or, when the header gives none, when
     * {@link #QUIET_WAITS} waits in a row passed with nothing arriving. Otherwise waits again.
     */
    private static void settle(int started, int quiet) {
        Map<String, String> read = null;
        boolean again = false;
        int next = started;
        int waits = quiet;
        int total = headerCount(lastRow.get());
        synchronized (LOCK) {
            if (started != generation) {
                again = true;
                next = generation;
                waits = 1;
            } else if (!lastShown) {
                settling = false;
            } else if (total > 0 ? !complete(scan.size(), total) : quiet < QUIET_WAITS) {
                // More is on its way: a page is loading, or the header says accounts are still to come.
                again = true;
                waits = quiet + 1;
            } else {
                settling = false;
                read = new LinkedHashMap<>(scan);
            }
        }
        if (again) {
            final int generationNow = next;
            final int count = waits;
            Utils.runOnMainThreadDelayed(() -> settle(generationNow, count), SETTLE_MS);
        } else if (read != null && !read.isEmpty()) {
            final Map<String, String> finished = read;
            Utils.runOnBackgroundThread(() -> commit(finished));
        }
    }

    /** The count in the list's header, found from any of its views, or -1. */
    private static int headerCount(@Nullable View row) {
        try {
            if (row == null) return -1;
            return findCount(row.getRootView(), 0);
        } catch (Throwable failure) {
            return -1;
        }
    }

    private static int findCount(View view, int depth) {
        if (depth > 14) return -1;
        if (view.getVisibility() == View.VISIBLE) {
            CharSequence text = view instanceof TextView ? ((TextView) view).getText() : null;
            int count = headerCount(text);
            if (count < 0) count = headerCount(view.getContentDescription());
            boolean leaf = !(view instanceof ViewGroup) || ((ViewGroup) view).getChildCount() == 0;
            if (count < 0 && leaf && view.isClickable()) {
                android.view.accessibility.AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
                if (info != null) count = headerCount(info.getText());
            }
            if (count >= 0) return count;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                int count = findCount(group.getChildAt(i), depth + 1);
                if (count >= 0) return count;
            }
        }
        return -1;
    }

    /** Compares a finished scan with the saved followers, keeps what changed and says so. Off the main thread. */
    static void commit(Map<String, String> found) {
        try {
            Store db = store();
            Map<String, String> saved = db.followers();
            Result result = compare(saved, found);
            if (result.incomplete) {
                Logger.printDebug(() -> "Unfollowers: the list read had " + found.size() + " of " + saved.size()
                        + " saved followers, so it was left alone");
                return;
            }
            // A name this scan didn't see a row for stays the one it had.
            Map<String, String> merged = new LinkedHashMap<>();
            for (Map.Entry<String, String> account : found.entrySet()) {
                String name = account.getValue() != null ? account.getValue() : saved.get(account.getKey());
                merged.put(account.getKey(), name);
            }
            long now = System.currentTimeMillis();
            List<Entry> left = new ArrayList<>();
            for (String id : result.gone) left.add(new Entry(id, saved.get(id), now));
            db.replace(merged, left, now);
            if (result.first) {
                Utils.showToastLong(L10n.f("HushGram saved your %1$s followers and will tell you who unfollows.",
                        String.valueOf(merged.size())));
            } else if (!left.isEmpty()) {
                Utils.showToastLong(message(left));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, COMMIT, failure);
        }
    }

    /** What the toast says about [left], the people who just left. */
    static String message(List<Entry> left) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < left.size() && i < 3; i++) {
            if (i > 0) names.append(", ");
            String name = left.get(i).username;
            names.append(name == null ? left.get(i).id : "@" + name);
        }
        if (left.size() > 3) names.append(" +").append(left.size() - 3);
        return L10n.f(left.size() == 1 ? "%1$s unfollowed you." : "Unfollowed you: %1$s", names.toString());
    }

    /** The people who unfollowed you, newest first. Call off the main thread. */
    public static List<Entry> left() {
        try {
            return store().left();
        } catch (Throwable failure) {
            Logger.printException(() -> "Unfollowers: could not read the list", failure);
            return new ArrayList<>();
        }
    }

    /** {followers saved, when they were saved}. Call off the main thread. */
    public static long[] saved() {
        try {
            return store().summary();
        } catch (Throwable failure) {
            Logger.printException(() -> "Unfollowers: could not read the saved followers", failure);
            return new long[2];
        }
    }

    /** Forgets the saved followers and everyone who left. Call off the main thread. */
    public static void clear() {
        try {
            store().clear();
        } catch (Throwable failure) {
            Logger.printException(() -> "Unfollowers: could not clear", failure);
        }
    }

    private static Store store() {
        Store current = store;
        if (current == null) {
            synchronized (LOCK) {
                if (store == null) store = new Store(Utils.getContext());
                current = store;
            }
        }
        return current;
    }

    /** The saved followers, the people who left, and when the followers were saved, in one database. */
    private static final class Store extends SQLiteOpenHelper {
        Store(Context context) {
            super(context, DATABASE, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE follower (id TEXT PRIMARY KEY, username TEXT, position INTEGER)");
            db.execSQL("CREATE TABLE left (id TEXT PRIMARY KEY, username TEXT, left_at INTEGER)");
            db.execSQL("CREATE TABLE meta (saved_at INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int from, int to) {
        }

        Map<String, String> followers() {
            Map<String, String> found = new LinkedHashMap<>();
            try (Cursor cursor = getReadableDatabase().query("follower", null, null, null, null, null, "position")) {
                while (cursor.moveToNext()) {
                    found.put(cursor.getString(cursor.getColumnIndexOrThrow("id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("username")));
                }
            }
            return found;
        }

        List<Entry> left() {
            List<Entry> found = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query("left", null, null, null, null, null, "left_at DESC")) {
                while (cursor.moveToNext()) {
                    found.add(new Entry(cursor.getString(cursor.getColumnIndexOrThrow("id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("username")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("left_at"))));
                }
            }
            return found;
        }

        long[] summary() {
            long[] summary = new long[2];
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM follower", null)) {
                if (cursor.moveToFirst()) summary[0] = cursor.getLong(0);
            }
            try (Cursor cursor = db.rawQuery("SELECT saved_at FROM meta", null)) {
                if (cursor.moveToFirst()) summary[1] = cursor.getLong(0);
            }
            return summary;
        }

        /** Saves [followers] in place of the saved ones and adds [left] to the people who left, all or nothing. */
        void replace(Map<String, String> followers, List<Entry> left, long now) {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("follower", null, null);
                int position = 0;
                for (Map.Entry<String, String> account : followers.entrySet()) {
                    ContentValues values = new ContentValues();
                    values.put("id", account.getKey());
                    values.put("username", account.getValue());
                    values.put("position", position++);
                    db.insertWithOnConflict("follower", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }
                for (Entry entry : left) {
                    ContentValues values = new ContentValues();
                    values.put("id", entry.id);
                    values.put("username", entry.username);
                    values.put("left_at", entry.leftAt);
                    db.insertWithOnConflict("left", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                }
                // Someone who followed again isn't one who left.
                for (String id : followers.keySet()) db.delete("left", "id = ?", new String[] {id});
                db.delete("meta", null, null);
                ContentValues meta = new ContentValues();
                meta.put("saved_at", now);
                db.insert("meta", null, meta);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        void clear() {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("follower", null, null);
                db.delete("left", null, null);
                db.delete("meta", null, null);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }
}
