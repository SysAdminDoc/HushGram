/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Slide across the tabs on Instagram's own tab bar, with Glass tab bar off (#201). The slide itself is
 * {@link GlassTabSlide}'s, touch for touch: a tap and a long press stay Instagram's, a finger gone sideways
 * past the slop slides, and lifting it opens the tab under it. What the glass draws as its moving
 * highlight is a light rounded box here, drawn in the bar's overlay over the tab under the finger, and
 * gone once the finger lifts. Instagram's own selected tab only changes when a tab opens.
 */
final class PlainTabSlide implements GlassTabSlide.Host {
    static final int INSET_DP = 4;
    static final int CORNER_DP = 12;
    /** White at about 18% over a dark bar, black at about 10% over a light one. */
    static final int ON_DARK = 0x2effffff;
    static final int ON_LIGHT = 0x1a000000;

    /**
     * Each bar's slide, held weakly both ways: the window's touch watcher holds the slide, and the slide
     * holds the bar, so a strong value here would keep every bar Instagram dropped. Main thread only.
     */
    private static final WeakHashMap<View, WeakReference<PlainTabSlide>> slides = new WeakHashMap<>();

    private final ViewGroup bar;
    final GlassTabSlide slide;
    final GradientDrawable highlight = new GradientDrawable();
    private boolean shown;

    PlainTabSlide(ViewGroup bar) {
        this.bar = bar;
        this.slide = new GlassTabSlide(bar, this, () -> !HushgramPause.isPaused());
        highlight.setCornerRadius(CORNER_DP * bar.getResources().getDisplayMetrics().density);
    }

    /**
     * Called as an activity comes to the front with Glass tab bar off. With Slide across the tabs on, the
     * activity's tab bar, when it has one, gets its slide, and its window's touches go through it.
     */
    static void apply(Activity activity) {
        try {
            if (!Settings.GLASS_TAB_BAR_SLIDE.get()) return;
            View found = GlassTabBar.find(activity.getWindow().getDecorView(), GlassTabBar.TAB_BAR);
            // Glass turned off without the restart it asks for: the bar keeps its pill and the pill's slide.
            if (!(found instanceof ViewGroup) || GlassTabBar.wearsGlass(found)) return;
            WeakReference<PlainTabSlide> held = slides.get(found);
            PlainTabSlide known = held == null ? null : held.get();
            if (known == null) {
                known = new PlainTabSlide((ViewGroup) found);
                slides.put(found, new WeakReference<>(known));
                HookStatus.invoked(FamilyNames.GLASS_TAB_BAR);
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, GlassTabSlide.COUNT_WATCHING);
                Logger.printDebug(() -> "Glass tab bar: slides on Instagram's own tab bar");
            }
            GlassTabSlide.install(activity.getWindow(), known.slide);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "tab slide", failure);
        }
    }

    /** The slide watching [bar], or null when it has none. For tests. */
    @Nullable
    static PlainTabSlide of(View bar) {
        WeakReference<PlainTabSlide> held = slides.get(bar);
        return held == null ? null : held.get();
    }

    /** Whether the highlight is drawn over the bar now. */
    boolean shown() {
        return shown;
    }

    /** The tabs' row, without the bar's padding (the room Instagram keeps under it for the navigation bar). */
    @Override public boolean onPill(float x, float y) {
        return x >= bar.getPaddingLeft() && x < bar.getWidth() - bar.getPaddingRight()
                && y >= bar.getPaddingTop() && y < bar.getHeight() - bar.getPaddingBottom();
    }

    @Override public void follow(float x) {
        View tab = Float.isNaN(x) ? null : slide.tabAt(x);
        if (tab == null) {
            if (shown) bar.getOverlay().remove(highlight);
            shown = false;
            return;
        }
        int inset = Math.round(INSET_DP * bar.getResources().getDisplayMetrics().density);
        int width = tab.getWidth();
        int left = Math.round(x - width / 2f);
        int from = bar.getPaddingLeft();
        int to = bar.getWidth() - bar.getPaddingRight() - width;
        left = Math.max(from, Math.min(to, left));
        highlight.setColor(darkBar() ? ON_DARK : ON_LIGHT);
        highlight.setBounds(left + inset, tab.getTop() + inset, left + width - inset, tab.getBottom() - inset);
        if (!shown) bar.getOverlay().add(highlight);
        shown = true;
        bar.invalidate();
    }

    /** Whether the bar is dark: by its own colour when it has a plain one, else by the theme. */
    private boolean darkBar() {
        Drawable background = bar.getBackground();
        if (background instanceof ColorDrawable) {
            int color = ((ColorDrawable) background).getColor();
            if (Color.alpha(color) > 0x80) {
                return 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color) < 128f;
            }
        }
        int night = bar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }
}
