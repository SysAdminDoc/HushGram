/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.ResourceIdCache;
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

    private CarouselNumber() {
    }

    /** The ids of the number on this build, or null when it has none of them, which is reported. */
    @Nullable
    static int[] resolve(Activity owner) {
        int[] resolved = new int[NAMES.length];
        boolean any = false;
        for (int i = 0; i < NAMES.length; i++) {
            resolved[i] = ids.resolve(owner.getResources(), owner.getPackageName(), NAMES[i], false);
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
        if (view.getVisibility() == View.GONE) return;
        if (!hidden.containsKey(view)) HookStatus.counted(FamilyNames.CAROUSEL_ARROWS, COUNT_HIDDEN);
        hidden.put(view, view.getVisibility());
        view.setVisibility(View.GONE);
    }

    /** Brings back every number hidden, each as it stood. Instagram's next bind has the last word on a reused row. */
    static void putBack() {
        for (Map.Entry<View, Integer> entry : hidden.entrySet()) {
            View view = entry.getKey();
            if (view != null && view.getVisibility() == View.GONE) view.setVisibility(entry.getValue());
        }
        hidden.clear();
    }

    /** Tests give the number an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, String name, int id) {
        ids.putForTests(context.getPackageName(), name, id);
    }

    static void resetForTests() {
        ids.clear();
        hidden.clear();
    }
}
