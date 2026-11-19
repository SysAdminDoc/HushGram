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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

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
 * The button by Clear shows the rows as a grid of big pictures instead, and the page opens the way
 * it was left (#773).
 */
@SuppressWarnings("deprecation") // Preference is what the settings page builds on.
final class ViewingHistoryPage {
    /** The tabs in order. Null is everything. */
    private static final Kind[] TABS = {null, Kind.POST, Kind.REEL, Kind.STORY, Kind.PROFILE};

    /** How many tiles the grid shows across, as Instagram's own grids do. */
    static final int GRID_COLUMNS = 3;

    final Dialog dialog;
    final TextView[] tabs = new TextView[TABS.length];
    final EditText search;
    final ListView list;
    final GridView grid;
    /** Show as grid, or Show as list while the grid shows. */
    final ImageButton layout;
    final TextView empty;
    private final ScreenColors palette = ScreenColors.current();
    private final Rows rows;
    private final Tiles tiles;
    private final Drawable nothingYet;
    private final Drawable nothingMatches;
    /** The settings row the page opened from, which the settings lock goes by. */
    private final Preference origin;
    private List<ViewingHistory.Row> all = Collections.emptyList();
    private Kind kind;

    private ViewingHistoryPage(Context context, Preference row) {
        origin = row;
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
        title.setText(L10n.t(context, "Viewing history"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPaddingRelative(Dim.dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        layout = iconButton(context, palette, SettingsIcons.GRID, L10n.t(context, "Show as grid"), v -> showAsGrid(!asGrid()));
        paintLayoutButton();
        bar.addView(layout, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        bar.addView(iconButton(context, palette, SettingsIcons.DELETE, L10n.t(context, "Clear viewing history"), v ->
                HushgramPreferenceFragment.clearViewingHistory(row, row, this::read)),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        root.addView(bar);

        search = new EditText(context);
        search.setHint(L10n.t(context, "Search"));
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        // The settings' own field, outlined on its surface and blue while it has focus, in place of
        // the bare underline it had.
        ScreenColors.styleField(search);
        // A magnifier at its start, as the settings search has. A compound drawable isn't read, and
        // the hint already names the field.
        Drawable glass = SettingsIcons.icon(context, SettingsIcons.SEARCH, palette.summary);
        glass.setBounds(0, 0, Dim.dp(20), Dim.dp(20));
        search.setCompoundDrawablesRelative(glass, null, null, null);
        search.setCompoundDrawablePadding(Dim.dp(12));
        LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // The settings search's 18dp side inset, so both fields sit in the same place.
        searchSpace.setMarginStart(Dim.dp(18));
        searchSpace.setMarginEnd(Dim.dp(18));
        searchSpace.topMargin = Dim.dp(4);
        searchSpace.bottomMargin = Dim.dp(4);
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
            tab.setBackground(tabSurface(palette));
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
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            options(view, rows.get(position));
            return true;
        });
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        grid = new GridView(context);
        grid.setNumColumns(GRID_COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Dim.dp(2));
        grid.setVerticalSpacing(Dim.dp(2));
        grid.setCacheColorHint(Color.TRANSPARENT);
        // Each tile draws its own press ripple over its picture.
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        tiles = new Tiles(context, palette, tileSize(context));
        grid.setAdapter(tiles);
        grid.setOnItemClickListener((parent, view, position, id) -> open(context, tiles.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            options(view, tiles.get(position));
            return true;
        });
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = new TextView(context);
        empty.setTextColor(palette.summary);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Dim.dp(32), Dim.dp(32), Dim.dp(32), Dim.dp(32));
        empty.setCompoundDrawablePadding(Dim.dp(12));
        // A mark over the words, so an empty page reads as empty at a glance: the history clock
        // before anything's been seen, the magnifier when a search or tab leaves nothing.
        nothingYet = emptyMark(context, SettingsIcons.UPDATES);
        nothingMatches = emptyMark(context, SettingsIcons.SEARCH);
        // Shown in place of the list or the grid by show(): an AdapterView's own empty view sets
        // that view visible whenever it has rows, so the list and the grid can't both have one.
        body.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
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
        for (int at = 0; at < TABS.length; at++) {
            boolean picked = TABS[at] == wanted;
            tabs[at].setSelected(picked);
            tabs[at].setTextColor(picked ? palette.heading : palette.summary);
            tabs[at].setTypeface(Typeface.create("sans-serif-medium", picked ? Typeface.BOLD : Typeface.NORMAL));
        }
        show();
    }

    /** Whether the page shows the grid, as it was last left. A pause leaves it as it was. */
    static boolean asGrid() {
        return Settings.VIEWING_HISTORY_GRID.savedValue();
    }

    private void showAsGrid(boolean tiled) {
        Settings.VIEWING_HISTORY_GRID.save(tiled);
        paintLayoutButton();
        show();
    }

    /** The button shows the way the page isn't showing, and says it. */
    private void paintLayoutButton() {
        boolean tiled = asGrid();
        paintIcon(layout, palette, tiled ? SettingsIcons.LIST : SettingsIcons.GRID,
                L10n.t(dialog.getContext(), tiled ? "Show as list" : "Show as grid"));
    }

    private void show() {
        List<ViewingHistory.Row> shown = matching(all, kind, String.valueOf(search.getText()));
        rows.set(shown);
        tiles.set(shown);
        boolean tiled = asGrid();
        list.setVisibility(!shown.isEmpty() && !tiled ? View.VISIBLE : View.GONE);
        grid.setVisibility(!shown.isEmpty() && tiled ? View.VISIBLE : View.GONE);
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        Context context = dialog.getContext();
        empty.setText(all.isEmpty() ? HushgramPreferenceFragment.viewingHistorySummary(all, System.currentTimeMillis())
                : L10n.t(context, "Nothing matches."));
        empty.setCompoundDrawablesRelative(null, all.isEmpty() ? nothingYet : nothingMatches, null, null);
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

    /** Where the person who posted [row] is, for a post or a reel. Null for a profile or a story, whose tap already goes there. */
    @Nullable
    static Uri profileLink(ViewingHistory.Row row) {
        if (row.kind == Kind.PROFILE || row.kind == Kind.STORY || row.owner.isEmpty()) return null;
        return Uri.parse("https://www.instagram.com/" + Uri.encode(row.owner) + "/");
    }

    static final int COPY_LINK = 1;
    static final int OPEN_PROFILE = 2;
    static final int REMOVE = 3;

    /** What a long press on [row] offers: copying its link, its poster's profile, and taking it off the list. */
    static void fill(Menu menu, ViewingHistory.Row row) {
        if (link(row) != null) menu.add(Menu.NONE, COPY_LINK, Menu.NONE, L10n.t("Copy link"));
        if (profileLink(row) != null) menu.add(Menu.NONE, OPEN_PROFILE, Menu.NONE, L10n.t("Open profile"));
        menu.add(Menu.NONE, REMOVE, Menu.NONE, L10n.t("Remove from history"));
    }

    private void options(View anchor, ViewingHistory.Row row) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        fill(menu.getMenu(), row);
        menu.setOnMenuItemClickListener(item -> pick(item.getItemId(), row));
        menu.show();
    }

    /** Does what the long press menu's [id] says for [row]. */
    boolean pick(int id, ViewingHistory.Row row) {
        Context context = dialog.getContext();
        switch (id) {
            case COPY_LINK:
                Uri link = link(row);
                if (link == null) return true;
                Utils.setClipboard(context, "Instagram", link.toString());
                Utils.showToastShort(L10n.t("Copied"));
                return true;
            case OPEN_PROFILE:
                go(context, profileLink(row));
                return true;
            case REMOVE:
                if (!HushgramPreferenceFragment.viewingHistoryChangeAllowed(origin)) return true;
                ViewingHistory.remove(context, row.kind, row.id);
                forgetPicture(row);
                // Off the page at once. The file catches up off the main thread.
                List<ViewingHistory.Row> left = new ArrayList<>(all);
                left.remove(row);
                all = left;
                show();
                return true;
            default:
                return false;
        }
    }

    private void open(Context context, ViewingHistory.Row row) {
        go(context, link(row));
    }

    private void go(Context context, @Nullable Uri link) {
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

    /**
     * A tab's press ripple over a 2dp line in the heading blue under its words, drawn only while
     * the tab is picked, so the picked tab shows by more than its colour.
     */
    static RippleDrawable tabSurface(ScreenColors palette) {
        GradientDrawable line = new GradientDrawable();
        line.setColor(palette.heading);
        LayerDrawable under = new LayerDrawable(new Drawable[]{line});
        under.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        under.setLayerHeight(0, Dim.dp(2));
        under.setLayerInsetRelative(0, Dim.dp(12), 0, Dim.dp(12), Dim.dp(6));
        StateListDrawable picked = new StateListDrawable();
        picked.addState(new int[]{android.R.attr.state_selected}, under);
        return new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), picked,
                new ColorDrawable(Color.WHITE));
    }

    private Drawable emptyMark(Context context, String icon) {
        Drawable mark = SettingsIcons.icon(context, icon, palette.hint);
        mark.setBounds(0, 0, Dim.dp(40), Dim.dp(40));
        return mark;
    }

    private static ImageButton iconButton(Context context, ScreenColors palette, String icon, String label, View.OnClickListener tap) {
        ImageButton button = new ImageButton(context);
        paintIcon(button, palette, icon, label);
        button.setPadding(Dim.dp(12), Dim.dp(12), Dim.dp(12), Dim.dp(12));
        button.setBackground(ripple(palette));
        button.setOnClickListener(tap);
        return button;
    }

    private static void paintIcon(ImageButton button, ScreenColors palette, String icon, String label) {
        Drawable drawable = SettingsIcons.icon(button.getContext(), icon, palette.title);
        drawable.setBounds(0, 0, Dim.dp(24), Dim.dp(24));
        button.setImageDrawable(drawable);
        button.setContentDescription(label);
    }

    /** How many pixels across the grid's pictures are read at: a third of the screen. */
    static int tileSize(Context context) {
        return Math.max(1, context.getResources().getDisplayMetrics().widthPixels / GRID_COLUMNS);
    }

    /**
     * The pictures shown lately, by row and size, in an eighth of the memory Instagram may use,
     * from 8 MB up to 32 MB: a screen of the grid's tiles takes about 12 MB.
     */
    private static final LruCache<String, Bitmap> PICTURES = new LruCache<String, Bitmap>(cacheBytes()) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    static int cacheBytes() {
        long eighth = Runtime.getRuntime().maxMemory() / 8;
        return (int) Math.max(8L << 20, Math.min(eighth, 32L << 20));
    }

    /** A lock per row, so two views of one row share one load, the list's and the grid's included. */
    private static final ConcurrentHashMap<String, Object> LOADING = new ConcurrentHashMap<>();

    private static String key(ViewingHistory.Row row) {
        return row.kind.name() + "\t" + row.id;
    }

    private static String key(ViewingHistory.Row row, int size) {
        return key(row) + "\t" + size;
    }

    /**
     * Reads or fetches [row]'s picture once at [size]. A second view of the row waits for the first
     * load and shows its picture, instead of fetching again or reading back the copy the first one saved.
     */
    @Nullable
    private static Bitmap load(Context application, ViewingHistory.Row row, int size) {
        synchronized (LOADING.computeIfAbsent(key(row), ignored -> new Object())) {
            String key = key(row, size);
            Bitmap cached = PICTURES.get(key);
            if (cached != null) return cached;
            Bitmap bitmap = ViewingHistory.picture(application, row, size);
            if (bitmap != null) PICTURES.put(key, bitmap);
            return bitmap;
        }
    }

    /** Forgets the pictures shown, for Clear viewing history. */
    static void forgetPictures() {
        PICTURES.evictAll();
    }

    /** Forgets [row]'s pictures shown, at every size, for Remove from history. */
    static void forgetPicture(ViewingHistory.Row row) {
        String held = key(row) + "\t";
        for (String key : PICTURES.snapshot().keySet()) {
            if (key.startsWith(held)) PICTURES.remove(key);
        }
    }

    /**
     * Shows [row]'s picture in [view], read at [size] pixels: at once when it was shown lately,
     * else its kind's icon [inset] from the edges while the picture is read or fetched off the
     * main thread. A recycled view that has moved on to another row by then keeps that row's.
     */
    private static void showPicture(Context context, ScreenColors palette, ImageView view, ViewingHistory.Row row,
                                    int size, int inset) {
        String key = key(row, size);
        view.setTag(key);
        Bitmap cached = PICTURES.get(key);
        if (cached != null) {
            fill(view, cached);
            return;
        }
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(inset, inset, inset, inset);
        view.setImageDrawable(SettingsIcons.icon(context, kindIcon(row.kind), palette.summary));
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> {
            Bitmap bitmap = load(application, row, size);
            if (bitmap == null) return;
            Utils.runOnMainThread(() -> {
                if (key.equals(view.getTag())) fill(view, bitmap);
            });
        });
    }

    private static void fill(ImageView view, Bitmap bitmap) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setPadding(0, 0, 0, 0);
        view.setImageBitmap(bitmap);
    }

    /** The icon a row shows until its picture comes, or when it has none. */
    static String kindIcon(Kind kind) {
        switch (kind) {
            case POST: return SettingsIcons.FEED;
            case REEL: return SettingsIcons.REELS;
            case STORY: return SettingsIcons.STORIES;
            default: return SettingsIcons.PERSON;
        }
    }

    /** The list's rows: a picture, then who, then what it was and when. */
    static final class Rows extends BaseAdapter {
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
            LinearLayout words = texts(row);
            ((TextView) words.getChildAt(0)).setText(seen.owner.isEmpty() ? label : "@" + seen.owner);
            ((TextView) words.getChildAt(1)).setText(seen.owner.isEmpty() ? ago : label + ", " + ago);
            showPicture(context, palette, picture(row), seen, Dim.dp(PICTURE_DP), Dim.dp(12));
            return row;
        }

        /** A row's picture. */
        static ImageView picture(LinearLayout row) {
            return (ImageView) row.getChildAt(0);
        }

        /** A row's two lines of words. */
        static LinearLayout texts(LinearLayout row) {
            return (LinearLayout) row.getChildAt(1);
        }

        private LinearLayout make() {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Dim.dp(64));
            row.setPaddingRelative(Dim.dp(16), Dim.dp(8), Dim.dp(16), Dim.dp(8));
            row.setBackground(ripple(palette));

            ImageView picture = new ImageView(context);
            // The words beside it say what the row is.
            picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable well = new GradientDrawable();
            well.setColor(palette.card);
            well.setCornerRadius(Dim.dp(8));
            picture.setBackground(well);
            // Rounded like the well under it.
            picture.setClipToOutline(true);
            LinearLayout.LayoutParams pictureSpace = new LinearLayout.LayoutParams(Dim.dp(PICTURE_DP), Dim.dp(PICTURE_DP));
            pictureSpace.setMarginEnd(Dim.dp(12));
            row.addView(picture, pictureSpace);

            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView who = new TextView(context);
            who.setTextColor(palette.title);
            who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            who.setSingleLine(true);
            // A long name or a long "Reel, 3 hours ago" in a wordy language ends in an ellipsis
            // instead of being cut off mid-letter at the row's edge.
            who.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(who);
            TextView what = new TextView(context);
            what.setTextColor(palette.summary);
            what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            what.setSingleLine(true);
            what.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(what);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    /** How big a row's picture is. */
    static final int PICTURE_DP = 48;

    /**
     * The grid's tiles (#773): the picture filling a square, who posted it over its foot and its
     * kind's icon in a corner, read out as the list's row is.
     */
    static final class Tiles extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private final int size;
        private List<ViewingHistory.Row> shown = Collections.emptyList();

        Tiles(Context context, ScreenColors palette, int size) {
            this.context = context;
            this.palette = palette;
            this.size = size;
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
            Tile tile = reused instanceof Tile ? (Tile) reused : make();
            ViewingHistory.Row seen = shown.get(position);
            String label = HushgramPreferenceFragment.viewingKindLabel(seen.kind);
            String ago = DateUtils.getRelativeTimeSpanString(seen.time, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            String who = seen.owner.isEmpty() ? label : "@" + seen.owner;
            caption(tile).setText(who);
            tile.setContentDescription(seen.owner.isEmpty() ? label + ", " + ago : who + ", " + label + ", " + ago);
            badge(tile).setImageDrawable(SettingsIcons.icon(context, kindIcon(seen.kind), Color.WHITE));
            showPicture(context, palette, picture(tile), seen, size, size / 3);
            return tile;
        }

        /** A tile's picture. */
        static ImageView picture(FrameLayout tile) {
            return (ImageView) tile.getChildAt(0);
        }

        /** Who posted a tile's picture. */
        static TextView caption(FrameLayout tile) {
            return (TextView) tile.getChildAt(1);
        }

        /** A tile's kind. */
        static ImageView badge(FrameLayout tile) {
            return (ImageView) tile.getChildAt(2);
        }

        private Tile make() {
            Tile tile = new Tile(context);
            tile.setBackgroundColor(palette.card);
            tile.setForeground(ripple(palette));

            // The tile's own words say what it is, so its parts aren't read on their own.
            ImageView picture = new ImageView(context);
            picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(picture, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView caption = new TextView(context);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            caption.setTextColor(Color.WHITE);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPaddingRelative(Dim.dp(6), Dim.dp(14), Dim.dp(6), Dim.dp(5));
            // A shade under the name, so it reads over a light picture too.
            caption.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, 0x00000000}));
            tile.addView(caption, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            ImageView badge = new ImageView(context);
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0x66000000);
            badge.setBackground(dot);
            badge.setPadding(Dim.dp(4), Dim.dp(4), Dim.dp(4), Dim.dp(4));
            FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(Dim.dp(24), Dim.dp(24), Gravity.TOP | Gravity.END);
            corner.setMargins(Dim.dp(6), Dim.dp(6), Dim.dp(6), Dim.dp(6));
            tile.addView(badge, corner);
            return tile;
        }
    }

    /** A grid tile, as tall as the column is wide. */
    static final class Tile extends FrameLayout {
        Tile(Context context) {
            super(context);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, widthSpec);
        }
    }
}
