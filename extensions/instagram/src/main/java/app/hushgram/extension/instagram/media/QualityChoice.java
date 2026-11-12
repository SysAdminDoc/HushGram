/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Default playback quality: videos, reels and video stories start at the quality picked in
 * HushGram's settings.
 *
 * <p>Instagram's player chooses a video's rung with one DASH format evaluator per track group. The
 * evaluator's custom-quality setter takes a label such as {@code 720p}, finds the track with that
 * label and keeps to it from then on, or chooses by Instagram's bandwidth estimate when no track has
 * it. Instagram itself calls that setter only from its internal video debug overlay; no menu a
 * person sees reaches it.
 *
 * <p>So the patch tells {@link #firstChoice} about the evaluator the first time it chooses a track,
 * right after it keeps the tracks it chooses among, and while the switch is on and a quality is
 * chosen, this hands the setter the one of the video's own labels the choice plays
 * ({@link PlaybackQuality#pick}). Tracks with no labels, such as a video's sound, are left to
 * Instagram, and so is an evaluator that already keeps to a track.
 *
 * <p>A kept track skips Instagram's bandwidth chooser altogether, so on a connection too slow for it
 * the video stalls again and again (#599). The patch also tells {@link #eachChoice} about every
 * choice, with the playback position and how much is buffered. When a track this kept keeps
 * loading slower than it plays while little is buffered, the next choice takes the rung below, and
 * so on while it still can't keep up. For {@link #HELD_DOWN_MS} after a step down, new videos start
 * no higher than the rung it took, so a slow connection stalls one video and not each one, and then
 * at the chosen quality again. Data saver's lowest rung has nothing below it to take.
 *
 * <p>It fails open: with the patch not in the build, the switch off, HushGram paused, the settings
 * not ready yet, Auto chosen, or a failure in here, Instagram picks the quality as it plays. A
 * change takes effect for the next video that starts.
 */
public final class QualityChoice {
    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "QualityChoice";

    /** What every line of this hook starts with, for a person reading the log. */
    static final String PREFIX = "Playback quality: ";

    /** Counted under the patch's name for each video that started at the chosen quality. */
    static final String APPLIED = "video started at the chosen quality";

    /** Counted under the patch's name for each step down to the rung below. */
    static final String STEPPED_DOWN = "video stepped down a rung to keep playing";

    /** Less buffered than this, in microseconds, while the kept track loads slower than it plays, and a stall is coming. */
    static final long LOW_BUFFER_US = 3_000_000L;

    /** Choices in a row that fall behind before the next one steps down. */
    static final int BEHIND_IN_A_ROW = 2;

    /** How far past the time between two choices the position may move before it counts as a seek. */
    static final long SEEK_SLACK_US = 1_000_000L;

    /** How far short of the time between two choices the load may come before it counts as behind: the clock and the position tick a few milliseconds apart. */
    static final long KEEP_UP_SLACK_US = 250_000L;

    /** How long after a step down new videos start no higher than the rung it took. */
    static final long HELD_DOWN_MS = 120_000L;

    /** Choices logged one by one before the log only counts them. */
    static final int LOGGED_ONE_BY_ONE = 40;

    /** After those, one line per this many choices. */
    static final int SUMMED_UP_BY = 50;

    private static final String FAMILY = FamilyNames.PLAYBACK_QUALITY;

    /** What this class reads from and hands to an evaluator. {@link #PATCHED} is the patch's; tests stand in. */
    interface Evaluator {
        /** The id of the track the evaluator keeps to, or null while it chooses by bandwidth. */
        @Nullable
        String customTrack(Object evaluator);

        /** The tracks the evaluator chooses among, or null before it has them. */
        @Nullable
        Object[] formats(Object evaluator);

        /** A track's quality label, such as 720p, or null when it has none. */
        @Nullable
        String label(Object format);

        /** Hands [label] to the evaluator's custom-quality setter. */
        void setCustomQuality(Object evaluator, String label);
    }

    static final Evaluator PATCHED = new Evaluator() {
        @Override
        public String customTrack(Object evaluator) {
            return QualityReader.customTrack(evaluator);
        }

        @Override
        public Object[] formats(Object evaluator) {
            return QualityReader.trackFormats(evaluator);
        }

        @Override
        public String label(Object format) {
            return QualityReader.formatLabel(format);
        }

        @Override
        public void setCustomQuality(Object evaluator, String label) {
            QualityReader.setCustomQuality(evaluator, label);
        }
    };

    static volatile Evaluator access = PATCHED;

    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /** The quality a test chooses instead of the saved one. The switch is still asked. */
    @Nullable
    static volatile PlaybackQuality qualityForTests;

    /** How many choices this made, for the log. */
    private static final AtomicInteger choices = new AtomicInteger();

    /** The evaluators this kept to a track, weakly, so a finished video's goes when Instagram drops it. */
    private static final Map<Object, Pin> pins = Collections.synchronizedMap(new WeakHashMap<>());

    /** The quality the last step down took, 0 before any, and when, on the {@link #choosing} clock. */
    private static int heldQuality;
    private static long heldAtMs;

    /** A track this kept an evaluator to, and what the evaluator's last choice saw. */
    private static final class Pin {
        /** The label of the rung kept now. */
        String label;
        /** False for data saver's lowest rung, which stays. */
        final boolean stepsDown;
        boolean played;
        int behind;
        long atMs = -1;
        long positionUs;
        long bufferedUs;

        Pin(String label, boolean stepsDown) {
            this.label = label;
            this.stepsDown = stepsDown;
        }

        /**
         * Notes a choice at [nowMs] and answers whether the kept track has fallen behind: the video
         * has played since it started or last stepped down, less than {@link #LOW_BUFFER_US} is buffered, and for {@link #BEHIND_IN_A_ROW}
         * choices in a row loaded short of the time it took by more than {@link #KEEP_UP_SLACK_US}.
         * A choice that loaded under half the time it took is a video already stalling, and counts for
         * the whole row. A position that went back, or moved further than the time allows, is a seek
         * or a loop and starts the count over.
         */
        boolean fallingBehind(long positionUs, long bufferedUs, long nowMs) {
            long lastMs = atMs;
            long lastPositionUs = this.positionUs;
            long lastBufferedUs = this.bufferedUs;
            atMs = nowMs;
            this.positionUs = positionUs;
            this.bufferedUs = bufferedUs;
            if (lastMs < 0) return false;
            long tookUs = (nowMs - lastMs) * 1000L;
            long playedUs = positionUs - lastPositionUs;
            if (playedUs < 0 || playedUs > tookUs + SEEK_SLACK_US) {
                behind = 0;
                return false;
            }
            if (playedUs > 0) played = true;
            long loadedUs = playedUs + bufferedUs - lastBufferedUs;
            boolean fellShort = played && bufferedUs < LOW_BUFFER_US && loadedUs + KEEP_UP_SLACK_US < tookUs;
            behind = !fellShort ? 0 : loadedUs * 2 < tookUs ? BEHIND_IN_A_ROW : behind + 1;
            return behind >= BEHIND_IN_A_ROW;
        }
    }

    private QualityChoice() {
    }

    /** Whether this build carries the patch. */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.defaultPlaybackQuality();
    }

    /**
     * The quality new videos start at: the chosen one while the patch is in, the settings are ready
     * and the switch is on, which a pause answers off. Null for Instagram's own choice.
     */
    @Nullable
    private static PlaybackQuality chosen() {
        if (!inBuild() || !Utils.settingsReady() || !Settings.DEFAULT_PLAYBACK_QUALITY.get()) return null;
        PlaybackQuality forced = qualityForTests;
        PlaybackQuality quality = forced != null ? forced : Settings.PLAYBACK_QUALITY.get();
        return quality == null || quality == PlaybackQuality.AUTO ? null : quality;
    }

    /**
     * Injection point, in the choice of a track of Instagram's DASH format evaluator, right after the
     * evaluator keeps the tracks of its first choice, which happens once for each evaluator. Hands
     * the evaluator's custom-quality setter the label the chosen quality plays, unless the
     * evaluator already keeps to a track. Never throws.
     */
    public static void firstChoice(@Nullable Object evaluator) {
        starting(evaluator, SystemClock.elapsedRealtime());
    }

    /** {@link #firstChoice} at [nowMs], for tests to pass the clock. */
    static void starting(@Nullable Object evaluator, long nowMs) {
        try {
            if (evaluator == null || !inBuild()) return;
            HookStatus.invoked(FAMILY);
            // Data saver wins while it's saving, whatever this patch's own switch and list say.
            boolean saving = DataSaver.saving();
            PlaybackQuality quality = saving ? PlaybackQuality.DATA_SAVER : chosen();
            if (quality == null || access.customTrack(evaluator) != null) return;
            List<String> labels = labels(access.formats(evaluator));
            String wanted = quality.pick(labels);
            if (wanted == null) return;
            int held = saving ? 0 : heldUnder(nowMs);
            String picked = held > 0 && PlaybackQuality.qualityOf(wanted) > held ? PlaybackQuality.atMost(labels, held) : wanted;
            access.setCustomQuality(evaluator, picked);
            if (access.customTrack(evaluator) == null) {
                // The setter looks the label up the way formatLabel reads it, so this is a stub
                // that reads something else.
                HookStatus.missingMember(FAMILY, "track kept by", "the custom-quality setter", "label");
                return;
            }
            HookStatus.bound(FAMILY, "labelled tracks");
            HookStatus.counted(FAMILY, APPLIED);
            pins.put(evaluator, new Pin(picked, !saving));
            if (saving) DataSaver.countVideo();
            log(quality.fileValue + " started a video at " + picked + " of " + String.join(", ", labels)
                    + (picked.equals(wanted) ? "." : ", held under " + held + "p since a step down."));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "first choice", failure);
        }
    }

    /**
     * Injection point, at the top of the same choice, every time the evaluator chooses a track. The
     * range form hands over all of the choice's parameters at once, so the ones between the evaluator
     * and the last three longs come along unread. Of those longs, the first is the playback position
     * and the second how much is buffered, both in microseconds. Never throws.
     */
    public static void eachChoice(@Nullable Object evaluator, Object arg1, Object arg2, Object arg3, Object arg4,
            Object arg5, Object arg6, Object arg7, Object arg8, float arg9,
            long positionUs, long bufferedUs, long arg12) {
        if (evaluator == null || pins.isEmpty()) return;
        choosing(evaluator, positionUs, bufferedUs, SystemClock.elapsedRealtime());
    }

    /** {@link #eachChoice} at [nowMs], for tests to pass the clock. */
    static void choosing(Object evaluator, long positionUs, long bufferedUs, long nowMs) {
        try {
            Pin pin = pins.get(evaluator);
            if (pin == null || !pin.stepsDown) return;
            synchronized (pin) {
                if (!pin.fallingBehind(positionUs, bufferedUs, nowMs)) return;
                String from = pin.label;
                String lower = PlaybackQuality.below(labels(access.formats(evaluator)), from);
                if (lower == null) return;
                access.setCustomQuality(evaluator, lower);
                if (access.customTrack(evaluator) == null) {
                    access.setCustomQuality(evaluator, from);
                    HookStatus.missingMember(FAMILY, "track kept by", "the custom-quality setter", "lower label");
                    return;
                }
                pin.label = lower;
                pin.behind = 0;
                // Instagram chooses again at once for the new rung while the video still waits, so
                // it's judged only once it plays again.
                pin.played = false;
                hold(PlaybackQuality.qualityOf(lower), nowMs);
                HookStatus.counted(FAMILY, STEPPED_DOWN);
                Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> PREFIX + "stepped " + from + " down to "
                        + lower + " with " + bufferedUs / 1000 + " ms buffered at " + positionUs / 1000 + " ms.");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "each choice", failure);
        }
    }

    /** Notes a step down to [quality] at [nowMs]; one still held keeps the lower of the two. */
    private static synchronized void hold(int quality, long nowMs) {
        heldQuality = heldUnder(nowMs) > 0 ? Math.min(heldQuality, quality) : quality;
        heldAtMs = nowMs;
    }

    /** The quality new videos start no higher than at [nowMs], or 0 when no step down is that recent. */
    private static synchronized int heldUnder(long nowMs) {
        return heldQuality > 0 && nowMs - heldAtMs < HELD_DOWN_MS ? heldQuality : 0;
    }

    /** The quality labels of [formats], leaving out the tracks that have none. */
    private static List<String> labels(@Nullable Object[] formats) {
        List<String> labels = new ArrayList<>();
        if (formats == null) return labels;
        for (Object format : formats) {
            if (format == null) continue;
            String label = access.label(format);
            if (PlaybackQuality.qualityOf(label) > 0) labels.add(label);
        }
        return labels;
    }

    /** One line per choice for the first {@link #LOGGED_ONE_BY_ONE}, then one per {@link #SUMMED_UP_BY}. */
    private static void log(String line) {
        int count = choices.incrementAndGet();
        if (count <= LOGGED_ONE_BY_ONE) {
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> PREFIX + line);
        } else if (count % SUMMED_UP_BY == 0) {
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE,
                    () -> PREFIX + count + " videos so far. The last one: " + line);
        }
    }

    /** Forgets the line count, the kept tracks and the last step down, as a new process would. */
    static synchronized void forget() {
        choices.set(0);
        pins.clear();
        heldQuality = 0;
        heldAtMs = 0;
    }
}
