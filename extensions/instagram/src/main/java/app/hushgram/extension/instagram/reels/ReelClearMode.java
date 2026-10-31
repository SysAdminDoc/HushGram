/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.LinkedHashSet;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keep clear mode on (#138): once you turn Instagram's clear mode on for a reel, the next reels you
 * swipe to start in it too, until you leave it yourself.
 *
 * <p>Instagram 450 calls clear mode its fullscreen view and keeps it per reel. Turning it on goes
 * through one handler, whose action reads the reel the pager is on, sets that reel's own flag and
 * hides its buttons. A reel in clear mode draws an exit button, and when that button leaves the
 * screen Instagram turns clear mode off for its reel through the same handler, naming
 * {@link #SWIPED_AWAY} as the reason. That is why a swipe to the next reel ends it, and why every
 * reel has to be put in clear mode by hand.
 *
 * <p>The patch tells this class each time the handler turned clear mode on ({@link #entered}) and
 * off ({@link #left}), which reel Instagram's turn-on is about to act on ({@link #current}), each
 * page the Reels pager lands on ({@link #paged}) and each reel whose exit button Instagram built
 * ({@link #built}). When a reel you put in clear mode leaves it because it left the screen, this
 * class asks the handler to turn clear mode on again, with the reason your own turn-on gave, so
 * Instagram does to the next reel exactly what your tap would have done.
 *
 * <p>Instagram's clear mode fades a reel's buttons, author row and top bar out but leaves the seek
 * bar of a long reel on screen (#277). The patch asks {@link #scrubber} where the seek bar row
 * decides whether to draw it, with the reel's own clear mode flag, and while the switch is on a reel
 * in clear mode draws no seek bar. That is the only thing this class hides.
 *
 * <p>Every reel page shares one alpha for its buttons, author row and top bar. Clear mode's fade
 * animates it to hidden, and Instagram resets it to shown when it binds a page at rest, behind the
 * fade's back, which brought every button back on a reel still in clear mode several swipes in
 * (#233). The patch asks {@link #chrome} each time a page writes that alpha, and while a reel you
 * put in clear mode is in it, asking for fully shown keeps the alpha as it is. The fade out never
 * asks for fully shown, and leaving clear mode yourself forgets it before Instagram's fade back in
 * starts, so both go through.
 *
 * <p>Instagram's turn-on acts on whatever reel the pager is on, so {@link #current} looks at that
 * reel first and holds the turn-on back when it would be the wrong one: the reel that just left
 * clear mode, which is what the pager still names when the viewer is closing or the swipe hasn't
 * landed, or a page with no exit button yet, which could never leave clear mode again. A swipe
 * that hasn't landed, or a page whose button isn't built yet, is tried again each time the pager
 * reports a page or a button is built, until {@link #RETRY_WINDOW_MS} after the reel left (#233).
 * A page report that still names the reel that left, the wait running out, or anything else ends
 * the carrying until you turn clear mode on again.
 *
 * <p>A swipe back takes the exit button off the screen quickly, since it sits low on the page, so
 * a reel leaves clear mode even when the swipe snaps back to it without landing. The pager reports
 * no page then. The patch tells {@link #settled} each time the pager comes to rest, and when a reel
 * still waits with no page reported since, the next try may put that same reel back in clear mode,
 * as long as the pager is still on screen: leaving Reels takes the exit button away the same way,
 * and turning clear mode on for a viewer nobody sees would act on whatever shows instead (#233).
 *
 * <p>Start every reel in clear mode (#138) doesn't wait for your tap. The patch hands this class
 * the handler as soon as the Reels viewer gives it its turn-on action ({@link #ready}), and the
 * first reel with an exit button, by a page report or the button being built, gets clear mode
 * through that handler with the reason Instagram's own menu row gives ({@link #MENU}). From there
 * it's carried like your own. Leaving it yourself stops the starting too, until another Reels
 * viewer is set up, and a reel that couldn't be carried to gets it started instead.
 *
 * <p>Leaving clear mode yourself, by its exit button or the menu, forgets it, and the next reels
 * show their buttons. What is kept is small and holds no view: whether clear mode is wanted, the
 * reason your turn-on gave (an enum constant), a few reel ids, and for the length of the wait the
 * handler, weakly, as the handler is held for starting. It lives in memory only. Nothing is carried
 * or started while the switch is off, HushGram is paused, the settings aren't ready, or anything
 * here fails, and Instagram's own turn-on and turn-off always run as they would.
 *
 * <p>The hooks run on Instagram's main thread, where it handles taps and page changes. The one that
 * doesn't, {@link #built}, keeps to a lock of its own.
 */
public final class ReelClearMode {
    /** The reason Instagram gives when a reel leaves clear mode because it left the screen. */
    static final String SWIPED_AWAY = "REELS_VIEWER_SWIPE_OR_EXIT";

    /** Counted under the patch's name for each reel clear mode was carried to. */
    static final String CARRIED = "reels started in clear mode";

    /** Counted under the patch's name for each reel put back in clear mode after its swipe snapped back. */
    static final String KEPT = "reels kept in clear mode after a swipe snapped back";

    /** Counted under the patch's name for each reel clear mode was started on with no tap (#138). */
    static final String STARTED = "reels put in clear mode with no tap";

    /** The reason Instagram's own menu row turns clear mode on with, which a started clear mode gives. */
    static final String MENU = "OVERFLOW_MENU";

    /** What the pager's scroll state calls the pager at rest. */
    static final String AT_REST = "IDLE";

    /** How long after a reel left clear mode the pager's next page still gets it. */
    static final long RETRY_WINDOW_MS = 3_000;

    /** How many reels are remembered as having an exit button of their own. */
    static final int REMEMBERED = 64;

    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /** What Instagram's turn-on found on the pager while this class asked for it. */
    private static final int NOTHING = 0;
    private static final int SAME_REEL = 1;
    private static final int NO_BUTTON = 2;
    private static final int REACHED = 3;
    /** A reel Instagram hasn't built an exit button for yet: its button may still come (#233). */
    private static final int NOT_BUILT = 4;

    /** What this class does to Instagram and reads from it. {@link #PATCHED} is the patch's; tests stand in. */
    interface Viewer {
        /** Turns clear mode on through Instagram's own handler, with the reason given. */
        void enter(Object handler, Object source);

        /** A reel's id. */
        @Nullable
        String id(Object item);

        /** Runs the work on the main thread once what's running there now is done. */
        void post(Runnable work);

        /** Milliseconds that only go forward. */
        long now();

        /** Whether the Reels pager is on screen: shown, with at least half of it in view each way. */
        boolean showing(Object pager);

        /** The handler's own reason for a turn-on from Instagram's menu row, or null when it has none. */
        @Nullable
        Object reason(Object handler);
    }

    static final Viewer PATCHED = new Viewer() {
        @Override
        public void enter(Object handler, Object source) {
            ReelClearMode.enter(handler, source);
        }

        @Override
        public String id(Object item) {
            return reelId(item);
        }

        @Override
        public void post(Runnable work) {
            Utils.runOnMainThread(work);
        }

        @Override
        public long now() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public boolean showing(Object pager) {
            if (!(pager instanceof View)) return false;
            View view = (View) pager;
            Rect seen = new Rect();
            return view.isShown() && view.getGlobalVisibleRect(seen)
                    && 2 * seen.width() >= view.getWidth() && 2 * seen.height() >= view.getHeight();
        }

        /**
         * The reason enum is the one parameter of the handler's turn-on. Its constants are read from
         * its own static fields, since Redex may leave an enum without values().
         */
        @Override
        public Object reason(Object handler) {
            for (Method method : handler.getClass().getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || !Enum.class.isAssignableFrom(parameters[0])) continue;
                for (Field field : parameters[0].getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) || field.getType() != parameters[0]) continue;
                    try {
                        field.setAccessible(true);
                        Object constant = field.get(null);
                        if (constant instanceof Enum && MENU.equals(((Enum<?>) constant).name())) return constant;
                    } catch (ReflectiveOperationException | RuntimeException unreadable) {
                        // Another constant may still be readable.
                    }
                }
            }
            return null;
        }
    };

    static volatile Viewer access = PATCHED;

    /** You turned clear mode on, and haven't turned it off. */
    private static volatile boolean wanted;

    /** The reason your own turn-on gave, handed back to Instagram for each next reel. */
    @Nullable
    private static volatile Object source;

    /** The reel in clear mode now, by id, or null when none is. */
    @Nullable
    private static volatile String shown;

    /** The reel Instagram's turn-on last acted on, read by {@link #entered} right after. */
    @Nullable
    private static volatile String enabling;

    /** True while this class is asking the handler to turn clear mode on. */
    private static volatile boolean carrying;

    /** The reel that just left clear mode, which a carried turn-on must not land on again. */
    @Nullable
    private static volatile String from;

    private static volatile int outcome = NOTHING;

    @Nullable
    private static volatile String reached;

    /** The handler to ask again on the pager's next page, until {@link #retryUntil}. */
    @Nullable
    private static volatile WeakReference<Object> waiting;

    /** When the wait for the pager's next page ends, or 0 when nothing waits. */
    private static volatile long retryUntil;

    private static volatile boolean retryPosted;

    /** The pager reported a page while a reel waits, so the next try's page is where the swipe landed. */
    private static volatile boolean pageReported;

    /**
     * The swipe that took the reel out of clear mode landed: the pager reported a page, or a try
     * found another reel on it. A rest after that isn't a snap-back, for as long as the reel waits.
     */
    private static volatile boolean landed;

    /** The pager, weakly, when it came to rest before the swipe {@link #landed} while a reel waits: the swipe snapped back. */
    @Nullable
    private static volatile WeakReference<Object> snapped;

    /** True while a carried turn-on may land on the reel that left, because its swipe snapped back. */
    private static volatile boolean returning;

    private static final Object BUILT_LOCK = new Object();

    /** The ids of the reels whose exit button Instagram built last, oldest first. */
    private static final LinkedHashSet<String> BUILT = new LinkedHashSet<>();

    @Nullable
    private static volatile String lastBuilt;

    /** The handler, weakly, while Start every reel in clear mode waits to start it (#138). */
    @Nullable
    private static volatile WeakReference<Object> starting;

    private static volatile boolean startPosted;

    /** The handler the last Reels viewer was set up with, weakly, to tell a new viewer from it. */
    @Nullable
    private static volatile WeakReference<Object> viewer;

    /** You left clear mode yourself in that viewer, so it isn't started there again. */
    private static volatile boolean declined;

    private ReelClearMode() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the handler's own turn-on. Only the handler and its reason may be passed. */
    public static void enter(Object handler, Object source) {
    }

    /** Filled in by the patch: the reel's own getId. Only a reel may be passed. */
    public static String reelId(Object item) {
        return null;
    }

    // ------------------------------------------------------------------ hooks

    /**
     * The hook, in the handler's turn-on right after its action ran. Your own turn-on is remembered
     * with the reel it acted on. One this class asked for comes through here too and is left alone.
     */
    public static void entered(@Nullable Object handler, @Nullable Object reason) {
        try {
            HookStatus.invoked(FAMILY);
            if (carrying) return;
            String id = enabling;
            enabling = null;
            if (!on() || handler == null || reason == null || id == null) {
                drop();
                return;
            }
            HookStatus.bound(FAMILY, "clear mode");
            wanted = true;
            source = reason;
            shown = id;
            clearRetry();
            Logger.printDebug(() -> "Clear mode: turned on, the next reels start in it");
        } catch (Throwable failure) {
            drop();
            HookStatus.threw(FAMILY, "clear mode on", failure);
        }
    }

    /**
     * The hook, in the handler's turn-off right after its action ran, with the reel and the reason.
     * Any reason but {@link #SWIPED_AWAY} is you leaving clear mode, which forgets it. A reel that
     * left the screen in the clear mode you asked for hands it to the reel the pager is on now.
     */
    public static void left(@Nullable Object handler, @Nullable Object item, @Nullable Object reason) {
        try {
            HookStatus.invoked(FAMILY);
            if (carrying) return;
            if (!swipedAway(reason)) {
                if (wanted) Logger.printDebug(() -> "Clear mode: turned off, the next reels show their buttons");
                // You left it yourself, so this viewer doesn't start it again (#138).
                starting = null;
                declined = true;
                drop();
                return;
            }
            if (!wanted) return;
            if (!on()) {
                drop();
                return;
            }
            String id = item == null ? null : access.id(item);
            String was = shown;
            if (handler == null || id == null || !id.equals(was)) {
                Logger.printDebug(() -> "Clear mode: a reel left it that isn't the one put in it, nothing carried"
                        + " (handler " + (handler != null) + ", reel " + (id != null) + ", one in it " + (was != null) + ")");
                return;
            }
            shown = null;
            clearRetry();
            carry(handler, id, 0, false, false);
        } catch (Throwable failure) {
            drop();
            HookStatus.threw(FAMILY, "clear mode off", failure);
        }
    }

    /**
     * The hook, in Instagram's turn-on where it has read the reel the pager is on and before it
     * tests it. Your own turn-on gets its reel back as it is. One this class asked for gets the reel
     * only when it's another reel than the one that just left clear mode, or that same one after its
     * swipe snapped back, and Instagram built it an exit button. Otherwise it gets null, which
     * Instagram's own test takes as no reel and does nothing.
     */
    @Nullable
    public static Object current(@Nullable Object item) {
        boolean ours = carrying;
        try {
            HookStatus.invoked(FAMILY);
            if (!ours) {
                // Cleared first, so a reel whose id can't be read leaves no older reel's behind.
                enabling = null;
                if (item != null) enabling = access.id(item);
                return item;
            }
            String id = item == null ? null : access.id(item);
            if (id == null) {
                outcome = NO_BUTTON;
                return null;
            }
            boolean same = id.equals(from);
            if (!same) landed = true;
            if (same && !returning) {
                outcome = SAME_REEL;
                return null;
            }
            if (!hasButton(id)) {
                outcome = NOT_BUILT;
                return null;
            }
            outcome = REACHED;
            reached = id;
            return item;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "clear mode reel", failure);
            if (!ours) return item;
            outcome = NO_BUTTON;
            return null;
        }
    }

    /**
     * The hook, first thing when the Reels pager reports the page it landed on. A reel that left
     * clear mode before its swipe landed is tried again, once Instagram is done with the page. With
     * nothing waiting, Start every reel in clear mode gets its try.
     */
    public static void paged() {
        try {
            HookStatus.invoked(FAMILY);
            long until = retryUntil;
            if (until == 0) {
                wakeIfStarting();
                return;
            }
            if (!on() || access.now() > until) {
                Logger.printDebug(() -> "Clear mode: the wait for the next reel ran out, the next reels show their buttons");
                drop();
                return;
            }
            pageReported = true;
            landed = true;
            if (retryPosted) return;
            retryPosted = true;
            access.post(ReelClearMode::retry);
        } catch (Throwable failure) {
            retryPosted = false;
            drop();
            HookStatus.threw(FAMILY, "clear mode page", failure);
        }
    }

    /**
     * The hook, where the Reels pager's listener handles a change of its scroll state, right after
     * it read the pager, with the pager and the state. A reel that left clear mode and still waits,
     * when the pager comes to rest and its swipe never {@link #landed}, had its swipe snap back: the
     * next try may put that same reel back in clear mode, once Instagram is done with the change (#233).
     */
    public static void settled(@Nullable Object pager, @Nullable Object state) {
        long until = retryUntil;
        if (until == 0 || landed || pager == null || !atRest(state)) return;
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || access.now() > until) {
                Logger.printDebug(() -> "Clear mode: the wait for the next reel ran out, the next reels show their buttons");
                drop();
                return;
            }
            snapped = new WeakReference<>(pager);
            if (retryPosted) return;
            retryPosted = true;
            access.post(ReelClearMode::retry);
        } catch (Throwable failure) {
            retryPosted = false;
            drop();
            HookStatus.threw(FAMILY, "clear mode settle", failure);
        }
    }

    /**
     * The hook, first thing where Instagram builds a reel's exit button, with the reel. Only a reel
     * seen here can leave clear mode again, so only such a reel is put in it. A new one wakes a reel
     * waiting for its button, or Start every reel in clear mode.
     */
    public static void built(@Nullable Object item) {
        try {
            HookStatus.invoked(FAMILY);
            if (item == null) return;
            String id = access.id(item);
            if (id == null || id.equals(lastBuilt)) return;
            synchronized (BUILT_LOCK) {
                BUILT.remove(id);
                BUILT.add(id);
                if (BUILT.size() > REMEMBERED) {
                    Iterator<String> oldest = BUILT.iterator();
                    oldest.next();
                    oldest.remove();
                }
                lastBuilt = id;
            }
            wakeIfWaiting();
            wakeIfStarting();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "clear mode exit button", failure);
        }
    }

    /**
     * The hook, in the Reels viewer's setup right after it handed the handler its turn-on action.
     * With Start every reel in clear mode on, the handler is kept, weakly, for the first reel with
     * an exit button. Otherwise nothing is kept (#138).
     */
    public static void ready(@Nullable Object handler) {
        try {
            HookStatus.invoked(FAMILY);
            if (handler == null || !startsOn()) {
                starting = null;
                return;
            }
            WeakReference<Object> last = viewer;
            if (last == null || last.get() != handler) {
                // Another viewer: the clear mode the one before it was in went with it, and leaving
                // it there doesn't hold this one back.
                viewer = new WeakReference<>(handler);
                declined = false;
                drop();
            }
            starting = declined ? null : new WeakReference<>(handler);
        } catch (Throwable failure) {
            starting = null;
            HookStatus.threw(FAMILY, "clear mode start", failure);
        }
    }

    /**
     * The hook, where the seek bar row tests whether to draw the seek bar, with Instagram's answer
     * and the reel's clear mode flag, each non-zero for yes. A reel in clear mode draws none while
     * the switch is on. Anything else, or a failure here, keeps Instagram's answer.
     */
    public static boolean scrubber(int shown, int fullscreen) {
        if (shown == 0) return false;
        if (fullscreen == 0) return true;
        try {
            HookStatus.invoked(FAMILY);
            return !on();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "clear mode seek bar", failure);
            return true;
        }
    }

    /**
     * The hook, where a reel page writes the alpha its buttons, author row and top bar share, with
     * the alpha asked for and the one held now. While a reel you put in clear mode is in it, asking
     * for fully shown from anything less keeps the alpha held (#233). Anything else, or a failure
     * here, keeps what was asked for.
     */
    public static float chrome(float asked, float now) {
        if (asked < 1f || now >= 1f || shown == null || !wanted) return asked;
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) return asked;
            Logger.printDebug(() -> "Clear mode: kept the buttons hidden when Instagram reset them");
            return now;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "clear mode buttons", failure);
            return asked;
        }
    }

    // ------------------------------------------------------------------ the rule

    /**
     * A reel that was waiting for its exit button may have it now: the next try runs once Instagram
     * is done with the button. Built can run off the main thread, so the try is posted (#233).
     */
    private static void wakeIfWaiting() {
        long until = retryUntil;
        if (until == 0 || retryPosted || access.now() > until) return;
        retryPosted = true;
        access.post(ReelClearMode::retry);
    }

    /**
     * Start every reel in clear mode holds a handler and nothing is in clear mode or waiting: the
     * next try runs once Instagram is done with the page or button (#138).
     */
    private static void wakeIfStarting() {
        if (starting == null || wanted || retryUntil != 0 || startPosted) return;
        startPosted = true;
        access.post(ReelClearMode::start);
    }

    /**
     * Start every reel in clear mode's try: the handler kept since the viewer's setup turns clear
     * mode on for the reel the pager is on, with the reason Instagram's menu row gives, just as a
     * tap on that row would. A reel {@link #current} holds back leaves it for the next page or
     * button. Once a reel is in it, clear mode is carried as if you had turned it on (#138).
     */
    private static void start() {
        try {
            startPosted = false;
            WeakReference<Object> held = starting;
            if (held == null || wanted || retryUntil != 0 || carrying) return;
            Object handler = held.get();
            if (handler == null || !startsOn()) {
                starting = null;
                return;
            }
            Object reason = access.reason(handler);
            if (reason == null) {
                starting = null;
                Logger.printDebug(() -> "Clear mode: the handler gives no " + MENU + " reason, nothing started");
                return;
            }
            from = null;
            outcome = NOTHING;
            reached = null;
            carrying = true;
            try {
                access.enter(handler, reason);
            } finally {
                carrying = false;
            }
            int found = outcome;
            String id = reached;
            outcome = NOTHING;
            reached = null;
            if (found != REACHED || id == null) {
                Logger.printDebug(() -> "Clear mode: not started yet, trying again on the next page or button ("
                        + (found == NOTHING ? "Instagram didn't look for a reel" : "found " + found) + ")");
                return;
            }
            HookStatus.bound(FAMILY, "clear mode");
            wanted = true;
            source = reason;
            shown = id;
            clearRetry();
            HookStatus.counted(FAMILY, STARTED);
            Logger.printDebug(() -> "Clear mode: started with no tap, the next reels start in it too");
        } catch (Throwable failure) {
            starting = null;
            drop();
            HookStatus.threw(FAMILY, "clear mode start", failure);
        }
    }

    /**
     * The pager's next page, a reel's exit button or the pager at rest, once Instagram has handled
     * it: the next try. A pager that came to rest before its swipe landed, and is still on screen,
     * lets it land on the reel that left. A swipe that landed stays landed for the tries after.
     */
    private static void retry() {
        try {
            retryPosted = false;
            WeakReference<Object> held = waiting;
            WeakReference<Object> rested = snapped;
            String left = from;
            long until = retryUntil;
            boolean page = pageReported;
            boolean moved = landed;
            clearRetry();
            if (until == 0 || !wanted) return;
            Object handler = held == null ? null : held.get();
            if (!on() || handler == null || left == null) {
                Logger.printDebug(() -> "Clear mode: the next try has no handler left, the next reels show their buttons");
                drop();
                return;
            }
            Object pager = rested == null ? null : rested.get();
            boolean back = pager != null && !moved && access.showing(pager);
            if (pager != null && !back) Logger.printDebug(() -> moved
                    ? "Clear mode: the pager came to rest after the swipe landed, the reel that left stays out"
                    : "Clear mode: the pager came to rest off screen, the reel that left stays out");
            landed = moved;
            carry(handler, left, until, page, back);
        } catch (Throwable failure) {
            drop();
            HookStatus.threw(FAMILY, "clear mode retry", failure);
        }
    }

    /**
     * Asks the handler to turn clear mode on for the reel the pager is on, after the reel
     * {@code left} left it. {@link #current} says what Instagram found there. {@code until} is when
     * the wait that began with the first try ends, or 0 on the first try, and {@code page} says the
     * pager reported a page since the last one. {@code back} says the swipe snapped back, so the
     * reel that left may be put in it again. Anything that isn't a reel put in clear mode, and isn't
     * worth waiting on ({@link #waitsOn}), forgets it.
     */
    private static void carry(Object handler, String left, long until, boolean page, boolean back) {
        Object reason = source;
        if (reason == null) {
            drop();
            return;
        }
        from = left;
        outcome = NOTHING;
        reached = null;
        carrying = true;
        returning = back;
        try {
            access.enter(handler, reason);
        } finally {
            carrying = false;
            returning = false;
        }
        int found = outcome;
        String id = reached;
        outcome = NOTHING;
        reached = null;
        if (found == REACHED && id != null) {
            from = null;
            shown = id;
            if (id.equals(left)) {
                HookStatus.counted(FAMILY, KEPT);
                Logger.printDebug(() -> "Clear mode: the swipe snapped back, turned it on again for the same reel");
            } else {
                HookStatus.counted(FAMILY, CARRIED);
                Logger.printDebug(() -> "Clear mode: carried to the next reel");
            }
        } else if (waitsOn(found, until, page)) {
            Logger.printDebug(() -> found == SAME_REEL
                    ? "Clear mode: the swipe hasn't landed yet, trying again on the next page"
                    : "Clear mode: the next reel has no exit button yet, trying again when it does");
            waiting = new WeakReference<>(handler);
            retryUntil = until == 0 ? access.now() + RETRY_WINDOW_MS : until;
        } else {
            Logger.printDebug(() -> "Clear mode: nothing to carry it to, the next reels show their buttons ("
                    + (found == NOTHING ? "Instagram didn't look for a reel" : "found " + found) + ")");
            drop();
        }
    }

    /**
     * Whether a try that found nothing to put in clear mode waits for another. A reel whose button
     * isn't built yet may get it, and a pager still on the reel that left may still land, so both
     * wait until the first try's {@link #RETRY_WINDOW_MS} runs out. A button built for a reel
     * further on doesn't mean the swipe landed (#233), but a page report that still names the reel
     * that left does: that swipe went back, and it ends.
     */
    private static boolean waitsOn(int found, long until, boolean page) {
        if (found != SAME_REEL && found != NOT_BUILT) return false;
        if (until == 0) return true;
        if (found == SAME_REEL && page) return false;
        return access.now() <= until;
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.KEEP_REEL_CLEAR_MODE.get();
    }

    private static boolean startsOn() {
        return on() && Settings.START_REEL_CLEAR_MODE.get();
    }

    private static boolean swipedAway(@Nullable Object reason) {
        return reason instanceof Enum && SWIPED_AWAY.equals(((Enum<?>) reason).name());
    }

    private static boolean atRest(@Nullable Object state) {
        return state instanceof Enum && AT_REST.equals(((Enum<?>) state).name());
    }

    private static boolean hasButton(String id) {
        synchronized (BUILT_LOCK) {
            return BUILT.contains(id);
        }
    }

    private static void clearRetry() {
        retryUntil = 0;
        waiting = null;
        pageReported = false;
        landed = false;
        snapped = null;
    }

    /** Off, paused, turned off by you or failed: nothing is wanted, so nothing comes back later. */
    private static void drop() {
        wanted = false;
        source = null;
        shown = null;
        enabling = null;
        from = null;
        clearRetry();
    }

    /** Whether clear mode is wanted for the next reels. For tests. */
    static boolean wanted() {
        return wanted;
    }

    /** Whether a reel that left clear mode waits for the pager's next page. For tests. */
    static boolean waiting() {
        return retryUntil != 0;
    }

    /** Whether Start every reel in clear mode holds a handler. For tests. */
    static boolean starting() {
        return starting != null;
    }

    /** Forgets everything, the reels with an exit button too. For tests. */
    static void forget() {
        drop();
        starting = null;
        startPosted = false;
        viewer = null;
        declined = false;
        carrying = false;
        returning = false;
        outcome = NOTHING;
        reached = null;
        retryPosted = false;
        synchronized (BUILT_LOCK) {
            BUILT.clear();
            lastBuilt = null;
        }
        access = PATCHED;
    }
}
