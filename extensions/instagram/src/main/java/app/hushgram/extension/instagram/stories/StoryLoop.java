/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

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
 *
 * <p>With the switch off, Loop this story in a story's menu loops just that story (#610). The
 * patch tells {@link #asking(Object)} first thing in the check which item it's about, and
 * {@link #loop(int)} says yes for an item whose media ID was looped from its menu.
 */
public final class StoryLoop {
    /** What the diagnostic report counts a story looped from its menu under. */
    static final String LOOPED = "story looped from its menu";

    /** What the diagnostic report counts a story's loop taken off from its menu under. */
    static final String UNLOOPED = "story loop taken off from its menu";

    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /** The story item the loop check was last asked about, held weakly so a closed viewer can go. */
    private static volatile WeakReference<Object> asked = new WeakReference<>(null);

    /** The media IDs of the stories looped from their menu, until the app is closed. */
    private static final Set<String> looping = Collections.synchronizedSet(new HashSet<>());

    private StoryLoop() {
    }

    /**
     * The viewer's loop test: Instagram's answer ([answer] nonzero for yes), or yes while the
     * switch is on or for a story looped from its menu. Instagram's answer while the switch is off
     * and the story isn't looped, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean loop(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FamilyNames.STORY_LOOP);
            return instagram || on() || looped(asked.get());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_LOOP, "story loop flag", failure);
            return instagram;
        }
    }

    /**
     * Remembers [item], the story item the viewer's loop check was just asked about, for
     * {@link #loop(int)}. Never throws.
     */
    public static void asking(Object item) {
        try {
            asked = new WeakReference<>(item);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_LOOP, "story loop item", failure);
        }
    }

    /**
     * True while this build has the patch and it's looping stories, every story or one looped from
     * its menu, so Stop Story auto-advance leaves a finished story to the loop check: it stands
     * aside for one the check loops and holds one the check turns down. Never throws.
     */
    static boolean takesOver() {
        try {
            return inBuild() && (on() || ready() && !looping.isEmpty());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_LOOP, "story loop check", failure);
            return false;
        }
    }

    /**
     * Whether a story's menu gets Loop this story: the patch is in this build and its switch is
     * off, since with it on every story loops already. Never throws.
     */
    public static boolean offersRow() {
        try {
            return inBuild() && ready() && !Settings.LOOP_STORIES.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_LOOP, "story loop row", failure);
            return false;
        }
    }

    /** Whether [media], a story's Media, was looped from its menu. Never throws. */
    public static boolean looping(Object media) {
        try {
            String id = id(InstagramMedia.mediaId(media));
            return id != null && looping.contains(id);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_LOOP, "story loop row", failure);
            return false;
        }
    }

    /**
     * Loops [media], a story's Media, or takes its loop off when it loops already, and answers
     * which: true for looping, false for playing once, null when nothing changed because the row
     * isn't offered or the story has no media ID. Never throws.
     */
    @Nullable
    public static Boolean toggle(Object media) {
        try {
            if (!offersRow()) return null;
            String id = id(InstagramMedia.mediaId(media));
            if (id == null) return null;
            boolean loop = !looping.remove(id);
            if (loop) looping.add(id);
            HookStatus.counted(FamilyNames.STORY_LOOP, loop ? LOOPED : UNLOOPED);
            return loop;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_LOOP, "story loop", failure);
            return null;
        }
    }

    /** Whether [item], the story item in the loop check, was looped from its menu. */
    private static boolean looped(@Nullable Object item) {
        if (item == null || looping.isEmpty() || !ready()) return false;
        String id = id(InstagramMedia.mediaId(InstagramMedia.storyItemMedia(item)));
        return id != null && looping.contains(id);
    }

    @Nullable
    private static String id(@Nullable String id) {
        if (id == null) return null;
        String trimmed = id.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean ready() {
        return Utils.settingsReady() && !HushgramPause.isPaused();
    }

    /** Forgets the stories looped from their menu and the item last asked about. For tests. */
    public static void forget() {
        looping.clear();
        asked = new WeakReference<>(null);
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
