/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.text.format.DateUtils;

import androidx.annotation.Nullable;

import java.util.Calendar;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Show a story's exact time" patch.
 *
 * <p>Instagram labels a story with how long ago it went up ("3h") through one method of the story
 * item. That method takes the time the story was posted, in seconds by the phone's clock, and hands
 * it to Instagram's relative formatter. The patch asks {@link #label} first, with that time, and
 * the method answers the label it gets back, or goes on to Instagram's own when it gets null.
 *
 * <p>{@link Settings#STORY_TIME_MODE} picks what the label says: the date and time, the time left
 * before the story expires a day after it went up, or only the time of day it went up. A story
 * that's already a day old has no time left, so that mode shows the date and time for it, and the
 * time of day alone reads right only for a story posted today, so that mode shows the date and
 * time for one posted on an earlier day.
 *
 * <p>Both story headers ask the story item for its label. One of them picks a second relative
 * formatter instead while one of Instagram's server flags is on, so the patch passes that flag's
 * answer through {@link #relativeHeader}, which answers off while the exact time is shown.
 */
public final class StoryTime {
    /** The date, with the month short, and the time. The year shows only for another year. */
    public static final int FORMAT = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH;

    /** How long a story stays up, in milliseconds. */
    static final long DAY = 24L * 60 * 60 * 1000;

    private static final long MINUTE = 60L * 1000;

    /** Past this many seconds, the time in milliseconds no longer fits a long. */
    private static final long LAST_SECOND = Long.MAX_VALUE / 1000L;

    private StoryTime() {
    }

    /** Whether the exact time is shown: the settings are read and the switch is on, which a pause answers off. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.SHOW_STORY_TIME.get();
    }

    /**
     * Injected in the story item's time label, in front of Instagram's own formatting, with the time
     * the story was posted in seconds. Answers the time the way the chosen mode says, in the phone's
     * language, time zone and 12 or 24-hour setting, while the switch is on. Answers null, for
     * Instagram's own label, while it's off, HushGram is paused, the settings aren't ready or the
     * time isn't one. Never throws.
     */
    @Nullable
    public static String label(long seconds) {
        try {
            HookStatus.invoked(FamilyNames.STORY_TIME);
            if (seconds <= 0 || seconds > LAST_SECOND || !on()) return null;
            Context context = Utils.getContext();
            return context == null ? null
                    : text(context, Settings.STORY_TIME_MODE.get(), seconds * 1000L, System.currentTimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_TIME, "story time label", failure);
            return null;
        }
    }

    /**
     * What a story posted at [posted] says at [now], both in milliseconds, in [mode]. A story the
     * clock says went up later than now, which a phone clock a little behind the server's can
     * give, counts as just posted.
     */
    static String text(Context context, @Nullable StoryTimeMode mode, long posted, long now) {
        long age = Math.max(0L, now - posted);
        if (mode == StoryTimeMode.TIME_LEFT && age < DAY) return timeLeft(DAY - age);
        if (mode == StoryTimeMode.TIME_POSTED && age < DAY && sameDay(posted, Math.max(now, posted))) {
            return DateUtils.formatDateTime(context, posted, DateUtils.FORMAT_SHOW_TIME);
        }
        return DateUtils.formatDateTime(context, posted, FORMAT);
    }

    /** Whether [one] and [other], in milliseconds, fall on the same day by the phone's clock and time zone. */
    static boolean sameDay(long one, long other) {
        Calendar first = Calendar.getInstance();
        first.setTimeInMillis(one);
        Calendar second = Calendar.getInstance();
        second.setTimeInMillis(other);
        return first.get(Calendar.ERA) == second.get(Calendar.ERA) && first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * [left] milliseconds as hours and minutes left, rounded up to the minute, so the last seconds
     * still say a minute rather than none.
     */
    static String timeLeft(long left) {
        long minutes = (left + MINUTE - 1) / MINUTE;
        long hours = minutes / 60;
        return hours > 0 ? L10n.f("%1$dh %2$dm left", hours, minutes % 60)
                : L10n.quantity(minutes, "1m left", "%1$dm left", minutes);
    }

    /**
     * Injected right after a story header reads the server flag that has it format the time with
     * its second relative formatter, with the flag's answer as an int. Answers off while the exact
     * time is shown, so the header asks the story item for its label, and Instagram's answer
     * otherwise. Never throws.
     */
    public static boolean relativeHeader(int answer) {
        boolean instagram = answer != 0;
        try {
            return instagram && !on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_TIME, "story header flag", failure);
            return instagram;
        }
    }
}
