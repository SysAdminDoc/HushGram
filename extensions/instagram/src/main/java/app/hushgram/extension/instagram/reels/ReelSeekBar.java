/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.widget.SeekBar;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Keep a seek bar on Reels" patch.
 *
 * <p>Instagram 449 puts its seek bar under an ordinary reel only when the reel is at least as long
 * as a server setting says. A shorter one gets no bar, or one that stays hidden until you hold the
 * reel. The patch hands each of Instagram's reads of that setting for ordinary reels to
 * {@link #minSeconds}, which answers one second while the switch is on, and its read of whether
 * ordinary reels get the hidden kind to {@link #lazy}, which answers no. Ads have settings of
 * their own, read in the same places, and the patch leaves those reads alone.
 *
 * <p>The bar shows the time only while you drag it. {@link #progress} runs with every change of
 * the bar's position or length and keeps a label of the time played and the reel's length beside
 * it ({@link ReelTimeLabel}). Instagram uses the same bar for ads and could use it elsewhere, so
 * {@link #bind} hears from the binder that ties a seek bar container to a reel whether that reel
 * is an ad, and only a bar in a container bound to an ordinary reel gets the label.
 *
 * <p>Off, paused, before the settings are read, or when anything goes wrong, Instagram's own
 * answers stand and the label is hidden. None of the hooks throws.
 */
public final class ReelSeekBar {
    /**
     * The shortest ordinary reel, in seconds, that gets the bar while the switch is on. A reel whose
     * length Instagram doesn't know comes as 0 seconds, so it still gets none.
     */
    static final long SHORTEST_SECONDS = 1L;

    private static volatile boolean loggedLength;

    private ReelSeekBar() {
    }

    /**
     * Injected after each of Instagram's reads of the shortest ordinary reel that gets the seek
     * bar, in seconds. Answers {@link #SHORTEST_SECONDS} while the switch is on, or the server's
     * value when that's already shorter, and the server's value otherwise.
     */
    public static long minSeconds(long seconds) {
        return minSeconds(seconds, ReelSeekBar::switchedOn);
    }

    static long minSeconds(long seconds, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            if (!on.getAsBoolean()) return seconds;
            if (!loggedLength) {
                loggedLength = true;
                Logger.printDebug(() -> "Reel seek bar: shortest reel with a bar answered "
                        + Math.min(seconds, SHORTEST_SECONDS) + " s in place of " + seconds + " s");
            }
            return Math.min(seconds, SHORTEST_SECONDS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek bar length", failure);
            return seconds;
        }
    }

    /**
     * Injected after Instagram's read of whether a short ordinary reel gets the seek bar that
     * stays hidden until you hold the reel, with Instagram's answer as an int (non-zero is yes).
     * Answers no while the switch is on, and Instagram's answer otherwise.
     */
    public static boolean lazy(int enabled) {
        return lazy(enabled, ReelSeekBar::switchedOn);
    }

    static boolean lazy(int enabled, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            return !on.getAsBoolean() && enabled != 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "hidden seek bar", failure);
            return enabled != 0;
        }
    }

    /**
     * Injected first thing in the reel seek bar's onProgressChanged, with the bar and its
     * position in milliseconds. Shows or updates the bar's time label, or hides it.
     */
    public static void progress(SeekBar bar, int progress) {
        ReelTimeLabel.update(bar, progress, ReelSeekBar::switchedOn);
    }

    /**
     * Injected in the binder that ties Instagram's seek bar container to a reel, right after it
     * reads whether the reel is an ad, with the container and that answer as an int (non-zero is
     * an ad). Only a bar in a container bound to an ordinary reel gets the time label.
     */
    public static void bind(Object container, int ad) {
        ReelTimeLabel.bind(container, ad != 0, ReelSeekBar::switchedOn);
    }

    /** Whether every reel keeps its bar and time label: Keep a seek bar, or the bigger bar, which needs the bar shown. */
    static boolean switchedOn() {
        return Utils.settingsReady() && (Settings.REEL_SEEK_BAR.get() || Settings.BIG_REEL_SEEK_BAR.get());
    }

    /** Whether the bar gets a thumb: Show a Reel seek thumb, or the bigger bar, which always has one. */
    static boolean thumbOn() {
        return Utils.settingsReady() && (Settings.REEL_SEEK_THUMB.get() || Settings.BIG_REEL_SEEK_BAR.get());
    }

    /** Whether the bar is restyled thicker with a dark fade behind it. */
    static boolean bigOn() {
        return Utils.settingsReady() && Settings.BIG_REEL_SEEK_BAR.get();
    }
}
