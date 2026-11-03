/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.feed.GlassSeekBar;
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
 * the row along the story's bottom ({@link #FOOTER}) when that's showing, under any sheet opened over
 * the story, and every {@link #TICK_MS} it shows
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
    /**
     * The row along the bottom of a story on Instagram 453 (#486), which the bar sits above: the Send
     * message box with the like and share buttons, or your own story's toolbar. A larger font or
     * display size makes it taller, so the bar follows its top rather than a fixed height.
     */
    static final String FOOTER = "reel_item_toolbar_footer_container";

    /**
     * The Send message box as builds before 453 name it, for when there's no {@link #FOOTER}. On 453
     * it sits in a stub that's never inflated.
     */
    static final String COMPOSER = "reel_viewer_message_composer_container";

    /** What the bar sits above, the first name found on screen. */
    private static final String[] ANCHORS = {FOOTER, COMPOSER};

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
    static void footerIdForTests(android.content.Context context, int id) {
        ids.putForTests(context.getPackageName(), FOOTER, id);
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
        @Nullable GlassSeekBar bar;
        @Nullable WeakReference<View> anchor;
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
            GlassSeekBar seek = bar != null ? bar : make(owner);
            if (seek == null) return;
            seek.look(Settings.STORY_SEEK_BAR_LOOK.get());
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
        private GlassSeekBar make(Object owner) {
            View view = player.view(owner);
            if (view == null || !(view.getParent() instanceof FrameLayout)) return null;
            FrameLayout container = (FrameLayout) view.getParent();
            HookStatus.invoked(FamilyNames.STORY_SEEK_BAR);
            GlassSeekBar seek = new GlassSeekBar(view.getContext(), FamilyNames.STORY_SEEK_BAR, HOOK);
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

        /** Keeps the bar just above the story's bottom row while it shows, else a little above the bottom. */
        private void place(Object owner, SeekBar seek) {
            ViewGroup parent = (ViewGroup) seek.getParent();
            if (parent == null) return;
            int bottom = dp(seek, BOTTOM_DP);
            View box = parent.getHeight() > 0 ? anchor(owner, parent) : null;
            if (box != null) {
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

        /**
         * The story's bottom row on screen over [parent], by the first of {@link #ANCHORS} found there,
         * or null. While a swipe moves to the next person's stories, both pages hold a row, so the one
         * overlapping [parent] most wins. The row found last is kept while it still covers half of
         * [parent], so the bar doesn't hop between the two rows mid-swipe.
         */
        @Nullable
        private View anchor(Object owner, ViewGroup parent) {
            View known = anchor == null ? null : anchor.get();
            int kept = known == null ? 0 : overlap(known, parent);
            if (kept > 0 && kept * 2 >= Math.min(known.getWidth(), parent.getWidth())) return known;
            anchor = null;
            View view = player.view(owner);
            if (view == null) return null;
            ArrayList<View> found = new ArrayList<>();
            for (String name : ANCHORS) {
                int id = ids.resolve(view.getResources(), view.getContext().getPackageName(), name, false);
                if (id == 0) continue;
                found.clear();
                collect(view, id, found);
                View best = null;
                int most = 0;
                for (View candidate : found) {
                    int across = overlap(candidate, parent);
                    if (across > most) {
                        best = candidate;
                        most = across;
                    }
                }
                if (best != null) {
                    anchor = new WeakReference<>(best);
                    return best;
                }
            }
            return null;
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

    /** Every view under [view], itself included, whose id is [id]. */
    private static void collect(View view, int id, List<View> into) {
        if (view.getId() == id) into.add(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), id, into);
    }

    /** How far across [view] covers [parent] from side to side, in pixels, or 0 when it doesn't show. */
    private static int overlap(View view, View parent) {
        if (!view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) return 0;
        int[] at = new int[2];
        int[] frame = new int[2];
        view.getLocationInWindow(at);
        parent.getLocationInWindow(frame);
        return Math.max(0, Math.min(at[0] + view.getWidth(), frame[0] + parent.getWidth()) - Math.max(at[0], frame[0]));
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
