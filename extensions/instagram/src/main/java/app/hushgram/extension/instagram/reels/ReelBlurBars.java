/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

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

    /** How many times a pager's loop has run, so tests can see it stop. */
    static volatile int rounds;

    /** The controller for each pager handed over, held weakly so a closed viewer's can go. */
    private static final Map<Object, Controller> CONTROLLERS = new WeakHashMap<>();

    private static volatile boolean logged;

    private ReelBlurBars() {
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
        rounds = 0;
        logged = false;
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

        /**
         * Puts the pre-draw listener on the window's observer or takes it off. The observer is the whole
         * window's, so it is on only while the loop is asleep: an awake loop has no use for it, and a
         * hidden pager would otherwise be asked about on every frame the app draws.
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

        /** The pager came back in front after the loop stopped for it. */
        @Override
        public void onGlobalLayout() {
            wake();
        }

        /**
         * Drawing starts again after the pager went from invisible to visible, which lays nothing out, or
         * after its window came back. Never cancels the draw.
         */
        @Override
        public boolean onPreDraw() {
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
                    return;
                }
                asleep = false;
                watchDraws(group, false);
                scan(group);
                schedule(group, REFRESH_MILLIS);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_BLUR_BARS, SCAN, failure);
                releaseAll();
            }
        }

        private void scan(ViewGroup group) {
            List<View> kept = new ArrayList<>();
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
                    continue;
                }
                if (paint(video, container)) kept.add(container);
            }
            for (View container : new ArrayList<>(backdrops.keySet())) {
                if (!kept.contains(container)) release(container);
            }
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
    static Rect trim(int[] pixels, int width, int height) {
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
    static int[] boxBlur(int[] in, int width, int height, int radius, boolean horizontal) {
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
