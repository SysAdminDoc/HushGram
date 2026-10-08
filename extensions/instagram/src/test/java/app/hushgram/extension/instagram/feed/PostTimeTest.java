/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What stands in for Instagram's time call on posts and comments: the date and time while the
 * switch is on, and Instagram's own label otherwise, which here is the unfilled stub's null.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PostTimeTest {
    private static final String ZONE = "America/New_York";
    private static final int YEAR = 2026;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private Locale locale;
    private TimeZone zone;
    private Context context;
    private final Object formatter = new Object();

    @Before
    public void enable() {
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE));
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHOW_POST_TIME.resetToDefault();
        HookStatus.clear();
        assertTrue(SystemClock.setCurrentTimeMillis((postedOnOctober2(0) + 2 * 60 * 60) * 1000L));
        hourSetting("12");
    }

    @After
    public void restore() {
        hourSetting(null);
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
        Settings.SHOW_POST_TIME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On to start, a post's time as a double and a comment's as a long both read as the date and time. */
    @Test
    public void bothShapesGetTheDateAndTime() {
        long posted = postedOnOctober2(0);
        assertEquals("Oct 2, 3:45 PM", plain(PostTime.time(formatter, context, (double) posted)));
        assertEquals("Oct 2, 3:45 PM", plain(PostTime.time(formatter, context, posted)));
        assertTrue(HookStatus.missing(FamilyNames.POST_TIME).toString(), HookStatus.missing(FamilyNames.POST_TIME).isEmpty());
    }

    /** A post from another year says which, the way a story's time does. */
    @Test
    public void aPostFromAnotherYearSaysTheYear() {
        assertEquals("Oct 2, " + (YEAR - 1) + ", 3:45 PM", plain(PostTime.time(formatter, context, postedOnOctober2(-1))));
    }

    /** Off, paused or before the settings are read, the call goes on to Instagram's formatting. */
    @Test
    public void offPausedAndUnreadyGoOnToInstagram() {
        long posted = postedOnOctober2(0);
        assertNotNull(PostTime.time(formatter, context, posted));

        Settings.SHOW_POST_TIME.save(false);
        assertNull(PostTime.time(formatter, context, posted));
        assertNull(PostTime.time(formatter, context, (double) posted));
        Settings.SHOW_POST_TIME.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull(PostTime.time(formatter, context, posted));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertNull(PostTime.time(formatter, context, posted)));
        assertNotNull("back on", PostTime.time(formatter, context, posted));
    }

    /** A time that can't be one, or no context to write it in, keeps Instagram's label. */
    @Test
    public void aTimeThatIsntOneKeepsInstagramsLabel() {
        assertNull(PostTime.time(formatter, context, 0L));
        assertNull(PostTime.time(formatter, context, -1L));
        assertNull(PostTime.time(formatter, context, Long.MAX_VALUE / 1000L + 1));
        assertNull(PostTime.time(formatter, context, Double.NaN));
        assertNull(PostTime.time(formatter, null, postedOnOctober2(0)));
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
