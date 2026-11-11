/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Keep story buttons below the story" patch (#598): with a large font or display
 * size, the row along the bottom of a story (the Send message box with like and share, or your own
 * story's toolbar) no longer covers the bottom of the story.
 *
 * <p>On a screen taller than 9:16, Instagram 454 shows a story as a card, {@link #MEDIA}, on its
 * page, {@link #PAGE}, with room under it for that row, {@link #FOOTER}. At a large font or display
 * size the row grows past that room, so it rides up over the story while part of the room under it
 * stays empty. The patch calls {@link #resumed} right after the story viewer, ReelViewerFragment,
 * hands its onResume on to its superclass. While the switch is on, the viewer's window gets a
 * pre-draw listener that moves each page's toolbar, {@link #TOOLBAR}, which holds the row and the
 * caption above it, down by as much as the row covers the story, but never past the page's bottom.
 * A row that doesn't cover the story isn't moved, so stories that fill the screen stay as they are.
 *
 * <p>It moves a toolbar only while Instagram has it at rest or where this put it, so Instagram's own
 * slides win. The listener looks for pages again only after a layout pass and never cancels a
 * frame. With the switch off or HushGram paused it puts back each toolbar it moved, and the viewer's
 * next resume lets it go.
 */
public final class StoryButtonsRoom {
    /** A story's page in the viewer on 454, the story card on it, its toolbar, and the row along the toolbar's bottom. */
    static final String PAGE = "reel_view_group";
    static final String MEDIA = "reel_viewer_media_layout";
    static final String TOOLBAR = "reel_item_toolbar_container";
    static final String FOOTER = "reel_item_toolbar_footer_container";

    static final String HOOK = "story buttons room";

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
            return StoryButtonsRoomReader.view(viewer);
        }
    };

    private static Reader reader = PATCHED;

    /** The keeper of the viewer last resumed with the switch on. Main thread only, where lifecycles run. */
    @Nullable
    private static Keeper current;

    private static volatile boolean logged;

    private StoryButtonsRoom() {
    }

    /**
     * Injected right after the story viewer's onResume hands on to its superclass's, with the viewer.
     * Lets go of the last viewer's keeper when the viewer, its view or the switch changed, putting
     * back what it moved, and while the switch is on makes sure this viewer has one. Never throws.
     */
    public static void resumed(Object viewer) {
        try {
            HookStatus.invoked(FamilyNames.STORY_BUTTONS_ROOM);
            boolean keeping = switchedOn();
            Keeper keeper = current;
            if (keeper == null && !keeping) return;
            View view = keeping && viewer != null ? reader.view(viewer) : null;
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
            }
            if (view.isAttachedToWindow()) keeper.listen(view);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_BUTTONS_ROOM, HOOK, failure);
        }
    }

    /** Whether story buttons are kept below the story. Reads off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_STORY_BUTTONS_BELOW.get();
    }

    /**
     * How far down a toolbar moves: as far as the row's top sits above the story's bottom, but no
     * further than the room between the row's bottom and the page's, and never up.
     */
    static int shift(int storyBottom, int rowTop, int rowBottom, int pageBottom) {
        return Math.max(0, Math.min(storyBottom - rowTop, pageBottom - rowBottom));
    }

    /** The parts of one story's page, held weakly since Instagram drops a page's views as it pages. */
    static final class Page {
        final WeakReference<View> page;
        final WeakReference<View> media;
        final WeakReference<View> toolbar;
        final WeakReference<View> footer;

        Page(View page, View media, View toolbar, View footer) {
            this.page = new WeakReference<>(page);
            this.media = new WeakReference<>(media);
            this.toolbar = new WeakReference<>(toolbar);
            this.footer = new WeakReference<>(footer);
        }
    }

    /**
     * Keeps one viewer's story buttons below its stories while its view is on screen. It listens to
     * the window only while the view is attached, since an observer taken from a detached view is a
     * stand-in that's thrown away on attach, and it stops on detach.
     */
    static final class Keeper implements ViewTreeObserver.OnPreDrawListener,
            ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        private final WeakReference<Object> viewer;
        private final WeakReference<View> root;
        /** The pages found at the last look. */
        private final List<Page> pages = new ArrayList<>();
        /** The toolbars this keeper moved and hasn't put back, with how far it moved each. */
        private final Map<View, Float> moved = new WeakHashMap<>();
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

        /** Stops for good and puts back what it moved. */
        void release() {
            stop();
            View view = root.get();
            if (view != null) view.removeOnAttachStateChangeListener(this);
            restore();
        }

        /** Puts each toolbar this keeper moved back at rest, unless Instagram has moved it since. */
        void restore() {
            for (Map.Entry<View, Float> entry : new ArrayList<>(moved.entrySet())) {
                View toolbar = entry.getKey();
                if (toolbar != null && toolbar.getTranslationY() == entry.getValue()) toolbar.setTranslationY(0f);
            }
            moved.clear();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            listen(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stop();
        }

        /** A layout pass may have bound another story or moved its row, so the next frame looks again. */
        @Override
        public void onGlobalLayout() {
            stale = true;
        }

        /** Moves each page's toolbar before the frame draws it. Always lets the frame go on. */
        @Override
        public boolean onPreDraw() {
            try {
                if (!switchedOn()) {
                    if (!moved.isEmpty()) restore();
                    return true;
                }
                View view = root.get();
                if (view == null) return true;
                if (stale) {
                    stale = false;
                    collect(view);
                }
                for (Page held : pages) fit(held);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_BUTTONS_ROOM, HOOK, failure);
            }
            return true;
        }

        private void fit(Page held) {
            View page = held.page.get();
            View media = held.media.get();
            View toolbar = held.toolbar.get();
            View footer = held.footer.get();
            if (page == null || media == null || toolbar == null || footer == null) return;
            Float mine = moved.get(toolbar);
            float now = toolbar.getTranslationY();
            if (now != 0f && (mine == null || now != mine)) {
                // Instagram is sliding it, so it's Instagram's until it comes back to rest.
                moved.remove(toolbar);
                return;
            }
            int storyTop = topIn(page, media);
            int rowTop = topIn(page, footer);
            boolean placed = storyTop != Integer.MIN_VALUE && rowTop != Integer.MIN_VALUE
                    && footer.getVisibility() == View.VISIBLE && footer.getHeight() > 0;
            int by = placed ? shift(storyTop + media.getHeight(), rowTop, rowTop + footer.getHeight(), page.getHeight()) : 0;
            if (by == 0) {
                if (mine != null) {
                    toolbar.setTranslationY(0f);
                    moved.remove(toolbar);
                }
                return;
            }
            if (now == by) return;
            toolbar.setTranslationY(by);
            moved.put(toolbar, (float) by);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Keep story buttons below the story: moved a story's buttons down " + by + " px");
            }
        }

        private void collect(View view) {
            pages.clear();
            int page = id(view, PAGE);
            int media = id(view, MEDIA);
            int toolbar = id(view, TOOLBAR);
            int footer = id(view, FOOTER);
            report(page, PAGE);
            report(media, MEDIA);
            report(toolbar, TOOLBAR);
            report(footer, FOOTER);
            if (page == 0 || media == 0 || toolbar == 0 || footer == 0) return;
            walk(view, page, media, toolbar, footer);
        }

        private void walk(View view, int page, int media, int toolbar, int footer) {
            if (view.getId() == page) {
                View story = view.findViewById(media);
                View bar = view.findViewById(toolbar);
                View row = bar == null ? null : bar.findViewById(footer);
                if (story != null && row != null) pages.add(new Page(view, story, bar, row));
                return;
            }
            if (!(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), page, media, toolbar, footer);
        }
    }

    /**
     * Where [view]'s top sits in [page] as laid out, leaving out any view's translation so a move
     * this made never feeds back. Integer.MIN_VALUE when [view] isn't inside [page].
     */
    static int topIn(View page, View view) {
        int top = 0;
        View at = view;
        while (at != page) {
            top += at.getTop();
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return Integer.MIN_VALUE;
            at = (View) parent;
        }
        return top;
    }

    private static void report(int id, String name) {
        if (id == 0) HookStatus.missingViewId(FamilyNames.STORY_BUTTONS_ROOM, name);
        else HookStatus.recoveredViewId(FamilyNames.STORY_BUTTONS_ROOM, name);
    }

    private static int id(View view, String name) {
        return IDS.resolve(view.getResources(), Utils.resourcePackage(view.getContext()), name, false);
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
        IDS.putForTests(Utils.resourcePackage(context), name, id);
    }

    static boolean keptForTests() {
        return current != null;
    }
}
