/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.shared.L10n;

/**
 * A row of chips under Search that scrolls sideways (#642). On a category's own page it holds every
 * category with its icon and name: a tap puts that category on the page in place of this one, so
 * moving between them doesn't go back through the list, and Back still returns to the list. On the
 * list of categories it holds All and the groups, and a tap shows that group's categories alone.
 * The chip that's showing reads as selected and is scrolled into view. A copy of the row stays over
 * the top of the list once the row itself has scrolled off it (#650).
 */
final class CategoryStrip extends Preference {
    static final String KEY = "hushgram_category_strip";
    /** Marks a chip's view, so a test can find it. */
    static final String CHIP_TAG = "hushgram_category_chip";
    /** Marks the copy that stays over the top of the list. */
    static final String PINNED_TAG = "hushgram_category_strip_pinned";
    /**
     * A chip's name. Not Android's title id: PreferenceFragment hides the first view with that id
     * on its page when the screen has no title, and before the list fills that was the pinned
     * copy's first chip, which showed empty (#650). A row's own title lookup found the chips too.
     */
    static final int NAME_ID = View.generateViewId();

    /** What a tap on a category's chip does. */
    interface Opener {
        void open(HushgramPreferenceFragment.Heading section);
    }

    /** What a tap on a group's chip does: that group, or -1 for All. */
    interface GroupPicker {
        void pick(int group);
    }

    private final Opener opener;
    private final GroupPicker picker;
    private List<HushgramPreferenceFragment.Heading> chips = Collections.emptyList();
    private HushgramPreferenceFragment.Heading selected;
    private List<Integer> groups = Collections.emptyList();
    private int group = -1;
    private String shown = "";
    @Nullable private HorizontalScrollView pinned;

    CategoryStrip(Context context, Opener opener, GroupPicker picker) {
        super(context);
        this.opener = opener;
        this.picker = picker;
        setKey(KEY);
        setPersistent(false);
        setSelectable(false);
    }

    /** The sections to offer, in page order, and the one on the page. Redraws only when that changed. */
    void show(List<HushgramPreferenceFragment.Heading> next, HushgramPreferenceFragment.Heading page) {
        StringBuilder signature = new StringBuilder(page.getKey()).append('\n');
        for (HushgramPreferenceFragment.Heading each : next) {
            signature.append(each.getKey()).append('|').append(each.getTitle()).append('|').append(each.icon).append('\n');
        }
        chips = new ArrayList<>(next);
        selected = page;
        groups = Collections.emptyList();
        redraw(signature.toString());
    }

    /** The groups to offer, in order, and the one picked, or -1 for All. Redraws only when that changed. */
    void showGroups(List<Integer> next, int picked) {
        StringBuilder signature = new StringBuilder("groups ").append(picked).append('\n');
        for (int each : next) signature.append(each).append('|').append(CategoryGroups.title(each)).append('\n');
        groups = new ArrayList<>(next);
        group = picked;
        chips = Collections.emptyList();
        selected = null;
        redraw(signature.toString());
    }

    List<HushgramPreferenceFragment.Heading> chips() {
        return chips;
    }

    /** The copy for over the top of the list, made for [context] the first time. Starts hidden. */
    View pinned(Context context) {
        if (pinned == null || pinned.getContext() != context) {
            pinned = scroller(context);
            pinned.setTag(PINNED_TAG);
            pinned.setVisibility(View.INVISIBLE);
            center(pinned, fill(pinned));
        }
        return pinned;
    }

    @Nullable View pinnedIfMade() {
        return pinned;
    }

    private void redraw(String signature) {
        if (signature.equals(shown)) return;
        shown = signature;
        if (pinned != null) center(pinned, fill(pinned));
        notifyChanged();
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        return scroller(getContext());
    }

    @Override
    protected void onBindView(View view) {
        HorizontalScrollView scroll = (HorizontalScrollView) view;
        center(scroll, fill(scroll));
    }

    private static HorizontalScrollView scroller(Context context) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** Puts the chips in [scroll]'s row and answers the selected one's view. */
    @Nullable
    private View fill(HorizontalScrollView scroll) {
        LinearLayout row = (LinearLayout) scroll.getChildAt(0);
        row.removeAllViews();
        Context context = row.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.round(4 * density);
        row.setPaddingRelative(Math.round(14 * density), gap, Math.round(14 * density), Math.round(8 * density));
        // The copy covers the rows scrolling under it.
        if (scroll == pinned) scroll.setBackgroundColor(ScreenColors.current().background);
        View current = null;
        if (groups.isEmpty()) {
            for (HushgramPreferenceFragment.Heading section : chips) {
                boolean open = section == selected;
                View chip = chip(context, section.getTitle(), section.icon, section, open, () -> opener.open(section));
                if (open) current = chip;
                add(row, chip, density);
            }
        } else {
            View all = chip(context, L10n.t("All"), null, null, group < 0, () -> picker.pick(-1));
            if (group < 0) current = all;
            add(row, all, density);
            for (int each : groups) {
                boolean open = each == group;
                View chip = chip(context, CategoryGroups.title(each), null, null, open, () -> picker.pick(each));
                if (open) current = chip;
                add(row, chip, density);
            }
        }
        return current;
    }

    private static void add(LinearLayout row, View chip, float density) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Math.round(8 * density));
        row.addView(chip, params);
    }

    private static void center(HorizontalScrollView scroll, @Nullable View current) {
        if (current != null) scroll.post(() -> {
            // Centered where it can be, so the chips on both sides of it show.
            int x = current.getLeft() - (scroll.getWidth() - current.getWidth()) / 2;
            scroll.scrollTo(Math.max(0, x), 0);
        });
    }

    private View chip(Context context, CharSequence name, @Nullable String iconCode,
                      @Nullable HushgramPreferenceFragment.Heading section, boolean open, Runnable action) {
        ScreenColors colors = ScreenColors.current();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout chip = new LinearLayout(context);
        chip.setTag(CHIP_TAG);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int side = Math.round(14 * density);
        chip.setPaddingRelative(iconCode == null ? side : Math.round(12 * density), 0, side, 0);
        chip.setMinimumHeight(Math.round(40 * density));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setSelected(open);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(open ? blend(colors.card, colors.accent) : colors.card);
        surface.setCornerRadius(Math.round(20 * density));
        surface.setStroke(Math.round((open ? 2 : 1) * density), open ? colors.accent : colors.outline);
        chip.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading), surface, null));

        if (iconCode != null) {
            ImageView icon = new ImageView(context);
            icon.setImageDrawable(SettingsIcons.icon(context, iconCode, open ? colors.accent : colors.heading));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int size = Math.round(18 * density);
            chip.addView(icon, new LinearLayout.LayoutParams(size, size));
        }

        TextView title = new TextView(context);
        title.setId(NAME_ID);
        title.setText(name);
        title.setSingleLine(true);
        title.setTextColor(colors.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (iconCode != null) titleParams.setMarginStart(Math.round(8 * density));
        chip.addView(title, titleParams);
        if (section != null) NewRowDots.paintTitle(title, section, colors.accent);
        chip.setOnClickListener(tapped -> {
            if (!open) action.run();
        });
        chip.setAccessibilityDelegate(new ChipSemantics(action, open));
        return chip;
    }

    private static int blend(int base, int accent) {
        return Color.argb(255,
                (Color.red(base) * 85 + Color.red(accent) * 15) / 100,
                (Color.green(base) * 85 + Color.green(accent) * 15) / 100,
                (Color.blue(base) * 85 + Color.blue(accent) * 15) / 100);
    }

    /** A chip is a button named by what it shows, and the one showing says it's selected. */
    private static final class ChipSemantics extends View.AccessibilityDelegate {
        private final Runnable action;
        private final boolean open;

        ChipSemantics(Runnable action, boolean open) {
            this.action = action;
            this.open = open;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(Button.class.getName());
            info.setSelected(open);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                if (!open) this.action.run();
                return true;
            }
            return super.performAccessibilityAction(host, action, arguments);
        }
    }
}
