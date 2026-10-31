/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.download.SaveLeftovers;
import app.hushgram.extension.instagram.feed.AmbientLight;
import app.hushgram.extension.instagram.feed.CarouselArrows;
import app.hushgram.extension.instagram.feed.CarouselPeek;
import app.hushgram.extension.instagram.feed.CommentEmojiRow;
import app.hushgram.extension.instagram.feed.FeedFollowButton;
import app.hushgram.extension.instagram.feed.HideCaptions;
import app.hushgram.extension.instagram.feed.FollowingFeed;
import app.hushgram.extension.instagram.feed.ReelCorners;
import app.hushgram.extension.instagram.feed.StoryCorners;
import app.hushgram.extension.instagram.metaai.MuseCard;
import app.hushgram.extension.instagram.misc.MediaCache;
import app.hushgram.extension.instagram.misc.RefreshRate;
import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.reels.BurnInGuard;
import app.hushgram.extension.instagram.reels.FullScreenBars;
import app.hushgram.extension.instagram.reels.ReelsTopBar;
import app.hushgram.extension.instagram.reels.ScrollBreak;
import app.hushgram.extension.instagram.reels.TabStart;
import app.hushgram.extension.instagram.misc.ProfileSaved;
import app.hushgram.extension.instagram.misc.LaunchScreen;
import app.hushgram.extension.instagram.misc.AppIcon;
import app.hushgram.extension.instagram.profile.ProfileTitle;
import app.hushgram.extension.instagram.stories.StoryProgressBar;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.ReopenSettingsAfterRestart;

/**
 * How the HushGram screen is reached.
 *
 * <p>A long-press shortcut on Instagram's launcher icon opens Instagram's main activity with
 * {@link #EXTRA_OPEN_SETTINGS}. Every Instagram activity's intent is read as it's created, and the
 * main activity's new intents as they arrive, and the next Instagram activity to resume shows the
 * screen as a full screen dialog. Nothing is added to Instagram's manifest, so no resource has to
 * be rebuilt to get here. The shortcut is kept first among Instagram's own, because a launcher
 * shows only the first few. A launcher that shows no shortcuts leaves the row at the top of
 * Instagram's own Settings and activity screen, which {@link SettingsScreenRow} draws. Inside
 * Instagram, a long press on the Instagram logo at the top of Home opens it too (see
 * {@link #setLogoLongClickListener}). The shortcut, the logo's long press and the row each have a
 * switch, and the last way in still on can't be turned off (HushFacebook#146).
 *
 * <p>The shortcut names the main activity itself rather than a launcher alias: Instagram's icon is
 * one of several aliases it switches between at runtime (its alternate app icons), and whichever
 * is enabled points at the main activity.
 */
@SuppressWarnings("unused")
public final class SettingsEntry {
    public static final String EXTRA_OPEN_SETTINGS = "app.hushgram.extension.instagram.OPEN_SETTINGS";
    static final String SHORTCUT_ID = "hushgram_settings";

    /** Exported, and what every launcher alias of Instagram's targets. A manifest name is kept. */
    static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";
    static final String DIALOG_TAG = "hushgram_settings";

    /** A request older than this is dropped rather than opened over some later screen. */
    private static final long REQUEST_LIFETIME_MS = 30_000;
    /** How long a request waits before it looks again at a screen whose theme can't draw text. */
    private static final long THEME_RETRY_MS = 250;

    /**
     * Whether a screen's theme can build the screen's text. During a relaunch Instagram's main
     * activity can still carry its launcher theme, whose text styles point at igds colors it
     * doesn't define, and the first TextView built over it threw (2026-10-09). Tests swap this,
     * since they can't build such a theme.
     */
    static volatile Predicate<Context> drawsText = SettingsEntry::buildsText;

    private static volatile boolean openPending;
    private static volatile long requestedAt;
    /** Whether a look at a screen whose theme can't draw text is already scheduled. Main thread only. */
    private static boolean themeRetryPending;
    /** The request whose wait for a theme was logged, so a wait is logged once, not every look. */
    private static volatile long themeWaitLoggedFor;
    private static volatile boolean callbacksRegistered;
    /** The activity the screen was last shown over, while the person hasn't closed it. */
    private static WeakReference<Activity> host;
    private static volatile boolean closedByUser;
    /**
     * The category the pending request opens expanded, after Restart now. Kept while the screen
     * moves to a screen in front, and dropped by the next request or the person closing it.
     */
    private static volatile String requestedPage;
    /** The long label last pushed, or found already on the shortcut, in this process. */
    private static volatile String publishedLabel;
    /** A check of the shortcut's place is waiting for the background thread. */
    private static final AtomicBoolean keepFirstQueued = new AtomicBoolean();

    private SettingsEntry() {
    }

    /**
     * Injected before each return of the application's {@code onCreate}, after Instagram's own
     * startup. Watches every Instagram activity, so a pending open lands on whichever one resumes
     * next: signed out, the main activity hands over to the login screen.
     */
    public static void onApplicationCreate(Context context) {
        try {
            if (!Utils.isMainProcess()) return;
            if (context instanceof Application && !callbacksRegistered) {
                ((Application) context).registerActivityLifecycleCallbacks(new OpenWhenResumed());
                callbacksRegistered = true;
            }
            // Restart now was tapped a moment ago: the screen opens again on its page.
            ReopenSettingsAfterRestart.reopenIfPending(SettingsEntry::reopenOn);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not watch activities", ex);
        }
        GlassTabBar.install(context);
        FullScreenBars.install(context);
        RefreshRate.install(context);
        ReelsTopBar.install(context);
        BurnInGuard.install(context);
        ScrollBreak.install(context);
        TabStart.install(context);
        ProfileSaved.install(context);
        CommentEmojiRow.install(context);
        FeedFollowButton.install(context);
        HideCaptions.install(context);
        FollowingFeed.install(context);
        ReelCorners.install(context);
        StoryCorners.install(context);
        CarouselPeek.install(context);
        CarouselArrows.install(context);
        AmbientLight.install(context);
        StoryProgressBar.install(context);
        ProfileMenuPress.install(context);
        ProfileTitle.install(context);
        MuseCard.install(context);
        LaunchScreen.install(context);
        AppIcon.install(context);
        if (SettingsStatus.messagesLock()) MessagesLock.watch(context);
        // A save Android stopped halfway left a pending gallery row, a work file or a notification.
        SaveLeftovers.sweepAfterStart(context);
        // With the patch in, the media cache is cleared on the way to the background once it's too large.
        MediaCache.watch(context);
        publishShortcut(context);
    }

    private static void publishShortcut(Context context) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> publishShortcutNow(app));
    }

    /**
     * Labels the shortcut again once Instagram has set its own language. That happens after the
     * application starts, so a label published then is in the phone's language. A string compare
     * while the label still matches.
     */
    static void relabelIfStale(Context context) {
        try {
            if (!L10n.t(context, "HushGram settings").equals(publishedLabel)) publishShortcut(context);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not check the shortcut's label", ex);
        }
    }

    /**
     * Publishes the launcher shortcut, labels it again when Instagram's language has changed, or
     * puts it back in front when Instagram's own went ahead of it, on the thread it's called on.
     * Package-visible for tests.
     */
    static void publishShortcutNow(Context app) {
        try {
            // Nothing to go by until the settings are read: the next screen asks again.
            if (!Utils.settingsReady()) return;
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            String longLabel = L10n.t(app, "HushGram settings");
            // Set before the attempt, so a shortcut that can't be pushed isn't tried on every screen.
            publishedLabel = longLabel;
            if (!Settings.SETTINGS_SHORTCUT.get()) {
                removeShortcut(manager);
                return;
            }
            ShortcutInfo existing = ours(manager);
            if (existing != null && existing.getRank() == 0
                    && longLabel.contentEquals(existing.getLongLabel())) return;
            // Evicts the lowest-ranked dynamic shortcut when Instagram's own fill the limit.
            push(manager, shortcut(app, longLabel));
            Logger.printInfo(() -> "Settings entry: launcher shortcut published");
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not publish the shortcut", ex);
        }
    }

    /**
     * Puts the shortcut back in front of Instagram's own, keeping the label it has, or publishes it
     * when Instagram's call removed it. Package-visible for tests.
     */
    static void keepFirstNow(Context app) {
        try {
            if (!Utils.settingsReady()) {
                // The switch can't be read yet, so the next screen checks the shortcut again.
                publishedLabel = null;
                return;
            }
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            // Switched off, Instagram's calls neither bring it back nor rank it.
            if (!Settings.SETTINGS_SHORTCUT.get()) {
                removeShortcut(manager);
                return;
            }
            ShortcutInfo existing = ours(manager);
            if (existing == null) {
                publishShortcutNow(app);
                return;
            }
            final int rank = existing.getRank();
            if (rank == 0) return;
            CharSequence label = existing.getLongLabel();
            push(manager, shortcut(app, label != null ? label : L10n.t(app, "HushGram settings")));
            Logger.printInfo(() -> "Settings entry: launcher shortcut moved back in front from rank " + rank);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not put the shortcut back in front", ex);
        }
    }

    /**
     * pushDynamicShortcut where the phone has it, which is Android 11 and newer. Android 9 and 10
     * get the same result by hand: the old copy goes, and when the dynamic shortcuts already fill
     * the limit the one ranked last goes too, before the shortcut is added at rank 0.
     */
    private static void push(ShortcutManager manager, ShortcutInfo shortcut) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.pushDynamicShortcut(shortcut);
            return;
        }
        manager.removeDynamicShortcuts(Collections.singletonList(SHORTCUT_ID));
        List<ShortcutInfo> others = manager.getDynamicShortcuts();
        if (!others.isEmpty() && others.size() >= manager.getMaxShortcutCountPerActivity()) {
            ShortcutInfo last = Collections.max(others, Comparator.comparingInt(ShortcutInfo::getRank));
            manager.removeDynamicShortcuts(Collections.singletonList(last.getId()));
        }
        manager.addDynamicShortcuts(Collections.singletonList(shortcut));
    }

    /**
     * The shortcut switch changed: off takes the shortcut out of the launcher's menu now, on puts it
     * back in front, both on a background thread. Instagram's own shortcuts stay. Also called after
     * the home screen icon changed, since Android takes a shortcut away with the icon it was under.
     */
    public static void shortcutSwitchChanged(Context context) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> publishShortcutNow(app));
    }

    /** Takes the HushGram shortcut out of the launcher's menu, if it's there. */
    private static void removeShortcut(ShortcutManager manager) {
        if (ours(manager) == null) return;
        manager.removeDynamicShortcuts(Collections.singletonList(SHORTCUT_ID));
        Logger.printInfo(() -> "Settings entry: launcher shortcut removed, its switch is off");
    }

    /** The HushGram shortcut among the dynamic ones, or null. */
    private static ShortcutInfo ours(ShortcutManager manager) {
        for (ShortcutInfo existing : manager.getDynamicShortcuts()) {
            if (SHORTCUT_ID.equals(existing.getId())) return existing;
        }
        return null;
    }

    private static ShortcutInfo shortcut(Context app, CharSequence longLabel) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(app.getPackageName(), MAIN_ACTIVITY))
                .putExtra(EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new ShortcutInfo.Builder(app, SHORTCUT_ID)
                .setShortLabel("HushGram")
                .setLongLabel(longLabel)
                .setIcon(Icon.createWithAdaptiveBitmap(shortcutIcon()))
                .setIntent(intent)
                // The platform puts the newest push first among equal ranks.
                .setRank(0)
                .build();
    }

    // Instagram's own calls that change its dynamic shortcuts come here instead: the patch sends
    // each ShortcutManager call of these names to the method of the same name here, the manager
    // first. Instagram's call runs as it did, with the same answer and the same exceptions, and
    // then the HushGram shortcut goes back in front.

    // Only Instagram's own pushDynamicShortcut calls come here, in place of the call it made, so
    // this runs only where Instagram's call would have run.
    @TargetApi(Build.VERSION_CODES.R)
    public static void pushDynamicShortcut(ShortcutManager manager, ShortcutInfo shortcut) {
        manager.pushDynamicShortcut(shortcut);
        keepFirst();
    }

    public static boolean addDynamicShortcuts(ShortcutManager manager, List<ShortcutInfo> shortcuts) {
        boolean added = manager.addDynamicShortcuts(shortcuts);
        keepFirst();
        return added;
    }

    /** Replaces every dynamic shortcut, the HushGram one too, which is published again after. */
    public static boolean setDynamicShortcuts(ShortcutManager manager, List<ShortcutInfo> shortcuts) {
        boolean set = manager.setDynamicShortcuts(shortcuts);
        keepFirst();
        return set;
    }

    public static boolean updateShortcuts(ShortcutManager manager, List<ShortcutInfo> shortcuts) {
        boolean updated = manager.updateShortcuts(shortcuts);
        keepFirst();
        return updated;
    }

    public static void removeAllDynamicShortcuts(ShortcutManager manager) {
        manager.removeAllDynamicShortcuts();
        keepFirst();
    }

    /**
     * Checks the shortcut on a background thread, once however many of Instagram's calls ask. The
     * flag drops as the check starts, so a call that lands during it asks for another.
     */
    private static void keepFirst() {
        try {
            Context context = Utils.getContext();
            if (context == null || !keepFirstQueued.compareAndSet(false, true)) return;
            final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            boolean queued = Utils.runOnBackgroundThread(() -> {
                keepFirstQueued.set(false);
                keepFirstNow(app);
            });
            if (!queued) keepFirstQueued.set(false);
        } catch (Throwable t) {
            keepFirstQueued.set(false);
            Logger.printException(() -> "Settings entry: could not check the shortcut after Instagram's", t);
        }
    }

    /** Injected at the start of the main activity's {@code onNewIntent}. */
    public static void onNewIntent(Activity activity, Intent intent) {
        try {
            if (noteIntent(intent)) ShortcutCover.show(activity);
            else if (leavesSettings(intent)) closeForLink(activity);
            if (openPending) OpenWhenResumed.openWhenSettled(activity);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: onNewIntent failure", ex);
        }
    }

    /**
     * Whether [intent], one that isn't the shortcut's, takes the person somewhere in Instagram: a
     * link, which carries data, or a tapped notification, which names itself in an extra. Instagram
     * shows where it leads under the settings, so they close for it.
     */
    static boolean leavesSettings(Intent intent) {
        if (intent == null) return false;
        if (intent.getData() != null) return true;
        Bundle extras = intent.getExtras();
        if (extras == null) return false;
        for (String key : extras.keySet()) {
            if (key != null && key.toLowerCase(Locale.ROOT).contains("notification")) return true;
        }
        return false;
    }

    /**
     * Closes the settings shown on [activity] as if the person had, so a link that came in shows.
     * A request that hasn't shown yet is dropped with its cover, or it would open over the link.
     */
    private static void closeForLink(Activity activity) {
        if (openPending) {
            openPending = false;
            onClosedByUser();
            ShortcutCover.off("a link or notification came in first");
            Logger.printInfo(() -> "Settings entry: a link or notification came in before the settings showed, dropping the request");
        }
        Fragment shown = activity.getFragmentManager().findFragmentByTag(DIALOG_TAG);
        if (!(shown instanceof DialogFragment)) return;
        onClosedByUser();
        ((DialogFragment) shown).dismissAllowingStateLoss();
        Logger.printInfo(() -> "Settings entry: a link or notification came in, closing the settings over it");
    }

    private static final String SAVED_SETTINGS = "app.hushgram.settings.saved_dialog";

    /** Keep Android's fragment identities and pending result routing only for an open settings dialog. */
    public static void removeFrameworkState(Bundle state, String key) {
        if ("android:fragments".equals(key) && state.getBoolean(SAVED_SETTINGS, false)) return;
        state.remove(key);
    }

    /** Whether this start has said that safe mode paused HushGram. */
    private static final AtomicBoolean pauseSaid = new AtomicBoolean();

    /**
     * Once a start, as the first Instagram screen comes up: safe mode paused HushGram after crashes,
     * which nobody asked for, so every switch is off without the person knowing. Ghost mode, the
     * locks and the rest would quietly stop working. The Pause switch and the marker file are the
     * person's own doing, so they get nothing here.
     */
    static void sayWhyPaused() {
        try {
            if (HushgramPause.reason() != HushgramPause.Reason.CRASH_LOOP || !pauseSaid.compareAndSet(false, true)) return;
            Utils.showToastLong(L10n.t("HushGram paused itself because Instagram kept crashing as it started. "
                    + "Its switches stay off until you turn it back on in HushGram settings."));
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings entry: could not say why HushGram is paused", failure);
        }
    }

    static void resetPauseSaidForTests() {
        pauseSaid.set(false);
    }

    static final class OpenWhenResumed implements Application.ActivityLifecycleCallbacks {
        /** The Instagram screen in front right now, if any. */
        private WeakReference<Activity> resumed;

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            try {
                // A screen put back after the process died carries its old intent; the request it
                // held was spent the first time.
                if (state == null && noteIntent(activity.getIntent())) ShortcutCover.show(activity);
                if (activity.getFragmentManager().findFragmentByTag(DIALOG_TAG) instanceof SettingsDialog) {
                    host = new WeakReference<>(activity);
                    closedByUser = false;
                    openPending = false;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Settings entry: could not read a new screen's intent", ex);
            }
        }

        @Override
        public void onActivityResumed(Activity activity) {
            resumed = new WeakReference<>(activity);
            SaveLeftovers.showInterrupted(activity);
            sayWhyPaused();
            followToFront(activity);
            if (openPending) openWhenSettled(activity);
            relabelIfStale(activity);
        }

        /**
         * Signed out, Instagram's login screen opens its modal over itself a moment after it
         * resumes, and the screen shown over the login screen ends up underneath, where nobody
         * sees it. The person can't open an Instagram screen while this one fills the display,
         * so a screen that resumes over it soon after the request is Instagram's own, and the
         * screen moves to it.
         */
        private static void followToFront(Activity activity) {
            WeakReference<Activity> shownOver = host;
            Activity previous = shownOver == null ? null : shownOver.get();
            if (previous == null || previous == activity || closedByUser) return;
            if (SystemClock.elapsedRealtime() - requestedAt > REQUEST_LIFETIME_MS) return;
            host = null;
            try {
                // A dismiss in code isn't a cancel, so this doesn't count as the person closing it.
                Fragment shown = previous.getFragmentManager().findFragmentByTag(DIALOG_TAG);
                if (shown instanceof DialogFragment) ((DialogFragment) shown).dismissAllowingStateLoss();
            } catch (Exception ex) {
                Logger.printException(() -> "Settings entry: could not close the covered screen", ex);
            }
            openPending = true;
            Logger.printInfo(() -> activity.getClass().getSimpleName() + " came up over the settings on "
                    + previous.getClass().getSimpleName() + "; moving them to the front");
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (resumed != null && resumed.get() == activity) resumed = null;
            ShortcutCover.offIfOver(activity);
        }

        @Override public void onActivityStarted(Activity activity) {
            ShortcutCover.onStarted(activity);
        }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
            Fragment dialog = activity.getFragmentManager().findFragmentByTag(DIALOG_TAG);
            if (dialog instanceof SettingsDialog && dialog.isAdded() && !dialog.isRemoving()) {
                state.putBoolean(SAVED_SETTINGS, true);
            } else {
                state.remove(SAVED_SETTINGS);
            }
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            // The screen can land on an activity just before it clears itself for the next one.
            // If its host goes away before the person closed it, ask again.
            WeakReference<Activity> shownOver = host;
            if (shownOver == null || shownOver.get() != activity) return;
            host = null;
            if (closedByUser) return;
            if (SystemClock.elapsedRealtime() - requestedAt > REQUEST_LIFETIME_MS) return;
            openPending = true;
            Logger.printInfo(() -> "Settings host " + activity.getClass().getSimpleName()
                    + " went away; opening again over the next screen");
            // Android resumes the next screen before it destroys the one it replaced, so that
            // screen is usually in front already and won't resume again to pick the request up.
            Activity current = resumed == null ? null : resumed.get();
            if (current != null && current != activity) openWhenSettled(current);
        }

        /**
         * Posted, so the activity has finished resuming before a fragment is committed. The request
         * stays pending until a screen is actually shown: a screen that resumes for a moment while
         * it clears itself for the next would otherwise spend it.
         */
        static void openWhenSettled(Activity activity) {
            Utils.runOnMainThread(() -> {
                if (!openPending) return;
                if (SystemClock.elapsedRealtime() - requestedAt > REQUEST_LIFETIME_MS) {
                    openPending = false;
                    Logger.printInfo(() -> "Settings request expired before an Instagram screen could show it");
                    return;
                }
                if (open(activity)) {
                    openPending = false;
                } else if (!themeRetryPending && !activity.isFinishing() && !activity.isDestroyed() && !drawsText.test(activity)) {
                    // The theme can arrive without another resume, so look again shortly. One look
                    // at a time: a resume during the wait would otherwise start a second chain.
                    themeRetryPending = true;
                    WeakReference<Activity> later = new WeakReference<>(activity);
                    Utils.runOnMainThreadDelayed(() -> {
                        themeRetryPending = false;
                        Activity waiting = later.get();
                        if (waiting != null) openWhenSettled(waiting);
                    }, THEME_RETRY_MS);
                }
            });
        }
    }

    static boolean buildsText(Context context) {
        try {
            new TextView(context);
            return true;
        } catch (UnsupportedOperationException unresolved) {
            return false;
        }
    }

    /**
     * The screen closed itself over a host whose theme couldn't draw it. The request starts again,
     * for this host once its theme can or for the next screen to resume.
     */
    static void reopenOnceThemed(Activity activity) {
        host = null;
        closedByUser = false;
        requestedAt = SystemClock.elapsedRealtime();
        openPending = true;
        if (activity != null) OpenWhenResumed.openWhenSettled(activity);
    }

    /**
     * Shows the screen over the given activity.
     *
     * @return whether the screen is showing (or already was) over this activity.
     */
    public static boolean open(Activity activity) {
        // Only a pending request carries a page; a tap on Instagram's own settings row doesn't.
        return open(activity, openPending ? requestedPage : null);
    }

    @SuppressWarnings("deprecation") // Framework fragments are what the shared preference code builds on.
    private static boolean open(Activity activity, String page) {
        final String name = activity.getClass().getSimpleName();
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                Logger.printInfo(() -> "Settings wait: " + name + " is finishing");
                return false;
            }
            FragmentManager fragments = activity.getFragmentManager();
            if (fragments.findFragmentByTag(DIALOG_TAG) != null) {
                host = new WeakReference<>(activity);
                closedByUser = false;
                return true;
            }
            if (fragments.isStateSaved()) {
                Logger.printInfo(() -> "Settings wait: " + name + " has saved its state");
                return false;
            }
            if (!drawsText.test(activity)) {
                if (themeWaitLoggedFor != requestedAt) {
                    themeWaitLoggedFor = requestedAt;
                    Logger.printInfo(() -> "Settings wait: " + name + "'s theme can't draw text yet");
                }
                return false;
            }
            // While Instagram is locked, the screen that could turn the lock off waits for the phone's lock.
            if (MessagesLock.locked()) {
                MessagesLock.confirmThen(activity, () -> open(activity, page));
                return true;
            }
            closedByUser = false;
            SettingsDialog.onPage(page).show(fragments, DIALOG_TAG);
            host = new WeakReference<>(activity);
            Logger.printInfo(() -> "Settings opened over " + name);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the HushGram settings over " + name, ex);
            return false;
        }
    }

    /**
     * Instagram's call that gives Home's title view its long press comes here instead. The title
     * view is the middle of the bar at the top of your feed and holds the Instagram logo, so a
     * press on the logo is a press on it. The patch sends the one call that does this, in the
     * method that draws the bar, with the same registers, and Instagram makes it again each time
     * the bar draws from a new state.
     *
     * <p>The title view gets a listener that opens this screen over the logo's activity first.
     * Instagram's long press there only does something on some accounts: on an Instagram Plus
     * account it opens the Change your app icon sheet, which used to take every press, so the logo
     * never reached HushGram there. Instagram's listener is asked only when this screen can't open,
     * the logo no longer being on a screen in front. The logo's tap isn't touched, so it still does
     * what Instagram made it do.
     *
     * <p>The press has a switch ({@link Settings#SETTINGS_LOGO_PRESS}) that a pause leaves as it is,
     * since this is a way back to the switch that resumes HushGram. The listener reads it on every
     * press, and off, the press is Instagram's. Anything that goes wrong leaves the title view with
     * Instagram's listener.
     */
    public static void setLogoLongClickListener(View title, View.OnLongClickListener instagrams) {
        View.OnLongClickListener listener = instagrams;
        try {
            listener = new LogoPress(instagrams);
            if (!logoWatched) {
                logoWatched = true;
                Logger.printInfo(() -> "Settings entry: a long press on the Instagram logo opens the settings");
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings entry: could not watch the Instagram logo", failure);
        }
        // Instagram's own call, with the listener above or Instagram's.
        title.setOnLongClickListener(listener);
    }

    /**
     * Home's newer header (the one with feed tabs) shows the Instagram logo as Home opens, and on
     * some accounts keeps it there, but gives it no long press of its own. The patch hands its logo
     * view here right after the header finds it in its layout, once per header, and the logo gets
     * the same long press as the older header's: this screen over the logo's activity. Nothing
     * listens for a tap there, so none is taken from Instagram. Never throws, since it runs inside
     * the header's constructor; anything that goes wrong leaves the logo as Instagram made it.
     */
    public static void watchLogo(View logo) {
        try {
            if (logo == null) return;
            setLogoLongClickListener(logo, null);
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings entry: could not watch the Instagram logo in Home's newer header", failure);
        }
    }

    /** Whether the logo's long press has been logged this run, so a redraw doesn't log it again. */
    private static volatile boolean logoWatched;

    /** The long press on Home's title view: this screen first, then Instagram's listener. */
    static final class LogoPress implements View.OnLongClickListener {
        /** Instagram's listener for the title view, or null when it passed none. */
        final View.OnLongClickListener instagrams;

        LogoPress(View.OnLongClickListener instagrams) {
            // One of ours handed back in isn't Instagram's, so the one it wraps is asked instead.
            this.instagrams = instagrams instanceof LogoPress ? ((LogoPress) instagrams).instagrams : instagrams;
        }

        @Override
        public boolean onLongClick(View title) {
            // The logo's long press is the way into HushGram, so Instagram's app icon sheet can't
            // take it first. Instagram's listener gets the press only when this screen can't open,
            // or with the switch off.
            if (logoPressOn() && openFromLogo(title)) return true;
            return instagrams != null && instagrams.onLongClick(title);
        }
    }

    /**
     * Whether a long press on the logo opens this screen: its switch, read through Pause. Before the
     * settings are read the press opens it, as it always did.
     */
    static boolean logoPressOn() {
        return !Utils.settingsReady() || Settings.SETTINGS_LOGO_PRESS.get();
    }

    /**
     * Asks for the screen over the logo's activity, the way the launcher shortcut does, so it waits
     * for the activity to settle and follows it to the next screen if it goes away. False leaves
     * the press as Instagram left it: Android then takes letting go as a tap, as it does without
     * HushGram. Never throws.
     */
    static boolean openFromLogo(View title) {
        try {
            Activity activity = NavigationSettings.ownerOf(title);
            if (activity == null) {
                Logger.printInfo(() -> "Settings entry: the Instagram logo isn't on a screen in front, so its long press is Instagram's");
                return false;
            }
            Logger.printInfo(() -> "Settings requested by a long press on the Instagram logo");
            return requestOpen(activity);
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings entry: the long press on the Instagram logo failed", failure);
            return false;
        }
    }

    /**
     * At start, after Restart now: asks for the screen on [page], the way the launcher shortcut
     * does, so it opens over the first Instagram screen that can show it.
     */
    static void reopenOn(String page) {
        requestedPage = page;
        closedByUser = false;
        requestedAt = SystemClock.elapsedRealtime();
        openPending = true;
        themeRetryPending = false;
        Logger.printInfo(() -> "Settings requested again after Restart now");
    }

    /** Queues a gesture through the shortcut's same resume/account handoff, without starting a tab. */
    public static boolean requestOpen(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (!closedByUser && ((host != null && host.get() == activity)
                || (openPending && SystemClock.elapsedRealtime() - requestedAt <= REQUEST_LIFETIME_MS))) return true;
        requestedPage = null;
        closedByUser = false;
        requestedAt = SystemClock.elapsedRealtime();
        openPending = true;
        OpenWhenResumed.openWhenSettled(activity);
        return true;
    }

    /** Called by the screen when the person closes it, so it isn't reopened. */
    static void onClosedByUser() {
        closedByUser = true;
        host = null;
        requestedPage = null;
    }

    /** Whether [intent] is the launcher shortcut's, which is then a pending request. */
    private static boolean noteIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS);
            requestedPage = null;
            requestedAt = SystemClock.elapsedRealtime();
            openPending = true;
            // A new request looks for itself; a look an old one left scheduled may never run.
            themeRetryPending = false;
            Logger.printInfo(() -> "Settings requested by the launcher shortcut");
            return true;
        }
        return false;
    }

    /**
     * Injected before each return of the {@code onCreateView} of Instagram's settings screen, with
     * the screen's arguments and the view it made. On the top screen, Settings and activity, it
     * answers that view with the HushGram settings row above it; on every other screen, and when
     * anything goes wrong, the view as it came. With {@link Settings#HIDE_MENU_ROW} on, the row
     * is left out while another way can open HushGram instead ({@link #menuRowLeftOut}).
     */
    public static View withSettingsRow(Bundle arguments, View screen) {
        try {
            if (screen == null || !SettingsScreenRow.isMainScreen(arguments)) return screen;
            if (menuRowLeftOut()) {
                Logger.printInfo(() -> "Settings entry: menu row left out, another way opens HushGram");
                return screen;
            }
            return SettingsScreenRow.above(screen);
        } catch (Throwable t) {
            Logger.printException(() -> "Settings entry: could not add the row to Instagram's settings", t);
            return screen;
        }
    }

    /**
     * Whether Instagram's settings screen leaves the HushGram row out: {@link Settings#HIDE_MENU_ROW}
     * on while another way in works, which is the launcher shortcut (on a launcher that shows it) or
     * the logo's long press with its switch on, the chosen tab long press (#84) on a button Instagram
     * built, or the long press on your profile's menu (#148) on a button that carries it. With every
     * other way off the row stays, so there's always a way in (HushFacebook#146). Paused, the switch
     * reads off.
     */
    static boolean menuRowLeftOut() {
        if (!Utils.settingsReady() || !Settings.HIDE_MENU_ROW.get()) return false;
        return (Settings.SETTINGS_SHORTCUT.get() && launcherShowsShortcuts()) || Settings.SETTINGS_LOGO_PRESS.get()
                || NavigationSettings.opensFromATab() || ProfileMenuPress.opensHushGram();
    }

    /**
     * Whether the shortcut switch opens anything: the home screen app takes pinned shortcuts, the
     * sign it shows the menu you get by holding an icon, or ours is already pinned there. A launcher
     * with neither (some third-party ones, Android Go) never shows it, so it isn't a way in.
     */
    static boolean launcherShowsShortcuts() {
        try {
            ShortcutManager manager = Utils.getContext().getSystemService(ShortcutManager.class);
            if (manager == null) return false;
            if (manager.isRequestPinShortcutSupported()) return true;
            for (ShortcutInfo pinned : manager.getPinnedShortcuts()) {
                if (SHORTCUT_ID.equals(pinned.getId())) return true;
            }
            return false;
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not tell whether the launcher shows shortcuts", ex);
            return false;
        }
    }

    /**
     * How many ways into HushGram are on, from the saved values the settings screen shows: the
     * launcher shortcut (on a launcher that shows it), the logo's long press, the menu row (on while
     * its hide switch is off), a tab long press (a tab chosen) and the long press on your profile's
     * menu.
     */
    static int waysInOn() {
        return (Settings.SETTINGS_SHORTCUT.savedValue() && launcherShowsShortcuts() ? 1 : 0)
                + (Settings.SETTINGS_LOGO_PRESS.savedValue() ? 1 : 0)
                + (Settings.HIDE_MENU_ROW.savedValue() ? 0 : 1)
                + (Settings.NAVIGATION_SETTINGS_TARGET.savedValue() != NavigationTarget.OFF ? 1 : 0)
                + (Settings.PROFILE_MENU_LONG_PRESS.savedValue() ? 1 : 0);
    }

    /**
     * Whether [way], the shortcut switch, the logo press switch or the menu row's hide switch, is the
     * last way in still on, so the settings screen greys it out under a line saying why. The menu
     * row's way is on while its hide switch is off. A shortcut the launcher never shows is no way in.
     */
    static boolean isLastWayIn(Setting<?> way) {
        boolean on = way == Settings.SETTINGS_SHORTCUT ? Settings.SETTINGS_SHORTCUT.savedValue() && launcherShowsShortcuts()
                : way == Settings.SETTINGS_LOGO_PRESS ? Settings.SETTINGS_LOGO_PRESS.savedValue()
                : way == Settings.HIDE_MENU_ROW && !Settings.HIDE_MENU_ROW.savedValue();
        return on && waysInOn() == 1;
    }

    /** A magenta disc with an "H", drawn so the shortcut needs no resource in Instagram's APK. */
    private static Bitmap shortcutIcon() {
        return mark(432, Color.BLACK, 0.30f); // Adaptive icon canvas: 108dp at xxxhdpi.
    }

    /** The HushGram mark, [size] pixels square on [background], the disc [radius] of the size across. */
    static Bitmap mark(int size, int background, float radius) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(background);
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(0xFFE1306C);
        canvas.drawCircle(size / 2f, size / 2f, size * radius, disc);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(size * radius * 0.8f);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText("H", size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, text);
        return bitmap;
    }
}
