/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Switch;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Seek bar on feed videos patch (#389).
 *
 * <p>The feed's video player, IgFeedVideoPlayer, tells {@link #progress} where the video it's playing
 * is each time it moves on. A seek bar then sits along the bottom of that video, in the frame holding
 * the video and its cover, just over the cover, and follows it. Every {@link #TICK_MS} the bar asks the
 * player for its video's cover again: when the feed hands the player another video the bar moves there,
 * and while the player has none or the cover's frame is off screen the bar hides. The cover itself is
 * hidden while the video plays, so only its frame counts. A paused video keeps its bar. Letting go of
 * a drag moves the video to that point the way Instagram's own feed scrubber does, and a video that
 * was paused stays paused there.
 *
 * <p>With the switch off, HushGram paused or the settings not read, the bar hides and a drag does
 * nothing. A bar hidden for {@link #GONE_MS} goes away and stops ticking until the player plays
 * again. Anything thrown leaves the video as Instagram plays it.
 */
public final class FeedSeekBar {
    /** How often the bar checks the player still shows its video. */
    static final long TICK_MS = 250;

    /** How long a hidden bar waits for its video to come back before it goes away. */
    static final long GONE_MS = 2000;

    /** The bar's height, along the bottom of the video. */
    static final int HEIGHT_DP = 32;

    static final String HOOK = "feed seek bar";
    static final String COUNT_SEEKS = "feed seeks";
    static final String COUNT_TOGGLES = "feed seek bar toggles";

    /** The toggle button's size, a small circle at the bottom start of the video (#437). */
    static final int BUTTON_DP = 28;

    /** The feed's video player, through the patch's stubs; tests stand in. */
    interface Player {
        @Nullable View view(Object player);
        boolean seekTo(Object player, int positionMs);
    }

    static final Player PATCHED = new Player() {
        @Override public View view(Object player) { return FeedSeekReader.view(player); }
        @Override public boolean seekTo(Object player, int positionMs) { return FeedSeekReader.seekTo(player, positionMs, true); }
    };

    private static Player feed = PATCHED;
    @Nullable private static Overlay current;
    private static volatile boolean logged;

    private FeedSeekBar() {
    }

    /** Whether the bar shows now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.FEED_SEEK_BAR.get();
    }

    /** Whether the toggle button shows: its switch on, HushGram not paused, the settings read (#437). */
    static boolean buttonWanted() {
        return Utils.settingsReady() && Settings.FEED_SEEK_BUTTON.get();
    }

    /** Whether a video gets an overlay at all: the bar or its toggle button is wanted. */
    static boolean active() {
        return wanted() || buttonWanted();
    }

    /**
     * Called first thing in the feed video player's progress callback, with the player and where its
     * video is and how long it is, in milliseconds. Never throws.
     */
    public static void progress(Object player, int position, int duration) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Utils.runOnMainThread(() -> progress(player, position, duration));
                return;
            }
            Overlay overlay = current;
            if (overlay == null || overlay.removed || overlay.player.get() != player) {
                if (player == null || !active()) return;
                if (overlay != null) overlay.remove();
                overlay = new Overlay(player);
                current = overlay;
                overlay.fed(position, duration);
                overlay.tick();
                return;
            }
            overlay.fed(position, duration);
            overlay.update(player);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
        }
    }

    @VisibleForTesting
    static void playerForTests(Player stand) {
        feed = stand;
    }

    @VisibleForTesting
    @Nullable
    static SeekBar barForTests() {
        Overlay overlay = current;
        return overlay == null ? null : overlay.bar;
    }

    @VisibleForTesting
    static void resetForTests() {
        Overlay overlay = current;
        if (overlay != null) overlay.remove();
        current = null;
        feed = PATCHED;
        logged = false;
    }

    /** One player's bar and its ticking. */
    private static final class Overlay implements SeekBar.OnSeekBarChangeListener, View.OnTouchListener, Runnable {
        final WeakReference<Object> player;
        @Nullable SeekBar bar;
        @Nullable Toggle button;
        int position;
        int duration;
        long hiddenSince = -1;
        boolean dragging;
        boolean removed;

        Overlay(Object player) {
            this.player = new WeakReference<>(player);
        }

        void fed(int position, int duration) {
            this.position = position;
            this.duration = duration;
        }

        @Override
        public void run() {
            tick();
        }

        void tick() {
            if (removed) return;
            try {
                Object owner = player.get();
                if (owner == null) {
                    remove();
                    return;
                }
                update(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
            if (!removed) Utils.runOnMainThreadDelayed(this, TICK_MS);
        }

        void update(Object owner) {
            if (removed) return;
            View view = active() && duration > 0 ? feed.view(owner) : null;
            // The view is the video's cover, which Instagram hides while the video plays, so it's the
            // frame holding the cover and the video that has to be on screen.
            FrameLayout frame = view != null && view.getParent() instanceof FrameLayout ? (FrameLayout) view.getParent() : null;
            if (frame == null || !frame.isShown()) {
                hide();
                return;
            }
            SeekBar seek = bar != null && bar.getParent() == frame ? bar : attach(frame, view);
            hiddenSince = -1;
            if (!dragging) {
                if (seek.getMax() != duration) seek.setMax(duration);
                seek.setProgress(Math.max(0, Math.min(duration, position)));
            }
            int barState = wanted() ? View.VISIBLE : View.GONE;
            if (seek.getVisibility() != barState) seek.setVisibility(barState);
            if (barState == View.GONE) dragging = false;
            showButton(frame, view);
        }

        /** Puts the toggle button in [frame] just after [view], or takes it out when its switch is off. */
        private void showButton(FrameLayout frame, View view) {
            if (!buttonWanted()) {
                removeButton();
                return;
            }
            Toggle shown = button;
            if (shown != null && shown.getParent() == frame) {
                shown.invalidate();
                return;
            }
            if (shown == null) {
                shown = new Toggle(view.getContext());
                shown.setOnClickListener(tapped -> toggle());
                button = shown;
            } else if (shown.getParent() != null) {
                ((ViewGroup) shown.getParent()).removeView(shown);
            }
            int size = dp(view, BUTTON_DP);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.START);
            params.setMarginStart(dp(view, 8));
            params.bottomMargin = dp(view, HEIGHT_DP + 4);
            frame.addView(shown, frame.indexOfChild(view) + 1, params);
        }

        private void removeButton() {
            Toggle shown = button;
            button = null;
            if (shown == null) return;
            shown.setOnClickListener(null);
            ViewGroup parent = (ViewGroup) shown.getParent();
            if (parent != null) parent.removeView(shown);
        }

        /** The button's tap: the bar's own switch flips, so the settings and the button always agree. */
        private void toggle() {
            try {
                if (removed || !buttonWanted()) return;
                Settings.FEED_SEEK_BAR.save(!Settings.FEED_SEEK_BAR.get());
                HookStatus.counted(FamilyNames.FEED_SEEK_BAR, COUNT_TOGGLES);
                Object owner = player.get();
                if (owner != null) update(owner);
                if (button != null) button.invalidate();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        /** Hides the bar, ending a drag under it, and takes it away once it's been hidden for {@link #GONE_MS}. */
        private void hide() {
            if (!buttonWanted()) removeButton();
            if (bar != null && bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
            dragging = false;
            long now = SystemClock.uptimeMillis();
            if (hiddenSince < 0) {
                hiddenSince = now;
            } else if (now - hiddenSince >= GONE_MS) {
                remove();
                if (current == this) current = null;
            }
        }

        /**
         * The bar, moved to [frame] just after [view]: over the video and taking touches before it,
         * under the rest of what Instagram draws there.
         */
        private SeekBar attach(FrameLayout frame, View view) {
            SeekBar seek = bar;
            if (seek == null) {
                HookStatus.invoked(FamilyNames.FEED_SEEK_BAR);
                seek = new SeekBar(view.getContext());
                seek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
                seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
                seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x80FFFFFF));
                seek.setOnSeekBarChangeListener(this);
                seek.setOnTouchListener(this);
                bar = seek;
            } else {
                ViewGroup old = (ViewGroup) seek.getParent();
                if (old != null) old.removeView(seek);
            }
            dragging = false;
            frame.addView(seek, frame.indexOfChild(view) + 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(view, HEIGHT_DP), Gravity.BOTTOM));
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Seek bar on feed videos: added a seek bar to a feed video");
            }
            return seek;
        }

        /**
         * A finger landing on the bar keeps the feed and Instagram's main tabs from taking the drag
         * until it lifts. Both claim a sideways or upward move before a SeekBar in a scrolling list
         * starts its own, and the tabs' list takes it even when the bar asks its parents not to, so
         * the tabs are held through {@link TabSwipe}.
         */
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && !removed && wanted()) {
                TabSwipe.hold(true);
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                TabSwipe.hold(false);
            }
            return false;
        }

        void remove() {
            removed = true;
            dragging = false;
            removeButton();
            SeekBar seek = bar;
            bar = null;
            if (seek != null) {
                TabSwipe.hold(false);
                seek.setOnSeekBarChangeListener(null);
                seek.setOnTouchListener(null);
                ViewGroup parent = (ViewGroup) seek.getParent();
                if (parent != null) parent.removeView(seek);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seek) {
            dragging = !removed && wanted();
        }

        @Override
        public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seek) {
            if (!dragging) return;
            dragging = false;
            Object owner = player.get();
            if (owner == null || !wanted()) return;
            try {
                int to = seek.getProgress();
                if (feed.seekTo(owner, to)) {
                    position = to;
                    HookStatus.counted(FamilyNames.FEED_SEEK_BAR, COUNT_SEEKS);
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }
    }

    /**
     * The small button that shows or hides the seek bar: a dark circle holding a line with a dot on it,
     * bright while the bar shows and dimmed with a slash while it's hidden.
     */
    static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription(L10n.t(context, "Seek bar"));
        }

        boolean barShown() {
            return Settings.FEED_SEEK_BAR.get();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            boolean on = barShown();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x99000000);
            canvas.drawCircle(width / 2, height / 2, Math.min(width, height) / 2, paint);
            int ink = on ? Color.WHITE : 0x99FFFFFF;
            float unit = getResources().getDisplayMetrics().density;
            paint.setColor(ink);
            paint.setStrokeWidth(2 * unit);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            float left = width * 0.24f;
            float right = width * 0.76f;
            float y = height / 2;
            canvas.drawLine(left, y, right, y, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(left + (right - left) * 0.4f, y, 3 * unit, paint);
            if (!on) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(width * 0.28f, height * 0.72f, width * 0.72f, height * 0.28f, paint);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Switch.class.getName());
            info.setCheckable(true);
            info.setChecked(barShown());
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
