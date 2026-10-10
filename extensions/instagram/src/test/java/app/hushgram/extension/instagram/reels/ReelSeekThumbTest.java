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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

    private void refresh() {
        ReelSeekBar.progress(bar, bar.getProgress());
        ShadowLooper.idleMainLooper();
    }
}
