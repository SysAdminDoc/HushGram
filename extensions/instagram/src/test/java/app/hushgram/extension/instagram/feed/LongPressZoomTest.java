/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the feed photo long press hooks do: off, paused, not ready, a video, a finger that moved and a
 * frame with nothing to show are Instagram's own long press; a held photo opens a zoom around the
 * finger that follows it and closes on a lift, a cancel or a second finger.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LongPressZoomTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final ToIntFunction<Object> PHOTO = media -> LongPressZoom.PHOTO;
    private static final ToIntFunction<Object> CAROUSEL = media -> LongPressZoom.CAROUSEL;
    private static final ToIntFunction<Object> VIDEO = media -> 2;

    private ActivityController<Activity> controller;
    private Feed feed;
    private FrameLayout frame;
    private ImageView image;
    private Bitmap bitmap;
    private final Object media = new Object();

    /** Where the photo sits on the screen. Every finger below is placed from here. */
    private float x0;
    private float y0;

    /** The list a feed post sits in, which notes whether a child asked it to keep its touches. */
    static final class Feed extends FrameLayout {
        boolean held;

        Feed(Context context) {
            super(context);
        }

        @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
            held = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    @Before
    public void clean() {
        Settings.LONG_PRESS_TO_ZOOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        LongPressZoom.resetForTests();
        controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        feed = new Feed(activity);
        frame = new FrameLayout(activity);
        image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        image.setImageBitmap(bitmap);
        frame.addView(image, new FrameLayout.LayoutParams(200, 200));
        feed.addView(frame, new FrameLayout.LayoutParams(200, 200));
        activity.setContentView(feed);
        idle();
        assertEquals("the photo is laid out", 200, image.getWidth());
        int[] spot = new int[2];
        image.getLocationOnScreen(spot);
        x0 = spot[0];
        y0 = spot[1];
    }

    @After
    public void restore() {
        LongPressZoom.resetForTests();
        if (controller != null) controller.close();
        Settings.LONG_PRESS_TO_ZOOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** A touch ([x], [y]) from the photo's corner. */
    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0L, 0L, action, x0 + x, y0 + y, 0);
        try {
            LongPressZoom.touch(event);
        } finally {
            event.recycle();
        }
    }

    private int holdAt(float x, float y, ToIntFunction<Object> type) {
        touch(MotionEvent.ACTION_DOWN, x, y);
        return LongPressZoom.press(frame, media, type);
    }

    private static String report() {
        List<String> lines = HookStatus.report();
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(line).append('\n');
        return all.toString();
    }

    /** Off to start: a long press is Instagram's, nothing opens, and only the call itself is counted. */
    @Test
    public void itStartsOffAndLeavesTheLongPressToInstagram() {
        assertFalse(Settings.LONG_PRESS_TO_ZOOM.get());
        assertEquals(0, holdAt(50f, 60f, PHOTO));
        assertFalse(LongPressZoom.isShown());
        assertFalse(feed.held);
        String report = report();
        assertTrue(report, report.contains(FamilyNames.LONG_PRESS_ZOOM + ": invoked 1"));
        assertFalse(report, report.contains(LongPressZoom.ZOOMED));
    }

    /**
     * On, a held photo opens over the window: the same bitmap, twice as big around the finger, and the
     * feed keeps its touches. Sliding moves the zoom with the finger and lifting closes it.
     */
    @Test
    public void aHeldPhotoZoomsAroundTheFingerUntilItLifts() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals(1, holdAt(50f, 60f, PHOTO));
        assertTrue(LongPressZoom.isShown());
        LongPressZoom.ZoomView view = (LongPressZoom.ZoomView) LongPressZoom.shownView();
        assertNotNull(view);
        View root = frame.getRootView();
        assertSame("the zoom sits over the whole window", root, view.getParent());
        assertTrue("the feed doesn't scroll under it", feed.held);
        assertSame("the picture is the bitmap the feed shows, nothing fetched", bitmap, ((BitmapDrawable) view.picture()).getBitmap());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.getImportantForAccessibility());
        assertTrue(report(), report().contains(LongPressZoom.ZOOMED + " 1"));

        // Twice as big around the finger, 50 and 60 into the photo: its corner moves 50 and 60 further out.
        int[] at = new int[2];
        image.getLocationInWindow(at);
        Rect picture = view.picture().getBounds();
        assertPoint("the picture's corner, pushed away from the finger", at[0] - 50f, at[1] - 60f, view.mapped(0f, 0f));
        assertPoint("the far corner", at[0] + 350f, at[1] + 340f, view.mapped(picture.right, picture.bottom));

        touch(MotionEvent.ACTION_MOVE, 90f, 120f);
        assertPoint("the zoom follows the finger", at[0] - 90f, at[1] - 120f, view.mapped(0f, 0f));
        view.draw(new Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)));
        assertTrue("drawing it is fine", LongPressZoom.isShown());

        touch(MotionEvent.ACTION_UP, 90f, 120f);
        assertFalse(LongPressZoom.isShown());
        assertNull("the zoom is gone from the window", view.getParent());
    }

    /** A carousel is photos too, and the page under the finger is the one that opens, not the one beside it. */
    @Test
    public void aCarouselZoomsThePagePressed() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        Bitmap next = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        ImageView page = new ImageView(frame.getContext());
        page.setImageBitmap(next);
        FrameLayout.LayoutParams beside = new FrameLayout.LayoutParams(200, 200);
        beside.leftMargin = 200;
        frame.addView(page, beside);
        frame.setLayoutParams(new FrameLayout.LayoutParams(400, 200));
        idle();
        assertEquals("the next page is laid out", 200, page.getWidth());

        assertEquals(1, holdAt(250f, 60f, CAROUSEL));
        assertSame("the page pressed", next, ((BitmapDrawable) ((LongPressZoom.ZoomView) LongPressZoom.shownView()).picture()).getBitmap());
        touch(MotionEvent.ACTION_UP, 250f, 60f);

        assertEquals(1, holdAt(50f, 60f, CAROUSEL));
        assertSame("the control: the first page", bitmap, ((BitmapDrawable) ((LongPressZoom.ZoomView) LongPressZoom.shownView()).picture()).getBitmap());
    }

    /** A finger that moved further than a tap may is a scroll or a drag, and Instagram decides what that is. */
    @Test
    public void aFingerThatMovedPastATapIsNotALongPress() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        float slop = ViewConfiguration.get(frame.getContext()).getScaledTouchSlop();
        touch(MotionEvent.ACTION_DOWN, 50f, 60f);
        touch(MotionEvent.ACTION_MOVE, 50f + slop * 2, 60f);
        touch(MotionEvent.ACTION_MOVE, 50f, 60f);
        assertEquals("it went back, but it went", 0, LongPressZoom.press(frame, media, PHOTO));
        assertFalse(LongPressZoom.isShown());
        assertTrue(report(), report().contains(LongPressZoom.WANDERED + " 1"));

        touch(MotionEvent.ACTION_DOWN, 50f, 60f);
        touch(MotionEvent.ACTION_MOVE, 50f + slop / 2, 60f);
        assertEquals("the control: a little wobble is still a press", 1, LongPressZoom.press(frame, media, PHOTO));
    }

    /** A video keeps Instagram's own long press, and so does a frame playing one. */
    @Test
    public void aVideoKeepsInstagramsLongPress() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals(0, holdAt(50f, 60f, VIDEO));
        assertTrue(report(), report().contains(LongPressZoom.NOT_A_PHOTO + " 1"));

        frame.addView(new TextureView(frame.getContext()), new FrameLayout.LayoutParams(200, 200));
        idle();
        assertEquals("a playing video in the frame", 0, holdAt(50f, 60f, PHOTO));
        assertFalse(LongPressZoom.isShown());
        assertTrue(report(), report().contains(LongPressZoom.NO_PICTURE + " 1"));
    }

    /** A frame showing only something small, an icon over nothing, has no photo to zoom. */
    @Test
    public void aFrameWithoutAPictureKeepsInstagramsLongPress() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        image.setLayoutParams(new FrameLayout.LayoutParams(20, 20));
        idle();
        assertEquals(0, holdAt(50f, 60f, PHOTO));
        assertFalse(LongPressZoom.isShown());
        assertTrue(report(), report().contains(LongPressZoom.NO_PICTURE + " 1"));

        image.setLayoutParams(new FrameLayout.LayoutParams(200, 200));
        image.setImageDrawable(null);
        idle();
        assertEquals("the control: no drawable, the view's own snapshot", 1, holdAt(50f, 60f, PHOTO));
        assertTrue(LongPressZoom.isShown());
    }

    /** A second finger is a pinch, Instagram's own zoom: it closes the zoom, and so does a cancel. */
    @Test
    public void aSecondFingerOrACancelClosesTheZoom() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals(1, holdAt(50f, 60f, PHOTO));
        touch(MotionEvent.ACTION_POINTER_DOWN, 70f, 60f);
        assertFalse(LongPressZoom.isShown());
        assertEquals("a pinch under way is never a long press", 0, LongPressZoom.press(frame, media, PHOTO));

        assertEquals(1, holdAt(50f, 60f, PHOTO));
        touch(MotionEvent.ACTION_CANCEL, 50f, 60f);
        assertFalse(LongPressZoom.isShown());
        assertEquals("nothing is held after a cancel", 0, LongPressZoom.press(frame, media, PHOTO));
    }

    /** Paused or before the settings are ready, a saved switch leaves the long press to Instagram. */
    @Test
    public void offPausedOrNotReadyKeepsInstagramsLongPress() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, holdAt(50f, 60f, PHOTO));
        assertTrue("the saved choice is kept", Settings.LONG_PRESS_TO_ZOOM.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        touch(MotionEvent.ACTION_DOWN, 50f, 60f);
        SettingsContextRule.withoutContext(() -> assertEquals("settings not ready", 0, LongPressZoom.press(frame, media, PHOTO)));
        assertFalse(LongPressZoom.isShown());
        assertFalse(report(), report().contains(LongPressZoom.ZOOMED));
        assertEquals("the control: on, the same press zooms", 1, LongPressZoom.press(frame, media, PHOTO));
    }

    /** With no finger seen going down, or no frame or post, it's Instagram's long press. */
    @Test
    public void aPressWithoutAFingerOrAPostIsInstagrams() {
        touch(MotionEvent.ACTION_DOWN, 50f, 60f);
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals("the finger went down while it was off", 0, LongPressZoom.press(frame, media, PHOTO));
        touch(MotionEvent.ACTION_DOWN, 50f, 60f);
        assertEquals(0, LongPressZoom.press(null, media, PHOTO));
        assertEquals(0, LongPressZoom.press(frame, null, PHOTO));
        assertFalse(LongPressZoom.isShown());
        assertEquals("as built, the post's type is unknown", 0, LongPressZoom.mediaType(media));
        assertEquals("so the built hook says no", 0, LongPressZoom.press(frame, media));
    }

    /** Anything thrown leaves Instagram's long press and says so. */
    @Test
    public void aCheckThatThrowsKeepsInstagramsLongPressAndSaysSo() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals(0, holdAt(50f, 60f, media -> {
            throw new IllegalStateException("no type");
        }));
        assertFalse(LongPressZoom.isShown());
        assertTrue(report(), report().contains("'" + LongPressZoom.PRESS + "' hook"));
    }

    /** When Instagram lets go of the bitmap under an open zoom, the zoom draws its backdrop and closes. */
    @Test
    public void aBitmapLetGoClosesTheZoom() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals(1, holdAt(50f, 60f, PHOTO));
        View view = LongPressZoom.shownView();
        // The feed lets go of the bitmap: its own view moves on and the bitmap is recycled.
        image.setImageDrawable(null);
        bitmap.recycle();
        view.draw(new Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)));
        idle();
        assertFalse(LongPressZoom.isShown());
        assertNull(view.getParent());
    }

    /** The zoom leaving the window some other way, the activity closing, forgets it. */
    @Test
    public void aZoomTakenOffTheWindowIsForgotten() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals(1, holdAt(50f, 60f, PHOTO));
        View view = LongPressZoom.shownView();
        ((android.view.ViewGroup) view.getParent()).removeView(view);
        assertFalse(LongPressZoom.isShown());
    }

    private static void assertPoint(String what, float x, float y, float[] point) {
        assertEquals(what + " x", x, point[0], 0.5f);
        assertEquals(what + " y", y, point[1], 0.5f);
    }
}
