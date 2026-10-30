/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Outline;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
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

/**
 * Rounded corners on stories (#363, #368): each story's media frame is rounded, the progress bar and
 * header beside it aren't, it doesn't follow the Reels switch, and off puts it back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryCornersTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout pager;
    private FrameLayout first;
    private FrameLayout second;
    private LinearLayout header;
    private FrameLayout reel;
    private final ViewOutlineProvider instagrams = ViewOutlineProvider.BOUNDS;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        StoryCorners.resetForTests();
        ReelCorners.resetForTests();
        int frameId = View.generateViewId();
        StoryCorners.idForTests(activity, frameId);
        int reelId = View.generateViewId();
        ReelCorners.idForTests(activity, reelId);

        pager = new FrameLayout(activity);
        first = page(frameId);
        second = page(frameId);
        header = (LinearLayout) ((FrameLayout) first.getParent()).getChildAt(1);
        reel = new FrameLayout(activity);
        reel.setId(reelId);
        reel.setOutlineProvider(instagrams);
        pager.addView(reel, new FrameLayout.LayoutParams(400, 800));
        activity.setContentView(pager);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.ROUNDED_STORY_CORNERS.resetToDefault();
        Settings.ROUNDED_REEL_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
        HookStatus.clear();
    }

    /**
     * A story page as 453 builds it: Instagram's rounded media layout holding the media frame, then the
     * header with the progress bar, both full width.
     */
    private FrameLayout page(int frameId) {
        FrameLayout mediaLayout = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        frame.setOutlineProvider(instagrams);
        frame.addView(new View(activity));
        mediaLayout.addView(frame);
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.addView(new View(activity), new LinearLayout.LayoutParams(400, 3));
        mediaLayout.addView(headerRow);
        pager.addView(mediaLayout, new FrameLayout.LayoutParams(400, 800));
        return frame;
    }

    @After
    public void restore() {
        StoryCorners.resetForTests();
        ReelCorners.resetForTests();
        Settings.ROUNDED_STORY_CORNERS.resetToDefault();
        Settings.ROUNDED_REEL_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    /** Draws one frame; true means a listener cancelled it. */
    private boolean frame() {
        return pager.getViewTreeObserver().dispatchOnPreDraw();
    }

    private float radius(View view) {
        Outline outline = new Outline();
        view.getOutlineProvider().getOutline(view, outline);
        return outline.getRadius();
    }

    @Test
    public void offToStartLeavesStoriesAlone() {
        assertFalse(Settings.ROUNDED_STORY_CORNERS.get());

        StoryCorners.watch(activity);
        frame();

        assertFalse(StoryCorners.listeningForTests(activity));
        assertSame(instagrams, first.getOutlineProvider());
        assertFalse(first.getClipToOutline());
    }

    @Test
    public void onRoundsEveryMediaFrameAndNotTheHeaderOrAReel() {
        Settings.ROUNDED_STORY_CORNERS.save(true);
        Settings.ROUNDED_CORNER_SIZE.save(CornerSize.LARGE);

        StoryCorners.watch(activity);
        assertFalse("the frame is drawn, not cancelled, already rounded", frame());

        float density = activity.getResources().getDisplayMetrics().density;
        for (FrameLayout media : new FrameLayout[] {first, second}) {
            assertTrue(media.getClipToOutline());
            assertNotSame(instagrams, media.getOutlineProvider());
            assertEquals(CornerSize.LARGE.dp * density, radius(media), 0.01f);
        }
        assertFalse("the header with the progress bar is whole", header.getClipToOutline());
        assertFalse("the parent layout is Instagram's", ((View) first.getParent()).getClipToOutline());
        assertSame("a reel waits for its own switch", instagrams, reel.getOutlineProvider());
        assertEquals(2, StoryCorners.roundedForTests());
        assertEquals(0, ReelCorners.roundedForTests());
        assertTrue(HookStatus.missing("Rounded post corners").toString(),
                HookStatus.missing("Rounded post corners").isEmpty());
    }

    @Test
    public void theReelsSwitchLeavesStoriesAlone() {
        Settings.ROUNDED_REEL_CORNERS.save(true);

        StoryCorners.watch(activity);
        ReelCorners.watch(activity);
        frame();

        assertTrue(reel.getClipToOutline());
        assertFalse(first.getClipToOutline());
        assertFalse(StoryCorners.listeningForTests(activity));
    }

    @Test
    public void offPutsEveryFrameBackAndStopsListening() {
        Settings.ROUNDED_STORY_CORNERS.save(true);
        StoryCorners.watch(activity);
        frame();
        assertTrue(first.getClipToOutline());

        Settings.ROUNDED_STORY_CORNERS.save(false);
        frame();

        assertFalse(StoryCorners.listeningForTests(activity));
        assertSame(instagrams, first.getOutlineProvider());
        assertSame(instagrams, second.getOutlineProvider());
        assertFalse(first.getClipToOutline());
        assertEquals(0, StoryCorners.roundedForTests());
    }

    @Test
    public void pausingAfterRoundingPutsEveryFrameBack() {
        Settings.ROUNDED_STORY_CORNERS.save(true);
        StoryCorners.watch(activity);
        frame();

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();

        assertFalse(StoryCorners.listeningForTests(activity));
        assertFalse(first.getClipToOutline());
        assertFalse(second.getClipToOutline());
    }

    /** Settings are a dialog over the story viewer, so turning the switch on there can't wait for a resume. */
    @Test
    public void flippingTheSwitchUnderSettingsRoundsWithoutAResume() {
        StoryCorners.watch(activity);
        assertFalse(StoryCorners.listeningForTests(activity));

        Settings.ROUNDED_STORY_CORNERS.save(true);
        StoryCorners.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertTrue(StoryCorners.listeningForTests(activity));
        assertTrue(first.getClipToOutline());
    }

    @Test
    public void aBuildWithoutTheFrameIdRoundsNothingAndSaysSo() {
        StoryCorners.resetForTests();
        Settings.ROUNDED_STORY_CORNERS.save(true);

        StoryCorners.watch(activity);
        frame();

        assertFalse(first.getClipToOutline());
        assertFalse(HookStatus.missing("Rounded post corners").toString(),
                HookStatus.missing("Rounded post corners").isEmpty());
    }
}
