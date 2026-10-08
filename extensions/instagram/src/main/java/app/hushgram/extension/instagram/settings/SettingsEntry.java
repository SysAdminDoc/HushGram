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

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.download.SaveLeftovers;
import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.misc.ProfileSaved;
import app.hushgram.extension.instagram.misc.MediaCache;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * How the HushGram screen is reached.
 *
 * <p>A long-press shortcut on Instagram's launcher icon opens Instagram's main activity with
 * {@link #EXTRA_OPEN_SETTINGS}. Every Instagram activity's intent is read as it's created, and the
 * main activity's new intents as they arrive, and the next Instagram activity to resume shows the
 * screen as a full screen dialog. Nothing is added to Instagram's manifest, so no resource has to
 * be rebuilt to get here. The shortcut is kept first among Instagram's own, because a launcher
 * shows only the first few. A launcher that shows no shortcuts leaves the row at the top of
 * Instagram's own Settings and activity screen, which {@link SettingsScreenRow} draws.
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

    private static volatile boolean openPending;
    private static volatile long requestedAt;
    private static volatile boolean callbacksRegistered;
    /** The activity the screen was last shown over, while the person hasn't closed it. */
    private static WeakReference<Activity> host;
    private static volatile boolean closedByUser;
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
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not watch activities", ex);
        }
        GlassTabBar.install(context);
        ProfileSaved.install(context);
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
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            String longLabel = L10n.t(app, "HushGram settings");
            // Set before the attempt, so a shortcut that can't be pushed isn't tried on every screen.
            publishedLabel = longLabel;
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
            ShortcutManager manager = app.getSystemService(ShortcutManager.class);
            if (manager == null) return;
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
            noteIntent(intent);
            if (openPending) OpenWhenResumed.openWhenSettled(activity);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: onNewIntent failure", ex);
        }
    }

    private static final String SAVED_SETTINGS = "app.hushgram.settings.saved_dialog";

    /** Keep Android's fragment identities and pending result routing only for an open settings dialog. */
    public static void removeFrameworkState(Bundle state, String key) {
        if ("android:fragments".equals(key) && state.getBoolean(SAVED_SETTINGS, false)) return;
        state.remove(key);
    }

    static final class OpenWhenResumed implements Application.ActivityLifecycleCallbacks {
        /** The Instagram screen in front right now, if any. */
        private WeakReference<Activity> resumed;

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            try {
                // A screen put back after the process died carries its old intent; the request it
                // held was spent the first time.
                if (state == null) noteIntent(activity.getIntent());
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
        }

        @Override public void onActivityStarted(Activity activity) { }
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
                if (open(activity)) openPending = false;
            });
        }
    }

    /**
     * Shows the screen over the given activity.
     *
     * @return whether the screen is showing (or already was) over this activity.
     */
    @SuppressWarnings("deprecation") // Framework fragments are what the shared preference code builds on.
    public static boolean open(Activity activity) {
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
            // While Instagram is locked, the screen that could turn the lock off waits for the phone's lock.
            if (MessagesLock.locked()) {
                MessagesLock.confirmThen(activity, () -> open(activity));
                return true;
            }
            closedByUser = false;
            new SettingsDialog().show(fragments, DIALOG_TAG);
            host = new WeakReference<>(activity);
            Logger.printInfo(() -> "Settings opened over " + name);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the HushGram settings over " + name, ex);
            return false;
        }
    }

    /** Queues a gesture through the shortcut's same resume/account handoff, without starting a tab. */
    static boolean requestOpen(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (!closedByUser && ((host != null && host.get() == activity)
                || (openPending && SystemClock.elapsedRealtime() - requestedAt <= REQUEST_LIFETIME_MS))) return true;
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
    }

    private static void noteIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            intent.removeExtra(EXTRA_OPEN_SETTINGS);
            requestedAt = SystemClock.elapsedRealtime();
            openPending = true;
            Logger.printInfo(() -> "Settings requested by the launcher shortcut");
        }
    }

    /**
     * Injected before each return of the {@code onCreateView} of Instagram's settings screen, with
     * the screen's arguments and the view it made. On the top screen, Settings and activity, it
     * answers that view with the HushGram settings row above it; on every other screen, and when
     * anything goes wrong, the view as it came.
     */
    public static View withSettingsRow(Bundle arguments, View screen) {
        try {
            if (screen == null || !SettingsScreenRow.isMainScreen(arguments)) return screen;
            return SettingsScreenRow.above(screen);
        } catch (Throwable t) {
            Logger.printException(() -> "Settings entry: could not add the row to Instagram's settings", t);
            return screen;
        }
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
