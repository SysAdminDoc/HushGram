/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

/**
 * Where {@link StoryMentions} puts a story's mention pill and what it says (#468): under the name
 * as "1 mention", which is all the switch did before there was a choice, or in the top bar next to
 * the menu as "@1 mention" or only "@1". From the top bar, a tap lists the accounts in a sheet
 * along the bottom of the screen.
 */
public enum StoryMentionsLook {
    UNDER_NAME,
    TOP_BAR,
    TOP_BAR_SHORT;

    /** Whether the pill goes in the top bar. */
    public boolean inTopBar() {
        return this != UNDER_NAME;
    }
}
