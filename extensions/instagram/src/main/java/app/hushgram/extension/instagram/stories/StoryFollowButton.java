/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide Follow on stories" patch (#237): keeps the Follow button out of the story
 * viewer's header for accounts you don't follow.
 *
 * <p>The button is a TextView inflated from a ViewStub of the same id into the header's row of
 * buttons, beside the three-dot menu. Instagram decides on each bind whether it shows, and a
 * header's view holder is recycled from story to story, so Instagram sets it again for every
 * account. The patch calls {@link #bind} first thing in the story item binder's toolbar bind with
 * the story's view holder. While the switch is on, the row gets a pre-draw listener that sets the
 * button GONE before any frame shows it, so the row closes up with no gap, and a later show from
 * Instagram is undone the same way. Nothing ever taps the button or calls anything on it but
 * {@link View#setVisibility}, and a ViewStub not yet inflated is left alone.
 *
 * <p>Each bind first puts back what the listener hid, so Instagram's own bind decides on a clean
 * slate. With the switch off or HushGram paused, the listener stops hiding and the next bind leaves
 * Instagram's button as Instagram sets it. A switch that was never on costs one check per bind.
 */
public final class StoryFollowButton {
    /** Instagram's row of buttons at the top end of the story header, and the Follow button in it. */
    static final String CONTAINER = "reel_header_extras_container";
    static final String FOLLOW = "reel_header_unconnected_follow_button_stub";

    static final String HOOK = "story follow button";

    private static final ResourceIdCache IDS = new ResourceIdCache();

    /**
     * The header rows that have a keeper. Each keeper holds its row weakly, so a row Instagram
     * drops takes its entry with it. Read and written on the main thread only, where binds run.
     */
    private static final Map<View, Keeper> KEEPERS = new WeakHashMap<>();

    /** Whether a keeper was ever made, so a switch that was never on costs nothing per story. */
    private static volatile boolean kept;

    private StoryFollowButton() {
    }

    /** Where {@link #bind} gets the header from Instagram's view holder. Tests hand in their own. */
    interface Reader {
        /** The root view of the story's view holder [holder], or null. */
        @Nullable
        View itemView(Object holder);
    }

    static final Reader PATCHED = new Reader() {
        @Override
        public View itemView(Object holder) {
            return StoryFollowButton.itemView(holder);
        }
    };

    /**
     * Injected first thing in the story item binder's toolbar bind. Puts back the Follow button the
     * row's keeper hid, and while the switch is on makes sure the row has one. Never throws.
     */
    public static void bind(Object holder) {
        bind(holder, PATCHED, StoryFollowButton::switchedOn);
    }

    static void bind(@Nullable Object holder, Reader reader, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.STORY_FOLLOW);
            boolean hiding = on.getAsBoolean();
            if (!hiding && !kept) return;
            View root = holder == null ? null : reader.itemView(holder);
            if (root == null) return;
            ViewGroup row = row(root, hiding);
            if (row == null) return;
            Keeper keeper = KEEPERS.get(row);
            if (keeper != null) keeper.restore();
            if (!hiding) {
                if (keeper != null) keeper.release(row);
                return;
            }
            if (keeper == null) {
                int follow = id(root, FOLLOW);
                if (follow == 0) {
                    HookStatus.missingViewId(FamilyNames.STORY_FOLLOW, FOLLOW);
                    return;
                }
                HookStatus.recoveredViewId(FamilyNames.STORY_FOLLOW, FOLLOW);
                keeper = new Keeper(row, follow, on);
                KEEPERS.put(row, keeper);
                row.addOnAttachStateChangeListener(keeper);
                if (row.isAttachedToWindow()) keeper.listen(row);
                kept = true;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_FOLLOW, HOOK, failure);
        }
    }

    /** Whether the Follow button is kept out of story headers. Reads off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_STORY_FOLLOW.get();
    }

    /**
     * Keeps one header row's Follow button gone while the row is on screen. It listens to the
     * window's pre-draw only while the row is attached, since an observer taken from a detached
     * view is a stand-in that's thrown away on attach, and it stops on detach.
     */
    static final class Keeper implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private final WeakReference<ViewGroup> row;
        private final int follow;
        private final BooleanSupplier on;
        @Nullable
        private ViewTreeObserver observer;
        @Nullable
        private WeakReference<View> hidden;
        private int before;

        Keeper(ViewGroup row, int follow, BooleanSupplier on) {
            this.row = new WeakReference<>(row);
            this.follow = follow;
            this.on = on;
        }

        void listen(View view) {
            if (observer != null) return;
            ViewTreeObserver tree = view.getViewTreeObserver();
            tree.addOnPreDrawListener(this);
            observer = tree;
        }

        void stop() {
            ViewTreeObserver tree = observer;
            observer = null;
            if (tree != null && tree.isAlive()) tree.removeOnPreDrawListener(this);
        }

        /** Stops for good: the switch went off, so the row needs no keeper until it's back on. */
        void release(View view) {
            stop();
            view.removeOnAttachStateChangeListener(this);
            KEEPERS.remove(view);
        }

        /** Puts back the button this keeper hid, unless something else has shown it since. */
        void restore() {
            View view = hidden == null ? null : hidden.get();
            hidden = null;
            if (view != null && view.getVisibility() == View.GONE) view.setVisibility(before);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            listen(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stop();
        }

        /** Hides the Follow button before the frame that would show it, and cancels that frame. */
        @Override
        public boolean onPreDraw() {
            try {
                ViewGroup group = row.get();
                if (group == null || !on.getAsBoolean()) return true;
                View button = group.findViewById(follow);
                if (button == null || button instanceof ViewStub || button.getVisibility() == View.GONE) return true;
                before = button.getVisibility();
                hidden = new WeakReference<>(button);
                button.setVisibility(View.GONE);
                // The frame is drawn again once the row has closed up, so the button never shows.
                return false;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_FOLLOW, HOOK, failure);
                return true;
            }
        }
    }

    /** Forgets the ids looked up, the keepers and that one was ever made. */
    static void resetForTests() {
        IDS.clear();
        for (Map.Entry<View, Keeper> entry : KEEPERS.entrySet()) {
            entry.getValue().stop();
            entry.getKey().removeOnAttachStateChangeListener(entry.getValue());
        }
        KEEPERS.clear();
        kept = false;
    }

    static void putIdForTests(Context context, String name, int id) {
        IDS.putForTests(context.getPackageName(), name, id);
    }

    static boolean keptForTests() {
        return kept;
    }

    static int keepersForTests() {
        return KEEPERS.size();
    }

    /** The header's row of buttons under [root], reporting a build without it while it's wanted. */
    @Nullable
    private static ViewGroup row(View root, boolean wanted) {
        int id = id(root, CONTAINER);
        View found = id == 0 ? null : root.findViewById(id);
        if (!(found instanceof ViewGroup)) {
            if (wanted) HookStatus.missingViewId(FamilyNames.STORY_FOLLOW, CONTAINER);
            return null;
        }
        HookStatus.recoveredViewId(FamilyNames.STORY_FOLLOW, CONTAINER);
        return (ViewGroup) found;
    }

    private static int id(View view, String name) {
        return IDS.resolve(view.getResources(), view.getContext().getPackageName(), name, false);
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the root view of the story's view holder [holder]. */
    @Nullable
    public static View itemView(Object holder) {
        return null;
    }
}
