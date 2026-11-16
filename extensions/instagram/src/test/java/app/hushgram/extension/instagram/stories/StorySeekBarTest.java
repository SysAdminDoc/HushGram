/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowChoreographer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.feed.GlassSeekBar;
import app.hushgram.extension.instagram.feed.OverlayLook;
import app.hushgram.extension.instagram.feed.SeekShape;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Seek bar on stories (#355): a bar over a video story follows the video, a drag pauses it like a
 * hold and a release seeks and plays on, and a photo, the switch off, a pause or the viewer going
 * away show none.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StorySeekBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout content;
    private FrameLayout viewerView;
    private View composer;
    private final Object viewer = new Object();
    private final Stand player = new Stand();

    /** The viewer's player as the stubs reach it. */
    private final class Stand implements StorySeekBar.Player {
        int position = 3000;
        int duration = 15000;
        boolean liveSeekThrows;
        final List<String> calls = new ArrayList<>();

        @Override public View view(Object of) { return of == viewer ? viewerView : null; }
        int reads;
        @Override public int position(Object of) { reads++; return position; }
        @Override public int duration(Object of) { return duration; }
        @Override public boolean seekTo(Object of, int positionMs) {
            calls.add("seek " + positionMs);
            if (liveSeekThrows) throw new IllegalStateException("Check failed.");
            position = positionMs;
            return true;
        }
        @Override public boolean pause(Object of, String reason) { calls.add("pause " + reason); return true; }
        @Override public boolean resume(Object of) { calls.add("resume"); return true; }
    }

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        StorySeekBar.resetForTests();
        StorySeekBar.playerForTests(player);
        int composerId = View.generateViewId();
        StorySeekBar.composerIdForTests(activity, composerId);
        viewerView = new FrameLayout(activity);
        composer = new View(activity);
        composer.setId(composerId);
        viewerView.addView(composer, new FrameLayout.LayoutParams(720, 120, Gravity.BOTTOM));
        activity.setContentView(viewerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        content = activity.findViewById(android.R.id.content);
        idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STORY_SEEK_BAR.resetToDefault();
        Settings.STORY_SEEK_BAR_LOOK.resetToDefault();
        Settings.STORY_SEEK_BAR_POSITION.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StorySeekBar.resetForTests();
        Settings.STORY_SEEK_BAR.resetToDefault();
        Settings.STORY_SEEK_BAR_LOOK.resetToDefault();
        Settings.STORY_SEEK_BAR_POSITION.resetToDefault();
        Settings.SEEK_BAR_SHAPE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tick() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StorySeekBar.TICK_MS));
    }

    private SeekBar bar() {
        SeekBar bar = StorySeekBar.barForTests();
        assertNotNull("the viewer has a bar", bar);
        return bar;
    }

    @Test
    public void offToStartAddsNothing() {
        StorySeekBar.resumed(viewer);
        tick();
        assertNull(StorySeekBar.barForTests());
        assertEquals("only the viewer's own view", 1, content.getChildCount());
    }

    @Test
    public void aVideoStoryGetsABarThatFollowsTheVideoAboveTheSendMessageBox() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        SeekBar bar = bar();
        assertSame("in the frame holding the viewer", content, bar.getParent());
        assertEquals("just after the viewer", content.indexOfChild(viewerView) + 1, content.indexOfChild(bar));
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(15000, bar.getMax());
        assertEquals(3000, bar.getProgress());

        player.position = 9000;
        tick();
        assertEquals(9000, bar.getProgress());

        idle();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
        assertEquals(Gravity.BOTTOM, params.gravity);
        assertEquals("just above the 120 pixel Send message box", 120, params.bottomMargin);
        assertTrue(player.calls.isEmpty());
    }

    /**
     * #689: the bar caught up with the video only once a tick, so on a playing video it jumped a
     * quarter of a second at a time. While the video plays it moves every frame, gliding on from the
     * player's last new position when that comes in steps, and it settles where a paused video is.
     */
    @Test
    public void aPlayingVideosBarMovesBetweenTicksAndSettlesWhenItPauses() {
        // Unpaused, Robolectric runs each frame at once and moves the clock on a frame, so a whole
        // glide would pass inside one idle. Paused, frames come when the clock reaches them.
        ShadowChoreographer.setPaused(true);
        try {
            Settings.STORY_SEEK_BAR.save(true);
            StorySeekBar.resumed(viewer);
            SeekBar bar = bar();
            assertEquals(3000, bar.getProgress());
            player.position = 3250;
            // A millisecond at a time up to the tick, so the glide is timed from the moment it read 3250.
            for (int waited = 0; waited <= StorySeekBar.TICK_MS && bar.getProgress() == 3000; waited++) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
            }
            assertEquals("the tick takes the new position", 3250, bar.getProgress());

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            int glided = bar.getProgress();
            assertTrue("it moves on between ticks at the video's pace: " + glided, glided > 3290 && glided <= 3350);

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StorySeekBar.TICK_MS));
            assertEquals("a paused video's bar goes back to where it stopped", 3250, bar.getProgress());
            tick();
            assertEquals(3250, bar.getProgress());
        } finally {
            ShadowChoreographer.setPaused(false);
        }
    }

    /**
     * #486: on Instagram 453 the Send message box sits in the row along the story's bottom, and the
     * old box's name only lives in a stub that's never inflated. The bar sits above that row, and
     * moves up with it when a larger font makes the row taller.
     */
    @Test
    public void onInstagram453TheBarSitsAboveTheStoryBottomRow() {
        int footerId = View.generateViewId();
        StorySeekBar.footerIdForTests(activity, footerId);
        LinearLayout footer = new LinearLayout(activity);
        footer.setId(footerId);
        viewerView.addView(footer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 181, Gravity.BOTTOM));
        idle();
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals("above the 181 pixel row, not the 120 pixel box", 181, margin(bar()));

        footer.getLayoutParams().height = 260;
        footer.requestLayout();
        idle();
        tick();
        idle();
        assertEquals("a taller row moves the bar up", 260, margin(bar()));
    }

    /** #486: mid-swipe to the next person's stories both pages hold a bottom row, and the one on screen counts. */
    @Test
    public void theBottomRowOnScreenWinsOverTheNextPersonsMidSwipe() {
        int footerId = View.generateViewId();
        StorySeekBar.footerIdForTests(activity, footerId);
        LinearLayout next = new LinearLayout(activity);
        next.setId(footerId);
        viewerView.addView(next, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 240, Gravity.BOTTOM));
        LinearLayout shown = new LinearLayout(activity);
        shown.setId(footerId);
        viewerView.addView(shown, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 181, Gravity.BOTTOM));
        idle();
        next.setTranslationX(content.getWidth());
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals("the row on screen, not the one past its edge", 181, margin(bar()));

        next.setTranslationX(0);
        shown.setTranslationX(-content.getWidth());
        tick();
        idle();
        assertEquals("the swipe done, the next person's row", 240, margin(bar()));
    }

    /**
     * #490: on your own story the "Say something..." line sits over the toolbar row, outside it, and
     * the bar clears both. With the line gone the bar comes back down to the row.
     */
    @Test
    public void onYourOwnStoryTheBarClearsTheSaySomethingLineOverTheToolbar() {
        int footerId = View.generateViewId();
        StorySeekBar.footerIdForTests(activity, footerId);
        LinearLayout footer = new LinearLayout(activity);
        footer.setId(footerId);
        viewerView.addView(footer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
        int lineId = View.generateViewId();
        StorySeekBar.idForTests(activity, StorySeekBar.COMMENT_LINE, lineId);
        View line = new View(activity);
        line.setId(lineId);
        FrameLayout.LayoutParams lineParams = new FrameLayout.LayoutParams(150, 40, Gravity.BOTTOM | Gravity.START);
        lineParams.bottomMargin = 100;
        viewerView.addView(line, lineParams);
        idle();
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals("above the line over the 100 pixel row", 140, margin(bar()));

        line.setVisibility(View.GONE);
        tick();
        idle();
        assertEquals("the line gone, back above the 120 pixel box under it", 120, margin(bar()));
    }

    /** #490: a story comments line high up the story isn't the bottom row, so the bar stays down. */
    @Test
    public void aSaySomethingLineHighUpTheStoryDoesNotLiftTheBar() {
        int lineId = View.generateViewId();
        StorySeekBar.idForTests(activity, StorySeekBar.COMMENT_LINE, lineId);
        View line = new View(activity);
        line.setId(lineId);
        FrameLayout.LayoutParams lineParams = new FrameLayout.LayoutParams(150, 40, Gravity.TOP | Gravity.START);
        lineParams.topMargin = 20;
        viewerView.addView(line, lineParams);
        idle();
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals("still above the 120 pixel Send message box", 120, margin(bar()));
    }

    /** #490: your own story's toolbar counts on its own too, for a build that moves it out of the footer. */
    @Test
    public void yourOwnStorysToolbarOutsideTheFooterStillLiftsTheBar() {
        int footerId = View.generateViewId();
        StorySeekBar.footerIdForTests(activity, footerId);
        LinearLayout footer = new LinearLayout(activity);
        footer.setId(footerId);
        viewerView.addView(footer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
        int selfId = View.generateViewId();
        StorySeekBar.idForTests(activity, StorySeekBar.SELF_TOOLBAR, selfId);
        LinearLayout self = new LinearLayout(activity);
        self.setId(selfId);
        viewerView.addView(self, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 150, Gravity.BOTTOM));
        idle();
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals("above the taller of the two rows", 150, margin(bar()));
    }

    /** #636: placed below, the bar sits in the middle of the space under the Send message row, and back above it after. */
    @Test
    public void belowPutsTheBarInTheSpaceUnderTheSendMessageRow() {
        rowOver(60);
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        SeekBar bar = bar();
        assertEquals("its full 32 pixels", 32, height(bar));
        assertEquals("in the middle of the 60 pixels under the row", 14, margin(bar));

        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.ABOVE);
        tick();
        idle();
        assertEquals("back above the 181 pixel row", 241, margin(bar));
        assertEquals(32, height(bar));
    }

    /** #636: less space under the row than the bar's height gets a bar just that tall, so it never covers the row. */
    @Test
    public void belowShrinksTheBarToATightSpace() {
        rowOver(24);
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals(24, height(bar()));
        assertEquals(0, margin(bar()));
    }

    /**
     * #636: with less than 16 dp under the row, the row goes up just enough to make that room and the
     * bar sits under it, staying put tick after tick. Back above, the row comes back down.
     */
    @Test
    public void belowWithLittleRoomLiftsTheRowJustEnough() {
        View footer = rowOver(10);
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals("up the 6 pixels the 16 needs", -6f, footer.getTranslationY(), 0f);
        assertEquals(16, height(bar()));
        assertEquals(0, margin(bar()));
        tick();
        idle();
        tick();
        idle();
        assertEquals("not lifted again on its own room", -6f, footer.getTranslationY(), 0f);
        assertEquals(16, height(bar()));

        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.ABOVE);
        tick();
        idle();
        assertEquals("back down", 0f, footer.getTranslationY(), 0f);
        assertEquals("above the row and the 10 pixels under it", 191, margin(bar()));
        assertEquals(32, height(bar()));
    }

    /** #636: a row Instagram moves itself is left where it put it, and lifted again once it's back. */
    @Test
    public void aRowInstagramMovesIsLeftToIt() {
        View footer = rowOver(10);
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals(-6f, footer.getTranslationY(), 0f);
        footer.setTranslationY(-100f);
        tick();
        idle();
        assertEquals("left where Instagram put it", -100f, footer.getTranslationY(), 0f);
        assertEquals("with plenty of room under it", 32, height(bar()));
        footer.setTranslationY(0f);
        tick();
        idle();
        assertEquals("lifted again", -6f, footer.getTranslationY(), 0f);
    }

    /** #636: turning the bar off, pausing HushGram or the bar going away puts a lifted row back. */
    @Test
    public void aLiftedRowComesBackDownWhenTheBarGoes() {
        View footer = rowOver(10);
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals(-6f, footer.getTranslationY(), 0f);
        Settings.STORY_SEEK_BAR.save(false);
        tick();
        idle();
        assertEquals("switched off", 0f, footer.getTranslationY(), 0f);

        Settings.STORY_SEEK_BAR.save(true);
        tick();
        idle();
        assertEquals(-6f, footer.getTranslationY(), 0f);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        tick();
        idle();
        assertEquals("paused", 0f, footer.getTranslationY(), 0f);

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        tick();
        idle();
        assertEquals(-6f, footer.getTranslationY(), 0f);
        StorySeekBar.paused(viewer);
        idle();
        assertEquals("the viewer went", 0f, footer.getTranslationY(), 0f);
    }

    /** #636: the bottom padding an edge-to-edge row keeps clear of the swipe area counts as room. */
    @Test
    public void aRowsBottomPaddingCountsAsRoom() {
        View footer = rowOver(0);
        footer.setPadding(0, 0, 0, 40);
        idle();
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals(32, height(bar()));
        assertEquals("in the middle of the 40 padded pixels", 4, margin(bar()));
        assertEquals("nothing lifted", 0f, footer.getTranslationY(), 0f);
    }

    /** #636: below the row, the bar keeps out of the system's bars and swipe area at the window's bottom. */
    @Test
    public void belowKeepsClearOfTheSystemBars() {
        View footer = rowOver(60);
        int[] frame = new int[2];
        content.getLocationInWindow(frame);
        int pastFrame = content.getRootView().getHeight() - frame[1] - content.getHeight();
        StorySeekBar.systemBottomForTests(pastFrame + 20);
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals(32, height(bar()));
        assertEquals("in the middle of the 40 pixels between the row and the bars", 24, margin(bar()));

        StorySeekBar.systemBottomForTests(pastFrame + 50);
        tick();
        idle();
        assertEquals("10 pixels left over the bars lifts the row 6", -6f, footer.getTranslationY(), 0f);
        assertEquals("and the bar sits on the bars", 50, margin(bar()));
        assertEquals(16, height(bar()));

        StorySeekBar.systemBottomForTests(pastFrame + 100);
        tick();
        idle();
        assertEquals("a keyboard over the row puts it back", 0f, footer.getTranslationY(), 0f);
        assertEquals("and the bar goes above the row", 241, margin(bar()));
        assertEquals(32, height(bar()));
    }

    /**
     * #636: the Send message row leaves a strip under its box and buttons with nothing drawn in it,
     * and the bar takes that strip without lifting the row. A row with its own background is drawn
     * down to its bottom, so it has no such strip.
     */
    @Test
    public void belowUsesTheEmptyStripUnderTheRowsBox() {
        LinearLayout footer = rowWithBoxOver(0);
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals("the 23 pixels under the box", 23, height(bar()));
        assertEquals(0, margin(bar()));
        assertEquals("nothing lifted", 0f, footer.getTranslationY(), 0f);

        footer.setBackgroundColor(0xFF000000);
        tick();
        idle();
        assertEquals("up the 16 pixels the bar needs", -16f, footer.getTranslationY(), 0f);
        assertEquals(16, height(bar()));
        assertEquals(0, margin(bar()));
    }

    /**
     * #636: on gesture navigation the swipe area along the bottom reaches above the bar there, and a
     * full-screen story's row sits right on that bar. The row goes up until the bar fits between its
     * box and the swipe area. A keyboard over the box still sends the bar above.
     */
    @Test
    public void belowLiftsTheRowClearOfTheSwipeArea() {
        LinearLayout footer = rowWithBoxOver(0);
        int pastFrame = pastFrame();
        StorySeekBar.systemBottomForTests(pastFrame);
        StorySeekBar.swipeBottomForTests(pastFrame + 48);
        Settings.STORY_SEEK_BAR_POSITION.save(StorySeekBarPosition.BELOW);
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        idle();
        assertEquals("up the 16 plus the 25 the swipe area takes past the 23 free", -41f, footer.getTranslationY(), 0f);
        assertEquals(16, height(bar()));
        assertEquals("right on the swipe area", 48, margin(bar()));
        tick();
        idle();
        assertEquals("not lifted again on its own room", -41f, footer.getTranslationY(), 0f);
        assertEquals(48, margin(bar()));

        StorySeekBar.systemBottomForTests(pastFrame + 300);
        StorySeekBar.swipeBottomForTests(pastFrame + 300);
        tick();
        idle();
        assertEquals("a keyboard over the box puts it back", 0f, footer.getTranslationY(), 0f);
        assertEquals("and the bar goes above the row", 181, margin(bar()));
        assertEquals(32, height(bar()));
    }

    /**
     * The Send message row as Instagram lays it out: a 135 pixel box 23 pixels down with nothing
     * drawn in the 23 pixels under it, plus a stub that never inflated.
     */
    private LinearLayout rowWithBoxOver(int space) {
        LinearLayout footer = (LinearLayout) rowOver(space);
        FrameLayout box = new FrameLayout(activity);
        box.addView(new View(activity), new FrameLayout.LayoutParams(200, 135));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(200, 135);
        at.topMargin = 23;
        footer.addView(box, at);
        View stub = new View(activity);
        stub.setVisibility(View.GONE);
        footer.addView(stub, new LinearLayout.LayoutParams(50, 181));
        idle();
        return footer;
    }

    /** How far the window reaches past the bottom of the bar's frame, in pixels. */
    private int pastFrame() {
        int[] frame = new int[2];
        content.getLocationInWindow(frame);
        return content.getRootView().getHeight() - frame[1] - content.getHeight();
    }

    /** The 181 pixel row along a story's bottom on 453, [space] pixels over the viewer's bottom, with the old box's stub never inflated. */
    private View rowOver(int space) {
        viewerView.removeView(composer);
        int footerId = View.generateViewId();
        StorySeekBar.footerIdForTests(activity, footerId);
        LinearLayout footer = new LinearLayout(activity);
        footer.setId(footerId);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 181, Gravity.BOTTOM);
        params.bottomMargin = space;
        viewerView.addView(footer, params);
        idle();
        return footer;
    }

    private static int margin(SeekBar bar) {
        return ((FrameLayout.LayoutParams) bar.getLayoutParams()).bottomMargin;
    }

    private static int height(SeekBar bar) {
        return bar.getLayoutParams().height;
    }

    /** #471: Seek bar look's choice for stories reaches the bar as it follows the video. */
    @Test
    public void theBarTakesSeekBarLook() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        GlassSeekBar bar = (GlassSeekBar) bar();
        assertEquals("solid to start, as the bar always was", OverlayLook.SOLID, bar.look());

        Settings.STORY_SEEK_BAR_LOOK.save(OverlayLook.SEE_THROUGH);
        tick();
        assertEquals(OverlayLook.SEE_THROUGH, bar.look());
        assertSame("the same bar, restyled", bar, StorySeekBar.barForTests());
    }

    /** #586: the story bar takes the same shape as the others, and Standard gives its own back. */
    @Test
    public void theBarTakesTheChosenShape() {
        Settings.STORY_SEEK_BAR.save(true);
        Settings.SEEK_BAR_SHAPE.save(SeekShape.EXPRESSIVE);
        StorySeekBar.resumed(viewer);
        tick();
        SeekBar bar = bar();
        assertEquals("SeekShapeDrawable", bar.getProgressDrawable().getClass().getSimpleName());
        assertEquals("a shape has no round handle", 0, bar.getThumb().getIntrinsicWidth());

        Settings.SEEK_BAR_SHAPE.save(SeekShape.STANDARD);
        tick();
        assertSame("the same bar", bar, StorySeekBar.barForTests());
        assertFalse("SeekShapeDrawable".equals(bar.getProgressDrawable().getClass().getSimpleName()));
    }

    @Test
    public void dragPausesLikeAHoldAndLettingGoSeeksThenPlaysOn() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        SeekBar bar = bar();
        Listener drag = new Listener(bar);

        drag.start();
        bar.setProgress(12000);
        player.position = 4000;
        tick();
        assertEquals("a drag isn't overwritten by the video", 12000, bar.getProgress());
        drag.stop();

        assertEquals(Arrays.asList("pause long_pressed", "seek 12000", "resume"), player.calls);
        tick();
        assertEquals(12000, bar.getProgress());
    }

    @Test
    public void aLiveStoryRefusingTheSeekStillPlaysOn() {
        Settings.STORY_SEEK_BAR.save(true);
        player.liveSeekThrows = true;
        StorySeekBar.resumed(viewer);
        Listener drag = new Listener(bar());
        drag.start();
        drag.stop();
        assertEquals(Arrays.asList("pause long_pressed", "seek 3000", "resume"), player.calls);
    }

    @Test
    public void aPhotoHidesTheBar() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        SeekBar bar = bar();
        player.duration = -1;
        tick();
        assertEquals(View.GONE, bar.getVisibility());
        player.duration = 8000;
        tick();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(8000, bar.getMax());
    }

    @Test
    public void unfilledStubsShowNothing() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.playerForTests(StorySeekBar.PATCHED);
        StorySeekBar.resumed(viewer);
        tick();
        assertNull(StorySeekBar.barForTests());
    }

    @Test
    public void switchingOffOrPausingHushgramHidesTheBarAndADragDoesNothing() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        SeekBar bar = bar();

        Settings.STORY_SEEK_BAR.save(false);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
        new Listener(bar).start();
        new Listener(bar).stop();
        assertTrue(player.calls.isEmpty());

        Settings.STORY_SEEK_BAR.save(true);
        tick();
        assertEquals(View.VISIBLE, bar.getVisibility());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
    }

    @Test
    public void theViewerGoingAwayTakesItsBarAndEndsADrag() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        SeekBar bar = bar();
        new Listener(bar).start();

        StorySeekBar.paused(new Object());
        assertSame("another viewer's pause leaves this bar", bar, StorySeekBar.barForTests());
        StorySeekBar.paused(viewer);
        assertNull(StorySeekBar.barForTests());
        assertNull(bar.getParent());
        assertEquals("a drag cut short plays the story on", Arrays.asList("pause long_pressed", "resume"), player.calls);
        tick();
        assertEquals(1, content.getChildCount());
        int reads = player.reads;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StorySeekBar.TICK_MS * 8));
        assertEquals("a gone viewer's bar stops ticking", reads, player.reads);
    }

    @Test
    public void theBarHidingMidDragPlaysTheStoryOn() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        SeekBar bar = bar();
        new Listener(bar).start();
        Settings.STORY_SEEK_BAR.save(false);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
        assertEquals(Arrays.asList("pause long_pressed", "resume"), player.calls);
        new Listener(bar).stop();
        assertEquals("letting go of a hidden bar does nothing more", 2, player.calls.size());
    }

    @Test
    public void aSheetOverTheStoryIsDrawnOverTheBar() {
        Settings.STORY_SEEK_BAR.save(true);
        FrameLayout sheets = new FrameLayout(activity);
        content.addView(sheets, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        StorySeekBar.resumed(viewer);
        SeekBar bar = bar();
        assertTrue("Instagram's sheets, drawn after the viewer, stay over the bar",
                content.indexOfChild(bar) < content.indexOfChild(sheets));
    }

    @Test
    public void resumingTwiceKeepsOneBar() {
        Settings.STORY_SEEK_BAR.save(true);
        StorySeekBar.resumed(viewer);
        SeekBar bar = bar();
        StorySeekBar.resumed(viewer);
        assertSame(bar, StorySeekBar.barForTests());
        assertEquals(2, content.getChildCount());
    }

    /** The bar's own drag callbacks, as a finger on it gives them. */
    private static final class Listener {
        private final SeekBar bar;

        Listener(SeekBar bar) {
            this.bar = bar;
        }

        void start() {
            shadowOf(bar).getOnSeekBarChangeListener().onStartTrackingTouch(bar);
        }

        void stop() {
            shadowOf(bar).getOnSeekBarChangeListener().onStopTrackingTouch(bar);
        }
    }
}
