/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.database.DataSetObserver;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;

/**
 * A choice list's adapter with some rows greyed out and not tappable, for a choice this phone can't
 * take (Material You before Android 12). The row's own text says why.
 */
final class DisabledChoices extends BaseAdapter {
    private final ListAdapter source;
    private final boolean[] disabled;

    DisabledChoices(ListAdapter source, boolean[] disabled) {
        this.source = source;
        this.disabled = disabled;
    }

    private boolean off(int position) {
        return position >= 0 && position < disabled.length && disabled[position];
    }

    @Override public int getCount() { return source.getCount(); }
    @Override public Object getItem(int position) { return source.getItem(position); }
    @Override public long getItemId(int position) { return source.getItemId(position); }
    @Override public boolean hasStableIds() { return source.hasStableIds(); }
    @Override public int getItemViewType(int position) { return source.getItemViewType(position); }
    @Override public int getViewTypeCount() { return source.getViewTypeCount(); }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return !off(position) && source.isEnabled(position); }
    @Override public void registerDataSetObserver(DataSetObserver observer) { source.registerDataSetObserver(observer); }
    @Override public void unregisterDataSetObserver(DataSetObserver observer) { source.unregisterDataSetObserver(observer); }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = source.getView(position, recycled, parent);
        row.setEnabled(!off(position));
        return row;
    }
}
