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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * Helper for the "Hide the reply box on stories" patch (#486, #618): keeps the Send message box,
 * and on switches of their own the Like, Comment and Share buttons, off the bottom of other
 * people's stories.
 *
 * <p>On 453 the box is {@link #BOX}, in the story's footer ({@link #FOOTER}) beside the like and
 * share buttons. Older layouts inflate it from a stub as {@link #OLD_BOX}. The patch calls
 * {@link #resumed} right after the story viewer, ReelViewerFragment, hands its onResume on to its
 * superclass. While any of the four switches is on, the viewer's window gets a pre-draw listener.
 * With the reply box switch on it sets each box it finds INVISIBLE before the frame draws. An
 * invisible box keeps its place, so the buttons beside it don't move, and it takes no taps. A box
 * holding the focus, where typed text goes, shows again until it lets go, so a reply Instagram
 * opens some other way is never typed blind.
 *
 * <p>The buttons ({@link Button}) go GONE instead: the footer is a ConstraintLayout, so a gone
 * button leaves no gap and takes no taps, and the parts next to it close up. Nothing is tapped, so
 * hiding the heart never likes or unlikes anything. Some buttons start as ViewStubs whose inflated
 * view may carry another id; ViewStub hands its own LayoutParams to that view, so a footer view
 * holding a button stub's LayoutParams is that button. Moving a button changes the layout, so the
 * listener cancels that one frame and the next one draws the closed-up footer, never two in a row.
 *
 * <p>The listener looks again only after a layout pass, which is when Instagram binds a new story
 * into the footer. With a switch off or HushGram paused it puts back what that switch hid, unless
 * Instagram has changed it since, and once none is on the viewer's next resume lets it go.
 */
public final class StoryReplyBox {
    /** The story's footer on 453, and the Send message box in it. */
    static final String FOOTER = "reel_item_toolbar_footer_container";
    static final String BOX = "message_composer_container";
    /** The box as older layouts inflate it, anywhere in the viewer. */
    static final String OLD_BOX = "reel_viewer_message_composer_container";

    static final String HOOK = "story reply box";

    /**
     * A footer button with a switch of its own, by the ids it goes by in the footer. The first is
     * the one 454 shows, and the one reported when the build lacks it; a stub's id stands for the
     * view it inflates.
     */
    enum Button {
        LIKE(Settings.HIDE_STORY_LIKE_BUTTON, "toolbar_like_container", "toolbar_like_button", "toolbar_like_button_stub"),
        COMMENT(Settings.HIDE_STORY_COMMENT_BUTTON, "toolbar_comment_button", "toolbar_comment_button_container",
                "toolbar_comment_button_mimic_ads", "toolbar_comment_button_stub", "toolbar_comment_count_stub",
                "reel_viewer_comments_button"),
        SHARE(Settings.HIDE_STORY_SHARE_BUTTON, "toolbar_reshare_button", "toolbar_reshare_button_stub");

        /** All three, read without copying, and only once a story viewer resumes. */
        static final Button[] ALL = values();

        final BooleanSetting setting;
        final String[] names;

        Button(BooleanSetting setting, String... names) {
            this.setting = setting;
            this.names = names;
        }

        /** Whether this button is kept off stories. Reads off while paused. */
        boolean on() {
            return Utils.settingsReady() && setting.get();
        }
    }

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
     * Lets go of the last viewer's keeper when the viewer, its view or the switches changed, putting
     * back what it hid, and while a switch is on makes sure this viewer has one. Never throws.
     */
    public static void resumed(Object viewer) {
        try {
            HookStatus.invoked(FamilyNames.STORY_REPLY_BOX);
            boolean hiding = anyOn();
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
                    Logger.printDebug(() -> "Story footer: watching the story viewer's reply box and buttons");
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

    /** Whether the reply box or any footer button is kept off stories. */
    static boolean anyOn() {
        if (switchedOn()) return true;
        for (Button button : Button.ALL) {
            if (button.on()) return true;
        }
        return false;
    }

    /** A footer button found at the last look, and which one it is. */
    private static final class Held {
        final WeakReference<View> view;
        final Button button;

        Held(View view, Button button) {
            this.view = new WeakReference<>(view);
            this.button = button;
        }
    }

    /**
     * Keeps one viewer's reply boxes invisible and switched-off buttons gone while its view is on
     * screen. It listens to the window only while the view is attached, since an observer taken
     * from a detached view is a stand-in that's thrown away on attach, and it stops on detach.
     */
    static final class Keeper implements ViewTreeObserver.OnPreDrawListener,
            ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        private final WeakReference<Object> viewer;
        private final WeakReference<View> root;
        /** The boxes found at the last look. Instagram drops a story's views as it pages, so weakly. */
        private final List<WeakReference<View>> boxes = new ArrayList<>();
        /** The boxes this keeper made invisible and hasn't put back. */
        private final Set<View> hidden = Collections.newSetFromMap(new WeakHashMap<>());
        /** The footer buttons found at the last look. */
        private final List<Held> buttons = new ArrayList<>();
        /** The buttons this keeper made gone and hasn't put back, with the visibility each had. */
        private final Map<View, Integer> gone = new WeakHashMap<>();
        /** Each button stub's LayoutParams, which the view it inflates takes over. */
        private final Map<ViewGroup.LayoutParams, Button> stubs = new WeakHashMap<>();
        @Nullable
        private ViewTreeObserver observer;
        private boolean stale = true;
        /** Whether this keeper cancelled the last frame, so it never cancels two in a row. */
        private boolean cancelled;

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

        /**
         * Shows each box and button this keeper hid, unless Instagram has changed it since. Answers
         * whether a button came back, which moves the rest of the footer.
         */
        boolean restore() {
            showBoxes();
            boolean moved = false;
            for (Map.Entry<View, Integer> entry : new ArrayList<>(gone.entrySet())) {
                View button = entry.getKey();
                if (button != null && button.getVisibility() == View.GONE) {
                    button.setVisibility(entry.getValue());
                    moved = true;
                }
            }
            gone.clear();
            return moved;
        }

        private void showBoxes() {
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

        /**
         * Hides each box and switched-on button before the frame draws it. Lets the frame go on,
         * except the one frame after a button moved, which would draw the footer's old layout.
         */
        @Override
        public boolean onPreDraw() {
            boolean moved = false;
            try {
                boolean boxOn = switchedOn();
                View view = root.get();
                if (!boxOn && !anyOn()) {
                    if (!hidden.isEmpty() || !gone.isEmpty()) moved = restore();
                } else if (view != null) {
                    if (stale) {
                        stale = false;
                        collect(view);
                    }
                    if (boxOn) hideBoxes();
                    else if (!hidden.isEmpty()) showBoxes();
                    moved = keepButtons();
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_REPLY_BOX, HOOK, failure);
            }
            if (moved && !cancelled) {
                cancelled = true;
                return false;
            }
            cancelled = false;
            return true;
        }

        private void hideBoxes() {
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
        }

        /** Makes each switched-on button gone and puts back each switched-off one. Answers whether one moved. */
        private boolean keepButtons() {
            boolean moved = false;
            for (Held held : buttons) {
                View button = held.view.get();
                if (button == null) continue;
                if (held.button.on()) {
                    if (button.getVisibility() != View.GONE) {
                        gone.put(button, button.getVisibility());
                        button.setVisibility(View.GONE);
                        moved = true;
                    }
                } else {
                    Integer was = gone.remove(button);
                    if (was != null && button.getVisibility() == View.GONE) {
                        button.setVisibility(was);
                        moved = true;
                    }
                }
            }
            return moved;
        }

        private void collect(View view) {
            boxes.clear();
            buttons.clear();
            int footer = id(view, FOOTER);
            int box = id(view, BOX);
            int old = id(view, OLD_BOX);
            report(footer, FOOTER);
            report(box, BOX);
            int[] ids = new int[0];
            Button[] owners = new Button[0];
            for (Button button : Button.ALL) {
                for (int i = 0; i < button.names.length; i++) {
                    int found = id(view, button.names[i]);
                    if (i == 0 && button.on()) report(found, button.names[0]);
                    if (found == 0) continue;
                    ids = Arrays.copyOf(ids, ids.length + 1);
                    owners = Arrays.copyOf(owners, owners.length + 1);
                    ids[ids.length - 1] = found;
                    owners[owners.length - 1] = button;
                }
            }
            walk(view, footer, box, old, ids, owners);
        }

        private void walk(View view, int footer, int box, int old, int[] ids, Button[] owners) {
            int id = view.getId();
            if (old != 0 && id == old) {
                boxes.add(new WeakReference<>(view));
                return;
            }
            if (footer != 0 && id == footer) {
                View found = box == 0 ? null : view.findViewById(box);
                if (found != null) boxes.add(new WeakReference<>(found));
                if (view instanceof ViewGroup) findButtons((ViewGroup) view, ids, owners);
                return;
            }
            if (!(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), footer, box, old, ids, owners);
        }

        /** Holds each button under the footer, or its stub's LayoutParams while it is still a stub. */
        private void findButtons(ViewGroup group, int[] ids, Button[] owners) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                Button button = null;
                int id = child.getId();
                for (int at = 0; at < ids.length && button == null; at++) {
                    if (ids[at] == id) button = owners[at];
                }
                if (child instanceof ViewStub) {
                    if (button != null && params != null) stubs.put(params, button);
                    continue;
                }
                if (button == null && params != null) button = stubs.get(params);
                if (button != null) buttons.add(new Held(child, button));
                else if (child instanceof ViewGroup) findButtons((ViewGroup) child, ids, owners);
            }
        }
    }

    private static void report(int id, String name) {
        if (id == 0) HookStatus.missingViewId(FamilyNames.STORY_REPLY_BOX, name);
        else HookStatus.recoveredViewId(FamilyNames.STORY_REPLY_BOX, name);
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
