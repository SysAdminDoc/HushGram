/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.profile.FollowerHistory.Change;
import app.hushgram.extension.instagram.profile.FollowerHistory.Event;
import app.hushgram.extension.instagram.profile.FollowerHistory.Kind;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.FollowersScreen;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Check now, and what a look at your whole Followers list does once it's taken.
 *
 * <p>Check now goes the way you would: the Profile tab, the followers count on your own profile,
 * then down your Followers list until Instagram has no more of it to load, where Recent unfollowers
 * takes it ({@link Unfollowers#seen}). It swipes up once every half second or so, as a thumb
 * flicking through the list would ({@link #swipeUp}), so Instagram is asked for nothing scrolling by
 * hand wouldn't ask. Leaving the list, a list that stops loading or four minutes going by ends it.
 *
 * <p>A list taken goes to the follower history ({@link FollowerHistory}). When Check now asked for
 * it, the history opens on what it found. A list you scrolled to the end yourself instead posts a
 * notification when someone's gone, with {@link Settings#UNFOLLOW_NOTIFICATIONS} on.
 */
public final class FollowersCheck {
    static final String STEP = "followers check";
    static final String NOTIFY = "unfollow notification";

    /** The views it finds by name: the Profile tab, your followers count and a follow list row's username. */
    static final String PROFILE_TAB = "profile_tab";
    static final String FOLLOWERS_BUTTON = "profile_header_followers_stacked_familiar";
    static final String ROW_USERNAME = "follow_list_username";

    static final String CHANNEL = "hushgram_unfollowers";
    static final int NOTIFICATION_ID = 0x48474601;

    private static final long STEP_MS = 550;
    private static final long FIRST_LOOK_MS = 1500;
    private static final long TIMEOUT_MS = 4 * 60_000L;
    /** Steps the list may sit still while Instagram loads its next page, about twenty seconds. */
    private static final int MAX_STILL = 36;
    /** How far up from a row the scrolling list is looked for. */
    private static final int MAX_DEPTH = 12;

    /** Where Check now is: going to your profile, opening the list, scrolling it, or saving what it took. */
    enum Stage { IDLE, PROFILE, FOLLOWERS, SCROLLING, SAVING }

    private static volatile Stage stage = Stage.IDLE;
    private static volatile long startedAt;
    /** When Check now handed its list over, so a list kept much later isn't taken as its answer. */
    private static volatile long handedAt;
    /** How long after the hand-over the list's keeping still answers Check now: the settle and a slow write. */
    private static final long ANSWER_MS = Unfollowers.SETTLE_MS + 15_000;
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static int still;
    private static boolean stepQueued;

    private FollowersCheck() {
    }

    /** Whether Check now is under way and hasn't handed a whole list over yet. */
    public static boolean running() {
        return stage != Stage.IDLE && stage != Stage.SAVING;
    }

    /** Called as a whole list is handed over to be kept, so the rebuilds after it aren't taken again. */
    static void handedOver() {
        if (stage == Stage.IDLE) return;
        // Each newer whole list while it settles counts from its own hand-over.
        stage = Stage.SAVING;
        handedAt = SystemClock.elapsedRealtime();
    }

    /**
     * Starts Check now over [activity], Instagram's screen under the history. Answers whether it
     * started: it needs Recent unfollowers on and the Profile tab or your profile on that screen.
     */
    public static boolean start(Activity activity) {
        try {
            if (activity == null || !Unfollowers.switchedOn()) return false;
            View root = activity.getWindow().getDecorView();
            View tab = find(root, PROFILE_TAB);
            View followers = find(root, FOLLOWERS_BUTTON);
            if (tab == null && followers == null) {
                Logger.printInfo(() -> "Check now: no Profile tab on " + activity.getClass().getSimpleName());
                return false;
            }
            host = new WeakReference<>(activity);
            startedAt = SystemClock.elapsedRealtime();
            stage = Stage.PROFILE;
            still = 0;
            Logger.printInfo(() -> "Check now: started");
            if (tab != null) tab.performClick();
            // Already on your profile, the header may not bind again for the hook to find it.
            Utils.runOnMainThreadDelayed(FollowersCheck::lookForCount, FIRST_LOOK_MS);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, STEP, failure);
            stage = Stage.IDLE;
            return false;
        }
    }

    /** Called from your own profile's header as Instagram binds it, with a view inside it. */
    static void ownProfile(View inside) {
        if (stage != Stage.PROFILE) return;
        View count = up(inside, FOLLOWERS_BUTTON);
        if (count != null) openFollowers(count);
    }

    private static void lookForCount() {
        if (stage != Stage.PROFILE) return;
        Activity activity = host.get();
        View count = activity == null ? null : find(activity.getWindow().getDecorView(), FOLLOWERS_BUTTON);
        if (count != null) {
            openFollowers(count);
        } else if (!timedOut()) {
            Utils.runOnMainThreadDelayed(FollowersCheck::lookForCount, STEP_MS);
        } else {
            fail(L10n.t("Check now couldn't find your followers count."));
        }
    }

    private static void openFollowers(View count) {
        stage = Stage.FOLLOWERS;
        View button = clickable(count);
        Utils.runOnMainThread(() -> {
            if (stage != Stage.FOLLOWERS) return;
            button.performClick();
            Logger.printInfo(() -> "Check now: opened your Followers list");
            Utils.runOnMainThreadDelayed(() -> {
                // Instagram took the tap but never built the list.
                if (stage == Stage.FOLLOWERS && timedOut()) fail(L10n.t("Check now couldn't open your Followers list."));
            }, TIMEOUT_MS);
        });
    }

    /**
     * Called each time your own Followers list builds its rows, with whether Instagram has more to
     * load, how many rows it holds and your follower count. Starts the scrolling, and ends Check now
     * when the list is all there yet too short to count.
     */
    static void ownFollowers(@Nullable Boolean more, int loaded, @Nullable Integer count) {
        if (!running()) return;
        if (stage != Stage.SCROLLING) {
            stage = Stage.SCROLLING;
            still = 0;
            Logger.printInfo(() -> "Check now: scrolling your Followers list");
            queueStep();
        }
        if (Boolean.FALSE.equals(more) && count != null && !Unfollowers.full(loaded, count)) {
            fail(L10n.f("Instagram showed %1$d of your %2$d followers, too few to compare. Try again later.", loaded, count));
        }
    }

    private static void queueStep() {
        if (stepQueued) return;
        stepQueued = true;
        Utils.runOnMainThreadDelayed(FollowersCheck::step, STEP_MS);
    }

    private static void step() {
        stepQueued = false;
        if (stage != Stage.SCROLLING) return;
        try {
            if (timedOut()) {
                fail(L10n.t("Check now ran out of time before your Followers list finished loading."));
                return;
            }
            Activity activity = host.get();
            View row = activity == null ? null : find(activity.getWindow().getDecorView(), ROW_USERNAME);
            View list = row == null ? null : scroller(row);
            if (list != null && list.canScrollVertically(1)) {
                swipeUp(list);
                still = 0;
            } else if (++still > MAX_STILL) {
                fail(row == null ? L10n.t("Check now stopped: your Followers list closed.")
                        : L10n.t("Check now stopped: your Followers list stopped loading."));
                return;
            }
            queueStep();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, STEP, failure);
            stage = Stage.IDLE;
        }
    }

    /**
     * One quick swipe up on [list], given to it as touches the way a finger's would be, from near its
     * bottom to near its top: whatever kind of list Instagram built, it scrolls and flings as it does
     * for a thumb. The list's own scroll call isn't used, since on a list that doesn't scroll by it,
     * it moves the whole view off the screen instead.
     */
    static void swipeUp(View list) {
        long down = SystemClock.uptimeMillis();
        float x = list.getWidth() / 2f;
        float from = list.getHeight() * 0.85f;
        float to = list.getHeight() * 0.15f;
        int moves = 8;
        dispatch(list, down, down, MotionEvent.ACTION_DOWN, x, from);
        for (int i = 1; i <= moves; i++) {
            dispatch(list, down, down + i * 10L, MotionEvent.ACTION_MOVE, x, from + (to - from) * i / moves);
        }
        dispatch(list, down, down + (moves + 1) * 10L, MotionEvent.ACTION_UP, x, to);
    }

    private static void dispatch(View view, long down, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static boolean timedOut() {
        return SystemClock.elapsedRealtime() - startedAt > TIMEOUT_MS;
    }

    private static void fail(String why) {
        if (stage == Stage.IDLE) return;
        stage = Stage.IDLE;
        Logger.printInfo(() -> "Check now: " + why);
        Utils.showToastLong(why);
    }

    /**
     * A list was taken and added to the history, with [change] what it added. Runs on any thread.
     * Check now's look opens the history on it; any other look that found someone gone posts the
     * notification. Your profile's button then counts again.
     */
    @SuppressWarnings("deprecation") // onBackPressed closes the Followers list the way Back does.
    static void taken(Context application, Change change) {
        Utils.runOnMainThread(() -> {
            try {
                boolean asked = stage == Stage.SAVING && SystemClock.elapsedRealtime() - handedAt < ANSWER_MS;
                stage = Stage.IDLE;
                FollowersChip.reload(application);
                if (asked) {
                    Activity activity = host.get();
                    if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        // Back to the profile first, so closing the history leaves you where you started.
                        activity.onBackPressed();
                        FollowersScreen.showAfterCheck(activity, change);
                    }
                    return;
                }
                if (!change.first && change.count(Kind.LEFT) > 0 && Settings.UNFOLLOW_NOTIFICATIONS.get()) notify(application, change);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, NOTIFY, failure);
            }
        });
    }

    /** Posts who left, which opens the history when tapped. Quiet when Instagram can't notify. */
    static void notify(Context application, Change change) {
        NotificationManager manager = application.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        String name = L10n.t(application, "Unfollowers");
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        if (channel == null || !name.contentEquals(channel.getName())) {
            NotificationChannel named = new NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_DEFAULT);
            named.setDescription(L10n.t(application, "Who left your Followers list, found by HushGram's follower history"));
            manager.createNotificationChannel(named);
        }
        List<String> names = new ArrayList<>();
        for (Event event : change.added) {
            if (event.kind == Kind.LEFT) names.add(event.username.isEmpty() ? event.id : "@" + event.username);
        }
        String title = names.size() == 1 ? L10n.f(application, "%1$s unfollowed you", names.get(0))
                : L10n.quantity(application, names.size(), "1 person unfollowed you", "%1$d people unfollowed you");
        String text = String.join(", ", names.subList(0, Math.min(names.size(), 12)));
        Intent open = FollowersScreen.intent(application);
        PendingIntent tap = PendingIntent.getActivity(application, NOTIFICATION_ID, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(application, CHANNEL)
                .setSmallIcon(application.getApplicationInfo().icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(String.join(", ", names)))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
        Logger.printInfo(() -> "Follower history: notified " + names.size() + " unfollows");
    }

    // ------------------------------------------------------------------ finding Instagram's views

    /** The first view shown under [root] with the id [name], or null. */
    @Nullable
    static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        if (id == 0) return null;
        return shown(root, id);
    }

    @Nullable
    private static View shown(View view, int id) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.getId() == id && view.isAttachedToWindow()) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View found = shown(group.getChildAt(i), id);
            if (found != null) return found;
        }
        return null;
    }

    /** Going up from [inside], the first view with the id [name] found in an ancestor, or null. */
    @Nullable
    static View up(View inside, String name) {
        int id = inside.getResources().getIdentifier(name, "id", inside.getContext().getPackageName());
        if (id == 0) return null;
        View at = inside;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            View found = ((ViewGroup) parent).findViewById(id);
            if (found != null && found != parent) return found;
            at = (View) parent;
        }
        return null;
    }

    /** [view], or its nearest clickable ancestor when it isn't clickable itself. */
    static View clickable(View view) {
        View at = view;
        for (int depth = 0; depth < MAX_DEPTH && at != null; depth++) {
            if (at.isClickable()) return at;
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return view;
    }

    /** The list a [row] scrolls in: its nearest ancestor that scrolls up or down, or null. */
    @Nullable
    static View scroller(View row) {
        ViewParent parent = row.getParent();
        for (int depth = 0; depth < MAX_DEPTH && parent instanceof View; depth++) {
            View at = (View) parent;
            if (at.canScrollVertically(1) || at.canScrollVertically(-1)) return at;
            parent = at.getParent();
        }
        return null;
    }
}
