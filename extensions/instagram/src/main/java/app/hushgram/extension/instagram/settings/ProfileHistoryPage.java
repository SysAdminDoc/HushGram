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
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
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

import app.hushgram.extension.instagram.download.ProfileHistory;
import app.hushgram.extension.instagram.download.ProfileHistory.Field;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ui.Dim;

/**
 * The profile history's own page (#611): a search box and every username, bio and picture an
 * account was seen to replace, the newest change first. Tapping a row opens that account's
 * profile by the username it has now.
 */
@SuppressWarnings("deprecation") // Preference is what the settings page builds on.
final class ProfileHistoryPage {
    final Dialog dialog;
    final EditText search;
    final ListView list;
    final TextView empty;
    private final ScreenColors palette = ScreenColors.current();
    private final Rows rows;
    private final Drawable nothingYet;
    private final Drawable nothingMatches;
    private List<ProfileHistory.Past> all = Collections.emptyList();

    private ProfileHistoryPage(Context context, Preference row) {
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
        title.setText(L10n.t(context, "Profile changes"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPaddingRelative(Dim.dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(iconButton(context, palette, SettingsIcons.DELETE, L10n.t(context, "Clear profile history"), v ->
                HushgramPreferenceFragment.clearProfileHistory(row, row, this::read)),
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
        // Where the settings search and the viewing history's sit.
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
        list.setOnItemLongClickListener((parent, view, position, id) -> options(view, rows.get(position)));
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
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    /** Opens the page over the settings, from the profile history's row. */
    static ProfileHistoryPage open(Context context, Preference row) {
        ProfileHistoryPage page = new ProfileHistoryPage(context, row);
        page.show();
        page.read();
        page.dialog.show();
        return page;
    }

    /** Reads the changes off the main thread, since the first read comes from a file. */
    private void read() {
        Context application = dialog.getContext().getApplicationContext();
        if (!Utils.runOnBackgroundThread(() -> {
            List<ProfileHistory.Past> read = ProfileHistory.past(application);
            Utils.runOnMainThread(() -> {
                all = read;
                show();
            });
        })) {
            all = ProfileHistory.past(application);
            show();
        }
    }

    private void show() {
        rows.set(matching(all, String.valueOf(search.getText())));
        empty.setText(all.isEmpty() ? HushgramPreferenceFragment.profileHistorySummary(all, System.currentTimeMillis())
                : L10n.t(dialog.getContext(), "Nothing matches."));
        empty.setCompoundDrawablesRelative(null, all.isEmpty() ? nothingYet : nothingMatches, null, null);
    }

    /** The changes whose account's username now, old username or old bio holds [query], in their order. */
    static List<ProfileHistory.Past> matching(List<ProfileHistory.Past> changes, @Nullable String query) {
        String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (wanted.startsWith("@")) wanted = wanted.substring(1);
        List<ProfileHistory.Past> kept = new ArrayList<>();
        for (ProfileHistory.Past change : changes) {
            boolean words = change.entry.field != Field.PICTURE
                    && change.entry.value.toLowerCase(Locale.ROOT).contains(wanted);
            if (!wanted.isEmpty() && !words && !change.username.toLowerCase(Locale.ROOT).contains(wanted)) continue;
            kept.add(change);
        }
        return kept;
    }

    /** The profile [change] belongs to, by the username it has now. */
    @Nullable
    static Uri link(ProfileHistory.Past change) {
        return change.username.isEmpty() ? null
                : Uri.parse("https://www.instagram.com/" + Uri.encode(change.username) + "/");
    }

    static final int COPY_USERNAME = 1;
    static final int COPY_BIO = 2;

    /** What a long press on [change] offers: copying the old username or the old bio. Nothing for a picture. */
    static void fill(Menu menu, ProfileHistory.Past change) {
        if (change.entry.field == Field.USERNAME) menu.add(Menu.NONE, COPY_USERNAME, Menu.NONE, L10n.t("Copy username"));
        if (change.entry.field == Field.BIO && !change.entry.value.isEmpty()) {
            menu.add(Menu.NONE, COPY_BIO, Menu.NONE, L10n.t("Copy bio"));
        }
    }

    private boolean options(View anchor, ProfileHistory.Past change) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        fill(menu.getMenu(), change);
        if (menu.getMenu().size() == 0) return false;
        menu.setOnMenuItemClickListener(item -> pick(item.getItemId(), change));
        menu.show();
        return true;
    }

    /** Does what the long press menu's [id] says for [change]. */
    boolean pick(int id, ProfileHistory.Past change) {
        if (id != COPY_USERNAME && id != COPY_BIO) return false;
        Utils.setClipboard(dialog.getContext(), "Instagram", change.entry.value);
        Utils.showToastShort(L10n.t("Copied"));
        return true;
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

    /** The old pictures shown lately, by account and picture, up to about 4 MB. */
    private static final LruCache<String, Bitmap> PICTURES = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Forgets the pictures shown, for Clear profile history. */
    static void forgetPictures() {
        PICTURES.evictAll();
    }

    /** The list's rows: the old picture or a person, then the account, then what it had and when. */
    static final class Rows extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private List<ProfileHistory.Past> shown = Collections.emptyList();

        Rows(Context context, ScreenColors palette) {
            this.context = context;
            this.palette = palette;
        }

        void set(List<ProfileHistory.Past> changes) {
            shown = changes;
            notifyDataSetChanged();
        }

        ProfileHistory.Past get(int position) {
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
            ProfileHistory.Past change = shown.get(position);
            String what = HushgramPreferenceFragment.profileChangeLabel(change, System.currentTimeMillis());
            // An old bio shows under its line, a few lines of it at most.
            if (change.entry.field == Field.BIO && !change.entry.value.isEmpty()) what += "\n" + change.entry.value;
            LinearLayout words = (LinearLayout) row.getChildAt(1);
            ((TextView) words.getChildAt(0)).setText("@" + change.username);
            ((TextView) words.getChildAt(1)).setText(what);
            show((ImageView) row.getChildAt(0), change);
            return row;
        }

        /**
         * Shows [change]'s old picture in [view], at once when it was shown lately, else a person
         * while it's read off the main thread. A username or a bio shows the person. A recycled view
         * that has moved on to another row by then keeps that row's.
         */
        private void show(ImageView view, ProfileHistory.Past change) {
            String key = change.id + "\t" + change.entry.value;
            view.setTag(key);
            Bitmap cached = change.entry.field == Field.PICTURE ? PICTURES.get(key) : null;
            if (cached != null) {
                fill(view, cached);
                return;
            }
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setPadding(Dim.dp(12), Dim.dp(12), Dim.dp(12), Dim.dp(12));
            view.setImageDrawable(SettingsIcons.icon(context, SettingsIcons.PERSON, palette.summary));
            if (change.entry.field != Field.PICTURE) return;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            int size = Dim.dp(PICTURE_DP);
            Utils.runOnBackgroundThread(() -> {
                Bitmap bitmap = ProfileHistory.picture(application, change.id, change.entry, size);
                if (bitmap == null) return;
                PICTURES.put(key, bitmap);
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
            // Round, like the profile pictures it holds.
            well.setShape(GradientDrawable.OVAL);
            picture.setBackground(well);
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
            who.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(who);
            TextView what = new TextView(context);
            what.setTextColor(palette.summary);
            what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            what.setMaxLines(4);
            what.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(what);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    /** How big a row's picture is. */
    static final int PICTURE_DP = 48;
}
