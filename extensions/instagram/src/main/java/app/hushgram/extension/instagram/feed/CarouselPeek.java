/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ViewWatcher;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Peek at the next carousel page (#361), part of the Rounded post corners patch.
 *
 * <p>A feed carousel is Instagram's own pager, which places each page {@code offset * (page size +
 * spacing)} from the one showing, leaves the page's scale alone and keeps only the pages in view
 * plus an extra buffer attached. While the switch is on, each carousel found on screen gets, through
 * the pager's own methods, a page size {@link #INSET} + {@link #GAP} + {@link #PEEK} narrower than
 * itself, a spacing of {@link #GAP} and a buffer of at least one page, and each of its pages is
 * scaled down to that size from a point that keeps its left edge {@link #INSET} in. So the page
 * showing sits inset, the next one's left edge shows at the right, and swiping, snapping and the
 * dots stay Instagram's, measured in the narrower pages.
 *
 * <p>A pager is the nearest of a few parents of a page frame ({@link #PAGE}) that has those methods,
 * looked up by name ({@link Pager}), so a build that renamed any leaves its carousels as they are.
 * Nothing of Instagram's code changes: like {@link ReelCorners}, a resumed activity joins the shared
 * view watcher while the switch is on, and each layout looks through the window once. A page added
 * as the carousel is swiped is laid out, so it's scaled before it shows. With the switch off,
 * HushGram paused or the settings not read, every pager and page touched gets what it had back.
 * Anything thrown leaves the screen as Instagram drew it.
 */
public final class CarouselPeek {
    /** The frame each photo page of a feed carousel sits in, a few levels under the pager. */
    static final String PAGE = "carousel_image_media_group";

    /** The frame of a video page, so a carousel showing only videos peeks too. A build without it still finds photo pages. */
    static final String VIDEO_PAGE = "carousel_video_media_group";

    /** How far in the page showing sits from the left, the gap between pages and how much of the next shows, in dp. */
    static final float INSET = 12, GAP = 8, PEEK = 32;

    /** How many parents of a page frame are asked whether they're its pager. */
    static final int PAGER_DEPTH = 6;

    static final String HOOK = "carousel peek";
    static final String COUNT_PEEKED = "carousels peeking";

    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** Each pager changed, with what it had before. Main thread only. */
    private static final Map<View, Before> pagers = new WeakHashMap<>();
    /** Each page scaled. */
    private static final Map<View, Boolean> pages = new WeakHashMap<>();
    /** The pager methods of each view class asked about, or null for a class that isn't a pager. */
    private static final Map<Class<?>, Pager> kinds = new WeakHashMap<>();
    private static volatile boolean registered;
    private static volatile boolean logged;

    /** What a pager had before it peeked, and the width it was set up for. */
    private static final class Before {
        final float spacing;
        final int buffer;
        final Object bias;
        int width;

        Before(float spacing, int buffer, Object bias) {
            this.spacing = spacing;
            this.buffer = buffer;
            this.bias = bias;
        }
    }

    /**
     * A pager's methods: the page size and spacing setters, the spacing and buffer getters, the
     * Kotlin default-argument bridge of the buffer setter, whose own name is minified but whose
     * bridge keeps {@code setExtraBufferSize$default}, and the buffer bias: its setter, the field
     * holding it and the enum's {@link #NEXT_ONLY}. A pager left biased the way the last swipe went
     * drops the next page after a swipe back, so a peeking pager keeps the pages after the one showing.
     */
    static final class Pager {
        static final String NEXT_ONLY = "BIAS_NEXT_ONLY";

        final Method size, spacing, spacingNow, bufferNow, buffer, bias;
        final Field biasNow;
        final Object nextOnly;

        Pager(Method size, Method spacing, Method spacingNow, Method bufferNow, Method buffer, Method bias, Field biasNow,
                Object nextOnly) {
            this.size = size;
            this.spacing = spacing;
            this.spacingNow = spacingNow;
            this.bufferNow = bufferNow;
            this.buffer = buffer;
            this.bias = bias;
            this.biasNow = biasNow;
            this.nextOnly = nextOnly;
        }

        /**
         * [kind]'s pager methods, or null when it isn't a pager or lacks any of them. A class with
         * the page size setter is Instagram's pager, so what else it lacks is reported.
         */
        @Nullable
        @SuppressWarnings({"unchecked", "rawtypes"})
        static Pager of(Class<?> kind) {
            Method size;
            try {
                size = kind.getMethod("setOverridePageSize", int.class);
            } catch (NoSuchMethodException notAPager) {
                return null;
            }
            String looking = "setExtraBufferSize$default";
            try {
                Method buffer = null, bias = null;
                for (Method method : kind.getMethods()) {
                    Class<?>[] takes = method.getParameterTypes();
                    if (method.getName().equals("setExtraBufferSize$default") && takes.length == 6
                            && takes[0].isAssignableFrom(kind) && takes[1] == int.class && takes[2] == boolean.class
                            && takes[3] == Boolean.class && takes[4] == int.class && takes[5] == Object.class) {
                        buffer = method;
                    } else if (method.getName().equals("setBufferBias") && takes.length == 1 && takes[0].isEnum()) {
                        bias = method;
                    }
                }
                if (buffer == null) return missing(kind, looking);
                if (bias == null) return missing(kind, looking = "setBufferBias");
                Class<?> biasKind = bias.getParameterTypes()[0];
                looking = "its one bias field";
                Field biasNow = null;
                for (Field field : kind.getFields()) {
                    if (field.getType() != biasKind || Modifier.isStatic(field.getModifiers())) continue;
                    if (biasNow != null) return missing(kind, looking);
                    biasNow = field;
                }
                if (biasNow == null) return missing(kind, looking);
                looking = NEXT_ONLY;
                Object nextOnly = Enum.valueOf((Class) biasKind, NEXT_ONLY);
                looking = "setPageSpacing, getPageSpacing or getExtraBufferSize";
                return new Pager(size, kind.getMethod("setPageSpacing", float.class), kind.getMethod("getPageSpacing"),
                        kind.getMethod("getExtraBufferSize"), buffer, bias, biasNow, nextOnly);
            } catch (NoSuchMethodException | IllegalArgumentException gone) {
                return missing(kind, looking);
            }
        }

        @Nullable
        private static Pager missing(Class<?> kind, String name) {
            HookStatus.missingMember(FamilyNames.ROUNDED_POST_CORNERS, "pager member", kind.getName(), name);
            return null;
        }

        /** Sets [pager]'s extra buffer to [pages], leaving the bridge's other arguments at their defaults. */
        void buffer(View pager, int pages) throws ReflectiveOperationException {
            buffer.invoke(null, pager, pages, false, null, 6, null);
        }
    }

    private CarouselPeek() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !SettingsStatus.roundedPostCorners() || !(context instanceof Application)) return;
            registered = true;
            ViewWatcher.onResumeAndPause((Application) context, CarouselPeek::watch, CarouselPeek::unwatch);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK + " start", failure);
        }
    }

    /** Whether carousels peek now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.PEEK_CAROUSEL_PAGES.get();
    }

    /** Starts watching [activity] if the switch is on, or puts back every carousel changed if it's off. */
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
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK, failure);
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
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK, failure);
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

    /**
     * Narrows [pager]'s pages to its width, if it wasn't set up for that width yet, and scales each
     * page it holds now that isn't scaled yet. True when anything changed.
     */
    static boolean peek(ViewGroup pager, float density) throws ReflectiveOperationException {
        Pager methods = kind(pager);
        int width = pager.getWidth();
        if (methods == null || width <= 0) return false;
        float inset = INSET * density, gap = GAP * density;
        int page = Math.round(width - inset - gap - PEEK * density);
        if (page <= 0) return false;
        float scale = page / (float) width;
        boolean changed = false;
        Before before = pagers.get(pager);
        if (before == null) {
            before = new Before((Float) methods.spacingNow.invoke(pager), (Integer) methods.bufferNow.invoke(pager),
                    methods.biasNow.get(pager));
            pagers.put(pager, before);
        }
        if (before.width != width) {
            before.width = width;
            methods.size.invoke(pager, page);
            methods.bias.invoke(pager, methods.nextOnly);
            methods.buffer(pager, Math.max(1, before.buffer));
            // The spacing setter lays the pages out again at the new size.
            methods.spacing.invoke(pager, gap);
            changed = true;
        } else if (methods.biasNow.get(pager) != methods.nextOnly) {
            // Kept from the next layout on, should Instagram set its bias again.
            methods.bias.invoke(pager, methods.nextOnly);
        }
        float pivot = inset / (1 - scale);
        for (int i = 0; i < pager.getChildCount(); i++) {
            View child = pager.getChildAt(i);
            // A pivot set by hand stays put, so a page scaled before its first layout gets its middle once it has a height.
            float middle = child.getHeight() / 2f;
            if (child.getScaleX() == scale && child.getPivotX() == pivot && child.getPivotY() == middle
                    && pages.containsKey(child)) continue;
            pages.put(child, Boolean.TRUE);
            child.setPivotX(pivot);
            child.setPivotY(middle);
            child.setScaleX(scale);
            child.setScaleY(scale);
            changed = true;
        }
        return changed;
    }

    /** Gives every pager and page changed what it had back, and forgets them. */
    static void putBack() {
        for (Map.Entry<View, Before> entry : new ArrayList<>(pagers.entrySet())) {
            View pager = entry.getKey();
            Pager methods = pager == null ? null : kind(pager);
            if (methods == null) continue;
            try {
                // -1 is the pager's own "no override": its pages take its width again.
                methods.size.invoke(pager, -1);
                methods.bias.invoke(pager, entry.getValue().bias);
                methods.buffer(pager, entry.getValue().buffer);
                methods.spacing.invoke(pager, entry.getValue().spacing);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK, failure);
            }
        }
        for (View page : new ArrayList<>(pages.keySet())) {
            if (page == null) continue;
            page.setScaleX(1);
            page.setScaleY(1);
            page.resetPivot();
        }
        pagers.clear();
        pages.clear();
    }

    /** Tests give the page frame an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), PAGE, id);
    }

    static void videoIdForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), VIDEO_PAGE, id);
    }

    static int peekingForTests() {
        return pagers.size();
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
        pagers.clear();
        pages.clear();
        kinds.clear();
        logged = false;
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
            HookStatus.invoked(FamilyNames.ROUNDED_POST_CORNERS);
            ViewWatcher.join(owner, this);
        }

        void detach() {
            Activity owner = activity.get();
            if (owner != null) ViewWatcher.leave(owner, this);
        }

        @Override
        public boolean wanted() {
            return CarouselPeek.wanted();
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
                        HookStatus.missingViewId(FamilyNames.ROUNDED_POST_CORNERS, PAGE);
                        return false;
                    }
                    HookStatus.recoveredViewId(FamilyNames.ROUNDED_POST_CORNERS, PAGE);
                    int video = ids.resolve(owner.getResources(), owner.getPackageName(), VIDEO_PAGE, false);
                    float density = owner.getResources().getDisplayMetrics().density;
                    changed = walk(owner.getWindow().getDecorView(), id, video, density, new int[] {ReelCorners.MAX_VIEWS});
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, HOOK, failure);
            }
            // A pager set up now moves its pages, so this frame is drawn again with them in place.
            return changed;
        }

        /** Peeks the carousel of each photo page frame with [id], or video page frame with [video], under [view]. True when one changed. */
        private boolean walk(View view, int id, int video, float density, int[] budget) throws ReflectiveOperationException {
            if (budget[0]-- <= 0) return false;
            if (view.getId() == id || (video != 0 && view.getId() == video)) {
                ViewGroup pager = pagerOf(view);
                if (pager == null || !peek(pager, density)) return false;
                HookStatus.counted(FamilyNames.ROUNDED_POST_CORNERS, COUNT_PEEKED);
                if (!logged) {
                    logged = true;
                    Logger.printDebug(() -> "Peek at the next carousel page: narrowed a carousel's pages");
                }
                return true;
            }
            boolean changed = false;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) changed |= walk(group.getChildAt(i), id, video, density, budget);
            }
            return changed;
        }
    }
}
