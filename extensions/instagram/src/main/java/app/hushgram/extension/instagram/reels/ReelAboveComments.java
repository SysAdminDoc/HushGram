/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Control the reel above comments (#665): with a reel's comments open, a tap on the reel shown above
 * them pauses or plays it, and a hold plays it at 2x until it's let go of. The comments stay open.
 *
 * <p>Instagram 454 shrinks the reel into the space above an open comments sheet and keeps it playing,
 * and its watch-and-comment manager answers each tap the sheet's own gesture detector sees outside the
 * sheet in onBottomSheetSingleTapConfirmed. Instagram keeps a tap only on the audio button there, and
 * any other tap closes the sheet, the reel included. The patch hands this class that answer
 * ({@link #tapped}): a tap on the reel, above the sheet's content, pauses or plays the reel through the
 * Reels player controller's own pause and resume, the calls Instagram's tap on a full-screen reel makes,
 * and is answered as kept, so the sheet stays.
 *
 * <p>A hold is read from every touch IgFragmentActivity hands on ({@link #touch}). A finger that comes
 * down on the reel with the sheet open and stays there for Android's long-press time sets the reel to
 * 2x through the controller's setPlaybackSpeed, playing it first if it was paused. Letting go, or
 * Android cancelling the touch, puts back the speed it had and pauses it again if the hold played it.
 * Moving the finger before then, or a second finger, leaves the reel alone, so a drag of the sheet
 * still drags it. The lift that ends a hold is the tap Instagram's detector then confirms, and it's
 * answered as kept without pausing.
 *
 * <p>The manager whose sheet moved last ({@link #sheetMoved}) is the one a hold looks at. Dragging the
 * sheet down and Back still close it, since neither is a tap. Nothing changes while the switch is off,
 * HushGram is paused, the settings aren't ready, the patch couldn't fill its stubs, or anything here
 * fails: the tap closes the sheet as Instagram has it.
 */
public final class ReelAboveComments {
    static final float HOLD_SPEED = 2f;

    /** Counted under the patch's name for each tap that paused or played the reel. */
    static final String TAPPED = "reel above the comments paused or played";

    /** Counted under the patch's name for each hold that played the reel at 2x. */
    static final String HELD = "reel above the comments held at 2x";

    private static final String FAMILY = FamilyNames.REEL_ABOVE_COMMENTS;

    /** No touch: a down time no event has. */
    private static final long NONE = Long.MIN_VALUE;

    /** What this class reads from Instagram's sheet and does to its player. {@link #PATCHED} is the patch's; tests stand in. */
    interface Sheet {
        /** Whether the manager's comments sheet is open and not on its way out. */
        boolean open(Object manager);

        /** Where the reel shows above the sheet's content, on the screen, or null when none of it does. */
        @Nullable
        Rect reel(Object manager);

        /** The Reels player controller the manager plays its reel through. */
        @Nullable
        Object controller(Object manager);

        /** 1 while the reel plays, 0 while it's paused, -1 when there's no reel to play. */
        int playing(Object controller);

        void pause(Object controller);

        void resume(Object controller);

        void speed(Object controller, float speed);
    }

    static final Sheet PATCHED = new Sheet() {
        @Override
        public boolean open(Object manager) {
            return sheetOpen(manager) != 0;
        }

        @Nullable
        @Override
        public Rect reel(Object manager) {
            return reelOnScreen(mediaView(manager));
        }

        @Nullable
        @Override
        public Object controller(Object manager) {
            return controllerOf(manager);
        }

        @Override
        public int playing(Object controller) {
            return playingNow(controller);
        }

        @Override
        public void pause(Object controller) {
            pausePlayer(controller);
        }

        @Override
        public void resume(Object controller) {
            resumePlayer(controller);
        }

        @Override
        public void speed(Object controller, float speed) {
            setPlayerSpeed(controller, speed);
        }
    };

    static volatile Sheet access = PATCHED;

    /** The manager whose sheet moved last, weakly. */
    @Nullable
    private static volatile WeakReference<Object> lastSheet;

    /** A finger down on the reel, waiting for the long-press time. Main thread only. */
    @Nullable
    private static Hold pending;

    /** The hold playing the reel at 2x now. Main thread only. */
    @Nullable
    private static Held held;

    /** The down time of the touch that became a hold, and of the last tap that paused or played. */
    private static volatile long holdDown = NONE;
    private static volatile long tapDown = NONE;

    @Nullable
    private static Handler handler;

    private ReelAboveComments() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the manager's scaled reel view, or null. */
    @Nullable
    public static View mediaView(Object manager) {
        return null;
    }

    /** Filled in by the patch: non-zero while the manager's sheet is open and not being dismissed. */
    public static int sheetOpen(Object manager) {
        return 0;
    }

    /** Filled in by the patch: the manager's Reels player controller. */
    @Nullable
    public static Object controllerOf(Object manager) {
        return null;
    }

    /** Filled in by the patch: the controller's isVideoPlaying on its current player, -1 with none. */
    public static int playingNow(Object controller) {
        return -1;
    }

    /** Filled in by the patch: the controller's pauseCurrentPlayer, as a tap on a reel calls it. */
    public static void pausePlayer(Object controller) {
    }

    /** Filled in by the patch: the controller's resumeCurrentPlayer, as a tap on a reel calls it. */
    public static void resumePlayer(Object controller) {
    }

    /** Filled in by the patch: the controller's setPlaybackSpeed. */
    public static void setPlayerSpeed(Object controller, float speed) {
    }

    // ------------------------------------------------------------------ hooks

    /** The hook, first thing in the manager's onBottomSheetHeightChanged. */
    public static void sheetMoved(@Nullable Object manager) {
        try {
            HookStatus.invoked(FAMILY);
            if (manager == null) return;
            WeakReference<Object> last = lastSheet;
            if (last == null || last.get() != manager) lastSheet = new WeakReference<>(manager);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "sheet", failure);
        }
    }

    /**
     * The hook, on each answer of the manager's onBottomSheetSingleTapConfirmed: [handled] (non-zero
     * for kept) as Instagram has it, or true when the tap was on the reel above the sheet and paused or
     * played it, or was the lift of a hold. Never throws.
     */
    public static boolean tapped(int handled, @Nullable Object manager, @Nullable MotionEvent event) {
        boolean kept = handled != 0;
        try {
            HookStatus.invoked(FAMILY);
            if (kept || manager == null || event == null) return kept;
            long down = event.getDownTime();
            if (down == holdDown || down == tapDown) return true;
            if (!on()) return false;
            Sheet sheet = access;
            if (!sheet.open(manager)) return false;
            Rect reel = sheet.reel(manager);
            if (reel == null || !reel.contains((int) event.getRawX(), (int) event.getRawY())) return false;
            Object controller = sheet.controller(manager);
            if (controller == null) return false;
            int playing = sheet.playing(controller);
            if (playing < 0) return false;
            if (playing > 0) sheet.pause(controller);
            else sheet.resume(controller);
            tapDown = down;
            HookStatus.bound(FAMILY, "tap");
            HookStatus.counted(FAMILY, TAPPED);
            Logger.printDebug(() -> "Reel above comments: " + (playing > 0 ? "paused" : "played") + " the reel, the comments stay open");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tap", failure);
            return kept;
        }
    }

    /** The hook, in IgFragmentActivity.dispatchTouchEvent: every touch, for a hold on the reel. Never throws. */
    public static void touch(@Nullable Activity activity, @Nullable MotionEvent event) {
        try {
            if (event == null) return;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelPending();
                    endHold();
                    down(activity, event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    Hold waiting = pending;
                    if (waiting != null && waiting.movedOff(event)) cancelPending();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelPending();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelPending();
                    endHold();
                    break;
                default:
                    break;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "touch", failure);
        }
    }

    // ------------------------------------------------------------------ the hold

    /** A finger down: waits for the long-press time when it's on the reel of an open sheet. */
    private static void down(@Nullable Activity activity, MotionEvent event) {
        WeakReference<Object> last = lastSheet;
        Object manager = last == null ? null : last.get();
        // No comments sheet has moved since Instagram started: nothing to read.
        if (manager == null) return;
        HookStatus.invoked(FAMILY);
        if (!on()) return;
        Sheet sheet = access;
        if (!sheet.open(manager)) return;
        Rect reel = sheet.reel(manager);
        float x = event.getRawX();
        float y = event.getRawY();
        if (reel == null || !reel.contains((int) x, (int) y)) return;
        Object controller = sheet.controller(manager);
        if (controller == null) return;
        int slop = activity == null ? 0 : ViewConfiguration.get(activity).getScaledTouchSlop();
        Hold hold = new Hold(manager, controller, event.getDownTime(), x, y, slop);
        pending = hold;
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
    }

    private static void cancelPending() {
        Hold waiting = pending;
        pending = null;
        if (waiting != null && handler != null) handler.removeCallbacks(waiting);
    }

    /** Puts back the speed the reel had before the hold, and pauses it again if the hold played it. */
    private static void endHold() {
        Held ending = held;
        held = null;
        if (ending == null) return;
        Sheet sheet = access;
        sheet.speed(ending.controller, ending.speedBefore);
        if (ending.played) sheet.pause(ending.controller);
        Logger.printDebug(() -> "Reel above comments: the hold ended, back to " + ending.speedBefore + "x");
    }

    /** A finger down on the reel, played at 2x if it stays there for the long-press time. */
    private static final class Hold implements Runnable {
        final WeakReference<Object> manager;
        final Object controller;
        final long downTime;
        final float x;
        final float y;
        final int slop;

        Hold(Object manager, Object controller, long downTime, float x, float y, int slop) {
            this.manager = new WeakReference<>(manager);
            this.controller = controller;
            this.downTime = downTime;
            this.x = x;
            this.y = y;
            this.slop = slop;
        }

        boolean movedOff(MotionEvent event) {
            float dx = event.getRawX() - x;
            float dy = event.getRawY() - y;
            return dx * dx + dy * dy > (float) slop * slop;
        }

        @Override
        public void run() {
            try {
                if (pending != this) return;
                pending = null;
                Object sheetManager = manager.get();
                Sheet sheet = access;
                if (sheetManager == null || !on() || !sheet.open(sheetManager)) return;
                int playing = sheet.playing(controller);
                if (playing < 0) return;
                float before = ReelSpeed.playing();
                boolean played = playing == 0;
                if (played) sheet.resume(controller);
                held = new Held(controller, before, played);
                holdDown = downTime;
                sheet.speed(controller, HOLD_SPEED);
                HookStatus.bound(FAMILY, "hold");
                HookStatus.counted(FAMILY, HELD);
                Logger.printDebug(() -> "Reel above comments: held, playing at " + HOLD_SPEED + "x from " + before + "x");
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "hold", failure);
            }
        }
    }

    /** A hold playing the reel at 2x: the speed to put back, and whether the hold played a paused reel. */
    private static final class Held {
        final Object controller;
        final float speedBefore;
        final boolean played;

        Held(Object controller, float speedBefore, boolean played) {
            this.controller = controller;
            this.speedBefore = speedBefore;
            this.played = played;
        }
    }

    // ------------------------------------------------------------------ the rule

    private static boolean on() {
        return Utils.settingsReady() && Settings.REEL_ABOVE_COMMENTS.get();
    }

    /**
     * Where [media], the reel view, shows on the screen above the content of the sheet open over it,
     * or null when it isn't shown, the sheet's content isn't found, or nothing of the reel is above it.
     */
    @Nullable
    static Rect reelOnScreen(@Nullable View media) {
        if (media == null || !media.isShown()) return null;
        Rect reel = new Rect();
        if (!media.getGlobalVisibleRect(reel)) return null;
        View root = media.getRootView();
        int contentId = FullScreenBars.id(media.getContext(), SheetsOnScreen.CONTENT);
        View content = contentId == 0 || root == null ? null : root.findViewById(contentId);
        if (content == null || !content.isShown()) return null;
        Rect sheet = new Rect();
        if (!content.getGlobalVisibleRect(sheet)) return null;
        Rect above = above(reel, sheet.top);
        if (above == null) return null;
        // The visible rects are in the window; a touch's raw position is on the screen.
        int[] window = new int[2];
        root.getLocationOnScreen(window);
        above.offset(window[0], window[1]);
        return above;
    }

    /** The part of [reel] above [sheetTop], or null when none of it is. */
    @Nullable
    static Rect above(Rect reel, int sheetTop) {
        Rect above = new Rect(reel);
        if (above.bottom > sheetTop) above.bottom = sheetTop;
        return above.isEmpty() ? null : above;
    }

    /** Forgets every sheet, touch and hold, and puts the patch's sheet back. For tests. */
    static void forget() {
        Hold waiting = pending;
        pending = null;
        if (waiting != null && handler != null) handler.removeCallbacks(waiting);
        held = null;
        lastSheet = null;
        holdDown = NONE;
        tapDown = NONE;
        access = PATCHED;
    }
}
