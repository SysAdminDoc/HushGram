/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ViewWatcher;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Keeps the column of buttons beside a reel under the Reels top bar (#516).
 *
 * <p>Each icon HushGram adds to that column (Download, Full screen, Speed, Text, Sound, Not
 * interested, Block) makes it taller, and Instagram grows the column upward from the seek bar. With
 * most of them on, the column ran up past the top bar and off the screen, and Like was the button
 * cut off. Before a frame drawn after a layout, every reel page in the viewer is looked at, and a
 * column that would reach above the top bar's bottom edge (or the status bar, with the top bar
 * hidden) is drawn smaller, scaled from its bottom edge so it still ends over the seek bar, just
 * enough to fit. Android sends a touch through the same scale, so each icon still takes its own
 * taps. A column that fits is left at its own size, and one shrunk goes back as soon as it fits,
 * while HushGram is paused, and with none of the added icons on.
 *
 * <p>Where a column fits is read as if its reel had settled in the viewer: from the column's place
 * in its own page, never from where a swipe has it now, so a reel sliding in already has its final
 * size. Anything thrown leaves the column as Instagram drew it.
 */
public final class ReelColumnFit {
    /** The reel's button column. */
    static final String COLUMN = "clips_ufi_component";
    /** The Reels viewer's pager, which holds one page per reel. */
    static final String PAGER = "clips_viewer_view_pager";
    /** The Reels top bar: Reels, Friends and the camera. */
    static final String TOP_BAR = "clips_viewer_action_bar";
    static final String HOOK = "reel column fit";
    static final String COUNT_SHRUNK = "reel button column shrunk to fit";
    /** Room kept between the top bar and the column's first button, in dp. */
    static final int GAP_DP = 8;
    /** The smallest the column is drawn. Past this it would be too small to tap, so it stays at this. */
    static final float SMALLEST = 0.5f;
    /** The most views looked at for columns in one pass. */
    private static final int MAX_PAGES = 12;

    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** The columns drawn smaller, so only those are put back. */
    private static final WeakHashMap<View, Float> shrunk = new WeakHashMap<>();
    private static volatile boolean registered;

    private ReelColumnFit() {
    }

    /** Called as the application starts. Does nothing in a build without Download any reel. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.reelDownload()) return;
            registered = true;
            ViewWatcher.onResumeAndPause((Application) context, ReelColumnFit::watch, ReelColumnFit::unwatch);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, HOOK + " start", failure);
        }
    }

    /** Whether any icon HushGram adds to the column is on. Paused answers no. Never throws. */
    static boolean anyIconOn() {
        return ReelDownloadButton.iconOn() || ReelFullScreenButton.on() || ReelSpeedButton.on() || ReelTextButton.on()
                || ReelSoundButton.on() || ReelNotInterestedButton.on() || ReelBlockButton.on();
    }

    /** Whether columns are fitted now: the settings read, HushGram not paused, an added icon on. */
    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && anyIconOn();
    }

    /**
     * The scale that fits a column whose settled top and bottom on screen are [top] and [bottom] under
     * [limit], the highest it may reach: 1 when it fits, never under {@link #SMALLEST}.
     */
    static float scaleFor(int top, int bottom, int limit) {
        int height = bottom - top;
        if (height <= 0 || top >= limit) return 1f;
        float scale = (bottom - limit) / (float) height;
        if (scale >= 1f) return 1f;
        return Math.max(SMALLEST, scale);
    }

    /** Starts watching [activity]. Called as an activity resumes. */
    static void watch(Activity activity) {
        try {
            if (!Utils.settingsReady()) return;
            Session session;
            synchronized (sessions) {
                session = sessions.get(activity);
                if (session == null) {
                    session = new Session();
                    sessions.put(activity, session);
                }
            }
            if (ViewWatcher.joined(activity, session)) return;
            HookStatus.invoked(FamilyNames.REEL_DOWNLOAD);
            ViewWatcher.join(activity, session);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, HOOK, failure);
        }
    }

    static void unwatch(Activity activity) {
        try {
            Session session;
            synchronized (sessions) {
                session = sessions.remove(activity);
            }
            if (session != null) ViewWatcher.leave(activity, session);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, HOOK, failure);
        }
    }

    /** Puts every column drawn smaller back at its own size. Answers whether one changed. */
    static boolean putBack() {
        boolean changed = false;
        for (View column : new ArrayList<>(shrunk.keySet())) {
            if (column == null) continue;
            column.setScaleX(1f);
            column.setScaleY(1f);
            changed = true;
        }
        shrunk.clear();
        return changed;
    }

    /** Fits every reel's column in [owner]'s window. Answers whether one changed size. */
    static boolean fit(Activity owner) {
        View decor = owner.getWindow().getDecorView();
        int pagerId = id(owner, PAGER);
        int columnId = id(owner, COLUMN);
        int barId = id(owner, TOP_BAR);
        if (pagerId == 0 || columnId == 0) {
            if (pagerId == 0) HookStatus.missingViewId(FamilyNames.REEL_DOWNLOAD, PAGER);
            if (columnId == 0) HookStatus.missingViewId(FamilyNames.REEL_DOWNLOAD, COLUMN);
            return false;
        }
        View pager = decor.findViewById(pagerId);
        if (!(pager instanceof ViewGroup) || !pager.isShown()) return false;
        ViewGroup pages = pagesOf((ViewGroup) pager);
        int[] at = new int[2];
        pages.getLocationOnScreen(at);
        int pagesTop = at[1];
        int limit = Math.max(pagesTop, topInset(decor));
        View bar = barId == 0 ? null : decor.findViewById(barId);
        if (bar != null && bar.isShown() && bar.getAlpha() > 0f && bar.getHeight() > 0) {
            bar.getLocationOnScreen(at);
            limit = Math.max(limit, at[1] + bar.getHeight());
        }
        limit += Math.round(GAP_DP * owner.getResources().getDisplayMetrics().density);
        boolean changed = false;
        int count = Math.min(pages.getChildCount(), MAX_PAGES);
        for (int i = 0; i < count; i++) {
            View page = pages.getChildAt(i);
            View column = page.findViewById(columnId);
            if (column == null || column == page || column.getVisibility() != View.VISIBLE || column.getHeight() <= 0) continue;
            int top = pagesTop + settledTop(column, page);
            changed |= scale(column, scaleFor(top, top + column.getHeight(), limit));
        }
        return changed;
    }

    /**
     * The view whose children are the reels' pages: the pager's one child when that child is a list of
     * its own (Instagram's pager holds a list), otherwise the pager itself.
     */
    static ViewGroup pagesOf(ViewGroup pager) {
        if (pager.getChildCount() == 1 && pager.getChildAt(0) instanceof ViewGroup) return (ViewGroup) pager.getChildAt(0);
        return pager;
    }

    /** How far [column] sits below the top of [page], whatever a swipe has done to the page. */
    static int settledTop(View column, View page) {
        int top = 0;
        View view = column;
        while (view != null && view != page) {
            top += view.getTop();
            if (!(view.getParent() instanceof View)) break;
            view = (View) view.getParent();
        }
        return top;
    }

    private static boolean scale(View column, float wanted) {
        Float now = shrunk.get(column);
        if (wanted >= 1f) {
            if (now == null) return false;
            shrunk.remove(column);
            column.setScaleX(1f);
            column.setScaleY(1f);
            return true;
        }
        column.setPivotX(column.getWidth() / 2f);
        column.setPivotY(column.getHeight());
        if (now != null && Math.abs(now - wanted) < 0.005f && column.getScaleY() == now) return false;
        shrunk.put(column, wanted);
        column.setScaleX(wanted);
        column.setScaleY(wanted);
        HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_SHRUNK);
        return true;
    }

    @SuppressWarnings("deprecation")
    private static int topInset(View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        return insets == null ? 0 : insets.getSystemWindowInsetTop();
    }

    private static int id(Activity activity, String name) {
        return ids.resolve(activity.getResources(), activity.getPackageName(), name, false);
    }

    /** Tests give a view an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, String name, int id) {
        ids.putForTests(context.getPackageName(), name, id);
    }

    static boolean listeningForTests(Activity activity) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && ViewWatcher.listening(activity, session);
        }
    }

    static void resetForTests() {
        ids.clear();
        putBack();
        synchronized (sessions) {
            for (Map.Entry<Activity, Session> each : new ArrayList<>(sessions.entrySet())) {
                if (each.getKey() != null) ViewWatcher.leave(each.getKey(), each.getValue());
            }
            sessions.clear();
        }
    }

    /** One activity's place in the shared frame watcher. */
    private static final class Session implements ViewWatcher.Member {
        @Override
        public boolean wanted() {
            return Utils.settingsReady();
        }

        @Override
        public boolean preDraw(Activity owner, boolean laidOut) {
            try {
                if (!ReelColumnFit.wanted()) return !shrunk.isEmpty() && putBack();
                return laidOut && fit(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, HOOK, failure);
                return false;
            }
        }
    }
}
