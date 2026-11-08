/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.download.FeedDownloadButton;
import app.hushgram.extension.instagram.reels.HomeHeader;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A clock on Home's header that opens the viewing history in one tap (#228), where the menu button
 * takes two.
 *
 * <p>{@link GhostHeaderButton#drew} posts {@link #place} behind each header draw, like the buttons
 * beside it. It goes right before HushGram's menu button when that's there, so the menu stays last,
 * and at the end of the header's row of buttons when it isn't. The icon is drawn in code in
 * Instagram's icon color for the theme. With the switch off or HushGram paused no button is added,
 * and one left from before is taken out at the next draw. Nothing here throws.
 */
public final class HistoryHeaderButton {
    private HistoryHeaderButton() { }

    /** The name the diagnostic report counts this under, the Reels tab line the header's other hooks use. */
    static final String ROUTE = FamilyNames.REELS_TAB;
    static final String PLACED = "Home header history button placed";
    static final String NO_ROW = "Home header history button not placed, no row to put it in";
    static final String OPENED = "Home header history button opened the viewing history";

    /** The button, its own type so the next draw finds it in the row. */
    static final class Clock extends ImageView {
        Clock(Context context) {
            super(context);
        }
    }

    /** Puts the button in, leaves the one there, or takes it out, as the switch and Pause say. Never throws. */
    public static void place(View header) {
        try {
            if (header == null) return;
            boolean wanted = Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HISTORY_BUTTON_ON_HOME.get();
            LinearLayout end = HomeHeader.endRowOf(header);
            LinearLayout row = end != null ? end : HomeHeader.inboxRowOf(header);
            if (row == null) {
                if (wanted) HookStatus.counted(ROUTE, NO_ROW);
                return;
            }
            ImageView existing = in(row);
            if (!wanted) {
                if (existing != null) row.removeView(existing);
                return;
            }
            if (existing != null) return;
            ImageView menu = HomeMenuButton.in(row);
            int at = menu != null ? row.indexOfChild(menu) : row.getChildCount();
            View neighbor = at > 0 ? row.getChildAt(at - 1) : null;
            Clock button = create(header.getContext(), neighbor);
            row.addView(button, at, params());
            HookStatus.counted(ROUTE, PLACED);
            Logger.printDebug(() -> "Home history: the button is on Home's header");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "history button", failure);
            Logger.printException(() -> "Home history: could not put the button on Home's header", failure);
        }
    }

    /** The button HushGram put in [row], or null. */
    @Nullable
    static ImageView in(@Nullable LinearLayout row) {
        if (row == null) return null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof Clock) return (ImageView) child;
        }
        return null;
    }

    /** Opens the viewing history over the screen [view] is on. Does nothing while paused. Never throws. */
    static void press(View view) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            Activity activity = HomeMenuButton.activityOf(view.getContext());
            if (activity == null) return;
            ViewingHistoryPage.open(activity, new HushgramPreferenceFragment.Row(activity));
            HookStatus.counted(ROUTE, OPENED);
            Logger.printDebug(() -> "Home history: the header's button opened the viewing history");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "history button tap", failure);
            Logger.printException(() -> "Home history: the header's button failed", failure);
        }
    }

    private static LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private static Clock create(Context context, @Nullable View neighbor) {
        Clock button = new Clock(context);
        button.setImageDrawable(new Face(context.getResources().getDisplayMetrics().density,
                FeedDownloadButton.iconColor(context)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        HomeHeader.padLike(button, neighbor);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setContentDescription(L10n.t("Viewing history"));
        button.setOnClickListener(HistoryHeaderButton::press);
        return button;
    }

    /** A clock face drawn in code: a ring and two hands, in the stroke and color of the buttons beside it. */
    static final class Face extends Drawable {
        private final float unit;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Face(float density, int color) {
            unit = density;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        int color() {
            return paint.getColor();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            canvas.drawCircle(x, y, 9.5f * unit, paint);
            // The hands at about ten past: one up, one to the right and a little down.
            canvas.drawLine(x, y, x, y - 5.5f * unit, paint);
            canvas.drawLine(x, y, x + 4f * unit, y + 2.5f * unit, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(24 * unit);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(24 * unit);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
