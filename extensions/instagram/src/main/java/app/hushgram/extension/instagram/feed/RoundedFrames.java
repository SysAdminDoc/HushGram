/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
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

    /**
     * Cuts a view's outline to a rounded rectangle of its size, or around what its children show. The
     * view system asks again whenever the size changes, and {@link #round} asks again when what the
     * children show has moved.
     */
    private static final class Rounded extends ViewOutlineProvider {
        final float radius;
        final boolean aroundChildren;
        /** The rectangle last handed out, in the view's own coordinates. */
        final Rect given = new Rect();

        Rounded(float radius, boolean aroundChildren) {
            this.radius = radius;
            this.aroundChildren = aroundChildren;
        }

        Rect wanted(View view) {
            return aroundChildren ? shown(view) : new Rect(0, 0, view.getWidth(), view.getHeight());
        }

        @Override
        public void getOutline(View view, Outline outline) {
            given.set(wanted(view));
            outline.setRoundRect(given, radius);
        }
    }

    /**
     * The part of [view] its shown children cover, within its own bounds, or all of it when they
     * cover none. A reel's frame fills the screen and holds the video at its own size, so a video with
     * black bars above and below is rounded at its corners rather than the screen's (#456).
     */
    static Rect shown(View view) {
        Rect whole = new Rect(0, 0, view.getWidth(), view.getHeight());
        if (!(view instanceof ViewGroup)) return whole;
        ViewGroup group = (ViewGroup) view;
        Rect covered = new Rect();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                covered.union(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            }
        }
        return covered.isEmpty() || !covered.intersect(whole) ? whole : covered;
    }

    private final Map<View, Kept> kept = new WeakHashMap<>();
    private final boolean aroundChildren;

    /** Frames rounded at their own size, as posts and stories are. */
    RoundedFrames() {
        this(false);
    }

    /** [aroundChildren]: round around what each frame's children show, as a reel's frame needs. */
    RoundedFrames(boolean aroundChildren) {
        this.aroundChildren = aroundChildren;
    }

    /**
     * Gives [view] a rounded outline of [radius] pixels and clips it to that, keeping what it had
     * first. False when it's already rounded to that radius, with its outline asked for again if what
     * its children show has moved since.
     */
    boolean round(View view, float radius) {
        Kept before = kept.get(view);
        if (before == null) {
            before = new Kept(view);
            kept.put(view, before);
        } else if (before.radius == radius) {
            ViewOutlineProvider provider = view.getOutlineProvider();
            if (provider instanceof Rounded) {
                Rounded rounded = (Rounded) provider;
                if (!rounded.given.equals(rounded.wanted(view))) view.invalidateOutline();
            }
            return false;
        }
        before.radius = radius;
        view.setOutlineProvider(new Rounded(radius, aroundChildren));
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
