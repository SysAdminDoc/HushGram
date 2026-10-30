/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Seek bar on stories patch (#355).
 *
 * <p>The story viewer, ReelViewerFragment, tells {@link #resumed} and {@link #paused} when it comes
 * on screen and goes. While it's resumed, a seek bar sits over the bottom of the viewer, just above
 * the Send message box ({@link #COMPOSER}) when that's showing, under any sheet opened over the
 * story, and every {@link #TICK_MS} it shows
 * where the story's video is. A photo has no length, so the bar hides for it. Dragging pauses the
 * story the way a press and hold does; letting go moves the video to that point and plays on, and
 * Instagram's own segment along the top follows the video. A live story's player refuses a seek, so
 * letting go there only plays on.
 *
 * <p>With the switch off, HushGram paused or the settings not read, the bar hides at its next tick
 * and a drag does nothing. A viewer going away takes its bar with it. Anything thrown leaves the
 * story as Instagram plays it.
 */
public final class StorySeekBar {
    /** The story viewer's Send message box, which the bar sits above. */
    static final String COMPOSER = "reel_viewer_message_composer_container";

    /** How often the bar catches up with the video. */
    static final long TICK_MS = 250;

    /** What the pause tells Instagram, the reason a press and hold gives. */
    static final String PAUSE_REASON = "long_pressed";

    /** The bar's height, and how far above the bottom it sits when there's no Send message box. */
    static final int HEIGHT_DP = 32;
    static final int BOTTOM_DP = 64;

    static final String HOOK = "story seek bar";
    static final String COUNT_SEEKS = "story seeks";

    /** The viewer's player, through the patch's stubs; tests stand in. */
    interface Player {
        @Nullable View view(Object viewer);
        int position(Object viewer);
        int duration(Object viewer);
        boolean seekTo(Object viewer, int positionMs);
        boolean pause(Object viewer, String reason);
        boolean resume(Object viewer);
    }

    static final Player PATCHED = new Player() {
        @Override public View view(Object viewer) { return StorySeekReader.view(viewer); }
        @Override public int position(Object viewer) { return StorySeekReader.position(viewer); }
        @Override public int duration(Object viewer) { return StorySeekReader.duration(viewer); }
        @Override public boolean seekTo(Object viewer, int positionMs) { return StorySeekReader.seekTo(viewer, positionMs); }
        @Override public boolean pause(Object viewer, String reason) { return StorySeekReader.pause(viewer, reason); }
        @Override public boolean resume(Object viewer) { return StorySeekReader.resume(viewer); }
    };

    private static Player player = PATCHED;
    private static final ResourceIdCache ids = new ResourceIdCache();
    @Nullable private static Overlay current;
    private static volatile boolean logged;

    private StorySeekBar() {
    }

    /** Whether the bar shows now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.STORY_SEEK_BAR.get();
    }

    /** Called by the story viewer's onResume, on the main thread, with the viewer. */
    public static void resumed(Object viewer) {
        try {
            Overlay old = current;
            if (old != null) {
                if (old.viewer.get() == viewer && old.attached()) return;
                old.remove();
            }
            current = new Overlay(viewer);
            current.tick();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
        }
    }

    /** Called by the story viewer's onPause, on the main thread, with the viewer. */
    public static void paused(Object viewer) {
        try {
            Overlay old = current;
            if (old == null || old.viewer.get() != viewer) return;
            current = null;
            old.remove();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
        }
    }

    @VisibleForTesting
    static void playerForTests(Player stand) {
        player = stand;
    }

    @VisibleForTesting
    static void composerIdForTests(android.content.Context context, int id) {
        ids.putForTests(context.getPackageName(), COMPOSER, id);
    }

    @VisibleForTesting
    @Nullable
    static SeekBar barForTests() {
        Overlay overlay = current;
        return overlay == null ? null : overlay.bar;
    }

    @VisibleForTesting
    static void resetForTests() {
        Overlay overlay = current;
        if (overlay != null) overlay.remove();
        current = null;
        player = PATCHED;
        ids.clear();
        logged = false;
    }

    /** One viewer's bar and its ticking. */
    private static final class Overlay implements SeekBar.OnSeekBarChangeListener, Runnable {
        final WeakReference<Object> viewer;
        @Nullable SeekBar bar;
        @Nullable WeakReference<View> composer;
        boolean dragging;
        boolean removed;

        Overlay(Object viewer) {
            this.viewer = new WeakReference<>(viewer);
        }

        boolean attached() {
            return !removed && bar != null && bar.getParent() != null;
        }

        @Override
        public void run() {
            tick();
        }

        void tick() {
            if (removed) return;
            try {
                Object owner = viewer.get();
                if (owner == null) {
                    remove();
                    return;
                }
                update(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
            }
            if (!removed) Utils.runOnMainThreadDelayed(this, TICK_MS);
        }

        private void update(Object owner) {
            if (!wanted()) {
                hide(owner);
                return;
            }
            int duration = player.duration(owner);
            if (duration <= 0) {
                hide(owner);
                return;
            }
            SeekBar seek = bar != null ? bar : make(owner);
            if (seek == null) return;
            if (!dragging) {
                if (seek.getMax() != duration) seek.setMax(duration);
                int position = player.position(owner);
                seek.setProgress(Math.max(0, Math.min(duration, position)));
            }
            place(owner, seek);
            if (seek.getVisibility() != View.VISIBLE) seek.setVisibility(View.VISIBLE);
        }

        /** Hides the bar, and plays the story on if it hid under a finger mid-drag. */
        private void hide(Object owner) {
            if (bar != null && bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
            if (dragging) {
                dragging = false;
                resume(owner);
            }
        }

        /**
         * The bar, in the frame holding the viewer, just after it: over everything the viewer draws
         * and taking touches before it, but under a sheet Instagram opens over the story, which its
         * own container draws later.
         */
        @Nullable
        private SeekBar make(Object owner) {
            View view = player.view(owner);
            if (view == null || !(view.getParent() instanceof FrameLayout)) return null;
            FrameLayout container = (FrameLayout) view.getParent();
            HookStatus.invoked(FamilyNames.STORY_SEEK_BAR);
            SeekBar seek = new SeekBar(view.getContext());
            seek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
            seek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x80FFFFFF));
            seek.setOnSeekBarChangeListener(this);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(view, HEIGHT_DP), Gravity.BOTTOM);
            params.bottomMargin = dp(view, BOTTOM_DP);
            container.addView(seek, container.indexOfChild(view) + 1, params);
            bar = seek;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Seek bar on stories: added a seek bar to the story viewer");
            }
            return seek;
        }

        /** Keeps the bar just above the Send message box while it shows, else a little above the bottom. */
        private void place(Object owner, SeekBar seek) {
            ViewGroup parent = (ViewGroup) seek.getParent();
            if (parent == null) return;
            int bottom = dp(seek, BOTTOM_DP);
            View box = composer(owner);
            if (box != null && box.isShown() && box.getHeight() > 0 && parent.getHeight() > 0) {
                int[] at = new int[2];
                int[] frame = new int[2];
                box.getLocationInWindow(at);
                parent.getLocationInWindow(frame);
                int above = frame[1] + parent.getHeight() - at[1];
                if (above > 0 && above < parent.getHeight()) bottom = above;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) seek.getLayoutParams();
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom;
                seek.setLayoutParams(params);
            }
        }

        @Nullable
        private View composer(Object owner) {
            View known = composer == null ? null : composer.get();
            if (known != null && known.isAttachedToWindow()) return known;
            View view = player.view(owner);
            if (view == null) return null;
            int id = ids.resolve(view.getResources(), view.getContext().getPackageName(), COMPOSER, false);
            if (id == 0) return null;
            View found = view.findViewById(id);
            composer = found == null ? null : new WeakReference<>(found);
            return found;
        }

        void remove() {
            removed = true;
            SeekBar seek = bar;
            bar = null;
            if (seek != null) {
                seek.setOnSeekBarChangeListener(null);
                ViewGroup parent = (ViewGroup) seek.getParent();
                if (parent != null) parent.removeView(seek);
            }
            if (dragging) {
                dragging = false;
                Object owner = viewer.get();
                if (owner != null) resume(owner);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seek) {
            Object owner = viewer.get();
            if (owner == null || !wanted()) return;
            try {
                dragging = player.pause(owner, PAUSE_REASON);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
            }
        }

        @Override
        public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seek) {
            Object owner = viewer.get();
            if (owner == null || !dragging) return;
            dragging = false;
            try {
                if (player.seekTo(owner, seek.getProgress())) {
                    HookStatus.counted(FamilyNames.STORY_SEEK_BAR, COUNT_SEEKS);
                }
            } catch (IllegalStateException live) {
                // A live story's player has nowhere to seek to; it plays on from where it is.
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
            }
            resume(owner);
        }

        private void resume(Object owner) {
            try {
                player.resume(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
            }
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
