/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.stories.StoryViewers;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.ui.Dim;

/**
 * Search viewers in the viewer list (#833): with Keep story viewers and Search bar in the viewer list
 * on, the list of who viewed one of your stories gets a Search viewers bar at its top, as Instagram's
 * own list had before its search became part of a subscription. A tap opens the story viewers page on
 * that story with the search box ready, over Instagram's list, and Back returns to the list.
 *
 * <p>The patch hands each row of the list to {@link #row} as Instagram binds it. The bar goes in once
 * per list: above it when the list sits in a column, or else over the list's top in the nearest plain
 * frame holding it, the list's top padding grown so its first row starts under the bar, the bar
 * following the list on every frame. Nothing is sent: the search reads the viewers kept on this phone.
 */
public final class StoryViewerSearch {
    static final String FAMILY = FamilyNames.STORY_VIEWERS;
    static final String STEP = "story viewer search";
    static final String ADDED = "Search viewers bar added to a viewer list";
    static final String NO_PLACE = "no plain holder for the Search viewers bar";

    /** How far up from the list a frame to hold the bar is looked for. */
    static final int MAX_LEVELS = 8;

    /** The username in one of the list's rows, the same name in every 457 build. */
    static final String USERNAME = "story_viewers_list_interaction_username";

    /** The bar's height, and the room around it, in dp. */
    static final int HEIGHT_DP = 36;
    static final int SIDE_DP = 16;
    static final int GAP_DP = 8;

    /** The lists that got a bar, so a list gets one however many rows it binds. */
    private static final Map<View, Boolean> BARS = new WeakHashMap<>();

    private static final String RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView";

    /** The username's view id, 0 before it's looked up. */
    private static volatile int usernameId;

    private StoryViewerSearch() {
    }

    /**
     * The hook, first thing where Instagram binds one row of a story's viewer list: puts the bar on
     * the row's list once the row is in it. Changes nothing of the row's and never throws.
     */
    public static void row(@Nullable View row) {
        try {
            if (row == null || !on()) return;
            HookStatus.invoked(FAMILY);
            // A row is bound before it goes in the list; a post runs once it's there.
            row.post(() -> attach(row));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, STEP, failure);
        }
    }

    /** Whether Keep story viewers and the bar's switch are both on. Off until the settings are ready. */
    static boolean on() {
        try {
            return StoryViewers.on() && Settings.STORY_VIEWERS_SEARCH.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Puts the bar on [row]'s list unless it has one. Never throws. */
    static void attach(View row) {
        try {
            View list = listOf(row);
            if (list == null || !on()) return;
            synchronized (BARS) {
                if (BARS.containsKey(list)) return;
                BARS.put(list, Boolean.TRUE);
            }
            place(list, bar(list.getContext(), list));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, STEP, failure);
        }
    }

    /** The scrolling list [row] is in, or null before it's in one. */
    @Nullable
    static View listOf(View row) {
        for (ViewParent parent = row.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (isList(parent.getClass())) return (View) parent;
        }
        return null;
    }

    private static boolean isList(Class<?> type) {
        for (Class<?> at = type; at != null && at != View.class; at = at.getSuperclass()) {
            if (RECYCLER_VIEW.equals(at.getName())) return true;
        }
        return false;
    }

    /**
     * Puts [bar] with [list]: above it when its parent is a plain column, or else over its top in the
     * nearest plain frame holding it, with the list's top padding grown by the bar's room so the first
     * row starts under it. A parent that lays out only some of its children, as a pull-to-refresh
     * holder does, would leave the bar unsized, so only plain ones take it. Answers whether it went in.
     */
    static boolean place(View list, View bar) {
        int side = Dim.dp(SIDE_DP);
        int gap = Dim.dp(GAP_DP);
        int height = Dim.dp(HEIGHT_DP);
        ViewParent parent = list.getParent();
        if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL
                && plain(parent.getClass(), LinearLayout.class)) {
            LinearLayout column = (LinearLayout) parent;
            LinearLayout.LayoutParams room = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
            room.setMargins(side, gap, side, gap);
            column.addView(bar, column.indexOfChild(list), room);
            HookStatus.counted(FAMILY, ADDED);
            return true;
        }
        FrameLayout holder = holderOf(list);
        if (holder == null) {
            HookStatus.counted(FAMILY, NO_PLACE);
            return false;
        }
        int width = list.getWidth() > 2 * side ? list.getWidth() - 2 * side : ViewGroup.LayoutParams.MATCH_PARENT;
        // Last, so it draws over the list's padding.
        holder.addView(bar, new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT));
        list.setPaddingRelative(list.getPaddingStart(), list.getPaddingTop() + height + 2 * gap,
                list.getPaddingEnd(), list.getPaddingBottom());
        Runnable keep = () -> follow(list, holder, bar, side, gap);
        ViewTreeObserver.OnPreDrawListener drawn = () -> {
            keep.run();
            return true;
        };
        list.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> keep.run());
        list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                view.getViewTreeObserver().addOnPreDrawListener(drawn);
                keep.run();
            }

            @Override public void onViewDetachedFromWindow(View view) {
                view.getViewTreeObserver().removeOnPreDrawListener(drawn);
                bar.setVisibility(View.INVISIBLE);
            }
        });
        if (list.isAttachedToWindow()) list.getViewTreeObserver().addOnPreDrawListener(drawn);
        keep.run();
        HookStatus.counted(FAMILY, ADDED);
        return true;
    }

    /** The nearest plain frame [list] is in, a few levels up at most, or null. */
    @Nullable
    static FrameLayout holderOf(View list) {
        ViewParent parent = list.getParent();
        for (int level = 0; level < MAX_LEVELS && parent instanceof ViewGroup; level++, parent = parent.getParent()) {
            if (parent instanceof FrameLayout && plain(parent.getClass(), FrameLayout.class)) return (FrameLayout) parent;
        }
        return null;
    }

    /**
     * Whether [type] lays out and takes children as [base] does: no class from it down to [base] has
     * its own onMeasure, onLayout or addView. A fragment's container takes only its fragments' views.
     */
    static boolean plain(Class<?> type, Class<?> base) {
        try {
            for (Class<?> at = type; at != base; at = at.getSuperclass()) {
                if (at == null) return false;
                for (Method method : at.getDeclaredMethods()) {
                    String name = method.getName();
                    if (name.equals("onMeasure") || name.equals("onLayout") || name.equals("addView")) return false;
                }
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Keeps [bar] in [holder] over [list]'s top, [side] in from its edges and [gap] down, as wide as
     * the list allows, and shown only while the list is.
     */
    static void follow(View list, ViewGroup holder, View bar, int side, int gap) {
        int[] at = new int[2];
        int[] base = new int[2];
        list.getLocationInWindow(at);
        holder.getLocationInWindow(base);
        float x = at[0] - base[0] + holder.getScrollX() + side - bar.getLeft();
        float y = at[1] - base[1] + holder.getScrollY() + gap - bar.getTop();
        if (bar.getTranslationX() != x) bar.setTranslationX(x);
        if (bar.getTranslationY() != y) bar.setTranslationY(y);
        ViewGroup.LayoutParams room = bar.getLayoutParams();
        int width = list.getWidth() - 2 * side;
        if (room != null && width > 0 && room.width != width) {
            room.width = width;
            bar.post(bar::requestLayout);
        }
        int shown = list.isShown() && list.isAttachedToWindow() ? View.VISIBLE : View.INVISIBLE;
        if (bar.getVisibility() != shown) bar.setVisibility(shown);
    }

    /** The bar: a rounded search field in the screen's colors, saying Search viewers, that opens the search. */
    static TextView bar(Context context, View list) {
        ScreenColors palette = ScreenColors.instagramFor(context);
        TextView bar = new TextView(context);
        bar.setText(L10n.t(context, "Search viewers"));
        bar.setTextColor(palette.summary);
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.setSingleLine(true);
        bar.setEllipsize(TextUtils.TruncateAt.END);
        bar.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        bar.setPaddingRelative(Dim.dp(12), 0, Dim.dp(12), 0);
        Drawable glass = SettingsIcons.icon(context, SettingsIcons.SEARCH, palette.summary);
        glass.setBounds(0, 0, Dim.dp(18), Dim.dp(18));
        bar.setCompoundDrawablesRelative(glass, null, null, null);
        bar.setCompoundDrawablePadding(Dim.dp(10));
        GradientDrawable field = new GradientDrawable();
        field.setColor(palette.iconSurface);
        field.setCornerRadius(Dim.dp(10));
        bar.setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), field, null));
        bar.setFocusable(true);
        bar.setClickable(true);
        bar.setOnClickListener(tapped -> open(list));
        return bar;
    }

    /**
     * Opens the story viewers page on the story [list] shows, the kept story whose viewers hold most of
     * the usernames on screen, or the one Instagram listed last. Answers the page, or null when it
     * couldn't open.
     */
    @Nullable
    static StoryViewersPage open(View list) {
        try {
            Context context = list.getContext();
            String story = StoryViewers.storyListing(context, usernames(list));
            return StoryViewersPage.openStory(context, story);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "story viewer search open", failure);
            Utils.showToastShort(L10n.t("Couldn't open that one."));
            return null;
        }
    }

    /** The usernames [list]'s rows show now, in lower case. */
    static List<String> usernames(View list) {
        List<String> names = new ArrayList<>();
        int id = usernameId;
        if (id == 0) {
            id = list.getResources().getIdentifier(USERNAME, "id", Utils.resourcePackage(list.getContext()));
            usernameId = id;
        }
        if (id != 0) collect(list, id, names);
        return names;
    }

    private static void collect(View view, int id, List<String> names) {
        if (view.getId() == id && view instanceof TextView) {
            String name = String.valueOf(((TextView) view).getText()).trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) names.add(name);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), id, names);
    }

    /** Forgets the lists that got a bar and the username's id, setting it to [id]. For tests. */
    static void forgetForTests(int id) {
        synchronized (BARS) {
            BARS.clear();
        }
        usernameId = id;
    }
}
