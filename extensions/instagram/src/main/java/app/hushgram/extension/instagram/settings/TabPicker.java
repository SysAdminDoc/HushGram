/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.reels.TabOrder;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * The Show and reorder tabs list (#207): every tab Instagram's bar can hold, with a box for whether
 * it's on the bar and a handle to drag it up or down, like Piko's.
 *
 * <p>Nothing here keeps a choice of its own. A box stands for the hide or show switch its tab
 * already has under Tab bar or Reels, and the order is {@link Settings#TAB_ORDER}, with the hidden
 * tabs in it too, so one turned back on comes back where it was put. Only what changed is saved, on
 * OK. Everything is read as saved, so while HushGram is paused, and Instagram gets its own bar, the
 * list still shows the user's choice and OK doesn't save over it. Two boxes can't change and read
 * in the summary's colour: Messages on a bar Instagram put it on, since nothing takes it off, and a
 * tab Instagram left off with only a hide switch (Create on most 451 accounts), since nothing adds it.
 */
final class TabPicker {
    static final String HOME = "FEED";
    static final String SEARCH = "SEARCH";
    static final String REELS = "CLIPS";
    static final String MESSAGES = "DIRECT";
    static final String ACTIVITY = "NEWS";
    static final String CREATE = "CREATION";
    static final String PROFILE = "PROFILE";

    private TabPicker() {
    }

    /** Every tab in the list's order: the saved order, then the bar's, then the ones it doesn't hold. */
    static List<String> rows(@Nullable String saved) {
        Set<String> names = new LinkedHashSet<>(TabOrder.parse(saved));
        names.addAll(TabOrder.choices(saved));
        names.addAll(TabOrder.TABS);
        return new ArrayList<>(names);
    }

    /**
     * Whether Instagram's own bar holds [tab]: on the bar as last built, or taken off it by its hide
     * switch, which the built bar no longer shows. Before a bar is built, as most accounts have it.
     */
    private static boolean instagramHas(String tab) {
        List<String> built = TabOrder.lastBuilt();
        if (built == null) return !ACTIVITY.equals(tab);
        if (built.contains(tab)) return true;
        app.hushgram.extension.shared.settings.BooleanSetting hide = REELS.equals(tab) ? Settings.HIDE_REELS_TAB : hideOnly(tab);
        return hide != null && hide.savedValue();
    }

    /** [tab]'s hide switch, for the tabs that only have one, or null. */
    @Nullable
    private static app.hushgram.extension.shared.settings.BooleanSetting hideOnly(String tab) {
        switch (tab) {
            case HOME:
                return Settings.HIDE_HOME_TAB;
            case SEARCH:
                return Settings.HIDE_SEARCH_TAB;
            case CREATE:
                return Settings.HIDE_CREATE_TAB;
            case PROFILE:
                return Settings.HIDE_PROFILE_TAB;
            default:
                return null;
        }
    }

    /** Whether [tab] is on the bar by its switches and what Instagram's bar holds. */
    static boolean shown(String tab) {
        app.hushgram.extension.shared.settings.BooleanSetting hide = hideOnly(tab);
        if (hide != null) return !hide.savedValue() && instagramHas(tab);
        switch (tab) {
            case REELS:
                return !Settings.HIDE_REELS_TAB.savedValue() && (instagramHas(REELS) || Settings.SHOW_REELS_TAB.savedValue());
            case MESSAGES:
                return (Settings.SHOW_MESSAGES_TAB.savedValue() || instagramHas(MESSAGES))
                        && !Settings.HIDE_MESSAGES_TAB.savedValue();
            case ACTIVITY:
                return Settings.SHOW_ACTIVITY_TAB.savedValue();
            default:
                return false;
        }
    }

    /**
     * Whether [tab]'s box can't change: a tab with only a hide switch that Instagram left off the bar
     * (Create on most accounts on 451), which nothing can add.
     */
    static boolean fixed(String tab) {
        return hideOnly(tab) != null && !instagramHas(tab);
    }

    /** Whether [tab] is on the bar with every switch at its default: wherever Instagram's own bar has it. */
    static boolean shownByDefault(String tab) {
        return !ACTIVITY.equals(tab) && instagramHas(tab);
    }

    /** Turns [tab]'s switches to put it on the bar or take it off. */
    static void setShown(String tab, boolean on) {
        switch (tab) {
            case HOME:
                Settings.HIDE_HOME_TAB.save(!on);
                break;
            case SEARCH:
                Settings.HIDE_SEARCH_TAB.save(!on);
                break;
            case CREATE:
                Settings.HIDE_CREATE_TAB.save(!on);
                break;
            case PROFILE:
                Settings.HIDE_PROFILE_TAB.save(!on);
                break;
            case REELS:
                // Turned on where Instagram's bar has no Reels, Show the Reels tab adds it.
                boolean add = on && !Settings.SHOW_REELS_TAB.savedValue() && !instagramHas(REELS);
                Settings.HIDE_REELS_TAB.save(!on);
                if (add) Settings.SHOW_REELS_TAB.save(true);
                break;
            case MESSAGES:
                // Show the Messages tab adds it where Instagram's bar has none, and Hide the Messages
                // tab takes Instagram's own off (#298). Taken off, the tab HushGram added goes first.
                if (on) {
                    Settings.HIDE_MESSAGES_TAB.save(false);
                    if (!instagramHas(MESSAGES)) Settings.SHOW_MESSAGES_TAB.save(true);
                } else if (Settings.SHOW_MESSAGES_TAB.savedValue()) {
                    Settings.SHOW_MESSAGES_TAB.save(false);
                } else {
                    Settings.HIDE_MESSAGES_TAB.save(true);
                }
                break;
            case ACTIVITY:
                Settings.SHOW_ACTIVITY_TAB.save(on);
                break;
            default:
                break;
        }
    }

    /**
     * Saves the list: [order] as the tab order, or Instagram's when it's the list's own order with
     * nothing saved, and each tab in [ticked] or not that changed from how it was. [reset] says
     * Reset was pressed since the list opened.
     */
    static void save(List<String> order, Set<String> ticked, boolean reset) {
        // Instagram's order as Reset showed it, before a switch below changes which tabs it holds.
        // Paused, no bar is kept, so the list's own order is only a guess at Instagram's, and an
        // order that happens to match it is the user's, saved as it is unless Reset asked for none.
        String chosen = order.equals(rows("")) && (reset || !HushgramPause.isPaused()) ? "" : TabOrder.join(order);
        for (String tab : order) {
            boolean on = ticked.contains(tab);
            if (on != shown(tab) && !fixed(tab)) setShown(tab, on);
        }
        if (!chosen.equals(Settings.TAB_ORDER.savedValue())) Settings.TAB_ORDER.save(chosen);
    }

    // ------------------------------------------------------------------ the list

    /** The list's rows, one per tab in [order], each ticked as [ticked] says. */
    static LinearLayout list(Context context, List<String> order, Set<String> ticked) {
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 12);
        list.setPadding(pad, pad / 2, pad, pad / 2);
        fill(list, order, ticked);
        return list;
    }

    /** Puts [list]'s rows back to [order] and [ticked]. */
    static void fill(LinearLayout list, List<String> order, Set<String> ticked) {
        list.removeAllViews();
        for (String tab : order) list.addView(row(list, tab, ticked.contains(tab)));
    }

    /** The tabs in [list]'s order. */
    static List<String> orderOf(LinearLayout list) {
        List<String> order = new ArrayList<>(list.getChildCount());
        for (int i = 0; i < list.getChildCount(); i++) order.add(String.valueOf(list.getChildAt(i).getTag()));
        return order;
    }

    /** The tabs [list] has ticked. */
    static Set<String> tickedOf(LinearLayout list) {
        Set<String> ticked = new LinkedHashSet<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (box(row).isChecked()) ticked.add(String.valueOf(row.getTag()));
        }
        return ticked;
    }

    static CheckBox box(View row) {
        return (CheckBox) ((ViewGroup) row).getChildAt(0);
    }

    static View handle(View row) {
        return ((ViewGroup) row).getChildAt(1);
    }

    /**
     * Moves [list]'s row at [index] one place up, the first staying where it is. The row above moves
     * down past it instead, so a dragged row stays attached and its touch goes on.
     */
    static void moveUp(LinearLayout list, int index) {
        if (index <= 0 || index >= list.getChildCount()) return;
        View above = list.getChildAt(index - 1);
        list.removeViewAt(index - 1);
        list.addView(above, index);
    }

    /** Moves [list]'s row at [index] one place down, the last staying where it is, by moving the one below up. */
    static void moveDown(LinearLayout list, int index) {
        if (index < 0 || index >= list.getChildCount() - 1) return;
        View below = list.getChildAt(index + 1);
        list.removeViewAt(index + 1);
        list.addView(below, index);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static View row(LinearLayout list, String tab, boolean on) {
        Context context = list.getContext();
        ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(tab);
        row.setMinimumHeight(dp(context, 52));

        CheckBox box = new CheckBox(context);
        box.setText(HushgramPreferenceFragment.tabLabel(tab));
        box.setTextSize(16);
        boolean fixed = fixed(tab);
        // A box that can't change reads in the summary's colour, so it doesn't look like the others.
        box.setTextColor(fixed ? colors.summary : colors.title);
        box.setButtonTintList(ColorStateList.valueOf(fixed ? colors.summary : colors.accent));
        box.setChecked(fixed ? shown(tab) : on);
        box.setEnabled(!fixed);
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView handle = new TextView(context);
        handle.setText("≡");
        handle.setTextSize(26);
        handle.setTextColor(colors.summary);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription(L10n.f("Drag to move %1$s", HushgramPreferenceFragment.tabLabel(tab)));
        int side = dp(context, 48);
        row.addView(handle, new LinearLayout.LayoutParams(side, side));
        handle.setOnTouchListener(new Drag(list, row));
        return row;
    }

    /** A drag on a row's handle: past half a row up or down, the row trades places with its neighbour. */
    private static final class Drag implements View.OnTouchListener {
        private final LinearLayout list;
        private final View row;
        private float lastY;

        Drag(LinearLayout list, View row) {
            this.list = list;
            this.row = row;
        }

        @Override public boolean onTouch(View handle, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastY = event.getRawY();
                    row.setAlpha(0.6f);
                    if (list.getParent() != null) list.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float step = Math.max(1, row.getHeight()) / 2f;
                    int at = list.indexOfChild(row);
                    float moved = event.getRawY() - lastY;
                    if (moved < -step && at > 0) {
                        moveUp(list, at);
                        lastY -= row.getHeight();
                    } else if (moved > step && at < list.getChildCount() - 1) {
                        moveDown(list, at);
                        lastY += row.getHeight();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    row.setAlpha(1f);
                    return true;
                default:
                    return false;
            }
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
