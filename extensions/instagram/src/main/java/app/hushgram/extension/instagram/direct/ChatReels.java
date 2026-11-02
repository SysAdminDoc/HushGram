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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
 * Paging the two-sided source, the viewer asks for more of the chat's own reels the same way it asks
 * for suggested ones, so the patch lets that request through for that source and leaves the stop to
 * the second side's. Pulling down at the top loads fresh reels, suggested ones, wherever the viewer's
 * config allows it, so the patch asks {@link #refresh(Object)} at every read of that permission, and
 * past the chat's last reel {@link ChatReelsExit} takes a swipe up back to the chat.
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

    /** How many reels the viewer sent in chats are remembered at most. */
    static final int SENT_KEPT = 500;

    /** The message whose viewer is on its way, or null, and when it was tapped. */
    @Nullable private static volatile Object opening;
    private static volatile long openedAt;
    /** The media ids of reels the viewer sent in chats, oldest first. */
    private static final Map<String, Boolean> SENT = new LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SENT_KEPT;
        }
    };
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
     * Whether [message], one of the chat's messages the opener is about to add to the chain of reels,
     * joins it: no for a message the viewer sent while "Only received reels" is on, unless it's the one
     * just tapped (its id shows in the tapped message's text). Yes for any other message, with the
     * switch off, while HushGram is paused, before the settings are ready and on any failure. A sent
     * message's reel, read from [wrapper], the message's wrapper, is also remembered for
     * {@link #leaveOutSent(List, Object)}, or forgotten when it's the one tapped. Never throws.
     */
    public static boolean received(@Nullable Object message, @Nullable Object wrapper) {
        boolean sent;
        String id;
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            if (message == null || !Utils.settingsReady() || HushgramPause.isPaused()) return true;
            if (!Settings.ONLY_RECEIVED_CHAT_REELS.get()) return true;
            sent = sentByViewer(message);
            id = sent ? itemId(message) : null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels sender", failure);
            return true;
        }
        if (sent && wrapper != null) remember(wrapper, id);
        return received(sent, id);
    }

    /** The test behind {@link #received(Object, Object)}, for a message the viewer sent or not and its item id. */
    static boolean received(boolean sent, @Nullable String id) {
        if (!sent || isTapped(id)) return true;
        HookStatus.counted(FamilyNames.CHAT_REELS, "sent reel left out of the chain");
        return false;
    }

    /** Remembers the reel of [wrapper], a message the viewer sent with item [id], or forgets it when it's the one tapped. */
    private static void remember(Object wrapper, @Nullable String id) {
        try {
            Object reel = reel(wrapper);
            rememberSent(reel == null ? null : mediaId(reel), id);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels sent media", failure);
        }
    }

    /** Remembers [media], the reel of a message the viewer sent with item [id], or forgets it when that message is the one tapped. */
    static void rememberSent(@Nullable String media, @Nullable String id) {
        if (media == null) return;
        boolean tapped = isTapped(id);
        synchronized (SENT) {
            if (tapped) SENT.remove(media);
            else SENT.put(media, Boolean.TRUE);
        }
    }

    /**
     * The reels to add to a viewer whose source is [source], its ClipsViewerSource, out of [items], a
     * page of them. A viewer opened from a chat pages the chat's reels from the server too, past the
     * opener, and merges each page in through its update or an insert rather than its add, so while
     * "Only received reels" is on the reels the viewer sent there, as remembered by
     * {@link #received(Object, Object)}, are left out of the page whichever way it comes in. [items] for
     * any other viewer, with the switch off, while HushGram is paused, before the settings are ready and
     * on any failure. Never throws.
     */
    @Nullable
    public static List<?> leaveOutSent(@Nullable List<?> items, @Nullable Object source) {
        return leaveOutSent(items, source, ChatReels::mediaId);
    }

    /** {@link #leaveOutSent(List, Object)}, reading each reel's media id with [ids]. */
    @Nullable
    static List<?> leaveOutSent(@Nullable List<?> items, @Nullable Object source, Function<Object, String> ids) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            if (items == null || items.isEmpty() || !fromChat(source)) return items;
            if (!Utils.settingsReady() || HushgramPause.isPaused() || !Settings.ONLY_RECEIVED_CHAT_REELS.get()) return items;
            synchronized (SENT) {
                if (SENT.isEmpty()) return items;
            }
            List<Object> kept = null;
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (!isSent(item == null ? null : ids.apply(item))) {
                    if (kept != null) kept.add(item);
                    continue;
                }
                if (kept == null) kept = new ArrayList<>(items.subList(0, i));
                HookStatus.counted(FamilyNames.CHAT_REELS, "sent reel left out of a page");
            }
            return kept == null ? items : kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels page", failure);
            return items;
        }
    }

    /** Whether [media] is a reel remembered as sent by the viewer in a chat. */
    static boolean isSent(@Nullable String media) {
        if (media == null) return false;
        synchronized (SENT) {
            return SENT.containsKey(media);
        }
    }

    /** Forgets every reel remembered as sent, for tests. */
    static void forgetSent() {
        synchronized (SENT) {
            SENT.clear();
        }
    }

    /** Whether the message just tapped is the one with [id]. */
    static boolean isTapped(@Nullable String id) {
        Object tapped = opening;
        return id != null && tapped != null && String.valueOf(tapped).contains(id);
    }

    /** Stub: the patch writes this, answering whether the viewer sent [message]. */
    public static boolean sentByViewer(Object message) {
        return false;
    }

    /** Stub: the patch writes this, answering [message]'s item id. */
    @Nullable
    public static String itemId(Object message) {
        return null;
    }

    /** Stub: the patch writes this, answering the reel item of [wrapper], a message's wrapper, or null. */
    @Nullable
    public static Object reel(Object wrapper) {
        return null;
    }

    /** Stub: the patch writes this, answering the media id of [reel], a reel item, or null. */
    @Nullable
    public static String mediaId(Object reel) {
        return null;
    }

    /**
     * Whether the viewer skips putting in one of Instagram's cached reels: yes while the switch is on
     * and [source], the viewer's ClipsViewerSource, is a chat's. No for any other viewer, and while
     * the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean stopCached(Object source) {
        return fromChat(source) && stop();
    }

    /** Whether [source], a viewer's ClipsViewerSource, is a chat's. */
    static boolean fromChat(@Nullable Object source) {
        return source instanceof Enum && CHAT_SOURCE.equals(((Enum<?>) source).name());
    }

    /**
     * Whether a Reels viewer Instagram lets load fresh reels at a pull down still may: no while
     * {@link #stopCached(Object)} is yes for [source], the viewer's ClipsViewerSource, since the fresh
     * reels are suggested ones and they'd take the chat's place (#390). Yes for any other viewer, and
     * while the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean refresh(Object source) {
        return !stopCached(source);
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
