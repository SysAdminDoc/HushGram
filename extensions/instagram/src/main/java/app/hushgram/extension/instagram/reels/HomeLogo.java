/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the Hide the Reels tab patch's Tab bar switch that puts Instagram's logo in the middle
 * of Home's header.
 *
 * <p>Instagram's newer header does this itself: it gives the header's two rows of buttons the same
 * width, so the title view between them sits in the middle, and centers the logo and the feed's
 * name inside the title view. The server flags that turn that header on also change the tab bar and
 * the profile, so the patch leaves them alone, and {@link #center} does the same two things once the
 * header has drawn (GhostHeaderButton queues it behind each draw, after HushGram's own header
 * buttons go in):
 *
 * <ul>
 *   <li>the views the title switches between (the logo, the feed's name, and the views it keeps
 *       them in) are centered across it, keeping their vertical gravity. A name not shown yet still
 *       waits in a ViewStub, whose layout params the name takes over when it shows, so it's centered
 *       too;</li>
 *   <li>each row of buttons gets a minimum width so the space from the header's edge to the title
 *       view is the same on both sides, whatever buttons are hidden or added. Where the rows sit
 *       different distances from the header's edges, the nearer one gets that much more.</li>
 * </ul>
 *
 * <p>The widths come from the buttons as the header last measured them, so they're worked out each
 * time the header lays out, which also covers buttons that change between draws. They're only set
 * when they differ, so a layout never asks for another one that changes nothing.
 *
 * <p>Logo and titles at the start (#310) does the opposite for accounts whose header centers the
 * logo itself: the views the title switches between go to its start, keeping their vertical
 * gravity, and both rows' minimum widths are cleared, so the title view begins right after the
 * start row's buttons. It wins over the centering switch.
 *
 * <p>With both switches off, HushGram paused or the settings not read yet, the header is left as it
 * is. What an earlier draw changed goes back at the next draw, and only what HushGram set and nothing
 * has changed since. Anything thrown leaves the header as it is.
 */
public final class HomeLogo {
    /** What's counted each time the logo is centered after a draw. */
    static final String CENTERED = "Home header logo centered";

    /** What's counted each time the logo is put at the start after a draw. */
    static final String AT_START = "Home header logo at the start";

    /** What's counted when the header's rows or title view can't be read. */
    static final String NOT_FOUND = "Home header logo not centered, no rows or title view";

    /** Where the logo goes: left as Instagram drew it, in the middle, or at the start. */
    static final int AS_IS = 0;
    static final int MIDDLE = 1;
    static final int START = 2;

    /** A minimum width HushGram hasn't set. */
    private static final int NONE = -1;

    /** The headers centered so far, with what was changed on each. Main thread only. */
    private static final Map<View, Changes> CHANGED = new WeakHashMap<>();

    /** The title view's class and its LinearLayout fields, looked up once. */
    private static volatile Class<?> heldIn;
    private static volatile Field[] held;

    private static volatile boolean logged;

    private HomeLogo() {
    }

    /** Whether the logo goes in the middle now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return placement() == MIDDLE;
    }

    /**
     * Where the logo goes now: {@link #AS_IS} with HushGram paused or the settings not read,
     * {@link #START} with Logo and titles at the start on, {@link #MIDDLE} with only the centering
     * switch on.
     */
    static int placement() {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return AS_IS;
        if (Settings.TITLES_AT_START.get()) return START;
        return Settings.CENTER_HOME_LOGO.get() ? MIDDLE : AS_IS;
    }

    /**
     * Called on the main thread once Home's header has drawn from its state and HushGram's own header
     * buttons are in. Centers the logo or puts it at the start, as {@link #placement} says, and
     * otherwise puts back what an earlier call changed. Never throws.
     */
    public static void center(View header) {
        try {
            if (header == null) return;
            Changes changes = CHANGED.get(header);
            int placement = placement();
            if (placement == AS_IS) {
                if (changes != null) undo(header, changes);
                return;
            }
            LinearLayout start = HomeHeader.startRowOf(header);
            LinearLayout end = HomeHeader.endRowOf(header);
            ViewGroup title = HomeHeader.titleOf(header);
            if (start == null || end == null || title == null) {
                HookStatus.counted(FamilyNames.REELS_TAB, NOT_FOUND);
                return;
            }
            if (changes == null) {
                changes = new Changes(start.getMinimumWidth(), end.getMinimumWidth());
                CHANGED.put(header, changes);
                header.addOnLayoutChangeListener(changes);
            }
            placeTitle(title, changes, placement);
            if (placement == START) {
                changes.startSet = widen(start, 0);
                changes.endSet = widen(end, 0);
            }
            // The buttons the header just put in haven't been measured yet, so the rows are evened
            // out by the layout check once the layout this asks for has measured them.
            header.requestLayout();
            HookStatus.counted(FamilyNames.REELS_TAB, placement == START ? AT_START : CENTERED);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> placement == START
                        ? "Home header: Instagram's logo is at the start"
                        : "Home header: Instagram's logo is in the middle");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "home header logo", failure);
        }
    }

    /** Centers each view [title] switches between across it, as {@link #placeTitle} does. */
    static void centerTitle(ViewGroup title, Changes changes) throws IllegalAccessException {
        placeTitle(title, changes, MIDDLE);
    }

    /**
     * Centers each view [title] switches between, and each LinearLayout it keeps in a field, across
     * [title], or puts it at [title]'s start, where its layout params are a FrameLayout's. Notes the
     * gravity it had before HushGram changed it, so a switch from one placement to the other still
     * puts back Instagram's.
     */
    static void placeTitle(ViewGroup title, Changes changes, int placement) throws IllegalAccessException {
        List<View> views = new ArrayList<>();
        for (int i = 0; i < title.getChildCount(); i++) views.add(title.getChildAt(i));
        views.addAll(heldBy(title));
        boolean moved = false;
        for (View view : views) {
            if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            int[] noted = changes.gravities.get(params);
            int original = noted != null && params.gravity == noted[1] ? noted[0] : params.gravity;
            int wanted = placement == START ? started(original) : centered(original);
            changes.gravities.put(params, new int[] {original, wanted});
            if (params.gravity != wanted) {
                params.gravity = wanted;
                moved = true;
            }
        }
        if (moved) title.requestLayout();
    }

    /** [gravity] centered across: its vertical part kept, its horizontal part centered. */
    static int centered(int gravity) {
        int vertical = gravity == FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY ? 0 : gravity & Gravity.VERTICAL_GRAVITY_MASK;
        return vertical | Gravity.CENTER_HORIZONTAL;
    }

    /** [gravity] at the start: its vertical part kept, its horizontal part the start. */
    static int started(int gravity) {
        int vertical = gravity == FrameLayout.LayoutParams.UNSPECIFIED_GRAVITY ? 0 : gravity & Gravity.VERTICAL_GRAVITY_MASK;
        return vertical | Gravity.START;
    }

    /** The LinearLayouts [title] keeps in its own fields: on Instagram, the logo's and the feed name's. */
    private static List<View> heldBy(ViewGroup title) throws IllegalAccessException {
        Class<?> type = title.getClass();
        Field[] fields = held;
        if (fields == null || heldIn != type) {
            List<Field> found = new ArrayList<>();
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !LinearLayout.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                found.add(field);
            }
            fields = found.toArray(new Field[0]);
            held = fields;
            heldIn = type;
        }
        List<View> views = new ArrayList<>(fields.length);
        for (Field field : fields) {
            Object view = field.get(title);
            if (view instanceof View) views.add((View) view);
        }
        return views;
    }

    /**
     * Gives [start] and [end] the minimum widths that leave as much room from the header's start edge
     * to the title view as from its end edge. Called as the header lays out. Where the rows haven't
     * been laid out yet, their distances from the header's edges count as equal.
     */
    static void balance(View header, LinearLayout start, LinearLayout end, Changes changes) {
        int startEdge = 0;
        int endEdge = 0;
        if (header.getWidth() > 0 && start.isLaidOut() && end.isLaidOut()) {
            int startLeft = leftIn(start, header);
            int endLeft = leftIn(end, header);
            if (startLeft != NONE && endLeft != NONE) {
                boolean rtl = header.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                startEdge = Math.max(0, rtl ? header.getWidth() - startLeft - start.getWidth() : startLeft);
                endEdge = Math.max(0, rtl ? endLeft : header.getWidth() - endLeft - end.getWidth());
            }
        }
        int outer = Math.max(startEdge + natural(start), endEdge + natural(end));
        changes.startSet = widen(start, outer - startEdge);
        changes.endSet = widen(end, outer - endEdge);
    }

    private static int widen(View row, int width) {
        if (row.getMinimumWidth() != width) row.setMinimumWidth(width);
        return width;
    }

    /** How wide [row]'s own buttons make it, from their last measure, whatever minimum width it has. */
    static int natural(LinearLayout row) {
        boolean across = row.getOrientation() == LinearLayout.HORIZONTAL;
        int buttons = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int width = child.getMeasuredWidth();
            if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                width += margins.leftMargin + margins.rightMargin;
            }
            buttons = across ? buttons + width : Math.max(buttons, width);
        }
        return row.getPaddingLeft() + buttons + row.getPaddingRight();
    }

    /** [view]'s left edge in [header]'s coordinates, or {@link #NONE} when it isn't inside [header]. */
    private static int leftIn(View view, View header) {
        int left = 0;
        View at = view;
        while (at != header) {
            left += at.getLeft();
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return NONE;
            at = (View) parent;
        }
        return left;
    }

    /**
     * Puts back what HushGram changed on [header]: the rows' minimum widths and the title's gravities,
     * each only while it still holds what HushGram set. Stops the layout check.
     */
    private static void undo(View header, Changes changes) {
        header.removeOnLayoutChangeListener(changes);
        CHANGED.remove(header);
        LinearLayout start = HomeHeader.startRowOf(header);
        LinearLayout end = HomeHeader.endRowOf(header);
        if (start != null && changes.startSet != NONE && start.getMinimumWidth() == changes.startSet) {
            start.setMinimumWidth(changes.startOriginal);
        }
        if (end != null && changes.endSet != NONE && end.getMinimumWidth() == changes.endSet) {
            end.setMinimumWidth(changes.endOriginal);
        }
        for (Map.Entry<FrameLayout.LayoutParams, int[]> noted : changes.gravities.entrySet()) {
            if (noted.getKey().gravity == noted.getValue()[1]) noted.getKey().gravity = noted.getValue()[0];
        }
        ViewGroup title = HomeHeader.titleOf(header);
        (title != null ? title : header).requestLayout();
    }

    /**
     * What HushGram changed on one header: the rows' minimum widths before and after, and each title
     * view's layout params' gravity before and after. Also the header's layout check, which evens the
     * rows out again when its buttons change, or keeps them cleared for the start. It holds no view, so the header it's kept for can go.
     */
    static final class Changes implements View.OnLayoutChangeListener {
        final int startOriginal;
        final int endOriginal;
        int startSet = NONE;
        int endSet = NONE;
        final Map<FrameLayout.LayoutParams, int[]> gravities = new IdentityHashMap<>();

        Changes(int startOriginal, int endOriginal) {
            this.startOriginal = startOriginal;
            this.endOriginal = endOriginal;
        }

        @Override
        public void onLayoutChange(View header, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            try {
                int placement = placement();
                if (placement == AS_IS) return;
                LinearLayout start = HomeHeader.startRowOf(header);
                LinearLayout end = HomeHeader.endRowOf(header);
                if (start == null || end == null) return;
                if (placement == START) {
                    startSet = widen(start, 0);
                    endSet = widen(end, 0);
                } else {
                    balance(header, start, end, this);
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REELS_TAB, "home header logo", failure);
            }
        }
    }
}
