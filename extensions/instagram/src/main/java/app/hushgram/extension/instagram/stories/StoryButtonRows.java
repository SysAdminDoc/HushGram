/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.function.BiConsumer;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Where the Mark as seen and Download buttons sit on a story (#647): in the header's row at the top,
 * as they always have, or with Story buttons at the bottom on, in the row at the bottom right of
 * someone else's story that holds Like and Share, where a thumb reaches them.
 *
 * <p>Both buttons are bound first thing in the story item binder's toolbar bind, before Instagram
 * shows or hides that bottom row for the story, and the holder was last bound for another story. So
 * a bind puts the button where the rows stand then, and posts a look on the button that runs once
 * Instagram's bind is done and moves it if the bottom row was shown or hidden since. A story whose
 * bottom row isn't showing (your own, or one with replies off) keeps the button in the header.
 *
 * <p>On your own story or highlight Instagram can leave that row showing with Like and Share hidden in
 * it, over your own toolbar (Activity to More), so a button put there sat on Send and More (#850). The
 * row counts only while it shows Like or Share.
 */
final class StoryButtonRows {
    /** Instagram's row of buttons at the bottom right of a story, and the Like button's box and Share in it. */
    static final String FOOTER = "toolbar_buttons_container";
    static final String LIKE = "toolbar_like_container";
    static final String SHARE = "toolbar_reshare_button";

    private static final ResourceIdCache IDS = new ResourceIdCache();

    private StoryButtonRows() {
    }

    /** Whether the buttons go in the bottom row. Reads off while paused or before the settings are read. */
    static boolean byThumb() {
        return Utils.settingsReady() && Settings.STORY_BUTTONS_BY_THUMB.get();
    }

    /**
     * The bottom row of buttons under [root], or null when this story's layout has none. A build
     * without the row's id is reported for [family] while the row is [wanted].
     */
    @Nullable
    static LinearLayout footer(View root, String family, boolean wanted) {
        int id = id(root, FOOTER);
        if (id == 0) {
            if (wanted) HookStatus.missingViewId(family, FOOTER);
            return null;
        }
        View found = root.findViewById(id);
        return found instanceof LinearLayout ? (LinearLayout) found : null;
    }

    /** Whether [view] shows within [root]: it and every parent up to [root] are visible. */
    static boolean shownWithin(View view, View root) {
        View at = view;
        while (at != null) {
            if (at.getVisibility() != View.VISIBLE) return false;
            if (at == root) return true;
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /**
     * Whether [footer] shows within [root] with neither Like nor Share showing in it, as on your own
     * story (#850). False when this build names neither, so the row is taken as it shows.
     */
    static boolean bare(LinearLayout footer, View root) {
        if (!shownWithin(footer, root)) return false;
        int like = id(footer, LIKE);
        int share = id(footer, SHARE);
        if (like == 0 && share == 0) return false;
        return !shows(footer, like, root) && !shows(footer, share, root);
    }

    private static boolean shows(LinearLayout row, int id, View root) {
        if (id == 0) return false;
        View found = row.findViewById(id);
        return found != null && shownWithin(found, root);
    }

    /**
     * Puts [button] in the row it belongs in on this story: the bottom row while [byThumb] is on and
     * that row shows Like or Share (or the header has no row and the bottom row is hidden), the
     * header's row otherwise, moving it out of the other row when it's there. [intoHeader] adds a
     * button to the header's row where that button goes. With [recheck], posts the same look on the
     * button for after Instagram's bind.
     */
    static void place(View button, View root, @Nullable LinearLayout header, @Nullable LinearLayout footer,
                      boolean byThumb, BiConsumer<LinearLayout, View> intoHeader, boolean recheck) {
        boolean low = byThumb && footer != null && (header == null || shownWithin(footer, root)) && !bare(footer, root);
        LinearLayout target = low ? footer : header;
        if (target == null) return;
        if (button.getParent() != target) {
            ViewParent old = button.getParent();
            if (old instanceof ViewGroup) ((ViewGroup) old).removeView(button);
            if (low) intoFooter(target, button);
            else intoHeader.accept(target, button);
        }
        if (recheck && byThumb && footer != null && header != null) {
            button.post(() -> place(button, root, header, footer, true, intoHeader, false));
        }
    }

    /**
     * Puts [button] before Like in the bottom row, or first when the row has no Like, so Instagram's
     * Like and Share keep their places at the edge. The same 48dp box as in the header, taking 40dp
     * of the row's height.
     */
    static void intoFooter(LinearLayout row, View button) {
        int at = 0;
        int like = id(row, LIKE);
        for (int i = 0; i < row.getChildCount(); i++) {
            if (like != 0 && row.getChildAt(i).getId() == like) {
                at = i;
                break;
            }
        }
        float density = row.getResources().getDisplayMetrics().density;
        int target = Math.round(48 * density);
        int overhang = -Math.round(4 * density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(target, target);
        params.topMargin = overhang;
        params.bottomMargin = overhang;
        params.gravity = Gravity.CENTER;
        row.addView(button, at, params);
    }

    private static int id(View view, String name) {
        return IDS.resolve(view.getResources(), Utils.resourcePackage(view.getContext()), name, false);
    }

    /** Forgets the ids looked up. */
    static void resetForTests() {
        IDS.clear();
    }

    static void putIdForTests(Context context, String name, int id) {
        IDS.putForTests(Utils.resourcePackage(context), name, id);
    }
}
