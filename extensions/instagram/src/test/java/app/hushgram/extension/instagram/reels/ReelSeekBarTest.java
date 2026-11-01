/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.lang.ref.WeakReference;
import java.util.Collection;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the seek bar hooks answer Instagram, and the time label they keep beside the reel's bar:
 * its text, where it sits, that it follows the bar and takes no touches, that it goes with its bar
 * and stays off ads and other screens, and that it gives way when the switch is off or anything
 * goes wrong. Views are in an activity's window, as Instagram's are.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37})
public class ReelSeekBarTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 400;

    /** Instagram 449's reel scrubber container and its bar, in pixels, as a phone showed them. */
    private static final int CONTAINER_PX = 45;
    private static final int BAR_PX = 28;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> activity;
    /** The activity's content, which each test's views go in. */
    private FrameLayout window;

    @Before
    public void enable() {
        ApplicationInfo info = RuntimeEnvironment.getApplication().getApplicationInfo();
        info.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.REEL_SEEK_BAR.save(true);
        HookStatus.clear();
        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        window = new FrameLayout(activity.get());
        activity.get().setContentView(window);
    }

    @After
    public void restore() {
        activity.close();
        Settings.REEL_SEEK_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        RuntimeEnvironment.setFontScale(1f);
    }

    /** On, ordinary reels of a second or more get the bar, never the hidden kind; a shorter server minimum stays. */
    @Test
    public void onTheBarIsKeptOnShortReels() {
        assertEquals(1L, ReelSeekBar.minSeconds(30L));
        assertEquals(1L, ReelSeekBar.minSeconds(1L));
        assertEquals(0L, ReelSeekBar.minSeconds(0L));
        assertEquals(-1L, ReelSeekBar.minSeconds(-1L));
        assertFalse(ReelSeekBar.lazy(1));
        assertFalse(ReelSeekBar.lazy(0));
        assertFalse("the hooks were counted", HookStatus.snapshot().isEmpty());
    }

    /** Off, paused or before the settings are read, Instagram's answers stand and the label is hidden. */
    @Test
    public void offPausedAndUnreadyLeaveInstagramsAnswers() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertNotNull(label);
        assertEquals(View.VISIBLE, label.getVisibility());

        Settings.REEL_SEEK_BAR.save(false);
        assertInstagramsAnswers("off", bar, label);
        Settings.REEL_SEEK_BAR.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertInstagramsAnswers("paused", bar, label);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertInstagramsAnswers("unready", bar, label));

        ReelSeekBar.progress(bar, 11_000);
        assertEquals("back on", View.VISIBLE, label.getVisibility());
        assertEquals("0:11 / 0:55", label.getText().toString());
    }

    private static void assertInstagramsAnswers(String what, SeekBar bar, TextView label) {
        assertEquals(what, 30L, ReelSeekBar.minSeconds(30L));
        assertEquals(what, 0L, ReelSeekBar.minSeconds(0L));
        assertTrue(what, ReelSeekBar.lazy(1));
        assertFalse(what, ReelSeekBar.lazy(0));
        ReelSeekBar.progress(bar, 12_000);
        assertEquals(what, View.GONE, label.getVisibility());
    }

    /** A switch that throws leaves Instagram's answers and hides the label, and says which hook threw. */
    @Test
    public void aThrowingSwitchLeavesInstagramsAnswersAndIsReported() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);

        assertEquals(30L, ReelSeekBar.minSeconds(30L, () -> {
            throw new IllegalStateException("settings went away");
        }));
        assertTrue(ReelSeekBar.lazy(1, () -> {
            throw new IllegalStateException("settings went away");
        }));
        ReelTimeLabel.update(bar, 11_000, () -> {
            throw new IllegalStateException("settings went away");
        });

        assertEquals(View.GONE, label.getVisibility());
        String missing = HookStatus.missing(FamilyNames.REEL_SEEK_BAR).toString();
        assertTrue(missing, missing.contains("'seek bar length'"));
        assertTrue(missing, missing.contains("'hidden seek bar'"));
        assertTrue(missing, missing.contains("'seek bar time'"));
    }

    /** m:ss below an hour and h:mm:ss from one, as Instagram writes its own scrubber times. */
    @Test
    public void timesReadLikeInstagramsOwn() {
        assertEquals("0:00", ReelTimeLabel.time(0));
        assertEquals("0:00", ReelTimeLabel.time(999));
        assertEquals("0:59", ReelTimeLabel.time(59_999));
        assertEquals("1:00", ReelTimeLabel.time(60_000));
        assertEquals("59:59", ReelTimeLabel.time(3_599_999));
        assertEquals("1:00:00", ReelTimeLabel.time(3_600_000));
        assertEquals("25:01:05", ReelTimeLabel.time(90_065_000));
        assertEquals("0:00", ReelTimeLabel.time(-5_000));

        assertEquals("0:10 / 0:55", ReelTimeLabel.text(10_000, 55_000));
        assertEquals("past the end counts as the end", "0:55 / 0:55", ReelTimeLabel.text(70_000, 55_000));
        assertEquals("before the start counts as the start", "0:00 / 0:55", ReelTimeLabel.text(-1, 55_000));
    }

    /** A bar with no length, a live video or one still loading, gets no label, and one it had goes. */
    @Test
    public void aBarWithNoLengthHasNoLabel() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 0);

        ReelSeekBar.progress(bar, 0);
        assertNull(ReelTimeLabel.labelOf(bar));
        assertEquals(1, container.getChildCount());

        bar.setMax(55_000);
        ReelSeekBar.progress(bar, 1_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals(View.VISIBLE, label.getVisibility());

        bar.setMax(0);
        ReelSeekBar.progress(bar, 0);
        assertEquals(View.GONE, label.getVisibility());
    }

    /**
     * In Instagram's FrameLayout container the label is a child of its own, above the end of the
     * bar's track. TalkBack reads it, and it takes no touch, so a drag starting on it reaches the bar.
     */
    @Test
    public void theLabelSitsAboveTheEndOfTheTrackAndTakesNoTouches() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 55_000);

        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        ReelSeekBar.progress(bar, 10_500);
        layout(container);

        TextView label = ReelTimeLabel.labelOf(bar);
        assertSame(container, label.getParent());
        assertEquals(2, container.getChildCount());
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:10 / 0:55", label.getText().toString());
        assertEquals("0:10 of 0:55", label.getContentDescription().toString());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, label.getImportantForAccessibility());
        assertFalse(label.isClickable());
        assertFalse(label.isLongClickable());
        assertFalse(label.isFocusable());
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, label.getWidth() / 2f, label.getHeight() / 2f, 0);
        assertFalse("the label took a touch", label.dispatchTouchEvent(down));
        down.recycle();

        assertEquals("the track's end", bar.getRight() - bar.getPaddingRight(), label.getRight());
        assertEquals("above the track", bar.getTop() + bar.getPaddingTop() - gap(bar.getContext()), label.getBottom());
        assertFits(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) assertNull(bar.getStateDescription());
    }

    /** One label for each bar, however often it's bound to another reel, and its text and width start over. */
    @Test
    public void oneLabelPerBarAcrossRebinds() {
        FrameLayout container = container();
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "1");
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);
        int shortWidth = label.getLayoutParams().width;

        // Bound to a reel over an hour long: the same label, wider, with the new reel's time.
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "2");
        bar.setMax(3_725_000);
        ReelSeekBar.progress(bar, 0);
        layout(container);
        assertSame(label, ReelTimeLabel.labelOf(bar));
        assertEquals(2, container.getChildCount());
        assertEquals("0:00 / 1:02:05", label.getText().toString());
        assertTrue("the label grew for the longer time", label.getLayoutParams().width > shortWidth);
        assertFits(label);

        // Another reel of the same length: a new tag alone starts the text over.
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "3");
        ReelSeekBar.progress(bar, 0);
        assertSame(label, ReelTimeLabel.labelOf(bar));
        assertEquals("0:00 / 1:02:05", label.getText().toString());
        assertEquals(ReelTimeLabel.REEL_TAG_PREFIX + "3", ReelTimeLabel.reelOf(bar));

        // The bar moved to another container: the label goes with it, still one.
        FrameLayout other = container();
        show(other, HEIGHT);
        container.removeView(bar);
        other.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        layout(other);
        ReelSeekBar.progress(bar, 5_000);
        assertSame(label, ReelTimeLabel.labelOf(bar));
        assertSame(other, label.getParent());
        assertEquals("the old container keeps nothing of the bar's", 0, container.getChildCount());
        assertEquals(2, other.getChildCount());

        // Another bar gets a label of its own.
        SeekBar second = bar(container(), 30_000);
        ReelSeekBar.progress(second, 0);
        assertNotSame(label, ReelTimeLabel.labelOf(second));
    }

    /** Right to left the bar fills from the right, so its end and the label are at the left. */
    @Test
    @Config(qualifiers = "ar")
    public void rightToLeftPutsTheLabelAtTheLeftEnd() {
        FrameLayout container = container();
        container.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        ReelSeekBar.progress(bar, 10_500);
        layout(container);

        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals(View.LAYOUT_DIRECTION_RTL, label.getLayoutDirection());
        assertEquals("the track's end", bar.getLeft() + bar.getPaddingLeft(), label.getLeft());
        assertEquals(ReelTimeLabel.text(10_000, 55_000), label.getText().toString());
        assertFits(label);
    }

    /** At twice the text size the longest time still fits on one line, nothing cut off. */
    @Test
    public void twiceTheTextSizeStillFits() {
        RuntimeEnvironment.setFontScale(2f);
        FrameLayout container = container();
        SeekBar bar = bar(container, 36_000_000 - 1_000);
        ReelSeekBar.progress(bar, 35_999_000);
        layout(container);
        ReelSeekBar.progress(bar, 35_999_500);
        layout(container);

        TextView label = ReelTimeLabel.labelOf(bar);
        // Android 14 and up scale large text less than linearly, so it's "larger", not exactly twice.
        float scaled = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ReelTimeLabel.TEXT_SP,
                label.getResources().getDisplayMetrics());
        assertTrue("the text didn't grow", scaled > ReelTimeLabel.TEXT_SP * label.getResources().getDisplayMetrics().density);
        assertEquals(scaled, label.getTextSize(), 0.01f);
        assertEquals("9:59:59 / 9:59:59", label.getText().toString());
        assertFits(label);
    }

    /** A bar not in a view yet gets no label and nothing throws; once it's put in one, the label comes. */
    @Test
    public void aBarWithNoParentGetsNoLabelAndNothingThrows() {
        SeekBar bar = new SeekBar(context());
        bar.setMax(55_000);

        ReelSeekBar.progress(bar, 1_000);
        ReelSeekBar.progress(null, 1_000);
        assertNull(ReelTimeLabel.labelOf(bar));
        assertTrue(HookStatus.missing(FamilyNames.REEL_SEEK_BAR).toString(), HookStatus.missing(FamilyNames.REEL_SEEK_BAR).isEmpty());

        FrameLayout container = container();
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        show(container, HEIGHT);
        ReelSeekBar.progress(bar, 2_000);
        assertNotNull(ReelTimeLabel.labelOf(bar));
        assertEquals(View.VISIBLE, ReelTimeLabel.labelOf(bar).getVisibility());
    }

    /** The label hides while the bar is dragged, when Instagram shows its own times, or hidden, and fades with it. */
    @Test
    public void theLabelFollowsTheBar() {
        FrameLayout container = container();
        SeekBar bar = bar(container, 55_000);
        ReelSeekBar.progress(bar, 10_000);
        layout(container);
        TextView label = ReelTimeLabel.labelOf(bar);

        bar.setPressed(true);
        ReelSeekBar.progress(bar, 20_000);
        assertEquals("dragged", View.GONE, label.getVisibility());
        bar.setPressed(false);

        bar.setVisibility(View.INVISIBLE);
        ReelSeekBar.progress(bar, 21_000);
        assertEquals("hidden", View.GONE, label.getVisibility());
        bar.setVisibility(View.VISIBLE);

        bar.setAlpha(0.4f);
        ReelSeekBar.progress(bar, 22_000);
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals(0.4f, label.getAlpha(), 0.001f);
        assertEquals("0:22 / 0:55", label.getText().toString());
    }

    /**
     * Any other parent, such as the Litho host of the newer scrubber, gets the label on its
     * overlay: not one of its children, above the end of the track, and on Android 11 and up the
     * bar carries its words, which go when the switch does.
     */
    @Test
    public void anotherParentGetsTheLabelOnItsOverlay() {
        LinearLayout host = new LinearLayout(context());
        ReelSeekBar.bind(host, 0);
        host.setOrientation(LinearLayout.VERTICAL);
        host.addView(new View(context()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200));
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 20, 40, 0);
        bar.setMax(55_000);
        host.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        show(host, HEIGHT);

        ReelSeekBar.progress(bar, 10_000);

        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals("the host's children", 2, host.getChildCount());
        assertNotNull(label.getParent());
        assertNotSame(host, label.getParent());
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:10 / 0:55", label.getText().toString());
        assertEquals("the track's end", bar.getRight() - bar.getPaddingRight(), label.getRight());
        assertEquals("above the track", bar.getTop() + bar.getPaddingTop() - gap(context()), label.getBottom());
        assertFits(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals("0:10 of 0:55", String.valueOf(bar.getStateDescription()));
        }

        Settings.REEL_SEEK_BAR.save(false);
        ReelSeekBar.progress(bar, 11_000);
        assertEquals(View.GONE, label.getVisibility());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) assertNull(bar.getStateDescription());
    }

    /**
     * Instagram 449's scrubber container is a FrameLayout hardly taller than its bar, in a Litho
     * host with room to spare. A label inside the container was cut in half on a phone, so it goes
     * on the host's overlay, whole, above the end of the track, and neither view gets a child.
     */
    @Test
    public void aContainerWithNoRoomPutsTheWholeLabelOnTheViewAboveIt() {
        float density = context().getResources().getDisplayMetrics().density;
        FrameLayout host = new FrameLayout(context());
        FrameLayout container = container();
        host.addView(container, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(16 * density), Gravity.BOTTOM));
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 0, 40, 0);
        bar.setMax(55_000);
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(10 * density), Gravity.BOTTOM));
        show(host, HEIGHT);

        ReelSeekBar.progress(bar, 10_000);
        layout(host);
        ReelSeekBar.progress(bar, 10_500);

        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals("the container's children", 1, container.getChildCount());
        assertEquals("the host's children", 1, host.getChildCount());
        assertNotNull(label.getParent());
        assertNotSame(container, label.getParent());
        assertNotSame(host, label.getParent());
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:10 / 0:55", label.getText().toString());
        assertTrue("the container had room for it", label.getHeight() + gap(context()) > bar.getTop() + bar.getPaddingTop());
        assertEquals("the track's end", container.getLeft() + bar.getRight() - bar.getPaddingRight(), label.getRight());
        assertEquals("above the track", container.getTop() + bar.getTop() + bar.getPaddingTop() - gap(context()), label.getBottom());
        assertTrue("inside the host", label.getTop() >= 0);
        assertFits(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals("0:10 of 0:55", String.valueOf(bar.getStateDescription()));
        }
    }

    /**
     * With Run Reels behind the tab bar on, the tab bar raises a reel's seek bar by scrolling the view
     * holding it, as far as the highest thing in it allows, and translating it the rest of the way. The
     * label stayed on that view's overlay, where only its layout had room, and came out with its top half
     * cut off at the view's top (#426). It goes to the view above, over the track where the track shows
     * now, with no tick or layout of the bar to prompt it, and goes back when the bar's lowered.
     */
    @Test
    public void aViewScrolledUpHasOnlyTheRoomStillShowing() {
        FrameLayout outer = new FrameLayout(context());
        FrameLayout band = new FrameLayout(context());
        outer.addView(band, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200, Gravity.BOTTOM));
        FrameLayout container = scrubber(band, 55_000, false);
        show(outer, HEIGHT);
        SeekBar bar = barIn(container);
        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        int overTrack = container.getTop() + bar.getTop() + bar.getPaddingTop() - gap(context());
        assertEquals("in the band, over the track", overTrack, label.getBottom());

        int scroll = container.getTop();
        band.scrollTo(0, scroll);
        frame();
        ShadowLooper.idleMainLooper();

        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("in the view above, over the track", band.getTop() + overTrack - scroll, label.getBottom());
        assertTrue("whole", label.getTop() >= 0);
        assertFits(label);

        band.setTranslationY(-50);
        frame();
        ShadowLooper.idleMainLooper();
        assertEquals("over the track, translated too", band.getTop() - 50 + overTrack - scroll, label.getBottom());

        band.setTranslationY(0);
        band.scrollTo(0, 0);
        frame();
        ShadowLooper.idleMainLooper();
        assertEquals("back in the band", overTrack, label.getBottom());
        assertNothingFailed();
    }

    /** A view scrolled up by less than the room over the bar keeps the label, which scrolls along with the bar. */
    @Test
    public void aViewScrolledALittleKeepsTheLabel() {
        FrameLayout outer = new FrameLayout(context());
        FrameLayout band = new FrameLayout(context());
        outer.addView(band, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200, Gravity.BOTTOM));
        FrameLayout container = scrubber(band, 55_000, false);
        show(outer, HEIGHT);
        SeekBar bar = barIn(container);
        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        int overTrack = container.getTop() + bar.getTop() + bar.getPaddingTop() - gap(context());

        band.scrollTo(0, 20);
        frame();
        ShadowLooper.idleMainLooper();

        assertEquals("the band's own place for it", overTrack, label.getBottom());
        assertTrue("whole", label.getTop() >= band.getScrollY());
    }

    /** The bar's own FrameLayout scrolled up gives the room away too: the label comes out of its children. */
    @Test
    public void aScrolledFrameLayoutGivesUpTheLabelItHeld() {
        FrameLayout container = container();
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 0, 40, 0);
        bar.setMax(55_000);
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_PX, Gravity.BOTTOM));
        FrameLayout outer = new FrameLayout(context());
        outer.addView(container, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200, Gravity.BOTTOM));
        show(outer, HEIGHT);
        ReelSeekBar.progress(bar, 10_000);
        layout(outer);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertSame(container, label.getParent());

        container.scrollTo(0, bar.getTop());
        frame();
        ShadowLooper.idleMainLooper();

        assertEquals("the bar is all it holds now", 1, container.getChildCount());
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("over the track", container.getTop() - gap(context()), label.getBottom());
    }

    /**
     * Instagram can give a bar its length and position before laying it out, and with autoplay held
     * nothing moves it again. The label comes with the bar's first layout all the same, and with
     * the switch off it doesn't.
     */
    @Test
    public void aBarSetBeforeItsLayoutGetsItsLabelOnceLaidOut() {
        FrameLayout container = container();
        show(container, HEIGHT);
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 20, 40, 0);
        bar.setMax(55_000);
        bar.setProgress(10_000);
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals("not laid out yet", View.GONE, label.getVisibility());

        layout(container);
        ShadowLooper.idleMainLooper();
        layout(container);

        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:10 / 0:55", label.getText().toString());
        assertSame(container, label.getParent());
        assertFits(label);

        // Off, a later layout of the bar brings nothing back.
        Settings.REEL_SEEK_BAR.save(false);
        ReelSeekBar.progress(bar, 11_000);
        assertEquals(View.GONE, label.getVisibility());
        bar.requestLayout();
        layout(container);
        ShadowLooper.idleMainLooper();
        assertEquals("off", View.GONE, label.getVisibility());
    }

    /** With no view near the bar tall enough for the whole label, there's no label rather than a cut-off one. */
    @Test
    public void noRoomAnywhereMeansNoLabel() {
        float density = context().getResources().getDisplayMetrics().density;
        FrameLayout container = container();
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 0, 40, 0);
        bar.setMax(55_000);
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(10 * density), Gravity.BOTTOM));
        showBoxed(container, Math.round(16 * density));

        ReelSeekBar.progress(bar, 10_000);

        TextView label = ReelTimeLabel.labelOf(bar);
        assertNotNull(label);
        assertEquals(View.GONE, label.getVisibility());
        assertNull("the label went nowhere", label.getParent());
        assertEquals("the container's children", 1, container.getChildCount());
        assertTrue(HookStatus.missing(FamilyNames.REEL_SEEK_BAR).toString(), HookStatus.missing(FamilyNames.REEL_SEEK_BAR).isEmpty());
    }

    /**
     * In 449's shape, the label is on the Litho host's overlay. When Litho takes the scrubber off
     * the host, the bar leaves the window: the label hides at once and leaves the host's overlay
     * right after, a late tick of the bar brings nothing back, and put back, it comes back.
     */
    @Test
    public void theLabelLeavesWithItsBar() {
        FrameLayout host = host();
        FrameLayout container = scrubber(host, 55_000, false);
        SeekBar bar = barIn(container);
        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals(View.VISIBLE, label.getVisibility());
        ViewGroup overlay = (ViewGroup) label.getParent();
        assertNotSame("on the host's overlay, not in the 45 px container", container, overlay);
        assertEquals(1, overlay.getChildCount());

        host.removeView(container);
        assertFalse(bar.isAttachedToWindow());
        assertEquals("hidden at once", View.GONE, label.getVisibility());
        ShadowLooper.idleMainLooper();
        assertNull("off the host", label.getParent());
        assertEquals("nothing left on the host's overlay", 0, overlay.getChildCount());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) assertNull(bar.getStateDescription());

        ReelSeekBar.progress(bar, 11_000);
        assertNull("a late tick", label.getParent());

        host.addView(container, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CONTAINER_PX, Gravity.BOTTOM));
        layout(host);
        ShadowLooper.idleMainLooper();
        assertSame(label, ReelTimeLabel.labelOf(bar));
        assertEquals("back", View.VISIBLE, label.getVisibility());
        assertEquals(1, ((ViewGroup) label.getParent()).getChildCount());
    }

    /**
     * The label hides when the bar, its container or any view above them is hidden or faded all the
     * way, and fades with the views between the bar and the host, on the next frame, without the
     * bar moving.
     */
    @Test
    public void theLabelHidesWithItsBarOrAnyViewAbove() {
        FrameLayout host = host();
        FrameLayout container = scrubber(host, 55_000, false);
        SeekBar bar = barIn(container);
        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals(View.VISIBLE, label.getVisibility());

        container.setVisibility(View.INVISIBLE);
        frame();
        assertEquals("container hidden", View.GONE, label.getVisibility());
        container.setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.VISIBLE, label.getVisibility());

        container.setAlpha(0f);
        frame();
        assertEquals("container faded out", View.GONE, label.getVisibility());
        container.setAlpha(0.5f);
        bar.setAlpha(0.5f);
        frame();
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("faded with the bar and its container", 0.25f, label.getAlpha(), 0.001f);
        bar.setAlpha(1f);
        container.setAlpha(1f);

        window.setVisibility(View.GONE);
        frame();
        assertEquals("a view above the host hidden", View.GONE, label.getVisibility());
        window.setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.VISIBLE, label.getVisibility());

        bar.setVisibility(View.GONE);
        frame();
        assertEquals("bar hidden", View.GONE, label.getVisibility());
        bar.setVisibility(View.VISIBLE);
        container.setAlpha(0f);
        ReelSeekBar.progress(bar, 12_000);
        assertEquals("and on a tick", View.GONE, label.getVisibility());
        container.setAlpha(1f);
        ReelSeekBar.progress(bar, 13_000);
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:13 / 0:55", label.getText().toString());
    }

    /**
     * Litho recycles a reel's host for an item with no bar, a photo or an ad Instagram gives none:
     * the scrubber comes off, other views go on, and nothing of the last reel's label is left on
     * the host's overlay.
     */
    @Test
    public void aHostRecycledForAnItemWithNoBarKeepsNoLabel() {
        FrameLayout host = host();
        FrameLayout container = scrubber(host, 55_000, false);
        SeekBar bar = barIn(container);
        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        ViewGroup overlay = (ViewGroup) label.getParent();
        assertEquals("0:10 / 0:55", label.getText().toString());

        host.removeView(container);
        host.addView(new View(context()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout(host);
        ShadowLooper.idleMainLooper();
        frame();

        assertNull(label.getParent());
        assertEquals(View.GONE, label.getVisibility());
        assertEquals("the host's overlay", 0, overlay.getChildCount());
    }

    /**
     * Litho puts the next reel's scrubber in the same host while the last one's is still there,
     * hidden. The new bar's label takes the host, the old one gives way, and the host holds one
     * label. Shown again while the new bar's label is up, the old bar gets none there.
     */
    @Test
    public void aReplacedBarLeavesOneLabelOnItsHost() {
        FrameLayout host = host();
        FrameLayout first = scrubber(host, 55_000, false);
        SeekBar firstBar = barIn(first);
        ReelSeekBar.progress(firstBar, 10_000);
        TextView firstLabel = ReelTimeLabel.labelOf(firstBar);
        ViewGroup overlay = (ViewGroup) firstLabel.getParent();

        first.setVisibility(View.GONE);
        FrameLayout second = scrubber(host, 30_000, false);
        SeekBar secondBar = barIn(second);
        ReelSeekBar.progress(secondBar, 5_000);
        TextView secondLabel = ReelTimeLabel.labelOf(secondBar);

        assertNotSame(firstLabel, secondLabel);
        assertSame(overlay, secondLabel.getParent());
        assertNull("the old label gave way", firstLabel.getParent());
        assertEquals("labels on the host", 1, overlay.getChildCount());
        assertEquals("0:05 / 0:30", secondLabel.getText().toString());

        first.setVisibility(View.VISIBLE);
        ReelSeekBar.progress(firstBar, 11_000);
        frame();
        assertNull(firstLabel.getParent());
        assertEquals(View.GONE, firstLabel.getVisibility());
        assertEquals("labels on the host", 1, overlay.getChildCount());
        assertSame(secondLabel, overlay.getChildAt(0));

        // Taken off for good, the old scrubber leaves the one label as it was.
        host.removeView(first);
        ShadowLooper.idleMainLooper();
        assertEquals(1, overlay.getChildCount());
        assertEquals(View.VISIBLE, secondLabel.getVisibility());
    }

    /**
     * With no room for the label anywhere near, a bar keeps the one label and the one listener it
     * got with its first tick, however many ticks come and whatever the collector does. Nothing
     * here holds the label but the bar: a label held only weakly was collected between ticks, and
     * each tick then gave the bar another one and another layout listener.
     */
    @Test
    public void aBarWithNoRoomKeepsOneLabelAndOneListener() {
        FrameLayout container = container();
        SeekBar bar = new SeekBar(context());
        bar.setMax(55_000);
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_PX, Gravity.BOTTOM));
        showBoxed(container, CONTAINER_PX);
        int layoutListeners = listeners(bar, "mOnLayoutChangeListeners");
        int windowListeners = listeners(bar, "mOnAttachStateChangeListeners");

        ReelSeekBar.progress(bar, 0);
        WeakReference<TextView> first = new WeakReference<>(ReelTimeLabel.labelOf(bar));
        assertNotNull(first.get());
        for (int tick = 1; tick <= 20; tick++) {
            collect();
            ReelSeekBar.progress(bar, tick * 1_000);
        }
        bar.requestLayout();
        layout(container, CONTAINER_PX);
        ShadowLooper.idleMainLooper();

        assertEquals("layout listeners", layoutListeners + 1, listeners(bar, "mOnLayoutChangeListeners"));
        assertEquals("window listeners", windowListeners + 1, listeners(bar, "mOnAttachStateChangeListeners"));
        TextView label = first.get();
        assertNotNull("the bar kept its first label", label);
        assertSame(label, ReelTimeLabel.labelOf(bar));
        assertNull("nowhere to go", label.getParent());
        assertEquals(View.GONE, label.getVisibility());
        assertNothingFailed();
    }

    /**
     * Litho can bind the scrubber container again while its reel plays. Bound again to the same
     * ordinary reel, the label stays up through it, with no frame where it's hidden. Bound to
     * another reel, it starts over: hidden at once, then shown with that reel's time.
     */
    @Test
    public void anOrdinaryReelBoundAgainKeepsItsLabelUp() {
        FrameLayout host = host();
        FrameLayout container = scrubber(host, 55_000, false);
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "1");
        SeekBar bar = barIn(container);
        bar.setProgress(10_000);
        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals(View.VISIBLE, label.getVisibility());

        ReelSeekBar.bind(container, 0);
        assertEquals("bound again, still up", View.VISIBLE, label.getVisibility());
        frame();
        assertEquals("and on the next frame", View.VISIBLE, label.getVisibility());
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:10 / 0:55", label.getText().toString());

        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "2");
        bar.setMax(30_000);
        bar.setProgress(0);
        ReelSeekBar.bind(container, 0);
        assertEquals("another reel, hidden at once", View.GONE, label.getVisibility());
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:00 / 0:30", label.getText().toString());
    }

    /**
     * #10: a bar Instagram moves on to another reel starts at 0 at once, where it used to keep the
     * last reel's spot until the new reel's player first moved it. Bound again to the same reel,
     * bound to an ad, or with no reel tag to tell by, it stays where it is.
     */
    @Test
    public void aBarBoundToAnotherReelStartsAtZero() {
        FrameLayout host = host();
        FrameLayout container = scrubber(host, 55_000, false);
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "1");
        SeekBar bar = barIn(container);
        bar.setProgress(10_000);
        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);

        ReelSeekBar.bind(container, 0);
        assertEquals("the same reel bound again", 10_000, bar.getProgress());

        bar.setProgress(20_000);
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "2");
        ReelSeekBar.bind(container, 0);
        assertEquals("another reel starts at 0", 0, bar.getProgress());
        ShadowLooper.idleMainLooper();
        assertEquals("0:00 / 0:55", label.getText().toString());

        bar.setProgress(3_000);
        ReelSeekBar.bind(container, 0);
        assertEquals("that reel bound again", 3_000, bar.getProgress());
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "3");
        ReelSeekBar.bind(container, 1);
        assertEquals("an ad's bar is Instagram's", 3_000, bar.getProgress());
        container.setTag(null);
        ReelSeekBar.bind(container, 0);
        assertEquals("no tag to tell by", 3_000, bar.getProgress());

        Settings.REEL_SEEK_BAR.save(false);
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "4");
        ReelSeekBar.bind(container, 0);
        assertEquals("off", 3_000, bar.getProgress());
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().toString().contains(ReelTimeLabel.COUNT_STARTED_OVER + " 1"));
        assertNothingFailed();
    }

    /**
     * Two bars in one container share the view their labels go in, which holds one label. When the
     * shown label moves from the container's children to the same container's overlay and back, it
     * keeps its hold, and the other bar's label still waits.
     */
    @Test
    public void twoBarsSharingAHostKeepOneLabelAcrossAMove() {
        FrameLayout container = container();
        SeekBar firstBar = new SeekBar(context());
        SeekBar secondBar = new SeekBar(context());
        for (SeekBar bar : new SeekBar[] {firstBar, secondBar}) {
            bar.setPadding(30, 0, 40, 0);
            bar.setMax(55_000);
            container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_PX, Gravity.BOTTOM));
        }
        show(container, HEIGHT);
        ReelSeekBar.progress(firstBar, 10_000);
        ReelSeekBar.progress(secondBar, 5_000);
        TextView firstLabel = ReelTimeLabel.labelOf(firstBar);
        TextView secondLabel = ReelTimeLabel.labelOf(secondBar);
        assertSame("a child of the container", container, firstLabel.getParent());
        assertEquals(View.VISIBLE, firstLabel.getVisibility());
        assertNull("the second bar's label waits", secondLabel.getParent());

        // Padding that leaves room above the bar on the container's overlay, but not inside it.
        int room = firstBar.getTop();
        int needed = firstLabel.getMeasuredHeight() + gap(context());
        assertTrue("room for the label above the bar", room - needed > 0);
        container.setPadding(0, room - needed + 1, 0, 0);
        layout(container);
        assertEquals("the bar stayed where it was", room, firstBar.getTop());
        ReelSeekBar.progress(firstBar, 11_000);
        assertNotSame("moved to the container's overlay", container, firstLabel.getParent());
        assertNotNull(firstLabel.getParent());
        assertEquals(View.VISIBLE, firstLabel.getVisibility());

        ReelSeekBar.progress(secondBar, 6_000);
        frame();
        assertNull("the second bar's label still waits", secondLabel.getParent());
        assertEquals(View.GONE, secondLabel.getVisibility());
        assertEquals("labels on the container's overlay", 1, ((ViewGroup) firstLabel.getParent()).getChildCount());

        container.setPadding(0, 0, 0, 0);
        layout(container);
        ReelSeekBar.progress(firstBar, 12_000);
        assertSame("back among the container's children", container, firstLabel.getParent());
        ReelSeekBar.progress(secondBar, 7_000);
        frame();
        assertNull("and the second bar's label waits", secondLabel.getParent());
        assertEquals("the container's children: two bars and one label", 3, container.getChildCount());
        assertNothingFailed();
    }

    /**
     * The container Instagram binds is the nearest bound view above the bar, for the ad check and
     * the reel's tag alike, so a bar a view further down still gets its label, an ad's still gets
     * none, and binding the container to an ad reaches the bar.
     */
    @Test
    public void aBarFurtherDownItsContainerStillCounts() {
        FrameLayout host = host();
        FrameLayout container = new FrameLayout(context());
        container.setTag(ReelTimeLabel.REEL_TAG_PREFIX + "7");
        ReelSeekBar.bind(container, 0);
        FrameLayout wrapper = new FrameLayout(context());
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 0, 40, 0);
        bar.setMax(55_000);
        wrapper.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_PX, Gravity.BOTTOM));
        container.addView(wrapper, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CONTAINER_PX, Gravity.BOTTOM));
        host.addView(container, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CONTAINER_PX, Gravity.BOTTOM));
        layout(host);

        ReelSeekBar.progress(bar, 10_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertNotNull(label);
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:10 / 0:55", label.getText().toString());
        assertEquals(ReelTimeLabel.REEL_TAG_PREFIX + "7", ReelTimeLabel.reelOf(bar));

        ReelSeekBar.bind(container, 1);
        assertEquals("bound to an ad, hidden at once", View.GONE, label.getVisibility());
        ShadowLooper.idleMainLooper();
        assertNull(label.getParent());

        // A nearer container bound to an ad decides for its own bar.
        ReelSeekBar.bind(container, 0);
        ReelSeekBar.bind(wrapper, 1);
        ReelSeekBar.progress(bar, 11_000);
        frame();
        assertNull("the nearer container is an ad's", label.getParent());
        assertNull(ReelTimeLabel.reelOf(bar));
    }

    /**
     * Only a bar whose container Instagram bound to an ordinary reel gets the label. The same kind
     * of bar on another screen gets none, nor does an ad's, and a container bound to an ordinary
     * reel and then to an ad loses its label at once.
     */
    @Test
    public void adsAndOtherScreensGetNoLabel() {
        FrameLayout elsewhere = new FrameLayout(context());
        SeekBar other = bar(elsewhere, 55_000);
        ReelSeekBar.progress(other, 10_000);
        ReelSeekBar.progress(other, 10_500);
        assertNull("another screen's bar", ReelTimeLabel.labelOf(other));
        assertEquals(1, elsewhere.getChildCount());

        FrameLayout host = host();
        FrameLayout container = scrubber(host, 30_000, true);
        SeekBar bar = barIn(container);
        ReelSeekBar.progress(bar, 10_000);
        ReelSeekBar.progress(bar, 10_500);
        assertNull("an ad's bar", ReelTimeLabel.labelOf(bar));

        ReelSeekBar.bind(container, 0);
        ReelSeekBar.progress(bar, 11_000);
        TextView label = ReelTimeLabel.labelOf(bar);
        assertEquals("bound to an ordinary reel", View.VISIBLE, label.getVisibility());
        ViewGroup overlay = (ViewGroup) label.getParent();

        ReelSeekBar.bind(container, 1);
        assertEquals("bound to an ad, hidden at once", View.GONE, label.getVisibility());
        ShadowLooper.idleMainLooper();
        assertNull(label.getParent());
        assertEquals(0, overlay.getChildCount());
        ReelSeekBar.progress(bar, 12_000);
        frame();
        assertNull("an ad's tick", label.getParent());
        assertEquals(View.GONE, label.getVisibility());

        ReelSeekBar.bind(null, 0);
        ReelSeekBar.bind("not a view", 1);
        assertTrue(HookStatus.missing(FamilyNames.REEL_SEEK_BAR).toString(), HookStatus.missing(FamilyNames.REEL_SEEK_BAR).isEmpty());
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static int gap(Context context) {
        return Math.round(4f * context.getResources().getDisplayMetrics().density);
    }

    /** Instagram's attached scrubber container, bound to an ordinary reel: a FrameLayout for the bar. */
    private static FrameLayout container() {
        FrameLayout container = new FrameLayout(context());
        ReelSeekBar.bind(container, 0);
        return container;
    }

    /** Puts [root] in the window, [height] tall, and lays it out. */
    private void show(ViewGroup root, int height) {
        window.addView(root, new FrameLayout.LayoutParams(WIDTH, height));
        layout(root, height);
    }

    /**
     * Shows [inner], [height] tall, in three views of its own height, more than the label looks up
     * through, so no view near the bar has room above it, whatever the window has above them.
     */
    private void showBoxed(ViewGroup inner, int height) {
        ViewGroup outer = inner;
        for (int i = 0; i < 3; i++) {
            FrameLayout box = new FrameLayout(context());
            box.addView(outer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            outer = box;
        }
        show(outer, height);
    }

    /** A bar of [max] at the bottom of [container], which goes in the window if it isn't in a view yet. */
    private SeekBar bar(FrameLayout container, int max) {
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 20, 40, 0);
        bar.setMax(max);
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        if (container.getParent() == null) show(container, HEIGHT);
        layout(container);
        return bar;
    }

    /** A Litho host in the window, taller than the scrubber it holds. */
    private FrameLayout host() {
        FrameLayout host = new FrameLayout(context());
        show(host, HEIGHT);
        return host;
    }

    /**
     * 449's scrubber at the bottom of [host]: a {@link #CONTAINER_PX} container bound to a reel,
     * an ordinary one unless [ad], with a {@link #BAR_PX} bar of [max] at its bottom.
     */
    private static FrameLayout scrubber(FrameLayout host, int max, boolean ad) {
        FrameLayout container = new FrameLayout(context());
        ReelSeekBar.bind(container, ad ? 1 : 0);
        SeekBar bar = new SeekBar(context());
        bar.setPadding(30, 0, 40, 0);
        bar.setMax(max);
        container.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, BAR_PX, Gravity.BOTTOM));
        host.addView(container, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CONTAINER_PX, Gravity.BOTTOM));
        layout(host);
        return container;
    }

    private static SeekBar barIn(ViewGroup container) {
        return (SeekBar) container.getChildAt(0);
    }

    /** What runs before a frame of the window is drawn. */
    private void frame() {
        window.getViewTreeObserver().dispatchOnPreDraw();
    }

    /** No seek bar hook reported a failure, which would have hidden the label and left a diagnostic. */
    private static void assertNothingFailed() {
        assertTrue(HookStatus.missing(FamilyNames.REEL_SEEK_BAR).toString(), HookStatus.missing(FamilyNames.REEL_SEEK_BAR).isEmpty());
    }

    /** Runs the collector until it has really run: an object nothing else holds is gone. */
    private static void collect() {
        WeakReference<Object> canary = new WeakReference<>(new Object());
        for (int i = 0; i < 50 && canary.get() != null; i++) {
            System.gc();
            System.runFinalization();
        }
        assertNull("the collector never ran", canary.get());
    }

    /** How many listeners of a kind ([field] of View's ListenerInfo) [view] has. */
    private static int listeners(View view, String field) {
        Object info = ReflectionHelpers.getField(view, "mListenerInfo");
        if (info == null) return 0;
        Collection<?> held = ReflectionHelpers.getField(info, field);
        return held == null ? 0 : held.size();
    }

    private static void layout(ViewGroup root) {
        layout(root, HEIGHT);
    }

    private static void layout(ViewGroup root, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, height);
    }

    /** One line, nothing cut off: the text's widest line and its full height fit inside the label's padding. */
    private static void assertFits(TextView label) {
        Layout layout = label.getLayout();
        assertNotNull("the label has no layout", layout);
        assertEquals(1, layout.getLineCount());
        assertEquals(0, layout.getEllipsisCount(0));
        float room = label.getWidth() - label.getTotalPaddingLeft() - label.getTotalPaddingRight();
        assertTrue("the text needs " + layout.getLineMax(0) + " px, the label has " + room, layout.getLineMax(0) <= room);
        int tall = label.getHeight() - label.getTotalPaddingTop() - label.getTotalPaddingBottom();
        assertTrue("the text needs " + layout.getHeight() + " px of height, the label has " + tall, layout.getHeight() <= tall);
    }
}
