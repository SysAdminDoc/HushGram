/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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

import java.time.Duration;

import app.hushgram.extension.instagram.feed.GlassSeekBar;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Burn-in guard on stories (#479): with Dim stories too on, a story's progress bar, header (the eye
 * button in it) and reply box dim after the wait as a reel's controls do, HushGram's seek bar beside
 * the viewer with them, and any tap brings them back. A story covers the reel under it, and a reel
 * opened over a story is Reels again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BurnInGuardStoryTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int VIEWER = 7201, OVERLAY = 7301;
    private static final int STORY = 7501, PROGRESS = 7502, HEADER = 7503, EXTRAS = 7504, COMPOSER = 7505;
    private static final long IDLE = BurnInWait.FIVE_SECONDS.millis;

    private int W, H;
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private FrameLayout story;
    private View media, progress, composer;
    private LinearLayout header, extras;
    private ImageView eye;
    private EditText reply;
    private GlassSeekBar seek;

    @Before public void prepare() {
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        Settings.BURN_IN_LIGHT_WHEN_PAUSED.resetToDefault();
        Settings.BURN_IN_STORIES.resetToDefault();
        PauseForTests.resume();
        BurnInGuard.resetForTests();
        FullScreenBars.idForTests(FullScreenBars.VIEWER, VIEWER);
        for (String name : BurnInGuard.DIMMED_IDS) BurnInGuard.idForTests(name, 0);
        for (String name : BurnInGuard.WAKING_IDS) BurnInGuard.idForTests(name, 0);
        for (String name : BurnInGuard.QUIET_IDS) BurnInGuard.idForTests(name, 0);
        for (String name : BurnInGuard.STORY_DIMMED_IDS) BurnInGuard.idForTests(name, 0);
        BurnInGuard.idForTests(BurnInGuard.REEL_ID, 0);
        BurnInGuard.idForTests("clips_item_overlay_component", OVERLAY);
        BurnInGuard.idForTests(BurnInGuard.STORY_ID, STORY);
        BurnInGuard.idForTests("reel_viewer_progress_bar", PROGRESS);
        BurnInGuard.idForTests("reel_viewer_header_container", HEADER);
        BurnInGuard.idForTests("reel_header_extras_container", EXTRAS);
        BurnInGuard.idForTests("reel_viewer_message_composer_container", COMPOSER);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        W = activity.getWindow().getDecorView().getWidth();
        H = activity.getWindow().getDecorView().getHeight();
        assertTrue("the test window has a size", W > 0 && H > 0);

        root = new FrameLayout(activity);
        activity.setContentView(root);
        story = new FrameLayout(activity);
        story.setId(STORY);
        media = new View(activity);
        story.addView(media, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        progress = new View(activity);
        progress.setId(PROGRESS);
        story.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6));
        header = new LinearLayout(activity);
        header.setId(HEADER);
        extras = new LinearLayout(activity);
        extras.setId(EXTRAS);
        eye = new ImageView(activity);
        extras.addView(eye, new LinearLayout.LayoutParams(40, 40));
        header.addView(extras, new LinearLayout.LayoutParams(120, 40));
        story.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));
        FrameLayout box = new FrameLayout(activity);
        box.setId(COMPOSER);
        reply = new EditText(activity);
        box.addView(reply, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50));
        composer = box;
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60);
        bottom.gravity = android.view.Gravity.BOTTOM;
        story.addView(composer, bottom);
        root.addView(story, new FrameLayout.LayoutParams(W, H));
        // HushGram's seek bar on stories goes in the frame holding the viewer, right after it.
        seek = new GlassSeekBar(activity, "story seek bar", "story seek bar");
        root.addView(seek, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28));
        layout();
    }

    @After public void close() {
        BurnInGuard.resetForTests();
        controller.close();
        Settings.BURN_IN_GUARD.resetToDefault();
        Settings.BURN_IN_WAIT.resetToDefault();
        Settings.BURN_IN_STAY_DIM.resetToDefault();
        Settings.BURN_IN_LIGHT_WHEN_PAUSED.resetToDefault();
        Settings.BURN_IN_STORIES.resetToDefault();
        PauseForTests.resume();
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, W, H);
    }

    private static void after(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private void relayout() {
        layout();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
        after(BurnInGuard.LOOK_MS + 10);
    }

    private void send(int action) {
        int[] at = new int[2];
        root.getLocationInWindow(at);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, at[0] + root.getWidth() / 2f, at[1] + root.getHeight() / 2f, 0);
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void tap() {
        send(MotionEvent.ACTION_DOWN);
        send(MotionEvent.ACTION_UP);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** A reels viewer with its overlay, put in before the story (under it) or after (over it). */
    private FrameLayout reel(boolean over) {
        FrameLayout viewer = new FrameLayout(activity);
        viewer.setId(VIEWER);
        View overlay = new View(activity);
        overlay.setId(OVERLAY);
        viewer.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(viewer, over ? root.indexOfChild(story) + 1 : 0, new FrameLayout.LayoutParams(W, H));
        layout();
        return viewer;
    }

    private void assertStoryDimmed(String why) {
        assertEquals(why + ": the progress bar", BurnInGuard.IDLE_LEVEL, progress.getAlpha(), 0f);
        assertEquals(why + ": the header", BurnInGuard.IDLE_LEVEL, header.getAlpha(), 0f);
        assertEquals(why + ": the reply box", BurnInGuard.IDLE_LEVEL, composer.getAlpha(), 0f);
        assertEquals(why + ": the seek bar beside the viewer", BurnInGuard.IDLE_LEVEL, seek.getAlpha(), 0f);
        assertEquals(why + ": the eye's row, in the dimmed header, isn't dimmed twice", 1f, extras.getAlpha(), 0f);
        assertEquals(why + ": the eye itself keeps its own alpha", 1f, eye.getAlpha(), 0f);
        assertEquals(why + ": the story itself", 1f, media.getAlpha(), 0f);
        assertEquals(why + ": the viewer", 1f, story.getAlpha(), 0f);
    }

    private void assertStoryUp(String why) {
        assertEquals(why + ": the progress bar", 1f, progress.getAlpha(), 0f);
        assertEquals(why + ": the header", 1f, header.getAlpha(), 0f);
        assertEquals(why + ": the reply box", 1f, composer.getAlpha(), 0f);
        assertEquals(why + ": the seek bar", 1f, seek.getAlpha(), 0f);
    }

    @Test public void aStoryDimsAfterTheWaitAndATapBringsItBack() {
        assertTrue("Dim stories too starts on", Settings.BURN_IN_STORIES.get());
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE - 100);
        assertStoryUp("before the wait");
        after(200);
        assertStoryDimmed("after the wait");
        relayout();
        assertStoryDimmed("a second look dims nothing twice");

        tap();
        assertStoryUp("a tap");
        after(IDLE + 100);
        assertStoryDimmed("the wait again after the tap");
    }

    /** The row of header buttons, eye and all, dims on its own where it isn't inside the header (#479). */
    @Test public void theEyesRowDimsWhereverItSits() {
        header.removeView(extras);
        story.addView(extras, new FrameLayout.LayoutParams(120, 40));
        layout();
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertEquals("the eye's row", BurnInGuard.IDLE_LEVEL, extras.getAlpha(), 0f);
        assertEquals("the eye inside it isn't dimmed twice", 1f, eye.getAlpha(), 0f);
        tap();
        assertEquals("back", 1f, extras.getAlpha(), 0f);
    }

    @Test public void withDimStoriesTooOffAStoryAndTheReelUnderItStayBright() {
        View overlay = reel(false).findViewById(OVERLAY);
        Settings.BURN_IN_STORIES.save(false);
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE * 3);
        assertStoryUp("off");
        assertEquals("the reel under the story isn't looked after either", 1f, overlay.getAlpha(), 0f);
    }

    @Test public void aStoryOverAReelDimsTheStoryAndClosingItGoesBackToTheReel() {
        FrameLayout viewer = reel(false);
        View overlay = viewer.findViewById(OVERLAY);
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertStoryDimmed("the story on top");
        assertEquals("the reel under it", 1f, overlay.getAlpha(), 0f);

        root.removeView(story);
        root.removeView(seek);
        relayout();
        assertEquals("the story's controls come back as it closes", 1f, header.getAlpha(), 0f);
        after(IDLE - 300);
        assertEquals("the wait starts over on Reels", 1f, overlay.getAlpha(), 0f);
        after(400);
        assertEquals("then the reel dims", BurnInGuard.IDLE_LEVEL, overlay.getAlpha(), 0f);
    }

    @Test public void aReelOpenedOverAStoryIsReels() {
        View overlay = reel(true).findViewById(OVERLAY);
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertEquals("the reel on top dims", BurnInGuard.IDLE_LEVEL, overlay.getAlpha(), 0f);
        assertStoryUp("the story under it");
    }

    @Test public void typingAReplyKeepsThemBrightAndTheWaitStartsWhenItEnds() {
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        reply.setFocusableInTouchMode(true);
        assertTrue("the reply box takes the focus", reply.requestFocus());
        relayout();
        after(IDLE * 3);
        assertStoryUp("typing");

        // Sent or closed: Instagram folds the box back up, and it no longer has the focus.
        reply.setFocusable(false);
        assertTrue("the reply box let the focus go", !reply.isFocused());
        relayout();
        after(IDLE - 400);
        assertStoryUp("the wait starts once typing ends");
        after(500);
        assertStoryDimmed("then they dim");
    }

    /** Stay dim when I tap is Reels' own: on a story a tap still brings them back (#479). */
    @Test public void stayDimWhenITapStaysWithReels() {
        Settings.BURN_IN_STAY_DIM.save(true);
        Settings.BURN_IN_GUARD.save(BurnInMode.DIM);
        BurnInGuard.resumed(activity);
        after(IDLE + 100);
        assertStoryDimmed("idle");
        tap();
        assertStoryUp("a tap on a story");
    }

    @Test public void drawnOverGoesByChildOrderWhereThePathsMeet() {
        FrameLayout viewer = reel(true);
        assertTrue(BurnInGuard.drawnOver(viewer, story));
        assertTrue(!BurnInGuard.drawnOver(story, viewer));
        assertTrue("a view inside the later branch", BurnInGuard.drawnOver(viewer.findViewById(OVERLAY), header));
    }
}
