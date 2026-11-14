/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushgram.extension.shared.L10n;

/**
 * A switch row's description behind a small "i" at the end of its title (#130), for a shorter
 * page. A tap on the "i" shows the description; the rest of the row still flips the switch. Off,
 * or with a screen reader exploring by touch, the description stays under the title as before.
 */
final class DescriptionInfo {
    /** The title's tag while it carries the "i", so a recycled row can be put back. */
    static final String TAG = "hushgram_description_info";
    /** Set while a search is on: a row found by a word of its description shows that word. */
    static volatile boolean searching;

    private DescriptionInfo() {
    }

    /** Puts [description] behind the "i" on [row], or the row back as Android drew it. */
    @SuppressLint("ClickableViewAccessibility") // A screen reader keeps the description shown instead.
    static void bind(View row, @Nullable CharSequence description) {
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title == null || summary == null) return;
        if (!wanted(row.getContext(), description)) {
            if (TAG.equals(title.getTag())) {
                title.setCompoundDrawablesRelative(null, null, null, null);
                title.setTag(null);
            }
            // Also on a reused row the new-row dot was just painted on: the tap it kept would show
            // the description of the row this view showed before.
            title.setOnTouchListener(null);
            return;
        }
        // Android's own bind shows the summary again on the next bind, so hiding it here is undone
        // for a row that no longer wants it.
        summary.setVisibility(View.GONE);
        // As tall as the title's letters, so a larger font size grows it along with them.
        int size = Math.max(dp(title, 16), Math.round(title.getTextSize()));
        Mark mark = new Mark(title.getCurrentTextColor(), size);
        mark.setBounds(0, 0, size, size);
        Drawable dot = title.getCompoundDrawablesRelative()[2];
        if (dot != null && NewRowDots.TAG.equals(title.getTag())) {
            // A new row keeps its dot, its spacing, its tag and its "New" label, with the "i" after.
            title.setCompoundDrawablesRelative(null, null, new Beside(dot, mark, dp(title, 6)), null);
        } else {
            title.setCompoundDrawablesRelative(null, null, mark, null);
            title.setCompoundDrawablePadding(dp(title, 6));
            title.setTag(TAG);
        }
        title.setOnTouchListener((view, event) -> {
            if (!onMark((TextView) view, event.getX())) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) show(view.getContext(), title.getText(), description);
            return true;
        });
    }

    /** Whether a row with [description] shows it behind the "i" now. */
    static boolean wanted(Context context, @Nullable CharSequence description) {
        if (TextUtils.isEmpty(description) || searching || !Settings.DESCRIPTIONS_BEHIND_INFO.savedValue()) return false;
        AccessibilityManager accessibility = context.getSystemService(AccessibilityManager.class);
        return accessibility == null || !accessibility.isTouchExplorationEnabled();
    }

    /** Whether [x] in [title] falls on its "i", with a finger's width of give before it. */
    static boolean onMark(TextView title, float x) {
        Drawable end = title.getCompoundDrawablesRelative()[2];
        if (end == null) return false;
        // Beside a new row's dot, the "i" keeps the same room it has alone.
        int reach = end instanceof Beside
                ? ((Beside) end).second.getBounds().width() + ((Beside) end).gap + dp(title, 12)
                : end.getBounds().width() + title.getCompoundDrawablePadding() + dp(title, 12);
        boolean rtl = title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        return rtl ? x <= title.getPaddingLeft() + reach : x >= title.getWidth() - title.getPaddingRight() - reach;
    }

    static AlertDialog show(Context context, CharSequence title, CharSequence description) {
        AlertDialog dialog = new AlertDialog.Builder(HushgramPreferenceFragment.themed(context))
                .setTitle(title)
                .setMessage(description)
                .setPositiveButton(L10n.t("OK"), null)
                .show();
        ScreenColors.dialog(dialog);
        return dialog;
    }

    private static int dp(View view, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics()));
    }

    /**
     * A new row's dot and its "i" side by side at the end of the title, the dot first (#689). Both
     * share the title's one end slot, so the "i" alone used to take the dot's place.
     */
    static final class Beside extends Drawable {
        final Drawable first;
        final Drawable second;
        final int gap;

        Beside(Drawable first, Drawable second, int gap) {
            this.first = first;
            this.second = second;
            this.gap = gap;
            setBounds(0, 0, first.getBounds().width() + gap + second.getBounds().width(),
                    Math.max(first.getBounds().height(), second.getBounds().height()));
        }

        @Override
        public int getIntrinsicWidth() {
            return getBounds().width();
        }

        @Override
        public int getIntrinsicHeight() {
            return getBounds().height();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            // The title hands its reading direction on, so right to left puts the "i" on the left.
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            Drawable left = rtl ? second : first;
            Drawable right = rtl ? first : second;
            place(canvas, left, bounds.left, bounds);
            place(canvas, right, bounds.right - right.getBounds().width(), bounds);
        }

        private static void place(Canvas canvas, Drawable drawable, int left, Rect bounds) {
            Rect own = drawable.getBounds();
            int saved = canvas.save();
            canvas.translate(left - own.left, bounds.top + (bounds.height() - own.height()) / 2f - own.top);
            drawable.draw(canvas);
            canvas.restoreToCount(saved);
        }

        @Override
        public void setAlpha(int alpha) {
            first.setAlpha(alpha);
            second.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            first.setColorFilter(filter);
            second.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** A ring with an "i" in it, in the title's colour. */
    static final class Mark extends Drawable {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mark(int color, int size) {
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(size / 12f);
            ring.setColor(color);
            letter.setColor(color);
            letter.setTextAlign(Paint.Align.CENTER);
            letter.setTextSize(size * 0.62f);
            letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            canvas.drawCircle(x, y, bounds.width() / 2f - ring.getStrokeWidth(), ring);
            Paint.FontMetrics metrics = letter.getFontMetrics();
            canvas.drawText("i", x, y - (metrics.ascent + metrics.descent) / 2, letter);
        }

        @Override
        public void setAlpha(int alpha) {
            ring.setAlpha(alpha);
            letter.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            ring.setColorFilter(filter);
            letter.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
