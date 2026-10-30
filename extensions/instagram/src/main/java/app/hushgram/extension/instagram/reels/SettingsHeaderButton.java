/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

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

import java.util.function.Predicate;

import app.hushgram.extension.instagram.download.FeedDownloadButton;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsEntry;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A gear on Home's header that opens HushGram settings, for the Tab bar switch Settings button on
 * Home's header (#404).
 *
 * <p>The header's draw hook ({@code GhostHeaderButton.drew}) posts {@link #place} behind each draw.
 * The gear goes last in the header's end row of buttons, drawn in code in the color and size of the
 * Search and Ghost mode buttons beside it. A tap asks for the settings the way the logo's long press
 * does ({@link SettingsEntry#requestOpen}).
 *
 * <p>With the switch off or HushGram paused, no gear is added and one left from before is taken out
 * at the next draw. A tap while paused does nothing. Nothing in here throws.
 */
public final class SettingsHeaderButton {
    private SettingsHeaderButton() { }

    /** The name the diagnostic report counts this under, the Reels tab line the header's other hooks use. */
    static final String ROUTE = FamilyNames.REELS_TAB;
    static final String PLACED = "Home header Settings button placed";
    static final String NO_ROW = "Home header Settings button not placed, no row to put it in";
    static final String OPENED = "Home header Settings button opened HushGram settings";
    static final String NO_HOST = "Home header Settings button found no screen to open settings over";

    /** Stands in for the settings request in tests, or null to ask {@link SettingsEntry}. */
    @Nullable
    static volatile Predicate<Activity> openForTests;

    /** The button, its own type so the next draw finds it in the row. */
    static final class Gear extends ImageView {
        Gear(Context context) {
            super(context);
        }
    }

    /** Puts the gear in, leaves the one there, or takes it out, as the switch and Pause say. Never throws. */
    public static void place(View header) {
        try {
            if (header == null) return;
            boolean wanted = Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SETTINGS_BUTTON_ON_HOME.get();
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
            Gear button = create(header.getContext(), row.getChildCount() > 0 ? row.getChildAt(row.getChildCount() - 1) : null);
            row.addView(button, params());
            HookStatus.counted(ROUTE, PLACED);
            Logger.printDebug(() -> "Reels tab: the Settings button is on Home's header");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "settings button", failure);
            Logger.printException(() -> "Reels tab: could not put the Settings button on Home's header", failure);
        }
    }

    /** The gear HushGram put in [row], or null. */
    @Nullable
    public static ImageView in(@Nullable LinearLayout row) {
        if (row == null) return null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof Gear) return (ImageView) child;
        }
        return null;
    }

    /** The tap: opens HushGram settings over the screen in front. Does nothing while paused. Never throws. */
    static void press(View view) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            Activity activity = SearchHeaderButton.activityOf(view.getContext());
            Predicate<Activity> stand = openForTests;
            if (activity == null || !(stand != null ? stand.test(activity) : SettingsEntry.requestOpen(activity))) {
                HookStatus.counted(ROUTE, NO_HOST);
                return;
            }
            HookStatus.counted(ROUTE, OPENED);
            Logger.printDebug(() -> "Reels tab: the header's Settings button opened HushGram settings");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "settings button tap", failure);
            Logger.printException(() -> "Reels tab: the header's Settings button failed", failure);
        }
    }

    private static LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private static Gear create(Context context, @Nullable View neighbor) {
        Gear button = new Gear(context);
        button.setImageDrawable(new GearIcon(context.getResources().getDisplayMetrics().density,
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
        button.setContentDescription(L10n.t("HushGram settings"));
        button.setOnClickListener(SettingsHeaderButton::press);
        return button;
    }

    /** A gear drawn in code: a ring, a hole and eight teeth, in the stroke and color of the buttons beside it. */
    static final class GearIcon extends Drawable {
        private final float unit;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GearIcon(float density, int color) {
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
            canvas.drawCircle(x, y, 6.5f * unit, paint);
            canvas.drawCircle(x, y, 2.5f * unit, paint);
            for (int tooth = 0; tooth < 8; tooth++) {
                double angle = tooth * Math.PI / 4;
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
                canvas.drawLine(x + cos * 6.5f * unit, y + sin * 6.5f * unit,
                        x + cos * 9.5f * unit, y + sin * 9.5f * unit, paint);
            }
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
