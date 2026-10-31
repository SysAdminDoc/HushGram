/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Blur the bars around Reels" patch.
 *
 * <p>A reel that isn't as tall as the screen leaves bars above and below it, or beside it, and
 * Instagram paints them black. While the switch is on, a small blurred copy of the playing video is
 * painted there instead.
 *
 * <p>The Reels viewer is a ViewPager2 and the patch hands it over once, right after the viewer stores
 * it. Every page's video is a {@link TextureView}, so its picture can be read back cheaply. This class
 * looks at the pager only when a scroll has settled and about once a second after that, never per frame:
 * for the page on screen it works out where the video sits, and when that leaves bars it copies the
 * frame at one sixteenth of the video's size, blurs the few thousand pixels on the CPU and paints the
 * result, stretched to cover, as the background of the page's own container, which is behind the
 * video. The blur is done in code on that tiny copy, so it looks the same on every Android version and
 * needs neither RenderEffect (Android 12 and up) nor a second path for Android 9 to 11.
 *
 * <p>Each visible page holds one tiny bitmap, and a page that scrolls away, detaches, turns out to be
 * full screen, or finds the switch off or HushGram paused gets its own background back and its
 * bitmaps recycled. The pager and the pages are held weakly, so nothing outlives the viewer. Every
 * failure leaves Instagram's own black.
 *
 * <p>The viewer stops above Instagram's own tab bar, which leaves a black strip under the reel (#197).
 * While that bar shows, the reel on screen is copied the same way whether it has bars or not, and the
 * strip shows it mirrored and blurred, so it reads as the reel running on ({@link #drawStrip}). A
 * docked glass pill paints that strip round itself (#258). A floating one has the reel itself running
 * on behind it (#221), so it gets no strip.
 *
 * <p>Instagram draws nothing in the status bar's band above the viewer or in the gesture bar's band
 * under the tab bar, so the black behind the viewer showed there (#258). While a reel is in front, the
 * background that black comes from also paints the same copy into those two bands ({@link #drawBands}):
 * the reel's top mirrored up under the clock, darker under white icons and lighter under dark ones so
 * the clock stays readable, and the strip running on under the tab bar. That background is the one of the viewer's nearest parent that
 * spans the window across both bands ({@link #bandsHost}): Instagram's swipe container, which fills the
 * window in black over the window root's own. It sits under the viewer and everything opened over it,
 * so a sheet or another screen covers it as it covered the black.
 */
public final class ReelBlurBars {
    /** The steps a failure is reported under. */
    static final String SCAN = "scan the pager";
    static final String HAND_OVER = "hand over the pager";

    /** Counted each time a backdrop was drawn or refreshed behind a reel with bars. */
    static final String DRAWN = "drew a blurred backdrop behind a reel with bars";
    /** Counted each time the page on screen had no bars, so it was left as it was. */
    static final String FULL_SCREEN = "left a full-screen reel as it was";
    /** Counted each time the video had no frame to copy yet, or only black. */
    static final String NO_FRAME = "no frame to copy yet";
    /** Counted each time a video had no container around it that spans the page. */
    static final String NO_CONTAINER = "found no container for the video";
    /** Counted each time the strip under the tab bar got a frame of the reel on screen. */
    static final String STRIP = "showed the reel under the tab bar";
    /** The step a failure painting that strip is reported under. */
    static final String DRAW_STRIP = "draw the strip under the tab bar";
    /** Counted each time the bands under the system bars got a frame of the reel on screen. */
    static final String BANDS = "showed the reel under the system bars";
    /** The step a failure painting those bands is reported under. */
    static final String DRAW_BANDS = "draw the reel under the system bars";
    /** The step a failure taking the strip and the bands away is reported under. */
    static final String DROP_STRIP = "take the reel away from the bars";

    /** Instagram's tab bar, whose strip under a reel {@link #drawStrip} fills. */
    static final String TAB_BAR = "tab_bar";
    /** The tab bar's id, looked up once, or -1 before that. Tests set their own, since a test app has none of Instagram's. */
    static volatile int tabBarId = -1;

    /** How long after the last scroll movement the page is looked at. */
    static final long SETTLE_MILLIS = 250;
    /** How long between looks while the viewer is on screen. */
    static final long REFRESH_MILLIS = 1000;
    /** The frame is copied at 1 over this much of the video's size. */
    static final int SCALE = 16;
    /** How much of a page a video has to leave empty, as a share of the page, to count as bars. */
    static final float BAR_SHARE = 0.04f;
    /** The most views looked at in one pass, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = 4000;
    /** A pixel whose colour parts are all at or under this counts as black when trimming. */
    static final int BLACK = 16;
    /** How far the blur reaches in the tiny copy, and how many times it runs. */
    static final int BLUR_RADIUS = 2;
    static final int BLUR_PASSES = 2;
    /** How much black goes over the blurred copy, so the video stays the brightest thing. */
    static final int DIM_ALPHA = 110;
    /**
     * How much goes over it under the status bar, where the clock and icons sit on it: black under
     * light icons, white under dark ones.
     */
    static final int STATUS_DIM_ALPHA = 150;

    /** Reads a video's current frame. Swapped in tests, since a stand-in video has no surface. */
    interface FrameSource {
        /** The video's frame at [width] by [height], reusing [reuse] when it's the same size, or null when there is none. */
        @Nullable
        Bitmap frame(TextureView video, @Nullable Bitmap reuse, int width, int height);
    }

    static final FrameSource TEXTURE = (video, reuse, width, height) -> {
        if (reuse != null && !reuse.isRecycled() && reuse.isMutable() && reuse.getWidth() == width && reuse.getHeight() == height) {
            return video.getBitmap(reuse);
        }
        return video.getBitmap(width, height);
    };

    static volatile FrameSource source = TEXTURE;

    /** Whether a bar wears the glass, and whether that glass floats. Swapped in tests, since a test bar has none. */
    static volatile Predicate<View> wearsGlass = GlassTabBar::wearsGlass;
    static volatile Predicate<View> floatingGlass = GlassTabBar::wearsFloatingGlass;

    /** The system bars' insets on [decor]'s window, or null with none known. Swapped in tests, whose window has none. */
    static volatile Function<View, Rect> barInsets = ReelBlurBars::systemBars;

    /** Whether [view]'s window asks for dark status bar icons. Swapped in tests, whose window asks for neither. */
    static volatile Predicate<View> darkStatusIcons = ReelBlurBars::asksForDarkStatusIcons;

    /** How many times a pager's loop has run, so tests can see it stop. */
    static volatile int rounds;

    /** The controller for each pager handed over, held weakly so a closed viewer's can go. */
    private static final Map<Object, Controller> CONTROLLERS = new WeakHashMap<>();

    private static volatile boolean logged;

    /**
     * The controller whose reel the strip under the tab bar shows, and the blurred frame it shows, or
     * null while there is none. Only touched on the main thread.
     */
    @Nullable private static Controller stripOwner;
    @Nullable private static Bitmap stripFrame;
    private static final Paint STRIP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint STRIP_DIM = new Paint();
    private static final Paint STATUS_DIM = new Paint();
    private static final Paint STATUS_LIGHT = new Paint();

    /**
     * The bands under the system bars, in {@link #bandsHost}: the status bar's down to [bandTop], the
     * gesture bar's from [bandBottom], whose picture runs on from the strip that starts at [bandAnchor].
     * Set for the strip's owner. Only touched on the main thread.
     */
    private static int bandTop;
    private static int bandBottom = Integer.MAX_VALUE;
    private static int bandAnchor;
    /** The view whose background paints the bands. */
    @Nullable private static WeakReference<View> bandsHost;
    @Nullable private static BandsDrawable bandsBackground;

    static {
        STRIP_DIM.setColor(0xFF000000);
        STRIP_DIM.setAlpha(DIM_ALPHA);
        STATUS_DIM.setColor(0xFF000000);
        STATUS_DIM.setAlpha(STATUS_DIM_ALPHA);
        STATUS_LIGHT.setColor(0xFFFFFFFF);
        STATUS_LIGHT.setAlpha(STATUS_DIM_ALPHA);
    }

    private ReelBlurBars() {
    }

    /**
     * Paints the strip under the tab bar, [left] to [right] and [top] to [bottom] in [canvas]'s
     * coordinates, as the reel on screen running on: its frame mirrored so the bottom row meets the
     * strip's top edge, blurred, with the same black over it as the bars get (#197). Instagram's own bar
     * draws it through its background ({@link StripDrawable}). Paints nothing and says false with the switch off, HushGram paused, no
     * reel in front or no frame yet, so the caller paints what it would have. Never throws.
     */
    public static boolean drawStrip(Canvas canvas, float left, float top, float right, float bottom) {
        try {
            Controller owner = stripOwner;
            Bitmap shown = stripFrame;
            if (owner == null || shown == null || shown.isRecycled() || right <= left || bottom <= top
                    || !owner.inFrontNow() || !owner.on.getAsBoolean()) {
                return false;
            }
            float scale = (right - left) / shown.getWidth();
            int saved = canvas.save();
            canvas.clipRect(left, top, right, bottom);
            canvas.translate(left, top + shown.getHeight() * scale);
            canvas.scale(scale, -scale);
            canvas.drawBitmap(shown, 0, 0, STRIP_PAINT);
            canvas.restoreToCount(saved);
            canvas.drawRect(left, top, right, bottom, STRIP_DIM);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_BLUR_BARS, DRAW_STRIP, failure);
            return false;
        }
    }

    /**
     * Paints the bands under the system bars, within [left] to [right] and [top] to [bottom] of the
     * canvas of the view whose background they are: above {@link #bandTop} the reel's top mirrored upward with
     * {@link #STATUS_DIM_ALPHA} over it in the colour that keeps the window's status bar icons readable,
     * and below {@link #bandBottom} the strip under the tab bar running on (#258). Paints nothing and
     * says false when {@link #drawStrip} would, or with neither band there. Never throws.
     */
    static boolean drawBands(Canvas canvas, float left, float top, float right, float bottom) {
        try {
            Controller owner = stripOwner;
            Bitmap shown = stripFrame;
            if (owner == null || shown == null || shown.isRecycled() || right <= left || bottom <= top
                    || !owner.inFrontNow() || !owner.on.getAsBoolean()) {
                return false;
            }
            float scale = (right - left) / shown.getWidth();
            boolean drew = false;
            if (bandTop > top) {
                int saved = canvas.save();
                canvas.clipRect(left, top, right, bandTop);
                canvas.translate(left, bandTop);
                canvas.scale(scale, -scale);
                canvas.drawBitmap(shown, 0, 0, STRIP_PAINT);
                canvas.restoreToCount(saved);
                View host = bandsHost == null ? null : bandsHost.get();
                boolean darkIcons = host != null && darkStatusIcons.test(host);
                canvas.drawRect(left, top, right, bandTop, darkIcons ? STATUS_LIGHT : STATUS_DIM);
                drew = true;
            }
            if (bandBottom < bottom) {
                int saved = canvas.save();
                canvas.clipRect(left, bandBottom, right, bottom);
                canvas.translate(left, bandAnchor + shown.getHeight() * scale);
                canvas.scale(scale, -scale);
                canvas.drawBitmap(shown, 0, 0, STRIP_PAINT);
                canvas.restoreToCount(saved);
                canvas.drawRect(left, bandBottom, right, bottom, STRIP_DIM);
                drew = true;
            }
            return drew;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_BLUR_BARS, DRAW_BANDS, failure);
            return false;
        }
    }

    /** The system bars' insets on [decor]'s window while they show, or null before it has any. */
    @Nullable
    @SuppressWarnings("deprecation")
    static Rect systemBars(View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            return new Rect(bars.left, bars.top, bars.right, bars.bottom);
        }
        return new Rect(insets.getStableInsetLeft(), insets.getStableInsetTop(), insets.getStableInsetRight(), insets.getStableInsetBottom());
    }

    /**
     * Whether [view]'s window asks for dark status bar icons, through the older view flag (which
     * Instagram's Reels sets in light mode) or the insets controller.
     */
    @SuppressWarnings("deprecation")
    static boolean asksForDarkStatusIcons(View view) {
        if ((view.getWindowSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0) return true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        WindowInsetsController controller = view.getWindowInsetsController();
        return controller != null && (controller.getSystemBarsAppearance() & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0;
    }

    /**
     * The view whose background paints bands down to [top] and from [bottom] of the window round
     * [pager]: the nearest of its parents that spans the window's width and both bands, so no
     * background between it and the reel paints over them. On Instagram that is the swipe container,
     * whose black covered the window root's background (#258). The window root when no parent does.
     */
    static View bandsHost(View pager, int top, int bottom) {
        View root = pager.getRootView();
        int width = root.getWidth();
        int height = root.getHeight();
        int[] at = new int[2];
        for (ViewParent parent = pager.getParent(); parent instanceof View && parent != root; parent = parent.getParent()) {
            View view = (View) parent;
            view.getLocationInWindow(at);
            int viewBottom = at[1] + view.getHeight();
            boolean wide = at[0] <= 0 && at[0] + view.getWidth() >= width;
            boolean coversTop = top <= 0 || (at[1] <= 0 && viewBottom >= top);
            boolean coversBottom = bottom >= height || (at[1] <= bottom && viewBottom >= height);
            if (wide && coversTop && coversBottom) return view;
        }
        return root;
    }

    /**
     * Makes [host]'s background paint the bands down to [top] and from [bottom] of the window, the
     * gesture bar's running on from [anchor], keeping what it had under them. A background that
     * changed since, from Instagram or the system, is kept the same way. Another view holding them
     * gives them up first, so the new geometry isn't reset with it.
     */
    private static void showBands(View host, int top, int bottom, int anchor) {
        View held = bandsHost == null ? null : bandsHost.get();
        if (held != null && held != host) dropBands();
        Drawable current = host.getBackground();
        boolean holding = holdsBands(current);
        if (!holding && host == host.getRootView() && rootWrapped(current)) return;
        int[] at = new int[2];
        host.getLocationInWindow(at);
        bandTop = top - at[1];
        bandBottom = bottom - at[1];
        bandAnchor = anchor - at[1];
        if (bandsBackground == null) bandsBackground = new BandsDrawable();
        if (!holding) {
            bandsBackground.original = current;
            host.setBackground(bandsBackground);
            bandsHost = new WeakReference<>(host);
        }
        host.invalidate();
    }

    /** Gives the bands' view back the background it had, or at least has it drawn again without them. */
    private static void dropBands() {
        View host = bandsHost == null ? null : bandsHost.get();
        if (host != null && bandsBackground != null) {
            if (holdsBands(host.getBackground())) host.setBackground(bandsBackground.original);
            else host.invalidate();
        }
        if (bandsBackground != null) bandsBackground.original = null;
        bandsHost = null;
        bandTop = 0;
        bandBottom = Integer.MAX_VALUE;
    }

    /**
     * Whether [background] is the window root's own inset or blur layer round the background it was
     * given. Kept as the covered background, it would come back wrapped twice, so the root gets no
     * bands while it has one.
     */
    static boolean rootWrapped(@Nullable Drawable background) {
        if (background instanceof InsetDrawable) return true;
        if (!(background instanceof LayerDrawable)) return false;
        LayerDrawable layers = (LayerDrawable) background;
        Drawable first = layers.getNumberOfLayers() == 2 ? layers.getDrawable(0) : null;
        return first != null && first.getClass().getName().endsWith("BackgroundBlurDrawable");
    }

    /**
     * Whether [background] is the bands or holds them. The window root wraps what it's given in an
     * inset or a layer when its insets or blur change, and keeping that wrapper as the covered
     * background would have the bands draw themselves forever.
     */
    static boolean holdsBands(@Nullable Drawable background) {
        return holdsBands(background, 0);
    }

    private static boolean holdsBands(@Nullable Drawable background, int depth) {
        if (background == null || depth >= 8) return false;
        if (background instanceof BandsDrawable) return true;
        if (background instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) background;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) if (holdsBands(layers.getDrawable(i), depth + 1)) return true;
            return false;
        }
        return background instanceof DrawableWrapper && holdsBands(((DrawableWrapper) background).getDrawable(), depth + 1);
    }

    /** The bands' background while it paints them, for tests. */
    @Nullable
    static Drawable bandsBackground() {
        return bandsHost == null || bandsHost.get() == null ? null : bandsBackground;
    }

    /** The view whose background paints the bands, or null while none does, for tests. */
    @Nullable
    static View bandsHost() {
        return bandsHost == null || bandsBackground == null ? null : bandsHost.get();
    }

    /** The frame the strip under the tab bar shows, for tests. */
    @Nullable
    static Bitmap stripFrame() {
        return stripFrame;
    }

    private static int tabBarId(Context context) {
        int id = tabBarId;
        if (id == -1) {
            id = context.getResources().getIdentifier(TAB_BAR, "id", context.getPackageName());
            tabBarId = id;
        }
        return id;
    }

    /**
     * Injected right after the Reels viewer keeps its pager. While the switch is on, starts watching
     * it. Never throws.
     */
    public static void pager(@Nullable Object pager) {
        pager(pager, ReelBlurBars::switchedOn);
    }

    static void pager(@Nullable Object pager, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REEL_BLUR_BARS);
            if (!(pager instanceof ViewGroup) || !on.getAsBoolean()) return;
            ViewGroup group = (ViewGroup) pager;
            Controller controller;
            synchronized (CONTROLLERS) {
                if (CONTROLLERS.containsKey(group)) return;
                controller = new Controller(group, on);
                CONTROLLERS.put(group, controller);
            }
            controller.start();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_BLUR_BARS, HAND_OVER, failure);
        }
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.BLUR_REEL_BARS.get();
    }

    /** Lets go of every pager and backdrop, for tests. */
    static void reset() {
        synchronized (CONTROLLERS) {
            for (Controller controller : new ArrayList<>(CONTROLLERS.values())) controller.stop();
            CONTROLLERS.clear();
        }
        source = TEXTURE;
        wearsGlass = GlassTabBar::wearsGlass;
        floatingGlass = GlassTabBar::wearsFloatingGlass;
        rounds = 0;
        logged = false;
        dropBands();
        barInsets = ReelBlurBars::systemBars;
        darkStatusIcons = ReelBlurBars::asksForDarkStatusIcons;
        bandsBackground = null;
        stripOwner = null;
        stripFrame = null;
        tabBarId = -1;
    }

    /**
     * Whether a pager is in front: attached, shown by every parent, and in a window the user can see.
     * isShown() ignores the window, so a stopped activity's pager still counted and its loop kept scanning.
     */
    static boolean inFront(boolean attached, boolean shown, int windowVisibility) {
        return attached && shown && windowVisibility == View.VISIBLE;
    }

    /** How many pagers still have a listener on their window's observer, for tests. */
    static int listenerCount() {
        int count = 0;
        synchronized (CONTROLLERS) {
            for (Controller controller : CONTROLLERS.values()) if (controller.listening) count++;
        }
        return count;
    }

    /** How many pagers are on the window's pre-draw callbacks, for tests. */
    static int drawListenerCount() {
        int count = 0;
        synchronized (CONTROLLERS) {
            for (Controller controller : CONTROLLERS.values()) if (controller.drawListening) count++;
        }
        return count;
    }

    /** The backdrops currently held across all pagers, for tests. */
    static int backdropCount() {
        int count = 0;
        synchronized (CONTROLLERS) {
            for (Controller controller : CONTROLLERS.values()) count += controller.backdrops.size();
        }
        return count;
    }

    /** Watches one Reels pager. Only ever touched on the main thread. */
    private static final class Controller implements Runnable, View.OnAttachStateChangeListener,
            ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnWindowFocusChangeListener {
        private final WeakReference<ViewGroup> pager;
        private final BooleanSupplier on;
        /** Whether this controller is on the window's observer. Off while the pager is detached. */
        boolean listening;
        /** Whether this controller is on the window's pre-draw callbacks. Only while the loop is asleep. */
        boolean drawListening;
        /** Whether the loop stopped because the pager wasn't in front. A layout, scroll, draw or window focus change wakes it. */
        private boolean asleep;
        final Map<View, Backdrop> backdrops = new WeakHashMap<>();
        /** The tab bar, found once, and a full-screen reel's frame kept for the strip under it. */
        @Nullable private WeakReference<View> barRef;
        @Nullable private Bitmap stripRaw;
        @Nullable private Bitmap stripBlurred;
        /** What this put on Instagram's own tab bar as its background, made the first time it's needed. */
        @Nullable private StripDrawable barBackground;

        Controller(ViewGroup pager, BooleanSupplier on) {
            this.pager = new WeakReference<>(pager);
            this.on = on;
        }

        void start() {
            ViewGroup group = pager.get();
            if (group == null) return;
            group.addOnAttachStateChangeListener(this);
            listen(group);
            schedule(group, SETTLE_MILLIS);
        }

        void stop() {
            ViewGroup group = pager.get();
            if (group != null) {
                group.removeOnAttachStateChangeListener(this);
                unlisten(group);
                group.removeCallbacks(this);
            }
            releaseAll();
        }

        private void listen(ViewGroup group) {
            ViewTreeObserver observer = group.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.removeOnScrollChangedListener(this);
            observer.removeOnGlobalLayoutListener(this);
            observer.removeOnPreDrawListener(this);
            observer.removeOnWindowFocusChangeListener(this);
            observer.addOnScrollChangedListener(this);
            observer.addOnGlobalLayoutListener(this);
            observer.addOnWindowFocusChangeListener(this);
            listening = true;
            drawListening = false;
            if (asleep) watchDraws(group, true);
        }

        /** Takes this off the window's observer, which outlives the pager. */
        private void unlisten(ViewGroup group) {
            ViewTreeObserver observer = group.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnScrollChangedListener(this);
                observer.removeOnGlobalLayoutListener(this);
                observer.removeOnPreDrawListener(this);
                observer.removeOnWindowFocusChangeListener(this);
            }
            listening = false;
            drawListening = false;
        }

        private static boolean inFront(View view) {
            return ReelBlurBars.inFront(view.isAttachedToWindow(), view.isShown(), view.getWindowVisibility());
        }

        /** Whether the pager is in front right now, asked as the strip is drawn, so another tab never shows a reel. */
        boolean inFrontNow() {
            ViewGroup group = pager.get();
            return group != null && inFront(group);
        }

        /**
         * Puts the pre-draw listener on the window's observer or takes it off. The observer is the whole
         * window's, so it is on only while the loop is asleep or this pager's bands show: an awake loop
         * has no other use for it, and a hidden pager would otherwise be asked about on every frame the
         * app draws. The bands need it because a pager turning invisible lays nothing out, and the
         * background holding them would show them over the next screen until the loop looked again.
         */
        private void watchDraws(ViewGroup group, boolean watch) {
            if (drawListening == watch) return;
            ViewTreeObserver observer = group.getViewTreeObserver();
            if (!observer.isAlive()) return;
            if (watch) observer.addOnPreDrawListener(this);
            else observer.removeOnPreDrawListener(this);
            drawListening = watch;
        }

        /** Starts the loop again if it stopped for the pager not being in front and it is back. */
        private void wake() {
            if (!asleep) return;
            ViewGroup group = pager.get();
            if (group == null || !inFront(group)) return;
            // Awake from here, so a draw every frame doesn't push the first look back each time.
            asleep = false;
            watchDraws(group, false);
            schedule(group, SETTLE_MILLIS);
        }

        private void schedule(ViewGroup group, long delay) {
            group.removeCallbacks(this);
            group.postDelayed(this, delay);
        }

        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
            listen((ViewGroup) view);
            schedule((ViewGroup) view, SETTLE_MILLIS);
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
            view.removeCallbacks(this);
            unlisten((ViewGroup) view);
            releaseAll();
        }

        /**
         * The pager came back in front after the loop stopped for it, or left it, which takes the strip
         * and the bands away now: the background holding them isn't drawn again on its own.
         */
        @Override
        public void onGlobalLayout() {
            leftFront();
            wake();
        }

        /** Runs inside Instagram's layout, draw and scroll callbacks, so it never throws. */
        private void leftFront() {
            try {
                if (stripOwner == this && !inFrontNow()) dropStrip();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_BLUR_BARS, DROP_STRIP, failure);
            }
        }

        /**
         * Drawing starts again after the pager went from invisible to visible, which lays nothing out, or
         * after its window came back. While the bands show, a frame after the pager went invisible takes
         * them away. Never cancels the draw.
         */
        @Override
        public boolean onPreDraw() {
            leftFront();
            wake();
            return true;
        }

        /** The window gained focus, as it does coming back from the background. */
        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (hasFocus) wake();
        }

        /** A scroll moved something. Looks once it has been quiet for a moment. */
        @Override
        public void onScrollChanged() {
            leftFront();
            ViewGroup group = pager.get();
            if (group != null) schedule(group, SETTLE_MILLIS);
        }

        @Override
        public void run() {
            ViewGroup group = pager.get();
            if (group == null) return;
            rounds++;
            try {
                if (!on.getAsBoolean()) {
                    releaseAll();
                    return;
                }
                if (!inFront(group)) {
                    asleep = true;
                    watchDraws(group, true);
                    dropStrip();
                    return;
                }
                asleep = false;
                watchDraws(group, stripOwner == this && bandsBackground() != null);
                scan(group);
                schedule(group, REFRESH_MILLIS);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_BLUR_BARS, SCAN, failure);
                releaseAll();
            }
        }

        private void scan(ViewGroup group) {
            List<View> kept = new ArrayList<>();
            View bar = stripBar(group);
            Bands bands = bands(group, bar);
            boolean wanted = bar != null || bands != null;
            Bitmap strip = null;
            for (TextureView video : videos(group)) {
                if (video.getWidth() <= 0 || video.getHeight() <= 0) continue;
                ViewGroup container = containerOf(video, group);
                if (container == null) {
                    HookStatus.counted(FamilyNames.REEL_BLUR_BARS, NO_CONTAINER);
                    continue;
                }
                if (!onScreen(container, group)) continue;
                Rect picture = pictureIn(video, container);
                if (picture == null || !hasBars(picture, container)) {
                    HookStatus.counted(FamilyNames.REEL_BLUR_BARS, FULL_SCREEN);
                    if (wanted && strip == null) strip = stripCopy(video);
                    continue;
                }
                if (paint(video, container)) {
                    kept.add(container);
                    Backdrop backdrop = backdrops.get(container);
                    if (strip == null && backdrop != null) strip = backdrop.blurred;
                }
            }
            for (View container : new ArrayList<>(backdrops.keySet())) {
                if (!kept.contains(container)) release(container);
            }
            showStrip(bar, bands, strip);
        }

        /**
         * The bands under the system bars this pager leaves black, in the window. Null with neither: no
         * insets, or the viewer reaching both edges. A floating pill has the reel running on behind it
         * (#221), so its bottom stays as it is.
         */
        @Nullable
        private Bands bands(ViewGroup group, @Nullable View bar) {
            View decor = group.getRootView();
            int height = decor.getHeight();
            Rect insets = barInsets.apply(decor);
            if (insets == null || height <= 0) return null;
            int[] at = new int[2];
            group.getLocationInWindow(at);
            int top = Math.min(at[1], insets.top);
            int anchor;
            int edge;
            View shown = barRef == null ? null : barRef.get();
            if (bar != null) {
                bar.getLocationInWindow(at);
                anchor = at[1];
                edge = at[1] + bar.getHeight();
            } else if (shown != null && shown.isShown() && floatingGlass.test(shown)) {
                anchor = height;
                edge = height;
            } else {
                anchor = at[1] + group.getHeight();
                edge = anchor;
            }
            int bottom = Math.max(edge, height - insets.bottom);
            if (top <= 0 && bottom >= height) return null;
            return new Bands(top, bottom, anchor);
        }

        /**
         * Instagram's tab bar when the strip under the reel is this class's to fill: shown, and not a
         * floating glass pill, which has the reel running on behind it (#221). A docked pill paints the
         * strip round itself (#258).
         */
        @Nullable
        private View stripBar(ViewGroup group) {
            View bar = barRef == null ? null : barRef.get();
            if (bar == null || !bar.isAttachedToWindow()) {
                int id = tabBarId(group.getContext());
                bar = id == 0 ? null : group.getRootView().findViewById(id);
                barRef = bar == null ? null : new WeakReference<>(bar);
            }
            if (bar == null || !bar.isShown() || floatingGlass.test(bar)) return null;
            return bar;
        }

        /** A blurred copy of a full-screen reel for the strip, in the bitmaps kept for it, or null with no frame yet. */
        @Nullable
        private Bitmap stripCopy(TextureView video) {
            int width = Math.max(2, video.getWidth() / SCALE);
            int height = Math.max(2, video.getHeight() / SCALE);
            Bitmap raw = source.frame(video, stripRaw, width, height);
            if (raw == null) return null;
            if (stripRaw != null && stripRaw != raw) stripRaw.recycle();
            stripRaw = raw;
            Bitmap blurred = blur(raw, stripBlurred);
            if (blurred != null) stripBlurred = blurred;
            return blurred;
        }

        /**
         * Shows [frame] in the strip under [bar] and in [bands], or takes this controller's strip away
         * with no frame or neither place to show it.
         */
        private void showStrip(@Nullable View bar, @Nullable Bands bands, @Nullable Bitmap frame) {
            if (frame == null || (bar == null && bands == null)) {
                dropStrip();
                return;
            }
            stripOwner = this;
            stripFrame = frame;
            if (bar != null) {
                if (!wearsGlass.test(bar)) {
                    if (barBackground == null) barBackground = new StripDrawable();
                    if (bar.getBackground() != barBackground) {
                        barBackground.original = bar.getBackground();
                        bar.setBackground(barBackground);
                    }
                }
                bar.invalidate();
                HookStatus.counted(FamilyNames.REEL_BLUR_BARS, STRIP);
            } else {
                restoreBar();
            }
            ViewGroup group = pager.get();
            if (bands != null && group != null) {
                showBands(bandsHost(group, bands.top, bands.bottom), bands.top, bands.bottom, bands.anchor);
                watchDraws(group, true);
                HookStatus.counted(FamilyNames.REEL_BLUR_BARS, BANDS);
            } else {
                dropBands();
                if (group != null) watchDraws(group, asleep);
            }
        }

        /**
         * Takes this controller's strip and bands away and recycles its copy: Instagram's own bar and the
         * bands' view get back the backgrounds they had.
         */
        void dropStrip() {
            View bar = barRef == null ? null : barRef.get();
            if (stripOwner == this) {
                stripOwner = null;
                stripFrame = null;
                if (bar != null) bar.invalidate();
                dropBands();
                ViewGroup group = pager.get();
                if (group != null) watchDraws(group, asleep);
            }
            restoreBar();
            if (stripRaw != null) stripRaw.recycle();
            if (stripBlurred != null && stripBlurred != stripRaw) stripBlurred.recycle();
            stripRaw = null;
            stripBlurred = null;
        }

        /** Gives Instagram's own bar back the background it had under the strip. */
        private void restoreBar() {
            View bar = barRef == null ? null : barRef.get();
            if (bar != null && barBackground != null && bar.getBackground() == barBackground) {
                bar.setBackground(barBackground.original);
            }
            if (barBackground != null) barBackground.original = null;
        }

        /** Every visible TextureView under the pager. */
        private List<TextureView> videos(ViewGroup group) {
            List<TextureView> found = new ArrayList<>();
            ArrayDeque<View> pending = new ArrayDeque<>();
            pending.push(group);
            int seen = 0;
            while (!pending.isEmpty() && seen++ < MAX_VIEWS) {
                View view = pending.pop();
                if (view.getVisibility() != View.VISIBLE) continue;
                if (view instanceof TextureView) {
                    found.add((TextureView) view);
                } else if (view instanceof ViewGroup) {
                    ViewGroup parent = (ViewGroup) view;
                    for (int i = 0; i < parent.getChildCount(); i++) pending.push(parent.getChildAt(i));
                }
            }
            return found;
        }

        /**
         * The closest view around the video that spans the page, whose background is what shows in
         * the bars. Null when none does before the pager's own list of pages.
         */
        @Nullable
        private ViewGroup containerOf(View video, ViewGroup pagerView) {
            View view = video;
            while (view.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent == pagerView) return null;
                if (parent.getWidth() >= pagerView.getWidth() * 0.9f && parent.getHeight() >= pagerView.getHeight() * 0.9f) {
                    return parent;
                }
                view = parent;
            }
            return null;
        }

        /** Whether [container] covers at least half of what shows of the pager, which is the page on screen. */
        private boolean onScreen(View container, View pagerView) {
            Rect shown = new Rect();
            Rect visible = new Rect();
            if (!pagerView.getGlobalVisibleRect(shown) || !container.getGlobalVisibleRect(visible)) return false;
            long area = (long) shown.width() * shown.height();
            return area > 0 && (long) visible.width() * visible.height() * 2 >= area;
        }

        /** Where the picture is, in the container's coordinates, with the video's own transform applied. */
        @Nullable
        private Rect pictureIn(TextureView video, ViewGroup container) {
            Matrix transform = new Matrix();
            video.getTransform(transform);
            RectF content = new RectF(0, 0, video.getWidth(), video.getHeight());
            transform.mapRect(content);
            Rect picture = new Rect();
            content.round(picture);
            container.offsetDescendantRectToMyCoords(video, picture);
            if (!picture.intersect(0, 0, container.getWidth(), container.getHeight())) return null;
            return picture;
        }

        private boolean hasBars(Rect picture, View container) {
            return container.getHeight() - picture.height() > container.getHeight() * BAR_SHARE
                    || container.getWidth() - picture.width() > container.getWidth() * BAR_SHARE;
        }

        /** Copies the frame, blurs it and sets it as the container's background. False when there was no frame. */
        private boolean paint(TextureView video, ViewGroup container) {
            Backdrop backdrop = backdrops.get(container);
            int width = Math.max(2, video.getWidth() / SCALE);
            int height = Math.max(2, video.getHeight() / SCALE);
            Bitmap raw = source.frame(video, backdrop == null ? null : backdrop.raw, width, height);
            Bitmap blurred = raw == null ? null : blur(raw, backdrop == null ? null : backdrop.blurred);
            if (blurred == null) {
                HookStatus.counted(FamilyNames.REEL_BLUR_BARS, NO_FRAME);
                return backdrop != null;
            }
            if (backdrop == null) {
                backdrop = new Backdrop(this);
                backdrops.put(container, backdrop);
                container.addOnAttachStateChangeListener(backdrop);
            }
            if (backdrop.raw != raw && backdrop.raw != null) backdrop.raw.recycle();
            backdrop.raw = raw;
            backdrop.blurred = blurred;
            backdrop.drawable.show(blurred);
            if (container.getBackground() != backdrop.drawable) {
                backdrop.original = container.getBackground();
                container.setBackground(backdrop.drawable);
            }
            HookStatus.counted(FamilyNames.REEL_BLUR_BARS, DRAWN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Reels: drew a blurred backdrop behind a reel with bars");
            }
            return true;
        }

        void release(View container) {
            Backdrop backdrop = backdrops.remove(container);
            if (backdrop == null) return;
            container.removeOnAttachStateChangeListener(backdrop);
            if (container.getBackground() == backdrop.drawable) container.setBackground(backdrop.original);
            backdrop.free();
        }

        void releaseAll() {
            for (View container : new ArrayList<>(backdrops.keySet())) release(container);
            dropStrip();
        }
    }

    /**
     * Where the bands under the system bars run, in the window: the status bar's down to [top], the
     * gesture bar's from [bottom], with the strip under the tab bar starting at [anchor].
     */
    private static final class Bands {
        final int top;
        final int bottom;
        final int anchor;

        Bands(int top, int bottom, int anchor) {
            this.top = top;
            this.bottom = bottom;
            this.anchor = anchor;
        }
    }

    /** What one page holds: its two tiny bitmaps, the drawable showing one, and the background it covered. */
    private static final class Backdrop implements View.OnAttachStateChangeListener {
        private final Controller owner;
        final BackdropDrawable drawable = new BackdropDrawable();
        @Nullable Bitmap raw;
        @Nullable Bitmap blurred;
        @Nullable Drawable original;

        Backdrop(Controller owner) {
            this.owner = owner;
        }

        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
        }

        /** The page went away with its views, so its bitmaps do too. */
        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
            owner.release(view);
        }

        void free() {
            drawable.show(null);
            original = null;
            if (raw != null) raw.recycle();
            if (blurred != null && blurred != raw) blurred.recycle();
            raw = null;
            blurred = null;
        }
    }

    /** Paints the blurred copy stretched to cover the bounds, with a little black over it. */
    static final class BackdropDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint dim = new Paint();
        @Nullable private Bitmap bitmap;

        BackdropDrawable() {
            dim.setColor(0xFF000000);
            dim.setAlpha(DIM_ALPHA);
        }

        void show(@Nullable Bitmap next) {
            bitmap = next;
            invalidateSelf();
        }

        @Nullable
        Bitmap bitmap() {
            return bitmap;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Bitmap shown = bitmap;
            Rect bounds = getBounds();
            if (shown == null || shown.isRecycled() || bounds.isEmpty()) {
                canvas.drawColor(0xFF000000);
                return;
            }
            float scale = Math.max(bounds.width() / (float) shown.getWidth(), bounds.height() / (float) shown.getHeight());
            canvas.save();
            canvas.clipRect(bounds);
            canvas.translate(
                    bounds.left + (bounds.width() - shown.getWidth() * scale) / 2f,
                    bounds.top + (bounds.height() - shown.getHeight() * scale) / 2f);
            canvas.scale(scale, scale);
            canvas.drawBitmap(shown, 0, 0, paint);
            canvas.restore();
            canvas.drawRect(bounds, dim);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /**
     * Instagram's own tab bar's background while a reel shows above it: the strip ({@link #drawStrip}),
     * or the background it covered whenever the strip has nothing to show, so a tab switch never waits
     * for the next look to get the bar's colour back.
     */
    static final class StripDrawable extends Drawable {
        @Nullable Drawable original;

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (drawStrip(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom)) return;
            Drawable covered = original;
            if (covered != null) {
                covered.setBounds(bounds);
                covered.draw(canvas);
            }
        }

        // The bar keeps the padding and least size the background it covered gave it.
        @Override
        public boolean getPadding(@NonNull Rect padding) {
            Drawable covered = original;
            if (covered != null) return covered.getPadding(padding);
            return super.getPadding(padding);
        }

        @Override
        public int getMinimumWidth() {
            Drawable covered = original;
            return covered == null ? 0 : covered.getMinimumWidth();
        }

        @Override
        public int getMinimumHeight() {
            Drawable covered = original;
            return covered == null ? 0 : covered.getMinimumHeight();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The bands' view's background while a reel is in front: the background it covered, then the bands
     * under the system bars ({@link #drawBands}) over it. It answers the covered background's opacity,
     * since a window root takes its format from it, or translucent with none, since the bands alone
     * leave the rest of the view uncovered.
     */
    static final class BandsDrawable extends Drawable {
        @Nullable Drawable original;
        /** Set while drawing, so a covered background that somehow leads back here draws once. */
        private boolean drawing;

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (drawing) return;
            drawing = true;
            try {
                Rect bounds = getBounds();
                Drawable covered = original;
                if (covered != null) {
                    covered.setBounds(bounds);
                    covered.draw(canvas);
                }
                drawBands(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_BLUR_BARS, DRAW_BANDS, failure);
            } finally {
                drawing = false;
            }
        }

        @Override
        public boolean getPadding(@NonNull Rect padding) {
            Drawable covered = original;
            if (covered != null) return covered.getPadding(padding);
            return super.getPadding(padding);
        }

        @Override
        public int getMinimumWidth() {
            Drawable covered = original;
            return covered == null ? 0 : covered.getMinimumWidth();
        }

        @Override
        public int getMinimumHeight() {
            Drawable covered = original;
            return covered == null ? 0 : covered.getMinimumHeight();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            Drawable covered = original;
            return covered == null ? PixelFormat.TRANSLUCENT : covered.getOpacity();
        }
    }

    /**
     * The blurred picture of [raw], trimmed of black edges the video itself carries or the copy
     * picked up, in [reuse] when it's the same size. Null when nothing but black is left.
     */
    @Nullable
    static Bitmap blur(Bitmap raw, @Nullable Bitmap reuse) {
        int width = raw.getWidth();
        int height = raw.getHeight();
        int[] pixels = new int[width * height];
        raw.getPixels(pixels, 0, width, 0, 0, width, height);
        Rect kept = trim(pixels, width, height);
        if (kept == null) return null;
        int w = kept.width();
        int h = kept.height();
        int[] part = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(pixels, (kept.top + y) * width + kept.left, part, y * w, w);
        for (int pass = 0; pass < BLUR_PASSES; pass++) {
            part = boxBlur(part, w, h, BLUR_RADIUS, true);
            part = boxBlur(part, w, h, BLUR_RADIUS, false);
        }
        Bitmap out = reuse != null && !reuse.isRecycled() && reuse.isMutable() && reuse.getWidth() == w && reuse.getHeight() == h
                ? reuse : Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(part, 0, w, 0, 0, w, h);
        if (reuse != null && reuse != out) reuse.recycle();
        return out;
    }

    /**
     * The part of the picture inside its black edges, but never less than a quarter of each side,
     * or null when all of it is black.
     */
    @Nullable
    public static Rect trim(int[] pixels, int width, int height) {
        int top = 0;
        int bottom = height;
        int left = 0;
        int right = width;
        int minimumRows = Math.max(1, height / 4);
        int minimumColumns = Math.max(1, width / 4);
        while (bottom - top > minimumRows && darkRow(pixels, width, top, 0, width)) top++;
        while (bottom - top > minimumRows && darkRow(pixels, width, bottom - 1, 0, width)) bottom--;
        while (right - left > minimumColumns && darkColumn(pixels, width, left, top, bottom)) left++;
        while (right - left > minimumColumns && darkColumn(pixels, width, right - 1, top, bottom)) right--;
        for (int y = top; y < bottom; y++) {
            if (!darkRow(pixels, width, y, left, right)) return new Rect(left, top, right, bottom);
        }
        return null;
    }

    private static boolean darkRow(int[] pixels, int width, int y, int from, int to) {
        for (int x = from; x < to; x++) if (!dark(pixels[y * width + x])) return false;
        return true;
    }

    private static boolean darkColumn(int[] pixels, int width, int x, int from, int to) {
        for (int y = from; y < to; y++) if (!dark(pixels[y * width + x])) return false;
        return true;
    }

    private static boolean dark(int pixel) {
        return ((pixel >> 16) & 0xFF) <= BLACK && ((pixel >> 8) & 0xFF) <= BLACK && (pixel & 0xFF) <= BLACK;
    }

    /** One pass of a box blur along rows or columns, the edge pixel standing in past the edge. */
    public static int[] boxBlur(int[] in, int width, int height, int radius, boolean horizontal) {
        int[] out = new int[in.length];
        int length = horizontal ? width : height;
        int lines = horizontal ? height : width;
        int window = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int at = Math.min(length - 1, Math.max(0, i + k));
                    int pixel = horizontal ? in[line * width + at] : in[at * width + line];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                }
                int value = 0xFF000000 | (r / window << 16) | (g / window << 8) | (b / window);
                if (horizontal) out[line * width + i] = value;
                else out[i * width + line] = value;
            }
        }
        return out;
    }
}
