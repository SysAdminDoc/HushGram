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
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

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

/** Rounded corners on Reels (#362): each reel's video frame is rounded, its overlay isn't, and off puts it back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelCornersTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout pager;
    private FrameLayout first;
    private FrameLayout second;
    private FrameLayout overlay;
    private final ViewOutlineProvider instagrams = ViewOutlineProvider.BOUNDS;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        ReelCorners.resetForTests();
        int frameId = View.generateViewId();
        ReelCorners.idForTests(activity, frameId);

        pager = new FrameLayout(activity);
        first = page(frameId);
        second = page(frameId);
        overlay = new FrameLayout(activity);
        overlay.setId(View.generateViewId());
        ((ViewGroup) first.getParent()).addView(overlay);
        activity.setContentView(pager);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.ROUNDED_REEL_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
        HookStatus.clear();
    }

    /** A reel page as 452.1 builds it: the video frame a few levels down, its overlay beside it. */
    private FrameLayout page(int frameId) {
        FrameLayout page = new FrameLayout(activity);
        FrameLayout media = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        frame.setOutlineProvider(instagrams);
        frame.addView(new View(activity));
        media.addView(frame);
        page.addView(media);
        pager.addView(page, new FrameLayout.LayoutParams(400, 800));
        return frame;
    }

    @After
    public void restore() {
        ReelCorners.resetForTests();
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
    public void offToStartLeavesReelsAlone() {
        assertFalse(Settings.ROUNDED_REEL_CORNERS.get());

        ReelCorners.watch(activity);
        frame();

        assertFalse(ReelCorners.listeningForTests(activity));
        assertSame(instagrams, first.getOutlineProvider());
        assertFalse(first.getClipToOutline());
    }

    @Test
    public void onRoundsEveryVideoFrameAndNotTheOverlay() {
        Settings.ROUNDED_REEL_CORNERS.save(true);

        ReelCorners.watch(activity);
        assertFalse("the frame is drawn, not cancelled, already rounded", frame());

        float density = activity.getResources().getDisplayMetrics().density;
        for (FrameLayout video : new FrameLayout[] {first, second}) {
            assertTrue(video.getClipToOutline());
            assertNotSame(instagrams, video.getOutlineProvider());
            assertEquals(CornerSize.SMALL.dp * density, radius(video), 0.01f);
        }
        assertFalse("the overlay with the seek bar and buttons is whole", overlay.getClipToOutline());
        assertEquals(2, ReelCorners.roundedForTests());
        assertTrue(HookStatus.missing("Rounded post corners").toString(),
                HookStatus.missing("Rounded post corners").isEmpty());
    }

    @Test
    public void aNewPageIsRoundedAfterItsLayoutAndASizeChangeReachesEveryFrame() {
        Settings.ROUNDED_REEL_CORNERS.save(true);
        ReelCorners.watch(activity);
        frame();

        Settings.ROUNDED_CORNER_SIZE.save(CornerSize.LARGE);
        FrameLayout third = page(first.getId());
        // The new page's layout pass marks the screen laid out again and draws the next frame.
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        float density = activity.getResources().getDisplayMetrics().density;
        assertTrue(third.getClipToOutline());
        assertEquals(CornerSize.LARGE.dp * density, radius(third), 0.01f);
        assertEquals(CornerSize.LARGE.dp * density, radius(first), 0.01f);
        assertEquals(3, ReelCorners.roundedForTests());
    }

    @Test
    public void offPutsEveryFrameBackAndStopsListening() {
        Settings.ROUNDED_REEL_CORNERS.save(true);
        ReelCorners.watch(activity);
        frame();

        Settings.ROUNDED_REEL_CORNERS.save(false);
        frame();

        assertFalse(ReelCorners.listeningForTests(activity));
        assertSame(instagrams, first.getOutlineProvider());
        assertSame(instagrams, second.getOutlineProvider());
        assertFalse(first.getClipToOutline());
        assertEquals(0, ReelCorners.roundedForTests());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.ROUNDED_REEL_CORNERS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        ReelCorners.watch(activity);
        frame();

        assertFalse(ReelCorners.listeningForTests(activity));
        assertFalse(first.getClipToOutline());
    }

    /** Settings are a dialog over the Reels screen, so turning the switch on there can't wait for a resume. */
    @Test
    public void flippingTheSwitchUnderSettingsRoundsWithoutAResume() {
        ReelCorners.watch(activity);
        assertFalse(ReelCorners.listeningForTests(activity));

        Settings.ROUNDED_REEL_CORNERS.save(true);
        ReelCorners.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertTrue(ReelCorners.listeningForTests(activity));
        assertTrue(first.getClipToOutline());
        assertTrue(second.getClipToOutline());

        Settings.ROUNDED_REEL_CORNERS.save(false);
        ReelCorners.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();

        assertSame(instagrams, first.getOutlineProvider());
        assertFalse(first.getClipToOutline());
    }

    @Test
    public void pausingAfterRoundingPutsEveryFrameBack() {
        Settings.ROUNDED_REEL_CORNERS.save(true);
        ReelCorners.watch(activity);
        frame();
        assertTrue(first.getClipToOutline());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();

        assertFalse(ReelCorners.listeningForTests(activity));
        assertSame(instagrams, first.getOutlineProvider());
        assertFalse(first.getClipToOutline());
        assertFalse(second.getClipToOutline());
        assertEquals(0, ReelCorners.roundedForTests());
    }

    @Test
    public void aBuildWithoutTheFrameIdRoundsNothingAndSaysSo() {
        ReelCorners.resetForTests();
        Settings.ROUNDED_REEL_CORNERS.save(true);

        ReelCorners.watch(activity);
        frame();

        assertFalse(first.getClipToOutline());
        assertFalse(HookStatus.missing("Rounded post corners").toString(),
                HookStatus.missing("Rounded post corners").isEmpty());
    }
}
