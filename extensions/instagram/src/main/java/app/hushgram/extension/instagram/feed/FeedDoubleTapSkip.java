/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Double tap to skip on feed videos, which comes with the Seek bar on feed videos patch
 * (#562).
 *
 * <p>The gesture listener Instagram keeps for each post's media frame in a feed hands every double
 * tap to {@link #doubleTap} first, with that frame and the tap. The feed's video player tells
 * {@link #fed}, through {@link FeedSeekBar#progress}, which player moved on last and where its video
 * was. A double tap inside that video's frame, on its left third, moves the video back
 * {@link #skipMs()}, and on its right third ahead, with the same pause, seek and play Instagram's own
 * feed scrubber makes, and a pair of arrows flashes on that side. The answer true returns from the
 * listener before Instagram likes the post.
 *
 * <p>A double tap in the middle third, on a post other than the playing video, while the switch is
 * off, HushGram is paused or the settings aren't read, or when anything here throws, answers false,
 * and Instagram's double tap goes on as it always did.
 */
public final class FeedDoubleTapSkip {
    /** How long the player's last position, plus the time since, still says where the video is. */
    static final long FRESH_MS = 1000;

    /** How long the arrows show. */
    static final long FLASH_MS = 600;

    /** The arrows' size, and how far they sit from the video's edge. */
    static final int FLASH_DP = 64;
    static final int FLASH_MARGIN_DP = 32;

    static final String HOOK = "feed double tap skip";
    static final String COUNT_SKIPS = "feed double tap skips";

    private static WeakReference<Object> last = new WeakReference<>(null);
    private static int position;
    private static int duration;
    private static long fedAt;
    @Nullable private static WeakReference<Flash> shown;
    private static volatile boolean logged;

    private FeedDoubleTapSkip() {
    }

    /** Whether a double tap skips: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.FEED_DOUBLE_TAP_SKIP.get();
    }

    /** How far a double tap moves the video, in milliseconds: Skip length's seconds (#645, 10 to start). */
    static int skipMs() {
        return Settings.FEED_DOUBLE_TAP_SKIP_SECONDS.get() * 1000;
    }

    /** The player's progress, on the main thread: where its video is and how long it is, in milliseconds. */
    static void fed(@Nullable Object player, int at, int length) {
        if (player == null || !wanted()) return;
        if (last.get() != player) last = new WeakReference<>(player);
        position = at;
        duration = length;
        fedAt = SystemClock.uptimeMillis();
    }

    /**
     * Called first thing in the feed post gesture listener's double tap, with the post's media frame
     * and the tap. True when the tap moved the playing video, so Instagram doesn't like the post.
     * Never throws.
     */
    public static boolean doubleTap(@Nullable View frame, @Nullable MotionEvent event) {
        try {
            if (frame == null || event == null || !wanted()) return false;
            Object player = last.get();
            if (player == null || duration <= 0) return false;
            FeedSeekBar.Player feed = FeedSeekBar.player();
            View cover = feed.view(player);
            // The cover hides while the video plays, so it's the frame holding both that counts.
            FrameLayout video = cover != null && cover.getParent() instanceof FrameLayout ? (FrameLayout) cover.getParent() : null;
            if (video == null || !video.isShown()) return false;
            float x = event.getRawX();
            float y = event.getRawY();
            if (!inside(frame, x, y) || !inside(video, x, y)) return false;
            int side = side(video, x);
            if (side == 0) return false;

            long now = SystemClock.uptimeMillis();
            long since = now - fedAt;
            int from = position + (since >= 0 && since < FRESH_MS ? (int) since : 0);
            int skip = skipMs();
            int to = target(from, side, duration, skip);
            HookStatus.invoked(FamilyNames.FEED_SEEK_BAR);
            boolean held = false;
            try {
                held = feed.pause(player);
                if (feed.seekTo(player, to)) {
                    position = to;
                    fedAt = now;
                    HookStatus.counted(FamilyNames.FEED_SEEK_BAR, COUNT_SKIPS);
                }
            } finally {
                if (held) feed.play(player);
            }
            flash(video, side, skip / 1000);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Double tap to skip on feed videos: moved a feed video " + (side < 0 ? "back" : "ahead"));
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            return false;
        }
    }

    /** Where a skip of [skip] milliseconds from [from] lands: back for a negative [side], ahead otherwise, inside the video. */
    static int target(int from, int side, int length, int skip) {
        long to = (long) from + (side < 0 ? -skip : skip);
        return (int) Math.max(0, Math.min(Math.max(0, length - 1), to));
    }

    /** -1 for the left third of [video] at screen x [x], 1 for the right third, 0 for the middle. */
    static int side(View video, float x) {
        int[] at = new int[2];
        video.getLocationOnScreen(at);
        int width = video.getWidth();
        if (width <= 0) return 0;
        float fraction = (x - at[0]) / width;
        if (fraction < 1f / 3) return -1;
        if (fraction > 2f / 3) return 1;
        return 0;
    }

    /** Whether the screen point [x], [y] is on [view]. */
    private static boolean inside(View view, float x, float y) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
    }

    /**
     * Shows the arrows on [side] of [video] with the [seconds] moved under them, over what came before
     * them, and takes them away after {@link #FLASH_MS}.
     */
    private static void flash(FrameLayout video, int side, int seconds) {
        Flash old = shown == null ? null : shown.get();
        if (old != null) old.remove();
        Flash arrows = new Flash(video.getContext(), side, seconds);
        int size = dp(video, FLASH_DP);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size,
                Gravity.CENTER_VERTICAL | (side < 0 ? Gravity.LEFT : Gravity.RIGHT));
        params.leftMargin = dp(video, FLASH_MARGIN_DP);
        params.rightMargin = params.leftMargin;
        video.addView(arrows, params);
        shown = new WeakReference<>(arrows);
        arrows.animate().alpha(0f).setStartDelay(FLASH_MS / 2).setDuration(FLASH_MS / 2);
        Utils.runOnMainThreadDelayed(arrows::remove, FLASH_MS);
    }

    @VisibleForTesting
    @Nullable
    static Flash flashForTests() {
        Flash arrows = shown == null ? null : shown.get();
        return arrows == null || arrows.getParent() == null ? null : arrows;
    }

    @VisibleForTesting
    static void resetForTests() {
        Flash arrows = shown == null ? null : shown.get();
        if (arrows != null) arrows.remove();
        shown = null;
        last = new WeakReference<>(null);
        position = 0;
        duration = 0;
        fedAt = 0;
        logged = false;
    }

    /** Two arrows on a dark circle, pointing the way the video moved, with the seconds under them. */
    static final class Flash extends View {
        final int side;
        final int seconds;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Flash(Context context, int side, int seconds) {
            super(context);
            this.side = side;
            this.seconds = seconds;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void remove() {
            animate().cancel();
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float unit = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x80000000);
            canvas.drawCircle(width / 2, height / 2, Math.min(width, height) / 2, paint);

            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * unit);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            float middle = height * 0.4f;
            float arm = height * 0.11f;
            float step = width * 0.14f;
            for (int each = 0; each < 2; each++) {
                float tip = width / 2 + side * (each * step - step / 2 + arm / 2);
                float back = tip - side * arm;
                canvas.drawLine(back, middle - arm, tip, middle, paint);
                canvas.drawLine(tip, middle, back, middle + arm, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(13 * unit);
            canvas.drawText(String.valueOf(seconds), width / 2, height * 0.78f, paint);
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
