/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.download.ViewingHistory;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ui.Dim;

/**
 * One row of the viewing history at a time, big, over the history page (#865). Swiping sideways,
 * or the left and right arrow keys, goes to the next or the previous row as the page lists them,
 * with its tab and search. Open and Open profile go to Instagram as a long press's do. Back closes
 * just this, so the page is still there, scrolled to the row seen last.
 */
final class ViewingHistoryViewer {
    /** The widest the picture is read at, Instagram's own widest. */
    static final int MOST_PX = 1080;

    final Dialog dialog;
    /** Where a swipe is read, the picture's frame. */
    final FrameLayout surface;
    final ImageView picture;
    final TextView who;
    final TextView what;
    /** Which row this is of how many, like 3 of 12. */
    final TextView place;
    final TextView open;
    final TextView profile;
    private final ViewingHistoryPage page;
    private final ScreenColors palette;
    private final List<ViewingHistory.Row> rows;
    private int at;
    private float downX;
    private float downY;

    private ViewingHistoryViewer(ViewingHistoryPage page, Context context, ScreenColors palette,
                                 List<ViewingHistory.Row> rows, int at) {
        this.page = page;
        this.palette = palette;
        this.rows = new ArrayList<>(rows);
        this.at = Math.max(0, Math.min(at, this.rows.size() - 1));
        dialog = new Dialog(context, palette.theme());
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(Dim.dp(4), Dim.dp(4), Dim.dp(16), Dim.dp(4));
        bar.addView(ViewingHistoryPage.iconButton(context, palette, SettingsIcons.BACK, L10n.t(context, "Back"),
                v -> dialog.dismiss()), new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        who = new TextView(context);
        who.setTextColor(palette.title);
        who.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        who.setSingleLine(true);
        who.setEllipsize(TextUtils.TruncateAt.END);
        who.setPaddingRelative(Dim.dp(8), 0, Dim.dp(8), 0);
        who.setAccessibilityHeading(true);
        bar.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        place = new TextView(context);
        place.setTextColor(palette.summary);
        place.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bar.addView(place);
        root.addView(bar);

        surface = new FrameLayout(context);
        picture = new ImageView(context);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        surface.addView(picture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surface.setOnTouchListener((view, event) -> touched(event));
        root.addView(surface, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        what = new TextView(context);
        what.setTextColor(palette.summary);
        what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        what.setPaddingRelative(Dim.dp(20), Dim.dp(12), Dim.dp(20), 0);
        root.addView(what);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setPaddingRelative(Dim.dp(8), Dim.dp(4), Dim.dp(8), Dim.dp(8));
        open = button(context, L10n.t(context, "Open"), v -> go(ViewingHistoryPage.link(shown())));
        profile = button(context, L10n.t(context, "Open profile"), v -> go(ViewingHistoryPage.profileLink(shown())));
        buttons.addView(open);
        buttons.addView(profile);
        root.addView(buttons);

        dialog.setOnKeyListener((shown, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
                move(right != rightToLeft() ? 1 : -1);
            }
            return true;
        });
        dialog.setOnDismissListener(gone -> page.reveal(this.at));

        SettingsDialog.keepOffTheBars(root);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(palette.background));
            palette.paintBars(window);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
        }
        bind();
    }

    /** Shows [rows] from the one at [at] over the page. */
    static ViewingHistoryViewer show(ViewingHistoryPage page, Context context, ScreenColors palette,
                                     List<ViewingHistory.Row> rows, int at) {
        ViewingHistoryViewer viewer = new ViewingHistoryViewer(page, context, palette, rows, at);
        viewer.dialog.show();
        return viewer;
    }

    ViewingHistory.Row shown() {
        return rows.get(at);
    }

    int position() {
        return at;
    }

    /** Goes [step] rows on, and answers whether there was a row there. The ends stay put. */
    boolean move(int step) {
        int next = at + step;
        if (step == 0 || next < 0 || next >= rows.size()) return false;
        at = next;
        bind();
        return true;
    }

    /**
     * The rows a finger lifted [dx], [dy] pixels from where it went down on a [width] wide picture
     * moves: 1 for the next, -1 for the one before, 0 for a tap or a drag up or down. A swipe to
     * the start shows the next, as Instagram's own sideways lists do, mirrored right to left.
     */
    static int stepOf(float dx, float dy, int width, boolean rightToLeft) {
        if (Math.abs(dx) < Math.max(Dim.dp(48), width / 6f) || Math.abs(dx) < Math.abs(dy) * 1.5f) return 0;
        boolean towardStart = rightToLeft ? dx > 0 : dx < 0;
        return towardStart ? 1 : -1;
    }

    private boolean touched(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                move(stepOf(event.getX() - downX, event.getY() - downY, surface.getWidth(), rightToLeft()));
                return true;
            default:
                return true;
        }
    }

    private boolean rightToLeft() {
        return surface.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private void go(@Nullable Uri link) {
        if (page.go(dialog.getContext(), link)) dialog.dismiss();
    }

    private void bind() {
        ViewingHistory.Row row = shown();
        Context context = dialog.getContext();
        String label = HushgramPreferenceFragment.viewingKindLabel(row.kind);
        String ago = DateUtils.getRelativeTimeSpanString(row.time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        who.setText(row.owner.isEmpty() ? label : "@" + row.owner);
        what.setText(label + ", " + ago);
        place.setText(L10n.f(context, "%1$s of %2$s", String.valueOf(at + 1), String.valueOf(rows.size())));
        open.setVisibility(ViewingHistoryPage.link(row) != null ? View.VISIBLE : View.GONE);
        profile.setVisibility(ViewingHistoryPage.profileLink(row) != null ? View.VISIBLE : View.GONE);
        surface.setContentDescription(who.getText() + ", " + what.getText());
        int size = Math.min(MOST_PX, Math.max(1, context.getResources().getDisplayMetrics().widthPixels));
        ViewingHistoryPage.showWhole(context, palette, picture, row, size);
    }

    private TextView button(Context context, String label, View.OnClickListener tap) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextColor(palette.heading);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Dim.dp(48));
        button.setPaddingRelative(Dim.dp(12), 0, Dim.dp(12), 0);
        button.setBackground(ViewingHistoryPage.tabSurface(palette));
        button.setOnClickListener(tap);
        return button;
    }
}
