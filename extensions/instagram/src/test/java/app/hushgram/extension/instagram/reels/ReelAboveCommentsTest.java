/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Control the reel above comments (#665): with a reel's comments open, a tap on the reel above them
 * pauses or plays it and is kept, so the sheet stays, and a hold plays it at 2x until it's let go of.
 * Instagram's own answers stand for every other tap, and off, paused or failing, every tap is
 * Instagram's.
 */
@RunWith(RobolectricTestRunner.class)
public class ReelAboveCommentsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Where the reel shows above the sheet, on the screen. */
    private static final Rect REEL = new Rect(100, 50, 600, 400);

    /** Stands in for Instagram's sheet and Reels player: whether the reel plays, and every call made on it. */
    private static final class FakeSheet implements ReelAboveComments.Sheet {
        final Object controller = new Object();
        final List<String> calls = new ArrayList<>();
        boolean open = true;
        Rect reel = new Rect(REEL);
        boolean hasController = true;
        int playing = 1;
        RuntimeException failure;

        @Override
        public boolean open(Object manager) {
            if (failure != null) throw failure;
            return open;
        }

        @Override
        public Rect reel(Object manager) {
            return reel == null ? null : new Rect(reel);
        }

        @Override
        public Object controller(Object manager) {
            return hasController ? controller : null;
        }

        @Override
        public int playing(Object controller) {
            return playing;
        }

        @Override
        public void pause(Object controller) {
            calls.add("pause");
            playing = 0;
        }

        @Override
        public void resume(Object controller) {
            calls.add("resume");
            playing = 1;
        }

        @Override
        public void speed(Object controller, float speed) {
            calls.add("speed " + speed);
        }
    }

    private FakeSheet sheet;
    private Object manager;
    private Activity activity;
    private long clock;

    @Before
    public void start() {
        ReelAboveComments.forget();
        ReelSpeed.forget();
        sheet = new FakeSheet();
        ReelAboveComments.access = sheet;
        manager = new Object();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        clock = 1_000L;
        HookStatus.clear();
        Settings.REEL_ABOVE_COMMENTS.save(true);
        ReelAboveComments.sheetMoved(manager);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.REEL_ABOVE_COMMENTS.resetToDefault();
        ReelAboveComments.forget();
        ReelSpeed.forget();
        HookStatus.clear();
    }

    private MotionEvent event(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, clock, action, x, y, 0);
        clock += 10;
        return event;
    }

    /** A tap as Instagram's detector confirms it, with Instagram's own answer [handled]: 1 when it's kept. */
    private int tap(int handled, float x, float y) {
        long down = clock;
        MotionEvent confirmed = event(down, MotionEvent.ACTION_UP, x, y);
        return ReelAboveComments.tapped(handled, manager, confirmed) ? 1 : 0;
    }

    private long down(float x, float y) {
        long down = clock;
        ReelAboveComments.touch(activity, event(down, MotionEvent.ACTION_DOWN, x, y));
        return down;
    }

    private void waitHold() {
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout() + 50L, TimeUnit.MILLISECONDS);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_ABOVE_COMMENTS + ":")) return line;
        }
        return null;
    }

    @Test
    public void aTapOnTheReelPausesThenPlaysItAndKeepsTheSheet() {
        assertEquals("a tap on a playing reel isn't kept", 1, tap(0, 300, 200));
        assertEquals(List.of("pause"), sheet.calls);
        assertEquals("a tap on a paused reel isn't kept", 1, tap(0, 300, 200));
        assertEquals(List.of("pause", "resume"), sheet.calls);
        assertTrue(statusLine(), String.join("\n", HookStatus.report()).contains(ReelAboveComments.TAPPED));
    }

    @Test
    public void instagramsOwnKeptTapStands() {
        // The audio button: Instagram keeps that tap itself.
        assertEquals(1, tap(1, 300, 200));
        assertTrue(sheet.calls.isEmpty());
    }

    @Test
    public void aTapOffTheReelClosesTheSheetAsInstagramHasIt() {
        assertEquals("beside the reel", 0, tap(0, 20, 200));
        assertEquals("on the sheet below the reel", 0, tap(0, 300, 900));
        assertTrue(sheet.calls.isEmpty());
    }

    @Test
    public void withoutAnOpenSheetAReelOrAPlayerTheTapIsInstagrams() {
        sheet.open = false;
        assertEquals("a closing sheet", 0, tap(0, 300, 200));
        sheet.open = true;
        sheet.reel = null;
        assertEquals("no reel shown above the sheet", 0, tap(0, 300, 200));
        sheet.reel = new Rect(REEL);
        sheet.hasController = false;
        assertEquals("no player controller", 0, tap(0, 300, 200));
        sheet.hasController = true;
        sheet.playing = -1;
        assertEquals("no reel in the player", 0, tap(0, 300, 200));
        assertFalse("no manager", ReelAboveComments.tapped(0, null, event(clock, MotionEvent.ACTION_UP, 300, 200)));
        assertFalse("no event", ReelAboveComments.tapped(0, manager, null));
        assertTrue("Instagram's kept tap with no manager", ReelAboveComments.tapped(1, null, null));
        assertTrue(sheet.calls.isEmpty());
    }

    @Test
    public void offTheTapIsInstagrams() {
        assertFalse("the switch starts off", Settings.REEL_ABOVE_COMMENTS.defaultValue);
        Settings.REEL_ABOVE_COMMENTS.save(false);
        assertEquals(0, tap(0, 300, 200));
        down(300, 200);
        waitHold();
        assertTrue(sheet.calls.isEmpty());
    }

    @Test
    public void pausedTheTapIsInstagrams() {
        for (HushgramPause.Reason reason : new HushgramPause.Reason[] {
                HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertEquals(reason.name(), 0, tap(0, 300, 200));
            down(300, 200);
            waitHold();
            assertTrue(reason.name(), sheet.calls.isEmpty());
            PauseForTests.resume();
        }
        assertEquals(1, tap(0, 300, 200));
    }

    @Test
    public void withoutSettingsTheTapIsInstagrams() {
        int[] answer = new int[1];
        SettingsContextRule.withoutContext(() -> answer[0] = tap(0, 300, 200));
        assertEquals(0, answer[0]);
        assertTrue(sheet.calls.isEmpty());
    }

    @Test
    public void aHoldPlaysAtTwiceTheSpeedUntilItsLetGoOf() {
        long down = down(300, 200);
        assertTrue("sped up before the long-press time", sheet.calls.isEmpty());
        waitHold();
        assertEquals(List.of("speed 2.0"), sheet.calls);
        ReelAboveComments.touch(activity, event(down, MotionEvent.ACTION_UP, 300, 200));
        assertEquals(List.of("speed 2.0", "speed 1.0"), sheet.calls);
        // Instagram's detector confirms the lift of a hold as a tap: kept, and it doesn't pause.
        assertTrue(ReelAboveComments.tapped(0, manager, event(down, MotionEvent.ACTION_UP, 300, 200)));
        assertEquals(List.of("speed 2.0", "speed 1.0"), sheet.calls);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelAboveComments.HELD));
    }

    @Test
    public void aHoldPutsBackTheSpeedTheReelHad() {
        ReelSpeed.speedSet(1.5f);
        long down = down(300, 200);
        waitHold();
        ReelAboveComments.touch(activity, event(down, MotionEvent.ACTION_CANCEL, 300, 200));
        assertEquals(List.of("speed 2.0", "speed 1.5"), sheet.calls);
    }

    @Test
    public void aHoldOnAPausedReelPlaysItAndPausesItAgain() {
        sheet.playing = 0;
        long down = down(300, 200);
        waitHold();
        assertEquals(List.of("resume", "speed 2.0"), sheet.calls);
        ReelAboveComments.touch(activity, event(down, MotionEvent.ACTION_UP, 300, 200));
        assertEquals(List.of("resume", "speed 2.0", "speed 1.0", "pause"), sheet.calls);
    }

    @Test
    public void aHoldLeftByALostLiftEndsAtTheNextTouch() {
        down(300, 200);
        waitHold();
        // Another hook swallowed the lift: the next finger down ends the hold.
        down(20, 900);
        assertEquals(List.of("speed 2.0", "speed 1.0"), sheet.calls);
    }

    @Test
    public void aDragOrASecondFingerIsNoHold() {
        long down = down(300, 200);
        ReelAboveComments.touch(activity, event(down, MotionEvent.ACTION_MOVE, 300, 400));
        waitHold();
        assertTrue("a drag sped the reel up", sheet.calls.isEmpty());
        down = down(300, 200);
        ReelAboveComments.touch(activity, event(down, MotionEvent.ACTION_MOVE, 301, 201));
        waitHold();
        assertEquals("a finger that barely moved isn't held", List.of("speed 2.0"), sheet.calls);
        ReelAboveComments.touch(activity, event(down, MotionEvent.ACTION_UP, 301, 201));
        sheet.calls.clear();
        down = down(300, 200);
        ReelAboveComments.touch(activity, MotionEvent.obtain(down, clock, MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300, 200, 0));
        waitHold();
        assertTrue("a pinch sped the reel up", sheet.calls.isEmpty());
    }

    @Test
    public void aShortTouchOrOneOffTheReelIsNoHold() {
        long down = down(300, 200);
        ReelAboveComments.touch(activity, event(down, MotionEvent.ACTION_UP, 300, 200));
        waitHold();
        down(300, 900);
        waitHold();
        sheet.open = false;
        down(300, 200);
        waitHold();
        assertTrue(sheet.calls.isEmpty());
    }

    @Test
    public void aSheetThatNeverMovedIsLeftAlone() {
        ReelAboveComments.forget();
        ReelAboveComments.access = sheet;
        down(300, 200);
        waitHold();
        assertTrue(sheet.calls.isEmpty());
    }

    @Test
    public void aFailureIsReportedAndTheTapIsInstagrams() {
        sheet.failure = new IllegalStateException("the sheet failed");
        assertEquals(0, tap(0, 300, 200));
        assertTrue(HookStatus.missing(FamilyNames.REEL_ABOVE_COMMENTS).get(0)
                .startsWith("a working 'tap' hook (it threw "));
        down(300, 200);
        waitHold();
        assertTrue(sheet.calls.isEmpty());
    }

    @Test
    public void unpatchedStubsDoNothing() {
        ReelAboveComments.access = ReelAboveComments.PATCHED;
        assertEquals(0, tap(0, 300, 200));
        down(300, 200);
        waitHold();
        assertNull(ReelAboveComments.reelOnScreen(null));
        View shown = new View(activity);
        activity.setContentView(shown);
        assertNull("a reel with no sheet over it", ReelAboveComments.reelOnScreen(shown));
    }

    @Test
    public void theReelCountsOnlyAboveTheSheet() {
        assertEquals(new Rect(0, 100, 500, 700), ReelAboveComments.above(new Rect(0, 100, 500, 900), 700));
        assertEquals(new Rect(0, 100, 500, 600), ReelAboveComments.above(new Rect(0, 100, 500, 600), 700));
        assertNull("a reel under the sheet", ReelAboveComments.above(new Rect(0, 800, 500, 900), 700));
        assertNull("a reel the sheet covers to its top", ReelAboveComments.above(new Rect(0, 700, 500, 900), 700));
    }
}
