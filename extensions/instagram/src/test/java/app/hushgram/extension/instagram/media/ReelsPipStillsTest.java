/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Photo and carousel reels in Instagram's picture in picture window for reels (#873): with the
 * switch on, the window's check takes a photo or a carousel as it takes a video, the window's bind
 * shows a photo page's picture in place of the player and a carousel's dots, and the window's Next
 * and Previous turn a carousel's pages before Instagram goes to another reel. Videos keep their
 * answer, and every other reel stays as Instagram has it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelsPipStillsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object photo = new Object();
    private final Object carousel = new Object();
    private final Object live = new Object();
    private final Object unknown = new Object();
    private final Map<Object, Integer> types = new HashMap<>();

    /** Reels for the bind, each with its Media, and the Media's type, pages and picture. */
    private final Map<Object, Object> mediaOf = new HashMap<>();
    private final Map<Object, Integer> typeOf = new HashMap<>();
    private final Map<Object, List<?>> pagesOf = new HashMap<>();
    private final Map<Object, String> pictureOf = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final Object bindLambda = new Object();
    private final Object bindScope = new Object();
    private Object bindReel;
    private FrameLayout layout;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ReelsPipStills.forgetForTests();
        types.put(photo, 1);
        types.put(carousel, 8);
        types.put(live, 7);
        ReelsPipStills.typesForTests = types::get;
        ReelsPipStills.reelsForTests = new ReelsPipStills.Reels() {
            @Override
            public Object media(Object reel) {
                return mediaOf.get(reel);
            }

            @Override
            public Integer type(Object media) {
                return typeOf.get(media);
            }

            @Override
            public List<?> pages(Object media) {
                return pagesOf.get(media);
            }

            @Override
            public String picture(Object media, int size) {
                return pictureOf.get(media);
            }
        };
        ReelsPipStills.windowForTests = new ReelsPipStills.Window() {
            @Override
            public Bitmap fetch(Context context, String url, int size) {
                events.add("fetch " + url);
                return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            }

            @Override
            public void pause() {
                events.add("pause");
            }

            @Override
            public void bind(Object binder, Object scope, View view) {
                assertSame(bindLambda, binder);
                assertSame(bindScope, scope);
                Integer page = instagramBinds(bindReel);
                events.add("bind " + page);
            }
        };
        ReelsPipStills.workForTests = Runnable::run;
        Settings.REELS_PIP_STILLS.save(true);
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        layout = new FrameLayout(screen);
        screen.setContentView(layout);
    }

    @After
    public void restore() {
        ReelsPipStills.typesForTests = null;
        ReelsPipStills.reelsForTests = null;
        ReelsPipStills.windowForTests = null;
        ReelsPipStills.workForTests = null;
        ReelsPipStills.forgetForTests();
        Settings.REELS_PIP_STILLS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    // ---- The window's check ----

    /** Off to start: a photo or a carousel stays out, as Instagram has it. */
    @Test
    public void startsOff() {
        Settings.REELS_PIP_STILLS.resetToDefault();
        assertEquals(0, ReelsPipStills.window(0, photo));
        assertEquals(0, ReelsPipStills.window(0, carousel));
    }

    /** On, a photo and a carousel go in. */
    @Test
    public void photosAndCarouselsGoIn() {
        assertEquals(1, ReelsPipStills.window(0, photo));
        assertEquals(1, ReelsPipStills.window(0, carousel));
    }

    /** A video keeps Instagram's answer, on or off, without its type being read. */
    @Test
    public void videosKeepTheirAnswer() {
        ReelsPipStills.typesForTests = model -> {
            throw new AssertionError("a video's type was read");
        };
        assertEquals(1, ReelsPipStills.window(1, photo));
        Settings.REELS_PIP_STILLS.resetToDefault();
        assertEquals(1, ReelsPipStills.window(1, photo));
    }

    /** A live, or a reel whose type can't be read, stays out. */
    @Test
    public void otherReelsStayOut() {
        assertEquals(0, ReelsPipStills.window(0, live));
        assertEquals(0, ReelsPipStills.window(0, unknown));
    }

    /** Paused, or before HushGram's settings are ready, Instagram decides. */
    @Test
    public void pausedOrNotReadyLeavesItToInstagram() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, ReelsPipStills.window(0, photo));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals(0, ReelsPipStills.window(0, photo)));
    }

    /** A failure keeps Instagram's no, and is reported. */
    @Test
    public void aFailureKeepsInstagramsAnswer() {
        ReelsPipStills.typesForTests = model -> {
            throw new IllegalStateException("broken");
        };
        assertEquals(0, ReelsPipStills.window(0, photo));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(ReelsPipStills.HOOK));
    }

    /** As built, before the patch writes them, the reads answer nothing. */
    @Test
    public void theReadsAreStubs() {
        assertNull(ReelsPipStills.mediaType(photo));
        assertNull(ReelsPipStills.reelMedia(photo));
    }

    // ---- The window's bind ----

    /** A photo binds no player: its picture shows over the layout, what played is paused, no dots. */
    @Test
    public void aPhotoShowsItsPictureInPlaceOfThePlayer() {
        Object reel = reel(1, "https://scontent.cdninstagram.com/photo.jpg");
        assertNull(instagramBindsReel(reel));
        ReelsPipStills.Slides slides = slides();
        assertEquals(View.VISIBLE, slides.getVisibility());
        assertEquals(View.VISIBLE, slides.picture.getVisibility());
        assertNotNull("the picture shows", slides.picture.getDrawable());
        assertEquals(View.GONE, slides.dots.getVisibility());
        assertEquals(Arrays.asList("fetch https://scontent.cdninstagram.com/photo.jpg", "pause"), events);
    }

    /** A video reel keeps its player and Instagram's page, and the pictures go once one shows. */
    @Test
    public void aVideoReelKeepsThePlayer() {
        Object video = reel(2, null);
        assertSame(video, instagramBindsReel(video));
        assertNull(ReelsPipStills.page(video));
        assertNull("nothing goes over a video", findSlides());

        instagramBindsReel(reel(1, "https://scontent.cdninstagram.com/photo.jpg"));
        assertEquals(View.VISIBLE, slides().getVisibility());
        assertSame(video, instagramBindsReel(video));
        assertEquals(View.GONE, slides().getVisibility());
        assertNull(slides().picture.getDrawable());
    }

    /** No reel is Instagram's own, and stays none. */
    @Test
    public void noReelStaysNone() {
        assertNull(ReelsPipStills.bound(layout, null));
        assertNull(ReelsPipStills.page(null));
    }

    /** A carousel shows its first page and a dot for each page. */
    @Test
    public void aCarouselShowsItsDots() {
        Object reel = carousel(page(1, "https://scontent.cdninstagram.com/1.jpg"),
                page(1, "https://scontent.cdninstagram.com/2.jpg"), page(2, "https://scontent.cdninstagram.com/3.jpg"));
        assertNull(instagramBindsReel(reel));
        ReelsPipStills.Dots dots = slides().dots;
        assertEquals(View.VISIBLE, dots.getVisibility());
        assertEquals(3, dots.count);
        assertEquals(0, dots.at);
        assertTrue("the next page's picture is fetched ahead", events.contains("fetch https://scontent.cdninstagram.com/2.jpg"));
    }

    /** Next turns a carousel's pages, and at the last one leaves the move to Instagram; Previous the same back. */
    @Test
    public void nextTurnsThePagesThenLeavesTheReelToInstagram() {
        Object reel = carousel(page(1, "https://scontent.cdninstagram.com/1.jpg"), page(1, "https://scontent.cdninstagram.com/2.jpg"));
        instagramBindsReel(reel);
        events.clear();

        assertEquals(1, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_NEXT)));
        idle();
        assertEquals(1, slides().dots.at);
        assertNotNull(slides().picture.getDrawable());
        assertEquals("the second page shows", "https://scontent.cdninstagram.com/2.jpg", slides().want);
        assertEquals("past the last page, Instagram moves on", 0, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_NEXT)));
        assertEquals(1, slides().dots.at);

        assertEquals(1, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_PREVIOUS)));
        idle();
        assertEquals(0, slides().dots.at);
        assertEquals("https://scontent.cdninstagram.com/1.jpg", slides().want);
        assertEquals("before the first page, Instagram goes back", 0, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_PREVIOUS)));
    }

    /** Turning to a video page binds Instagram's player again, for that page; back to a photo pauses it. */
    @Test
    public void aVideoPageBindsAgainWithItsPage() {
        Object reel = carousel(page(1, "https://scontent.cdninstagram.com/1.jpg"), page(2, "https://scontent.cdninstagram.com/2.jpg"));
        bindReel = reel;
        instagramBinds(reel);
        events.clear();

        assertEquals(1, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_NEXT)));
        idle();
        assertEquals(Arrays.asList("bind 1"), events);
        assertEquals(View.GONE, slides().picture.getVisibility());
        assertEquals("the dots stay over the video", View.VISIBLE, slides().dots.getVisibility());
        assertEquals(1, slides().dots.at);

        events.clear();
        assertEquals(1, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_PREVIOUS)));
        idle();
        assertEquals(View.VISIBLE, slides().picture.getVisibility());
        assertTrue(events.toString(), events.contains("pause"));
    }

    /** A carousel whose first page is a video plays that page. */
    @Test
    public void aCarouselOpeningOnAVideoPlaysIt() {
        Object reel = carousel(page(2, "https://scontent.cdninstagram.com/1.jpg"), page(1, "https://scontent.cdninstagram.com/2.jpg"));
        assertSame(reel, ReelsPipStills.bound(layout, reel));
        assertEquals(Integer.valueOf(0), ReelsPipStills.page(reel));
        assertEquals(View.VISIBLE, slides().dots.getVisibility());
        assertEquals(View.GONE, slides().picture.getVisibility());
    }

    /** Another reel starts at its own first page. */
    @Test
    public void aNewReelStartsAtItsFirstPage() {
        Object first = carousel(page(1, "https://scontent.cdninstagram.com/a1.jpg"), page(1, "https://scontent.cdninstagram.com/a2.jpg"));
        instagramBindsReel(first);
        ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_NEXT));
        idle();
        assertEquals(1, slides().dots.at);
        Object second = carousel(page(1, "https://scontent.cdninstagram.com/b1.jpg"), page(1, "https://scontent.cdninstagram.com/b2.jpg"));
        instagramBindsReel(second);
        assertEquals(0, slides().dots.at);
        assertEquals("https://scontent.cdninstagram.com/b1.jpg", slides().want);
    }

    /** Off or paused, the bind and the buttons are Instagram's, and pictures shown before go. */
    @Test
    public void offOrPausedLeavesItAllToInstagram() {
        Object reel = carousel(page(1, "https://scontent.cdninstagram.com/1.jpg"), page(1, "https://scontent.cdninstagram.com/2.jpg"));
        instagramBindsReel(reel);
        Settings.REELS_PIP_STILLS.save(false);
        assertEquals(0, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_NEXT)));
        assertSame(reel, instagramBindsReel(reel));
        assertNull(ReelsPipStills.page(reel));
        assertEquals(View.GONE, slides().getVisibility());

        Settings.REELS_PIP_STILLS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(reel, instagramBindsReel(reel));
    }

    /** Without a carousel showing, any other action or none, the buttons are Instagram's. */
    @Test
    public void theButtonsAreInstagramsWithoutACarousel() {
        assertEquals(0, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_NEXT)));
        instagramBindsReel(reel(1, "https://scontent.cdninstagram.com/photo.jpg"));
        assertEquals(0, ReelsPipStills.skip(new Intent(ReelsPipStills.ACTION_NEXT)));
        instagramBindsReel(carousel(page(1, "https://scontent.cdninstagram.com/1.jpg"), page(1, "https://scontent.cdninstagram.com/2.jpg")));
        assertEquals(0, ReelsPipStills.skip(new Intent("app.hushgram.extension.instagram.REELS_PIP_PAUSE")));
        assertEquals(0, ReelsPipStills.skip(new Intent()));
        assertEquals(0, ReelsPipStills.skip(null));
    }

    /** A failure binds Instagram's reel and is reported. */
    @Test
    public void aFailureKeepsInstagramsReel() {
        Object reel = new Object();
        ReelsPipStills.reelsForTests = new ReelsPipStills.Reels() {
            @Override
            public Object media(Object r) {
                throw new IllegalStateException("broken");
            }

            @Override
            public Integer type(Object media) {
                return null;
            }

            @Override
            public List<?> pages(Object media) {
                return null;
            }

            @Override
            public String picture(Object media, int size) {
                return null;
            }
        };
        assertSame(reel, ReelsPipStills.bound(layout, reel));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(ReelsPipStills.HOOK));
    }

    // ---- Helpers ----

    /** The way the patched bind runs: its arguments, then the reel, then the page. Answers the page. */
    private Integer instagramBinds(Object reel) {
        ReelsPipStills.binding(bindLambda, bindScope, layout);
        Object answer = ReelsPipStills.bound(layout, reel);
        return ReelsPipStills.page(answer);
    }

    /** The same, answering the reel the bind goes on with. */
    private Object instagramBindsReel(Object reel) {
        ReelsPipStills.binding(bindLambda, bindScope, layout);
        Object answer = ReelsPipStills.bound(layout, reel);
        ReelsPipStills.page(answer);
        return answer;
    }

    private Object reel(int type, String picture) {
        Object reel = new Object();
        Object media = page(type, picture);
        mediaOf.put(reel, media);
        return reel;
    }

    private Object page(int type, String picture) {
        Object media = new Object();
        typeOf.put(media, type);
        if (picture != null) pictureOf.put(media, picture);
        return media;
    }

    private Object carousel(Object... pages) {
        Object reel = new Object();
        Object media = new Object();
        typeOf.put(media, 8);
        pagesOf.put(media, Arrays.asList(pages));
        mediaOf.put(reel, media);
        return reel;
    }

    private ReelsPipStills.Slides findSlides() {
        for (int i = 0; i < layout.getChildCount(); i++) {
            if (layout.getChildAt(i) instanceof ReelsPipStills.Slides) return (ReelsPipStills.Slides) layout.getChildAt(i);
        }
        return null;
    }

    private ReelsPipStills.Slides slides() {
        ReelsPipStills.Slides slides = findSlides();
        assertNotNull("the pictures are over the layout", slides);
        return slides;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }
}
