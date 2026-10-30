/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * "Hide the tab bar as you scroll" on Instagram's own bar, with Glass tab bar off (#343). The hiding is
 * {@link GlassScrollHide}'s, touch for touch, the wait on Reels left out, so Reels keep their bar as before.
 *
 * <p>Instagram stops each main screen above its bar, with a bottom margin as tall as the bar on the
 * screens' holder ({@link GlassTabBar#CONTENT}), on its screens, and on Home's pager beside them where the
 * tabs swipe. A bar slid off alone left an empty strip that tall. While the bar is away each of those
 * margins is 0, so the screens run to the bottom, and the bar's shadow line is hidden. Once the bar has slid
 * all the way back, only the margins this set to 0 and still 0 get the bar's height again, and only while
 * the bar shows: a bar Instagram hid itself (a reel opened from a post) keeps the margins Instagram gave.
 */
final class PlainScrollHide implements GlassScrollHide.Room {
    static final String COUNT_ROOM = "scroll gave the bar's room";

    /** Each bar's hider, held weakly both ways, as {@link PlainTabSlide} holds its slides. Main thread only. */
    private static final WeakHashMap<View, WeakReference<PlainScrollHide>> hides = new WeakHashMap<>();

    private final View bar;
    final GlassScrollHide hide;
    /** The views whose margin this set to 0, and the margin each had. */
    private final WeakHashMap<View, Integer> given = new WeakHashMap<>();
    @Nullable private WeakReference<View> shadowHidden;

    PlainScrollHide(View bar) {
        this.bar = bar;
        this.hide = new GlassScrollHide(bar, () -> !HushgramPause.isPaused(), true, 0L, null, this);
    }

    /**
     * Called as an activity comes to the front with Glass tab bar off. With the switch on, the activity's tab
     * bar, when it has one, gets its hider, and its window's touches go through it.
     */
    static void apply(Activity activity) {
        try {
            if (!Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.get()) return;
            View found = GlassTabBar.find(activity.getWindow().getDecorView(), GlassTabBar.TAB_BAR);
            // Glass turned off without the restart it asks for: the pill keeps the glass's own hiding.
            if (!(found instanceof ViewGroup) || GlassTabBar.wearsGlass(found)) return;
            WeakReference<PlainScrollHide> held = hides.get(found);
            PlainScrollHide known = held == null ? null : held.get();
            if (known == null) {
                known = new PlainScrollHide(found);
                hides.put(found, new WeakReference<>(known));
                HookStatus.invoked(FamilyNames.GLASS_TAB_BAR);
                HookStatus.counted(FamilyNames.GLASS_TAB_BAR, "scroll hide watching");
                Logger.printDebug(() -> "Glass tab bar: scroll hides Instagram's own tab bar");
            }
            GlassScrollHide.install(activity.getWindow(), known.hide);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "scroll hide", failure);
        }
    }

    /** Brings back every bar a scroll has hidden: the screen is going to the background. */
    static void restoreAll() {
        try {
            for (WeakReference<PlainScrollHide> held : new ArrayList<>(hides.values())) {
                PlainScrollHide known = held == null ? null : held.get();
                if (known != null) known.hide.restoreNow();
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GLASS_TAB_BAR, "restore", failure);
        }
    }

    /** The hider on [bar], or null when it has none. For tests. */
    @Nullable
    static PlainScrollHide of(View bar) {
        WeakReference<PlainScrollHide> held = hides.get(bar);
        return held == null ? null : held.get();
    }

    @Override public void away(boolean away) {
        if (away) takeRoom();
        else giveBack();
    }

    private void takeRoom() {
        int height = barHeight();
        if (height <= 0 || !(bar.getParent() instanceof View)) return;
        View holder = (View) bar.getParent();
        View content = GlassTabBar.find(holder, GlassTabBar.CONTENT);
        if (content == null) return;
        int changed = zero(content, height);
        if (content instanceof ViewGroup) {
            ViewGroup screens = (ViewGroup) content;
            for (int i = 0; i < screens.getChildCount(); i++) changed += zero(screens.getChildAt(i), height);
        }
        int pager = GlassTabBar.id(bar.getContext(), GlassTabBar.SWIPE_PAGER);
        if (pager != 0 && content.getParent() instanceof ViewGroup) {
            ViewGroup beside = (ViewGroup) content.getParent();
            for (int i = 0; i < beside.getChildCount(); i++) {
                View sibling = beside.getChildAt(i);
                if (sibling.getId() == pager) changed += zero(sibling, height);
            }
        }
        View shadow = GlassTabBar.find(holder, GlassTabBar.TAB_BAR_SHADOW);
        if (shadow != null && shadow.getVisibility() == View.VISIBLE) {
            shadow.setVisibility(View.INVISIBLE);
            shadowHidden = new WeakReference<>(shadow);
        }
        if (changed > 0) {
            HookStatus.counted(FamilyNames.GLASS_TAB_BAR, COUNT_ROOM);
        } else {
            Logger.printDebug(() -> "Glass tab bar: no screen stops at Instagram's bar (height " + height + ")");
        }
    }

    private void giveBack() {
        boolean shows = bar.getVisibility() == View.VISIBLE;
        List<View> views = new ArrayList<>(given.keySet());
        for (View view : views) {
            Integer had = given.get(view);
            if (!shows || had == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (margins.bottomMargin != 0) continue;
            margins.bottomMargin = had;
            view.setLayoutParams(margins);
        }
        given.clear();
        View shadow = shadowHidden == null ? null : shadowHidden.get();
        shadowHidden = null;
        // The shadow is put back whatever the bar does: Instagram hides it its own way (GONE), and nothing else would.
        if (shadow != null && shadow.getVisibility() == View.INVISIBLE) shadow.setVisibility(View.VISIBLE);
    }

    /** [view]'s bottom margin to 0 when it's the bar's [height], remembered. Returns 1 when it changed. */
    private int zero(View view, int height) {
        if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return 0;
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (margins.bottomMargin != height) return 0;
        given.put(view, height);
        margins.bottomMargin = 0;
        view.setLayoutParams(margins);
        return 1;
    }

    /** The bar's height as Instagram lays it out: its fixed height when it has one, else as measured. */
    private int barHeight() {
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params != null && params.height > 0) return params.height;
        return bar.getHeight();
    }
}
