/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.util.Collections;
import java.util.List;

/**
 * The stubs the "Start Home on Following" patch writes, so {@link FavoritesFeed} can reach
 * Instagram's renamed classes without naming them.
 */
public final class FavoritesFeedTargets {
    private FavoritesFeedTargets() {
    }

    /** Home's feed types, the enum Favorites is a constant of. Unpatched, there's none. */
    public static Class<?> feedTypes() {
        return null;
    }

    /** Hands [feed] to Home's feed switch [feedSwitch]. Unpatched, it does nothing. */
    public static void show(Object feedSwitch, Object feed) {
    }

    /** The screens added to the tab host [host], oldest first. Unpatched, there are none. */
    public static List<?> fragments(Object host) {
        return Collections.emptyList();
    }
}
