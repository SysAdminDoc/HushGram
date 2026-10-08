/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.text.format.DateUtils;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the story time hooks answer: the posted time the way the chosen mode says while the switch
 * is on, Instagram's label otherwise.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryTimeTest {
    private static final String ZONE = "America/New_York";
    /** The year the stories are posted in, and the clock's, so the label leaves the year out. */
    private static final int YEAR = 2026;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Locale locale;
    private TimeZone zone;

    @Before
    public void enable() {
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHOW_STORY_TIME.save(true);
        HookStatus.clear();
        // Robolectric's clock starts in 1970, and a date outside the current year gets its year,
        // so the clock moves to a little after the story went up, the way a phone's would be.
        assertTrue(SystemClock.setCurrentTimeMillis((postedOnOctober2(0) + 2 * 60 * 60) * 1000L));
    }

    @After
    public void restore() {
        hourSetting(null);
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
        Settings.SHOW_STORY_TIME.resetToDefault();
        Settings.STORY_TIME_MODE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A phone set to 12-hour time gets the month, the day and the time with AM or PM. */
    @Test
    public void aTwelveHourPhoneGetsTheDateAndTimeWithPm() {
        hourSetting("12");
        assertEquals("Oct 2, 3:45 PM", plain(StoryTime.label(postedOnOctober2(0))));
    }

    /** A phone set to 24-hour time gets the same date with the 24-hour time. */
    @Test
    public void aTwentyFourHourPhoneGetsTheTwentyFourHourTime() {
        hourSetting("24");
        assertEquals("Oct 2, 15:45", plain(StoryTime.label(postedOnOctober2(0))));
    }

    /** The phone's language picks the date's words and order. */
    @Test
    public void theLabelFollowsThePhonesLanguage() {
        Locale.setDefault(Locale.GERMANY);
        hourSetting("24");
        String label = plain(StoryTime.label(postedOnOctober2(0)));
        assertTrue(label, label.startsWith("2. Okt"));
        assertTrue(label, label.endsWith("15:45"));
    }

    /** A story from another year, a highlight say, says which year. */
    @Test
    public void aStoryFromAnotherYearSaysTheYear() {
        hourSetting("12");
        assertEquals("Oct 2, " + (YEAR - 1) + ", 3:45 PM", plain(StoryTime.label(postedOnOctober2(-1))));
    }

    /** Off, paused or before the settings are read, the label is Instagram's, and so is the header's flag. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsLabel() {
        long posted = postedOnOctober2(0);
        assertNotNull(StoryTime.label(posted));
        assertFalse("on, the header asks the story item", StoryTime.relativeHeader(1));
        assertFalse(StoryTime.relativeHeader(0));

        Settings.SHOW_STORY_TIME.save(false);
        assertNull(StoryTime.label(posted));
        assertTrue(StoryTime.relativeHeader(1));
        assertFalse(StoryTime.relativeHeader(0));
        Settings.SHOW_STORY_TIME.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull(StoryTime.label(posted));
        assertTrue(StoryTime.relativeHeader(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertNull(StoryTime.label(posted));
            assertTrue(StoryTime.relativeHeader(1));
            assertFalse(StoryTime.relativeHeader(0));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertNull(StoryTime.label(posted));
            assertTrue(StoryTime.relativeHeader(1));
        });

        assertNotNull("back on", StoryTime.label(posted));
        assertTrue(HookStatus.missing(FamilyNames.STORY_TIME).toString(), HookStatus.missing(FamilyNames.STORY_TIME).isEmpty());
    }

    /** A time that can't be a posted one, none at all or one too large for milliseconds, keeps Instagram's label. */
    @Test
    public void aTimeThatIsntOneKeepsInstagramsLabel() {
        assertNull(StoryTime.label(0));
        assertNull(StoryTime.label(-1));
        assertNull(StoryTime.label(Long.MAX_VALUE));
        assertNull(StoryTime.label(Long.MAX_VALUE / 1000L + 1));
    }

    /** The choice starts at the date and time, which is all the switch showed before there was one. */
    @Test
    public void theChoiceStartsAtTheDateAndTime() {
        assertSame(StoryTimeMode.DATE_AND_TIME, Settings.STORY_TIME_MODE.defaultValue);
        assertSame(StoryTimeMode.DATE_AND_TIME, Settings.STORY_TIME_MODE.get());
        hourSetting("12");
        assertEquals("Oct 2, 3:45 PM", plain(StoryTime.label(postedOnOctober2(0))));
    }

    /** Time left counts down to a day after the story went up, in hours and minutes, rounded up to the minute. */
    @Test
    public void timeLeftCountsDownToADayAfterPosting() {
        long posted = postedOnOctober2(0) * 1000L;
        assertEquals("18h 14m left", text(StoryTimeMode.TIME_LEFT, posted, posted + duration(5, 46, 20)));
        assertEquals("1h 0m left", text(StoryTimeMode.TIME_LEFT, posted, posted + duration(23, 0, 0)));
        assertEquals("45m left", text(StoryTimeMode.TIME_LEFT, posted, posted + duration(23, 15, 0)));
        assertEquals("the last seconds still say a minute", "1m left",
                text(StoryTimeMode.TIME_LEFT, posted, posted + StoryTime.DAY - 1));
        assertEquals("24h 0m left", text(StoryTimeMode.TIME_LEFT, posted, posted));
        assertEquals("a phone clock behind the server's counts as just posted", "24h 0m left",
                text(StoryTimeMode.TIME_LEFT, posted, posted - duration(0, 2, 0)));
    }

    /** The hook reads the clock: a story posted 5h 46m 20s ago has 18h 14m left. */
    @Test
    public void theHookCountsTimeLeftFromNow() {
        Settings.STORY_TIME_MODE.save(StoryTimeMode.TIME_LEFT);
        long posted = System.currentTimeMillis() / 1000L - duration(5, 46, 20) / 1000L;
        assertEquals("18h 14m left", StoryTime.label(posted));
    }

    /** A story a day old has no time left, so it shows the date and time instead. */
    @Test
    public void aStoryADayOldShowsTheDateAndTimeInsteadOfTimeLeft() {
        hourSetting("12");
        long posted = postedOnOctober2(0) * 1000L;
        assertEquals("Oct 2, 3:45 PM", plain(text(StoryTimeMode.TIME_LEFT, posted, posted + StoryTime.DAY)));
        assertEquals("Oct 2, 3:45 PM", plain(text(StoryTimeMode.TIME_LEFT, posted, posted + 3 * StoryTime.DAY)));
    }

    /** Time posted shows the time of day alone, in the phone's 12 or 24-hour setting, and the date too once a day has passed. */
    @Test
    public void timePostedShowsOnlyTheTimeOfDay() {
        long posted = postedOnOctober2(0) * 1000L;
        hourSetting("12");
        assertEquals("3:45 PM", plain(text(StoryTimeMode.TIME_POSTED, posted, posted + duration(5, 0, 0))));
        hourSetting("24");
        assertEquals("15:45", plain(text(StoryTimeMode.TIME_POSTED, posted, posted + duration(5, 0, 0))));
        assertEquals("Oct 2, 15:45", plain(text(StoryTimeMode.TIME_POSTED, posted, posted + StoryTime.DAY)));
        // Through the hook, which reads the phone's clock: an hour old is the time alone unless that
        // was yesterday, two days old the date too.
        Settings.STORY_TIME_MODE.save(StoryTimeMode.TIME_POSTED);
        Context context = RuntimeEnvironment.getApplication();
        long hourAgo = System.currentTimeMillis() / 1000L - 60 * 60;
        int hourAgoFormat = StoryTime.sameDay(hourAgo * 1000L, System.currentTimeMillis()) ? DateUtils.FORMAT_SHOW_TIME : StoryTime.FORMAT;
        assertEquals(DateUtils.formatDateTime(context, hourAgo * 1000L, hourAgoFormat), StoryTime.label(hourAgo));
        long twoDaysAgo = hourAgo - 2 * 24 * 60 * 60;
        assertEquals(DateUtils.formatDateTime(context, twoDaysAgo * 1000L, StoryTime.FORMAT), StoryTime.label(twoDaysAgo));
    }

    /**
     * The time alone reads right only for a story posted today on the phone's clock. At 9:00 AM, one
     * posted at 11:30 PM the night before is under a day old but shows its date too. The day is the
     * phone's time zone's.
     */
    @Test
    public void timePostedOnAnEarlierDayShowsTheDateToo() {
        hourSetting("12");
        long posted = inNewYork(Calendar.OCTOBER, 1, 23, 30);
        assertEquals("Oct 1, 11:30 PM", plain(text(StoryTimeMode.TIME_POSTED, posted, inNewYork(Calendar.OCTOBER, 2, 9, 0))));
        assertEquals("midnight starts a new day", "Oct 1, 11:30 PM",
                plain(text(StoryTimeMode.TIME_POSTED, posted, inNewYork(Calendar.OCTOBER, 2, 0, 0))));
        assertEquals("11:30 PM", plain(text(StoryTimeMode.TIME_POSTED, posted, inNewYork(Calendar.OCTOBER, 1, 23, 59))));

        // 2:00 AM in New York is still the evening before in Los Angeles.
        long twoAm = inNewYork(Calendar.OCTOBER, 2, 2, 0);
        assertEquals("Oct 1, 11:30 PM", plain(text(StoryTimeMode.TIME_POSTED, posted, twoAm)));
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        assertEquals("8:30 PM", plain(text(StoryTimeMode.TIME_POSTED, posted, twoAm)));
    }

    /** One minute left takes the singular where the language's verb agrees with the count. */
    @Test
    @Config(qualifiers = "es")
    public void oneMinuteLeftTakesTheSingularInSpanish() {
        long posted = postedOnOctober2(0) * 1000L;
        assertEquals("queda 1 min", text(StoryTimeMode.TIME_LEFT, posted, posted + StoryTime.DAY - 1));
        assertEquals("quedan 2 min", text(StoryTimeMode.TIME_LEFT, posted, posted + StoryTime.DAY - duration(0, 2, 0)));
    }

    /** The same in Brazilian Portuguese. */
    @Test
    @Config(qualifiers = "pt-rBR")
    public void oneMinuteLeftTakesTheSingularInPortuguese() {
        long posted = postedOnOctober2(0) * 1000L;
        assertEquals("falta 1 min", text(StoryTimeMode.TIME_LEFT, posted, posted + StoryTime.DAY - 1));
        assertEquals("faltam 2 min", text(StoryTimeMode.TIME_LEFT, posted, posted + StoryTime.DAY - duration(0, 2, 0)));
    }

    /** No mode, which a setting that can't be read gives, is the date and time. */
    @Test
    public void noModeIsTheDateAndTime() {
        hourSetting("12");
        long posted = postedOnOctober2(0) * 1000L;
        assertEquals("Oct 2, 3:45 PM", plain(text(null, posted, posted + duration(1, 0, 0))));
        assertEquals("Oct 2, 3:45 PM", plain(text(StoryTimeMode.DATE_AND_TIME, posted, posted + duration(1, 0, 0))));
    }

    /** Time left is written in the phone's language. */
    @Test
    @Config(qualifiers = "de")
    public void timeLeftFollowsThePhonesLanguage() {
        long posted = postedOnOctober2(0) * 1000L;
        assertEquals("noch 18 Std. 14 Min.", text(StoryTimeMode.TIME_LEFT, posted, posted + duration(5, 46, 20)));
        assertEquals("noch 45 Min.", text(StoryTimeMode.TIME_LEFT, posted, posted + duration(23, 15, 0)));
    }

    /** Whatever the mode, the label is Instagram's while the switch is off, HushGram is paused or the settings aren't ready. */
    @Test
    public void everyModeKeepsInstagramsLabelOffPausedAndUnready() {
        long posted = postedOnOctober2(0);
        for (StoryTimeMode mode : StoryTimeMode.values()) {
            Settings.STORY_TIME_MODE.save(mode);
            assertNotNull(mode.name(), StoryTime.label(posted));

            Settings.SHOW_STORY_TIME.save(false);
            assertNull(mode.name(), StoryTime.label(posted));
            assertTrue(StoryTime.relativeHeader(1));
            Settings.SHOW_STORY_TIME.save(true);

            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertNull(mode.name(), StoryTime.label(posted));
            assertTrue(StoryTime.relativeHeader(1));
            assertSame("a pause keeps the choice", mode, Settings.STORY_TIME_MODE.savedValue());
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();

            SettingsContextRule.withoutContext(() -> assertNull(mode.name(), StoryTime.label(posted)));
            SettingsContextRule.beforeThePauseIsDecided(() -> assertNull(mode.name(), StoryTime.label(posted)));
            assertNotNull(mode.name() + " back on", StoryTime.label(posted));
        }
        assertTrue(HookStatus.missing(FamilyNames.STORY_TIME).toString(), HookStatus.missing(FamilyNames.STORY_TIME).isEmpty());
    }

    private static String text(StoryTimeMode mode, long posted, long now) {
        Context context = RuntimeEnvironment.getApplication();
        return StoryTime.text(context, mode, posted, now);
    }

    /** [hours], [minutes] and [seconds] in milliseconds. */
    private static long duration(int hours, int minutes, int seconds) {
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L;
    }

    /** Seconds since 1970 of October 2 at 3:45:30 PM in New York, [years] from {@link #YEAR}. */
    private static long postedOnOctober2(int years) {
        Calendar posted = Calendar.getInstance(TimeZone.getTimeZone(ZONE), Locale.US);
        posted.clear();
        posted.set(YEAR + years, Calendar.OCTOBER, 2, 15, 45, 30);
        return posted.getTimeInMillis() / 1000L;
    }

    /** Milliseconds since 1970 of [month] [day] at [hour]:[minute] in New York, in {@link #YEAR}. */
    private static long inNewYork(int month, int day, int hour, int minute) {
        Calendar time = Calendar.getInstance(TimeZone.getTimeZone(ZONE), Locale.US);
        time.clear();
        time.set(YEAR, month, day, hour, minute);
        return time.getTimeInMillis();
    }

    /** The phone's 12 or 24-hour setting, or null for the language's own. */
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
