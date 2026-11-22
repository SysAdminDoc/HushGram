/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * How much Blocked words has hidden (#807): the reels, Home posts and comments its words and
 * {@code @name}s took out, over the last 24 hours and in all. The Blocked words row shows it under
 * the count of words.
 *
 * <p>Instagram builds a comment's row again each time it scrolls back into view and reads Home's
 * page more than once, so each thing counts once a run, told apart by its words, and by who posted
 * it when the list names accounts (otherwise the author isn't read at all).
 * The last day is kept as a count per hour, the hours older than a day dropped as new ones come in.
 * Neither count goes into an exported configuration.
 */
public final class CaptionWordStats {
    static final long HOUR = 60L * 60L * 1000L;
    static final int HOURS = 24;

    /** How many hidden things this run remembers having counted. */
    static final int REMEMBERED = 4096;

    private static final Map<String, Boolean> COUNTED = new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED;
        }
    };

    /** The clock the hours are read from; tests set their own. */
    static LongSupplier clock = System::currentTimeMillis;

    private CaptionWordStats() {
    }

    /**
     * Counts one thing the list hid, posted by [author] with [words], unless this run counted the
     * same one already. Before the settings are read nothing is counted. Never throws.
     */
    public static void hidden(@Nullable String author, @Nullable String words) {
        try {
            if (!Utils.settingsReady()) return;
            String identity = (author == null ? "" : author) + '\n' + (words == null ? "" : words);
            synchronized (COUNTED) {
                if (COUNTED.put(identity, Boolean.TRUE) != null) return;
                long hour = clock.getAsLong() / HOUR;
                TreeMap<Long, Long> hours = hours(Settings.CAPTION_WORD_HIDDEN_HOURS.get(), hour);
                hours.merge(hour, 1L, Long::sum);
                Settings.CAPTION_WORD_HIDDEN_HOURS.save(text(hours));
                Settings.CAPTION_WORD_HIDDEN_TOTAL.save(Math.max(0L, Settings.CAPTION_WORD_HIDDEN_TOTAL.get()) + 1L);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Caption word stats: couldn't count a hidden item", failure);
        }
    }

    /** How many things the list hid in the 24 hours up to now. */
    static long lastDay() {
        long total = 0;
        for (long count : hours(Settings.CAPTION_WORD_HIDDEN_HOURS.get(), clock.getAsLong() / HOUR).values()) total += count;
        return total;
    }

    /** How many things the list has hidden in all. */
    static long allTime() {
        return Math.max(0L, Settings.CAPTION_WORD_HIDDEN_TOTAL.get());
    }

    /**
     * The line the Blocked words row shows under its count of words, or null while the list has
     * hidden nothing or the counts can't be read.
     */
    @Nullable
    public static String summary() {
        try {
            if (!Utils.settingsReady()) return null;
            long all = allTime();
            if (all == 0) return null;
            return String.format(Locale.getDefault(), L10n.t("%1$d hidden in the last 24 hours, %2$d in all"), lastDay(), all);
        } catch (Throwable failure) {
            Logger.printException(() -> "Caption word stats: couldn't read the counts", failure);
            return null;
        }
    }

    /** The counts per hour in [stored], "hour:count" pairs between commas, without those older than a day before [now]. */
    static TreeMap<Long, Long> hours(@Nullable String stored, long now) {
        TreeMap<Long, Long> hours = new TreeMap<>();
        if (stored == null || stored.isEmpty()) return hours;
        for (String pair : stored.split(",")) {
            int colon = pair.indexOf(':');
            if (colon <= 0) continue;
            try {
                long hour = Long.parseLong(pair.substring(0, colon).trim());
                long count = Long.parseLong(pair.substring(colon + 1).trim());
                if (hour > now - HOURS && hour <= now && count > 0) hours.merge(hour, count, Long::sum);
            } catch (NumberFormatException ignored) {
                // A pair that isn't two numbers is dropped; the rest still count.
            }
        }
        return hours;
    }

    static String text(TreeMap<Long, Long> hours) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Long, Long> hour : hours.entrySet()) {
            if (text.length() > 0) text.append(',');
            text.append(hour.getKey()).append(':').append(hour.getValue());
        }
        return text.toString();
    }

    /** Forgets what this run counted and puts the real clock back. Tests only. */
    static void resetForTests() {
        synchronized (COUNTED) {
            COUNTED.clear();
        }
        clock = System::currentTimeMillis;
    }
}
