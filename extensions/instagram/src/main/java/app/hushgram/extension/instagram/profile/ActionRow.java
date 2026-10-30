/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The row of action buttons on a profile (Follow, Message, Edit profile, Share profile), for the
 * hooks that hide one of its buttons: the person-plus button ({@link ProfileSuggestions}) and Share
 * profile ({@link ShareProfileButton}). It hides a button or gives one it hid its own visibility
 * back, and spreads the row's other text buttons over the space hidden buttons leave.
 */
final class ActionRow {
    /**
     * The tags Instagram gives the icon buttons of a profile's action row, the ones it sizes apart
     * from the text buttons: the person-plus button, Saved and the overflow menu.
     */
    static final Set<String> ICON_TAGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "chaining_icon", "saved", "overflow_menu")));

    /** What's counted when the row's text buttons take the hidden buttons' space, and when they give it back. */
    static final String ROW_SPREAD = "action buttons spread over the hidden button's space";
    static final String ROW_PUT_BACK = "action buttons put back to Instagram's widths";

    /**
     * The buttons this class hid, with the visibility each had. Instagram reuses the buttons between
     * binds and never sets their visibility itself, so a button hidden earlier gets its own
     * visibility back once it's shown again. Weak, so a button gone from the screen isn't kept.
     */
    private static final Map<View, Integer> hidden = new WeakHashMap<>();

    /** The params this class weighted, so replacement native weights aren't taken back. Weak, as {@link #hidden} is. */
    private static final Map<View, LinearLayout.LayoutParams> spread = new WeakHashMap<>();

    /** The buttons whose row is fitted before the next frame, so a second bind before it adds no second listener. */
    private static final Set<View> waiting = Collections.newSetFromMap(new WeakHashMap<>());

    private ActionRow() {
    }

    /**
     * Hides [button] when [hide] says so. Otherwise a button this hid before gets its old visibility
     * back, and any other is left as it is. When that changed anything, the row is fitted before its
     * next frame, with the counts going to [family]. Throws what the button throws.
     */
    static void hide(View button, boolean hide, String family) {
        boolean changed;
        synchronized (hidden) {
            if (hide) {
                int was = button.getVisibility();
                button.setVisibility(View.GONE);
                // Only the first hide records it: a later one would record GONE as its own.
                if (!hidden.containsKey(button)) hidden.put(button, was);
                changed = true;
            } else {
                Integer was = hidden.remove(button);
                if (was != null) button.setVisibility(was);
                changed = was != null;
            }
        }
        // The button isn't in the row yet: Instagram empties the row and adds each button back
        // after setting it up. The fit waits until it's attached there again.
        if (changed) fitBeforeNextFrame(button, family);
    }

    /**
     * Fits [button]'s row right before the next frame draws. A refresh rebinds the row and asks for a
     * layout before the button is set up, and that layout holds back anything posted until it has
     * drawn, so a posted fit let one frame show Instagram's widths each time and the buttons twitched
     * (#220). A fit that changes a weight skips that frame, and the row is laid out again first.
     */
    private static void fitBeforeNextFrame(View button, String family) {
        synchronized (waiting) {
            if (!waiting.add(button)) return;
        }
        ViewTreeObserver added = button.getViewTreeObserver();
        added.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                synchronized (waiting) {
                    waiting.remove(button);
                }
                // A button added while detached had its listener moved to the window's observer.
                ViewTreeObserver observer = added.isAlive() ? added : button.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                return !fitRow(button, family);
            }
        });
    }

    /**
     * Spreads the action row's visible text buttons over the space the buttons this hid leave (#87,
     * #253), or takes that back once none is hidden. Answers whether a weight changed, counting that
     * for [family]. Never throws.
     *
     * <p>Instagram doesn't let the row's buttons share it: it works out a fixed width for each text
     * button (Follow, Message, Edit profile, Share profile) from the row's width, how many there are,
     * and whether an icon button sits beside them, which keeps its own fixed width. The icon buttons
     * are the ones it tags {@link #ICON_TAGS}. So with a button gone the others keep widths that leave
     * room for it, and a gap sits in the row. Each visible text button here gets a weight of 1, so the
     * row hands them that space in equal parts on top of Instagram's widths. Where there's no space
     * left over, a weight changes nothing, and Instagram's own widths keep working as they did.
     */
    static boolean fitRow(View button, String family) {
        try {
            if (!(button.getParent() instanceof LinearLayout)) return false;
            LinearLayout row = (LinearLayout) button.getParent();
            if (row.getOrientation() != LinearLayout.HORIZONTAL) return false;
            boolean gone = false;
            synchronized (hidden) {
                for (int i = 0; i < row.getChildCount() && !gone; i++) {
                    View child = row.getChildAt(i);
                    gone = child != null && hidden.containsKey(child) && child.getVisibility() == View.GONE;
                }
            }
            int moved = 0;
            synchronized (spread) {
                for (int i = 0; i < row.getChildCount(); i++) {
                    View child = row.getChildAt(i);
                    if (child == null || isIcon(child)) continue;
                    if (!(child.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
                    LinearLayout.LayoutParams owned = spread.get(child);
                    if (owned != null && (owned != params || params.weight != 1)) {
                        // A native rebind replaced the params or changed their weight.
                        spread.remove(child);
                    }
                    if (gone && child.getVisibility() != View.GONE && params.weight == 0) {
                        params.weight = 1;
                        spread.put(child, params);
                        moved++;
                    } else if (!gone && spread.remove(child) == params) {
                        params.weight = 0;
                        moved++;
                    }
                }
            }
            if (moved == 0) return false;
            row.requestLayout();
            HookStatus.counted(family, gone ? ROW_SPREAD : ROW_PUT_BACK);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(family, ROW_SPREAD, failure);
            return false;
        }
    }

    /** Whether Instagram counts [child] as one of the row's icon buttons, by the tag it gives it. */
    private static boolean isIcon(View child) {
        Object tag = child.getTag();
        return tag != null && ICON_TAGS.contains(tag.toString());
    }
}
