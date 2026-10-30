/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Swipe left to open the profile answers a reel's swipe gate, and when it leaves the answer to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SwipeToProfileTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private static final Object HOST = new Object();

    /** A tab host, its container and the profile panel, standing in for Instagram's. */
    private static final class FakePanels implements SwipeToProfile.Panels {
        final Object reels = new Object();
        final Object home = new Object();
        final Object controller = new Object();
        final Object container = new Object();
        final Object panel = new Object();
        Object view = new Object();
        Object tab = reels;
        boolean on;
        int turns;
        RuntimeException failure;
        final Set<Object> pagers = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Object, Boolean> inputs = new IdentityHashMap<>();
        boolean stockAnswer;
        int stockWalks;

        @Override public Object controller(Object host) { return host == HOST ? controller : null; }
        @Override public Object container(Object of) { return of == controller ? container : null; }
        @Override public Object tab(Object host) { return tab; }
        @Override public Object reelsTab() { return reels; }
        @Override public Object panel(Object of) { return of == container ? panel : null; }
        @Override public Object rightView(Object of) { return of == controller ? view : null; }
        @Override public boolean panelOn(Object of) { return on; }

        @Override
        public void turn(Object of, Object which, boolean value) {
            if (failure != null) throw failure;
            assertSame(container, of);
            assertSame(panel, which);
            on = value;
            turns++;
        }

        @Override
        public boolean stockScrolls(View view, int dx, int x, int y, boolean checkView, boolean skipOff) {
            stockWalks++;
            return stockAnswer;
        }

        @Override
        public boolean pager(Object view) {
            if (pagerFailure != null) throw pagerFailure;
            return view != null && pagers.contains(view);
        }

        @Override public boolean inputOn(Object pager) { return !Boolean.FALSE.equals(inputs.get(pager)); }
        @Override public void input(Object pager, boolean value) { inputs.put(pager, value); }

        RuntimeException pagerFailure;

        /** A view group standing in for a sideways pager, filling its parent. */
        FrameLayout addPager(ViewGroup parent) {
            FrameLayout pager = add(parent, new SidewaysGroup(parent.getContext()), 0, 100);
            pagers.add(pager);
            return pager;
        }

        /** A view group standing in for an up-and-down pager like the reels' own, filling its parent. */
        FrameLayout addUprightPager(ViewGroup parent) {
            FrameLayout pager = add(parent, new FrameLayout(parent.getContext()), 0, 100);
            pagers.add(pager);
            return pager;
        }
    }

    /** A view that can always scroll sideways, like a pager's list or a carousel. */
    private static final class Sideways extends View {
        Sideways(Context context) {
            super(context);
        }

        @Override
        public boolean canScrollHorizontally(int direction) {
            return true;
        }
    }

    /** A view group that can always scroll sideways, like a sideways pager. */
    private static final class SidewaysGroup extends FrameLayout {
        SidewaysGroup(Context context) {
            super(context);
        }

        @Override
        public boolean canScrollHorizontally(int direction) {
            return true;
        }
    }

    /** Adds [child] to [parent], laid out from [left] to [right] across and the full 100 down. */
    private static <T extends View> T add(ViewGroup parent, T child, int left, int right) {
        parent.addView(child);
        child.layout(left, 0, right, 100);
        return child;
    }

    private static FrameLayout container() {
        FrameLayout container = new FrameLayout(RuntimeEnvironment.getApplication());
        container.layout(0, 0, 100, 100);
        return container;
    }

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.REEL_SWIPE_TO_PROFILE.save(true);
        SwipeToProfile.forgetSetUpForTests();
        SwipeToProfile.tabChanging(new FakePanels());
        HookStatus.clear();
    }

    @After
    public void restore() {
        SwipeToProfile.forgetSetUpForTests();
        Settings.REEL_SWIPE_TO_PROFILE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnTheSwipeIsAllowed() {
        assertTrue(SwipeToProfile.allow(0));
        assertTrue(SwipeToProfile.allow(1));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    @Test
    public void offToStartAndOffLeaveTheAnswerToInstagram() {
        Settings.REEL_SWIPE_TO_PROFILE.resetToDefault();
        assertFalse(Settings.REEL_SWIPE_TO_PROFILE.get());
        assertFalse(SwipeToProfile.allow(0));
        assertTrue(SwipeToProfile.allow(1));
        Settings.REEL_SWIPE_TO_PROFILE.save(false);
        assertFalse(SwipeToProfile.allow(0));
        assertTrue(SwipeToProfile.allow(1));
    }

    @Test
    public void pausedAndUnreadyLeaveTheAnswerToInstagram() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(SwipeToProfile.allow(0));
        assertTrue(SwipeToProfile.allow(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(SwipeToProfile.allow(0)));

        assertTrue(SwipeToProfile.allow(0));
    }

    @Test
    public void aThrowingSwitchLeavesTheAnswerToInstagramAndIsReported() {
        assertFalse(SwipeToProfile.allow(false, THROWS));

        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void reachingReelsTurnsThePanelOnAndTheNextTabChangeTurnsItOff() {
        FakePanels panels = new FakePanels();

        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertTrue(panels.on);

        panels.tab = panels.home;
        SwipeToProfile.tabChanging(panels);
        assertFalse(panels.on);
        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertFalse(panels.on);
        assertEquals(2, panels.turns);
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** Instagram turned it on for this account, so it stays Instagram's to turn off. */
    @Test
    public void aPanelInstagramTurnedOnIsLeftAlone() {
        FakePanels panels = new FakePanels();
        panels.on = true;

        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        SwipeToProfile.tabChanging(panels);

        assertTrue(panels.on);
        assertEquals(0, panels.turns);
    }

    @Test
    public void offTurnsNothingOnButStillTurnsOffWhatItTurnedOn() {
        FakePanels panels = new FakePanels();

        SwipeToProfile.tabChanged(HOST, panels, () -> false);
        assertFalse(panels.on);

        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertTrue(panels.on);
        SwipeToProfile.tabChanging(panels);
        assertFalse(panels.on);
        SwipeToProfile.tabChanging(panels);
        assertEquals(2, panels.turns);
    }

    @Test
    public void aThrowingTurnIsReportedAndLeavesNothingToTurnOff() {
        FakePanels panels = new FakePanels();
        panels.failure = new IllegalStateException("container went away");

        SwipeToProfile.tabChanged(HOST, panels, () -> true);

        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.PANEL + "'"));
        panels.failure = null;
        SwipeToProfile.tabChanging(panels);
        assertEquals(0, panels.turns);
    }

    /** With the switch on, the setup builds the right panel the new tab bar leaves out, when the layout has its view. */
    @Test
    public void theSetUpBuildsTheRightPanelOnlyWithTheSwitchOnAndAView() {
        FakePanels panels = new FakePanels();

        assertTrue(SwipeToProfile.leaveOutRightPanel(true, panels.controller, panels, () -> false));
        assertFalse(SwipeToProfile.leaveOutRightPanel(false, panels.controller, panels, () -> true));
        panels.view = null;
        assertTrue(SwipeToProfile.leaveOutRightPanel(true, panels.controller, panels, () -> true));
        panels.view = new Object();
        assertFalse(SwipeToProfile.leaveOutRightPanel(true, panels.controller, panels, () -> true));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    @Test
    public void aThrowingSwitchLeavesTheRightPanelOutAndIsReported() {
        assertTrue(SwipeToProfile.leaveOutRightPanel(true, new Object(), new FakePanels(), THROWS));

        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.PANEL + "'"));
    }

    /** A right panel only HushGram had built starts on, so it's turned off away from Reels and with the switch off. */
    @Test
    public void aRightPanelHushGramBuiltIsOnlyOnForReels() {
        FakePanels panels = new FakePanels();
        panels.tab = panels.home;
        panels.on = true;
        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertTrue("Instagram's own panel is Instagram's", panels.on);

        assertFalse(SwipeToProfile.leaveOutRightPanel(true, panels.controller, panels, () -> true));
        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertFalse(panels.on);

        panels.tab = panels.reels;
        SwipeToProfile.tabChanging(panels);
        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertTrue(panels.on);
        SwipeToProfile.tabChanged(HOST, panels, () -> false);
        assertFalse(panels.on);
        assertEquals(3, panels.turns);
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** A later setup that builds the panel itself, as on an account with the old tab bar, leaves that panel Instagram's. */
    @Test
    public void aLaterSetupInstagramBuiltLeavesItsPanelOn() {
        FakePanels panels = new FakePanels();
        panels.tab = panels.home;
        assertFalse(SwipeToProfile.leaveOutRightPanel(true, panels.controller, panels, () -> true));

        assertFalse(SwipeToProfile.leaveOutRightPanel(false, panels.controller, panels, () -> true));
        panels.on = true;
        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertTrue("Instagram's own panel is Instagram's", panels.on);
        assertEquals(0, panels.turns);
    }

    /** Unpatched, the stubs answer nothing and the hooks do nothing. */
    @Test
    public void unpatchedStubsLeaveTheTabsAlone() {
        assertTrue(SwipeToProfile.leaveOutRightPanel(1, new Object()));
        assertFalse(SwipeToProfile.leaveOutRightPanel(0, new Object()));
        SwipeToProfile.tabChanged(HOST);
        SwipeToProfile.tabChanging(HOST);
        SwipeToProfile.pagerInput(new Object(), 1);
        SwipeToProfile.touched(container(), event(MotionEvent.ACTION_DOWN, 500));
        SwipeToProfile.touched(container(), event(MotionEvent.ACTION_MOVE, 100));
        assertFalse(SwipeToProfile.childScrolls(container(), 10, 50, 50, 0, 0));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** The tabs' pager and the list inside it can scroll, but with the switch on the swipe stays the container's. */
    @Test
    public void withTheSwitchOnThePagerAndItsListAreWalkedPastAndThePagerIsHeld() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        add(pager, new Sideways(container.getContext()), 0, 100);

        assertFalse(SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> true));

        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
        assertEquals(0, panels.stockWalks);
        SwipeToProfile.tabChanging(panels);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** A swipe right gets Instagram's walk with the switch on too, so the tabs' pager still slides to Home, and nothing is held. */
    @Test
    public void aSwipeRightGetsInstagramsWalkAndHoldsNothing() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        add(pager, new Sideways(container.getContext()), 0, 100);
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container, 10, 50, 50, false, true, panels, () -> true));

        assertEquals(1, panels.stockWalks);
        assertFalse(panels.inputs.containsKey(pager));
    }

    /** Inside a pager's page, a carousel under the finger still takes the swipe, and the pager above it isn't held. */
    @Test
    public void aSidewaysViewInsideAPageStillTakesTheSwipe() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        FrameLayout list = add(pager, new FrameLayout(container.getContext()), 0, 100);
        FrameLayout page = add(list, new FrameLayout(container.getContext()), 0, 100);
        add(page, new Sideways(container.getContext()), 0, 100);

        assertTrue(SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> true));
        assertFalse(panels.inputs.containsKey(pager));
    }

    /** Only the views under the finger count, in each one's own coordinates, and a hidden one doesn't. */
    @Test
    public void onlyVisibleViewsUnderTheFingerCount() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout half = add(container, new FrameLayout(container.getContext()), 50, 100);
        View carousel = add(half, new Sideways(container.getContext()), 10, 50);

        assertFalse(SwipeToProfile.childScrolls(container, -10, 40, 50, false, true, panels, () -> true));
        assertFalse(SwipeToProfile.childScrolls(container, -10, 55, 50, false, true, panels, () -> true));
        assertTrue(SwipeToProfile.childScrolls(container, -10, 70, 50, false, true, panels, () -> true));
        carousel.setVisibility(View.INVISIBLE);
        assertFalse(SwipeToProfile.childScrolls(container, -10, 70, 50, false, true, panels, () -> true));
    }

    /** A pager Instagram turned off stays Instagram's: not held, and not turned on at the next tab change. */
    @Test
    public void aPagerInstagramTurnedOffIsLeftAlone() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        panels.inputs.put(pager, false);

        assertFalse(SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> true));
        SwipeToProfile.tabChanging(panels);

        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
    }

    /** The reels' own up-and-down pager and what's inside it get Instagram's walk, and that pager is never held. */
    @Test
    public void theReelsUprightPagerGoesToInstagramsWalkAndIsNeverHeld() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout outer = panels.addPager(container);
        FrameLayout reels = panels.addUprightPager(outer);
        add(reels, new Sideways(container.getContext()), 0, 100);
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> true));
        assertEquals(1, panels.stockWalks);
        assertFalse(panels.inputs.containsKey(reels));
        assertFalse(panels.inputs.containsKey(outer));

        panels.stockAnswer = false;
        assertFalse(SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> true));
        assertFalse(panels.inputs.containsKey(reels));
        assertEquals(Boolean.FALSE, panels.inputs.get(outer));
    }

    /** A walk that throws is reported, gives a held pager back and leaves the swipe to Instagram. */
    @Test
    public void aThrowingWalkGivesHeldPagersBackAndIsReported() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
        panels.pagerFailure = new IllegalStateException("boom");
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> true));

        assertEquals(1, panels.stockWalks);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.PAGER + "'"));
    }

    /** The walk and the pager hold are open only after a tab change to Reels, with the switch on. */
    @Test
    public void onlyATabChangeToReelsOpensTheWalk() {
        FakePanels panels = new FakePanels();
        assertFalse(SwipeToProfile.reelsOpen(() -> true));

        SwipeToProfile.tabChanged(HOST, panels, () -> false);
        assertTrue(SwipeToProfile.reelsOpen(() -> true));
        assertFalse(SwipeToProfile.reelsOpen(() -> false));

        SwipeToProfile.tabChanging(panels);
        assertFalse(SwipeToProfile.reelsOpen(() -> true));

        panels.tab = panels.home;
        SwipeToProfile.tabChanged(HOST, panels, () -> true);
        assertFalse(SwipeToProfile.reelsOpen(() -> true));
    }

    /** With the switch off Instagram walks the views, and the pagers HushGram held get their input back. */
    @Test
    public void offLeavesTheWalkToInstagramAndGivesHeldPagersBack() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        FrameLayout pager = panels.addPager(container);
        add(pager, new Sideways(container.getContext()), 0, 100);
        SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> true);
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container, -10, 50, 50, false, true, panels, () -> false));

        assertEquals(1, panels.stockWalks);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
    }

    @Test
    public void aThrowingSwitchLeavesTheWalkToInstagramAndIsReported() {
        FakePanels panels = new FakePanels();
        panels.stockAnswer = true;

        assertTrue(SwipeToProfile.childScrolls(container(), -10, 50, 50, false, true, panels, THROWS));

        assertEquals(1, panels.stockWalks);
        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.SWITCH + "'"));
    }

    /** As a touch starts the tabs' pager gets what Instagram asks for, and an off Instagram asked for outlasts the next tab change. */
    @Test
    public void theTabsPagerGetsWhatInstagramAsksAsATouchStarts() {
        FakePanels panels = new FakePanels();
        Object pager = new Object();

        SwipeToProfile.pagerInput(pager, true, panels);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        SwipeToProfile.pagerInput(pager, false, panels);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
        SwipeToProfile.tabChanging(panels);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));

        SwipeToProfile.pagerInput(null, true, panels);
        assertFalse(panels.inputs.containsKey(null));
    }

    /** On Reels with the switch on, a move left by the touch slop holds the tabs' pager until the next touch starts or the next tab change. */
    @Test
    public void aMoveLeftByTheSlopHoldsTheTabsPagerUntilTheNextTouch() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        Object pager = new Object();
        startTouch(container, panels, pager, true);

        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 500 - slop() + 1), panels, () -> true);
        assertEquals("short of the slop", Boolean.TRUE, panels.inputs.get(pager));
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 500 - slop()), panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));

        startTouch(container, panels, pager, true);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));

        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
        SwipeToProfile.tabChanging(panels);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** A move right, however far, leaves the tabs' pager on, so it still slides to Home. */
    @Test
    public void aMoveRightLeavesTheTabsPagerOn() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        Object pager = new Object();
        startTouch(container, panels, pager, true);

        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 500 + 20 * slop()), panels, () -> true);

        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
    }

    /** The next touch gives a held tabs' pager its input back even when Instagram doesn't set it then, and leaves an off of Instagram's alone. */
    @Test
    public void theNextTouchGivesTheTabsPagerBackWithoutInstagramsCall() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        Object pager = new Object();
        startTouch(container, panels, pager, true);
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));

        SwipeToProfile.touched(container, event(MotionEvent.ACTION_DOWN, 500), panels, () -> true);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 500 + 20 * slop()), panels, () -> true);
        assertEquals("a swipe right after it", Boolean.TRUE, panels.inputs.get(pager));

        SwipeToProfile.pagerInput(pager, false, panels);
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, () -> true);
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_DOWN, 500), panels, () -> true);
        assertEquals("Instagram's own off", Boolean.FALSE, panels.inputs.get(pager));
    }

    /** Once a second finger lands, a move left holds nothing until the next touch starts. */
    @Test
    public void aSecondFingerLeavesTheTabsPagerOn() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        Object pager = new Object();
        startTouch(container, panels, pager, true);

        SwipeToProfile.touched(container, event(MotionEvent.ACTION_POINTER_DOWN, 500), panels, () -> true);
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, () -> true);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));

        startTouch(container, panels, pager, true);
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, () -> true);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
    }

    /** With the switch off a move left changes nothing, and a pager Instagram turned off as the touch started stays Instagram's. */
    @Test
    public void offOrAPagerInstagramTurnedOffIsLeftAloneOnAMoveLeft() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        Object pager = new Object();
        startTouch(container, panels, pager, true);
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, () -> false);
        assertEquals(Boolean.TRUE, panels.inputs.get(pager));

        startTouch(container, panels, pager, false);
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, () -> true);
        SwipeToProfile.tabChanging(panels);
        assertEquals(Boolean.FALSE, panels.inputs.get(pager));
    }

    @Test
    public void aThrowingSwitchLeavesTheTabsPagerOnAndIsReported() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        Object pager = new Object();
        startTouch(container, panels, pager, true);

        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, THROWS);

        assertEquals(Boolean.TRUE, panels.inputs.get(pager));
        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.SWITCH + "'"));
    }

    /** A right swipe on the profile a left swipe on a reel opened takes the touch from the views under it and goes back on the lift. */
    @Test
    public void aRightSwipeOnTheProfileTheReelOpenedGoesBack() {
        Screen screen = screen();
        assertTrue(SwipeToProfile.allow(false, () -> true));
        resumeProfile();
        float x = 20;
        float y = headerY(screen);

        assertFalse(dispatch(screen, MotionEvent.ACTION_DOWN, x, y, () -> true));
        assertFalse("within the slop", dispatch(screen, MotionEvent.ACTION_MOVE, x + slop() / 2f, y, () -> true));
        assertTrue(dispatch(screen, MotionEvent.ACTION_MOVE, x + 2 * slop(), y + slop() / 2f, () -> true));
        assertTrue(dispatch(screen, MotionEvent.ACTION_MOVE, x + 4 * slop(), y, () -> true));
        assertTrue(dispatch(screen, MotionEvent.ACTION_UP, x + width(screen) / 2f, y, () -> true));
        ShadowLooper.idleMainLooper();

        assertEquals(1, screen.backs);
        assertEquals(where(screen), Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), screen.header.actions);
        assertTrue(HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString(), HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).isEmpty());
    }

    /** A left swipe the container held the tabs' pager for opened the profile too, so a right swipe on it goes back. */
    @Test
    public void aLeftSwipeThePagerWasHeldForCountsToo() {
        FakePanels panels = new FakePanels();
        FrameLayout container = container();
        Object pager = new Object();
        startTouch(container, panels, pager, true);
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_MOVE, 100), panels, () -> true);
        resumeProfile();
        collectGarbage();

        assertTrue(swipesBack(screen(), () -> true));
    }

    /** A close lifted short of twice the touch slop, and of a tenth of the screen's width, keeps the touch from the views but doesn't go back. */
    @Test
    public void aShortRightSwipeDoesntGoBack() {
        Screen screen = screen();
        SwipeToProfile.allow(false, () -> true);
        resumeProfile();
        float y = headerY(screen);

        dispatch(screen, MotionEvent.ACTION_DOWN, 20, y, () -> true);
        assertTrue(dispatch(screen, MotionEvent.ACTION_MOVE, 20 + slop(), y, () -> true));
        assertTrue(dispatch(screen, MotionEvent.ACTION_UP, 20 + 2 * slop() - 1, y, () -> true));
        ShadowLooper.idleMainLooper();

        assertEquals(0, screen.backs);
        assertEquals(where(screen), Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), screen.header.actions);
    }

    /** A left swipe, and one going more up or down than right, stay with the profile's own views. */
    @Test
    public void leftAndUpAndDownSwipesStayInstagrams() {
        Screen screen = screen();
        SwipeToProfile.allow(false, () -> true);
        resumeProfile();
        float y = headerY(screen);
        float middle = width(screen) / 2f;

        assertFalse(dispatch(screen, MotionEvent.ACTION_DOWN, middle, y, () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_MOVE, middle - 2 * slop(), y, () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_UP, middle - 2 * slop(), y, () -> true));

        assertFalse(dispatch(screen, MotionEvent.ACTION_DOWN, 20, y, () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_MOVE, 20 + slop(), y + 2 * slop(), () -> true));
        assertFalse("given back for the rest of the touch", dispatch(screen, MotionEvent.ACTION_MOVE, 20 + 10 * slop(), y + 2 * slop(), () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_UP, 20 + width(screen) / 2f, y, () -> true));
        ShadowLooper.idleMainLooper();

        assertEquals(0, screen.backs);
        assertFalse(screen.header.actions.contains(MotionEvent.ACTION_CANCEL));
    }

    /** A view under the finger that can scroll left, like the grid past its first tab, keeps the swipe. */
    @Test
    public void aViewThatCanScrollLeftKeepsTheSwipe() {
        Screen screen = screen();
        SwipeToProfile.allow(false, () -> true);
        resumeProfile();
        int[] strip = new int[2];
        screen.strip.getLocationInWindow(strip);
        float y = strip[1] + screen.strip.getHeight() / 2f;

        assertFalse(dispatch(screen, MotionEvent.ACTION_DOWN, 20, y, () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_MOVE, 20 + 2 * slop(), y, () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_UP, 20 + width(screen) / 2f, y, () -> true));
        ShadowLooper.idleMainLooper();
        assertEquals(0, screen.backs);

        assertTrue("the header above it", swipesBack(screen, () -> true));
    }

    /** A second finger, before the swipe is clear or after, never goes back. */
    @Test
    public void aSecondFingerNeverGoesBack() {
        Screen screen = screen();
        SwipeToProfile.allow(false, () -> true);
        resumeProfile();
        float y = headerY(screen);

        int secondDown = MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        int secondUp = MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        float far = 20 + width(screen) / 2f;

        dispatch(screen, MotionEvent.ACTION_DOWN, 1, 20, y, () -> true);
        assertFalse(dispatch(screen, secondDown, 2, 20, y, () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_MOVE, 2, 20 + 2 * slop(), y, () -> true));
        assertFalse(dispatch(screen, secondUp, 2, far, y, () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_UP, 1, far, y, () -> true));

        dispatch(screen, MotionEvent.ACTION_DOWN, 1, 20, y, () -> true);
        assertTrue(dispatch(screen, MotionEvent.ACTION_MOVE, 1, 20 + 2 * slop(), y, () -> true));
        assertTrue(dispatch(screen, secondDown, 2, 20 + 2 * slop(), y, () -> true));
        assertTrue(dispatch(screen, MotionEvent.ACTION_MOVE, 2, far, y, () -> true));
        assertTrue(dispatch(screen, secondUp, 2, far, y, () -> true));
        assertTrue(dispatch(screen, MotionEvent.ACTION_UP, 1, far, y, () -> true));
        ShadowLooper.idleMainLooper();

        assertEquals(0, screen.backs);
    }

    /** Off, paused or the settings not read yet, a right swipe on the profile stays Instagram's. */
    @Test
    public void offPausedAndUnreadyLeaveTheProfileAlone() {
        Screen screen = screen();
        SwipeToProfile.allow(false, () -> true);
        resumeProfile();

        Settings.REEL_SWIPE_TO_PROFILE.save(false);
        assertFalse("off", swipesBack(screen, null));
        assertEquals(where(screen), Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), screen.header.actions);
        Settings.REEL_SWIPE_TO_PROFILE.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", swipesBack(screen, null));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("unready", swipesBack(screen, null)));

        assertTrue("on", swipesBack(screen, null));
    }

    /** Only the profile a left swipe on a reel opened, while it shows, closes on a right swipe. */
    @Test
    public void aProfileTheSwipeDidntOpenStaysInstagrams() {
        Screen screen = screen();
        resumeProfile();
        assertFalse("opened some other way", swipesBack(screen, () -> true));

        SwipeToProfile.allow(false, () -> true);
        SystemClock.sleep(SwipeToProfile.OPENS_WITHIN_MS + 1);
        resumeProfile();
        assertFalse("shown too long after the swipe", swipesBack(screen, () -> true));

        SwipeToProfile.allow(false, () -> true);
        Object swiped = new Object();
        SwipeToProfile.profileResumed(swiped);
        SwipeToProfile.profilePaused(swiped);
        assertFalse("paused", swipesBack(screen, () -> true));

        SystemClock.sleep(SwipeToProfile.OPENS_WITHIN_MS + 1);
        Object over = new Object();
        SwipeToProfile.profileResumed(over);
        assertFalse("another profile over it", swipesBack(screen, () -> true));
        SwipeToProfile.profilePaused(over);
        SwipeToProfile.profileResumed(swiped);
        assertTrue("back on it", swipesBack(screen, () -> true));
    }

    @Test
    public void aThrowingSwitchLeavesTheProfileAloneAndIsReported() {
        Screen screen = screen();
        SwipeToProfile.allow(false, () -> true);
        resumeProfile();

        assertFalse(swipesBack(screen, THROWS));

        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.SWITCH + "'"));
    }

    /** A screen that fails the checks of a right swipe, here one attached to nothing, leaves the touch to Instagram and is reported. */
    @Test
    public void aFailedCloseLeavesTheTouchToInstagramAndIsReported() {
        SwipeToProfile.allow(false, () -> true);
        resumeProfile();
        Activity unattached = new Activity();
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20, 50, 0);
        MotionEvent move = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 520, 50, 0);

        assertFalse(SwipeToProfile.closesProfile(unattached, down, () -> true));
        assertFalse(SwipeToProfile.closesProfile(unattached, move, () -> true));

        String missing = HookStatus.missing(FamilyNames.SWIPE_TO_PROFILE).toString();
        assertTrue(missing, missing.contains("'" + SwipeToProfile.CLOSE + "'"));
    }

    /** A profile opened from the one the swipe opened, by a mention say, doesn't take its place, even inside the swipe's window. */
    @Test
    public void aProfileOpenedFromTheSwipedOneDoesntTakeItsPlace() {
        Screen screen = screen();
        SwipeToProfile.allow(false, () -> true);
        Object swiped = new Object();
        SwipeToProfile.profileResumed(swiped);
        float y = headerY(screen);

        dispatch(screen, MotionEvent.ACTION_DOWN, 20, y, () -> true);
        dispatch(screen, MotionEvent.ACTION_UP, 20, y, () -> true);
        SwipeToProfile.profilePaused(swiped);
        Object mentioned = new Object();
        SwipeToProfile.profileResumed(mentioned);
        assertFalse("the profile the tap opened", swipesBack(screen, () -> true));

        SwipeToProfile.profilePaused(mentioned);
        SwipeToProfile.profileResumed(swiped);
        assertTrue("back on the one the swipe opened", swipesBack(screen, () -> true));
    }

    /** A swipe Instagram allows on its own opens a profile that closes the same way, while the switch is on. */
    @Test
    public void aSwipeInstagramAllowsItselfClosesTheSameWay() {
        Screen screen = screen();
        assertTrue(SwipeToProfile.allow(true, () -> false));
        resumeProfile();
        assertFalse("the switch was off at the swipe", swipesBack(screen, () -> true));

        assertTrue(SwipeToProfile.allow(true, () -> true));
        resumeProfile();
        assertTrue(swipesBack(screen, () -> true));
    }

    /** A close the system cancels, a new touch after a close that never lifted, and a profile gone before the posted Back runs. */
    @Test
    public void aCancelledCloseOrAGoneProfileDoesntGoBack() {
        Screen screen = screen();
        SwipeToProfile.allow(false, () -> true);
        Object profile = new Object();
        SwipeToProfile.profileResumed(profile);
        float y = headerY(screen);
        float far = 20 + width(screen) / 2f;

        dispatch(screen, MotionEvent.ACTION_DOWN, 20, y, () -> true);
        assertTrue(dispatch(screen, MotionEvent.ACTION_MOVE, 20 + 2 * slop(), y, () -> true));
        assertTrue("the cancel ends the close", dispatch(screen, MotionEvent.ACTION_CANCEL, far, y, () -> true));
        assertFalse("what follows is Instagram's", dispatch(screen, MotionEvent.ACTION_MOVE, far, y, () -> true));
        ShadowLooper.idleMainLooper();
        assertEquals(0, screen.backs);

        dispatch(screen, MotionEvent.ACTION_DOWN, 20, y, () -> true);
        assertTrue(dispatch(screen, MotionEvent.ACTION_MOVE, 20 + 2 * slop(), y, () -> true));
        assertTrue("a touch after one that never lifted starts over", swipesBack(screen, () -> true));
        assertEquals(1, screen.backs);

        dispatch(screen, MotionEvent.ACTION_DOWN, 20, y, () -> true);
        dispatch(screen, MotionEvent.ACTION_MOVE, 20 + 2 * slop(), y, () -> true);
        dispatch(screen, MotionEvent.ACTION_UP, far, y, () -> true);
        SwipeToProfile.profilePaused(profile);
        ShadowLooper.idleMainLooper();
        assertEquals("paused before the Back ran", 1, screen.backs);
    }

    /** A view answering for the last touch that went down on it, as a Compose view does, is asked once it was handed this one. */
    @Test
    public void aViewAnsweringForItsLastTouchIsAskedAfterItGetsThisOne() {
        RowScreen screen = Robolectric.buildActivity(RowScreen.class).setup().get();
        ShadowLooper.idleMainLooper();
        SwipeToProfile.allow(false, () -> true);
        resumeProfile();
        int[] at = new int[2];
        screen.row.getLocationInWindow(at);
        float high = at[1] + screen.row.getHeight() / 3f;
        float low = at[1] + screen.row.getHeight() * 5 / 6f;
        float far = 20 + width(screen) / 2f;

        dispatch(screen, MotionEvent.ACTION_DOWN, 20, low, () -> true);
        dispatch(screen, MotionEvent.ACTION_UP, 20, low, () -> true);

        assertFalse(dispatch(screen, MotionEvent.ACTION_DOWN, 20, high, () -> true));
        assertFalse("the row scrolls left up there", dispatch(screen, MotionEvent.ACTION_MOVE, 20 + 2 * slop(), high, () -> true));
        assertFalse(dispatch(screen, MotionEvent.ACTION_UP, far, high, () -> true));
        ShadowLooper.idleMainLooper();
        assertEquals(0, screen.backs);

        dispatch(screen, MotionEvent.ACTION_DOWN, 20, low, () -> true);
        assertTrue("and not down here", dispatch(screen, MotionEvent.ACTION_MOVE, 20 + 2 * slop(), low, () -> true));
        assertTrue(dispatch(screen, MotionEvent.ACTION_UP, far, low, () -> true));
        ShadowLooper.idleMainLooper();
        assertEquals(1, screen.backs);
    }

    /** A screen counting the Backs it gets: a header filling it that records each touch it's handed, and a strip along the bottom that can scroll sideways. */
    public static final class Screen extends Activity {
        int backs;
        Recorder header;
        View strip;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(this);
            header = new Recorder(this);
            root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            strip = new Sideways(this);
            root.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40, Gravity.BOTTOM));
            setContentView(root);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onBackPressed() {
            backs++;
        }
    }

    /** A screen counting the Backs it gets, filled by a {@link LastDown} row. */
    public static final class RowScreen extends Activity {
        int backs;
        View row;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(this);
            row = new LastDown(this);
            root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onBackPressed() {
            backs++;
        }
    }

    /** A view taking every touch that, like a Compose view, says whether it can scroll left for where the last touch went down on it: yes in its top half. */
    private static final class LastDown extends View {
        float downY = -1;

        LastDown(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downY = event.getY();
            return true;
        }

        @Override
        public boolean canScrollHorizontally(int direction) {
            return direction < 0 && downY >= 0 && downY < getHeight() / 2f;
        }
    }

    /** A view taking every touch, recording each action it's handed. */
    private static final class Recorder extends View {
        final List<Integer> actions = new ArrayList<>();

        Recorder(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            actions.add(event.getActionMasked());
            return true;
        }
    }

    private static Screen screen() {
        Screen screen = Robolectric.buildActivity(Screen.class).setup().get();
        ShadowLooper.idleMainLooper();
        return screen;
    }

    /** A third of the way down the header, well above the strip, in the window's coordinates. */
    private static float headerY(Screen screen) {
        int[] at = new int[2];
        screen.header.getLocationInWindow(at);
        return at[1] + screen.header.getHeight() / 3f;
    }

    /** Where the header sits in the window, for a failure message. */
    private static String where(Screen screen) {
        int[] at = new int[2];
        screen.header.getLocationInWindow(at);
        return "header at " + at[0] + "," + at[1] + " size " + screen.header.getWidth() + "x" + screen.header.getHeight() + ", touched at y " + headerY(screen);
    }

    private static int width(Activity screen) {
        return screen.getResources().getDisplayMetrics().widthPixels;
    }

    private static boolean dispatch(Activity screen, int action, float x, float y, BooleanSupplier on) {
        return dispatch(screen, action, 1, x, y, on);
    }

    /**
     * Hands a touchscreen event with one or two fingers, the first at (x, y) and the second 20 below
     * it, to the screen as the patched touch dispatch does: to HushGram first, through the switch the
     * hook reads when {@code on} is null, then to the views unless HushGram took it. Views on API 37
     * skip an event with no input source.
     */
    private static boolean dispatch(Activity screen, int action, int fingers, float x, float y, BooleanSupplier on) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[fingers];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[fingers];
        for (int i = 0; i < fingers; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = x;
            coords[i].y = y + 20 * i;
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(0, 0, action, fingers, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            boolean taken = on == null ? SwipeToProfile.closesProfile(screen, event) : SwipeToProfile.closesProfile(screen, event, on);
            if (!taken) screen.getWindow().superDispatchTouchEvent(event);
            return taken;
        } finally {
            event.recycle();
        }
    }

    /** A right swipe on the header, lifted half the screen's width right of where it went down; whether it went back. */
    private static boolean swipesBack(Screen screen, BooleanSupplier on) {
        int before = screen.backs;
        float y = headerY(screen);
        dispatch(screen, MotionEvent.ACTION_DOWN, 20, y, on);
        dispatch(screen, MotionEvent.ACTION_MOVE, 20 + 2 * slop(), y, on);
        dispatch(screen, MotionEvent.ACTION_UP, 20 + width(screen) / 2f, y, on);
        ShadowLooper.idleMainLooper();
        return screen.backs > before;
    }

    /** A touch going down at 500 across, and the container setting the tabs' pager input as Instagram does then. */
    private static void startTouch(FrameLayout container, FakePanels panels, Object pager, boolean wanted) {
        SwipeToProfile.touched(container, event(MotionEvent.ACTION_DOWN, 500), panels, () -> true);
        SwipeToProfile.pagerInput(pager, wanted, panels);
    }

    private static MotionEvent event(int action, float x) {
        return MotionEvent.obtain(0, 0, action, x, 50, 0);
    }

    private static int slop() {
        return ViewConfiguration.get(RuntimeEnvironment.getApplication()).getScaledTouchSlop();
    }

    /**
     * Profiles the test resumed, held as Instagram holds the fragment it shows. SwipeToProfile keeps
     * them weakly, so one held by nothing could be collected mid-test and read as "no profile".
     */
    private final List<Object> profiles = new ArrayList<>();

    private Object resumeProfile() {
        Object profile = new Object();
        profiles.add(profile);
        SwipeToProfile.profileResumed(profile);
        return profile;
    }

    /** Runs the collector until an object nobody holds is gone, as a loaded full suite does at any point. */
    private static void collectGarbage() {
        java.lang.ref.WeakReference<Object> gone = new java.lang.ref.WeakReference<>(new Object());
        for (int i = 0; i < 50 && gone.get() != null; i++) System.gc();
        assertTrue("the collector never ran", gone.get() == null);
    }
}
