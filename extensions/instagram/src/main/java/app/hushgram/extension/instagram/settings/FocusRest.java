/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * A view that holds the focus while fingers drive the screen, so opening a page never puts the
 * cursor in the search field and raises the keyboard. Under a keyboard or a D-pad it steps aside.
 * Left focusable there, it was a stop Tab landed on with nothing to show for it, the arrows couldn't
 * leave it for the rows inside it, and Android's focus highlight grayed everything it covers, which
 * for the screen's root is the whole screen.
 */
final class FocusRest implements ViewTreeObserver.OnTouchModeChangeListener, View.OnAttachStateChangeListener {
    /** Main thread only. A rest goes when its view does. */
    private static final WeakHashMap<View, FocusRest> RESTS = new WeakHashMap<>();

    private final WeakReference<View> holder;

    private FocusRest(View holder) {
        this.holder = new WeakReference<>(holder);
    }

    /** Makes [holder] a rest for the focus in touch mode and no stop at all outside it. */
    static void on(View holder) {
        FocusRest rest = RESTS.get(holder);
        if (rest == null) {
            rest = new FocusRest(holder);
            RESTS.put(holder, rest);
            holder.setDefaultFocusHighlightEnabled(false);
            holder.addOnAttachStateChangeListener(rest);
            if (holder.isAttachedToWindow()) holder.getViewTreeObserver().addOnTouchModeChangeListener(rest);
        }
        rest.onTouchModeChanged(holder.isInTouchMode());
    }

    @Override public void onTouchModeChanged(boolean touch) {
        View view = holder.get();
        if (view == null) return;
        // Giving the focus up here hands it to the first row or button that can take it.
        if (touch) view.setFocusableInTouchMode(true);
        else view.setFocusable(false);
    }

    @Override public void onViewAttachedToWindow(View view) {
        view.getViewTreeObserver().addOnTouchModeChangeListener(this);
        onTouchModeChanged(view.isInTouchMode());
    }

    @Override public void onViewDetachedFromWindow(View view) {
        view.getViewTreeObserver().removeOnTouchModeChangeListener(this);
    }
}
