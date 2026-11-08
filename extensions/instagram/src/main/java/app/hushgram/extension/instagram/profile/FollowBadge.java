/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The Following list mark drawn on a profile picture (#572): a green disc with a check for an
 * account that follows you back, a red one with a cross for an account that doesn't. A ring in the
 * page's color parts it from the picture under it. It goes on the picture as its foreground, at the
 * picture's bottom end corner, so it moves with the picture however the row is laid out.
 */
final class FollowBadge extends Drawable {
    static final int GREEN = 0xFF2DB84B;
    static final int RED = 0xFFED4956;
    /** How wide the badge is, ring included. */
    static final float SIZE_DP = 18f;
    /** How wide its ring is. */
    static final float RING_DP = 2f;

    final boolean followsYou;
    final int ringColor;
    private final int size;
    private final float ring;
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    FollowBadge(Context context, boolean followsYou) {
        this.followsYou = followsYou;
        float density = context.getResources().getDisplayMetrics().density;
        size = Math.round(SIZE_DP * density);
        ring = RING_DP * density;
        ringColor = night(context) ? Color.BLACK : Color.WHITE;
        edge.setColor(ringColor);
        fill.setColor(followsYou ? GREEN : RED);
        glyph.setColor(Color.WHITE);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Whether [context] is in dark mode, as Instagram's own dark mode sets it on its pages. */
    static boolean night(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        if (radius <= ring) return;
        float x = bounds.exactCenterX(), y = bounds.exactCenterY();
        canvas.drawCircle(x, y, radius, edge);
        float inner = radius - ring;
        canvas.drawCircle(x, y, inner, fill);
        glyph.setStrokeWidth(inner * 0.26f);
        path.rewind();
        if (followsYou) {
            path.moveTo(x - inner * 0.45f, y + inner * 0.02f);
            path.lineTo(x - inner * 0.12f, y + inner * 0.34f);
            path.lineTo(x + inner * 0.46f, y - inner * 0.30f);
        } else {
            float arm = inner * 0.34f;
            path.moveTo(x - arm, y - arm);
            path.lineTo(x + arm, y + arm);
            path.moveTo(x + arm, y - arm);
            path.lineTo(x - arm, y + arm);
        }
        canvas.drawPath(path, glyph);
    }

    @Override
    public void setAlpha(int alpha) {
        edge.setAlpha(alpha);
        fill.setAlpha(alpha);
        glyph.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        edge.setColorFilter(filter);
        fill.setColorFilter(filter);
        glyph.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
