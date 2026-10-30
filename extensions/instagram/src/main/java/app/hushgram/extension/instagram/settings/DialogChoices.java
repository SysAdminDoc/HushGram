/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.ListAdapter;
import android.widget.TextView;

/** Styles the framework's real list rows without replacing selection or checkbox behavior. */
final class DialogChoices extends BaseAdapter {
    private final ListAdapter source;
    private final ScreenColors colors;

    DialogChoices(ListAdapter source, ScreenColors colors) {
        this.source = source;
        this.colors = colors;
    }

    @Override public int getCount() { return source.getCount(); }
    @Override public Object getItem(int position) { return source.getItem(position); }
    @Override public long getItemId(int position) { return source.getItemId(position); }
    @Override public boolean hasStableIds() { return source.hasStableIds(); }
    @Override public boolean isEnabled(int position) { return source.isEnabled(position); }
    @Override public boolean areAllItemsEnabled() { return source.areAllItemsEnabled(); }
    @Override public int getItemViewType(int position) { return source.getItemViewType(position); }
    @Override public int getViewTypeCount() { return source.getViewTypeCount(); }
    @Override public void registerDataSetObserver(DataSetObserver observer) { source.registerDataSetObserver(observer); }
    @Override public void unregisterDataSetObserver(DataSetObserver observer) { source.unregisterDataSetObserver(observer); }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = source.getView(position, recycled, parent);
        if (row instanceof TextView) {
            TextView choice = (TextView) row;
            float density = row.getResources().getDisplayMetrics().density;
            choice.setTextColor(ScreenColors.dimmedWhenDisabled(colors.title));
            choice.setTextSize(16);
            choice.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            choice.setSingleLine(false);
            choice.setMaxLines(Integer.MAX_VALUE);
            if (choice instanceof CheckedTextView) {
                choice.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                choice.setMinHeight(Math.round(48 * density));
                choice.setMinimumHeight(Math.round(48 * density));
                ColorStateList indicator = new ColorStateList(new int[][]{
                        {-android.R.attr.state_enabled, android.R.attr.state_checked},
                        {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                        new int[]{ScreenColors.half(colors.heading), ScreenColors.half(colors.summary),
                                colors.heading, colors.summary});
                ((CheckedTextView) choice).setCheckMarkTintList(indicator);
                // Material's framework layout uses a start drawable for its radio indicator.
                for (android.graphics.drawable.Drawable drawable : choice.getCompoundDrawablesRelative()) {
                    if (drawable != null) drawable.mutate().setTintList(indicator);
                }
                GradientDrawable background = new GradientDrawable();
                background.setColor(colors.choiceFill(android.graphics.Color.TRANSPARENT));
                background.setStroke(Math.round(density), colors.interactionOutline(android.graphics.Color.TRANSPARENT));
                background.setCornerRadius(Math.round(8 * density));
                choice.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading),
                        background, null).mutate());
                // A native choice is a compact full-width row, with room to grow when text wraps.
                int verticalPadding = (int) (8 * density);
                choice.setPaddingRelative(Math.round(24 * density), verticalPadding,
                        Math.round(24 * density), verticalPadding);
                return row;
            }
            int gap = Math.round(4 * density);
            int side = Math.round(18 * density);
            choice.setMinHeight(Math.round(48 * density) + 2 * gap);
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(colors.choiceFill(colors.card));
            surface.setStroke(Math.round(density), colors.interactionOutline(colors.outline));
            surface.setCornerRadius(Math.round(12 * density));
            choice.setBackground(new RippleDrawable(ScreenColors.interactionRipple(colors.heading),
                    new InsetDrawable(surface, side, gap, side, gap), null).mutate());
            // Rounding both sides up at densities such as 450dpi exceeds the measured 48dp row.
            int verticalPadding = (int) (12 * density) + gap;
            choice.setPaddingRelative(side + Math.round(16 * density), verticalPadding,
                    side + Math.round(16 * density), verticalPadding);
        }
        return row;
    }
}
