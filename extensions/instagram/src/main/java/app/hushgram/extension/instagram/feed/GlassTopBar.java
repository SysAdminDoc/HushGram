/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.misc.GlassBlur;
import app.hushgram.extension.instagram.misc.GlassTabBar;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the See-through top bar switch (#418): Home's header shows the feed blurred under it,
 * like frosted glass, with a hairline along its bottom.
 *
 * <p>{@link HomeTopBar#slidingViews} hands the header here as Home sets it up. The header gets a
 * background of its own: a render node holding the views its parent draws before it, the feed, blurred
 * and tinted the way the glass tab bar does it, with the tab bar's blur and opacity sliders. Nothing is
 * copied. The node records those views once more, as the glass tab bar does, so the posts move under the
 * bar as they scroll. Below Android 12 there's no blur, so the header gets a firmer plain tint.
 *
 * <p>A background Instagram puts on the header or on a view in it as wide as the header would cover the
 * glass, so each frame takes it off and keeps it. Everything goes back when the switch is off, HushGram
 * is paused or the header leaves the window. It fails open: anything thrown puts Instagram's header back.
 */
public final class GlassTopBar {
    static final String HOOK = "top bar glass";
    static final String COUNT_GLAZED = "top bar glazed";
    /** The backdrop is recorded at this fraction of its size and blurred there, like the glass tab bar's. */
    static final int DOWNSAMPLE = 4;
    /** The hairline along the bottom of a dark bar, and of a light one. */
    static final int HAIRLINE_DARK = 0x1fffffff;
    static final int HAIRLINE_LIGHT = 0x1f000000;
    /** How deep under the header a view as wide as it is has its background taken off. */
    private static final int CLEAR_DEPTH = 3;

    /** Headers already given the glass. Weak, so a header Home lets go of goes with it. Main thread. */
    private static final Set<View> GLAZED = Collections.newSetFromMap(new WeakHashMap<>());

    private GlassTopBar() {
    }

    /** Gives each view in [views] the glass while the switch is on. Never throws. */
    static void glaze(List<?> views) {
        glaze(views, GlassTopBar::switchedOn);
    }

    static void glaze(List<?> views, BooleanSupplier on) {
        try {
            if (views == null || !on.getAsBoolean()) return;
            for (Object view : views) {
                if (view instanceof View) attach((View) view);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_TOP_BAR, HOOK, failure);
        }
    }

    private static void attach(View header) {
        if (!GLAZED.add(header)) return;
        Glass glass = new Glass(header);
        header.addOnAttachStateChangeListener(glass);
        if (header.isAttachedToWindow()) glass.onViewAttachedToWindow(header);
        HookStatus.counted(FamilyNames.HOME_TOP_BAR, COUNT_GLAZED);
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.SEE_THROUGH_HOME_TOP_BAR.get();
    }

    /** Whether [header] has the glass showing now. */
    static boolean showing(View header) {
        return header.getBackground() instanceof Frost;
    }

    /** Whether [header]'s glass has its backdrop recorded. */
    static boolean recorded(View header) {
        Drawable background = header.getBackground();
        return background instanceof Frost && ((Frost) background).glass.recorded;
    }

    /**
     * The view under [header]'s parent, [header] or one of the views wrapped round it, whose parent
     * draws the feed before it: the first parent more than twice as tall as [header]. Null when there's
     * none yet.
     */
    @Nullable
    static View host(View header) {
        View at = header;
        int tall = Math.max(1, header.getHeight());
        for (int depth = 0; depth < 6; depth++) {
            ViewParent up = at.getParent();
            if (!(up instanceof ViewGroup)) return null;
            if (((ViewGroup) up).getHeight() > 2 * tall) return at;
            at = (View) up;
        }
        return null;
    }

    /** Whether [background] fills its view with colour, so the glass under it wouldn't show. */
    static boolean fills(@Nullable Drawable background) {
        if (background == null || background instanceof Frost) return false;
        if (background instanceof ColorDrawable) return Color.alpha(((ColorDrawable) background).getColor()) != 0;
        return background.getOpacity() == PixelFormat.OPAQUE;
    }

    /** The colour the glass is tinted from: Instagram's own fill when it had a solid one, else the theme's. */
    static int base(@Nullable Drawable original, Iterable<Drawable> cleared, boolean night) {
        int found = solid(original);
        for (Drawable background : cleared) {
            if (found != 0) break;
            found = solid(background);
        }
        if (found != 0) return found;
        return night ? Color.BLACK : Color.WHITE;
    }

    private static int solid(@Nullable Drawable background) {
        if (!(background instanceof ColorDrawable)) return 0;
        int colour = ((ColorDrawable) background).getColor();
        return Color.alpha(colour) == 255 ? colour : 0;
    }

    /** One header's glass: puts it on before each frame, records what's under it, takes it off again. */
    private static final class Glass implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final View header;
        final Frost frost;
        @Nullable private ViewTreeObserver observer;
        /** Instagram's latest background on the header, put back when the glass comes off. */
        @Nullable private Drawable original;
        /** The views in the header whose fill was taken off, with that fill. */
        private final Map<View, Drawable> cleared = new HashMap<>();
        private final Set<View> leftOut = new HashSet<>();
        private final GlassTabBar.BlurTries tries = new GlassTabBar.BlurTries();
        private boolean on;
        private boolean broken;
        int base = Color.BLACK;

        @Nullable private RenderNode node;
        private int nodeBlurDp = -1;
        float nodePad;
        boolean recorded;
        private long recordedAt;
        private float lastX = Float.NaN;
        private float lastY = Float.NaN;
        private int lastWidth;
        private int lastHeight;

        Glass(View header) {
            this.header = header;
            this.frost = new Frost(this, header.getResources().getDisplayMetrics().density);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (broken || observer != null) return;
            observer = header.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stopListening();
            takeOff();
            forgetBackdrop();
        }

        private void stopListening() {
            ViewTreeObserver listening = observer;
            observer = null;
            if (listening != null && listening.isAlive()) listening.removeOnPreDrawListener(this);
            else header.getViewTreeObserver().removeOnPreDrawListener(this);
        }

        @Override
        public boolean onPreDraw() {
            try {
                if (!switchedOn()) {
                    takeOff();
                    return true;
                }
                putOn();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) record(SystemClock.uptimeMillis());
            } catch (Throwable failure) {
                // Instagram's header as it was, for as long as this header lives.
                broken = true;
                stopListening();
                try {
                    takeOff();
                } catch (Throwable ignored) {
                    // Already reporting the first failure.
                }
                HookStatus.threw(FamilyNames.HOME_TOP_BAR, HOOK, failure);
            }
            return true;
        }

        private void putOn() {
            Drawable now = header.getBackground();
            if (now != frost) {
                original = now;
                header.setBackground(frost);
            }
            on = true;
            View host = host(header);
            int wide = header.getWidth();
            // The views wrapped round the header, then the header's own, down to CLEAR_DEPTH.
            for (View at = header; host != null && at != host; ) {
                at = (View) at.getParent();
                clear(at, wide);
            }
            if (header instanceof ViewGroup) clearIn((ViewGroup) header, wide, 1);
            boolean night = (header.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            base = base(original, cleared.values(), night);
        }

        private void clearIn(ViewGroup group, int wide, int depth) {
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                View child = group.getChildAt(i);
                clear(child, wide);
                if (depth < CLEAR_DEPTH && child instanceof ViewGroup) clearIn((ViewGroup) child, wide, depth + 1);
            }
        }

        private void clear(View view, int wide) {
            Drawable background = view.getBackground();
            if (!fills(background) || view.getWidth() < wide - 1) return;
            cleared.put(view, background);
            view.setBackground(null);
        }

        private void takeOff() {
            if (!on) return;
            on = false;
            if (header.getBackground() == frost) header.setBackground(original);
            for (Map.Entry<View, Drawable> entry : cleared.entrySet()) {
                if (entry.getKey().getBackground() == null) entry.getKey().setBackground(entry.getValue());
            }
            cleared.clear();
            original = null;
        }

        private void forgetBackdrop() {
            recorded = false;
            lastX = Float.NaN;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && node != null) node.discardDisplayList();
        }

        /**
         * Records the views drawn before the header in its host's parent, round the header and the blur's
         * reach past it, into the node at a quarter of their size. It references their render nodes, so a
         * post moving under the bar moves in the glass too. Recorded again when the header moves or every
         * {@link GlassTabBar#RECORD_EVERY_MS}, for views coming and going under it.
         */
        private void record(long now) {
            View host = host(header);
            if (host == null) {
                recorded = false;
                return;
            }
            ViewGroup source = (ViewGroup) host.getParent();
            int index = source.indexOfChild(host);
            int width = header.getWidth();
            int height = header.getHeight();
            if (index <= 0 || width <= 0 || height <= 0) {
                recorded = false;
                return;
            }
            float x = 0f;
            float y = 0f;
            for (View at = header; at != source; at = (View) at.getParent()) {
                x += at.getX();
                y += at.getY();
                ViewGroup up = (ViewGroup) at.getParent();
                if (up != source) {
                    x -= up.getScrollX();
                    y -= up.getScrollY();
                }
            }
            int blurDp = Settings.GLASS_TAB_BAR_BLUR_RADIUS.get();
            boolean same = recorded && x == lastX && y == lastY && width == lastWidth && height == lastHeight
                    && blurDp == nodeBlurDp;
            if (same && now - recordedAt < GlassTabBar.RECORD_EVERY_MS) return;
            if (tries.resting(now)) return;
            try {
                float density = header.getResources().getDisplayMetrics().density;
                float scale = 1f / DOWNSAMPLE;
                float pad = GlassBlur.radiusPx(blurDp, density);
                float wide = width + 2 * pad;
                float tall = height + 2 * pad;
                int nodeWidth = Math.max(1, (int) Math.ceil(wide * scale));
                int nodeHeight = Math.max(1, (int) Math.ceil(tall * scale));
                RenderNode backdrop = node;
                if (backdrop == null) {
                    backdrop = new RenderNode("hushgram-top-bar-glass");
                    node = backdrop;
                    nodeBlurDp = -1;
                }
                if (blurDp != nodeBlurDp) {
                    float blur = GlassBlur.effectRadius(blurDp, density, scale);
                    ColorMatrix saturate = new ColorMatrix();
                    saturate.setSaturation(1.5f);
                    ColorFilter colour = new ColorMatrixColorFilter(saturate);
                    backdrop.setRenderEffect(RenderEffect.createChainEffect(
                            RenderEffect.createColorFilterEffect(colour),
                            RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)));
                    nodeBlurDp = blurDp;
                }
                backdrop.setPosition(0, 0, nodeWidth, nodeHeight);
                float left = x - pad;
                float top = y - pad;
                Canvas recording = backdrop.beginRecording(nodeWidth, nodeHeight);
                try {
                    recording.scale(scale, scale);
                    recording.translate(-left, -top);
                    recording.clipRect(left, top, left + wide, top + tall);
                    // Where nothing draws, the glass shows the bar's own colour and not the sharp feed under it.
                    recording.drawColor(base);
                    GlassTabBar.surfacePaths(source, index, leftOut);
                    for (int i = 0; i < index; i++) {
                        View sibling = source.getChildAt(i);
                        if (sibling.getWidth() <= 0 || sibling.getBottom() <= top || sibling.getTop() >= top + tall) continue;
                        GlassTabBar.drawLeavingOutSurfaces(recording, sibling, leftOut, 0);
                    }
                } finally {
                    backdrop.endRecording();
                }
                nodePad = pad;
                recorded = true;
                recordedAt = now;
                lastX = x;
                lastY = y;
                lastWidth = width;
                lastHeight = height;
                tries.worked();
            } catch (Throwable failure) {
                // A recording cut short isn't shown: the bar draws its plain tint until one works.
                recorded = false;
                if (tries.failed(now)) HookStatus.threw(FamilyNames.HOME_TOP_BAR, HOOK, failure);
            }
        }

        /** Draws the blurred backdrop over [bounds] and says whether it did. */
        boolean drawBackdrop(Canvas canvas, Rect bounds) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !recorded || node == null
                    || !canvas.isHardwareAccelerated()) return false;
            int saved = canvas.save();
            try {
                canvas.clipRect(bounds);
                canvas.translate(-nodePad, -nodePad);
                canvas.scale(DOWNSAMPLE, DOWNSAMPLE);
                canvas.drawRenderNode(node);
            } finally {
                canvas.restoreToCount(saved);
            }
            return true;
        }
    }

    /** The header's background while the glass is on: the blur, the tint over it and the hairline. */
    static final class Frost extends Drawable {
        final Glass glass;
        private final Paint paint = new Paint();
        private final int hairline;

        Frost(Glass glass, float density) {
            this.glass = glass;
            this.hairline = Math.max(1, Math.round(density / 2f));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            int base = glass.base;
            boolean blurred = glass.drawBackdrop(canvas, bounds);
            paint.setColor(GlassTabBar.tint(base, blurred, GlassTabBar.opacityScale()));
            canvas.drawRect(bounds, paint);
            paint.setColor(hairline(base));
            canvas.drawRect(bounds.left, bounds.bottom - hairline, bounds.right, bounds.bottom, paint);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The hairline's colour along the bottom of a bar tinted from [base]. */
    static int hairline(int base) {
        return GlassTabBar.isDark(base) ? HAIRLINE_DARK : HAIRLINE_LIGHT;
    }
}
