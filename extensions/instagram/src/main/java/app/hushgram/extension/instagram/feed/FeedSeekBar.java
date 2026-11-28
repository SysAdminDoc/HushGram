/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Seek bar on feed videos patch (#389).
 *
 * <p>The feed's video player, IgFeedVideoPlayer, tells {@link #progress} where the video it's playing
 * is each time it moves on. A seek bar then sits along the bottom of that video, in the frame holding
 * the video and its cover, just over the cover, and follows it. Every {@link #TICK_MS} the bar asks the
 * player for its video's cover again: when the feed hands the player another video the bar moves there,
 * and while the player has none or the cover's frame is off screen the bar hides. The cover itself is
 * hidden while the video plays, so only its frame counts. A paused video keeps its bar. A drag goes
 * the way Instagram's own feed scrubber goes: the video pauses while a finger is on the bar, and
 * letting go moves it to that point and plays it on from there (#472). Seeking alone, as the bar
 * once did, could leave the video stuck on the frame it was moved to. Most feed posts come without
 * the picture strip a reel has, so with the preview on, the paused video itself follows the drag
 * there and shows the moment the finger is on (#567).
 *
 * <p>Instagram keeps buttons along the bottom of a feed video, tags at the start and sound at the end,
 * each in a row of its own. While the bar shows, those rows are lifted by the bar's height, so the
 * buttons sit over the bar instead of covering its ends, and the toggle button lines up beside the
 * tags button, past it (#794). The rows are put back where they were when the bar hides or moves to
 * another video.
 *
 * <p>With the switch off, HushGram paused or the settings not read, the bar hides and a drag does
 * nothing. A bar hidden for {@link #GONE_MS} goes away and stops ticking until the player plays
 * again. Anything thrown leaves the video as Instagram plays it.
 */
public final class FeedSeekBar {
    /** How often the bar checks the player still shows its video. */
    static final long TICK_MS = 250;

    /** How long a hidden bar waits for its video to come back before it goes away. */
    static final long GONE_MS = 2000;

    /** The least time between two moves of a paused video following a drag. */
    static final long FOLLOW_MS = 150;

    /** The bar's height, along the bottom of the video. */
    static final int HEIGHT_DP = 32;

    /** The least the bar is tall on the bottom edge (#567), where it's only as tall as its round handle. */
    static final int EDGE_MIN_DP = 12;

    /** How tall the bar is on the bottom edge with a {@linkplain SeekShape#tallHandle() tall handle}, which needs the room. */
    static final int EDGE_TALL_DP = 22;

    static final String HOOK = "feed seek bar";
    static final String COUNT_SEEKS = "feed seeks";
    static final String COUNT_TOGGLES = "feed seek bar toggles";
    static final String COUNT_TIME_FLIPS = "feed seek time flips";

    /** The gap between the bar and the time over it (#711). */
    static final int TIME_GAP_DP = 2;

    /**
     * The time's text size on a feed video, small enough to sit between the bar and Instagram's tags
     * and sound buttons, which go up above it (#711).
     */
    static final float TIME_SP = 10f;

    /** The toggle button's size, a small circle at the bottom start of the video (#437). */
    static final int BUTTON_DP = 28;

    /** The gap between the toggle and a button of Instagram's beside it (#794). */
    static final int BUTTON_GAP_DP = 4;

    /**
     * Instagram's sound button on a feed video. The row along the video's bottom holding it holds the
     * tags button too, and the bar lifts that row clear of itself (#794).
     */
    static final String SOUND_BUTTON = "slideout_iconview_icon";

    /** How far above the video's frame the row is looked for: the post's media holds both. */
    static final int ROW_LEVELS = 2;

    /**
     * How far above a carousel video's frame its sound button is looked for (#711): Instagram 456 puts
     * the video four wrappers, each the video's size, under the post's row that also holds the overlay
     * with the sound button's corner.
     */
    static final int CAROUSEL_LEVELS = 4;

    /**
     * The post's menu button, three dots in its name row. A reel on Home lays that row over the top of
     * the video, and the time in the top corner goes under the button there (#788).
     */
    static final String MENU_BUTTON = "media_option_button";

    /** How far above the video's frame the post's menu button is looked for, short of the feed's list. */
    static final int MENU_LEVELS = 8;

    private static final ResourceIdCache ids = new ResourceIdCache();

    /** The feed's video player, through the patch's stubs; tests stand in. */
    interface Player {
        @Nullable View view(Object player);
        boolean seekTo(Object player, int positionMs);
        boolean pause(Object player);
        boolean play(Object player);
        /** The picture strip of the post the player has now, for the preview while a drag moves. */
        @Nullable default SeekSheet sheet(Object player) { return null; }
    }

    static final Player PATCHED = new Player() {
        @Override public View view(Object player) { return FeedSeekReader.view(player); }
        @Override public boolean seekTo(Object player, int positionMs) { return FeedSeekReader.seekTo(player, positionMs, true); }
        @Override public boolean pause(Object player) { return FeedSeekReader.pause(player, null) != null; }
        @Override public boolean play(Object player) { return FeedSeekReader.play(player, null, null, null) != null; }
        @Override public SeekSheet sheet(Object player) { return SeekSheet.ofMedia(FeedSeekReader.media(player)); }
    };

    private static Player feed = PATCHED;
    @Nullable private static Overlay current;
    private static volatile boolean logged;

    private FeedSeekBar() {
    }

    /** Whether the bar shows now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return Utils.settingsReady() && Settings.FEED_SEEK_BAR.get();
    }

    /** Whether the toggle button shows: its switch on, HushGram not paused, the settings read (#437). */
    static boolean buttonWanted() {
        return Utils.settingsReady() && Settings.FEED_SEEK_BUTTON.get();
    }

    /** Whether the time shows over a showing bar: its switch on, HushGram not paused, the settings read (#711). */
    static boolean timeWanted() {
        return Utils.settingsReady() && Settings.FEED_SEEK_TIME.get();
    }

    /**
     * Whether the time goes in the video's top corner (#788): Time in the top corner on, and Time under
     * the tags button off, since that one wins.
     */
    static boolean timeTopWanted() {
        return Settings.FEED_SEEK_TIME_TOP.isAvailable() && Settings.FEED_SEEK_TIME_TOP.get();
    }

    /**
     * The time over the bar: the time played, past the end counted as the end, and the length, "0:12 / 0:45",
     * or with [left] the time still to go, "-0:33 / 0:45". The story seek bar's time reads it too (#784).
     */
    public static String timeText(int at, int length, boolean left) {
        int played = Math.max(0, Math.min(at, length));
        String total = SeekPreview.time(Math.max(0, length));
        return (left ? "-" + SeekPreview.time(length - played) : SeekPreview.time(played)) + " / " + total;
    }

    /**
     * The time's label, white bold text [sp] big with a shadow over the video, a tap on it flipping what
     * it counts. The story seek bar's time is one too (#784).
     */
    public static TextView timeLabel(Context context, float sp) {
        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.round(4 * density);
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.WHITE);
        // Over the video, the shadow keeps white text readable on a bright frame.
        label.setShadowLayer(2f * density, 0f, 0.5f * density, 0x99000000);
        // Digits all as wide as each other, so the time doesn't wobble as it counts.
        label.setFontFeatureSettings("tnum");
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(gap, gap / 2, gap, gap / 2);
        label.setClickable(true);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return label;
    }

    /**
     * The time showing over the bar on a feed video now, or null. A carousel's dots pill goes above it too.
     * Null too while the time sits in the video's top corner (#788), away from the bar.
     */
    @Nullable
    static View shownTime() {
        Overlay overlay = current;
        TextView label = overlay == null || overlay.removed || overlay.timeOnTop ? null : overlay.time;
        return label != null && label.getVisibility() == View.VISIBLE && label.getParent() != null ? label : null;
    }

    /** Whether a video gets an overlay at all: the bar or its toggle button is wanted. */
    static boolean active() {
        return wanted() || buttonWanted();
    }

    /**
     * Called first thing in the feed video player's progress callback, with the player and where its
     * video is and how long it is, in milliseconds. Never throws.
     */
    public static void progress(Object player, int position, int duration) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Utils.runOnMainThread(() -> progress(player, position, duration));
                return;
            }
            FeedDoubleTapSkip.fed(player, position, duration);
            Overlay overlay = current;
            if (overlay == null || overlay.removed || overlay.player.get() != player) {
                if (player == null || !active()) return;
                if (overlay != null) overlay.remove();
                overlay = new Overlay(player);
                current = overlay;
                overlay.fed(position, duration);
                overlay.tick();
                return;
            }
            overlay.fed(position, duration);
            overlay.update(player);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
        }
    }

    /** The bar showing on a feed video now, or null. A carousel's dots pill goes above it (#471). */
    @Nullable
    static View shownBar() {
        Overlay overlay = current;
        GlassSeekBar seek = overlay == null || overlay.removed ? null : overlay.bar;
        return seek != null && seek.getVisibility() == View.VISIBLE && seek.getParent() != null ? seek : null;
    }

    /** The feed's player as the stubs reach it, which {@link FeedDoubleTapSkip} moves too. */
    static Player player() {
        return feed;
    }

    @VisibleForTesting
    static void playerForTests(Player stand) {
        feed = stand;
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
    @Nullable
    static View buttonForTests() {
        Overlay overlay = current;
        return overlay == null ? null : overlay.button;
    }

    /** Gives Instagram's [name] view id the value [id] in tests, where the app's ids aren't there. */
    @VisibleForTesting
    static void idForTests(Context context, String name, int id) {
        ids.putForTests(Utils.resourcePackage(context), name, id);
    }

    @VisibleForTesting
    static void resetForTests() {
        Overlay overlay = current;
        if (overlay != null) overlay.remove();
        current = null;
        feed = PATCHED;
        logged = false;
        ids.clear();
    }

    /** One player's bar and its ticking. */
    private static final class Overlay implements SeekBar.OnSeekBarChangeListener, View.OnTouchListener, Runnable {
        final WeakReference<Object> player;
        @Nullable GlassSeekBar bar;
        @Nullable Toggle button;
        @Nullable TextView time;
        /** Whether the time sits in the video's top corner, away from the bar (#788). */
        boolean timeOnTop;
        int position;
        int duration;
        long hiddenSince = -1;
        boolean dragging;
        /** Whether the drag under way paused the video, which letting go then plays on. */
        boolean held;
        boolean removed;
        /** Where a drag over a post with no picture strip moves the video next, where it last did, and when. */
        int follow = -1;
        int followed = -1;
        long followedAt = -1;
        boolean followWaits;
        /** Whether a finger on the bar holds the feed and the tabs, and where it landed. */
        boolean claimed;
        float downX;
        float downY;
        /**
         * Instagram's rows of buttons this bar lifted (#794), each with the translation it had, and how far
         * they went up.
         */
        final WeakHashMap<View, Float> lifted = new WeakHashMap<>();
        int liftedBy;

        Overlay(Object player) {
            this.player = new WeakReference<>(player);
        }

        void fed(int position, int duration) {
            this.position = position;
            this.duration = duration;
        }

        @Override
        public void run() {
            tick();
        }

        void tick() {
            if (removed) return;
            try {
                Object owner = player.get();
                if (owner == null) {
                    remove();
                    return;
                }
                update(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
            if (!removed) Utils.runOnMainThreadDelayed(this, TICK_MS);
        }

        void update(Object owner) {
            if (removed) return;
            View view = active() && duration > 0 ? feed.view(owner) : null;
            // The view is the video's cover, which Instagram hides while the video plays, so it's the
            // frame holding the cover and the video that has to be on screen.
            FrameLayout frame = view != null && view.getParent() instanceof FrameLayout ? (FrameLayout) view.getParent() : null;
            if (frame == null || !frame.isShown()) {
                hide();
                return;
            }
            GlassSeekBar seek = bar != null && bar.getParent() == frame ? bar : attach(frame, view);
            seek.look(Settings.FEED_SEEK_BAR_LOOK.get());
            SeekShape shape = SeekShapes.feedWanted();
            seek.shape(shape, Settings.FEED_SEEK_BAR_ON_EDGE.get() || shape == SeekShape.LINE);
            fit(seek, ViewGroup.LayoutParams.MATCH_PARENT, barHeight(view, seek), 0);
            hiddenSince = -1;
            if (!dragging) {
                if (seek.getMax() != duration) seek.setMax(duration);
                seek.setProgress(Math.max(0, Math.min(duration, position)));
            }
            int barState = wanted() ? View.VISIBLE : View.GONE;
            if (seek.getVisibility() != barState) seek.setVisibility(barState);
            if (barState == View.GONE) dragging = false;
            List<ViewGroup> rows = buttonRows(frame);
            int clear = buttonWanted() || timeWanted() ? startCornerEnd(frame, rows, seek, button, time) : -1;
            showTime(frame, view, seek, rows, clear);
            int lift = barState == View.VISIBLE && !rows.isEmpty() ? barHeight(view, seek) + clearOfTime(rows, view) : 0;
            lift(rows, lift);
            showButton(frame, view, rows, lift, clear);
        }

        /**
         * How much higher than the bar Instagram's [rows] go so their buttons end above the time showing
         * over the bar, a gap clear of it (#711): the time's height and a gap on each side of it, less the
         * room the rows already leave under their lowest button. Zero with no time showing, or with the
         * time up in the video's top corner (#788).
         */
        private int clearOfTime(List<ViewGroup> rows, View view) {
            TextView shown = time;
            if (shown == null || shown.getParent() == null || timeOnTop) return 0;
            int under = roomUnder(rows);
            if (under < 0) return 0;
            int height = shown.getHeight();
            if (height == 0) {
                int any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                shown.measure(any, any);
                height = shown.getMeasuredHeight();
            }
            return Math.max(0, height + 2 * dp(view, TIME_GAP_DP) - under);
        }

        /**
         * Keeps Instagram's [rows] of buttons, the sound button's row and the tags button's beside it
         * along the video's bottom, lifted [by] pixels so they sit over the bar instead of on it and a
         * touch at the bar's ends goes to the bar (#794). Rows lifted before are put back first, each
         * unless Instagram moved it since, which means it's placing that row itself.
         */
        private void lift(List<ViewGroup> rows, int by) {
            if (by > 0 && by == liftedBy && lifted.size() == rows.size()) {
                boolean holding = true;
                for (View row : rows) {
                    Float from = lifted.get(row);
                    if (from == null || row.getTranslationY() != from - liftedBy) holding = false;
                }
                if (holding) return;
            }
            for (Map.Entry<View, Float> held : new ArrayList<>(lifted.entrySet())) {
                View row = held.getKey();
                if (row != null && row.getTranslationY() == held.getValue() - liftedBy) row.setTranslationY(held.getValue());
            }
            lifted.clear();
            liftedBy = 0;
            if (rows.isEmpty() || by <= 0) return;
            liftedBy = by;
            for (View row : rows) {
                float from = row.getTranslationY();
                lifted.put(row, from);
                row.setTranslationY(from - by);
            }
        }

        /**
         * Puts the time over the middle of the bar in [frame], or takes it away while its switch is off
         * or the bar is hidden (#711). It sits in the middle, under Instagram's tags and sound buttons,
         * so a tags button that slides out wide over the middle still doesn't cover it. With Time under the
         * tags button on, it sits in the bottom start corner instead, lined up with the first button of
         * Instagram's [rows] ({@link #cornerStart}), or past Instagram's small views in that corner,
         * which end [clear] pixels in from the start (#891).
         */
        private void showTime(FrameLayout frame, View view, SeekBar seek, List<ViewGroup> rows, int clear) {
            if (!timeWanted() || seek.getVisibility() != View.VISIBLE) {
                removeTime();
                return;
            }
            TextView shown = time;
            if (shown == null) {
                shown = timeLabel(view.getContext(), TIME_SP);
                shown.setOnClickListener(tapped -> flipTime());
                time = shown;
            }
            boolean top = timeTopWanted();
            timeOnTop = top;
            int bottom = top ? 0 : barHeight(view, seek) + dp(view, TIME_GAP_DP);
            boolean corner = !top && Settings.FEED_SEEK_TIME_UNDER_TAGS.get();
            int gravity = top ? Gravity.TOP | Gravity.END
                    : Gravity.BOTTOM | (corner ? Gravity.START : Gravity.CENTER_HORIZONTAL);
            if (shown.getParent() != frame) {
                if (shown.getParent() != null) ((ViewGroup) shown.getParent()).removeView(shown);
                frame.addView(shown, frame.indexOfChild(seek) + 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity));
            }
            fit(shown, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom);
            int start = corner ? Math.max(0, cornerStart(frame, view, rows, clear) - shown.getPaddingStart()) : 0;
            int[] place = {0, 0};
            if (top) {
                topCorner(frame, view, shown, place);
                place[1] = Math.max(0, place[1] - shown.getPaddingEnd());
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) shown.getLayoutParams();
            if (params.gravity != gravity || params.getMarginStart() != start
                    || params.topMargin != place[0] || params.getMarginEnd() != place[1]) {
                params.gravity = gravity;
                params.setMarginStart(start);
                params.topMargin = place[0];
                params.setMarginEnd(place[1]);
                shown.setLayoutParams(params);
            }
            showTimeAt(dragging ? seek.getProgress() : position);
        }

        /**
         * Where the time goes in the video's top end corner with Time in the top corner on (#788),
         * written into [place] as its top margin and how far in from the end its text ends: a gap under
         * the lowest of Instagram's small views shown in that corner, like the countdown or a carousel's
         * count, with its text ending where the content of the one furthest toward the end does
         * ({@link #cornerViews}). With none there, 8 dp down and in.
         */
        private void topCorner(FrameLayout frame, View view, TextView shown, int[] place) {
            List<View> found = cornerViews(frame, shown, bar, button);
            if (found.isEmpty()) {
                place[0] = dp(view, 8);
                place[1] = dp(view, 8);
                return;
            }
            boolean rtl = frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int[] at = new int[2];
            frame.getLocationInWindow(at);
            int frameLeft = at[0];
            int frameTop = at[1];
            int lowest = 0;
            int inset = Integer.MAX_VALUE;
            for (View corner : found) {
                corner.getLocationInWindow(at);
                lowest = Math.max(lowest, at[1] - frameTop + corner.getHeight());
                // How far in from the video's end the view's content ends, inside its padding. The
                // one furthest out sets the line, so a Follow button left of the menu doesn't (#788).
                int end = rtl ? at[0] - frameLeft + corner.getPaddingLeft()
                        : frame.getWidth() - (at[0] - frameLeft + corner.getWidth() - corner.getPaddingRight());
                inset = Math.min(inset, Math.max(0, end));
            }
            place[0] = lowest + dp(view, TIME_GAP_DP);
            place[1] = inset;
        }

        /**
         * How far into the video from its start edge the time's text starts in the corner (#711): where
         * the first button on the start side of Instagram's [rows] starts, which is the tags button, or
         * with none there as far in as the last button on the end side sits from the end. Without a row,
         * the toggle button has the corner over the bar, so the time starts a gap past it; else 8 dp in.
         * Instagram's small views in that corner, like the picture of someone who reposted the post,
         * end [clear] pixels in, and the time, or the toggle before it, starts a gap past them (#891).
         */
        private int cornerStart(FrameLayout frame, View view, List<ViewGroup> rows, int clear) {
            int past = clear >= 0 ? clear + dp(view, BUTTON_GAP_DP) : 0;
            int inset = rows.isEmpty() ? -1 : startInset(rows, frame, frame.getWidth());
            if (inset >= 0) return Math.max(inset, past);
            int start = Math.max(dp(view, 8), past);
            return rows.isEmpty() && buttonWanted() ? start + dp(view, BUTTON_DP) + dp(view, BUTTON_GAP_DP) : start;
        }

        /** Writes the time at [at] into the label, when there is one. */
        private void showTimeAt(int at) {
            TextView shown = time;
            if (shown == null) return;
            String text = timeText(at, duration, Settings.FEED_SEEK_TIME_LEFT.get());
            if (!text.contentEquals(shown.getText())) shown.setText(text);
        }

        /** A tap on the time flips it between the time played and the time left, through its own switch. */
        private void flipTime() {
            try {
                if (removed || !timeWanted()) return;
                Settings.FEED_SEEK_TIME_LEFT.save(!Settings.FEED_SEEK_TIME_LEFT.get());
                HookStatus.counted(FamilyNames.FEED_SEEK_BAR, COUNT_TIME_FLIPS);
                Object owner = player.get();
                if (owner != null) update(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        private void removeTime() {
            TextView shown = time;
            time = null;
            timeOnTop = false;
            if (shown == null) return;
            shown.setOnClickListener(null);
            ViewGroup parent = (ViewGroup) shown.getParent();
            if (parent != null) parent.removeView(shown);
        }

        /**
         * Puts the toggle button in [frame] just after [view], or takes it out when its switch is off.
         * With Instagram's [rows] of buttons there, lifted [lift] pixels, the toggle sits in them,
         * past a tags button on its side and level with it, or with the sound button when there's none,
         * so none of them overlap (#794). Without them it sits at the bottom start, over the bar. Either
         * way it starts a gap past Instagram's small views in that corner, which end [clear] pixels in
         * from the start, like the picture of someone who reposted the post (#891).
         */
        private void showButton(FrameLayout frame, View view, List<ViewGroup> rows, int lift, int clear) {
            if (!buttonWanted()) {
                removeButton();
                return;
            }
            int size = dp(view, BUTTON_DP);
            int[] place = {dp(view, 8), barHeight(view, bar) + dp(view, 4)};
            if (!rows.isEmpty()) inRow(rows, frame, frame.getWidth(), lift, size, dp(view, BUTTON_GAP_DP), place);
            if (clear >= 0) place[0] = Math.max(place[0], clear + dp(view, BUTTON_GAP_DP));
            Toggle shown = button;
            if (shown != null && shown.getParent() == frame) {
                fit(shown, size, size, place[1]);
                if (shown.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) shown.getLayoutParams();
                    if (params.getMarginStart() != place[0]) {
                        params.setMarginStart(place[0]);
                        shown.setLayoutParams(params);
                    }
                }
                shown.invalidate();
                return;
            }
            if (shown == null) {
                shown = new Toggle(view.getContext());
                shown.setOnClickListener(tapped -> toggle());
                button = shown;
            } else if (shown.getParent() != null) {
                ((ViewGroup) shown.getParent()).removeView(shown);
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.START);
            params.setMarginStart(place[0]);
            params.bottomMargin = place[1];
            frame.addView(shown, frame.indexOfChild(view) + 1, params);
        }

        private void removeButton() {
            Toggle shown = button;
            button = null;
            if (shown == null) return;
            shown.setOnClickListener(null);
            ViewGroup parent = (ViewGroup) shown.getParent();
            if (parent != null) parent.removeView(shown);
        }

        /** The button's tap: the bar's own switch flips, so the settings and the button always agree. */
        private void toggle() {
            try {
                if (removed || !buttonWanted()) return;
                Settings.FEED_SEEK_BAR.save(!Settings.FEED_SEEK_BAR.get());
                HookStatus.counted(FamilyNames.FEED_SEEK_BAR, COUNT_TOGGLES);
                Object owner = player.get();
                if (owner != null) update(owner);
                if (button != null) button.invalidate();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        /** Hides the bar, ending a drag under it, and takes it away once it's been hidden for {@link #GONE_MS}. */
        private void hide() {
            if (!buttonWanted()) removeButton();
            removeTime();
            lift(Collections.emptyList(), 0);
            if (bar != null && bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
            dragging = false;
            long now = SystemClock.uptimeMillis();
            if (hiddenSince < 0) {
                hiddenSince = now;
            } else if (now - hiddenSince >= GONE_MS) {
                remove();
                if (current == this) current = null;
            }
        }

        /**
         * The bar, moved to [frame] just after [view]: over the video and taking touches before it,
         * under the rest of what Instagram draws there.
         */
        private GlassSeekBar attach(FrameLayout frame, View view) {
            GlassSeekBar seek = bar;
            if (seek == null) {
                HookStatus.invoked(FamilyNames.FEED_SEEK_BAR);
                seek = new GlassSeekBar(view.getContext(), FamilyNames.FEED_SEEK_BAR, HOOK);
                seek.setOnSeekBarChangeListener(this);
                seek.setOnTouchListener(this);
                bar = seek;
            } else {
                ViewGroup old = (ViewGroup) seek.getParent();
                if (old != null) old.removeView(seek);
            }
            dragging = false;
            frame.addView(seek, frame.indexOfChild(view) + 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barHeight(view, seek), Gravity.BOTTOM));
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Seek bar on feed videos: added a seek bar to a feed video");
            }
            return seek;
        }

        /**
         * A finger landing on the bar keeps the feed and Instagram's main tabs from taking the drag
         * until it lifts. Both claim a sideways or upward move before a SeekBar in a scrolling list
         * starts its own, and the tabs' list takes it even when the bar asks its parents not to, so
         * the tabs are held through {@link TabSwipe}. A finger that moves up or down past the touch slop
         * before the bar starts its own drag is a scroll that began on the bar, so the feed and the
         * tabs get it back and Home scrolls.
         */
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && !removed && wanted()) {
                claimed = true;
                downX = event.getX();
                downY = event.getY();
                TabSwipe.hold(true);
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_MOVE && claimed && !dragging) {
                float across = Math.abs(event.getX() - downX);
                float down = Math.abs(event.getY() - downY);
                if (down > ViewConfiguration.get(view.getContext()).getScaledTouchSlop() && down > across) {
                    claimed = false;
                    TabSwipe.hold(false);
                    if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                claimed = false;
                TabSwipe.hold(false);
            }
            return false;
        }

        /** A bar taken away mid-drag leaves its video to the feed, which plays the video it shows. */
        void remove() {
            removed = true;
            dragging = false;
            held = false;
            removeButton();
            removeTime();
            lift(Collections.emptyList(), 0);
            SeekBar seek = bar;
            bar = null;
            if (seek != null) {
                TabSwipe.hold(false);
                seek.setOnSeekBarChangeListener(null);
                seek.setOnTouchListener(null);
                ViewGroup parent = (ViewGroup) seek.getParent();
                if (parent != null) parent.removeView(seek);
            }
        }

        /** A finger on the bar pauses the video, as Instagram's own scrubber does with its "seek" reason. */
        @Override
        public void onStartTrackingTouch(SeekBar seek) {
            dragging = !removed && wanted();
            held = false;
            follow = followed = -1;
            followedAt = -1;
            Object owner = player.get();
            if (!dragging || owner == null) return;
            try {
                held = feed.pause(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        @Override
        public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
            if (!fromUser || !dragging) return;
            try {
                showTimeAt(progress);
                if (!SeekPreview.on()) return;
                Object owner = player.get();
                SeekSheet sheet = owner != null ? feed.sheet(owner) : null;
                SeekPreview.dragged(seek, progress, sheet, FamilyNames.FEED_SEEK_BAR);
                if (owner != null && sheet == null) follow(progress);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        /**
         * Moves the paused video to [to], so a post with no picture strip shows the moment in the video
         * itself: at once when the last move was {@link #FOLLOW_MS} ago, otherwise once that time is up,
         * to wherever the drag is by then.
         */
        private void follow(int to) {
            follow = to;
            long wait = followedAt < 0 ? 0 : followedAt + FOLLOW_MS - SystemClock.uptimeMillis();
            if (wait <= 0) {
                followNow();
            } else if (!followWaits) {
                followWaits = true;
                Utils.runOnMainThreadDelayed(() -> {
                    followWaits = false;
                    followNow();
                }, wait);
            }
        }

        private void followNow() {
            Object owner = player.get();
            if (!dragging || removed || owner == null || follow < 0 || follow == followed) return;
            try {
                followedAt = SystemClock.uptimeMillis();
                if (feed.seekTo(owner, follow)) followed = follow;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }

        /**
         * Letting go moves the video to the bar's point and plays it on from there. A drag the bar's
         * hiding ended seeks nothing, but a video it paused still plays on.
         */
        @Override
        public void onStopTrackingTouch(SeekBar seek) {
            boolean seeks = dragging;
            boolean plays = held;
            dragging = false;
            held = false;
            Object owner = player.get();
            if (owner == null || !(seeks || plays)) return;
            try {
                if (seeks && wanted()) {
                    int to = seek.getProgress();
                    if (feed.seekTo(owner, to)) {
                        position = to;
                        HookStatus.counted(FamilyNames.FEED_SEEK_BAR, COUNT_SEEKS);
                    }
                }
                if (plays) feed.play(owner);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, HOOK, failure);
            }
        }
    }

    /**
     * The small button that shows or hides the seek bar: a dark circle holding a line with a dot on it,
     * bright while the bar shows and dimmed with a slash while it's hidden.
     */
    static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription(L10n.t(context, "Seek bar"));
        }

        boolean barShown() {
            return Settings.FEED_SEEK_BAR.get();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            boolean on = barShown();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x99000000);
            canvas.drawCircle(width / 2, height / 2, Math.min(width, height) / 2, paint);
            int ink = on ? Color.WHITE : 0x99FFFFFF;
            float unit = getResources().getDisplayMetrics().density;
            paint.setColor(ink);
            paint.setStrokeWidth(2 * unit);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            float left = width * 0.24f;
            float right = width * 0.76f;
            float y = height / 2;
            canvas.drawLine(left, y, right, y, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(left + (right - left) * 0.4f, y, 3 * unit, paint);
            if (!on) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(width * 0.28f, height * 0.72f, width * 0.72f, height * 0.28f, paint);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Switch.class.getName());
            info.setCheckable(true);
            info.setChecked(barShown());
        }
    }

    /**
     * Instagram's row of buttons along the bottom of the video in [frame] (#794): the nearest holder
     * of a sound button, in the post's media around the frame, that's as wide as the video, ends at
     * its bottom and takes less than half its height. A carousel's video has no such row: its sound
     * button sits in a holder of its own in the video's bottom corner, in the post's overlay a few
     * wrappers further up, so that holder is the row there. Null when there's none, which leaves
     * Instagram's buttons where they are.
     */
    @Nullable
    static ViewGroup buttonRow(FrameLayout frame) {
        int sound = ids.resolve(frame.getResources(), Utils.resourcePackage(frame.getContext()), SOUND_BUTTON, false);
        if (sound == 0) return null;
        ViewGroup root = frame;
        for (int up = 0; up < ROW_LEVELS && root.getParent() instanceof ViewGroup; up++) root = (ViewGroup) root.getParent();
        ViewGroup row = rowIn(root, frame, sound, true);
        if (row != null) return row;
        for (int up = ROW_LEVELS; up < CAROUSEL_LEVELS && wraps(root.getParent(), frame); up++) root = (ViewGroup) root.getParent();
        return rowIn(root, frame, sound, false);
    }

    /**
     * Instagram's rows of buttons along the bottom of the video in [frame] (#794): the sound button's
     * {@link #buttonRow}, then each holder beside it that shows a button, ends where it ends, takes less
     * than half the video's height and lies within its width. Instagram 456 and 457 keep the tags button
     * in one of those, a holder across the start side that stops at the sound button's, not in the
     * sound button's row. Empty when there's no row.
     */
    static List<ViewGroup> buttonRows(FrameLayout frame) {
        ViewGroup row = buttonRow(frame);
        if (row == null) return Collections.emptyList();
        List<ViewGroup> rows = new ArrayList<>();
        rows.add(row);
        if (!(row.getParent() instanceof ViewGroup)) return rows;
        ViewGroup parent = (ViewGroup) row.getParent();
        int start = across(parent, frame);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == row || !(child instanceof ViewGroup) || child.getVisibility() != View.VISIBLE) continue;
            if (child.getHeight() * 2 >= frame.getHeight() || Math.abs(child.getBottom() - row.getBottom()) > 1) continue;
            if (child.getLeft() < start || child.getRight() > start + frame.getWidth()) continue;
            if (roomUnder((ViewGroup) child) >= 0) rows.add((ViewGroup) child);
        }
        return rows;
    }

    /**
     * Instagram's small views showing in the top end corner of the video in [frame] (#788): its
     * countdown, a carousel's count, or the post's menu button when the name row lies over the video.
     * They're looked for in the post's media around the frame, as far up as the sound button's row is
     * ({@link #buttonRow}), plus the post's {@link #MENU_BUTTON}. Each one reaches into the video's top
     * third, lies in its end half, reaches its last quarter and is less than half its width and a third
     * of its height, so the play button in the middle and the rows along the bottom never count.
     * HushGram's own views, [ours], don't either.
     */
    static List<View> cornerViews(FrameLayout frame, View... ours) {
        ViewGroup root = mediaRoot(frame);
        List<View> found = new ArrayList<>();
        int[] at = new int[2];
        frame.getLocationInWindow(at);
        boolean rtl = frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        List<View> skip = Arrays.asList(ours);
        corners(root, frame, at[0], at[1], rtl, skip, found);
        View menu = menuButton(frame);
        if (menu != null && !found.contains(menu) && inCorner(menu, frame, at[0], at[1], rtl)) found.add(menu);
        return found;
    }

    /** Adds every shown view under [group] that sits in [frame]'s top end corner ({@link #inCorner}) to [found]. */
    private static void corners(ViewGroup group, FrameLayout frame, int left, int top, boolean rtl, List<View> skip,
                                List<View> found) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() == 0f || skip.contains(child)) continue;
            if (inCorner(child, frame, left, top, rtl)) found.add(child);
            if (child instanceof ViewGroup) corners((ViewGroup) child, frame, left, top, rtl, skip, found);
        }
    }

    /**
     * Whether [view] is a small view in the top end corner of [frame], which starts at [left] and [top]
     * in the window: reaching into its top third, in its end half, out to its last quarter, and less
     * than half as wide and a third as tall.
     */
    private static boolean inCorner(View view, FrameLayout frame, int left, int top, boolean rtl) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int w = view.getWidth();
        int h = view.getHeight();
        if (w == 0 || h == 0 || w * 2 > width || h * 3 > height || !view.isShown()) return false;
        int[] at = new int[2];
        view.getLocationInWindow(at);
        int x = at[0] - left;
        int y = at[1] - top;
        if (y + h <= 0 || y * 3 > height) return false;
        if (rtl) return x >= -1 && (x + w) * 2 <= width && x * 4 <= width;
        return x * 2 >= width && x + w <= width + 1 && (x + w) * 4 >= 3 * width;
    }

    /**
     * The post's media around [frame], where Instagram's views over the video are: as far up as the
     * sound button's row is ({@link #buttonRow}), and on through a carousel's wrappers.
     */
    private static ViewGroup mediaRoot(FrameLayout frame) {
        ViewGroup root = frame;
        for (int up = 0; up < ROW_LEVELS && root.getParent() instanceof ViewGroup; up++) root = (ViewGroup) root.getParent();
        for (int up = ROW_LEVELS; up < CAROUSEL_LEVELS && wraps(root.getParent(), frame); up++) root = (ViewGroup) root.getParent();
        return root;
    }

    /**
     * How far in from the start edge of the video in [frame] Instagram's small views in its bottom
     * start corner end (#891): the picture of someone who reposted the post, the note over it, and
     * the like. -1 when none is there. They're looked for where {@link #cornerViews} looks, as views
     * that draw something rather than the groups holding them ({@link #inStartCorner}). Instagram's
     * [rows] of buttons, which go up over the bar and leave room for the toggle themselves, and
     * HushGram's own views, [ours], don't count.
     */
    static int startCornerEnd(FrameLayout frame, List<ViewGroup> rows, View... ours) {
        if (frame.getWidth() == 0 || frame.getHeight() == 0) return -1;
        int[] at = new int[2];
        frame.getLocationInWindow(at);
        boolean rtl = frame.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        List<View> skip = new ArrayList<>(Arrays.asList(ours));
        skip.addAll(rows);
        return startCorner(mediaRoot(frame), frame, at[0], at[1], rtl, skip, -1);
    }

    /** {@link #startCornerEnd} under [group], at least [end]. */
    private static int startCorner(ViewGroup group, FrameLayout frame, int left, int top, boolean rtl, List<View> skip,
                                   int end) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() == 0f || skip.contains(child)) continue;
            if (child instanceof ViewGroup) {
                end = startCorner((ViewGroup) child, frame, left, top, rtl, skip, end);
            } else {
                end = Math.max(end, inStartCorner(child, frame, left, top, rtl));
            }
        }
        return end;
    }

    /**
     * How far in from the start edge of [frame], which starts at [left] and [top] in the window,
     * [view] ends when it's a small view in the frame's bottom start corner, else -1: less than half
     * as wide and a third as tall, reaching into its bottom third, in its start half and starting in
     * its first quarter. An image with nothing in it, or a text with no words, draws nothing there.
     */
    private static int inStartCorner(View view, FrameLayout frame, int left, int top, boolean rtl) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int w = view.getWidth();
        int h = view.getHeight();
        if (w == 0 || h == 0 || w * 2 > width || h * 3 > height || !view.isShown()) return -1;
        if (view.getBackground() == null) {
            if (view instanceof ImageView && ((ImageView) view).getDrawable() == null) return -1;
            if (view instanceof TextView && TextUtils.isEmpty(((TextView) view).getText())) return -1;
        }
        int[] at = new int[2];
        view.getLocationInWindow(at);
        int x = at[0] - left;
        int y = at[1] - top;
        if (y >= height || (y + h) * 3 < 2 * height) return -1;
        if (rtl) {
            boolean inside = x + w <= width + 1 && x * 2 >= width && (width - x - w) * 4 <= width;
            return inside ? width - x : -1;
        }
        boolean inside = x >= -1 && (x + w) * 2 <= width && x * 4 <= width;
        return inside ? x + w : -1;
    }

    /**
     * The post's menu button around [frame]: the nearest under the frame's wrappers, looked for up to
     * {@link #MENU_LEVELS} above it and never in a list that scrolls, which holds other posts. Null
     * when there's none.
     */
    @Nullable
    private static View menuButton(FrameLayout frame) {
        int menu = ids.resolve(frame.getResources(), Utils.resourcePackage(frame.getContext()), MENU_BUTTON, false);
        if (menu == 0) return null;
        ViewParent at = frame.getParent();
        for (int up = 0; up < MENU_LEVELS && at instanceof ViewGroup; up++, at = at.getParent()) {
            ViewGroup group = (ViewGroup) at;
            if (group.canScrollVertically(1) || group.canScrollVertically(-1)) return null;
            View found = group.findViewById(menu);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Under [root], the holder of a sound button that ends at [frame]'s bottom and takes less than half
     * its height: the nearest one as wide as the video when [wide], else the outermost one lying
     * within the video's width.
     */
    @Nullable
    private static ViewGroup rowIn(ViewGroup root, FrameLayout frame, int sound, boolean wide) {
        ArrayList<View> icons = new ArrayList<>();
        collect(root, sound, icons);
        int bottom = top(frame, root) + frame.getHeight();
        int start = left(frame, root);
        for (View icon : icons) {
            ViewGroup corner = null;
            for (ViewParent at = icon.getParent(); at instanceof ViewGroup && at != root && at != frame; at = at.getParent()) {
                ViewGroup holder = (ViewGroup) at;
                if (holder.getHeight() * 2 >= frame.getHeight()) continue;
                if (Math.abs(top(holder, root) + holder.getHeight() - bottom) > 1) continue;
                if (wide) {
                    if (holder.getWidth() == frame.getWidth()) return holder;
                    continue;
                }
                int left = left(holder, root);
                if (left >= start && left + holder.getWidth() <= start + frame.getWidth()) corner = holder;
            }
            if (corner != null) return corner;
        }
        return null;
    }

    /** Whether [parent] is one more wrapper of [frame]'s media: a view group as wide and, within a pixel or two, as tall. */
    private static boolean wraps(ViewParent parent, View frame) {
        if (!(parent instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) parent;
        return group.getWidth() == frame.getWidth() && Math.abs(group.getHeight() - frame.getHeight()) <= 2;
    }

    /** Adds every shown view with [id] under [group] to [found]. */
    private static void collect(ViewGroup group, int id, List<View> found) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.getId() == id) found.add(child);
            if (child instanceof ViewGroup) collect((ViewGroup) child, id, found);
        }
    }

    /** Where [view] is laid out below [root]'s top, leaving out any translation; -1 when it isn't under [root]. */
    private static int top(View view, ViewGroup root) {
        int top = 0;
        for (View at = view; at != root; at = (View) at.getParent()) {
            top += at.getTop();
            if (!(at.getParent() instanceof View)) return -1;
        }
        return top;
    }

    /** Where [view] is laid out past [root]'s left, leaving out any translation; -1 when it isn't under [root]. */
    private static int left(View view, ViewGroup root) {
        int left = 0;
        for (View at = view; at != root; at = (View) at.getParent()) {
            left += at.getLeft();
            if (!(at.getParent() instanceof View)) return -1;
        }
        return left;
    }

    /** How far [row] starts past [frame]'s left on screen: 0 for a row as wide as the video, more for a carousel's corner. */
    static int across(View frame, View row) {
        int[] at = new int[2];
        row.getLocationInWindow(at);
        int x = at[0];
        frame.getLocationInWindow(at);
        return x - at[0];
    }

    /**
     * The toggle's place among Instagram's [rows], lifted [lift] pixels, written into [place] as its
     * start margin and bottom margin in the video's [frame] (#794): past the end of any of their
     * buttons on the start side, [gap] after it, and level with the one it sits beside, or with the
     * first button when none is on that side. The video is [width] wide. Each row's buttons are read
     * from its children through {@link #buttonIn}, and every row ends where the video does.
     */
    static void inRow(List<ViewGroup> rows, View frame, int width, int lift, int size, int gap, int[] place) {
        int middle = -1;
        int start = place[0];
        for (ViewGroup row : rows) {
            boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int offset = across(frame, row);
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
                int[] button = buttonIn(child);
                int left = offset + button[0];
                int right = left + button[2];
                // How far above the row's bottom, and so the video's, the button's middle is.
                int level = row.getHeight() - (button[1] + button[3] / 2);
                if (middle < 0) middle = level;
                boolean startSide = rtl ? left + right > width : left + right < width;
                int past = (rtl ? width - left : right) + gap;
                if (startSide && past > start) {
                    start = past;
                    middle = level;
                }
            }
        }
        if (middle < 0) return;
        place[0] = start;
        place[1] = Math.max(0, middle + lift - size / 2);
    }

    /** {@link #startInset(ViewGroup, int, int)} over all of Instagram's [rows] around the video in [frame], which is [width] wide. */
    static int startInset(List<ViewGroup> rows, View frame, int width) {
        int startSide = -1;
        int endSide = -1;
        for (ViewGroup row : rows) {
            int[] sides = sides(row, across(frame, row), width);
            if (sides[0] != Integer.MAX_VALUE) startSide = startSide < 0 ? sides[0] : Math.min(startSide, sides[0]);
            if (sides[1] != Integer.MAX_VALUE) endSide = endSide < 0 ? sides[1] : Math.min(endSide, sides[1]);
        }
        int inset = startSide >= 0 ? startSide : endSide;
        return inset < 0 ? -1 : Math.max(0, inset);
    }

    /**
     * How far in from the video's start edge the first of [row]'s buttons on the start side starts, or
     * with none there, how far in from the end edge the last on the end side ends; -1 when the row
     * shows no button (#711). The row starts [offset] pixels into the video, which is [width] wide, and
     * its buttons are read as {@link #inRow} reads them.
     */
    static int startInset(ViewGroup row, int offset, int width) {
        int[] sides = sides(row, offset, width);
        int inset = sides[0] != Integer.MAX_VALUE ? sides[0] : sides[1];
        return inset == Integer.MAX_VALUE ? -1 : Math.max(0, inset);
    }

    /**
     * How far the first of [row]'s buttons on the start side starts from the video's start edge, and how
     * far the last on the end side ends from its end edge, {@link Integer#MAX_VALUE} for a side with none.
     */
    private static int[] sides(ViewGroup row, int offset, int width) {
        boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int startSide = Integer.MAX_VALUE;
        int endSide = Integer.MAX_VALUE;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
            int[] button = buttonIn(child);
            int left = offset + button[0];
            int right = left + button[2];
            int fromStart = rtl ? width - right : left;
            int fromEnd = rtl ? left : width - right;
            if (rtl ? left + right > width : left + right < width) {
                startSide = Math.min(startSide, fromStart);
            } else {
                endSide = Math.min(endSide, fromEnd);
            }
        }
        return new int[]{startSide, endSide};
    }

    /** The least {@link #roomUnder(ViewGroup)} among Instagram's [rows] that show a button; -1 when none does. */
    static int roomUnder(List<ViewGroup> rows) {
        int least = -1;
        for (ViewGroup row : rows) {
            int under = roomUnder(row);
            if (under >= 0) least = least < 0 ? under : Math.min(least, under);
        }
        return least;
    }

    /**
     * How far above [row]'s bottom its lowest button ends, its buttons read as {@link #inRow} reads them;
     * -1 when it shows none (#711).
     */
    static int roomUnder(ViewGroup row) {
        int lowest = -1;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
            int[] button = buttonIn(child);
            lowest = Math.max(lowest, button[1] + button[3]);
        }
        return lowest < 0 ? -1 : row.getHeight() - lowest;
    }

    /**
     * Where the button a row's [child] shows sits in the row, as left, top, width and height: the child
     * itself, or the one shown child of a child padding it, and inside that the innermost of any groups
     * that are each the last one's only shown child. Instagram's tags button is three wrappers deep,
     * the last one drawing its round back (#794).
     */
    private static int[] buttonIn(View child) {
        int left = child.getLeft();
        int top = child.getTop();
        View button = child;
        View only = child instanceof ViewGroup ? onlyShown((ViewGroup) child) : null;
        while (only != null) {
            left += only.getLeft();
            top += only.getTop();
            button = only;
            View next = only instanceof ViewGroup ? onlyShown((ViewGroup) only) : null;
            only = next instanceof ViewGroup ? next : null;
        }
        return new int[]{left, top, button.getWidth(), button.getHeight()};
    }

    /** [group]'s one shown child, or null when it shows none or more than one. */
    @Nullable
    private static View onlyShown(ViewGroup group) {
        View only = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (only != null) return null;
            only = child;
        }
        return only;
    }

    /**
     * How tall the bar is: room for a finger along the bottom of the video, or with
     * {@link Settings#FEED_SEEK_BAR_ON_EDGE} on only its round handle's height, so it sits right on the
     * video's bottom edge and the rest of the video takes taps and scrolls as before (#567).
     */
    static int barHeight(View view, @Nullable SeekBar seek) {
        SeekShape shape = SeekShapes.feedWanted();
        if (!Settings.FEED_SEEK_BAR_ON_EDGE.get() && shape != SeekShape.LINE) return dp(view, HEIGHT_DP);
        if (shape.tallHandle()) return dp(view, EDGE_TALL_DP);
        Drawable handle = seek != null ? seek.getThumb() : null;
        return Math.max(handle != null ? handle.getIntrinsicHeight() : 0, dp(view, EDGE_MIN_DP));
    }

    /** Gives [child], already in a frame, the size and bottom margin asked, when it has others. */
    private static void fit(View child, int width, int height, int bottomMargin) {
        if (!(child.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
        if (params.width == width && params.height == height && params.bottomMargin == bottomMargin) return;
        params.width = width;
        params.height = height;
        params.bottomMargin = bottomMargin;
        child.setLayoutParams(params);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
