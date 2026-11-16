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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.hushgram.extension.instagram.profile.Unfollowers;
import app.hushgram.extension.instagram.stories.StoryViewers;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ui.Dim;

/**
 * The story viewers' own page (#710): a period and a view to pick, a search box, and the kept
 * viewer lists set beside your last Followers list. Viewers shows everyone listed, the most stories
 * first; No view recorded the followers on none of the period's lists; Frequent those on at least
 * half; Stories each story, which opens on who viewed it and which followers aren't on its list.
 * Tapping a person opens their profile.
 */
@SuppressWarnings("deprecation") // Preference is what the settings page builds on.
final class StoryViewersPage {
    /** How far back the page looks. */
    enum Period {
        WEEK(7), MONTH(30), QUARTER(90), ALL(0);

        final int days;

        Period(int days) {
            this.days = days;
        }

        /** The first moment of the period at [now], 0 for everything kept. */
        long from(long now) {
            return days == 0 ? 0 : now - days * StoryViewers.DAY;
        }

        /** The chip's words, each a literal of its own so the tables can carry it. */
        String label(Context context) {
            switch (this) {
                case WEEK: return L10n.t(context, "Last 7 days");
                case MONTH: return L10n.t(context, "Last 30 days");
                case QUARTER: return L10n.t(context, "Last 90 days");
                default: return L10n.t(context, "All kept");
            }
        }
    }

    /** What the list shows. */
    enum Show {
        VIEWERS, UNSEEN, FREQUENT, STORIES;

        String label(Context context) {
            switch (this) {
                case VIEWERS: return L10n.t(context, "Viewers");
                case UNSEEN: return L10n.t(context, "No view recorded");
                case FREQUENT: return L10n.t(context, "Frequent");
                default: return L10n.t(context, "Stories");
            }
        }
    }

    /** One row: a person, or a story to open. */
    static final class Entry {
        final String title;
        final String detail;
        /** The username a tap opens, empty for none. */
        final String username;
        /** Drawn in the accent color: a frequent viewer, or someone on the opened story's list. */
        final boolean accent;
        @Nullable final StoryViewers.Story story;

        Entry(String title, String detail, String username, boolean accent, @Nullable StoryViewers.Story story) {
            this.title = title;
            this.detail = detail;
            this.username = username;
            this.accent = accent;
            this.story = story;
        }
    }

    /** Where the followers come from: Recent unfollowers keeps the last Followers list opened. */
    static String followersHint() {
        return L10n.t("To see followers with no view recorded, turn on Recent unfollowers and "
                + "open your Followers list once.");
    }

    final Dialog dialog;
    final EditText search;
    final ListView list;
    final TextView empty;
    final TextView overview;
    final LinearLayout periods;
    final LinearLayout views;
    private final ScreenColors palette = ScreenColors.current();
    private final Rows rows;
    private final Drawable nothingYet;
    private final Drawable nothingMatches;
    Period period = Period.MONTH;
    Show showing = Show.VIEWERS;
    /** The story opened from Stories, or null. */
    @Nullable StoryViewers.Story opened;
    private Map<String, String> followers = Collections.emptyMap();
    @Nullable private StoryViewers.Summary allKept;
    @Nullable private StoryViewers.Summary inPeriod;

    private StoryViewersPage(Context context, Preference row) {
        dialog = new Dialog(context, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(Dim.dp(4), Dim.dp(4), Dim.dp(4), Dim.dp(4));
        bar.addView(iconButton(context, palette, SettingsIcons.BACK, L10n.t(context, "Back"), v -> back()),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        TextView title = new TextView(context);
        title.setText(L10n.t(context, "Story viewers"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPaddingRelative(Dim.dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(iconButton(context, palette, SettingsIcons.DELETE, L10n.t(context, "Clear story viewers"), v ->
                HushgramPreferenceFragment.clearStoryViewers(row, row, this::read)),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        root.addView(bar);

        periods = chipRow(context, root);
        views = chipRow(context, root);

        overview = new TextView(context);
        overview.setTextColor(palette.summary);
        overview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        overview.setPaddingRelative(Dim.dp(18), Dim.dp(4), Dim.dp(18), Dim.dp(4));
        root.addView(overview);

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
        list.setOnItemClickListener((parent, view, position, id) -> tapped(context, rows.get(position)));
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
                list();
            }
        });

        SettingsDialog.keepOffTheBars(root);
        FocusRest.on(root);
        dialog.setContentView(root);
        dialog.setOnKeyListener((shown, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK || opened == null) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) back();
            return true;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(palette.background));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    /** Opens the page over the settings, from the story viewers row. */
    static StoryViewersPage open(Context context, Preference row) {
        StoryViewersPage page = new StoryViewersPage(context, row);
        page.show();
        page.read();
        page.dialog.show();
        return page;
    }

    /** Back from an opened story to the stories, or else off the page. */
    private void back() {
        if (opened == null) {
            dialog.dismiss();
            return;
        }
        opened = null;
        show();
    }

    /** Reads the kept lists and the followers off the main thread, since both come from files. */
    private void read() {
        Context application = dialog.getContext().getApplicationContext();
        Period wanted = period;
        if (!Utils.runOnBackgroundThread(() -> {
            Map<String, String> known = Unfollowers.shown(application).followers;
            long now = System.currentTimeMillis();
            StoryViewers.Summary all = StoryViewers.summary(application, 0, known);
            StoryViewers.Summary some = wanted == Period.ALL ? all : StoryViewers.summary(application, wanted.from(now), known);
            Utils.runOnMainThread(() -> {
                if (wanted != period) return;
                followers = known;
                allKept = all;
                inPeriod = some;
                show();
            });
        })) {
            followers = Unfollowers.shown(application).followers;
            allKept = StoryViewers.summary(application, 0, followers);
            inPeriod = period == Period.ALL ? allKept : StoryViewers.summary(application, period.from(System.currentTimeMillis()), followers);
            show();
        }
    }

    private void show() {
        Context context = dialog.getContext();
        periods.removeAllViews();
        views.removeAllViews();
        for (Period one : Period.values()) {
            periods.addView(chip(one.label(context), one == period, v -> {
                period = one;
                opened = null;
                read();
                show();
            }));
        }
        for (Show one : Show.values()) {
            views.addView(chip(one.label(context), opened == null && one == showing, v -> {
                showing = one;
                opened = null;
                show();
            }));
        }
        list();
    }

    /** Fills the list for the picked period and view, the search box applied. */
    private void list() {
        Context context = dialog.getContext();
        long now = System.currentTimeMillis();
        if (inPeriod == null || allKept == null) {
            overview.setText(L10n.t(context, "Reading the list…"));
            rows.set(Collections.emptyList());
            empty.setText("");
            empty.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        overview.setText(overview(context, inPeriod, opened, followers));
        List<Entry> all = entries(context, inPeriod, allKept, showing, opened, followers, now);
        rows.set(matching(all, String.valueOf(search.getText())));
        boolean nothing = all.isEmpty();
        empty.setText(nothing ? emptyText(context, inPeriod, allKept, showing, followers) : L10n.t(context, "Nothing matches."));
        empty.setCompoundDrawablesRelative(null, nothing ? nothingYet : nothingMatches, null, null);
    }

    /** The line above the list: what the period holds, or the opened story's count. */
    static String overview(Context context, StoryViewers.Summary period, @Nullable StoryViewers.Story opened,
            Map<String, String> followers) {
        if (opened != null) return storyTitle(context, opened) + "\n" + storyDetail(context, period, opened, followers);
        String counts = L10n.f("Stories kept: %1$d. Viewers: %2$d.", period.stories.size(), period.viewers.size());
        int reach = period.reach();
        if (reach >= 0 && !period.stories.isEmpty()) {
            return counts + " " + L10n.f("%1$d%% of your followers viewed at least one.", reach);
        }
        if (followers.isEmpty()) return counts + " " + followersHint();
        return counts;
    }

    /** The rows for [view] of [period], or for the [opened] story, at [now]. */
    static List<Entry> entries(Context context, StoryViewers.Summary period, StoryViewers.Summary allKept, Show view,
            @Nullable StoryViewers.Story opened, Map<String, String> followers, long now) {
        List<Entry> entries = new ArrayList<>();
        if (opened != null) {
            Map<String, String> names = new HashMap<>();
            for (StoryViewers.Person person : allKept.viewers) names.put(person.id, person.username);
            for (String id : opened.viewers) {
                String username = followers.containsKey(id) ? followers.get(id) : names.get(id);
                entries.add(person(username, L10n.t("Viewed this story"), true));
            }
            for (StoryViewers.Person missed : period.missed(opened, followers)) {
                entries.add(person(missed.username, L10n.t("Not on this story's list"), false));
            }
            return entries;
        }
        switch (view) {
            case VIEWERS:
            case FREQUENT:
                for (StoryViewers.Person person : view == Show.FREQUENT ? period.frequentViewers() : period.viewers) {
                    String ago = DateUtils.getRelativeTimeSpanString(person.last, now, DateUtils.MINUTE_IN_MILLIS).toString();
                    entries.add(person(person.username, L10n.f("Stories viewed: %1$d of %2$d, the newest posted %3$s",
                            person.views, period.stories.size(), ago), period.frequent(person)));
                }
                break;
            case UNSEEN:
                Map<String, Long> lastSeen = new HashMap<>();
                for (StoryViewers.Person person : allKept.viewers) lastSeen.put(person.id, person.last);
                long start = Math.max(period.from, period.since);
                for (StoryViewers.Person person : period.unseen) {
                    Long last = lastSeen.get(person.id);
                    entries.add(person(person.username, last != null && last > 0
                            ? L10n.f("None in this period. The last story they viewed is from %s", date(context, last))
                            : L10n.f("No view recorded since %s", date(context, start)), false));
                }
                break;
            case STORIES:
                for (StoryViewers.Story story : period.stories) {
                    entries.add(new Entry(storyTitle(context, story), storyDetail(context, period, story, followers), "", false, story));
                }
                break;
        }
        return entries;
    }

    private static Entry person(@Nullable String username, String detail, boolean accent) {
        String name = username == null ? "" : username;
        return new Entry(name.isEmpty() ? L10n.t("Unknown account") : "@" + name, detail, name, accent, null);
    }

    static String storyTitle(Context context, StoryViewers.Story story) {
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH;
        return L10n.f("Story from %s", DateUtils.formatDateTime(context, story.posted, flags));
    }

    /** How many viewers [story] has kept, and of its whole list, how many followers aren't on it. */
    static String storyDetail(Context context, StoryViewers.Summary period, StoryViewers.Story story, Map<String, String> followers) {
        if (!story.complete()) return L10n.f("Viewers kept: %1$d of %2$d", story.viewers.size(), story.total);
        if (followers.isEmpty()) return L10n.f("Viewers: %1$d", story.viewers.size());
        return L10n.f("Viewers: %1$d. Followers not on the list: %2$d", story.viewers.size(), period.missed(story, followers).size());
    }

    /** What the empty list says for [view]. */
    static String emptyText(Context context, StoryViewers.Summary period, StoryViewers.Summary allKept, Show view,
            Map<String, String> followers) {
        if (allKept.stories.isEmpty()) return HushgramPreferenceFragment.storyViewersSummary(context, allKept);
        if (period.stories.isEmpty()) return L10n.t("No stories kept in this period.");
        if (view == Show.UNSEEN) {
            return followers.isEmpty()
                    ? followersHint()
                    : L10n.t("Every follower viewed at least one story in this period.");
        }
        if (view == Show.FREQUENT) return L10n.t("No one has viewed half of this period's stories yet.");
        return L10n.t("No stories kept in this period.");
    }

    private static String date(Context context, long time) {
        return DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** The rows whose title or username holds [query], in their order. */
    static List<Entry> matching(List<Entry> entries, @Nullable String query) {
        String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (wanted.startsWith("@")) wanted = wanted.substring(1);
        if (wanted.isEmpty()) return entries;
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.username.toLowerCase(Locale.ROOT).contains(wanted)
                    || entry.title.toLowerCase(Locale.ROOT).contains(wanted)) kept.add(entry);
        }
        return kept;
    }

    /** The profile [entry] is about, by its username, or null for a story or an account without one. */
    @Nullable
    static Uri link(Entry entry) {
        return entry.username.isEmpty() ? null : Uri.parse("https://www.instagram.com/" + Uri.encode(entry.username) + "/");
    }

    private void tapped(Context context, Entry entry) {
        if (entry.story != null) {
            opened = entry.story;
            search.setText("");
            show();
            return;
        }
        try {
            Uri link = link(entry);
            if (link == null) throw new ActivityNotFoundException();
            // This app's own package, so a copy installed next to Instagram opens itself.
            context.startActivity(new Intent(Intent.ACTION_VIEW, link).setPackage(context.getPackageName()));
            Activity activity = SettingsScreenRow.activityOf(context);
            if (activity != null) SettingsEntry.closeForLink(activity);
            dialog.dismiss();
        } catch (ActivityNotFoundException | SecurityException failure) {
            Utils.showToastShort(L10n.t("Couldn't open that one."));
        }
    }

    private LinearLayout chipRow(Context context, LinearLayout root) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPaddingRelative(Dim.dp(18), 0, Dim.dp(10), 0);
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView chip(String label, boolean picked, View.OnClickListener tap) {
        TextView chip = new TextView(dialog.getContext());
        chip.setText(label);
        chip.setSingleLine(true);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setGravity(Gravity.CENTER);
        chip.setTextColor(picked ? palette.onAccent : palette.title);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(Dim.dp(18));
        shape.setColor(picked ? palette.accent : palette.card);
        if (!picked) shape.setStroke(Dim.dp(1), palette.outline);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), shape, null));
        chip.setPaddingRelative(Dim.dp(16), 0, Dim.dp(16), 0);
        chip.setSelected(picked);
        chip.setOnClickListener(tap);
        // 36 dp tall, and with the space above and below, a 48 dp place to tap.
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Dim.dp(36));
        space.setMarginEnd(Dim.dp(8));
        space.topMargin = Dim.dp(6);
        space.bottomMargin = Dim.dp(6);
        chip.setLayoutParams(space);
        return chip;
    }

    private static RippleDrawable ripple(ScreenColors palette) {
        return new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), null, new ColorDrawable(Color.WHITE));
    }

    private Drawable emptyMark(Context context, String icon) {
        Drawable mark = SettingsIcons.icon(context, icon, palette.hint);
        mark.setBounds(0, 0, Dim.dp(40), Dim.dp(40));
        return mark;
    }

    private static ImageButton iconButton(Context context, ScreenColors palette, String icon, String label,
            View.OnClickListener tap) {
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

    /** The list's rows: a mark, in the accent color for a frequent viewer, then who or which story. */
    static final class Rows extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private List<Entry> shown = Collections.emptyList();

        Rows(Context context, ScreenColors palette) {
            this.context = context;
            this.palette = palette;
        }

        void set(List<Entry> entries) {
            shown = entries;
            notifyDataSetChanged();
        }

        Entry get(int position) {
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
            Entry entry = shown.get(position);
            LinearLayout words = (LinearLayout) row.getChildAt(1);
            ((TextView) words.getChildAt(0)).setText(entry.title);
            ((TextView) words.getChildAt(1)).setText(entry.detail);
            ImageView mark = (ImageView) row.getChildAt(0);
            mark.setImageDrawable(SettingsIcons.icon(context, entry.story != null ? SettingsIcons.STORIES : SettingsIcons.PERSON,
                    entry.accent ? palette.heading : palette.summary));
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
            LinearLayout.LayoutParams markSpace = new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48));
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
}
