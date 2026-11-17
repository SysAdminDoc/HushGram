/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import kotlin.jvm.functions.Function1;

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
 *
 * <p>Drawing smaller isn't enough on its own (#676). Instagram puts in only the buttons whose
 * unscaled place is on screen, so a Like laid out above the top of the screen stayed out of the
 * shrunk column, which showed its count under an empty spot. So a column found too tall also sets
 * how much of their own size HushGram's icons (and the room under each) are laid out at from then
 * on, just enough that the next columns fit as laid out, never under {@link #LEAST_SHARE}. That share
 * is kept in HushGram's own store and starts over at full size when the number of icons switched on
 * or their size changes.
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
    /** The most reel viewers fitted in one pass. */
    private static final int MAX_VIEWERS = 4;
    /** The least share of their own size HushGram's icons are laid out at in a column too tall. */
    static final float LEAST_SHARE = 0.6f;
    /** Extra room taken off with each share learned, in dp, so a column lands inside the limit. */
    static final int SLACK_DP = 2;
    /** HushGram's own store for the learned share. Only this phone's Instagram reads it. */
    static final String STORE = "hushgram_reel_column";
    static final String KEY_SHARE = "share";
    static final String KEY_ICONS = "icons";
    static final String KEY_SIZE = "size";
    /** The top 16 bits Litho gives a size in pixels, and one in scaled pixels. */
    static final long PX_BITS = 0x7ff9000000000000L;
    static final long SP_BITS = 0x7ffa000000000000L;
    private static final long KIND_BITS = 0xFFFF000000000000L;

    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** The columns drawn smaller, so only those are put back. */
    private static final WeakHashMap<View, Float> shrunk = new WeakHashMap<>();
    /** Columns already read for the share, with the height each had then, so one is read once. */
    private static final WeakHashMap<View, Integer> read = new WeakHashMap<>();
    private static final Object lock = new Object();
    private static volatile boolean registered;
    /** The share of their own size the icons are laid out at: 1 until a column is too tall. */
    private static volatile float share = 1f;
    /** How many icons were on, and the pixels one icon took, when {@link #share} was set. */
    private static volatile int shareIcons = -1;
    private static volatile int shareSize = -1;
    /** The most pixels one icon and the room under it take at full size, as last laid out. */
    private static volatile int pitch;
    private static volatile boolean loaded;

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
                || ReelSoundButton.on() || ReelNotInterestedButton.on() || ReelBlockButton.on() || ReelMuteButton.on();
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

    /** How many of the icons HushGram adds to the column are switched on. Paused answers none. */
    static int iconsOn() {
        boolean[] on = {ReelDownloadButton.iconOn(), ReelFullScreenButton.on(), ReelSpeedButton.on(), ReelTextButton.on(),
                ReelSoundButton.on(), ReelNotInterestedButton.on(), ReelBlockButton.on(), ReelMuteButton.on()};
        int count = 0;
        for (boolean each : on) if (each) count++;
        return count;
    }

    /**
     * One of HushGram's icons for the column, made by the bridge with [size] and the room [margin]
     * under it laid out at the share the column has learned it needs (#676). Every icon HushGram adds
     * to the column comes through here.
     */
    static Object icon(Drawable drawable, CharSequence description, Function1<Object, Object> click, long size, long margin) {
        float now = shareFor(size, margin);
        return ReelDownloadButton.bridge.icon(drawable, description, click, scaled(size, now), scaled(margin, now));
    }

    /**
     * The share to lay out an icon of [size] over [margin] at, noting the pixels it takes at full
     * size. A share learned for another number of icons or another icon size starts over at 1.
     * Never throws.
     */
    static float shareFor(long size, long margin) {
        try {
            Context context = Utils.getContext();
            if (context == null) return share;
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int sizePx = pixels(size, metrics);
            int icons = iconsOn();
            synchronized (lock) {
                load(context);
                if (icons != shareIcons || sizePx != shareSize) {
                    boolean was = share < 1f;
                    share = 1f;
                    shareIcons = icons;
                    shareSize = sizePx;
                    pitch = 0;
                    if (was) save(context);
                }
                int taken = sizePx + Math.max(0, pixels(margin, metrics));
                if (taken > pitch) pitch = taken;
                return share;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, HOOK + " share", failure);
            return 1f;
        }
    }

    /**
     * Litho's [dimen] at [share] of itself: pixels, scaled pixels and dp each kept in their own
     * kind. None, and any size Litho reads some other way, stay as they are.
     */
    static long scaled(long dimen, float share) {
        if (share >= 1f || dimen == 0) return dimen;
        long kind = dimen & KIND_BITS;
        if (kind == PX_BITS) return PX_BITS | (Math.round((int) dimen * share) & 0xFFFFFFFFL);
        if (kind == SP_BITS) return SP_BITS | (Float.floatToRawIntBits(Float.intBitsToFloat((int) dimen) * share) & 0xFFFFFFFFL);
        double dp = Double.longBitsToDouble(dimen);
        if (Double.isNaN(dp)) return dimen;
        return Double.doubleToRawLongBits(dp * share);
    }

    /** The pixels Litho's [dimen] comes to, or 0 for none and any size read some other way. */
    static int pixels(long dimen, DisplayMetrics metrics) {
        if (dimen == 0) return 0;
        long kind = dimen & KIND_BITS;
        if (kind == PX_BITS) return (int) dimen;
        if (kind == SP_BITS) return Math.round(Float.intBitsToFloat((int) dimen) * metrics.scaledDensity);
        double dp = Double.longBitsToDouble(dimen);
        if (Double.isNaN(dp)) return 0;
        return (int) Math.round(dp * metrics.density);
    }

    /**
     * Lowers the share by what a column [over] pixels too tall needs, spread over the icons switched
     * on, never under {@link #LEAST_SHARE}. Answers whether it changed.
     */
    static boolean learn(Context context, int over) {
        if (over <= 0) return false;
        synchronized (lock) {
            load(context);
            int icons = shareIcons;
            int each = pitch;
            if (icons <= 0 || each <= 0 || share <= LEAST_SHARE) return false;
            int slack = Math.round(SLACK_DP * context.getResources().getDisplayMetrics().density);
            float next = Math.max(LEAST_SHARE, share - (over + slack) / (float) (icons * each));
            if (next >= share) return false;
            share = next;
            save(context);
            return true;
        }
    }

    private static void load(Context context) {
        if (loaded) return;
        loaded = true;
        SharedPreferences store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        float saved = store.getFloat(KEY_SHARE, 1f);
        if (!(saved >= LEAST_SHARE && saved < 1f)) return;
        share = saved;
        shareIcons = store.getInt(KEY_ICONS, -1);
        shareSize = store.getInt(KEY_SIZE, -1);
    }

    private static void save(Context context) {
        SharedPreferences.Editor editor = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit();
        if (share >= 1f) editor.clear();
        else editor.putFloat(KEY_SHARE, share).putInt(KEY_ICONS, shareIcons).putInt(KEY_SIZE, shareSize);
        editor.apply();
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

    /**
     * Fits every reel's column in [owner]'s window. Answers whether one changed size.
     *
     * <p>Instagram keeps the Reels tab built while Explore or search shows a reel viewer of its own,
     * with the same ids, so the first pager in the window is often the hidden tab's. Every viewer on
     * screen is fitted, each under its own top bar.
     */
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
        List<View> pagers = new ArrayList<>();
        shownWithId(decor, pagerId, null, pagers, MAX_VIEWERS);
        boolean changed = false;
        for (View pager : pagers) {
            if (pager instanceof ViewGroup && pager.isShown()) changed |= fitViewer(owner, decor, (ViewGroup) pager, columnId, barId);
        }
        return changed;
    }

    /**
     * Adds to [found] each view under [root] with [id] that is shown, until there are [most]. Hidden
     * branches, [skip] and whatever is under a view found are passed over.
     */
    static void shownWithId(View root, int id, @Nullable View skip, List<View> found, int most) {
        if (found.size() >= most || root == skip || root.getVisibility() != View.VISIBLE) return;
        if (root.getId() == id) {
            found.add(root);
            return;
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) shownWithId(group.getChildAt(i), id, skip, found, most);
    }

    /**
     * The top bar shown nearest [pager]: the shallowest one under the closest of its parents that
     * holds one, so a viewer never reads a bar from another screen. Each view is looked at once at
     * most.
     */
    @Nullable
    static View barFor(View pager, int barId) {
        if (barId == 0) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        View below = pager;
        ViewParent parent = pager.getParent();
        while (parent instanceof ViewGroup) {
            queue.clear();
            queue.add((View) parent);
            while (!queue.isEmpty()) {
                View view = queue.poll();
                if (view == below || view.getVisibility() != View.VISIBLE) continue;
                if (view.getId() == barId) return view;
                if (!(view instanceof ViewGroup)) continue;
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
            below = (View) parent;
            parent = parent.getParent();
        }
        return null;
    }

    /** Fits the columns in one reel viewer's [pager]. Answers whether one changed size. */
    private static boolean fitViewer(Activity owner, View decor, ViewGroup pager, int columnId, int barId) {
        ViewGroup pages = pagesOf(pager);
        int[] at = new int[2];
        pages.getLocationOnScreen(at);
        int pagesTop = at[1];
        int limit = Math.max(pagesTop, topInset(decor));
        View bar = barFor(pager, barId);
        if (bar != null && bar.isShown() && bar.getAlpha() > 0f && bar.getHeight() > 0) {
            bar.getLocationOnScreen(at);
            limit = Math.max(limit, at[1] + bar.getHeight());
        }
        limit += Math.round(GAP_DP * owner.getResources().getDisplayMetrics().density);
        List<View> columns = new ArrayList<>();
        List<Integer> tops = new ArrayList<>();
        int count = Math.min(pages.getChildCount(), MAX_PAGES);
        for (int i = 0; i < count; i++) {
            View page = pages.getChildAt(i);
            View column = page.findViewById(columnId);
            if (column == null || column == page || column.getVisibility() != View.VISIBLE || column.getHeight() <= 0) continue;
            columns.add(column);
            tops.add(pagesTop + settledTop(column, page));
        }
        learnFrom(owner, columns, tops, limit);
        boolean changed = false;
        for (int i = 0; i < columns.size(); i++) {
            View column = columns.get(i);
            int top = tops.get(i);
            changed |= scale(column, scaleFor(top, top + column.getHeight(), limit));
        }
        return changed;
    }

    /**
     * Lowers the share by what the tallest new column over [limit] needs. Every column there now was
     * laid out at the share before, so each is noted with its height and read again only once it's
     * laid out anew.
     */
    private static void learnFrom(Context context, List<View> columns, List<Integer> tops, int limit) {
        int most = 0;
        synchronized (lock) {
            for (int i = 0; i < columns.size(); i++) {
                View column = columns.get(i);
                Integer height = read.get(column);
                if (height != null && height == column.getHeight()) continue;
                most = Math.max(most, limit - tops.get(i));
            }
            if (most <= 0) return;
            for (View column : columns) read.put(column, column.getHeight());
        }
        learn(context, most);
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
        return ids.resolve(activity.getResources(), Utils.resourcePackage(activity), name, false);
    }

    /** Tests give a view an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, String name, int id) {
        ids.putForTests(Utils.resourcePackage(context), name, id);
    }

    static boolean listeningForTests(Activity activity) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && ViewWatcher.listening(activity, session);
        }
    }

    static float shareForTests() {
        return share;
    }

    static void resetForTests() {
        ids.clear();
        putBack();
        synchronized (lock) {
            share = 1f;
            shareIcons = -1;
            shareSize = -1;
            pitch = 0;
            loaded = false;
            read.clear();
        }
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
