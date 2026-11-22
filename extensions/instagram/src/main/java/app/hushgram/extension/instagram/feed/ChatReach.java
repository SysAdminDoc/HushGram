/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.ToIntFunction;

import app.hushgram.extension.shared.Logger;

/**
 * Runs a chat's messages on under its glass header and up behind the status bar (#684), the way Home's feed
 * runs under its own ({@link GlassReach}).
 *
 * <p>Instagram lays the messages out below the header, so the glass had only the chat's background to blur
 * and stopped at the header's top edge. Here the view holding the message list, the nearest one a FrameLayout
 * lays out from its top, is pulled up to the window's top (to the header's top when the header isn't right
 * below the status bar), the list is padded by as much so the oldest message still stops where it did, and
 * the parents in between stop clipping. The view holding the messages stays clipped to its own bounds on
 * screen, since Instagram draws it after the header: what runs on under the header is only seen blurred, in
 * the glass, which records the messages too. With the header right below the status bar, every parent from
 * the header's up to the one at the window's top stops clipping as well, so the glass reaches up behind the
 * clock.
 *
 * <p>The list runs on down the same way (#729). Instagram ends it about 33dp above the bottom of the view holding
 * the messages, halfway down the message bar, and that view ends above the navigation bar, where the window shows
 * black. So the message bar's glass had messages behind its top half only, cut off sharp across it, and black
 * under that. The view whose bottom the list shares is stretched down to the window's bottom (to the bottom of
 * the view holding the messages while the keyboard is up), the list padded by as much so the newest message
 * still stops where it did, and the parents down to the one at the window's bottom stop clipping.
 *
 * <p>Nothing moves unless the list starts at the header's bottom edge and the header is where it rests. A
 * view whose parent lays it out where it likes, margin or not, is let go on the first layout and the next one up
 * tried. Everything goes back when the glass comes off or the header is hidden. Main thread only.
 */
final class ChatReach {
    /** How many parents up from the header's the one at the window's top is looked for. */
    private static final int DEPTH = 16;
    /** How many views up from the list the one to pull is looked for, below the view holding the messages. */
    private static final int PULL_DEPTH = 6;

    /** The height of [view]'s window, and of the navigation bar at its bottom. Swapped in tests. */
    static ToIntFunction<View> window = view -> view.getRootView().getHeight();
    static ToIntFunction<View> navigation = ChatReach::navigationBar;

    /** The lists taken down now, by the reach that took each. Weak, so a chat that's gone goes with it. Main thread. */
    private static final Map<View, ChatReach> STRETCHED = new WeakHashMap<>();

    /** The ids of the view beside the header holding the messages, and of the list in it. */
    private final int messagesId;
    private final int listId;

    @Nullable private View messages;
    @Nullable private View list;
    @Nullable private View pulled;
    /** The pulled view's own top margin, the list's own top padding and whether it clipped to it. */
    private int pulledMargin;
    private int listPadding;
    private boolean listClipped;
    /** The clip the messages' view had before this one, put back with it. */
    @Nullable private Rect messagesClip;
    /** How far the list was taken up. */
    private int by;
    /** The parents this reach told not to clip. */
    private final List<ViewGroup> unclipped = new ArrayList<>();
    /** How many of the views that could be pulled didn't move when they were, and whether this one did. */
    private int skipped;
    private boolean checked;
    private boolean failed;
    private boolean described;
    /** How far the glass reaches above the header, behind the status bar, 0 while it doesn't. */
    int gap;
    /** Whether a margin or padding was set since {@link #moved()} was last asked, which only the next layout takes. */
    private boolean moved;

    /** The view stretched down, whose bottom the list shares, with its own bottom margin, and the list's own bottom padding. */
    @Nullable private View stretched;
    private int stretchedMargin;
    private int listBottomPadding;
    /** How far the list was taken down, and how much of that is behind the navigation bar. */
    private int down;
    private int beneath;
    /** Whether the parents down to the window's bottom stopped clipping, and whether the stretch was checked or let go. */
    private boolean unclippedBelow;
    private boolean checkedDown;
    private boolean failedDown;

    ChatReach(int messagesId, int listId) {
        this.messagesId = messagesId;
        this.listId = listId;
    }

    /**
     * [bottom], the padding Instagram's helper is setting at the bottom of [view], with the room this gives the list
     * when [view] is one taken down. Instagram pads a chat's messages again each time the message bar changes height
     * and scrolls them by the difference to keep the newest in view, so without the room in it the messages dropped
     * behind the bar until it was put back, and could stay there (#528, #757).
     */
    static int bottomPadding(@Nullable View view, int bottom) {
        ChatReach reach = view == null || STRETCHED.isEmpty() ? null : STRETCHED.get(view);
        if (reach == null || reach.list != view) return bottom;
        reach.listBottomPadding = bottom;
        return bottom + reach.down;
    }

    /** [bottom], [view]'s bottom padding as read, as Instagram set it: without the room this gives a list taken down. */
    static int ownBottomPadding(@Nullable View view, int bottom) {
        ChatReach reach = view == null || STRETCHED.isEmpty() ? null : STRETCHED.get(view);
        if (reach == null || reach.list != view || bottom != reach.listBottomPadding + reach.down) return bottom;
        return reach.listBottomPadding;
    }

    static int navigationBar(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0;
        return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
    }

    /**
     * Whether this set a margin or padding since last asked. The frame laid out before it would show the messages
     * where Instagram put them, a line or more off for that one frame (#757), so that frame is better not drawn.
     */
    boolean moved() {
        boolean was = moved;
        moved = false;
        return was;
    }

    /** Sets [view]'s layout params, which only the next layout takes. */
    private void relay(View view, ViewGroup.LayoutParams params) {
        view.setLayoutParams(params);
        moved = true;
    }

    /** Sets [view]'s top and bottom padding, which only the next layout takes. */
    private void pad(View view, int top, int bottom) {
        view.setPadding(view.getPaddingLeft(), top, view.getPaddingRight(), bottom);
        moved = true;
    }

    /** The view holding the messages while they run on under the header, else null. */
    @Nullable
    View messages() {
        return pulled == null ? null : messages;
    }

    /** The message list while it runs on under the header, else null. */
    @Nullable
    View list() {
        return pulled == null ? null : list;
    }

    /**
     * Puts each view from [list]'s parent up to [content], [content] too, in [into]. The glass draws those piece by
     * piece and the list in them whole, so what runs on above [content]'s top is in it. Drawn whole, a parent
     * cuts what it holds to its own bounds, whatever its clipChildren says, when it has a clip rectangle, an
     * outline that clips or an alpha layer: on a phone the glass showed the messages from the header's bottom
     * edge down only.
     */
    static void pathTo(View list, View content, Set<View> into) {
        ViewParent up = list.getParent();
        for (int depth = 0; up instanceof ViewGroup && depth < DEPTH; depth++) {
            into.add((View) up);
            if (up == content) return;
            up = up.getParent();
        }
    }

    /** Whatever from [list]'s parent up to [content] cuts what it holds to its own bounds, for the debug log. */
    static String cuts(View list, View content) {
        StringBuilder found = new StringBuilder();
        ViewParent up = list.getParent();
        for (int depth = 0; up instanceof ViewGroup && depth < DEPTH; depth++) {
            View view = (View) up;
            String cut = (view.getClipBounds() != null ? " clip " + view.getClipBounds().toShortString() : "")
                    + (view.getClipToOutline() ? " outline" : "")
                    + (view.getLayerType() != View.LAYER_TYPE_NONE ? " layer " + view.getLayerType() : "")
                    + (view.getAlpha() < 1f ? " alpha " + view.getAlpha() : "");
            if (!cut.isEmpty()) found.append(view.getClass().getSimpleName()).append(cut).append(", ");
            if (up == content) break;
            up = up.getParent();
        }
        return found.length() == 0 ? "nothing" : found.toString();
    }

    /**
     * Runs the messages, in the view after [index] in [source] with the messages' id, on under [header], or
     * keeps them there, and says how far above the header the glass reaches: the status bar's height, or 0.
     */
    int reach(View header, ViewGroup source, int index) {
        if (pulled != null) {
            if (holds(header)) return gap;
            retreat();
            return 0;
        }
        if (failed || index < 0 || !header.isShown()) return 0;
        View content = messagesIn(source, index);
        if (content == null) return 0;
        View found = content.findViewById(listId);
        if (found == null || !found.isShown()) return 0;
        int headerTop = windowTop(header);
        int contentTop = windowTop(content);
        if (windowTop(found) != contentTop || Math.abs(contentTop - headerTop - header.getHeight()) > 1) return 0;
        int status = GlassReach.insets.applyAsInt(source);
        boolean belowStatus = status > 0 && headerTop == status;
        // Anywhere else, the chat is still sliding in.
        if (!belowStatus && headerTop != 0) return 0;
        View pull = pullable(found, content, skipped);
        if (pull == null) {
            failed = true;
            Logger.printDebug(() -> "Chat glass: no view to pull the messages up by, " + GlassReach.parents(found));
            return 0;
        }
        View top = belowStatus ? frameAbove(header, status) : null;
        int up = contentTop - (top != null ? 0 : headerTop);
        String cut = described ? "" : cuts(found, content);
        unclipBetween(pull, content);
        if (top != null) unclipBetween(header, top);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) pull.getLayoutParams();
        pulledMargin = params.topMargin;
        params.topMargin -= up;
        relay(pull, params);
        listPadding = found.getPaddingTop();
        listClipped = !(found instanceof ViewGroup) || ((ViewGroup) found).getClipToPadding();
        pad(found, listPadding + up, found.getPaddingBottom());
        if (found instanceof ViewGroup) ((ViewGroup) found).setClipToPadding(false);
        messagesClip = content.getClipBounds();
        messages = content;
        list = found;
        pulled = pull;
        by = up;
        gap = top != null ? status : 0;
        View stretch = failedDown ? null : stretchable(found, content);
        if (stretch != null) {
            unclipBetween(stretch, content, true);
            stretched = stretch;
            stretchedMargin = ((ViewGroup.MarginLayoutParams) stretch.getLayoutParams()).bottomMargin;
            listBottomPadding = found.getPaddingBottom();
            reachDown(stretch, found, content);
        }
        clipToBounds(content, beneath);
        if (!described) {
            described = true;
            String stretching = stretch == null ? "nothing to stretch down" : "taken down " + down + " on "
                    + stretch.getClass().getSimpleName() + ", behind the navigation bar " + beneath;
            Logger.printDebug(() -> "Chat glass: messages pulled up " + up + " on " + pull.getClass().getSimpleName()
                    + ", behind the status bar " + gap + ", " + stretching + ", cut short by " + cut);
        }
        return gap;
    }

    /**
     * Stretches [stretch], and [found] in it, down to the bottom of [content], or on to the window's bottom when
     * only the navigation bar is below [content], padding the list by as much. Puts back the margin and padding
     * Instagram set again since. Says how far down it goes.
     */
    private void reachDown(View stretch, View found, View content) {
        if (!(stretch.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) || !(stretch.getParent() instanceof View)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) stretch.getLayoutParams();
        if (params.bottomMargin != stretchedMargin - down) stretchedMargin = params.bottomMargin;
        if (found.getPaddingBottom() != listBottomPadding + down) listBottomPadding = found.getPaddingBottom();
        View parent = (View) stretch.getParent();
        int natural = windowTop(parent) + parent.getHeight() - parent.getPaddingBottom() - stretchedMargin;
        int bottom = windowTop(content) + content.getHeight();
        int below = window.applyAsInt(content) - bottom;
        int under = below > 0 && below <= navigation.applyAsInt(content) + 1 ? below : 0;
        if (under > 0 && !unclippedBelow) {
            View frame = frameBelow(content, bottom + under);
            if (frame != null) {
                unclipBetween(content, frame, true);
                unclippedBelow = true;
            } else {
                under = 0;
            }
        }
        beneath = under;
        int want = Math.max(0, bottom + under - natural);
        if (params.bottomMargin != stretchedMargin - want) {
            params.bottomMargin = stretchedMargin - want;
            relay(stretch, params);
        }
        if (found.getPaddingBottom() != listBottomPadding + want) pad(found, found.getPaddingTop(), listBottomPadding + want);
        down = want;
        if (want > 0) {
            STRETCHED.put(found, this);
        } else if (STRETCHED.get(found) == this) {
            STRETCHED.remove(found);
        }
    }

    /** Puts the stretched view's margin and the list's bottom padding back as Instagram had them. */
    private void letDown() {
        View stretch = stretched;
        if (stretch != null && stretch.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) stretch.getLayoutParams();
            if (params.bottomMargin == stretchedMargin - down) {
                params.bottomMargin = stretchedMargin;
                relay(stretch, params);
            }
        }
        View found = list;
        if (stretch != null && found != null && found.getPaddingBottom() == listBottomPadding + down) {
            pad(found, found.getPaddingTop(), listBottomPadding);
        }
        if (found != null && STRETCHED.get(found) == this) STRETCHED.remove(found);
        stretched = null;
        down = 0;
        beneath = 0;
        checkedDown = false;
    }

    /**
     * Whether the messages still run on under [header], putting back what Instagram set again since: a clip on
     * a parent, the list's padding, the pull's margin. False when the header is hidden, or the list didn't go
     * up once laid out, and then the next view up is tried.
     */
    private boolean holds(View header) {
        View pull = pulled;
        View found = list;
        View content = messages;
        if (pull == null || found == null || content == null || !header.isShown() || !found.isAttachedToWindow()
                || !(pull.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return false;
        for (ViewGroup group : unclipped) {
            if (group.getClipChildren()) group.setClipChildren(false);
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) pull.getLayoutParams();
        if (params.topMargin != pulledMargin - by) {
            pulledMargin = params.topMargin;
            params.topMargin -= by;
            relay(pull, params);
        }
        if (found.getPaddingTop() != listPadding + by) {
            listPadding = found.getPaddingTop();
            pad(found, listPadding + by, found.getPaddingBottom());
        }
        if (found instanceof ViewGroup && ((ViewGroup) found).getClipToPadding()) ((ViewGroup) found).setClipToPadding(false);
        View stretch = stretched;
        if (stretch != null) {
            reachDown(stretch, found, content);
            if (!checkedDown && !stretch.isLayoutRequested() && !found.isLayoutRequested()) checkDown(stretch);
        }
        clipToBounds(content, beneath);
        // Checked once, on the first layout since the pull: an animation moving the list later isn't the parent.
        if (checked || pull.isLayoutRequested() || found.isLayoutRequested()) return true;
        ViewParent parent = pull.getParent();
        if (parent instanceof View && pull.getTop() == ((View) parent).getPaddingTop() + params.topMargin) {
            checked = true;
            return true;
        }
        skipped++;
        Logger.printDebug(() -> "Chat glass: " + pull.getClass().getSimpleName() + " didn't move, trying the next view up");
        return false;
    }

    /**
     * Checks once, laid out since, that [stretch]'s parent took its bottom margin. One laying it out where it
     * likes leaves the list where Instagram had it at the bottom, while the top stays under the header.
     */
    private void checkDown(View stretch) {
        checkedDown = true;
        ViewParent parent = stretch.getParent();
        if (!(parent instanceof View) || !(stretch.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        int margin = ((ViewGroup.MarginLayoutParams) stretch.getLayoutParams()).bottomMargin;
        View holding = (View) parent;
        if (stretch.getBottom() == holding.getHeight() - holding.getPaddingBottom() - margin) return;
        failedDown = true;
        letDown();
        Logger.printDebug(() -> "Chat glass: " + stretch.getClass().getSimpleName() + " didn't stretch, the list keeps its bottom");
    }

    /** Puts everything back as Instagram had it, leaving what Instagram set itself since. */
    void retreat() {
        letDown();
        unclippedBelow = false;
        View pull = pulled;
        if (pull != null && pull.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) pull.getLayoutParams();
            if (params.topMargin == pulledMargin - by) {
                params.topMargin = pulledMargin;
                relay(pull, params);
            }
        }
        View found = list;
        if (found != null) {
            if (found.getPaddingTop() == listPadding + by) pad(found, listPadding, found.getPaddingBottom());
            if (found instanceof ViewGroup) ((ViewGroup) found).setClipToPadding(listClipped);
        }
        View content = messages;
        if (content != null && pull != null) content.setClipBounds(messagesClip);
        for (ViewGroup group : unclipped) GlassReach.clipAgain(group);
        unclipped.clear();
        checked = false;
        pulled = null;
        list = null;
        messages = null;
        messagesClip = null;
        by = 0;
        gap = 0;
    }

    /** The view after [index] in [source] with the messages' id, showing, or null. */
    @Nullable
    private View messagesIn(ViewGroup source, int index) {
        if (messagesId == 0 || listId == 0) return null;
        for (int i = index + 1, n = source.getChildCount(); i < n; i++) {
            View child = source.getChildAt(i);
            if (child.getId() == messagesId && child.isShown()) return child;
        }
        return null;
    }

    /**
     * The view from [list] up to, not counting, [content] that a FrameLayout lays out filling it from its top, past
     * the first [skip] of them, or null when there's none left. Only through FrameLayouts: pulled up in any other
     * parent, a view could take the ones laid out beside it, a banner over the messages, up under the header too.
     */
    @Nullable
    static View pullable(View list, View content, int skip) {
        View at = list;
        for (int depth = 0; depth < PULL_DEPTH && at != content; depth++) {
            ViewParent up = at.getParent();
            if (!(up instanceof FrameLayout) || !(at.getLayoutParams() instanceof FrameLayout.LayoutParams)) return null;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) at.getLayoutParams();
            boolean fromTop = params.gravity == -1 || (params.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP;
            if (params.height == ViewGroup.LayoutParams.MATCH_PARENT && fromTop && skip-- == 0) return at;
            at = (View) up;
        }
        return null;
    }

    /**
     * The parent of [header] at the window's top, when [header] starts [status] below it and every view between
     * them is laid out from their parent's top. Null otherwise.
     */
    @Nullable
    private static View frameAbove(View header, int status) {
        View view = header;
        for (int depth = 0; depth < DEPTH + GlassTopBar.HOST_DEPTH; depth++) {
            ViewParent up = view.getParent();
            if (!(up instanceof ViewGroup)) return null;
            view = (View) up;
            int top = windowTop(view);
            if (top == 0) return view;
            if (top != status) return null;
        }
        return null;
    }

    /** Every parent from [from]'s up to [top], [top] too, stops clipping. */
    private void unclipBetween(View from, View top) {
        unclipBetween(from, top, false);
    }

    /** {@link #unclipBetween(View, View)}, and with [bottom], each with bottom padding lets the list draw over it too. */
    private void unclipBetween(View from, View top, boolean bottom) {
        for (View at = (View) from.getParent(); at instanceof ViewGroup; at = (View) at.getParent()) {
            ViewGroup group = (ViewGroup) at;
            if (!unclipped.contains(group)) {
                unclipped.add(group);
                GlassReach.stopClipping(group);
            }
            if (bottom && group.getPaddingBottom() > 0 && group.getClipToPadding()) group.setClipToPadding(false);
            if (at == top || !(at.getParent() instanceof View)) break;
        }
    }

    /**
     * The view holding the messages, down to the bottom of [list]'s parent, that a FrameLayout lays out filling it,
     * from [list] up to, not counting, [content]: stretched down, it takes the list down with it. Null when the list
     * isn't laid out like that.
     */
    @Nullable
    static View stretchable(View list, View content) {
        int bottom = windowTop(list) + list.getHeight();
        View found = null;
        View at = list;
        for (int depth = 0; depth < PULL_DEPTH && at != content; depth++) {
            ViewParent up = at.getParent();
            if (!(up instanceof FrameLayout) || !(at.getLayoutParams() instanceof FrameLayout.LayoutParams)) break;
            if (at.getLayoutParams().height != ViewGroup.LayoutParams.MATCH_PARENT || windowTop(at) + at.getHeight() != bottom) break;
            found = at;
            at = (View) up;
        }
        return found;
    }

    /** The parent of [content] reaching down to [bottom] in the window, or null. */
    @Nullable
    private static View frameBelow(View content, int bottom) {
        View view = content;
        for (int depth = 0; depth < DEPTH + GlassTopBar.HOST_DEPTH; depth++) {
            ViewParent up = view.getParent();
            if (!(up instanceof ViewGroup)) return null;
            view = (View) up;
            if (windowTop(view) + view.getHeight() >= bottom) return view;
        }
        return null;
    }

    /** Keeps [content] drawing inside its own bounds, and [beneath] more below them, wherever the list in it runs. */
    private static void clipToBounds(View content, int beneath) {
        int wide = content.getWidth();
        int tall = content.getHeight() + beneath;
        Rect now = content.getClipBounds();
        if (now != null && now.left == 0 && now.top == 0 && now.right == wide && now.bottom == tall) return;
        content.setClipBounds(new Rect(0, 0, wide, tall));
    }

    private static int windowTop(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1];
    }
}
