/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.feed.FeedSeekBar;
import app.hushgram.extension.instagram.feed.GlassSeekBar;
import app.hushgram.extension.instagram.feed.SeekPreview;
import app.hushgram.extension.instagram.feed.SeekShapes;
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
 * where the story's video is, following a playing one every frame in between. A photo has no length,
 * so the bar hides for it. Dragging pauses the
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

    /**
     * Your own story's toolbar (#490): Activity with the viewers' faces, Share, Send, Mention and
     * More. The story's item binder finds it in the same toolbar as the Send message row, so it's
     * normally inside {@link #FOOTER}; it's looked up on its own in case a build moves it out.
     */
    static final String SELF_TOOLBAR = "self_reel_item_toolbar_container";

    /**
     * The "Say something..." line story comments put just above your own story's toolbar (#490).
     * It isn't part of the toolbar, so the bar has to clear it too, but only while it sits in the
     * bottom half of the viewer.
     */
    static final String COMMENT_LINE = "story_comments_add_comment_text";

    /** What the bar sits above: the highest of these on screen. */
    private static final String[] ANCHORS = {FOOTER, SELF_TOOLBAR, COMPOSER, COMMENT_LINE};

    /** How often the bar catches up with the video, finds its place and takes the look chosen. */
    static final long TICK_MS = 250;

    /**
     * While the video plays the bar also follows it every frame (#689), and when the player reports
     * its position in steps the bar glides on from the last one for up to this long, in ms. A position
     * that doesn't move for longer is a paused video, and the bar settles where it is.
     */
    static final long GLIDE_MS = 150;

    /** The fastest the bar takes a story's video to play, in ms of video a ms, past any speed there is. */
    static final float FASTEST = 4f;

    /** What the pause tells Instagram, the reason a press and hold gives. */
    static final String PAUSE_REASON = "long_pressed";

    /** The bar's height, and how far above the bottom it sits when there's no Send message box. */
    static final int HEIGHT_DP = 32;
    static final int BOTTOM_DP = 64;

    /**
     * Placed below the Send message box (#636), the bar shrinks to the room under it down to this, in
     * dp, which still holds every shape's handle. With less room it stays above the box.
     */
    static final int BELOW_MIN_DP = 16;

    static final String HOOK = "story seek bar";
    static final String COUNT_SEEKS = "story seeks";
    static final String COUNT_TIME_FLIPS = "story seek time flips";

    /**
     * The time (#784) sits at the start of the bar, this far in at least, in dp, and this far above
     * the bar's box, or above the story's bottom row when the bar is placed under it.
     */
    static final int TIME_START_DP = 8;
    static final int TIME_GAP_DP = 2;

    /** The time's text size, as the reel's time label has it. */
    static final float TIME_SP = 12f;

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
    /** What tests say the system's bars cover at the window's bottom, in pixels, or -1 to read the window. */
    private static int systemBottomForTests = -1;
    /** What tests say the swipe area covers at the window's bottom, in pixels, or -1 for just the bars. */
    private static int swipeBottomForTests = -1;
    private static final ResourceIdCache ids = new ResourceIdCache();
    @Nullable private static Overlay current;
    private static volatile boolean logged;

    private StorySeekBar() {
    }

    /** Whether the bar shows now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.STORY_SEEK_BAR.get();
    }

    /** Whether the time shows at the start of a showing bar (#784): its switch on as well. */
    static boolean timeWanted() {
        return wanted() && Settings.STORY_SEEK_TIME.get();
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
        ids.putForTests(Utils.resourcePackage(context), COMPOSER, id);
    }

    @VisibleForTesting
    static void footerIdForTests(android.content.Context context, int id) {
        ids.putForTests(Utils.resourcePackage(context), FOOTER, id);
    }

    @VisibleForTesting
    static void idForTests(android.content.Context context, String name, int id) {
        ids.putForTests(Utils.resourcePackage(context), name, id);
    }

    @VisibleForTesting
    static void systemBottomForTests(int pixels) {
        systemBottomForTests = pixels;
    }

    @VisibleForTesting
    static void swipeBottomForTests(int pixels) {
        swipeBottomForTests = pixels;
    }

    @VisibleForTesting
    @Nullable
    static SeekBar barForTests() {
        Overlay overlay = current;
        return overlay == null ? null : overlay.bar;
    }

    @VisibleForTesting
    @Nullable
    static TextView timeForTests() {
        Overlay overlay = current;
        return overlay == null ? null : overlay.time;
    }

    @VisibleForTesting
    static void resetForTests() {
        Overlay overlay = current;
        if (overlay != null) overlay.remove();
        current = null;
        player = PATCHED;
        systemBottomForTests = -1;
        swipeBottomForTests = -1;
        ids.clear();
        logged = false;
    }

    /** One viewer's bar and its ticking. */
    private static final class Overlay implements SeekBar.OnSeekBarChangeListener, Runnable {
        final WeakReference<Object> viewer;
        @Nullable GlassSeekBar bar;
        /** The view found for each of {@link #ANCHORS}, by index. */
        @SuppressWarnings("unchecked")
        final WeakReference<View>[] anchors = new WeakReference[ANCHORS.length];
        boolean dragging;
        boolean removed;
        /** The player's position when it last moved, or -1 before it's known, the time of that, and how fast it moved then. */
        int known = -1;
        long knownAt;
        float rate;
        /** Whether a frame is coming that follows the video. */
        boolean framing;
        final Runnable frame = this::frame;
        /**
         * The rows this bar lifted to make room under them (#636), each with the translation it had
         * before, and how far up they went. Weak, since Instagram drops a story's views as it pages.
         */
        final Map<View, Float> lifted = new WeakHashMap<>();
        int liftedBy;
        /**
         * The time at the start of the bar (#784), while its switch is on, and how far up it sits: right
         * over the bar's box, or over the story's bottom row when the bar sits under it with less room
         * than the time needs between the two ({@link #timeOverRows}, {@link #roomOverBar}). Only what the
         * rows draw over the time's own width counts ({@link #timeRows}), so a row with just its heart and
         * share buttons at the end leaves the time right over the bar.
         */
        @Nullable TextView time;
        int timeBottom;
        int timeOverRows = -1;
        int roomOverBar = Integer.MAX_VALUE;
        final ArrayList<WeakReference<View>> timeRows = new ArrayList<>();

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
            seek.shape(SeekShapes.wanted(), false);
            if (!dragging) {
                if (seek.getMax() != duration) seek.setMax(duration);
                follow(seek, player.position(owner), duration);
            }
            place(owner, seek);
            if (seek.getVisibility() != View.VISIBLE) seek.setVisibility(View.VISIBLE);
            showTime(seek);
        }

        /**
         * Puts the bar where the video is at [position] (#689). A position that moved on since the
         * last one sets how fast the video plays, and while it plays a frame after this follows it
         * again. Between the player's new positions the bar glides on at that speed for up to
         * {@link #GLIDE_MS}; a position standing still longer than that is a paused video, so the bar
         * settles on it and stops following frames until a tick sees the video move.
         */
        private void follow(SeekBar seek, int position, int duration) {
            long now = SystemClock.uptimeMillis();
            int at = Math.max(0, Math.min(duration, position));
            if (at != known) {
                float moved = known >= 0 && now > knownAt ? (at - known) / (float) (now - knownAt) : 0f;
                rate = moved > 0f ? Math.min(moved, FASTEST) : 0f;
                known = at;
                knownAt = now;
                if (seek.getProgress() != at) seek.setProgress(at);
            } else if (rate <= 0f || now - knownAt > GLIDE_MS) {
                rate = 0f;
                if (seek.getProgress() != at) seek.setProgress(at);
            } else {
                int ahead = Math.min(duration, at + Math.round(rate * (now - knownAt)));
                if (ahead > seek.getProgress()) seek.setProgress(ahead);
            }
            if (rate > 0f && !framing) {
                framing = true;
                seek.postOnAnimation(frame);
            }
        }

        /** One frame of following a playing video. */
        private void frame() {
            framing = false;
            SeekBar seek = bar;
            Object owner = viewer.get();
            if (removed || dragging || seek == null || owner == null || rate <= 0f
                    || seek.getVisibility() != View.VISIBLE) {
                return;
            }
            try {
                follow(seek, player.position(owner), seek.getMax());
            } catch (Throwable failure) {
                rate = 0f;
                HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
            }
        }

        /** Forgets where the video was, so a jump a drag made isn't taken for its speed. */
        private void forget() {
            known = -1;
            rate = 0f;
        }

        /** Hides the bar, and plays the story on if it hid under a finger mid-drag. */
        private void hide(Object owner) {
            forget();
            lift(new ArrayList<>(), 0);
            if (bar != null && bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
            if (time != null && time.getVisibility() != View.GONE) time.setVisibility(View.GONE);
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

        /**
         * Keeps the bar just above the story's bottom row while it shows, else a little above the
         * bottom. The row is the highest of {@link #ANCHORS} on screen, so on your own story the bar
         * clears the toolbar and the "Say something..." line over it, and on someone else's the Send
         * message row.
         *
         * <p>Placed below (#636), the bar sits in the middle of the space between the lowest row's
         * bottom and the system's bars or swipe area, as tall as fits there up to {@link #HEIGHT_DP},
         * so it never covers the row. A row's bottom padding counts as free, since an edge-to-edge
         * row pads itself clear of the swipe area, and so does the strip under the row's box and
         * buttons where it draws nothing. With less than {@link #BELOW_MIN_DP} free, the rows are
         * lifted just enough to make that room above the swipe area, and the bar sits under them at
         * that height. When the row's box is already under the system's bars or the keyboard,
         * nothing is lifted and the bar stays above.
         */
        private void place(Object owner, SeekBar seek) {
            ViewGroup parent = (ViewGroup) seek.getParent();
            if (parent == null) return;
            int height = dp(seek, HEIGHT_DP);
            int bottom = dp(seek, BOTTOM_DP);
            ArrayList<View> rows = new ArrayList<>();
            ArrayList<View> boxed = new ArrayList<>();
            int lift = 0;
            int highest = 0;
            int under = -1;
            boolean underRows = false;
            if (parent.getHeight() > 0) {
                findAnchors(owner, parent);
                int[] frame = new int[2];
                parent.getLocationInWindow(frame);
                for (int i = 0; i < ANCHORS.length; i++) {
                    View box = anchors[i] == null ? null : anchors[i].get();
                    if (box == null) continue;
                    int[] at = new int[2];
                    box.getLocationInWindow(at);
                    // Where the row would be without this bar's lift, so the room under it doesn't feed on itself.
                    int above = frame[1] + parent.getHeight() - at[1] - (liftedHere(box) ? liftedBy : 0);
                    if (above <= 0 || above >= parent.getHeight()) continue;
                    if (ANCHORS[i].equals(COMMENT_LINE)) {
                        if (above * 2 > parent.getHeight()) continue;
                    } else {
                        int free = Math.max(box.getPaddingBottom(), emptyBelow(box));
                        int below = Math.max(0, above - box.getHeight() + free);
                        under = under < 0 ? below : Math.min(under, below);
                        boxed.add(box);
                    }
                    highest = Math.max(highest, above);
                    rows.add(box);
                }
                if (highest > 0) bottom = highest;
                if (under >= 0 && Settings.STORY_SEEK_BAR_POSITION.get() == StorySeekBarPosition.BELOW) {
                    int window = seek.getRootView().getHeight();
                    int frameBottom = frame[1] + parent.getHeight();
                    // The bars and keyboard hide what's under them; the swipe area only takes the drags.
                    int hidden = Math.max(0, frameBottom - (window - systemBottom(seek, false)));
                    int covered = Math.max(hidden, frameBottom - (window - systemBottom(seek, true)));
                    int room = under - covered;
                    int least = dp(seek, BELOW_MIN_DP);
                    if (room >= least) {
                        height = Math.min(height, room);
                        bottom = covered + (room - height) / 2;
                        underRows = true;
                    } else if (under >= hidden) {
                        lift = least - room;
                        height = least;
                        bottom = covered;
                        underRows = true;
                    }
                }
            }
            lift(outermost(rows), lift);
            // The time sits right over the bar. Under the rows it needs room between the bar and the rows'
            // bottom, where they'll be after the lift; with too little it goes over the rows instead (#784).
            int gap = dp(seek, TIME_GAP_DP);
            timeBottom = bottom + height + gap;
            timeOverRows = underRows ? highest + lift + gap : -1;
            roomOverBar = underRows ? under + lift - (bottom + height) : Integer.MAX_VALUE;
            timeRows.clear();
            if (underRows) {
                for (View box : boxed) timeRows.add(new WeakReference<>(box));
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) seek.getLayoutParams();
            if (params.bottomMargin != bottom || params.height != height) {
                params.bottomMargin = bottom;
                params.height = height;
                seek.setLayoutParams(params);
            }
        }

        /**
         * Fills {@link #anchors} with each of {@link #ANCHORS} on screen over [parent], or null, in
         * one walk of the viewer. While a swipe moves to the next person's stories, both pages hold
         * one, so the one overlapping [parent] most wins. The one found last is kept while it still
         * covers half of [parent], so the bar doesn't hop between the two pages mid-swipe.
         */
        private void findAnchors(Object owner, ViewGroup parent) {
            int[] wanted = new int[ANCHORS.length];
            boolean any = false;
            View view = null;
            for (int i = 0; i < ANCHORS.length; i++) {
                View known = anchors[i] == null ? null : anchors[i].get();
                int kept = known == null ? 0 : overlap(known, parent);
                if (kept > 0 && kept * 2 >= Math.min(known.getWidth(), parent.getWidth())) continue;
                anchors[i] = null;
                if (view == null) view = player.view(owner);
                if (view == null) return;
                wanted[i] = ids.resolve(view.getResources(), Utils.resourcePackage(view.getContext()), ANCHORS[i], false);
                any |= wanted[i] != 0;
            }
            if (!any) return;
            ArrayList<View> found = new ArrayList<>();
            collect(view, wanted, found);
            int[] most = new int[ANCHORS.length];
            for (View candidate : found) {
                int across = overlap(candidate, parent);
                if (across <= 0) continue;
                for (int i = 0; i < ANCHORS.length; i++) {
                    if (wanted[i] == 0 || candidate.getId() != wanted[i] || across <= most[i]) continue;
                    most[i] = across;
                    anchors[i] = new WeakReference<>(candidate);
                }
            }
        }

        /** Whether [view] is, or sits in, a row this bar still holds lifted. */
        private boolean liftedHere(View view) {
            for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
                if (holds((View) at)) return true;
            }
            return false;
        }

        /** Whether [row] is lifted by this bar and Instagram hasn't moved it since. */
        private boolean holds(View row) {
            Float from = lifted.get(row);
            return from != null && row.getTranslationY() == from - liftedBy;
        }

        /** The [rows] not inside another of them, which move their insides along. */
        private ArrayList<View> outermost(ArrayList<View> rows) {
            ArrayList<View> outer = new ArrayList<>();
            for (View row : rows) {
                boolean inside = false;
                for (Object at = row.getParent(); at instanceof View && !inside; at = ((View) at).getParent()) {
                    inside = rows.contains(at);
                }
                if (!inside && !outer.contains(row)) outer.add(row);
            }
            return outer;
        }

        /**
         * Keeps [rows] lifted [by] pixels and puts back every other row this bar lifted (#636). A row
         * Instagram moved since is let go as it is, since it's placing that row itself.
         */
        private void lift(ArrayList<View> rows, int by) {
            for (Map.Entry<View, Float> entry : new ArrayList<>(lifted.entrySet())) {
                View row = entry.getKey();
                if (row == null) continue;
                boolean held = holds(row);
                if (held && by == liftedBy && rows.contains(row)) continue;
                if (held) row.setTranslationY(entry.getValue());
                lifted.remove(row);
            }
            if (lifted.isEmpty()) liftedBy = 0;
            if (by <= 0) return;
            liftedBy = by;
            for (View row : rows) {
                if (lifted.containsKey(row)) continue;
                float from = row.getTranslationY();
                lifted.put(row, from);
                row.setTranslationY(from - by);
            }
        }

        /**
         * Puts the time at the start of [seek] (#784), {@link #timeBottom} up, the text's start where the
         * bar's line starts, or takes it away with its switch off.
         */
        private void showTime(SeekBar seek) {
            if (!timeWanted()) {
                removeTime();
                return;
            }
            ViewGroup parent = (ViewGroup) seek.getParent();
            if (parent == null) return;
            TextView shown = time;
            if (shown == null) {
                shown = FeedSeekBar.timeLabel(seek.getContext(), TIME_SP);
                shown.setOnClickListener(clicked -> flipTime());
                time = shown;
            }
            if (shown.getParent() != parent) {
                if (shown.getParent() != null) ((ViewGroup) shown.getParent()).removeView(shown);
                parent.addView(shown, parent.indexOfChild(seek) + 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.START));
            }
            int start = Math.max(dp(seek, TIME_START_DP), seek.getPaddingStart() - shown.getPaddingStart());
            int over = timeBottomFor(shown, start);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) shown.getLayoutParams();
            if (params.bottomMargin != over || params.getMarginStart() != start) {
                params.bottomMargin = over;
                params.setMarginStart(start);
                shown.setLayoutParams(params);
            }
            if (shown.getVisibility() != View.VISIBLE) shown.setVisibility(View.VISIBLE);
            showTimeAt(seek.getProgress());
        }

        /**
         * How far up the time [shown], [start] pixels in from the start edge, sits: right over the bar
         * while it fits under what the rows draw over its width, which a bar placed below them leaves
         * it, else over the rows.
         */
        private int timeBottomFor(TextView shown, int start) {
            if (timeOverRows < 0) return timeBottom;
            int tall = shown.getHeight();
            int wide = shown.getWidth();
            if (tall <= 0 || wide <= 0) {
                shown.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                tall = shown.getMeasuredHeight();
                wide = shown.getMeasuredWidth();
            }
            int gap = dp(shown, TIME_GAP_DP);
            return roomAcross(shown, start, wide, gap) >= tall + gap ? timeBottom : timeOverRows;
        }

        /**
         * The room between the bar's box and the lowest thing {@link #timeRows} draw over the time's
         * width, [wide] pixels from [start] in from the start edge of the time's frame (#784). A row that
         * draws nothing there leaves room up to its top. {@link #roomOverBar} when no row is known.
         */
        private int roomAcross(TextView shown, int start, int wide, int gap) {
            ViewGroup parent = (ViewGroup) shown.getParent();
            if (parent == null || timeRows.isEmpty()) return roomOverBar;
            int[] frame = new int[2];
            parent.getLocationInWindow(frame);
            int frameBottom = frame[1] + parent.getHeight();
            int left = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? parent.getWidth() - start - wide : start;
            int lowest = -1;
            for (WeakReference<View> held : timeRows) {
                View box = held.get();
                if (box == null) continue;
                int[] at = new int[2];
                box.getLocationInWindow(at);
                int drawn = drawnBottomAcross(box, 0, left - (at[0] - frame[0]), wide, 0);
                int below = frameBottom - at[1] - Math.max(0, drawn);
                lowest = lowest < 0 ? below : Math.min(lowest, below);
            }
            return lowest < 0 ? roomOverBar : lowest - (timeBottom - gap);
        }

        /** Writes the time at [at] into the label, when there is one. */
        private void showTimeAt(int at) {
            TextView shown = time;
            SeekBar seek = bar;
            if (shown == null || seek == null) return;
            String text = FeedSeekBar.timeText(at, seek.getMax(), Settings.STORY_SEEK_TIME_LEFT.get());
            if (!text.contentEquals(shown.getText())) shown.setText(text);
        }

        /** A tap on the time flips it between the time played and the time left, through its own switch. */
        private void flipTime() {
            try {
                if (removed || !timeWanted()) return;
                Settings.STORY_SEEK_TIME_LEFT.save(!Settings.STORY_SEEK_TIME_LEFT.get());
                HookStatus.counted(FamilyNames.STORY_SEEK_BAR, COUNT_TIME_FLIPS);
                SeekBar seek = bar;
                if (seek != null) showTimeAt(seek.getProgress());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
            }
        }

        private void removeTime() {
            TextView shown = time;
            time = null;
            if (shown == null) return;
            shown.setOnClickListener(null);
            ViewGroup parent = (ViewGroup) shown.getParent();
            if (parent != null) parent.removeView(shown);
        }

        void remove() {
            removed = true;
            lift(new ArrayList<>(), 0);
            removeTime();
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
            forget();
            try {
                dragging = player.pause(owner, PAUSE_REASON);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_SEEK_BAR, HOOK, failure);
            }
        }

        @Override
        public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
            if (fromUser && dragging) SeekPreview.dragged(seek, progress, null, StorySpeed.current(), FamilyNames.STORY_SEEK_BAR);
            showTimeAt(progress);
        }

        @Override
        public void onStopTrackingTouch(SeekBar seek) {
            Object owner = viewer.get();
            if (owner == null || !dragging) return;
            dragging = false;
            forget();
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

    /** Every view under [view], itself included, whose id is one of [ids] other than 0. */
    private static void collect(View view, int[] ids, List<View> into) {
        int own = view.getId();
        if (own != View.NO_ID) {
            for (int id : ids) {
                if (id != 0 && id == own) {
                    into.add(view);
                    break;
                }
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), ids, into);
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

    /**
     * How much of [row]'s bottom has nothing drawn in it, in pixels (#636): Instagram's Send message
     * row keeps a strip under its box and buttons, which is room for the bar. Anything with a
     * background counts as drawn whole, so a row with its own background, or with nothing drawn in
     * it, gives 0.
     */
    static int emptyBelow(View row) {
        int lowest = drawnBottom(row, 0, 0);
        return lowest < 0 ? 0 : Math.max(0, row.getHeight() - lowest);
    }

    /** The lowest edge of what [view] draws, [top] pixels down from the row's top, or -1 for nothing. */
    private static int drawnBottom(View view, int top, int depth) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) return -1;
        int bottom = top + view.getHeight();
        if (!(view instanceof ViewGroup) || view.getBackground() != null || depth >= 16) return bottom;
        ViewGroup group = (ViewGroup) view;
        int lowest = -1;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            int at = top + child.getTop() + Math.round(child.getTranslationY());
            lowest = Math.max(lowest, drawnBottom(child, at, depth + 1));
        }
        return lowest;
    }

    /**
     * The lowest edge of what [view] draws across a span [wide] pixels from [from] pixels in from its
     * left, [top] pixels down from the row's top, or -1 for nothing there (#784). A group with a
     * background draws all of itself, as {@link #drawnBottom} reads it.
     */
    static int drawnBottomAcross(View view, int top, int from, int wide, int depth) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) return -1;
        if (from >= view.getWidth() || from + wide <= 0) return -1;
        int bottom = top + view.getHeight();
        if (!(view instanceof ViewGroup) || view.getBackground() != null || depth >= 16) return bottom;
        ViewGroup group = (ViewGroup) view;
        int lowest = -1;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            int at = top + child.getTop() + Math.round(child.getTranslationY());
            int into = from - child.getLeft() - Math.round(child.getTranslationX());
            lowest = Math.max(lowest, drawnBottomAcross(child, at, into, wide, depth + 1));
        }
        return lowest;
    }

    /**
     * How far up from the window's bottom the system's bars and any keyboard reach, in pixels, and
     * with [swipe] the swipe area along the bottom too, where a drag goes home instead of to the bar.
     * On gesture navigation that area reaches well above the bar at the bottom (#636).
     */
    @SuppressWarnings("deprecation")
    private static int systemBottom(View view, boolean swipe) {
        if (systemBottomForTests >= 0 || swipeBottomForTests >= 0) {
            int bars = Math.max(0, systemBottomForTests);
            return swipe ? Math.max(bars, swipeBottomForTests) : bars;
        }
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int types = WindowInsets.Type.systemBars() | WindowInsets.Type.ime();
            if (swipe) types |= WindowInsets.Type.mandatorySystemGestures();
            return insets.getInsets(types).bottom;
        }
        int bottom = insets.getSystemWindowInsetBottom();
        if (swipe && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            bottom = Math.max(bottom, insets.getMandatorySystemGestureInsets().bottom);
        }
        return bottom;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
