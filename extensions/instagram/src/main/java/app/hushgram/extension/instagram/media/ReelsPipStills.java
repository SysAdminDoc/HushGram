/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.download.PictureFetch;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Photo and carousel reels in Instagram's picture in picture window for reels (#873).
 *
 * <p>Instagram asks one check whether a reel can go in its small window, both as you leave a reel
 * and when the window's Previous and Next look for the reel to go to. The check's first question is
 * whether the reel is a video, and a photo or a carousel stops it there. The patch hands that answer
 * to {@link #window} with the reel's model, and with the switch on, a photo or a carousel answers
 * yes. The check's other questions still count: an ad stays out, and so does a reel whose shape
 * Android's window can't take.
 *
 * <p>The window itself only plays videos: its content binds Instagram's player to a video layout.
 * The patch hands that bind the layout and the reel at its start ({@link #binding}, {@link #bound}),
 * and for a photo, or a carousel's photo page, the extension answers no reel, which is Instagram's
 * own way past the player, pauses what played, and lays the page's picture over the layout. A
 * carousel gets dots for its pages over whatever shows. A carousel's video page plays: the bind asks
 * {@link #page} which page's video to play, Instagram's own read of a carousel's page.
 *
 * <p>Instagram's Previous and Next in the window go a reel back or on. The patch has their receiver
 * ask {@link #skip} first, and on a carousel with a page that way, the window turns to it, binding
 * again for a video page, and the reel stays. Past the last page, or before the first, Instagram
 * goes on to the next reel or back to the one before.
 *
 * <p>Every hook runs on the main thread and never throws into Instagram.
 */
public final class ReelsPipStills {
    static final String HOOK = "photos in the reels window";

    /** Instagram's {@code media_type} for a photo, a video and a carousel. */
    static final int PHOTO = 1;
    static final int VIDEO = 2;
    static final int CAROUSEL = 8;

    /** The actions of the window's Previous and Next, which Instagram's receiver tells apart. */
    static final String ACTION_NEXT = "instagram.features.clips.pip.ACTION_NEXT";
    static final String ACTION_PREVIOUS = "instagram.features.clips.pip.ACTION_PREVIOUS";

    /** The width a picture is fetched at: a little past a large window's. */
    static final int PICTURE_SIZE = 720;

    /** What the window reads from a reel. */
    interface Reels {
        /** The Media of the window's reel. */
        @Nullable
        Object media(Object reel);

        @Nullable
        Integer type(Object media);

        /** A carousel's pages, each a Media. */
        @Nullable
        List<?> pages(Object media);

        /** The address of a page's picture at about [size] across. */
        @Nullable
        String picture(Object media, int size);
    }

    /** What the window does besides drawing, for tests. */
    interface Window {
        @Nullable
        Bitmap fetch(Context context, String url, int size);

        /** Pauses what plays. */
        void pause();

        /** Instagram's bind again, with its own arguments. */
        void bind(Object binder, Object scope, View layout) throws Exception;
    }

    /** Tests read a model's type through this instead of the patch-written {@link #mediaType}. */
    @Nullable
    static volatile java.util.function.Function<Object, Integer> typesForTests;
    @Nullable
    static volatile Reels reelsForTests;
    @Nullable
    static volatile Window windowForTests;
    /** Where pictures are fetched; tests run them right away. */
    @Nullable
    static volatile Executor workForTests;

    private static final Object LOCK = new Object();
    private static final LruCache<String, Bitmap> PICTURES = new LruCache<>(8);
    @Nullable
    private static ExecutorService fetcher;

    /** The reel the window shows, while it's a photo or a carousel. */
    @Nullable
    private static Shown shown;
    /** Instagram's bind and its first argument, from its last run. */
    @Nullable
    private static WeakReference<Object> binder;
    @Nullable
    private static WeakReference<Object> bindScope;
    /** The carousel page whose video the bind plays next. */
    @Nullable
    private static Integer playPage;

    private static final class Shown {
        final WeakReference<View> layout;
        final WeakReference<Object> media;
        final boolean carousel;
        int count;
        int at;

        Shown(View layout, Object media, boolean carousel, int count) {
            this.layout = new WeakReference<>(layout);
            this.media = new WeakReference<>(media);
            this.carousel = carousel;
            this.count = count;
        }
    }

    private ReelsPipStills() {
    }

    // ---- Hooks ----

    /**
     * In Instagram's check whether a reel can go in the window, right after it asked whether the
     * reel is a video: {@code video} is that answer, non-zero for yes, and {@code model} is the
     * reel's model the check read it from. Answers what the check goes on with, 1 for yes.
     */
    public static int window(int video, Object model) {
        if (video != 0) return video;
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (!stillsOn()) return 0;
            java.util.function.Function<Object, Integer> types = typesForTests;
            Integer type = types != null ? types.apply(model) : mediaType(model);
            boolean still = type != null && (type == PHOTO || type == CAROUSEL);
            Logger.printDebug(() -> "ReelsPipStills: a reel of type " + type + (still ? " goes in" : " stays out"));
            return still ? 1 : 0;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return 0;
        }
    }

    /**
     * At the window's bind, with Instagram's own arguments: the bind, its first argument and the
     * layout. Kept, weakly, so turning to a carousel's video page can bind again.
     */
    public static void binding(Object bind, Object scope, Object layout) {
        try {
            synchronized (LOCK) {
                binder = bind != null ? new WeakReference<>(bind) : null;
                bindScope = scope != null ? new WeakReference<>(scope) : null;
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
        }
    }

    /**
     * At the window's bind, right before it asks whether it has a reel, with its video [layout] and
     * the [reel]. Answers the reel to bind the player to: the reel itself, or null, Instagram's own
     * answer for none, for a photo or a carousel's photo page, whose picture goes over the layout.
     */
    @Nullable
    public static Object bound(View layout, Object reel) {
        synchronized (LOCK) {
            playPage = null;
        }
        if (reel == null) return null;
        try {
            if (!stillsOn()) {
                clear(layout);
                return reel;
            }
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            Reels reels = reels();
            Object media = reels.media(reel);
            Integer type = media != null ? reels.type(media) : null;
            boolean carousel = type != null && type == CAROUSEL;
            List<?> pages = carousel ? reels.pages(media) : null;
            int count = carousel ? (pages != null ? pages.size() : 0) : 1;
            if (type == null || (type != PHOTO && !carousel) || count == 0) {
                clear(layout);
                return reel;
            }
            Shown now;
            synchronized (LOCK) {
                now = shown;
                if (now == null || now.media.get() != media || now.layout.get() != layout) {
                    now = new Shown(layout, media, carousel, count);
                    shown = now;
                }
                now.count = count;
                if (now.at >= count) now.at = count - 1;
            }
            Object page = carousel ? pages.get(now.at) : media;
            Integer pageType = carousel ? reels.type(page) : type;
            if (pageType != null && pageType == VIDEO) {
                synchronized (LOCK) {
                    playPage = now.at;
                }
                draw(layout, now, null, reels);
                return reel;
            }
            draw(layout, now, page, reels);
            pause();
            int at = now.at;
            Logger.printDebug(() -> "ReelsPipStills: the window shows page " + at + " of " + count);
            return null;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return reel;
        }
    }

    /**
     * Right after {@link #bound}, with the reel it answered: the carousel page whose video the bind
     * plays, or null for Instagram's own choice.
     */
    @Nullable
    public static Integer page(Object reel) {
        synchronized (LOCK) {
            Integer page = playPage;
            playPage = null;
            return reel != null ? page : null;
        }
    }

    /**
     * First thing in the receiver of the window's Previous and Next, with the button's [intent].
     * Answers 1 when the window turned to a carousel's page that way and the receiver is done, or 0
     * for Instagram's own move to another reel.
     */
    public static int skip(Intent intent) {
        try {
            if (intent == null || !stillsOn()) return 0;
            String action = intent.getAction();
            int step = ACTION_NEXT.equals(action) ? 1 : ACTION_PREVIOUS.equals(action) ? -1 : 0;
            if (step == 0) return 0;
            Shown now;
            synchronized (LOCK) {
                now = shown;
            }
            if (now == null || !now.carousel) return 0;
            View layout = now.layout.get();
            Object media = now.media.get();
            if (layout == null || media == null || !layout.isAttachedToWindow()) return 0;
            int to;
            synchronized (LOCK) {
                to = now.at + step;
                if (to < 0 || to >= now.count) return 0;
                now.at = to;
            }
            Logger.printDebug(() -> "ReelsPipStills: the window turns to page " + to);
            Utils.runOnMainThreadNowOrLater(() -> turn(now, layout, media));
            return 1;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return 0;
        }
    }

    /**
     * The Media of the window's reel. Answers null as built: the patch writes its body.
     */
    @Nullable
    public static Object reelMedia(Object reel) {
        return null;
    }

    /**
     * The {@code media_type} of the media in a reel's model, read the way Instagram's own video
     * question reads it. Answers null as built: the patch writes its body.
     */
    @Nullable
    public static Integer mediaType(Object model) {
        return null;
    }

    /** Forgets the window's reel, its bind and its pictures, for tests. */
    static void forgetForTests() {
        synchronized (LOCK) {
            shown = null;
            binder = null;
            bindScope = null;
            playPage = null;
        }
        PICTURES.evictAll();
    }

    static boolean stillsOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.REELS_PIP_STILLS.get();
    }

    // ---- Turning pages ----

    /** Shows the carousel's page [now] is at: its picture, or its video through Instagram's bind. */
    private static void turn(Shown now, View layout, Object media) {
        try {
            Reels reels = reels();
            List<?> pages = reels.pages(media);
            if (pages == null || now.at >= pages.size()) return;
            Object page = pages.get(now.at);
            Integer type = reels.type(page);
            if (type != null && type == VIDEO && bindAgain(layout)) return;
            draw(layout, now, page, reels);
            pause();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
        }
    }

    /** Runs Instagram's bind again, which plays the page {@link #bound} picks. False when it can't. */
    private static boolean bindAgain(View layout) {
        Object bind;
        Object scope;
        synchronized (LOCK) {
            bind = binder != null ? binder.get() : null;
            scope = bindScope != null ? bindScope.get() : null;
        }
        if (bind == null || scope == null) return false;
        try {
            Window window = windowForTests;
            if (window != null) {
                window.bind(bind, scope, layout);
            } else {
                Method invoke = bind.getClass().getMethod("invoke", Object.class, Object.class);
                invoke.invoke(bind, scope, layout);
            }
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return false;
        }
    }

    /** Pauses what plays in the window, the way its Pause does, since a picture shows now. */
    private static void pause() {
        Window window = windowForTests;
        if (window != null) {
            window.pause();
            return;
        }
        for (Object player : BackgroundPlay.recentPlayers()) {
            if (BackgroundPlay.playingNow(player)) BackgroundPlay.pauseUnderSettings(player);
        }
    }

    // ---- Drawing ----

    /** Lays [page]'s picture over [layout], or nothing for a video page, and the dots of a carousel. */
    private static void draw(View layout, Shown now, @Nullable Object page, Reels reels) {
        if (!(layout instanceof ViewGroup)) return;
        Slides slides = slides((ViewGroup) layout, true);
        if (slides == null) return;
        slides.setVisibility(View.VISIBLE);
        slides.bringToFront();
        slides.dots.show(now.carousel ? now.count : 0, now.at);
        if (page == null) {
            slides.want = null;
            slides.picture.setImageDrawable(null);
            slides.picture.setVisibility(View.GONE);
            return;
        }
        slides.picture.setVisibility(View.VISIBLE);
        String url = reels.picture(page, PICTURE_SIZE);
        slides.want = url;
        Bitmap cached = url != null ? PICTURES.get(url) : null;
        slides.picture.setImageBitmap(cached);
        if (url != null && cached == null) fetch(slides, url);
        // The next page, fetched ahead so it's there when Next turns to it.
        if (now.carousel && now.at + 1 < now.count) {
            Object media = now.media.get();
            List<?> pages = media != null ? reels.pages(media) : null;
            String next = pages != null && now.at + 1 < pages.size() ? reels.picture(pages.get(now.at + 1), PICTURE_SIZE) : null;
            if (next != null && PICTURES.get(next) == null) fetch(null, next);
        }
    }

    /** Takes the pictures off [layout], for a video reel or with the switch off. */
    private static void clear(View layout) {
        synchronized (LOCK) {
            if (shown != null && shown.layout.get() == layout) shown = null;
        }
        if (!(layout instanceof ViewGroup)) return;
        Slides slides = slides((ViewGroup) layout, false);
        if (slides == null) return;
        slides.want = null;
        slides.picture.setImageDrawable(null);
        slides.setVisibility(View.GONE);
    }

    /** Fetches [url] off the main thread, and shows it in [slides] if they still want it. */
    private static void fetch(@Nullable Slides slides, String url) {
        Context context = slides != null ? slides.getContext() : Utils.getContext();
        if (context == null) return;
        Runnable work = () -> {
            Window window = windowForTests;
            Bitmap picture = window != null ? window.fetch(context, url, PICTURE_SIZE) : PictureFetch.fetch(context, url, PICTURE_SIZE);
            if (picture == null) return;
            PICTURES.put(url, picture);
            if (slides == null) return;
            Utils.runOnMainThreadNowOrLater(() -> {
                if (url.equals(slides.want)) slides.picture.setImageBitmap(picture);
            });
        };
        Executor tests = workForTests;
        if (tests != null) {
            tests.execute(work);
            return;
        }
        synchronized (LOCK) {
            if (fetcher == null) {
                fetcher = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "HushGram window pictures");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            fetcher.execute(work);
        }
    }

    @Nullable
    private static Slides slides(ViewGroup layout, boolean make) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof Slides) return (Slides) child;
        }
        if (!make) return null;
        Slides slides = new Slides(layout.getContext());
        layout.addView(slides, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return slides;
    }

    private static Reels reels() {
        Reels reels = reelsForTests;
        return reels != null ? reels : INSTAGRAM;
    }

    /** Instagram's reads: the patch-written {@link #reelMedia}, and the bridges the download patches share. */
    private static final Reels INSTAGRAM = new Reels() {
        @Nullable
        @Override
        public Object media(Object reel) {
            return reelMedia(reel);
        }

        @Nullable
        @Override
        public Integer type(Object media) {
            return InstagramMedia.mediaType(media);
        }

        @Nullable
        @Override
        public List<?> pages(Object media) {
            return InstagramMedia.carouselMedia(media);
        }

        @Nullable
        @Override
        public String picture(Object media, int size) {
            Object versions = InstagramMedia.imageVersions(media);
            List<?> candidates = versions != null ? InstagramMedia.imageCandidates(versions) : null;
            if (candidates == null) return null;
            Object best = null;
            int bestWidth = 0;
            for (Object candidate : candidates) {
                if (candidate == null || InstagramMedia.candidateUrl(candidate) == null) continue;
                int width = InstagramMedia.candidateWidth(candidate);
                boolean better = best == null
                        || (width >= size ? bestWidth < size || width < bestWidth : bestWidth < size && width > bestWidth);
                if (better) {
                    best = candidate;
                    bestWidth = width;
                }
            }
            return best != null ? InstagramMedia.candidateUrl(best) : null;
        }
    };

    /** The pictures and dots over the window's video layout. */
    @SuppressLint("ViewConstructor")
    static final class Slides extends FrameLayout {
        final ImageView picture;
        final Dots dots;
        @Nullable
        String want;

        Slides(Context context) {
            super(context);
            setClickable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            picture = new ImageView(context);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setBackgroundColor(Color.BLACK);
            addView(picture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            dots = new Dots(context);
            float density = context.getResources().getDisplayMetrics().density;
            LayoutParams place = new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(16 * density), android.view.Gravity.BOTTOM);
            place.bottomMargin = Math.round(6 * density);
            addView(dots, place);
            // Over the TextureView the player adds after.
            setTranslationZ(8 * density);
        }
    }

    /** A carousel's dots: one per page, the page showing lit. */
    static final class Dots extends View {
        private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        int count;
        int at;

        Dots(Context context) {
            super(context);
            lit.setColor(Color.WHITE);
            dim.setColor(Color.argb(140, 255, 255, 255));
            edge.setColor(Color.argb(110, 0, 0, 0));
        }

        void show(int pages, int page) {
            count = pages;
            at = page;
            setVisibility(pages > 1 ? VISIBLE : GONE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (count < 2) return;
            float density = getResources().getDisplayMetrics().density;
            float radius = 3 * density;
            float gap = 5 * density;
            float width = getWidth();
            float step = Math.min(radius * 2 + gap, (width - 2 * radius) / Math.max(1, count));
            float r = Math.min(radius, step / 2.5f);
            float start = (width - step * (count - 1)) / 2f;
            float y = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                float x = start + i * step;
                canvas.drawCircle(x, y, r + density, edge);
                canvas.drawCircle(x, y, r, i == at ? lit : dim);
            }
        }
    }
}
