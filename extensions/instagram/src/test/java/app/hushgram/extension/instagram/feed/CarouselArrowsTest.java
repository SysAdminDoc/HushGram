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
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

/** Carousel arrows (#417): a back and a next arrow over each feed carousel, shown where they lead somewhere, and a tap turns the page. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CarouselArrowsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int WIDTH = 720, HEIGHT = 900, HEADER = 120, FOOTER = 80;
    private static final String FAMILY = "Carousel arrows";

    /** A carousel's pages, as many as asked for. */
    static BaseAdapter pages(int count) {
        return new BaseAdapter() {
            @Override public int getCount() {
                return count;
            }
            @Override public Object getItem(int position) {
                return null;
            }
            @Override public long getItemId(int position) {
                return position;
            }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return null;
            }
        };
    }

    /** Instagram's pager as far as the helper sees it: the page getters 453's ReboundViewPager keeps by name. */
    public static class FakePager extends FrameLayout {
        int at;
        /** How far the pager's own count is from the page shown, as in a carousel that loops. */
        int loop;
        BaseAdapter pages = pages(3);

        public FakePager(Context context) {
            super(context);
        }

        public int getCurrentDataIndex() {
            return pages == null ? -1 : at;
        }

        public int getCurrentRawDataIndex() {
            return at + loop;
        }

        public Adapter getAdapter() {
            return pages;
        }
    }

    /** A pager with the page getters but no adapter getter. */
    public static class OldPager extends FrameLayout {
        public OldPager(Context context) {
            super(context);
        }

        public int getCurrentDataIndex() {
            return 0;
        }

        public int getCurrentRawDataIndex() {
            return 0;
        }
    }

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private LinearLayout column;
    private FrameLayout footer;
    private View instagramDots;
    private FakePager pager;
    private int frameId;
    private final List<Integer> turned = new ArrayList<>();

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        CarouselArrows.resetForTests();
        frameId = View.generateViewId();
        CarouselArrows.idForTests(activity, frameId);
        int dotsId = View.generateViewId();
        CarouselArrows.dotsIdForTests(activity, dotsId);
        CarouselArrows.turnForTests((carousel, page) -> {
            assertTrue(carousel == pager);
            turned.add(page);
        });

        // The post: a frame layout holding a column with the header above the carousel.
        root = new FrameLayout(activity);
        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(new View(activity), new LinearLayout.LayoutParams(WIDTH, HEADER));
        pager = new FakePager(activity);
        column.addView(pager, new LinearLayout.LayoutParams(WIDTH, HEIGHT));
        root.addView(column, new FrameLayout.LayoutParams(WIDTH, HEADER + HEIGHT));
        page(pager);
        page(pager);
        // The feed's next row holds Instagram's own dots, centered under the carousel, as 453 lays out a post's buttons.
        LinearLayout feed = new LinearLayout(activity);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.addView(root, new LinearLayout.LayoutParams(WIDTH, HEADER + HEIGHT));
        footer = new FrameLayout(activity);
        instagramDots = new View(activity);
        instagramDots.setId(dotsId);
        footer.addView(instagramDots, new FrameLayout.LayoutParams(60, 12, Gravity.CENTER));
        feed.addView(footer, new LinearLayout.LayoutParams(WIDTH, FOOTER));
        activity.setContentView(feed);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.CAROUSEL_ARROWS.resetToDefault();
        Settings.CAROUSEL_DOTS_PILL.resetToDefault();
        HookStatus.clear();
    }

    /** A carousel page as 453 builds it: the page, its zoom container, then the photo frame. */
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
        CarouselArrows.resetForTests();
        Settings.CAROUSEL_ARROWS.resetToDefault();
        Settings.CAROUSEL_DOTS_PILL.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    /** Runs a frame's pre-draw through the window's observer, which a row out of the window has no part in. True when the frame is held back. */
    private boolean frame() {
        return activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
    }

    /** Lays the screen out and draws a frame, as after a swipe. */
    private void settle() {
        shadowOf(Looper.getMainLooper()).idle();
        frame();
    }

    private float density() {
        return activity.getResources().getDisplayMetrics().density;
    }

    private boolean shows(View arrow) {
        return arrow.getVisibility() == View.VISIBLE;
    }

    private View back() {
        return CarouselArrows.backForTests(pager);
    }

    private View next() {
        return CarouselArrows.nextForTests(pager);
    }

    private void on() {
        Settings.CAROUSEL_ARROWS.save(true);
        CarouselArrows.watch(activity);
        assertFalse("no frame is held back for the arrows", frame());
    }

    @Test
    public void offToStartLeavesCarouselsAlone() {
        assertFalse(Settings.CAROUSEL_ARROWS.get());

        CarouselArrows.watch(activity);
        frame();

        assertFalse(CarouselArrows.listeningForTests(activity));
        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertEquals(1, root.getChildCount());
    }

    @Test
    public void theFirstPageShowsOnlyNextOverTheCarouselsRightEdgeHalfwayDown() {
        on();
        settle();

        assertEquals(1, CarouselArrows.withArrowsForTests());
        assertEquals("both arrows sit in the post's frame layout", 3, root.getChildCount());
        assertTrue(back().getParent() == root && next().getParent() == root);
        assertFalse(shows(back()));
        assertTrue(shows(next()));
        assertEquals("Previous page", back().getContentDescription().toString());
        assertEquals("Next page", next().getContentDescription().toString());
        assertTrue(next().isClickable());

        float d = density(), touch = CarouselArrows.TOUCH * d;
        float inset = (CarouselArrows.MARGIN - (CarouselArrows.TOUCH - CarouselArrows.BUTTON) / 2) * d;
        float top = HEADER + HEIGHT / 2f - touch / 2;
        assertEquals(Math.round(touch), next().getWidth());
        assertEquals(Math.round(WIDTH - inset - touch), next().getLeft() + next().getTranslationX(), 0f);
        assertEquals(Math.round(top), next().getTop() + next().getTranslationY(), 0f);
        assertEquals(Math.round(inset), back().getLeft() + back().getTranslationX(), 0f);
        assertEquals(Math.round(top), back().getTop() + back().getTranslationY(), 0f);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + CarouselArrows.COUNT_SHOWN + " 1"));
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());

        assertFalse("nor on the next frame", frame());
        settle();
        assertEquals("a carousel gets its arrows once", 3, root.getChildCount());
    }

    @Test
    public void theArrowsFollowTheCarouselsPage() {
        on();

        pager.at = 1;
        assertFalse(frame());
        assertTrue("a middle page shows both", shows(back()) && shows(next()));

        pager.at = 2;
        assertFalse(frame());
        assertTrue("the last page shows back only", shows(back()) && !shows(next()));

        pager.at = 0;
        assertFalse(frame());
        assertTrue(!shows(back()) && shows(next()));
    }

    @Test
    public void aCarouselOfOnePageShowsNeither() {
        pager.pages = pages(1);

        on();

        assertNotNull(back());
        assertFalse(shows(back()));
        assertFalse(shows(next()));
    }

    @Test
    public void aCarouselWithNoPagesYetShowsNeither() {
        pager.pages = null;

        Settings.CAROUSEL_ARROWS.save(true);
        CarouselArrows.watch(activity);
        frame();

        assertFalse(shows(back()));
        assertFalse(shows(next()));
    }

    @Test
    public void aTapTurnsThePagerOnePageThroughItsOwnCount() {
        on();
        pager.at = 1;
        pager.loop = 300;
        frame();

        assertTrue(next().performClick());
        assertTrue(back().performClick());

        assertEquals(Arrays.asList(302, 300), turned);
    }

    @Test
    public void aTapPastEitherEndTurnsNothing() {
        on();

        back().performClick();
        pager.at = 2;
        next().performClick();

        assertTrue(turned.toString(), turned.isEmpty());
    }

    @Test
    public void aTurnThatThrowsIsReportedAndTheScreenStays() {
        CarouselArrows.turnForTests((carousel, page) -> {
            throw new IllegalStateException("refused");
        });
        on();

        next().performClick();

        assertTrue(shows(next()));
        String missing = HookStatus.missing(FAMILY).toString();
        assertTrue(missing, missing.contains("'carousel arrows tap' hook (it threw java.lang.IllegalStateException)"));
    }

    @Test
    public void aCarouselThatLeavesTheWindowLosesItsArrows() {
        on();

        root.removeView(column);
        frame();

        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertEquals(0, root.getChildCount());
    }

    /**
     * #425: the feed reuses a row it scrolled away, so a carousel keeps its arrows and pill for the row's
     * return. Adding them again would lay the whole feed out each time, and no frame is held back either
     * way. Instagram's dots are its own while the row is gone.
     */
    @Test
    public void aRowScrolledOutAndBackAddsNothingAndLaysNothingOut() {
        Settings.CAROUSEL_ARROWS.save(true);
        pillOn();
        settle();
        View arrow = next();
        CarouselArrows.Pill held = pill();
        assertEquals(0f, instagramDots.getAlpha(), 0f);
        ViewGroup feed = (ViewGroup) root.getParent();

        feed.removeView(root);
        assertFalse(frame());
        settle();

        assertEquals("the row keeps both arrows and the pill", 4, root.getChildCount());
        assertEquals(1, CarouselArrows.withArrowsForTests());
        assertEquals(1f, instagramDots.getAlpha(), 0f);

        feed.addView(root, 0, new LinearLayout.LayoutParams(WIDTH, HEADER + HEIGHT));
        settle();
        assertFalse(frame());

        assertSame(arrow, next());
        assertSame(held, pill());
        assertEquals(4, root.getChildCount());
        assertFalse("nothing asked for another layout", root.isLayoutRequested());
        assertTrue(shows(next()) && shows(pill()));
        assertEquals(0f, instagramDots.getAlpha(), 0f);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + CarouselArrows.COUNT_SHOWN + " 1"));
    }

    /** A row the feed isn't holding at the moment still loses its arrows when the switch goes off. */
    @Test
    public void offTakesTheArrowsOffARowOutOfTheWindow() {
        on();
        settle();
        ((ViewGroup) root.getParent()).removeView(root);
        settle();
        assertEquals(3, root.getChildCount());

        Settings.CAROUSEL_ARROWS.save(false);
        frame();

        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertEquals(1, root.getChildCount());
    }

    /** The arrows lead back through their host to the carousel, so what remembers them must not keep every row scrolled past. */
    @Test
    public void aRowNothingElseHoldsCanBeCollected() throws InterruptedException {
        on();
        settle();
        ((ViewGroup) root.getParent()).removeView(root);
        settle();
        WeakReference<View> row = new WeakReference<>(pager);
        pager = null;
        root = null;
        column = null;

        for (int i = 0; i < 40 && row.get() != null; i++) {
            System.gc();
            Thread.sleep(25);
            frame();
        }

        assertNull("the arrows kept a row nothing else holds", row.get());
    }

    /** The arrows stay as long as their host holds them, however often memory is reclaimed in between. */
    @Test
    public void arrowsOnAKeptRowSurviveACollection() throws InterruptedException {
        on();
        settle();
        View arrow = next();
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(10);
            settle();
        }

        assertSame(arrow, next());
        assertEquals(3, root.getChildCount());
    }

    /** Each class is asked about once and remembered, so the answers must stay each class's own. */
    @Test
    public void dotsAreKnownByClassEveryTimeAndOnlyThoseAre() {
        View dots = new CirclePageIndicator(activity);
        View other = new View(activity);
        for (int i = 0; i < 2; i++) {
            assertTrue(CarouselArrows.isDots(dots, 0));
            assertFalse(CarouselArrows.isDots(other, 0));
        }
    }

    @Test
    public void offTakesEveryArrowAway() {
        on();

        Settings.CAROUSEL_ARROWS.save(false);
        frame();

        assertFalse(CarouselArrows.listeningForTests(activity));
        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertEquals(1, root.getChildCount());
        assertNull(back());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.CAROUSEL_ARROWS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        CarouselArrows.watch(activity);
        frame();

        assertFalse(CarouselArrows.listeningForTests(activity));
        assertEquals(1, root.getChildCount());
    }

    @Test
    public void flippingTheSwitchUnderSettingsShowsArrowsWithoutAResume() {
        CarouselArrows.watch(activity);
        assertFalse(CarouselArrows.listeningForTests(activity));

        Settings.CAROUSEL_ARROWS.save(true);
        CarouselArrows.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();
        frame();

        assertTrue(CarouselArrows.listeningForTests(activity));
        assertTrue(shows(next()));
    }

    /** A carousel whose pages on screen are all videos is found by the video page frame. */
    @Test
    public void aCarouselShowingOnlyVideosGetsArrowsToo() {
        int videoId = View.generateViewId();
        CarouselArrows.videoIdForTests(activity, videoId);
        pager.removeAllViews();
        frameId = videoId;
        page(pager);
        shadowOf(Looper.getMainLooper()).idle();

        on();

        assertTrue(shows(next()));
    }

    @Test
    public void aPagerWithoutItsAdapterGetterIsLeftAloneAndSaysSo() {
        column.removeView(pager);
        OldPager old = new OldPager(activity);
        column.addView(old, new LinearLayout.LayoutParams(WIDTH, HEIGHT));
        page(old);
        shadowOf(Looper.getMainLooper()).idle();
        Settings.CAROUSEL_ARROWS.save(true);

        CarouselArrows.watch(activity);
        assertFalse(frame());

        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertEquals(1, root.getChildCount());
        String missing = HookStatus.missing(FAMILY).toString();
        assertTrue(missing, missing.contains("getAdapter"));
    }

    /** A page frame with no pager above it is left as it is. */
    @Test
    public void aFrameWithoutAKnownPagerIsLeftAlone() {
        column.removeView(pager);
        FrameLayout plain = new FrameLayout(activity);
        column.addView(plain, new LinearLayout.LayoutParams(WIDTH, HEIGHT));
        page(plain);
        shadowOf(Looper.getMainLooper()).idle();
        Settings.CAROUSEL_ARROWS.save(true);

        CarouselArrows.watch(activity);
        assertFalse(frame());

        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());
    }

    private CarouselArrows.Pill pill() {
        return CarouselArrows.pillForTests(pager);
    }

    private void pillOn() {
        Settings.CAROUSEL_DOTS_PILL.save(true);
        CarouselArrows.watch(activity);
        assertFalse("no frame is held back for the pill", frame());
    }

    /** #419: the page dots move onto a round pill over the bottom middle of the carousel, and Instagram's own fade out. */
    @Test
    public void thePillShowsThePageOverTheCarouselsBottomAndInstagramsDotsFade() {
        pillOn();
        settle();

        assertEquals(1, CarouselArrows.withArrowsForTests());
        assertNull("the arrows' switch is off", back());
        assertEquals("the pill sits in the post's frame layout", 2, root.getChildCount());
        assertTrue(pill().getParent() == root);
        assertTrue(shows(pill()));
        assertEquals(0, pill().at);
        assertEquals(3, pill().count);
        assertFalse(pill().isClickable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, pill().getImportantForAccessibility());

        float d = density();
        int wide = CarouselArrows.Pill.widest(d);
        assertEquals(wide, pill().getWidth());
        assertEquals(Math.round(CarouselArrows.PILL_HEIGHT * d), pill().getHeight());
        assertEquals(Math.round(WIDTH / 2f - wide / 2f), pill().getLeft() + pill().getTranslationX(), 0f);
        assertEquals(Math.round(HEADER + HEIGHT - (CarouselArrows.PILL_MARGIN + CarouselArrows.PILL_HEIGHT) * d),
                pill().getTop() + pill().getTranslationY(), 0f);
        assertEquals(0f, instagramDots.getAlpha(), 0f);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + CarouselArrows.COUNT_PILLS + " 1"));
        assertFalse(report, report.contains(CarouselArrows.COUNT_SHOWN));
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());

        assertFalse("nor on the next frame", frame());
    }

    @Test
    public void thePillFollowsTheCarouselsPage() {
        pillOn();

        pager.at = 2;
        assertFalse(frame());
        assertEquals(2, pill().at);

        pager.at = 1;
        assertFalse(frame());
        assertEquals(1, pill().at);
        assertFalse(frame());
    }

    @Test
    public void aCarouselOfOnePageShowsNoPillAndKeepsItsDots() {
        pager.pages = pages(1);

        Settings.CAROUSEL_DOTS_PILL.save(true);
        CarouselArrows.watch(activity);
        frame();

        assertNotNull(pill());
        assertFalse(shows(pill()));
        assertEquals(1f, instagramDots.getAlpha(), 0f);
    }

    /** Instagram setting its dots back while the pill shows only lasts until the next frame. */
    @Test
    public void dotsInstagramShowsAgainFadeOnTheNextFrame() {
        pillOn();

        instagramDots.setAlpha(1f);

        assertFalse(frame());
        assertEquals(0f, instagramDots.getAlpha(), 0f);
    }

    /** Dots further under the carousel than a post's own button row belong to another post. */
    @Test
    public void dotsFarUnderTheCarouselAreLeftAlone() {
        footer.getLayoutParams().height = 600;
        ((FrameLayout.LayoutParams) instagramDots.getLayoutParams()).gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        footer.requestLayout();
        shadowOf(Looper.getMainLooper()).idle();

        pillOn();

        assertTrue(shows(pill()));
        assertEquals(1f, instagramDots.getAlpha(), 0f);
    }

    @Test
    public void offTakesThePillAwayAndBringsInstagramsDotsBack() {
        pillOn();
        assertEquals(0f, instagramDots.getAlpha(), 0f);

        Settings.CAROUSEL_DOTS_PILL.save(false);
        frame();

        assertFalse(CarouselArrows.listeningForTests(activity));
        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertEquals(1, root.getChildCount());
        assertEquals(1f, instagramDots.getAlpha(), 0f);
    }

    @Test
    public void aCarouselThatLeavesTheWindowGivesItsDotsBack() {
        pillOn();

        root.removeView(column);
        frame();

        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertEquals(0, root.getChildCount());
        assertEquals(1f, instagramDots.getAlpha(), 0f);
    }

    @Test
    public void bothSwitchesGiveACarouselItsArrowsAndAPill() {
        Settings.CAROUSEL_ARROWS.save(true);
        pillOn();

        assertEquals(4, root.getChildCount());
        assertTrue(shows(next()) && shows(pill()));
        assertFalse(shows(back()));
    }

    /** Turning the pill on under carousels already holding arrows swaps in a pill and arrows, with no resume. */
    @Test
    public void thePillFlippedOnUnderArrowsJoinsThemWithoutAResume() {
        on();
        settle();

        Settings.CAROUSEL_DOTS_PILL.save(true);
        CarouselArrows.switchChanged(activity);
        for (int i = 0; i < 3; i++) settle();

        assertNotNull(pill());
        assertTrue(shows(pill()) && shows(next()));
        assertEquals(4, root.getChildCount());
        assertEquals(0f, instagramDots.getAlpha(), 0f);

        Settings.CAROUSEL_ARROWS.save(false);
        for (int i = 0; i < 3; i++) settle();

        assertNull(back());
        assertTrue(shows(pill()));
        assertEquals(2, root.getChildCount());
    }

    /** Past seven pages the dots slide with the page, kept in the middle until an end is reached. */
    @Test
    public void aLongCarouselsDotsSlideWithItsPage() {
        assertEquals(0, CarouselArrows.Pill.first(1, 3));
        assertEquals(0, CarouselArrows.Pill.first(6, 7));
        assertEquals(0, CarouselArrows.Pill.first(2, 10));
        assertEquals(2, CarouselArrows.Pill.first(5, 10));
        assertEquals(3, CarouselArrows.Pill.first(9, 10));
        assertEquals(13, CarouselArrows.Pill.first(19, 20));
    }

    /** Instagram's page dots as 453 draws them: a CirclePageIndicator with no id. */
    public static class CirclePageIndicator extends View {
        public CirclePageIndicator(Context context) {
            super(context);
        }
    }

    /** #419 on a phone: 453's feed row holds the dots with no id, so they fade by their class, and come back. */
    @Test
    public void dotsWithNoIdFadeByTheirClass() {
        footer.removeView(instagramDots);
        View dots = new CirclePageIndicator(activity);
        footer.addView(dots, new FrameLayout.LayoutParams(60, 12, Gravity.CENTER));
        shadowOf(Looper.getMainLooper()).idle();

        pillOn();
        settle();

        assertTrue(shows(pill()));
        assertEquals(0f, dots.getAlpha(), 0f);

        Settings.CAROUSEL_DOTS_PILL.save(false);
        frame();

        assertEquals(1f, dots.getAlpha(), 0f);
    }

    /** With the switch off, dots of that class are never touched. */
    @Test
    public void dotsWithNoIdStayWithThePillOff() {
        footer.removeView(instagramDots);
        View dots = new CirclePageIndicator(activity);
        footer.addView(dots, new FrameLayout.LayoutParams(60, 12, Gravity.CENTER));
        shadowOf(Looper.getMainLooper()).idle();

        on();
        settle();

        assertTrue(shows(next()));
        assertEquals(1f, dots.getAlpha(), 0f);
    }

    /** A build without Instagram's dots id still shows the pill, and says the dots couldn't be found. */
    @Test
    public void withoutTheDotsIdThePillShowsAndSaysSo() {
        CarouselArrows.resetForTests();
        CarouselArrows.idForTests(activity, frameId);

        pillOn();

        assertTrue(shows(pill()));
        assertEquals(1f, instagramDots.getAlpha(), 0f);
        String missing = HookStatus.missing(FAMILY).toString();
        assertTrue(missing, missing.contains(CarouselArrows.DOTS));
    }

    @Test
    public void aBuildWithoutTheFrameIdShowsNothingAndSaysSo() {
        CarouselArrows.resetForTests();
        Settings.CAROUSEL_ARROWS.save(true);

        CarouselArrows.watch(activity);
        frame();

        assertEquals(0, CarouselArrows.withArrowsForTests());
        assertFalse(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());
    }
}
