/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Loop a story" patch.
 *
 * <p>Instagram already knows how to loop a story, behind a server test. The story viewer asks a
 * private check whether a story item plays again, and when the test says yes, a video loops in
 * the player instead of reporting that it's done, and a photo that's done gets its timer and
 * progress bar started over instead of the viewer moving on. The check says no before it reads
 * the test for an ad and for a few special kinds of story, so those still move on. The patch
 * answers the test's read through {@link #loop(int)}.
 *
 * <p>Stop Story auto-advance asks {@link #takesOver()} first thing when a story is done. A video
 * that loops never reports that it's done, so for photos to behave the same, Loop wins for every
 * story it loops: with both on, Stop asks the viewer's loop check about the finished item itself
 * and lets it through when the check says yes. A story the check turns down never reaches
 * {@link #loop(int)}, can't loop, and Stop still holds it.
 */
public final class StoryLoop {
    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    private StoryLoop() {
    }

    /**
     * The viewer's loop test: Instagram's answer ([answer] nonzero for yes), or yes while the
     * switch is on. Instagram's answer while the switch is off, HushGram is paused or the settings
     * aren't ready. Never throws.
     */
    public static boolean loop(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.STORY_LOOP);
            return instagram || on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_LOOP, "story loop flag", failure);
            return instagram;
        }
    }

    /**
     * True while this build has the patch and it's looping stories, so Stop Story auto-advance
     * leaves a finished story to the loop check: it stands aside for one the check loops and holds
     * one the check turns down. Never throws.
     */
    static boolean takesOver() {
        try {
            return inBuild() && on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_LOOP, "story loop check", failure);
            return false;
        }
    }

    /** Whether this build carries the patch. */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.storyLoop();
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.LOOP_STORIES.get();
    }
}
