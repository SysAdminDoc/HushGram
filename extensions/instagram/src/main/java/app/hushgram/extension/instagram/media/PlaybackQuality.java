/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * The quality a video starts playing at: Instagram's own choice, the lowest rung, a ceiling, or the
 * highest rung.
 *
 * <p>A rung is one of the qualities Instagram's player can switch between for a video, and it's
 * counted by the label Meta's manifest gives it, such as {@code 720p}. A label isn't a picture size:
 * a reel's 270p to 720p rungs can all be 720x1280 at rising bit rates. A ceiling is a wish, not a
 * condition, the same as the Download quality's: it takes the best rung at or under it, and a video
 * with nothing that low plays the lowest rung above it.
 *
 * <p>It holds no Android type and reads no setting, and the name a person reads is the settings
 * screen's, in the phone's language.
 */
public enum PlaybackQuality {
    AUTO(-1, "auto"),
    DATA_SAVER(0, "data_saver"),
    P480(480, "480p"),
    P720(720, "720p"),
    HIGHEST(Integer.MAX_VALUE, "highest");

    /** The highest rung this choice wants: MAX_VALUE for the highest, 0 for the lowest, -1 for Instagram's. */
    final int ceiling;

    /** What a settings file holds for this choice, and what a log line calls it. It never changes once written. */
    public final String fileValue;

    PlaybackQuality(int ceiling, String fileValue) {
        this.ceiling = ceiling;
        this.fileValue = fileValue;
    }

    /** The choice a settings file names, or null when it names none this build knows. */
    @Nullable
    public static PlaybackQuality fromFile(@Nullable Object value) {
        if (!(value instanceof String)) return null;
        for (PlaybackQuality quality : values()) {
            if (quality.fileValue.equals(value)) return quality;
        }
        return null;
    }

    /**
     * The label among [labels] this choice plays, or null for {@link #AUTO} and when no label states
     * a quality. A null or unreadable label is skipped: audio tracks have none.
     */
    @Nullable
    public String pick(@Nullable Iterable<String> labels) {
        return this == AUTO ? null : atMost(labels, ceiling);
    }

    /**
     * The best label among [labels] at or under [ceiling], or the lowest above it when none is that
     * low; null when no label states a quality.
     */
    @Nullable
    static String atMost(@Nullable Iterable<String> labels, int ceiling) {
        if (labels == null) return null;
        String picked = null;
        int pickedQuality = 0;
        for (String label : labels) {
            int quality = qualityOf(label);
            if (quality <= 0) continue;
            if (picked == null || better(quality, pickedQuality, ceiling)) {
                picked = label;
                pickedQuality = quality;
            }
        }
        return picked;
    }

    /**
     * The best rung among [labels] under [label]'s, or null when nothing is lower or [label] states
     * no quality.
     */
    @Nullable
    static String below(@Nullable Iterable<String> labels, @Nullable String label) {
        int from = qualityOf(label);
        if (labels == null || from <= 0) return null;
        String below = null;
        int belowQuality = 0;
        for (String other : labels) {
            int quality = qualityOf(other);
            if (quality > belowQuality && quality < from) {
                below = other;
                belowQuality = quality;
            }
        }
        return below;
    }

    /** Whether a rung of quality [a] suits [ceiling] better than one of [b]. */
    private static boolean better(int a, int b, int ceiling) {
        boolean fitsA = a <= ceiling;
        boolean fitsB = b <= ceiling;
        if (fitsA != fitsB) return fitsA;
        return fitsA ? a > b : a < b;
    }

    /** The number in a label such as {@code 720p}, or 0 for anything else. DashManifest reads a track's label the same way. */
    static int qualityOf(@Nullable String label) {
        if (label == null) return 0;
        String text = label.trim().toLowerCase(Locale.US);
        int digits = text.length() - 1;
        if (digits < 3 || digits > 4 || text.charAt(digits) != 'p') return 0;
        int quality = 0;
        for (int i = 0; i < digits; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return 0;
            quality = quality * 10 + (c - '0');
        }
        return quality;
    }
}
