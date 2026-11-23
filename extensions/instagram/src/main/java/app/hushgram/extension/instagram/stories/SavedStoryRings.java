/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Story rings on saved posts (#790): the ring around the author's picture at the top of a post you
 * saved, and a tap on it opening their story, the way Home has them.
 *
 * <p>A post's top row asks one place for the author's story reel before it draws the ring and wires
 * the tap: the reel Instagram already holds for that account, from the tray or from the post's own
 * data, so nothing is fetched to decide. That place hands back no reel unless the post is on one of
 * the surfaces Instagram lists (Home, Explore, a single post, the feed a post opens into, comments),
 * and the saved feeds aren't on that list, which is why a saved post never shows a ring even when the
 * tray shows one for the same account. The patch asks {@link #shows} with the post's surface right
 * where that list is checked. A yes goes on as if the surface were listed, so every other check
 * Instagram makes (the flags its caller passes, a post with no author, the reel's own check and
 * Instagram's server switch for the ring) still decides.
 *
 * <p>The switch off, a pause, settings that aren't ready or any failure leave Instagram's list as it
 * is.
 */
public final class SavedStoryRings {
    /** The hook's name in the diagnostics, and what it counts. */
    static final String HOOK = "saved post story ring";
    static final String LET_THROUGH = "saved posts asked for a story ring";

    /** The surfaces that show your saved posts, by Instagram's names for them. */
    static final Set<String> SAVED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("SAVE_FEED", "SAVE_HOME")));

    private SavedStoryRings() {
    }

    /** Whether a post on [surface], Instagram's surface enum, gets its author's story ring like a post on Home. */
    public static boolean shows(Object surface) {
        try {
            if (!(surface instanceof Enum) || !SAVED.contains(((Enum<?>) surface).name())) return false;
            if (!Utils.settingsReady() || HushgramPause.isPaused() || !Settings.SAVED_STORY_RINGS.get()) return false;
            HookStatus.counted(FamilyNames.SAVED_STORY_RINGS, LET_THROUGH);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_STORY_RINGS, HOOK, failure);
            return false;
        }
    }
}
