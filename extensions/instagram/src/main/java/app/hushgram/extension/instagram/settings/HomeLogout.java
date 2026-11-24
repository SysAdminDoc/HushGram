/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

/**
 * Log out in Home's menu (#850): the two calls the Hide the Reels tab patch writes the bodies of.
 *
 * <p>{@link #start} starts the Log out that Instagram's own settings row starts: the same controller,
 * made from Instagram's main screen the way the row makes it from the settings screen, asked for the
 * same logout. Instagram then runs it as it does from settings, with its own questions. A build whose
 * patch couldn't find any of it keeps the bodies below, so {@link #wired} answers false and the menu
 * and settings leave the entry out.
 */
public final class HomeLogout {
    private HomeLogout() { }

    /** Whether the patch wrote {@link #start} in this build. The patch writes the body. */
    public static boolean wired() {
        return false;
    }

    /**
     * Starts Instagram's own Log out from [activity], Instagram's main screen, and answers true. The
     * patch writes the body. It throws when [activity] isn't Instagram's main screen or has no account.
     */
    public static boolean start(Object activity) {
        return false;
    }
}
