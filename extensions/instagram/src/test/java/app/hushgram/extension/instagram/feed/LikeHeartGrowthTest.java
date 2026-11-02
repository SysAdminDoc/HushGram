/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Grow the heart (#461): the step each double tap on the same heart is on, counted with an injected clock,
 * the spring on the way, and the scale the heart's draw puts on the canvas.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LikeHeartGrowthTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final AtomicLong now = new AtomicLong(10_000L);
    private final Drawable heart = new ColorDrawable(0xffff0000);
    private final Drawable other = new ColorDrawable(0xff00ff00);
    private final Drawable.Callback post = new Host();
    private final Drawable.Callback otherPost = new Host();

    @Before
    public void prepare() {
        Settings.CHANGE_LIKE_ANIMATION.resetToDefault();
        Settings.GROW_LIKE_HEART.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        LikeHeartGrowth.resetForTests();
        LikeHeartGrowth.clockForTests(now::get);
        heart.setBounds(100, 200, 300, 400);
    }

    @After
    public void restore() {
        LikeHeartGrowth.resetForTests();
        Settings.CHANGE_LIKE_ANIMATION.resetToDefault();
        Settings.GROW_LIKE_HEART.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The size the heart settles at after the tap just made. */
    private float settled(Object drawable) {
        return LikeHeartGrowth.scaleAt(drawable, now.get() + 5_000L);
    }

    private void tapAfter(long millis, Object drawable) {
        now.addAndGet(millis);
        LikeHeartGrowth.tap(drawable, now.get());
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertEquals(Boolean.FALSE, Settings.GROW_LIKE_HEART.defaultValue);
        assertFalse(Settings.GROW_LIKE_HEART.rebootApp);
    }

    @Test
    public void eachDoubleTapInTheWindowIsAStepBiggerUpToTheCap() {
        tapAfter(0, heart);
        assertEquals(1.0f, settled(heart), 0f);
        tapAfter(600, heart);
        assertEquals(1.25f, settled(heart), 0f);
        tapAfter(600, heart);
        assertEquals(1.5f, settled(heart), 0f);
        tapAfter(600, heart);
        assertEquals(1.75f, settled(heart), 0f);
        tapAfter(600, heart);
        assertEquals("the cap", 1.75f, settled(heart), 0f);
        tapAfter(600, heart);
        assertEquals("still the cap", 1.75f, settled(heart), 0f);
    }

    @Test
    public void theWindowRunsFromTheLastTapNotTheFirst() {
        tapAfter(0, heart);
        tapAfter(1_400, heart);
        tapAfter(1_400, heart);
        assertEquals("each tap was inside 1.5 s of the one before", 1.5f, settled(heart), 0f);
    }

    @Test
    public void aTapAfterTheWindowStartsOverAtNormalSize() {
        tapAfter(0, heart);
        tapAfter(600, heart);
        tapAfter(600, heart);
        assertEquals(1.5f, settled(heart), 0f);

        tapAfter(LikeHeartGrowth.WINDOW_MS + 1, heart);
        assertEquals(1.0f, settled(heart), 0f);
        assertEquals("and it springs from normal size, not from the old one", 1.0f,
                LikeHeartGrowth.scaleAt(heart, now.get() + 50L), 0f);
        tapAfter(600, heart);
        assertEquals("a new run grows from there", 1.25f, settled(heart), 0f);
    }

    @Test
    public void aTapOnAHeartInAnotherViewIsAnotherRun() {
        heart.setCallback(post);
        other.setCallback(otherPost);
        tapAfter(0, heart);
        tapAfter(600, heart);
        assertEquals(1.25f, settled(heart), 0f);

        tapAfter(600, other);
        assertEquals(1.0f, settled(other), 0f);
        assertEquals("the first one isn't drawn big any more", 1.0f, settled(heart), 0f);
    }

    /** A feed post builds a new heart for every double tap, in the same view. */
    @Test
    public void aNewHeartInTheSameViewKeepsGrowing() {
        Drawable second = new ColorDrawable(0xff0000ff);
        heart.setCallback(post);
        second.setCallback(post);
        tapAfter(0, heart);
        tapAfter(1_250, second);
        assertEquals(1.25f, settled(second), 0f);
        assertEquals("the old heart isn't the one drawn big", 1.0f, settled(heart), 0f);
    }

    /** The feed's heart isn't in a view yet when it starts playing. */
    @Test
    public void aNewHeartNotInAViewYetKeepsGrowingInsideTheWindow() {
        Drawable second = new ColorDrawable(0xff0000ff);
        Drawable third = new ColorDrawable(0xff00ffff);
        tapAfter(0, heart);
        tapAfter(1_250, second);
        tapAfter(1_250, third);
        assertEquals(1.5f, settled(third), 0f);

        tapAfter(LikeHeartGrowth.WINDOW_MS + 1, heart);
        assertEquals("after the window it starts over", 1.0f, settled(heart), 0f);
    }

    /** The view a heart was drawn into is kept, so a heart in another view later starts its own run. */
    @Test
    public void theViewIsLearnedWhenTheHeartIsDrawn() {
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        Settings.GROW_LIKE_HEART.save(true);
        tapAfter(0, heart);
        heart.setCallback(post);
        LikeHeartGrowth.begin(heart, canvas());
        LikeHeartGrowth.end();
        other.setCallback(otherPost);
        tapAfter(600, other);
        assertEquals(1.0f, settled(other), 0f);
    }

    /** Two hearts start with one double tap, like the like button's own: the one in another view stays out of the run. */
    @Test
    public void aHeartPlayingWithTheTapInAnotherViewIsLeftOut() {
        heart.setCallback(post);
        other.setCallback(otherPost);
        tapAfter(0, heart);
        tapAfter(100, other);
        assertEquals(1.0f, settled(other), 0f);
        tapAfter(600, heart);
        assertEquals(1.25f, settled(heart), 0f);
    }

    /** A new heart for the same post inside one double tap takes the size over without a step. */
    @Test
    public void aNewHeartInsideTheSameTapTakesTheSizeOver() {
        Drawable fresh = new ColorDrawable(0xff0000ff);
        tapAfter(0, heart);
        tapAfter(600, heart);
        tapAfter(100, fresh);
        assertEquals(1.25f, settled(fresh), 0f);
        assertEquals(1.0f, settled(heart), 0f);
        tapAfter(600, fresh);
        assertEquals(1.5f, settled(fresh), 0f);
    }

    @Test
    public void theSameTapPlayedTwiceCountsOnce() {
        tapAfter(0, heart);
        tapAfter(100, heart);
        assertEquals(1.0f, settled(heart), 0f);
        tapAfter(600, heart);
        assertEquals(1.25f, settled(heart), 0f);
        tapAfter(150, heart);
        assertEquals(1.25f, settled(heart), 0f);
    }

    /** The size springs past its step a little and settles on it. */
    @Test
    public void theSizeOvershootsALittleAndSettles() {
        tapAfter(0, heart);
        tapAfter(600, heart);
        long tapped = now.get();
        float most = 0f;
        for (long age = 0; age <= 1_000L; age += 10L) {
            most = Math.max(most, LikeHeartGrowth.scaleAt(heart, tapped + age));
        }
        assertEquals("starts from the old size", 1.0f, LikeHeartGrowth.scaleAt(heart, tapped), 0f);
        assertTrue("overshoots " + most, most > 1.25f);
        assertTrue("but only a little: " + most, most < 1.32f);
        assertEquals(1.25f, LikeHeartGrowth.scaleAt(heart, tapped + 1_300L), 0f);
    }

    @Test
    public void aNewStepSpringsFromWhereTheLastOneWas() {
        tapAfter(0, heart);
        tapAfter(600, heart);
        tapAfter(100 + LikeHeartGrowth.SAME_TAP_MS, heart);
        float justAfter = LikeHeartGrowth.scaleAt(heart, now.get());
        assertTrue("starts from the size it was at, " + justAfter, justAfter > 1.1f && justAfter < 1.35f);
        assertEquals(1.5f, settled(heart), 0f);
    }

    @Test
    public void drawScalesTheCanvasAboutTheMiddleOfTheHeartAndGivesItBack() {
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        Settings.GROW_LIKE_HEART.save(true);
        tapAfter(0, heart);
        tapAfter(600, heart);
        now.addAndGet(2_000L);
        Recording canvas = canvas();
        List<String> about = List.of("save", "translate 200.0 300.0", "scale 1.25 1.25", "translate -200.0 -300.0");

        LikeHeartGrowth.begin(heart, canvas);
        assertEquals(about, canvas.calls);
        LikeHeartGrowth.end();
        assertEquals("given back", about.size() + 1, canvas.calls.size());
        assertEquals("restore", canvas.calls.get(about.size()));

        LikeHeartGrowth.end();
        assertEquals("a second end gives nothing back", about.size() + 1, canvas.calls.size());
    }

    @Test
    public void drawLeavesAHeartAtNormalSizeAndAnotherHeartAlone() {
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        Settings.GROW_LIKE_HEART.save(true);
        Recording canvas = canvas();
        tapAfter(0, heart);
        LikeHeartGrowth.begin(heart, canvas);
        LikeHeartGrowth.end();
        tapAfter(600, heart);
        now.addAndGet(2_000L);
        LikeHeartGrowth.begin(other, canvas);
        LikeHeartGrowth.end();
        assertTrue(canvas.calls.toString(), canvas.calls.isEmpty());
    }

    @Test
    public void switchedOffOrPausedDrawsAsBefore() {
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        Settings.GROW_LIKE_HEART.save(true);
        tapAfter(0, heart);
        tapAfter(600, heart);
        now.addAndGet(2_000L);
        Recording canvas = canvas();

        Settings.GROW_LIKE_HEART.save(false);
        LikeHeartGrowth.begin(heart, canvas);
        LikeHeartGrowth.end();
        assertTrue("off", canvas.calls.isEmpty());

        Settings.GROW_LIKE_HEART.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        LikeHeartGrowth.begin(heart, canvas);
        LikeHeartGrowth.end();
        assertTrue("paused", canvas.calls.isEmpty());
    }

    @Test
    public void unpatchedNothingIsAHeartSoNothingCountsOrBreaks() {
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        Settings.GROW_LIKE_HEART.save(true);
        LikeHeartGrowth.played(heart);
        LikeHeartGrowth.played(null);
        now.addAndGet(600L);
        LikeHeartGrowth.played(heart);
        assertEquals(1.0f, settled(heart), 0f);
    }

    @Test
    public void counts() {
        tapAfter(0, heart);
        tapAfter(600, heart);
        tapAfter(600, heart);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(LikeHeartGrowth.COUNT_GREW + " 2"));
    }

    private static Recording canvas() {
        return new Recording();
    }

    /** A canvas that writes down the save, translate, scale and restore calls the heart's draw makes. */
    private static final class Recording extends Canvas {
        final List<String> calls = new ArrayList<>();

        @Override public int save() {
            calls.add("save");
            return 1;
        }

        @Override public void translate(float dx, float dy) {
            calls.add("translate " + dx + " " + dy);
        }

        @Override public void scale(float sx, float sy) {
            calls.add("scale " + sx + " " + sy);
        }

        @Override public void restore() {
            calls.add("restore");
        }
    }

    /** The view a heart draws into. */
    private static final class Host implements Drawable.Callback {
        @Override public void invalidateDrawable(Drawable who) {
        }

        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
        }

        @Override public void unscheduleDrawable(Drawable who, Runnable what) {
        }
    }
}
