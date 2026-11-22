/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.os.Bundle;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Posts and reels saved into a locked collection stay out of Saved's other grids while the lock is
 * shut (#678), so All, Posts, Reels and Saved's search don't show what the collection's own page covers.
 *
 * <p>Instagram tells which collections a saved post is in only by their ids, and {@link CollectionLocks}
 * keeps a collection by its name. Each collection Instagram reads goes through {@link #read}, so its
 * id is known by its name from then on. The id of one on a list is also remembered
 * ({@link Settings#LOCKED_COLLECTION_IDS}), so a grid that loads before the collections do after a
 * restart still knows it.
 *
 * <p>Saved's grid asks {@link #leaveOut} about each post as it makes its rows, and its Reels screen
 * hands {@link #leaveOutReels} each batch of reels it gets before making rows of them, as Saved's search
 * hands {@link #leaveOutFound} what it found. A search that found only locked posts shows that it found
 * nothing ({@link #leftNothing}), as one that found nothing at all does. A post is left out
 * while the lock is shut and one of its collections is on the list for the account in front, unless
 * the grid belongs to one of the post's own collections: a collection's page always shows all of
 * itself, and an unlocked collection sharing a post with a locked one keeps it. After an unlock the
 * posts come back the next time the grid makes its rows, as a pull to refresh does.
 *
 * <p>Nothing here throws into Instagram. Anything unexpected shows the post.
 */
public final class LockedCollectionItems {
    /** What's counted each time a post is left out of a grid. */
    static final String LEFT_OUT = "Posts of locked collections left out of Saved";
    /** The step a failure is reported under. */
    static final String STEP = "locked collection posts";

    /** How many collection ids are remembered at most, the newest kept. */
    static final int REMEMBERED = 100;

    /** Each collection read since Instagram started, its id to its name as the list keeps names. */
    private static final Map<String, String> names = new ConcurrentHashMap<>();

    /** The remembered ids as last parsed, with the text they came from. */
    private static volatile String rememberedRaw;
    private static volatile Map<String, String> remembered = Collections.emptyMap();

    private static volatile boolean logged;

    private LockedCollectionItems() {
    }

    // ---------------------------------------------------------------- the collections

    /** [collection], one Instagram just read, is known by its id from now on. Never throws. */
    public static void read(@Nullable Object collection) {
        if (collection == null) return;
        try {
            learned(idOf(collection), nameOf(collection));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
        }
    }

    /** The collection with [id] is called [name]. Remembered as well when the name is on a list. */
    static void learned(@Nullable String id, @Nullable String name) {
        String kept = CollectionLocks.kept(name);
        if (id == null || id.isEmpty() || kept == null) return;
        names.put(id, kept);
        if (CollectionLocks.listed(null, kept)) remember(id, kept);
    }

    /** The collection called [name] went on a list: each id read for that name is remembered. */
    static void locked(@Nullable String name) {
        String kept = CollectionLocks.kept(name);
        if (kept == null) return;
        for (Map.Entry<String, String> each : names.entrySet()) {
            if (each.getValue().equals(kept)) remember(each.getKey(), kept);
        }
    }

    /** The name the collection with [id] has, as the list keeps names, or null when it isn't known. */
    @Nullable
    static String nameFor(String id) {
        String name = names.get(id);
        return name != null ? name : remembered().get(id);
    }

    // ---------------------------------------------------------------- the grid

    /**
     * Non-zero when [media], a post Saved's grid is making a row for, is left out of it: the lock is
     * shut and the post is in a collection on the list, and [shown], the collection the grid is of,
     * isn't one of the post's. Fails open: anything unexpected shows the post. Never throws.
     */
    public static int leaveOut(@Nullable Object media, @Nullable Object shown) {
        if (media == null) return 0;
        return leaveOut(() -> savedIn(media), () -> shown == null ? null : idOf(shown));
    }

    /** {@link #leaveOut(Object, Object)} for the collections [ids] gives and the one [shown] gives. */
    static int leaveOut(Supplier<List<?>> ids, Supplier<String> shown) {
        try {
            if (!MessagesLock.collectionListLocked()) return 0;
            if (!leaveOut(ids.get(), shown.get())) return 0;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, LEFT_OUT);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Saved: left a locked collection's post out of the grid");
            }
            return 1;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
            return 0;
        }
    }

    /**
     * Whether a post in the collections [ids] is left out of the grid of the collection [shown]: one
     * of them is on the list for the account in front, and none is [shown].
     */
    static boolean leaveOut(@Nullable List<?> ids, @Nullable String shown) {
        if (ids == null || ids.isEmpty()) return false;
        boolean locked = false;
        for (Object each : ids) {
            if (each == null) continue;
            String id = each.toString();
            if (id.equals(shown)) return false;
            String name = nameFor(id);
            if (name != null && CollectionLocks.listed(name)) locked = true;
        }
        return locked;
    }

    // ---------------------------------------------------------------- the Reels tab

    /**
     * [reels], the items Saved's Reels screen [screen] just got, without those whose post
     * {@link #leaveOut} leaves out of the collection the screen is of. Called before the screen's grid
     * makes rows of them. Answers [reels] itself when none is left out. Fails open, never throws.
     */
    @Nullable
    public static List<?> leaveOutReels(@Nullable List<?> reels, @Nullable Object screen) {
        if (reels == null || reels.isEmpty()) return reels;
        try {
            if (!MessagesLock.collectionListLocked()) return reels;
            Object shown = screen == null ? null : collectionOf(screen);
            return leaveOutEach(reels, reel -> reel != null && leaveOut(mediaOf(reel), shown) != 0);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
            return reels;
        }
    }

    // ---------------------------------------------------------------- Saved's search

    /** The argument Saved's search keeps the id of the collection it searches in under, as Instagram names it. */
    static final String SEARCH_COLLECTION = "SavedSearchFragment.ARGUMENT_COLLECTION_ID";

    /**
     * What {@link #leaveOutFound} answers when it left out everything the search found. Empty, and
     * {@link #leftNothing} knows it by itself, so the search shows that it found nothing.
     */
    static final List<Object> NOTHING_LEFT = Collections.unmodifiableList(new ArrayList<>(0));

    /**
     * [posts], what Saved's search [screen] found, without those {@link #leaveOut} leaves out of the
     * collection the search is in, or of all of Saved when it's in none. Answers [posts] itself when
     * none is left out, and {@link #NOTHING_LEFT} when all of them are. Fails open, never throws.
     */
    @Nullable
    public static List<?> leaveOutFound(@Nullable List<?> posts, @Nullable Object screen) {
        if (posts == null || posts.isEmpty()) return posts;
        try {
            if (!MessagesLock.collectionListLocked()) return posts;
            String shown = searchedIn(screen);
            return found(posts, post -> post != null && leaveOut(() -> savedIn(post), () -> shown) != 0);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
            return posts;
        }
    }

    /** {@link #leaveOutEach} for a search, answering {@link #NOTHING_LEFT} when [out] took every one of [posts]. */
    static List<?> found(List<?> posts, Predicate<Object> out) {
        List<?> kept = leaveOutEach(posts, out);
        return kept != posts && kept.isEmpty() ? NOTHING_LEFT : kept;
    }

    /**
     * Non-zero when [found], what Saved's search just took, is {@link #NOTHING_LEFT}: everything it
     * found is in a locked collection. The search then shows Instagram's own page for a search that
     * found nothing, and not an empty grid. Never throws.
     */
    public static int leftNothing(@Nullable Object found) {
        return found == NOTHING_LEFT ? 1 : 0;
    }

    /** The id of the collection Saved's search [screen] searches in, from its arguments, or null for all of Saved. */
    @Nullable
    static String searchedIn(@Nullable Object screen) {
        if (screen == null) return null;
        try {
            Object arguments = screen.getClass().getMethod("getArguments").invoke(screen);
            return arguments instanceof Bundle ? ((Bundle) arguments).getString(SEARCH_COLLECTION) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** [items] without those [out] says yes to: [items] itself when that's none, a new list in the same order otherwise. */
    static List<?> leaveOutEach(List<?> items, Predicate<Object> out) {
        List<Object> kept = null;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            boolean left = out.test(item);
            if (left && kept == null) kept = new ArrayList<>(items.subList(0, i));
            else if (!left && kept != null) kept.add(item);
        }
        return kept == null ? items : kept;
    }

    // ---------------------------------------------------------------- the ids remembered

    private static Map<String, String> remembered() {
        try {
            if (!Utils.settingsReady()) return Collections.emptyMap();
            String raw = Settings.LOCKED_COLLECTION_IDS.get();
            if (!raw.equals(rememberedRaw)) {
                remembered = Collections.unmodifiableMap(parse(raw));
                rememberedRaw = raw;
            }
            return remembered;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
            return Collections.emptyMap();
        }
    }

    private static void remember(String id, String name) {
        try {
            if (!Utils.settingsReady()) return;
            Map<String, String> kept = parse(Settings.LOCKED_COLLECTION_IDS.savedValue());
            if (name.equals(kept.get(id))) return;
            kept.remove(id);
            kept.put(id, name);
            Settings.LOCKED_COLLECTION_IDS.save(format(kept));
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, STEP, t);
        }
    }

    /** The ids in [text], a line for each with a tab and the name, oldest first. */
    static Map<String, String> parse(@Nullable String text) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return ids;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String id = line.substring(0, tab).trim();
            String name = line.substring(tab + 1).trim();
            if (!id.isEmpty() && !name.isEmpty()) ids.put(id, name);
        }
        return ids;
    }

    /** [ids] as the text they're kept in, only the newest {@link #REMEMBERED}. */
    static String format(Map<String, String> ids) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> each : ids.entrySet()) {
            lines.add(ChatList.clean(each.getKey()) + '\t' + ChatList.clean(each.getValue()));
        }
        int from = Math.max(0, lines.size() - REMEMBERED);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    // ---------------------------------------------------------------- Instagram's own

    /** Rewritten by the patch: the ids of the collections [media], a saved post, is in, or null. */
    @Nullable
    public static List<?> savedIn(Object media) {
        return null;
    }

    /** Rewritten by the patch: the id of [collection]. */
    @Nullable
    public static String idOf(Object collection) {
        return null;
    }

    /** Rewritten by the patch: the name of [collection]. */
    @Nullable
    public static String nameOf(Object collection) {
        return null;
    }

    /** Rewritten by the patch: the post [reel], one of the items Saved's Reels screen gets, shows. */
    @Nullable
    public static Object mediaOf(Object reel) {
        return null;
    }

    /** Rewritten by the patch: the collection [screen], Saved's Reels screen, is of. */
    @Nullable
    public static Object collectionOf(Object screen) {
        return null;
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        names.clear();
        rememberedRaw = null;
        remembered = Collections.emptyMap();
        logged = false;
    }
}
