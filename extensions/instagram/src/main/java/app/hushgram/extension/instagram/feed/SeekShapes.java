/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StoryProgressBar;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Puts a {@link SeekShape} on a SeekBar and takes it off again (#586). Only the bar's drawables
 * change, and its side padding for a line flush with the edges, so its touches and listeners stay
 * as they were. What a shape replaced is kept and given back when the bar goes back to
 * {@link SeekShape#STANDARD}. Main thread only.
 *
 * <p>The same choice draws the segments of a story's progress bar and the seek bar of a video opened
 * from a chat (#630), through the hooks below.
 */
public final class SeekShapes {
    /** No handle: the shape draws its own, and a SeekBar lays out around this one's nothing. */
    private static final class NoHandle extends android.graphics.drawable.ColorDrawable {
        NoHandle() {
            super(0);
        }

        @Override
        public int getIntrinsicWidth() {
            return 0;
        }

        @Override
        public int getIntrinsicHeight() {
            return 0;
        }
    }

    private static final class Stock {
        @Nullable final Drawable progress;
        @Nullable final Drawable thumb;
        final int thumbOffset;
        final int paddingLeft;
        final int paddingRight;

        Stock(SeekBar bar) {
            progress = bar.getProgressDrawable();
            thumb = bar.getThumb();
            thumbOffset = bar.getThumbOffset();
            paddingLeft = bar.getPaddingLeft();
            paddingRight = bar.getPaddingRight();
        }
    }

    private static final Map<SeekBar, Stock> STOCK = new WeakHashMap<>();

    /** At most this many views round a shaped bar stop clipping it, its parent first. */
    static final int ROOM_REACH = 4;

    /**
     * The views a shaped bar was let draw past and the bar's place then. Held weakly: the views hold
     * the bar, and a value that leads back to its key keeps a WeakHashMap entry forever.
     */
    private static final class Room {
        final ArrayList<WeakReference<ViewGroup>> views = new ArrayList<>(ROOM_REACH);
        WeakReference<ViewParent> parent = new WeakReference<>(null);
        int top;
        int height;
        int above;
        int below;
    }

    private static final Map<SeekBar, Room> ROOM = new WeakHashMap<>();

    /**
     * What HushGram turned off on a view a shaped bar draws past, and the bars that need it so. Two bars
     * can share a view, which clips again only once neither needs the room, and only what HushGram
     * turned off is turned back on. The bars are held weakly, so one dropped without going back to
     * standard doesn't keep the view open for good.
     */
    private static final class Opened {
        final boolean children;
        final boolean padding;
        final ArrayList<WeakReference<SeekBar>> bars = new ArrayList<>(2);

        Opened(boolean children, boolean padding) {
            this.children = children;
            this.padding = padding;
        }

        void add(SeekBar bar) {
            for (WeakReference<SeekBar> held : bars) {
                if (held.get() == bar) return;
            }
            bars.add(new WeakReference<>(bar));
        }

        /** Takes [bar] off, with every bar that's gone. True when no bar needs the room any more. */
        boolean without(SeekBar bar) {
            for (int i = bars.size() - 1; i >= 0; i--) {
                SeekBar held = bars.get(i).get();
                if (held == null || held == bar) bars.remove(i);
            }
            return bars.isEmpty();
        }
    }

    private static final Map<ViewGroup, Opened> OPENED = new WeakHashMap<>();

    static final String STORY_HOOK = "story progress shape";
    static final String CHAT_HOOK = "chat video seek bar shape";

    /** The shape the story progress bar drawing now takes, read at the top of its onDraw, and the screen's density. */
    private static SeekShape storyShape = SeekShape.STANDARD;
    private static float storyDensity = 1f;
    /**
     * How many times thicker Thicker story progress bar draws the bar this frame. A shape undoes the
     * stretch on its waves (#689) and adds what it would add to Instagram's segment to its track (#775).
     */
    private static float storyStretch = 1f;
    private static final RectF SEGMENT = new RectF();
    private static final Path SEGMENT_WAVE = new Path();
    /**
     * The see-through track of the segment playing now, held back while the wave shape draws (#796):
     * Instagram draws that segment's whole track and then its played part over it, and the wave's
     * gaps showed the straight track running on under the wave. Held until its played part comes, it
     * then draws only the rest of the track past the wave. At most {@link #HELD_MOST} copies, one per
     * call Instagram made on that segment, kept for the frame.
     */
    private static final int HELD_MOST = 4;
    private static final RectF[] HELD_TRACKS = new RectF[HELD_MOST];
    private static final Paint[] HELD_PAINTS = new Paint[HELD_MOST];
    private static final RectF HELD_PIECE = new RectF();
    private static int held;
    /** Whether this frame's playing segment has had its track drawn, held or not, so later tracks draw at once. */
    private static boolean heldDone;
    /** Whether the bar's onDraw tells {@link #storyBarDrawn} it's done, without which nothing is held. */
    private static boolean barEndHooked;
    /** Each header a story progress bar draws past, with whether it clipped its children before. */
    private static final Map<ViewGroup, Boolean> UNCLIPPED = new WeakHashMap<>();

    private SeekShapes() {
    }

    /** The shape chosen for every seek bar: {@link SeekShape#STANDARD} while the settings can't be read or HushGram is paused. */
    public static SeekShape wanted() {
        return Utils.settingsReady() ? Settings.SEEK_BAR_SHAPE.get() : SeekShape.STANDARD;
    }

    /**
     * The shape of the seek bar on feed videos: {@link Settings#FEED_SEEK_SHAPE} when it picks one of
     * its own (#567), otherwise {@link #wanted()}.
     */
    public static SeekShape feedWanted() {
        return Utils.settingsReady() ? Settings.FEED_SEEK_SHAPE.get().or(Settings.SEEK_BAR_SHAPE.get()) : SeekShape.STANDARD;
    }

    /** Whether a shape other than the standard one is chosen. */
    public static boolean changed() {
        return wanted() != SeekShape.STANDARD;
    }

    /** Draws [bar] in [shape] with [look]'s colours, along its bottom edge when [flush]. {@link SeekShape#STANDARD} gives back what it had. */
    public static void apply(SeekBar bar, SeekShape shape, OverlayLook look, boolean flush) {
        if (shape == SeekShape.STANDARD) {
            restore(bar);
            return;
        }
        int played = GlassSeekBar.progress(look);
        int rest = GlassSeekBar.track(look);
        Drawable now = bar.getProgressDrawable();
        if (now instanceof SeekShapeDrawable && ((SeekShapeDrawable) now).draws(shape, flush)) {
            ((SeekShapeDrawable) now).colors(played, rest);
            makeRoom(bar, (SeekShapeDrawable) now);
            return;
        }
        if (!STOCK.containsKey(bar)) STOCK.put(bar, new Stock(bar));
        float density = bar.getResources().getDisplayMetrics().density;
        SeekShapeDrawable drawn = new SeekShapeDrawable(shape, flush, density, played, rest);
        bar.setProgressDrawable(drawn);
        if (!(bar.getThumb() instanceof NoHandle)) bar.setThumb(new NoHandle());
        bar.setThumbOffset(0);
        boolean edgeToEdge = flush && shape == SeekShape.LINE;
        Stock stock = STOCK.get(bar);
        int left = edgeToEdge || stock == null ? 0 : stock.paddingLeft;
        int right = edgeToEdge || stock == null ? 0 : stock.paddingRight;
        bar.setPadding(left, bar.getPaddingTop(), right, bar.getPaddingBottom());
        makeRoom(bar, drawn);
    }

    /**
     * Lets [bar] draw [shape] past its own edges (#450). A reel's bar is a few pixels high, so the
     * views round it cut the top off the wave and the tall handle. Its parent stops clipping, then
     * each view above that until one has the room, at most {@link #ROOM_REACH} of them, and a view
     * whose padding would cut it stops clipping to its padding too. The walk stops before a view that
     * scrolls, such as the reels' pager, whose pages would all draw past their edges. Called on every
     * tick, so it does nothing while the bar stays where it was; once it moved, changed size or needs
     * other room, the old views get their clipping back first. Waits for the bar's first layout.
     */
    private static void makeRoom(SeekBar bar, SeekShapeDrawable shape) {
        int height = bar.getHeight();
        if (height <= 0 || shape.getBounds().isEmpty()) return;
        int[] spill = shape.spill();
        // ProgressBar draws its track moved down by its top padding.
        int above = Math.max(0, spill[0] - bar.getPaddingTop() - shape.getBounds().top);
        int below = Math.max(0, bar.getPaddingTop() + shape.getBounds().bottom + spill[1] - height);
        ViewParent parent = bar.getParent();
        Room room = ROOM.get(bar);
        if (room != null && room.parent.get() == parent && room.top == bar.getTop() && room.height == height
                && room.above == above && room.below == below && !scrollsNow(room)) return;
        if (room != null) giveRoomBack(room, bar);
        clipForGoneBars();
        if (above == 0 && below == 0) {
            ROOM.remove(bar);
            return;
        }
        if (room == null) room = new Room();
        room.parent = new WeakReference<>(parent);
        room.top = bar.getTop();
        room.height = height;
        room.above = above;
        room.below = below;
        ROOM.put(bar, room);
        View child = bar;
        while ((above > 0 || below > 0) && room.views.size() < ROOM_REACH && child.getParent() instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) child.getParent();
            if (scrolls(group)) break;
            open(group, bar);
            room.views.add(new WeakReference<>(group));
            above -= child.getTop();
            below -= group.getHeight() - child.getBottom();
            child = group;
        }
    }

    /** Whether [group] scrolls either way, as a pager or a list does. */
    private static boolean scrolls(ViewGroup group) {
        return group.canScrollVertically(1) || group.canScrollVertically(-1)
                || group.canScrollHorizontally(1) || group.canScrollHorizontally(-1);
    }

    /**
     * Whether a view [room] was opened in scrolls now. A reels pager holding one reel can't scroll when
     * the walk passes it, and can once the next reel loads, so each tick looks again and the walk then
     * stops before it.
     */
    private static boolean scrollsNow(Room room) {
        for (WeakReference<ViewGroup> held : room.views) {
            ViewGroup group = held.get();
            if (group != null && scrolls(group)) return true;
        }
        return false;
    }

    /** Lets [group] draw its children past its edges and padding for [bar] too. */
    private static void open(ViewGroup group, SeekBar bar) {
        Opened opened = OPENED.get(group);
        if (opened == null) {
            boolean children = group.getClipChildren();
            boolean padding = group.getClipToPadding() && (group.getPaddingTop() > 0 || group.getPaddingBottom() > 0);
            opened = new Opened(children, padding);
            OPENED.put(group, opened);
            if (children) group.setClipChildren(false);
            if (padding) group.setClipToPadding(false);
        }
        opened.add(bar);
    }

    /** Gives the views [bar] was let draw past their clipping back, each once no other bar needs it. */
    private static void giveRoomBack(Room room, SeekBar bar) {
        for (WeakReference<ViewGroup> held : room.views) {
            ViewGroup group = held.get();
            Opened opened = group == null ? null : OPENED.get(group);
            if (opened == null || !opened.without(bar)) continue;
            OPENED.remove(group);
            clipAgain(group, opened);
        }
        room.views.clear();
    }

    /**
     * Clips the views again that only bars gone since needed open: a reel dropped without its bar
     * going back to standard took its room along. Looked at whenever a bar makes new room.
     */
    private static void clipForGoneBars() {
        for (Iterator<Map.Entry<ViewGroup, Opened>> it = OPENED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ViewGroup, Opened> entry = it.next();
            if (!entry.getValue().without(null)) continue;
            it.remove();
            clipAgain(entry.getKey(), entry.getValue());
        }
    }

    /** Turns back on what HushGram turned off on [group]. */
    private static void clipAgain(ViewGroup group, Opened opened) {
        if (opened.children && !group.getClipChildren()) group.setClipChildren(true);
        if (opened.padding && !group.getClipToPadding()) group.setClipToPadding(true);
    }

    /**
     * First thing in the story progress bar's onDraw (#630): reads the shape its segments take this
     * frame and, while it isn't {@link SeekShape#STANDARD}, lets [bar] draw past its few pixels of
     * height into the header around it, which a thicker shape needs. Back on standard the header
     * clips again as it did. On a bar Thicker story progress bar stretches, a shape keeps its waves
     * their own size (#689) and still draws thicker (#775), so it reads that stretch too. Never throws.
     */
    public static void storyBar(View bar) {
        held = 0;
        heldDone = false;
        try {
            SeekShape shape = wanted();
            storyDensity = bar.getResources().getDisplayMetrics().density;
            storyStretch = shape == SeekShape.STANDARD ? 1f : StoryProgressBar.stretchOf(bar);
            storyShape = shape;
            ViewParent parent = bar.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup header = (ViewGroup) parent;
            if (shape == SeekShape.STANDARD) {
                Boolean clipped = UNCLIPPED.remove(header);
                if (clipped != null && clipped != header.getClipChildren()) header.setClipChildren(clipped);
            } else if (!UNCLIPPED.containsKey(header)) {
                UNCLIPPED.put(header, header.getClipChildren());
                if (header.getClipChildren()) header.setClipChildren(false);
            }
        } catch (Throwable failure) {
            storyShape = SeekShape.STANDARD;
            HookStatus.threw(FamilyNames.FEED_SEEK_BAR, STORY_HOOK, failure);
        }
    }

    /**
     * Stands in for each Canvas.drawRoundRect the story progress bar makes (#630), with the same
     * arguments. On {@link SeekShape#STANDARD} it makes that very call, so the bar looks as Instagram
     * drew it; otherwise it draws the segment in the shape {@link #storyBar} read. The wave holds the
     * playing segment's track back until its played part comes (#796).
     */
    public static void storySegment(Canvas canvas, RectF rect, float rx, float ry, Paint paint) {
        SeekShape shape = storyShape;
        if (shape != SeekShape.STANDARD) {
            try {
                if (shape == SeekShape.WAVE && barEndHooked && heldBack(canvas, rect, paint)) return;
                SeekShapeDrawable.segment(canvas, rect, paint, shape, storyDensity, storyStretch, SEGMENT, SEGMENT_WAVE);
                return;
            } catch (Throwable failure) {
                held = 0;
                storyShape = SeekShape.STANDARD;
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, STORY_HOOK, failure);
            }
        }
        canvas.drawRoundRect(rect, rx, ry, paint);
    }

    /**
     * Last thing before each return of the story progress bar's onDraw (#796): draws whole whatever
     * track the wave still holds, a segment whose played part never came. Never throws.
     */
    public static void storyBarDrawn(Canvas canvas) {
        barEndHooked = true;
        if (held == 0) return;
        try {
            drawHeld(canvas, null);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SEEK_BAR, STORY_HOOK, failure);
        } finally {
            held = 0;
        }
    }

    /**
     * Whether the wave keeps [rect] back to draw later: the first see-through track of the frame, and
     * any more on that same segment, are the playing segment's. An opaque segment, its played part,
     * first draws the held tracks without the part it covers, so the wave never lies over a straight
     * track; anything else first draws them whole. Instagram's [paint] and [rect] are copied, as it
     * changes both right after.
     */
    private static boolean heldBack(Canvas canvas, RectF rect, Paint paint) {
        boolean track = paint.getAlpha() < SeekShapeDrawable.PLAYED_ALPHA;
        if (track && !heldDone && held < HELD_MOST && (held == 0 || sameSegment(HELD_TRACKS[0], rect))) {
            if (HELD_TRACKS[held] == null) {
                HELD_TRACKS[held] = new RectF();
                HELD_PAINTS[held] = new Paint();
            }
            HELD_TRACKS[held].set(rect);
            HELD_PAINTS[held].set(paint);
            held++;
            return true;
        }
        if (held > 0) {
            try {
                drawHeld(canvas, track ? null : rect);
            } finally {
                held = 0;
                heldDone = true;
            }
        }
        return false;
    }

    /** Whether [rect] lies over [track] at all, which a later call on the same segment does. */
    private static boolean sameSegment(RectF track, RectF rect) {
        return rect.left < track.right && rect.right > track.left && rect.top < track.bottom && rect.bottom > track.top;
    }

    /**
     * Draws the held tracks, each without the stretch [played] covers when it's on the same row: the
     * part left of it and the part right of it, so a bar filling from either side keeps its rest.
     */
    private static void drawHeld(Canvas canvas, @Nullable RectF played) {
        for (int i = 0; i < held; i++) {
            RectF track = HELD_TRACKS[i];
            Paint paint = HELD_PAINTS[i];
            if (played == null || !(played.top < track.bottom && played.bottom > track.top)) {
                SeekShapeDrawable.segment(canvas, track, paint, SeekShape.WAVE, storyDensity, storyStretch, SEGMENT, SEGMENT_WAVE);
                continue;
            }
            float before = Math.min(played.left, track.right);
            if (before > track.left) heldPiece(canvas, track, track.left, before, paint);
            float after = Math.max(played.right, track.left);
            if (after < track.right) heldPiece(canvas, track, after, track.right, paint);
        }
    }

    private static void heldPiece(Canvas canvas, RectF track, float left, float right, Paint paint) {
        HELD_PIECE.set(left, track.top, right, track.bottom);
        SeekShapeDrawable.segment(canvas, HELD_PIECE, paint, SeekShape.WAVE, storyDensity, storyStretch, SEGMENT, SEGMENT_WAVE);
    }

    /**
     * Once Instagram has built the seek bar of a video opened from a chat (#630): puts the chosen
     * shape on it, in the solid look the reels' bar takes. Standard leaves it as Instagram made it.
     */
    public static void chatVideo(SeekBar bar) {
        try {
            SeekShape shape = wanted();
            if (shape != SeekShape.STANDARD) apply(bar, shape, OverlayLook.SOLID, false);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SEEK_BAR, CHAT_HOOK, failure);
        }
    }

    static void resetForTests() {
        storyShape = SeekShape.STANDARD;
        storyDensity = 1f;
        storyStretch = 1f;
        held = 0;
        heldDone = false;
        barEndHooked = false;
        UNCLIPPED.clear();
        ROOM.clear();
        OPENED.clear();
    }

    /** Gives [bar] back what a shape replaced, and the views round it their clipping, and does nothing to a bar with no shape on it. */
    public static void restore(SeekBar bar) {
        Room room = ROOM.remove(bar);
        if (room != null) giveRoomBack(room, bar);
        Stock stock = STOCK.remove(bar);
        if (stock == null) return;
        if (bar.getProgressDrawable() instanceof SeekShapeDrawable) bar.setProgressDrawable(stock.progress);
        if (bar.getThumb() instanceof NoHandle) {
            bar.setThumb(stock.thumb);
            bar.setThumbOffset(stock.thumbOffset);
        }
        bar.setPadding(stock.paddingLeft, bar.getPaddingTop(), stock.paddingRight, bar.getPaddingBottom());
    }
}
