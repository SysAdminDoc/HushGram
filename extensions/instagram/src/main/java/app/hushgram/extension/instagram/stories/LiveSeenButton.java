/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the second switch of the "View live anonymously" patch: a Mark as seen button in the
 * live viewer's header (#412).
 *
 * <p>The patch calls {@link #bind} at the end of the live header's constructor with the header's
 * root view. The button goes in the row at the top end of the header, before the three-dot menu,
 * the way {@link StorySeenButton} sits in a story's header: an eye crossed out while the live's
 * heartbeats are held, a solid eye once it's marked. A tap marks the live on screen ({@link
 * LiveSeen#mark}), so its next heartbeat goes out and you join its viewer list. There's no taking
 * it back: the host's list has you from that heartbeat on.
 *
 * <p>The button shows only while lives are watched anonymously and its own switch is on. With
 * either off, HushGram paused or the settings not read yet, it's hidden.
 */
public final class LiveSeenButton {
    /** Instagram's three-dot menu at the top end of the live header, in the row the button joins. */
    static final String MENU = "iglive_header_options";

    static final String HOOK = "live seen button";

    private static final ResourceIdCache IDS = new ResourceIdCache();

    private LiveSeenButton() {
    }

    /** Injected at the end of the live header's constructor. Shows or hides the button. Never throws. */
    public static void bind(View root) {
        bind(root, LiveSeenButton::switchedOn);
    }

    static void bind(@Nullable View root, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.LIVE_SEEN);
            LiveSeen.opened();
            boolean showing = on.getAsBoolean();
            if (root == null) return;
            View menu = menu(root, showing);
            if (menu == null) return;
            LinearLayout row = (LinearLayout) menu.getParent();
            ImageView button = find(row);
            if (!showing) {
                if (button != null) button.setVisibility(View.GONE);
                return;
            }
            if (button == null) {
                button = create(row.getContext(), on);
                insert(row, row.indexOfChild(menu), button);
            }
            show(button, LiveSeen.markedNow());
            button.setVisibility(View.VISIBLE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIVE_SEEN, HOOK, failure);
        }
    }

    /** A tap on [button]: marks the live on screen and shows it. Never throws. */
    static void tapped(ImageView button, BooleanSupplier on) {
        try {
            if (!on.getAsBoolean()) {
                button.setVisibility(View.GONE);
                return;
            }
            LiveSeen.mark();
            show(button, true);
            button.announceForAccessibility(StorySeenButton.describe(StoryMarks.State.SENT));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LIVE_SEEN, HOOK, failure);
        }
    }

    /** Whether the button shows: lives watched anonymously and its own switch on. Both read off while paused. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.VIEW_LIVE_ANONYMOUSLY.get() && Settings.MARK_LIVE_SEEN.get();
    }

    static void resetForTests() {
        IDS.clear();
    }

    static void putIdForTests(Context context, String name, int id) {
        IDS.putForTests(context.getPackageName(), name, id);
    }

    /** The header's three-dot menu under [root], in a row of buttons, reporting a build without it while the button is wanted. */
    @Nullable
    private static View menu(View root, boolean wanted) {
        int id = IDS.resolve(root.getResources(), root.getContext().getPackageName(), MENU, false);
        View found = id == 0 ? null : root.findViewById(id);
        ViewParent parent = found == null ? null : found.getParent();
        if (!(parent instanceof LinearLayout)) {
            if (wanted) HookStatus.missingViewId(FamilyNames.LIVE_SEEN, MENU);
            return null;
        }
        HookStatus.recoveredViewId(FamilyNames.LIVE_SEEN, MENU);
        return found;
    }

    @Nullable
    private static ImageView find(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof StorySeenButton.Eye) {
                return (ImageView) child;
            }
        }
        return null;
    }

    /** Puts [button] at [at], before the menu, in a 48dp touch target that doesn't grow the header. */
    private static void insert(LinearLayout row, int at, ImageView button) {
        float density = row.getResources().getDisplayMetrics().density;
        int target = Math.round(48 * density);
        int overhang = -Math.round(4 * density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(target, target);
        params.topMargin = overhang;
        params.bottomMargin = overhang;
        params.gravity = Gravity.CENTER;
        row.addView(button, at, params);
    }

    private static ImageView create(Context context, BooleanSupplier on) {
        ImageView button = new ImageView(context);
        button.setImageDrawable(new StorySeenButton.Eye(context.getResources().getDisplayMetrics().density));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setOnClickListener(view -> tapped((ImageView) view, on));
        return button;
    }

    private static void show(ImageView button, boolean marked) {
        StoryMarks.State state = marked ? StoryMarks.State.SENT : StoryMarks.State.UNMARKED;
        if (button.getDrawable() instanceof StorySeenButton.Eye) ((StorySeenButton.Eye) button.getDrawable()).setState(state);
        button.setContentDescription(StorySeenButton.describe(state));
        button.setEnabled(!marked);
    }
}
