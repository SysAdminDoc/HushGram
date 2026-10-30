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
import android.view.ViewGroup;
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
 * Center titles on other screens (#161), on the shared bar the S25's 450.0.0.50.77 dump showed: a
 * 1080 wide bar whose title area runs 0..1001, with the back button at 0..158 and the title's group
 * at 158..546 (its title and icons, the title drawn at 203..512), and the bar's buttons after the area.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ScreenTitleTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int SHARED = 7501, AREA = 7502, TITLE = 7503, HOME = 7504, PROFILE_BAR = 7505, NAMES = 7506;

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout column;
    private LinearLayout bar;
    private ViewGroup area;
    private View back;
    private LinearLayout group;
    private LinearLayout icons;
    private View title;

    @Before public void prepare() {
        Settings.CENTER_PROFILE_NAME.resetToDefault();
        Settings.CENTER_SCREEN_TITLES.resetToDefault();
        Settings.TITLES_AT_START.resetToDefault();
        PauseForTests.resume();
        ProfileTitle.resetForTests();
        ProfileTitle.idForTests(ProfileTitle.SHARED_BAR_ID, SHARED);
        ProfileTitle.idForTests(ProfileTitle.TITLE_AREA_ID, AREA);
        ProfileTitle.idForTests(ProfileTitle.TITLE_ID, TITLE);
        ProfileTitle.idForTests(ProfileTitle.HOME_BAR_ID, HOME);
        ProfileTitle.idForTests(ProfileTitle.BAR_ID, PROFILE_BAR);
        ProfileTitle.idForTests(ProfileTitle.CONTAINER_ID, NAMES);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(column);
        build(1001, 309, false);
    }

    @After public void close() {
        ProfileTitle.resetForTests();
        controller.close();
        Settings.CENTER_PROFILE_NAME.resetToDefault();
        Settings.CENTER_SCREEN_TITLES.resetToDefault();
        Settings.TITLES_AT_START.resetToDefault();
        PauseForTests.resume();
    }

    /**
     * A shared bar whose title area is [areaWidth] wide, with a title [titleWidth] wide, the back
     * button first, or with [backOnTheRight] at the area's far end as a right to left layout has it.
     */
    private void build(int areaWidth, int titleWidth, boolean backOnTheRight) {
        if (bar != null) column.removeView(bar);
        bar = new LinearLayout(activity);
        bar.setId(SHARED);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        back = new View(activity);
        group = new LinearLayout(activity);
        icons = new LinearLayout(activity);
        icons.setPadding(45, 0, 34, 0);
        title = new View(activity);
        title.setId(TITLE);
        icons.addView(title, new LinearLayout.LayoutParams(titleWidth, 66));
        group.addView(icons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 66));
        if (backOnTheRight) {
            FrameLayout frame = new FrameLayout(activity);
            int groupWidth = titleWidth + 79;
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(groupWidth, 66);
            at.leftMargin = areaWidth - 158 - groupWidth;
            frame.addView(group, at);
            FrameLayout.LayoutParams end = new FrameLayout.LayoutParams(158, 157);
            end.leftMargin = areaWidth - 158;
            frame.addView(back, end);
            area = frame;
        } else {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(back, new LinearLayout.LayoutParams(158, 157));
            row.addView(group, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 66));
            area = row;
        }
        area.setId(AREA);
        bar.addView(area, new LinearLayout.LayoutParams(areaWidth, 157));
        bar.addView(new View(activity), new LinearLayout.LayoutParams(1080 - areaWidth, 157));
        column.addView(bar, 0, new LinearLayout.LayoutParams(1080, 157));
        layout();
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        int w = Math.max(decor.getWidth(), 1080);
        int h = Math.max(decor.getHeight(), 600);
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

    /** Where the group's middle is drawn on the bar. */
    private float drawnMiddle() {
        return area.getLeft() + group.getLeft() + group.getTranslationX() + group.getWidth() / 2f;
    }

    @Test public void theDumpsShapeIsWhatTheTestBuilds() {
        assertEquals(158, group.getLeft());
        assertEquals(546, group.getRight());
        assertEquals(203, group.getLeft() + icons.getLeft() + title.getLeft());
    }

    @Test public void offToStartAndTheTitleStaysWhereInstagramPutIt() {
        assertFalse(Settings.CENTER_SCREEN_TITLES.get());
        ProfileTitle.resumed(activity);
        relayout();
        assertEquals(0f, group.getTranslationX(), 0f);
        assertFalse("no layout listener while off", ProfileTitle.watchingLayoutsForTests());
    }

    @Test public void theTitlesMiddleIsTheBarsMiddle() {
        Settings.CENTER_SCREEN_TITLES.save(true);
        ProfileTitle.resumed(activity);
        assertEquals("the S25's 352 comes to 540", 188f, group.getTranslationX(), 0f);
        assertEquals(540f, drawnMiddle(), 0.5f);
        assertEquals("the back button stays", 0f, back.getTranslationX(), 0f);
        relayout();
        assertEquals("the same however often it lays out", 188f, group.getTranslationX(), 0f);
    }

    @Test public void aTitleMovesOnlyAsFarAsTheButtonsBesideItLet() {
        Settings.CENTER_SCREEN_TITLES.save(true);
        build(700, 309, false);
        ProfileTitle.resumed(activity);
        assertEquals("its right edge stops where the bar's buttons start", 154f, group.getTranslationX(), 0f);
        assertEquals(700f, group.getRight() + group.getTranslationX(), 0f);
    }

    @Test public void aLongTitleStaysWhereInstagramPutIt() {
        Settings.CENTER_SCREEN_TITLES.save(true);
        build(1001, 764, false);
        ProfileTitle.resumed(activity);
        assertEquals(1001, group.getRight());
        assertEquals("it already fills the room between the back button and the buttons", 0f, group.getTranslationX(), 0f);
    }

    @Test public void withTheBackButtonOnTheRightTheTitleMovesLeft() {
        Settings.CENTER_SCREEN_TITLES.save(true);
        build(1001, 309, true);
        ProfileTitle.resumed(activity);
        assertEquals(-109f, group.getTranslationX(), 0f);
        assertEquals(540f, drawnMiddle(), 0.5f);
        assertEquals(0f, back.getTranslationX(), 0f);
    }

    @Test public void aBarShowingNoTitleGetsItsGroupBack() {
        Settings.CENTER_SCREEN_TITLES.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(188f, group.getTranslationX(), 0f);
        title.setVisibility(View.GONE);
        relayout();
        assertEquals(0f, group.getTranslationX(), 0f);
    }

    @Test public void turningItOnAndOffInTheSettingsIsSeenWhenTheWindowGetsFocusBack() {
        ProfileTitle.resumed(activity);
        Settings.CENTER_SCREEN_TITLES.save(true);
        focusBack();
        assertEquals(188f, group.getTranslationX(), 0f);
        Settings.CENTER_SCREEN_TITLES.save(false);
        focusBack();
        assertEquals("put back", 0f, group.getTranslationX(), 0f);
        assertFalse(ProfileTitle.watchingLayoutsForTests());
    }

    @Test public void pauseHushGramPutsTheTitleBack() {
        Settings.CENTER_SCREEN_TITLES.save(true);
        ProfileTitle.resumed(activity);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        focusBack();
        assertEquals(0f, group.getTranslationX(), 0f);
    }

    /** A title Instagram already put after the back button stays there at the start (#310). */
    @Test public void atTheStartATitleAfterTheBackButtonStays() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(0f, group.getTranslationX(), 0f);
        assertTrue(ProfileTitle.watchingLayoutsForTests());
    }

    /** A title the newer bar pushed toward the middle goes back to just after the back button. */
    @Test public void atTheStartAPushedInTitleGoesBackToTheBackButton() {
        ((LinearLayout.LayoutParams) group.getLayoutParams()).leftMargin = 200;
        group.requestLayout();
        layout();
        Settings.CENTER_SCREEN_TITLES.save(true);
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals("it wins over centering", -200f, group.getTranslationX(), 0f);
        assertEquals(158f, group.getLeft() + group.getTranslationX(), 0f);
        Settings.TITLES_AT_START.save(false);
        Settings.CENTER_SCREEN_TITLES.save(false);
        focusBack();
        assertEquals("put back", 0f, group.getTranslationX(), 0f);
    }

    @Test public void atTheStartInARightToLeftBarTheTitleGoesUpToTheBackButtonOnTheRight() {
        build(1001, 309, true);
        area.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < area.getChildCount(); i++) {
            ((FrameLayout.LayoutParams) area.getChildAt(i).getLayoutParams()).gravity = Gravity.LEFT;
        }
        ((FrameLayout.LayoutParams) group.getLayoutParams()).leftMargin = 300;
        group.requestLayout();
        layout();
        assertEquals(688, group.getRight());
        assertEquals(843, back.getLeft());
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(155f, group.getTranslationX(), 0f);
        assertEquals(0f, back.getTranslationX(), 0f);
    }

    @Test public void homesHeaderIsLeftToItsOwnSwitch() {
        Settings.CENTER_SCREEN_TITLES.save(true);
        column.removeView(bar);
        FrameLayout home = new FrameLayout(activity);
        home.setId(HOME);
        home.addView(bar, new FrameLayout.LayoutParams(1080, 157));
        column.addView(home, 0, new LinearLayout.LayoutParams(1080, 157));
        layout();
        ProfileTitle.resumed(activity);
        assertEquals(0f, group.getTranslationX(), 0f);
    }

    /** Each switch moves only its own bar, and turning one off puts back only what it moved. */
    @Test public void eachSwitchMovesOnlyItsOwnBar() {
        LinearLayout profile = new LinearLayout(activity);
        profile.setId(PROFILE_BAR);
        profile.addView(new View(activity), new LinearLayout.LayoutParams(136, 158));
        FrameLayout names = new FrameLayout(activity);
        names.setId(NAMES);
        View name = new View(activity);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(460, 158);
        at.leftMargin = 139;
        names.addView(name, at);
        LinearLayout.LayoutParams around = new LinearLayout.LayoutParams(604, 158);
        around.leftMargin = 45;
        profile.addView(names, around);
        profile.addView(new View(activity), new LinearLayout.LayoutParams(272, 158));
        column.addView(profile, new LinearLayout.LayoutParams(1080, 158));
        layout();

        Settings.CENTER_PROFILE_NAME.save(true);
        ProfileTitle.resumed(activity);
        assertEquals("the profile switch moves the name", -10f, name.getTranslationX(), 0f);
        assertEquals("and leaves the shared bar", 0f, group.getTranslationX(), 0f);

        Settings.CENTER_SCREEN_TITLES.save(true);
        focusBack();
        assertEquals(188f, group.getTranslationX(), 0f);
        assertEquals(-10f, name.getTranslationX(), 0f);

        Settings.CENTER_PROFILE_NAME.save(false);
        focusBack();
        assertEquals("the name goes back", 0f, name.getTranslationX(), 0f);
        assertEquals("the title stays centered", 188f, group.getTranslationX(), 0f);
        assertTrue(ProfileTitle.watchingLayoutsForTests());
    }

    /** A profile's own bar inside the shared bar, as a custom view there, still gets its name centered. */
    @Test public void aProfileBarInsideTheSharedBarIsCenteredToo() {
        bar.setId(View.NO_ID);
        column.removeView(bar);
        FrameLayout shared = new FrameLayout(activity);
        shared.setId(SHARED);
        shared.addView(bar, new FrameLayout.LayoutParams(1080, 157));
        LinearLayout profile = new LinearLayout(activity);
        profile.setId(PROFILE_BAR);
        profile.addView(new View(activity), new LinearLayout.LayoutParams(136, 158));
        FrameLayout names = new FrameLayout(activity);
        names.setId(NAMES);
        View name = new View(activity);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(460, 158);
        at.leftMargin = 139;
        names.addView(name, at);
        LinearLayout.LayoutParams around = new LinearLayout.LayoutParams(604, 158);
        around.leftMargin = 45;
        profile.addView(names, around);
        profile.addView(new View(activity), new LinearLayout.LayoutParams(272, 158));
        shared.addView(profile, new FrameLayout.LayoutParams(1080, 158));
        column.addView(shared, 0, new LinearLayout.LayoutParams(1080, 158));
        layout();

        Settings.CENTER_PROFILE_NAME.save(true);
        Settings.CENTER_SCREEN_TITLES.save(true);
        ProfileTitle.resumed(activity);
        assertEquals("the shared bar's title is centered", 188f, group.getTranslationX(), 0f);
        assertEquals("and so is the name in the profile bar inside it", -10f, name.getTranslationX(), 0f);
    }
}
