/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Looper;
import android.view.Gravity;
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

/**
 * Pure black's cover for the strip behind the navigation bar (#571): on under a comments screen and
 * under a reels viewer with no tab bar over it, off everywhere else, and only in dark mode.
 */
@RunWith(RobolectricTestRunner.class)
public class PureBlackNavTest {
    private static final int VIEWER = 0x7f0b7001;
    private static final int TAB_BAR = 0x7f0b7002;
    private static final int INSET = 60;
    private static final int RAISED = 0xff223344;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private FrameLayout frame;
    private View decor;

    @Before
    public void prepare() {
        PureBlackNav.resetForTests(INSET);
        PureBlackNav.idForTests(PureBlackNav.VIEWER, VIEWER);
        PureBlackNav.idForTests(PureBlackNav.TAB_BAR, TAB_BAR);
        controller = Robolectric.buildActivity(Activity.class).setup();
        frame = new FrameLayout(controller.get());
        controller.get().setContentView(frame);
        decor = controller.get().getWindow().getDecorView();
    }

    @After
    public void restore() {
        PureBlackNav.resetForTests(null);
        controller.close();
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_BACKGROUNDS.resetToDefault();
        AccentTheme.darkSurfacesForTests(null);
    }

    private View add(int id, int height) {
        View view = new View(controller.get());
        view.setId(id);
        frame.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM));
        return view;
    }

    private void settle() {
        shadowOf(Looper.getMainLooper()).idle();
        PureBlackNav.track(decor);
        PureBlackNav.refresh(decor);
    }

    @Test
    @Config(qualifiers = "night")
    public void aViewerWithNoTabBarGetsTheStripAtTheBottomOfTheWindow() {
        add(VIEWER, ViewGroup.LayoutParams.MATCH_PARENT);
        settle();
        Rect strip = PureBlackNav.cover(decor);
        assertNotNull(strip);
        assertEquals(decor.getHeight(), strip.bottom);
        assertEquals(decor.getHeight() - INSET, strip.top);
        assertEquals(decor.getWidth(), strip.width());
    }

    @Test
    @Config(qualifiers = "night")
    public void aTabBarOverTheViewerKeepsTheStripClear() {
        add(VIEWER, ViewGroup.LayoutParams.MATCH_PARENT);
        add(TAB_BAR, 150);
        settle();
        assertNull(PureBlackNav.cover(decor));
    }

    @Test
    @Config(qualifiers = "night")
    public void aTabBarUnderTheViewerDoesNot() {
        add(TAB_BAR, 150);
        add(VIEWER, ViewGroup.LayoutParams.MATCH_PARENT);
        settle();
        assertNotNull(PureBlackNav.cover(decor));
    }

    @Test
    @Config(qualifiers = "night")
    public void aHiddenTabBarDoesNotCount() {
        add(VIEWER, ViewGroup.LayoutParams.MATCH_PARENT);
        add(TAB_BAR, 150).setVisibility(View.GONE);
        settle();
        assertNotNull(PureBlackNav.cover(decor));
    }

    @Test
    @Config(qualifiers = "night")
    public void aViewerFillingLittleOfTheWindowDoesNot() {
        add(VIEWER, 200);
        settle();
        assertNull(PureBlackNav.cover(decor));
    }

    @Test
    @Config(qualifiers = "night")
    public void theStripFollowsACommentsScreenAndComesOffWhenItGoes() {
        LinearLayout comments = new LinearLayout(controller.get());
        frame.addView(comments, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600, Gravity.BOTTOM));
        shadowOf(Looper.getMainLooper()).idle();
        PureBlackNav.commentsShown(comments);
        PureBlackNav.refresh(decor);
        assertNotNull(PureBlackNav.cover(decor));

        frame.removeView(comments);
        settle();
        assertNull(PureBlackNav.cover(decor));
    }

    @Test
    @Config(qualifiers = "night")
    public void aCommentsSheetOnAPaintedPageGetsAStripInItsOwnShade() {
        paintBackgrounds();
        LinearLayout comments = new LinearLayout(controller.get());
        frame.addView(comments, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600, Gravity.BOTTOM));
        shadowOf(Looper.getMainLooper()).idle();
        PureBlackNav.commentsShown(comments);
        PureBlackNav.refresh(decor);
        assertEquals(RAISED, PureBlackNav.coverColor(decor));

        Settings.ACCENT_BACKGROUNDS.save(false);
        PureBlackNav.refresh(decor);
        assertEquals(Color.BLACK, PureBlackNav.coverColor(decor));
    }

    @Test
    @Config(qualifiers = "night")
    public void aViewersStripStaysBlackOnAPaintedPage() {
        paintBackgrounds();
        add(VIEWER, ViewGroup.LayoutParams.MATCH_PARENT);
        settle();
        assertEquals(Color.BLACK, PureBlackNav.coverColor(decor));
    }

    private static void paintBackgrounds() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_BACKGROUNDS.save(true);
        AccentTheme.darkSurfacesForTests(new int[] {0xff112233, RAISED, 0xff334455});
    }

    @Test
    @Config(qualifiers = "night")
    public void noNavigationBarMeansNoStrip() {
        PureBlackNav.resetForTests(0);
        PureBlackNav.idForTests(PureBlackNav.VIEWER, VIEWER);
        PureBlackNav.idForTests(PureBlackNav.TAB_BAR, TAB_BAR);
        add(VIEWER, ViewGroup.LayoutParams.MATCH_PARENT);
        settle();
        assertNull(PureBlackNav.cover(decor));
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightModeLeavesTheStripAlone() {
        add(VIEWER, ViewGroup.LayoutParams.MATCH_PARENT);
        settle();
        assertNull(PureBlackNav.cover(decor));
    }

    @Test
    public void theLaterSiblingOrTheHigherOneDrawsOver() {
        FrameLayout parent = new FrameLayout(controller.get());
        FrameLayout left = new FrameLayout(controller.get());
        View deep = new View(controller.get());
        View right = new View(controller.get());
        left.addView(deep);
        parent.addView(left);
        parent.addView(right);
        assertTrue(PureBlackNav.drawsOver(right, deep));
        assertFalse(PureBlackNav.drawsOver(deep, right));
        left.setTranslationZ(4f);
        assertTrue(PureBlackNav.drawsOver(deep, right));
        assertTrue(PureBlackNav.drawsOver(deep, left));
    }
}
