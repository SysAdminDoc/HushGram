/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The gate on Instagram's Reels seek strip (#452): a press on a button the strip reaches over goes to
 * the button, and every other press, a press on the bar included, goes to Instagram's listener as
 * before. Laid out like 453 on a Galaxy S25 FE, where the strip covers the lower part of More.
 */
@RunWith(RobolectricTestRunner.class)
public class ReelSeekTouchTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 800;
    /** The strip's height and the bar's, both at the bottom. */
    private static final int STRIP_PX = 100;
    private static final int BAR_PX = 40;
    /** More: 100 px square on the right, its bottom 60 px above the screen's, so the strip covers its lower 40 px. */
    private static final int MORE_PX = 100;
    private static final int MORE_BOTTOM_MARGIN = 60;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private FrameLayout parent;
    private FrameLayout reel;
    private SeekBar bar;
    private View more;
    private View strip;

    /** What Instagram's strip listener and More's own touch listener were handed. */
    private final List<Integer> instagram = new ArrayList<>();
    private final List<Integer> moreTouches = new ArrayList<>();

    @Before
    public void lay() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ReflectionHelpers.setStaticField(ReelSeekTouch.class, "loggedPass", false);
        Context context = RuntimeEnvironment.getApplication();
        parent = new FrameLayout(context);
        // The reel page under it all, which pauses on a tap.
        reel = new FrameLayout(context);
        reel.setClickable(true);
        parent.addView(reel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar = new SeekBar(context);
        reel.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_PX, Gravity.BOTTOM));
        more = new View(context);
        more.setClickable(true);
        more.setOnTouchListener((view, event) -> {
            moreTouches.add(event.getActionMasked());
            return false;
        });
        FrameLayout.LayoutParams moreAt = new FrameLayout.LayoutParams(MORE_PX, MORE_PX, Gravity.BOTTOM | Gravity.END);
        moreAt.bottomMargin = MORE_BOTTOM_MARGIN;
        reel.addView(more, moreAt);
        // Instagram's strip, raised above the reel, plain and not clickable.
        strip = new View(context);
        parent.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, STRIP_PX, Gravity.BOTTOM));
        ReelSeekTouch.listen(strip, (view, event) -> {
            instagram.add(event.getActionMasked());
            return true;
        });
        layout();
    }

    @After
    public void restore() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private void layout() {
        parent.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, WIDTH, HEIGHT);
    }

    /** A whole tap at ({@code x}, {@code y}) in the parent, as Android hands it down: press, a small move, release. */
    private void tap(int x, int y) {
        long down = SystemClock.uptimeMillis();
        int[][] steps = {{MotionEvent.ACTION_DOWN, 0}, {MotionEvent.ACTION_MOVE, 2}, {MotionEvent.ACTION_UP, 2}};
        for (int i = 0; i < steps.length; i++) {
            int[] step = steps[i];
            MotionEvent event = MotionEvent.obtain(down, down + 20L * i, step[0], x + step[1], y, 0);
            parent.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    /** A point on More where the strip covers it. */
    private static final int MORE_X = WIDTH - MORE_PX / 2;
    private static final int MORE_Y = HEIGHT - MORE_BOTTOM_MARGIN - 20;

    @Test
    public void theStripCoversPartOfMoreAsOnThePhone() {
        assertEquals(HEIGHT - STRIP_PX, strip.getTop());
        assertTrue("More's bottom reaches under the strip", more.getBottom() > strip.getTop());
        assertTrue("More's top is above the strip", more.getTop() < strip.getTop());
        assertTrue("the strip covers the press point", MORE_Y >= strip.getTop());
    }

    /** The bug: the press on More, under the strip, goes to More, and Instagram's listener sees nothing of it. */
    @Test
    public void aTapOnMoreUnderTheStripGoesToMore() {
        tap(MORE_X, MORE_Y);

        assertEquals("Instagram's listener", new ArrayList<Integer>(), instagram);
        assertEquals("More", Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), moreTouches);
        assertFalse("the hook was counted", HookStatus.snapshot().isEmpty());
    }

    /** A press on the strip away from any button, or on the bar itself, stays Instagram's, the whole gesture. */
    @Test
    public void pressesAwayFromButtonsStillSeek() {
        tap(WIDTH / 2, HEIGHT - STRIP_PX + 20);
        assertEquals("above the bar", Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), instagram);

        instagram.clear();
        tap(MORE_X, HEIGHT - BAR_PX / 2);
        assertEquals("on the bar under More's column", Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), instagram);
        assertTrue("More saw none of them", moreTouches.isEmpty());
    }

    /** More hidden, faded out the way Clean up Reels hides it, or turned off, isn't a button to give way to. */
    @Test
    public void aButtonYouCantSeeOrUseKeepsNothing() {
        more.setVisibility(View.INVISIBLE);
        assertNull("invisible", ReelSeekTouch.buttonAt(strip, MORE_X, MORE_Y - strip.getTop()));
        more.setVisibility(View.VISIBLE);
        more.setAlpha(0f);
        assertNull("faded out", ReelSeekTouch.buttonAt(strip, MORE_X, MORE_Y - strip.getTop()));
        more.setAlpha(1f);
        more.setEnabled(false);
        assertNull("disabled", ReelSeekTouch.buttonAt(strip, MORE_X, MORE_Y - strip.getTop()));
        more.setEnabled(true);
        more.setClickable(false);
        assertNull("not tappable", ReelSeekTouch.buttonAt(strip, MORE_X, MORE_Y - strip.getTop()));
        more.setClickable(true);
        assertSame("back", more, ReelSeekTouch.buttonAt(strip, MORE_X, MORE_Y - strip.getTop()));
    }

    /** A tappable row that takes in the whole strip, like a caption panel across the bottom, stays behind it. */
    @Test
    public void aViewSpanningTheWholeStripStaysBehindIt() {
        LinearLayout panel = new LinearLayout(parent.getContext());
        panel.setClickable(true);
        reel.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, STRIP_PX + 50, Gravity.BOTTOM));
        layout();

        tap(WIDTH / 3, HEIGHT - STRIP_PX + 20);

        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), instagram);
    }

    /** Paused or before the settings are read, every press goes to Instagram's listener, More's too. */
    @Test
    public void pausedOrUnreadyLeavesInstagramsStrip() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        tap(MORE_X, MORE_Y);
        assertEquals("paused", Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), instagram);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        instagram.clear();
        SettingsContextRule.withoutContext(() -> tap(MORE_X, MORE_Y));
        assertEquals("unready", Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), instagram);
        assertTrue("More saw none of them", moreTouches.isEmpty());
    }

    /** A strip Instagram clears keeps no gate, and the gate decides once per press, not per move. */
    @Test
    public void theGateFollowsInstagramsListener() {
        ReelSeekTouch.listen(strip, null);
        tap(MORE_X, MORE_Y);
        assertTrue("no listener left on the strip", instagram.isEmpty());
        assertEquals("More still gets its tap", MotionEvent.ACTION_DOWN, (int) moreTouches.get(0));

        moreTouches.clear();
        List<Integer> seen = new ArrayList<>();
        ReelSeekTouch.Gate gate = new ReelSeekTouch.Gate((view, event) -> seen.add(event.getActionMasked()));
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, WIDTH / 2f, 20f, 0);
        MotionEvent onMore = MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_MOVE, MORE_X, MORE_Y - strip.getTop(), 0);
        assertTrue(gate.onTouch(strip, press));
        assertTrue("a drag that started on the strip keeps seeking over More", gate.onTouch(strip, onMore));
        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), seen);
        press.recycle();
        onMore.recycle();
    }

    /**
     * #526: a press the glass tab bar hands the strip, from where the strip runs over the bar, stays
     * Instagram's seek even with a button under the point, and the next ordinary press is gated again.
     */
    @Test
    public void aPressFromTheTabBarStaysWithTheSeekBar() {
        long down = SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(down, down + 20L * i, actions[i], MORE_X - 40f * i, MORE_Y - strip.getTop(), 0);
            assertTrue(ReelSeekTouch.fromTheBar(strip, event));
            event.recycle();
        }
        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), instagram);
        assertTrue("More saw none of it", moreTouches.isEmpty());

        instagram.clear();
        tap(MORE_X, MORE_Y);
        assertTrue("an ordinary press on More is More's again", instagram.isEmpty());
        assertEquals(MotionEvent.ACTION_DOWN, (int) moreTouches.get(0));
    }

    /** A strip with no parent, or a press outside it, is Instagram's. */
    @Test
    public void aStripOutOfPlaceGivesNothingAway() {
        View loose = new View(parent.getContext());
        assertNull(ReelSeekTouch.buttonAt(loose, 10, 10));
        assertNull("above the strip", ReelSeekTouch.buttonAt(strip, MORE_X, -10));
    }
}
