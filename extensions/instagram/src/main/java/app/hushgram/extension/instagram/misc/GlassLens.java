/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.Settings;

/**
 * The lens in the glass tab bar's raised bubble (#289), on Android 13 and newer: the blurred backdrop under
 * the selected tab's bubble drawn a little larger, bending more toward the bubble's rim, as glass would.
 * It is the backdrop the pill already blurs, run through a small shader into a render node of its own, so
 * nothing is read back and no view is touched. Before Android 13 there is no shader to run and the bubble
 * has its rim and gradient alone. A failure turns the lens off for this bar and the bubble carries on.
 */
final class GlassLens {
    /** How much the centre of the bubble magnifies, and how much more its rim bends the backdrop. */
    static final float CENTRE_MAGNIFY = 0.10f;
    static final float RIM_BEND = 0.14f;

    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 box;\n"
            + "uniform float radius;\n"
            + "uniform float centre;\n"
            + "uniform float rim;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 mid = (box.xy + box.zw) * 0.5;\n"
            + "  float2 hs = (box.zw - box.xy) * 0.5;\n"
            + "  float2 q = abs(p - mid) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (inside <= 0.0) return half4(0.0);\n"
            + "  float depth = clamp(-d / max(1.0, min(hs.x, hs.y)), 0.0, 1.0);\n"
            + "  float bend = (1.0 - depth) * (1.0 - depth);\n"
            + "  float2 source = mid + (p - mid) * (1.0 - centre - rim * bend);\n"
            + "  return content.eval(source) * inside;\n"
            + "}\n";

    /**
     * The liquid pill's rim (#369): within {@link #RIM_BAND} of the edge the backdrop is mirrored across the
     * band's inner line, as {@link #rimSource} works out, so what runs under the edge shows upside down and
     * moves the other way, as it does through the edge of real glass. Inside the band the backdrop is left as is.
     */
    private static final String RIM_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 box;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 mid = (box.xy + box.zw) * 0.5;\n"
            + "  float2 hs = (box.zw - box.xy) * 0.5;\n"
            + "  float2 q = abs(p - mid) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (inside <= 0.0) return half4(0.0);\n"
            + "  float2 side = sign(p - mid);\n"
            + "  float2 normal = q.x > 0.0 && q.y > 0.0 ? normalize(q) * side\n"
            + "      : (q.x > q.y ? float2(side.x, 0.0) : float2(0.0, side.y));\n"
            + "  float depth = max(0.0, -d);\n"
            + "  float2 source = depth < band ? p - normal * (2.0 * (band - depth)) : p;\n"
            + "  return content.eval(source) * inside;\n"
            + "}\n";

    /** How deep the rim's mirrored band reaches into the pill, as a share of the pill's half height. */
    static final float RIM_BAND = 0.42f;

    /**
     * How far in from the edge the rim samples the backdrop for a point [depth] in from it, in a band [band]
     * deep: past the band, the point itself, and within it, the mirror image across the band's inner line, so
     * the edge shows what's 2 x [band] in and the order runs backwards. The same sum as {@link #RIM_SHADER}.
     */
    static float rimSource(float depth, float band) {
        return depth < band ? 2f * band - depth : depth;
    }

    /** The deepest the rim's band may reach, as a share of the pill's half height, whatever the strength. */
    static final float MAX_RIM_BAND = 0.95f;

    /** [percent] as the multiplier the bend is scaled by, kept inside the slider's range. */
    static float scale(int percent) {
        return Math.max(0, Math.min(200, percent)) / 100f;
    }

    /** The rim's band at [percent] of its standard depth, as a share of the pill's half height. */
    static float bandShare(int percent) {
        return Math.min(MAX_RIM_BAND, RIM_BAND * scale(percent));
    }

    /** Whether this Android can run the lens at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /** Whether this is the pill's rim (#369) rather than the bubble's lens (#289). */
    private final boolean rim;
    /** A strength to draw at instead of the saved one, in percent, while a slider is being dragged. -1 for none. */
    int strengthNow = -1;
    @Nullable private RenderNode node;
    @Nullable private RuntimeShader shader;
    private boolean broken;

    /** The bubble's lens. */
    GlassLens() {
        this(false);
    }

    /** The bubble's lens, or with [rim] the pill's rim. */
    GlassLens(boolean rim) {
        this.rim = rim;
    }

    /** The bend's strength in percent: the one being dragged, else the slider's, else the standard for a bar with no bend switch on. */
    int percent() {
        if (strengthNow >= 0) return strengthNow;
        return Settings.GLASS_TAB_BAR_REFRACT.get() ? Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.get() : 100;
    }

    /** How many times the lens was asked to paint, and how many it painted. Read by tests. */
    int asked;
    int painted;

    /**
     * Draws the lens over [box] ([radius] corners), in the bar's coordinates, into [canvas]. [backdrop] is
     * the pill's blurred backdrop node, which the pill draws at [nodeLeft], [nodeTop] and [downsample] times
     * its size. [width] and [height] are the bar's. Says whether the lens drew.
     */
    boolean paint(Canvas canvas, @Nullable RenderNode backdrop, float nodeLeft, float nodeTop, float downsample,
            int width, int height, RectF box, float radius) {
        asked++;
        if (broken || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backdrop == null || width <= 0 || height <= 0 || !canvas.isHardwareAccelerated()) {
            return false;
        }
        try {
            RuntimeShader lens = shader;
            if (lens == null) {
                lens = new RuntimeShader(rim ? RIM_SHADER : SHADER);
                shader = lens;
            }
            RenderNode layer = node;
            if (layer == null) {
                layer = new RenderNode(rim ? "hushgram-glass-rim" : "hushgram-glass-lens");
                node = layer;
            }
            lens.setFloatUniform("box", box.left, box.top, box.right, box.bottom);
            lens.setFloatUniform("radius", Math.max(0f, radius));
            if (rim) {
                lens.setFloatUniform("band", bandShare(percent()) * Math.min(box.width(), box.height()) / 2f);
            } else {
                float bend = scale(percent());
                lens.setFloatUniform("centre", CENTRE_MAGNIFY * bend);
                lens.setFloatUniform("rim", RIM_BEND * bend);
            }
            layer.setPosition(0, 0, width, height);
            layer.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"));
            Canvas recording = layer.beginRecording(width, height);
            try {
                recording.translate(nodeLeft, nodeTop);
                recording.scale(downsample, downsample);
                recording.drawRenderNode(backdrop);
            } finally {
                layer.endRecording();
            }
            canvas.drawRenderNode(layer);
            painted++;
            return true;
        } catch (Throwable failure) {
            broken = true;
            GlassTabBar.diagnose("lens failed", failure);
            return false;
        }
    }

    /** Lets go of the lens's render node. */
    void discard() {
        RenderNode layer = node;
        node = null;
        shader = null;
        if (layer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) layer.discardDisplayList();
    }
}
