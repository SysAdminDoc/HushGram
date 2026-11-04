/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.shared.L10n;

/**
 * The categories as tiles in two columns (three on a screen 600 dp wide or more), in place of
 * the one row each section has (#494). One row of the page holds the whole grid. A tile shows the
 * section's icon, title and intro; the black look keeps the icon and title. A tap opens that
 * section on this page, below the tiles, so there is no page to go to and nothing for Back to undo.
 */
final class CategoryGrid extends Preference {
    static final String KEY = "hushgram_category_grid";
    /** Marks a tile's view, so a test can find it. */
    static final String TILE_TAG = "hushgram_category_tile";

    /** What a tap on a tile does. */
    interface Opener {
        void open(HushgramPreferenceFragment.Heading section);
    }

    private final Opener opener;
    private List<HushgramPreferenceFragment.Heading> tiles = Collections.emptyList();
    private String shown = "";

    CategoryGrid(Context context, Opener opener) {
        super(context);
        this.opener = opener;
        setKey(KEY);
        setPersistent(false);
        setSelectable(false);
    }

    /** The sections to show, in page order. Redraws only when what a tile shows has changed. */
    void show(List<HushgramPreferenceFragment.Heading> next) {
        StringBuilder signature = new StringBuilder();
        for (HushgramPreferenceFragment.Heading each : next) {
            signature.append(each.getKey()).append('|').append(each.getTitle()).append('|')
                    .append(each.expanded).append(each.compact).append('|').append(each.intro).append('\n');
        }
        tiles = new ArrayList<>(next);
        if (signature.toString().equals(shown)) return;
        shown = signature.toString();
        notifyChanged();
    }

    /** Draws the tiles again, for a state the signature can't see (a new-row dot). */
    void refresh() {
        notifyChanged();
    }

    List<HushgramPreferenceFragment.Heading> tiles() {
        return tiles;
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        return grid;
    }

    @Override
    protected void onBindView(View view) {
        LinearLayout grid = (LinearLayout) view;
        grid.removeAllViews();
        Context context = grid.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.round(4 * density);
        int inset = Math.round(14 * density);
        grid.setPaddingRelative(inset, gap, inset, gap);
        int columns = context.getResources().getConfiguration().screenWidthDp >= 600 ? 3 : 2;
        for (int start = 0; start < tiles.size(); start += columns) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < columns; column++) {
                int at = start + column;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                params.setMargins(gap, gap, gap, gap);
                if (at < tiles.size()) line.addView(tile(context, tiles.get(at)), params);
                else line.addView(new View(context), params);
            }
            grid.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private View tile(Context context, HushgramPreferenceFragment.Heading section) {
        ScreenColors colors = ScreenColors.current();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * density);
        LinearLayout tile = new LinearLayout(context);
        tile.setTag(TILE_TAG);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.START);
        tile.setPaddingRelative(pad, pad, pad, pad);
        tile.setMinimumHeight(Math.round(64 * density));
        tile.setClickable(true);
        tile.setFocusable(true);
        GradientDrawable surface = new GradientDrawable();
        boolean open = section.expanded;
        // The open section's tile reads as selected: an accent outline over a faint accent fill.
        surface.setColor(open ? blend(colors.card, colors.accent) : colors.card);
        surface.setCornerRadius(Math.round(12 * density));
        surface.setStroke(Math.round((open ? 2 : 1) * density), open ? colors.accent : colors.outline);
        tile.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading), surface, null));

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(SettingsIcons.icon(context, section.icon, colors.heading));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(24 * density);
        tile.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView title = new TextView(context);
        title.setId(android.R.id.title);
        title.setText(section.getTitle());
        title.setTextColor(colors.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = Math.round(8 * density);
        tile.addView(title, titleParams);

        CharSequence intro = section.compact ? null : section.intro;
        if (!TextUtils.isEmpty(intro)) {
            TextView summary = new TextView(context);
            summary.setId(android.R.id.summary);
            summary.setText(intro);
            summary.setTextColor(colors.summary);
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryParams.topMargin = Math.round(2 * density);
            tile.addView(summary, summaryParams);
        }
        NewRowDots.paint(tile, section, colors.accent);
        tile.setOnClickListener(tapped -> opener.open(section));
        tile.setAccessibilityDelegate(new TileSemantics(section, title));
        return tile;
    }

    private static int blend(int base, int accent) {
        return Color.argb(255,
                (Color.red(base) * 85 + Color.red(accent) * 15) / 100,
                (Color.green(base) * 85 + Color.green(accent) * 15) / 100,
                (Color.blue(base) * 85 + Color.blue(accent) * 15) / 100);
    }

    /** A tile is a button that says whether its section is open, and is named by its title and intro. */
    private final class TileSemantics extends View.AccessibilityDelegate {
        private final HushgramPreferenceFragment.Heading section;
        private final TextView title;

        TileSemantics(HushgramPreferenceFragment.Heading section, TextView title) {
            this.section = section;
            this.title = title;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(Button.class.getName());
            CharSequence name = section.getTitle();
            CharSequence intro = section.compact ? null : section.intro;
            CharSequence label = TextUtils.isEmpty(intro) ? name : TextUtils.concat(name, "\n", intro);
            if (NewRowDots.TAG.equals(title.getTag())) label = TextUtils.concat(label, ", ", L10n.t("New"));
            CharSequence state = section.expanded ? L10n.t("Expanded") : L10n.t("Collapsed");
            if (Build.VERSION.SDK_INT >= 30) {
                info.setStateDescription(state);
            } else {
                label = TextUtils.concat(label, ", ", state);
            }
            info.setContentDescription(label);
            info.setClickable(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            if (!section.expanded) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK
                    || action == AccessibilityNodeInfo.ACTION_EXPAND && !section.expanded) {
                opener.open(section);
                return true;
            }
            return super.performAccessibilityAction(host, action, arguments);
        }
    }
}
