/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;

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
 * hands its onResume on to its superclass. While a switch is on, the viewer's window gets a
 * pre-draw listener that moves each page's toolbar, {@link #TOOLBAR}, which holds the row and the
 * caption above it, down by as much as the row covers the story, but never past the page's bottom.
 * A row that doesn't cover the story isn't moved, so stories that fill the screen stay as they are.
 *
 * <p>The patch's second switch, Move story controls off the story (#902), uses the black space a
 * story card leaves above and below it. The header over the card's top, {@link #HEADER} with the
 * progress bar and the name and music row, moves up into the space between the system's bars and
 * the card, as far as there's room, and when all of it fits the name row goes on top with the
 * progress bar right under it, against the card. The toolbar moves down until the row's box sits
 * on the system's bars, out of the strip of black it leaves under itself, so a seek bar placed
 * above the row follows it off the story. Views that would clip the header on its way up let it
 * draw past their tops while it's up there, and get their clipping back when it comes down.
 *
 * <p>Two more calls carry the header up there. The card, Instagram's RoundedCornerFrameLayout,
 * draws its children into a node clipped to its rounded box, so a header above the card never
 * shows. The patch calls {@link #drawStart} first thing in the card's dispatchDraw, and for a card
 * whose header is up the card's children are drawn once more into the strip between the page's top
 * and the card's, before the card draws as it did, clipped to its own box. And the story viewer
 * never hands a touch above the card to the header, so the patch hands every touch to
 * {@link #touch} in IgFragmentActivity.dispatchTouchEvent, and a tap on the header up there taps
 * the button under the finger, such as the name.
 *
 * <p>It moves a view only while Instagram has it at rest or where this put it, so Instagram's own
 * slides win. The listener looks for pages again only after a layout pass and never cancels a
 * frame. With both switches off or HushGram paused it puts back each view it moved, and the
 * viewer's next resume lets it go.
 */
public final class StoryButtonsRoom {
    /** A story's page in the viewer on 454, the story card on it, its toolbar, and the row along the toolbar's bottom. */
    static final String PAGE = "reel_view_group";
    static final String MEDIA = "reel_viewer_media_layout";
    static final String TOOLBAR = "reel_item_toolbar_container";
    static final String FOOTER = "reel_item_toolbar_footer_container";

    /**
     * The header along the top of a story card on 457 (#902): a vertical LinearLayout holding the
     * progress bar and, under it, the row with the picture, name, time and music.
     */
    static final String HEADER = "reel_viewer_header_container";
    static final String PROGRESS = "reel_viewer_progress_bar";
    static final String HEADER_ROW = "reel_viewer_header";

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

    /** The keeper of the viewer last resumed with a switch on. Main thread only, where lifecycles run. */
    @Nullable
    private static Keeper current;

    /** What tests say the system's bars cover at the window's top and bottom, in pixels, or -1 to read the window. */
    private static int barsTopForTests = -1;
    private static int barsBottomForTests = -1;

    private static volatile boolean logged;
    private static volatile boolean loggedOff;
    private static volatile boolean loggedDrawn;
    private static volatile boolean loggedTapped;

    /**
     * Story cards whose header is up above them (#902), with how far above the card's top their
     * children may draw: up to the page's top. Main thread only, where views draw.
     */
    private static final Map<View, Integer> ABOVE = new WeakHashMap<>();

    /** The headers up above their cards, each with its card. Main thread only, where touches come. */
    private static final Map<View, WeakReference<View>> LIFTED = new WeakHashMap<>();

    /** The card whose dispatchDraw runs next as its own pass, after the strip above it was drawn. */
    @Nullable
    private static Object ownPass;

    /** The header a finger went down on above its card, where it went down, and whether it moved since. */
    private static WeakReference<View> pressed = new WeakReference<>(null);
    private static float downX;
    private static float downY;
    private static boolean pressMoved;

    private StoryButtonsRoom() {
    }

    /**
     * Injected right after the story viewer's onResume hands on to its superclass, with the viewer.
     * Lets go of the last viewer's keeper when the viewer, its view or the switches changed, putting
     * back what it moved, and while a switch is on makes sure this viewer has one. Never throws.
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

    /**
     * Injected first thing in RoundedCornerFrameLayout.dispatchDraw, with the card and its canvas
     * (#902). Answers 0 for every card but a story card whose header is up, and for that card's own
     * pass, and Instagram's code then draws the card as it did. For a story card whose header is up
     * it clips the canvas to the strip between the page's top and the card's and answers the count
     * to put the canvas back to, never 0. The patch then draws the card's children once into that
     * strip and hands on to {@link #stripDrawn}. Never throws.
     */
    public static int drawStart(Object card, Object canvas) {
        Canvas drawn = null;
        int count = 0;
        try {
            if (ownPass != null && ownPass == card) {
                ownPass = null;
                return 0;
            }
            if (ABOVE.isEmpty() || !(card instanceof View) || !(canvas instanceof Canvas)) return 0;
            View view = (View) card;
            Integer above = ABOVE.get(view);
            if (above == null || above <= 0) return 0;
            drawn = (Canvas) canvas;
            count = drawn.save();
            if (count < 1) {
                drawn.restore();
                return 0;
            }
            drawn.clipRect(0, -above, view.getWidth(), 0);
            if (!loggedDrawn) {
                loggedDrawn = true;
                Logger.printDebug(() -> "Move story controls off the story: drew a story's header above it");
            }
            return count;
        } catch (Throwable failure) {
            if (drawn != null && count >= 1) {
                try {
                    drawn.restoreToCount(count);
                } catch (Throwable ignored) {
                    // The canvas is Instagram's to put right from here.
                }
            }
            HookStatus.threw(FamilyNames.STORY_BUTTONS_ROOM, HOOK, failure);
            return 0;
        }
    }

    /**
     * Called by the patch after the card's children were drawn into the strip above it, with the
     * count {@link #drawStart} answered. Puts the canvas back, clips it to the card's own box, and
     * marks the card's next dispatchDraw, which the patch calls right after, as its own pass, which
     * {@link #drawStart} answers 0 for. Answers the count to put the canvas back to after that pass,
     * or 0 for nothing to put back. Never throws.
     */
    public static int stripDrawn(Object card, Object canvas, int count) {
        // First, so the pass the patch calls next is the card's own even if what follows throws.
        ownPass = card;
        try {
            Canvas drawn = (Canvas) canvas;
            drawn.restoreToCount(count);
            int own = drawn.save();
            View view = (View) card;
            drawn.clipRect(0, 0, view.getWidth(), view.getHeight());
            return own;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_BUTTONS_ROOM, HOOK, failure);
            return 0;
        }
    }

    /** Called by the patch after the card's own pass, with the count {@link #stripDrawn} answered. Never throws. */
    public static void cardDrawn(Object canvas, int count) {
        try {
            ownPass = null;
            if (count >= 1) ((Canvas) canvas).restoreToCount(count);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_BUTTONS_ROOM, HOOK, failure);
        }
    }

    /**
     * Injected first thing in IgFragmentActivity.dispatchTouchEvent, which every touch on an
     * Instagram screen passes through before any of its views, or right after Tap to play's clock
     * (#902). A finger going down on a header that's up above its card, and coming up again without
     * moving further than the touch slop, taps the button in the header under it, such as the name.
     * Answers true for each event of a touch that went down there, which Instagram then never sees,
     * so the story neither pauses nor skips. Answers false for every other touch, which Instagram
     * gets as it was. Never throws.
     */
    public static boolean touch(Activity activity, MotionEvent event) {
        try {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                View down = LIFTED.isEmpty() ? null : headerAt(activity, event.getX(), event.getY());
                pressed = new WeakReference<>(down);
                if (down == null) return false;
                downX = event.getX();
                downY = event.getY();
                pressMoved = false;
                return true;
            }
            View header = pressed.get();
            if (header == null) return false;
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    if (beyondSlop(header, event.getX(), event.getY())) pressMoved = true;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    pressMoved = true;
                    break;
                case MotionEvent.ACTION_UP:
                    pressed = new WeakReference<>(null);
                    if (!pressMoved && !beyondSlop(header, event.getX(), event.getY()) && header.isShown()) {
                        View button = clickableAt(header, event.getX(), event.getY());
                        if (button != null) {
                            button.performClick();
                            if (!loggedTapped) {
                                loggedTapped = true;
                                Logger.printDebug(() -> "Move story controls off the story: tapped a story's header above it");
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pressed = new WeakReference<>(null);
                    break;
                default:
                    break;
            }
            return true;
        } catch (Throwable failure) {
            pressed = new WeakReference<>(null);
            HookStatus.threw(FamilyNames.STORY_BUTTONS_ROOM, HOOK, failure);
            return false;
        }
    }

    /**
     * The header up above its card in [activity]'s window whose box holds the point [x], [y] in
     * that window above the card's top, or null. The part of a header still over its card, when
     * there wasn't room for all of it, stays Instagram's.
     */
    @Nullable
    static View headerAt(@Nullable Activity activity, float x, float y) {
        Window window = activity == null ? null : activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        if (decor == null) return null;
        int[] at = new int[2];
        for (Map.Entry<View, WeakReference<View>> entry : LIFTED.entrySet()) {
            View header = entry.getKey();
            View card = entry.getValue().get();
            if (header == null || card == null || !header.isShown() || header.getRootView() != decor) continue;
            card.getLocationInWindow(at);
            if (y >= at[1] || !inBox(header, x, y)) continue;
            return header;
        }
        return null;
    }

    /** The innermost clickable view showing in [view] whose box in the window holds [x], [y], or null. */
    @Nullable
    static View clickableAt(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE || !inBox(view, x, y)) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View hit = clickableAt(group.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return view.isClickable() && view.isEnabled() ? view : null;
    }

    /** Whether [view]'s box in its window, where it's moved to, holds [x], [y]. */
    static boolean inBox(View view, float x, float y) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
    }

    private static boolean beyondSlop(View view, float x, float y) {
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        float dx = x - downX;
        float dy = y - downY;
        return dx * dx + dy * dy > (float) slop * slop;
    }

    /** Whether either switch is on. Reads off while paused. */
    static boolean switchedOn() {
        return keepsBelow() || movesOff();
    }

    /** Whether story buttons are kept below the story. Reads off while paused. */
    static boolean keepsBelow() {
        return Utils.settingsReady() && Settings.KEEP_STORY_BUTTONS_BELOW.get();
    }

    /** Whether the story's header and buttons move off the story into the black around it (#902). Reads off while paused. */
    static boolean movesOff() {
        return Utils.settingsReady() && Settings.STORY_CONTROLS_OFF_STORY.get();
    }

    /**
     * How far down a toolbar moves: as far as the row's top sits above the story's bottom, but no
     * further than the room between the row's bottom and the page's, and never up.
     */
    static int shift(int storyBottom, int rowTop, int rowBottom, int pageBottom) {
        return Math.max(0, Math.min(storyBottom - rowTop, pageBottom - rowBottom));
    }

    /**
     * How far down a toolbar moves to put the row's box at the bottom (#902): from where the box ends,
     * [free] pixels above the row's bottom, down to [floor], the lower of the page's bottom and the
     * system's bars, and never up. All in the page's pixels.
     */
    static int drop(int rowBottom, int free, int floor) {
        return Math.max(0, floor - (rowBottom - Math.max(0, free)));
    }

    /**
     * How far up the header moves (#902): far enough that its bottom, [reach] pixels into the story,
     * comes off it, but only through the [room] above the story plus the [empty] strip along the
     * header's own top that draws nothing.
     */
    static int lift(int reach, int room, int empty) {
        if (reach <= 0) return 0;
        return Math.max(0, Math.min(reach, Math.max(0, room) + Math.max(0, empty)));
    }

    /** The parts of one story's page, held weakly since Instagram drops a page's views as it pages. */
    static final class Page {
        final WeakReference<View> page;
        final WeakReference<View> media;
        final WeakReference<View> toolbar;
        final WeakReference<View> footer;
        final WeakReference<View> header;
        final WeakReference<View> progress;
        final WeakReference<View> headerRow;

        Page(View page, View media, View toolbar, View footer, @Nullable View header, @Nullable View progress,
             @Nullable View headerRow) {
            this.page = new WeakReference<>(page);
            this.media = new WeakReference<>(media);
            this.toolbar = new WeakReference<>(toolbar);
            this.footer = new WeakReference<>(footer);
            this.header = new WeakReference<>(header);
            this.progress = new WeakReference<>(progress);
            this.headerRow = new WeakReference<>(headerRow);
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
        /** The views this keeper moved and hasn't put back, with how far it moved each. */
        private final Map<View, Float> moved = new WeakHashMap<>();
        /** The views this keeper stopped clipping their insides so a header can draw above them (#902). */
        private final Map<ViewGroup, Boolean> unclipped = new WeakHashMap<>();
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

        /** Puts each view this keeper moved back at rest, unless Instagram has moved it since, and the clipping back. */
        void restore() {
            for (Map.Entry<View, Float> entry : new ArrayList<>(moved.entrySet())) {
                View view = entry.getKey();
                if (view != null && view.getTranslationY() == entry.getValue()) view.setTranslationY(0f);
            }
            moved.clear();
            clipAgain();
            letDownAll();
        }

        /** Gives back the clipping this keeper took away. */
        void clipAgain() {
            for (ViewGroup group : new ArrayList<>(unclipped.keySet())) {
                if (group != null && !group.getClipChildren()) group.setClipChildren(true);
            }
            unclipped.clear();
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

        /** Moves each page's toolbar, and with #902's switch its header, before the frame draws them. Always lets the frame go on. */
        @Override
        public boolean onPreDraw() {
            try {
                boolean below = keepsBelow();
                boolean off = movesOff();
                if (!below && !off) {
                    if (!moved.isEmpty() || !unclipped.isEmpty()) restore();
                    return true;
                }
                View view = root.get();
                if (view == null) return true;
                if (stale) {
                    stale = false;
                    collect(view, off);
                }
                boolean lifted = false;
                for (Page held : pages) lifted |= fit(held, below, off);
                if (!lifted && !unclipped.isEmpty()) clipAgain();
                if (!lifted && !ABOVE.isEmpty()) letDownAll();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_BUTTONS_ROOM, HOOK, failure);
            }
            return true;
        }

        /** Places one page's toolbar and header. Answers whether its header is up off the story. */
        private boolean fit(Page held, boolean below, boolean off) {
            View page = held.page.get();
            View media = held.media.get();
            View toolbar = held.toolbar.get();
            View footer = held.footer.get();
            if (page == null || media == null || toolbar == null || footer == null) return false;
            int storyTop = topIn(page, media);
            int rowTop = topIn(page, footer);
            boolean placed = storyTop != Integer.MIN_VALUE && rowTop != Integer.MIN_VALUE
                    && footer.getVisibility() == View.VISIBLE && footer.getHeight() > 0;
            int by = 0;
            if (placed && below) {
                by = shift(storyTop + media.getHeight(), rowTop, rowTop + footer.getHeight(), page.getHeight());
            }
            if (placed && off) {
                int free = Math.max(footer.getPaddingBottom(), StorySeekBar.emptyBelow(footer));
                int[] at = new int[2];
                page.getLocationInWindow(at);
                int bars = page.getRootView().getHeight() - barsBottom(page) - at[1];
                by = Math.max(by, drop(rowTop + footer.getHeight(), free, Math.min(page.getHeight(), bars)));
            }
            int down = place(toolbar, by);
            int up = off && storyTop != Integer.MIN_VALUE ? raise(held, page, media, storyTop) : 0;
            if (!logged && down > 0) {
                logged = true;
                Logger.printDebug(() -> "Keep story buttons below the story: moved a story's buttons down " + down + " px");
            }
            if (!loggedOff && up > 0) {
                loggedOff = true;
                Logger.printDebug(() -> "Move story controls off the story: moved a story's header up " + up + " px");
            }
            View header = held.header.get();
            if (!off && header != null) {
                place(header, 0);
                swap(held, false);
            }
            if (up <= 0) letDown(media, header);
            return up > 0;
        }

        /**
         * Moves the page's header up off the story into the space above it (#902), and when all of it
         * fits, puts the name row over the progress bar. Answers how far up it went.
         */
        private int raise(Page held, View page, View media, int storyTop) {
            View header = held.header.get();
            if (header == null) return 0;
            int headerTop = topIn(page, header);
            if (headerTop == Integer.MIN_VALUE || header.getVisibility() != View.VISIBLE || header.getHeight() <= 0) {
                place(header, 0);
                swap(held, false);
                return 0;
            }
            int reach = headerTop + header.getHeight() - storyTop;
            int empty = header.getBackground() == null ? Math.max(0, drawnTop(header)) : 0;
            int[] at = new int[2];
            page.getLocationInWindow(at);
            int storyTopInWindow = at[1] + storyTop;
            int headerTopInWindow = at[1] + headerTop;
            List<ViewGroup> clipping = new ArrayList<>();
            int ceiling = ceiling(header, barsTop(page), clipping);
            int up = lift(reach, storyTopInWindow - ceiling, empty);
            int went = -place(header, -up);
            swap(held, went > 0 && went == reach);
            if (went <= 0) return 0;
            int drawnFrom = headerTopInWindow - went + empty;
            for (ViewGroup group : clipping) {
                int[] spot = new int[2];
                group.getLocationInWindow(spot);
                if (spot[1] <= drawnFrom) continue;
                unclip(group);
                // A view's own drawing is cut to its box when the view holding it clips its
                // children, so that one lets go too.
                if (group.getParent() instanceof ViewGroup) unclip((ViewGroup) group.getParent());
            }
            // The card draws what's in it within its rounded box, so it's told to draw the header
            // over its top too, as high as the header may go.
            int above = storyTopInWindow - ceiling;
            if (above > 0 && drawnFrom < storyTopInWindow) carry(media, header, above);
            else letDown(media, header);
            return went;
        }

        private void unclip(ViewGroup group) {
            if (!group.getClipChildren()) return;
            group.setClipChildren(false);
            unclipped.put(group, Boolean.TRUE);
        }

        /**
         * Puts the name row above the progress bar when [on], each into the other's place within the
         * header, or back.
         */
        private void swap(Page held, boolean on) {
            View progress = held.progress.get();
            View row = held.headerRow.get();
            if (progress == null || row == null) return;
            boolean fits = on && progress.getVisibility() == View.VISIBLE && row.getVisibility() == View.VISIBLE
                    && progress.getParent() == row.getParent() && progress.getTop() < row.getTop()
                    && progress.getBottom() <= row.getBottom();
            if (!fits) {
                place(row, 0);
                place(progress, 0);
                return;
            }
            place(row, progress.getTop() - row.getTop());
            place(progress, row.getBottom() - progress.getBottom());
        }

        /**
         * Moves [view] [by] pixels down, up when negative, or back to rest for 0, unless Instagram is
         * moving it. Answers how far it sits moved by this keeper now.
         */
        private int place(View view, int by) {
            Float mine = moved.get(view);
            float now = view.getTranslationY();
            if (now != 0f && (mine == null || now != mine)) {
                // Instagram is sliding it, so it's Instagram's until it comes back to rest.
                moved.remove(view);
                return 0;
            }
            if (by == 0) {
                if (mine != null) {
                    view.setTranslationY(0f);
                    moved.remove(view);
                }
                return 0;
            }
            if (now != by) view.setTranslationY(by);
            moved.put(view, (float) by);
            return by;
        }

        private void collect(View view, boolean off) {
            pages.clear();
            int page = id(view, PAGE);
            int media = id(view, MEDIA);
            int toolbar = id(view, TOOLBAR);
            int footer = id(view, FOOTER);
            report(page, PAGE);
            report(media, MEDIA);
            report(toolbar, TOOLBAR);
            report(footer, FOOTER);
            int[] head = {id(view, HEADER), id(view, PROGRESS), id(view, HEADER_ROW)};
            if (off) {
                report(head[0], HEADER);
                report(head[1], PROGRESS);
                report(head[2], HEADER_ROW);
            }
            if (page == 0 || media == 0 || toolbar == 0 || footer == 0) return;
            walk(view, page, media, toolbar, footer, head);
        }

        private void walk(View view, int page, int media, int toolbar, int footer, int[] head) {
            if (view.getId() == page) {
                View story = view.findViewById(media);
                View bar = view.findViewById(toolbar);
                View row = bar == null ? null : bar.findViewById(footer);
                View header = head[0] == 0 ? null : view.findViewById(head[0]);
                View progress = header == null || head[1] == 0 ? null : header.findViewById(head[1]);
                View name = header == null || head[2] == 0 ? null : header.findViewById(head[2]);
                if (story != null && row != null) pages.add(new Page(view, story, bar, row, header, progress, name));
                return;
            }
            if (!(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), page, media, toolbar, footer, head);
        }
    }

    /**
     * Marks [card] as drawing its children up to [above] pixels over its top and [header] as up
     * above it (#902), and has the card draw again when that's new.
     */
    static void carry(View card, View header, int above) {
        Integer was = ABOVE.get(card);
        if (was == null || was != above) {
            ABOVE.put(card, above);
            card.invalidate();
        }
        WeakReference<View> held = LIFTED.get(header);
        if (held == null || held.get() != card) LIFTED.put(header, new WeakReference<>(card));
    }

    /** Lets [card] draw within its box again and [header] take no more touches, when they were marked. */
    static void letDown(View card, @Nullable View header) {
        if (ABOVE.remove(card) != null) card.invalidate();
        if (header != null) LIFTED.remove(header);
    }

    /** Lets every card draw within its box again and every header go. */
    static void letDownAll() {
        for (View card : new ArrayList<>(ABOVE.keySet())) {
            if (card != null) card.invalidate();
        }
        ABOVE.clear();
        LIFTED.clear();
        pressed = new WeakReference<>(null);
    }

    /**
     * The highest spot in the window [header] can draw up to (#902): the bottom of the system's bars
     * at the top, [barsTop], unless one of the views it sits in, starting with its parent, clips to its
     * outline, its clip bounds or its top padding below that, which then is the ceiling. The views
     * between it and the first one reaching above the ceiling go in [clipping], the ones that have to
     * stop clipping their insides for the header to show above them.
     */
    static int ceiling(View header, int barsTop, List<ViewGroup> clipping) {
        int[] at = new int[2];
        for (ViewParent parent = header.getParent(); parent instanceof ViewGroup; parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            group.getLocationInWindow(at);
            int top = at[1];
            if (top <= barsTop) return barsTop;
            if (group.getClipToOutline() || group.getClipBounds() != null) return top;
            if (group.getClipToPadding() && group.getPaddingTop() > 0) return top + group.getPaddingTop();
            clipping.add(group);
        }
        return barsTop;
    }

    /** How far down from [view]'s top the first thing it draws starts, or 0 for a view that draws itself. */
    static int drawnTop(View view) {
        if (!(view instanceof ViewGroup)) return 0;
        ViewGroup group = (ViewGroup) view;
        int highest = Integer.MAX_VALUE;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getHeight() <= 0) continue;
            highest = Math.min(highest, child.getTop());
        }
        return highest == Integer.MAX_VALUE ? 0 : highest;
    }

    /** How far down from the window's top the system's bars and any cutout reach, in pixels. */
    @SuppressWarnings("deprecation")
    static int barsTop(View view) {
        if (barsTopForTests >= 0) return barsTopForTests;
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return bars.top;
        }
        return insets.getSystemWindowInsetTop();
    }

    /** How far up from the window's bottom the system's bars and any keyboard reach, in pixels. */
    @SuppressWarnings("deprecation")
    static int barsBottom(View view) {
        if (barsBottomForTests >= 0) return barsBottomForTests;
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            return bars.bottom;
        }
        return insets.getSystemWindowInsetBottom();
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

    /** Lets go of the keeper without putting anything back, and forgets the ids, the reader and the bars. */
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
        loggedOff = false;
        loggedDrawn = false;
        loggedTapped = false;
        ABOVE.clear();
        LIFTED.clear();
        ownPass = null;
        pressed = new WeakReference<>(null);
        barsTopForTests = -1;
        barsBottomForTests = -1;
    }

    /** How far above [card]'s top its children draw, or null when its header isn't up. */
    @Nullable
    static Integer aboveForTests(View card) {
        return ABOVE.get(card);
    }

    static void readerForTests(Reader stand) {
        reader = stand;
    }

    static void putIdForTests(Context context, String name, int id) {
        IDS.putForTests(Utils.resourcePackage(context), name, id);
    }

    static void barsForTests(int top, int bottom) {
        barsTopForTests = top;
        barsBottomForTests = bottom;
    }

    static boolean keptForTests() {
        return current != null;
    }
}
