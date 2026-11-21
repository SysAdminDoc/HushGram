/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * The stubs the "Keep the tab bar on reel links" patch writes, so {@link ReelLinkTabBar} can reach
 * Instagram's renamed link route, viewer flag and tab screens without naming them.
 */
public final class ReelLinkTabBarTargets {
    private ReelLinkTabBarTargets() {
    }

    /** Opens the reel link's viewer from [arguments] in [activity]. Unpatched, it does nothing. */
    public static void show(Object arguments, Object activity, Object session) {
    }

    /**
     * The name of the viewer config's field the viewer hides the tab bar by, the one its toString
     * prints as shouldHideTabBar. Unpatched, null.
     */
    @Nullable
    public static String tabBarHiddenFlag() {
        return null;
    }

    /** The screens added to a tab [host], the ones its root check reads. Unpatched, none. */
    public static List<?> fragments(Object host) {
        return Collections.emptyList();
    }
}
