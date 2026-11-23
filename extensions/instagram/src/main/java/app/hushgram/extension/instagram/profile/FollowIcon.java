/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.profile.FriendshipStatus.Relation;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Show it as an icon at the top (#842): a green check in the profile's top bar when the account
 * follows you and a red cross when it doesn't, just before the bar's last button, the menu. It
 * stands in for the label by the name and the chip. A tap lists the friendship flags, as the chip's
 * does (#698).
 *
 * <p>The top bar isn't in the header the hooks are handed. It sits beside the list the header
 * scrolls in, in the profile screen's own frame, so going up from the pronouns slot, the first view
 * holding the bar's row of buttons is that frame, and a profile opened over another finds its own.
 *
 * <p>Instagram can fill the row again after the header is bound, as it sets the bar up, so the row's
 * layout listener puts the icon back, or back in its place, whenever it isn't there.
 *
 * <p>Runs on the main thread, where Instagram binds the header.
 */
final class FollowIcon {
    /** The row of buttons at the end of a profile's top bar, by name. */
    static final String BUTTONS = "right_action_bar_buttons";
    /** Counted each time a profile's top bar can't be found, so the label or chip shows instead. */
    static final String NO_TOP_BAR = "Follow icon left out, no top bar";
    /** How wide the icon is, ring included. */
    static final float SIZE_DP = 22f;
    /** The room on each side of it, which keeps it as far from the menu as the bar's buttons are apart. */
    static final float SIDE_DP = 12f;

    /** How far up from the pronouns slot the top bar is looked for. */
    private static final int MAX_DEPTH = 14;
    /** Marks the icon among the row's buttons. */
    private static final Object TAG = new Object();

    /** Set by tests that have no Instagram resources: the row's id. */
    static int buttonsIdForTests;
    /** The row's id in this app, or -1 before the first lookup. */
    private static volatile int buttonsId = -1;

    /**
     * The rows showing an icon. A row goes with its screen: its [Mark] holds it and the header only
     * weakly, and finds the icon in it each time rather than keeping it.
     */
    private static final Map<ViewGroup, Mark> SHOWN = new WeakHashMap<>();

    private FollowIcon() {
    }

    /** Whether any top bar has an icon, so one may need taking away. */
    static boolean anyShown() {
        return !SHOWN.isEmpty();
    }

    /**
     * With the switch on and [relation] known, shows its icon in the top bar of the profile [inside]
     * is part of, and a tap on it lists the flags of the profile screen [header] belongs to. Otherwise
     * takes an icon it put there before away. True when the icon is showing, so the label and the chip
     * stay out.
     */
    static boolean show(View inside, @Nullable Relation relation, @Nullable Object header) {
        ViewGroup row = buttons(inside);
        boolean wanted = relation != null && Settings.FOLLOW_ICON_AT_TOP.get();
        if (row == null) {
            if (wanted) HookStatus.counted(FamilyNames.FRIENDSHIP_STATUS, NO_TOP_BAR);
            return false;
        }
        if (!wanted) {
            clear(row);
            return false;
        }
        Mark mark = SHOWN.get(row);
        if (mark == null) {
            mark = new Mark(row);
            SHOWN.put(row, mark);
            row.addOnLayoutChangeListener(mark);
        }
        mark.followsYou = relation != Relation.DOESNT_FOLLOW_YOU;
        mark.header = new WeakReference<>(header);
        place(row, mark);
        return true;
    }

    /** Takes the icon out of [row] and stops watching it. */
    private static void clear(ViewGroup row) {
        Mark mark = SHOWN.remove(row);
        if (mark != null) row.removeOnLayoutChangeListener(mark);
        ImageView icon = iconIn(row);
        if (icon != null) row.removeView(icon);
    }

    /**
     * The row of buttons in the top bar of the profile [inside] is part of: going up from [inside],
     * the first view holding one. Null when there's none, or it isn't showing.
     */
    @Nullable
    static ViewGroup buttons(View inside) {
        int id = buttonsId(inside.getContext());
        if (id == 0) return null;
        View at = inside;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) parent;
            View row = group.findViewById(id);
            if (row != null && row != group) {
                return row instanceof ViewGroup && row.getVisibility() == View.VISIBLE ? (ViewGroup) row : null;
            }
            at = group;
        }
        return null;
    }

    private static int buttonsId(Context context) {
        if (buttonsIdForTests != 0) return buttonsIdForTests;
        if (buttonsId == -1) buttonsId = context.getResources().getIdentifier(BUTTONS, "id", Utils.resourcePackage(context));
        return buttonsId;
    }

    /** The icon in [row], or null. */
    @Nullable
    static ImageView iconIn(ViewGroup row) {
        for (int index = 0; index < row.getChildCount(); index++) {
            View child = row.getChildAt(index);
            if (child.getTag() == TAG) return (ImageView) child;
        }
        return null;
    }

    /** Where the icon goes in [row]: just before the last of Instagram's buttons, or first when there are none. */
    private static int spot(ViewGroup row, @Nullable View icon) {
        int buttons = row.getChildCount() - (icon != null && icon.getParent() == row ? 1 : 0);
        return Math.max(0, buttons - 1);
    }

    /** Whether [row] shows [mark]'s icon in its place, with the right badge. */
    private static boolean placed(ViewGroup row, Mark mark) {
        ImageView icon = iconIn(row);
        if (icon == null || row.indexOfChild(icon) != spot(row, icon)) return false;
        Drawable drawable = icon.getDrawable();
        return drawable instanceof FollowBadge && ((FollowBadge) drawable).followsYou == mark.followsYou;
    }

    /** Puts [mark]'s icon in its place in [row], made or moved as needed. */
    private static void place(ViewGroup row, Mark mark) {
        if (placed(row, mark)) return;
        Context context = row.getContext();
        ImageView icon = iconIn(row);
        if (icon == null) {
            icon = new ImageView(context);
            icon.setTag(TAG);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            int side = Math.round(SIDE_DP * context.getResources().getDisplayMetrics().density);
            icon.setPadding(side, 0, side, 0);
            icon.setOnClickListener(mark);
        } else {
            row.removeView(icon);
        }
        icon.setImageDrawable(new FollowBadge(context, mark.followsYou, SIZE_DP));
        String label = FriendshipStatus.text(mark.followsYou);
        icon.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) icon.setTooltipText(label);
        ViewGroup.LayoutParams params = row instanceof LinearLayout
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        row.addView(icon, spot(row, null), params);
    }

    /** One row's icon: what it shows, the profile it's for, and the look after it when Instagram lays the row out again. */
    private static final class Mark implements View.OnLayoutChangeListener, View.OnClickListener {
        private final WeakReference<ViewGroup> row;
        boolean followsYou;
        WeakReference<Object> header = new WeakReference<>(null);

        Mark(ViewGroup row) {
            this.row = new WeakReference<>(row);
        }

        @Override
        public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            try {
                ViewGroup at = row.get();
                if (at == null || SHOWN.get(at) != this || placed(at, this)) return;
                // Not while the row is laid out: on the next pass, if it's still this row's icon.
                at.post(() -> {
                    try {
                        if (SHOWN.get(at) == this && Settings.FOLLOW_ICON_AT_TOP.get()) place(at, this);
                    } catch (Throwable failure) {
                        HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "profile top bar", failure);
                    }
                });
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "profile top bar", failure);
            }
        }

        @Override
        public void onClick(View view) {
            try {
                FriendshipFlags.open(view.getContext(), header.get());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, "profile top bar", failure);
            }
        }
    }
}
