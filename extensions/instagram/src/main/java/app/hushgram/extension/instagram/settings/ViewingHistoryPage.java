/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.hushgram.extension.instagram.download.ExternalDownload;
import app.hushgram.extension.instagram.download.ViewingHistory;
import app.hushgram.extension.instagram.download.ViewingHistory.Kind;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ui.Dim;

/**
 * The viewing history's own page (#228): a tab for each kind, a search box and every row kept,
 * newest first. Tapping a row opens it again in Instagram: a post or a reel by its id, anything
 * else by the person who posted it, since a story is gone in a day and has no link of its own.
 */
@SuppressWarnings("deprecation") // Preference is what the settings page builds on.
final class ViewingHistoryPage {
    /** The tabs in order. Null is everything. */
    private static final Kind[] TABS = {null, Kind.POST, Kind.REEL, Kind.STORY, Kind.PROFILE};

    final Dialog dialog;
    final TextView[] tabs = new TextView[TABS.length];
    final EditText search;
    final ListView list;
    final TextView empty;
    private final Rows rows;
    private List<ViewingHistory.Row> all = Collections.emptyList();
    private Kind kind;

    private ViewingHistoryPage(Context context, Preference row) {
        ScreenColors palette = ScreenColors.DEFAULT;
        dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(Dim.dp(4), Dim.dp(4), Dim.dp(4), Dim.dp(4));
        bar.addView(iconButton(context, palette, SettingsIcons.BACK, L10n.t(context, "Back"), v -> dialog.dismiss()),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        TextView title = new TextView(context);
        title.setText(L10n.t(context, "Viewing history"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPaddingRelative(Dim.dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(iconButton(context, palette, SettingsIcons.DELETE, L10n.t(context, "Clear viewing history"), v ->
                HushgramPreferenceFragment.clearViewingHistory(row, row, this::read)),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        root.addView(bar);

        search = new EditText(context);
        search.setHint(L10n.t(context, "Search"));
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTextColor(palette.title);
        search.setHintTextColor(palette.summary);
        search.setBackgroundTintList(ColorStateList.valueOf(palette.outline));
        LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchSpace.setMarginStart(Dim.dp(16));
        searchSpace.setMarginEnd(Dim.dp(16));
        root.addView(search, searchSpace);

        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(context);
        tabRow.setPaddingRelative(Dim.dp(8), Dim.dp(4), Dim.dp(8), Dim.dp(4));
        for (int at = 0; at < TABS.length; at++) {
            Kind wanted = TABS[at];
            TextView tab = new TextView(context);
            tab.setText(tabLabel(context, wanted));
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setGravity(Gravity.CENTER);
            tab.setMinHeight(Dim.dp(48));
            tab.setPaddingRelative(Dim.dp(12), 0, Dim.dp(12), 0);
            tab.setBackground(ripple(palette));
            tab.setOnClickListener(v -> pick(wanted));
            tabs[at] = tab;
            tabRow.addView(tab, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        tabScroll.addView(tabRow);
        root.addView(tabScroll);

        FrameLayout body = new FrameLayout(context);
        list = new ListView(context);
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setClipToPadding(false);
        rows = new Rows(context, palette);
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> open(context, rows.get(position)));
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = new TextView(context);
        empty.setTextColor(palette.summary);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Dim.dp(32), Dim.dp(32), Dim.dp(32), Dim.dp(32));
        body.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        list.setEmptyView(empty);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
            }
            @Override public void afterTextChanged(Editable text) {
                show();
            }
        });

        SettingsDialog.keepOffTheBars(root);
        FocusRest.on(root);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    /** Opens the page over the settings, from the viewing history's row. */
    static ViewingHistoryPage open(Context context, Preference row) {
        ViewingHistoryPage page = new ViewingHistoryPage(context, row);
        page.pick(null);
        page.read();
        page.dialog.show();
        return page;
    }

    /** Reads the rows off the main thread, since the first read comes from a file. */
    private void read() {
        Context application = dialog.getContext().getApplicationContext();
        if (!Utils.runOnBackgroundThread(() -> {
            List<ViewingHistory.Row> read = ViewingHistory.entries(application);
            Utils.runOnMainThread(() -> {
                all = read;
                show();
            });
        })) {
            all = ViewingHistory.entries(application);
            show();
        }
    }

    private void pick(@Nullable Kind wanted) {
        kind = wanted;
        ScreenColors palette = ScreenColors.DEFAULT;
        for (int at = 0; at < TABS.length; at++) {
            boolean picked = TABS[at] == wanted;
            tabs[at].setSelected(picked);
            tabs[at].setTextColor(picked ? palette.heading : palette.summary);
            tabs[at].setTypeface(Typeface.create("sans-serif-medium", picked ? Typeface.BOLD : Typeface.NORMAL));
        }
        show();
    }

    private void show() {
        List<ViewingHistory.Row> shown = matching(all, kind, String.valueOf(search.getText()));
        rows.set(shown);
        Context context = dialog.getContext();
        empty.setText(all.isEmpty() ? HushgramPreferenceFragment.viewingHistorySummary(all, System.currentTimeMillis())
                : L10n.t(context, "Nothing matches."));
    }

    /** The rows of [kind] (null for all of them) whose poster or id holds [query], in their order. */
    static List<ViewingHistory.Row> matching(List<ViewingHistory.Row> rows, @Nullable Kind kind, @Nullable String query) {
        String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (wanted.startsWith("@")) wanted = wanted.substring(1);
        List<ViewingHistory.Row> kept = new ArrayList<>();
        for (ViewingHistory.Row row : rows) {
            if (kind != null && row.kind != kind) continue;
            if (!wanted.isEmpty() && !row.owner.toLowerCase(Locale.ROOT).contains(wanted)
                    && !row.id.toLowerCase(Locale.ROOT).contains(wanted)) continue;
            kept.add(row);
        }
        return kept;
    }

    /**
     * Where tapping [row] goes in Instagram, or null when it doesn't know. Always the web address:
     * 453 takes {@code instagram://media} without a word and stays where it is.
     */
    @Nullable
    static Uri link(ViewingHistory.Row row) {
        if (row.kind == Kind.POST || row.kind == Kind.REEL) {
            String post = ExternalDownload.linkOfId(row.id, row.kind == Kind.REEL);
            return post == null ? null : Uri.parse(post);
        }
        String name = row.kind == Kind.PROFILE ? row.id : row.owner;
        return name.isEmpty() ? null : Uri.parse("https://www.instagram.com/" + Uri.encode(name) + "/");
    }

    private void open(Context context, ViewingHistory.Row row) {
        Uri link = link(row);
        try {
            if (link == null) throw new ActivityNotFoundException();
            // This app's own package, so a copy installed next to Instagram opens itself.
            context.startActivity(new Intent(Intent.ACTION_VIEW, link).setPackage(context.getPackageName()));
            // The link opens on the screen under this page, and settings opened moments ago would
            // follow it to the front, so both make way.
            Activity activity = SettingsScreenRow.activityOf(context);
            if (activity != null) SettingsEntry.closeForLink(activity);
            dialog.dismiss();
        } catch (ActivityNotFoundException | SecurityException failure) {
            Utils.showToastShort(L10n.t("Couldn't open that one."));
        }
    }

    private static String tabLabel(Context context, @Nullable Kind kind) {
        if (kind == null) return L10n.t(context, "All");
        switch (kind) {
            case POST: return L10n.t(context, "Posts");
            case REEL: return L10n.t(context, "Reels");
            case STORY: return L10n.t(context, "Stories");
            default: return L10n.t(context, "Profiles");
        }
    }

    private static RippleDrawable ripple(ScreenColors palette) {
        return new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), null, new ColorDrawable(Color.WHITE));
    }

    private static ImageButton iconButton(Context context, ScreenColors palette, String icon, String label, View.OnClickListener tap) {
        ImageButton button = new ImageButton(context);
        android.graphics.drawable.Drawable drawable = SettingsIcons.icon(context, icon, palette.title);
        drawable.setBounds(0, 0, Dim.dp(24), Dim.dp(24));
        button.setImageDrawable(drawable);
        button.setPadding(Dim.dp(12), Dim.dp(12), Dim.dp(12), Dim.dp(12));
        button.setBackground(ripple(palette));
        button.setContentDescription(label);
        button.setOnClickListener(tap);
        return button;
    }

    /** The list's rows: who, then what it was and when. */
    private static final class Rows extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private List<ViewingHistory.Row> shown = Collections.emptyList();

        Rows(Context context, ScreenColors palette) {
            this.context = context;
            this.palette = palette;
        }

        void set(List<ViewingHistory.Row> rows) {
            shown = rows;
            notifyDataSetChanged();
        }

        ViewingHistory.Row get(int position) {
            return shown.get(position);
        }

        @Override public int getCount() {
            return shown.size();
        }

        @Override public Object getItem(int position) {
            return shown.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View reused, ViewGroup parent) {
            LinearLayout row = reused instanceof LinearLayout ? (LinearLayout) reused : make();
            ViewingHistory.Row seen = shown.get(position);
            String label = HushgramPreferenceFragment.viewingKindLabel(seen.kind);
            String ago = DateUtils.getRelativeTimeSpanString(seen.time, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            ((TextView) row.getChildAt(0)).setText(seen.owner.isEmpty() ? label : "@" + seen.owner);
            ((TextView) row.getChildAt(1)).setText(seen.owner.isEmpty() ? ago : label + ", " + ago);
            return row;
        }

        private LinearLayout make() {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Dim.dp(56));
            row.setPaddingRelative(Dim.dp(16), Dim.dp(8), Dim.dp(16), Dim.dp(8));
            row.setBackground(ripple(palette));
            TextView who = new TextView(context);
            who.setTextColor(palette.title);
            who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            who.setSingleLine(true);
            row.addView(who);
            TextView what = new TextView(context);
            what.setTextColor(palette.summary);
            what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            what.setSingleLine(true);
            row.addView(what);
            return row;
        }
    }
}
