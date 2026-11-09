/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keeps the tab bar on a profile opened from a reel (#385). Instagram opens a reel author's profile
 * in a screen of its own, and that screen has no tab bar, so the bar is gone from the profile's first
 * frame and from any reel opened from it. With the switch on, the profile goes onto the tab the reel
 * is playing on, the way Instagram's other profile links do. Back then returns to the reel.
 */
public final class ReelProfileTabBar {
    private ReelProfileTabBar() {
    }

    static final String TAB_BAR = "tab_bar";
    static final String WHAT = "open a reel's profile on the tab";

    interface Push {
        void onTheTab(Object activity, Object factory, Object session, Object config);
    }

    /**
     * Called where Instagram would open the profile's own screen. True when the profile went onto
     * the tab instead, and that screen mustn't open.
     */
    public static boolean open(Object activity, Object factory, Object session, Object config) {
        if (!(activity instanceof Activity)) return false;
        Activity screen = (Activity) activity;
        int bar = screen.getResources().getIdentifier(TAB_BAR, "id", screen.getPackageName());
        return open(screen, bar, factory, session, config, ReelProfileTabBarTargets::push);
    }

    static boolean open(Activity activity, int bar, Object factory, Object session, Object config, Push push) {
        try {
            if (!Settings.REEL_PROFILE_TAB_BAR.get()) return false;
            if (activity.isFinishing() || activity.isDestroyed() || activity.findViewById(bar) == null) return false;
            push.onTheTab(activity, factory, session, config);
            Logger.printDebug(() -> "ReelProfileTabBar: opened the profile on the tab");
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_PROFILE_TAB_BAR, WHAT, t);
            return false;
        }
    }
}
