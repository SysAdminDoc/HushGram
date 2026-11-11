/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Open reel links on that reel" patch (#606).
 *
 * <p>Instagram keeps a few reels on the phone, its flash cache, so a Reels viewer has something to
 * show before the network answers. For a viewer opened from a link it does this on purpose: it puts
 * those cached reels in front of the linked reel's placeholder, plays the first one, and moves to
 * the linked reel only once it has loaded. On a slow start that's a stranger's reel for a few
 * seconds, which looks like the link opened the wrong reel.
 *
 * <p>As the viewer sets up, Instagram skips that cache when its config says so. The patch asks
 * {@link #skipsCachedReels(Object)} at that check with where the viewer was opened from, a
 * {@code ClipsViewerSource} whose constant names Instagram's build keeps. A viewer opened from a
 * link ({@link #FROM_LINK} or {@link #FROM_LINK_PRIME}) gets a yes, so it shows a loading placeholder
 * until the linked reel is there. Everything else keeps Instagram's cache.
 *
 * <p>The cache isn't the only thing that gets in first. When Instagram is already running, the
 * viewer also asks the network for more reels while it fetches the linked one, and that chunk
 * usually comes back first. Instagram adds it, then slots the linked reel in at the reel on screen
 * (or one past it) without scrolling there, so another reel plays and the linked one sits a swipe
 * away. The patch asks {@link #holdsReels} before each chunk goes in: for a link's viewer whose
 * linked reel isn't in yet it says hold, and the chunk waits. {@link #linkedReelIn(Object)} is told
 * when the viewer inserts reels at a position, which for a link's viewer is the linked reel going
 * in. The held chunks are added right after, behind it, or after {@link #WAIT_MILLIS} if the
 * linked reel never comes.
 */
public final class ReelLinks {
    /** What {@link HookStatus} counts: a viewer opened from a link that skipped the cached reels. */
    static final String SKIPPED = "reel links opened without cached reels";

    /** What {@link HookStatus} counts: a chunk of reels held until the linked reel was in. */
    static final String HELD = "reel chunks held for the linked reel";

    /** What {@link HookStatus} counts: a held chunk added once the linked reel was in. */
    static final String ADDED = "held reel chunks added after the linked reel";

    /** What {@link HookStatus} counts: a held chunk added because the linked reel took too long. */
    static final String WAITED_OUT = "held reel chunks added without the linked reel";

    /** The hooks' names in a failure report. */
    static final String VIEWER = "reels viewer from a link";
    static final String CHUNK = "reels added to a link's viewer";
    static final String LINKED = "linked reel added";

    /** The source of a viewer opened from a link, as Instagram 454 names it. */
    static final String FROM_LINK = "THIRD_PARTY_URL";

    /** The source of a viewer opened from a link Instagram warmed up first, as Instagram 454 names it. */
    static final String FROM_LINK_PRIME = "PRIME_THIRD_PARTY_URL";

    /** How long a link's viewer holds its chunks for the linked reel before adding them anyway. */
    static final long WAIT_MILLIS = 10_000;

    /** A link's viewer: the chunks it held, in the order they came, and whether its linked reel is in. */
    private static final class Viewer {
        final List<Object[]> held = new ArrayList<>();
        final Method fill;
        final Handler handler;
        boolean linked;

        Viewer(Method fill, Handler handler, boolean linked) {
            this.fill = fill;
            this.handler = handler;
            this.linked = linked;
        }
    }

    /** Each adapter the hooks have seen, until Instagram lets go of it. */
    private static final Map<Object, Viewer> VIEWERS = new WeakHashMap<>();

    /** Each adapter class's method adding a chunk, or null when it has no single one. */
    private static final Map<Class<?>, Method> FILLS = new HashMap<>();

    private ReelLinks() {
    }

    /**
     * Asked as a Reels viewer decides whether to put Instagram's cached reels in front, with where
     * it was opened from. 1 skips them: the viewer was opened from a link, the switch is on, HushGram
     * isn't paused and the settings are ready. 0 for every other source, a source that isn't an enum,
     * and anything that throws. Never throws.
     */
    public static int skipsCachedReels(Object source) {
        try {
            HookStatus.invoked(FamilyNames.REEL_LINKS);
            if (!(source instanceof Enum)) return 0;
            String name = ((Enum<?>) source).name();
            if (!name.equals(FROM_LINK) && !name.equals(FROM_LINK_PRIME)) return 0;
            if (!Utils.settingsReady() || !Settings.OPEN_REEL_LINKS_FIRST.get()) return 0;
            HookStatus.counted(FamilyNames.REEL_LINKS, SKIPPED);
            Logger.printDebug(() -> "Reels viewer opened from " + name + ": cached reels skipped");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_LINKS, VIEWER, failure);
            return 0;
        }
    }

    /**
     * Asked as Instagram adds a chunk of reels from the network to a Reels viewer, with the viewer's
     * adapter and the arguments of the call adding it: the head item, the reels, then its five flags
     * (first page, refreshing, loading, more pages, forward). 1 holds the chunk back: the viewer was
     * opened from a link ({@link #FROM_LINK}), its linked reel isn't in yet, the chunk goes after the
     * reels already there rather than refreshing them, and the switch is on. The chunk is then added
     * with the same arguments once the linked reel is in, or after {@link #WAIT_MILLIS}. 0 adds it
     * now: every other viewer, a chunk after the linked reel, and anything that throws. Never throws.
     */
    public static int holdsReels(Object adapter, Object head, Object items,
                                 int firstPage, int refreshing, int loading, int more, int forward) {
        try {
            HookStatus.invoked(FamilyNames.REEL_LINKS);
            // A refresh can clear the viewer, and a chunk that isn't forward adds nothing: never hold those.
            if (adapter == null || refreshing != 0 || forward == 0) return 0;
            if (!Utils.settingsReady() || !Settings.OPEN_REEL_LINKS_FIRST.get()) return 0;
            Object[] call = {head, items, firstPage != 0, false, loading != 0, more != 0, true};
            Viewer started = null;
            synchronized (VIEWERS) {
                Viewer viewer = VIEWERS.get(adapter);
                // Chunks after the linked reel go straight in, unless held ones still wait ahead of them.
                if (viewer != null && viewer.linked && viewer.held.isEmpty()) return 0;
                if (viewer == null) {
                    if (!FROM_LINK.equals(sourceOf(adapter))) return 0;
                    Method fill = fillOf(adapter.getClass());
                    Looper looper = Looper.myLooper();
                    if (fill == null || looper == null) return 0;
                    viewer = new Viewer(fill, new Handler(looper), false);
                    VIEWERS.put(adapter, viewer);
                    started = viewer;
                }
                viewer.held.add(call);
            }
            if (started != null) {
                WeakReference<Object> held = new WeakReference<>(adapter);
                started.handler.postDelayed(() -> release(held, WAITED_OUT), WAIT_MILLIS);
                Logger.printDebug(() -> "Reels viewer opened from a link: holding reels until the linked reel is in");
            }
            HookStatus.counted(FamilyNames.REEL_LINKS, HELD);
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_LINKS, CHUNK, failure);
            return 0;
        }
    }

    /**
     * Told as a Reels viewer inserts reels at a position, before it does, with its adapter. For a
     * link's viewer that's the linked reel going in: what the viewer held is added right after, once
     * Instagram is done placing and showing the linked reel, and later chunks go straight in. A
     * viewer that held nothing yet just remembers its linked reel is in. Never throws.
     */
    public static void linkedReelIn(Object adapter) {
        try {
            HookStatus.invoked(FamilyNames.REEL_LINKS);
            if (adapter == null) return;
            Viewer viewer;
            synchronized (VIEWERS) {
                viewer = VIEWERS.get(adapter);
                if (viewer == null) {
                    VIEWERS.put(adapter, new Viewer(null, null, true));
                    return;
                }
                if (viewer.linked) return;
                viewer.linked = true;
            }
            WeakReference<Object> held = new WeakReference<>(adapter);
            viewer.handler.post(() -> release(held, ADDED));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_LINKS, LINKED, failure);
        }
    }

    /** Adds what [adapter]'s viewer held, in the order it came, and lets every later chunk straight in. */
    private static void release(WeakReference<Object> held, String why) {
        Object adapter = held.get();
        if (adapter == null) return;
        List<Object[]> calls;
        Method fill;
        synchronized (VIEWERS) {
            Viewer viewer = VIEWERS.get(adapter);
            if (viewer == null) return;
            viewer.linked = true;
            if (viewer.held.isEmpty()) return;
            calls = new ArrayList<>(viewer.held);
            viewer.held.clear();
            fill = viewer.fill;
        }
        for (Object[] call : calls) {
            try {
                fill.invoke(adapter, call);
                HookStatus.counted(FamilyNames.REEL_LINKS, why);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_LINKS, CHUNK, failure);
            }
        }
        Logger.printDebug(() -> "Reels viewer opened from a link: " + calls.size() + " " + why);
    }

    /**
     * Where [adapter]'s viewer was opened from, read from the config it holds: the constant's name
     * when every source it leads to agrees, null when there's none or they differ.
     */
    static String sourceOf(Object adapter) {
        String found = null;
        for (Field[] path : ReelsTopBar.sourceFieldsOf(adapter.getClass())) {
            Object value;
            try {
                value = path[0].get(adapter);
                if (value != null && path.length > 1) value = path[1].get(value);
            } catch (IllegalAccessException | RuntimeException unreadable) {
                continue;
            }
            if (!(value instanceof Enum)) continue;
            String name = ((Enum<?>) value).name();
            if (found != null && !found.equals(name)) return null;
            found = name;
        }
        return found;
    }

    /**
     * [type]'s method adding a chunk of reels, found by its shape: an instance method taking an item,
     * a List and five booleans and returning nothing. Null unless there's exactly one, so the chunk
     * is never held when it couldn't be added later. Looked up once per class.
     */
    static Method fillOf(Class<?> type) {
        synchronized (FILLS) {
            if (FILLS.containsKey(type)) return FILLS.get(type);
        }
        Method found = null;
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) continue;
            if (parameters.length != 7 || parameters[0].isPrimitive() || parameters[1] != List.class) continue;
            boolean flags = true;
            for (int at = 2; at < parameters.length; at++) flags &= parameters[at] == boolean.class;
            if (!flags) continue;
            found = method;
            count++;
        }
        Method fill = count == 1 ? found : null;
        if (fill != null) fill.setAccessible(true);
        synchronized (FILLS) {
            FILLS.put(type, fill);
        }
        return fill;
    }

    /** Forgets every viewer, for tests. */
    static void forgetForTests() {
        synchronized (VIEWERS) {
            VIEWERS.clear();
        }
    }
}
