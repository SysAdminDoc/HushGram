/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.download.FeedDownloadButton;
import app.hushgram.extension.instagram.feed.GlassSeekBar;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Burn-in guard (#162), Hushfeed's ported to the Reels screen: keeps a reel's fixed controls from
 * sitting on the same pixels of an OLED screen for hours.
 *
 * <p>The dim: once the Reels screen has gone the Dim after choice ({@link BurnInWait}) without a
 * touch, the reel's overlay (its button column, author and caption), its seek bar, the Interested row
 * under some reels (the views without an id beside the button column), the Add comment bar, the top
 * bar and the tab bar are drawn at {@link #IDLE_LEVEL} of what Instagram draws them at, and the next touch brings them
 * back. Only their alpha changes, so a dimmed button still takes its tap. They're found by the ids
 * Instagram 450 gives them ({@link #DIMMED_IDS}), and a view inside one already dimmed is left alone,
 * so nothing is dimmed twice. A finger held on the screen counts as a touch until it lifts.
 *
 * <p>What a touch is: a finger going down on one of the reel's controls ({@link #WAKING_IDS}), on
 * anything that isn't the reel, or moving as a swipe does. A tap on the reel itself (to pause or play,
 * a double tap to like) and a tap on Save or HushGram's Download aren't, so they leave the controls
 * dimmed and the wait running (#162). With Stay dim when I tap on ({@link #stayDim}), no finger on the
 * Reels screen is a touch, swipes to the next reel included: the wait runs from arriving on Reels, and
 * once dimmed the controls stay dimmed until you leave it (#162).
 *
 * <p>The shift moves the window's content around a small square, {@link #STEP_DP} at a time, every
 * {@link #SHIFT_EVERY_MS}. It's one view's translation, the content frame's, which Instagram doesn't
 * animate, where each control's would be fought over with Instagram's own animations. The strips a step
 * leaves bare are painted black over the decor ({@link Gap}), since the window under the content takes
 * Instagram's theme and showed white there in light mode (#669).
 *
 * <p>Stories (#479), with Dim stories too on ({@link #stories}): while the story viewer fills the
 * window with no reel drawn over it, its progress bar, header (with HushGram's eye, Download and speed
 * buttons) and reply bar or own-story toolbar dim the same way ({@link #STORY_DIMMED_IDS}), and
 * HushGram's seek bar beside the viewer with them. Every finger on a story is a touch, since a tap
 * there moves to the next story or back, and typing a reply keeps them bright. Stay dim when I tap and
 * Light when paused stay with Reels. The shift moves a story too: it's the same window.
 *
 * <p>Both run only while the Reels screen ({@link FullScreenBars#screenOf}) or a story shows, with the
 * choice on, HushGram not paused and the activity in front. Anywhere else nothing dims and the content goes back
 * where it was. A view whose alpha, or a content frame whose translation, something else changed
 * after HushGram wrote it is left the way that left it. The window getting focus back counts as a
 * touch, so coming back from the settings or another screen doesn't land on dimmed controls.
 *
 * <p>No layout listener runs for nothing: with the choice off an activity gets only a window focus
 * listener, which hears the choice turned on once the settings close. While it's on, a layout
 * (a new reel, another screen) asks for a look at most every {@link #LOOK_MS}, and while the controls
 * are dimmed a look comes every {@link #KEEP_MS} to dim what Instagram brought back on its own. A draw
 * listener dims a dimmed view again before the frame that would show Instagram's alpha ({@link #redim}),
 * and a layout while dimmed dims what it brought in at once, so a reel scrolled to is dimmed before
 * its first frame.
 */
public final class BurnInGuard {
    /** The share of Instagram's own opacity the controls dim to. */
    static final float IDLE_LEVEL = 0.25f;
    /** How long the content stays in one place. */
    static final long SHIFT_EVERY_MS = 120_000L;
    static final float STEP_DP = 2f;
    /** The most often a layout asks for a look. */
    static final long LOOK_MS = 250L;
    /**
     * How often a pass looks again while the controls are dimmed: Instagram puts a control's alpha
     * back on its own at times, with no layout to say so (the button column after a tap that pauses or
     * mutes the reel, or as a reel starts over, seen on 451). A look that finds nothing changed writes nothing.
     */
    static final long KEEP_MS = 1_000L;
    /** The most views looked at in one search, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = 4000;

    /**
     * The ids of what dims, as Instagram 450 and 451 name them: a reel's overlay and its parts (the
     * button column, the author and caption), the Reels top bar and the tab bar, and outside the
     * overlay the reel's seek bar ({@code scrubber}, 451; HushGram's time label fades with it, see
     * {@link ReelTimeLabel}) (#162), and the Add comment bar under a reel, in the footer below the
     * overlay ({@code comment_composer_inner_layout}, 454, #648). The comment sheet's own box has other ids.
     */
    static final String[] DIMMED_IDS = {
            "clips_item_overlay_component", "clips_ufi_component", "clips_media_info_component",
            "clips_viewer_media_info_container", "clips_viewer_media_info", "clips_viewer_action_bar", "tab_bar",
            "scrubber", "comment_composer_inner_layout"
    };

    /**
     * The reel's button column. On 451 the row of Not interested and Interested buttons under some
     * reels sits beside it with no id of its own (#162), so the views beside the column that have no
     * id dim with it.
     */
    static final String COLUMN_ID = "clips_ufi_component";

    /**
     * What a finger landing on counts as a touch even on the reel (#162): the button column, the
     * author and caption, the seek bar and the strip under the reel that takes its drags (451), the
     * top bar, the tab bar and the Add comment bar (#648). The overlay holding them isn't one, since its
     * empty parts are the reel.
     */
    static final String[] WAKING_IDS = {
            "clips_ufi_component", "clips_media_info_component", "clips_viewer_media_info_container",
            "clips_viewer_media_info", "clips_viewer_action_bar", "tab_bar", "scrubber", "clips_expanded_touch_view",
            "comment_composer_inner_layout"
    };

    /** Save and its count, in the button column, which a finger landing on doesn't count as a touch (#162). */
    static final String[] QUIET_IDS = {"save_button", "save_count"};

    /** The reels' pager: a finger landing in it, on none of {@link #WAKING_IDS}, is on the reel itself. */
    static final String REEL_ID = "clips_viewer_view_pager";

    /**
     * The story viewer's root (#479), which Instagram puts in the main screen's container of screens,
     * so the same window as Reels. Everything dimmed on a story is inside it.
     */
    static final String STORY_ID = "reel_viewer_root";

    /**
     * The ids of what dims on a story, as Instagram 453 names them (#479): the progress bar, the header
     * (picture, name, time and the row of buttons at its end, where HushGram's eye, Download and speed
     * buttons go), and at the bottom the Send message bar or your own story's toolbar. A view inside
     * one already dimmed is left alone, as on Reels.
     */
    static final String[] STORY_DIMMED_IDS = {
            "reel_viewer_progress_bar", "reel_viewer_header_container", "reel_viewer_header",
            "reel_header_extras_container", "reel_viewer_message_composer_container", "reel_item_toolbar_container",
            "viewer_reel_item_toolbar_container", "self_reel_item_toolbar_container",
            "reel_item_toolbar_footer_container"
    };

    /** What the guard looks after on the screen in front: nothing, the Reels screen, or a story (#479). */
    enum Guarded { NONE, REELS, STORY }

    /** Where the content goes, in steps: where Instagram put it, then once around it. */
    private static final int[][] PLACES = {
            {0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    static final String COUNT_DIMMED = "Reels controls dimmed while idle";
    static final String COUNT_SHIFTED = "Reels screen moved a step";
    static final String COUNT_QUIET = "Reels tap on the video, Save or Download left the controls dimmed";
    static final String COUNT_PAUSED = "Reels controls kept bright while the reel is paused";
    static final String COUNT_STAY_DIM = "Reels touch left the controls dimmed (Stay dim when I tap)";
    static final String COUNT_STORY_DIMMED = "Story controls dimmed while idle";

    private static final HashMap<String, Integer> IDS = new HashMap<>();
    private static volatile boolean registered;
    private static final Map<Window, Boolean> WRAPPED = new WeakHashMap<>();
    /** Views this class dimmed: the alpha they had, then the alpha written here. */
    private static final Map<View, float[]> DIMMED = new WeakHashMap<>();
    /** Content views this class moved: the [x, y] they had, then the [x, y] written here. */
    private static final Map<View, float[]> MOVED = new WeakHashMap<>();
    /** Decor views whose overlay holds the black over what the shift left bare (#669). */
    private static final Map<View, Gap> GAPS = new WeakHashMap<>();
    private static final Runnable TICK = BurnInGuard::tick;
    private static final Focus FOCUS = new Focus();
    private static final Layout LAYOUT = new Layout();
    private static final Draw DRAW = new Draw();

    private static Handler handler;
    /** The activity in front, the one every pass looks at. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /** The decor view whose observer has the listeners. */
    private static WeakReference<View> observed = new WeakReference<>(null);
    private static boolean layoutWatched;
    /** When a finger last touched the screen. */
    private static long touchedAt;
    /** A finger is on the screen: holding still is still a touch, and the wait starts when it lifts. */
    private static boolean fingerDown;
    /** What the last pass found to look after, with the choice on. */
    private static Guarded guarded = Guarded.NONE;
    /** The last pass dimmed the controls, and nothing has brought them back since. */
    private static boolean dimming;
    /** The finger now down went down on the reel itself or on Save or Download, and hasn't swiped. */
    private static boolean quiet;
    /** The reel on screen was paused by a tap (#457): with Light when paused on, the controls stay bright until it plays. */
    private static boolean paused;
    /** The last pass found a reply to the story being typed (#479). */
    private static boolean wasTyping;
    /** Where on the screen that finger went down. */
    private static float quietX, quietY;
    /** When the next timed pass is due, or -1 when none is. */
    private static long tickAt = -1L;

    private BurnInGuard() {
    }

    /** Called as the application starts. Does nothing in a build without Clean up Reels. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.reelDeclutter()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Lifecycle());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard start", failure);
        }
    }

    /** The choice now: off until the settings are read and while HushGram is paused. */
    static BurnInMode mode() {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return BurnInMode.OFF;
        BurnInMode mode = Settings.BURN_IN_GUARD.get();
        return mode == null ? BurnInMode.OFF : mode;
    }

    /** How long the screen goes untouched before the controls dim: the Dim after choice. */
    static long idleAfter() {
        BurnInWait wait = Settings.BURN_IN_WAIT.get();
        return (wait == null ? BurnInWait.FIVE_SECONDS : wait).millis;
    }

    /** Stay dim when I tap (#162): no finger on the Reels screen brings the controls back. Off while paused. */
    static boolean stayDim() {
        return Settings.BURN_IN_STAY_DIM.get();
    }

    /** Light when paused (#457): a reel you pause brings the controls to full brightness until it plays again. */
    static boolean lightWhenPaused() {
        return Settings.BURN_IN_LIGHT_WHEN_PAUSED.get();
    }

    /** Dim stories too (#479): the guard looks after a story the way it does the Reels screen. */
    static boolean stories() {
        return Settings.BURN_IN_STORIES.get();
    }

    /**
     * The Reels tap just paused the reel on screen (#457). With Light when paused on and the guard
     * watching Reels, the controls come back now and stay until a tap on the reel plays it again or a
     * swipe moves on. Never throws.
     */
    public static void reelPaused() {
        try {
            if (guarded != Guarded.REELS || !lightWhenPaused() || front.get() == null) return;
            paused = true;
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, COUNT_PAUSED);
            passSoon();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard paused", failure);
        }
    }

    /** The place the content is in at [now], in steps of {@link #STEP_DP}. */
    static int[] placeAt(long now) {
        return PLACES[(int) ((now / SHIFT_EVERY_MS) % PLACES.length)];
    }

    static int id(Context context, String name) {
        synchronized (IDS) {
            Integer found = IDS.get(name);
            if (found == null) {
                found = context.getResources().getIdentifier(name, "id", Utils.resourcePackage(context));
                IDS.put(name, found);
            }
            return found;
        }
    }

    /** Tests give each view an id of their own, since a test app has none of Instagram's. */
    static void idForTests(String name, int id) {
        synchronized (IDS) {
            IDS.put(name, id);
        }
    }

    /** As [activity] comes to the front: listens to it and looks once. */
    static void resumed(Activity activity) {
        try {
            leave();
            front = new WeakReference<>(activity);
            View decor = activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
            if (decor == null) return;
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.addOnWindowFocusChangeListener(FOCUS);
            observed = new WeakReference<>(decor);
            touchedAt = SystemClock.uptimeMillis();
            fingerDown = false;
            pass();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard watch", failure);
        }
    }

    /** As [activity] leaves the front: puts back what it changed and takes its listeners off. */
    static void paused(Activity activity) {
        try {
            if (front.get() == activity) leave();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard release", failure);
        }
    }

    private static void leave() {
        Activity activity = front.get();
        undim();
        if (activity != null) place(activity, false, 0L);
        watchLayouts(false);
        View decor = observed.get();
        if (decor != null && decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnWindowFocusChangeListener(FOCUS);
        }
        observed = new WeakReference<>(null);
        front = new WeakReference<>(null);
        guarded = Guarded.NONE;
        fingerDown = false;
        quiet = false;
        paused = false;
        wasTyping = false;
        schedule(Long.MAX_VALUE);
    }

    /**
     * Looks at the activity in front: dims or brings back its controls, moves its content or puts it
     * back, and asks for a pass at the moment either next changes, since nothing lays out on its own
     * while a reel just plays.
     */
    static void pass() {
        Activity activity = front.get();
        if (activity == null) return;
        try {
            Window window = activity.getWindow();
            View decor = window == null ? null : window.peekDecorView();
            BurnInMode mode = mode();
            watchLayouts(mode != BurnInMode.OFF);
            Guarded where = mode == BurnInMode.OFF || decor == null ? Guarded.NONE : guardedOn(decor);
            boolean on = where != Guarded.NONE;
            long now = SystemClock.uptimeMillis();
            if (on) {
                watch(window);
                // Coming onto the Reels screen or a story, or from one to the other, starts the wait.
                if (where != guarded) touchedAt = now;
            }
            guarded = where;
            long wait = idleAfter();
            // Typing a reply to a story: the keyboard takes the fingers, so the wait starts once it's done.
            boolean typing = where == Guarded.STORY && typing(decor);
            if (typing || wasTyping) touchedAt = now;
            wasTyping = typing;
            boolean holding = typing || where == Guarded.REELS && paused && lightWhenPaused();
            boolean dim = on && !fingerDown && !holding && now - touchedAt >= wait;
            if (dim) {
                dimAll(decor, where);
                dimming = true;
            } else {
                undim();
            }
            boolean shifting = on && mode == BurnInMode.DIM_AND_SHIFT;
            place(activity, shifting, now);
            long next = !on || fingerDown || holding ? Long.MAX_VALUE : dim ? now + KEEP_MS : touchedAt + wait;
            schedule(Math.min(next,
                    shifting ? (now / SHIFT_EVERY_MS + 1) * SHIFT_EVERY_MS : Long.MAX_VALUE));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard", failure);
            try {
                undim();
                place(activity, false, 0L);
            } catch (Throwable again) {
                HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard put back", again);
            }
        }
    }

    /**
     * What [decor] shows that the guard looks after (#479): a story when the story viewer fills the
     * window and no reel is drawn over it, as long as Dim stories too is on, else the Reels screen as
     * {@link FullScreenBars#screenOf} finds it. A story with Dim stories too off is nothing to look
     * after, since it covers whatever reel is under it.
     */
    static Guarded guardedOn(View decor) {
        View story = storyRoot(decor);
        boolean reels = FullScreenBars.screenOf(decor) == FullScreenBars.Screen.REELS;
        if (story != null) {
            int viewerId = FullScreenBars.id(decor.getContext(), FullScreenBars.VIEWER);
            View viewer = viewerId == 0 ? null : decor.findViewById(viewerId);
            boolean reelOver = reels && viewer != null && drawnOver(viewer, story);
            if (!reelOver) return stories() ? Guarded.STORY : Guarded.NONE;
        }
        return reels ? Guarded.REELS : Guarded.NONE;
    }

    /** The story viewer's root in [decor] while it fills the window, or null. */
    @Nullable
    private static View storyRoot(View decor) {
        View story = find(decor, STORY_ID);
        return story != null && FullScreenBars.fills(story, decor) ? story : null;
    }

    @Nullable
    private static View find(View root, String name) {
        int id = id(root.getContext(), name);
        return id == 0 ? null : root.findViewById(id);
    }

    /**
     * Whether [top] is drawn over [under]: where their paths up meet, [top]'s branch comes later in the
     * children, and a later child draws over an earlier one.
     */
    static boolean drawnOver(View top, View under) {
        Map<ViewParent, View> path = new IdentityHashMap<>();
        View child = under;
        for (ViewParent parent = under.getParent(); parent instanceof ViewGroup; parent = parent.getParent()) {
            path.put(parent, child);
            child = (View) parent;
        }
        child = top;
        for (ViewParent parent = top.getParent(); parent instanceof ViewGroup; parent = parent.getParent()) {
            View other = path.get(parent);
            if (other != null) {
                ViewGroup group = (ViewGroup) parent;
                return group.indexOfChild(child) > group.indexOfChild(other);
            }
            child = (View) parent;
        }
        return false;
    }

    /**
     * Typing a reply to the story (#479): the Send message box has the focus, so the keyboard is up
     * and the fingers are on it, where no touch reaches this window.
     */
    private static boolean typing(View decor) {
        View focus = decor.findFocus();
        return focus != null && focus.onCheckIsTextEditor();
    }

    /** Dims what dims on [where] in [decor]: the Reels screen's controls, or a story's (#479). */
    private static void dimAll(View decor, Guarded where) {
        if (where == Guarded.STORY) {
            View story = storyRoot(decor);
            if (story == null) return;
            dimAll(story, STORY_DIMMED_IDS, COUNT_STORY_DIMMED);
            dimSeekBar(story);
        } else {
            dimAll(decor, DIMMED_IDS, COUNT_DIMMED);
        }
    }

    /**
     * HushGram's seek bar on stories, which sits beside the viewer in the frame holding it (see
     * {@link app.hushgram.extension.instagram.stories.StorySeekBar}), dims with the story's controls.
     */
    private static void dimSeekBar(View story) {
        if (!(story.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) story.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view instanceof GlassSeekBar && view.getVisibility() == View.VISIBLE) dim(view, COUNT_STORY_DIMMED);
        }
    }

    /**
     * Dims each of [names] shown in [root], the first one on each path down, and the views without an
     * id beside the button column ({@link #COLUMN_ID}) when it's one of them.
     */
    private static void dimAll(View root, String[] names, String count) {
        int[] ids = ids(root.getContext(), names);
        int column = names == DIMMED_IDS ? id(root.getContext(), COLUMN_ID) : 0;
        Set<View> beside = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        int seen = 0;
        while (!pending.isEmpty() && seen++ < MAX_VIEWS) {
            View view = pending.poll();
            if (view.getVisibility() != View.VISIBLE || beside.contains(view)) continue;
            if (dims(view, ids)) {
                dim(view, count);
                if (column != 0 && view.getId() == column) dimBeside(view, beside);
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
        }
    }

    /** Dims the shown views without an id beside [column], and adds them to [beside] so the search doesn't go into them. */
    private static void dimBeside(View column, Set<View> beside) {
        if (!(column.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) column.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == column || view.getId() != View.NO_ID || view.getVisibility() != View.VISIBLE) continue;
            if (beside.add(view)) dim(view, COUNT_DIMMED);
        }
    }

    private static boolean dims(View view, int[] ids) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        for (int wanted : ids) if (wanted != 0 && wanted == id) return true;
        return false;
    }

    /** Dims [view], counting it under [count] the first time. */
    private static void dim(View view, String count) {
        float alpha = view.getAlpha();
        float[] held = DIMMED.get(view);
        if (held == null) {
            held = new float[]{alpha, alpha * IDLE_LEVEL};
            DIMMED.put(view, held);
            HookStatus.counted(FamilyNames.REEL_DECLUTTER, count);
        } else if (alpha != held[1]) {
            // Instagram set its own since; that's what the dim is taken from now.
            held[0] = alpha;
            held[1] = alpha * IDLE_LEVEL;
        }
        if (alpha != held[1]) view.setAlpha(held[1]);
    }

    /** Brings back each view dimmed here, if its alpha is still the one written here. */
    private static void undim() {
        dimming = false;
        if (DIMMED.isEmpty()) return;
        for (Map.Entry<View, float[]> entry : DIMMED.entrySet()) {
            View view = entry.getKey();
            float[] held = entry.getValue();
            if (view != null && view.getAlpha() == held[1]) view.setAlpha(held[0]);
        }
        DIMMED.clear();
    }

    private static void place(Activity activity, boolean shifting, long now) {
        // peekDecorView, so an activity that never made its views doesn't get them made here as it leaves.
        Window window = activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        if (decor != null && !shifting) bare(decor);
        View content = decor == null ? null : decor.findViewById(android.R.id.content);
        if (content == null) return;
        float[] held = MOVED.get(content);
        if (!shifting) {
            if (held != null) {
                MOVED.remove(content);
                if (content.getTranslationX() == held[2]) content.setTranslationX(held[0]);
                if (content.getTranslationY() == held[3]) content.setTranslationY(held[1]);
            }
            return;
        }
        float x = content.getTranslationX();
        float y = content.getTranslationY();
        if (held == null) {
            held = new float[]{x, y, x, y};
            MOVED.put(content, held);
        } else {
            // Something else wrote its own since the last pass; that's where the content is now.
            if (x != held[2]) held[0] = x;
            if (y != held[3]) held[1] = y;
        }
        int[] at = placeAt(now);
        float step = STEP_DP * content.getResources().getDisplayMetrics().density;
        held[2] = held[0] + at[0] * step;
        held[3] = held[1] + at[1] * step;
        cover(decor, content, held);
        if (x == held[2] && y == held[3]) return;
        content.setTranslationX(held[2]);
        content.setTranslationY(held[3]);
        HookStatus.counted(FamilyNames.REEL_DECLUTTER, COUNT_SHIFTED);
    }

    /**
     * Puts black over the strips the shift leaves bare (#669), from where the content was before it to
     * where it is now. Reels and stories are black screens, but the window under them takes Instagram's
     * theme, so in light mode a step to one side showed a white line down the other.
     */
    private static void cover(View decor, View content, float[] held) {
        float dx = held[2] - held[0];
        float dy = held[3] - held[1];
        if (dx == 0f && dy == 0f) {
            bare(decor);
            return;
        }
        Gap gap = GAPS.get(decor);
        if (gap == null || gap.content.get() != content) {
            bare(decor);
            gap = new Gap(content);
            GAPS.put(decor, gap);
            decor.getOverlay().add(gap);
        }
        gap.setBounds(0, 0, decor.getWidth(), decor.getHeight());
        if (gap.baseX != held[0] || gap.baseY != held[1] || gap.dx != dx || gap.dy != dy) {
            gap.baseX = held[0];
            gap.baseY = held[1];
            gap.dx = dx;
            gap.dy = dy;
            gap.invalidateSelf();
        }
    }

    /** Takes the black {@link #cover} put on [decor] off again. */
    private static void bare(View decor) {
        Gap gap = GAPS.remove(decor);
        if (gap != null) decor.getOverlay().remove(gap);
    }

    /** The black over the strips the shift left bare, drawn over the decor in its overlay (#669). */
    static final class Gap extends Drawable {
        final WeakReference<View> content;
        /** The translation the content had before the shift, and the shift on top of it. */
        float baseX, baseY, dx, dy;
        private final Paint paint = new Paint();
        private final int[] at = new int[2];
        private final int[] decorAt = new int[2];

        Gap(View content) {
            this.content = new WeakReference<>(content);
            paint.setColor(Color.BLACK);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            View view = content.get();
            if (view == null) return;
            view.getLocationInWindow(at);
            View root = view.getRootView();
            root.getLocationInWindow(decorAt);
            // Where the content would be without the shift, in the decor's own coordinates.
            float left = at[0] - decorAt[0] - view.getTranslationX() + baseX;
            float top = at[1] - decorAt[1] - view.getTranslationY() + baseY;
            float right = left + view.getWidth();
            float bottom = top + view.getHeight();
            // A step is rarely whole pixels (2 dp is 5.25 px at 2.625), so each strip reaches to the
            // next whole pixel and no half-lit seam is left between it and the content.
            if (dx > 0f) canvas.drawRect(left, top, (float) Math.ceil(left + dx), bottom, paint);
            else if (dx < 0f) canvas.drawRect((float) Math.floor(right + dx), top, right, bottom, paint);
            if (dy > 0f) canvas.drawRect(left, top, right, (float) Math.ceil(top + dy), paint);
            else if (dy < 0f) canvas.drawRect(left, (float) Math.floor(bottom + dy), right, bottom, paint);
        }

        @Override public void setAlpha(int alpha) {
        }

        @Override public void setColorFilter(@Nullable ColorFilter filter) {
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static void schedule(long next) {
        if (next == Long.MAX_VALUE) next = -1L;
        if (next == tickAt) return;
        Handler main = handler();
        main.removeCallbacks(TICK);
        tickAt = next;
        if (next >= 0L) main.postAtTime(TICK, next);
    }

    private static void tick() {
        tickAt = -1L;
        pass();
    }

    /**
     * A pass as soon as the main thread is free, in place of any timed one. It counts as due now, so
     * a layout before it runs leaves it be rather than putting it off by up to {@link #LOOK_MS}.
     */
    private static void passSoon() {
        Handler main = handler();
        main.removeCallbacks(TICK);
        tickAt = SystemClock.uptimeMillis();
        main.post(TICK);
    }

    /**
     * A finger on the window ([action] is its MotionEvent action, or -1 for the window getting focus
     * back): dimmed controls come back now, and a lift on the Reels screen asks for a pass so the wait
     * is timed from it. The watcher stays on a window once added, so off or elsewhere a lift asks for
     * nothing; the focus coming back still does, which is how a choice made in the settings is seen.
     */
    static void touched(int action) {
        if (action < 0 && guarded == Guarded.REELS && stayDim()) {
            // Stay dim: a sheet or the share screen closing leaves the controls as they are, and the
            // pass still sees a choice changed in the settings.
            if (front.get() != null) passSoon();
            return;
        }
        touchedAt = SystemClock.uptimeMillis();
        boolean lifted = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action < 0;
        if (action == MotionEvent.ACTION_DOWN) fingerDown = true;
        else if (lifted) fingerDown = false;
        undim();
        if (lifted && (guarded != Guarded.NONE || action < 0) && front.get() != null) passSoon();
    }

    /**
     * A finger event on the window. While the guard is on, one that goes down on the reel itself or
     * on Save or Download isn't a touch ({@link #quietAt}) until it lifts, unless it moves farther than
     * a tap does or a second finger joins: a swipe to the next reel or a pinch counts from then (#162).
     * With Stay dim when I tap on, no finger on the Reels screen is a touch at all.
     */
    static void seen(MotionEvent event) {
        int action = event.getActionMasked();
        // On a story every finger is a touch: a tap there moves to the next story or back (#479).
        boolean reels = guarded == Guarded.REELS;
        if (paused && reels && action == MotionEvent.ACTION_DOWN && landing(event.getRawX(), event.getRawY()) == 1) {
            // A tap on the reel plays it again: the wait starts over from here.
            paused = false;
            touchedAt = SystemClock.uptimeMillis();
            quiet = true;
            quietX = event.getRawX();
            quietY = event.getRawY();
            passSoon();
            return;
        }
        if (reels && stayDim()) {
            quiet = false;
            fingerDown = false;
            if (action == MotionEvent.ACTION_DOWN) HookStatus.counted(FamilyNames.REEL_DECLUTTER, COUNT_STAY_DIM);
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            quiet = reels && quietAt(event.getRawX(), event.getRawY());
            if (quiet) {
                quietX = event.getRawX();
                quietY = event.getRawY();
                HookStatus.counted(FamilyNames.REEL_DECLUTTER, COUNT_QUIET);
                return;
            }
        } else if (quiet) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                quiet = false;
                return;
            }
            if (action == MotionEvent.ACTION_MOVE && !swiped(event)) return;
            quiet = false;
            paused = false;
            touched(MotionEvent.ACTION_DOWN);
            return;
        }
        touched(action);
    }

    /** The quiet finger moved farther from where it went down than a tap does. */
    private static boolean swiped(MotionEvent event) {
        Activity activity = front.get();
        int slop = activity == null ? 0 : ViewConfiguration.get(activity).getScaledTouchSlop();
        float dx = event.getRawX() - quietX;
        float dy = event.getRawY() - quietY;
        return dx * dx + dy * dy > (float) slop * slop;
    }

    /**
     * A finger going down at [x, y] on the screen lands on the reel itself, or on Save or HushGram's
     * Download: what it lands on is the deepest view there that takes taps, as the window hands the
     * touch on, or the topmost one there when none does, and the views it sits in decide. The first of
     * Save or Download ({@link #QUIET_IDS}), one of the controls ({@link #WAKING_IDS}) or a view
     * without an id beside the button column, and the reels' pager ({@link #REEL_ID}) found going up
     * from it decides. Anywhere else, or with no pager to know the reel by, it's a touch.
     */
    static boolean quietAt(float x, float y) {
        return landing(x, y) != 0;
    }

    /** What {@link #quietAt} found: 1 for the reel itself, 2 for Save or Download, 0 for anything else. */
    private static int landing(float x, float y) {
        Activity activity = front.get();
        Window window = activity == null ? null : activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        if (decor == null) return 0;
        Context context = decor.getContext();
        int reel = id(context, REEL_ID);
        if (reel == 0) return 0;
        View[] topmost = new View[1];
        View hit = landsOn(decor, x, y, new int[1], new int[2], topmost);
        View from = hit != null ? hit : topmost[0];
        int column = id(context, COLUMN_ID);
        int[] waking = ids(context, WAKING_IDS);
        int[] quietIds = ids(context, QUIET_IDS);
        String download = null;
        for (View view = from; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            int id = view.getId();
            if (dims(view, quietIds)) return 2;
            CharSequence description = view.getContentDescription();
            if (description != null) {
                if (download == null) download = FeedDownloadButton.description(context);
                if (download.contentEquals(description)) return 2;
            }
            if (dims(view, waking) || besideColumn(view, column)) return 0;
            if (id == reel) return 1;
        }
        return 0;
    }

    /**
     * The deepest view under [view] at [x, y] on the screen that takes taps, trying the topmost first,
     * or null when none does. [topmost] gets the first view found there with nothing under it there.
     */
    @Nullable
    private static View landsOn(View view, float x, float y, int[] seen, int[] at, View[] topmost) {
        if (seen[0]++ >= MAX_VIEWS || view.getVisibility() != View.VISIBLE) return null;
        view.getLocationOnScreen(at);
        if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = landsOn(group.getChildAt(i), x, y, seen, at, topmost);
                if (found != null) return found;
            }
        }
        if (topmost[0] == null) topmost[0] = view;
        return view.isClickable() || view.isLongClickable() ? view : null;
    }

    /** A view without an id beside the button column: the Interested row and the strip under it. */
    private static boolean besideColumn(View view, int column) {
        if (column == 0 || view.getId() != View.NO_ID || !(view.getParent() instanceof ViewGroup)) return false;
        ViewGroup parent = (ViewGroup) view.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i).getId() == column) return true;
        }
        return false;
    }

    private static int[] ids(Context context, String[] names) {
        int[] ids = new int[names.length];
        for (int i = 0; i < ids.length; i++) ids[i] = id(context, names[i]);
        return ids;
    }

    /** Adds or takes off the layout and draw listeners on the window in front. */
    private static void watchLayouts(boolean on) {
        if (on == layoutWatched) return;
        View decor = observed.get();
        if (on && decor == null) return;
        layoutWatched = on;
        if (decor == null || !decor.getViewTreeObserver().isAlive()) return;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (on) {
            observer.addOnGlobalLayoutListener(LAYOUT);
            observer.addOnPreDrawListener(DRAW);
        } else {
            observer.removeOnGlobalLayoutListener(LAYOUT);
            observer.removeOnPreDrawListener(DRAW);
        }
    }

    /**
     * Dims again each dimmed view whose alpha Instagram changed since, from Instagram's new alpha. 451
     * writes the button column's alpha back on a tap that pauses or plays the reel, and a write is drawn
     * on the next frame, so waiting for the {@link #KEEP_MS} look let the buttons flash bright for up to
     * a second on every such tap (#162). Done before the frame is drawn, the flash never shows.
     */
    private static void redim() {
        if (DIMMED.isEmpty()) return;
        for (Map.Entry<View, float[]> entry : DIMMED.entrySet()) {
            View view = entry.getKey();
            // Already counted when it was first dimmed.
            if (view != null && view.getAlpha() != entry.getValue()[1]) dim(view, COUNT_DIMMED);
        }
    }

    private static Handler handler() {
        Looper main = Looper.getMainLooper();
        if (handler == null || handler.getLooper() != main) handler = new Handler(main);
        return handler;
    }

    /** Wraps [window]'s callback once, as Hold to download does, to see touches. */
    private static void watch(@Nullable Window window) {
        if (window == null || WRAPPED.containsKey(window)) return;
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        window.setCallback((Window.Callback) Proxy.newProxyInstance(BurnInGuard.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, new Watcher(inner)));
        WRAPPED.put(window, Boolean.TRUE);
    }

    /** The callback [callback] passes calls on to when it's a Burn-in guard watcher, else [callback]. */
    public static Window.Callback innerOf(Window.Callback callback) {
        if (callback != null && Proxy.isProxyClass(callback.getClass())
                && Proxy.getInvocationHandler(callback) instanceof Watcher) {
            return ((Watcher) Proxy.getInvocationHandler(callback)).inner;
        }
        return callback;
    }

    /** Passes every call on to the callback it wraps, and looks at touches on the way. */
    private static final class Watcher implements InvocationHandler {
        final Window.Callback inner;

        Watcher(Window.Callback inner) {
            this.inner = inner;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName())) {
                try {
                    seen((MotionEvent) args[0]);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard touch", failure);
                }
            }
            try {
                return method.invoke(inner, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }

    /** The window getting focus back: the settings closing, or a screen over it going away. */
    private static final class Focus implements ViewTreeObserver.OnWindowFocusChangeListener {
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            if (!hasFocus) return;
            try {
                touched(-1);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard focus", failure);
            }
        }
    }

    /** A frame about to be drawn: what Instagram brought back while dimmed is dimmed again first. */
    private static final class Draw implements ViewTreeObserver.OnPreDrawListener {
        @Override public boolean onPreDraw() {
            try {
                redim();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard redim", failure);
            }
            return true;
        }
    }

    /**
     * A layout: a new reel, or another screen, so a look soon. While the controls are dimmed, what the
     * layout brought in (the next reel's buttons as it scrolls in) is dimmed now, before it's drawn.
     */
    private static final class Layout implements ViewTreeObserver.OnGlobalLayoutListener {
        private long lastLook = Long.MIN_VALUE / 2;

        @Override public void onGlobalLayout() {
            View decor = observed.get();
            Guarded where = guarded;
            if (dimming && decor != null && where != Guarded.NONE) {
                try {
                    // Still where it dimmed: a layout that opened another screen is left to the pass, which brings them back.
                    if (guardedOn(decor) == where) dimAll(decor, where);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.REEL_DECLUTTER, "burn-in guard layout dim", failure);
                }
            }
            long now = SystemClock.uptimeMillis();
            long due = Math.max(now, lastLook + LOOK_MS);
            if (tickAt >= 0L && tickAt <= due) return;
            lastLook = due;
            schedule(due);
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            resumed(activity);
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            paused(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }

    static long nextPassAtForTests() {
        return tickAt;
    }

    static void watchForTests(Window window) {
        watch(window);
    }

    static boolean watchingLayoutsForTests() {
        return layoutWatched;
    }

    static boolean pausedForTests() {
        return paused;
    }

    static void resetForTests() {
        leave();
        if (handler != null) handler.removeCallbacks(TICK);
        tickAt = -1L;
        touchedAt = 0L;
        paused = false;
        LAYOUT.lastLook = Long.MIN_VALUE / 2;
        DIMMED.clear();
        MOVED.clear();
        GAPS.clear();
        WRAPPED.clear();
    }
}
