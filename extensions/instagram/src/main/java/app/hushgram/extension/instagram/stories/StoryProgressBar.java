/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ViewWatcher;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Thicker story progress bar patch (#360).
 *
 * <p>The story viewer draws the segmented bar along the top of a story in a view of its own,
 * {@link #BAR}, a few pixels tall and centred in its own height, at the top of a header with far more
 * room around it. Stretching that view vertically about its middle draws every segment thicker and
 * leaves the header's name, time and buttons where they were. Nothing of Instagram's code changes:
 * while the switch is on, a resumed activity gets one global-layout listener, which notes that the
 * screen was laid out again, and one pre-draw listener, which then looks through the window once and
 * stretches each bar it finds to the thickness picked, before the frame that shows it. The same view
 * in a direct message's media viewer has another id and stays as it is.
 *
 * <p>With the switch off, HushGram paused or the settings not read, an activity gets no listener, and
 * one already listening puts every bar back as Instagram had it and drops both listeners at its next
 * frame. A resumed activity decides again, and so does the one under HushGram settings when the
 * switch is flipped, so the switch needs no restart. Bars are held weakly. Anything thrown leaves the
 * screen as Instagram drew it.
 */
public final class StoryProgressBar {
    /** The story viewer's segmented progress bar. */
    static final String BAR = "reel_viewer_progress_bar";

    /** The most views looked at in one search, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = 6000;

    static final String HOOK = "story progress bar";
    static final String COUNT_THICKENED = "story progress bars thickened";

    /** Each bar stretched, with the vertical scale Instagram had given it. */
    private static final Map<View, Float> stretched = new WeakHashMap<>();
    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    private static volatile boolean registered;
    private static volatile boolean logged;

    private StoryProgressBar() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        try {
            if (registered || !SettingsStatus.storyProgressBar() || !(context instanceof Application)) return;
            registered = true;
            ViewWatcher.onResumeAndPause((Application) context, StoryProgressBar::watch, StoryProgressBar::unwatch);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_PROGRESS_BAR, HOOK + " start", failure);
        }
    }

    /** Whether bars are thickened now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.THICKER_STORY_BAR.get();
    }

    /** Starts watching [activity] if the switch is on, or puts back what was stretched if it's off. */
    static void watch(Activity activity) {
        try {
            if (!wanted()) {
                putBack();
                return;
            }
            Session session;
            synchronized (sessions) {
                session = sessions.get(activity);
                if (session == null) {
                    session = new Session(activity);
                    sessions.put(activity, session);
                }
            }
            session.attach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_PROGRESS_BAR, HOOK, failure);
        }
    }

    /**
     * The switch was flipped in HushGram settings. They open as a dialog over [activity], which stays
     * resumed, so it decides again now rather than at its next resume. Posted, so it reads the value
     * this change saves.
     */
    public static void switchChanged(@Nullable Activity activity) {
        if (activity != null) Utils.runOnMainThread(() -> watch(activity));
    }

    static void unwatch(Activity activity) {
        try {
            Session session;
            synchronized (sessions) {
                session = sessions.remove(activity);
            }
            if (session != null) session.detach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_PROGRESS_BAR, HOOK, failure);
        }
    }

    /** Stretches [bar] to [scale] times the scale Instagram gave it. True the first time it's stretched. */
    private static boolean stretch(View bar, float scale) {
        Float original = stretched.get(bar);
        boolean first = original == null;
        if (first) {
            original = bar.getScaleY();
            stretched.put(bar, original);
        }
        float wanted = original * scale;
        if (bar.getScaleY() != wanted) bar.setScaleY(wanted);
        return first;
    }

    /** Gives every bar stretched back the scale Instagram had given it. */
    private static void putBack() {
        if (stretched.isEmpty()) return;
        for (Map.Entry<View, Float> entry : new ArrayList<>(stretched.entrySet())) {
            View bar = entry.getKey();
            if (bar != null) bar.setScaleY(entry.getValue());
        }
        stretched.clear();
    }

    /** Tests give the bar an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), BAR, id);
    }

    static int stretchedForTests() {
        return stretched.size();
    }

    public static boolean listeningForTests(Activity activity) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && session.listening();
        }
    }

    public static void resetForTests() {
        ids.clear();
        synchronized (sessions) {
            for (Session session : new ArrayList<>(sessions.values())) session.detach();
            sessions.clear();
        }
        stretched.clear();
        logged = false;
    }

    /** One activity's place in the shared frame watcher. */
    private static final class Session implements ViewWatcher.Member {
        private final WeakReference<Activity> activity;

        Session(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        boolean listening() {
            Activity owner = activity.get();
            return owner != null && ViewWatcher.listening(owner, this);
        }

        void attach() {
            Activity owner = activity.get();
            if (owner == null || ViewWatcher.joined(owner, this)) return;
            HookStatus.invoked(FamilyNames.STORY_PROGRESS_BAR);
            ViewWatcher.join(owner, this);
        }

        void detach() {
            Activity owner = activity.get();
            if (owner != null) ViewWatcher.leave(owner, this);
        }

        @Override
        public boolean wanted() {
            return StoryProgressBar.wanted();
        }

        @Override
        public boolean preDraw(Activity owner, boolean laidOut) {
            try {
                if (!wanted()) {
                    putBack();
                    detach();
                    return false;
                }
                if (laidOut) {
                    int id = ids.resolve(owner.getResources(), owner.getPackageName(), BAR, false);
                    if (id == 0) {
                        HookStatus.missingViewId(FamilyNames.STORY_PROGRESS_BAR, BAR);
                        return false;
                    }
                    HookStatus.recoveredViewId(FamilyNames.STORY_PROGRESS_BAR, BAR);
                    walk(owner.getWindow().getDecorView(), id, Settings.STORY_BAR_THICKNESS.get().scale,
                            new int[] {MAX_VIEWS});
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_PROGRESS_BAR, HOOK, failure);
            }
            // The scale is set before this frame is drawn, so the frame already shows it.
            return false;
        }

        private void walk(View view, int id, float scale, int[] budget) {
            if (budget[0]-- <= 0) return;
            if (view.getId() == id) {
                if (stretch(view, scale)) {
                    HookStatus.counted(FamilyNames.STORY_PROGRESS_BAR, COUNT_THICKENED);
                    if (!logged) {
                        logged = true;
                        Logger.printDebug(() -> "Thicker story progress bar: stretched a story's progress bar");
                    }
                }
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), id, scale, budget);
            }
        }
    }
}
