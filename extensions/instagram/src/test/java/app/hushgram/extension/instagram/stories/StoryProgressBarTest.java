/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
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
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Thicker story progress bar (#360): each story's bar is stretched, the header beside it isn't, and off puts it back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryProgressBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String FAMILY = "Thicker story progress bar";

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout pager;
    private View first;
    private View second;
    private View name;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        StoryProgressBar.resetForTests();
        int barId = View.generateViewId();
        StoryProgressBar.idForTests(activity, barId);

        pager = new FrameLayout(activity);
        first = page(barId);
        second = page(barId);
        name = ((LinearLayout) first.getParent()).getChildAt(1);
        activity.setContentView(pager);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.THICKER_STORY_BAR.resetToDefault();
        Settings.STORY_BAR_THICKNESS.resetToDefault();
        HookStatus.clear();
    }

    /** A story page as 452.1 builds it: a header holding the 3 pixel bar, then the row with the name. */
    private View page(int barId) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        View bar = new View(activity);
        bar.setId(barId);
        header.addView(bar, new LinearLayout.LayoutParams(720, 3));
        header.addView(new View(activity), new LinearLayout.LayoutParams(720, 122));
        pager.addView(header, new FrameLayout.LayoutParams(720, 141));
        return bar;
    }

    @After
    public void restore() {
        StoryProgressBar.resetForTests();
        Settings.THICKER_STORY_BAR.resetToDefault();
        Settings.STORY_BAR_THICKNESS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    /** Draws one frame; true means a listener cancelled it. */
    private boolean frame() {
        return pager.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test
    public void offToStartLeavesStoriesAlone() {
        assertFalse(Settings.THICKER_STORY_BAR.get());

        StoryProgressBar.watch(activity);
        frame();

        assertFalse(StoryProgressBar.listeningForTests(activity));
        assertEquals(1f, first.getScaleY(), 0f);
    }

    @Test
    public void onStretchesEveryBarTwiceAndNotTheHeader() {
        Settings.THICKER_STORY_BAR.save(true);

        StoryProgressBar.watch(activity);
        assertFalse("the frame is drawn, not cancelled, already stretched", frame());

        assertEquals(2f, first.getScaleY(), 0f);
        assertEquals(2f, second.getScaleY(), 0f);
        assertEquals("only the height changes", 1f, first.getScaleX(), 0f);
        assertEquals("the name's row stays as it was", 1f, name.getScaleY(), 0f);
        assertEquals(2, StoryProgressBar.stretchedForTests());
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());
    }

    @Test
    public void aNewStoryIsStretchedAfterItsLayoutAndAThicknessChangeReachesEveryBar() {
        Settings.THICKER_STORY_BAR.save(true);
        StoryProgressBar.watch(activity);
        frame();

        Settings.STORY_BAR_THICKNESS.save(StoryBarThickness.THREE_TIMES);
        View third = page(first.getId());
        // The new page's layout pass marks the screen laid out again and draws the next frame.
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertEquals(3f, third.getScaleY(), 0f);
        assertEquals("multiplied from Instagram's scale, not from the last one", 3f, first.getScaleY(), 0f);
        assertEquals(3, StoryProgressBar.stretchedForTests());
    }

    @Test
    public void aScaleInstagramSetIsKeptAndGivenBack() {
        first.setScaleY(0.5f);
        Settings.THICKER_STORY_BAR.save(true);
        StoryProgressBar.watch(activity);
        frame();
        assertEquals(1f, first.getScaleY(), 0f);

        Settings.THICKER_STORY_BAR.save(false);
        frame();

        assertEquals(0.5f, first.getScaleY(), 0f);
    }

    @Test
    public void offPutsEveryBarBackAndStopsListening() {
        Settings.THICKER_STORY_BAR.save(true);
        StoryProgressBar.watch(activity);
        frame();

        Settings.THICKER_STORY_BAR.save(false);
        frame();

        assertFalse(StoryProgressBar.listeningForTests(activity));
        assertEquals(1f, first.getScaleY(), 0f);
        assertEquals(1f, second.getScaleY(), 0f);
        assertEquals(0, StoryProgressBar.stretchedForTests());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.THICKER_STORY_BAR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        StoryProgressBar.watch(activity);
        frame();

        assertFalse(StoryProgressBar.listeningForTests(activity));
        assertEquals(1f, first.getScaleY(), 0f);
    }

    @Test
    public void pausingAfterStretchingPutsEveryBarBack() {
        Settings.THICKER_STORY_BAR.save(true);
        StoryProgressBar.watch(activity);
        frame();
        assertEquals(2f, first.getScaleY(), 0f);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();

        assertFalse(StoryProgressBar.listeningForTests(activity));
        assertEquals(1f, first.getScaleY(), 0f);
        assertEquals(1f, second.getScaleY(), 0f);
        assertEquals(0, StoryProgressBar.stretchedForTests());
    }

    /** Settings are a dialog over Instagram's screen, so turning the switch on there can't wait for a resume. */
    @Test
    public void flippingTheSwitchUnderSettingsStretchesWithoutAResume() {
        StoryProgressBar.watch(activity);
        assertFalse(StoryProgressBar.listeningForTests(activity));

        Settings.THICKER_STORY_BAR.save(true);
        StoryProgressBar.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertTrue(StoryProgressBar.listeningForTests(activity));
        assertEquals(2f, first.getScaleY(), 0f);

        Settings.THICKER_STORY_BAR.save(false);
        StoryProgressBar.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1f, first.getScaleY(), 0f);
    }

    @Test
    public void aBuildWithoutTheBarIdStretchesNothingAndSaysSo() {
        StoryProgressBar.resetForTests();
        Settings.THICKER_STORY_BAR.save(true);

        StoryProgressBar.watch(activity);
        frame();

        assertEquals(1f, first.getScaleY(), 0f);
        assertFalse(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());
    }
}
