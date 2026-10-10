/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Show hidden like counts" patch.
 *
 * <p>When a poster hides the like count on a post or reel, Instagram 450 still gets the post's
 * {@code like_count} with it, or doesn't, and a flag saying the count is hidden. Every like row,
 * the feed's, a reel's, the grid's and the likes text, asks one method whether to hide the count,
 * with the poster's id and that flag. The patch hands the flag through {@link #hidden} first in
 * that method, so while the switch is on a hidden count is treated as shown. Instagram's own like
 * rows then draw a number only when the post's count is above zero, so nothing is made up.
 *
 * <p>To be sure of that even where a row would print a zero, the count is let through only once
 * the server has been seen sending one with a post that hid it. The patch hands every like count
 * Instagram reads, with the post it read it from, to {@link #sawCount}, which checks the post's
 * flag and counts what came, so the Diagnostics report says either way whether the count arrives.
 * Until one has, a hidden count stays hidden.
 *
 * <p>The owner's own menus and the request that changes the setting read the flag elsewhere, so
 * they never see the switch. Both hooks fail open: with the switch off, HushGram paused, the
 * settings not read yet, or anything thrown, Instagram decides as it would.
 */
public final class HiddenLikeCounts {
    /** The steps a failure is reported under. */
    static final String DECISION = "like count decision";
    static final String COUNT_READ = "like count read";

    /** Counted each time a hidden count was let through. */
    static final String SHOWN = "let a hidden like count show";
    /** Counted each time a post that hid its likes came with a count above zero. */
    static final String CAME_WITH_COUNT = "a post that hid its likes came with its count";
    /** Counted each time a post that hid its likes came without one. */
    static final String NO_COUNT = "a post hid its likes and the server sent no count";

    /**
     * The key Instagram's data files a post's hidden-count flag under: the hash of its field name,
     * {@code like_and_view_counts_disabled}, written out so the patch can check it in the dex.
     */
    static final int LIKES_HIDDEN_KEY = -1301662067;

    /** Set once the server has sent a count with a post that hid it. */
    private static volatile boolean countsArrive;
    private static volatile boolean logged;

    private HiddenLikeCounts() {
    }

    /**
     * Injected first in the method every like row asks, with the post's hidden-count flag (passed
     * as an int, nonzero for hidden). Answers whether to hide the count: what it was given, except
     * false in place of true while the switch is on and the server has sent a hidden post's count.
     * Never throws.
     */
    public static boolean hidden(int hidden) {
        return hidden(hidden != 0, HiddenLikeCounts::switchedOn);
    }

    static boolean hidden(boolean hidden, BooleanSupplier on) {
        HookStatus.invoked(FamilyNames.HIDDEN_LIKE_COUNTS);
        try {
            if (!hidden || !countsArrive || !on.getAsBoolean()) return hidden;
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
     * Injected in Instagram's like count reader right after it reads a post's {@code like_count},
     * with the post's data and what it read (an Integer, or null when the server sent none). Only
     * looks: while the switch is on and the post hid its likes, counts whether the number came.
     * Never throws.
     */
    public static void sawCount(@Nullable Object tree, @Nullable Object count) {
        sawCount(tree, count, HiddenLikeCounts::switchedOn, HiddenLikeCounts::likesHidden);
    }

    static void sawCount(@Nullable Object tree, @Nullable Object count, BooleanSupplier on,
                         Function<Object, Boolean> likesHidden) {
        try {
            if (tree == null || !on.getAsBoolean() || !Boolean.TRUE.equals(likesHidden.apply(tree))) return;
            if (count instanceof Integer && (Integer) count > 0) {
                countsArrive = true;
                HookStatus.counted(FamilyNames.HIDDEN_LIKE_COUNTS, CAME_WITH_COUNT);
            } else {
                HookStatus.counted(FamilyNames.HIDDEN_LIKE_COUNTS, NO_COUNT);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HIDDEN_LIKE_COUNTS, COUNT_READ, t);
        }
    }

    private static Boolean likesHidden(Object tree) {
        return flag(tree, LIKES_HIDDEN_KEY);
    }

    /**
     * Filled in by the patch: the boolean {@code tree} keeps under {@code key}, read the way the
     * like count reader reads its own, or null unpatched.
     */
    @Nullable
    static Boolean flag(Object tree, int key) {
        return null;
    }

    /** Forgets that a count has arrived, for tests. */
    static void reset() {
        countsArrive = false;
        logged = false;
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.SHOW_HIDDEN_LIKE_COUNTS.get();
    }
}
