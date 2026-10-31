/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.misc.AppIcon;
import app.hushgram.extension.instagram.misc.AppIconChoice;

/**
 * The App icon list's adapter with each row's real icon drawn before its name (#321, #327): the same
 * drawable Android's launcher reads from that choice's alias, at row size.
 */
final class IconChoices extends BaseAdapter {
    private final ListAdapter source;
    private final Context context;
    private final AppIconChoice[] choices;

    IconChoices(Context context, ListAdapter source, AppIconChoice[] choices) {
        this.context = context;
        this.source = source;
        this.choices = choices;
    }

    /** The icon [choice] gives the home screen, drawn square at [size] pixels, or null when it can't be read. */
    @Nullable
    static Drawable preview(Context context, AppIconChoice choice, int size) {
        Drawable icon = AppIcon.preview(context, choice);
        if (icon == null) return null;
        icon = icon.mutate();
        icon.setBounds(0, 0, size, size);
        return icon;
    }

    @Override public int getCount() { return source.getCount(); }
    @Override public Object getItem(int position) { return source.getItem(position); }
    @Override public long getItemId(int position) { return source.getItemId(position); }
    @Override public boolean hasStableIds() { return source.hasStableIds(); }
    @Override public int getItemViewType(int position) { return source.getItemViewType(position); }
    @Override public int getViewTypeCount() { return source.getViewTypeCount(); }
    @Override public boolean areAllItemsEnabled() { return source.areAllItemsEnabled(); }
    @Override public boolean isEnabled(int position) { return source.isEnabled(position); }
    @Override public void registerDataSetObserver(DataSetObserver observer) { source.registerDataSetObserver(observer); }
    @Override public void unregisterDataSetObserver(DataSetObserver observer) { source.unregisterDataSetObserver(observer); }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = source.getView(position, recycled, parent);
        if (row instanceof TextView && position >= 0 && position < choices.length) {
            TextView text = (TextView) row;
            float density = context.getResources().getDisplayMetrics().density;
            int size = Math.round(40 * density);
            text.setCompoundDrawablesRelative(preview(context, choices[position], size), null, null, null);
            text.setCompoundDrawablePadding(Math.round(12 * density));
        }
        return row;
    }
}
