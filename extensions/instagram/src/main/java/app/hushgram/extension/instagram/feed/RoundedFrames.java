/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The frames one rounding switch has cut, with the outline provider and clipping flag each had, so
 * the switch going off can put every one back as it was. Frames are held weakly. Main thread only.
 */
final class RoundedFrames {
    /** What a frame was before it was rounded, and the radius it has now. */
    private static final class Kept {
        final ViewOutlineProvider provider;
        final boolean clipped;
        float radius;

        Kept(View view) {
            provider = view.getOutlineProvider();
            clipped = view.getClipToOutline();
        }
    }

    /** Cuts a view's outline to a rounded rectangle of its size. The view system asks again whenever the size changes. */
    private static final class Rounded extends ViewOutlineProvider {
        final float radius;

        Rounded(float radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }

    private final Map<View, Kept> kept = new WeakHashMap<>();

    /**
     * Gives [view] a rounded outline of [radius] pixels and clips it to that, keeping what it had
     * first. False, with nothing done, when it's already rounded to that radius.
     */
    boolean round(View view, float radius) {
        Kept before = kept.get(view);
        if (before == null) {
            before = new Kept(view);
            kept.put(view, before);
        } else if (before.radius == radius) {
            return false;
        }
        before.radius = radius;
        view.setOutlineProvider(new Rounded(radius));
        view.setClipToOutline(true);
        return true;
    }

    /** Gives every frame rounded so far its outline provider and clipping back, and forgets them. */
    void putBack() {
        for (Map.Entry<View, Kept> entry : new ArrayList<>(kept.entrySet())) {
            View view = entry.getKey();
            if (view == null) continue;
            view.setOutlineProvider(entry.getValue().provider);
            view.setClipToOutline(entry.getValue().clipped);
        }
        kept.clear();
    }

    boolean isEmpty() {
        return kept.isEmpty();
    }

    int size() {
        return kept.size();
    }

    /** Forgets every frame without touching it. Tests only. */
    void clear() {
        kept.clear();
    }
}
