/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The line a reel's title gets for its posting time: what it says, with and without the seconds,
 * and that the title's parts stay as Instagram made them whenever there's no time to show.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelPostTimeTest {
    private static final String ZONE = "America/New_York";
    private static final int YEAR = 2026;
    private static final long HOUR = 60L * 60 * 1000;

    /** A reel's post: its taken_at, as Instagram parsed it. */
    static final class Post {
        final Long takenAt;

        Post(Long takenAt) {
            this.takenAt = takenAt;
        }
    }

    /** The Reels title of one reel, whose post can be null. */
    static final class Title {
        final Post post;

        Title(Post post) {
            this.post = post;
        }
    }

    /** Stands in for the subtitle Instagram's maker answers: the title it was made for and the state it shows. */
    static final class Subtitle {
        final Object title;
        final Object state;

        Subtitle(Object title, Object state) {
            this.title = title;
            this.state = state;
        }
    }

    /** Reads a Title the way the patch-filled stubs read Instagram's, counting each read. */
    static class Reader implements ReelPostTime.Reader {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Object media(Object title) {
            reads.incrementAndGet();
            return title instanceof Title ? ((Title) title).post : null;
        }

        @Override
        public Long takenAt(Object media) {
            return ((Post) media).takenAt;
        }

        @Override
        public Object component(Object title, Object line) {
            return new Subtitle(title, line);
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Reader reader = new Reader();
    private final Object nameRow = "the name row";
    private final Object audio = "the audio";
    private Locale locale;
    private TimeZone zone;
    private Context context;
    private long now;

    @Before
    public void enable() {
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHOW_REEL_POST_TIME.save(true);
        Settings.REEL_POST_TIME_SECONDS.resetToDefault();
        Settings.REEL_POST_TIME_AGO.resetToDefault();
        HookStatus.clear();
        now = postedOnOctober2(0) * 1000L + 2 * HOUR;
        // The date leaves the year out for this year, which is the year of the phone's clock.
        assertTrue(SystemClock.setCurrentTimeMillis(now));
        hourSetting("12");
    }

    @After
    public void restore() {
        hourSetting(null);
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
        Settings.SHOW_REEL_POST_TIME.resetToDefault();
        Settings.REEL_POST_TIME_SECONDS.resetToDefault();
        Settings.REEL_POST_TIME_AGO.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private List<Object> parts() {
        return new ArrayList<>(Arrays.asList(nameRow, audio));
    }

    private static Title reel(Long takenAt) {
        return new Title(new Post(takenAt));
    }

    /** The words of the lines [title] gets, one per text line, or null when its parts come back as they went in. */
    private String lineOf(Object title) {
        List<Object> parts = parts();
        ReelPostTime.line(title, parts, reader, now);
        if (parts.size() == 2) {
            assertEquals(Arrays.asList(nameRow, audio), parts);
            return null;
        }
        assertTrue("two lines at most: " + parts, parts.size() <= 4);
        StringBuilder words = new StringBuilder();
        for (Object part : parts.subList(2, parts.size())) {
            Subtitle made = (Subtitle) part;
            assertSame(title, made.title);
            if (words.length() > 0) words.append('\n');
            words.append(ReelPostTime.lineText(made.state));
        }
        return words.toString();
    }

    /** On, a reel with a posting time gets one more part after Instagram's own, made for its title. */
    @Test
    public void aReelWithATimeGetsALineAfterInstagramsParts() {
        Title title = reel(postedOnOctober2(0));
        List<Object> parts = parts();

        ReelPostTime.line(title, parts, reader, now);

        assertEquals(3, parts.size());
        assertSame("the name row keeps its place", nameRow, parts.get(0));
        assertSame("Instagram's subtitle keeps its place", audio, parts.get(1));
        Subtitle made = (Subtitle) parts.get(2);
        assertSame(title, made.title);
        assertEquals("Oct 2, 3:45 PM", plain(String.valueOf(ReelPostTime.lineText(made.state))));
        assertTrue(HookStatus.missing(FamilyNames.REEL_POST_TIME).toString(),
                HookStatus.missing(FamilyNames.REEL_POST_TIME).isEmpty());
    }

    /** The seconds are there only when asked for, and the time without them is the one a post's exact time has. */
    @Test
    public void theSecondsShowOnlyWhenAskedFor() {
        Title title = reel(postedOnOctober2(0));
        assertEquals("off to start", Boolean.FALSE, Settings.REEL_POST_TIME_SECONDS.defaultValue);
        assertEquals("Oct 2, 3:45 PM", plain(lineOf(title)));

        Settings.REEL_POST_TIME_SECONDS.save(true);
        assertEquals("Oct 2, 3:45:30 PM", plain(lineOf(title)));

        Settings.REEL_POST_TIME_SECONDS.save(false);
        assertEquals("Oct 2, 3:45 PM", plain(lineOf(title)));
    }

    /**
     * How long ago shows only when asked for, on a line of its own under the time, with the seconds
     * or without them (#195). Next to the Follow button the two on one line ran out of room.
     */
    @Test
    public void howLongAgoShowsOnlyWhenAskedFor() {
        Title title = reel(postedOnOctober2(0));
        assertEquals("off to start", Boolean.FALSE, Settings.REEL_POST_TIME_AGO.defaultValue);
        assertEquals("Oct 2, 3:45 PM", plain(lineOf(title)));

        Settings.REEL_POST_TIME_AGO.save(true);
        assertEquals("Oct 2, 3:45 PM\n2h ago", plain(lineOf(title)));
        Settings.REEL_POST_TIME_SECONDS.save(true);
        assertEquals("Oct 2, 3:45:30 PM\n2h ago", plain(lineOf(title)));

        assertEquals("a minute ahead of the phone's clock is a clock that's off, so just the time",
                "Oct 2, 5:46:30 PM", plain(lineOf(reel(now / 1000L + 60))));
    }

    /** Days, hours and minutes, the ones that aren't none, and the seconds under a minute. */
    @Test
    public void howLongAgoCountsDaysHoursAndMinutes() {
        long second = 1000L;
        long minute = 60 * second;
        long day = 24 * HOUR;
        assertEquals("1d 1h 1m ago", ReelPostTime.ago(context, day + HOUR + minute));
        assertEquals("1d 5m ago", ReelPostTime.ago(context, day + 5 * minute + 59 * second));
        assertEquals("2d ago", ReelPostTime.ago(context, 2 * day));
        assertEquals("1h 1m ago", ReelPostTime.ago(context, 61 * minute));
        assertEquals("59m ago", ReelPostTime.ago(context, HOUR - second));
        assertEquals("30s ago", ReelPostTime.ago(context, 30 * second + 999));
        assertEquals("0s ago", ReelPostTime.ago(context, 0));
        assertEquals("400d ago", ReelPostTime.ago(context, 400 * day));
        assertNull("ahead of the phone's clock", ReelPostTime.ago(context, -1));
        assertNull(ReelPostTime.ago(null, HOUR));
    }

    /** A phone in Turkish, which HushGram speaks, gets the units and the ago in Turkish, never half in English. */
    @Test
    public void howLongAgoIsInTheLanguageOfHushGramsWords() {
        RuntimeEnvironment.setQualifiers("+tr");
        try {
            String ago = ReelPostTime.ago(RuntimeEnvironment.getApplication(), 24 * HOUR + HOUR + 60 * 1000L);
            assertEquals("ICU's short Turkish units: gün, saat, dakika", "1g 1 sa 1d önce", ago);
        } finally {
            RuntimeEnvironment.setQualifiers("+en-rUS");
        }
    }

    /** A phone on a 24-hour clock gets its own clock, with the seconds and without. */
    @Test
    public void a24HourPhoneGetsItsOwnClock() {
        Title title = reel(postedOnOctober2(0));
        hourSetting("24");
        assertEquals("Oct 2, 15:45", plain(lineOf(title)));
        Settings.REEL_POST_TIME_SECONDS.save(true);
        assertEquals("Oct 2, 15:45:30", plain(lineOf(title)));
    }

    /** A reel from another year says which, the longest the line gets. */
    @Test
    public void aReelFromAnotherYearSaysTheYear() {
        Title title = reel(postedOnOctober2(-1));
        assertEquals("Oct 2, " + (YEAR - 1) + ", 3:45 PM", plain(lineOf(title)));
        Settings.REEL_POST_TIME_SECONDS.save(true);
        assertEquals("Oct 2, " + (YEAR - 1) + ", 3:45:30 PM", plain(lineOf(title)));
    }

    /** The time is written in the phone's time zone, not the server's. */
    @Test
    public void theTimeIsThePhonesOwn() {
        Title title = reel(postedOnOctober2(0));
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        Settings.REEL_POST_TIME_SECONDS.save(true);
        assertEquals("Oct 2, 12:45:30 PM", plain(lineOf(title)));
    }

    /** The seconds go after the minutes, set off the way the phone sets the minutes off from the hour. */
    @Test
    public void theSecondsFollowThePhonesTimePattern() {
        assertEquals("h:mm:ss a", ReelPostTime.secondsPattern("h:mm a"));
        assertEquals("HH:mm:ss", ReelPostTime.secondsPattern("HH:mm"));
        assertEquals("H.mm.ss", ReelPostTime.secondsPattern("H.mm"));
        assertEquals("a h:mm:ss", ReelPostTime.secondsPattern("a h:mm"));
        assertEquals("HH 'h' mm:ss", ReelPostTime.secondsPattern("HH 'h' mm"));
        assertEquals("H'h'mm:ss", ReelPostTime.secondsPattern("H'h'mm"));
        assertEquals("h:mm:ss a", ReelPostTime.secondsPattern("h:mm a"));
        assertEquals("K:mm:ss a", ReelPostTime.secondsPattern("K:mm a"));

        assertNull("seconds already", ReelPostTime.secondsPattern("h:mm:ss a"));
        assertNull("no minutes", ReelPostTime.secondsPattern("h a"));
        assertNull("minutes only in quoted words", ReelPostTime.secondsPattern("H 'mm'"));
        assertNull(ReelPostTime.secondsPattern(""));
        assertNull(ReelPostTime.secondsPattern(null));
    }

    /** A time that can't be a posting time, or no context to write it in, gets no line. */
    @Test
    public void aTimeThatIsntOneGetsNoLine() {
        long posted = postedOnOctober2(0);
        for (boolean exact : new boolean[]{false, true}) {
            assertNotNull(ReelPostTime.label(context, posted, exact, now));
            assertNull(ReelPostTime.label(context, 0L, exact, now));
            assertNull(ReelPostTime.label(context, -1L, exact, now));
            assertNull(ReelPostTime.label(context, Long.MIN_VALUE, exact, now));
            assertNull(ReelPostTime.label(context, Long.MAX_VALUE / 1000L + 1, exact, now));
            assertNull(ReelPostTime.label(context, Long.MAX_VALUE, exact, now));
            assertNull(ReelPostTime.label(null, posted, exact, now));
        }

        for (Long none : new Long[]{null, 0L, -1L, Long.MAX_VALUE}) {
            assertNull(String.valueOf(none), lineOf(reel(none)));
        }
        assertTrue(HookStatus.missing(FamilyNames.REEL_POST_TIME).toString(),
                HookStatus.missing(FamilyNames.REEL_POST_TIME).isEmpty());
    }

    /** A time more than a day past the phone's clock isn't a posting time. One a little ahead is a clock that's a little off. */
    @Test
    public void aTimeInTheFutureGetsNoLine() {
        long nowSeconds = now / 1000L;
        assertNotNull("a minute ahead", lineOf(reel(nowSeconds + 60)));
        assertNotNull("a day ahead to the second", lineOf(reel(nowSeconds + 24 * 60 * 60)));
        assertNull("a day and a second ahead", lineOf(reel(nowSeconds + 24 * 60 * 60 + 1)));
        assertNull("a year ahead", lineOf(reel(postedOnOctober2(1))));
        assertNotNull("years back", lineOf(reel(postedOnOctober2(-10))));
    }

    /**
     * Instagram builds the title's parts again for every reel, so a title drawn for a reel with no
     * time gets no line whatever the title before it had, and one with a time gets its own.
     */
    @Test
    public void aTitleDrawnAgainForAReelWithoutATimeHasNoLine() {
        Title timed = reel(postedOnOctober2(0));
        assertEquals("Oct 2, 3:45 PM", plain(lineOf(timed)));

        assertNull("a reel whose post doesn't say", lineOf(reel(null)));
        assertNull("a reel with no post", lineOf(new Title(null)));
        assertNull("something that isn't the title", lineOf("an ad's header"));

        Title later = reel(postedOnOctober2(0) + 60);
        assertEquals("Oct 2, 3:46 PM", plain(lineOf(later)));
        assertEquals("drawn again, it says the same", "Oct 2, 3:45 PM", plain(lineOf(timed)));
    }

    /** Parts that aren't a list, a missing title and a maker answering nothing all leave things as they were. */
    @Test
    public void whatCantTakeALineIsLeftAlone() {
        Title title = reel(postedOnOctober2(0));
        ReelPostTime.line(title, null, reader, now);
        ReelPostTime.line(title, "not a list", reader, now);
        List<Object> parts = parts();
        ReelPostTime.line(null, parts, reader, now);
        assertEquals(Arrays.asList(nameRow, audio), parts);
        assertEquals("nothing was read", 0, reader.reads.get());

        ReelPostTime.line(title, parts, new Reader() {
            @Override
            public Object component(Object owner, Object line) {
                return null;
            }
        }, now);
        assertEquals(Arrays.asList(nameRow, audio), parts);
        assertTrue(HookStatus.missing(FamilyNames.REEL_POST_TIME).toString(),
                HookStatus.missing(FamilyNames.REEL_POST_TIME).isEmpty());
    }

    /** Off, paused or before the settings are read, the parts stay Instagram's and no reel is read. */
    @Test
    public void offPausedAndUnreadyAddNoLine() {
        Title title = reel(postedOnOctober2(0));
        Settings.REEL_POST_TIME_SECONDS.save(true);

        Settings.SHOW_REEL_POST_TIME.save(false);
        assertNull(lineOf(title));
        Settings.SHOW_REEL_POST_TIME.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull(lineOf(title));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertNull(lineOf(title)));

        assertEquals("a reel was read while the switch was off", 0, reader.reads.get());
        assertEquals("back on", "Oct 2, 3:45:30 PM", plain(lineOf(title)));
    }

    /** A reader that throws, a list that takes nothing more: the parts stay as they came and the report says which hook threw. */
    @Test
    public void aThrowingReaderOrListAddsNoLineAndIsReported() {
        Title title = reel(postedOnOctober2(0));
        ReelPostTime.Reader broken = new Reader() {
            @Override
            public Long takenAt(Object media) {
                throw new ClassCastException("Post cannot be cast to Media");
            }
        };
        List<Object> parts = parts();
        ReelPostTime.line(title, parts, broken, now);
        assertEquals(Arrays.asList(nameRow, audio), parts);
        String missing = HookStatus.missing(FamilyNames.REEL_POST_TIME).toString();
        assertTrue(missing, missing.contains("'reel time'"));
        assertTrue(missing, missing.contains(ClassCastException.class.getName()));

        HookStatus.clear();
        List<Object> sealed = Collections.unmodifiableList(parts());
        ReelPostTime.line(title, sealed, reader, now);
        assertEquals(Arrays.asList(nameRow, audio), sealed);
        missing = HookStatus.missing(FamilyNames.REEL_POST_TIME).toString();
        assertTrue(missing, missing.contains(UnsupportedOperationException.class.getName()));
    }

    /** As built, with the stubs unfilled, the hook adds nothing: there's no reel to read. */
    @Test
    public void theUnpatchedStubsAddNothing() {
        List<Object> parts = parts();
        ReelPostTime.line(reel(postedOnOctober2(0)), parts);
        assertEquals(Arrays.asList(nameRow, audio), parts);
        assertNull(ReelPostTime.media(new Object()));
        assertNull(ReelPostTime.takenAt(new Object()));
        assertNull(ReelPostTime.component(new Object(), new ReelPostTime.Line("Oct 2, 3:45 PM")));
    }

    /** Only the line's own state has words: any state of Instagram's goes on to Instagram's choice. */
    @Test
    public void onlyTheLinesOwnStateHasWords() {
        assertEquals("Oct 2, 3:45 PM", String.valueOf(ReelPostTime.lineText(new ReelPostTime.Line("Oct 2, 3:45 PM"))));
        assertEquals("", String.valueOf(ReelPostTime.lineText(new ReelPostTime.Line(null))));
        assertNull(ReelPostTime.lineText(null));
        assertNull(ReelPostTime.lineText(new Object()));
        assertNull(ReelPostTime.lineText("Oct 2, 3:45 PM"));
        assertNotNull(ReelPostTime.noClick());
        assertSame(ReelPostTime.noClick(), ReelPostTime.noClick());
        ReelPostTime.noClick().onClick(null);
    }

    /** Two lines with the same words are the same state, so a title drawn again unchanged is unchanged to Instagram. */
    @Test
    public void linesWithTheSameWordsAreEqual() {
        ReelPostTime.Line one = new ReelPostTime.Line("Oct 2, 3:45 PM");
        ReelPostTime.Line same = new ReelPostTime.Line("Oct 2, 3:45 PM");
        ReelPostTime.Line other = new ReelPostTime.Line("Oct 2, 3:46 PM");
        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, other);
        assertNotEquals(one, "Oct 2, 3:45 PM");
        assertNotEquals(one, null);
    }

    private static long postedOnOctober2(int years) {
        Calendar posted = Calendar.getInstance(TimeZone.getTimeZone(ZONE), Locale.US);
        posted.clear();
        posted.set(YEAR + years, Calendar.OCTOBER, 2, 15, 45, 30);
        return posted.getTimeInMillis() / 1000L;
    }

    private static void hourSetting(String value) {
        android.provider.Settings.System.putString(RuntimeEnvironment.getApplication().getContentResolver(),
                android.provider.Settings.System.TIME_12_24, value);
    }

    /** Newer ICU puts a narrow no-break space before PM; the words are what's checked. */
    private static String plain(String label) {
        assertNotNull("no label", label);
        return label.replace(' ', ' ').replace(' ', ' ');
    }
}
