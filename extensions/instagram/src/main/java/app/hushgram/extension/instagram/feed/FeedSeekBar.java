/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
 * hidden while the video plays, so only its frame counts. A paused video keeps its bar. A drag goes
 * the way Instagram's own feed scrubber goes: the video pauses while a finger is on the bar, and
 * letting go moves it to that point and plays it on from there (#472). Seeking alone, as the bar
 * once did, could leave the video stuck on the frame it was moved to. Most feed posts come without
 * the picture strip a reel has, so with the preview on, the paused video itself follows the drag
 * there and shows the moment the finger is on (#567).
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

    /** The least time between two moves of a paused video following a drag. */
    static final long FOLLOW_MS = 150;

    /** The bar's height, along the bottom of the video. */
    static final int HEIGHT_DP = 32;

    /** The least the bar is tall on the bottom edge (#567), where it's only as tall as its round handle. */
    static final int EDGE_MIN_DP = 12;

    /** How tall the bar is on the bottom edge with a {@linkplain SeekShape#tallHandle() tall handle}, which needs the room. */
    static final int EDGE_TALL_DP = 22;

    static final String HOOK = "feed seek bar";
    static final String COUNT_SEEKS = "feed seeks";
    static final String COUNT_TOGGLES = "feed seek bar toggles";

    /** The toggle button's size, a small circle at the bottom start of the video (#437). */
    static final int BUTTON_DP = 28;

    /** The feed's video player, through the patch's stubs; tests stand in. */
    interface Player {
        @Nullable View view(Object player);
        boolean seekTo(Object player, int positionMs);
        boolean pause(Object player);
        boolean play(Object player);
        /** The picture strip of the post the player has now, for the preview while a drag moves. */
        @Nullable default SeekSheet sheet(Object player) { return null; }
    }

    static final Player PATCHED = new Player() {
        @Override public View view(Object player) { return FeedSeekReader.view(player); }
        @Override public boolean seekTo(Object player, int positionMs) { return FeedSeekReader.seekTo(player, positionMs, true); }
        @Override public boolean pause(Object player) { return FeedSeekReader.pause(player, null) != null; }
        @Override public boolean play(Object player) { return FeedSeekReader.play(player, null, null, null) != null; }
        @Override public SeekSheet sheet(Object player) { return SeekSheet.ofMedia(FeedSeekReader.media(player)); }
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
            FeedDoubleTapSkip.fed(player, position, duration);
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

    /** The bar showing on a feed video now, or null. A carousel's dots pill goes above it (#471). */
    @Nullable
    static View shownBar() {
        Overlay overlay = current;
        GlassSeekBar seek = overlay == null || overlay.removed ? null : overlay.bar;
        return seek != null && seek.getVisibility() == View.VISIBLE && seek.getParent() != null ? seek : null;
    }

    /** The feed's player as the stubs reach it, which {@link FeedDoubleTapSkip} moves too. */
    static Player player() {
        return feed;
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
        @Nullable GlassSeekBar bar;
        @Nullable Toggle button;
        int position;
        int duration;
        long hiddenSince = -1;
        boolean dragging;
        /** Whether the drag under way paused the video, which letting go then plays on. */
        boolean held;
        boolean removed;
        /** Where a drag over a post with no picture strip moves the video next, where it last did, and when. */
        int follow = -1;
        int followed = -1;
        long followedAt = -1;
        boolean followWaits;
        /** Whether a finger on the bar holds the feed and the tabs, and where it landed. */
        boolean claimed;
        float downX;
        float downY;

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
            GlassSeekBar seek = bar != null && bar.getParent() == frame ? bar : attach(frame, view);
            seek.look(Settings.FEED_SEEK_BAR_LOOK.get());
            SeekShape shape = SeekShapes.wanted();
            seek.shape(shape, Settings.FEED_SEEK_BAR_ON_EDGE.get() || shape == SeekShape.LINE);
            fit(seek, ViewGroup.LayoutParams.MATCH_PARENT, barHeight(view, seek), 0);
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
                int size = dp(view, BUTTON_DP);
                fit(shown, size, size, barHeight(view, bar) + dp(view, 4));
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
            params.bottomMargin = barHeight(view, bar) + dp(view, 4);
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
        private GlassSeekBar attach(FrameLayout frame, View view) {
            GlassSeekBar seek = bar;
            if (seek == null) {
                HookStatus.invoked(FamilyNames.FEED_SEEK_BAR);
                seek = new GlassSeekBar(view.getContext(), FamilyNames.FEED_SEEK_BAR, HOOK);
                seek.setOnSeekBarChangeListener(this);
                seek.setOnTouchListener(this);
                bar = seek;
            } else {
                ViewGroup old = (ViewGroup) seek.getParent();
                if (old != null) old.removeView(seek);
            }
            dragging = false;
            frame.addView(seek, frame.indexOfChild(view) + 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barHeight(view, seek), Gravity.BOTTOM));
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
         * the tabs are held through {@link TabSwipe}. A finger that moves up or down past the touch slop
         * before the bar starts its own drag is a scroll that began on the bar, so the feed and the
         * tabs get it back and Home scrolls.
         */
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && !removed && wanted()) {
                claimed = true;
                downX = event.getX();
                downY = event.getY();
                TabSwipe.hold(true);
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_MOVE && claimed && !dragging) {
                float across = Math.abs(event.getX() - downX);
                float down = Math.abs(event.getY() - downY);
                if (down > ViewConfiguration.get(view.getContext()).getScaledTouchSlop() && down > across) {
                    claimed = false;
                    TabSwipe.hold(false);
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                claimed = false;
                TabSwipe.hold(false);
            }
            return false;
        }

        /** A bar taken away mid-drag leaves its video to the feed, which plays the video it shows. */
        void remove() {
            removed = true;
            dragging = false;
            held = false;
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

        /** A finger on the bar pauses the video, as Instagram's own scrubber does with its "seek" reason. */
        @Override
        public void onStartTrackingTouch(SeekBar seek) {
            dragging = !removed && wanted();
            held = false;
            follow = followed = -1;
            followedAt = -1;
            Object owner = player.get();
            if (!dragging || owner == null) return;
            try {
                held = feed.pause(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        @Override
        public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
            if (!fromUser || !dragging || !SeekPreview.on()) return;
            try {
                Object owner = player.get();
                SeekSheet sheet = owner != null ? feed.sheet(owner) : null;
                SeekPreview.dragged(seek, progress, sheet, FamilyNames.FEED_SEEK_BAR);
                if (owner != null && sheet == null) follow(progress);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        /**
         * Moves the paused video to [to], so a post with no picture strip shows the moment in the video
         * itself: at once when the last move was {@link #FOLLOW_MS} ago, otherwise once that time is up,
         * to wherever the drag is by then.
         */
        private void follow(int to) {
            follow = to;
            long wait = followedAt < 0 ? 0 : followedAt + FOLLOW_MS - SystemClock.uptimeMillis();
            if (wait <= 0) {
                followNow();
            } else if (!followWaits) {
                followWaits = true;
                Utils.runOnMainThreadDelayed(() -> {
                    followWaits = false;
                    followNow();
                }, wait);
            }
        }

        private void followNow() {
            Object owner = player.get();
            if (!dragging || removed || owner == null || follow < 0 || follow == followed) return;
            try {
                followedAt = SystemClock.uptimeMillis();
                if (feed.seekTo(owner, follow)) followed = follow;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        /**
         * Letting go moves the video to the bar's point and plays it on from there. A drag the bar's
         * hiding ended seeks nothing, but a video it paused still plays on.
         */
        @Override
        public void onStopTrackingTouch(SeekBar seek) {
            boolean seeks = dragging;
            boolean plays = held;
            dragging = false;
            held = false;
            Object owner = player.get();
            if (owner == null || !(seeks || plays)) return;
            try {
                if (seeks && wanted()) {
                    int to = seek.getProgress();
                    if (feed.seekTo(owner, to)) {
                        position = to;
                        HookStatus.counted(FamilyNames.FEED_SEEK_BAR, COUNT_SEEKS);
                    }
                }
                if (plays) feed.play(owner);
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

    /**
     * How tall the bar is: room for a finger along the bottom of the video, or with
     * {@link Settings#FEED_SEEK_BAR_ON_EDGE} on only its round handle's height, so it sits right on the
     * video's bottom edge and the rest of the video takes taps and scrolls as before (#567).
     */
    static int barHeight(View view, @Nullable SeekBar seek) {
        SeekShape shape = SeekShapes.wanted();
        if (!Settings.FEED_SEEK_BAR_ON_EDGE.get() && shape != SeekShape.LINE) return dp(view, HEIGHT_DP);
        if (shape.tallHandle()) return dp(view, EDGE_TALL_DP);
        Drawable handle = seek != null ? seek.getThumb() : null;
        return Math.max(handle != null ? handle.getIntrinsicHeight() : 0, dp(view, EDGE_MIN_DP));
    }

    /** Gives [child], already in a frame, the size and bottom margin asked, when it has others. */
    private static void fit(View child, int width, int height, int bottomMargin) {
        if (!(child.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
        if (params.width == width && params.height == height && params.bottomMargin == bottomMargin) return;
        params.width = width;
        params.height = height;
        params.bottomMargin = bottomMargin;
        child.setLayoutParams(params);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
