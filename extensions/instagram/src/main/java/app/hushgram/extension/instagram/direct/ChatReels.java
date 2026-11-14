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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
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
 *
 * <p>The reply bar under a reel from a chat answers the message that shared it, but Instagram only
 * keeps the tapped reel's. {@link #collected(Object, Object, Object)} remembers each message's reply
 * info, chat by chat, as Instagram goes through the chat's messages, and {@link #replyTo(Object, Object, Object, int)} and
 * {@link #replyInfo(Object, Object, Object)} hand the bar the one for the reel on screen (#488).
 * {@link #received(Object, Object)} also hands each message to {@link ChatReelsNotice}, whose banner
 * says when the other person wrote after the reel on screen (#489).
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

    /** How many chats keep their reels' messages for the reply bar at most. */
    static final int CHATS_KEPT = 4;
    /** How many reels of one chat keep their messages for the reply bar at most. */
    static final int REPLIES_KEPT = 500;
    /** How many of one reel's messages are kept at most. */
    static final int MESSAGES_KEPT = 50;
    /** The taps on media in a chat that went on to open, counted. */
    private static volatile int taps;
    /** The chats whose messages' reply infos are remembered, the one collected from last at the end. */
    private static final List<ChatReplies> CHATS = new ArrayList<>();
    /** The chat the last tap's viewer answers from, once Instagram's info named it, or null. Guarded by {@link #CHATS}. */
    @Nullable private static ChatReplies viewerChat;
    /** The tap {@link #viewerChat} was named after. Guarded by {@link #CHATS}. */
    private static int viewerTap = -1;
    /** Instagram's own answer to the chat reels test, a server test it reads once a session: yes chains a chat's reels without the switch. */
    private static volatile boolean instagramChains;

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
            if (instagramChains != instagram) {
                instagramChains = instagram;
                Logger.printDebug(() -> "Instagram's own chat reels test: " + (instagram ? "on" : "off"));
            }
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
            ChatReelsNotice.note(message, wrapper);
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
     * Remembers [info], the reply info Instagram just built for one of a chat's messages, the one in
     * [wrapper], so a reel swiped to from that chat gets the reply bar and reactions for its own message
     * (#488). [chat] is the chat's collector, which Instagram runs the chat's messages through as the
     * chat loads, before any tap, and the opener runs again only one of its ways in (#658), so the
     * replies are kept chat by chat rather than tap by tap. Instagram keeps only the tapped reel's info
     * and asks for it by position, so past the first reel its bar hid and a reaction quoted the tapped
     * message. Nothing while the chat's reels aren't chained (see {@link #chaining()}), HushGram is
     * paused or the settings aren't ready, and nothing is ever sent. Never throws.
     */
    public static void collected(@Nullable Object info, @Nullable Object wrapper, @Nullable Object chat) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            if (info == null || wrapper == null || chat == null || !Utils.settingsReady() || HushgramPause.isPaused()) return;
            if (!chaining()) return;
            Object reel = reel(wrapper);
            if (reel != null) rememberReply(chat, mediaId(reel), messageOf(info), info);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels reply", failure);
        }
    }

    /** Remembers [info] as the reply info of [message], a message in [chat] holding the reel [media]. */
    static void rememberReply(Object chat, @Nullable String media, @Nullable String message, Object info) {
        if (media == null || message == null || message.isEmpty()) return;
        synchronized (CHATS) {
            Map<String, Map<String, Object>> reels = chatOf(chat).reels;
            Map<String, Object> messages = reels.remove(media);
            if (messages == null) messages = new Kept<>(MESSAGES_KEPT);
            messages.remove(message);
            messages.put(message, info);
            reels.put(media, messages);
        }
    }

    /**
     * [chat]'s remembered replies, new ones when it has none, moved to the end as the chat collected
     * from last. Chats that are gone and the oldest past {@link #CHATS_KEPT} are let go. Guarded by
     * {@link #CHATS}.
     */
    private static ChatReplies chatOf(Object chat) {
        ChatReplies found = null;
        for (int i = CHATS.size() - 1; i >= 0; i--) {
            Object held = CHATS.get(i).chat.get();
            if (held != null && held != chat) continue;
            ChatReplies replies = CHATS.remove(i);
            if (held != null) found = replies;
        }
        if (found == null) found = new ChatReplies(chat);
        CHATS.add(found);
        while (CHATS.size() > CHATS_KEPT) CHATS.remove(0);
        return found;
    }

    /**
     * The reply info for the reel at [position] of [adapter], a viewer's adapter, when the reply bar
     * updates for that page: [instagram], Instagram's own, or the info of the reel's own message in the
     * chat (see {@link #reply(String, String)}). [instagram] for any viewer but a chat's ([source] is its
     * ClipsViewerSource), while the chat's reels aren't chained, while HushGram is paused, before the
     * settings are ready and on any failure. Never throws.
     */
    @Nullable
    public static Object replyTo(@Nullable Object instagram, @Nullable Object source, @Nullable Object adapter, int position) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            if (adapter == null || position < 0 || !replies(source)) return instagram;
            if (position >= count(adapter)) return instagram;
            Object own = ownReply(instagram, item(adapter, position));
            if (own == null) return instagram;
            HookStatus.counted(FamilyNames.CHAT_REELS, "reply bar given a swiped reel's message");
            return own;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels reply bar", failure);
            return instagram;
        }
    }

    /**
     * The reply info a reply typed or a reaction sent on [item], the reel on screen, quotes:
     * [instagram], Instagram's own, or the info of the reel's own message in the chat (see
     * {@link #reply(String, String)}). [instagram] for any viewer but a chat's ([source] is its
     * ClipsViewerSource), while the chat's reels aren't chained, while HushGram is paused, before the
     * settings are ready and on any failure. Never throws.
     */
    @Nullable
    public static Object replyInfo(@Nullable Object instagram, @Nullable Object source, @Nullable Object item) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_REELS);
            if (!replies(source)) return instagram;
            Object own = ownReply(instagram, item);
            if (own == null) return instagram;
            HookStatus.counted(FamilyNames.CHAT_REELS, "reply moved to a swiped reel's message");
            return own;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels reply", failure);
            return instagram;
        }
    }

    /** Whether a viewer whose ClipsViewerSource is [source] gets its reels' own messages on the reply bar. */
    private static boolean replies(@Nullable Object source) {
        return fromChat(source) && Utils.settingsReady() && !HushgramPause.isPaused() && chaining();
    }

    /**
     * Whether a chat's reels are chained, so a reel swiped to needs its own message on the reply bar:
     * the switch is on, or Instagram's own chat reels test said yes, which chains them with the switch
     * off and has the same wrong-message reply bar (#662).
     */
    static boolean chaining() {
        return instagramChains || Settings.SWIPE_CHAT_REELS.get();
    }

    /** The info of [item]'s own message in the chat in place of [instagram], or null to keep Instagram's. */
    @Nullable
    private static Object ownReply(@Nullable Object instagram, @Nullable Object item) {
        if (item == null) return null;
        return reply(mediaId(item), instagram == null ? null : messageOf(instagram));
    }

    /**
     * The reply info of a message holding the reel [media] in the viewer's chat (see
     * {@link #viewersChat(String)}), the one remembered last, or null to keep Instagram's: when the chat
     * has none, or when [instagram], the message Instagram's info quotes, is one of them already.
     */
    @Nullable
    static Object reply(@Nullable String media, @Nullable String instagram) {
        if (media == null) return null;
        synchronized (CHATS) {
            ChatReplies chat = viewersChat(instagram);
            Map<String, Object> messages = chat == null ? null : chat.reels.get(media);
            if (messages == null || messages.isEmpty()) return null;
            if (instagram != null && messages.containsKey(instagram)) return null;
            Object last = null;
            for (Object info : messages.values()) last = info;
            return last;
        }
    }

    /**
     * The chat the last tap's viewer answers from: the one holding [instagram], the message Instagram's
     * own info quotes, which then stands for the rest of that tap, or else the one that stood earlier in
     * the tap, or else the chat collected from last. Null with no chat remembered. Guarded by
     * {@link #CHATS}.
     */
    @Nullable
    private static ChatReplies viewersChat(@Nullable String instagram) {
        int tap = taps;
        if (instagram != null) {
            for (int i = CHATS.size() - 1; i >= 0; i--) {
                ChatReplies chat = CHATS.get(i);
                if (!chat.holds(instagram)) continue;
                viewerChat = chat;
                viewerTap = tap;
                return chat;
            }
        }
        ChatReplies named = viewerChat;
        if (named != null && viewerTap == tap && CHATS.contains(named)) return named;
        return CHATS.isEmpty() ? null : CHATS.get(CHATS.size() - 1);
    }

    /** Forgets every remembered reply, for tests. */
    static void forgetReplies() {
        synchronized (CHATS) {
            CHATS.clear();
            viewerChat = null;
            viewerTap = -1;
        }
    }

    /** One chat's reply infos, by the reel's media id and then the message, oldest first, with the chat's collector held weakly. */
    private static final class ChatReplies {
        final WeakReference<Object> chat;
        final Map<String, Map<String, Object>> reels = new Kept<>(REPLIES_KEPT);

        ChatReplies(Object chat) {
            this.chat = new WeakReference<>(chat);
        }

        /** Whether [message] is one of this chat's remembered messages. */
        boolean holds(String message) {
            for (Map<String, Object> messages : reels.values()) {
                if (messages.containsKey(message)) return true;
            }
            return false;
        }
    }

    /** Counts a tap on media in a chat that goes on to open, for tests and {@link #open(Object, View)}. */
    static void tapped() {
        taps++;
    }

    /** How many taps on media in a chat went on to open, so {@link ChatReelsNotice} starts over after a new one. */
    static int tapCount() {
        return taps;
    }

    /** Stub: the patch writes this, answering the message [info], a reply info, quotes. */
    @Nullable
    public static String messageOf(Object info) {
        return null;
    }

    /** Stub: the patch writes this, answering how many reels [adapter], a viewer's adapter, holds. */
    public static int count(Object adapter) {
        return 0;
    }

    /** Stub: the patch writes this, answering the reel item at [position] of [adapter], a viewer's adapter, or null. */
    @Nullable
    public static Object item(Object adapter, int position) {
        return null;
    }

    /** A map dropping its eldest entry past [limit] entries. */
    private static final class Kept<V> extends LinkedHashMap<String, V> {
        private final int limit;

        Kept(int limit) {
            this.limit = limit;
        }

        @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
            return size() > limit;
        }
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
            tapped();
            ChatReelsNotice.opened(view);
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

    /**
     * Clears a tap's open when any activity pauses. The replies stay: Instagram goes through a chat's
     * messages once as it loads, so a viewer closing and the next tap in the same chat still need them.
     */
    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityPaused(@NonNull Activity activity) {
            covered();
        }

        @Override public void onActivityResumed(@NonNull Activity activity) { }
        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
