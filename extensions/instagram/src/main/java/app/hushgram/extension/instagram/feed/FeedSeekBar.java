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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

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
 * <p>Instagram keeps a row of buttons along the bottom of a feed video, tags at the start and sound
 * at the end. While the bar shows, that row is lifted by the bar's height, so the buttons sit over
 * the bar instead of covering its ends, and the toggle button lines up in the row beside them (#794).
 * The row is put back where it was when the bar hides or moves to another video.
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

    /** The time showing over the bar on a feed video now, or null. A carousel's dots pill goes above it too. */
    @Nullable
    static View shownTime() {
        Overlay overlay = current;
        TextView label = overlay == null || overlay.removed ? null : overlay.time;
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
        /** Instagram's row of buttons this bar lifted (#794), the translation it had, and how far it went up. */
        @Nullable WeakReference<View> lifted;
        float liftedFrom;
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
            showTime(frame, view, seek);
            ViewGroup row = buttonRow(frame);
            int lift = barState == View.VISIBLE && row != null ? barHeight(view, seek) + clearOfTime(row, view) : 0;
            lift(row, lift);
            showButton(frame, view, row, lift);
        }

        /**
         * How much higher than the bar Instagram's [row] goes so its buttons end above the time showing
         * over the bar, a gap clear of it (#711): the time's height and a gap on each side of it, less the
         * room the row already leaves under its lowest button. Zero with no time showing.
         */
        private int clearOfTime(ViewGroup row, View view) {
            TextView shown = time;
            if (shown == null || shown.getParent() == null) return 0;
            int under = roomUnder(row);
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
         * Keeps Instagram's [row] of buttons, the sound and tags buttons along the video's bottom,
         * lifted [by] pixels so they sit over the bar instead of on it and a touch at the bar's ends
         * goes to the bar (#794). A row lifted before is put back first, unless Instagram moved it
         * since, which means it's placing that row itself.
         */
        private void lift(@Nullable View row, int by) {
            View held = lifted == null ? null : lifted.get();
            boolean holding = held != null && held.getTranslationY() == liftedFrom - liftedBy;
            if (held == row && by == liftedBy && holding && by > 0) return;
            if (holding) held.setTranslationY(liftedFrom);
            lifted = null;
            if (row == null || by <= 0) return;
            liftedFrom = row.getTranslationY();
            liftedBy = by;
            row.setTranslationY(liftedFrom - by);
            lifted = new WeakReference<>(row);
        }

        /**
         * Puts the time over the middle of the bar in [frame], or takes it away while its switch is off
         * or the bar is hidden (#711). It sits in the middle, under Instagram's tags and sound buttons,
         * so a tags button that slides out wide over the middle still doesn't cover it.
         */
        private void showTime(FrameLayout frame, View view, SeekBar seek) {
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
            int bottom = barHeight(view, seek) + dp(view, TIME_GAP_DP);
            if (shown.getParent() != frame) {
                if (shown.getParent() != null) ((ViewGroup) shown.getParent()).removeView(shown);
                frame.addView(shown, frame.indexOfChild(seek) + 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            }
            fit(shown, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, bottom);
            showTimeAt(dragging ? seek.getProgress() : position);
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
            if (shown == null) return;
            shown.setOnClickListener(null);
            ViewGroup parent = (ViewGroup) shown.getParent();
            if (parent != null) parent.removeView(shown);
        }

        /**
         * Puts the toggle button in [frame] just after [view], or takes it out when its switch is off.
         * With Instagram's [row] of buttons there, lifted [lift] pixels, the toggle sits in that row,
         * level with the sound button and past a tags button on its side, so none of them overlap
         * (#794). Without one it sits at the bottom start, over the bar.
         */
        private void showButton(FrameLayout frame, View view, @Nullable ViewGroup row, int lift) {
            if (!buttonWanted()) {
                removeButton();
                return;
            }
            int size = dp(view, BUTTON_DP);
            int[] place = {dp(view, 8), barHeight(view, bar) + dp(view, 4)};
            if (row != null) inRow(row, across(frame, row), frame.getWidth(), lift, size, dp(view, BUTTON_GAP_DP), place);
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
            lift(null, 0);
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
            lift(null, 0);
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
     * The toggle's place in Instagram's [row], lifted [lift] pixels, written into [place] as its start
     * margin and bottom margin in the video's frame (#794): centered on the row's buttons, and past
     * the end of any of them on the start side, [gap] after it. The row starts [offset] pixels into
     * the video, which is [width] wide. Each of the row's buttons is its child, or that child's only
     * shown child when it's a group padding one.
     */
    static void inRow(ViewGroup row, int offset, int width, int lift, int size, int gap, int[] place) {
        boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int middle = -1;
        int start = place[0];
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
            int left = offset + child.getLeft();
            int top = child.getTop();
            View button = child;
            if (child instanceof ViewGroup) {
                View only = onlyShown((ViewGroup) child);
                if (only != null) {
                    left += only.getLeft();
                    top += only.getTop();
                    button = only;
                }
            }
            int right = left + button.getWidth();
            if (middle < 0) middle = top + button.getHeight() / 2;
            boolean startSide = rtl ? left + right > width : left + right < width;
            if (startSide) start = Math.max(start, (rtl ? width - left : right) + gap);
        }
        if (middle < 0) return;
        place[0] = start;
        place[1] = Math.max(0, row.getHeight() - middle + lift - size / 2);
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
            int bottom = child.getBottom();
            if (child instanceof ViewGroup) {
                View only = onlyShown((ViewGroup) child);
                if (only != null) bottom = child.getTop() + only.getBottom();
            }
            lowest = Math.max(lowest, bottom);
        }
        return lowest < 0 ? -1 : row.getHeight() - lowest;
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
