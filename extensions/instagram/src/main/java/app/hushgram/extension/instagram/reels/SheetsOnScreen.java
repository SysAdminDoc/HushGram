/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Part of Full screen Reels and Home (#626): while HushGram hides the bars, a bottom sheet whose content
 * Instagram keeps past the window's foot is padded back up to it, so a comment box stays on the screen.
 *
 * <p>Instagram lays a sheet (comments, share, a post's menu) out the window's whole height and slides it
 * up by a translation. The sheet's content, a comments list over the comment box for one, is kept to the
 * part Instagram works out is left showing, again at each frame of a drag: Instagram 454 lays it out as
 * tall as its holder and pads its bottom by the part below the foot, and content shorter than its holder
 * is sized to what shows instead. On a Galaxy S21 with Android 15 and the bars hidden, what shows came
 * out about 84 pixels more than the window has, so the comment box sat under the screen's foot. Where
 * the content's padded area ends is measured in the window, translations included, and the overflow goes
 * on as more bottom padding: the content keeps its height, the list over the box gives up that much, and
 * the box ends at the foot. Content that fits is left alone, so is content as tall as its holder with no
 * padding (Instagram didn't keep that one to what shows), and so is one running far past the foot, as a
 * sheet on its way in or out can.
 *
 * <p>While the keyboard shows the padding stays as it is, since Instagram shortens the content for the
 * keys then. Padding Instagram gives the content itself is the content's own from then on. When the bars
 * come back each content gets its own padding back. Anything thrown leaves the sheet as Instagram has it.
 */
final class SheetsOnScreen implements ViewTreeObserver.OnGlobalLayoutListener {
    /** Where Instagram puts a bottom sheet over the screens. It stays once added, with a child while a sheet is up. */
    static final String HOLDER = "layout_container_bottom_sheet";
    /** What holds a sheet's content under its handle. */
    static final String CONTENT = "bottom_sheet_container_view";
    static final String COUNT_LIFTED = "sheet's content kept above the window's foot";
    /** An overflow past this share of the window's height is a sheet on its way in or out, not one sized wrong. */
    static final int MAX_SHARE = 4;

    /** The bottom padding a content view had before HushGram added {@code by}. */
    private static final class Lifted {
        final int padding;
        final int by;

        Lifted(int padding, int by) {
            this.padding = padding;
            this.by = by;
        }

        /** Whether [view] still has the padding HushGram gave it. */
        boolean kept(View view) {
            return view.getPaddingBottom() == padding + by;
        }
    }

    /** Held weakly so a closed sheet's content can go. Main thread only. */
    private final WeakHashMap<View, Lifted> lifted = new WeakHashMap<>();
    private WeakReference<View> watched = new WeakReference<>(null);
    private WeakReference<View> holder = new WeakReference<>(null);
    private boolean dirty = true;

    @Override public void onGlobalLayout() {
        // The holder only ever comes with a layout.
        dirty = true;
    }

    /**
     * Each look at [decor]'s screen: with the bars hidden ([barsHidden]), pads the content of a sheet
     * that runs past the window's foot up to it, unless the keyboard shows ([keyboardShows]); otherwise
     * gives each content view its own padding back.
     */
    void follow(View decor, boolean barsHidden, boolean keyboardShows) {
        try {
            if (!barsHidden) {
                putBack();
                return;
            }
            watch(decor);
            if (keyboardShows) return;
            View found = holder(decor);
            if (!(found instanceof ViewGroup) || ((ViewGroup) found).getChildCount() == 0) return;
            int contentId = FullScreenBars.id(decor.getContext(), CONTENT);
            View content = contentId == 0 ? null : found.findViewById(contentId);
            if (!(content instanceof ViewGroup) || !content.isShown()) return;
            ViewGroup group = (ViewGroup) content;
            for (int i = 0; i < group.getChildCount(); i++) lift(group.getChildAt(i), group.getHeight(), decor);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "sheet on screen", failure);
        }
    }

    /** Gives each content view HushGram padded its own padding back, and stops listening. */
    void putBack() {
        try {
            unwatch();
            for (Map.Entry<View, Lifted> entry : lifted.entrySet()) {
                View view = entry.getKey();
                if (view != null && entry.getValue().kept(view)) padBottom(view, entry.getValue().padding);
            }
            lifted.clear();
            holder = new WeakReference<>(null);
            dirty = true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "sheet on screen put back", failure);
        }
    }

    /** How many content views are padded now, for tests. */
    int liftedCount() {
        return lifted.size();
    }

    /** How far [view] ends past the foot of [decor]'s window, translations included; 0 or less when it fits. */
    static int overflow(View view, View decor) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1] + view.getHeight() - decor.getHeight();
    }

    @Nullable
    private View holder(View decor) {
        View found = holder.get();
        if (found != null && found.getRootView() == decor) return found;
        if (!dirty) return null;
        dirty = false;
        int id = FullScreenBars.id(decor.getContext(), HOLDER);
        found = id == 0 ? null : decor.findViewById(id);
        holder = new WeakReference<>(found);
        return found;
    }

    private void watch(View decor) {
        if (watched.get() == decor) return;
        unwatch();
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.addOnGlobalLayoutListener(this);
        watched = new WeakReference<>(decor);
        dirty = true;
    }

    private void unwatch() {
        View decor = watched.get();
        watched = new WeakReference<>(null);
        if (decor == null) return;
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
    }

    /**
     * Pads [view] up to the window's foot when Instagram keeps it to what shows of the [room] it sits in:
     * sized shorter than the room, or as tall as the room with Instagram's own bottom padding standing in
     * for the part below the foot.
     */
    private void lift(View view, int room, View decor) {
        Lifted was = lifted.get(view);
        if (was != null && !was.kept(view)) {
            // Instagram padded the content itself since (it does at each frame of a drag): that's the
            // content's own from now on.
            lifted.remove(view);
            was = null;
        }
        int padding = was == null ? view.getPaddingBottom() : was.padding;
        boolean sized = view.getHeight() < room;
        // Content as tall as the room ends where its own padding starts.
        int over = overflow(view, decor) - (sized ? 0 : padding);
        if (view.getVisibility() != View.VISIBLE || over > decor.getHeight() / MAX_SHARE) return;
        // Content filling the whole room with no padding isn't kept to what shows, and runs past the
        // foot on purpose.
        int by = sized || padding > 0 ? Math.max(0, over) : 0;
        if (was == null ? by == 0 : was.by == by) return;
        padBottom(view, padding + by);
        if (by == 0) {
            lifted.remove(view);
            return;
        }
        lifted.put(view, new Lifted(padding, by));
        HookStatus.invoked(FamilyNames.FULL_SCREEN_BARS);
        HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_LIFTED);
        Logger.printDebug(() -> "Full screen bars: a sheet's content ran " + by + " px past the window's foot, padded it up");
    }

    private static void padBottom(View view, int padding) {
        view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(), view.getPaddingEnd(), padding);
    }
}
