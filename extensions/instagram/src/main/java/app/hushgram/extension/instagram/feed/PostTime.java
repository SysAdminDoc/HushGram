/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.text.format.DateUtils;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StoryTime;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Show a post's exact time" patch.
 *
 * <p>Instagram writes how long ago a post went up ("3 hours ago") and how long ago a comment was
 * written ("3h") with one relative formatter, handed the time in seconds by the phone's clock. The
 * patch puts {@link #time} in place of that call where a feed post's footer, a comment's header and
 * a comment row ask for it, on the same arguments: the formatter, the context and the time. While
 * the switch is on it answers the date and time the way a story's exact time does, like
 * Oct 2, 3:45 PM, with the year for another year. Otherwise it hands the call on to Instagram's own
 * formatting, through the stubs the patch fills, so the label is the one Instagram would have made.
 */
public final class PostTime {
    /** Past this many seconds, the time in milliseconds no longer fits a long. */
    private static final long LAST_SECOND = Long.MAX_VALUE / 1000L;

    private PostTime() {
    }

    /**
     * Filled in by the patch: Instagram's own label for [seconds], from the formatting a post's
     * footer or a comment row asked for with the time as a double. Only that formatter may be passed.
     */
    @Nullable
    public static String instagramTime(Object formatter, Context context, double seconds) {
        return null;
    }

    /**
     * Filled in by the patch: Instagram's own label for [seconds], from the formatting a comment's
     * header or row asked for with the time as a long. Only that formatter may be passed.
     */
    @Nullable
    public static String instagramTime(Object formatter, Context context, long seconds) {
        return null;
    }

    /** Whether the exact time is shown: the settings are read and the switch is on, which a pause answers off. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.SHOW_POST_TIME.get();
    }

    /**
     * In place of Instagram's call for a post's or comment's time, given as a double. Answers the
     * date and time while the switch is on, and Instagram's own label otherwise. Never throws for
     * its own sake; Instagram's formatting can still throw as it always could.
     */
    public static String time(Object formatter, Context context, double seconds) {
        String exact = Double.isNaN(seconds) ? null : exact(context, (long) seconds);
        return exact != null ? exact : instagramTime(formatter, context, seconds);
    }

    /** In place of Instagram's call for a post's or comment's time, given as a long. See the other. */
    public static String time(Object formatter, Context context, long seconds) {
        String exact = exact(context, seconds);
        return exact != null ? exact : instagramTime(formatter, context, seconds);
    }

    /**
     * The date and time for a post or comment from [seconds], in the phone's language, time zone
     * and 12 or 24-hour setting, or null for Instagram's own label: the switch is off, HushGram is
     * paused, the settings aren't ready, there's no context, or the time isn't one.
     */
    @Nullable
    static String exact(@Nullable Context context, long seconds) {
        try {
            HookStatus.invoked(FamilyNames.POST_TIME);
            if (context == null || seconds <= 0 || seconds > LAST_SECOND || !on()) return null;
            HookStatus.bound(FamilyNames.POST_TIME, "post time");
            return DateUtils.formatDateTime(context, seconds * 1000L, StoryTime.FORMAT);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.POST_TIME, "post time", failure);
            return null;
        }
    }
}
