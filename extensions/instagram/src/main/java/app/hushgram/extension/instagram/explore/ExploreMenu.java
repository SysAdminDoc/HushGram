/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import android.view.View;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Explore without personalization" patch.
 *
 * <p>Where the law asks for it (the EU's Digital Services Act), Instagram puts a menu button on
 * Explore's top bar with a row that switches the grid to one that isn't personalized. Picking that
 * row clears the controller's personalized flag and reloads the grid, and ten minutes later, the
 * next time Explore comes back, a timer reloads the personalized grid on its own.
 *
 * <p>The patch asks {@link #unpersonalize} as Explore resumes on the personalized grid. On a yes it
 * makes the same check Instagram makes before it shows the menu, and where the menu is offered it
 * does what the row does: clears the flag and reloads the grid through the same call, so the
 * request is Instagram's own. {@link #reloaded} counts each of those reloads.
 * {@link #keepUnpersonalized} stops the ten-minute timer. {@link #button} and {@link #oldButton}
 * hide the menu button on the two top bar layouts. Outside the EU the check says no, Instagram
 * never shows that menu, and nothing changes.
 */
public final class ExploreMenu {
    /** What's counted each time Explore is reloaded without personalization. */
    static final String UNPERSONALIZED = "explore unpersonalized";

    /** What's counted each time the ten-minute switch back is skipped. */
    static final String KEPT = "kept unpersonalized";

    /** What's counted each time the menu button is hidden. */
    static final String HIDDEN = "menu button hidden";

    private ExploreMenu() {
    }

    /**
     * Injected where Explore resumes, when the controller says the personalized grid is showing.
     * Answers true while Explore without personalization is on, and the patch then goes on to
     * Instagram's own check for the menu. False while the switch is off, HushGram is paused or the
     * settings aren't ready, or when anything goes wrong, and the patch leaves Explore alone without
     * asking Instagram anything. Never throws.
     */
    public static boolean unpersonalize() {
        try {
            HookStatus.invoked(FamilyNames.EXPLORE_MENU);
            return withoutPersonalization();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_MENU, "explore resume", failure);
            return false;
        }
    }

    /**
     * Injected right before the patch reloads Explore without personalization, after every check
     * has passed. Only counts the reload. Never throws.
     */
    public static void reloaded() {
        try {
            HookStatus.counted(FamilyNames.EXPLORE_MENU, UNPERSONALIZED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_MENU, "explore reload", failure);
        }
    }

    /**
     * Injected at the top of the timer that brings the personalized grid back ten minutes after
     * the other one was picked. Answers true while Explore without personalization is on, and the
     * timer then reports that nothing changed. Never throws.
     */
    public static boolean keepUnpersonalized() {
        try {
            HookStatus.invoked(FamilyNames.EXPLORE_MENU);
            if (!withoutPersonalization()) return false;
            HookStatus.counted(FamilyNames.EXPLORE_MENU, KEPT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_MENU, "explore timer", failure);
            return false;
        }
    }

    /**
     * Injected where the newer top bar keeps the menu button it just made. Hides it while Hide
     * Explore's personalized menu is on. Never throws.
     */
    public static void button(View view) {
        try {
            HookStatus.invoked(FamilyNames.EXPLORE_MENU);
            if (view == null || !hideMenu()) return;
            view.setVisibility(View.GONE);
            HookStatus.counted(FamilyNames.EXPLORE_MENU, HIDDEN);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_MENU, "explore menu button", failure);
        }
    }

    /**
     * Injected at the top of the older top bar's button maker, with the icon it was asked for and
     * the menu's icon. Answers true for the menu's button while Hide Explore's personalized menu is
     * on, and the button isn't made. Every other button is. Never throws.
     */
    public static boolean oldButton(int icon, int menuIcon) {
        try {
            if (icon != menuIcon) return false;
            HookStatus.invoked(FamilyNames.EXPLORE_MENU);
            if (!hideMenu()) return false;
            HookStatus.counted(FamilyNames.EXPLORE_MENU, HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPLORE_MENU, "explore menu button", failure);
            return false;
        }
    }

    private static boolean withoutPersonalization() {
        return Utils.settingsReady() && Settings.EXPLORE_WITHOUT_PERSONALIZATION.get();
    }

    private static boolean hideMenu() {
        return Utils.settingsReady() && Settings.HIDE_EXPLORE_MENU.get();
    }
}
