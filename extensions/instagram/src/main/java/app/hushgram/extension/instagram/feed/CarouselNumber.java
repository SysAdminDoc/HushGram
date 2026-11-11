/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keeps Instagram's own page number ("3/5") off a carousel post while Hide page number is on (#581).
 *
 * <p>{@link CarouselArrows} finds it on each layout, in the look through the window that finds the
 * carousels. Instagram showing it again asks for a layout, so it's gone again before that frame draws.
 * Each one hidden remembers how it stood and goes back when the switch goes off.
 */
final class CarouselNumber {
    /** The number's holder, its text where a screen has no holder, and the rounded holder some carousels use. */
    static final String[] NAMES = {
            "carousel_index_indicator_component",
            "carousel_index_indicator_text_view",
            "carousel_index_pill_container",
    };

    static final String COUNT_HIDDEN = "carousel page numbers hidden";

    private static final ResourceIdCache ids = new ResourceIdCache();
    /** Each number hidden and how it stood before. Main thread only. */
    private static final Map<View, Integer> hidden = new WeakHashMap<>();
    /** Each number whose empty pill is hidden, with that pill. Main thread only. */
    private static final Map<View, Pill> pills = new WeakHashMap<>();
    /** A pill is at most this fraction of what's around it, so a wrapper that happens to hold only the number stays. */
    private static final int PILL_SHARE = 3;

    private CarouselNumber() {
    }

    /** The ids of the number on this build, or null when it has none of them, which is reported. */
    @Nullable
    static int[] resolve(Activity owner) {
        int[] resolved = new int[NAMES.length];
        boolean any = false;
        for (int i = 0; i < NAMES.length; i++) {
            resolved[i] = ids.resolve(owner.getResources(), Utils.resourcePackage(owner), NAMES[i], false);
            any |= resolved[i] != 0;
        }
        if (!any) {
            HookStatus.missingViewId(FamilyNames.CAROUSEL_ARROWS, NAMES[0]);
            return null;
        }
        HookStatus.recoveredViewId(FamilyNames.CAROUSEL_ARROWS, NAMES[0]);
        return resolved;
    }

    static boolean is(View view, int[] numbers) {
        int id = view.getId();
        if (id == View.NO_ID) return false;
        for (int number : numbers) {
            if (number == id) return true;
        }
        return false;
    }

    static void hide(View view) {
        hidePill(view);
        if (view.getVisibility() == View.GONE) return;
        if (!hidden.containsKey(view)) HookStatus.counted(FamilyNames.CAROUSEL_ARROWS, COUNT_HIDDEN);
        hidden.put(view, view.getVisibility());
        view.setVisibility(View.GONE);
    }

    /**
     * On the feed the dark pill behind the number has no id. It's the small host around the text, and it would stay
     * up empty. Litho hands that host to other rows once the number comes out of it, so it goes back right then.
     */
    private static void hidePill(View number) {
        if (pills.containsKey(number) || !(number.getParent() instanceof ViewGroup)) return;
        ViewGroup holder = (ViewGroup) number.getParent();
        if (holder.getId() != View.NO_ID || holder.getChildCount() != 1 || holder.getVisibility() != View.VISIBLE) return;
        if (!(holder.getParent() instanceof View)) return;
        int width = holder.getWidth();
        if (width <= 0 || width * PILL_SHARE > ((View) holder.getParent()).getWidth()) return;
        Pill pill = new Pill(holder);
        pills.put(number, pill);
        number.addOnAttachStateChangeListener(pill);
        holder.setVisibility(View.INVISIBLE);
    }

    /** Brings back every number hidden, each as it stood. Instagram's next bind has the last word on a reused row. */
    static void putBack() {
        for (Map.Entry<View, Integer> entry : hidden.entrySet()) {
            View view = entry.getKey();
            if (view != null && view.getVisibility() == View.GONE) view.setVisibility(entry.getValue());
        }
        hidden.clear();
        for (Map.Entry<View, Pill> entry : pills.entrySet()) {
            View number = entry.getKey();
            if (number != null) number.removeOnAttachStateChangeListener(entry.getValue());
            entry.getValue().show();
        }
        pills.clear();
    }

    /** A pill put away until its number leaves it. */
    private static final class Pill implements View.OnAttachStateChangeListener {
        private final View holder;

        Pill(View holder) {
            this.holder = holder;
        }

        void show() {
            if (holder.getVisibility() == View.INVISIBLE) holder.setVisibility(View.VISIBLE);
        }

        @Override
        public void onViewAttachedToWindow(View number) {
        }

        @Override
        public void onViewDetachedFromWindow(View number) {
            try {
                number.removeOnAttachStateChangeListener(this);
                pills.remove(number);
                show();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CAROUSEL_ARROWS, "page number pill", failure);
            }
        }
    }

    /** Tests give the number an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, String name, int id) {
        ids.putForTests(Utils.resourcePackage(context), name, id);
    }

    static void resetForTests() {
        ids.clear();
        hidden.clear();
        pills.clear();
    }
}
