/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Peek at the next carousel page (#361): each carousel's pages narrow and scale, the next one shows, and off puts it all back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CarouselPeekTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int WIDTH = 720, HEIGHT = 900;

    /** The pager's buffer bias, with 452.1's names, which survive minifying. */
    public enum FakeBias { BIAS_START, BIAS_CENTER, BIAS_FORWARD, BIAS_NEXT_ONLY }

    /** Instagram's pager as far as the helper sees it: the methods 452.1's ReboundViewPager keeps by name, and its public bias field. */
    public static class FakePager extends FrameLayout {
        public FakeBias A0K = FakeBias.BIAS_FORWARD;
        int pageSize = -1;
        float spacing = 3;
        int buffer;
        int bufferCalls;

        public void setBufferBias(FakeBias bias) {
            A0K = bias;
        }

        public FakePager(Context context) {
            super(context);
        }

        public void setOverridePageSize(int size) {
            pageSize = size;
        }

        public void setPageSpacing(float spacing) {
            this.spacing = spacing;
        }

        public float getPageSpacing() {
            return spacing;
        }

        public int getExtraBufferSize() {
            return buffer;
        }

        /** Kotlin's bridge for a setter with two default arguments; [mask] 6 means both are defaults. */
        public static void setExtraBufferSize$default(FakePager pager, int size, boolean now, Boolean bias, int mask, Object unused) {
            assertEquals(6, mask);
            pager.buffer = size;
            pager.bufferCalls++;
        }
    }

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private FakePager pager;
    private FrameLayout first;
    private FrameLayout second;
    private int frameId;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        CarouselPeek.resetForTests();
        frameId = View.generateViewId();
        CarouselPeek.idForTests(activity, frameId);

        root = new FrameLayout(activity);
        pager = new FakePager(activity);
        root.addView(pager, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        first = page(pager);
        second = page(pager);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.PEEK_CAROUSEL_PAGES.resetToDefault();
        HookStatus.clear();
    }

    /** A carousel page as 452.1 builds it: the page, its zoom container, then the photo frame. */
    private FrameLayout page(FrameLayout parent) {
        FrameLayout page = new FrameLayout(activity);
        FrameLayout zoom = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(frameId);
        zoom.addView(frame);
        page.addView(zoom);
        parent.addView(page, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        return page;
    }

    @After
    public void restore() {
        CarouselPeek.resetForTests();
        Settings.PEEK_CAROUSEL_PAGES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private boolean frame() {
        return root.getViewTreeObserver().dispatchOnPreDraw();
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    private int pageSize() {
        float d = density();
        return Math.round(WIDTH - (CarouselPeek.INSET + CarouselPeek.GAP + CarouselPeek.PEEK) * d);
    }

    @Test
    public void offToStartLeavesCarouselsAlone() {
        assertFalse(Settings.PEEK_CAROUSEL_PAGES.get());

        CarouselPeek.watch(activity);
        frame();

        assertFalse(CarouselPeek.listeningForTests(activity));
        assertEquals(-1, pager.pageSize);
        assertEquals(1f, first.getScaleX(), 0f);
    }

    @Test
    public void onNarrowsThePagesKeepsTheNextOneAttachedAndScalesEachPage() {
        Settings.PEEK_CAROUSEL_PAGES.save(true);

        CarouselPeek.watch(activity);
        assertTrue("the pages moved, so the frame is drawn again", frame());

        float scale = pageSize() / (float) WIDTH;
        assertEquals(pageSize(), pager.pageSize);
        assertEquals(CarouselPeek.GAP * density(), pager.spacing, 0.01f);
        assertEquals("the next page stays attached at rest", 1, pager.buffer);
        assertEquals("and after a swipe back", FakeBias.BIAS_NEXT_ONLY, pager.A0K);
        for (FrameLayout page : new FrameLayout[] {first, second}) {
            assertEquals(scale, page.getScaleX(), 0.0001f);
            assertEquals(scale, page.getScaleY(), 0.0001f);
            // The scaled page's left edge lands INSET in from the pager's.
            float left = page.getPivotX() * (1 - scale);
            assertEquals(CarouselPeek.INSET * density(), left, 0.01f);
            assertEquals(HEIGHT / 2f, page.getPivotY(), 0.01f);
        }
        assertEquals(1, CarouselPeek.peekingForTests());
        assertTrue(HookStatus.missing("Rounded post corners").toString(),
                HookStatus.missing("Rounded post corners").isEmpty());

        assertFalse("nothing left to change on the next frame", frame());
        assertEquals("set up once per width", 1, pager.bufferCalls);
    }

    @Test
    public void aBiggerBufferIsKept() {
        pager.buffer = 2;
        Settings.PEEK_CAROUSEL_PAGES.save(true);

        CarouselPeek.watch(activity);
        frame();

        assertEquals(2, pager.buffer);
    }

    @Test
    public void aPageAddedWhileSwipingIsScaledToo() {
        Settings.PEEK_CAROUSEL_PAGES.save(true);
        CarouselPeek.watch(activity);
        frame();

        FrameLayout third = page(pager);
        // Seen on 452.1: the pager attaches the next page and a frame scales it before it has a height.
        frame();
        // The new page's layout pass marks the screen laid out again and draws the next frame.
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertEquals(pageSize() / (float) WIDTH, third.getScaleX(), 0.0001f);
        assertEquals("scaled from its middle once it has a height", HEIGHT / 2f, third.getPivotY(), 0.01f);
    }

    @Test
    public void offPutsEveryPagerAndPageBack() {
        Settings.PEEK_CAROUSEL_PAGES.save(true);
        CarouselPeek.watch(activity);
        frame();

        Settings.PEEK_CAROUSEL_PAGES.save(false);
        frame();

        assertFalse(CarouselPeek.listeningForTests(activity));
        assertEquals(-1, pager.pageSize);
        assertEquals(3f, pager.spacing, 0f);
        assertEquals(0, pager.buffer);
        assertEquals(FakeBias.BIAS_FORWARD, pager.A0K);
        assertEquals(1f, first.getScaleX(), 0f);
        assertEquals(1f, second.getScaleY(), 0f);
        assertFalse("each page scales from its own middle again", first.isPivotSet() || second.isPivotSet());
        assertEquals(0, CarouselPeek.peekingForTests());
    }

    @Test
    public void aBiasInstagramSetsAgainIsTurnedBackOnTheNextLayout() {
        Settings.PEEK_CAROUSEL_PAGES.save(true);
        CarouselPeek.watch(activity);
        frame();

        pager.A0K = FakeBias.BIAS_FORWARD;
        pager.requestLayout();
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertEquals(FakeBias.BIAS_NEXT_ONLY, pager.A0K);
        assertEquals("the rest stays as it was set up", 1, pager.bufferCalls);
    }

    /** An older pager's bias, without the one that keeps the next page. */
    public enum OldBias { BIAS_START, BIAS_FORWARD }

    /** A pager with Instagram's page size setter whose bias can't keep the next page attached. */
    public static class OldPager extends FrameLayout {
        public OldBias A0K = OldBias.BIAS_FORWARD;
        int pageSize = -1;

        public OldPager(Context context) {
            super(context);
        }

        public void setOverridePageSize(int size) {
            pageSize = size;
        }

        public void setBufferBias(OldBias bias) {
            A0K = bias;
        }

        public static void setExtraBufferSize$default(OldPager pager, int size, boolean now, Boolean bias, int mask, Object unused) {
        }
    }

    @Test
    public void aPagerMissingAPartIsLeftAloneAndSaysSo() {
        root.removeView(pager);
        OldPager old = new OldPager(activity);
        root.addView(old, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        FrameLayout page = page(old);
        shadowOf(Looper.getMainLooper()).idle();
        Settings.PEEK_CAROUSEL_PAGES.save(true);

        CarouselPeek.watch(activity);
        frame();

        assertEquals(-1, old.pageSize);
        assertEquals(1f, page.getScaleX(), 0f);
        String missing = HookStatus.missing("Rounded post corners").toString();
        assertTrue(missing, missing.contains("BIAS_NEXT_ONLY"));
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.PEEK_CAROUSEL_PAGES.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        CarouselPeek.watch(activity);
        frame();

        assertFalse(CarouselPeek.listeningForTests(activity));
        assertEquals(-1, pager.pageSize);
    }

    @Test
    public void flippingTheSwitchUnderSettingsPeeksWithoutAResume() {
        CarouselPeek.watch(activity);
        assertFalse(CarouselPeek.listeningForTests(activity));

        Settings.PEEK_CAROUSEL_PAGES.save(true);
        CarouselPeek.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertTrue(CarouselPeek.listeningForTests(activity));
        assertEquals(pageSize(), pager.pageSize);
    }

    /** A carousel whose pages on screen are all videos is found by the video page frame. */
    @Test
    public void aCarouselShowingOnlyVideosPeeksToo() {
        int videoId = View.generateViewId();
        CarouselPeek.videoIdForTests(activity, videoId);
        root.removeView(pager);
        FakePager videos = new FakePager(activity);
        root.addView(videos, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        frameId = videoId;
        FrameLayout page = page(videos);
        shadowOf(Looper.getMainLooper()).idle();
        Settings.PEEK_CAROUSEL_PAGES.save(true);

        CarouselPeek.watch(activity);
        frame();

        assertEquals(pageSize(), videos.pageSize);
        assertEquals(pageSize() / (float) WIDTH, page.getScaleX(), 0.0001f);
    }

    /** A page frame with no pager above it, or a pager without the methods, is left as it is. */
    @Test
    public void aFrameWithoutAKnownPagerIsLeftAlone() {
        root.removeView(pager);
        FrameLayout plain = new FrameLayout(activity);
        root.addView(plain, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        FrameLayout page = page(plain);
        shadowOf(Looper.getMainLooper()).idle();
        Settings.PEEK_CAROUSEL_PAGES.save(true);

        CarouselPeek.watch(activity);
        assertFalse(frame());

        assertEquals(1f, page.getScaleX(), 0f);
        assertEquals(0, CarouselPeek.peekingForTests());
    }

    @Test
    public void aBuildWithoutTheFrameIdPeeksNothingAndSaysSo() {
        CarouselPeek.resetForTests();
        Settings.PEEK_CAROUSEL_PAGES.save(true);

        CarouselPeek.watch(activity);
        frame();

        assertEquals(-1, pager.pageSize);
        assertFalse(HookStatus.missing("Rounded post corners").toString(),
                HookStatus.missing("Rounded post corners").isEmpty());
    }
}
