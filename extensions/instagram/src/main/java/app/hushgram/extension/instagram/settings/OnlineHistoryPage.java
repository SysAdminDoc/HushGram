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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.hushgram.extension.instagram.direct.OnlineHistory;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ui.Dim;

/**
 * The online history's own page (#590): a search box and every time someone in your messages was
 * seen coming online or going offline, the newest first, each with how long ago and the exact time.
 * Tapping a row opens that account's profile.
 */
@SuppressWarnings("deprecation") // Preference is what the settings page builds on.
final class OnlineHistoryPage {
    final Dialog dialog;
    final EditText search;
    final ListView list;
    final TextView empty;
    private final ScreenColors palette = ScreenColors.current();
    private final Rows rows;
    private final Drawable nothingYet;
    private final Drawable nothingMatches;
    private List<OnlineHistory.Event> all = Collections.emptyList();

    private OnlineHistoryPage(Context context, Preference row) {
        dialog = new Dialog(context, palette.theme());
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(Dim.dp(4), Dim.dp(4), Dim.dp(4), Dim.dp(4));
        bar.addView(iconButton(context, palette, SettingsIcons.BACK, L10n.t(context, "Back"), v -> dialog.dismiss()),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        TextView title = new TextView(context);
        title.setText(L10n.t(context, "Online history"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPaddingRelative(Dim.dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(iconButton(context, palette, SettingsIcons.DELETE, L10n.t(context, "Clear online history"), v ->
                HushgramPreferenceFragment.clearOnlineHistory(row, row, this::read)),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        root.addView(bar);

        search = new EditText(context);
        search.setHint(L10n.t(context, "Search"));
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        ScreenColors.styleField(search);
        Drawable glass = SettingsIcons.icon(context, SettingsIcons.SEARCH, palette.summary);
        glass.setBounds(0, 0, Dim.dp(20), Dim.dp(20));
        search.setCompoundDrawablesRelative(glass, null, null, null);
        search.setCompoundDrawablePadding(Dim.dp(12));
        LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Where the profile history's search sits.
        searchSpace.setMarginStart(Dim.dp(18));
        searchSpace.setMarginEnd(Dim.dp(18));
        searchSpace.topMargin = Dim.dp(4);
        searchSpace.bottomMargin = Dim.dp(4);
        root.addView(search, searchSpace);

        FrameLayout body = new FrameLayout(context);
        list = new ListView(context);
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setClipToPadding(false);
        rows = new Rows(context, palette);
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> go(context, link(rows.get(position))));
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = new TextView(context);
        empty.setTextColor(palette.summary);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Dim.dp(32), Dim.dp(32), Dim.dp(32), Dim.dp(32));
        empty.setCompoundDrawablePadding(Dim.dp(12));
        nothingYet = emptyMark(context, SettingsIcons.PERSON);
        nothingMatches = emptyMark(context, SettingsIcons.SEARCH);
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
            window.setBackgroundDrawable(new ColorDrawable(palette.background));
            palette.paintBars(window);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    /** Opens the page over the settings, from the online history's row. */
    static OnlineHistoryPage open(Context context, Preference row) {
        OnlineHistoryPage page = new OnlineHistoryPage(context, row);
        page.show();
        page.read();
        page.dialog.show();
        return page;
    }

    /** Reads the changes off the main thread, since the first read comes from a file. */
    private void read() {
        Context application = dialog.getContext().getApplicationContext();
        if (!Utils.runOnBackgroundThread(() -> {
            List<OnlineHistory.Event> read = OnlineHistory.events(application);
            Utils.runOnMainThread(() -> {
                all = read;
                show();
            });
        })) {
            all = OnlineHistory.events(application);
            show();
        }
    }

    private void show() {
        rows.set(matching(all, String.valueOf(search.getText())));
        empty.setText(all.isEmpty() ? HushgramPreferenceFragment.onlineHistorySummary(all, System.currentTimeMillis())
                : L10n.t(dialog.getContext(), "Nothing matches."));
        empty.setCompoundDrawablesRelative(null, all.isEmpty() ? nothingYet : nothingMatches, null, null);
    }

    /** The changes whose account's username holds [query], in their order. */
    static List<OnlineHistory.Event> matching(List<OnlineHistory.Event> events, @Nullable String query) {
        String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (wanted.startsWith("@")) wanted = wanted.substring(1);
        List<OnlineHistory.Event> kept = new ArrayList<>();
        for (OnlineHistory.Event event : events) {
            if (!wanted.isEmpty() && !event.username.toLowerCase(Locale.ROOT).contains(wanted)) continue;
            kept.add(event);
        }
        return kept;
    }

    /** The profile [event] belongs to, by its username, or null when Instagram never gave one. */
    @Nullable
    static Uri link(OnlineHistory.Event event) {
        return event.username.isEmpty() ? null
                : Uri.parse("https://www.instagram.com/" + Uri.encode(event.username) + "/");
    }

    /** The exact date and time of [event], beside how long ago it was. */
    static String when(Context context, OnlineHistory.Event event) {
        int flags = android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME
                | android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
        return android.text.format.DateUtils.formatDateTime(context, event.time, flags);
    }

    private void go(Context context, @Nullable Uri link) {
        try {
            if (link == null) throw new ActivityNotFoundException();
            // This app's own package, so a copy installed next to Instagram opens itself.
            context.startActivity(new Intent(Intent.ACTION_VIEW, link).setPackage(context.getPackageName()));
            // The profile opens on the screen under this page, and the settings make way for it.
            Activity activity = SettingsScreenRow.activityOf(context);
            if (activity != null) SettingsEntry.closeForLink(activity);
            dialog.dismiss();
        } catch (ActivityNotFoundException | SecurityException failure) {
            Utils.showToastShort(L10n.t("Couldn't open that one."));
        }
    }

    private static RippleDrawable ripple(ScreenColors palette) {
        return new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), null, new ColorDrawable(Color.WHITE));
    }

    private Drawable emptyMark(Context context, String icon) {
        Drawable mark = SettingsIcons.icon(context, icon, palette.hint);
        mark.setBounds(0, 0, Dim.dp(40), Dim.dp(40));
        return mark;
    }

    private static ImageButton iconButton(Context context, ScreenColors palette, String icon, String label, View.OnClickListener tap) {
        ImageButton button = new ImageButton(context);
        Drawable drawable = SettingsIcons.icon(context, icon, palette.title);
        drawable.setBounds(0, 0, Dim.dp(24), Dim.dp(24));
        button.setImageDrawable(drawable);
        button.setPadding(Dim.dp(12), Dim.dp(12), Dim.dp(12), Dim.dp(12));
        button.setBackground(ripple(palette));
        button.setContentDescription(label);
        button.setOnClickListener(tap);
        return button;
    }

    /** The list's rows: a person, in the accent color when they came online, then who and when. */
    static final class Rows extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private List<OnlineHistory.Event> shown = Collections.emptyList();

        Rows(Context context, ScreenColors palette) {
            this.context = context;
            this.palette = palette;
        }

        void set(List<OnlineHistory.Event> events) {
            shown = events;
            notifyDataSetChanged();
        }

        OnlineHistory.Event get(int position) {
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
            OnlineHistory.Event event = shown.get(position);
            String what = HushgramPreferenceFragment.onlineChangeLabel(event, System.currentTimeMillis())
                    + "\n" + when(context, event);
            LinearLayout words = (LinearLayout) row.getChildAt(1);
            ((TextView) words.getChildAt(0)).setText(HushgramPreferenceFragment.onlineName(event));
            ((TextView) words.getChildAt(1)).setText(what);
            ImageView mark = (ImageView) row.getChildAt(0);
            mark.setImageDrawable(SettingsIcons.icon(context, SettingsIcons.PERSON,
                    event.online ? palette.heading : palette.summary));
            return row;
        }

        private LinearLayout make() {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Dim.dp(64));
            row.setPaddingRelative(Dim.dp(16), Dim.dp(8), Dim.dp(16), Dim.dp(8));
            row.setBackground(ripple(palette));

            ImageView mark = new ImageView(context);
            GradientDrawable well = new GradientDrawable();
            well.setColor(palette.card);
            well.setShape(GradientDrawable.OVAL);
            mark.setBackground(well);
            mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mark.setPadding(Dim.dp(12), Dim.dp(12), Dim.dp(12), Dim.dp(12));
            // The words beside it say what the row is.
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams markSpace = new LinearLayout.LayoutParams(Dim.dp(MARK_DP), Dim.dp(MARK_DP));
            markSpace.setMarginEnd(Dim.dp(12));
            row.addView(mark, markSpace);

            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView who = new TextView(context);
            who.setTextColor(palette.title);
            who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            who.setSingleLine(true);
            who.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(who);
            TextView what = new TextView(context);
            what.setTextColor(palette.summary);
            what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            what.setMaxLines(3);
            what.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(what);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    /** How big a row's mark is, the size of the profile history's pictures. */
    static final int MARK_DP = 48;
}
