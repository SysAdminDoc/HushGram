/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 * <p>Nothing moves unless the list starts at the header's bottom edge and the header is where it rests. A
 * view whose parent lays it out where it likes, margin or not, is let go on the first layout and the next one up
 * tried. Everything goes back when the glass comes off or the header is hidden. Main thread only.
 */
final class ChatReach {
    /** How many parents up from the header's the one at the window's top is looked for. */
    private static final int DEPTH = 16;
    /** How many views up from the list the one to pull is looked for, below the view holding the messages. */
    private static final int PULL_DEPTH = 6;

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

    ChatReach(int messagesId, int listId) {
        this.messagesId = messagesId;
        this.listId = listId;
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
        pull.setLayoutParams(params);
        listPadding = found.getPaddingTop();
        listClipped = !(found instanceof ViewGroup) || ((ViewGroup) found).getClipToPadding();
        found.setPadding(found.getPaddingLeft(), listPadding + up, found.getPaddingRight(), found.getPaddingBottom());
        if (found instanceof ViewGroup) ((ViewGroup) found).setClipToPadding(false);
        messagesClip = content.getClipBounds();
        clipToBounds(content);
        messages = content;
        list = found;
        pulled = pull;
        by = up;
        gap = top != null ? status : 0;
        if (!described) {
            described = true;
            Logger.printDebug(() -> "Chat glass: messages pulled up " + up + " on " + pull.getClass().getSimpleName()
                    + ", behind the status bar " + gap + ", cut short by " + cut);
        }
        return gap;
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
            pull.setLayoutParams(params);
        }
        if (found.getPaddingTop() != listPadding + by) {
            listPadding = found.getPaddingTop();
            found.setPadding(found.getPaddingLeft(), listPadding + by, found.getPaddingRight(), found.getPaddingBottom());
        }
        if (found instanceof ViewGroup && ((ViewGroup) found).getClipToPadding()) ((ViewGroup) found).setClipToPadding(false);
        clipToBounds(content);
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

    /** Puts everything back as Instagram had it, leaving what Instagram set itself since. */
    void retreat() {
        View pull = pulled;
        if (pull != null && pull.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) pull.getLayoutParams();
            if (params.topMargin == pulledMargin - by) {
                params.topMargin = pulledMargin;
                pull.setLayoutParams(params);
            }
        }
        View found = list;
        if (found != null) {
            if (found.getPaddingTop() == listPadding + by) {
                found.setPadding(found.getPaddingLeft(), listPadding, found.getPaddingRight(), found.getPaddingBottom());
            }
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
        for (View at = (View) from.getParent(); at instanceof ViewGroup; at = (View) at.getParent()) {
            ViewGroup group = (ViewGroup) at;
            if (!unclipped.contains(group)) {
                unclipped.add(group);
                GlassReach.stopClipping(group);
            }
            if (at == top || !(at.getParent() instanceof View)) break;
        }
    }

    /** Keeps [content] drawing inside its own bounds, wherever the list in it runs. */
    private static void clipToBounds(View content) {
        int wide = content.getWidth();
        int tall = content.getHeight();
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
