/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.os.Looper;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;

/**
 * The play button a tap started a video from, kept out of sight while that video plays.
 *
 * <p>Instagram draws the play button on a feed video, a reel in the feed and a carousel page with
 * Litho, from the state the post was drawn with, and draws the post again only when something
 * about it changes. A tap on the button starts the video without changing anything the post is
 * drawn from, so the button stayed over the video it had started. The view-based button Instagram
 * uses elsewhere hides itself on a tap. This does the same for the Litho one: the tapped button's
 * view goes invisible, and comes back when the start it asked for ends (a pause, or another video
 * on its player), when no start comes of the tap within {@link #CLAIM_WINDOW_MS}, or when Litho
 * takes the view off the screen, since Litho hands a view it took off to the next thing it draws.
 * One button is hidden at a time, as Instagram plays one feed video at a time. A tap on the video
 * itself starts it without the button's click, so the button under that tap is found instead
 * ({@link PlayButtonFinder}) and hidden the same way (#229).
 *
 * <p>The click hands the start to Instagram before the hook hears of it, and Instagram usually
 * starts the video inside that call, so a start that came after the tap and before the click
 * returned is the button's as much as one that comes after it.
 */
final class PlayButtons {
    /** How long after a tap on a button a start still counts as the button's. */
    static final long CLAIM_WINDOW_MS = TapToPlay.LOAD_WINDOW_MS;

    /** How long a hidden button waits for its start before it comes back. */
    static final long UNCLAIMED_MS = CLAIM_WINDOW_MS + 500;

    private static final String SOURCE = "TapToPlay";

    private static final Object LOCK = new Object();

    @Nullable
    private static Hidden hidden;

    /** The last start a tap let through, which the tapped button's own click may have set off. */
    @Nullable
    private static Start latest;

    private PlayButtons() { }

    /**
     * The view a Litho click event carries: the event's own View field, whatever Instagram's build
     * named it. Null when there's no event or it carries no view.
     */
    @Nullable
    static View viewOf(@Nullable Object click) throws IllegalAccessException {
        if (click instanceof View) return (View) click;
        for (Class<?> type = click == null ? null : click.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !View.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(click);
                if (value != null) return (View) value;
            }
        }
        return null;
    }

    /**
     * Hides [button], whose click at [now] came of the tap that ended at [tapAt], bringing back any
     * button hidden before it. A start that came after that tap is the button's.
     */
    static void hide(View button, long now, long tapAt) {
        Hidden previous;
        Hidden next = new Hidden(button, now, tapAt);
        boolean claimed;
        synchronized (LOCK) {
            Hidden current = hidden;
            // Found under the same tap before its click got here: already hidden for this start.
            if (current != null && current.tapAt == tapAt && current.view.get() == button) return;
            Start start = latest;
            if (start != null && start.at >= tapAt && now - start.at <= CLAIM_WINDOW_MS) next.player = start.player;
            claimed = next.player != null;
            previous = hidden;
            hidden = next;
        }
        if (previous != null) previous.restore("another video's button was tapped");
        next.hide();
        log(claimed ? "the play button is hidden while its video plays" : "the play button is hidden until its video starts");
    }

    /**
     * A start a tap let through at [now] on [player]: the hidden button's, if it came after the
     * button's tap and soon enough after its click. A start decided on another thread just before
     * the tap can reach here after the click.
     */
    static void started(@Nullable Object player, long now) {
        started(player, now, TapClock.NO_TAP, false);
    }

    /**
     * The same, for a start the tap that came up at [tapAt] let through. When it's [byTap], that
     * tap's first start of a player outside Reels, and no button is hidden for the tap, the play
     * button under the tap is hidden as if it had been tapped: a tap on the video itself starts it
     * through Instagram's autoplay, and the button's click never runs (#229).
     */
    static void started(@Nullable Object player, long now, long tapAt, boolean byTap) {
        if (player == null) return;
        WeakReference<Object> started = new WeakReference<>(player);
        synchronized (LOCK) {
            latest = new Start(started, now);
            Hidden current = hidden;
            if (current != null && current.player == null && now >= current.tapAt && now - current.at <= CLAIM_WINDOW_MS) {
                current.player = started;
            }
        }
        if (byTap) hideUnderTap(started, now, tapAt);
    }

    /**
     * Finds the play button under the tap that came up at [tapAt], on the main thread, and hides it
     * for [player]'s start at [now], unless that tap's own button is hidden already, a newer tap came,
     * or the start has ended by then.
     */
    private static void hideUnderTap(WeakReference<Object> player, long now, long tapAt) {
        View window = TapClock.window();
        if (window == null) return;
        // Posted even from the main thread, so the start Instagram is in the middle of finishes first.
        window.post(() -> {
            float[] point = TapClock.pointOf(tapAt);
            Object playing = player.get();
            if (point == null || playing == null || !TapToPlay.armed(playing)) return;
            synchronized (LOCK) {
                Hidden current = hidden;
                if (current != null && (current.tapAt == tapAt || current.player != null && current.player.get() == playing)) return;
            }
            View button = PlayButtonFinder.find(window, point[0], point[1]);
            if (button == null) {
                log("a tap on a video started it, and no play button was found under the tap");
                return;
            }
            Hidden previous;
            Hidden next = new Hidden(button, now, tapAt);
            synchronized (LOCK) {
                next.player = player;
                previous = hidden;
                hidden = next;
            }
            if (previous != null) previous.restore("another video started");
            next.hide();
            log("a tap on the video started it, so its play button is hidden while it plays");
        });
    }

    /** What a tap started on [player] has ended: its button comes back. */
    static void ended(@Nullable Object player) {
        Hidden ended = null;
        synchronized (LOCK) {
            Hidden current = hidden;
            if (player != null && current != null && current.player != null && current.player.get() == player) {
                ended = current;
                hidden = null;
            }
        }
        if (ended != null) ended.restore("its video stopped");
    }

    /** The button hidden now, or null. For tests. */
    @Nullable
    static View hiddenButton() {
        synchronized (LOCK) {
            return hidden == null ? null : hidden.view.get();
        }
    }

    /** Forgets the hidden button and the last start without showing it. For tests. */
    static void forget() {
        synchronized (LOCK) {
            hidden = null;
            latest = null;
        }
    }

    /** Brings [which] back, for [why], if it's still the hidden button. */
    private static void release(Hidden which, String why) {
        synchronized (LOCK) {
            if (hidden != which) return;
            hidden = null;
        }
        which.restore(why);
    }

    private static void log(String line) {
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> "Tap to play: " + line);
    }

    private static void onMain(View view, Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else view.post(action);
    }

    private static final class Start {
        final WeakReference<Object> player;
        final long at;

        Start(WeakReference<Object> player, long at) {
            this.player = player;
            this.at = at;
        }
    }

    private static final class Hidden implements View.OnAttachStateChangeListener {
        final WeakReference<View> view;
        final long at;
        final long tapAt;
        /** The player the tap's start went to; null until it comes. Written under [LOCK]. */
        @Nullable
        WeakReference<Object> player;
        private int visibility = View.VISIBLE;

        Hidden(View view, long at, long tapAt) {
            this.view = new WeakReference<>(view);
            this.at = at;
            this.tapAt = tapAt;
        }

        void hide() {
            View button = view.get();
            if (button == null) return;
            onMain(button, () -> {
                visibility = button.getVisibility();
                button.setVisibility(View.INVISIBLE);
                button.addOnAttachStateChangeListener(this);
            });
            button.postDelayed(() -> {
                boolean unclaimed;
                synchronized (LOCK) {
                    unclaimed = player == null;
                }
                if (unclaimed) release(this, "no video started");
            }, UNCLAIMED_MS);
        }

        void restore(String why) {
            View button = view.get();
            if (button == null) return;
            log("the play button is back, " + why);
            onMain(button, () -> {
                button.removeOnAttachStateChangeListener(this);
                if (button.getVisibility() == View.INVISIBLE) button.setVisibility(visibility);
            });
        }

        @Override
        public void onViewAttachedToWindow(View v) { }

        @Override
        public void onViewDetachedFromWindow(View v) {
            release(this, "Instagram took it off the screen");
        }
    }
}
