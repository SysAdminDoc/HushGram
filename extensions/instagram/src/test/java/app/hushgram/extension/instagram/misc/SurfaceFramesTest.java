/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/** #144: the copies of a SurfaceView's frames the glass blurs in the video's place. */
@RunWith(RobolectricTestRunner.class)
public class SurfaceFramesTest {
    private final List<SurfaceFrames.Done> pending = new ArrayList<>();
    private final List<Bitmap> targets = new ArrayList<>();
    private final AtomicInteger fresh = new AtomicInteger();
    private SurfaceView video;
    private SurfaceFrames.Copier savedCopier;
    private Predicate<SurfaceView> savedSurface;

    @Before public void setUp() {
        savedCopier = SurfaceFrames.copier;
        savedSurface = SurfaceFrames.hasSurface;
        SurfaceFrames.copier = (view, into, done) -> {
            targets.add(into);
            pending.add(done);
        };
        SurfaceFrames.hasSurface = view -> true;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        video = new SurfaceView(activity);
        root.addView(video);
        activity.setContentView(root);
        video.layout(0, 0, 1080, 1350);
    }

    @After public void tearDown() {
        SurfaceFrames.copier = savedCopier;
        SurfaceFrames.hasSurface = savedSurface;
    }

    private void land(boolean worked) {
        pending.remove(0).copied(worked);
    }

    @Test public void aCopyIsAQuarterOfTheViewAndNeverPastTheLongestSide() {
        assertArrayEquals(new int[] {270, 338}, SurfaceFrames.copySize(1080, 1350, 4));
        assertArrayEquals("held to the longest side", new int[] {480, 240}, SurfaceFrames.copySize(4000, 2000, 4));
        assertArrayEquals("never below a pixel", new int[] {1, 1}, SurfaceFrames.copySize(1, 1, 4));
        assertArrayEquals("no downsample below 1", new int[] {100, 50}, SurfaceFrames.copySize(100, 50, 0));
    }

    @Test public void oneCopyIsInFlightAtATimeAndTheLandedOneIsDrawn() {
        SurfaceFrames frames = new SurfaceFrames(4);
        assertNull("nothing copied yet", frames.frameOf(video));
        assertTrue(frames.ask(video, 1000, fresh::incrementAndGet));
        assertEquals(270, targets.get(0).getWidth());
        assertEquals(338, targets.get(0).getHeight());
        assertFalse("one copy in flight", frames.ask(video, 2000, fresh::incrementAndGet));
        assertNull("the copy hasn't landed", frames.frameOf(video));

        land(true);
        assertEquals("a landed copy asks for a redraw", 1, fresh.get());
        assertSame(targets.get(0), frames.frameOf(video));

        assertFalse("too soon for the next", frames.ask(video, 1000 + SurfaceFrames.COPY_EVERY_MS - 1, fresh::incrementAndGet));
        assertTrue(frames.ask(video, 1000 + SurfaceFrames.COPY_EVERY_MS, fresh::incrementAndGet));
        assertNotSame("the next copy isn't written into the frame being drawn", targets.get(0), targets.get(1));
        assertSame("the shown frame stays until the next lands", targets.get(0), frames.frameOf(video));
        land(true);
        assertSame(targets.get(1), frames.frameOf(video));

        assertTrue(frames.ask(video, 1000 + 2 * SurfaceFrames.COPY_EVERY_MS, fresh::incrementAndGet));
        assertSame("the two bitmaps take turns", targets.get(0), targets.get(2));
    }

    @Test public void framesThatKeepFailingRestAndLeaveThePlaceClear() {
        SurfaceFrames frames = new SurfaceFrames(4);
        frames.ask(video, 0, fresh::incrementAndGet);
        land(true);
        long now = 0;
        for (int i = 0; i < SurfaceFrames.TRIES; i++) {
            now += SurfaceFrames.COPY_EVERY_MS;
            assertTrue(frames.ask(video, now, fresh::incrementAndGet));
            if (i < SurfaceFrames.TRIES - 1) {
                land(false);
                assertSame("one failure keeps the last frame", targets.get(0), frames.frameOf(video));
            } else {
                land(false);
            }
        }
        assertNull("after the tries, the place is clear", frames.frameOf(video));
        assertEquals("a failed copy asks for nothing", 1, fresh.get());
        assertFalse("resting", frames.ask(video, now + SurfaceFrames.REST_MS - 1, fresh::incrementAndGet));
        assertTrue("tried again after the rest", frames.ask(video, now + SurfaceFrames.REST_MS, fresh::incrementAndGet));
    }

    @Test public void aRefusedCopyCountsAsAFailedOne() {
        SurfaceFrames.copier = (view, into, done) -> {
            throw new IllegalArgumentException("Surface isn't valid");
        };
        SurfaceFrames frames = new SurfaceFrames(4);
        assertTrue(frames.ask(video, 0, fresh::incrementAndGet));
        assertTrue("not left in flight", frames.ask(video, SurfaceFrames.COPY_EVERY_MS, fresh::incrementAndGet));
        assertNull(frames.frameOf(video));
    }

    @Test public void noCopyWithoutASurfaceOrWhileHidden() {
        SurfaceFrames frames = new SurfaceFrames(4);
        SurfaceFrames.hasSurface = view -> false;
        assertFalse(frames.ask(video, 0, fresh::incrementAndGet));
        SurfaceFrames.hasSurface = view -> true;
        video.setVisibility(View.INVISIBLE);
        assertFalse(frames.ask(video, 0, fresh::incrementAndGet));
        video.setVisibility(View.VISIBLE);
        assertTrue(frames.ask(video, 0, fresh::incrementAndGet));
    }

    @Test public void framesOfSurfaceViewsNoLongerUnderTheBarAreLetGo() {
        SurfaceFrames frames = new SurfaceFrames(4);
        frames.ask(video, 0, fresh::incrementAndGet);
        land(true);
        assertEquals(1, frames.size());
        frames.keepOnly(Collections.singleton(video));
        assertEquals(1, frames.size());
        frames.keepOnly(Collections.emptySet());
        assertEquals(0, frames.size());
        assertNull(frames.frameOf(video));
    }
}
