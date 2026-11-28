/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
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

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Move story controls off the story (#902), the second switch of Keep story buttons below the story:
 * where the story card leaves black space above and below it, the header over the card's top moves
 * up into the space under the system's bars, with the name row over the progress bar once all of it
 * fits, and the toolbar moves down until the reply box sits at the page's bottom or on the bars.
 *
 * <p>The page is 457's on a tall phone, scaled: 200 px of black above a 1300 px page, a 1000 px card
 * at its top holding a 141 px header (a 6 px progress bar 15 px down, then a 122 px name row that
 * starts 2 px above the bar's bottom), and a 1150 px toolbar whose 150 px row ends in 40 px of
 * padding, so the reply box ends at 1110 with 190 px of page under it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w720dp-h1800dp-mdpi")
public class StoryControlsOffTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private FrameLayout viewerView;
    private FrameLayout page;
    private FrameLayout media;
    private LinearLayout header;
    private View progress;
    private View row;
    private FrameLayout toolbar;
    private int pageTop;
    private final Object viewer = new Object();

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        StoryButtonsRoom.resetForTests();
        int pageId = View.generateViewId();
        int mediaId = View.generateViewId();
        int toolbarId = View.generateViewId();
        int footerId = View.generateViewId();
        int headerId = View.generateViewId();
        int progressId = View.generateViewId();
        int rowId = View.generateViewId();
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.PAGE, pageId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.MEDIA, mediaId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.TOOLBAR, toolbarId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.FOOTER, footerId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.HEADER, headerId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.PROGRESS, progressId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.HEADER_ROW, rowId);

        viewerView = new FrameLayout(activity);
        page = new FrameLayout(activity);
        page.setId(pageId);
        media = new FrameLayout(activity);
        media.setId(mediaId);
        header = new LinearLayout(activity);
        header.setId(headerId);
        header.setOrientation(LinearLayout.VERTICAL);
        progress = new View(activity);
        progress.setId(progressId);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(720, 6);
        bar.topMargin = 15;
        header.addView(progress, bar);
        row = new View(activity);
        row.setId(rowId);
        LinearLayout.LayoutParams name = new LinearLayout.LayoutParams(720, 122);
        name.topMargin = -2;
        header.addView(row, name);
        media.addView(header, new FrameLayout.LayoutParams(720, 141, Gravity.TOP));
        page.addView(media, new FrameLayout.LayoutParams(720, 1000, Gravity.TOP));
        toolbar = new FrameLayout(activity);
        toolbar.setId(toolbarId);
        FrameLayout footer = new FrameLayout(activity);
        footer.setId(footerId);
        footer.setPadding(0, 0, 0, 40);
        toolbar.addView(footer, new FrameLayout.LayoutParams(720, 150, Gravity.BOTTOM));
        page.addView(toolbar, new FrameLayout.LayoutParams(720, 1150, Gravity.TOP));
        FrameLayout.LayoutParams placed = new FrameLayout.LayoutParams(720, 1300, Gravity.TOP);
        placed.topMargin = 200;
        viewerView.addView(page, placed);
        activity.setContentView(viewerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        StoryButtonsRoom.readerForTests(of -> of == viewer ? viewerView : null);
        idle();
        int[] at = new int[2];
        page.getLocationInWindow(at);
        pageTop = at[1];
        assertTrue("the window holds the whole page", page.getRootView().getHeight() - pageTop >= 1300);
        assertEquals("the name row starts 2 px above the bar's bottom", 19, row.getTop());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_STORY_BUTTONS_BELOW.resetToDefault();
        Settings.STORY_CONTROLS_OFF_STORY.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryButtonsRoom.resetForTests();
        Settings.KEEP_STORY_BUTTONS_BELOW.resetToDefault();
        Settings.STORY_CONTROLS_OFF_STORY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** One frame: the layout pass's listeners, then the pre-draw's. Answers whether a listener cancelled it. */
    private boolean frame() {
        ViewTreeObserver tree = viewerView.getViewTreeObserver();
        tree.dispatchOnGlobalLayout();
        return tree.dispatchOnPreDraw();
    }

    /** Resumes the viewer with [room] pixels between the system's bars and the card, and nothing over the page's bottom. */
    private void open(int room) {
        StoryButtonsRoom.barsForTests(pageTop - room, 0);
        StoryButtonsRoom.resumed(viewer);
        assertFalse("the frame goes on", frame());
    }

    private static int top(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1];
    }

    @Test
    public void theLiftIsTheReachCappedByTheRoomAndTheEmptyStrip() {
        assertEquals("all of it fits", 141, StoryButtonsRoom.lift(141, 141, 15));
        assertEquals("the empty strip may go past the room", 141, StoryButtonsRoom.lift(141, 126, 15));
        assertEquals("as far as there's room", 75, StoryButtonsRoom.lift(141, 60, 15));
        assertEquals("no room, only the strip", 15, StoryButtonsRoom.lift(141, 0, 15));
        assertEquals("not over the story", 0, StoryButtonsRoom.lift(0, 141, 15));
        assertEquals("a room under the bars counts as none", 15, StoryButtonsRoom.lift(141, -20, 15));
    }

    @Test
    public void theDropPutsTheBoxOnTheFloor() {
        assertEquals("box ends at 1110, floor 1300", 190, StoryButtonsRoom.drop(1150, 40, 1300));
        assertEquals("the bars cut the floor", 140, StoryButtonsRoom.drop(1150, 40, 1250));
        assertEquals("already on it", 0, StoryButtonsRoom.drop(1150, 40, 1110));
        assertEquals("never up", 0, StoryButtonsRoom.drop(1150, 0, 1100));
    }

    @Test
    public void theHeaderMovesUpIntoTheBlackWithTheNameOverTheBar() {
        open(141);
        assertEquals("its bottom comes off the card", -141f, header.getTranslationY(), 0f);
        assertEquals("the name row goes where the bar was", -4f, row.getTranslationY(), 0f);
        assertEquals("the bar goes to the header's bottom", 120f, progress.getTranslationY(), 0f);
        assertEquals("the bar sits right on the card", top(media), top(progress) + progress.getHeight());
        assertEquals("the name row starts where the bar did", pageTop - 141 + 15, top(row));
        assertFalse("the card lets the header draw above it", media.getClipChildren());
        assertFalse("so does the page", page.getClipChildren());
        assertFalse("and the viewer lets the page draw above its top", viewerView.getClipChildren());
        assertEquals("the card draws its children up to the bars", Integer.valueOf(141), StoryButtonsRoom.aboveForTests(media));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.STORY_BUTTONS_ROOM + ": invoked 1"));
        assertTrue(HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).toString(),
                HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).isEmpty());
    }

    @Test
    public void withLessRoomItGoesAsFarAsTheBarsAndKeepsItsOrder() {
        open(60);
        assertEquals(-75f, header.getTranslationY(), 0f);
        assertEquals("what it draws starts right under the bars", pageTop - 60, top(progress));
        assertEquals("the order stays", 0f, row.getTranslationY(), 0f);
        assertEquals(0f, progress.getTranslationY(), 0f);
        assertFalse(media.getClipChildren());
    }

    @Test
    public void withNoRoomOnlyTheEmptyStripGoesAndNothingStopsClipping() {
        open(0);
        assertEquals("the bar comes up to the card's top", -15f, header.getTranslationY(), 0f);
        assertEquals(pageTop, top(progress));
        assertTrue(media.getClipChildren());
        assertTrue(page.getClipChildren());
        assertNull("nothing draws above the card", StoryButtonsRoom.aboveForTests(media));
    }

    @Test
    public void aCardClippedToItsShapeKeepsTheHeaderOnIt() {
        media.setClipToOutline(true);
        open(141);
        assertEquals("only the strip that draws nothing", -15f, header.getTranslationY(), 0f);
        assertEquals(0f, row.getTranslationY(), 0f);
        assertTrue(page.getClipChildren());
        assertNull(StoryButtonsRoom.aboveForTests(media));
    }

    @Test
    public void theReplyBoxGoesToThePagesBottom() {
        Settings.KEEP_STORY_BUTTONS_BELOW.save(false);
        open(141);
        assertEquals("from 1110 down to 1300", 190f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void theSystemsBarsStopTheBox() {
        Settings.KEEP_STORY_BUTTONS_BELOW.save(false);
        StoryButtonsRoom.barsForTests(pageTop - 141, page.getRootView().getHeight() - pageTop - 1250);
        StoryButtonsRoom.resumed(viewer);
        frame();
        assertEquals("down to the bars at 1250", 140f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void withBothSwitchesTheFartherMoveWins() {
        FrameLayout.LayoutParams card = (FrameLayout.LayoutParams) media.getLayoutParams();
        card.height = 1040;
        media.setLayoutParams(card);
        idle();
        Settings.STORY_CONTROLS_OFF_STORY.save(false);
        open(141);
        assertEquals("the row covers 40 px of the card", 40f, toolbar.getTranslationY(), 0f);
        Settings.STORY_CONTROLS_OFF_STORY.save(true);
        frame();
        assertEquals(190f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void laterFramesKeepEverythingWithoutCreeping() {
        open(141);
        header.requestLayout();
        idle();
        frame();
        frame();
        assertEquals(-141f, header.getTranslationY(), 0f);
        assertEquals(-4f, row.getTranslationY(), 0f);
        assertEquals(120f, progress.getTranslationY(), 0f);
        assertEquals(190f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void theSwitchGoingOffPutsEverythingBack() {
        open(141);
        Settings.STORY_CONTROLS_OFF_STORY.save(false);
        frame();
        assertEquals(0f, header.getTranslationY(), 0f);
        assertEquals(0f, row.getTranslationY(), 0f);
        assertEquals(0f, progress.getTranslationY(), 0f);
        assertEquals("the row never covered the card", 0f, toolbar.getTranslationY(), 0f);
        assertTrue("the clipping comes back", media.getClipChildren());
        assertTrue(page.getClipChildren());
        assertTrue(viewerView.getClipChildren());
        assertNull("the card draws within its box again", StoryButtonsRoom.aboveForTests(media));
    }

    @Test
    public void aPausePutsEverythingBack() {
        open(141);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertEquals(0f, header.getTranslationY(), 0f);
        assertEquals(0f, progress.getTranslationY(), 0f);
        assertEquals(0f, toolbar.getTranslationY(), 0f);
        assertTrue(media.getClipChildren());
        assertNull(StoryButtonsRoom.aboveForTests(media));
        assertTrue("the saved choice is kept", Settings.STORY_CONTROLS_OFF_STORY.savedValue());
    }

    @Test
    public void instagramsOwnMoveOfTheHeaderWins() {
        open(141);
        header.setTranslationY(-500f);
        frame();
        assertEquals("left where Instagram put it", -500f, header.getTranslationY(), 0f);
        assertEquals("the rows go back in order", 0f, row.getTranslationY(), 0f);
        assertEquals(0f, progress.getTranslationY(), 0f);
        assertTrue("and the clipping comes back", media.getClipChildren());
        header.setTranslationY(0f);
        frame();
        assertEquals("back at rest, it moves again", -141f, header.getTranslationY(), 0f);
    }

    @Test
    public void offToStartLeavesTheHeaderAndBoxAlone() {
        Settings.STORY_CONTROLS_OFF_STORY.resetToDefault();
        assertFalse("off by default", Settings.STORY_CONTROLS_OFF_STORY.get());
        open(141);
        assertEquals(0f, header.getTranslationY(), 0f);
        assertEquals(0f, progress.getTranslationY(), 0f);
        assertEquals(0f, toolbar.getTranslationY(), 0f);
        assertTrue(media.getClipChildren());
        assertFalse("nobody asked for the header's ids",
                HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).toString().contains(StoryButtonsRoom.HEADER));
    }

    @Test
    public void onItsOwnItStillRuns() {
        Settings.KEEP_STORY_BUTTONS_BELOW.save(false);
        open(141);
        assertTrue(StoryButtonsRoom.keptForTests());
        assertEquals(-141f, header.getTranslationY(), 0f);
    }

    /**
     * The card's dispatchDraw (#902): for a card whose header is up, the extension clips the canvas
     * to the strip above the card for the children's extra pass, then to the card's own box for its
     * own pass, which it answers 0 for so Instagram's code runs, and puts the canvas back after.
     */
    @Test
    public void aCardWithItsHeaderUpDrawsTheStripThenItself() {
        open(141);
        Clips canvas = new Clips();
        int count = StoryButtonsRoom.drawStart(media, canvas);
        assertEquals("the count before the strip's clip", 1, count);
        assertEquals("the strip above the card", "0,-141,720,0", canvas.clip);
        int own = StoryButtonsRoom.stripDrawn(media, canvas, count);
        assertEquals("the strip's clip is gone and the card's is on", 1, own);
        assertEquals(2, canvas.saves);
        assertEquals("the card's own box", "0,0,720,1000", canvas.clip);
        assertEquals("the card's own pass is Instagram's", 0, StoryButtonsRoom.drawStart(media, canvas));
        StoryButtonsRoom.cardDrawn(canvas, own);
        assertEquals("the canvas is back where it was", 1, canvas.saves);
        assertEquals("the next frame draws the strip again", 1, StoryButtonsRoom.drawStart(media, canvas));
    }

    @Test
    public void everyOtherCardDrawsAsItDid() {
        Clips canvas = new Clips();
        assertEquals("nothing is up yet", 0, StoryButtonsRoom.drawStart(media, canvas));
        open(141);
        assertEquals("another card", 0, StoryButtonsRoom.drawStart(page, canvas));
        assertEquals("not a canvas", 0, StoryButtonsRoom.drawStart(media, new Object()));
        assertEquals("nothing was clipped", 1, canvas.saves);
        Settings.STORY_CONTROLS_OFF_STORY.save(false);
        frame();
        assertEquals("the switch went off", 0, StoryButtonsRoom.drawStart(media, canvas));
        assertNull(canvas.clip);
    }

    /** A tap on the name row up above the card taps it, and Instagram never sees the touch (#902). */
    @Test
    public void aTapOnTheHeaderAboveTheCardTapsItsButton() {
        int[] taps = {0};
        row.setOnClickListener(v -> taps[0]++);
        open(141);
        Activity activity = controller.get();
        float y = top(row) + 40;
        assertTrue("above the card", y < top(media));
        assertTrue("down on it is the header's", StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_DOWN, 100, y)));
        assertTrue(StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_MOVE, 102, y + 1)));
        assertTrue(StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_UP, 102, y + 1)));
        assertEquals("the row was tapped", 1, taps[0]);
        assertFalse("the next touch on the card is Instagram's", StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_DOWN, 100, top(media) + 50)));
        assertFalse(StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_UP, 100, top(media) + 50)));
        assertEquals(1, taps[0]);
    }

    @Test
    public void aSwipeOrAnOffSwitchTapsNothing() {
        int[] taps = {0};
        row.setOnClickListener(v -> taps[0]++);
        open(141);
        Activity activity = controller.get();
        float y = top(row) + 40;
        assertTrue(StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_DOWN, 100, y)));
        assertTrue("a swipe stays the header's", StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_MOVE, 300, y)));
        assertTrue(StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_UP, 300, y)));
        assertEquals("but taps nothing", 0, taps[0]);
        Settings.STORY_CONTROLS_OFF_STORY.save(false);
        frame();
        assertFalse("with the header back, the touch is Instagram's", StoryButtonsRoom.touch(activity, event(MotionEvent.ACTION_DOWN, 100, y)));
        assertEquals(0, taps[0]);
    }

    private static MotionEvent event(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    /** A canvas that counts its saves and keeps its last clip, the way the card's drawing sees them. */
    private static final class Clips extends Canvas {
        int saves = 1;
        String clip;

        @Override
        public int save() {
            return saves++;
        }

        @Override
        public void restoreToCount(int count) {
            saves = count;
        }

        @Override
        public void restore() {
            saves--;
        }

        @Override
        public boolean clipRect(int left, int top, int right, int bottom) {
            clip = left + "," + top + "," + right + "," + bottom;
            return true;
        }
    }

    @Test
    public void aBuildWithoutTheHeaderIsReportedAndStillMovesTheBox() {
        StoryButtonsRoom.putIdForTests(controller.get(), StoryButtonsRoom.HEADER, 0);
        open(141);
        assertEquals(0f, header.getTranslationY(), 0f);
        assertEquals(190f, toolbar.getTranslationY(), 0f);
        assertTrue(HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).toString(),
                HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).toString().contains(StoryButtonsRoom.HEADER));
    }
}
