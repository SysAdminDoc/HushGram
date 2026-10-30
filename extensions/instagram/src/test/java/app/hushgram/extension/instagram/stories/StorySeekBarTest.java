/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        HookStatus.clear();
    }

    @After
    public void restore() {
        StorySeekBar.resetForTests();
        Settings.STORY_SEEK_BAR.resetToDefault();
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
