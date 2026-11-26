/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Buttons for Instagram's picture in picture window for reels (#873).
 *
 * <p>Android draws a picture in picture window's buttons itself, from the RemoteActions the app
 * hands it with the window's settings, so an app can't put views of its own in the window. Instagram
 * builds those settings in ClipsViewerPipUtil, and sets a list of its own buttons there only when a
 * server flag says so. The patch has that method ask {@link #withActions} at its top, with the
 * flag's answer, so the list is set whenever a button here is on, and hands {@link #actions} the
 * list right before it's set. Pause goes in the middle of whatever Instagram put there.
 *
 * <p>Pause pauses the video playing in the window through Instagram's own pause, the way the
 * HushGram settings pause the video under them ({@link SettingsPause}), and the button turns into
 * Play, which plays it again. The players come from the "Keep playing in the background" start
 * hook, which also tells {@link #started} when Instagram starts a video itself, and the button
 * turns back into Pause then.
 *
 * <p>Instagram's own Previous and Next, which go a reel back or on to the next one, sit behind the
 * same server flag: the window's content, ClipsPiPFragment, reads it in its onCreate and moves between
 * reels and listens for those buttons only when it says yes. The patch hands {@link #skipButtons}
 * that answer, and with Previous and next on, the window moves between reels and both builders set
 * Instagram's buttons. The window plays video reels only, so they go between those, unless
 * {@link ReelsPipStills} lets photos and carousels in too, whose pages they turn first.
 *
 * <p>Every hook runs on the main thread, and none of them ever throws into Instagram.
 */
public final class ReelsPipButtons {
    static final String ACTION_PAUSE = "app.hushgram.extension.instagram.REELS_PIP_PAUSE";
    static final String ACTION_PLAY = "app.hushgram.extension.instagram.REELS_PIP_PLAY";
    static final String HOOK = "reels window buttons";

    private static final int PAUSE_REQUEST = 873;
    private static final int PLAY_REQUEST = 874;
    private static final int INTENT_FLAGS = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;

    /** What the window does, for tests that have no picture in picture. */
    interface Window {
        boolean showing(Activity screen);

        int maxActions(Activity screen);

        void setActions(Activity screen, List<RemoteAction> actions);
    }

    @Nullable
    static volatile Window windowForTests;

    private static final Object LOCK = new Object();
    /** The screen Instagram set the window up for last. */
    @Nullable
    private static WeakReference<Activity> screen;
    /** Whether Instagram's flag answered yes for its own buttons the last time. */
    private static boolean instagramButtons;
    /** The buttons Instagram put in the window's list last, kept for redrawing it. */
    private static List<RemoteAction> theirs = new ArrayList<>();
    /** Whether the window's video is paused from its button, so the button shows Play. */
    private static boolean paused;
    /** The players the window's Pause paused, oldest first. */
    private static final List<WeakReference<Object>> PAUSED = new ArrayList<>();
    @Nullable
    private static Context listeningOn;

    private ReelsPipButtons() {
    }

    // ---- Hooks ----

    /**
     * At the top of ClipsViewerPipUtil's settings builder, with the [activity] it builds them for and
     * Instagram's own answer (non-zero for yes) to whether the window gets its buttons. Yes whenever a
     * button here is on too.
     */
    public static boolean withActions(Activity activity, int stock) {
        boolean theirsWanted = stock != 0;
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            synchronized (LOCK) {
                instagramButtons = theirsWanted;
                screen = activity != null ? new WeakReference<>(activity) : null;
            }
            return theirsWanted || playButtonOn() || skipButtonsOn();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return theirsWanted;
        }
    }

    /**
     * In ClipsPiPFragment's onCreate, with Instagram's answer (non-zero for yes) to whether the window
     * moves between reels with its Previous and Next. Yes whenever Previous and next is on too.
     */
    public static boolean skipButtons(int stock) {
        boolean flagged = stock != 0;
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            return flagged || skipButtonsOn();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return flagged;
        }
    }

    /**
     * Right before the settings builder sets the window's buttons, with the list Instagram made.
     * Answers the list to set: Instagram's buttons when its flag said yes or Previous and next is on,
     * and Pause or Play.
     */
    public static List<?> actions(List<?> stock) {
        boolean keepTheirs;
        synchronized (LOCK) {
            keepTheirs = instagramButtons;
        }
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            keepTheirs = keepTheirs || skipButtonsOn();
            Activity activity;
            synchronized (LOCK) {
                activity = screen != null ? screen.get() : null;
            }
            if (activity == null || !playButtonOn()) return keepTheirs ? stock : new ArrayList<RemoteAction>();
            List<RemoteAction> kept = new ArrayList<>();
            if (keepTheirs && stock != null) {
                for (Object button : stock) {
                    if (button instanceof RemoteAction) kept.add((RemoteAction) button);
                }
            }
            boolean inWindow = showing(activity);
            synchronized (LOCK) {
                theirs = kept;
                // Set up outside the window, it's for a new one, where the reel plays.
                if (!inWindow) forgetPausedLocked();
            }
            listen(activity);
            return buttons(activity);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return keepTheirs ? stock : new ArrayList<RemoteAction>();
        }
    }

    /** From {@link BackgroundPlay#started}: a video starting turns a Play button back into Pause. */
    static void started(Object player) {
        synchronized (LOCK) {
            if (!paused) return;
            forgetPausedLocked();
        }
        Utils.runOnMainThread(ReelsPipButtons::redraw);
    }

    // ---- The buttons ----

    /** Pause in the window: pauses each video playing, and the button turns into Play. */
    static void pauseTapped() {
        List<Object> stopped = new ArrayList<>();
        for (Object player : BackgroundPlay.recentPlayers()) {
            if (!BackgroundPlay.playingNow(player)) continue;
            // Instagram's own pause, the one the settings use too.
            BackgroundPlay.pauseUnderSettings(player);
            stopped.add(player);
        }
        synchronized (LOCK) {
            paused = true;
            PAUSED.clear();
            for (Object player : stopped) PAUSED.add(new WeakReference<>(player));
        }
        int count = stopped.size();
        Logger.printInfo(() -> "ReelsPipButtons: paused " + count + " from the window");
        redraw();
    }

    /** Play in the window: plays what its Pause paused, or the newest video, and the button turns into Pause. */
    static void playTapped() {
        List<Object> again = new ArrayList<>();
        synchronized (LOCK) {
            for (WeakReference<Object> ref : PAUSED) {
                Object player = ref.get();
                if (player != null) again.add(player);
            }
            forgetPausedLocked();
        }
        if (again.isEmpty()) {
            List<Object> recent = BackgroundPlay.recentPlayers();
            if (!recent.isEmpty()) again.add(recent.get(0));
        }
        for (Object player : again) BackgroundPlay.playAgain(player);
        int count = again.size();
        Logger.printInfo(() -> "ReelsPipButtons: played " + count + " from the window");
        redraw();
    }

    /** Sets the window's buttons again, when it shows and Pause is still wanted. */
    static void redraw() {
        try {
            Activity activity;
            synchronized (LOCK) {
                activity = screen != null ? screen.get() : null;
            }
            if (activity == null || !playButtonOn() || !showing(activity)) return;
            List<RemoteAction> buttons = buttons(activity);
            Window window = windowForTests;
            if (window != null) window.setActions(activity, buttons);
            else activity.setPictureInPictureParams(new PictureInPictureParams.Builder().setActions(buttons).build());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
        }
    }

    /**
     * Instagram's buttons with Pause or Play in the middle. Android refuses a list longer than the
     * window takes, and the window's settings with it, so Instagram's first ones go until it fits.
     */
    private static List<RemoteAction> buttons(Activity activity) {
        List<RemoteAction> list;
        boolean showPlay;
        synchronized (LOCK) {
            list = new ArrayList<>(theirs);
            showPlay = paused;
        }
        RemoteAction ours = button(activity, showPlay);
        list.add(list.size() / 2, ours);
        int room = Math.max(1, maxActions(activity));
        for (Iterator<RemoteAction> it = list.iterator(); list.size() > room && it.hasNext(); ) {
            if (it.next() != ours) it.remove();
        }
        return list;
    }

    private static RemoteAction button(Activity activity, boolean play) {
        Context context = activity.getApplicationContext() != null ? activity.getApplicationContext() : activity;
        Intent intent = new Intent(play ? ACTION_PLAY : ACTION_PAUSE).setPackage(context.getPackageName());
        PendingIntent tap = PendingIntent.getBroadcast(context, play ? PLAY_REQUEST : PAUSE_REQUEST, intent, INTENT_FLAGS);
        Icon icon = Icon.createWithResource(context, play ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause);
        String title = L10n.t(context, play ? "Play" : "Pause");
        return new RemoteAction(icon, title, title, tap);
    }

    /** Registers the buttons' receiver once per process, for Instagram's own broadcasts only. */
    private static void listen(Context context) {
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            if (listeningOn == application) return;
            listeningOn = application;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PAUSE);
        filter.addAction(ACTION_PLAY);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_NOT_EXPORTED : 0;
        application.registerReceiver(new Tapped(), filter, flags);
    }

    private static final class Tapped extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent != null ? intent.getAction() : null;
                if (ACTION_PAUSE.equals(action)) pauseTapped();
                else if (ACTION_PLAY.equals(action)) playTapped();
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            }
        }
    }

    private static void forgetPausedLocked() {
        paused = false;
        PAUSED.clear();
    }

    private static boolean showing(Activity activity) {
        Window window = windowForTests;
        if (window != null) return window.showing(activity);
        return activity.isInPictureInPictureMode();
    }

    private static int maxActions(Activity activity) {
        Window window = windowForTests;
        if (window != null) return window.maxActions(activity);
        return activity.getMaxNumPictureInPictureActions();
    }

    static boolean playButtonOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.REELS_PIP_PLAY_BUTTON.get();
    }

    static boolean skipButtonsOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.REELS_PIP_SKIP_BUTTONS.get();
    }

    /** Whether the window's button shows Play. For tests. */
    static boolean showsPlay() {
        synchronized (LOCK) {
            return paused;
        }
    }

    /** Forgets the window, its buttons and the receiver. For tests. */
    static void resetForTests() {
        synchronized (LOCK) {
            screen = null;
            instagramButtons = false;
            theirs = new ArrayList<>();
            listeningOn = null;
            forgetPausedLocked();
        }
        windowForTests = null;
    }
}
