/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ViewWatcher;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * Rounds one kind of full-screen media frame, found by its view id, while its switch is on: a reel's
 * video for {@link ReelCorners}, a story's photo or video for {@link StoryCorners}. Both are part of
 * the Rounded post corners patch.
 *
 * <p>Nothing of Instagram's code changes: while the switch is on, a resumed activity joins the shared
 * {@link ViewWatcher}, which notes that the screen was laid out again and then, before the next frame
 * is drawn, has this look through the window once and round each frame it finds that isn't rounded
 * yet, at the size Corner size picks. A new page is laid out as it comes in, so it's rounded before
 * its first frame shows.
 *
 * <p>With the switch off, HushGram paused or the settings not read, an activity doesn't join, and one
 * already joined puts every frame it rounded back as Instagram had it and leaves at its next frame. A
 * resumed activity decides again, and so does the one under HushGram settings when the switch is
 * flipped, so the switch needs no restart. Frames are held weakly. Anything thrown leaves the screen
 * as Instagram drew it.
 */
final class FrameCorners {
    /** The most views looked at in one search, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = 6000;

    private final String frame;
    private final BooleanSetting setting;
    private final String hook;
    private final String countRounded;
    private final String roundedLine;
    private final RoundedFrames rounded = new RoundedFrames();
    private final ResourceIdCache ids = new ResourceIdCache();
    private final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    private volatile boolean registered;
    private volatile boolean logged;

    /**
     * @param frame the id name of the frame to round
     * @param setting the switch that turns it on
     * @param hook the name the diagnostics report gives it
     * @param countRounded the name of its count of frames rounded
     * @param roundedLine the debug log line written the first time one is rounded
     */
    FrameCorners(String frame, BooleanSetting setting, String hook, String countRounded, String roundedLine) {
        this.frame = frame;
        this.setting = setting;
        this.hook = hook;
        this.countRounded = countRounded;
        this.roundedLine = roundedLine;
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    void install(Context context) {
        try {
            if (registered || !SettingsStatus.roundedPostCorners() || !(context instanceof Application)) return;
            registered = true;
            ViewWatcher.onResumeAndPause((Application) context, this::watch, this::unwatch);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, hook + " start", failure);
        }
    }

    /** Whether frames are rounded now: the switch on, HushGram not paused, the settings read. */
    boolean wanted() {
        return Utils.settingsReady() && setting.get();
    }

    /** Starts watching [activity] if the switch is on, or puts back what was rounded if it's off. */
    void watch(Activity activity) {
        try {
            if (!wanted()) {
                if (!rounded.isEmpty()) rounded.putBack();
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
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, hook, failure);
        }
    }

    /**
     * The switch was flipped in HushGram settings. They open as a dialog over [activity], which stays
     * resumed, so it decides again now rather than at its next resume. Posted, so it reads the value
     * this change saves.
     */
    void switchChanged(@Nullable Activity activity) {
        if (activity != null) Utils.runOnMainThread(() -> watch(activity));
    }

    void unwatch(Activity activity) {
        try {
            Session session;
            synchronized (sessions) {
                session = sessions.remove(activity);
            }
            if (session != null) session.detach();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, hook, failure);
        }
    }

    /** Tests give the frame an id of their own, since a test app has none of Instagram's. */
    void idForTests(Context context, int id) {
        ids.putForTests(context.getPackageName(), frame, id);
    }

    int roundedForTests() {
        return rounded.size();
    }

    boolean listeningForTests(Activity activity) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && session.listening();
        }
    }

    void resetForTests() {
        ids.clear();
        synchronized (sessions) {
            for (Session session : new ArrayList<>(sessions.values())) session.detach();
            sessions.clear();
        }
        rounded.clear();
        logged = false;
    }

    /** One activity's place in the shared frame watcher. */
    private final class Session implements ViewWatcher.Member {
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
            HookStatus.invoked(FamilyNames.ROUNDED_POST_CORNERS);
            ViewWatcher.join(owner, this);
        }

        void detach() {
            Activity owner = activity.get();
            if (owner != null) ViewWatcher.leave(owner, this);
        }

        @Override
        public boolean wanted() {
            return FrameCorners.this.wanted();
        }

        @Override
        public boolean preDraw(Activity owner, boolean laidOut) {
            try {
                if (!wanted()) {
                    rounded.putBack();
                    detach();
                    return false;
                }
                if (laidOut) {
                    int id = ids.resolve(owner.getResources(), owner.getPackageName(), frame, false);
                    if (id == 0) {
                        HookStatus.missingViewId(FamilyNames.ROUNDED_POST_CORNERS, frame);
                        return false;
                    }
                    HookStatus.recoveredViewId(FamilyNames.ROUNDED_POST_CORNERS, frame);
                    float radius = Settings.ROUNDED_CORNER_SIZE.get().dp * owner.getResources().getDisplayMetrics().density;
                    walk(owner.getWindow().getDecorView(), id, radius, new int[] {MAX_VIEWS});
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, hook, failure);
            }
            // The outline is set before this frame is drawn, so the frame already shows it.
            return false;
        }

        private void walk(View view, int id, float radius, int[] budget) {
            if (budget[0]-- <= 0) return;
            if (view.getId() == id) {
                if (rounded.round(view, radius)) {
                    HookStatus.counted(FamilyNames.ROUNDED_POST_CORNERS, countRounded);
                    if (!logged) {
                        logged = true;
                        Logger.printDebug(() -> roundedLine);
                    }
                }
                return;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), id, radius, budget);
            }
        }
    }
}
