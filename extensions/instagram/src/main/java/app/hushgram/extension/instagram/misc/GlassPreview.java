/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;

/**
 * A small sample of the glass tab bar for the settings page (#391): a pill over colorful shapes, drawn with
 * the blur radius, tint and edge bend the sliders hold. While a slider is dragged its row feeds the value in
 * here ({@link #live}), so the sample moves with the thumb and the saved value follows on release. The blur
 * needs Android 12 and a hardware canvas and the bend Android 13, as on the bar itself. Without them the
 * sample is the tint alone. Nothing here throws.
 */
public final class GlassPreview extends View {
    /** The sliders that feed the sample while they're dragged. */
    public static final int BLUR = 0;
    public static final int TINT = 1;
    public static final int BEND = 2;

    private static final int[] SCENE = {0xff3b82f6, 0xffa855f7, 0xfff97316, 0xff22c55e};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final RectF capsule = new RectF();
    private final int[] live = {-1, -1, -1};
    private final float density;
    private int base = 0xff000000;
    @Nullable private RenderNode scene;
    @Nullable private GlassLens rim;
    private boolean blurred;

    public GlassPreview(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setContentDescription(app.hushgram.extension.shared.L10n.t("Preview of the tab bar"));
    }

    /** The color the settings page sits on, which says whether the sample's glass is dark or light. */
    public void setBase(int color) {
        base = color | 0xff000000;
        invalidate();
    }

    /** Draws [slider] at [value] until released with -1, ahead of the saved value. */
    public void live(int slider, int value) {
        if (slider < 0 || slider >= live.length) return;
        live[slider] = value;
        invalidate();
    }

    /** The blur radius in dp the sample draws with. */
    public int blurDp() {
        return live[BLUR] >= 0 ? live[BLUR] : Settings.GLASS_TAB_BAR_BLUR_RADIUS.get();
    }

    /** The tint strength in percent the sample draws with. */
    public int tintPercent() {
        return live[TINT] >= 0 ? live[TINT] : Settings.GLASS_TAB_BAR_OPACITY.get();
    }

    /** The edge bend in percent the sample draws with, zero while the bend switch is off. */
    public int bendPercent() {
        if (live[BEND] >= 0) return live[BEND];
        return Settings.GLASS_TAB_BAR_REFRACT.get() ? Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.get() : 0;
    }

    /** The tint over the pill, as the bar itself would lay it. */
    public int pillTint() {
        return GlassTabBar.tint(base, blurred, tintPercent() / 100f);
    }

    /** Whether the last draw had the backdrop blurred. */
    public boolean wasBlurred() {
        return blurred;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(132 * density));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        GlassLens made = rim;
        rim = null;
        if (made != null) made.discard();
        RenderNode node = scene;
        scene = null;
        if (node != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) node.discardDisplayList();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        blurred = false;
        try {
            drawScene(canvas, width, height);
            float side = 18 * density;
            float tall = 52 * density;
            pill.set(side, height - tall - 14 * density, width - side, height - 14 * density);
            float round = tall / 2f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated()) {
                blurred = drawBlurred(canvas, width, height, round);
            }
        } catch (Throwable failure) {
            blurred = false;
            Logger.printDebug(() -> "Glass preview: the blur could not draw: " + failure);
        }
        float round = pill.height() / 2f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pillTint());
        canvas.drawRoundRect(pill, round, round, paint);
        boolean dark = GlassTabBar.isDark(base);
        paint.setShader(new LinearGradient(0, pill.top, 0, pill.bottom, dark ? 0x30ffffff : 0x66ffffff, 0x00ffffff,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(pill, round, round, paint);
        paint.setShader(null);
        capsule.set(pill.left + width * 0.26f, pill.top + 5 * density, pill.left + width * 0.26f + 64 * density, pill.bottom - 5 * density);
        paint.setColor(GlassTabBar.highlight(base));
        canvas.drawRoundRect(capsule, capsule.height() / 2f, capsule.height() / 2f, paint);
        paint.setColor(dark ? 0xccffffff : 0xcc111111);
        float step = (pill.width() - 2 * round) / 3f;
        for (int tab = 0; tab < 4; tab++) {
            canvas.drawCircle(pill.left + round + step * tab, pill.centerY(), 4.5f * density, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(GlassTabBar.edge(base));
        RectF edge = new RectF(pill);
        edge.inset(density / 2f, density / 2f);
        canvas.drawRoundRect(edge, edge.height() / 2f, edge.height() / 2f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Colorful circles and bars, so blur and bend have something to act on. */
    private void drawScene(Canvas canvas, int width, int height) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, width, height, SCENE[0], SCENE[2], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        for (int at = 0; at < 7; at++) {
            paint.setColor(SCENE[at % SCENE.length]);
            float x = width * (0.08f + 0.14f * at);
            canvas.drawCircle(x, height * (at % 2 == 0 ? 0.62f : 0.82f), 15 * density, paint);
        }
        paint.setColor(0xffffffff);
        for (int at = 0; at < 5; at++) {
            float left = width * (0.06f + 0.19f * at);
            canvas.drawRect(left, height * 0.12f, left + 8 * density, height * 0.5f, paint);
        }
        paint.setColor(0xff111111);
        canvas.drawRect(0, height * 0.52f, width, height * 0.52f + 4 * density, paint);
    }

    /** Draws the scene blurred inside the pill, with the edge bend over it where Android 13 allows. */
    private boolean drawBlurred(Canvas canvas, int width, int height, float round) {
        RenderNode node = scene;
        if (node == null) {
            node = new RenderNode("hushgram-glass-preview");
            scene = node;
        }
        node.setPosition(0, 0, width, height);
        float radius = GlassBlur.effectRadius(blurDp(), density, 1f);
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        Canvas recording = node.beginRecording(width, height);
        try {
            drawScene(recording, width, height);
        } finally {
            node.endRecording();
        }
        int saved = canvas.save();
        try {
            android.graphics.Path shape = new android.graphics.Path();
            shape.addRoundRect(pill, round, round, android.graphics.Path.Direction.CW);
            canvas.clipPath(shape);
            canvas.drawRenderNode(node);
        } finally {
            canvas.restoreToCount(saved);
        }
        int bend = bendPercent();
        if (bend > 0 && GlassLens.supported()) {
            GlassLens made = rim;
            if (made == null) {
                made = new GlassLens(true);
                rim = made;
            }
            made.strengthNow = bend;
            made.paint(canvas, node, 0f, 0f, 1f, width, height, pill, round);
        }
        return true;
    }
}
