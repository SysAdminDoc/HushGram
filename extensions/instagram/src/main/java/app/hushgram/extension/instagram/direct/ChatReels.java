/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Swipe through a chat's reels" patch (#350).
 *
 * <p>Instagram already knows how to chain the reels shared in a chat, behind a server test. With
 * the test on, a reel opened from a chat takes the chat's other shared reels along into the Reels
 * viewer, so swiping up goes through the ones shared there before Instagram's suggested reels, and
 * Back still lands in the chat. The patch answers the test's one read through {@link #chain(int)}.
 *
 * <p>The viewer gets a chat's reels one of two ways: handed all of them when it opens, then asking
 * for more past them, which are suggested reels, or paging a source with two sides, the chat's own
 * shared reels and then Instagram's suggested ones. Near its last reel it also puts in reels
 * Instagram keeps cached for the Reels tab, without asking the server. The patch asks
 * {@link #stop()} before a viewer opened from a chat asks for more, and before it asks for that
 * second side, and {@link #stopCached(Object)} before a cached reel goes in, so with the second
 * switch on it ends after the chat's reels (#390). The viewer also puts a loading placeholder
 * after the reels it adds, for the next ones to replace, which {@link #placeholder(Object, int)}
 * leaves out there, so the last page is the chat's last reel and not a gray one that never loads.
 *
 * <p>The patch also asks {@link #open(Object, View)} first on every tap on media in a chat. On a slow
 * phone a shared post or reel takes a while to open, and each tap while it loads starts another
 * open, so the viewers stack and Back has to close each one (#390).
 */
public final class ChatReels {
    /** The name of Instagram's viewer source for a reel opened from a chat. */
    private static final String CHAT_SOURCE = "DIRECT";

    /** How long a repeat tap on a message waits at most for the first tap's viewer, in ms. */
    static final long OPENING_MS = 10_000;

    /** The message whose viewer is on its way, or null, and when it was tapped. */
    @Nullable private static volatile Object opening;
    private static volatile long openedAt;
    /** The application the activity watcher is registered on. */
    @Nullable private static Application watched;

    private ChatReels() {
    }

    /**
     * The chat reels test: Instagram's answer ([answer] nonzero for yes), or yes while the switch is
     * on. Instagram's answer while the switch is off, HushGram is paused or the settings aren't
     * ready. Never throws.
     */
    public static boolean chain(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            return instagram || (Utils.settingsReady() && Settings.SWIPE_CHAT_REELS.get());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels flag", failure);
            return instagram;
        }
    }

    /**
     * Whether the viewer of a chat's reels skips asking for suggested reels once the chat's own run
     * out: yes while the switch is on. No while it's off, HushGram is paused or the settings aren't
     * ready, so Instagram goes on to its suggestions. Never throws.
     */
    public static boolean stop() {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            return Utils.settingsReady() && Settings.STOP_AFTER_CHAT_REELS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels end", failure);
            return false;
        }
    }

    /**
     * Whether the viewer skips putting in one of Instagram's cached reels: yes while the switch is on
     * and [source], the viewer's ClipsViewerSource, is a chat's. No for any other viewer, and while
     * the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean stopCached(Object source) {
        return source instanceof Enum && CHAT_SOURCE.equals(((Enum<?>) source).name()) && stop();
    }

    /**
     * Whether the viewer puts its loading placeholder after the reels it adds: Instagram's [ghost]
     * (nonzero for yes), except no while {@link #stopCached(Object)} is yes for [source], since
     * nothing would replace it. Never throws.
     */
    public static boolean placeholder(Object source, int ghost) {
        return ghost != 0 && !stopCached(source);
    }

    /**
     * Whether a tap on [message]'s media in a chat goes on to Instagram's open. No for a repeat tap
     * on the same message until the first tap's viewer covers the chat: an activity pauses, the
     * chat's window loses focus or [view], the tapped bubble, leaves it, or {@link #OPENING_MS} pass.
     * Yes for any other message, while HushGram is paused, before the settings are ready and on any
     * failure. Never throws.
     */
    public static boolean open(@Nullable Object message, @Nullable View view) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            if (message == null || !Utils.settingsReady() || HushgramPause.isPaused()) return true;
            long now = SystemClock.uptimeMillis();
            if (message.equals(opening) && now - openedAt < OPENING_MS) {
                HookStatus.counted(FamilyNames.CHAT_REELS, "repeat tap on a chat's media");
                return false;
            }
            opening = message;
            openedAt = now;
            watch(message, view);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat media tap", failure);
            return true;
        }
    }

    /** The viewer covers the chat, or the chat is gone, so the next tap opens again. */
    static void covered() {
        opening = null;
    }

    private static void watch(Object message, @Nullable View view) {
        Context context = Utils.getContext();
        Context app = context == null ? null : context.getApplicationContext();
        if (app instanceof Application && app != watched) {
            ((Application) app).registerActivityLifecycleCallbacks(new Watcher());
            watched = (Application) app;
        }
        if (view == null) return;
        Cover cover = new Cover(message, view);
        view.addOnAttachStateChangeListener(cover);
        view.getViewTreeObserver().addOnWindowFocusChangeListener(cover);
    }

    /** Clears [message]'s open once its bubble's window loses focus or the bubble leaves it, then stops listening. */
    private static final class Cover implements View.OnAttachStateChangeListener, ViewTreeObserver.OnWindowFocusChangeListener {
        private final Object message;
        private final View view;

        Cover(Object message, View view) {
            this.message = message;
            this.view = view;
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            if (!hasFocus) done();
        }

        @Override public void onViewDetachedFromWindow(@NonNull View v) {
            done();
        }

        @Override public void onViewAttachedToWindow(@NonNull View v) { }

        private void done() {
            try {
                if (opening == message) covered();
                view.removeOnAttachStateChangeListener(this);
                ViewTreeObserver observer = view.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(this);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CHAT_REELS, "chat media tap cover", failure);
            }
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityPaused(@NonNull Activity activity) {
            covered();
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityResumed(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
