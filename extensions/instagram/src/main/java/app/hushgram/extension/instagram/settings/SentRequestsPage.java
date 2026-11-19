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

import app.hushgram.extension.instagram.profile.SentRequests;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ui.Dim;

/**
 * The Sent follow requests page (#690): a search box and the requests the signed-in account sent
 * that are still waiting, the newest first, each with its picture, username and name. Tapping a row
 * opens that profile, where Instagram's own follow button cancels the request and asks first. A long
 * press copies the username or takes the account off the list, leaving the request as it is.
 */
@SuppressWarnings("deprecation") // Preference is what the settings page builds on.
public final class SentRequestsPage {
    final Dialog dialog;
    final EditText search;
    final ListView list;
    final TextView empty;
    /** The settings row it was opened from. */
    private final Preference origin;
    private final ScreenColors palette = ScreenColors.current();
    private final Rows rows;
    private final Drawable nothingYet;
    private final Drawable nothingMatches;
    private List<SentRequests.Request> all = Collections.emptyList();

    private SentRequestsPage(Context context, Preference row) {
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
        title.setText(L10n.t(context, "Sent follow requests"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPaddingRelative(Dim.dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        TextView hint = new TextView(context);
        hint.setText(L10n.t(context, "Tap an account to open its profile, where its follow button cancels the request. "
                + "Instagram asks before it does."));
        hint.setTextColor(palette.summary);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setPaddingRelative(Dim.dp(18), 0, Dim.dp(18), Dim.dp(4));
        root.addView(hint);

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
        // Where the Saved profiles page's sits.
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
            palette.paintBars(window);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    /** Opens the page over the settings, from the Your sent requests [row]. */
    static SentRequestsPage open(Context context, Preference row) {
        SentRequestsPage page = new SentRequestsPage(context, row);
        page.show();
        page.read();
        page.dialog.show();
        return page;
    }

    /** Reads the list off the main thread, since the first read comes from a file. */
    private void read() {
        if (!Utils.runOnBackgroundThread(() -> {
            List<SentRequests.Request> read = SentRequests.all(SentRequests.shownOwner());
            Utils.runOnMainThread(() -> {
                all = read;
                show();
            });
        })) {
            all = SentRequests.all(SentRequests.shownOwner());
            show();
        }
    }

    private void show() {
        rows.set(SentRequests.matching(all, String.valueOf(search.getText())));
        empty.setText(all.isEmpty() ? HushgramPreferenceFragment.sentRequestsSummary(all)
                : L10n.t(dialog.getContext(), "Nothing matches."));
        empty.setCompoundDrawablesRelative(null, all.isEmpty() ? nothingYet : nothingMatches, null, null);
    }

    /** [request]'s profile, by the username it was seen with last. */
    static Uri link(SentRequests.Request request) {
        return Uri.parse("https://www.instagram.com/" + Uri.encode(request.username) + "/");
    }

    static final int COPY_USERNAME = 1;
    static final int REMOVE = 2;

    /** What a long press on a row offers: copying the username, and taking the account off the list. */
    static void fill(Menu menu) {
        menu.add(Menu.NONE, COPY_USERNAME, Menu.NONE, L10n.t("Copy username"));
        menu.add(Menu.NONE, REMOVE, Menu.NONE, L10n.t("Remove from the list"));
    }

    private boolean options(View anchor, SentRequests.Request request) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        fill(menu.getMenu());
        menu.setOnMenuItemClickListener(item -> pick(item.getItemId(), request));
        menu.show();
        return true;
    }

    /** Does what the long press menu's [id] says for [request]. */
    boolean pick(int id, SentRequests.Request request) {
        switch (id) {
            case COPY_USERNAME:
                Utils.setClipboard(dialog.getContext(), "Instagram", request.username);
                Utils.showToastShort(L10n.t("Copied"));
                return true;
            case REMOVE:
                remove(request);
                return true;
            default:
                return false;
        }
    }

    /**
     * Takes [request] off the list and the page at once, says so, and brings the settings row up to
     * date. The request itself stays sent, and the file catches up off the main thread. The settings
     * lock holds it, as it holds Clear.
     */
    void remove(SentRequests.Request request) {
        if (!HushgramPreferenceFragment.sentRequestsChangeAllowed(origin)) return;
        SentRequests.remove(request);
        PICTURES.remove(key(request));
        List<SentRequests.Request> left = new ArrayList<>(all);
        left.remove(request);
        all = left;
        show();
        origin.setSummary(HushgramPreferenceFragment.sentRequestsSummary(all));
        Utils.showToastShort(L10n.f("Removed @%s from the list", request.username));
    }

    private void go(Context context, Uri link) {
        try {
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

    /** The pictures shown lately, by account and picture, up to about 4 MB. */
    private static final LruCache<String, Bitmap> PICTURES = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static String key(SentRequests.Request request) {
        return request.id + "\t" + request.username + "\t" + request.url;
    }

    /** Forgets the pictures shown, as a new process would. */
    static void forgetPictures() {
        PICTURES.evictAll();
    }

    /** The list's rows: the picture or a person, then the username and name. */
    static final class Rows extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private List<SentRequests.Request> shown = Collections.emptyList();

        Rows(Context context, ScreenColors palette) {
            this.context = context;
            this.palette = palette;
        }

        void set(List<SentRequests.Request> requests) {
            shown = requests;
            notifyDataSetChanged();
        }

        SentRequests.Request get(int position) {
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
            SentRequests.Request request = shown.get(position);
            LinearLayout words = (LinearLayout) row.getChildAt(1);
            ((TextView) words.getChildAt(0)).setText("@" + request.username);
            TextView name = (TextView) words.getChildAt(1);
            name.setText(request.name);
            name.setVisibility(request.name.isEmpty() ? View.GONE : View.VISIBLE);
            show((ImageView) row.getChildAt(0), request);
            return row;
        }

        /**
         * Shows [request]'s picture in [view], at once when it was shown lately, else a person while
         * it's read off the main thread. A recycled view that has moved on to another row by then
         * keeps that row's.
         */
        private void show(ImageView view, SentRequests.Request request) {
            String key = key(request);
            view.setTag(key);
            Bitmap cached = PICTURES.get(key);
            if (cached != null) {
                fill(view, cached);
                return;
            }
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setPadding(Dim.dp(12), Dim.dp(12), Dim.dp(12), Dim.dp(12));
            view.setImageDrawable(SettingsIcons.icon(context, SettingsIcons.PERSON, palette.summary));
            if (request.url.isEmpty()) return;
            Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            int size = Dim.dp(PICTURE_DP);
            Utils.runOnBackgroundThread(() -> {
                Bitmap bitmap = SentRequests.picture(application, request, size);
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
            // Round, like Instagram's profile pictures.
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
            TextView name = new TextView(context);
            name.setTextColor(palette.summary);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(name);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    /** How big a row's picture is. */
    static final int PICTURE_DP = 48;
}
