/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide reels you've seen" patch (#830).
 *
 * <p>Instagram records each reel you reach, in any Reels viewer, before it batches them for
 * {@code clips/write_seen_state/}. The patch hands that record's media id to {@link #watched(String)}
 * first thing, so the id is kept here even when Don't send reel watch history holds the record back.
 * The Reels viewer's network callback hands each page to {@link #page(List, String)}, which leaves out
 * the reels kept here while the page is one Instagram picked (the Reels tab, not a profile's reels or
 * a chat's), and the viewer's data source hands what it takes in later to {@link #added}. Only media
 * ids are kept, the last {@link #MAX_SEEN}, in a preferences file of their own, and nothing is sent
 * anywhere. Nothing is kept while the switch is off.
 */
public final class SeenReels {
    /** The diagnostic counter route: each Reels page looked at, and the reels left out. */
    static final String ROUTE = "Reels you've seen";

    /** The counter route for what the Reels viewer's data source takes in after its first page. */
    static final String ADDED_ROUTE = "Reels you've seen, added later";

    /** What a reel left out is counted under. */
    static final String SEEN = "reels you've seen";

    /** What a page where every reel was seen, kept whole so the viewer has something to show, is counted under. */
    static final String PAGE_KEPT = "page kept, every reel seen";

    /** The preferences file the ids are kept in. Only this class writes it. */
    static final String FILE = "hushgram_seen_reels";

    /** The key holding the ids, oldest first, separated by spaces. */
    static final String KEY = "ids";

    /** How many reels are remembered. The one watched longest ago goes first. */
    static final int MAX_SEEN = 3000;

    /** How long after the last new id the file is written, so a run of swipes writes it once. */
    static final long SAVE_DELAY_MS = 3000;

    private static final Object LOCK = new Object();

    /** The ids, oldest first. Null until read from the file. Guarded by {@link #LOCK}. */
    @Nullable private static LinkedHashSet<String> seen;

    /** The file, once opened, or a test's own. Guarded by {@link #LOCK}. */
    @Nullable private static SharedPreferences store;

    /** Whether a write is waiting on {@link #SAVE_DELAY_MS}. Guarded by {@link #LOCK}. */
    private static boolean savePending;

    /** Writes the file now in place of later, for tests. */
    private static boolean saveAtOnce;

    /** Whether this run has said it keeps reels, and that it checked a page, in the debug log. */
    private static volatile boolean watchedLogged;
    private static volatile boolean pageLogged;

    private static final Runnable SAVE = SeenReels::save;

    private SeenReels() {
    }

    /** Whether the switch is on: settings ready, not paused, switch on. */
    static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_SEEN_REELS.get();
    }

    /**
     * Injected first thing where Instagram records a reel as reached, with that record's media id.
     * Keeps the reel's id while the switch is on. Never throws.
     */
    public static void watched(@Nullable String id) {
        watched(id, SeenReels::on);
    }

    static void watched(@Nullable String id, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SEEN_REELS);
            if (!on.getAsBoolean()) return;
            String key = key(id);
            if (key == null) return;
            synchronized (LOCK) {
                LinkedHashSet<String> ids = loaded();
                ids.remove(key);
                ids.add(key);
                while (ids.size() > MAX_SEEN) ids.remove(ids.iterator().next());
                scheduleSave();
                if (!watchedLogged) {
                    watchedLogged = true;
                    int count = ids.size();
                    Logger.printDebug(() -> "Seen reels: keeping the reels you reach, " + count + " so far");
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SEEN_REELS, "reel watched", failure);
        }
    }

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module name.
     * Answers a new list without the reels already watched while the switch is on and the page is
     * one Instagram picked, and [items] itself for any other viewer, when nothing goes, when every
     * reel would, when the switch is off or paused, or when anything goes wrong. Never throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, SeenReels::on, SeenReels::mediaIdOf);
    }

    static List<?> page(List<?> items, String module, BooleanSupplier on, Function<Object, String> ids) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.SEEN_REELS);
            if (!PaidPartnershipReels.pickedByInstagram(module)) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0 || !on.getAsBoolean()) return items;

            boolean[] goes = new boolean[size];
            int[] counts = mark(items, ids, goes, ROUTE);
            if (!pageLogged && counts != null) {
                pageLogged = true;
                int reels = counts[1];
                int remembered = size();
                Logger.printDebug(() -> "Seen reels: checking a Reels page of " + reels + " against " + remembered + " reels you've seen");
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (counts == null || counts[0] == 0) return items;
            int matched = counts[0];
            if (matched == counts[1]) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                Logger.printDebug(() -> "Seen reels: kept a page of " + size + " where every reel was seen");
                return items;
            }
            List<Object> kept = keep(items, goes, matched);
            FeedFilterCounters.removed(ROUTE, matched, SEEN);
            int left = kept.size();
            Logger.printDebug(() -> "Seen reels: took " + matched + " of " + size + " reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SEEN_REELS, "reels page", failure);
            return items;
        }
    }

    /**
     * Injected first thing in the Reels viewer's data source where it adds a list to its items or
     * inserts one among them, with the viewer's module name and [held], how many items it holds
     * already. {@link #page} keeps a page whole when every reel on it was seen, and the Reels tab's
     * pages hold one to three reels, so this takes those out once the viewer holds more than one
     * item, even when nothing else in the list stays: one item may be no more than the placeholder
     * Instagram shows while a page loads, so the viewer always keeps a reel to stand on. Answers
     * [items] itself for any other viewer, before that, when nothing goes, when the switch is off or
     * paused, or when anything goes wrong. Never throws.
     */
    public static List<?> added(List<?> items, String module, int held) {
        return added(items, module, held, SeenReels::on, SeenReels::mediaIdOf);
    }

    static List<?> added(List<?> items, String module, int held, BooleanSupplier on, Function<Object, String> ids) {
        if (items == null) return null;
        try {
            if (held <= 1 || !PaidPartnershipReels.pickedByInstagram(module)) return items;
            int size = items.size();
            if (size == 0 || !on.getAsBoolean()) return items;
            FeedFilterCounters.sawList(ADDED_ROUTE, size);

            boolean[] goes = new boolean[size];
            int[] counts = mark(items, ids, goes, ADDED_ROUTE);
            if (counts == null || counts[0] == 0) return items;
            int matched = counts[0];
            List<Object> kept = keep(items, goes, matched);
            FeedFilterCounters.removed(ADDED_ROUTE, matched, SEEN);
            int left = kept.size();
            Logger.printDebug(() -> "Seen reels: took " + matched + " of " + size + " added reels out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SEEN_REELS, "reels items added", failure);
            return items;
        }
    }

    /**
     * Marks in [goes] each item whose reel was seen. Answers how many were and how many items are
     * reels at all, or null when [items] doesn't hold as many items as [goes] has room for.
     */
    @Nullable
    private static int[] mark(List<?> items, Function<Object, String> ids, boolean[] goes, String route) {
        int matched = 0;
        int reels = 0;
        int at = 0;
        synchronized (LOCK) {
            LinkedHashSet<String> watched = loaded();
            for (Object item : items) {
                if (at >= goes.length) return null;
                String key = item == null ? null : key(ids.apply(item));
                if (key != null) reels++;
                boolean hit = key != null && watched.contains(key);
                if (hit) {
                    matched++;
                    FeedFilterCounters.sawKind(route, SEEN);
                }
                goes[at++] = hit;
            }
        }
        return at == goes.length ? new int[] {matched, reels} : null;
    }

    /** A new list of the items of [items] that [goes] doesn't mark, [matched] of them marked. */
    private static List<Object> keep(List<?> items, boolean[] goes, int matched) {
        List<Object> kept = new ArrayList<>(Math.max(0, goes.length - matched));
        int at = 0;
        for (Object item : items) {
            if (at < goes.length && !goes[at]) kept.add(item);
            at++;
        }
        return kept;
    }

    /**
     * The key a reel is kept under: its media id up to the underscore Instagram puts before the
     * author's id, so a record naming the reel by its id alone and a post naming it with the author
     * meet. Null for a missing or blank id.
     */
    @Nullable
    static String key(@Nullable String id) {
        if (id == null) return null;
        int cut = id.indexOf('_');
        String key = (cut < 0 ? id : id.substring(0, cut)).trim();
        if (key.isEmpty()) return null;
        for (int i = 0; i < key.length(); i++) {
            if (Character.isWhitespace(key.charAt(i))) return null;
        }
        return key;
    }

    /** The media id of a Reels item's post, or null for anything that isn't one. */
    @Nullable
    private static String mediaIdOf(Object item) {
        Object media = media(item);
        return media == null ? null : mediaId(media);
    }

    /** The ids, read from the file the first time. Guarded by {@link #LOCK}. */
    private static LinkedHashSet<String> loaded() {
        if (seen != null) return seen;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        SharedPreferences file = file();
        String stored = file == null ? null : file.getString(KEY, null);
        if (stored != null) {
            for (String id : stored.split(" ")) {
                String key = key(id);
                if (key != null) ids.add(key);
            }
            while (ids.size() > MAX_SEEN) ids.remove(ids.iterator().next());
        }
        seen = ids;
        return ids;
    }

    /** The preferences file, or null before Instagram has a context. Guarded by {@link #LOCK}. */
    @Nullable
    private static SharedPreferences file() {
        if (store != null) return store;
        Context context = Utils.getContext();
        if (context == null) return null;
        store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return store;
    }

    /** Writes the ids {@link #SAVE_DELAY_MS} after the last change, once for a run of them. Guarded by {@link #LOCK}. */
    private static void scheduleSave() {
        if (saveAtOnce) {
            write();
            return;
        }
        if (savePending) return;
        savePending = true;
        new Handler(Looper.getMainLooper()).postDelayed(SAVE, SAVE_DELAY_MS);
    }

    private static void save() {
        try {
            synchronized (LOCK) {
                savePending = false;
                write();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SEEN_REELS, "seen reels save", failure);
        }
    }

    /** Puts the ids in the file. Guarded by {@link #LOCK}. */
    private static void write() {
        SharedPreferences file = file();
        if (file == null || seen == null) return;
        file.edit().putString(KEY, String.join(" ", seen)).apply();
    }

    /** How many reels are remembered, for tests. */
    static int size() {
        synchronized (LOCK) {
            return loaded().size();
        }
    }

    /** Reads from [file] and writes to it at once, forgetting what was read before, for tests. */
    static void use(@Nullable SharedPreferences file) {
        synchronized (LOCK) {
            store = file;
            seen = null;
            savePending = false;
            saveAtOnce = true;
        }
    }

    /**
     * The post a Reels item carries, or null for anything else. The patch writes the body, which
     * checks the item is Instagram's clips item and reads its one post field. Null as built.
     */
    public static Object media(Object item) {
        return null;
    }

    /** The media id of [media], a post. The patch writes the body, which asks Media for its id. Null as built. */
    public static String mediaId(Object media) {
        return null;
    }
}
