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

    /** Whether this Android can run the lens at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    @Nullable private RenderNode node;
    @Nullable private RuntimeShader shader;
    private boolean broken;
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
                lens = new RuntimeShader(SHADER);
                shader = lens;
            }
            RenderNode layer = node;
            if (layer == null) {
                layer = new RenderNode("hushgram-glass-lens");
                node = layer;
            }
            lens.setFloatUniform("box", box.left, box.top, box.right, box.bottom);
            lens.setFloatUniform("radius", Math.max(0f, radius));
            lens.setFloatUniform("centre", CENTRE_MAGNIFY);
            lens.setFloatUniform("rim", RIM_BEND);
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
