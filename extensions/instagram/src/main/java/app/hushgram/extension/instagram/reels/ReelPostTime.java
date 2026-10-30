/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.content.Context;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StoryTime;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Show a reel's exact time" patch (#172).
 *
 * <p>The Reels viewer draws the author's name row and the line under it as one column of parts,
 * built again each time a reel is shown: the name row, then a subtitle such as the audio when the
 * reel has one. Instagram makes that subtitle from a small state object that says what it shows.
 * The patch hands the column's list of parts to {@link #line} just before the column is made, with
 * the title the list belongs to. While the switch is on and the reel's post says when it went up,
 * this adds one more subtitle to the end of the list, made by Instagram's own subtitle maker from a
 * {@link Line}, a state of HushGram's own that holds the words. Where Instagram picks what to draw
 * for a state, the patch asks {@link #lineText} first, and draws those words as Instagram's plain
 * subtitle text, in its type and color, with no icon.
 *
 * <p>The line is a part of its own under the name row and the native subtitle, so the name, the
 * Follow button, the audio and the caption keep the room they had. Nothing is kept between reels:
 * the list is new for each title, so a reel with no time gets no line whatever the reel before it
 * had.
 *
 * <p>The time is the post's taken_at, the server's own posting time in seconds, written the way a
 * post's exact time is: the date and the time in the phone's language, time zone and 12 or 24-hour
 * setting, like Oct 2, 3:45 PM, with the year for another year. {@link Settings#REEL_POST_TIME_SECONDS}
 * adds the seconds, like Oct 2, 3:45:30 PM, and {@link Settings#REEL_POST_TIME_AGO} how long ago that
 * was, like 1d 2h 5m ago (#195), on a line of its own under the time, worked out as the reel's title
 * is made. A time that
 * is zero, negative, too large to be one, or more than a day past the phone's clock isn't a posting
 * time, and gets no line.
 *
 * <p>Off, paused, before the settings are read, or when anything throws, the list is left as
 * Instagram made it.
 */
public final class ReelPostTime {
    /** How far past the phone's clock a posting time may be, in milliseconds, and still be one: clocks differ, but not by days. */
    static final long DAY = 24L * 60 * 60 * 1000;

    /** Past this many seconds, the time in milliseconds no longer fits a long. */
    private static final long LAST_SECOND = Long.MAX_VALUE / 1000L;

    /** The date alone, as {@link StoryTime#FORMAT} writes it. */
    private static final int DATE = StoryTime.FORMAT & ~DateUtils.FORMAT_SHOW_TIME;

    private static final long MINUTE = 60L * 1000;

    /** What a tap on the line does: nothing. Instagram's subtitle text has to be given a listener. */
    private static final View.OnClickListener NO_CLICK = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
        }
    };

    /**
     * The state of the line's subtitle: the words it shows. The patch adds Instagram's subtitle
     * state interface to it, which has no methods, so Instagram's subtitle carries it like one of
     * its own. Two lines with the same words are equal, so a title drawn again with the same time
     * is the same title to Instagram.
     */
    static final class Line {
        final String text;

        Line(String text) {
            this.text = text == null ? "" : text;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Line && text.equals(((Line) other).text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }

        @Override
        public String toString() {
            return "Line(" + text + ")";
        }
    }

    /** Reads a title and makes its line; the patch-filled stubs below outside tests. */
    interface Reader {
        /** The post of the reel a title belongs to, or null when it has none. */
        Object media(Object title);

        /** The post's taken_at in seconds, or null when it doesn't say. */
        Long takenAt(Object media);

        /** Instagram's subtitle for [title] showing [line]. */
        Object component(Object title, Object line);
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object title) {
            return ReelPostTime.media(title);
        }

        @Override
        public Long takenAt(Object media) {
            return ReelPostTime.takenAt(media);
        }

        @Override
        public Object component(Object title, Object line) {
            return ReelPostTime.component(title, line);
        }
    };

    private ReelPostTime() {
    }

    /** Whether the time is shown: the settings are read and the switch is on, which a pause answers off. */
    private static boolean on() {
        return Utils.settingsReady() && Settings.SHOW_REEL_POST_TIME.get();
    }

    /**
     * Injected in the Reels title's render, just before it makes its column, with the title and
     * the list of the column's parts. Adds the line with the reel's posting time to the end of the
     * list while the switch is on and the reel has one. Leaves the list alone otherwise, and when
     * anything goes wrong. Never throws.
     */
    public static void line(Object title, Object children) {
        line(title, children, PATCHED, System.currentTimeMillis());
    }

    static void line(Object title, Object children, Reader reader, long now) {
        try {
            HookStatus.invoked(FamilyNames.REEL_POST_TIME);
            if (title == null || !(children instanceof List) || !on()) return;
            Object media = reader.media(title);
            Long posted = media == null ? null : reader.takenAt(media);
            if (posted == null) return;
            Context context = Utils.getContext();
            String text = label(context, posted, Settings.REEL_POST_TIME_SECONDS.get(), now);
            if (text == null) return;
            Object component = reader.component(title, new Line(text));
            if (component == null) return;
            @SuppressWarnings("unchecked")
            List<Object> parts = (List<Object>) children;
            parts.add(component);
            HookStatus.bound(FamilyNames.REEL_POST_TIME, "reel time");
            if (!Settings.REEL_POST_TIME_AGO.get()) return;
            // A line of its own: next to the Follow button the two together ran out of room.
            String ago = ago(context, now - posted * 1000L);
            Object below = ago == null ? null : reader.component(title, new Line(ago));
            if (below != null) parts.add(below);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_POST_TIME, "reel time", failure);
        }
    }

    /**
     * Injected first where Instagram picks what a subtitle draws for its state. Answers the words
     * of a {@link Line}, which the patch then draws as plain subtitle text, and null for any state
     * of Instagram's own, which goes on to Instagram's choice. A line always has words, so one
     * never reaches Instagram's choice, which knows nothing of it. Never throws.
     */
    @Nullable
    public static CharSequence lineText(Object state) {
        return state instanceof Line ? ((Line) state).text : null;
    }

    /** The listener the line's text is made with: a tap on the time does nothing. */
    public static View.OnClickListener noClick() {
        return NO_CLICK;
    }

    /**
     * What a reel posted at [seconds] says at [now], which is in milliseconds, with the seconds
     * when [exact]. Null when there's no context to write it in or the time isn't a posting time.
     */
    @Nullable
    static String label(@Nullable Context context, long seconds, boolean exact, long now) {
        if (context == null || seconds <= 0 || seconds > LAST_SECOND) return null;
        long posted = seconds * 1000L;
        if (posted > now && posted - now > DAY) return null;
        String whole = DateUtils.formatDateTime(context, posted, StoryTime.FORMAT);
        if (!exact) return whole;
        String withSeconds = withSeconds(context, posted, whole);
        return withSeconds == null ? whole : withSeconds;
    }

    /**
     * How long [elapsed] milliseconds is, like 1d 2h 5m ago: the days, hours and minutes that
     * aren't none, or the seconds under a minute. The units are written short in the language
     * HushGram's own words come out in, so the line never mixes two. Null for a time ahead of the
     * phone's clock, which is a clock a little off rather than a reel from the future.
     */
    @Nullable
    static String ago(@Nullable Context context, long elapsed) {
        if (context == null || elapsed < 0) return null;
        long minutes = elapsed / MINUTE;
        List<Measure> parts = new ArrayList<>(3);
        if (minutes == 0) {
            parts.add(new Measure(elapsed / 1000L, MeasureUnit.SECOND));
        } else {
            long days = minutes / (24 * 60);
            long hours = minutes / 60 % 24;
            if (days > 0) parts.add(new Measure(days, MeasureUnit.DAY));
            if (hours > 0) parts.add(new Measure(hours, MeasureUnit.HOUR));
            if (minutes % 60 > 0) parts.add(new Measure(minutes % 60, MeasureUnit.MINUTE));
        }
        String span = MeasureFormat.getInstance(L10n.textLocale(context), MeasureFormat.FormatWidth.NARROW)
                .formatMeasures(parts.toArray(new Measure[0]));
        return L10n.f(context, "%1$s ago", span);
    }

    /**
     * [whole], the date and time of [posted], with the seconds in its time. The time of day is
     * written again from the phone's own time pattern with the seconds added, and put where the
     * time stands in [whole], so the date, its order and the words between them stay the phone's.
     * Null when the phone's time pattern has no place for seconds.
     */
    @Nullable
    private static String withSeconds(Context context, long posted, String whole) {
        java.text.DateFormat format = DateFormat.getTimeFormat(context);
        if (!(format instanceof SimpleDateFormat)) return null;
        SimpleDateFormat clock = (SimpleDateFormat) format;
        String pattern = secondsPattern(clock.toPattern());
        if (pattern == null) return null;
        clock.applyPattern(pattern);
        String exact = clock.format(new Date(posted));
        String time = DateUtils.formatDateTime(context, posted, DateUtils.FORMAT_SHOW_TIME);
        int at = time.isEmpty() ? -1 : whole.indexOf(time);
        if (at < 0 || at != whole.lastIndexOf(time)) {
            // The time isn't in the label as the phone writes it alone, so the date goes in front of it.
            return DateUtils.formatDateTime(context, posted, DATE) + " " + exact;
        }
        return whole.substring(0, at) + exact + whole.substring(at + time.length());
    }

    /**
     * [pattern], a time of day's pattern, with the seconds after its minutes, set off by the mark
     * that sets the minutes off from the hour: h:mm a gives h:mm:ss a, and H.mm gives H.mm.ss.
     * Null when the pattern has no minutes outside quoted words, or has seconds already.
     */
    @Nullable
    static String secondsPattern(@Nullable String pattern) {
        if (pattern == null) return null;
        boolean quoted = false;
        int minutes = -1;
        int end = -1;
        for (int at = 0; at < pattern.length(); at++) {
            char letter = pattern.charAt(at);
            if (letter == '\'') {
                quoted = !quoted;
            } else if (!quoted && letter == 's') {
                return null;
            } else if (!quoted && letter == 'm' && minutes < 0) {
                minutes = at;
                end = at;
                while (end < pattern.length() && pattern.charAt(end) == 'm') end++;
                at = end - 1;
            }
        }
        if (minutes < 0) return null;
        String mark = ":";
        if (minutes >= 2 && "hHkK".indexOf(pattern.charAt(minutes - 2)) >= 0) {
            char between = pattern.charAt(minutes - 1);
            if (between != '\'' && !Character.isLetterOrDigit(between) && !Character.isWhitespace(between)) {
                mark = String.valueOf(between);
            }
        }
        return pattern.substring(0, end) + mark + "ss" + pattern.substring(end);
    }

    /**
     * The post of the reel a title belongs to, or null for anything that isn't the Reels title or
     * has no reel. The patch writes the body, which reads the title's reel and the reel's one post
     * field. Null as built.
     */
    public static Object media(Object title) {
        return null;
    }

    /**
     * A post's taken_at, in seconds, or null when it doesn't say. The patch writes the body, which
     * calls the post's getter for that field. Null as built.
     */
    public static Long takenAt(Object media) {
        return null;
    }

    /**
     * Instagram's subtitle for [title] showing [line], which has to be a {@link Line}. The patch
     * writes the body, which calls the copy of the title's subtitle maker it adds, the one taking
     * the state to show. Null as built.
     */
    public static Object component(Object title, Object line) {
        return null;
    }
}
