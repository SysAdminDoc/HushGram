/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.graphics.Rect;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Whole reel in the reel menu (#845): Show the whole reel in the menu a reel's three dots open fits
 * that one reel inside the screen, without turning Show the whole reel on for every reel.
 *
 * <p>Instagram plays a reel in a SimpleVideoLayout the size of the viewer. A reel it crops gets a
 * TextureView laid out at the video's own shape, wider (or taller) than that layout, which cuts it:
 * on a 1080x2316 S22 a 9:16 reel's texture is 1261 wide in a layout 1080 wide. The row scales that
 * texture down around the layout's middle until the whole video fits, with bars where the shapes
 * differ, and nothing of Instagram's layout changes.
 *
 * <p>It lasts for that reel while it's on screen. Swipe to the next one and the texture gets its own
 * scale back, so the reel is cropped again when it comes back, and Fill the screen, the same row's
 * label while the reel shows whole, crops it right away. A reel that isn't cut gets no row.
 */
public final class ReelWhole {
    /** What a reel shown whole from the menu is counted under. */
    static final String COUNT_WHOLE = "reels shown whole from the menu";

    /** At this scale or above a reel counts as whole already, and its menu gets no row. */
    static final float NO_CUT = 0.99f;

    /** What the menu offers for the reel on screen. */
    public enum Offer {
        /** Show the whole reel: the reel on screen is cut. */
        WHOLE,
        /** Fill the screen: the reel on screen was shown whole from the menu. */
        CROP,
        /** Nothing: no reel on screen, or one that isn't cut. */
        NONE
    }

    /** What a texture shown whole had before, to give back. */
    static final class Kept {
        final float scaleX;
        final float scaleY;
        final float translationX;
        final float translationY;
        /** Where the reel's layout sat on screen, top edge, when it was shown whole. */
        final int top;
        ViewTreeObserver.OnScrollChangedListener watcher;
        View.OnAttachStateChangeListener attach;

        Kept(View texture, int top) {
            scaleX = texture.getScaleX();
            scaleY = texture.getScaleY();
            translationX = texture.getTranslationX();
            translationY = texture.getTranslationY();
            this.top = top;
        }
    }

    /** Every texture shown whole, by the texture. Weak, so a page Instagram lets go of isn't kept. */
    static final Map<TextureView, Kept> WHOLE = Collections.synchronizedMap(new WeakHashMap<>());

    private ReelWhole() {
    }

    /** Whether Whole reel in the reel menu is on. Paused answers no. Never throws. */
    public static boolean inMenu() {
        try {
            return Utils.settingsReady() && Settings.WHOLE_REEL_IN_REEL_MENU.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "whole reel switch", failure);
            return false;
        }
    }

    /** What the menu offers for the reel on screen in [activity]. Never throws. */
    public static Offer offer(@Nullable Activity activity) {
        try {
            if (!inMenu()) return Offer.NONE;
            TextureView texture = onScreen(activity);
            if (texture == null) return Offer.NONE;
            if (WHOLE.containsKey(texture)) return Offer.CROP;
            return fitScale(texture) < NO_CUT ? Offer.WHOLE : Offer.NONE;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "whole reel offer", failure);
            return Offer.NONE;
        }
    }

    /**
     * The row's tap: the reel on screen in [activity] shows whole, or, shown whole already, is cropped
     * again. Answers what it did, {@link Offer#NONE} when there was nothing to do. Never throws.
     */
    public static Offer toggle(@Nullable Activity activity) {
        try {
            if (!inMenu()) return Offer.NONE;
            TextureView texture = onScreen(activity);
            if (texture == null) return Offer.NONE;
            if (WHOLE.containsKey(texture)) {
                restore(texture);
                return Offer.CROP;
            }
            return showWhole(texture) ? Offer.WHOLE : Offer.NONE;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "whole reel tap", failure);
            return Offer.NONE;
        }
    }

    /**
     * The scale that fits [texture] whole inside the layout holding it: the smaller of the layout's
     * width over the texture's and its height over the texture's. 1 when either has no size.
     */
    static float fitScale(TextureView texture) {
        View layout = (View) texture.getParent();
        int width = texture.getWidth();
        int height = texture.getHeight();
        if (layout == null || width <= 0 || height <= 0 || layout.getWidth() <= 0 || layout.getHeight() <= 0) return 1f;
        return Math.min(layout.getWidth() / (float) width, layout.getHeight() / (float) height);
    }

    /**
     * Scales [texture] to {@link #fitScale} with its middle on the layout's middle, keeps what it had,
     * and watches for the reel leaving the screen. False for a texture that isn't cut.
     */
    static boolean showWhole(TextureView texture) {
        float scale = fitScale(texture);
        if (!(scale < NO_CUT) || !(scale > 0f)) return false;
        View layout = (View) texture.getParent();
        Kept kept = new Kept(texture, topOf(layout));
        // Scaling goes around the pivot, so the texture's middle lands where the pivot plus the
        // scaled distance to the middle puts it; the translation moves that onto the layout's middle.
        float pivotX = texture.getPivotX();
        float pivotY = texture.getPivotY();
        float middleX = texture.getLeft() + pivotX + scale * (texture.getWidth() / 2f - pivotX);
        float middleY = texture.getTop() + pivotY + scale * (texture.getHeight() / 2f - pivotY);
        texture.setScaleX(scale);
        texture.setScaleY(scale);
        texture.setTranslationX(layout.getWidth() / 2f - middleX);
        texture.setTranslationY(layout.getHeight() / 2f - middleY);
        watch(texture, kept);
        WHOLE.put(texture, kept);
        HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_WHOLE);
        return true;
    }

    /** Gives [texture] back what it had and stops watching it. */
    static void restore(TextureView texture) {
        Kept kept = WHOLE.remove(texture);
        if (kept == null) return;
        texture.setScaleX(kept.scaleX);
        texture.setScaleY(kept.scaleY);
        texture.setTranslationX(kept.translationX);
        texture.setTranslationY(kept.translationY);
        if (kept.watcher != null) {
            ViewTreeObserver observer = texture.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnScrollChangedListener(kept.watcher);
        }
        if (kept.attach != null) texture.removeOnAttachStateChangeListener(kept.attach);
    }

    /**
     * A swipe moves the reel's page, which Instagram's pager reports as a scroll: once the layout's
     * top has moved half its height or more from where it was, or the page leaves the window, the
     * texture is cropped again.
     */
    private static void watch(TextureView texture, Kept kept) {
        kept.watcher = () -> recheck(texture);
        kept.attach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
            }

            @Override public void onViewDetachedFromWindow(View view) {
                try {
                    restore(texture);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "whole reel detach", failure);
                }
            }
        };
        texture.getViewTreeObserver().addOnScrollChangedListener(kept.watcher);
        texture.addOnAttachStateChangeListener(kept.attach);
    }

    /** What the watcher runs on a scroll: crops [texture] again once its reel has moved away. Never throws. */
    static void recheck(TextureView texture) {
        try {
            Kept kept = WHOLE.get(texture);
            if (kept == null) return;
            View layout = (View) texture.getParent();
            if (layout == null || !texture.isAttachedToWindow()
                    || Math.abs(topOf(layout) - kept.top) * 2 >= layout.getHeight()) {
                restore(texture);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "whole reel watch", failure);
        }
    }

    private static int topOf(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[1];
    }

    /**
     * The texture of the reel on screen in [activity]: the TextureView in a SimpleVideoLayout, shown,
     * whose layout covers the middle of the window. Null when there's none.
     */
    @Nullable
    static TextureView onScreen(@Nullable Activity activity) {
        if (activity == null || activity.getWindow() == null) return null;
        View root = activity.getWindow().getDecorView();
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        int middleX = origin[0] + root.getWidth() / 2;
        int middleY = origin[1] + root.getHeight() / 2;
        List<View> layouts = new ArrayList<>();
        videoLayouts(root, layouts);
        Rect shown = new Rect();
        for (View layout : layouts) {
            TextureView texture = textureIn((ViewGroup) layout);
            if (texture == null || !texture.isShown() || !layout.getGlobalVisibleRect(shown)) continue;
            if (shown.contains(middleX, middleY)) return texture;
        }
        return null;
    }

    /** Every SimpleVideoLayout under [view], by its class's name, which Instagram keeps. */
    private static void videoLayouts(View view, List<View> found) {
        if (!(view instanceof ViewGroup) || view.getVisibility() != View.VISIBLE) return;
        if (view.getClass().getName().endsWith("SimpleVideoLayout")) {
            found.add(view);
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) videoLayouts(group.getChildAt(i), found);
    }

    @Nullable
    private static TextureView textureIn(ViewGroup layout) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof TextureView) return (TextureView) child;
        }
        return null;
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        for (TextureView texture : new ArrayList<>(WHOLE.keySet())) restore(texture);
        WHOLE.clear();
    }
}
