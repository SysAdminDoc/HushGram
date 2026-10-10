/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The time label of the "Keep a seek bar on Reels" patch: "0:10 / 0:55" above the end of the
 * reel's seek bar.
 *
 * <p>Instagram's reel seek bar keeps the reel's position and length in milliseconds as its
 * progress and max, and every change of either reaches its onProgressChanged, where
 * {@link #update} runs. Only a bar whose container Instagram bound to an ordinary reel gets a
 * label ({@link #bind}): an ad's bar, or the same kind of bar anywhere else, stays as it is. The
 * container is the nearest view above the bar that Instagram bound, for the reel's tag too, so a
 * bar a view or two further down still counts.
 *
 * <p>Each bar gets one label, made with its first update and kept by the bar itself for as long as
 * the bar lives, along with one listener for the bar's layout, its window and the window's frames.
 * The label goes in the nearest view that has room for all of it above the bar's track:
 * <ul>
 *   <li>When that's the bar's own parent and a FrameLayout, the label is a child of it. TalkBack
 *       reads it, and it takes no touches, so a drag that starts on it still reaches the bar.</li>
 *   <li>Otherwise it goes on the overlay of the bar's parent or of a view further up, which draws
 *       over that view without joining its children and never takes a touch. A Litho host lays
 *       out only what it put there itself, and Instagram 449's scrubber container, a FrameLayout,
 *       is hardly taller than the bar, so a child label there was cut in half on a phone.
 *       TalkBack can't reach an overlay, so on Android 11 and up the bar itself carries the
 *       label's words as its state.</li>
 *   <li>When no view near the bar has the room, there's no label rather than a cut-off one.</li>
 * </ul>
 * A view holds at most one of these labels: a label whose bar has gone from under it gives way.
 *
 * <p>The label shows only while its bar does: while the bar and every view above it are shown, and
 * faded as much as the views between the bar and the label's holder are. That's checked before
 * every frame, so a container Instagram hides or fades without moving the bar hides the label too.
 * It's hidden while you drag the bar, when Instagram shows its own times, and when the bar has no
 * length. When the bar leaves the window, its label is hidden at once and taken off its holder
 * right after, and its text and width start over when the bar is bound to another reel, which
 * shows as a new length or a new reel tag on the bar's container.
 *
 * <p>Runs on the main thread, where a SeekBar calls its listener.
 */
final class ReelTimeLabel {
    /** The start of the tag Instagram 449 puts on a reel's scrubber container, then the reel's id. */
    static final String REEL_TAG_PREFIX = "clips_scrubber_";

    /** The label's text size, the size of Instagram's own scrubber times. */
    static final float TEXT_SP = 12f;

    /**
     * How many views up from the bar, its parent first, the container Instagram bound to a reel is
     * looked for, and how far down from a bound container its bar is.
     */
    private static final int TAG_REACH = 8;

    /**
     * How many views up from the bar, its parent first, one with room for the label is looked for.
     * On 449 the scrubber container has none and the Litho host holding it has plenty.
     */
    private static final int HOST_REACH = 4;

    /** The bigger bar's track height, thumb size and the height of the dark fade behind it, in dp. */
    static final float BIG_TRACK_DP = 4f;
    static final float BIG_THUMB_DP = 14f;
    static final float BIG_FADE_DP = 64f;
    /** The bigger bar's track behind the progress, white at about a third, and the fade's darkest end, 40% black. */
    static final int BIG_TRACK_COLOR = 0x55FFFFFF;
    static final int BIG_FADE_COLOR = 0x66000000;

    /** The space between the label and the bar's track, and around the label's text. */
    private static final float GAP_DP = 4f;

    /** Runs the label's update after the layout pass or the window change that asked for it. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Each bar's tracker. Weak both ways: the bar keeps its tracker as a listener, and the label leads back to the bar. */
    private static final Map<SeekBar, WeakReference<Tracker>> TRACKERS = new WeakHashMap<>();

    /** Each seek bar container Instagram bound to a reel: true for an ordinary reel, false for an ad. */
    private static final Map<View, Boolean> REELS = new WeakHashMap<>();

    /** The label each view holds, as a child or on its overlay. At most one per view. */
    private static final Map<ViewGroup, WeakReference<TextView>> HELD = new WeakHashMap<>();

    /** What a label knows, kept as its tag. Nothing here holds the bar or a view above it strongly. */
    static final class State {
        /** The bar the label belongs to. */
        final WeakReference<SeekBar> bar;
        /** The reel tag and the length the label was laid out for; a change of either starts it over. */
        String reel;
        int max = -1;
        /** The label's width, enough for the longest time this length can show. */
        int width;
        /** The text on the label now, or null after it starts over. */
        String text;
        /** The view whose overlay holds the label, or null when it's a child or nowhere. */
        WeakReference<ViewGroup> overlay;
        /** Whether the bar carries the label's words as its state description. */
        boolean described;
        /** Whether the last update placed the label to be shown, subject to the bar showing. */
        boolean wanted;
        /** Only restore a snapshot while the bar still carries our own drawable. */
        Drawable originalThumb;
        GradientDrawable thumb;
        int originalThumbOffset;
        /** The bigger bar: Instagram's own track and background, ours in their place, and the views let draw past their edges. */
        boolean styled;
        Drawable originalProgress;
        LayerDrawable bigTrack;
        Drawable originalBackground;
        LayerDrawable backdrop;
        int fadeIndex;
        int styledHeight = -1;
        int styledBottom = -1;
        final ViewGroup[] unclipped = new ViewGroup[HOST_REACH];
        final boolean[] clippedBefore = new boolean[HOST_REACH];
        int unclippedCount;

        State(SeekBar bar) {
            this.bar = new WeakReference<>(bar);
        }
    }

    /** The view the label goes in, where the bar is in that view, and whether the label is its child. */
    private static final class Host {
        final ViewGroup view;
        final int barLeft;
        final int barTop;
        final boolean child;

        Host(ViewGroup view, int barLeft, int barTop, boolean child) {
            this.view = view;
            this.barLeft = barLeft;
            this.barTop = barTop;
            this.child = child;
        }
    }

    /**
     * A bar's label and its one listener. The bar holds it, as a listener, for as long as the bar
     * lives, so the label is never collected and made again while the bar has no room for it, and
     * the bar never gets a second listener. Registered for the window's frames only while the bar
     * is in a window.
     */
    private static final class Tracker implements View.OnLayoutChangeListener, View.OnAttachStateChangeListener,
            ViewTreeObserver.OnPreDrawListener {
        final TextView label;
        final BooleanSupplier on;
        /** The window's observer this listens to for frames, or null outside a window. */
        private ViewTreeObserver frames;
        /** Whether an update of the bar is waiting to run. */
        private boolean queued;

        Tracker(TextView label, BooleanSupplier on) {
            this.label = label;
            this.on = on;
        }

        /**
         * Instagram can give a bar its length and position before laying it out, and with autoplay
         * held (Tap to play) nothing moves the bar again, so on a phone 2 of 10 reels got no label.
         * Each layout of the bar shows the label again for where the bar is now, after the layout
         * pass, since the label can be added to the bar's parent.
         */
        @Override
        public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            queue((SeekBar) view);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            try {
                watch(view);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek bar window", failure);
            }
            queue((SeekBar) view);
        }

        /**
         * The bar left the window: its label is hidden at once and taken off its holder right
         * after. Not at once, since Android is going through the views of the window to detach
         * them, the label's holder perhaps among them.
         */
        @Override
        public void onViewDetachedFromWindow(View view) {
            try {
                unwatch();
                updateThumb((SeekBar) view, (State) label.getTag(), false);
                hide((SeekBar) view, label);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek bar window", failure);
                hideAfterFailure(label);
            }
            queue((SeekBar) view);
        }

        /** Before every frame of the bar's window: the label shows only while the bar does. */
        @Override
        public boolean onPreDraw() {
            try {
                SeekBar bar = barOf(label);
                if (bar != null) {
                    updateThumb(bar, (State) label.getTag(), ReelSeekBar.thumbOn()
                            && ordinaryReel(bar) && bar.getMax() > 0 && bar.isAttachedToWindow());
                    follow(bar, label);
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek bar frame", failure);
                hideAfterFailure(label);
            }
            return true;
        }

        void watch(View bar) {
            if (frames != null && frames.isAlive()) return;
            frames = bar.getViewTreeObserver();
            frames.addOnPreDrawListener(this);
        }

        void unwatch() {
            if (frames != null && frames.isAlive()) frames.removeOnPreDrawListener(this);
            frames = null;
        }

        /** Updates the bar's label once the main thread is free, at most once however often it's asked. */
        void queue(SeekBar bar) {
            if (queued) return;
            queued = true;
            MAIN.post(() -> {
                queued = false;
                update(bar, bar.getProgress(), on);
            });
        }
    }

    private ReelTimeLabel() {
    }

    /**
     * Notes whether Instagram bound [container], a seek bar container, to an ordinary reel or to an
     * ad. A bar in a container bound to an ad or to another reel loses its label at once, and one
     * in a container bound to an ordinary reel gets its label shown for that reel. Bound again to
     * the same ordinary reel, the label stays up rather than blinking for a frame. Never throws.
     */
    static void bind(Object container, boolean ad, BooleanSupplier on) {
        if (!(container instanceof View)) return;
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            View view = (View) container;
            Boolean before;
            synchronized (REELS) {
                before = REELS.put(view, !ad);
            }
            boolean stillOrdinary = !ad && Boolean.TRUE.equals(before);
            for (SeekBar bar : barsIn(view)) {
                Tracker tracker = trackerOf(bar);
                if (tracker != null) {
                    State state = (State) tracker.label.getTag();
                    if (ad) updateThumb(bar, state, false);
                    if (!stillOrdinary || !Objects.equals(state.reel, reelOf(bar))) hide(bar, tracker.label);
                    tracker.queue(bar);
                } else if (!ad && (on.getAsBoolean() || ReelSeekBar.thumbOn())) {
                    MAIN.post(() -> update(bar, bar.getProgress(), on));
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek bar reel", failure);
        }
    }

    /**
     * Shows [bar]'s position and length in its label while the switch is on, and hides the label
     * otherwise, or when the bar has no length. A bar outside a container bound to an ordinary
     * reel, or outside a window, has no label anywhere. Never throws.
     */
    static void update(SeekBar bar, int progress, BooleanSupplier on) {
        if (bar == null) return;
        TextView label = null;
        try {
            HookStatus.invoked(FamilyNames.REEL_SEEK_BAR);
            Tracker tracker = trackerOf(bar);
            label = tracker == null ? null : tracker.label;
            ViewParent parent = bar.getParent();
            if (!(parent instanceof ViewGroup) || !ordinaryReel(bar)) {
                // An ad's bar, a bar on another screen, or one taken out of its container.
                if (label != null) remove(bar, label);
                return;
            }
            int max = bar.getMax();
            boolean labelOn = on.getAsBoolean();
            boolean thumbOn = ReelSeekBar.thumbOn() && max > 0 && bar.isAttachedToWindow();
            if (tracker == null && thumbOn) tracker = track(bar, on);
            if (tracker != null) {
                label = tracker.label;
                updateThumb(bar, (State) label.getTag(), thumbOn);
            }
            if (!labelOn || max <= 0) {
                if (label != null) hide(bar, label);
                return;
            }
            if (tracker == null) tracker = track(bar, on);
            label = tracker.label;
            if (!bar.isAttachedToWindow()) {
                remove(bar, label);
                return;
            }
            show(bar, (ViewGroup) parent, label, progress, max);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_SEEK_BAR, "seek bar time", failure);
            hideAfterFailure(label);
        }
    }

    /** The bar's label, or null before its first. */
    static TextView labelOf(SeekBar bar) {
        Tracker tracker = trackerOf(bar);
        return tracker == null ? null : tracker.label;
    }

    /** Uses Android's thumb drawing and seeking without replacing any listener. */
    private static void updateThumb(SeekBar bar, State state, boolean wanted) {
        float density = bar.getResources().getDisplayMetrics().density;
        boolean big = wanted && ReelSeekBar.bigOn();
        updateStyle(bar, state, big);
        int diameter = Math.min(Math.round((big ? BIG_THUMB_DP : 10) * density), bar.getHeight());
        if (!wanted || diameter <= 0) {
            if (state.thumb != null && bar.getThumb() == state.thumb) {
                bar.setThumb(state.originalThumb);
                bar.setThumbOffset(state.originalThumbOffset);
            }
            state.thumb = null;
            state.originalThumb = null;
            return;
        }
        if (state.thumb == null || bar.getThumb() != state.thumb
                || state.thumb.getIntrinsicWidth() != diameter) {
            if (state.thumb == null || bar.getThumb() != state.thumb) {
                state.originalThumb = bar.getThumb();
                state.originalThumbOffset = bar.getThumbOffset();
            }
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(Color.WHITE);
            thumb.setStroke(Math.max(1, Math.round(density)), Color.BLACK);
            thumb.setSize(diameter, diameter);
            state.thumb = thumb;
            // AbsSeekBar only positions a replacement when its old thumb is non-null. A same-call
            // seed makes Android lay out a previously absent thumb without changing playback progress.
            if (bar.getThumb() == null) bar.setThumb(new GradientDrawable());
            bar.setThumb(thumb);
            // Clear only our drawable's tint, preserving the native policy and original drawable.
            thumb.setTintList(null);
        }
        // Instagram pads its compact track down to the bottom of the view. Keep the full circle
        // inside that view without changing its track, padding, touch bounds or horizontal position.
        Rect bounds = state.thumb.getBounds();
        int top = Math.max(-bar.getPaddingTop(),
                Math.min(bounds.top, bar.getHeight() - bar.getPaddingTop() - diameter));
        if (bounds.top != top) state.thumb.setBounds(bounds.left, top, bounds.right, top + diameter);
    }

    /**
     * The bigger bar, while [big]: a thicker white track with a translucent one behind it, in place of
     * Instagram's drawable for the bar's track, and a soft dark fade drawn as part of the bar's own
     * background, reaching up from the bar's bottom. The bar stays Instagram's view, so its taps,
     * dragging and accessibility seeking are untouched. The fade is drawn by the bar, in its place
     * among the reel's views, so it can't land over the caption or buttons above it; the views from
     * the bar's parent up to the first with room for the fade are let draw past their edges for it.
     * Costs nothing per frame once set; it starts over only if the bar's size or place changes or
     * Instagram swaps a drawable. Off, it gives Instagram's drawables and clipping back.
     */
    private static void updateStyle(SeekBar bar, State state, boolean big) {
        ViewParent parent = bar.getParent();
        if (!big || !(parent instanceof ViewGroup)) {
            unstyle(bar, state);
            return;
        }
        int height = bar.getHeight();
        if (height <= 0) return;
        float density = bar.getResources().getDisplayMetrics().density;
        if (state.bigTrack == null) state.bigTrack = bigTrack(density);
        if (bar.getProgressDrawable() != state.bigTrack) {
            state.originalProgress = bar.getProgressDrawable();
            bar.setProgressDrawable(state.bigTrack);
        }
        boolean moved = state.styledHeight != height || state.styledBottom != bar.getBottom();
        if (state.backdrop == null || bar.getBackground() != state.backdrop) {
            state.originalBackground = bar.getBackground();
            GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {Color.TRANSPARENT, BIG_FADE_COLOR});
            state.fadeIndex = state.originalBackground == null ? 0 : 1;
            state.backdrop = new LayerDrawable(state.originalBackground == null
                    ? new Drawable[] {fade} : new Drawable[] {state.originalBackground, fade});
            bar.setBackground(state.backdrop);
            moved = true;
        }
        if (moved) {
            restoreClipping(state);
            int wanted = Math.round(BIG_FADE_DP * density);
            ViewGroup view = (ViewGroup) parent;
            int room = bar.getBottom();
            int count = 0;
            while (count < HOST_REACH) {
                state.unclipped[count] = view;
                state.clippedBefore[count] = view.getClipChildren();
                view.setClipChildren(false);
                count++;
                if (room >= wanted) break;
                ViewParent up = view.getParent();
                if (!(up instanceof ViewGroup)) break;
                room += view.getTop();
                view = (ViewGroup) up;
            }
            state.unclippedCount = count;
            // The fade ends at the bar's bottom and reaches up by its height, as far as there's room.
            state.backdrop.setLayerInset(state.fadeIndex, 0, height - Math.min(wanted, room), 0, 0);
            state.styledHeight = height;
            state.styledBottom = bar.getBottom();
        }
        state.styled = true;
    }

    private static void unstyle(SeekBar bar, State state) {
        if (!state.styled) return;
        state.styled = false;
        if (bar.getProgressDrawable() == state.bigTrack) bar.setProgressDrawable(state.originalProgress);
        if (bar.getBackground() == state.backdrop) bar.setBackground(state.originalBackground);
        restoreClipping(state);
        state.originalProgress = null;
        state.originalBackground = null;
        state.backdrop = null;
        state.styledHeight = -1;
        state.styledBottom = -1;
    }

    private static void restoreClipping(State state) {
        for (int i = 0; i < state.unclippedCount; i++) {
            state.unclipped[i].setClipChildren(state.clippedBefore[i]);
            state.unclipped[i] = null;
        }
        state.unclippedCount = 0;
    }

    /** A translucent track with the progress over it in white, both [BIG_TRACK_DP] thick, drawn in code. */
    private static LayerDrawable bigTrack(float density) {
        float thickness = BIG_TRACK_DP * density;
        ClipDrawable progress = new ClipDrawable(new Pill(Color.WHITE, thickness), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable track = new LayerDrawable(new Drawable[] {new Pill(BIG_TRACK_COLOR, thickness), progress});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        return track;
    }

    /** A rounded bar of a fixed thickness, centered in its bounds' height and as wide as they are. */
    static final class Pill extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float thickness;

        Pill(int color, float thickness) {
            this.paint.setColor(color);
            this.thickness = thickness;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float middle = (bounds.top + bounds.bottom) / 2f;
            rect.set(bounds.left, middle - thickness / 2f, bounds.right, middle + thickness / 2f);
            canvas.drawRoundRect(rect, thickness / 2f, thickness / 2f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** "m:ss", or "h:mm:ss" from an hour, like Instagram's own scrubber times, in the phone's digits. */
    static String time(long milliseconds) {
        long seconds = Math.max(0L, milliseconds) / 1000L;
        long hours = seconds / 3600L;
        long minutes = seconds / 60L % 60L;
        Locale locale = Locale.getDefault();
        return hours > 0
                ? String.format(locale, "%d:%02d:%02d", hours, minutes, seconds % 60L)
                : String.format(locale, "%d:%02d", minutes, seconds % 60L);
    }

    /** The label's text: the time played, past the end counted as the end, and the length. */
    static String text(int progress, int max) {
        return time(played(progress, max)) + " / " + time(max);
    }

    private static int played(int progress, int max) {
        return Math.max(0, Math.min(progress, max));
    }

    private static Tracker trackerOf(SeekBar bar) {
        synchronized (TRACKERS) {
            WeakReference<Tracker> held = TRACKERS.get(bar);
            return held == null ? null : held.get();
        }
    }

    /** Whether the container Instagram bound nearest above [bar] holds an ordinary reel. */
    private static boolean ordinaryReel(View bar) {
        View container = containerOf(bar);
        if (container == null) return false;
        synchronized (REELS) {
            return Boolean.TRUE.equals(REELS.get(container));
        }
    }

    /**
     * The nearest view above [bar], its parent first and up to {@link #TAG_REACH}, that Instagram
     * bound to a reel, or null. On 449 that's the bar's parent, the FrameLayout the scrubber puts
     * it in.
     */
    private static View containerOf(View bar) {
        ViewParent parent = bar.getParent();
        synchronized (REELS) {
            for (int i = 0; i < TAG_REACH && parent instanceof View; i++) {
                if (REELS.containsKey(parent)) return (View) parent;
                parent = parent.getParent();
            }
        }
        return null;
    }

    /** The bars [container] is the nearest bound container of, down to {@link #TAG_REACH} below it. */
    private static List<SeekBar> barsIn(View container) {
        List<SeekBar> bars = new ArrayList<>(1);
        collectBars(container, TAG_REACH, bars);
        List<SeekBar> own = new ArrayList<>(bars.size());
        for (SeekBar bar : bars) {
            if (containerOf(bar) == container) own.add(bar);
        }
        return own;
    }

    private static void collectBars(View view, int depth, List<SeekBar> bars) {
        if (depth <= 0 || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof SeekBar) {
                bars.add((SeekBar) child);
            } else {
                collectBars(child, depth - 1, bars);
            }
        }
    }

    /** Makes the bar's one label and listener, and has the bar keep them. */
    private static Tracker track(SeekBar bar, BooleanSupplier on) {
        Tracker tracker = new Tracker(create(bar), on);
        synchronized (TRACKERS) {
            TRACKERS.put(bar, new WeakReference<>(tracker));
        }
        bar.addOnLayoutChangeListener(tracker);
        bar.addOnAttachStateChangeListener(tracker);
        if (bar.isAttachedToWindow()) tracker.watch(bar);
        return tracker;
    }

    private static TextView create(SeekBar bar) {
        Context context = bar.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.round(GAP_DP * density);
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.WHITE);
        // Over the video, the shadow keeps white text readable on a bright frame.
        label.setShadowLayer(2f * density, 0f, 0.5f * density, 0x99000000);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        label.setPadding(gap, gap / 2, gap, gap / 2);
        label.setClickable(false);
        label.setLongClickable(false);
        label.setFocusable(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        label.setVisibility(View.GONE);
        // Layout parameters before any view holds it: a TextView that has been measured reads them
        // on every new text, and a label with no room, or waiting for another bar's, holds none.
        label.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        label.setTag(new State(bar));
        return label;
    }

    private static SeekBar barOf(TextView label) {
        return ((State) label.getTag()).bar.get();
    }

    private static void show(SeekBar bar, ViewGroup parent, TextView label, int progress, int max) {
        State state = (State) label.getTag();
        String reel = reelOf(bar);
        if (max != state.max || !Objects.equals(reel, state.reel)) {
            state.max = max;
            state.reel = reel;
            state.text = null;
            state.width = widthFor(label, max);
        }
        String text = text(progress, max);
        boolean changed = !text.equals(state.text);
        if (changed) {
            state.text = text;
            label.setText(text);
            label.setContentDescription(L10n.f("%1$s of %2$s", time(played(progress, max)), time(max)));
        }
        Host host = bar.getWidth() > 0 && bar.getHeight() > 0 ? hostFor(bar, parent, label, state.width) : null;
        if (host == null || !claim(host.view, label)) {
            // Not laid out yet, nowhere near the bar has room for all of the label, or another
            // bar's label is showing there.
            hide(bar, label);
            return;
        }
        attach(host, label, state);
        if (state.overlay == null) {
            if (state.described) undescribe(bar, state);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (changed || !state.described)) {
            bar.setStateDescription(label.getContentDescription());
            state.described = true;
        }
        place(bar, host, label, state);
        state.wanted = true;
        follow(bar, label);
    }

    /**
     * Shows the label while its bar shows and isn't being dragged, faded as much as the views from
     * the bar up to the label's holder are, and hides it otherwise. A label whose bar is no longer
     * under its holder, or no longer in a container bound to an ordinary reel, comes off.
     */
    private static void follow(SeekBar bar, TextView label) {
        State state = (State) label.getTag();
        ViewGroup holder = holderOf(label);
        if (holder == null) return;
        if (!under(bar, holder) || !ordinaryReel(bar)) {
            remove(bar, label);
            return;
        }
        float alpha = alphaUpTo(bar, holder);
        boolean visible = state.wanted && bar.isShown() && !bar.isPressed() && alpha > 0f;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (label.getVisibility() != visibility) label.setVisibility(visibility);
        if (visible && label.getAlpha() != alpha) label.setAlpha(alpha);
    }

    /** The view holding the label: the one whose overlay it's on, or its parent, or null. */
    private static ViewGroup holderOf(TextView label) {
        State state = (State) label.getTag();
        ViewParent parent = label.getParent();
        if (parent == null) return null;
        if (state.overlay != null) return state.overlay.get();
        return parent instanceof ViewGroup ? (ViewGroup) parent : null;
    }

    /** Whether [holder] is the bar's parent or a view up to {@link #HOST_REACH} above it. */
    private static boolean under(View bar, ViewGroup holder) {
        ViewParent parent = bar.getParent();
        for (int i = 0; i <= HOST_REACH && parent != null; i++) {
            if (parent == holder) return true;
            parent = parent.getParent();
        }
        return false;
    }

    /** How faded the bar is, with every view between it and [holder]: the holder fades the label itself. */
    private static float alphaUpTo(View bar, ViewGroup holder) {
        float alpha = 1f;
        View view = bar;
        while (view != null && view != holder) {
            alpha *= view.getAlpha();
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return alpha;
    }

    /**
     * Whether [label] may go in [host], which holds one label at most. Another label there gives
     * way when its bar is gone, no longer under [host], not showing, or its label isn't wanted;
     * otherwise [label] waits. {@link #attach} records the hold once the label is in place.
     */
    private static boolean claim(ViewGroup host, TextView label) {
        TextView other;
        synchronized (HELD) {
            WeakReference<TextView> held = HELD.get(host);
            other = held == null ? null : held.get();
        }
        if (other != null && other != label && holderOf(other) == host) {
            SeekBar otherBar = barOf(other);
            boolean showing = ((State) other.getTag()).wanted && otherBar != null && otherBar.isShown()
                    && under(otherBar, host);
            if (showing) return false;
            remove(otherBar, other);
        }
        return true;
    }

    /**
     * The nearest view, the bar's parent first, with room above the bar's track for the whole label
     * and the gap under it, or null when none within {@link #HOST_REACH} has it.
     */
    private static Host hostFor(SeekBar bar, ViewGroup parent, TextView label, int width) {
        label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int needed = label.getMeasuredHeight() + Math.round(GAP_DP * bar.getResources().getDisplayMetrics().density);
        int left = bar.getLeft();
        int top = bar.getTop();
        ViewGroup view = parent;
        for (int i = 0; i < HOST_REACH; i++) {
            int room = top + bar.getPaddingTop();
            // A child is laid out inside the FrameLayout's padding; an overlay draws over all of the view.
            if (i == 0 && view instanceof FrameLayout && room - view.getPaddingTop() >= needed) {
                return new Host(view, left, top, true);
            }
            if (room >= needed) return new Host(view, left, top, false);
            ViewParent up = view.getParent();
            if (!(up instanceof ViewGroup)) return null;
            left += view.getLeft();
            top += view.getTop();
            view = (ViewGroup) up;
        }
        return null;
    }

    /**
     * Puts the label in [host], a child of the bar's FrameLayout or on the view's overlay, and
     * records that [host] holds it. The record comes last: moving between a view's children and
     * the same view's overlay takes the label off first, which drops the view's record.
     */
    private static void attach(Host host, TextView label, State state) {
        if (host.child) {
            if (label.getParent() != host.view || state.overlay != null) {
                detach(label, state);
                host.view.addView(label, new FrameLayout.LayoutParams(
                        state.width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END));
            }
        } else {
            ViewGroup current = state.overlay == null ? null : state.overlay.get();
            if (current != host.view || label.getParent() == null) {
                detach(label, state);
                // A width of its own, so a new text redraws the label without asking for a new layout.
                label.setLayoutParams(new ViewGroup.LayoutParams(state.width, ViewGroup.LayoutParams.WRAP_CONTENT));
                host.view.getOverlay().add(label);
                state.overlay = new WeakReference<>(host.view);
            }
        }
        synchronized (HELD) {
            HELD.put(host.view, new WeakReference<>(label));
        }
    }

    /** Takes the label off whatever holds it, and lets that view hold another. */
    private static void detach(TextView label, State state) {
        ViewGroup holder = holderOf(label);
        ViewGroup host = state.overlay == null ? null : state.overlay.get();
        if (host != null) host.getOverlay().remove(label);
        state.overlay = null;
        ViewParent current = label.getParent();
        if (current instanceof ViewGroup) ((ViewGroup) current).removeView(label);
        if (holder != null) {
            synchronized (HELD) {
                WeakReference<TextView> held = HELD.get(holder);
                if (held != null && held.get() == label) HELD.remove(holder);
            }
        }
    }

    /** Hides the label and takes it off whatever holds it. [bar] may be gone. */
    private static void remove(SeekBar bar, TextView label) {
        State state = (State) label.getTag();
        if (bar != null) updateThumb(bar, state, false);
        state.text = null;
        state.wanted = false;
        label.setVisibility(View.GONE);
        if (bar != null && state.described) undescribe(bar, state);
        detach(label, state);
    }

    /**
     * Above the end of the bar's track: the right end, or the left in a right-to-left layout,
     * where the bar fills from the right. Layout parameters change only when the place does, so
     * playback doesn't ask the reel for a new layout on every tick.
     */
    private static void place(SeekBar bar, Host host, TextView label, State state) {
        int gap = Math.round(GAP_DP * bar.getResources().getDisplayMetrics().density);
        boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (host.child) {
            ViewGroup parent = host.view;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) label.getLayoutParams();
            int bottom = (parent.getHeight() - parent.getPaddingBottom() - bar.getBottom())
                    + bar.getHeight() - bar.getPaddingTop() + gap;
            int end = rtl
                    ? bar.getLeft() + bar.getPaddingLeft() - parent.getPaddingLeft()
                    : parent.getWidth() - parent.getPaddingRight() - bar.getRight() + bar.getPaddingRight();
            end = Math.max(0, end);
            bottom = Math.max(0, bottom);
            if (params.width != state.width || params.bottomMargin != bottom || params.getMarginEnd() != end
                    || params.gravity != (Gravity.BOTTOM | Gravity.END)) {
                params.width = state.width;
                params.bottomMargin = bottom;
                params.setMarginEnd(end);
                params.gravity = Gravity.BOTTOM | Gravity.END;
                label.setLayoutParams(params);
            }
            return;
        }
        ViewGroup.LayoutParams params = label.getLayoutParams();
        if (params != null && params.width != state.width) {
            params.width = state.width;
            label.setLayoutParams(params);
        }
        label.measure(View.MeasureSpec.makeMeasureSpec(state.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = label.getMeasuredHeight();
        int left = rtl
                ? host.barLeft + bar.getPaddingLeft()
                : host.barLeft + bar.getWidth() - bar.getPaddingRight() - state.width;
        int top = Math.max(0, host.barTop + bar.getPaddingTop() - gap - height);
        label.layout(left, top, left + state.width, top + height);
    }

    /**
     * Wide enough for any time up to [max] in the label's own paint, so a font scale or the
     * phone's digits never cut it off: the longest text, measured as it is and with every digit
     * as each of 0 to 9, whichever is widest.
     */
    private static int widthFor(TextView label, int max) {
        String longest = text(max, max);
        Paint paint = label.getPaint();
        float width = paint.measureText(longest);
        for (char digit = '0'; digit <= '9'; digit++) {
            width = Math.max(width, paint.measureText(withDigits(longest, digit)));
        }
        return (int) Math.ceil(width) + label.getPaddingLeft() + label.getPaddingRight() + 1;
    }

    private static String withDigits(String text, char digit) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isDigit(c) ? digit : c);
        }
        return out.toString();
    }

    /**
     * The tag Instagram 449 gives the bar's reel container when it binds it, "clips_scrubber_" and
     * the reel's id, or null.
     */
    static String reelOf(View bar) {
        View container = containerOf(bar);
        Object tag = container == null ? null : container.getTag();
        return tag instanceof String && ((String) tag).startsWith(REEL_TAG_PREFIX) ? (String) tag : null;
    }

    private static void hide(SeekBar bar, TextView label) {
        State state = (State) label.getTag();
        state.text = null;
        state.wanted = false;
        label.setVisibility(View.GONE);
        if (state.described) undescribe(bar, state);
    }

    /** Gives the bar back the state Android describes it with, which the label's words stood in for. */
    private static void undescribe(SeekBar bar, State state) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bar.setStateDescription(null);
        state.described = false;
    }

    /** After a failure the label goes, so it can't stay up with a wrong time. Logged if even that fails. */
    private static void hideAfterFailure(TextView label) {
        if (label == null) return;
        try {
            State state = (State) label.getTag();
            SeekBar bar = state.bar.get();
            if (bar != null) updateThumb(bar, state, false);
            state.wanted = false;
            label.setVisibility(View.GONE);
        } catch (RuntimeException alsoFailed) {
            Logger.printException(() -> "Reel seek bar: the label couldn't be hidden", alsoFailed);
        }
    }
}
