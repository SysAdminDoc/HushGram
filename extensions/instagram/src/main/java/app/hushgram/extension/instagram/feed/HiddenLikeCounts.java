/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Show hidden like counts" patch.
 *
 * <p>When a poster hides the like count on a post or reel, Instagram 450 still gets the post's
 * {@code like_count} with it, or doesn't, and a flag saying the count is hidden. Every caller that
 * decides whether to draw the count, the feed's like row, a reel's item config, the grid's and the
 * likes text, asks one method, with the session, the poster's id and that flag. The patch hands the
 * poster's id and the flag through {@link #hidden} first in that method, so while the switch is on a
 * hidden count is treated as shown when that poster's hidden posts have come with a count.
 * Instagram's own like rows then draw a number only when the post's count is above zero, so nothing
 * is made up.
 *
 * <p>The method gets the poster's id and no post id, so the decision is kept by poster. Each like
 * row hands the post's data and the flag it just read to {@link #rowRead}, and the like count reader
 * hands every post's data and count to {@link #sawCount}. Both read the poster's id off the post's
 * own data, and for a post that hid its likes note whether its {@code like_count} came above zero.
 * Instagram asks the method for a reel, or anywhere else no row ran first, with the same poster id,
 * so those get the answer the notes give. A poster is let through once one of their hidden posts
 * came with a count, and not while a hidden post of theirs that came without one is fresh (ten
 * minutes), so a hidden post the server sent no count for stays as Instagram drew it. Nothing is
 * kept for a post that shows its likes, and the notes are a bounded, least recently used table, so
 * they don't grow over a long session.
 *
 * <p>The owner's own menus and the request that changes the setting read the flag elsewhere, so
 * they never see the switch. Every hook fails open: with the switch off, HushGram paused, the
 * settings not read yet, or anything thrown, Instagram decides as it would.
 */
public final class HiddenLikeCounts {
    /** The steps a failure is reported under. */
    static final String DECISION = "like count decision";
    static final String COUNT_READ = "like count read";
    static final String ROW_READ = "like row read";

    /** Counted each time a hidden count was let through. */
    static final String SHOWN = "let a hidden like count show";
    /** Counted each time a post that hid its likes came with a count above zero. */
    static final String CAME_WITH_COUNT = "a post that hid its likes came with its count";
    /** Counted each time a post that hid its likes came without one. */
    static final String NO_COUNT = "a post hid its likes and the server sent no count";
    /** Counted each time a like row left a hidden post hidden for want of a count. */
    static final String LEFT_HIDDEN = "hidden posts left hidden without a count";

    /**
     * The key Instagram's data files a post's hidden-count flag under: the hash of its field name,
     * {@code like_and_view_counts_disabled}, written out so the patch can check it in the dex.
     */
    static final int LIKES_HIDDEN_KEY = -1301662067;
    /** The key of the post's count, the hash of {@code like_count}, checked the same way. */
    static final int LIKE_COUNT_KEY = -792455577;
    /** The key of a post's poster, the hash of {@code user}, checked the same way. */
    static final int USER_KEY = 3599307;
    /** The key of the poster's id, the hash of {@code id}, checked the same way. */
    static final int ID_KEY = 3355;

    /** How many posters are remembered. The least recently used goes first. */
    static final int MAX_POSTERS = 256;
    /** How long a hidden post that came without a count holds its poster's other posts hidden. */
    static final long HOLD_MILLIS = 10 * 60 * 1000L;

    /** What the hidden posts of one poster have come with. */
    private static final class Noted {
        /** One of their hidden posts came with a count above zero. */
        boolean withCount;
        /** One of their hidden posts came without one, at {@link #withoutAt}. */
        boolean without;
        long withoutAt;
    }

    private static final Map<String, Noted> noted = new LinkedHashMap<String, Noted>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Noted> eldest) {
            return size() > MAX_POSTERS;
        }
    };
    private static volatile boolean logged;

    private HiddenLikeCounts() {
    }

    /**
     * Injected first in the method every caller asks, with the poster's id and the post's
     * hidden-count flag (passed as an int, nonzero for hidden). Answers whether to hide the count:
     * what it was given, except false in place of true while the switch is on and that poster's
     * hidden posts came with a count. Never throws.
     */
    public static boolean hidden(@Nullable String poster, int hidden) {
        return hidden(poster, hidden != 0, HiddenLikeCounts::switchedOn, HiddenLikeCounts::nowMillis);
    }

    static boolean hidden(@Nullable String poster, boolean hidden, BooleanSupplier on, LongSupplier now) {
        HookStatus.invoked(FamilyNames.HIDDEN_LIKE_COUNTS);
        try {
            if (!hidden || poster == null || !on.getAsBoolean() || !countedFor(poster, now.getAsLong())) return hidden;
            HookStatus.counted(FamilyNames.HIDDEN_LIKE_COUNTS, SHOWN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Feed: showed a like count its poster hid");
            }
            return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HIDDEN_LIKE_COUNTS, DECISION, t);
            return hidden;
        }
    }

    /**
     * Injected in each like row right after it reads a post's hidden-count flag, with the post's
     * data and the flag it read (a Boolean, or null). Only looks: for a post that hid its likes,
     * notes under its poster whether its own count came above zero. Never throws.
     */
    public static void rowRead(@Nullable Object tree, @Nullable Object flag) {
        rowRead(tree, flag, HiddenLikeCounts::switchedOn, HiddenLikeCounts::likeCount, HiddenLikeCounts::posterOf,
                HiddenLikeCounts::nowMillis);
    }

    static void rowRead(@Nullable Object tree, @Nullable Object flag, BooleanSupplier on, Function<Object, Object> likeCount,
                        Function<Object, String> posterOf, LongSupplier now) {
        try {
            if (tree == null || !Boolean.TRUE.equals(flag) || !on.getAsBoolean()) return;
            Object count = likeCount.apply(tree);
            boolean came = count instanceof Integer && (Integer) count > 0;
            if (!came) HookStatus.counted(FamilyNames.HIDDEN_LIKE_COUNTS, LEFT_HIDDEN);
            note(posterOf.apply(tree), came, now.getAsLong());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HIDDEN_LIKE_COUNTS, ROW_READ, t);
        }
    }

    /**
     * Injected in Instagram's like count reader right after it reads a post's {@code like_count},
     * with the post's data and what it read (an Integer, or null when the server sent none). Only
     * looks: while the switch is on and the post hid its likes, counts whether the number came and
     * notes it under the post's poster. Never throws.
     */
    public static void sawCount(@Nullable Object tree, @Nullable Object count) {
        sawCount(tree, count, HiddenLikeCounts::switchedOn, HiddenLikeCounts::likesHidden, HiddenLikeCounts::posterOf,
                HiddenLikeCounts::nowMillis);
    }

    static void sawCount(@Nullable Object tree, @Nullable Object count, BooleanSupplier on,
                         Function<Object, Boolean> likesHidden, Function<Object, String> posterOf, LongSupplier now) {
        try {
            if (tree == null || !on.getAsBoolean() || !Boolean.TRUE.equals(likesHidden.apply(tree))) return;
            boolean came = count instanceof Integer && (Integer) count > 0;
            HookStatus.counted(FamilyNames.HIDDEN_LIKE_COUNTS, came ? CAME_WITH_COUNT : NO_COUNT);
            note(posterOf.apply(tree), came, now.getAsLong());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HIDDEN_LIKE_COUNTS, COUNT_READ, t);
        }
    }

    /** Notes under the poster that one of their hidden posts came with a count, or without. */
    private static void note(@Nullable String poster, boolean came, long now) {
        if (poster == null || poster.isEmpty()) return;
        synchronized (noted) {
            Noted entry = noted.get(poster);
            if (entry == null) {
                entry = new Noted();
                noted.put(poster, entry);
            }
            if (came) {
                entry.withCount = true;
            } else {
                entry.without = true;
                entry.withoutAt = now;
            }
        }
    }

    /** Whether the poster's hidden posts came with a count, and none came without one lately. */
    private static boolean countedFor(String poster, long now) {
        synchronized (noted) {
            Noted entry = noted.get(poster);
            return entry != null && entry.withCount && !(entry.without && now - entry.withoutAt < HOLD_MILLIS);
        }
    }

    /** How many posters are remembered, for tests. */
    static int remembered() {
        synchronized (noted) {
            return noted.size();
        }
    }

    private static Boolean likesHidden(Object tree) {
        return flag(tree, LIKES_HIDDEN_KEY);
    }

    private static Object likeCount(Object tree) {
        return count(tree, LIKE_COUNT_KEY);
    }

    /** The id of the account that posted this data, or null when it has none. */
    @Nullable
    private static String posterOf(Object tree) {
        Object poster = child(tree, USER_KEY);
        if (poster == null) return null;
        Object id = text(poster, ID_KEY);
        return id instanceof String ? (String) id : null;
    }

    /**
     * Filled in by the patch: the boolean {@code tree} keeps under {@code key}, read the way the
     * like count reader reads its own, or null unpatched.
     */
    @Nullable
    static Boolean flag(Object tree, int key) {
        return null;
    }

    /**
     * Filled in by the patch: the Integer {@code tree} keeps under {@code key}, read the way the
     * like count reader reads it, or null unpatched.
     */
    @Nullable
    static Object count(Object tree, int key) {
        return null;
    }

    /**
     * Filled in by the patch: the part of {@code tree} kept under {@code key}, read the way a like
     * row reads a post's poster, or null unpatched.
     */
    @Nullable
    static Object child(Object tree, int key) {
        return null;
    }

    /**
     * Filled in by the patch: the string {@code tree} keeps under {@code key}, read the way a like
     * row reads a poster's id, or null unpatched.
     */
    @Nullable
    static Object text(Object tree, int key) {
        return null;
    }

    /** Forgets what the rows noted, for tests. */
    static void reset() {
        synchronized (noted) {
            noted.clear();
        }
        logged = false;
    }

    static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.SHOW_HIDDEN_LIKE_COUNTS.get();
    }
}
