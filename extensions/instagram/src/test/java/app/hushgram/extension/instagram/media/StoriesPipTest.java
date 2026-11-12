/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Picture in picture for stories: when a story goes to the window, which pause is held, and what ends it (#572). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoriesPipTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object viewer = new Object();
    private final Object other = new Object();
    private final FakeWindow window = new FakeWindow();
    private Screen screen;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STORIES_PICTURE_IN_PICTURE.save(true);
        HookStatus.clear();
        StoriesPip.resetForTests();
        BackgroundPlay.resetForTests();
        StoriesPip.windowForTests = window;
        screen = Robolectric.buildActivity(Screen.class).setup().get();
    }

    @After
    public void restore() {
        StoriesPip.resetForTests();
        BackgroundPlay.resetForTests();
        Settings.STORIES_PICTURE_IN_PICTURE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Leaving with a story playing puts its screen in the window and holds only the fragment's pause of that story. */
    @Test
    public void leavingWithAStoryPlayingOpensTheWindow() {
        playing(viewer);

        assertTrue(StoriesPip.leaving(screen));

        assertEquals(Collections.singletonList(screen), window.entered);
        assertTrue(StoriesPip.active());
        assertTrue("the fragment's pause is held", StoriesPip.pausing(viewer, StoriesPip.FRAGMENT_PAUSED));
        assertFalse("another viewer's isn't", StoriesPip.pausing(other, StoriesPip.FRAGMENT_PAUSED));
        assertFalse("nor a pause for another reason", StoriesPip.pausing(viewer, "user_paused"));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.BACKGROUND_PLAY));
    }

    @Test
    public void offOpensNothing() {
        Settings.STORIES_PICTURE_IN_PICTURE.save(false);
        playing(viewer);

        assertFalse(StoriesPip.leaving(screen));

        assertTrue(window.entered.isEmpty());
        assertFalse(StoriesPip.pausing(viewer, StoriesPip.FRAGMENT_PAUSED));
    }

    /** A story paused since it resumed, or never resumed at all, isn't playing. */
    @Test
    public void aPausedStoryOpensNothing() {
        assertFalse("nothing resumed yet", StoriesPip.leaving(screen));

        playing(viewer);
        StoriesPip.pausing(viewer, "user_paused");

        assertFalse(StoriesPip.leaving(screen));
        assertTrue(window.entered.isEmpty());
    }

    /** A story playing on one screen doesn't send another screen being left to the window. */
    @Test
    public void aStoryOnAnotherScreenOpensNothing() {
        playing(viewer);
        Screen second = Robolectric.buildActivity(Screen.class).setup().get();

        assertFalse(StoriesPip.leaving(second));

        assertTrue(window.entered.isEmpty());
    }

    /** A screen that stopped plays nothing, though its fragment never said it paused. */
    @Test
    public void aStoppedScreenOpensNothing() {
        playing(viewer);
        StoriesPip.screenStopped(screen);

        assertFalse(StoriesPip.leaving(screen));
    }

    /** When Android won't make the window, nothing is held. */
    @Test
    public void noWindowHoldsNothing() {
        window.allow = false;
        playing(viewer);

        assertFalse(StoriesPip.leaving(screen));

        assertFalse(StoriesPip.active());
        assertFalse(StoriesPip.pausing(viewer, StoriesPip.FRAGMENT_PAUSED));
    }

    /** Closing the window stops the screen, and the story is paused through the viewer's own pause. */
    @Test
    public void closingTheWindowPausesTheStory() {
        open();

        StoriesPip.screenStopped(screen);

        assertEquals(Collections.singletonList(viewer), window.paused);
        assertFalse(StoriesPip.active());
        assertFalse("its pauses go through again", StoriesPip.pausing(viewer, StoriesPip.FRAGMENT_PAUSED));
    }

    /** The screen resuming in the window keeps the session; resuming full screen ends it with the story playing. */
    @Test
    public void expandingTheWindowKeepsTheStoryPlaying() {
        open();

        StoriesPip.screenResumed(screen);
        assertTrue("resumed inside the window", StoriesPip.active());

        window.showing = false;
        StoriesPip.screenResumed(screen);

        assertFalse(StoriesPip.active());
        assertTrue("nothing paused it", window.paused.isEmpty());
    }

    @Test
    public void closingTheScreenEndsItQuietly() {
        open();

        StoriesPip.screenDestroyed(screen);

        assertFalse(StoriesPip.active());
        assertTrue(window.paused.isEmpty());
    }

    /** A window asked for that never showed is dropped, and the story paused, once the watchdog runs. */
    @Test
    public void theWatchdogDropsAWindowThatNeverShowed() {
        window.showOnEnter = false;
        open();

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StoriesPip.WATCHDOG_MS));

        assertFalse(StoriesPip.active());
        assertEquals(Collections.singletonList(viewer), window.paused);
    }

    @Test
    public void theWatchdogLeavesAShowingWindowAlone() {
        open();

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StoriesPip.WATCHDOG_MS));

        assertTrue(StoriesPip.active());
        assertTrue(window.paused.isEmpty());
    }

    /** The next video story starts in the window, though its screen is paused there; elsewhere the viewer's own answer stands. */
    @Test
    public void aVideoStoryStartsInTheWindow() {
        assertFalse("no window, a paused screen starts nothing", StoriesPip.startsVideo(viewer));
        window.resumedViewers.add(viewer);
        assertTrue("a resumed one does", StoriesPip.startsVideo(viewer));
        window.resumedViewers.clear();

        open();

        assertTrue(StoriesPip.startsVideo(viewer));
        assertFalse("another viewer doesn't", StoriesPip.startsVideo(other));

        StoriesPip.screenStopped(screen);

        assertFalse("not once the window closed", StoriesPip.startsVideo(viewer));
    }

    /** Keep playing in the background's leave hint asks first, and a story in the window keeps nothing else. */
    @Test
    public void theLeaveHintAsksFirst() {
        playing(viewer);

        BackgroundPlay.leaving(screen);

        assertTrue(StoriesPip.active());
        assertEquals(BackgroundPlay.Phase.IDLE, BackgroundPlay.phase());
    }

    /** Through the real activity callbacks: to the window, resumed there, then closed. */
    @Test
    public void throughTheActivityCallbacks() {
        BackgroundPlay.watch(RuntimeEnvironment.getApplication());
        ActivityController<Screen> live = Robolectric.buildActivity(Screen.class).setup();
        StoriesPip.resumed(viewer);

        assertTrue(StoriesPip.leaving(live.get()));
        live.pause();
        assertTrue(StoriesPip.pausing(viewer, StoriesPip.FRAGMENT_PAUSED));
        live.resume();
        assertTrue(StoriesPip.active());

        live.pause().stop();

        assertFalse(StoriesPip.active());
        assertEquals(Collections.singletonList(viewer), window.paused);
    }

    /** No hook throws into Instagram, whatever it's handed. */
    @Test
    public void nullsAreHarmless() {
        assertFalse(StoriesPip.leaving(null));
        assertFalse(StoriesPip.pausing(null, null));
        assertFalse(StoriesPip.startsVideo(null));
        StoriesPip.resumed(null);
        assertFalse(StoriesPip.active());
    }

    private void playing(Object story) {
        StoriesPip.screenResumed(screen);
        StoriesPip.resumed(story);
    }

    private void open() {
        playing(viewer);
        assertTrue(StoriesPip.leaving(screen));
    }

    public static final class Screen extends Activity {
    }

    /** The window Android would make, and the viewer's pause and isResumed() the patch writes. */
    private static final class FakeWindow implements StoriesPip.Window {
        boolean allow = true;
        boolean showOnEnter = true;
        boolean showing;
        final List<Activity> entered = new ArrayList<>();
        final List<Object> paused = new ArrayList<>();
        final List<Object> resumedViewers = new ArrayList<>();

        @Override
        public boolean enter(Activity screen) {
            entered.add(screen);
            showing = allow && showOnEnter;
            return allow;
        }

        @Override
        public boolean showing(Activity screen) {
            return showing;
        }

        @Override
        public void pause(Object viewer) {
            paused.add(viewer);
        }

        @Override
        public boolean resumed(Object viewer) {
            return resumedViewers.contains(viewer);
        }
    }
}
