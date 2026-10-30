/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The blurred backdrop behind a reel with bars: put only behind a page that has bars, taken off
 * when the switch goes off or HushGram pauses, and its bitmaps recycled when the page goes. The
 * stand-in video has no surface, so the frame comes from a test source; how a real TextureView
 * answers needs a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelBlurBarsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final int VIDEO_HEIGHT = 608;

    private ActivityController<Activity> controller;
    private Activity activity;
    private WindowPager pager;
    private FrameLayout list;
    private FrameLayout page;
    private TextureView video;
    private final ColorDrawable black = new ColorDrawable(Color.BLACK);
    private final List<Bitmap> frames = new ArrayList<>();
    private int asked;

    @Before
    public void open() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.BLUR_REEL_BARS.save(true);
        HookStatus.clear();
        ReelBlurBars.reset();
        // A window with no system bars to paint under, whatever Robolectric reports; the bands tests give it some.
        ReelBlurBars.barInsets = view -> null;
        ReelBlurBars.source = (view, reuse, width, height) -> {
            asked++;
            Bitmap bitmap = reuse != null && reuse.getWidth() == width && reuse.getHeight() == height
                    ? reuse : Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(200, 80, 40));
            if (!frames.contains(bitmap)) frames.add(bitmap);
            return bitmap;
        };
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        pager = new WindowPager(activity);
        list = new FrameLayout(activity);
        page = pageWith(VIDEO_HEIGHT);
        pager.addView(list, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        list.addView(page, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        activity.setContentView(pager, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layout();
    }

    @After
    public void close() throws Exception {
        ReelBlurBars.reset();
        controller.close();
        Settings.BLUR_REEL_BARS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A pager whose window visibility a test sets, since a stopped activity's window can't be modelled directly. */
    private static final class WindowPager extends FrameLayout {
        int windowVisibility = View.VISIBLE;

        WindowPager(Activity activity) {
            super(activity);
        }

        @Override
        public int getWindowVisibility() {
            return windowVisibility;
        }
    }

    /** What the framework does before it draws a frame: the window's observer asks each pre-draw listener. */
    private void preDraw() throws Exception {
        java.lang.reflect.Method dispatch = android.view.ViewTreeObserver.class.getDeclaredMethod("dispatchOnPreDraw");
        dispatch.setAccessible(true);
        dispatch.invoke(pager.getViewTreeObserver());
    }

    /** What the framework does when the window gains or loses focus. */
    private void windowFocus(boolean hasFocus) throws Exception {
        java.lang.reflect.Method dispatch = android.view.ViewTreeObserver.class.getDeclaredMethod("dispatchOnWindowFocusChange", boolean.class);
        dispatch.setAccessible(true);
        dispatch.invoke(pager.getViewTreeObserver(), hasFocus);
    }

    /** A page that is a full-size container with a black background and a video of [videoHeight] in the middle. */
    private FrameLayout pageWith(int videoHeight) {
        FrameLayout container = new FrameLayout(activity);
        container.setBackground(black);
        video = new TextureView(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(WIDTH, videoHeight);
        params.topMargin = (HEIGHT - videoHeight) / 2;
        container.addView(video, params);
        return container;
    }

    private void layout() {
        layout(activity);
    }

    private static void layout(Activity activity) {
        ShadowLooper.idleMainLooper();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, WIDTH, HEIGHT);
    }

    private static void advance(long millis) {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS);
    }

    private String report() {
        return HookStatus.report().toString();
    }

    private boolean backdropShows() {
        return page.getBackground() instanceof ReelBlurBars.BackdropDrawable;
    }

    private static final int BAR = 0x7f0b0197;
    private final ColorDrawable barBlack = new ColorDrawable(Color.BLACK);

    /** Instagram's own tab bar under the viewer, as on Reels with the glass off, black as Reels makes it. */
    private View tabBar() {
        ReelBlurBars.tabBarId = BAR;
        View bar = new View(activity);
        bar.setId(BAR);
        bar.setBackground(barBlack);
        ((ViewGroup) pager.getParent()).addView(bar, new FrameLayout.LayoutParams(WIDTH, 150, Gravity.BOTTOM));
        layout();
        return bar;
    }

    private static android.graphics.Canvas canvas() {
        return new android.graphics.Canvas(Bitmap.createBitmap(WIDTH, 150, Bitmap.Config.ARGB_8888));
    }

    /** Swaps the page for one whose video fills it, as most reels do. */
    private void fullScreenPage() {
        list.removeView(page);
        page = pageWith(HEIGHT);
        list.addView(page, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layout();
    }


    @Test
    public void aReelWithBarsGetsABlurredBackdropBehindIt() {
        ReelBlurBars.pager(pager);
        advance(100);
        assertFalse("nothing before the scroll has settled", backdropShows());

        advance(300);

        assertTrue(backdropShows());
        assertEquals(1, ReelBlurBars.backdropCount());
        assertNotNull(((ReelBlurBars.BackdropDrawable) page.getBackground()).bitmap());
        assertTrue(report(), report().contains(ReelBlurBars.DRAWN));
        assertTrue(HookStatus.missing(FamilyNames.REEL_BLUR_BARS).toString(), HookStatus.missing(FamilyNames.REEL_BLUR_BARS).isEmpty());
    }

    /**
     * #197: the viewer stops above the tab bar, so most reels, which fill their page, still had a black
     * strip under them. The bar's background becomes the reel running on: the frame mirrored, so the
     * strip's top edge shows the reel's bottom (red here) and not its top (blue).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aFullScreenReelRunsOnBlurredIntoTheStripUnderInstagramsTabBar() {
        ReelBlurBars.source = (view, reuse, width, height) -> {
            asked++;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLUE);
            for (int y = height / 2; y < height; y++) for (int x = 0; x < width; x++) bitmap.setPixel(x, y, Color.RED);
            return bitmap;
        };
        fullScreenPage();
        View bar = tabBar();

        ReelBlurBars.pager(pager);
        advance(400);

        assertTrue(String.valueOf(bar.getBackground()), bar.getBackground() instanceof ReelBlurBars.StripDrawable);
        assertSame("the page has no bars of its own to fill", black, page.getBackground());
        assertEquals("one copy for the strip", 1, asked);
        assertTrue(report(), report().contains(ReelBlurBars.STRIP));
        Bitmap drawn = Bitmap.createBitmap(WIDTH, 150, Bitmap.Config.ARGB_8888);
        Drawable background = bar.getBackground();
        background.setBounds(0, 0, WIDTH, 150);
        background.draw(new android.graphics.Canvas(drawn));
        int top = drawn.getPixel(WIDTH / 2, 0);
        assertTrue("the reel's bottom meets the strip's top: " + Integer.toHexString(top),
                Color.red(top) > 60 && Color.blue(top) < 20);
        assertTrue("dimmed like the bars: " + Integer.toHexString(top), Color.red(top) < 200);
    }

    private static final int STATUS = 96;
    private static final int GESTURE = 42;
    private final ColorDrawable windowGreen = new ColorDrawable(Color.GREEN);

    /** A reel whose top half is blue and bottom half red, so a test can tell which end shows where. */
    private void twoToneReel() {
        ReelBlurBars.source = (view, reuse, width, height) -> {
            asked++;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLUE);
            for (int y = height / 2; y < height; y++) for (int x = 0; x < width; x++) bitmap.setPixel(x, y, Color.RED);
            return bitmap;
        };
    }

    /**
     * A phone's system bars round Reels: the viewer starts under a status bar of [STATUS], the tab bar
     * sits on a gesture strip of [GESTURE], and the window's own background is green so whatever paints
     * over it shows.
     */
    private View underSystemBars() {
        ReelBlurBars.barInsets = view -> new Rect(0, STATUS, 0, GESTURE);
        activity.getWindow().setBackgroundDrawable(windowGreen);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) pager.getLayoutParams();
        params.topMargin = STATUS;
        pager.setLayoutParams(params);
        View bar = tabBar();
        FrameLayout.LayoutParams barParams = (FrameLayout.LayoutParams) bar.getLayoutParams();
        barParams.bottomMargin = GESTURE;
        bar.setLayoutParams(barParams);
        layout();
        return bar;
    }

    /**
     * What paints under the views across the whole window: the window root's background, then the
     * background of the view holding the bands where that is another view. That view spans the window,
     * so its background is drawn to the window's edges, as the root's is: Robolectric lays the window
     * out at its own small screen size once the looper runs, whatever the test measured.
     */
    private Bitmap windowBackground() {
        Bitmap drawn = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(drawn);
        View decor = activity.getWindow().getDecorView();
        Drawable background = decor.getBackground();
        background.setBounds(0, 0, WIDTH, HEIGHT);
        background.draw(canvas);
        View host = ReelBlurBars.bandsHost();
        if (host != null && host != decor) {
            int[] at = new int[2];
            host.getLocationInWindow(at);
            canvas.translate(at[0], at[1]);
            host.getBackground().setBounds(0, 0, WIDTH - at[0], HEIGHT - at[1]);
            host.getBackground().draw(canvas);
        }
        return drawn;
    }

    /** Each of [view]'s parents up to its window root, with the background it has now. */
    private static Map<View, Drawable> parentBackgrounds(View view) {
        Map<View, Drawable> backgrounds = new LinkedHashMap<>();
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            backgrounds.put((View) parent, ((View) parent).getBackground());
        }
        return backgrounds;
    }

    private static void assertBackgroundsBack(Map<View, Drawable> before) {
        for (Map.Entry<View, Drawable> parent : before.entrySet()) {
            assertSame("its own background back: " + parent.getKey(), parent.getValue(), parent.getKey().getBackground());
        }
    }

    /** What the framework does after a layout pass: the window's observer tells each global layout listener. */
    private void globalLayout() throws Exception {
        java.lang.reflect.Method dispatch = android.view.ViewTreeObserver.class.getDeclaredMethod("dispatchOnGlobalLayout");
        dispatch.setAccessible(true);
        dispatch.invoke(pager.getViewTreeObserver());
    }

    /**
     * #258: on Reels the status bar's band above the viewer and the gesture strip under the tab bar
     * showed the window's black background. That background paints the reel there now: its top (blue)
     * mirrored up under the clock and dimmed more so the clock stays readable, and its bottom (red)
     * running on under the bar, while the rest of the window keeps its own background.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theBandsUnderTheSystemBarsShowTheReelAndTheRestKeepsTheWindowsBackground() {
        twoToneReel();
        fullScreenPage();
        View bar = underSystemBars();

        ReelBlurBars.pager(pager);
        advance(400);

        assertNotNull(ReelBlurBars.bandsBackground());
        assertTrue(String.valueOf(bar.getBackground()), bar.getBackground() instanceof ReelBlurBars.StripDrawable);
        assertEquals("one copy for the strip and the bands", 1, asked);
        assertTrue(report(), report().contains(ReelBlurBars.BANDS));
        Bitmap drawn = windowBackground();
        int status = drawn.getPixel(WIDTH / 2, STATUS / 2);
        assertTrue("the reel's top under the clock: " + Integer.toHexString(status),
                Color.blue(status) > 60 && Color.red(status) < 20);
        assertTrue("dimmed more than the strip: " + Integer.toHexString(status),
                Color.blue(status) < 255 - ReelBlurBars.DIM_ALPHA);
        int gesture = drawn.getPixel(WIDTH / 2, HEIGHT - GESTURE / 2);
        assertTrue("the strip running on under the bar: " + Integer.toHexString(gesture),
                Color.red(gesture) > 60 && Color.blue(gesture) < 20);
        assertEquals("the window's own background between them", Color.GREEN, drawn.getPixel(WIDTH / 2, HEIGHT / 2));
    }

    /**
     * #258: Instagram's Reels asks for dark status bar icons in light mode, and dark icons on a darkened
     * band were hard to read. The band goes lighter under dark icons, while the gesture strip keeps the
     * strip's dimming.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theBandUnderDarkStatusIconsIsLightened() {
        ReelBlurBars.darkStatusIcons = view -> true;
        twoToneReel();
        fullScreenPage();
        underSystemBars();

        ReelBlurBars.pager(pager);
        advance(400);

        Bitmap drawn = windowBackground();
        int status = drawn.getPixel(WIDTH / 2, STATUS / 2);
        assertTrue("white over the reel's top: " + Integer.toHexString(status),
                Color.red(status) > ReelBlurBars.STATUS_DIM_ALPHA - 20 && Color.green(status) > ReelBlurBars.STATUS_DIM_ALPHA - 20);
        assertTrue("still the reel's top (blue) under it: " + Integer.toHexString(status),
                Color.blue(status) > Color.red(status) + 40);
        int gesture = drawn.getPixel(WIDTH / 2, HEIGHT - GESTURE / 2);
        assertTrue("the gesture strip stays dimmed: " + Integer.toHexString(gesture),
                Color.red(gesture) > 60 && Color.red(gesture) < 255 - ReelBlurBars.DIM_ALPHA + 20 && Color.green(gesture) < 20);
    }

    /**
     * The window root hands out its own inset round the background it was given, and wraps whatever it
     * gets back again, so a root holding one gets no bands. A plain layer the app set is no wrapper.
     */
    @Test
    public void theWindowRootsOwnWrapperIsLeftAlone() {
        assertTrue(ReelBlurBars.rootWrapped(new InsetDrawable(new ColorDrawable(Color.GREEN), 0, 0, 0, 48)));
        assertFalse(ReelBlurBars.rootWrapped(new ColorDrawable(Color.GREEN)));
        assertFalse(ReelBlurBars.rootWrapped(new LayerDrawable(new Drawable[] {new ColorDrawable(Color.GREEN), new ColorDrawable(Color.BLACK)})));
        assertFalse(ReelBlurBars.rootWrapped(null));
    }

    /** With nothing under it, the bands' background says it's translucent, since the bands alone leave the rest uncovered. */
    @Test
    @SuppressWarnings("deprecation")
    public void theBandsWithNothingUnderThemAreTranslucent() {
        ReelBlurBars.BandsDrawable bands = new ReelBlurBars.BandsDrawable();
        assertEquals(PixelFormat.TRANSLUCENT, bands.getOpacity());
        bands.original = new ColorDrawable(Color.BLACK);
        assertEquals(PixelFormat.OPAQUE, bands.getOpacity());
    }

    /** Leaving Reels gives the window its own background back with the next layout, and a pager out of sight never paints the bands. */
    @Test
    public void leavingReelsGivesTheWindowItsOwnBackgroundBack() throws Exception {
        fullScreenPage();
        underSystemBars();
        Map<View, Drawable> before = parentBackgrounds(pager);
        android.graphics.Canvas window = new android.graphics.Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888));

        ReelBlurBars.pager(pager);
        advance(400);
        assertNotNull(ReelBlurBars.bandsBackground());
        assertTrue(ReelBlurBars.drawBands(window, 0, 0, WIDTH, HEIGHT));

        pager.setVisibility(View.GONE);
        assertFalse("not even until the layout", ReelBlurBars.drawBands(window, 0, 0, WIDTH, HEIGHT));
        globalLayout();

        assertBackgroundsBack(before);
        assertNull(ReelBlurBars.bandsBackground());
        assertNull(ReelBlurBars.bandsHost());
        assertNull(ReelBlurBars.stripFrame());
    }

    /**
     * The window root wraps its background in an inset or a layer when its insets or blur change, and
     * the view holding the bands can be it. The bands inside that wrapper are still the bands: kept as
     * the background they cover, they would draw themselves until the stack ran out.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theBandsInsideAWrapperTheWindowRootMadeAreStillTheBands() throws Exception {
        twoToneReel();
        fullScreenPage();
        underSystemBars();
        Map<View, Drawable> before = parentBackgrounds(pager);
        ReelBlurBars.pager(pager);
        advance(400);
        View host = ReelBlurBars.bandsHost();
        assertNotNull(host);

        host.setBackground(new InsetDrawable(host.getBackground(), 0));
        layout();
        advance(1100);

        assertSame(host, ReelBlurBars.bandsHost());
        assertTrue("the wrapper stays: " + host.getBackground(), host.getBackground() instanceof InsetDrawable);
        assertTrue(ReelBlurBars.holdsBands(host.getBackground()));
        Bitmap drawn = windowBackground();
        int status = drawn.getPixel(WIDTH / 2, STATUS / 2);
        assertTrue("the reel's top under the clock: " + Integer.toHexString(status),
                Color.blue(status) > 60 && Color.red(status) < 20);
        assertEquals(Color.GREEN, drawn.getPixel(WIDTH / 2, HEIGHT / 2));

        pager.setVisibility(View.GONE);
        globalLayout();
        assertBackgroundsBack(before);
    }

    /**
     * The bands go on the nearest parent spanning the window across both of them. Instagram's
     * coordinator ends above the gesture band, so it holds them only with no band down there, and the
     * window root holds them when no parent spans them.
     */
    @Test
    public void theBandsGoOnTheNearestParentSpanningThemOrTheWindowRoot() {
        FrameLayout root = new FrameLayout(activity);
        FrameLayout swipe = new FrameLayout(activity);
        FrameLayout coordinator = new FrameLayout(activity);
        FrameLayout viewer = new FrameLayout(activity);
        FrameLayout.LayoutParams viewerParams = new FrameLayout.LayoutParams(WIDTH, HEIGHT - STATUS - GESTURE);
        viewerParams.topMargin = STATUS;
        coordinator.addView(viewer, viewerParams);
        swipe.addView(coordinator, new FrameLayout.LayoutParams(WIDTH, HEIGHT - GESTURE));
        root.addView(swipe, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layOut(root);

        assertSame(swipe, ReelBlurBars.bandsHost(viewer, STATUS, HEIGHT - GESTURE));
        assertSame("no band under it", coordinator, ReelBlurBars.bandsHost(viewer, STATUS, HEIGHT));

        swipe.setLayoutParams(new FrameLayout.LayoutParams(WIDTH / 2, HEIGHT));
        layOut(root);
        assertSame(root, ReelBlurBars.bandsHost(viewer, STATUS, HEIGHT - GESTURE));
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, HEIGHT);
    }

    /**
     * #258 on a phone: Instagram's swipe container fills the window in black between the window root
     * and the viewer, so bands painted by the window root's background stayed under that black. The
     * container's background paints them now, over its black, and the window root's is left alone.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theBandsGoOnTheBlackContainerThatFillsTheWindowOverTheWindowRoot() throws Exception {
        twoToneReel();
        fullScreenPage();
        View bar = underSystemBars();
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        Drawable rootBefore = decor.getBackground();
        ColorDrawable swipeBlack = new ColorDrawable(Color.BLACK);
        FrameLayout swipe = new FrameLayout(activity);
        swipe.setBackground(swipeBlack);
        ViewGroup content = (ViewGroup) pager.getParent();
        content.removeView(pager);
        content.removeView(bar);
        swipe.addView(pager, pager.getLayoutParams());
        swipe.addView(bar, bar.getLayoutParams());
        decor.addView(swipe, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layout();

        ReelBlurBars.pager(pager);
        advance(400);

        assertSame(swipe, ReelBlurBars.bandsHost());
        assertSame("the window root's own background", rootBefore, decor.getBackground());
        Bitmap drawn = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        swipe.getBackground().setBounds(0, 0, WIDTH, HEIGHT);
        swipe.getBackground().draw(new android.graphics.Canvas(drawn));
        int status = drawn.getPixel(WIDTH / 2, STATUS / 2);
        assertTrue("the reel's top under the clock: " + Integer.toHexString(status),
                Color.blue(status) > 60 && Color.red(status) < 20);
        int gesture = drawn.getPixel(WIDTH / 2, HEIGHT - GESTURE / 2);
        assertTrue("the strip running on under the bar: " + Integer.toHexString(gesture),
                Color.red(gesture) > 60 && Color.blue(gesture) < 20);
        assertEquals("the container's black between them", Color.BLACK, drawn.getPixel(WIDTH / 2, HEIGHT / 2));

        pager.setVisibility(View.GONE);
        globalLayout();
        assertSame(swipeBlack, swipe.getBackground());
        assertNull(ReelBlurBars.bandsHost());
    }

    /**
     * A pager turning invisible lays nothing out, so the window root's background kept the bands over
     * the next screen until the loop looked again. While they show the window's frames are watched,
     * and the first one after the pager hides takes them away.
     */
    @Test
    public void theBandsGoWithTheFirstFrameAfterThePagerTurnsInvisible() throws Exception {
        fullScreenPage();
        underSystemBars();
        Map<View, Drawable> before = parentBackgrounds(pager);
        ReelBlurBars.pager(pager);
        advance(400);
        assertNotNull(ReelBlurBars.bandsBackground());
        assertEquals("watched while the bands show", 1, ReelBlurBars.drawListenerCount());

        pager.setVisibility(View.INVISIBLE);
        preDraw();

        assertBackgroundsBack(before);
        assertNull(ReelBlurBars.bandsBackground());
        advance(1100);
        assertEquals("asleep: watched for coming back", 1, ReelBlurBars.drawListenerCount());

        pager.setVisibility(View.VISIBLE);
        preDraw();
        advance(400);
        assertNotNull("back with the pager", ReelBlurBars.bandsBackground());
    }

    /**
     * Reels opening in a new window while the old one still holds the bands: the old window gets its
     * background back and the new one paints them from its first look, not a refresh later.
     */
    @Test
    public void aNewWindowTakesTheBandsOverFromItsFirstLook() {
        fullScreenPage();
        underSystemBars();
        Map<View, Drawable> firstBefore = parentBackgrounds(pager);
        ReelBlurBars.pager(pager);
        advance(400);
        assertNotNull(ReelBlurBars.bandsBackground());

        ActivityController<Activity> other = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            Activity second = other.get();
            second.getWindow().setBackgroundDrawable(new ColorDrawable(Color.GREEN));
            int height = HEIGHT - STATUS - GESTURE;
            WindowPager next = new WindowPager(second);
            FrameLayout nextList = new FrameLayout(second);
            FrameLayout nextPage = new FrameLayout(second);
            nextPage.setBackground(new ColorDrawable(Color.BLACK));
            nextPage.addView(new TextureView(second), new FrameLayout.LayoutParams(WIDTH, height));
            nextList.addView(nextPage, new FrameLayout.LayoutParams(WIDTH, height));
            next.addView(nextList, new FrameLayout.LayoutParams(WIDTH, height));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(WIDTH, height);
            params.topMargin = STATUS;
            second.setContentView(next, params);
            layout(second);
            View secondDecor = second.getWindow().getDecorView();

            ReelBlurBars.pager(next);
            advance(400);

            assertBackgroundsBack(firstBefore);
            View host = ReelBlurBars.bandsHost();
            assertNotNull(host);
            assertSame("in the new window", secondDecor, host.getRootView());
            assertTrue(String.valueOf(host.getBackground()), ReelBlurBars.holdsBands(host.getBackground()));
            android.graphics.Canvas window = new android.graphics.Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888));
            assertTrue("painted from the first look", ReelBlurBars.drawBands(window, 0, 0, WIDTH, HEIGHT));
        } finally {
            other.close();
        }
    }

    /** A floating pill has the reel running on behind it (#221): the band under the clock still shows the reel, the bottom stays the window's. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aFloatingPillGetsTheBandUnderTheClockButNotTheOneUnderIt() {
        twoToneReel();
        fullScreenPage();
        View bar = underSystemBars();
        ReelBlurBars.wearsGlass = view -> view == bar;
        ReelBlurBars.floatingGlass = view -> view == bar;

        ReelBlurBars.pager(pager);
        advance(400);

        assertSame("the pill stays the bar's background", barBlack, bar.getBackground());
        assertNotNull(ReelBlurBars.bandsBackground());
        Bitmap drawn = windowBackground();
        int status = drawn.getPixel(WIDTH / 2, STATUS / 2);
        assertTrue("the reel's top under the clock: " + Integer.toHexString(status),
                Color.blue(status) > 60 && Color.red(status) < 20);
        assertEquals(Color.GREEN, drawn.getPixel(WIDTH / 2, HEIGHT - GESTURE / 2));
    }

    /** The strip answers for the bar's layout as the background it covers did, so the bar never moves. */
    @Test
    public void theStripKeepsThePaddingAndLeastSizeOfTheBackgroundItCovers() {
        fullScreenPage();
        View bar = tabBar();
        bar.setBackground(new ColorDrawable(Color.BLACK) {
            @Override
            public boolean getPadding(Rect padding) {
                padding.set(4, 8, 12, 16);
                return true;
            }

            @Override
            public int getMinimumWidth() {
                return 70;
            }

            @Override
            public int getMinimumHeight() {
                return 90;
            }
        });

        ReelBlurBars.pager(pager);
        advance(400);

        Drawable strip = bar.getBackground();
        assertTrue(String.valueOf(strip), strip instanceof ReelBlurBars.StripDrawable);
        Rect padding = new Rect();
        assertTrue(strip.getPadding(padding));
        assertEquals(new Rect(4, 8, 12, 16), padding);
        assertEquals(70, strip.getMinimumWidth());
        assertEquals(90, strip.getMinimumHeight());
    }

    /**
     * #258: a docked glass pill, as with Show content behind the tab bar off, paints the strip round
     * itself, so it gets the copy and keeps its own background. A floating pill gets no strip.
     */
    @Test
    public void aDockedGlassPillGetsTheStripAndAFloatingOneDoesNot() {
        fullScreenPage();
        View bar = tabBar();
        ReelBlurBars.wearsGlass = view -> view == bar;
        ReelBlurBars.floatingGlass = view -> false;

        ReelBlurBars.pager(pager);
        advance(400);

        assertSame("the pill stays the bar's background", barBlack, bar.getBackground());
        assertNotNull(ReelBlurBars.stripFrame());
        assertTrue(ReelBlurBars.drawStrip(canvas(), 0, 0, WIDTH, 150));

        ReelBlurBars.reset();
        ReelBlurBars.barInsets = view -> null;
        ReelBlurBars.source = (view, reuse, width, height) -> {
            asked++;
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        };
        ReelBlurBars.tabBarId = BAR;
        ReelBlurBars.wearsGlass = view -> view == bar;
        ReelBlurBars.floatingGlass = view -> view == bar;
        asked = 0;
        ReelBlurBars.pager(pager);
        advance(1500);

        assertEquals("no copy for a floating pill", 0, asked);
        assertSame(barBlack, bar.getBackground());
        assertNull(ReelBlurBars.stripFrame());
    }

    @Test
    public void aReelWithBarsLendsTheStripItsOwnCopy() {
        View bar = tabBar();

        ReelBlurBars.pager(pager);
        advance(400);

        assertTrue(backdropShows());
        assertTrue(bar.getBackground() instanceof ReelBlurBars.StripDrawable);
        assertSame(((ReelBlurBars.BackdropDrawable) page.getBackground()).bitmap(), ReelBlurBars.stripFrame());
        assertEquals("no second copy", 1, asked);
    }

    @Test
    public void theTabBarGetsItsBlackBackWhenTheSwitchGoesOffOrHushGramPauses() {
        fullScreenPage();
        View bar = tabBar();
        ReelBlurBars.pager(pager);
        advance(400);
        Bitmap copy = ReelBlurBars.stripFrame();
        assertNotNull(copy);

        Settings.BLUR_REEL_BARS.save(false);
        assertFalse("off shows nothing from the next frame", ReelBlurBars.drawStrip(canvas(), 0, 0, WIDTH, 150));
        advance(1100);

        assertSame(barBlack, bar.getBackground());
        assertNull(ReelBlurBars.stripFrame());
        assertTrue(copy.isRecycled());

        Settings.BLUR_REEL_BARS.save(true);
        ReelBlurBars.reset();
        ReelBlurBars.source = (view, reuse, width, height) -> {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(200, 80, 40));
            return bitmap;
        };
        ReelBlurBars.tabBarId = BAR;
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(bar.getBackground() instanceof ReelBlurBars.StripDrawable);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        advance(1100);

        assertSame(barBlack, bar.getBackground());
        assertNull(ReelBlurBars.stripFrame());
    }

    /** Tapping another tab hides the viewer: the strip stops at once and the bar's black is back by the next look. */
    @Test
    public void anotherTabInFrontGetsTheBarsOwnBackground() {
        fullScreenPage();
        View bar = tabBar();
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(ReelBlurBars.drawStrip(canvas(), 0, 0, WIDTH, 150));

        pager.setVisibility(View.GONE);

        assertFalse("not even until the next look", ReelBlurBars.drawStrip(canvas(), 0, 0, WIDTH, 150));
        advance(1100);
        assertSame(barBlack, bar.getBackground());
        assertNull(ReelBlurBars.stripFrame());
    }

    @Test
    public void aHiddenTabBarCostsAFullScreenReelNothing() {
        fullScreenPage();
        View bar = tabBar();
        bar.setVisibility(View.GONE);

        ReelBlurBars.pager(pager);
        advance(1500);

        assertEquals(0, asked);
        assertSame(barBlack, bar.getBackground());
        assertNull(ReelBlurBars.stripFrame());
    }

    @Test
    public void theCopyIsSmallAndStaysOneBitmapAsItRefreshes() {
        ReelBlurBars.pager(pager);
        advance(400);
        int first = asked;
        assertTrue(first >= 1);
        assertEquals(WIDTH / ReelBlurBars.SCALE, frames.get(0).getWidth());
        assertEquals(VIDEO_HEIGHT / ReelBlurBars.SCALE, frames.get(0).getHeight());

        advance(3000);

        assertTrue("about once a second, not per frame: " + asked, asked >= first + 2 && asked <= first + 4);
        assertEquals("the same bitmap is reused", 1, frames.size());
    }

    @Test
    public void aFullScreenReelIsLeftAlone() {
        list.removeView(page);
        page = pageWith(HEIGHT);
        list.addView(page, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layout();

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());
        assertEquals(0, asked);
        assertTrue(report(), report().contains(ReelBlurBars.FULL_SCREEN));
    }

    @Test
    public void aVideoScaledSmallerByItsTransformCountsAsHavingBars() {
        list.removeView(page);
        page = pageWith(HEIGHT);
        Matrix half = new Matrix();
        half.setScale(0.5f, 0.5f, WIDTH / 2f, HEIGHT / 2f);
        video.setTransform(half);
        list.addView(page, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        layout();

        ReelBlurBars.pager(pager);
        advance(400);

        assertTrue(backdropShows());
    }

    @Test
    public void aPageOffScreenGetsNothing() {
        FrameLayout away = pageWith(VIDEO_HEIGHT);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(WIDTH, HEIGHT);
        params.topMargin = HEIGHT;
        list.addView(away, params);
        layout();

        ReelBlurBars.pager(pager);
        advance(400);

        assertTrue(backdropShows());
        assertSame("the page below the screen keeps its black", black, away.getBackground());
        assertEquals(1, ReelBlurBars.backdropCount());
    }

    @Test
    public void turningTheSwitchOffGivesBackTheBlackAndRecyclesTheCopy() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(backdropShows());
        Bitmap copy = frames.get(0);

        Settings.BLUR_REEL_BARS.save(false);
        advance(1100);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());
        assertTrue(copy.isRecycled());
    }

    @Test
    public void pausingGivesBackTheBlack() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(backdropShows());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        advance(1100);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test
    public void aPageThatDetachesRecyclesItsCopyAndGetsItsBackgroundBack() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(backdropShows());
        Bitmap copy = frames.get(0);

        list.removeView(page);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());
        assertTrue(copy.isRecycled());
    }

    @Test
    public void aPageThatBecomesFullScreenGivesTheBackdropUp() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertTrue(backdropShows());

        ViewGroup.LayoutParams params = video.getLayoutParams();
        params.height = HEIGHT;
        ((FrameLayout.LayoutParams) params).topMargin = 0;
        video.setLayoutParams(params);
        layout();
        advance(1100);

        assertSame(black, page.getBackground());
        assertEquals(0, ReelBlurBars.backdropCount());
    }

    @Test
    public void noFrameYetLeavesTheBlack() {
        ReelBlurBars.source = (view, reuse, width, height) -> null;

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        assertTrue(report(), report().contains(ReelBlurBars.NO_FRAME));
    }

    @Test
    public void aFrameOfNothingButBlackLeavesTheBlack() {
        ReelBlurBars.source = (view, reuse, width, height) -> {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
            return bitmap;
        };

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        assertTrue(report(), report().contains(ReelBlurBars.NO_FRAME));
    }

    @Test
    public void withTheSwitchOffTheHandOverWatchesNothing() {
        Settings.BLUR_REEL_BARS.save(false);

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        assertEquals(0, asked);
    }

    @Test
    public void somethingThatIsNotAPagerIsIgnored() {
        ReelBlurBars.pager(null);
        ReelBlurBars.pager(new Object());
        advance(1500);

        assertEquals(0, asked);
        assertTrue(HookStatus.missing(FamilyNames.REEL_BLUR_BARS).toString(), HookStatus.missing(FamilyNames.REEL_BLUR_BARS).isEmpty());
    }

    @Test
    public void aFailureLeavesTheBlackAndIsReported() {
        ReelBlurBars.source = (view, reuse, width, height) -> {
            throw new IllegalStateException("surface went away");
        };

        ReelBlurBars.pager(pager);
        advance(1500);

        assertSame(black, page.getBackground());
        String missing = HookStatus.missing(FamilyNames.REEL_BLUR_BARS).toString();
        assertTrue(missing, missing.contains("'" + ReelBlurBars.SCAN + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void aHandOverThatThrowsIsReported() {
        ReelBlurBars.pager(pager, () -> {
            throw new IllegalStateException("settings went away");
        });

        String missing = HookStatus.missing(FamilyNames.REEL_BLUR_BARS).toString();
        assertTrue(missing, missing.contains("'" + ReelBlurBars.HAND_OVER + "'"));
        assertEquals(0, ReelBlurBars.backdropCount());
    }

    @Test
    public void blackEdgesAreTrimmedButNeverMoreThanThreeQuartersOfASide() {
        int[] pixels = new int[10 * 8];
        java.util.Arrays.fill(pixels, Color.BLACK);
        for (int y = 2; y < 6; y++) for (int x = 1; x < 9; x++) pixels[y * 10 + x] = Color.rgb(90, 90, 90);

        Rect kept = ReelBlurBars.trim(pixels, 10, 8);

        assertEquals(new Rect(1, 2, 9, 6), kept);
        java.util.Arrays.fill(pixels, Color.BLACK);
        assertNull(ReelBlurBars.trim(pixels, 10, 8));
        java.util.Arrays.fill(pixels, Color.rgb(90, 90, 90));
        assertEquals(new Rect(0, 0, 10, 8), ReelBlurBars.trim(pixels, 10, 8));
    }

    @Test
    public void theBlurSoftensAHardEdgeAndKeepsItOpaque() {
        int[] edge = new int[8 * 4];
        for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) edge[y * 8 + x] = x < 4 ? Color.WHITE : Color.rgb(20, 20, 20);

        int[] blurred = ReelBlurBars.boxBlur(edge, 8, 4, ReelBlurBars.BLUR_RADIUS, true);

        int left = Color.red(blurred[3]);
        int right = Color.red(blurred[4]);
        assertTrue("the edge is no longer hard: " + left + " " + right, left < 255 && right > 20);
        assertTrue(left > right);
        assertEquals(0xFF, Color.alpha(blurred[0]));
        assertEquals("a flat area stays flat", 255, Color.red(blurred[0]));
    }

    @Test
    public void aDetachedPagerLeavesNothingOnTheWindow() {
        ReelBlurBars.pager(pager);
        advance(400);
        assertEquals(1, ReelBlurBars.listenerCount());

        ((ViewGroup) pager.getParent()).removeView(pager);

        assertEquals(0, ReelBlurBars.listenerCount());
        assertEquals(0, ReelBlurBars.backdropCount());
    }

    @Test
    public void aPagerThatIsNotInFrontStopsTheLoopAndAScrollWakesIt() {
        ReelBlurBars.pager(pager);
        advance(400);
        asked = 0;
        advance(0);

        pager.setVisibility(View.GONE);
        advance(1100);
        int whileHidden = ReelBlurBars.rounds;
        advance(5000);
        assertEquals("the loop does not run while hidden", whileHidden, ReelBlurBars.rounds);
        assertEquals(0, asked);

        pager.setVisibility(View.VISIBLE);
        layout();
        advance(400);
        advance(1100);
        assertTrue("it runs again once it is back in front", ReelBlurBars.rounds > whileHidden);
        assertTrue(asked > 0);
    }

    @Test
    public void aPagerIsInFrontOnlyInAVisibleWindow() {
        assertTrue(ReelBlurBars.inFront(true, true, View.VISIBLE));
        assertFalse("detached", ReelBlurBars.inFront(false, true, View.VISIBLE));
        assertFalse("hidden by a parent", ReelBlurBars.inFront(true, false, View.VISIBLE));
        assertFalse("window invisible", ReelBlurBars.inFront(true, true, View.INVISIBLE));
        assertFalse("window gone, as in a stopped activity", ReelBlurBars.inFront(true, true, View.GONE));
    }

    /** The activity stops: the pager is still shown to its parents, but its window is gone and the 1 s scan must stop. */
    @Test
    public void aStoppedWindowStopsTheLoopAndComingBackWakesIt() throws Exception {
        ReelBlurBars.pager(pager);
        advance(400);
        advance(1100);
        asked = 0;

        pager.windowVisibility = View.GONE;
        assertTrue("the parents still say shown", pager.isShown());
        advance(1100);
        int whileStopped = ReelBlurBars.rounds;
        advance(10_000);
        assertEquals("the loop does not run in the background", whileStopped, ReelBlurBars.rounds);
        assertEquals(0, asked);

        pager.windowVisibility = View.VISIBLE;
        windowFocus(true);
        advance(400);
        advance(1100);
        assertTrue("it runs again once the window is back", ReelBlurBars.rounds > whileStopped);
        assertTrue(asked > 0);
    }

    /** INVISIBLE to VISIBLE invalidates but lays nothing out, so the next draw is what wakes the loop. */
    @Test
    public void aPagerThatTurnsVisibleAgainWakesWithoutALayout() throws Exception {
        ReelBlurBars.pager(pager);
        advance(400);
        advance(1100);

        pager.setVisibility(View.INVISIBLE);
        advance(1100);
        int whileInvisible = ReelBlurBars.rounds;
        advance(5000);
        assertEquals("the loop stopped", whileInvisible, ReelBlurBars.rounds);

        pager.setVisibility(View.VISIBLE);
        preDraw();
        advance(400);
        advance(1100);
        assertTrue("the next draw woke it", ReelBlurBars.rounds > whileInvisible);
    }

    /** Draws while the pager is still out of front change nothing, and a frame at a time doesn't push the first look back. */
    @Test
    public void drawsWhileNotInFrontLeaveItAsleepAndDrawsAfterwardsDoNotDelayIt() throws Exception {
        ReelBlurBars.pager(pager);
        advance(400);
        advance(1100);
        pager.windowVisibility = View.GONE;
        advance(1100);
        int asleepAt = ReelBlurBars.rounds;

        preDraw();
        advance(1500);
        assertEquals("a draw in a stopped window wakes nothing", asleepAt, ReelBlurBars.rounds);

        pager.windowVisibility = View.VISIBLE;
        for (int frame = 0; frame < 40; frame++) {
            preDraw();
            advance(16);
        }
        assertTrue("one look happened while frames kept coming", ReelBlurBars.rounds > asleepAt);
    }

    /** An awake loop has no use for the window's pre-draw callbacks, so a pager in front adds none to every frame. */
    @Test
    public void thePreDrawListenerIsOnlyOnWhileTheLoopIsAsleep() throws Exception {
        ReelBlurBars.pager(pager);
        advance(400);
        advance(1100);
        assertEquals("awake: none", 0, ReelBlurBars.drawListenerCount());

        pager.setVisibility(View.INVISIBLE);
        advance(1100);
        assertEquals("asleep: one", 1, ReelBlurBars.drawListenerCount());

        pager.setVisibility(View.VISIBLE);
        preDraw();
        assertEquals("woken: gone again", 0, ReelBlurBars.drawListenerCount());
        advance(400);
        advance(1100);
        assertEquals(0, ReelBlurBars.drawListenerCount());

        pager.setVisibility(View.INVISIBLE);
        advance(1100);
        assertEquals(1, ReelBlurBars.drawListenerCount());
        ((ViewGroup) pager.getParent()).removeView(pager);
        assertEquals("detached: nothing left on the window", 0, ReelBlurBars.drawListenerCount());
    }
}
