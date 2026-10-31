/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.WindowManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which display mode Instagram's windows ask for. */
@RunWith(RobolectricTestRunner.class)
public class RefreshRateTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static RefreshRate.Mode[] panel() {
        return new RefreshRate.Mode[]{
                new RefreshRate.Mode(1, 1080, 2400, 60f),
                new RefreshRate.Mode(2, 1080, 2400, 120f),
                new RefreshRate.Mode(3, 1440, 3200, 60f),
                new RefreshRate.Mode(4, 1440, 3200, 120f),
                new RefreshRate.Mode(5, 1080, 2400, 90f),
        };
    }

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIGHEST_REFRESH_RATE.save(true);
        HookStatus.clear();
        RefreshRate.source = (activity, current) -> {
            current[0] = 1;
            return panel();
        };
    }

    @After
    public void restore() {
        RefreshRate.source = RefreshRate::modesOf;
        Settings.HIGHEST_REFRESH_RATE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.HIGHEST_REFRESH_RATE.resetToDefault();
        assertFalse(Settings.HIGHEST_REFRESH_RATE.get());
        assertFalse(Settings.HIGHEST_REFRESH_RATE.rebootApp);
    }

    /** The fastest mode at the pixel size the screen runs at now, never a bigger one. */
    @Test
    public void picksTheFastestModeAtTheCurrentResolution() {
        assertEquals(2, RefreshRate.fastestId(1, panel()));
        assertEquals(2, RefreshRate.fastestId(5, panel()));
        assertEquals(4, RefreshRate.fastestId(3, panel()));
    }

    /**
     * A display that reports its fastest mode still gets asked for it: the S25 reports 120 Hz while
     * it draws Instagram at 60 from a slower base mode, and only the request moves it (#401).
     */
    @Test
    public void alreadyOnTheFastestModeStillAsksForIt() {
        assertEquals(2, RefreshRate.fastestId(2, panel()));
        assertEquals(4, RefreshRate.fastestId(4, panel()));
        RefreshRate.source = (activity, current) -> {
            current[0] = 2;
            return panel();
        };
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RefreshRate.apply(activity);
        assertEquals(2, activity.getWindow().getAttributes().preferredDisplayModeId);
    }

    /** An unknown current mode and no modes at all leave the choice to Android. */
    @Test
    public void noPreferenceWhenNothingIsKnown() {
        assertEquals(0, RefreshRate.fastestId(99, panel()));
        assertEquals(0, RefreshRate.fastestId(1, null));
        assertEquals(0, RefreshRate.fastestId(1, new RefreshRate.Mode[0]));
    }

    @Test
    public void onTheWindowAsksForTheFastestMode() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RefreshRate.apply(activity);
        assertEquals(2, activity.getWindow().getAttributes().preferredDisplayModeId);
        assertEquals(HookStatus.missing(app.hushgram.extension.instagram.settings.FamilyNames.REFRESH_RATE).toString(), 0,
                HookStatus.missing(app.hushgram.extension.instagram.settings.FamilyNames.REFRESH_RATE).size());
    }

    /** Off, paused or before the settings are ready, no mode is asked for, and a request made earlier is taken back. */
    @Test
    public void offPausedAndUnreadyAskForNothing() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RefreshRate.apply(activity);
        assertEquals(2, activity.getWindow().getAttributes().preferredDisplayModeId);

        Settings.HIGHEST_REFRESH_RATE.save(false);
        RefreshRate.apply(activity);
        assertEquals("off takes the request back", 0, activity.getWindow().getAttributes().preferredDisplayModeId);

        Settings.HIGHEST_REFRESH_RATE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        RefreshRate.apply(other);
        assertEquals("paused", 0, other.getWindow().getAttributes().preferredDisplayModeId);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            Activity third = Robolectric.buildActivity(Activity.class).setup().get();
            RefreshRate.apply(third);
            assertEquals("no context", 0, third.getWindow().getAttributes().preferredDisplayModeId);
        });
    }

    /**
     * HushGram settings open over Instagram's screen, which never resumes when they close, so the
     * flip itself reaches the activity under them (#401). Before, the request waited for a cold start.
     */
    @Test
    public void flippingTheSwitchReachesTheActivityUnderTheSettingsWithoutAResume() {
        Settings.HIGHEST_REFRESH_RATE.save(false);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RefreshRate.apply(activity);
        assertEquals(0, activity.getWindow().getAttributes().preferredDisplayModeId);

        Settings.HIGHEST_REFRESH_RATE.save(true);
        RefreshRate.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("on asks at once", 2, activity.getWindow().getAttributes().preferredDisplayModeId);

        Settings.HIGHEST_REFRESH_RATE.save(false);
        RefreshRate.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("off takes it back at once", 0, activity.getWindow().getAttributes().preferredDisplayModeId);

        RefreshRate.switchChanged(null);
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** A mode somebody else put on the window is not this class's to undo. */
    @Test
    public void aModeSetByAnotherPartIsLeftAlone() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        params.preferredDisplayModeId = 7;
        activity.getWindow().setAttributes(params);
        Settings.HIGHEST_REFRESH_RATE.save(false);
        RefreshRate.apply(activity);
        assertEquals(7, activity.getWindow().getAttributes().preferredDisplayModeId);
    }

    /** A failing display read never reaches Instagram. */
    @Test
    public void aFailingReadIsRecordedNotThrown() {
        RefreshRate.source = (activity, current) -> {
            throw new IllegalStateException("no display");
        };
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RefreshRate.apply(activity);
        assertEquals(0, activity.getWindow().getAttributes().preferredDisplayModeId);
    }
}
