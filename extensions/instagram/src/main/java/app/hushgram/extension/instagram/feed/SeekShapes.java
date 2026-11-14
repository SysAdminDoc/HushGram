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
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
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
     * What HushGram turned off on a view a shaped bar draws past, and how many bars need it so. Two bars
     * can share a view, which clips again only once neither needs the room, and only what HushGram
     * turned off is turned back on.
     */
    private static final class Opened {
        final boolean children;
        final boolean padding;
        int bars;

        Opened(boolean children, boolean padding) {
            this.children = children;
            this.padding = padding;
        }
    }

    private static final Map<ViewGroup, Opened> OPENED = new WeakHashMap<>();

    static final String STORY_HOOK = "story progress shape";
    static final String CHAT_HOOK = "chat video seek bar shape";

    /** The shape the story progress bar drawing now takes, read at the top of its onDraw, and the screen's density. */
    private static SeekShape storyShape = SeekShape.STANDARD;
    private static float storyDensity = 1f;
    private static final RectF SEGMENT = new RectF();
    private static final Path SEGMENT_WAVE = new Path();
    /** Each header a story progress bar draws past, with whether it clipped its children before. */
    private static final Map<ViewGroup, Boolean> UNCLIPPED = new WeakHashMap<>();

    private SeekShapes() {
    }

    /** The shape chosen for every seek bar: {@link SeekShape#STANDARD} while the settings can't be read or HushGram is paused. */
    public static SeekShape wanted() {
        return Utils.settingsReady() ? Settings.SEEK_BAR_SHAPE.get() : SeekShape.STANDARD;
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
                && room.above == above && room.below == below) return;
        if (room != null) giveRoomBack(room);
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
            open(group);
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

    /** Lets [group] draw its children past its edges and padding for one more bar. */
    private static void open(ViewGroup group) {
        Opened opened = OPENED.get(group);
        if (opened == null) {
            boolean children = group.getClipChildren();
            boolean padding = group.getClipToPadding() && (group.getPaddingTop() > 0 || group.getPaddingBottom() > 0);
            opened = new Opened(children, padding);
            OPENED.put(group, opened);
            if (children) group.setClipChildren(false);
            if (padding) group.setClipToPadding(false);
        }
        opened.bars++;
    }

    /** Gives the views a bar was let draw past their clipping back, each once no other bar needs it. */
    private static void giveRoomBack(Room room) {
        for (WeakReference<ViewGroup> held : room.views) {
            ViewGroup group = held.get();
            Opened opened = group == null ? null : OPENED.get(group);
            if (opened == null || --opened.bars > 0) continue;
            OPENED.remove(group);
            if (opened.children && !group.getClipChildren()) group.setClipChildren(true);
            if (opened.padding && !group.getClipToPadding()) group.setClipToPadding(true);
        }
        room.views.clear();
    }

    /**
     * First thing in the story progress bar's onDraw (#630): reads the shape its segments take this
     * frame and, while it isn't {@link SeekShape#STANDARD}, lets [bar] draw past its few pixels of
     * height into the header around it, which a thicker shape needs. Back on standard the header
     * clips again as it did. Never throws.
     */
    public static void storyBar(View bar) {
        try {
            SeekShape shape = wanted();
            storyDensity = bar.getResources().getDisplayMetrics().density;
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
     * drew it; otherwise it draws the segment in the shape {@link #storyBar} read.
     */
    public static void storySegment(Canvas canvas, RectF rect, float rx, float ry, Paint paint) {
        SeekShape shape = storyShape;
        if (shape != SeekShape.STANDARD) {
            try {
                SeekShapeDrawable.segment(canvas, rect, paint, shape, storyDensity, SEGMENT, SEGMENT_WAVE);
                return;
            } catch (Throwable failure) {
                storyShape = SeekShape.STANDARD;
                HookStatus.threw(FamilyNames.FEED_SEEK_BAR, STORY_HOOK, failure);
            }
        }
        canvas.drawRoundRect(rect, rx, ry, paint);
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
        UNCLIPPED.clear();
        ROOM.clear();
        OPENED.clear();
    }

    /** Gives [bar] back what a shape replaced, and the views round it their clipping, and does nothing to a bar with no shape on it. */
    public static void restore(SeekBar bar) {
        Room room = ROOM.remove(bar);
        if (room != null) giveRoomBack(room);
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
