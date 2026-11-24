/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Whole story in the story menu (#845): with Stories fill the screen on, one story fits inside the
 * screen until the next story binds to its page. On a 1080x2340 phone a story laid out over the whole
 * screen is cut at the sides; shown whole its frame is 1080 wide and 1920 tall, a band above and below.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w360dp-h780dp-xxhdpi")
public class StoryWholeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private FrameLayout room;
    private View frame;
    private final Object holder = new Object();

    @Before
    public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Settings.FILL_STORY_SCREEN.save(true);
        Settings.WHOLE_STORY_IN_STORY_MENU.save(true);
        controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        controller.create();
        room = new FrameLayout(activity);
        frame = new FrameLayout(activity);
        room.addView(frame, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        activity.setContentView(room);
        controller.start().resume().visible();
        idle();
    }

    @After
    public void tearDown() {
        StoryWhole.resetForTests();
        ReelFill.SHARED_REELS.clear();
        ReelFill.SHARED_FRAMES.clear();
        Settings.FILL_STORY_SCREEN.resetToDefault();
        Settings.WHOLE_STORY_IN_STORY_MENU.resetToDefault();
        HookStatus.clear();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** What Instagram's binder and whole-screen story layout do as a story opens on [page]. */
    private void instagramLaysOut(Object page, int sharedReel) {
        ReelFill.storyBound(page, sharedReel);
        frame.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        ReelFill.storyFrame(page, frame);
        frame.requestLayout();
        idle();
    }

    /** The height of a 9:16 frame as wide as the room, from the shape, not from the frame. */
    private int wholeHeight() {
        return Math.round(room.getWidth() * 16f / 9f);
    }

    /** Off to start: nothing offered and a tap leaves the frame filling the screen. */
    @Test
    public void startsOff() {
        Settings.WHOLE_STORY_IN_STORY_MENU.resetToDefault();
        instagramLaysOut(holder, 0);
        assertEquals(StoryWhole.Offer.NONE, StoryWhole.offer(controller.get()));
        assertEquals(StoryWhole.Offer.NONE, StoryWhole.toggle(controller.get()));
        idle();
        assertEquals(room.getHeight(), frame.getHeight());
    }

    /**
     * A story cut at the sides: the tap gives its frame the room's width and a 9:16 height, centered,
     * and Instagram's own second layout keeps it. The row then turns to Fill the screen, whose tap puts
     * Instagram's whole-screen frame back.
     */
    @Test
    public void aCutStoryShowsWholeAndFillsAgain() {
        instagramLaysOut(holder, 0);
        Activity activity = controller.get();
        assertTrue("the phone is taller than 9:16", room.getHeight() > wholeHeight());
        assertEquals(StoryWhole.Offer.WHOLE, StoryWhole.offer(activity));

        assertEquals(StoryWhole.Offer.WHOLE, StoryWhole.toggle(activity));
        idle();
        assertEquals(room.getWidth(), frame.getWidth());
        assertEquals(wholeHeight(), frame.getHeight());
        assertEquals("a band above and below", (room.getHeight() - wholeHeight()) / 2, frame.getTop());
        assertEquals(Gravity.CENTER, ((FrameLayout.LayoutParams) frame.getLayoutParams()).gravity);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(StoryWhole.COUNT_WHOLE));
        assertEquals(StoryWhole.Offer.CROP, StoryWhole.offer(activity));

        frame.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        ReelFill.storyFrame(holder, frame);
        idle();
        assertEquals("Instagram's second layout keeps it whole", wholeHeight(), frame.getHeight());

        assertEquals(StoryWhole.Offer.CROP, StoryWhole.toggle(activity));
        idle();
        assertEquals(room.getHeight(), frame.getHeight());
        assertEquals(room.getWidth(), frame.getWidth());
        assertEquals(StoryWhole.Offer.WHOLE, StoryWhole.offer(activity));
    }

    /** The next story bound to the same page fills the screen again; one bound to another page doesn't. */
    @Test
    public void theNextStoryFillsTheScreenAgain() {
        instagramLaysOut(holder, 0);
        assertEquals(StoryWhole.Offer.WHOLE, StoryWhole.toggle(controller.get()));
        ReelFill.storyBound(new Object(), 0);
        idle();
        assertEquals(wholeHeight(), frame.getHeight());

        ReelFill.storyBound(holder, 0);
        idle();
        assertEquals(room.getHeight(), frame.getHeight());
        assertTrue(StoryWhole.WHOLE.isEmpty());
    }

    /** A page that leaves the window gets Instagram's frame back. */
    @Test
    public void aPageLeavingTheWindowFillsAgain() {
        instagramLaysOut(holder, 0);
        assertEquals(StoryWhole.Offer.WHOLE, StoryWhole.toggle(controller.get()));
        room.removeView(frame);
        assertTrue(StoryWhole.WHOLE.isEmpty());
        assertEquals(MATCH_PARENT, frame.getLayoutParams().height);
    }

    /** A story sharing a reel already has its whole 9:16 frame, so it gets no row. */
    @Test
    public void aSharedReelGetsNoRow() {
        instagramLaysOut(holder, 1);
        frame.requestLayout();
        idle();
        assertEquals(wholeHeight(), frame.getHeight());
        assertEquals(StoryWhole.Offer.NONE, StoryWhole.offer(controller.get()));
        assertEquals(StoryWhole.Offer.NONE, StoryWhole.toggle(controller.get()));
    }

    /** With Stories fill the screen off the story is Instagram's whole card, and there's no row. */
    @Test
    public void storiesThatDontFillGetNoRow() {
        Settings.FILL_STORY_SCREEN.save(false);
        instagramLaysOut(holder, 0);
        assertEquals(StoryWhole.Offer.NONE, StoryWhole.offer(controller.get()));
        assertEquals(StoryWhole.Offer.NONE, StoryWhole.toggle(controller.get()));
        assertEquals(StoryWhole.Offer.NONE, StoryWhole.offer(null));
        assertNull(StoryWhole.menuLabel(controller.get()));
    }

    /** Paused, nothing is offered. */
    @Test
    public void pausedOffersNothing() {
        instagramLaysOut(holder, 0);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            assertEquals(StoryWhole.Offer.NONE, StoryWhole.offer(controller.get()));
        } finally {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
        }
    }

    /** Both of the row's labels are its own, and Instagram's rows aren't. */
    @Test
    public void theRowKnowsItsLabels() {
        assertTrue(StoryWhole.isMenuLabel("Show the whole story"));
        assertTrue(StoryWhole.isMenuLabel("Fill the screen"));
        assertFalse(StoryWhole.isMenuLabel("Report"));
        assertFalse(StoryWhole.isMenuLabel(null));
    }
}
