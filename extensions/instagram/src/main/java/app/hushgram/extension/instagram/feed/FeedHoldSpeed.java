/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

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
 */
public final class FeedHoldSpeed {
    /** Each side's strip as a share of the video's width: half, so a hold anywhere on it counts. */
    static final double STRIP = 0.5d;

    private static final String HOOK = "feed hold for 2x flag";

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

    /** Each side's strip as a share of the video's width: {@link #STRIP} while the switch is on, otherwise Instagram's. Never throws. */
    public static double strip(double answer) {
        try {
            HookStatus.invoked(FamilyNames.FEED_HOLD_FAST);
            return on() ? STRIP : answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HOLD_FAST, HOOK, failure);
            return answer;
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
}
