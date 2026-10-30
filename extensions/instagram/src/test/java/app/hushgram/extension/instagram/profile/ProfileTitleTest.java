/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Center the name on profiles (#140), on the bar the S25's 450.0.0.50.77 dump showed: the + button
 * (0..136), the username's container (181..785) with the name and its arrow at 320..780, and Threads
 * and the menu (785..1057), on a 1080 wide bar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfileTitleTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int BAR = 7401, CONTAINER = 7402;

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private LinearLayout bar;
    private FrameLayout container;
    private View name;

    @Before public void prepare() {
        Settings.CENTER_PROFILE_NAME.resetToDefault();
        Settings.TITLES_AT_START.resetToDefault();
        PauseForTests.resume();
        ProfileTitle.resetForTests();
        ProfileTitle.idForTests(ProfileTitle.BAR_ID, BAR);
        ProfileTitle.idForTests(ProfileTitle.CONTAINER_ID, CONTAINER);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        bar = new LinearLayout(activity);
        bar.setId(BAR);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(new View(activity), new LinearLayout.LayoutParams(136, 158));
        container = new FrameLayout(activity);
        container.setId(CONTAINER);
        LinearLayout.LayoutParams around = new LinearLayout.LayoutParams(604, 158);
        around.leftMargin = 45;
        bar.addView(container, around);
        bar.addView(new View(activity), new LinearLayout.LayoutParams(272, 158));
        root.addView(bar, new FrameLayout.LayoutParams(1080, 158));
        place(139, 460);
    }

    @After public void close() {
        ProfileTitle.resetForTests();
        controller.close();
        Settings.CENTER_PROFILE_NAME.resetToDefault();
        Settings.TITLES_AT_START.resetToDefault();
        PauseForTests.resume();
    }

    /** The name group at [left] in its container, [width] wide. */
    private void place(int left, int width) {
        if (name != null) container.removeView(name);
        name = new View(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, 158);
        params.leftMargin = left;
        container.addView(name, params);
        layout();
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        int w = Math.max(decor.getWidth(), 1080);
        int h = Math.max(decor.getHeight(), 400);
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
    }

    private void relayout() {
        layout();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private void focusBack() {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        ReflectionHelpers.callInstanceMethod(observer, "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Where the name's middle is drawn on the bar. */
    private float drawnMiddle() {
        return container.getLeft() + name.getLeft() + name.getTranslationX() + name.getWidth() / 2f;
    }

    @Test public void offToStartAndTheNameStaysWhereInstagramPutIt() {
        assertFalse(Settings.CENTER_PROFILE_NAME.get());
        ProfileTitle.resumed(activity);
        relayout();
        assertEquals(0f, name.getTranslationX(), 0f);
        assertFalse("no layout listener while off", ProfileTitle.watchingLayoutsForTests());
    }

    @Test public void theNamesMiddleIsTheBarsMiddle() {
        Settings.CENTER_PROFILE_NAME.save(true);
        ProfileTitle.resumed(activity);
        assertEquals("the S25's 550 comes to 540", -10f, name.getTranslationX(), 0f);
        assertEquals(540f, drawnMiddle(), 0.5f);
        relayout();
        assertEquals("the same however often it lays out", -10f, name.getTranslationX(), 0f);
    }

    @Test public void aNameLeftOfTheMiddleMovesRight() {
        Settings.CENTER_PROFILE_NAME.save(true);
        place(0, 300);
        ProfileTitle.resumed(activity);
        assertEquals(540f, drawnMiddle(), 0.5f);
        assertTrue(name.getTranslationX() > 0f);
    }

    @Test public void aLongNameMovesOnlyAsFarAsItsContainerLets() {
        Settings.CENTER_PROFILE_NAME.save(true);
        place(0, 580);
        ProfileTitle.resumed(activity);
        assertEquals("its right edge stops at the container's", 24f, name.getTranslationX(), 0f);
        assertEquals(604f, name.getLeft() + name.getTranslationX() + name.getWidth(), 0f);
    }

    @Test public void aNameThatChangesIsCenteredAgainAfterTheLayout() {
        Settings.CENTER_PROFILE_NAME.save(true);
        ProfileTitle.resumed(activity);
        assertTrue(ProfileTitle.watchingLayoutsForTests());
        name.getLayoutParams().width = 200;
        ((FrameLayout.LayoutParams) name.getLayoutParams()).leftMargin = 400;
        name.requestLayout();
        relayout();
        assertEquals(540f, drawnMiddle(), 0.5f);
    }

    @Test public void turningItOnAndOffInTheSettingsIsSeenWhenTheWindowGetsFocusBack() {
        ProfileTitle.resumed(activity);
        Settings.CENTER_PROFILE_NAME.save(true);
        focusBack();
        assertEquals(-10f, name.getTranslationX(), 0f);
        Settings.CENTER_PROFILE_NAME.save(false);
        focusBack();
        assertEquals("put back", 0f, name.getTranslationX(), 0f);
        assertFalse(ProfileTitle.watchingLayoutsForTests());
    }

    @Test public void pauseHushGramPutsTheNameBack() {
        Settings.CENTER_PROFILE_NAME.save(true);
        ProfileTitle.resumed(activity);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        focusBack();
        assertEquals(0f, name.getTranslationX(), 0f);
    }

    @Test public void aMoveSomethingElseMadeIsKept() {
        Settings.CENTER_PROFILE_NAME.save(true);
        ProfileTitle.resumed(activity);
        name.setTranslationX(30f);
        Settings.CENTER_PROFILE_NAME.save(false);
        focusBack();
        assertEquals("not HushGram's to put back", 30f, name.getTranslationX(), 0f);
    }

    /** Logo and titles at the start (#310): the name goes to its container's start, after the + button. */
    @Test public void atTheStartTheNameBeginsAtItsContainersStart() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-139f, name.getTranslationX(), 0f);
        assertEquals("right after the + button and the container's margin", 181f,
                container.getLeft() + name.getLeft() + name.getTranslationX(), 0f);
        assertTrue(ProfileTitle.watchingLayoutsForTests());
        relayout();
        assertEquals("the same however often it lays out", -139f, name.getTranslationX(), 0f);
    }

    @Test public void atTheStartWinsOverCentering() {
        Settings.CENTER_PROFILE_NAME.save(true);
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-139f, name.getTranslationX(), 0f);
        Settings.TITLES_AT_START.save(false);
        focusBack();
        assertEquals("centered again", -10f, name.getTranslationX(), 0f);
    }

    @Test public void atTheStartInARightToLeftBarTheNameGoesRight() {
        Settings.TITLES_AT_START.save(true);
        container.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ((FrameLayout.LayoutParams) name.getLayoutParams()).gravity = Gravity.LEFT;
        name.requestLayout();
        layout();
        ProfileTitle.resumed(activity);
        assertEquals("its right edge at the container's", 5f, name.getTranslationX(), 0f);
    }

    @Test public void turningTheStartOffPutsTheNameBack() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-139f, name.getTranslationX(), 0f);
        Settings.TITLES_AT_START.save(false);
        focusBack();
        assertEquals(0f, name.getTranslationX(), 0f);
        assertFalse(ProfileTitle.watchingLayoutsForTests());
    }

    /** The newer header pads the name's row on both sides (#310): at the start the name sits in that padding, drawn, not cut off. */
    @Test public void atTheStartANameInItsContainersPaddingIsDrawn() {
        container.setPadding(96, 0, 96, 0);
        layout();
        assertTrue(container.getClipToPadding());
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-235f, name.getTranslationX(), 0f);
        assertFalse("drawn in the padding", container.getClipToPadding());
        relayout();
        assertFalse("still drawn after another layout", container.getClipToPadding());
        Settings.TITLES_AT_START.save(false);
        focusBack();
        assertEquals(0f, name.getTranslationX(), 0f);
        assertTrue("clipped again once it's back", container.getClipToPadding());
    }

    @Test public void aCenteredNameInsideThePaddingKeepsItClipped() {
        container.setPadding(96, 0, 96, 0);
        Settings.CENTER_PROFILE_NAME.save(true);
        place(0, 200);
        ProfileTitle.resumed(activity);
        assertEquals(540f, drawnMiddle(), 0.5f);
        assertTrue(container.getClipToPadding());
    }

    @Test public void aHiddenBarIsLeftAlone() {
        Settings.CENTER_PROFILE_NAME.save(true);
        bar.setVisibility(View.GONE);
        ProfileTitle.resumed(activity);
        assertEquals(0f, name.getTranslationX(), 0f);
    }
}
