/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.widget.SeekBar;

/**
 * A seek bar over a video in the look its choice asks for (#471): {@link OverlayLook#SOLID} white, as the
 * bars always were, {@link OverlayLook#SEE_THROUGH} fainter so more of the video shows, or
 * {@link OverlayLook#FROSTED} white on a capsule of frosted glass that blurs the video under it
 * ({@link FrostedBackdrop}), or tints it where there's no blur. Seek bar on feed videos and Seek bar on
 * stories each make one and hand it their own choice as they update it.
 *
 * <p>The glass is recorded as the bar draws, which it does on each move of the video's position, and
 * holds a reference to the video's own render node, so the video plays on in it between recordings.
 */
public final class GlassSeekBar extends SeekBar {
    static final int SOLID_TRACK = 0x80FFFFFF;
    static final int SEE_THROUGH_PROGRESS = 0x99FFFFFF, SEE_THROUGH_THUMB = 0xB3FFFFFF, SEE_THROUGH_TRACK = 0x33FFFFFF;
    static final int FROSTED_TRACK = 0x4DFFFFFF;
    /** Over the blur, a light shade so a white bar reads on a bright video. Where there's no blur, a deeper one. */
    static final int FROST_TINT = 0x26000000, FROST_TINT_UNBLURRED = 0x4D000000;
    /** The capsule's hairline edge. */
    static final int FROST_EDGE = 0x59FFFFFF;
    /** The capsule's height, and how far it reaches past each end of the track, in dp. */
    static final int CAPSULE_DP = 20, CAPSULE_REACH_DP = 10;

    private final FrostedBackdrop backdrop;
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF capsule = new RectF();
    private OverlayLook look;

    public GlassSeekBar(Context context, String family, String hook) {
        super(context);
        backdrop = new FrostedBackdrop(family, hook, "hushgram-seek-bar-glass");
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(FROST_EDGE);
        look(OverlayLook.SOLID);
    }

    /** Takes on [wanted]'s colours, unless it has them already. */
    public void look(OverlayLook wanted) {
        if (wanted == null || wanted == look) return;
        look = wanted;
        setProgressTintList(ColorStateList.valueOf(progress(wanted)));
        setThumbTintList(ColorStateList.valueOf(thumb(wanted)));
        setProgressBackgroundTintList(ColorStateList.valueOf(track(wanted)));
        if (wanted != OverlayLook.FROSTED) backdrop.release();
        invalidate();
    }

    public OverlayLook look() {
        return look;
    }

    /** The colour of the part played with [look]. */
    static int progress(OverlayLook look) {
        return look == OverlayLook.SEE_THROUGH ? SEE_THROUGH_PROGRESS : Color.WHITE;
    }

    /** The colour of the handle with [look]. */
    static int thumb(OverlayLook look) {
        return look == OverlayLook.SEE_THROUGH ? SEE_THROUGH_THUMB : Color.WHITE;
    }

    /** The colour of the part still to play with [look]. */
    static int track(OverlayLook look) {
        switch (look) {
            case SEE_THROUGH:
                return SEE_THROUGH_TRACK;
            case FROSTED:
                return FROSTED_TRACK;
            default:
                return SOLID_TRACK;
        }
    }

    /** The frosted look's capsule, round the track, in the bar's own coordinates. */
    RectF capsule() {
        float density = getResources().getDisplayMetrics().density;
        float reach = CAPSULE_REACH_DP * density;
        float half = Math.min(getHeight(), CAPSULE_DP * density) / 2f;
        float middle = getHeight() / 2f;
        capsule.set(Math.max(0, getPaddingLeft() - reach), middle - half,
                Math.min(getWidth(), getWidth() - getPaddingRight() + reach), middle + half);
        return capsule;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (look == OverlayLook.FROSTED && getWidth() > 0 && getHeight() > 0) {
            backdrop.record(this, SystemClock.uptimeMillis());
            RectF shape = capsule();
            float radius = shape.height() / 2f;
            boolean blurred = backdrop.draw(canvas, shape, radius);
            tint.setColor(blurred ? FROST_TINT : FROST_TINT_UNBLURRED);
            canvas.drawRoundRect(shape, radius, radius, tint);
            float line = Math.max(1f, getResources().getDisplayMetrics().density / 2f);
            edge.setStrokeWidth(line);
            shape.inset(line / 2f, line / 2f);
            canvas.drawRoundRect(shape, radius, radius, edge);
        }
        super.onDraw(canvas);
    }
}
