/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

/**
 * Which tab bar Instagram draws ({@link TabBarLayout}, #356). Instagram's choice leaves it to the
 * account, which Instagram can move between the two on its own. Swipe is the newer bar, with
 * Messages on it and a sideways swipe between every tab. Classic is the older one, with Create in
 * the bar and Messages at the top of Home.
 */
public enum TabBarLayoutChoice {
    INSTAGRAM,
    SWIPE,
    CLASSIC
}
