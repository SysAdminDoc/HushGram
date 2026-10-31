/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.Adapter;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ViewWatcher;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Carousel arrows patch (#417): a back and a next arrow over each feed carousel.
 *
 * <p>A feed carousel is Instagram's own pager, found as the nearest of a few parents of a page frame
 * ({@link #PAGE} or {@link #VIDEO_PAGE}, the same frames Peek at the next carousel page looks for)
 * that has the pager's page getters, looked up by name ({@link Pager}). Each one found gets two round
 * arrows, added to the nearest frame layout above it and kept over its left and right edges, halfway
 * down. The back arrow shows from the second page on and the next arrow up to the second to last, so
 * a carousel of one page shows neither. A tap slides the carousel one page through the pager's own
 * animated page change ({@link CarouselTargets#turn}), the call Instagram's own buttons make, so the
 * dots, the page counter and a video's playback follow as they do after a swipe.
 *
 * <p>Like {@link CarouselPeek}, a resumed activity joins the shared view watcher while the switch is
 * on: each layout looks through the window once for carousels, and each frame only checks the
 * carousels already holding arrows, so the arrows follow a swipe. A carousel scrolled out of the
 * window loses its arrows. With the switch off, HushGram paused or the settings not read, every
 * arrow is taken away. Anything thrown leaves the screen as Instagram drew it.
 */
public final class CarouselArrows {
    static final String PAGE = CarouselPeek.PAGE;
    static final String VIDEO_PAGE = CarouselPeek.VIDEO_PAGE;

    /** How many parents of a page frame are asked whether they're its pager. */
    static final int PAGER_DEPTH = CarouselPeek.PAGER_DEPTH;

    /** How many parents of a pager are asked whether they're a frame layout that can hold the arrows. */
    static final int HOST_DEPTH = 4;

    /** Each arrow's round button, the space around it that still takes a tap, and its gap from the carousel's edge, in dp. */
    static final float BUTTON = 28, TOUCH = 40, MARGIN = 8;

    static final String HOOK = "carousel arrows";
    static final String COUNT_SHOWN = "carousels with arrows";

    /** What a tap on an arrow does with the pager and the page it moves to. Tests stand in for the patched bridge. */
    interface Turn {
        void to(View pager, int page);
    }

    private static final Turn PATCHED = (pager, page) -> CarouselTargets.turn(pager, page, 0f);
    private static Turn turn = PATCHED;

    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** Each pager given arrows, with them. Main thread only. */
    private static final Map<View, Arrows> pagers = new WeakHashMap<>();
    /** The page getters of each view class asked about, or null for a class that isn't a pager. */
    private static final Map<Class<?>, Pager> kinds = new WeakHashMap<>();
    private static volatile boolean registered;
    private static volatile boolean logged;

    /**
     * A pager's page getters: the page showing, counted among the carousel's pages, the same page
     * counted the way the pager counts when it moves (they differ only for a pager that loops), and
     * the adapter holding the pages.
     */
    static final class Pager {
        final Method index, raw, adapter;

        Pager(Method index, Method raw, Method adapter) {
            this.index = index;
            this.raw = raw;
            this.adapter = adapter;
        }

        /**
         * [kind]'s page getters, or null when it isn't a pager or lacks any of them. A class with
         * {@code getCurrentDataIndex} is Instagram's pager, so what else it lacks is reported.
         */
        @Nullable
        static Pager of(Class<?> kind) {
            Method index;
            try {
                index = kind.getMethod("getCurrentDataIndex");
            } catch (NoSuchMethodException notAPager) {
                return null;
            }
            if (index.getReturnType() != int.class) return null;
            String looking = "getCurrentRawDataIndex";
            try {
                Method raw = kind.getMethod(looking);
                looking = "getAdapter";
                Method adapter = kind.getMethod(looking);
                if (raw.getReturnType() != int.class) return missing(kind, "getCurrentRawDataIndex");
                if (!Adapter.class.isAssignableFrom(adapter.getReturnType())) return missing(kind, looking);
                return new Pager(index, raw, adapter);
            } catch (NoSuchMethodException gone) {
                return missing(kind, looking);
            }
        }

        @Nullable
        private static Pager missing(Class<?> kind, String name) {
            HookStatus.missingMember(FamilyNames.CAROUSEL_ARROWS, "pager member", kind.getName(), name);
            return null;
        }

        int index(View pager) throws ReflectiveOperationException {
            return (Integer) index.invoke(pager);
        }

        int raw(View pager) throws ReflectiveOperationException {
            return (Integer) raw.invoke(pager);
        }

        int count(View pager) throws ReflectiveOperationException {
            Adapter pages = (Adapter) adapter.invoke(pager);
            return pages == null ? 0 : pages.getCount();
        }
    }

    private CarouselArrows() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !SettingsStatus.carouselArrows() || !(context instanceof Application)) return;
            registered = true;
            ViewWatcher.onResumeAndPause((Application) context, CarouselArrows::watch, CarouselArrows::unwatch);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CAROUSEL_ARROWS, HOOK + " start", failure);
        }
    }

    /** Whether carousels get arrows now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.CAROUSEL_ARROWS.get();
    }

    /** Starts watching [activity] if the switch is on, or takes every arrow away if it's off. */
    static void watch(Activity activity) {
        try {
            if (!wanted()) {
                putBack();
                return;
            }
            Session session;
            synchronized (sessions) {
                session = sessions.get(activity);
                if (session == null) {
                    session = new Session(activity);
                    sessions.put(activity, session);
                }
            }
            session.attach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CAROUSEL_ARROWS, HOOK, failure);
        }
    }

    /** The switch was flipped in HushGram settings, over [activity], which stays resumed. Posted, so it reads the saved value. */
    public static void switchChanged(@Nullable Activity activity) {
        if (activity != null) Utils.runOnMainThread(() -> watch(activity));
    }

    static void unwatch(Activity activity) {
        try {
            Session session;
            synchronized (sessions) {
                session = sessions.remove(activity);
            }
            if (session != null) session.detach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CAROUSEL_ARROWS, HOOK, failure);
        }
    }

    @Nullable
    private static Pager kind(View view) {
        Class<?> kind = view.getClass();
        if (kinds.containsKey(kind)) return kinds.get(kind);
        Pager pager = Pager.of(kind);
        kinds.put(kind, pager);
        return pager;
    }

    /** The pager holding the page frame [frame]: the nearest of its first {@link #PAGER_DEPTH} parents that is one. */
    @Nullable
    static ViewGroup pagerOf(View frame) {
        ViewParent parent = frame.getParent();
        for (int i = 0; i < PAGER_DEPTH && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            if (kind((View) parent) != null) return (ViewGroup) parent;
        }
        return null;
    }

    /** The frame layout the arrows of [pager] go in: the nearest of its first {@link #HOST_DEPTH} parents that is one. */
    @Nullable
    static FrameLayout hostOf(View pager) {
        ViewParent parent = pager.getParent();
        for (int i = 0; i < HOST_DEPTH && parent instanceof ViewGroup; i++, parent = parent.getParent()) {
            if (parent instanceof FrameLayout) return (FrameLayout) parent;
        }
        return null;
    }

    /** Slides [pager] [step] pages on, when that page is one of its own. */
    static void go(View pager, int step) {
        try {
            Pager methods = kind(pager);
            if (methods == null) return;
            int at = methods.index(pager), count = methods.count(pager);
            if (at < 0 || at + step < 0 || at + step >= count) return;
            turn.to(pager, methods.raw(pager) + step);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CAROUSEL_ARROWS, HOOK + " tap", failure);
        }
    }

    /** Gives [pager] arrows in its host, if it has none there yet. True when any were added. */
    static boolean give(ViewGroup pager) {
        Arrows arrows = pagers.get(pager);
        FrameLayout host = hostOf(pager);
        if (host == null) return false;
        if (arrows != null) {
            if (arrows.host.get() == host) return false;
            arrows.remove();
        }
        pagers.put(pager, new Arrows(pager, host));
        return true;
    }

    /**
     * Shows, hides and places the arrows of every carousel holding some, and takes them off a carousel
     * that left the window or its host. True when anything changed.
     */
    static boolean follow() throws ReflectiveOperationException {
        boolean changed = false;
        for (Map.Entry<View, Arrows> entry : new ArrayList<>(pagers.entrySet())) {
            View pager = entry.getKey();
            Arrows arrows = entry.getValue();
            if (pager == null) continue;
            if (!arrows.holds(pager)) {
                arrows.remove();
                pagers.remove(pager);
                changed = true;
                continue;
            }
            changed |= arrows.follow(pager, kind(pager));
        }
        return changed;
    }

    /** Takes every arrow away, and forgets the carousels. */
    static void putBack() {
        for (Arrows arrows : new ArrayList<>(pagers.values())) {
            if (arrows != null) arrows.remove();
        }
        pagers.clear();
    }

    /** Tests give the page frame an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), PAGE, id);
    }

    static void videoIdForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), VIDEO_PAGE, id);
    }

    static void turnForTests(@Nullable Turn stand) {
        turn = stand == null ? PATCHED : stand;
    }

    static int withArrowsForTests() {
        return pagers.size();
    }

    @Nullable
    static View backForTests(View pager) {
        Arrows arrows = pagers.get(pager);
        return arrows == null ? null : arrows.back;
    }

    @Nullable
    static View nextForTests(View pager) {
        Arrows arrows = pagers.get(pager);
        return arrows == null ? null : arrows.next;
    }

    public static boolean listeningForTests(Activity activity) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && session.listening();
        }
    }

    public static void resetForTests() {
        ids.clear();
        synchronized (sessions) {
            for (Session session : new ArrayList<>(sessions.values())) session.detach();
            sessions.clear();
        }
        putBack();
        kinds.clear();
        turn = PATCHED;
        logged = false;
    }

    /** One carousel's two arrows and the frame layout holding them. */
    private static final class Arrows {
        final WeakReference<FrameLayout> host;
        final Arrow back, next;
        private final Rect bounds = new Rect();

        Arrows(View pager, FrameLayout host) {
            this.host = new WeakReference<>(host);
            Context context = host.getContext();
            back = new Arrow(context, false);
            next = new Arrow(context, true);
            back.setContentDescription(L10n.t(context, "Previous page"));
            next.setContentDescription(L10n.t(context, "Next page"));
            WeakReference<View> carousel = new WeakReference<>(pager);
            back.setOnClickListener(tapped -> {
                View held = carousel.get();
                if (held != null) go(held, -1);
            });
            next.setOnClickListener(tapped -> {
                View held = carousel.get();
                if (held != null) go(held, 1);
            });
            int size = Math.round(TOUCH * context.getResources().getDisplayMetrics().density);
            for (Arrow arrow : new Arrow[] {back, next}) {
                arrow.setVisibility(View.INVISIBLE);
                host.addView(arrow, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));
            }
        }

        /** Whether [pager] is still in the window, under the host its arrows are in. */
        boolean holds(View pager) {
            FrameLayout frame = host.get();
            if (frame == null || !pager.isAttachedToWindow() || back.getParent() != frame) return false;
            for (ViewParent parent = pager.getParent(); parent != null; parent = parent.getParent()) {
                if (parent == frame) return true;
            }
            return false;
        }

        /** Shows the arrows that lead to a page of [pager]'s and places them over its edges. True when anything changed. */
        boolean follow(View pager, @Nullable Pager methods) throws ReflectiveOperationException {
            FrameLayout frame = host.get();
            boolean shown = methods != null && frame != null && pager.isShown() && pager.getWidth() > 0;
            int at = shown ? methods.index(pager) : -1;
            int count = shown ? methods.count(pager) : 0;
            boolean changed = show(back, at > 0 && at < count);
            changed |= show(next, at >= 0 && at < count - 1);
            if (!shown) return changed;
            bounds.set(0, 0, pager.getWidth(), pager.getHeight());
            frame.offsetDescendantRectToMyCoords(pager, bounds);
            float density = pager.getResources().getDisplayMetrics().density;
            float inset = (MARGIN - (TOUCH - BUTTON) / 2) * density;
            float top = bounds.centerY() - TOUCH * density / 2;
            changed |= place(back, bounds.left + inset, top);
            changed |= place(next, bounds.right - inset - TOUCH * density, top);
            return changed;
        }

        private static boolean show(View arrow, boolean shown) {
            int visibility = shown ? View.VISIBLE : View.INVISIBLE;
            if (arrow.getVisibility() == visibility) return false;
            // Invisible, not gone, so the host isn't laid out again each time a carousel reaches its end.
            arrow.setVisibility(visibility);
            return true;
        }

        private static boolean place(View arrow, float left, float top) {
            float x = Math.round(left) - arrow.getLeft(), y = Math.round(top) - arrow.getTop();
            if (arrow.getTranslationX() == x && arrow.getTranslationY() == y) return false;
            arrow.setTranslationX(x);
            arrow.setTranslationY(y);
            return true;
        }

        void remove() {
            for (View arrow : new View[] {back, next}) {
                ViewParent parent = arrow.getParent();
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(arrow);
            }
        }
    }

    /** A round arrow button: a light disc with a dark chevron, so it reads on dark and bright pages alike. */
    static final class Arrow extends View {
        private final boolean forward;
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevron = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Arrow(Context context, boolean forward) {
            super(context);
            this.forward = forward;
            disc.setColor(0xE6FFFFFF);
            chevron.setColor(0xFF262626);
            chevron.setStyle(Paint.Style.STROKE);
            chevron.setStrokeCap(Paint.Cap.ROUND);
            chevron.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
            float density = context.getResources().getDisplayMetrics().density;
            setElevation(2 * density);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int radius = Math.round(radius(view));
                    int x = view.getWidth() / 2, y = view.getHeight() / 2;
                    outline.setOval(x - radius, y - radius, x + radius, y + radius);
                }
            });
        }

        private static float radius(View view) {
            return BUTTON * view.getResources().getDisplayMetrics().density / 2;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f, y = getHeight() / 2f, radius = radius(this);
            canvas.drawCircle(x, y, radius, disc);
            float reach = radius * 0.32f, lean = forward ? 1 : -1;
            chevron.setStrokeWidth(radius * 0.15f);
            path.reset();
            path.moveTo(x - lean * reach / 2, y - reach);
            path.lineTo(x + lean * reach / 2, y);
            path.lineTo(x - lean * reach / 2, y + reach);
            canvas.drawPath(path, chevron);
        }
    }

    /** One activity's place in the shared view watcher. */
    private static final class Session implements ViewWatcher.Member {
        private final WeakReference<Activity> activity;

        Session(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        boolean listening() {
            Activity owner = activity.get();
            return owner != null && ViewWatcher.listening(owner, this);
        }

        void attach() {
            Activity owner = activity.get();
            if (owner == null || ViewWatcher.joined(owner, this)) return;
            HookStatus.invoked(FamilyNames.CAROUSEL_ARROWS);
            ViewWatcher.join(owner, this);
        }

        void detach() {
            Activity owner = activity.get();
            if (owner != null) ViewWatcher.leave(owner, this);
        }

        @Override
        public boolean wanted() {
            return CarouselArrows.wanted();
        }

        @Override
        public boolean preDraw(Activity owner, boolean laidOut) {
            boolean changed = false;
            try {
                if (!wanted()) {
                    putBack();
                    detach();
                    return false;
                }
                if (laidOut) {
                    int id = ids.resolve(owner.getResources(), owner.getPackageName(), PAGE, false);
                    if (id == 0) {
                        HookStatus.missingViewId(FamilyNames.CAROUSEL_ARROWS, PAGE);
                        return false;
                    }
                    HookStatus.recoveredViewId(FamilyNames.CAROUSEL_ARROWS, PAGE);
                    int video = ids.resolve(owner.getResources(), owner.getPackageName(), VIDEO_PAGE, false);
                    changed = walk(owner.getWindow().getDecorView(), id, video, new int[] {ReelCorners.MAX_VIEWS});
                }
                changed |= follow();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CAROUSEL_ARROWS, HOOK, failure);
            }
            // Arrows shown, hidden or moved now are drawn on the next frame.
            return changed;
        }

        /** Gives arrows to the carousel of each photo page frame with [id], or video page frame with [video], under [view]. True when any were added. */
        private boolean walk(View view, int id, int video, int[] budget) {
            if (budget[0]-- <= 0) return false;
            if (view.getId() == id || (video != 0 && view.getId() == video)) {
                ViewGroup pager = pagerOf(view);
                if (pager == null || !give(pager)) return false;
                HookStatus.counted(FamilyNames.CAROUSEL_ARROWS, COUNT_SHOWN);
                if (!logged) {
                    logged = true;
                    Logger.printDebug(() -> "Carousel arrows: gave a carousel its arrows");
                }
                return true;
            }
            boolean changed = false;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) changed |= walk(group.getChildAt(i), id, video, budget);
            }
            return changed;
        }
    }
}
