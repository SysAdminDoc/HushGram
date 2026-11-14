/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.feed.FeedDoubleTapSkip;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Double tap to skip on Reels" patch (#554).
 *
 * <p>Instagram 454 already skips through a reel on a double tap, behind a server flag it asks for
 * each reel: a double tap in the strip along the left edge goes back and one along the right edge
 * goes ahead. Two more server numbers say how far, in seconds, and how wide each strip is, as a
 * share of the reel's width. A double tap between the strips is Instagram's like, and a reel no
 * longer than the skip gets no skip. The patch answers the yes or no through {@link #skip(int)},
 * the seconds through {@link #seconds(float)} and the strips through {@link #edge(float)}, with a
 * double version of each number for the builds that read them as doubles.
 *
 * <p>Instagram shows nothing when it skips. Right after a skip the gesture navigator hands the way it
 * went, its FORWARD or BACKWARD, to {@link #skipped(Object)}, and while Show the skip arrows is on
 * the feed's arrows flash on that side of the screen with the seconds under them (#691).
 */
public final class ReelSkip {
    /**
     * How wide each strip is when Instagram's own number isn't one it could use: a third of the
     * reel, leaving the middle third for the like.
     */
    static final float EDGE = 1f / 3f;

    private static final String HOOK = "reel double tap skip flag";
    private static final String ARROWS_HOOK = "reel double tap skip arrows";
    static final String COUNT_ARROWS = "reel skip arrows shown";

    /** The way a skip went that the debug log has named, once a run. */
    private static volatile boolean loggedOther;

    private ReelSkip() {
    }

    /**
     * After a double tap skipped a reel, {@code direction} being the way it went: while the switch and
     * Show the skip arrows are on, the feed's arrows flash on that side of the screen, over the reel,
     * with the seconds Skip length moves under them. Instagram calls it on the main thread, from the
     * double tap. Never throws.
     */
    public static void skipped(@Nullable Object direction) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DOUBLE_TAP_SKIP);
            if (!on() || !Settings.REEL_SKIP_ARROWS.get()) return;
            int side = side(direction);
            if (side == 0) {
                if (!loggedOther) {
                    loggedOther = true;
                    Logger.printInfo(() -> "Reel double tap skip: no side for " + direction);
                }
                return;
            }
            Activity activity = Utils.getActivity();
            View content = activity == null ? null : activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            FeedDoubleTapSkip.flash((FrameLayout) content, side, Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.get());
            HookStatus.counted(FamilyNames.REEL_DOUBLE_TAP_SKIP, COUNT_ARROWS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOUBLE_TAP_SKIP, ARROWS_HOOK, failure);
        }
    }

    /**
     * -1 for a skip back, which a double tap on the left strip makes, 1 for one ahead, from the
     * right strip, and 0 for anything else. Instagram's way is an enum whose constants keep their
     * names, FORWARD and BACKWARD, through Redex.
     */
    static int side(@Nullable Object direction) {
        if (!(direction instanceof Enum)) return 0;
        String name = ((Enum<?>) direction).name();
        if ("BACKWARD".equals(name)) return -1;
        if ("FORWARD".equals(name)) return 1;
        return 0;
    }

    /**
     * Whether a reel gets double tap to skip: yes while the switch is on, otherwise Instagram's
     * answer ({@code answer} nonzero for yes). Never throws.
     */
    public static boolean skip(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.REEL_DOUBLE_TAP_SKIP);
            return instagram || on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOUBLE_TAP_SKIP, HOOK, failure);
            return instagram;
        }
    }

    /**
     * How many seconds a double tap skips: Skip length's (#645, 10 to start, #554 asked for 10) while the
     * switch is on, otherwise Instagram's. Never throws.
     */
    public static float seconds(float answer) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DOUBLE_TAP_SKIP);
            return on() ? chosen() : answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOUBLE_TAP_SKIP, HOOK, failure);
            return answer;
        }
    }

    /** {@link #seconds(float)} for a build that reads the number as a double. */
    public static double secondsDouble(double answer) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DOUBLE_TAP_SKIP);
            return on() ? chosen() : answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOUBLE_TAP_SKIP, HOOK, failure);
            return answer;
        }
    }

    /**
     * How wide each strip is, as a share of the reel's width. While the switch is on: Instagram's
     * own number when it leaves a middle for the like (above 0 and under a half), otherwise
     * {@link #EDGE}. Off, Instagram's. Never throws.
     */
    public static float edge(float answer) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DOUBLE_TAP_SKIP);
            if (!on()) return answer;
            Logger.printDebug(() -> "Reel double tap skip: Instagram's strip is " + answer);
            return answer > 0f && answer < 0.5f ? answer : EDGE;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOUBLE_TAP_SKIP, HOOK, failure);
            return answer;
        }
    }

    /** {@link #edge(float)} for a build that reads the number as a double. */
    public static double edgeDouble(double answer) {
        try {
            HookStatus.invoked(FamilyNames.REEL_DOUBLE_TAP_SKIP);
            if (!on()) return answer;
            return answer > 0d && answer < 0.5d ? answer : EDGE;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOUBLE_TAP_SKIP, HOOK, failure);
            return answer;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.DOUBLE_TAP_SKIP_REELS.get();
    }

    private static float chosen() {
        return Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.get();
    }
}
