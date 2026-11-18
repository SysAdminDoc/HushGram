/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.widget.PopupMenu;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Holds the story still while a list of the story Download button is open (#745, #726). A list
 * doesn't pause the viewer, so the story ran on and moved to the next one underneath it, and
 * Download then saved a story that had already left the screen.
 *
 * <p>A row's click runs before its list closes, and Download on a photo story with music opens the
 * video or photo list from there, so two lists can be open at once. The story is paused when the
 * first opens and played once the last has closed.
 *
 * <p>The patch calls {@link #viewerShown} right after the story viewer's onResume hands on to its
 * superclass's. That says which viewer to hold, and it also ends a list left open while the viewer
 * was away, whose dismiss may never come. Instagram's own onResume plays the story at that point,
 * so nothing here plays it again.
 */
public final class StoryListHold {
    static final String HOOK = "story list hold";

    /** The viewer's own pause and resume. Apart so a test can stand in for them. */
    interface Playback {
        void hold(Object viewer);

        void play(Object viewer);
    }

    static final Playback PATCHED = new Playback() {
        @Override
        public void hold(Object viewer) {
            StoryReelReader.pause(viewer, null);
        }

        @Override
        public void play(Object viewer) {
            StoryReelReader.play(viewer, null, null);
        }
    };

    static Playback playback = PATCHED;

    private static WeakReference<Object> shown = new WeakReference<>(null);
    private static WeakReference<Object> held = new WeakReference<>(null);
    private static final List<PopupMenu> OPEN = new ArrayList<>();

    private StoryListHold() { }

    /** The hook: [viewer], the story viewer, has just come to the front. Never throws. */
    public static void viewerShown(Object viewer) {
        try {
            shown = new WeakReference<>(viewer);
            if (OPEN.isEmpty()) return;
            held = new WeakReference<>(null);
            List<PopupMenu> lists = new ArrayList<>(OPEN);
            OPEN.clear();
            for (PopupMenu list : lists) list.dismiss();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, HOOK, failure);
        }
    }

    /** [list] has just opened over the story: the story waits until the last open list closes. Never throws. */
    public static void over(PopupMenu list) {
        try {
            list.setOnDismissListener(StoryListHold::closed);
            OPEN.add(list);
            if (OPEN.size() > 1) return;
            Object viewer = shown.get();
            if (viewer == null) return;
            playback.hold(viewer);
            held = new WeakReference<>(viewer);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, HOOK, failure);
        }
    }

    private static void closed(PopupMenu list) {
        try {
            if (!OPEN.remove(list) || !OPEN.isEmpty()) return;
            Object viewer = held.get();
            held = new WeakReference<>(null);
            if (viewer != null) playback.play(viewer);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_DOWNLOAD, HOOK, failure);
        }
    }

    static void forget() {
        playback = PATCHED;
        shown = new WeakReference<>(null);
        held = new WeakReference<>(null);
        OPEN.clear();
    }
}
