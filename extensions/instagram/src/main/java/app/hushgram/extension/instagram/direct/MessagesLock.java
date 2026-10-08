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
import android.graphics.Color;
import android.graphics.Rect;
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

import androidx.annotation.RequiresApi;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
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
 */
public final class MessagesLock {
    /** The inbox's list of chats, and the frame around it, as Instagram names them. */
    static final String INBOX_LIST = "inbox_refreshable_thread_list_recyclerview";
    static final String INBOX_FRAME = "list_container";
    /** A chat's whole screen: its header, the messages and the composer. */
    static final String CHAT_ROOT = "thread_view_root";
    /** Every screen's content, for Lock all of Instagram. */
    static final String APP = "app";
    /** Marks the cover this class puts in a window. */
    static final String COVER_TAG = "hushgram_messages_lock";

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

    /** Asks the phone's lock, then runs one of the two. Tests put a fake in. */
    interface Asker {
        void ask(Activity activity, Runnable confirmed, Runnable notConfirmed);
    }

    static volatile Asker asker = MessagesLock::askPhone;
    /** View ids by name, for tests whose app has none of Instagram's. */
    static volatile Map<String, Integer> idsForTests;

    private static volatile boolean open;
    /** When Instagram last left the screen while open and Lock again said to wait; 0 when it isn't away. */
    private static volatile long leftAt;
    private static volatile boolean watching;
    private static int started;
    private static long askedAt;
    private static boolean askedThisTime;
    /** What the activity in front was last told about the recent apps picture, and when. */
    private static Boolean keptOutOfRecents;
    private static long toldRecentsAt;
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    private static ViewTreeObserver.OnPreDrawListener drawListener;
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
        if (locked()) hideShade();
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
            if (notification == null || !locked() || !isMessage(notification) || isHidden(notification)) return notification;
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
            if (!locked()) return false;
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

    /**
     * A lock was just turned on while nothing was locked. It waits until you leave Instagram rather
     * than covering the screen you're on, since you're the one who turned it on.
     */
    public static void openUntilLeft() {
        if (!locked()) open = true;
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
        ask(activity, then);
    }

    /** A lock is on. The switches keep their value while HushGram is paused or in safe mode. */
    private static boolean switchedOn() {
        try {
            return Utils.settingsReady() && (Settings.LOCK_MESSAGES.get() || Settings.LOCK_APP.get());
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
        // Android refuses a notification with no small icon when it's posted, so a copy of one
        // that has none fails here, before anything is posted.
        if (original.getSmallIcon() == null) throw new IllegalArgumentException("the notification has no small icon");
        Notification.Builder builder = new Notification.Builder(context, original.getChannelId());
        Bundle marked = new Bundle();
        marked.putBoolean(HIDDEN_EXTRA, true);
        builder.setSmallIcon(original.getSmallIcon())
                .setContentTitle(appName(context))
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
     * be written over (no small icon, a channel deleted since) is reported and the rest still are.
     */
    static void hideShade() {
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
        @Override public void onActivityDestroyed(Activity activity) { }
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
    }

    /** Away longer than Lock again allows: locked, even before Instagram comes back. */
    private static void expire() {
        long since = leftAt;
        if (!open || since == 0 || SystemClock.elapsedRealtime() - since < lockDelay()) return;
        leftAt = 0;
        relock(askedAt == 0);
    }

    /** The next look at the messages asks again, and what the shade shows of them goes now. */
    static void relock(boolean askAgain) {
        boolean wasOpen = open;
        open = false;
        if (askAgain) askedThisTime = false;
        if (wasOpen && switchedOn()) hideShade();
    }

    /** Checks the activity in front before each of its frames, so a cover is in place before the messages draw. */
    static void follow(Activity activity) {
        unfollow();
        try {
            watched = new WeakReference<>(activity);
            keptOutOfRecents = null;
            ViewTreeObserver.OnPreDrawListener listener = () -> {
                check(activity);
                return true;
            };
            activity.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(listener);
            drawListener = listener;
            check(activity);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    private static void unfollow() {
        Activity activity = watched.get();
        ViewTreeObserver.OnPreDrawListener listener = drawListener;
        drawListener = null;
        watched = new WeakReference<>(null);
        if (activity == null || listener == null) return;
        try {
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    /**
     * Puts a cover over the inbox and over a chat wherever either is on screen while the messages
     * are locked, or over the whole screen with Lock all of Instagram, and takes them away otherwise.
     * Each window has covers of its own, so a second activity coming up never touches the covers of
     * the one under it. The first time one shows after Instagram comes back, the phone's lock is
     * asked once; a cancel leaves the cover with its Unlock button.
     */
    static void check(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            ViewGroup window = (ViewGroup) decor;
            boolean lock = locked();
            boolean whole = wholeApp();
            // One cover at a time: two would each keep pulling itself in front of the other.
            boolean app = (whole || cover(window, APP) != null) && place(activity, window, APP, lock && whole);
            boolean inbox = place(activity, window, INBOX_LIST, lock && !whole);
            boolean chat = place(activity, window, CHAT_ROOT, lock && !whole);
            boolean guarded = whole ? app : inbox || chat;
            keepOutOfRecents(activity, !lock && switchedOn() && guarded);
            if (!lock || !guarded) {
                // Asked again the next time the messages show, unless a prompt is still up.
                if (askedAt == 0) askedThisTime = false;
                return;
            }
            if (!askedThisTime) {
                askedThisTime = true;
                HookStatus.counted(FamilyNames.MESSAGES_LOCK, COVERED);
                ask(activity, null);
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, SCREEN, t);
        }
    }

    /**
     * Shows the cover for [name]'s screen over the part of [window] it takes up, or hides it. True
     * when that screen is on screen at all.
     */
    private static boolean place(Activity activity, ViewGroup window, String name, boolean lock) {
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
            window.addView(cover, new FrameLayout.LayoutParams(area.width(), area.height(), Gravity.TOP | Gravity.START));
            moved = true;
        }
        if (cover.getVisibility() != View.VISIBLE) {
            cover.setVisibility(View.VISIBLE);
            moved = true;
        }
        cover.hide(anchor);
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

    /** The inbox's frame when it holds the list, so its edges are covered too; the list otherwise. */
    private static View frameAround(Activity activity, View list) {
        int frame = id(activity, INBOX_FRAME);
        if (frame == 0) return list;
        for (ViewParent parent = list.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (((View) parent).getId() == frame) return (View) parent;
        }
        return list;
    }

    static int id(Context context, String name) {
        if (APP.equals(name)) return android.R.id.content;
        Map<String, Integer> forTests = idsForTests;
        if (forTests != null) {
            Integer id = forTests.get(name);
            return id == null ? 0 : id;
        }
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }

    /**
     * A cover over one screen of one window. While it's up, screen readers skip the screen under it,
     * so TalkBack can't read or open a chat through it; once it's down they read it as before.
     */
    static final class Cover extends FrameLayout {
        /** The screen it covers: [INBOX_LIST], [CHAT_ROOT] or [APP]. */
        final String screen;
        private WeakReference<View> hidden = new WeakReference<>(null);
        private int importance;

        Cover(Context context, String screen) {
            super(context);
            this.screen = screen;
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
        boolean dark = Utils.isDarkModeEnabled();
        int text = dark ? Color.WHITE : Color.BLACK;
        Cover cover = new Cover(activity, screen);
        cover.setTag(COVER_TAG);
        cover.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
        // Takes every touch, so nothing under it can be scrolled or opened.
        cover.setClickable(true);
        cover.setFocusable(true);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        cover.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(android.R.drawable.ic_lock_lock);
        icon.setColorFilter(text);
        int size = dp(activity, 48);
        column.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView title = new TextView(activity);
        title.setText(wholeApp ? L10n.t("Instagram is locked") : L10n.t("Your messages are locked"));
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
        column.addView(title);

        TextView unlock = new TextView(activity);
        unlock.setText(L10n.t("Unlock"));
        unlock.setTextColor(Color.rgb(0, 149, 246));
        unlock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        unlock.setGravity(Gravity.CENTER);
        unlock.setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 12));
        unlock.setOnClickListener(v -> ask(activity, null));
        column.addView(unlock);
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
        try {
            asker.ask(activity, () -> {
                askedAt = 0;
                open = true;
                if (then != null) then.run();
            }, () -> askedAt = 0);
        } catch (Throwable t) {
            askedAt = 0;
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, ASK, t);
        }
    }

    /**
     * Android 11 and up ask in Instagram's own window, with the fingerprint or face and the PIN,
     * pattern or password as the fallback. Android 9 and 10 open the phone's own lock screen check
     * and read its answer through a small headless fragment: Android 10's prompt in the app's window
     * can't be trusted to offer the PIN on a phone with no fingerprint or face, and Android 9's
     * can't at all. An ask that fails for any reason but your cancel goes to the phone's own check,
     * so there's always a way in.
     */
    private static void askPhone(Activity activity, Runnable confirmed, Runnable notConfirmed) {
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            HookStatus.counted(FamilyNames.MESSAGES_LOCK, NO_PHONE_LOCK);
            Utils.showToastLong(wholeApp()
                    ? L10n.t("Set a screen lock on your phone so HushGram can lock Instagram.")
                    : L10n.t("Set a screen lock on your phone so HushGram can lock your messages."));
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
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                .setTitle(askTitle())
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        builder.build().authenticate(new CancellationSignal(), activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        confirmed.run();
                        refresh(activity);
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence message) {
                        if (cancelledByYou(code) || activity.isFinishing()) {
                            notConfirmed.run();
                            return;
                        }
                        try {
                            phoneCheck(activity, keyguard, confirmed, notConfirmed);
                        } catch (Throwable t) {
                            HookStatus.threw(FamilyNames.MESSAGES_LOCK, ASK, t);
                            notConfirmed.run();
                        }
                    }
                });
    }

    /**
     * A prompt that ended because you closed it, as opposed to one that couldn't ask (no usable
     * sensor, a lockout, a vendor error), which goes on to the phone's own check.
     */
    static boolean cancelledByYou(int code) {
        // A prompt that takes the phone's own lock has no button to say no with, only a cancel.
        return code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED;
    }

    private static String askTitle() {
        return wholeApp() ? L10n.t("Unlock Instagram") : L10n.t("Unlock your messages");
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
        private static final String TAG = "hushgram_messages_lock";
        private static final int REQUEST = 0x4847;
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

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            if (intent != null) startActivityForResult(intent, REQUEST);
            else finish(false);
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

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        open = false;
        leftAt = 0;
        watching = false;
        started = 0;
        askedAt = 0;
        askedThisTime = false;
        keptOutOfRecents = null;
        toldRecentsAt = 0;
        unfollow();
        anchors.clear();
        secured.clear();
        asker = MessagesLock::askPhone;
        idsForTests = null;
    }
}
