/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.widget.SeekBar;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.feed.SeekShapes;
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
 * <p>A reel that's a photo with a song plays through the same player, but its post states no length,
 * so Instagram leaves it without a bar. On its own switch, {@link #photoReel} and {@link #photoRow} say
 * yes to the bar for that kind of reel alone, and {@link #photoProgress} gives its bar the length the
 * player reports.
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

    /** The name of Instagram's kind of reel for a photo with a song. Redex keeps an enum's names. */
    static final String PHOTO_WITH_MUSIC = "PHOTO_WITH_MUSIC";

    /** How many of the latest photo reels are remembered: more than Instagram keeps loaded around the one showing. */
    private static final int PHOTOS_KEPT = 64;

    /** The container tags of the latest reels that are a photo with a song. */
    private static final Map<String, Boolean> PHOTOS = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > PHOTOS_KEPT;
        }
    };

    /** Whether the seek bar row this thread is working out is a photo's. */
    private static final ThreadLocal<Boolean> ROW_IS_PHOTO = new ThreadLocal<>();

    private static volatile boolean loggedLength;

    private ReelSeekBar() {
    }

    /**
     * Injected after each of Instagram's reads of the shortest ordinary reel that gets the seek
     * bar, in seconds. Answers {@link #SHORTEST_SECONDS} while the switch is on, or the server's
     * value when that's already shorter, and the server's value otherwise.
     */
    public static long minSeconds(long seconds) {
        return minSeconds(seconds, ReelSeekBar::everyReel);
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
        return lazy(enabled, () -> everyReel() || rowIsPhoto());
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
        ReelSeekRoom.keep(bar);
    }

    /**
     * Injected in the binder that ties Instagram's seek bar container to a reel, right after it
     * reads whether the reel is an ad, with the container and that answer as an int (non-zero is
     * an ad). Only a bar in a container bound to an ordinary reel gets the time label.
     */
    public static void bind(Object container, int ad) {
        ReelTimeLabel.bind(container, ad != 0, ReelSeekBar::switchedOn);
    }

    /**
     * Injected in the same binder just before that read, with the container and the reel item it
     * reads. Names the container's reel by the item's id, which Instagram 454 doesn't always tag it with.
     */
    public static void bindReel(Object container, @Nullable Object item) {
        try {
            ReelTimeLabel.bindReel(container, item == null ? null : ReelItemReader.id(item));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek bar reel id", failure);
        }
    }

    /**
     * Whether every reel keeps its bar and time label: Keep a seek bar, or a restyled bar (bigger, or
     * like DM videos), which needs the bar shown.
     */
    static boolean switchedOn() {
        return Utils.settingsReady() && (Settings.REEL_SEEK_BAR.get() || styled());
    }

    /**
     * Whether short reels get a bar: all of the above, or Always show seek bar in clear mode (#480),
     * which wants the bar on a short reel too but leaves the time label out. The length Instagram
     * reads here comes with no reel, so a short reel gets its bar in or out of clear mode.
     */
    static boolean everyReel() {
        return switchedOn() || (Utils.settingsReady() && Settings.CLEAR_MODE_SEEK_BAR.get());
    }

    /** Whether the bar gets a thumb: Round handle on the seek bar, or a restyled bar, which always has one. */
    static boolean thumbOn() {
        return Utils.settingsReady() && !shaped() && (Settings.REEL_SEEK_THUMB.get() || styled());
    }

    /** Whether a {@code SeekShape} draws the bar, which takes the place of the handle and the thicker track. */
    static boolean shaped() {
        return switchedOn() && SeekShapes.changed();
    }

    /**
     * Keeps the post of the reel [id], for the preview's pictures, while the preview's on. The row a
     * reel's bound from may be worked out off the main thread.
     */
    public static void rememberReel(@Nullable Object media, @Nullable String id) {
        if (media != null && id != null) REEL_MEDIA.put(ReelTimeLabel.REEL_TAG_PREFIX + id, new WeakReference<>(media));
    }

    /** The post of the reel tagged [tag], when it's been seen and is still around. */
    @Nullable
    static Object mediaOf(@Nullable String tag) {
        WeakReference<Object> media = tag != null ? REEL_MEDIA.get(tag) : null;
        return media != null ? media.get() : null;
    }

    private static final int REMEMBERED_REELS = 32;
    private static final Map<String, WeakReference<Object>> REEL_MEDIA = Collections.synchronizedMap(
            new LinkedHashMap<String, WeakReference<Object>>(REMEMBERED_REELS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, WeakReference<Object>> eldest) {
                    return size() > REMEMBERED_REELS;
                }
            });

    /** Whether the bar's track is drawn by HushGram: thicker with a dark fade behind it, or like DM videos. */
    static boolean bigOn() {
        return Utils.settingsReady() && !shaped() && styled();
    }

    /** Whether the restyled bar takes the look of a video in a chat, which wins over the bigger bar. */
    static boolean dmOn() {
        return Utils.settingsReady() && Settings.DM_REEL_SEEK_BAR.get();
    }

    private static boolean styled() {
        return Settings.BIG_REEL_SEEK_BAR.get() || Settings.DM_REEL_SEEK_BAR.get();
    }

    /**
     * Whether a reel that's a photo with a song gets a bar (#10). Asked first by the patch's hooks, so
     * nothing works out a reel's kind while this is off.
     */
    public static boolean photoBarOn() {
        return Utils.settingsReady() && Settings.PHOTO_REEL_SEEK_BAR.get();
    }

    /**
     * Injected where Instagram's check of whether a reel gets the bar answers, with that answer as an
     * int (non-zero is yes), the reel's kind and its id, while {@link #photoBarOn}. Instagram goes by
     * the length a reel's post says, and a photo's says none, so a photo with a song never gets one.
     * Answers yes for that kind and Instagram's answer otherwise.
     */
    public static boolean photoReel(int show, Object kind, String id) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            return note(kind, id) || show != 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "photo seek bar", failure);
            return show != 0;
        }
    }

    /**
     * Injected where Instagram's Reels progress controller answers whether a reel's bar shows only while
     * you touch the reel, with that answer as an int (non-zero is yes), the reel's kind and its id, while
     * {@link #photoBarOn}. It says yes to a reel shorter than the minimum, and a photo's length is 0, so it
     * hid a photo's bar each time the reel came on screen. Answers no for a photo with a song and
     * Instagram's answer otherwise.
     */
    public static boolean photoTouchOnly(int hidden, Object kind, String id) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            return !note(kind, id) && hidden != 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "photo seek bar shown", failure);
            return hidden != 0;
        }
    }

    /**
     * Injected first thing where Instagram works out a reel's seek bar row, with the reel's kind and
     * id, while {@link #photoBarOn}. Tells {@link #lazy}, a few lines later in that method on the
     * same thread, which reel it's answering for: with no length, a photo counts as a short reel, and
     * could get the hidden kind that only shows while you hold the reel.
     */
    public static void photoRow(Object kind, String id) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            ROW_IS_PHOTO.set(note(kind, id));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "photo seek bar row", failure);
        }
    }

    /**
     * Injected first thing in the Reels player's progress callback, while {@link #photoBarOn}, with
     * the id of the reel it's playing, the position and the length, both in milliseconds. A photo's
     * post gives its bar a length of 0, so its position would stay at 0, and only the player knows how
     * long the song runs. Video reels pass straight through.
     */
    public static void photoProgress(String id, int position, int length) {
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            if (length > 0 && id != null && isPhoto(ReelTimeLabel.REEL_TAG_PREFIX + id)) {
                ReelTimeLabel.sizeBars(ReelTimeLabel.REEL_TAG_PREFIX + id, length);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "photo seek bar length", failure);
        }
    }

    /** Whether the reel behind {@code tag}, its seek bar container's tag, gets the photo's bar and label. */
    static boolean photoShown(String tag) {
        return tag != null && photoBarOn() && isPhoto(tag);
    }

    /** Notes the reel when its kind is a photo with a song, and says whether it is. */
    private static boolean note(Object kind, String id) {
        if (!(kind instanceof Enum) || !PHOTO_WITH_MUSIC.equals(((Enum<?>) kind).name())) return false;
        if (id != null) {
            synchronized (PHOTOS) {
                PHOTOS.put(ReelTimeLabel.REEL_TAG_PREFIX + id, Boolean.TRUE);
            }
        }
        return true;
    }

    private static boolean isPhoto(String tag) {
        synchronized (PHOTOS) {
            return PHOTOS.containsKey(tag);
        }
    }

    private static boolean rowIsPhoto() {
        return photoBarOn() && Boolean.TRUE.equals(ROW_IS_PHOTO.get());
    }
}
