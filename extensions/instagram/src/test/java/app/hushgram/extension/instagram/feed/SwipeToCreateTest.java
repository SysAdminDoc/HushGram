/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
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
        SwipeToCreate.forgetSwipeForTests();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.STOP_SWIPE_TO_CREATE.resetToDefault();
        Settings.HOME_SWIPE_OPENS.resetToDefault();
        SwipeToCreate.forgetSwipeForTests();
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

    private final Context context = RuntimeEnvironment.getApplication();

    /** A tab button that counts its clicks. */
    private View tabButton(int[] clicks) {
        View tab = new View(context);
        tab.setOnClickListener(view -> clicks[0]++);
        return tab;
    }

    private int step(float target, View tab, boolean switchOn) {
        return SwipeToCreate.hold(target, 0f, "swipe", () -> switchOn, () -> HomeSwipe.MESSAGES, picked -> tab);
    }

    /** Held at Home, each step comes in as how far it went. The tab opens when they add up to the mark, once. */
    @Test
    public void aPickedTabHoldsEveryStepAndOpensOnceTheStepsAddUp() {
        int[] clicks = {0};
        View tab = tabButton(clicks);
        float step = SwipeToCreate.OPEN_AT * 0.4f;

        assertEquals("the panel stays put with the switch off", 1, step(-step, tab, false));
        assertEquals("one step is not a swipe", 0, clicks[0]);
        assertEquals(1, step(-step, tab, false));
        assertEquals("two steps are short of the mark", 0, clicks[0]);
        assertEquals(1, step(-step, tab, false));
        assertEquals("the third step passes it", 1, clicks[0]);
        assertEquals(1, step(-step, tab, false));
        assertEquals("and the rest of the swipe opens nothing more", 1, clicks[0]);
    }

    /** A flick that never added up to the mark settles toward the camera, and that opens the tab at once. */
    @Test
    public void aFlingOpensThePickedTabAtOnce() {
        int[] clicks = {0};
        assertEquals(1, step(-1f, tabButton(clicks), false));
        assertEquals(1, clicks[0]);
    }

    /** The next swipe starts counting over. */
    @Test
    public void aLaterSwipeOpensTheTabAgain() {
        int[] clicks = {0};
        View tab = tabButton(clicks);
        assertEquals(1, step(-1f, tab, false));
        ShadowSystemClock.advanceBy(Duration.ofMillis(SwipeToCreate.NEW_SWIPE_AFTER_MS + 1));
        assertEquals(1, step(-SwipeToCreate.OPEN_AT, tab, false));
        assertEquals(2, clicks[0]);
    }

    /** A tab that isn't on the bar (hidden, or a screen with no bar) leaves the swipe to the switch. */
    @Test
    public void aTabWithNoButtonLeavesTheSwipeToTheSwitch() {
        assertEquals(0, step(-1f, null, false));
        SwipeToCreate.forgetSwipeForTests();
        assertEquals(1, step(-1f, null, true));
    }

    /** The camera picked, nothing is looked up or clicked and the switch decides, as before the choice. */
    @Test
    public void theCameraChoiceLeavesTheSwipeAsItWas() {
        int[] clicks = {0};
        View tab = tabButton(clicks);
        assertEquals(0, SwipeToCreate.hold(-1f, 0f, "swipe", () -> false, () -> HomeSwipe.CAMERA, picked -> tab));
        assertEquals(1, SwipeToCreate.hold(-1f, 0f, "swipe", () -> true, () -> HomeSwipe.CAMERA, picked -> tab));
        assertEquals(0, clicks[0]);
    }

    /** Only a finger's move toward the camera counts: the + button and a swipe back out open nothing. */
    @Test
    public void theTabIsOnlyOpenedByASwipeTowardTheCamera() {
        int[] clicks = {0};
        View tab = tabButton(clicks);
        assertEquals(0, SwipeToCreate.hold(-1f, 0f, "camera_action_bar_button", () -> false, () -> HomeSwipe.REELS, picked -> tab));
        assertEquals(0, SwipeToCreate.hold(0f, -0.3f, "swipe", () -> false, () -> HomeSwipe.REELS, picked -> tab));
        assertEquals(0, clicks[0]);
    }

    /** With a tab picked the gate opens though the switch is off, or the hook would never run. */
    @Test
    public void aPickedTabOpensTheGateWithTheSwitchOff() {
        Settings.STOP_SWIPE_TO_CREATE.save(false);
        Settings.HOME_SWIPE_OPENS.save(HomeSwipe.PROFILE);
        assertEquals(1, SwipeToCreate.enabled());
        Settings.HOME_SWIPE_OPENS.save(HomeSwipe.CAMERA);
        assertEquals(0, SwipeToCreate.enabled());
    }

    /** The button is the one on the tab bar, and a missing bar or tab finds nothing. */
    @Test
    public void theButtonIsLookedUpInsideTheTabBar() {
        FrameLayout root = new FrameLayout(context);
        FrameLayout bar = new FrameLayout(context);
        bar.setId(11);
        View tab = new View(context);
        tab.setId(12);
        View sameIdOutside = new View(context);
        sameIdOutside.setId(12);
        root.addView(sameIdOutside);
        root.addView(bar);
        bar.addView(tab);

        assertTrue(SwipeToCreate.buttonIn(root, 11, 12) == tab);
        assertTrue(SwipeToCreate.buttonIn(root, 0, 12) == null);
        assertTrue(SwipeToCreate.buttonIn(root, 11, 0) == null);
        assertTrue(SwipeToCreate.buttonIn(root, 99, 12) == null);
    }

    /** A choice that throws lets the swipe go. */
    @Test
    public void aThrowingChoiceLetsTheSwipeGo() {
        assertEquals(0, SwipeToCreate.hold(-1f, 0f, "swipe", () -> true, () -> {
            throw new IllegalStateException("settings went away");
        }, picked -> null));
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
