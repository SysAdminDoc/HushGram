/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.download.FeedDownloadButton;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keeps a chat's header readable with HushGram's buttons in it (#802).
 *
 * <p>HushGram puts up to three buttons first in Instagram's row of call buttons: the check mark of
 * {@link MarkReadButton}, the eye of {@link SeenChoiceButton} and the menu of {@link ChatPager}. A phone
 * about 384dp wide fits the back arrow, the picture and Instagram's own three buttons with little to
 * spare, so three more pushed the chat's name out of the bar and the last call button past the screen's
 * edge, where it was cut off. Before the first frame after the row is laid out again, this works out the
 * room the row and the name share. When two or more of ours would leave the name less than
 * {@link #NAME_ROOM_DP}, or less than Instagram's own buttons leave it when that's already smaller, they
 * fold into one More button whose menu holds everything they do. With room again they come back.
 *
 * <p>Every width is the one a button asks for, not the one the row gave it, and the room is read from
 * where Instagram laid the name and the row out, translations left out, so the answer never depends on
 * the answer before it and the bar doesn't flip between the two. Each of ours says whether it's wanted
 * through {@link #show}, and stays hidden while the row is folded. With no name in the header nothing
 * folds, as before.
 */
final class ChatHeaderFit {
    static final String MORE_TAG = "hushgram_chat_more";
    /** Instagram's glyphs for More, by name since the numbers change; the first one found is used. */
    static final String[] MORE_ICONS = {"instagram_more_vertical_pano_outline_24", "instagram_more_vertical_outline_24",
            "instagram_more_horizontal_pano_outline_24", "instagram_more_horizontal_outline_24"};
    /** Our buttons, in the order the More menu lists what they do. */
    static final String[] OURS = {MarkReadButton.TAG, SeenChoiceButton.TAG, ChatPager.BUTTON_TAG};
    /** The room the chat's name keeps beside the buttons. */
    static final int NAME_ROOM_DP = 80;
    static final String STEP = "chat header fit";
    /** Fits in a row, each changing it, past which the row is left as it is until a fit changes nothing. */
    private static final int MOST_CHANGES = 3;
    private static final int GROUP_ACTIONS = 0;
    private static final int GROUP_SEEN = 1;
    private static final int GROUP_PAGER = 2;

    /** Whether each of our buttons is wanted, apart from the row being folded. */
    private static final Map<View, Boolean> WANTED = new WeakHashMap<>();
    /** The rows watched for layout. */
    private static final Map<View, Boolean> WATCHED = new WeakHashMap<>();
    /** The rows with a fit waiting for their next frame. */
    private static final Map<View, Boolean> PENDING = new WeakHashMap<>();
    /** Fits in a row that changed something in a row, for {@link #MOST_CHANGES}. */
    private static final Map<View, Integer> CHANGES = new WeakHashMap<>();
    private static volatile WeakReference<PopupMenu> shown = new WeakReference<>(null);

    private static final View.OnLayoutChangeListener LAID_OUT = new View.OnLayoutChangeListener() {
        @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                             int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (v instanceof LinearLayout) dirty((LinearLayout) v);
        }
    };

    private static final View.OnClickListener MORE_TAP = new View.OnClickListener() {
        @Override public void onClick(View v) {
            try {
                openMore(v);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
            }
        }
    };

    private static final View.OnAttachStateChangeListener NO_KEEP = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {
        }

        @Override public void onViewDetachedFromWindow(View v) {
        }
    };

    private ChatHeaderFit() {
    }

    /** Called as a button of ours goes into [row]: the row is watched from then on and fitted before its next frame. */
    static void watch(LinearLayout row) {
        if (WATCHED.put(row, Boolean.TRUE) == null) row.addOnLayoutChangeListener(LAID_OUT);
        dirty(row);
    }

    /** Says whether [button] is wanted, and shows it, or keeps it hidden while its row is folded into More. */
    static void show(View button, boolean wanted) {
        WANTED.put(button, wanted);
        ViewParent parent = button.getParent();
        LinearLayout row = parent instanceof LinearLayout ? (LinearLayout) parent : null;
        int visibility = wanted && (row == null || !folded(row)) ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != visibility) button.setVisibility(visibility);
        if (row != null) dirty(row);
    }

    /** Whether [row] shows More in place of our buttons. */
    static boolean folded(LinearLayout row) {
        View more = child(row, MORE_TAG);
        return more != null && more.getVisibility() == View.VISIBLE;
    }

    /** Fits [row] before its next frame, once however many times it's asked. */
    static void dirty(final LinearLayout row) {
        if (PENDING.put(row, Boolean.TRUE) != null) return;
        row.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver observer = row.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                PENDING.remove(row);
                return !fitChanged(row);
            }
        });
    }

    /** Fits [row], counting changes so two answers can't take turns forever. True when it changed something. */
    private static boolean fitChanged(LinearLayout row) {
        try {
            Integer before = CHANGES.get(row);
            int changes = before == null ? 0 : before;
            if (changes >= MOST_CHANGES) {
                CHANGES.remove(row);
                log("left the header as it is after " + changes + " changes in a row");
                return false;
            }
            boolean changed = fit(row);
            if (changed) CHANGES.put(row, changes + 1);
            else CHANGES.remove(row);
            return changed;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
            return false;
        }
    }

    /**
     * Folds our buttons in [row] into More when they'd crowd the name or the call buttons, and unfolds them
     * when there's room. Takes More out once none of ours is left. True when it changed something.
     */
    static boolean fit(LinearLayout row) {
        List<View> ours = new ArrayList<>();
        List<View> wanted = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!isOurs(child)) continue;
            ours.add(child);
            if (wanted(child)) wanted.add(child);
        }
        View more = child(row, MORE_TAG);
        if (ours.isEmpty()) {
            if (more == null) return false;
            row.removeView(more);
            dismiss();
            return true;
        }
        if (row.getWidth() <= 0) return false;
        boolean fold = wanted.size() >= 2 && crowded(row, wanted, more);
        boolean changed = false;
        if (fold && more == null) {
            more = makeMore(row);
            if (more == null) fold = false;
            else changed = true;
        }
        for (View button : ours) changed |= setShown(button, !fold && wanted(button));
        if (more != null) {
            boolean was = more.getVisibility() == View.VISIBLE;
            changed |= setShown(more, fold);
            if (was != fold) {
                if (!fold) dismiss();
                final String how = fold ? "folded " + wanted.size() + " buttons into More" : "unfolded, the bar has room";
                log(how);
            }
        }
        return changed;
    }

    /**
     * Whether [wanted], each as wide as it asks, would leave the chat's name less room than it should get,
     * or wouldn't fit beside Instagram's own buttons at all.
     */
    static boolean crowded(LinearLayout row, List<View> wanted, @Nullable View more) {
        int room = room(row);
        if (room < 0) return false;
        int theirs = row.getPaddingLeft() + row.getPaddingRight();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child == more || isOurs(child) || child.getVisibility() == View.GONE) continue;
            theirs += asked(child);
        }
        int ourWidth = 0;
        for (View button : wanted) ourWidth += asked(button);
        int floor = Math.min(dp(row, NAME_ROOM_DP), Math.max(0, room - theirs));
        boolean crowded = theirs + ourWidth + floor > room;
        if (crowded != folded(row)) {
            final String line = String.format(Locale.US, "room %d, Instagram's buttons %d, ours %d, the name keeps %d",
                    room, theirs, ourWidth, floor);
            log(line);
        }
        return crowded;
    }

    /**
     * The room the row and the chat's name share, from the start of the name's area to the far end of the
     * row (the other way round right to left), or -1 when the header shows no name. Positions are where
     * Instagram laid the views out, without translations, so a name moved to the middle doesn't change it.
     */
    static int room(LinearLayout row) {
        View name = ChatPager.nearest(row, ChatPager.NAME_AREA);
        if (name == null || name == row || name.getVisibility() == View.GONE || !name.isLaidOut()) return -1;
        int rowStart = laidOutLeft(row);
        int rowEnd = rowStart + row.getWidth();
        int nameStart = laidOutLeft(name);
        int nameEnd = nameStart + name.getWidth();
        return rowStart + rowEnd >= nameStart + nameEnd ? rowEnd - nameStart : nameEnd - rowStart;
    }

    /** Where [view]'s left edge is laid out in its window, scrolling counted and translations not. */
    static int laidOutLeft(View view) {
        int x = 0;
        View at = view;
        while (true) {
            x += at.getLeft();
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return x;
            at = (View) parent;
            x -= at.getScrollX();
        }
    }

    /** How wide [child] asks to be in its row, margins counted, even when the row gave it less or it's hidden. */
    static int asked(View child) {
        ViewGroup.LayoutParams params = child.getLayoutParams();
        int margins = 0;
        if (params instanceof ViewGroup.MarginLayoutParams) {
            margins = ((ViewGroup.MarginLayoutParams) params).leftMargin + ((ViewGroup.MarginLayoutParams) params).rightMargin;
        }
        if (params != null && params.width > 0) return params.width + margins;
        if (child.getMeasuredWidth() > 0 && (child.getMeasuredWidthAndState() & View.MEASURED_STATE_TOO_SMALL) == 0) {
            return child.getMeasuredWidth() + margins;
        }
        if (child instanceof ImageView) {
            Drawable glyph = ((ImageView) child).getDrawable();
            if (glyph != null && glyph.getIntrinsicWidth() > 0) {
                return Math.max(glyph.getIntrinsicWidth() + child.getPaddingLeft() + child.getPaddingRight(),
                        child.getMinimumWidth()) + margins;
            }
        }
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        child.measure(unspecified, unspecified);
        return child.getMeasuredWidth() + margins;
    }

    /** More, made like the call buttons and put where ours start, or null when Instagram has no glyph for it. */
    @Nullable
    private static View makeMore(LinearLayout row) {
        Drawable glyph = ChatPager.icon(row.getContext(), MORE_ICONS);
        if (glyph == null) return null;
        ImageView more = ChatPager.addHeaderButton(row, glyph, L10n.t("More"), MORE_TAG, MORE_TAP, NO_KEEP);
        more.setVisibility(View.GONE);
        return more;
    }

    /**
     * Opens what the folded buttons do under More: Mark as read while the chat's receipt is held, the chat's
     * three Seen choices with its own checked, and the chat menu's lines.
     */
    static void openMore(View anchor) {
        ViewParent parent = anchor.getParent();
        if (!(parent instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) parent;
        PopupMenu popup = new PopupMenu(anchor.getContext(), anchor, Gravity.END);
        Menu menu = popup.getMenu();
        final List<Runnable> actions = new ArrayList<>();
        final View check = child(row, MarkReadButton.TAG);
        if (check != null && wanted(check)) {
            MenuItem item = menu.add(GROUP_ACTIONS, actions.size(), actions.size(), L10n.t("Mark as read"));
            actions.add(check::callOnClick);
            Drawable glyph = ChatPager.icon(anchor.getContext(), MarkReadButton.ICONS);
            if (glyph != null) {
                glyph.setTintList(ColorStateList.valueOf(FeedDownloadButton.iconColor(anchor.getContext())));
                item.setIcon(glyph);
            }
        }
        View eye = child(row, SeenChoiceButton.TAG);
        if (eye != null && wanted(eye)) SeenChoiceButton.addChoices(anchor.getContext(), menu, GROUP_SEEN, actions);
        View clock = child(row, ChatPager.BUTTON_TAG);
        if (clock != null && wanted(clock)) ChatPager.addLines(anchor, menu, GROUP_PAGER, actions);
        if (menu.size() == 0) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) popup.setForceShowIcon(true);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                try {
                    if (id >= 0 && id < actions.size()) actions.get(id).run();
                } catch (Throwable t) {
                    HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
                }
                return true;
            }
        });
        popup.setOnDismissListener(new PopupMenu.OnDismissListener() {
            @Override public void onDismiss(PopupMenu closed) {
                if (shown.get() == closed) shown = new WeakReference<>(null);
            }
        });
        shown = new WeakReference<>(popup);
        popup.show();
    }

    private static void dismiss() {
        PopupMenu open = shown.get();
        shown = new WeakReference<>(null);
        if (open != null) open.dismiss();
    }

    private static boolean isOurs(View child) {
        Object tag = child.getTag();
        for (String ours : OURS) {
            if (ours.equals(tag)) return true;
        }
        return false;
    }

    /** Whether [button] is wanted; one that never said is wanted while it shows. */
    private static boolean wanted(View button) {
        Boolean said = WANTED.get(button);
        return said != null ? said : button.getVisibility() == View.VISIBLE;
    }

    @Nullable
    private static View child(LinearLayout row, String tag) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (tag.equals(child.getTag())) return child;
        }
        return null;
    }

    private static boolean setShown(View view, boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (view.getVisibility() == visibility) return false;
        view.setVisibility(visibility);
        return true;
    }

    private static int dp(View view, int dp) {
        return Math.round(dp * view.getResources().getDisplayMetrics().density);
    }

    private static void log(final String line) {
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, STEP, () -> line);
    }

    /** Forgets every row and button. For tests. */
    static void resetForTests() {
        WANTED.clear();
        WATCHED.clear();
        PENDING.clear();
        CHANGES.clear();
        shown = new WeakReference<>(null);
    }
}
