/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
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
        try {
            if (evaluator == null || !inBuild()) return;
            HookStatus.invoked(FAMILY);
            // Data saver wins while it's saving, whatever this patch's own switch and list say.
            boolean saving = DataSaver.saving();
            PlaybackQuality quality = saving ? PlaybackQuality.DATA_SAVER : chosen();
            if (quality == null || access.customTrack(evaluator) != null) return;
            List<String> labels = labels(access.formats(evaluator));
            String picked = quality.pick(labels);
            if (picked == null) return;
            access.setCustomQuality(evaluator, picked);
            if (access.customTrack(evaluator) == null) {
                // The setter looks the label up the way formatLabel reads it, so this is a stub
                // that reads something else.
                HookStatus.missingMember(FAMILY, "track kept by", "the custom-quality setter", "label");
                return;
            }
            HookStatus.bound(FAMILY, "labelled tracks");
            HookStatus.counted(FAMILY, APPLIED);
            if (saving) DataSaver.countVideo();
            log(quality.fileValue + " started a video at " + picked + " of " + String.join(", ", labels) + ".");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "first choice", failure);
        }
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

    /** Forgets the line count, as a new process would. */
    static void forget() {
        choices.set(0);
    }
}
