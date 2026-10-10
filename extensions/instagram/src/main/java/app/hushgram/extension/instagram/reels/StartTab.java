/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

/**
 * The tab Instagram opens on when it's started from its icon, and the name Instagram's tab enum
 * gives it. Home is the choice that leaves Instagram's own start alone.
 */
public enum StartTab {
    HOME("FEED"),
    SEARCH("SEARCH"),
    MESSAGES("DIRECT"),
    REELS("CLIPS"),
    PROFILE("PROFILE");

    /** The tab's name in Instagram's tab enum. */
    final String tab;

    StartTab(String tab) {
        this.tab = tab;
    }
}
