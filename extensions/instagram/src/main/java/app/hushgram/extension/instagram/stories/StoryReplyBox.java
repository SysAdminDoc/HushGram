/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the reply box on stories" patch (#486): keeps the Send message box off the
 * bottom of other people's stories.
 *
 * <p>On 453 the box is {@link #BOX}, in the story's footer ({@link #FOOTER}) beside the like and
 * share buttons. Older layouts inflate it from a stub as {@link #OLD_BOX}. The patch calls
 * {@link #resumed} right after the story viewer, ReelViewerFragment, hands its onResume on to its
 * superclass. While the switch is on, the viewer's window gets a pre-draw listener that sets each
 * box it finds INVISIBLE before the frame draws. An invisible box keeps its place, so the buttons
 * beside it don't move, and it takes no taps. A box holding the focus, where typed text goes, shows
 * again until it lets go, so a reply Instagram opens some other way is never typed blind.
 *
 * <p>The listener looks for boxes again only after a layout pass, which is when Instagram binds a
 * new story into the footer. It never cancels a frame. With the switch off or HushGram paused it
 * puts back each box it hid that is still invisible, and the viewer's next resume lets it go.
 */
public final class StoryReplyBox {
    /** The story's footer on 453, and the Send message box in it. */
    static final String FOOTER = "reel_item_toolbar_footer_container";
    static final String BOX = "message_composer_container";
    /** The box as older layouts inflate it, anywhere in the viewer. */
    static final String OLD_BOX = "reel_viewer_message_composer_container";

    static final String HOOK = "story reply box";

    private static final ResourceIdCache IDS = new ResourceIdCache();

    /** Where the viewer's view comes from. Tests hand in their own. */
    interface Reader {
        /** The story viewer [viewer]'s own view, or null before it has one. */
        @Nullable
        View view(Object viewer);
    }

    static final Reader PATCHED = new Reader() {
        @Override
        public View view(Object viewer) {
            return StoryReplyBoxReader.view(viewer);
        }
    };

    private static Reader reader = PATCHED;

    /** The keeper of the viewer last resumed with the switch on. Main thread only, where lifecycles run. */
    @Nullable
    private static Keeper current;

    private static volatile boolean logged;

    private StoryReplyBox() {
    }

    /**
     * Injected right after the story viewer's onResume hands on to its superclass's, with the viewer.
     * Lets go of the last viewer's keeper when the viewer, its view or the switch changed, putting
     * back what it hid, and while the switch is on makes sure this viewer has one. Never throws.
     */
    public static void resumed(Object viewer) {
        try {
            HookStatus.invoked(FamilyNames.STORY_REPLY_BOX);
            boolean hiding = switchedOn();
            Keeper keeper = current;
            if (keeper == null && !hiding) return;
            View view = hiding && viewer != null ? reader.view(viewer) : null;
            if (keeper != null && !keeper.holds(viewer, view)) {
                current = null;
                keeper.release();
                keeper = null;
            }
            if (view == null) return;
            if (keeper == null) {
                keeper = new Keeper(viewer, view);
                current = keeper;
                view.addOnAttachStateChangeListener(keeper);
                if (!logged) {
                    logged = true;
                    Logger.printDebug(() -> "Hide the reply box on stories: watching the story viewer's reply box");
                }
            }
            if (view.isAttachedToWindow()) keeper.listen(view);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_REPLY_BOX, HOOK, failure);
        }
    }

    /** Whether the reply box is kept off stories. Reads off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_STORY_REPLY_BOX.get();
    }

    /**
     * Keeps one viewer's reply boxes invisible while its view is on screen. It listens to the
     * window only while the view is attached, since an observer taken from a detached view is a
     * stand-in that's thrown away on attach, and it stops on detach.
     */
    static final class Keeper implements ViewTreeObserver.OnPreDrawListener,
            ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        private final WeakReference<Object> viewer;
        private final WeakReference<View> root;
        /** The boxes found at the last look. Instagram drops a story's views as it pages, so weakly. */
        private final List<WeakReference<View>> boxes = new ArrayList<>();
        /** The boxes this keeper made invisible and hasn't put back. */
        private final Set<View> hidden = Collections.newSetFromMap(new WeakHashMap<>());
        @Nullable
        private ViewTreeObserver observer;
        private boolean stale = true;

        Keeper(Object viewer, View root) {
            this.viewer = new WeakReference<>(viewer);
            this.root = new WeakReference<>(root);
        }

        boolean holds(@Nullable Object of, @Nullable View view) {
            return view != null && viewer.get() == of && root.get() == view;
        }

        void listen(View view) {
            if (observer != null) return;
            ViewTreeObserver tree = view.getViewTreeObserver();
            tree.addOnGlobalLayoutListener(this);
            tree.addOnPreDrawListener(this);
            observer = tree;
            stale = true;
        }

        void stop() {
            ViewTreeObserver tree = observer;
            observer = null;
            if (tree != null && tree.isAlive()) {
                tree.removeOnPreDrawListener(this);
                tree.removeOnGlobalLayoutListener(this);
            }
        }

        /** Stops for good and puts back what it hid. */
        void release() {
            stop();
            View view = root.get();
            if (view != null) view.removeOnAttachStateChangeListener(this);
            restore();
        }

        /** Shows each box this keeper hid, unless Instagram has changed it since. */
        void restore() {
            for (View box : new ArrayList<>(hidden)) {
                if (box.getVisibility() == View.INVISIBLE) box.setVisibility(View.VISIBLE);
            }
            hidden.clear();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            listen(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stop();
        }

        /** A layout pass may have bound another story, so the next frame looks for boxes again. */
        @Override
        public void onGlobalLayout() {
            stale = true;
        }

        /** Hides each box before the frame draws it. Always lets the frame go on. */
        @Override
        public boolean onPreDraw() {
            try {
                if (!switchedOn()) {
                    if (!hidden.isEmpty()) restore();
                    return true;
                }
                View view = root.get();
                if (view == null) return true;
                if (stale) {
                    stale = false;
                    collect(view);
                }
                for (WeakReference<View> held : boxes) {
                    View box = held.get();
                    if (box == null) continue;
                    if (box.hasFocus()) {
                        if (hidden.remove(box) && box.getVisibility() == View.INVISIBLE) box.setVisibility(View.VISIBLE);
                    } else if (box.getVisibility() == View.VISIBLE) {
                        hidden.add(box);
                        box.setVisibility(View.INVISIBLE);
                    }
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_REPLY_BOX, HOOK, failure);
            }
            return true;
        }

        private void collect(View view) {
            boxes.clear();
            int footer = id(view, FOOTER);
            int box = id(view, BOX);
            int old = id(view, OLD_BOX);
            report(footer, FOOTER);
            report(box, BOX);
            walk(view, footer, box, old);
        }

        private void walk(View view, int footer, int box, int old) {
            int id = view.getId();
            if (old != 0 && id == old) {
                boxes.add(new WeakReference<>(view));
                return;
            }
            if (footer != 0 && id == footer) {
                View found = box == 0 ? null : view.findViewById(box);
                if (found != null) boxes.add(new WeakReference<>(found));
                return;
            }
            if (!(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), footer, box, old);
        }
    }

    private static void report(int id, String name) {
        if (id == 0) HookStatus.missingViewId(FamilyNames.STORY_REPLY_BOX, name);
        else HookStatus.recoveredViewId(FamilyNames.STORY_REPLY_BOX, name);
    }

    private static int id(View view, String name) {
        return IDS.resolve(view.getResources(), view.getContext().getPackageName(), name, false);
    }

    /** Lets go of the keeper without putting anything back, and forgets the ids and the reader. */
    static void resetForTests() {
        Keeper keeper = current;
        current = null;
        if (keeper != null) {
            keeper.stop();
            View view = keeper.root.get();
            if (view != null) view.removeOnAttachStateChangeListener(keeper);
        }
        IDS.clear();
        reader = PATCHED;
        logged = false;
    }

    static void readerForTests(Reader stand) {
        reader = stand;
    }

    static void putIdForTests(Context context, String name, int id) {
        IDS.putForTests(context.getPackageName(), name, id);
    }

    static boolean keptForTests() {
        return current != null;
    }
}
