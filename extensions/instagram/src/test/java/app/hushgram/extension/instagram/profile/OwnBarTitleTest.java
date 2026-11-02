/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Logo and titles at the start on the Messages inbox's and the Reels tab's own bars (#407), in the
 * shapes the S22's 453.0.0.57.79 dumps showed. The inbox bar is 1080 wide with the title's slot at
 * 192..888, the title at 333..747 inside it and the new message button at 933..1057, its 60 px icon
 * drawn in the middle. The Reels bar has Create at 0..134, the tabs' row at 202..877 with the tabs
 * from 251, and the button for its options at 923..1057.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OwnBarTitleTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int INBOX = 7601, INBOX_TITLE = 7602, REELS = 7603, REELS_TITLE = 7604;

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout column;
    private FrameLayout inbox;
    private FrameLayout slot;
    private View title;
    private ImageView compose;
    private FrameLayout reels;
    private View create;
    private LinearLayout tabs;

    @Before public void prepare() {
        Settings.CENTER_SCREEN_TITLES.resetToDefault();
        Settings.TITLES_AT_START.resetToDefault();
        PauseForTests.resume();
        ProfileTitle.resetForTests();
        ProfileTitle.idForTests(ProfileTitle.INBOX_BAR_ID, INBOX);
        ProfileTitle.idForTests(ProfileTitle.INBOX_TITLE_ID, INBOX_TITLE);
        ProfileTitle.idForTests(ProfileTitle.REELS_BAR_ID, REELS);
        ProfileTitle.idForTests(ProfileTitle.REELS_TITLE_ID, REELS_TITLE);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(column);
        inbox = inboxBar(192, 141, 933);
        column.addView(inbox, new LinearLayout.LayoutParams(1080, 157));
        reels = reelsBar();
        column.addView(reels, new LinearLayout.LayoutParams(1080, 157));
        layout();
    }

    @After public void close() {
        ProfileTitle.resetForTests();
        controller.close();
        Settings.CENTER_SCREEN_TITLES.resetToDefault();
        Settings.TITLES_AT_START.resetToDefault();
        PauseForTests.resume();
    }

    /** The inbox bar: the slot at [slotLeft], the title [titleLeft] into it, the new message button at [composeLeft]. */
    private FrameLayout inboxBar(int slotLeft, int titleLeft, int composeLeft) {
        FrameLayout bar = new FrameLayout(activity);
        bar.setId(INBOX);
        slot = new FrameLayout(activity);
        title = new View(activity);
        title.setId(INBOX_TITLE);
        slot.addView(title, at(titleLeft, 414, 66));
        bar.addView(slot, at(slotLeft, 696, 157));
        compose = button();
        bar.addView(compose, at(composeLeft, 124, 157));
        return bar;
    }

    private FrameLayout reelsBar() {
        FrameLayout bar = new FrameLayout(activity);
        bar.setId(REELS);
        create = new View(activity);
        bar.addView(create, at(0, 134, 157));
        tabs = new LinearLayout(activity);
        tabs.setId(REELS_TITLE);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(200, 66);
        first.leftMargin = 49;
        tabs.addView(new View(activity), first);
        tabs.addView(new View(activity), new LinearLayout.LayoutParams(200, 66));
        bar.addView(tabs, at(202, 675, 157));
        bar.addView(button(), at(923, 134, 157));
        return bar;
    }

    /** A bar button drawing a 60 px icon in its middle, as Instagram's do. */
    private ImageView button() {
        ImageView button = new ImageView(activity);
        GradientDrawable icon = new GradientDrawable();
        icon.setSize(60, 60);
        button.setImageDrawable(icon);
        button.setScaleType(ImageView.ScaleType.CENTER);
        return button;
    }

    private static FrameLayout.LayoutParams at(int left, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        params.leftMargin = left;
        return params;
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

    /** Where the title is drawn on the inbox bar. */
    private float titleLeft() {
        return slot.getLeft() + slot.getTranslationX() + title.getLeft();
    }

    /** Where the first tab is drawn on the Reels bar. */
    private float tabsLeft() {
        return tabs.getLeft() + tabs.getTranslationX() + tabs.getChildAt(0).getLeft();
    }

    @Test public void theDumpsShapesAreWhatTheTestBuilds() {
        assertEquals(333, slot.getLeft() + title.getLeft());
        assertEquals(888, slot.getRight());
        assertEquals(251, tabs.getLeft() + tabs.getChildAt(0).getLeft());
        assertEquals(134, create.getRight());
    }

    @Test public void offBothTitlesStayWhereInstagramPutThem() {
        Settings.CENTER_SCREEN_TITLES.save(true);
        ProfileTitle.resumed(activity);
        relayout();
        assertEquals(0f, slot.getTranslationX(), 0f);
        assertEquals(0f, tabs.getTranslationX(), 0f);
    }

    /** The new message icon is drawn 55 px in from the end, at 965..1025, so the title starts 55 px in. */
    @Test public void theInboxTitleSitsAsFarInAsTheNewMessageIcon() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-278f, slot.getTranslationX(), 0f);
        assertEquals(55f, titleLeft(), 0f);
        assertEquals("the button stays", 0f, compose.getTranslationX(), 0f);
        relayout();
        assertEquals("the same however often it lays out", -278f, slot.getTranslationX(), 0f);
        assertTrue(ProfileTitle.watchingLayoutsForTests());
    }

    @Test public void theReelsTabsGoUpToCreate() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-117f, tabs.getTranslationX(), 0f);
        assertEquals(134f, tabsLeft(), 0f);
        assertEquals(0f, create.getTranslationX(), 0f);
    }

    /** With Create hidden the tabs keep the room its options button's icon has at the end: 23 plus 37. */
    @Test public void withCreateHiddenTheReelsTabsStayOffTheEdge() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        create.setVisibility(View.GONE);
        relayout();
        assertEquals(60f, tabsLeft(), 0f);
        create.setVisibility(View.VISIBLE);
        relayout();
        assertEquals("and back up to Create when it shows", 134f, tabsLeft(), 0f);
    }

    @Test public void aTitleAlreadyAtTheStartStays() {
        column.removeView(inbox);
        inbox = inboxBar(0, 30, 933);
        column.addView(inbox, 0, new LinearLayout.LayoutParams(1080, 157));
        layout();
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(0f, slot.getTranslationX(), 0f);
    }

    @Test public void aBarShowingNoTitleGetsItsSlotBack() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-278f, slot.getTranslationX(), 0f);
        title.setVisibility(View.GONE);
        relayout();
        assertEquals(0f, slot.getTranslationX(), 0f);
    }

    @Test public void turningItOffOrCenteringOnlyPutsThemBack() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-278f, slot.getTranslationX(), 0f);
        Settings.TITLES_AT_START.save(false);
        Settings.CENTER_SCREEN_TITLES.save(true);
        focusBack();
        assertEquals("centering leaves these bars as Instagram has them", 0f, slot.getTranslationX(), 0f);
        assertEquals(0f, tabs.getTranslationX(), 0f);
    }

    @Test public void pauseHushGramPutsThemBack() {
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        focusBack();
        assertEquals(0f, slot.getTranslationX(), 0f);
        assertEquals(0f, tabs.getTranslationX(), 0f);
    }

    /** Mirrored: the new message button at 23..147 and the title's right edge 55 px in from the right. */
    @Test public void inARightToLeftBarTheTitleGoesRight() {
        column.removeView(inbox);
        inbox = inboxBar(192, 141, 23);
        inbox.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        column.addView(inbox, 0, new LinearLayout.LayoutParams(1080, 157));
        layout();
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(278f, slot.getTranslationX(), 0f);
        assertEquals(1025f, titleLeft() + title.getWidth(), 0f);
    }

    /** A tab kept in the background has a Reels bar too, which comes first in the window. */
    @Test public void aHiddenTabsBarDoesntKeepTheShownOneFromMoving() {
        FrameLayout background = new FrameLayout(activity);
        FrameLayout hiddenBar = reelsBar();
        LinearLayout hiddenTabs = tabs;
        background.addView(hiddenBar, new FrameLayout.LayoutParams(1080, 157));
        background.setVisibility(View.GONE);
        column.addView(background, 0, new LinearLayout.LayoutParams(1080, 157));
        column.removeView(reels);
        reels = reelsBar();
        column.addView(reels, new LinearLayout.LayoutParams(1080, 157));
        layout();
        Settings.TITLES_AT_START.save(true);
        ProfileTitle.resumed(activity);
        assertEquals(-117f, tabs.getTranslationX(), 0f);
        assertEquals(0f, hiddenTabs.getTranslationX(), 0f);
    }
}
