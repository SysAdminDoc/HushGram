/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import android.view.View;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the Repost button" patch.
 *
 * <p>Instagram marks each post and reel with whether it can be reposted, in a field its code calls
 * enable_media_notes_production. The Repost button, its count and the repost action all ask that
 * field first. The patch answers it through {@link #hide} where the post's model reads it, and
 * through {@link #eligible} wherever the buttons read it from the post's data tree, so while the
 * switch is on no post or reel can be reposted and the button isn't drawn. Feed also keeps its own
 * flags for whether a post's action row shows the button and its count, and draws the row from
 * them again and again, so {@link #feedState} answers those as each row's state is built (#69).
 */
public final class RepostButton {
    private static volatile boolean logged;
    private static final Map<View, HiddenView> hiddenViews = Collections.synchronizedMap(new WeakHashMap<>());

    private RepostButton() {
    }

    /**
     * Injected first thing in the post model's read of the field. Answers true, and the read says
     * the post can't be reposted, while the switch is on, and false otherwise, or when anything goes
     * wrong. Never throws, and never waits for the settings: before they're ready the button stays.
     */
    public static boolean hide() {
        return hidden("post model");
    }

    /**
     * Injected right after a button reads the field from a post's data tree. Answers false while the
     * switch is on, and what the tree said otherwise, null included, or when anything goes wrong.
     * Never throws.
     */
    @Nullable
    public static Boolean eligible(@Nullable Boolean eligible) {
        return hidden("data tree") ? Boolean.FALSE : eligible;
    }

    /** The component-backed Feed renderer checks current settings before mounting either view. */
    public static boolean feedComponent() {
        return hidden("feed component");
    }

    /**
     * Injected where Feed builds a post's action-row state, right before it keeps whether the row
     * shows the Repost button, and its count. The flag comes as an int, non-zero for yes, so the
     * hook doesn't depend on Instagram's code leaving that register typed as a boolean. Answers
     * false while the switch is on, and the flag otherwise, or when anything goes wrong. Counts
     * which way it went. Never throws.
     */
    public static boolean feedState(int enabled) {
        return feedState(enabled != 0, RepostButton::switchedOn);
    }

    static boolean feedState(boolean enabled, BooleanSupplier on) {
        boolean hide = hidden("feed state", on);
        HookStatus.counted(FamilyNames.REPOST_BUTTON, hide ? "feed state off" : "feed state on");
        return enabled && !hide;
    }

    /**
     * Injected after Feed's UFI binder draws the repost icon and count. The upstream state can be
     * built before settings are ready, so the rendered Feed row gets one final, switch-aware pass.
     */
    public static void feedUfi(@Nullable View icon, @Nullable View count) {
        try {
            if (!hidden("feed UFI")) return;
            hide(icon);
            hide(count);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REPOST_BUTTON, "feed UFI", failure);
        }
    }

    /** Runs before native rebinding, even when paused or unready. Native writes then take precedence. */
    public static void restoreFeedUfi(@Nullable View icon, @Nullable View count) {
        try {
            for (View view : new View[]{icon, count}) {
                HiddenView saved = hiddenViews.remove(view);
                if (saved != null) saved.restore(view);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REPOST_BUTTON, "restore feed UFI", failure);
        }
    }

    private static boolean hidden(String where) {
        return hidden(where, RepostButton::switchedOn);
    }

    private static boolean hidden(String where, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.REPOST_BUTTON);
            if (!on.getAsBoolean()) return false;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Repost button: answered no reposts, first from the " + where);
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REPOST_BUTTON, where, failure);
            return false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_REPOST_BUTTON.get();
    }

    private static void hide(@Nullable View view) {
        if (view == null) return;
        hiddenViews.putIfAbsent(view, new HiddenView(view));
        view.setVisibility(View.GONE);
        view.setEnabled(false);
        view.setClickable(false);
        view.setLongClickable(false);
        view.setContentDescription(null);
    }

    /** Keeps no reference to the weakly keyed view or its listeners. */
    private static final class HiddenView {
        final int visibility;
        final boolean enabled, clickable, longClickable;
        final CharSequence description;

        HiddenView(View view) {
            visibility = view.getVisibility();
            enabled = view.isEnabled();
            clickable = view.isClickable();
            longClickable = view.isLongClickable();
            description = view.getContentDescription();
        }

        void restore(View view) {
            view.setVisibility(visibility);
            view.setEnabled(enabled);
            view.setClickable(clickable);
            view.setLongClickable(longClickable);
            view.setContentDescription(description);
        }
    }
}
