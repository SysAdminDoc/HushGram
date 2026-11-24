/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hold for 2x on feed videos" patch (#552).
 *
 * <p>Instagram 454 already plays a feed video at twice its speed while a finger holds it, behind
 * server flags it reads in the feed's press entry, where a press held past the hold delay goes: one
 * for the whole thing, one for each kind of post, one for the speed-up and one for a carousel's. Two
 * more say where a hold counts, a strip along each side that is either a fixed width or a share of
 * the video's width. A hold anywhere else, or with the flags off, opens the post in Reels. The patch
 * answers each yes through {@link #fast(int)}, the fixed width through {@link #fixedStrip(int)} and
 * the share through {@link #strip(double)}: half the width from each side, so the whole video counts.
 * The lift is Instagram's own release, which puts the speed back.
 *
 * <p>Keep 2x after you let go (#552) keeps that speed past the lift. Instagram keeps one flag per feed
 * that says its video plays fast, and puts the speed back through one reset that clears it, which
 * both the press entry and the release call. The release asks {@link #lifted} before its reset, and
 * a yes skips it: the video goes on at 2x and the lift counts as a fast one, so it doesn't open
 * Reels. The patch still puts the post's 2x look to rest, the part of the reset that brings the
 * name over the video back and takes the time off. The next held press resets the speed as it always does and plays fast again, and its lift
 * lets the speed go back. The press entry tells {@link #pressed} before its reset, so a kept flag
 * Instagram has cleared since, because the video went off the screen, isn't taken for a kept one.
 *
 * <p>Show 2x after you let go (#860) asks {@link #showsKept} on a lift that keeps the speed, and a yes hands
 * the post the hold state the press entry hands it, in place of the resting one, so Instagram's 2x and time
 * labels stay on the video while it plays fast. The next held press resets the speed and the look with it.
 *
 * <p>Only after a long hold (#552) keeps the speed only past a hold that played at 2x for
 * {@link #LONG_HOLD_MS}, timed from that press entry, so a quick hold to peek at 2x goes back to
 * normal on the lift.
 *
 * <p>Keep the name while you hold (#552) keeps the post's look through the hold. Once the press
 * entry has the video at 2x, it hands the post its hold state, which hides the name over the video
 * and shows Instagram's 2x and time labels. The patch asks {@link #keepsLook} first, and a yes hands
 * it the resting state instead, so only the speed changes.
 *
 * <p>Hold the middle to pause it (#639) narrows the strips to a third each ({@link #SIDE_STRIP}), so a hold
 * on the left or right third plays at 2x, and {@link FeedTapPause#pressed} takes a hold on the middle third
 * before Instagram sees it: the video pauses while the finger stays down and plays again as it lifts.
 */
public final class FeedHoldSpeed {
    /** Each side's strip as a share of the video's width: half, so a hold anywhere on it counts. */
    static final double STRIP = 0.5d;

    /**
     * Each side's strip with Hold the middle to pause it on (#639): a third, so only a hold on the left or right third
     * plays at 2x, and the middle third is left to {@link FeedTapPause#pressed}, which pauses the video while it's held.
     */
    static final double SIDE_STRIP = 1d / 3d;

    /** Whether a test says this build has the Tap a feed video to pause it patch, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean tapPauseInBuildForTests;

    /** What {@link HookStatus} counts: lifts that kept a feed video at 2x, and lifts after one that let it go back. */
    static final String KEPT = "feed videos kept at 2x";
    static final String LET_GO = "feed videos back to normal speed";

    private static final String HOOK = "feed hold for 2x flag";
    private static final String LOCK_HOOK = "feed 2x lock";

    /** Lifts that went back to normal speed because Only after a long hold wanted a longer hold. */
    static final String SHORT = "short feed holds back to normal speed";

    /** Holds that kept the post's look, the name over the video included, for Keep the name while you hold. */
    static final String LOOK_KEPT = "feed holds that kept the name";

    /** Lifts that kept a feed video at 2x and left Instagram's 2x and time labels on it, for Show 2x after you let go. */
    static final String BADGE_SHOWN = "feed videos showing 2x after the lift";

    /**
     * How long a hold plays at 2x before Only after a long hold keeps it (#552). The press entry comes
     * after Instagram's hold delay, so with that it's about 3 seconds of finger on the video.
     */
    static final long LONG_HOLD_MS = 2500L;

    /** The flags a lift has kept at 2x. Weak, so a feed that goes away takes its flag with it. */
    private static final Set<AtomicBoolean> KEPT_FLAGS = Collections.newSetFromMap(new WeakHashMap<>());

    /** When each feed's held press last started, by its flag, guarded by {@link #KEPT_FLAGS}. */
    private static final Map<AtomicBoolean, Long> PRESSED_AT = new WeakHashMap<>();

    /** The clock a hold is timed by; a test steps its own. */
    static volatile LongSupplier clock = SystemClock::uptimeMillis;

    /** Whether a test says this build has the patch, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    private FeedHoldSpeed() {
    }

    /**
     * Whether a held feed video plays fast: yes while the switch is on, otherwise Instagram's answer
     * ({@code answer} nonzero for yes). Never throws.
     */
    public static boolean fast(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.FEED_HOLD_FAST);
            return instagram || on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HOLD_FAST, HOOK, failure);
            return instagram;
        }
    }

    /**
     * Whether the strip along each side is a fixed width: no while the switch is on, so
     * {@link #strip(double)} sets it, otherwise Instagram's answer. Never throws.
     */
    public static boolean fixedStrip(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.FEED_HOLD_FAST);
            return instagram && !on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HOLD_FAST, HOOK, failure);
            return instagram;
        }
    }

    /**
     * Each side's strip as a share of the video's width: {@link #STRIP} while the switch is on, {@link #SIDE_STRIP}
     * with Hold the middle to pause it on as well, otherwise Instagram's. Never throws.
     */
    public static double strip(double answer) {
        try {
            HookStatus.invoked(FamilyNames.FEED_HOLD_FAST);
            if (!on()) return answer;
            return middlePauses() ? SIDE_STRIP : STRIP;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HOLD_FAST, HOOK, failure);
            return answer;
        }
    }

    /**
     * Told with the feed's fast flag as a held press starts, before Instagram resets the speed for
     * it. A kept flag that's clear again was reset by Instagram on its own, so it's forgotten and
     * this press's lift is a first one. The press's start is noted for Only after a long hold. Never
     * throws.
     */
    public static void pressed(@Nullable AtomicBoolean fast) {
        try {
            HookStatus.invoked(FamilyNames.FEED_HOLD_FAST);
            if (fast == null) return;
            synchronized (KEPT_FLAGS) {
                if (!fast.get()) KEPT_FLAGS.remove(fast);
                PRESSED_AT.put(fast, clock.getAsLong());
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HOLD_FAST, LOCK_HOOK, failure);
        }
    }

    /**
     * Whether the lift that ends a held press keeps the feed video at 2x: yes when the hold sped it
     * up, both switches are on and the speed wasn't already kept from the hold before, which this
     * lift lets go. With Only after a long hold on, the hold must also have played at 2x for
     * {@link #LONG_HOLD_MS}. No lets Instagram reset the speed. Never throws.
     */
    public static boolean lifted(@Nullable AtomicBoolean fast) {
        try {
            HookStatus.invoked(FamilyNames.FEED_HOLD_FAST);
            if (fast == null) return false;
            synchronized (KEPT_FLAGS) {
                Long pressedAt = PRESSED_AT.remove(fast);
                if (!fast.get()) {
                    KEPT_FLAGS.remove(fast);
                    return false;
                }
                if (KEPT_FLAGS.remove(fast)) {
                    HookStatus.counted(FamilyNames.FEED_HOLD_FAST, LET_GO);
                    return false;
                }
                if (!keeps()) return false;
                if (longOnly() && (pressedAt == null || clock.getAsLong() - pressedAt < LONG_HOLD_MS)) {
                    HookStatus.counted(FamilyNames.FEED_HOLD_FAST, SHORT);
                    return false;
                }
                KEPT_FLAGS.add(fast);
            }
            HookStatus.counted(FamilyNames.FEED_HOLD_FAST, KEPT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HOLD_FAST, LOCK_HOOK, failure);
            return false;
        }
    }

    /**
     * Whether a held press that has the feed video at 2x keeps the post's look, the name over the
     * video included: yes with Hold for 2x on feed videos and Keep the name while you hold both on.
     * No lets Instagram show its hold look. Never throws.
     */
    public static boolean keepsLook() {
        try {
            HookStatus.invoked(FamilyNames.FEED_HOLD_FAST);
            if (!on() || !Settings.HOLD_FEED_VIDEO_KEEP_NAME.get()) return false;
            HookStatus.counted(FamilyNames.FEED_HOLD_FAST, LOOK_KEPT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HOLD_FAST, LOCK_HOOK, failure);
            return false;
        }
    }

    /**
     * Whether a lift that keeps a feed video at 2x hands the post its hold look, Instagram's 2x and time labels,
     * instead of putting it to rest: yes with Hold for 2x on feed videos, Keep 2x after you let go and Show 2x after
     * you let go on. Never throws.
     */
    public static boolean showsKept() {
        try {
            HookStatus.invoked(FamilyNames.FEED_HOLD_FAST);
            if (!keeps() || !Settings.HOLD_FEED_VIDEO_KEEP_BADGE.get()) return false;
            HookStatus.counted(FamilyNames.FEED_HOLD_FAST, BADGE_SHOWN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HOLD_FAST, LOCK_HOOK, failure);
            return false;
        }
    }

    /**
     * Whether a held press on a feed video is Instagram's to speed up: the patch is in and its switch
     * on. {@link FeedTapPause} leaves such a press alone then. Never throws.
     */
    static boolean holding() {
        try {
            Boolean forced = inBuildForTests;
            return (forced != null ? forced : SettingsStatus.feedHoldFast()) && on();
        } catch (Throwable failure) {
            return false;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.HOLD_FEED_VIDEO_FAST.get();
    }

    /**
     * Whether a hold on the middle third of a feed video pauses it (#639): Hold for 2x on in a build with it, Hold the
     * middle to pause it on, and the Tap a feed video to pause it patch in the build, whose press hook does the pause.
     * Never throws.
     */
    static boolean middlePauses() {
        try {
            if (!holding() || !Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.get()) return false;
            Boolean forced = tapPauseInBuildForTests;
            return forced != null ? forced : SettingsStatus.feedTapPause();
        } catch (Throwable failure) {
            return false;
        }
    }

    private static boolean keeps() {
        return on() && Settings.HOLD_FEED_VIDEO_KEEP.get();
    }

    private static boolean longOnly() {
        return Settings.HOLD_FEED_VIDEO_KEEP_LONG.get();
    }
}
