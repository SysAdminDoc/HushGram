/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37})
public class ReelSeekThumbTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> activity;
    private FrameLayout root, container;
    private SeekBar bar;
    private Drawable original;

    @Before public void createBar() {
        Settings.REEL_SEEK_BAR.save(true);
        Settings.REEL_SEEK_THUMB.save(false);
        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        root = new FrameLayout(activity.get());
        container = new FrameLayout(activity.get());
        container.setTag("clips_scrubber_test");
        bar = new SeekBar(activity.get());
        GradientDrawable nativeThumb = new GradientDrawable();
        nativeThumb.setColor(Color.MAGENTA);
        nativeThumb.setSize(6, 6);
        original = nativeThumb;
        bar.setThumb(original);
        bar.setThumbOffset(3);
        bar.setPadding(0, 0, 0, 0);
        bar.setMax(100);
        bar.setProgress(25);
        container.addView(bar, new FrameLayout.LayoutParams(400, 28));
        root.addView(container, new FrameLayout.LayoutParams(400, 60));
        activity.get().setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 400);
        ReelSeekBar.bind(container, 0);
        refresh();
    }

    @After public void restore() {
        activity.close();
        Settings.REEL_SEEK_THUMB.resetToDefault();
        Settings.BIG_REEL_SEEK_BAR.resetToDefault();
        Settings.DM_REEL_SEEK_BAR.resetToDefault();
        Settings.REEL_SEEK_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test public void defaultOffLeavesTheNativeDrawableAndOffset() {
        assertFalse(Settings.REEL_SEEK_THUMB.get());
        assertSame(original, bar.getThumb());
        assertEquals(3, bar.getThumbOffset());
    }

    @Test public void enablingAddsOneWhiteCircleInsideTheExistingBar() {
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        Drawable thumb = bar.getThumb();
        assertNotSame(original, thumb);
        assertTrue(thumb.getIntrinsicWidth() > 6);
        assertEquals(thumb.getIntrinsicWidth(), thumb.getIntrinsicHeight());
        assertTrue(thumb.getIntrinsicHeight() <= bar.getHeight());
        Bitmap pixels = Bitmap.createBitmap(thumb.getIntrinsicWidth(), thumb.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        thumb.setBounds(0, 0, pixels.getWidth(), pixels.getHeight());
        thumb.draw(new Canvas(pixels));
        assertEquals(Color.WHITE, pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2));
        assertEquals(0, Color.alpha(pixels.getPixel(0, 0)));
        refresh();
        assertSame(thumb, bar.getThumb());
        assertEquals(1, container.getChildCount());
    }

    @Test public void thumbCanBeEnabledWithoutThePersistentBarAndTimeLabel() {
        Settings.REEL_SEEK_BAR.save(false);
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        assertNotSame(original, bar.getThumb());
        assertEquals(25, bar.getProgress());
    }

    @Test public void compactNativeTrackPaddingDoesNotShrinkOrClipTheHandle() {
        bar.setPadding(0, 20, 0, 0);
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        Drawable thumb = bar.getThumb();
        int expected = Math.min(Math.round(10 * bar.getResources().getDisplayMetrics().density), bar.getHeight());
        assertEquals(expected, thumb.getIntrinsicHeight());
        assertTrue(thumb.getBounds().top + bar.getPaddingTop() >= 0);
        assertTrue(thumb.getBounds().bottom + bar.getPaddingTop() <= bar.getHeight());
        bar.setProgress(75);
        refresh();
        assertSame(thumb, bar.getThumb());
        assertTrue(thumb.getBounds().bottom + bar.getPaddingTop() <= bar.getHeight());
        assertEquals(20, bar.getPaddingTop());
        assertEquals(75, bar.getProgress());
    }

    @Test public void disablingAndPauseRestoreTheExactNativeThumbOffsetAndTint() {
        ColorStateList tint = ColorStateList.valueOf(Color.BLUE);
        bar.setThumbTintList(tint);
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        assertNotSame(original, bar.getThumb());
        assertSame(tint, bar.getThumbTintList());
        Settings.REEL_SEEK_THUMB.save(false);
        refresh();
        assertSame(original, bar.getThumb());
        assertEquals(3, bar.getThumbOffset());
        assertSame(tint, bar.getThumbTintList());
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        refresh();
        assertSame(original, bar.getThumb());
        assertEquals(3, bar.getThumbOffset());
    }

    @Test public void rebindingToAnAdRestoresBeforeAnotherFrame() {
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        assertNotSame(original, bar.getThumb());
        ReelSeekBar.bind(container, 1);
        assertSame(original, bar.getThumb());
        refresh();
        assertSame(original, bar.getThumb());
        ReelSeekBar.bind(container, 0);
        refresh();
        assertNotSame(original, bar.getThumb());
    }

    @Test public void removingTheBarRestoresItsNativeDrawable() {
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        assertNotSame(original, bar.getThumb());
        container.removeView(bar);
        ShadowLooper.idleMainLooper();
        assertSame(original, bar.getThumb());
    }

    @Test public void aNullNativeThumbGetsPositionedBeforePlaybackMovesAndRestoresToNull() {
        bar.setThumb(null);
        bar.setThumbOffset(0);
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        assertNotNull(bar.getThumb());
        assertTrue("The thumb must draw even while playback is paused", bar.getThumb().getBounds().height() > 0);
        assertEquals(25, bar.getProgress());
        Settings.REEL_SEEK_THUMB.save(false);
        refresh();
        assertNull(bar.getThumb());
        assertEquals(0, bar.getThumbOffset());
    }

    @Test public void beforeSettingsAreReadyTheOriginalThumbStaysUntouched() {
        Settings.REEL_SEEK_THUMB.save(true);
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            refresh();
            assertSame(original, bar.getThumb());
        });
        refresh();
        assertNotSame(original, bar.getThumb());
    }

    @Test public void laterNativeDrawableAndOffsetAreNeverReplacedByAnOldSnapshot() {
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        assertNotSame(original, bar.getThumb());
        GradientDrawable replacement = new GradientDrawable();
        replacement.setSize(8, 8);
        bar.setThumb(replacement);
        bar.setThumbOffset(2);
        Settings.REEL_SEEK_THUMB.save(false);
        refresh();
        assertSame(replacement, bar.getThumb());
        assertEquals(2, bar.getThumbOffset());
    }

    @Test public void accessibilityStillSeeksTheSameNativeBarInEitherDirection() {
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        assertNotSame(original, bar.getThumb());
        int[] changed = {0};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean user) {
                assertSame(bar, view);
                assertTrue(user);
                changed[0]++;
            }
            @Override public void onStartTrackingTouch(SeekBar view) { }
            @Override public void onStopTrackingTouch(SeekBar view) { }
        });
        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            bar.setLayoutDirection(direction);
            Bundle request = new Bundle();
            request.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, direction == View.LAYOUT_DIRECTION_LTR ? 40 : 70);
            assertTrue(bar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), request));
            assertEquals(direction == View.LAYOUT_DIRECTION_LTR ? 40 : 70, bar.getProgress());
        }
        assertEquals(2, changed[0]);
    }

    @Test public void defaultOffLeavesTheTrackAndBackgroundAlone() {
        Drawable track = bar.getProgressDrawable();
        Drawable background = bar.getBackground();
        assertFalse(Settings.BIG_REEL_SEEK_BAR.get());
        refresh();
        assertSame(track, bar.getProgressDrawable());
        assertSame(background, bar.getBackground());
        assertTrue(container.getClipChildren());
    }

    @Test public void theBiggerBarThickensTheTrackAddsAThumbAndAFadeOnTheSameBar() {
        Settings.REEL_SEEK_BAR.save(false);
        Drawable track = bar.getProgressDrawable();
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        assertNotSame(track, bar.getProgressDrawable());
        assertNotNull(bar.getProgressDrawable().getBounds());
        assertTrue(bar.getBackground() instanceof android.graphics.drawable.LayerDrawable);
        assertFalse(container.getClipChildren());
        int expected = Math.min(Math.round(ReelTimeLabel.BIG_THUMB_DP * bar.getResources().getDisplayMetrics().density),
                bar.getHeight());
        assertEquals(expected, bar.getThumb().getIntrinsicWidth());
        assertEquals(25, bar.getProgress());
        assertSame(bar, container.getChildAt(0));
        // The fade and the track draw: white where the progress is, translucent after it.
        Bitmap pixels = Bitmap.createBitmap(400, 28, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(pixels));
        assertTrue(Color.alpha(pixels.getPixel(20, 27)) > 0 || Color.alpha(pixels.getPixel(20, 14)) > 0);
        // Settled, a frame changes none of it.
        Drawable styled = bar.getProgressDrawable();
        Drawable backdrop = bar.getBackground();
        refresh();
        assertSame(styled, bar.getProgressDrawable());
        assertSame(backdrop, bar.getBackground());
    }

    /**
     * #10: Instagram moves the bar about ten times a second, so the restyled progress eases to each new
     * level over the time since the one before, rather than jumping. Going back, or a level after a
     * pause longer than the easing allows, is drawn at once.
     */
    @Test public void theRestyledProgressEasesBetweenInstagramsSteps() {
        ReelTimeLabel.Pill pill = new ReelTimeLabel.Pill(Color.WHITE, 8f, true);
        pill.setBounds(0, 0, 1000, 20);
        pill.setLevel(1000);
        assertEquals("the first level is drawn at once", 1000f, pill.shownLevel(SystemClock.uptimeMillis()), 0.01f);

        ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS);
        long step = SystemClock.uptimeMillis();
        pill.setLevel(2000);
        assertEquals("starts where it was", 1000f, pill.shownLevel(step), 0.01f);
        assertEquals("halfway after half the step", 1500f, pill.shownLevel(step + 50), 0.01f);
        assertEquals("there as the next step comes", 2000f, pill.shownLevel(step + 100), 0.01f);

        ShadowSystemClock.advanceBy(50, TimeUnit.MILLISECONDS);
        long early = SystemClock.uptimeMillis();
        pill.setLevel(2500);
        assertEquals("a step that comes early starts from where the line is", 1500f, pill.shownLevel(early), 0.01f);
        assertEquals(2500f, pill.shownLevel(early + 50), 0.01f);

        ShadowSystemClock.advanceBy(10, TimeUnit.MILLISECONDS);
        pill.setLevel(0);
        assertEquals("a new reel starts at 0 at once", 0f, pill.shownLevel(SystemClock.uptimeMillis()), 0.01f);

        ShadowSystemClock.advanceBy(ReelTimeLabel.Pill.EASE_MAX_MS + 1, TimeUnit.MILLISECONDS);
        pill.setLevel(400);
        assertEquals("after a pause the level is drawn at once", 400f, pill.shownLevel(SystemClock.uptimeMillis()), 0.01f);

        // While it eases, a frame asks for the next one.
        AtomicInteger asked = new AtomicInteger();
        pill.setCallback(new Drawable.Callback() {
            @Override public void invalidateDrawable(Drawable who) { asked.incrementAndGet(); }
            @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { }
            @Override public void unscheduleDrawable(Drawable who, Runnable what) { }
        });
        ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS);
        pill.setLevel(900);
        Bitmap pixels = Bitmap.createBitmap(1000, 20, Bitmap.Config.ARGB_8888);
        asked.set(0);
        pill.draw(new Canvas(pixels));
        assertEquals("easing, the next frame is asked for", 1, asked.get());
        ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS);
        asked.set(0);
        pill.draw(new Canvas(pixels));
        assertEquals("arrived, no more frames", 0, asked.get());
    }

    /**
     * #10, as logged on the S22: Instagram moves the bar with an animated setProgress, and Android sets the
     * new level, then starts its own short animation over from the old one, so the level went new, old,
     * new within a frame. The line goes by the bar's progress, so that doesn't restart its ease, and the
     * handle stays on the line's end wherever Android's animation has it.
     */
    @Test public void androidsOwnProgressAnimationDoesNotRestartTheEase() {
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        ReelTimeLabel.Thumb thumb = (ReelTimeLabel.Thumb) bar.getThumb();
        ReelTimeLabel.Pill line = (ReelTimeLabel.Pill) ((LayerDrawable) bar.getProgressDrawable())
                .findDrawableByLayerId(android.R.id.progress);
        int room = bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight()
                - thumb.getIntrinsicWidth() + 2 * bar.getThumbOffset();
        ShadowSystemClock.advanceBy(ReelTimeLabel.Pill.EASE_MAX_MS + 1, TimeUnit.MILLISECONDS);
        bar.setProgress(30);
        ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS);
        long step = SystemClock.uptimeMillis();
        bar.setProgress(40);
        line.setLevel(3000);
        ShadowSystemClock.advanceBy(10, TimeUnit.MILLISECONDS);
        line.setLevel(4000);
        long now = SystemClock.uptimeMillis();
        float shown = line.shownLevel(now);
        assertTrue("still on its way a frame later, not there at once: " + shown, shown > 3000f && shown < 3300f);
        assertEquals("the handle on the line's end", (shown - 4000f) / 10000f * room, thumb.shift(), 0.5f);
        line.setLevel(3000);
        assertEquals("the handle on the line's end while Android has it back", (shown - 3000f) / 10000f * room,
                thumb.shift(), 0.5f);
        line.setLevel(4000);
        assertEquals("the animation doesn't restart the ease", shown, line.shownLevel(now), 0.01f);
        ShadowSystemClock.advanceBy(ReelTimeLabel.Pill.EASE_MAX_MS, TimeUnit.MILLISECONDS);
        assertEquals(4000f, line.shownLevel(SystemClock.uptimeMillis()), 0.01f);
        assertTrue("eased over the step's time", SystemClock.uptimeMillis() - step > 100);

        // A real step back (a new reel) is drawn at once, and Android's animation from the old spot down
        // to it doesn't pull the line back up.
        bar.setProgress(0);
        line.setLevel(4000);
        line.setLevel(1500);
        assertEquals(0f, line.shownLevel(SystemClock.uptimeMillis()), 0.01f);
    }

    /**
     * #10: Android puts the handle at Instagram's stepped level, where it covered the eased line's end
     * and still jumped. It draws back by as much as the line is behind, and where Android put it once
     * the line arrives.
     */
    @Test public void theHandleSlidesAlongWithTheEasedLine() {
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        assertTrue(bar.getThumb() instanceof ReelTimeLabel.Thumb);
        ReelTimeLabel.Thumb thumb = (ReelTimeLabel.Thumb) bar.getThumb();
        ReelTimeLabel.Pill line = (ReelTimeLabel.Pill) ((LayerDrawable) bar.getProgressDrawable())
                .findDrawableByLayerId(android.R.id.progress);
        int room = bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight()
                - thumb.getIntrinsicWidth() + 2 * bar.getThumbOffset();
        ShadowSystemClock.advanceBy(ReelTimeLabel.Pill.EASE_MAX_MS + 1, TimeUnit.MILLISECONDS);
        bar.setProgress(30);
        assertEquals("after a pause, where Android put it", 0f, thumb.shift(), 0.01f);

        ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS);
        bar.setProgress(40);
        float start = line.behind(SystemClock.uptimeMillis()) * room;
        assertEquals("starts about a tenth of the bar back", -room / 10f, start, 2f);
        assertEquals("on the line's end", start, thumb.shift(), 0.5f);
        Bitmap pixels = Bitmap.createBitmap(400, 28, Bitmap.Config.ARGB_8888);
        thumb.draw(new Canvas(pixels));
        Rect placed = thumb.getBounds();
        int y = Math.max(0, Math.min(27, placed.centerY()));
        assertEquals("drawn on the line's end", Color.WHITE, pixels.getPixel(placed.centerX() + Math.round(start), y));
        assertEquals("not where Android put it", 0, Color.alpha(pixels.getPixel(placed.centerX(), y)));

        ShadowSystemClock.advanceBy(50, TimeUnit.MILLISECONDS);
        float later = thumb.shift();
        assertEquals("still on the line's end", line.behind(SystemClock.uptimeMillis()) * room, later, 0.5f);
        assertTrue("closer to Instagram's level", later > start + 1f && later < -1f);
        ShadowSystemClock.advanceBy(ReelTimeLabel.Pill.EASE_MAX_MS, TimeUnit.MILLISECONDS);
        assertEquals("arrived, where Android put it", 0f, thumb.shift(), 0.01f);
        pixels.eraseColor(Color.TRANSPARENT);
        thumb.draw(new Canvas(pixels));
        assertEquals(Color.WHITE, pixels.getPixel(placed.centerX(), y));

        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bar.setProgress(45);
        ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS);
        bar.setProgress(55);
        boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float back = line.behind(SystemClock.uptimeMillis()) * room;
        assertTrue(back < -1f);
        assertEquals("right to left, it draws back toward the right", rtl ? -back : back, thumb.shift(), 0.5f);

        Settings.BIG_REEL_SEEK_BAR.save(false);
        Settings.REEL_SEEK_THUMB.save(true);
        refresh();
        ShadowSystemClock.advanceBy(100, TimeUnit.MILLISECONDS);
        bar.setProgress(60);
        assertEquals("Instagram's own track has no easing to follow", 0f,
                ((ReelTimeLabel.Thumb) bar.getThumb()).shift(), 0.01f);
    }

    /**
     * #10: Instagram keeps its reel track a few pixels high at the bottom of the bar, and marks how much of
     * the reel has loaded as the bar's secondary progress. The bigger bar is as thick as it says, through
     * the handle's middle, and white only as far as the reel has played, however much has loaded.
     */
    @Test @Config(qualifiers = "xxhdpi")
    public void theBiggerBarIsThickerThanInstagramsTrackAndWhiteOnlyAsFarAsPlayed() {
        bar.setPadding(0, 21, 0, 0);
        bar.setSecondaryProgress(80);
        Settings.REEL_SEEK_BAR.save(false);
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        bar.setSecondaryProgress(100);
        LayerDrawable track = (LayerDrawable) bar.getProgressDrawable();
        assertEquals("the played share, not the loaded one", 2500, track.findDrawableByLayerId(android.R.id.progress).getLevel());

        Bitmap pixels = Bitmap.createBitmap(400, 28, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(pixels));
        int thickness = Math.round(ReelTimeLabel.BIG_TRACK_DP * bar.getResources().getDisplayMetrics().density);
        assertTrue("thicker than Instagram's 7 px", thickness > 7);
        int middle = bar.getPaddingTop() + bar.getThumb().getBounds().centerY();
        for (int y = middle - thickness / 2 + 1; y < middle + thickness / 2; y++) {
            assertEquals("played, row " + y, Color.WHITE, pixels.getPixel(10, y));
        }
        int unplayed = pixels.getPixel(350, middle);
        assertNotEquals("loaded but not played", Color.WHITE, unplayed);
        assertTrue(Color.alpha(unplayed) > 0);
    }

    @Test public void theBiggerBarAlsoShowsTheTimeLabelAndKeepsShortReelsInstagramsBar() {
        Settings.REEL_SEEK_BAR.save(false);
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        assertNotNull(ReelTimeLabel.labelOf(bar));
        assertTrue(ReelSeekBar.switchedOn());
        assertEquals(1L, ReelSeekBar.minSeconds(7L));
        assertFalse(ReelSeekBar.lazy(1));
    }

    @Test public void turningTheBiggerBarOffOrPausingGivesInstagramsDrawablesAndClippingBack() {
        Drawable track = bar.getProgressDrawable();
        Drawable background = bar.getBackground();
        container.setClipChildren(false);
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        assertNotSame(track, bar.getProgressDrawable());
        Settings.BIG_REEL_SEEK_BAR.save(false);
        refresh();
        assertSame(track, bar.getProgressDrawable());
        assertSame(background, bar.getBackground());
        assertFalse("A view that already drew past its edges keeps doing so", container.getClipChildren());
        container.setClipChildren(true);
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        refresh();
        assertSame(track, bar.getProgressDrawable());
        assertSame(background, bar.getBackground());
        assertTrue(container.getClipChildren());
        assertSame(original, bar.getThumb());
    }

    @Test public void theBiggerBarComesOffAnAdAtOnceAndWhenTheBarLeaves() {
        Drawable track = bar.getProgressDrawable();
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        ReelSeekBar.bind(container, 1);
        assertSame(track, bar.getProgressDrawable());
        ReelSeekBar.bind(container, 0);
        refresh();
        assertNotSame(track, bar.getProgressDrawable());
        container.removeView(bar);
        ShadowLooper.idleMainLooper();
        assertSame(track, bar.getProgressDrawable());
        assertTrue(container.getClipChildren());
    }

    /**
     * #10: the bar like DM videos draws Instagram's chat video track, thin and gray with the played part
     * white, through the middle of a plain white handle 16dp across, and puts no fade behind it.
     */
    @Test @Config(qualifiers = "xxhdpi")
    public void theDmLookIsAThinGrayTrackAndAPlainWhiteHandleWithNoFade() {
        bar.setPadding(0, 21, 0, 0);
        bar.setSecondaryProgress(100);
        Drawable track = bar.getProgressDrawable();
        Drawable background = bar.getBackground();
        Settings.REEL_SEEK_BAR.save(false);
        Settings.DM_REEL_SEEK_BAR.save(true);
        refresh();

        assertNotSame(track, bar.getProgressDrawable());
        assertSame("no fade", background, bar.getBackground());
        assertTrue("nothing needs to draw past its edges", container.getClipChildren());
        float density = bar.getResources().getDisplayMetrics().density;
        Drawable thumb = bar.getThumb();
        assertEquals(Math.min(Math.round(ReelTimeLabel.DM_THUMB_DP * density), bar.getHeight()), thumb.getIntrinsicWidth());
        Bitmap circle = Bitmap.createBitmap(thumb.getIntrinsicWidth(), thumb.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Drawable copy = thumb.getConstantState().newDrawable();
        copy.setBounds(0, 0, circle.getWidth(), circle.getHeight());
        copy.draw(new Canvas(circle));
        int edge = circle.getPixel(circle.getWidth() / 2, 1);
        assertTrue("no dark outline: " + Integer.toHexString(edge), Color.alpha(edge) == 0 || Color.red(edge) > 200);

        Bitmap pixels = Bitmap.createBitmap(400, 28, Bitmap.Config.ARGB_8888);
        bar.draw(new Canvas(pixels));
        int middle = bar.getPaddingTop() + thumb.getBounds().centerY();
        assertEquals("played", Color.WHITE, pixels.getPixel(10, middle));
        assertEquals("not played yet, however much has loaded", ReelTimeLabel.DM_TRACK_COLOR, pixels.getPixel(350, middle));
        int thickness = Math.round(ReelTimeLabel.DM_TRACK_DP * density);
        assertEquals("thinner than the bigger bar's", 0, Color.alpha(pixels.getPixel(350, middle + thickness)));
        assertTrue(ReelSeekBar.switchedOn());
        assertEquals("short reels keep the bar", 1L, ReelSeekBar.minSeconds(7L));
    }

    /** With Bigger seek bar on too the DM look wins, and moving between the two keeps Instagram's own for Off. */
    @Test public void theDmLookWinsOverTheBiggerBarAndBothGiveInstagramsBack() {
        Drawable track = bar.getProgressDrawable();
        Drawable background = bar.getBackground();
        Settings.BIG_REEL_SEEK_BAR.save(true);
        refresh();
        assertNotSame("the fade is up", background, bar.getBackground());
        assertFalse(container.getClipChildren());
        Drawable bigger = bar.getProgressDrawable();

        Settings.DM_REEL_SEEK_BAR.save(true);
        refresh();
        assertNotSame(bigger, bar.getProgressDrawable());
        assertNotSame(track, bar.getProgressDrawable());
        assertSame("the fade comes off", background, bar.getBackground());
        assertTrue(container.getClipChildren());
        float density = bar.getResources().getDisplayMetrics().density;
        assertEquals(Math.min(Math.round(ReelTimeLabel.DM_THUMB_DP * density), bar.getHeight()), bar.getThumb().getIntrinsicWidth());

        Settings.DM_REEL_SEEK_BAR.save(false);
        refresh();
        assertNotSame("back to the bigger bar", background, bar.getBackground());
        Settings.BIG_REEL_SEEK_BAR.save(false);
        refresh();
        assertSame(track, bar.getProgressDrawable());
        assertSame(background, bar.getBackground());
        assertTrue(container.getClipChildren());

        Settings.DM_REEL_SEEK_BAR.save(true);
        refresh();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        refresh();
        assertSame("paused, Instagram's track", track, bar.getProgressDrawable());
        assertSame(original, bar.getThumb());
    }

    private void refresh() {
        ReelSeekBar.progress(bar, bar.getProgress());
        ShadowLooper.idleMainLooper();
    }
}
