/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A ghost button on Home's header, beside the Messages button, that turns Ghost mode on or off the
 * way the long press on New message does: every ghost switch in the build gets the same value and a
 * toast says which way it went. The icon is drawn, not taken from Instagram, and shows the state:
 * an outline while Ghost mode is off, a solid ghost while it's on.
 *
 * <p>The patch calls {@link #drew} as the header starts drawing from its state. The button is put in
 * after that call returns, in the row the Messages button sits in, since the header clears its rows
 * when it rebuilds. With the switch off, HushGram paused, or no ghost patch in the build, no button
 * is added, and one left from before is taken out the next time the header draws.
 */
public final class GhostHeaderButton {
    private GhostHeaderButton() { }

    /** The name the diagnostic report counts this under, the Reels tab line the header's other hooks use. */
    static final String ROUTE = FamilyNames.REELS_TAB;
    static final String PLACED = "Home header Ghost button placed";
    static final String NO_ANCHOR = "Home header Ghost button not placed, no row to put it in";

    /** The header's public getter for the Messages button, which Instagram's layouts and other code call by name. */
    static final String INBOX_GETTER = "getDirectInboxView";

    /**
     * Injected first thing as Home's header draws from its state. Queues the button's placement
     * behind the draw. Never throws, and does nothing to the header itself.
     */
    public static void drew(View header) {
        try {
            HookStatus.invoked(ROUTE);
            if (header == null) return;
            header.post(() -> place(header));
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "ghost button", failure);
        }
    }

    /** Puts the button in, leaves the one there, or takes it out, as the switch, Pause and the build say. */
    static void place(View header) {
        try {
            List<BooleanSetting> switches = GhostMode.switches(PatchFamily.inThisBuild());
            boolean wanted = Utils.settingsReady() && !HushgramPause.isPaused()
                    && Settings.GHOST_BUTTON_ON_HOME.get() && !switches.isEmpty();
            LinearLayout row = rowOf(header);
            if (row == null) {
                if (wanted) HookStatus.counted(ROUTE, NO_ANCHOR);
                return;
            }
            ImageView existing = find(row);
            if (!wanted) {
                if (existing != null) row.removeView(existing);
                return;
            }
            if (existing != null) {
                existing.invalidate();
                return;
            }
            View inbox = inboxButton(header);
            ImageView button = create(header.getContext(), switches, inbox);
            row.addView(button, row.indexOfChild(inbox), params(row));
            HookStatus.counted(ROUTE, PLACED);
            Logger.printDebug(() -> "Ghost mode: the button is on Home's header");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "ghost button", failure);
            Logger.printException(() -> "Ghost mode: could not put the button on Home's header", failure);
        }
    }

    /** The row the Messages button sits in, when it's a LinearLayout, or null. */
    @Nullable
    static LinearLayout rowOf(View header) {
        View inbox = inboxButton(header);
        if (inbox == null) return null;
        ViewParent parent = inbox.getParent();
        return parent instanceof LinearLayout ? (LinearLayout) parent : null;
    }

    /** Home's Messages button, asked of the header by its getter's name, or null when it has none yet. */
    @Nullable
    static View inboxButton(View header) {
        try {
            Object view = header.getClass().getMethod(INBOX_GETTER).invoke(header);
            return view instanceof View ? (View) view : null;
        } catch (ReflectiveOperationException | RuntimeException missing) {
            return null;
        }
    }

    @Nullable
    private static ImageView find(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof Ghost) return (ImageView) child;
        }
        return null;
    }

    private static LinearLayout.LayoutParams params(LinearLayout row) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private static ImageView create(Context context, List<BooleanSetting> switches, View inbox) {
        ImageView button = new ImageView(context);
        BooleanSupplier on = () -> GhostMode.on(switches);
        button.setImageDrawable(new Ghost(context.getResources().getDisplayMetrics().density, night(context), on));
        button.setScaleType(ImageView.ScaleType.CENTER);
        // The Messages button's own padding sizes the touch target the same as its neighbors.
        button.setPaddingRelative(inbox.getPaddingStart(), inbox.getPaddingTop(), inbox.getPaddingEnd(), inbox.getPaddingBottom());
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        describe(button, on.getAsBoolean());
        button.setOnClickListener(GhostHeaderButton::press);
        return button;
    }

    /** The tap: false-safe, so a paused HushGram or a build with nothing to turn leaves Ghost mode alone. */
    static void press(View view) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            List<BooleanSetting> switches = GhostMode.switches(PatchFamily.inThisBuild());
            if (switches.isEmpty()) return;
            boolean on = GhostMode.flip(switches);
            describe(view, GhostMode.on(switches));
            view.invalidate();
            Logger.printDebug(() -> "Ghost mode: the header button turned it " + (on ? "on" : "off"));
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "ghost button tap", failure);
            Logger.printException(() -> "Ghost mode: the header button's tap failed", failure);
        }
    }

    static void describe(View button, boolean on) {
        button.setContentDescription(on
                ? L10n.t("Ghost mode is on. Tap to turn it off.")
                : L10n.t("Ghost mode is off. Tap to turn it on."));
    }

    private static boolean night(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * The ghost, drawn rather than taken from Instagram's resources: an outline with two dots for
     * eyes while Ghost mode is off, solid with the eyes cut out while it's on. The color follows the
     * app's light or dark mode, like Instagram's own header icons.
     */
    static final class Ghost extends Drawable {
        private final float unit;
        private final BooleanSupplier on;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path body = new Path();
        private final RectF head = new RectF();

        Ghost(float density, boolean night, BooleanSupplier on) {
            unit = density;
            this.on = on;
            paint.setColor(night ? Color.WHITE : 0xFF262626);
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        boolean isOn() {
            return on.getAsBoolean();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            float half = 8 * unit;
            float top = y - 9 * unit;
            float bottom = y + 9 * unit;
            body.reset();
            body.setFillType(Path.FillType.EVEN_ODD);
            head.set(x - half, top, x + half, top + 2 * half);
            body.moveTo(x - half, bottom);
            body.lineTo(x - half, top + half);
            body.arcTo(head, 180, 180);
            body.lineTo(x + half, bottom);
            // Three scallops along the bottom edge, from the right corner back to the left.
            for (int step = 1; step <= 6; step++) {
                body.lineTo(x + half - step * half / 3, step % 2 == 1 ? bottom - 3 * unit : bottom);
            }
            body.close();
            float eyeY = top + half - unit;
            if (isOn()) {
                body.addCircle(x - 3 * unit, eyeY, 1.6f * unit, Path.Direction.CW);
                body.addCircle(x + 3 * unit, eyeY, 1.6f * unit, Path.Direction.CW);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(body, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawPath(body, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x - 3 * unit, eyeY, 1.4f * unit, paint);
                canvas.drawCircle(x + 3 * unit, eyeY, 1.4f * unit, paint);
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
