/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.function.LongSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Scroll break (#297): after the time the choice names on Home's feed, Reels and Explore, a calm
 * screen covers those three for {@link #BREAK_MS}, with a countdown. Messages, profiles, the camera
 * and every other screen stay as they are, and the tab bar stays out from under it, so a tap on
 * another tab gets away from it.
 *
 * <p>Only time on those screens counts, read once a second from which tab Instagram marks selected
 * and whether the reels viewer fills the window ({@link FullScreenBars#screenOf}). A tick that comes
 * late counts at most {@link #MAX_STEP_MS}, so a phone that slept with Instagram open doesn't run the
 * clock. The break runs on the clock since the phone started, in the background too, and Instagram
 * sitting in the background for {@link #BREAK_MS} starts the count over, since that was a break.
 *
 * <p>Off, or HushGram paused, nothing counts and no break screen shows. It's all views over
 * Instagram's own: nothing of Instagram's changes.
 */
public final class ScrollBreak {
    /** How long a break lasts, and how long in the background counts as one. */
    static final long BREAK_MS = 5 * 60_000L;

    /** How often the screen is looked at while Instagram is in front. */
    static final long TICK_MS = 1_000L;

    /** How often the choice is looked at while it's off or HushGram is paused, so turning it on needs no restart. */
    static final long IDLE_TICK_MS = 5_000L;

    /** The most one tick counts, so a late tick isn't counted as scrolling. */
    static final long MAX_STEP_MS = 5_000L;

    /** What's counted for each break that starts. */
    static final String STARTED = "break screen shown";

    /** The break screen's tag, so a test or a second activity finds the one already there. */
    static final String TAG = "hushgram_scroll_break";

    /** The clock the counts read. Tests swap in their own. */
    static volatile LongSupplier clock = SystemClock::elapsedRealtime;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable TICK = ScrollBreak::tick;

    /** Time on the counted screens since the last break, in ms. Only the main thread touches the state below. */
    private static long used;
    /** When the break ends, on {@link #clock}, or 0 while there's none. */
    private static long breakUntil;
    /** When the last tick counted, or 0 while Instagram isn't in front. */
    private static long lastTick;
    /** When Instagram's last screen went to the background, or 0 while one is shown. */
    private static long hiddenAt;
    /** Instagram's activities started and not yet stopped. */
    private static int started;
    /** The activity in front. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    private static volatile boolean registered;

    private ScrollBreak() {
    }

    /** Called as the application starts. Does nothing in a build without Stop Reels scrolling. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.reelScrolling()
                    || !Utils.isMainProcess()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityStarted(Activity activity) {
                    started(clock.getAsLong());
                }
                @Override public void onActivityResumed(Activity activity) {
                    resumed(activity);
                }
                @Override public void onActivityPaused(Activity activity) {
                    paused(activity);
                }
                @Override public void onActivityStopped(Activity activity) {
                    stopped(clock.getAsLong());
                }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) { }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCROLLING, "scroll break", failure);
        }
    }

    /** An Instagram activity started at [now]. Back from a background stay of a break or more, the count starts over. */
    static void started(long now) {
        if (started++ == 0 && hiddenAt != 0) {
            if (now - hiddenAt >= BREAK_MS) used = 0;
            hiddenAt = 0;
        }
    }

    /** An Instagram activity stopped at [now]. With none left started, Instagram is in the background. */
    static void stopped(long now) {
        if (started > 0 && --started == 0) hiddenAt = now;
    }

    /** [activity] came to the front: it's the one looked at from now on. */
    static void resumed(Activity activity) {
        Activity before = front.get();
        if (before != null && before != activity) hide(before);
        front = new WeakReference<>(activity);
        lastTick = 0;
        tick();
    }

    /** [activity] left the front: nothing counts until one resumes. */
    static void paused(Activity activity) {
        if (front.get() != activity) return;
        MAIN.removeCallbacks(TICK);
        lastTick = 0;
    }

    /**
     * Looks at the screen in front: counts the time since the last look when it's one of the three,
     * starts a break when the time is up, and shows or hides the break screen. Runs again in a
     * second while the activity is in front. Never throws.
     */
    static void tick() {
        MAIN.removeCallbacks(TICK);
        Activity activity = front.get();
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) return;
        long next = TICK_MS;
        try {
            long now = clock.getAsLong();
            View decor = activity.getWindow().getDecorView();
            ScrollBreakTime limit = limit();
            if (limit == ScrollBreakTime.OFF) {
                next = IDLE_TICK_MS;
                used = 0;
                breakUntil = 0;
                lastTick = 0;
                hide(activity);
            } else {
                boolean counted = counted(decor);
                long since = lastTick;
                if (breakUntil != 0 && now >= breakUntil) {
                    // Only the time after the break ended counts toward the next one.
                    since = Math.max(since, breakUntil);
                    breakUntil = 0;
                    used = 0;
                }
                if (breakUntil == 0) {
                    if (counted && since != 0) used += Math.max(0, Math.min(now - since, MAX_STEP_MS));
                    if (used >= limit.millis()) {
                        breakUntil = now + BREAK_MS;
                        HookStatus.counted(FamilyNames.REEL_SCROLLING, STARTED);
                        Logger.printDebug(() -> "Scroll break: " + limit.minutes + " minutes up, break screen on");
                    }
                }
                lastTick = now;
                if (breakUntil != 0 && counted) show(activity, decor, breakUntil - now);
                else hide(activity);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SCROLLING, "scroll break", failure);
            hide(activity);
        }
        // Paused takes the next one off, so a tick only comes back while this activity is in front.
        MAIN.postDelayed(TICK, next);
    }

    /** The choice, or Off while HushGram is paused or the settings aren't read yet. */
    static ScrollBreakTime limit() {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return ScrollBreakTime.OFF;
        ScrollBreakTime limit = Settings.SCROLL_BREAK.get();
        return limit == null ? ScrollBreakTime.OFF : limit;
    }

    /** Whether [decor] shows Home's feed, Reels or Explore, the screens a break covers. */
    static boolean counted(View decor) {
        FullScreenBars.Screen screen = FullScreenBars.screenOf(decor);
        return screen == FullScreenBars.Screen.REELS || screen == FullScreenBars.Screen.HOME
                || FullScreenBars.exploreSelected(decor);
    }

    /**
     * Puts the break screen over [decor], with the tab bar left showing through it, saying how long is
     * left. The whole window is covered, so a floating glass pill doesn't leave the feed showing and
     * scrollable around and under it.
     */
    private static void show(Activity activity, View decor, long left) {
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup window = (ViewGroup) decor;
        View shown = window.findViewWithTag(TAG);
        Cover cover = shown instanceof Cover ? (Cover) shown : null;
        if (cover == null) {
            cover = new Cover(activity);
            window.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        cover.leaveOpen(FullScreenBars.tabBarRest(decor));
        cover.bringToFront();
        cover.showLeft(left);
    }

    /** Takes the break screen off [activity]'s window, if it's there. */
    private static void hide(Activity activity) {
        if (activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        View shown = decor.findViewWithTag(TAG);
        if (shown != null && shown.getParent() instanceof ViewGroup) ((ViewGroup) shown.getParent()).removeView(shown);
    }

    /** "4:59": the time left, in minutes and seconds, rounded up so it never reads 0:00 while it shows. */
    static String clockText(long left) {
        long seconds = Math.max(0, (left + 999) / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * The break screen: a calm color, what it is, and the countdown. It takes every touch over it but
     * the tab bar's, which it leaves showing through.
     */
    static final class Cover extends FrameLayout {
        private final TextView countdown;
        /** Where the tab bar is, left uncovered and reachable, or null to cover everything. */
        @Nullable private Rect open;

        @SuppressWarnings("ClickableViewAccessibility")
        Cover(Context context) {
            super(context);
            setTag(TAG);
            setBackgroundColor(0xF2181C24);
            setClickable(true);
            setFocusable(true);
            setOnTouchListener((view, event) -> true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            int side = dp(context, 32);
            column.setPadding(side, 0, side, 0);
            TextView title = text(context, L10n.t("Time for a break"), 22, 0xFFECEFF4);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            column.addView(title);
            TextView body = text(context, L10n.t("Home, Reels and Explore open again when the countdown ends. "
                    + "Messages and your profile still work."), 15, 0xFFB8C0CC);
            body.setPadding(0, dp(context, 12), 0, dp(context, 20));
            column.addView(body);
            countdown = text(context, "", 40, 0xFFECEFF4);
            column.addView(countdown);
            addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
        }

        /** Leaves [bar] uncovered, so the tab bar shows and takes its own taps, or covers everything for null. */
        void leaveOpen(@Nullable Rect bar) {
            if (bar == null ? open == null : bar.equals(open)) return;
            open = bar == null ? null : new Rect(bar);
            invalidate();
        }

        @Nullable
        Rect leftOpen() {
            return open == null ? null : new Rect(open);
        }

        @Override
        public void draw(Canvas canvas) {
            if (open == null) {
                super.draw(canvas);
                return;
            }
            int saved = canvas.save();
            canvas.clipOutRect(open);
            super.draw(canvas);
            canvas.restoreToCount(saved);
        }

        /** A touch that starts on the tab bar goes past the cover to it. */
        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && open != null
                    && open.contains(Math.round(event.getX()), Math.round(event.getY()))) {
                return false;
            }
            return super.dispatchTouchEvent(event);
        }

        void showLeft(long left) {
            String time = clockText(left);
            if (!time.contentEquals(countdown.getText())) {
                countdown.setText(time);
                setContentDescription(L10n.f("Time for a break. Home, Reels and Explore open again in %1$s.", time));
            }
        }

        String countdownText() {
            return countdown.getText().toString();
        }

        private static TextView text(Context context, String words, int sp, int color) {
            TextView view = new TextView(context);
            view.setText(words);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            view.setTextColor(color);
            view.setGravity(Gravity.CENTER);
            return view;
        }

        private static int dp(Context context, int dp) {
            return Math.round(dp * context.getResources().getDisplayMetrics().density);
        }
    }

    /** The break screen on [activity]'s window, or null. For tests. */
    @Nullable
    static Cover coverOn(Activity activity) {
        View shown = activity.getWindow().getDecorView().findViewWithTag(TAG);
        return shown instanceof Cover ? (Cover) shown : null;
    }

    /** Forgets every count, for tests. */
    static void forgetForTests() {
        MAIN.removeCallbacks(TICK);
        used = 0;
        breakUntil = 0;
        lastTick = 0;
        hiddenAt = 0;
        started = 0;
        front = new WeakReference<>(null);
        clock = SystemClock::elapsedRealtime;
    }
}
