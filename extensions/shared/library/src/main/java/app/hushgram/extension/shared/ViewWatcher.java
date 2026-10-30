/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * One global-layout listener and one pre-draw listener for each activity, shared by the helpers
 * that change Instagram's views just before a frame without changing its code (the view hider,
 * Rounded corners on Reels, Peek at the next carousel page, Thicker story progress bar).
 *
 * <p>A helper joins an activity with a {@link Member} while its switch is on. The activity's
 * listeners go on with the first member and come off with the last, so with every helper on, the
 * window still has one of each. A layout marks every member's window as laid out again, and before
 * each frame every member is asked in the order it joined, with whether a layout came since it last
 * looked. A member leaves on its own, from its pre-draw too. A decor view that isn't attached yet
 * is waited for, since an observer taken from it would be a stand-in thrown away on attach.
 * Main thread only, like the lifecycle callbacks and frames that drive it.
 */
public final class ViewWatcher {
    /** What a helper does in an activity's frames. */
    public interface Member {
        /** Whether the member still wants the frames, asked as a waited-for window attaches. */
        boolean wanted();

        /**
         * Before a frame of [owner]'s window. [laidOut] is true the first time and whenever the window
         * was laid out since this member last looked. Answers whether the frame should be drawn again
         * because the member changed what it shows. Catches what it throws.
         */
        boolean preDraw(Activity owner, boolean laidOut);
    }

    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();

    private ViewWatcher() {
    }

    /** Calls [resumed] and [paused] as each of the application's activities resumes and pauses. */
    public static void onResumeAndPause(Application application, Consumer<Activity> resumed, Consumer<Activity> paused) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(@NonNull Activity activity) {
                resumed.accept(activity);
            }

            @Override public void onActivityPaused(@NonNull Activity activity) {
                paused.accept(activity);
            }

            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityStopped(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }
        });
    }

    /** Adds [member] to [activity]'s frames, from the next one. Does nothing if it's in already. */
    public static void join(Activity activity, Member member) {
        Session session;
        synchronized (sessions) {
            session = sessions.get(activity);
            if (session == null) {
                session = new Session(activity);
                sessions.put(activity, session);
            }
        }
        session.add(member);
    }

    /** Takes [member] out of [activity]'s frames, and the listeners off with the last member. */
    public static void leave(Activity activity, Member member) {
        Session session;
        synchronized (sessions) {
            session = sessions.get(activity);
        }
        if (session != null && session.remove(member)) {
            synchronized (sessions) {
                if (sessions.get(activity) == session) sessions.remove(activity);
            }
        }
    }

    /** Whether [member] is in [activity]'s frames, waiting for its window or not. */
    public static boolean joined(Activity activity, Member member) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && session.members.containsKey(member);
        }
    }

    /** Whether [member] is in [activity]'s frames and the window's listeners are on. */
    public static boolean listening(Activity activity, Member member) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && session.observer != null && session.members.containsKey(member);
        }
    }

    /** One activity's listeners and its members, each with whether a layout came since it looked. */
    private static final class Session implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final WeakReference<Activity> activity;
        private final Map<Member, Boolean> members = new LinkedHashMap<>();
        @Nullable
        private ViewTreeObserver observer;
        private boolean waiting;

        Session(Activity activity) {
            this.activity = new WeakReference<>(activity);
        }

        void add(Member member) {
            Activity owner = activity.get();
            if (owner == null || members.containsKey(member)) return;
            members.put(member, Boolean.TRUE);
            View decor = owner.getWindow().getDecorView();
            if (observer != null) {
                decor.invalidate();
            } else if (!waiting) {
                if (decor.isAttachedToWindow()) {
                    listen(decor);
                } else {
                    waiting = true;
                    decor.addOnAttachStateChangeListener(this);
                }
            }
        }

        /** Answers true when [member] was the last, and the listeners are off. */
        boolean remove(Member member) {
            if (members.remove(member) == null || !members.isEmpty()) return false;
            ViewTreeObserver tree = observer;
            observer = null;
            if (tree != null && tree.isAlive()) {
                tree.removeOnGlobalLayoutListener(this);
                tree.removeOnPreDrawListener(this);
            }
            Activity owner = activity.get();
            if (waiting && owner != null) owner.getWindow().getDecorView().removeOnAttachStateChangeListener(this);
            waiting = false;
            return true;
        }

        private void listen(View decor) {
            ViewTreeObserver tree = decor.getViewTreeObserver();
            tree.addOnGlobalLayoutListener(this);
            tree.addOnPreDrawListener(this);
            observer = tree;
            decor.invalidate();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (!waiting) return;
            waiting = false;
            view.removeOnAttachStateChangeListener(this);
            Activity owner = activity.get();
            for (Member member : new ArrayList<>(members.keySet())) {
                if (!member.wanted() && owner != null) leave(owner, member);
            }
            if (observer == null && !members.isEmpty()) listen(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
        }

        @Override
        public void onGlobalLayout() {
            for (Map.Entry<Member, Boolean> each : members.entrySet()) each.setValue(Boolean.TRUE);
        }

        @Override
        public boolean onPreDraw() {
            Activity owner = activity.get();
            boolean changed = false;
            // A copy, since a member may leave in its own pre-draw.
            for (Member member : new ArrayList<>(members.keySet())) {
                Boolean laidOut = members.get(member);
                if (laidOut == null) continue;
                members.put(member, Boolean.FALSE);
                if (owner == null) {
                    remove(member);
                } else {
                    changed |= member.preDraw(owner, laidOut);
                }
            }
            return !changed;
        }
    }
}
