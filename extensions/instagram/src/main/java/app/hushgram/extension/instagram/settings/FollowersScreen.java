/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
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
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.hushgram.extension.instagram.profile.FollowerHistory;
import app.hushgram.extension.instagram.profile.FollowerHistory.Change;
import app.hushgram.extension.instagram.profile.FollowerHistory.Event;
import app.hushgram.extension.instagram.profile.FollowerHistory.Kind;
import app.hushgram.extension.instagram.profile.FollowersCheck;
import app.hushgram.extension.instagram.profile.FollowersChip;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * The follower history: who unfollowed you, who followed you again and who's new, newest first and
 * grouped by the check that found them, over a summary of your followers and the last check. A row
 * opens the person's profile and a long press copies their username. Check now runs a check
 * ({@link FollowersCheck}), and Export saves the history as a CSV file in Download/HushGram.
 *
 * <p>Like the HushGram screen it's a full screen framework dialog over Instagram's own activity, so
 * Back closes it and nothing is added to Instagram's manifest. It opens from the button on your
 * profile, the launcher shortcut, the notification and the Recent unfollowers row in settings.
 */
@SuppressWarnings("deprecation") // Framework fragments, as SettingsDialog uses.
public final class FollowersScreen extends DialogFragment {
    public static final String EXTRA_OPEN_FOLLOWERS = "app.hushgram.extension.instagram.OPEN_FOLLOWERS";
    static final String TAG = "hushgram_followers";
    private static final String CHECKED_NOW = "hushgram_checked_now";
    private static final String LEFT_NOW = "hushgram_left_now";
    private static final String BACK_NOW = "hushgram_back_now";
    private static final String NEW_NOW = "hushgram_new_now";

    /** Unfollowed, followed again and new, in the colors the follow chip uses on dark. */
    static final int LEFT_COLOR = Color.rgb(0xED, 0x49, 0x56);
    static final int BACK_COLOR = Color.rgb(0x2E, 0xCC, 0x71);
    static final int NEW_COLOR = Color.rgb(0x37, 0x97, 0xEF);
    /** Between the parts of a line, as the follow label puts it after pronouns. */
    private static final String SEPARATOR = " · ";

    private final ScreenColors palette = ScreenColors.DEFAULT;
    private FollowerHistory.Log log = FollowerHistory.Log.NONE;
    /** When the history was last opened before this time, so the changes after it are marked new. */
    private long seenBefore;
    @Nullable private Kind filter;
    private boolean youFollowOnly;
    private boolean loaded;
    private Rows rows;
    private LinearLayout header;
    private TextView empty;

    // ------------------------------------------------------------------ ways in

    /** The intent that opens Instagram on this screen, for the launcher shortcut and the notification. */
    public static Intent intent(Context context) {
        return new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(context.getPackageName(), SettingsEntry.MAIN_ACTIVITY))
                .putExtra(EXTRA_OPEN_FOLLOWERS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Shows the history over the activity [view] is on, when it's on one in front. */
    public static void showFrom(View view) {
        Activity activity = NavigationSettings.ownerOf(view);
        if (activity != null) show(activity);
    }

    /** Shows the history over [activity]. Answers whether it's showing. */
    public static boolean show(Activity activity) {
        return show(activity, null);
    }

    /** Shows the history over [activity] with what the check Check now just made found. */
    public static void showAfterCheck(Activity activity, Change change) {
        // Posted, so the Back that closed the Followers list has gone through first.
        Utils.runOnMainThread(() -> show(activity, change));
    }

    static boolean show(Activity activity, @Nullable Change change) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            FragmentManager fragments = activity.getFragmentManager();
            if (fragments.isStateSaved()) return false;
            DialogFragment shown = (DialogFragment) fragments.findFragmentByTag(TAG);
            if (shown != null) shown.dismissAllowingStateLoss();
            FollowersScreen screen = new FollowersScreen();
            if (change != null) {
                Bundle arguments = new Bundle();
                arguments.putBoolean(CHECKED_NOW, true);
                arguments.putInt(LEFT_NOW, change.count(Kind.LEFT));
                arguments.putInt(BACK_NOW, change.count(Kind.BACK));
                arguments.putInt(NEW_NOW, change.count(Kind.NEW));
                screen.setArguments(arguments);
            }
            screen.show(fragments, TAG);
            Logger.printInfo(() -> "Follower history opened over " + activity.getClass().getSimpleName());
            return true;
        } catch (Exception failure) {
            Logger.printException(() -> "Could not open the follower history", failure);
            return false;
        }
    }

    // ------------------------------------------------------------------ the dialog

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Material_NoActionBar);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = new Dialog(getActivity(), getTheme());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(palette.background));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
        }
        return dialog;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        Context context = getContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);
        root.addView(titleBar(context));

        FrameLayout body = new FrameLayout(context);
        ListView list = new ListView(context);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(24));
        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        list.addHeaderView(header, null, false);
        rows = new Rows();
        list.setAdapter(rows);
        list.setOnItemClickListener((view, row, position, id) -> {
            Object item = list.getItemAtPosition(position);
            if (item instanceof Event) openProfile((Event) item);
        });
        list.setOnItemLongClickListener((view, row, position, id) -> {
            Object item = list.getItemAtPosition(position);
            if (!(item instanceof Event)) return false;
            copy((Event) item);
            return true;
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        buildHeader();
        load();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        ShortcutCover.offOnceDrawn(getDialog());
    }

    private View titleBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(dp(4), dp(8), dp(18), dp(8));
        ImageButton back = new ImageButton(context);
        Drawable arrow = SettingsIcons.icon(context, SettingsIcons.BACK, palette.title);
        arrow.setBounds(0, 0, dp(24), dp(24));
        arrow.setLayoutDirection(getResources().getConfiguration().getLayoutDirection());
        back.setImageDrawable(arrow);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), null,
                new ColorDrawable(Color.WHITE)));
        back.setContentDescription(L10n.t(context, "Back"));
        back.setOnClickListener(v -> dismissAllowingStateLoss());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(context);
        title.setText(L10n.t(context, "Follower history"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setPaddingRelative(dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return bar;
    }

    /** Reads the history off the main thread, marks it seen, then fills the screen. */
    private void load() {
        Context application = getContext().getApplicationContext();
        Utils.runOnBackgroundThread(() -> {
            FollowerHistory.Log read = FollowerHistory.shown(application);
            FollowerHistory.markSeen(application, System.currentTimeMillis());
            Utils.runOnMainThread(() -> {
                if (!isAdded()) return;
                log = read;
                seenBefore = read.seen;
                loaded = true;
                buildHeader();
                rows.refresh();
                FollowersChip.reload(application);
            });
        });
    }

    // ------------------------------------------------------------------ the summary at the top

    private void buildHeader() {
        Context context = getContext();
        header.removeAllViews();

        LinearLayout card = card(context);
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        LinearLayout count = new LinearLayout(context);
        count.setOrientation(LinearLayout.VERTICAL);
        TextView number = text(context, loaded && log.checked > 0 ? NumberFormat.getIntegerInstance().format(log.followers) : "–",
                34, palette.title, true);
        count.addView(number);
        count.addView(text(context, L10n.t(context, "followers at the last check"), 13, palette.summary, false));
        top.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView last = text(context, lastCheck(context), 13, palette.summary, false);
        last.setGravity(Gravity.END);
        top.addView(last);
        card.addView(top);

        View rule = new View(context);
        rule.setBackgroundColor(palette.outline);
        LinearLayout.LayoutParams ruleSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        ruleSize.setMargins(0, dp(16), 0, dp(12));
        card.addView(rule, ruleSize);

        LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(stat(context, log.count(Kind.LEFT), L10n.t(context, "unfollowed"), LEFT_COLOR, Kind.LEFT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stats.addView(stat(context, log.count(Kind.BACK), L10n.t(context, "followed again"), BACK_COLOR, Kind.BACK),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stats.addView(stat(context, log.count(Kind.NEW), L10n.t(context, "new"), NEW_COLOR, Kind.NEW),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(stats);
        header.addView(card, cardSpace());

        Bundle arguments = getArguments();
        if (arguments != null && arguments.getBoolean(CHECKED_NOW)) header.addView(banner(context, arguments), cardSpace());

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button check = new Button(context);
        check.setText(L10n.t(context, "Check now"));
        ScreenColors.styleAction(check, true);
        check.setOnClickListener(v -> checkNow());
        LinearLayout.LayoutParams checkSize = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        checkSize.setMarginEnd(dp(8));
        actions.addView(check, checkSize);
        Button export = new Button(context);
        export.setText(L10n.t(context, "Export"));
        ScreenColors.styleAction(export, false);
        export.setEnabled(!log.events.isEmpty());
        export.setOnClickListener(v -> export());
        actions.addView(export, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(actions, cardSpace());

        header.addView(filters(context));

        empty = text(context, emptyText(context), 15, palette.summary, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPaddingRelative(dp(32), dp(40), dp(32), dp(24));
        empty.setVisibility(rows == null || rows.getCount() > 0 || !loaded ? View.GONE : View.VISIBLE);
        header.addView(empty);
    }

    private String lastCheck(Context context) {
        if (!loaded) return L10n.t(context, "Reading…");
        if (log.checked <= 0) return L10n.t(context, "Not checked yet");
        return L10n.f(context, "Last checked\n%1$s", when(context, log.checked));
    }

    private String emptyText(Context context) {
        if (log.checked <= 0) {
            return L10n.t(context, "Tap Check now, or scroll your own Followers list to the end. That first look is the "
                    + "starting point, and anyone who leaves after it shows up here.");
        }
        if (filter != null || youFollowOnly) return L10n.t(context, "Nothing here with this filter.");
        return L10n.t(context, "No changes yet. Each time you check, anyone who left or joined shows up here.");
    }

    private View stat(Context context, int count, String label, int color, Kind kind) {
        LinearLayout stat = new LinearLayout(context);
        stat.setOrientation(LinearLayout.VERTICAL);
        stat.setGravity(Gravity.CENTER_HORIZONTAL);
        stat.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView number = text(context, NumberFormat.getIntegerInstance().format(count), 22, color, true);
        number.setGravity(Gravity.CENTER);
        stat.addView(number);
        TextView words = text(context, label, 12, palette.summary, false);
        words.setGravity(Gravity.CENTER);
        stat.addView(words);
        stat.setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(color)), null, rounded(Color.WHITE, 12)));
        stat.setOnClickListener(v -> setFilter(filter == kind ? null : kind, false));
        stat.setContentDescription(count + " " + label);
        return stat;
    }

    private View banner(Context context, Bundle arguments) {
        int left = arguments.getInt(LEFT_NOW);
        int back = arguments.getInt(BACK_NOW);
        int added = arguments.getInt(NEW_NOW);
        String said;
        if (left + back + added == 0) {
            said = L10n.t(context, "Checked just now. No one left or joined since the check before.");
        } else {
            List<String> parts = new ArrayList<>();
            if (left > 0) parts.add(L10n.quantity(context, left, "1 unfollowed", "%1$d unfollowed"));
            if (back > 0) parts.add(L10n.quantity(context, back, "1 followed again", "%1$d followed again"));
            if (added > 0) parts.add(L10n.quantity(context, added, "1 new", "%1$d new"));
            String found = String.join(SEPARATOR, parts);
            said = L10n.f(context, "Checked just now: %1$s.", found);
        }
        TextView banner = text(context, said, 14, palette.title, false);
        banner.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor((palette.accent & 0x00FFFFFF) | 0x33000000);
        fill.setStroke(dp(1), palette.accent);
        fill.setCornerRadius(dp(14));
        banner.setBackground(fill);
        return banner;
    }

    private View filters(Context context) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPaddingRelative(dp(12), dp(4), dp(12), dp(8));
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chip(context, L10n.f(context, "All %1$d", log.count(null)), filter == null && !youFollowOnly,
                v -> setFilter(null, false)));
        chips.addView(chip(context, L10n.f(context, "Unfollowed %1$d", log.count(Kind.LEFT)), filter == Kind.LEFT && !youFollowOnly,
                v -> setFilter(Kind.LEFT, false)));
        chips.addView(chip(context, L10n.f(context, "You follow them %1$d", youFollowCount()), youFollowOnly,
                v -> setFilter(Kind.LEFT, true)));
        chips.addView(chip(context, L10n.f(context, "Followed again %1$d", log.count(Kind.BACK)), filter == Kind.BACK,
                v -> setFilter(Kind.BACK, false)));
        chips.addView(chip(context, L10n.f(context, "New %1$d", log.count(Kind.NEW)), filter == Kind.NEW,
                v -> setFilter(Kind.NEW, false)));
        scroll.addView(chips);
        return scroll;
    }

    private int youFollowCount() {
        int count = 0;
        for (Event event : log.events) if (event.kind == Kind.LEFT && Boolean.TRUE.equals(event.youFollow)) count++;
        return count;
    }

    private TextView chip(Context context, String label, boolean selected, View.OnClickListener tap) {
        TextView chip = text(context, label, 14, selected ? palette.onAccent : palette.title, false);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(18));
        fill.setColor(selected ? palette.accent : palette.card);
        fill.setStroke(dp(1), selected ? palette.accent : palette.outline);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), fill, null));
        chip.setSelected(selected);
        chip.setOnClickListener(tap);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        space.setMarginEnd(dp(8));
        chip.setLayoutParams(space);
        return chip;
    }

    private void setFilter(@Nullable Kind kind, boolean youFollow) {
        filter = kind;
        youFollowOnly = youFollow;
        buildHeader();
        rows.refresh();
    }

    // ------------------------------------------------------------------ the list

    /** The changes shown, with a heading before each check's. */
    private final class Rows extends BaseAdapter {
        private final List<Object> items = new ArrayList<>();

        void refresh() {
            items.clear();
            long heading = -1;
            for (Event event : log.events) {
                if (filter != null && event.kind != filter) continue;
                if (youFollowOnly && !Boolean.TRUE.equals(event.youFollow)) continue;
                if (event.when != heading) {
                    heading = event.when;
                    items.add(heading);
                }
                items.add(event);
            }
            notifyDataSetChanged();
            if (empty != null) {
                empty.setText(emptyText(getContext()));
                empty.setVisibility(items.isEmpty() && loaded ? View.VISIBLE : View.GONE);
            }
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return items.get(position) instanceof Event ? 1 : 0; }
        @Override public boolean isEnabled(int position) { return items.get(position) instanceof Event; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Object item = items.get(position);
            if (item instanceof Event) return row(parent.getContext(), (Event) item, recycled);
            return sectionHeading(parent.getContext(), (Long) item, recycled);
        }
    }

    private View sectionHeading(Context context, long when, @Nullable View recycled) {
        TextView heading = recycled instanceof TextView ? (TextView) recycled : text(context, "", 13, palette.heading, true);
        heading.setPaddingRelative(dp(20), dp(20), dp(20), dp(6));
        heading.setAllCaps(true);
        heading.setLetterSpacing(0.06f);
        heading.setText(when(context, when));
        heading.setAccessibilityHeading(true);
        return heading;
    }

    private View row(Context context, Event event, @Nullable View recycled) {
        RowViews views = recycled != null && recycled.getTag() instanceof RowViews ? (RowViews) recycled.getTag() : new RowViews(context);
        int color = event.kind == Kind.LEFT ? LEFT_COLOR : event.kind == Kind.BACK ? BACK_COLOR : NEW_COLOR;
        String name = event.username.isEmpty() ? event.id : event.username;
        views.initial.setText(name.substring(0, 1).toUpperCase(Locale.ROOT));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor((color & 0x00FFFFFF) | 0x33000000);
        circle.setStroke(dp(1), color);
        views.initial.setBackground(circle);
        views.initial.setTextColor(color);
        views.name.setText(event.username.isEmpty() ? L10n.f(context, "Account %1$s", event.id) : "@" + event.username);
        String what = event.kind == Kind.LEFT ? L10n.t(context, "Unfollowed you")
                : event.kind == Kind.BACK ? L10n.t(context, "Followed you again") : L10n.t(context, "Started following you");
        String span = event.since > 0
                ? L10n.f(context, "between %1$s and %2$s", day(context, event.since), day(context, event.when))
                : L10n.f(context, "by %1$s", day(context, event.when));
        views.detail.setText(what + SEPARATOR + span);
        views.detail.setTextColor(palette.summary);
        // Invisible rather than gone, so every row's picture lines up.
        views.dot.setVisibility(event.when > seenBefore && seenBefore > 0 ? View.VISIBLE : View.INVISIBLE);
        boolean following = event.kind == Kind.LEFT && Boolean.TRUE.equals(event.youFollow);
        views.tag.setVisibility(following ? View.VISIBLE : View.GONE);
        views.root.setContentDescription(views.name.getText() + ", " + what + ", " + span
                + (following ? ", " + L10n.t(context, "You follow them") : ""));
        return views.root;
    }

    /** One row's views, kept on the row for when the list hands it back. */
    private final class RowViews {
        final LinearLayout root;
        final TextView initial;
        final TextView name;
        final TextView detail;
        final View dot;
        final TextView tag;

        RowViews(Context context) {
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPaddingRelative(dp(6), dp(10), dp(16), dp(10));
            root.setMinimumHeight(dp(64));
            root.setBackground(new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), null,
                    new ColorDrawable(Color.WHITE)));
            // New since the history was last opened: a dot at the row's start, the same place on every row.
            dot = new View(context);
            GradientDrawable pink = new GradientDrawable();
            pink.setShape(GradientDrawable.OVAL);
            pink.setColor(palette.heading);
            dot.setBackground(pink);
            LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotSize.setMarginEnd(dp(6));
            root.addView(dot, dotSize);
            initial = text(context, "", 17, palette.title, true);
            initial.setGravity(Gravity.CENTER);
            initial.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(initial, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPaddingRelative(dp(14), 0, dp(8), 0);
            name = text(context, "", 16, palette.title, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(name);
            detail = text(context, "", 13, palette.summary, false);
            detail.setMaxLines(2);
            words.addView(detail);
            root.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tag = text(context, L10n.t(context, "You follow them"), 12, palette.heading, false);
            tag.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable outline = new GradientDrawable();
            outline.setCornerRadius(dp(12));
            outline.setStroke(dp(1), ScreenColors.half(palette.heading));
            tag.setBackground(outline);
            tag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(tag);
            root.setTag(this);
        }
    }

    // ------------------------------------------------------------------ actions

    private void openProfile(Event event) {
        if (event.username.isEmpty()) return;
        Activity activity = getActivity();
        if (activity == null) return;
        dismissAllowingStateLoss();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("instagram://user?username=" + Uri.encode(event.username)))
                    .setPackage(activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception failure) {
            Logger.printException(() -> "Follower history: could not open a profile", failure);
        }
    }

    private void copy(Event event) {
        Context context = getContext();
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        String name = event.username.isEmpty() ? event.id : event.username;
        clipboard.setPrimaryClip(ClipData.newPlainText(name, name));
        Utils.showToastShort(L10n.f(context, "Copied %1$s", name));
    }

    private void checkNow() {
        Activity activity = getActivity();
        if (activity == null) return;
        if (!Settings.TRACK_UNFOLLOWERS.get()) {
            Utils.showToastLong(L10n.t(activity, "Turn on Recent unfollowers in HushGram settings > Profiles first."));
            return;
        }
        dismissAllowingStateLoss();
        // After the dialog's window is gone, so the tabs it covered are the ones found.
        Utils.runOnMainThreadDelayed(() -> {
            if (!FollowersCheck.start(activity)) {
                Utils.showToastLong(L10n.t(activity, "Check now needs Instagram's main screen. Open your profile and try again."));
            } else {
                Utils.showToastShort(L10n.t(activity, "Checking your followers…"));
            }
        }, 250);
    }

    /** Saves the history as CSV in Download/HushGram, off the main thread. */
    private void export() {
        Context application = getContext().getApplicationContext();
        FollowerHistory.Log saving = log;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Utils.showToastLong(L10n.t(application, "Export needs Android 10 or newer."));
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            String name = "follower-history-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date()) + ".csv";
            try {
                ContentResolver resolver = application.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/HushGram");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri file = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (file == null) throw new IllegalStateException("no Downloads entry");
                try (OutputStream out = resolver.openOutputStream(file)) {
                    if (out == null) throw new IllegalStateException("no stream");
                    out.write(FollowerHistory.csv(saving).getBytes(StandardCharsets.UTF_8));
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(file, values, null, null);
                Utils.showToastLong(L10n.f(application, "Saved to Download/HushGram/%1$s", name));
            } catch (Exception failure) {
                Logger.printException(() -> "Follower history: export failed", failure);
                Utils.showToastLong(L10n.t(application, "Couldn't save the follower history."));
            }
        });
    }

    // ------------------------------------------------------------------ small parts

    private LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(14));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(palette.card);
        surface.setCornerRadius(dp(20));
        surface.setStroke(dp(1), palette.outline);
        card.setBackground(surface);
        return card;
    }

    private LinearLayout.LayoutParams cardSpace() {
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        space.setMargins(dp(12), dp(6), dp(12), dp(6));
        return space;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView text(Context context, String words, int sp, int color, boolean medium) {
        TextView text = new TextView(context);
        text.setText(words);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(color);
        if (medium) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    /** Today or Yesterday with the time, or the date with the time. */
    private static String when(Context context, long time) {
        String clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(time));
        if (DateUtils.isToday(time)) return L10n.f(context, "today, %1$s", clock);
        if (DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS)) return L10n.f(context, "yesterday, %1$s", clock);
        return day(context, time) + ", " + clock;
    }

    /** The date alone, without the year when it's this year's. */
    private static String day(Context context, long time) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        boolean thisYear = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
        return DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                | (thisYear ? DateUtils.FORMAT_NO_YEAR : DateUtils.FORMAT_SHOW_YEAR));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
