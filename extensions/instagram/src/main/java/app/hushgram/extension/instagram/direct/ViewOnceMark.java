/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Mark one-time photos and videos (#165): a view once photo or video in a chat gets a
 * border and Instagram's own view once icon, so it stands out before it's opened.
 *
 * <p>Instagram's chat binds each photo or video bubble from its message and a holder of views. The
 * patch passes the bubble's frame and the message's view once flag, a plain yes or no from the
 * message Instagram already has, to {@link #mark}. The mark is a foreground drawn over the bubble,
 * so nothing about the message, the media or the thread changes. Chat rows are recycled, so each
 * bind also takes the mark off a bubble that isn't view once, or when the switch is off.
 *
 * <p>This only draws. It reads nothing from the media, saves nothing, replays nothing, sends no
 * receipt and makes no network call. The hook fails open: with the switch off, HushGram paused, the
 * settings not read yet or anything thrown, the bubble is left as Instagram drew it.
 */
public final class ViewOnceMark {
    static final String HOOK = "view once mark";
    static final String COUNT_MARKED = "view once bubbles marked";
    /** Instagram's own view once icon, by the resource name it keeps in every build. */
    static final String GLYPH = "instagram_view_once_pano_outline_24";

    private static final int ACCENT = 0xFF3797EF;

    private ViewOnceMark() {
    }

    /**
     * Injected after a photo or video bubble is bound. [frame] is the bubble's frame and [once]
     * is the message's view once flag, non-zero for yes, since a hook never takes a boolean
     * register. Never throws.
     */
    public static void mark(View frame, int once) {
        mark(frame, once != 0, ViewOnceMark::switchedOn);
    }

    static void mark(View frame, boolean once, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.VIEW_ONCE_MARK);
            if (frame == null) return;
            if (once && on.getAsBoolean()) {
                if (!(frame.getForeground() instanceof Mark)) {
                    // Don't cover a foreground Instagram set itself.
                    if (frame.getForeground() != null) return;
                    frame.setForeground(new Mark(glyph(frame.getContext())));
                }
                HookStatus.counted(FamilyNames.VIEW_ONCE_MARK, COUNT_MARKED);
            } else if (frame.getForeground() instanceof Mark) {
                frame.setForeground(null);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIEW_ONCE_MARK, HOOK, failure);
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.MARK_VIEW_ONCE.get();
    }

    /** Instagram's view once icon, or null when this build has no resource by that name. */
    private static Drawable glyph(Context context) {
        try {
            Resources resources = context.getResources();
            int id = resources.getIdentifier(GLYPH, "drawable", context.getPackageName());
            if (id == 0) return null;
            Drawable icon = context.getDrawable(id);
            return icon == null ? null : icon.mutate();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The mark: a rounded border over the bubble and the icon in its top right corner. */
    static final class Mark extends Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable glyph;

        Mark(Drawable glyph) {
            this.glyph = glyph;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(ACCENT);
            disc.setStyle(Paint.Style.FILL);
            disc.setColor(0xCC000000);
            if (glyph != null) glyph.setTint(0xFFFFFFFF);
        }

        boolean hasGlyph() {
            return glyph != null;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float density = density();
            float width = 2f * density;
            stroke.setStrokeWidth(width);
            float radius = 16f * density;
            RectF box = new RectF(bounds);
            box.inset(width / 2f, width / 2f);
            canvas.drawRoundRect(box, radius, radius, stroke);
            if (glyph == null) return;
            int size = Math.round(20f * density);
            int pad = Math.round(6f * density);
            int right = bounds.right - pad;
            int top = bounds.top + pad;
            canvas.drawCircle(right - size / 2f, top + size / 2f, size * 0.7f, disc);
            glyph.setBounds(right - size, top, right, top + size);
            glyph.draw(canvas);
        }

        private float density() {
            return Resources.getSystem().getDisplayMetrics().density;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
