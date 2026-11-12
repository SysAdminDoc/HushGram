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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every category as a chip with its icon and name, in one row that scrolls sideways under Search
 * on a category's own page (#642). A tap puts that category on the page in place of this one, so
 * moving between them doesn't go back through the list, and Back still returns to the list. The
 * page's own chip reads as selected and is scrolled into view.
 */
final class CategoryStrip extends Preference {
    static final String KEY = "hushgram_category_strip";
    /** Marks a chip's view, so a test can find it. */
    static final String CHIP_TAG = "hushgram_category_chip";

    /** What a tap on a chip does. */
    interface Opener {
        void open(HushgramPreferenceFragment.Heading section);
    }

    private final Opener opener;
    private List<HushgramPreferenceFragment.Heading> chips = Collections.emptyList();
    private HushgramPreferenceFragment.Heading selected;
    private String shown = "";

    CategoryStrip(Context context, Opener opener) {
        super(context);
        this.opener = opener;
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
        if (signature.toString().equals(shown)) return;
        shown = signature.toString();
        notifyChanged();
    }

    List<HushgramPreferenceFragment.Heading> chips() {
        return chips;
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    @Override
    protected void onBindView(View view) {
        HorizontalScrollView scroll = (HorizontalScrollView) view;
        LinearLayout row = (LinearLayout) scroll.getChildAt(0);
        row.removeAllViews();
        Context context = row.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.round(4 * density);
        row.setPaddingRelative(Math.round(14 * density), gap, Math.round(14 * density), Math.round(8 * density));
        View current = null;
        for (HushgramPreferenceFragment.Heading section : chips) {
            View chip = chip(context, section, section == selected);
            if (section == selected) current = chip;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(Math.round(8 * density));
            row.addView(chip, params);
        }
        View page = current;
        if (page != null) scroll.post(() -> {
            // Centered where it can be, so the chips on both sides of it show.
            int x = page.getLeft() - (scroll.getWidth() - page.getWidth()) / 2;
            scroll.scrollTo(Math.max(0, x), 0);
        });
    }

    private View chip(Context context, HushgramPreferenceFragment.Heading section, boolean open) {
        ScreenColors colors = ScreenColors.current();
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout chip = new LinearLayout(context);
        chip.setTag(CHIP_TAG);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        int side = Math.round(12 * density);
        chip.setPaddingRelative(side, 0, Math.round(14 * density), 0);
        chip.setMinimumHeight(Math.round(40 * density));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setSelected(open);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(open ? blend(colors.card, colors.accent) : colors.card);
        surface.setCornerRadius(Math.round(20 * density));
        surface.setStroke(Math.round((open ? 2 : 1) * density), open ? colors.accent : colors.outline);
        chip.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading), surface, null));

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(SettingsIcons.icon(context, section.icon, open ? colors.accent : colors.heading));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(18 * density);
        chip.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView title = new TextView(context);
        title.setId(android.R.id.title);
        title.setText(section.getTitle());
        title.setSingleLine(true);
        title.setTextColor(colors.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMarginStart(Math.round(8 * density));
        chip.addView(title, titleParams);
        NewRowDots.paint(chip, section, colors.accent);
        chip.setOnClickListener(tapped -> {
            if (!open) opener.open(section);
        });
        chip.setAccessibilityDelegate(new ChipSemantics(section, open));
        return chip;
    }

    private static int blend(int base, int accent) {
        return Color.argb(255,
                (Color.red(base) * 85 + Color.red(accent) * 15) / 100,
                (Color.green(base) * 85 + Color.green(accent) * 15) / 100,
                (Color.blue(base) * 85 + Color.blue(accent) * 15) / 100);
    }

    /** A chip is a button named by its category, and the page's own one says it's selected. */
    private final class ChipSemantics extends View.AccessibilityDelegate {
        private final HushgramPreferenceFragment.Heading section;
        private final boolean open;

        ChipSemantics(HushgramPreferenceFragment.Heading section, boolean open) {
            this.section = section;
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
                if (!open) opener.open(section);
                return true;
            }
            return super.performAccessibilityAction(host, action, arguments);
        }
    }
}
