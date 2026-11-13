/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import androidx.annotation.RequiresApi;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.ShimmerTitle;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.Setting;

/**
 * Helper for the "Lock your messages" patch.
 *
 * <p>While the switch is on, your inbox and every chat stay under a cover until the phone's own
 * lock (fingerprint, face, PIN, pattern or password) says it's you. Once it does, they stay open
 * until Instagram leaves the screen, then lock again, at once or after the time Lock again sets.
 * While they're locked, a message notification says only that a message came, and Instagram's
 * banner for a new message inside the app waits. Lock all of Instagram, the second switch, covers
 * every screen the same way instead of just the messages.
 *
 * <p>The inbox and a chat are found by the view ids Instagram gives them when it builds them, on
 * each frame of the activity in front, and the cover is drawn over just that part of the screen,
 * so the tabs and the top bar still work. Each activity's window has covers of its own, and screen
 * readers skip what's under one. Nothing here is Instagram's own state: the lock lives in this
 * class, and a restart starts it locked. While the messages are open, the recent apps picture
 * doesn't show them, and when the lock comes back, the message notifications already in the shade
 * lose their text too.
 *
 * <p>Off, the settings not read yet or anything thrown, Instagram shows everything as it always
 * does. Paused or in safe mode, a lock that's on keeps working, and covers all of Instagram, the
 * plainest cover there is, so neither can be used to get around it. A phone with no screen lock has
 * nothing to ask, so the messages stay open and a toast says why.
 *
 * <p>Lock Saved covers Saved the same way, its collections and its search too, over the whole
 * screen, since the top bar names the collection. It leaves the messages and their notifications as
 * the other two switches leave them. Every lock shares one unlock: once the phone's lock says it's
 * you, everything is open until Instagram leaves the screen.
 *
 * <p>Single chats can be locked on their own ({@link ChatLocks}), with or without the switches
 * above. A chat on the list gets the same cover over just that chat, asks the same lock, opens
 * until Instagram leaves the screen, and its message notifications lose their text the same way.
 * The banner for a new message waits while any chat on the list is locked, since it can't be told
 * which chat it shows.
 *
 * <p>A chat that opens under a cover isn't read yet, so its seen receipt waits too, whatever Read
 * messages without the seen receipt says, and the chat stays unread ({@link ThreadSeen}). The unlock
 * lets the receipt of the chat it was asked in go as it would have then (#649).
 *
 * <p>Ask for each chat makes an unlock good for one chat: another chat coming to the front locks
 * again, and so does the inbox when it shows after a chat's unlock. The same chat coming back (from
 * a photo, or the phone's own check on Android 9 and 10) stays open.
 */
public final class MessagesLock {
    /** The inbox's list of chats, and the frame around it, as Instagram names them. */
    static final String INBOX_LIST = "inbox_refreshable_thread_list_recyclerview";
    static final String INBOX_FRAME = "list_container";
    /** A chat's whole screen: its header, the messages and the composer. */
    static final String CHAT_ROOT = "thread_view_root";
    /** Every screen's content, for Lock all of Instagram. */
    static final String APP = "app";
    /** The HushGram settings' content, in their own window over an activity. */
    static final String SETTINGS = "settings";
    /** Every screen's content while Saved shows, for Lock Saved. */
    static final String SAVED = "saved";
    /**
     * Saved's pages as Instagram names them: its grid of posts and collections, a collection, the
     * posts of one kind, its search box and results, and the collection tiles 454 opens Saved on
     * (the profile's bookmark goes straight there). Any of them on screen means Saved is.
     */
    public static final String[] SAVED_PAGES = {
            "saved_view_pager", "save_collection_viewpager", "saved_scoped_posts_grid",
            "saved_search_input", "saved_search_results", "saved_collection_thumbnail",
    };
    /** Marks the cover this class puts in a window. Fast scroll in Saved shows no handle while one is up. */
    public static final String COVER_TAG = "hushgram_messages_lock";

    /** Steps a failure is reported under. */
    static final String SWITCH = "switch read";
    static final String NOTIFICATION = "notification";
    static final String BANNER = "message banner";
    static final String SCREEN = "screen check";
    static final String ASK = "phone lock";

    /** Counted outcomes a report needs. */
    static final String HIDDEN = "notification text hidden";
    static final String HELD = "banner held";
    static final String COVERED = "covered";
    static final String NO_PHONE_LOCK = "no screen lock on the phone";

    /** An ask that never answered (an activity gone mid-prompt) stops blocking a new one after this. */
    private static final long ASK_TIMEOUT_MS = 60_000;

    /**
     * Android 13 and up: how often an open inbox or chat tells its activity again to stay out of the
     * recent apps picture. Instagram turns the picture back on by itself (its Home feed does, a moment
     * after it leaves the screen), and there's no asking what it's set to.
     */
    static final long RETELL_RECENTS_MS = 1_000;

    /** Marks a notification this class wrote, so it isn't hidden twice. */
    static final String HIDDEN_EXTRA = "hushgram_lock_hidden";

    /**
     * The small icon of a copy whose message came without one: Android's own chat icon, which every
     * phone has and which draws as a status bar icon should, unlike Instagram's colored app icon.
     */
    static final int NO_ICON = android.R.drawable.stat_notify_chat;

    /** Asks the phone's lock, then runs one of the two. Tests put a fake in. */
    interface Asker {
        void ask(Activity activity, Runnable confirmed, Runnable notConfirmed);
    }

    static volatile Asker asker = MessagesLock::askConfigured;
    /** View ids by name, for tests whose app has none of Instagram's. */
    static volatile Map<String, Integer> idsForTests;

    private static volatile boolean open;
    /** For Ask for each chat, what the last unlock was for: a chat's id, {@link #INBOX_LIST}, or null while locked. */
    private static volatile String openFor;
    /**
     * The chat the ask on screen is for, and its account: the one in front as it came up, or the one
     * that opened after. Coming in from a notification asks as the screen resumes, before its chat does.
     */
    private static volatile String askedChat;
    private static volatile String askedAccount;
    /** When Instagram last left the screen while open and Lock again said to wait; 0 when it isn't away. */
    private static volatile long leftAt;
    private static volatile boolean watching;
    private static int started;
    private static long askedAt;
    /** The screen the open ask belongs to. Its dialog goes with it without a word when it's destroyed. */
    private static WeakReference<Activity> askedBy = new WeakReference<>(null);
    private static boolean askedThisTime;
    /** The settings' own cover asked since the lock came back, so a cancel isn't asked again at once. */
    private static boolean settingsAsked;
    /** Saved is under its cover on the screen in front, so the ask names it. */
    private static boolean savedCovered;
    /** What the activity in front was last told about the recent apps picture, and when. */
    private static Boolean keptOutOfRecents;
    private static long toldRecentsAt;
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    private static ViewTreeObserver.OnPreDrawListener drawListener;
    private static ViewTreeObserver.OnWindowFocusChangeListener focusListener;
    /** Each window's screens as last found in it, so one activity's frames never move another's. */
    private static final Map<View, Map<String, WeakReference<View>>> anchors = new WeakHashMap<>();
    /** Windows this class marked secure, so it only ever takes away a mark it put there. */
    private static final Map<Window, Boolean> secured = new WeakHashMap<>();

    private MessagesLock() {
    }

    /**
     * Called once Instagram's application has started, when this patch is in the build. Watches
     * every activity: the one in front is checked before each frame, and leaving Instagram locks
     * the messages again. A start begins locked, so message notifications an earlier start left in
     * the shade lose their text now.
     */
    public static void watch(Context context) {
        try {
            if (watching || !(context instanceof Application)) return;
            watching = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
        if (messagesLocked() || chatListLocked()) hideShade();
    }

    /**
     * Asked with each notification before it goes to Android, wherever Instagram posts one: its
     * notification poster, an inline reply's update and every other post in its code. While the
     * messages are locked, a message's notification comes back as a copy that says only that a
     * message came. Everything else, and everything while unlocked, goes as it is.
     */
    public static Notification notification(Notification notification) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (notification == null || !isMessage(notification) || isHidden(notification) || !lockedFor(notification)) {
                return notification;
            }
            Notification hidden = hide(Utils.getContext(), notification);
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, HIDDEN);
            return hidden;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, NOTIFICATION, t);
            return notification;
        }
    }

    /**
     * Asked first when Instagram goes to show its banner for something new while you're in the app.
     * True skips that banner while the messages are locked, since it shows the message.
     */
    public static boolean holdBanner() {
        try {
            HookStatus.invoked(FamilyNames.MESSAGES_LOCK);
            if (!messagesLocked() && !chatListLocked()) return false;
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, HELD);
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, BANNER, t);
            return false;
        }
    }

    /** True while a lock is on and the phone's lock hasn't been confirmed since Instagram came back. */
    public static boolean locked() {
        if (!switchedOn()) return false;
        expire();
        return !open;
    }

    /** {@link #locked}, for the messages: Lock Saved on its own leaves them and their notifications be. */
    static boolean messagesLocked() {
        if (!messagesOn()) return false;
        expire();
        return !open;
    }

    /**
     * The chat on screen is on the list of locked chats and the phone's lock hasn't said it's you
     * since Instagram came back. Whether the messages lock is on doesn't matter here.
     */
    static boolean chatLocked() {
        if (!ChatLocks.currentListed()) return false;
        expire();
        return !open;
    }

    /** Some chat is on the list and the phone's lock hasn't said it's you since Instagram came back. */
    public static boolean chatListLocked() {
        if (!ChatLocks.any()) return false;
        expire();
        return !open;
    }

    /**
     * Some lock a seen receipt waits for is shut: the messages, or a chat on the list. Only with this
     * patch in. Never throws.
     */
    static boolean receiptsWait() {
        try {
            return watching && (messagesLocked() || chatListLocked());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return false;
        }
    }

    /**
     * A seen receipt of [thread] on [account] waits for the lock: the messages are locked, or the
     * chat is on the list and locked. A null [account] matches any. Only with this patch in. Never
     * throws.
     */
    static boolean holdsReceipt(String account, String thread) {
        try {
            if (!watching) return false;
            if (messagesLocked()) return true;
            if (thread == null || thread.isEmpty() || !ChatLocks.listed(account, thread)) return false;
            expire();
            return !open;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return false;
        }
    }

    /**
     * Asked when a chat comes to the front, with its id. With Ask for each chat on, a chat other than
     * the one the last unlock was for locks again, so it asks before it shows.
     */
    static void chatOpened(String id) {
        if (id != null && askedAt != 0) {
            askedChat = id;
            askedAccount = ChatLocks.currentAccount();
        }
        if (open && id != null && !id.equals(openFor) && eachChat()) relock(askedAt == 0);
    }

    /** [notification] is hidden now: the messages are locked, or its chat is on the list and locked. */
    private static boolean lockedFor(Notification notification) {
        if (messagesLocked()) return true;
        if (!ChatLocks.listedIn(notification)) return false;
        expire();
        return !open;
    }

    /**
     * Runs [then] at once while nothing is locked, else after the phone's lock confirms it's you.
     * Taking a chat off the list goes through here, so it can't be used to get around the lock.
     */
    public static void confirmChatsThen(Activity activity, Runnable then) {
        if (!locked() && !chatListLocked()) {
            then.run();
            return;
        }
        ask(activity, then);
    }

    /**
     * Runs [then] at once while neither messages lock is on, else after the phone's lock confirms
     * it's you. The list of hidden chats opens through here, since it names them.
     */
    public static void confirmHiddenThen(Activity activity, Runnable then) {
        if (!messagesOn()) {
            then.run();
            return;
        }
        ask(activity, then);
    }

    /**
     * Runs [then] after the phone's lock or the PIN confirms it's you, every time and whether or not
     * a messages lock is on: the hidden chats come back into the inbox through here (#661).
     */
    static void confirmRevealThen(Activity activity, Runnable then) {
        ask(activity, then);
    }

    /**
     * Runs [then] after the HushGram PIN is entered, every time, when a PIN is what unlocks the
     * messages: a setting that weakens a lock (switching a lock off, changing the PIN or how to
     * unlock) never takes the shortcut of an open session, and never the phone's lock. With no PIN
     * in play it is {@link #confirmThen}, exactly as before.
     */
    public static void confirmPinThen(Activity activity, Runnable then) {
        if (!PinLock.protects()) {
            confirmThen(activity, then);
            return;
        }
        if (activity == null) return;
        if (locked()) settingsAsked = true;
        PinLock.ask(activity, then, () -> { });
    }

    /**
     * A lock was just turned on while nothing was locked. It waits until you leave Instagram rather
     * than covering the screen you're on, since you're the one who turned it on. A chat locked on its
     * own that hasn't been unlocked since Instagram came back keeps everything shut: the session this
     * would open is the one that chat, its removal and its notifications share.
     */
    public static void openUntilLeft() {
        if (locked() || chatListLocked()) return;
        // With Ask for each chat, the inbox stays open as well, and a chat still asks.
        if (openFor == null) openFor = INBOX_LIST;
        open = true;
        // The switch is saved once its row's listener returns. The screen under settings is checked
        // then, so an inbox behind them stays out of the recent apps picture from now on, not from
        // the next frame it happens to draw, which a leave straight from settings never gets to.
        Utils.runOnMainThread(() -> {
            Activity activity = watched.get();
            if (activity != null) check(activity);
        });
    }

    /**
     * Runs [then] at once while the messages are open or the switch is off, else after the phone's
     * lock confirms it's you. Turning the switch off goes through here, so it can't be used to get
     * around the lock.
     */
    public static void confirmThen(Activity activity, Runnable then) {
        if (!locked()) {
            then.run();
            return;
        }
        // Only the settings, and what opens them, ask through here and confirmPinThen. Answered or
        // not, this is their one ask since the lock came back: a settings file arriving while locked
        // asks before their window has the focus again, and a cancel leaves their cover with its
        // Unlock button rather than asking a second time as the focus returns.
        settingsAsked = true;
        ask(activity, then);
    }

    /** A lock is on. The switches keep their value while HushGram is paused or in safe mode. */
    private static boolean switchedOn() {
        return messagesOn() || savedOn();
    }

    /** Lock your messages or Lock all of Instagram is on, so the messages are guarded. */
    private static boolean messagesOn() {
        try {
            return Utils.settingsReady() && (Settings.LOCK_MESSAGES.get() || Settings.LOCK_APP.get());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return false;
        }
    }

    /** Ask for each chat is on, with a lock it applies to: the messages lock, or a chat on the list. */
    private static boolean eachChat() {
        try {
            return Utils.settingsReady() && Settings.LOCK_EACH_CHAT.get() && (messagesOn() || ChatLocks.any());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return false;
        }
    }

    /** Lock Saved is on. */
    private static boolean savedOn() {
        try {
            return Utils.settingsReady() && Settings.LOCK_SAVED.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return false;
        }
    }

    /**
     * Lock all of Instagram is on: every screen gets the cover, not just the messages. Paused or in
     * safe mode, a lock that's on covers every screen too, since it needs none of Instagram's view
     * ids and nothing of HushGram's but this class.
     */
    private static boolean wholeApp() {
        try {
            return Utils.settingsReady() && (Settings.LOCK_APP.get() || Setting.isPaused() && switchedOn());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return false;
        }
    }

    /** How long Instagram may be away before it locks, in milliseconds. */
    private static long lockDelay() {
        try {
            return Settings.LOCK_AGAIN.get().millis;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SWITCH, t);
            return 0;
        }
    }

    // ---------------------------------------------------------------- notifications

    /**
     * A message, by what Instagram marks it with: the message category, a conversation's messages,
     * or one of Instagram's message channels. A call is left alone, since its screen needs the
     * caller and the buttons.
     */
    static boolean isMessage(Notification notification) {
        if (Notification.CATEGORY_CALL.equals(notification.category)) return false;
        if (Notification.CATEGORY_MESSAGE.equals(notification.category)) return true;
        Bundle extras = notification.extras;
        if (extras != null && extras.containsKey(Notification.EXTRA_MESSAGES)) return true;
        String channel = notification.getChannelId();
        return channel != null && channel.startsWith("ig_direct") && !channel.contains("video_chat");
    }

    /**
     * A copy that keeps how the notification behaves (where a tap goes, its group, its channel,
     * when it came) and drops what it says: the sender, the text, the picture, the reply and other
     * buttons, and the conversation it belongs to, which Android would show with the sender's name.
     */
    static Notification hide(Context context, Notification original) {
        return hide(context, original, (original.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
    }

    /** A copy written over one already in the shade alerts only once, so it doesn't ring again. */
    private static Notification hide(Context context, Notification original, boolean alertOnce) {
        Notification.Builder builder = new Notification.Builder(context, original.getChannelId());
        // Android refuses a notification with no small icon when it's posted. One that came without
        // any still loses its text, under the phone's own chat icon, rather than going as it is.
        Icon icon = original.getSmallIcon();
        if (icon != null) builder.setSmallIcon(icon);
        else builder.setSmallIcon(NO_ICON);
        Bundle marked = new Bundle();
        marked.putBoolean(HIDDEN_EXTRA, true);
        // Its chat and account stay on the copy, so what is in the shade can still be matched to the chat.
        String chat = ChatLocks.idsOf(original);
        if (chat != null) marked.putString(ChatLocks.CHAT_EXTRA, chat);
        String account = ChatLocks.accountOf(original);
        if (account != null) marked.putString(ChatLocks.ACCOUNT_EXTRA, account);
        builder.setContentTitle(appName(context))
                .setContentText(L10n.t("New message"))
                .setContentIntent(original.contentIntent)
                .setDeleteIntent(original.deleteIntent)
                .setWhen(original.when)
                .setShowWhen(true)
                .setAutoCancel((original.flags & Notification.FLAG_AUTO_CANCEL) != 0)
                .setOnlyAlertOnce(alertOnce)
                .addExtras(marked)
                .setGroup(original.getGroup())
                .setGroupSummary((original.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
                .setSortKey(original.getSortKey())
                .setCategory(original.category)
                .setColor(original.color)
                .setNumber(original.number)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        builder.setTimeoutAfter(original.getTimeoutAfter())
                .setGroupAlertBehavior(original.getGroupAlertBehavior())
                .setBadgeIconType(original.getBadgeIconType());
        return builder.build();
    }

    private static String appName(Context context) {
        CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
        return label == null ? "Instagram" : label.toString();
    }

    /** A copy this class wrote. */
    static boolean isHidden(Notification notification) {
        Bundle extras = notification.extras;
        return extras != null && extras.getBoolean(HIDDEN_EXTRA, false);
    }

    /**
     * The lock just came back: each message notification Instagram has in the shade, posted while
     * the messages were open, is written over with a copy that says only that a message came. The
     * copy keeps its place, its group and where a tap goes, and doesn't ring again. One that can't
     * be written over (a channel deleted since) is reported and the rest still are.
     */
    static void hideShade() {
        hideShade(messagesOn());
    }

    /**
     * A chat was just put on the locked list: its notifications in the shade lose their text now,
     * not at the next lock. Other chats' stay as they are, since the messages may be open.
     */
    public static void chatListed() {
        hideShade(false);
    }

    /** [everyMessage] covers each message notification, otherwise just those of listed chats. */
    private static void hideShade(boolean everyMessage) {
        Context context;
        NotificationManager manager;
        StatusBarNotification[] active;
        try {
            context = Utils.getContext();
            if (context == null) return;
            manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            active = manager.getActiveNotifications();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, NOTIFICATION, t);
            return;
        }
        if (active == null) return;
        for (StatusBarNotification posted : active) {
            try {
                Notification shown = posted.getNotification();
                if (shown == null || !isMessage(shown) || isHidden(shown)) continue;
                // Only the notifications of listed chats go when just chats are locked, matched by
                // their mark or, for one that came before any chat was locked, Instagram's tag.
                if (!everyMessage && !ChatLocks.listedIn(shown, posted.getTag())) continue;
                manager.notify(posted.getTag(), posted.getId(), hide(context, shown, true));
                HookStatus.counted(FamilyNames.MESSAGES_LOCK, HIDDEN);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.MESSAGES_LOCK, NOTIFICATION, t);
                Logger.printException(() -> "Lock your messages: could not hide a notification in the shade", t);
            }
        }
    }

    // ---------------------------------------------------------------- the cover

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityStarted(Activity activity) {
            if (started++ <= 0) {
                // Back before Lock again's time ran out: still open, and the next leave starts over.
                expire();
                leftAt = 0;
            }
        }

        @Override public void onActivityStopped(Activity activity) {
            started--;
            // A rotation stops and starts the activity again; only leaving Instagram locks. The
            // phone's own lock screen check on Android 9 and 10 stops Instagram too, and coming
            // back from it mustn't ask again.
            if (started <= 0 && !activity.isChangingConfigurations()) left();
        }

        @Override public void onActivityResumed(Activity activity) {
            follow(activity);
        }

        @Override public void onActivityPaused(Activity activity) {
            if (watched.get() != activity) return;
            // Told once more as it leaves, since Android takes the recent apps picture now.
            if (Boolean.TRUE.equals(keptOutOfRecents)) tellRecents(activity, true);
            unfollow();
        }

        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) {
            // Android drops a destroyed screen's dialog without dismissing it, so the PIN ask never
            // hears back. Over all the same, or the cover's Unlock would wait out ASK_TIMEOUT_MS.
            // The phone's own check outlasts a turn of the screen and answers the screen that comes
            // back, so that ask isn't over and a second one shouldn't start.
            if (askedAt != 0 && askedBy.get() == activity
                    && !(activity.isChangingConfigurations() && Answer.waiting())) {
                askedAt = 0;
                askedBy = new WeakReference<>(null);
            }
        }
    }

    /** Instagram left the screen: locked now, or once it's been away as long as Lock again says. */
    static void left() {
        long delay = open ? lockDelay() : 0;
        if (delay > 0) {
            leftAt = SystemClock.elapsedRealtime();
            // Locked once the time's up even if nothing asks, so the shade loses the text then.
            Utils.runOnMainThreadDelayed(MessagesLock::expire, delay);
            return;
        }
        leftAt = 0;
        relock(askedAt == 0);
        HiddenChats.conceal();
    }

    /** Away longer than Lock again allows: locked, even before Instagram comes back. */
    private static void expire() {
        long since = leftAt;
        if (!open || since == 0 || SystemClock.elapsedRealtime() - since < lockDelay()) return;
        leftAt = 0;
        relock(askedAt == 0);
        HiddenChats.conceal();
    }

    /** The next look at the messages asks again, and what the shade shows of them goes now. */
    static void relock(boolean askAgain) {
        boolean wasOpen = open;
        open = false;
        openFor = null;
        if (askAgain) {
            askedThisTime = false;
            settingsAsked = false;
        } else {
            // An ask is up as the lock comes back (on Android 9 and 10 the phone's own check is a
            // leave). Answered or not, it's the settings' one ask too, so a cancel isn't asked again.
            settingsAsked = true;
        }
        if (wasOpen && (messagesOn() || ChatLocks.any())) hideShade();
    }

    /**
     * Checks the activity in front before each of its frames, so a cover is in place before the
     * messages draw, and again when its window gets the focus back, since the phone's lock is only
     * asked while it has it.
     */
    static void follow(Activity activity) {
        unfollow();
        try {
            watched = new WeakReference<>(activity);
            keptOutOfRecents = null;
            ViewTreeObserver.OnPreDrawListener listener = () -> {
                check(activity);
                return true;
            };
            ViewTreeObserver.OnWindowFocusChangeListener focus = hasFocus -> {
                if (hasFocus) check(activity);
            };
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            observer.addOnPreDrawListener(listener);
            drawListener = listener;
            observer.addOnWindowFocusChangeListener(focus);
            focusListener = focus;
            check(activity);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    private static void unfollow() {
        Activity activity = watched.get();
        ViewTreeObserver.OnPreDrawListener listener = drawListener;
        ViewTreeObserver.OnWindowFocusChangeListener focus = focusListener;
        drawListener = null;
        focusListener = null;
        watched = new WeakReference<>(null);
        if (activity == null || (listener == null && focus == null)) return;
        try {
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (!observer.isAlive()) return;
            if (listener != null) observer.removeOnPreDrawListener(listener);
            if (focus != null) observer.removeOnWindowFocusChangeListener(focus);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    /**
     * Puts a cover over the inbox and over a chat wherever either is on screen while the messages
     * are locked, over the whole screen while Saved shows with Lock Saved, or over every screen with
     * Lock all of Instagram, and takes them away otherwise.
     * Each window has covers of its own, so a second activity coming up never touches the covers of
     * the one under it. The first time one shows after Instagram comes back, the phone's lock is
     * asked once, as soon as Instagram's window has the focus; a cancel leaves the cover with its
     * Unlock button.
     */
    static void check(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            ViewGroup window = (ViewGroup) decor;
            if (!switchedOn() && !ChatLocks.any() && !anyCover(window)) {
                // Nothing to cover and nothing to take down. Before every frame, all that's left
                // is learning the open chat's name for Lock a chat, and that walks the whole window,
                // so it waits IDLE_LOOK_MS between looks instead of running on each draw. With no
                // chat open, or its name found, there's nothing to learn and nothing is walked.
                long now = SystemClock.elapsedRealtime();
                if (!ChatLocks.wantsName() || now - idleLookedAt < IDLE_LOOK_MS) {
                    keepOutOfRecents(activity, false);
                    if (askedAt == 0) askedThisTime = false;
                    return;
                }
                idleLookedAt = now;
            }
            String unlockedFor = openFor;
            if (open && unlockedFor != null && !INBOX_LIST.equals(unlockedFor) && ChatLocks.current() == null
                    && eachChat() && onScreen(activity, window, INBOX_LIST) && !onScreen(activity, window, CHAT_ROOT)) {
                // Ask for each chat: back in the inbox from a chat that was unlocked by itself, the
                // inbox asks too, and is covered in this frame.
                relock(askedAt == 0);
            }
            boolean lock = locked();
            boolean whole = wholeApp();
            // A chat on the list is covered by itself, whether or not the messages are locked.
            boolean chatLock = chatLocked() && !(lock && whole);
            // One cover at a time: two would each keep pulling itself in front of the other.
            boolean app = (whole || cover(window, APP) != null) && place(activity, window, APP, lock && whole);
            boolean messagesGuarded = messagesOn() && !whole;
            boolean messages = lock && messagesGuarded;
            boolean inbox = place(activity, window, INBOX_LIST, messages);
            boolean chat = place(activity, window, CHAT_ROOT, messages || chatLock, !messages);
            // Saved and the messages never show at once, so their covers never take turns in front.
            boolean saved = savedOn() && !whole && savedShowing(activity, window);
            if (saved || cover(window, SAVED) != null) place(activity, window, SAVED, lock && saved);
            savedCovered = lock && saved;
            boolean guarded = whole ? app : messagesGuarded && (inbox || chat) || saved;
            if (chat) ChatLocks.learnName(anchor(activity, window, CHAT_ROOT));
            // A chat on the list stays out of the recent apps picture while it's open, too.
            boolean listedOpen = chat && !chatLock && ChatLocks.currentListed();
            keepOutOfRecents(activity, !lock && !chatLock && (guarded || listedOpen));
            if (!(lock && guarded) && !(chatLock && chat)) {
                // Asked again the next time the messages show, unless a prompt is still up.
                if (askedAt == 0) askedThisTime = false;
                return;
            }
            // Asked only while Instagram's window has the focus, so the prompt never comes up under
            // the notification shade or over the home screen a home gesture is heading to (#153).
            // The cover is up either way, and the focus coming back checks again.
            if (!askedThisTime && decor.hasWindowFocus()) {
                askedThisTime = true;
                HookStatus.counted(FamilyNames.MESSAGES_LOCK, COVERED);
                ask(activity, null);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    /**
     * The HushGram settings in [dialog], a window of their own over [activity], asked before each of
     * its frames and when it gets the focus. Kept open across a leave, they'd still work after the
     * lock came back, since the activity's covers are in the activity's window, under them. While a
     * lock is on and locked, they get a cover of their own, the lock is asked once while their window
     * has the focus, and the cover comes off once it says it's you. A cancel leaves the cover with its
     * Unlock button. True while covered.
     */
    public static boolean guardSettings(Activity activity, Window dialog) {
        try {
            ViewGroup window = (ViewGroup) dialog.getDecorView();
            boolean lock = locked();
            if (!lock) settingsAsked = false;
            if (!lock && cover(window, SETTINGS) == null) return false;
            boolean covered = place(activity, window, SETTINGS, lock) && lock;
            if (covered && !settingsAsked && window.hasWindowFocus()) {
                settingsAsked = true;
                HookStatus.counted(FamilyNames.MESSAGES_LOCK, COVERED);
                ask(activity, null);
            }
            return covered;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
            return false;
        }
    }

    /**
     * Shows the cover for [name]'s screen over the part of [window] it takes up, or hides it. True
     * when that screen is on screen at all.
     */
    private static boolean place(Activity activity, ViewGroup window, String name, boolean lock) {
        return place(activity, window, name, lock, false);
    }

    /** [single] says the cover is over one chat that was locked on its own rather than over all the messages. */
    private static boolean place(Activity activity, ViewGroup window, String name, boolean lock, boolean single) {
        View anchor = anchor(activity, window, name);
        Cover cover = cover(window, name);
        Rect area = new Rect();
        boolean shown = anchor != null && anchor.isShown() && anchor.getGlobalVisibleRect(area) && !area.isEmpty();
        if (!shown || !lock) {
            if (cover != null) {
                cover.unhide();
                if (cover.getVisibility() != View.GONE) cover.setVisibility(View.GONE);
            }
            return shown;
        }
        boolean moved = false;
        if (cover == null) {
            cover = buildCover(activity, name);
            // LEFT, not START: the area is in screen coordinates, and in a right-to-left layout START
            // would put the cover against the right edge, where the window's next layout leaves it.
            window.addView(cover, new FrameLayout.LayoutParams(area.width(), area.height(), Gravity.TOP | Gravity.LEFT));
            moved = true;
        }
        if (cover.getVisibility() != View.VISIBLE) {
            cover.setVisibility(View.VISIBLE);
            moved = true;
        }
        cover.hide(anchor);
        cover.titleFor(single);
        // The cover stays the window's last child, so it's drawn over anything Instagram adds later.
        if (window.getChildAt(window.getChildCount() - 1) != cover) cover.bringToFront();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cover.getLayoutParams();
        if (params.width != area.width() || params.height != area.height()
                || params.leftMargin != area.left || params.topMargin != area.top) {
            params.width = area.width();
            params.height = area.height();
            params.leftMargin = area.left;
            params.topMargin = area.top;
            cover.setLayoutParams(params);
            moved = true;
        }
        if (moved || cover.getLeft() != area.left || cover.getTop() != area.top) {
            // Laid out now, so the frame about to be drawn has the cover where the messages are.
            cover.measure(View.MeasureSpec.makeMeasureSpec(area.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(area.height(), View.MeasureSpec.EXACTLY));
            cover.layout(area.left, area.top, area.right, area.bottom);
        }
        return true;
    }

    /** Whether [name]'s screen shows in [window], any part of it. */
    private static boolean onScreen(Activity activity, ViewGroup window, String name) {
        View anchor = anchor(activity, window, name);
        Rect area = new Rect();
        return anchor != null && anchor.isShown() && anchor.getGlobalVisibleRect(area) && !area.isEmpty();
    }

    /** How long a screen with nothing locked and no cover up goes between looks for an open chat. */
    static final long IDLE_LOOK_MS = 500;
    private static long idleLookedAt = Long.MIN_VALUE / 2;

    /**
     * Whether [window] has a cover that isn't taken down yet. A cover {@link #place} has set to GONE
     * already gave the screen under it back, so it leaves nothing to do, and the window can go back
     * to the idle look after a lock is turned off.
     */
    private static boolean anyCover(ViewGroup window) {
        for (int i = window.getChildCount() - 1; i >= 0; i--) {
            View child = window.getChildAt(i);
            if (child instanceof Cover && child.getVisibility() != View.GONE) return true;
        }
        return false;
    }

    /** [window]'s cover for [name]'s screen, if it has one. */
    private static Cover cover(ViewGroup window, String name) {
        for (int i = window.getChildCount() - 1; i >= 0; i--) {
            View child = window.getChildAt(i);
            if (child instanceof Cover && ((Cover) child).screen.equals(name)) return (Cover) child;
        }
        return null;
    }

    /**
     * The screen's view in [window], the inbox's frame around its list when there is one. The one
     * found is kept while it's still on screen there. Once it isn't, it's looked for again, since
     * another view with the same id (a second inbox list, say) can be the one showing.
     */
    private static View anchor(Activity activity, ViewGroup window, String name) {
        Map<String, WeakReference<View>> kept = anchors.get(window);
        WeakReference<View> reference = kept == null ? null : kept.get(name);
        View view = reference == null ? null : reference.get();
        if (view != null && view.isAttachedToWindow() && view.getRootView() == window && view.isShown()) return view;
        int id = id(activity, name);
        if (id == 0) {
            HookStatus.missingViewId(FamilyNames.MESSAGES_LOCK, name);
            return null;
        }
        view = showing(window, id);
        if (view != null && INBOX_LIST.equals(name)) view = frameAround(activity, view);
        if (kept == null) {
            kept = new HashMap<>();
            anchors.put(window, kept);
        }
        if (view == null) kept.remove(name);
        else kept.put(name, new WeakReference<>(view));
        return view;
    }

    /**
     * The view with [id] in [window] that's on screen: the first one shown with a part of it
     * visible, else the first one shown, else the first one at all, as findViewById answers.
     */
    static View showing(ViewGroup window, int id) {
        List<View> shown = new ArrayList<>();
        collectShown(window, id, shown);
        Rect area = new Rect();
        for (View view : shown) {
            if (view.getGlobalVisibleRect(area) && !area.isEmpty()) return view;
        }
        return shown.isEmpty() ? window.findViewById(id) : shown.get(0);
    }

    private static void collectShown(View view, int id, List<View> shown) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.getId() == id) shown.add(view);
        if (view instanceof Cover || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectShown(group.getChildAt(i), id, shown);
    }

    /**
     * Whether one of Saved's pages is on screen in [window]. The page found is kept while it's still
     * shown there, so a screen that stays on Saved isn't walked again on every frame.
     */
    private static boolean savedShowing(Activity activity, ViewGroup window) {
        Map<String, WeakReference<View>> kept = anchors.get(window);
        WeakReference<View> reference = kept == null ? null : kept.get(SAVED_PAGE);
        View view = reference == null ? null : reference.get();
        Rect area = new Rect();
        if (view != null && view.isAttachedToWindow() && view.getRootView() == window && view.isShown()
                && view.getGlobalVisibleRect(area) && !area.isEmpty()) return true;
        int[] ids = new int[SAVED_PAGES.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = id(activity, SAVED_PAGES[i]);
            if (ids[i] == 0) HookStatus.missingViewId(FamilyNames.MESSAGES_LOCK, SAVED_PAGES[i]);
        }
        view = firstShown(window, ids, area);
        if (kept == null) {
            kept = new HashMap<>();
            anchors.put(window, kept);
        }
        if (view == null) kept.remove(SAVED_PAGE);
        else kept.put(SAVED_PAGE, new WeakReference<>(view));
        return view != null;
    }

    /** Where the Saved page found last is kept among a window's screens. */
    private static final String SAVED_PAGE = "saved page";

    /** The first view under [view] with one of [ids] that's shown with a part of it on screen. */
    private static View firstShown(View view, int[] ids, Rect area) {
        if (view.getVisibility() != View.VISIBLE) return null;
        int id = view.getId();
        if (id != View.NO_ID) {
            for (int wanted : ids) {
                if (wanted == id) {
                    if (view.isShown() && view.getGlobalVisibleRect(area) && !area.isEmpty()) return view;
                    break;
                }
            }
        }
        if (view instanceof Cover || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = firstShown(group.getChildAt(i), ids, area);
            if (found != null) return found;
        }
        return null;
    }

    /** The inbox's frame when it holds the list, so its edges are covered too; the list otherwise. */
    private static View frameAround(Activity activity, View list) {
        int frame = id(activity, INBOX_FRAME);
        if (frame == 0) return list;
        for (ViewParent parent = list.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (((View) parent).getId() == frame) return (View) parent;
        }
        return list;
    }

    /** Resource ids by name, misses included: they don't change while the process runs, and this is asked before every frame. */
    private static final Map<String, Integer> IDS = new java.util.concurrent.ConcurrentHashMap<>();

    static int id(Context context, String name) {
        if (APP.equals(name) || SETTINGS.equals(name) || SAVED.equals(name)) return android.R.id.content;
        Map<String, Integer> forTests = idsForTests;
        if (forTests != null) {
            Integer id = forTests.get(name);
            return id == null ? 0 : id;
        }
        Integer known = IDS.get(name);
        if (known == null) {
            known = context.getResources().getIdentifier(name, "id", Utils.resourcePackage(context));
            IDS.put(name, known);
        }
        return known;
    }

    /**
     * A cover over one screen of one window. While it's up, screen readers skip the screen under it,
     * so TalkBack can't read or open a chat through it; once it's down they read it as before.
     */
    static final class Cover extends FrameLayout {
        /** The screen it covers: [INBOX_LIST], [CHAT_ROOT], [APP], [SAVED] or [SETTINGS]. */
        final String screen;
        private WeakReference<View> hidden = new WeakReference<>(null);
        private int importance;
        private TextView title;
        private boolean single;

        Cover(Context context, String screen) {
            super(context);
            this.screen = screen;
        }

        /** The cover over a chat says so when that chat is locked on its own. */
        void titleFor(boolean single) {
            if (title == null || this.single == single || APP.equals(screen) || SETTINGS.equals(screen)
                    || SAVED.equals(screen)) return;
            this.single = single;
            title.setText(single ? L10n.t("This chat is locked") : L10n.t("Your messages are locked"));
        }

        /** Screen readers skip [anchor] and everything in it. */
        void hide(View anchor) {
            if (hidden.get() != anchor) {
                unhide();
                importance = anchor.getImportantForAccessibility();
                hidden = new WeakReference<>(anchor);
            }
            if (anchor.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                anchor.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
        }

        /** The screen under it is read as it was before. */
        void unhide() {
            View anchor = hidden.get();
            hidden = new WeakReference<>(null);
            if (anchor != null && anchor.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                anchor.setImportantForAccessibility(importance);
            }
        }
    }

    private static Cover buildCover(Activity activity, String screen) {
        boolean wholeApp = APP.equals(screen);
        ScreenColors colors = ScreenColors.DEFAULT;
        Cover cover = new Cover(activity, screen);
        cover.setTag(COVER_TAG);
        cover.setBackgroundColor(colors.background);
        // Takes every touch, so nothing under it can be scrolled or opened.
        cover.setClickable(true);
        cover.setFocusable(true);

        // The column spans the cover. A column that wraps its content shrinks rows that match it to
        // the icon's width, which cut the title to its first word and the Unlock link to nothing.
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(activity, 32), 0, dp(activity, 32), 0);
        cover.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(android.R.drawable.ic_lock_lock);
        icon.setColorFilter(colors.heading);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = dp(activity, 72);
        column.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView title = new TextView(activity);
        title.setText(SETTINGS.equals(screen) ? L10n.t("HushGram settings are locked")
                : wholeApp ? L10n.t("Instagram is locked")
                : SAVED.equals(screen) ? L10n.t("Saved is locked") : L10n.t("Your messages are locked"));
        ShimmerTitle.wear(title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(activity, 24), 0, dp(activity, 32));
        column.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        cover.title = title;

        TextView unlock = new TextView(activity);
        unlock.setText(L10n.t("Unlock"));
        ScreenColors.styleAction(unlock, true);
        // A finger's worth of height, and a button to a screen reader and a keyboard.
        unlock.setMinHeight(dp(activity, 48));
        unlock.setFocusable(true);
        unlock.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        unlock.setOnClickListener(v -> ask(activity, null));
        column.addView(unlock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return cover;
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Keeps an open inbox or chat out of the recent apps picture. Android 13 and up have a switch for
     * just that, told again every so often, since Instagram turns it back on by itself. Older Android
     * has only the secure flag, which also keeps screenshots out: it goes on the window while the
     * open messages are on screen, and comes off only if this class put it there.
     */
    private static void keepOutOfRecents(Activity activity, boolean keepOut) {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean due = keepOut && SystemClock.elapsedRealtime() - toldRecentsAt >= RETELL_RECENTS_MS;
            if (Boolean.valueOf(keepOut).equals(keptOutOfRecents) && !due) return;
            tellRecents(activity, keepOut);
            return;
        }
        keptOutOfRecents = keepOut;
        Window window = activity.getWindow();
        boolean secure = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        if (keepOut) {
            if (!secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                secured.put(window, Boolean.TRUE);
            }
        } else if (secured.remove(window) != null && secure) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** Android 13 and up: whether [activity] is left out of the recent apps picture. */
    private static void tellRecents(Activity activity, boolean keepOut) {
        if (Build.VERSION.SDK_INT < 33) return;
        keptOutOfRecents = keepOut;
        toldRecentsAt = SystemClock.elapsedRealtime();
        activity.setRecentsScreenshotEnabled(!keepOut);
    }

    // ---------------------------------------------------------------- asking the phone's lock

    private static void ask(Activity activity, Runnable then) {
        long now = SystemClock.elapsedRealtime();
        if (askedAt != 0 && now - askedAt < ASK_TIMEOUT_MS) return;
        askedAt = now;
        askedBy = new WeakReference<>(activity);
        // The chat on screen as the ask comes up, or the one opening under it. The phone's own check
        // takes the front, and the chat is no longer current by the time it answers.
        askedChat = ChatLocks.current();
        askedAccount = ChatLocks.currentAccount();
        try {
            asker.ask(activity, () -> {
                String chat = ChatLocks.current() != null ? ChatLocks.current() : askedChat;
                String account = ChatLocks.current() != null ? ChatLocks.currentAccount() : askedAccount;
                askedAt = 0;
                open = true;
                openFor = chat != null ? chat : INBOX_LIST;
                ThreadSeen.unlocked(account, chat);
                // What follows (a settings change, a new PIN) belongs to the screen that asked. Turned
                // while the phone's check was up, that screen is gone and its dialog can't show.
                if (then != null && !activity.isDestroyed()) then.run();
            }, () -> askedAt = 0);
        } catch (Throwable t) {
            askedAt = 0;
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, ASK, t);
        }
    }

    /**
     * The one place every lock path asks through: the HushGram PIN while Unlock with says PIN and a
     * PIN is set, else the phone's lock, exactly as before there was a choice.
     */
    private static void askConfigured(Activity activity, Runnable confirmed, Runnable notConfirmed) {
        if (PinLock.active()) {
            PinLock.ask(activity, confirmed, notConfirmed);
            return;
        }
        askPhone(activity, confirmed, notConfirmed);
    }

    static Asker configuredAskerForTests() {
        return MessagesLock::askConfigured;
    }

    /**
     * Android 11 and up ask in Instagram's own window, with the fingerprint or face and the PIN,
     * pattern or password as the fallback. Android 9 and 10 open the phone's own lock screen check
     * and read its answer through a small headless fragment: Android 10's prompt in the app's window
     * can't be trusted to offer the PIN on a phone with no fingerprint or face, and Android 9's
     * can't at all. An ask that fails for any reason but your cancel goes to the phone's own check,
     * so there's always a way in. Unlock with's Fingerprint or face only leaves the PIN, pattern and
     * password out of the prompt while the phone has a fingerprint or face to ask.
     */
    private static void askPhone(Activity activity, Runnable confirmed, Runnable notConfirmed) {
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, NO_PHONE_LOCK);
            Utils.showToastLong(wholeApp()
                    ? L10n.t("Set a phone screen lock so HushGram can lock Instagram.")
                    : askingForSaved() ? L10n.t("Set a phone screen lock so HushGram can lock Saved.")
                    : L10n.t("Set a phone screen lock so HushGram can lock messages."));
            confirmed.run();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                prompt(activity, keyguard, confirmed, notConfirmed);
                return;
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.MESSAGES_LOCK, ASK, t);
            }
        }
        phoneCheck(activity, keyguard, confirmed, notConfirmed);
    }

    /** The phone's own lock screen check, whose answer comes back through [Answer]. */
    private static void phoneCheck(Activity activity, KeyguardManager keyguard, Runnable confirmed, Runnable notConfirmed) {
        Intent intent = keyguard.createConfirmDeviceCredentialIntent(askTitle(), null);
        if (intent == null) {
            // Android has no check to show, which it says only for a phone with no screen lock.
            confirmed.run();
            return;
        }
        Answer.start(activity, intent, confirmed, notConfirmed);
    }

    @RequiresApi(30)
    private static void prompt(Activity activity, KeyguardManager keyguard, Runnable confirmed, Runnable notConfirmed) {
        boolean biometricsOnly = biometricsOnly(activity);
        promptFor(activity, biometricsOnly, notConfirmed).authenticate(new CancellationSignal(), activity.getMainExecutor(),
                answer(activity, biometricsOnly, MessagesLock::inFront, confirmed, notConfirmed,
                        () -> phoneCheck(activity, keyguard, confirmed, notConfirmed)));
    }

    /** One ask's prompt: the phone's lock in it, or with [biometricsOnly] a Cancel button in its place. */
    @RequiresApi(30)
    static BiometricPrompt promptFor(Activity activity, boolean biometricsOnly, Runnable notConfirmed) {
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                .setTitle(askTitle())
                .setAllowedAuthenticators(authenticators(biometricsOnly));
        // A prompt without the phone's lock must have a button to say no with.
        if (biometricsOnly) builder.setNegativeButton(L10n.t("Cancel"), activity.getMainExecutor(), (dialog, which) -> notConfirmed.run());
        return builder.build();
    }

    /**
     * What the prompt's answer does: a match unlocks, and an error goes on to [phoneCheck] only when
     * [phoneCheckAfter] says so with Instagram still [inFront]. When Fingerprint or face only waits
     * out a short lockout or a timeout, the system's own words say why the messages stay covered.
     */
    @RequiresApi(30)
    static BiometricPrompt.AuthenticationCallback answer(Activity activity, boolean biometricsOnly, BooleanSupplier inFront,
            Runnable confirmed, Runnable notConfirmed, Runnable phoneCheck) {
        return new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                confirmed.run();
                refresh(activity);
            }

            @Override
            public void onAuthenticationError(int code, CharSequence message) {
                boolean front = inFront.getAsBoolean() && !activity.isFinishing();
                if (!phoneCheckAfter(code, biometricsOnly, front)) {
                    if (front && biometricsOnly && !cancelledByYou(code) && message != null && message.length() > 0) {
                        Utils.showToastLong(message.toString());
                    }
                    notConfirmed.run();
                    return;
                }
                try {
                    phoneCheck.run();
                } catch (Throwable t) {
                    HookStatus.threw(FamilyNames.MESSAGES_LOCK, ASK, t);
                    notConfirmed.run();
                }
            }
        };
    }

    /** Whether an Instagram activity is on the screen, so a phone check would open over Instagram. */
    static boolean inFront() {
        return started > 0;
    }

    /**
     * The framework's BIOMETRIC_ERROR_NEGATIVE_BUTTON, which it keeps hidden. It calls the Cancel
     * button's listener instead, but some phones report the tap as this error as well.
     */
    static final int NEGATIVE_BUTTON = 13;

    /**
     * A prompt that ended because you closed it, as opposed to one that couldn't ask (no usable
     * sensor, a lockout, a vendor error), which goes on to the phone's own check.
     */
    static boolean cancelledByYou(int code) {
        return code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                || code == NEGATIVE_BUTTON;
    }

    /**
     * Whether a prompt that ended with [code] goes on to the phone's own check. Not after your
     * cancel, and not once Instagram has left the screen: the check would open over whatever is
     * there, and the lock asks again when Instagram comes back (audit A3). With Fingerprint or face
     * only, a short lockout or a timeout waits for the next try instead, since the phone's check
     * would let the PIN in after all. Anything that means the fingerprint or face can't be used
     * (none left, a permanent lockout, no sensor) still goes on, so you're never shut out.
     */
    static boolean phoneCheckAfter(int code, boolean biometricsOnly, boolean inFront) {
        if (cancelledByYou(code) || !inFront) return false;
        return !biometricsOnly || (code != BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT
                && code != BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT);
    }

    /** What the prompt takes: the fingerprint or face, and the phone's lock unless [biometricsOnly]. */
    static int authenticators(boolean biometricsOnly) {
        int biometrics = BiometricManager.Authenticators.BIOMETRIC_WEAK;
        return biometricsOnly ? biometrics : biometrics | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    }

    /**
     * Whether the next ask leaves the phone's lock out: Unlock with says Fingerprint or face only
     * and the phone has one to ask. Removing them all turns the phone's lock back on by itself.
     */
    static boolean biometricsOnly(Context context) {
        return Utils.settingsReady() && Settings.UNLOCK_WITH.get() == UnlockWith.BIOMETRICS && biometricsReady(context);
    }

    /**
     * Whether the phone can ask for a fingerprint or face, the same ones the lock's prompt takes. A
     * lockout counts: Android 16 phones with Identity Check report one in place of SUCCESS (both a
     * short and a permanent one, as the hidden BiometricManager.BIOMETRIC_ERROR_LOCKOUT, which is
     * BiometricPrompt's 7), and the PIN must not take over during a short one. The prompt itself
     * tells the two apart, and a permanent one still goes on to the phone's check.
     */
    public static boolean biometricsReady(Context context) {
        int state = biometricsState(context);
        return state == BiometricManager.BIOMETRIC_SUCCESS || state == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT;
    }

    /** BiometricManager's answer for the fingerprint or face the prompt takes; no hardware before Android 11 or on a failure. */
    public static int biometricsState(Context context) {
        if (Build.VERSION.SDK_INT < 30) return BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE;
        try {
            BiometricManager biometrics = context.getSystemService(BiometricManager.class);
            return biometrics == null ? BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
                    : biometrics.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        } catch (Throwable t) {
            Logger.printException(() -> "Lock your messages: could not ask whether a fingerprint or face is set up", t);
            return BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE;
        }
    }

    static String askTitle() {
        if (wholeApp()) return L10n.t("Unlock Instagram");
        if (askingForSaved()) return L10n.t("Unlock Saved");
        return messagesOn() ? L10n.t("Unlock your messages") : L10n.t("Unlock this chat");
    }

    /** Saved on screen, or asked from the settings with Lock Saved the only switch on. */
    private static boolean askingForSaved() {
        return savedCovered || !messagesOn() && savedOn() && !ChatLocks.currentListed();
    }

    /** Draws the activity again, so the covers come off at once. */
    private static void refresh(Activity activity) {
        try {
            activity.getWindow().getDecorView().invalidate();
        } catch (Throwable t) {
            Logger.printException(() -> "Lock your messages: could not redraw", t);
        }
    }

    /**
     * The answer from the phone's own lock screen check, on Android 9 and 10 and wherever the prompt
     * couldn't ask. A platform fragment gets the result of the activity it starts in any activity,
     * Instagram's included.
     */
    public static final class Answer extends Fragment {
        static final String TAG = "hushgram_messages_lock";
        static final int REQUEST = 0x4847;
        /** Saved with the fragment: the phone's check was started and is still open. */
        private static final String ASKED = "asked";
        private static Runnable pendingConfirmed;
        private static Runnable pendingNotConfirmed;
        private Intent intent;

        static void start(Activity activity, Intent intent, Runnable confirmed, Runnable notConfirmed) {
            FragmentManager manager = activity.getFragmentManager();
            Fragment old = manager.findFragmentByTag(TAG);
            if (old != null) manager.beginTransaction().remove(old).commitNowAllowingStateLoss();
            Answer answer = new Answer();
            answer.intent = intent;
            pendingConfirmed = confirmed;
            pendingNotConfirmed = notConfirmed;
            manager.beginTransaction().add(answer, TAG).commitNowAllowingStateLoss();
        }

        /** Whether the phone's check was started from here and hasn't answered yet. */
        static boolean waiting() {
            return pendingConfirmed != null || pendingNotConfirmed != null;
        }

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            // Brought back with its activity (a rotation or a theme change while the phone's check
            // was up): that check is still open and answers this fragment, so wait for it.
            if (state != null && state.getBoolean(ASKED)) return;
            if (intent != null) startActivityForResult(intent, REQUEST);
            else finish(false);
        }

        @Override
        public void onSaveInstanceState(Bundle state) {
            super.onSaveInstanceState(state);
            // A fragment that saves nothing comes back with no state at all, as if it were new.
            state.putBoolean(ASKED, true);
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            if (request == REQUEST) finish(result == Activity.RESULT_OK);
        }

        private void finish(boolean ok) {
            Runnable run = ok ? pendingConfirmed : pendingNotConfirmed;
            pendingConfirmed = null;
            pendingNotConfirmed = null;
            try {
                getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            } catch (Throwable t) {
                Logger.printException(() -> "Lock your messages: could not remove the answer", t);
            }
            if (run != null) run.run();
        }
    }

    static Asker realAskerForTests() {
        return MessagesLock::askPhone;
    }

    /** Opened, as a confirmed ask would leave it. */
    static void openForTests() {
        open = true;
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        open = false;
        openFor = null;
        askedChat = null;
        askedAccount = null;
        leftAt = 0;
        watching = false;
        started = 0;
        askedAt = 0;
        askedBy = new WeakReference<>(null);
        askedThisTime = false;
        settingsAsked = false;
        savedCovered = false;
        keptOutOfRecents = null;
        toldRecentsAt = 0;
        unfollow();
        anchors.clear();
        secured.clear();
        asker = MessagesLock::askConfigured;
        PinLock.resetForTests();
        idsForTests = null;
        idleLookedAt = Long.MIN_VALUE / 2;
        ChatLocks.resetForTests();
    }
}
