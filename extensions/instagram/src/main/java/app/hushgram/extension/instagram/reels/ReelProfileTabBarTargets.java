/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.Collections;
import java.util.List;

/**
 * The stub the "Keep the tab bar on a reel's profile" patch writes, so {@link ReelProfileTabBar} can
 * reach Instagram's renamed classes without naming them.
 */
public final class ReelProfileTabBarTargets {
    private ReelProfileTabBarTargets() {
    }

    /** Builds the profile screen with [factory] and puts it on the current tab. Unpatched, it does nothing. */
    public static void push(Object activity, Object factory, Object session, Object config) {
    }

    /** The screens added to the tab host [host], oldest first. Unpatched, there are none. */
    public static List<?> fragments(Object host) {
        return Collections.emptyList();
    }
}
