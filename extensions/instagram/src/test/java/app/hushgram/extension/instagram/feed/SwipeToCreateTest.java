/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which moves of Home's sliding container the hook holds, and that it lets every move go when it can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SwipeToCreateTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_SWIPE_TO_CREATE.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.STOP_SWIPE_TO_CREATE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, a finger's move from Home, or from past it, toward the camera is held, the end of the drag included. */
    @Test
    public void withTheSwitchOnASwipeTowardTheCameraIsHeld() {
        assertEquals(1, SwipeToCreate.hold(-0.05f, 0f, "swipe"));
        assertEquals(1, SwipeToCreate.hold(-1f, 0f, "swipe"));
        assertEquals(1, SwipeToCreate.hold(-0.2f, 0.4f, "swipe"));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.SWIPE_TO_CREATE));
    }

    /**
     * Other moves go where Instagram sent them: a tap or a link into the camera, a swipe back out of
     * it, a swipe toward the far side, and a move with a broken position or no reason.
     */
    @Test
    public void everyOtherMoveGoesOn() {
        assertEquals("the + button", 0, SwipeToCreate.hold(-1f, 0f, "camera_action_bar_button"));
        assertEquals("a tap on a partly shown panel", 0, SwipeToCreate.hold(-1f, 0f, "tap_partially_visible_panel"));
        assertEquals("back to Home from the camera", 0, SwipeToCreate.hold(-0.6f, -1f, "swipe"));
        assertEquals("back to Home from the camera", 0, SwipeToCreate.hold(0f, -0.3f, "swipe"));
        assertEquals("toward the far side", 0, SwipeToCreate.hold(0.3f, 0f, "swipe"));
        assertEquals("staying at Home", 0, SwipeToCreate.hold(0f, 0f, "swipe"));
        assertEquals("no reason", 0, SwipeToCreate.hold(-1f, 0f, null));
        assertEquals("a broken target", 0, SwipeToCreate.hold(Float.NaN, 0f, "swipe"));
        assertEquals("a broken position", 0, SwipeToCreate.hold(-1f, Float.NaN, "swipe"));
    }

    /** The switch starts off, and off, paused or asked before the settings are read, every swipe goes on. */
    @Test
    public void offPausedAndUnreadyLetTheSwipeGo() {
        Settings.STOP_SWIPE_TO_CREATE.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.STOP_SWIPE_TO_CREATE.defaultValue);
        assertEquals(0, SwipeToCreate.enabled());
        assertEquals(0, SwipeToCreate.hold(-1f, 0f, "swipe"));

        Settings.STOP_SWIPE_TO_CREATE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, SwipeToCreate.enabled());
        assertEquals(0, SwipeToCreate.hold(-1f, 0f, "swipe"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals(0, SwipeToCreate.hold(-1f, 0f, "swipe")));

        assertEquals(1, SwipeToCreate.hold(-1f, 0f, "swipe"));
    }

    /** A switch that throws lets the swipe go and says the hook threw. */
    @Test
    public void aThrowingSwitchLetsTheSwipeGoAndIsReported() {
        assertEquals(0, SwipeToCreate.hold(-1f, 0f, "swipe", THROWS));

        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_CREATE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToCreate.DRAG + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test public void nativeReadGateStaysClosedUntilReadyAndEnabled() {
        assertEquals(1, SwipeToCreate.enabled());
        SettingsContextRule.withoutContext(() -> assertEquals(0, SwipeToCreate.enabled()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals(0, SwipeToCreate.enabled()));
        assertEquals(1, SwipeToCreate.enabled());
        Settings.STOP_SWIPE_TO_CREATE.save(false);
        assertEquals(0, SwipeToCreate.enabled());
    }

    @Test public void nativeReadGateFailuresLeaveTheStockSetterInCharge() {
        assertEquals(0, SwipeToCreate.enabled(THROWS));
        assertEquals(0, SwipeToCreate.enabled(() -> { throw new OutOfMemoryError("no memory"); }));
        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_CREATE).toString();
        assertTrue(missing, missing.contains("'swipe gate'"));
    }
}
