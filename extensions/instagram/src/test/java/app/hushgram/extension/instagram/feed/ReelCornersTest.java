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
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Looper;
import android.view.Gravity;
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

    private Rect outlineRect(View view) {
        Outline outline = new Outline();
        view.getOutlineProvider().getOutline(view, outline);
        Rect rect = new Rect();
        assertTrue("a rounded rectangle", outline.getRect(rect));
        return rect;
    }

    /** A reel frame that counts each time its outline is asked for again. */
    private static final class Frame extends FrameLayout {
        int asked;

        Frame(Context context) {
            super(context);
        }

        @Override
        public void invalidateOutline() {
            asked++;
            super.invalidateOutline();
        }
    }

    /**
     * A reel shorter than the screen sits in its full-screen frame between black bars. The corners go
     * on the video, not in the bars, and move with it when it's laid out again (#456).
     */
    @Test
    public void aLetterboxedReelIsRoundedAtItsVideoAndFollowsIt() {
        Frame frame = new Frame(activity);
        frame.setId(first.getId());
        View video = new View(activity);
        frame.addView(video, new FrameLayout.LayoutParams(400, 600, Gravity.CENTER));
        pager.addView(frame, new FrameLayout.LayoutParams(400, 800));
        shadowOf(Looper.getMainLooper()).idle();
        Settings.ROUNDED_REEL_CORNERS.save(true);

        ReelCorners.watch(activity);
        frame();

        assertTrue(frame.getClipToOutline());
        assertEquals(new Rect(0, 100, 400, 700), outlineRect(frame));
        assertEquals("a frame filled by its video keeps its own edges", new Rect(0, 0, 400, 800), outlineRect(first));

        int asked = frame.asked;
        frame();
        assertEquals("nothing moved, nothing asked again", asked, frame.asked);

        video.setLayoutParams(new FrameLayout.LayoutParams(400, 500, Gravity.CENTER));
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertTrue("the outline is asked for again", frame.asked > asked);
        assertEquals(new Rect(0, 150, 400, 650), outlineRect(frame));
    }

    /**
     * Opening the comments on a wide reel slides its video up by moving it, with no new layout, and a
     * portrait reel's video is scaled down too. The outline goes where the video is drawn in the same
     * frame, rather than staying at its laid-out place and cutting it to a strip (#522).
     */
    @Test
    public void aWideReelSlidUpForTheCommentsKeepsItsVideoWhole() {
        Frame frame = new Frame(activity);
        frame.setId(first.getId());
        View video = new View(activity);
        frame.addView(video, new FrameLayout.LayoutParams(400, 240, Gravity.CENTER));
        pager.addView(frame, new FrameLayout.LayoutParams(400, 800));
        shadowOf(Looper.getMainLooper()).idle();
        Settings.ROUNDED_REEL_CORNERS.save(true);
        ReelCorners.watch(activity);
        frame();
        assertEquals(new Rect(0, 280, 400, 520), outlineRect(frame));

        int asked = frame.asked;
        video.setTranslationY(-240);
        assertFalse("the frame is drawn", frame());

        assertTrue("the outline is asked for again without a layout", frame.asked > asked);
        assertEquals(new Rect(0, 40, 400, 280), outlineRect(frame));

        video.setTranslationY(0);
        video.setPivotX(0);
        video.setPivotY(0);
        video.setScaleX(0.5f);
        video.setScaleY(0.5f);
        frame();

        assertEquals("scaled from its top left corner", new Rect(0, 280, 200, 400), outlineRect(frame));
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
