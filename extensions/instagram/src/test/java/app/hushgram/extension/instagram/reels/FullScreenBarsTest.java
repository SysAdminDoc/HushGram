/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** Full screen Reels and Home: the bars hide on the screens the switches name and come back when they're left. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FullScreenBarsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int VIEWER = 7101, BAR = 7102, REELS_TAB = 7103, HOME_TAB = 7104, OTHER_TAB = 7105;

    /** The window's own size: a view is only on screen as far as the window goes. */
    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private View viewer;
    private ViewGroup bar;
    private View reelsTab, homeTab, otherTab;

    @Before public void prepare() {
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FULL_SCREEN_HOME.resetToDefault();
        FullScreenBars.idForTests(FullScreenBars.VIEWER, VIEWER);
        FullScreenBars.idForTests(FullScreenBars.TAB_BAR, BAR);
        FullScreenBars.idForTests(FullScreenBars.REELS_TAB, REELS_TAB);
        FullScreenBars.idForTests(FullScreenBars.HOME_TAB, HOME_TAB);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);
        root = new FrameLayout(activity);
        activity.setContentView(root);
        bar = new LinearLayout(activity);
        bar.setId(BAR);
        reelsTab = tab(REELS_TAB);
        homeTab = tab(HOME_TAB);
        otherTab = tab(OTHER_TAB);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50));
        layout();
    }

    @After public void close() {
        FullScreenBars.release(activity);
        controller.close();
        Settings.FULL_SCREEN_REELS.resetToDefault();
        Settings.FULL_SCREEN_HOME.resetToDefault();
    }

    private View tab(int id) {
        View tab = new View(activity);
        tab.setId(id);
        bar.addView(tab, new LinearLayout.LayoutParams(10, 40));
        return tab;
    }

    private void showViewer(int width, int height) {
        if (viewer != null) root.removeView(viewer);
        viewer = new FrameLayout(activity);
        viewer.setId(VIEWER);
        root.addView(viewer, new FrameLayout.LayoutParams(width, height));
        layout();
    }

    private void select(View tab) {
        reelsTab.setSelected(false);
        homeTab.setSelected(false);
        otherTab.setSelected(false);
        tab.setSelected(true);
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    private FullScreenBars.Session watch() {
        FullScreenBars.track(activity);
        FullScreenBars.Session session = FullScreenBars.sessionFor(activity);
        assertNotNull(session);
        session.evaluate();
        return session;
    }

    private boolean barsGone() {
        int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
        return (flags & FullScreenBars.IMMERSIVE_FLAGS) == FullScreenBars.IMMERSIVE_FLAGS;
    }

    private FullScreenBars.Screen screen() {
        return FullScreenBars.screenOf(activity.getWindow().getDecorView());
    }

    @Test public void bothSwitchesStartOffAndNothingHides() {
        assertFalse(Settings.FULL_SCREEN_REELS.get());
        assertFalse(Settings.FULL_SCREEN_HOME.get());
        showViewer(W, H);
        assertFalse(watch().isHidden());
        assertFalse(barsGone());
    }

    @Test public void aViewerFillingTheWindowHidesTheBarsAndLeavingItBringsThemBack() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertTrue(barsGone());
        root.removeView(viewer);
        viewer = null;
        layout();
        session.evaluate();
        assertFalse(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    @Test public void aViewerOnlyPartlyOnScreenIsNotReels() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H / 3);
        assertFalse(watch().isHidden());
        assertEquals(FullScreenBars.Screen.OTHER, screen());
    }

    @Test public void withoutAViewerTheSelectedReelsTabIsReels() {
        Settings.FULL_SCREEN_REELS.save(true);
        select(reelsTab);
        assertEquals(FullScreenBars.Screen.REELS, screen());
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        select(otherTab);
        session.evaluate();
        assertFalse(session.isHidden());
    }

    @Test public void homeHidesTheBarsOnlyWithItsOwnSwitch() {
        select(homeTab);
        assertEquals(FullScreenBars.Screen.HOME, screen());
        FullScreenBars.Session session = watch();
        assertFalse(session.isHidden());
        Settings.FULL_SCREEN_REELS.save(true);
        session.evaluate();
        assertFalse("the Reels switch is not Home's", session.isHidden());
        Settings.FULL_SCREEN_HOME.save(true);
        session.evaluate();
        assertTrue(session.isHidden());
    }

    @Test public void homesSwitchDoesNotHideReels() {
        Settings.FULL_SCREEN_HOME.save(true);
        showViewer(W, H);
        assertFalse("Home's switch is not Reels'", watch().isHidden());
    }

    @Test public void aViewerOverTheHomeTabIsReelsNotHome() {
        select(homeTab);
        showViewer(W, H);
        assertEquals(FullScreenBars.Screen.REELS, screen());
    }

    @Test public void turningTheSwitchOffBringsTheBarsBack() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        Settings.FULL_SCREEN_REELS.save(false);
        session.evaluate();
        assertFalse(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    @Test public void pausingTheActivityBringsTheBarsBackAndForgetsIt() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        FullScreenBars.release(activity);
        assertFalse(session.isHidden());
        assertNull(FullScreenBars.sessionFor(activity));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    @Test public void theWindowsOwnFlagsAreKeptAndPutBack() {
        View decor = activity.getWindow().getDecorView();
        int before = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(before);
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            assertEquals(before | FullScreenBars.IMMERSIVE_FLAGS, decor.getSystemUiVisibility());
            // The window asking again, as it does when it gets focus back, must not save the hidden state.
            session.onWindowFocusChanged(true);
            FullScreenBars.release(activity);
            assertEquals(before, decor.getSystemUiVisibility());
        } else {
            session.onWindowFocusChanged(true);
            FullScreenBars.release(activity);
        }
        assertFalse(session.isHidden());
    }

    /**
     * A dialog over Reels that leads to a profile: focus comes back on a screen that doesn't want the
     * bars hidden, and the window still asks for them hidden, so they have to be put back right then.
     */
    @Test public void focusComingBackAwayFromReelsBringsTheBarsBack() {
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        root.removeView(viewer);
        viewer = null;
        layout();
        session.onWindowFocusChanged(true);
        assertFalse(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) assertFalse(barsGone());
    }

    /** A flag Instagram sets while the bars are hidden (a light status bar on the next screen) stays. */
    @Test public void flagsSetWhileTheBarsAreHiddenStayWhenTheyComeBack() {
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        Settings.FULL_SCREEN_REELS.save(true);
        showViewer(W, H);
        FullScreenBars.Session session = watch();
        assertTrue(session.isHidden());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            FullScreenBars.release(activity);
            assertEquals(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, decor.getSystemUiVisibility());
        }
    }

    /** Only the immersive flags HushGram added come off; one the window had already stays. */
    @Test public void onlyTheAddedImmersiveFlagsComeOff() {
        int before = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        int now = before | FullScreenBars.IMMERSIVE_FLAGS | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        assertEquals(before | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, FullScreenBars.withoutAdded(now, before));
        assertEquals(0, FullScreenBars.withoutAdded(FullScreenBars.IMMERSIVE_FLAGS, 0));
    }

    /** On Android 11 and newer only the bars that were showing before the hide are shown again. */
    @Config(sdk = 37)
    @Test public void onlyTheBarsThatWereShowingComeBack() {
        int status = android.view.WindowInsets.Type.statusBars(), navigation = android.view.WindowInsets.Type.navigationBars();
        android.view.WindowInsets noStatus = new android.view.WindowInsets.Builder()
                .setVisible(status, false).setVisible(navigation, true).build();
        assertEquals(navigation, FullScreenBars.visibleBars(noStatus));
        android.view.WindowInsets both = new android.view.WindowInsets.Builder()
                .setVisible(status, true).setVisible(navigation, true).build();
        assertEquals(status | navigation, FullScreenBars.visibleBars(both));
        assertEquals("no insets shows both", status | navigation, FullScreenBars.visibleBars(null));
    }

    @Test public void aScreenWithNoTabBarAndNoViewerIsLeftAlone() {
        Settings.FULL_SCREEN_REELS.save(true);
        Settings.FULL_SCREEN_HOME.save(true);
        root.removeView(bar);
        layout();
        assertEquals(FullScreenBars.Screen.OTHER, screen());
        assertFalse(watch().isHidden());
    }
}
