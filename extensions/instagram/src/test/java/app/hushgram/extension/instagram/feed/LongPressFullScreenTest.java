/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Long press a post for full screen (#505): what the press hook opens. A photo gives the viewer the picture
 * on screen, a video its post, and a carousel whichever page Instagram's pager is on. Off, paused, a finger that
 * moved and nothing to open leave the press to the zoom when that's on, otherwise to Instagram.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LongPressFullScreenTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final ToIntFunction<Object> PHOTO = media -> LongPressZoom.PHOTO;
    private static final ToIntFunction<Object> VIDEO = media -> LongPressZoom.VIDEO;

    private ActivityController<Activity> controller;
    private Activity activity;
    private LongPressZoomTest.Feed feed;
    private FrameLayout frame;
    private Bitmap bitmap;
    private Viewer viewer;
    private final Object media = new Object();
    private final Object photoPage = new Object();
    private final Object videoPage = new Object();
    private float x0;
    private float y0;

    /** A carousel of a photo page then a video page. */
    private final ToIntFunction<Object> carousel = post -> post == media ? LongPressZoom.CAROUSEL
            : post == videoPage ? LongPressZoom.VIDEO : LongPressZoom.PHOTO;

    /** Notes what it was asked to open. */
    private static final class Viewer implements LongPressZoom.FullScreen {
        List<?> pages;
        boolean opens = true;
        boolean breaks;
        Drawable picture;
        Object video;

        @Override public List<?> pages(Object post) {
            return pages;
        }

        @Override public boolean photo(Context context, Drawable shown) {
            if (breaks) throw new IllegalStateException("no viewer");
            picture = shown;
            return opens;
        }

        @Override public boolean video(Context context, Object post) {
            video = post;
            return opens;
        }
    }

    /** Instagram's carousel pager, by the names the extension reads. */
    public static final class Pager extends FrameLayout {
        private final int page;

        Pager(Context context, int page) {
            super(context);
            this.page = page;
        }

        public int getCurrentDataIndex() {
            return page;
        }

        public int getCurrentRawDataIndex() {
            return page;
        }

        public ListAdapter getAdapter() {
            return null;
        }
    }

    @Before
    public void clean() {
        reset();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        viewer = new Viewer();
        LongPressZoom.fullScreen = viewer;
        feed = new LongPressZoomTest.Feed(activity);
        frame = new FrameLayout(activity);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        image.setImageBitmap(bitmap);
        frame.addView(image, new FrameLayout.LayoutParams(200, 200));
        feed.addView(frame, new FrameLayout.LayoutParams(200, 200));
        activity.setContentView(feed);
        idle();
        int[] spot = new int[2];
        image.getLocationOnScreen(spot);
        x0 = spot[0];
        y0 = spot[1];
    }

    @After
    public void restore() {
        if (controller != null) controller.close();
        reset();
    }

    private static void reset() {
        LongPressZoom.resetForTests();
        Settings.LONG_PRESS_TO_ZOOM.resetToDefault();
        Settings.LONG_PRESS_ZOOM_STAYS_OPEN.resetToDefault();
        Settings.LONG_PRESS_FULL_SCREEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void touch(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0L, 0L, action, x0 + x, y0 + y, 0);
        try {
            LongPressZoom.touch(event);
        } finally {
            event.recycle();
        }
    }

    private int hold(ToIntFunction<Object> type) {
        touch(MotionEvent.ACTION_DOWN, 50f, 60f);
        return LongPressZoom.press(frame, media, type);
    }

    private void assertNothingOpened() {
        assertNull(viewer.picture);
        assertNull(viewer.video);
        assertFalse(LongPressZoom.isShown());
        assertFalse(feed.held);
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    /** The picture the viewer was given shows the post's own bitmap, and nothing was fetched for it. */
    private void assertShowsThePhoto() {
        assertTrue(viewer.picture instanceof BitmapDrawable);
        assertSame(bitmap, ((BitmapDrawable) viewer.picture).getBitmap());
    }

    /** Off to start: the press stays Instagram's and nothing is asked of the viewer. */
    @Test
    public void itStartsOffAndLeavesTheLongPressToInstagram() {
        assertFalse(Settings.LONG_PRESS_FULL_SCREEN.get());
        assertEquals(0, hold(PHOTO));
        assertEquals(0, LongPressZoom.press(frame, media, VIDEO));
        assertNothingOpened();
    }

    /** Paused: a saved switch answers off, so the press is Instagram's. */
    @Test
    public void pausedLeavesTheLongPressToInstagram() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, hold(PHOTO));
        assertNothingOpened();
    }

    /** A press whose touch was never seen opens nothing, so a held finger is what opens the viewer. */
    @Test
    public void aPressWithNoFingerDownOpensNothing() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        assertEquals(0, LongPressZoom.press(frame, media, PHOTO));
        assertNothingOpened();
    }

    /** Past a tap's reach the press isn't one, the same as for the zoom. */
    @Test
    public void aFingerThatMovedOpensNothing() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        float slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        touch(MotionEvent.ACTION_DOWN, 50f, 60f);
        touch(MotionEvent.ACTION_MOVE, 50f + slop + 5f, 60f);
        assertEquals(0, LongPressZoom.press(frame, media, PHOTO));
        assertNothingOpened();
        assertTrue(report(), report().contains(LongPressZoom.WANDERED));
    }

    /** A photo: the switch alone is enough, the viewer gets the post's own picture and the feed stays put. */
    @Test
    public void aHeldPhotoOpensItsPicture() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        assertFalse(Settings.LONG_PRESS_TO_ZOOM.get());
        assertEquals(1, hold(PHOTO));
        assertShowsThePhoto();
        assertNull(viewer.video);
        assertFalse(LongPressZoom.isShown());
        assertTrue(feed.held);
        assertTrue(report(), report().contains(LongPressZoom.FULL_SCREEN));
    }

    /** A video, which the zoom leaves alone: the viewer gets the post. */
    @Test
    public void aHeldVideoOpensThePost() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        assertEquals(1, hold(VIDEO));
        assertSame(media, viewer.video);
        assertNull(viewer.picture);
        assertTrue(feed.held);
    }

    /** A post of a kind that isn't either has nothing to open, and the press stays Instagram's. */
    @Test
    public void aPostThatDoesntSayOpensNothing() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        assertEquals(0, hold(post -> 0));
        assertNothingOpened();
        assertTrue(report(), report().contains(LongPressZoom.NOT_FULL_SCREEN));
    }

    /** A carousel on its video page plays that page, with a pager inside the frame. */
    @Test
    public void aCarouselOnAVideoPagePlaysThatPage() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        viewer.pages = Arrays.asList(photoPage, videoPage);
        frame.addView(new Pager(activity, 1), new FrameLayout.LayoutParams(1, 1));
        idle();
        assertEquals(1, hold(carousel));
        assertSame(videoPage, viewer.video);
        assertNull(viewer.picture);
    }

    /** A carousel on its photo page shows the picture on screen. */
    @Test
    public void aCarouselOnAPhotoPageShowsThePicture() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        viewer.pages = Arrays.asList(photoPage, videoPage);
        frame.addView(new Pager(activity, 0), new FrameLayout.LayoutParams(1, 1));
        idle();
        assertEquals(1, hold(carousel));
        assertShowsThePhoto();
        assertNull(viewer.video);
    }

    /** The pager can sit around the frame instead. */
    @Test
    public void thePagerCanBeAroundTheFrame() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        viewer.pages = Arrays.asList(photoPage, videoPage);
        feed.removeView(frame);
        Pager pager = new Pager(activity, 1);
        pager.addView(frame, new FrameLayout.LayoutParams(200, 200));
        feed.addView(pager, new FrameLayout.LayoutParams(200, 200));
        idle();
        assertEquals(1, hold(carousel));
        assertSame(videoPage, viewer.video);
    }

    /** With no pager to ask, a carousel still opens the picture under the finger. */
    @Test
    public void aCarouselWithNoPagerShowsThePictureUnderTheFinger() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        viewer.pages = Arrays.asList(photoPage, videoPage);
        assertEquals(1, hold(carousel));
        assertShowsThePhoto();
    }

    /** A pager past the end of the pages it's meant to page through tells nothing either. */
    @Test
    public void aPageOutsideThePostsPagesIsNoPage() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        viewer.pages = Arrays.asList(photoPage, videoPage);
        frame.addView(new Pager(activity, 7), new FrameLayout.LayoutParams(1, 1));
        idle();
        assertEquals(1, hold(carousel));
        assertShowsThePhoto();
        assertNull(viewer.video);
    }

    /** With no pager and a video playing under the finger, a still of it isn't what was pressed: nothing opens. */
    @Test
    public void aCarouselOfUnknownPageWithAVideoUnderTheFingerOpensNothing() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        frame.addView(new TextureView(activity), new FrameLayout.LayoutParams(200, 200));
        idle();
        assertEquals(0, hold(carousel));
        assertNothingOpened();
    }

    /** A video the viewer couldn't open, with the zoom off: the press is Instagram's. */
    @Test
    public void aViewerThatDoesntOpenLeavesTheLongPressToInstagram() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        viewer.opens = false;
        assertEquals(0, hold(VIDEO));
        assertFalse(LongPressZoom.isShown());
        assertFalse(feed.held);
        assertTrue(report(), report().contains(LongPressZoom.NOT_FULL_SCREEN));
    }

    /** With the zoom on too, the viewer comes first and the zoom doesn't open under it. */
    @Test
    public void bothOnTheViewerWins() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals(1, hold(PHOTO));
        assertShowsThePhoto();
        assertFalse(LongPressZoom.isShown());
    }

    /** With the zoom on too and the viewer unable to open, the photo still zooms. */
    @Test
    public void bothOnAViewerThatDoesntOpenFallsBackToTheZoom() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        viewer.opens = false;
        assertEquals(1, hold(PHOTO));
        assertTrue(LongPressZoom.isShown());
        assertTrue(report(), report().contains(LongPressZoom.ZOOMED));
    }

    /** The zoom alone keeps working as it did, and never asks the viewer. */
    @Test
    public void theZoomAloneNeverAsksTheViewer() {
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertEquals(1, hold(PHOTO));
        assertTrue(LongPressZoom.isShown());
        assertNull(viewer.picture);
        assertEquals(0, LongPressZoom.press(frame, media, VIDEO));
        assertNull(viewer.video);
    }

    /** Anything thrown leaves the press to Instagram, with nothing left open. */
    @Test
    public void aFailureLeavesTheLongPressToInstagram() {
        Settings.LONG_PRESS_FULL_SCREEN.save(true);
        viewer.breaks = true;
        assertEquals(0, hold(PHOTO));
        assertFalse(LongPressZoom.isShown());
        assertFalse(feed.held);
        assertTrue(report(), report().contains("press"));
    }
}
