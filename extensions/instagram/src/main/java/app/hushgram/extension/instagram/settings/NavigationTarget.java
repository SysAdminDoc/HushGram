/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

/** Stable enum names carried by Instagram's tab factory, never a screen position or resource ID. */
public enum NavigationTarget {
    OFF, FEED, SEARCH, CLIPS, DIRECT, PROFILE, SHARE, CREATION, NEWS,
    PRODUCER_PROFILE_PANEL, FEED_SWITCHER, DYNAMIC_TAB;

    /** Whether [tab], Instagram's tab or the name kept of it, is this choice. */
    boolean matches(Object tab) {
        String name = tab instanceof Enum<?> ? ((Enum<?>) tab).name() : tab instanceof String ? (String) tab : null;
        return this != OFF && name != null && name().equals(name);
    }
}
