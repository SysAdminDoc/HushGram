/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inspector.WindowInspector;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Blur the chat behind a held message (#684): while Instagram shows the reactions and the actions of a message
 * someone holds, everything else in the chat blurs, the way iOS does, and the held message stays sharp.
 *
 * <p>No part of Instagram's code changes. {@link GlassChat}'s watcher calls {@link #check} before each frame.
 * Instagram opens the reactions and actions as a screen of their own (MessageActionsFragment) in a frame over the
 * chat, by id {@link #ACTIONS_FRAME}, and the screen's view is {@link #ACTIONS}. The screen's arguments carry its
 * view model, and the view model's one Rect is where the held message's row sits on screen. The row of the list
 * of messages that covers most of that Rect stays sharp. Every other view the chat draws under its root gets a
 * blur and a light dim (a light wash on a light screen) through its render effect: the views beside the path
 * from the root down to the held row, and the other rows. The frame holding the reactions and actions is passed
 * over, so they stay sharp too. The blur fades in and out over {@link #FADE_MS}.
 *
 * <p>Instagram doesn't move the held message. When it sits low in the chat, the menu of actions (a popup window of
 * its own, found among the app's windows by its rows' id, {@link #MENU_ITEM}) and the reactions above it
 * ({@link #REACTIONS}) cover it, so you can't see what you're reacting to (#828). While the blur shows, the held
 * row slides to the nearest spot in the list where neither covers it, above the reactions or below the menu,
 * and shrinks when no spot is tall enough ({@link #place}). It slides back with the fade, and its own
 * translation and scale come back as they were.
 *
 * <p>Off, Android before 12, no Rect or no row under it, paused or anything thrown: nothing blurs, and every
 * render effect written here is taken off again.
 */
public final class HeldMessageBlur {
    static final String HOOK = "held message blur";
    static final String COUNT_BLURS = "held messages blurred";
    static final String COUNT_MISSES = "held messages with no row found";

    /** The frame Instagram opens a held message's reactions and actions in, and that screen's own view. */
    static final String ACTIONS_FRAME = "message_actions_fragment";
    static final String ACTIONS = "message_actions_container";
    /** The tag AndroidX keeps a screen on its view by, and the argument holding the screen's view model. */
    static final String FRAGMENT_TAG = "fragment_container_view_tag";
    static final String VIEW_MODEL = "MESSAGE_ACTIONS_VIEW_MODEL_KEY";

    /** The reactions over a held message, in the actions screen's view, and each row of the menu of actions. */
    static final String REACTIONS = "reactions_container";
    static final String MENU_ITEM = "context_menu_item";
    /** The room kept, in dp, between a held message moved into view and what it was moved clear of. */
    static final float ROOM_DP = 8f;
    /** The smallest a held message shrinks to when no spot in the chat is tall enough for it. */
    static final float SMALLEST = 0.3f;
    static final String COUNT_MOVED = "held messages moved into view";

    /** How often the frame is looked for while none is known, so a feed isn't searched every frame. */
    static final long SEARCH_EVERY_MS = GlassChat.SEARCH_EVERY_MS;
    static final long FADE_MS = 160L;
    /** The blur's radius, in dp, once faded in. */
    static final float BLUR_DP = 14f;
    /** How much darker a dark screen gets behind the held message, and how much lighter a light one. */
    static final float DIM_DARK = 0.3f;
    static final float WASH_LIGHT = 0.25f;

    private static WeakReference<ViewGroup> frame = new WeakReference<>(null);
    private static long searchedAt = -SEARCH_EVERY_MS;
    /** The actions screen's view the blur is for, and the one no row was found for, so it isn't looked at again. */
    private static WeakReference<View> heldFor = new WeakReference<>(null);
    private static WeakReference<View> missed = new WeakReference<>(null);
    private static WeakReference<View> held = new WeakReference<>(null);
    /** The views given a render effect here, and the strength each was last given. */
    private static final Map<View, Float> blurred = Collections.synchronizedMap(new WeakHashMap<>());
    /** How far the blur has faded in, 0 to 1, where it was when the fade began, and where it's going. */
    private static float level;
    private static float fadeFrom;
    private static float fadeTo;
    private static long fadeStart;
    /** Where the held message was on screen when it was held, before any move, or null. */
    @Nullable private static Rect heldAt;
    /** The row moved into view, and its own translation, scale, pivot and elevation from before the move. */
    private static WeakReference<View> moved = new WeakReference<>(null);
    private static final float[] movedFrom = new float[6];
    /** The views of the app's windows, for tests, in place of the platform's list. */
    @Nullable static volatile List<View> windowsForTests;

    private HeldMessageBlur() {
    }

    /** Whether the blur is wanted now. */
    static boolean on() {
        return Utils.settingsReady() && Settings.HELD_MESSAGE_BLUR.get();
    }

    /** Looks before a frame for a held message's actions, and blurs the chat behind it or takes the blur off. Never throws. */
    static void check(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            if (!on()) {
                clear();
                return;
            }
            step(activity, SystemClock.uptimeMillis());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_CHAT, HOOK, failure);
            try {
                clear();
            } catch (Throwable ignored) {
                // Already reporting the first failure.
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    static void step(Activity activity, long now) {
        ViewGroup known = actionsFrame(activity, now);
        View actions = known == null ? null : shownActions(activity, known);
        if (actions == null) {
            // Gone: the next message held gets its own look, even when Instagram shows it in the same view.
            heldFor = new WeakReference<>(null);
            missed = new WeakReference<>(null);
            if (fadeTo > 0f) fade(0f, now);
        } else if (actions != heldFor.get() && actions != missed.get()) {
            begin(activity, known, actions, now);
        }
        if (blurred.isEmpty()) return;
        float was = level;
        level = levelAt(now);
        ViewGroup root = known == null ? null : root(known);
        View row = held.get();
        if (fadeTo > 0f && root != null && row != null) {
            List<View> targets = new ArrayList<>();
            targets(root, known, row, targets);
            for (View each : targets) {
                if (!blurred.containsKey(each)) apply(each, level);
            }
        }
        if (level != was) {
            for (View each : new ArrayList<>(blurred.keySet())) apply(each, level);
        }
        if (row != null) keepInView(activity, actions, row, level);
        if (level == 0f && fadeTo == 0f) {
            clear();
            return;
        }
        if (level != fadeTo) activity.getWindow().getDecorView().postInvalidateOnAnimation();
    }

    /** The frame Instagram opens the actions in, looked for every {@link #SEARCH_EVERY_MS} while none is known. */
    @Nullable
    private static ViewGroup actionsFrame(Activity activity, long now) {
        ViewGroup known = frame.get();
        if (known != null && known.isAttachedToWindow()) return known;
        frame = new WeakReference<>(null);
        if (now - searchedAt < SEARCH_EVERY_MS) return null;
        searchedAt = now;
        int id = GlassChat.id(activity, ACTIONS_FRAME);
        if (id == 0) return null;
        View found = activity.findViewById(id);
        if (!(found instanceof ViewGroup)) return null;
        frame = new WeakReference<>((ViewGroup) found);
        return (ViewGroup) found;
    }

    /** The actions screen's view in [frame] while it shows, or null. */
    @Nullable
    static View shownActions(Activity activity, ViewGroup frame) {
        int id = GlassChat.id(activity, ACTIONS);
        if (id == 0) return null;
        View actions = frame.findViewById(id);
        return actions != null && actions.isShown() ? actions : null;
    }

    /** The view the whole chat draws under: the one holding the actions' frame. */
    @Nullable
    static ViewGroup root(View frame) {
        ViewParent parent = frame.getParent();
        return parent instanceof ViewGroup ? (ViewGroup) parent : null;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static void begin(Activity activity, ViewGroup frame, View actions, long now) {
        View row = heldRow(activity, frame, actions);
        if (row == null) {
            missed = new WeakReference<>(actions);
            HookStatus.counted(FamilyNames.GLASS_CHAT, COUNT_MISSES);
            return;
        }
        // Another message held while the last one's blur is on or fading: its row may be blurred, so start over
        // at the strength showing now.
        clearEffects();
        forgetMove();
        heldAt = heldRect(activity, actions);
        heldFor = new WeakReference<>(actions);
        held = new WeakReference<>(row);
        HookStatus.invoked(FamilyNames.GLASS_CHAT);
        HookStatus.counted(FamilyNames.GLASS_CHAT, COUNT_BLURS);
        ViewGroup root = root(frame);
        if (root == null) return;
        List<View> targets = new ArrayList<>();
        targets(root, frame, row, targets);
        for (View each : targets) apply(each, level);
        fade(1f, now);
    }

    /**
     * The held message's row: of the rows in the chat's list of messages, the one covering most of the Rect in the
     * actions screen's view model. Null when there's no view model, no Rect or no row under it.
     */
    @Nullable
    static View heldRow(Activity activity, ViewGroup frame, View actions) {
        Rect rect = heldRect(activity, actions);
        if (rect == null) return null;
        ViewGroup root = root(frame);
        int listId = GlassChat.id(activity, GlassChat.MESSAGE_LIST);
        View list = root == null || listId == 0 ? null : root.findViewById(listId);
        if (!(list instanceof ViewGroup)) return null;
        return rowOver((ViewGroup) list, rect);
    }

    /** The child of [list] covering most of [rect], in screen pixels, or null when none covers any of it. */
    @Nullable
    static View rowOver(ViewGroup list, Rect rect) {
        View best = null;
        long most = 0;
        int[] at = new int[2];
        for (int i = 0, n = list.getChildCount(); i < n; i++) {
            View child = list.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.getLocationOnScreen(at);
            Rect bounds = new Rect(at[0], at[1], at[0] + child.getWidth(), at[1] + child.getHeight());
            if (!bounds.intersect(rect)) continue;
            long covered = (long) bounds.width() * bounds.height();
            if (covered > most) {
                most = covered;
                best = child;
            }
        }
        return best;
    }

    /** The Rect in the view model of the screen [actions] is the view of, or null. */
    @Nullable
    @SuppressWarnings("deprecation")
    static Rect heldRect(Activity activity, View actions) {
        int tag = GlassChat.id(activity, FRAGMENT_TAG);
        Object screen = tag == 0 ? null : actions.getTag(tag);
        if (screen == null) return null;
        Object arguments;
        try {
            arguments = screen.getClass().getMethod("getArguments").invoke(screen);
        } catch (ReflectiveOperationException none) {
            return null;
        }
        if (!(arguments instanceof Bundle)) return null;
        Bundle bundle = (Bundle) arguments;
        bundle.setClassLoader(screen.getClass().getClassLoader());
        Rect found = rectIn(bundle.get(VIEW_MODEL));
        if (found != null) return found;
        // A build that names the argument otherwise: the first argument with a Rect in it.
        for (String key : bundle.keySet()) {
            found = rectIn(bundle.get(key));
            if (found != null) return found;
        }
        return null;
    }

    /** The first Rect, not empty, among [model]'s own fields, or null. */
    @Nullable
    static Rect rectIn(@Nullable Object model) {
        if (model == null) return null;
        for (Field field : model.getClass().getDeclaredFields()) {
            if (field.getType() != Rect.class || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(model);
                if (value instanceof Rect && !((Rect) value).isEmpty()) return new Rect((Rect) value);
            } catch (ReflectiveOperationException | RuntimeException none) {
                // The next field, if any.
            }
        }
        return null;
    }

    /**
     * Every view [root] draws that the blur goes on: walking down from [root] to [row], each view beside that path
     * that shows, but never [frame], which holds the reactions and the actions, nor anything on the path itself.
     */
    static void targets(ViewGroup root, View frame, View row, List<View> into) {
        Map<View, Boolean> path = new IdentityHashMap<>();
        for (View at = row; at != null && at != root; ) {
            path.put(at, Boolean.TRUE);
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        ViewGroup at = root;
        while (at != null) {
            ViewGroup next = null;
            for (int i = 0, n = at.getChildCount(); i < n; i++) {
                View child = at.getChildAt(i);
                if (child == frame || child.getVisibility() != View.VISIBLE) continue;
                if (path.containsKey(child)) {
                    if (child != row && child instanceof ViewGroup) next = (ViewGroup) child;
                    continue;
                }
                into.add(child);
            }
            at = next;
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static void apply(View view, float strength) {
        Float was = blurred.get(view);
        if (was != null && was == strength) return;
        blurred.put(view, strength);
        view.setRenderEffect(effect(view, strength));
    }

    /** The blur and the dim, or the light wash on a light screen, at [strength] of their full look. */
    @Nullable
    @RequiresApi(Build.VERSION_CODES.S)
    static RenderEffect effect(View view, float strength) {
        if (strength <= 0f) return null;
        boolean night = (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        ColorMatrix shade = new ColorMatrix();
        if (night) {
            float keep = 1f - DIM_DARK * strength;
            shade.setScale(keep, keep, keep, 1f);
        } else {
            float keep = 1f - WASH_LIGHT * strength;
            float add = 255f * WASH_LIGHT * strength;
            shade.set(new float[] {
                    keep, 0f, 0f, 0f, add,
                    0f, keep, 0f, 0f, add,
                    0f, 0f, keep, 0f, add,
                    0f, 0f, 0f, 1f, 0f});
        }
        RenderEffect colour = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(shade));
        float radius = BLUR_DP * view.getResources().getDisplayMetrics().density * strength;
        if (radius < 0.5f) return colour;
        return RenderEffect.createChainEffect(colour, RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }

    private static void fade(float to, long now) {
        if (fadeTo == to) return;
        fadeFrom = levelAt(now);
        fadeTo = to;
        fadeStart = now;
    }

    /** How far the blur has faded in at [now]. */
    static float levelAt(long now) {
        if (fadeFrom == fadeTo) return fadeTo;
        float part = Math.min(1f, Math.max(0f, (now - fadeStart) / (float) FADE_MS));
        // Eased out, so the blur comes in quick and settles.
        float eased = 1f - (1f - part) * (1f - part);
        return fadeFrom + (fadeTo - fadeFrom) * eased;
    }

    /**
     * Moves the held [row] clear of the reactions, the menu and the message bar while they cover where it was held,
     * [level] of the way there as the blur fades. Read while [actions] shows, so a menu that opens a frame late
     * still counts. While it's gone, the last move fades back out with the blur.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    static void keepInView(Activity activity, @Nullable View actions, View row, float level) {
        Rect bubble = heldAt;
        if (bubble == null) return;
        if (actions != null && fadeTo > 0f) {
            List<Rect> covers = new ArrayList<>();
            Rect reactions = shownRect(actions.findViewById(GlassChat.id(activity, REACTIONS)));
            if (reactions != null) covers.add(reactions);
            Rect menu = menuRect(activity);
            if (menu != null) covers.add(menu);
            Rect composer = shownRect(activity.findViewById(GlassChat.id(activity, COMPOSER)));
            if (composer != null) covers.add(composer);
            ViewParent parent = row.getParent();
            Rect area = parent instanceof View ? shownRect((View) parent) : null;
            if (area == null) return;
            int room = Math.round(ROOM_DP * row.getResources().getDisplayMetrics().density);
            float[] to = place(bubble, area.top, area.bottom, covers, room);
            if ((to[0] != 0f || to[1] != 1f) && (moveTo[0] == 0f && moveTo[1] == 1f)) {
                HookStatus.counted(FamilyNames.GLASS_CHAT, COUNT_MOVED);
            }
            moveTo[0] = to[0];
            moveTo[1] = to[1];
        }
        boolean still = moveTo[0] == 0f && moveTo[1] == 1f;
        if (moved.get() != row) {
            if (still) return;
            putBack();
            int[] at = new int[2];
            row.getLocationOnScreen(at);
            movedFrom[0] = row.getTranslationY();
            movedFrom[1] = row.getScaleX();
            movedFrom[2] = row.getScaleY();
            movedFrom[3] = row.getPivotX();
            movedFrom[4] = row.getPivotY();
            movedFrom[5] = row.getTranslationZ();
            moved = new WeakReference<>(row);
            row.setPivotX(bubble.exactCenterX() - at[0]);
            row.setPivotY(bubble.exactCenterY() - at[1]);
        }
        float scale = 1f + (moveTo[1] - 1f) * level;
        row.setTranslationY(movedFrom[0] + moveTo[0] * level);
        row.setScaleX(movedFrom[1] * scale);
        row.setScaleY(movedFrom[2] * scale);
        // Drawn over the rows after it in the list when it slides down onto them.
        row.setTranslationZ(movedFrom[5] + (level > 0f ? 1f : 0f));
    }

    /** Where the move of the held message is going: how far down, in pixels, and its scale. */
    private static final float[] moveTo = {0f, 1f};

    /** The message bar under the chat, which a message moved down into view stays above. */
    static final String COMPOSER = "message_composer_bar";

    /**
     * How far to move, and how much to shrink, the held message [bubble], in screen pixels, so none of [covers] sits
     * over it, keeping inside [top] to [bottom], the chat's list on screen, and [room] clear of each: {0, 1} when
     * nothing covers it. The nearest stretch tall enough wins. With none tall enough, it shrinks into the middle of
     * the tallest, never under {@link #SMALLEST}.
     */
    static float[] place(Rect bubble, int top, int bottom, List<Rect> covers, int room) {
        float[] stay = {0f, 1f};
        if (bubble.isEmpty() || bottom <= top) return stay;
        boolean covered = false;
        for (Rect each : covers) {
            if (Rect.intersects(each, bubble)) {
                covered = true;
                break;
            }
        }
        if (!covered) return stay;
        List<int[]> free = new ArrayList<>();
        free.add(new int[] {top + room, bottom - room});
        for (Rect each : covers) {
            if (each.right <= bubble.left || each.left >= bubble.right) continue;
            int from = each.top - room;
            int to = each.bottom + room;
            List<int[]> next = new ArrayList<>();
            for (int[] span : free) {
                if (to <= span[0] || from >= span[1]) {
                    next.add(span);
                    continue;
                }
                if (from > span[0]) next.add(new int[] {span[0], from});
                if (to < span[1]) next.add(new int[] {to, span[1]});
            }
            free = next;
        }
        int height = bubble.height();
        int nearest = Integer.MAX_VALUE;
        int[] tallest = null;
        for (int[] span : free) {
            int length = span[1] - span[0];
            if (tallest == null || length > tallest[1] - tallest[0]) tallest = span;
            if (length < height) continue;
            int move = Math.max(span[0], Math.min(bubble.top, span[1] - height)) - bubble.top;
            if (Math.abs(move) < Math.abs(nearest)) nearest = move;
        }
        if (nearest != Integer.MAX_VALUE) return new float[] {nearest, 1f};
        if (tallest == null || tallest[1] <= tallest[0]) return stay;
        float scale = Math.max(SMALLEST, (tallest[1] - tallest[0]) / (float) height);
        return new float[] {(tallest[0] + tallest[1]) / 2f - bubble.exactCenterY(), scale};
    }

    /** Where [view] is on screen while it shows and has a size, or null. */
    @Nullable
    static Rect shownRect(@Nullable View view) {
        if (view == null || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /**
     * Where the menu of a held message's actions is on screen, or null: the window of this app's, other than the
     * activity's own, that holds a menu row and shows.
     */
    @Nullable
    @RequiresApi(Build.VERSION_CODES.Q)
    static Rect menuRect(Activity activity) {
        int id = GlassChat.id(activity, MENU_ITEM);
        if (id == 0) return null;
        View decor = activity.getWindow().getDecorView();
        List<View> windows = windowsForTests;
        if (windows == null) windows = WindowInspector.getGlobalWindowViews();
        for (View each : windows) {
            if (each == null || each == decor || each.findViewById(id) == null) continue;
            Rect found = shownRect(each);
            if (found != null) return found;
        }
        return null;
    }

    /** Puts the moved row's own translation, scale, pivot and elevation back. */
    static void putBack() {
        View row = moved.get();
        moved = new WeakReference<>(null);
        if (row == null) return;
        row.setTranslationY(movedFrom[0]);
        row.setScaleX(movedFrom[1]);
        row.setScaleY(movedFrom[2]);
        row.setPivotX(movedFrom[3]);
        row.setPivotY(movedFrom[4]);
        row.setTranslationZ(movedFrom[5]);
    }

    /** Puts the moved row back and forgets where it was going. */
    private static void forgetMove() {
        putBack();
        moveTo[0] = 0f;
        moveTo[1] = 1f;
    }

    /** Takes every render effect written here off at once, and forgets the held message. */
    static void clear() {
        clearEffects();
        forgetMove();
        heldAt = null;
        heldFor = new WeakReference<>(null);
        held = new WeakReference<>(null);
        level = 0f;
        fadeFrom = 0f;
        fadeTo = 0f;
    }

    private static void clearEffects() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (View each : new ArrayList<>(blurred.keySet())) {
                if (each != null) each.setRenderEffect(null);
            }
        }
        blurred.clear();
    }

    /** Forgets the frame too, as the watched activity goes. */
    static void forget() {
        clear();
        frame = new WeakReference<>(null);
        missed = new WeakReference<>(null);
        searchedAt = -SEARCH_EVERY_MS;
    }

    /** Whether [view] carries the blur now, for tests. */
    static boolean blurredForTests(View view) {
        return blurred.containsKey(view);
    }

    /** How strong [view]'s blur is now, for tests, or -1 when it has none. */
    static float strengthForTests(View view) {
        Float strength = blurred.get(view);
        return strength == null ? -1f : strength;
    }
}
