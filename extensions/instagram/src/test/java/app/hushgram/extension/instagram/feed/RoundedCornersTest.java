/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What Rounded post corners does with a feed post's media frame: on, the frame gets a rounded outline
 * of the chosen size and clips to it, once; off, paused or before the settings are ready it stays as
 * Instagram made it, and a frame rounded before goes back to Instagram's own outline provider and
 * clipping the next time a holder is made.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class RoundedCornersTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Frame frame;
    private final Object holder = new Object();
    private final Object otherHolder = new Object();

    /** Instagram's own outline provider, which a stock frame keeps. */
    private static final ViewOutlineProvider STOCK = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline outline) {
            outline.setRect(0, 0, view.getWidth(), view.getHeight());
        }
    };

    /** A frame that counts how often it is given an outline provider. */
    private static final class Frame extends FrameLayout {
        int providerCalls;

        Frame(Activity activity) {
            super(activity);
        }

        @Override public void setOutlineProvider(ViewOutlineProvider provider) {
            providerCalls++;
            super.setOutlineProvider(provider);
        }
    }

    @Before public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        frame = new Frame(controller.get());
        frame.setOutlineProvider(STOCK);
        frame.providerCalls = 0;
        frame.setClipToOutline(false);
        controller.get().setContentView(frame);
        frame.layout(0, 0, 400, 300);
        RoundedCorners.forgetForTests();
        RoundedCorners.frames = holderKept -> holderKept == holder ? frame : null;
        reset();
    }

    @After public void close() {
        if (controller != null) controller.close();
        RoundedCorners.forgetForTests();
        reset();
    }

    private static void reset() {
        Settings.ROUNDED_POST_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private float density() {
        return frame.getResources().getDisplayMetrics().density;
    }

    private float radiusOf(View view) {
        Outline outline = new Outline();
        view.getOutlineProvider().getOutline(view, outline);
        assertFalse("an outline", outline.isEmpty());
        return outline.getRadius();
    }

    private void assertStock() {
        assertSame("Instagram's outline provider", STOCK, frame.getOutlineProvider());
        assertFalse("not clipping", frame.getClipToOutline());
        assertEquals("nothing set on it", 0, frame.providerCalls);
        assertEquals(0, RoundedCorners.roundedForTests());
    }

    @Test
    public void itStartsOffAndLeavesTheFrameAsInstagramMadeIt() {
        RoundedCorners.made(holder);
        assertStock();
        assertTrue("nothing was counted", HookStatus.report().isEmpty());
    }

    @Test
    public void onTheFrameGetsARoundedOutlineAndClipsToIt() {
        Settings.ROUNDED_POST_CORNERS.save(true);
        RoundedCorners.made(holder);
        assertTrue(frame.getClipToOutline());
        assertNotSame(STOCK, frame.getOutlineProvider());
        assertEquals(CornerSize.SMALL.dp * density(), radiusOf(frame), 0.01f);
        assertEquals(1, RoundedCorners.roundedForTests());
    }

    @Test
    public void theRadiusFollowsTheChoice() {
        Settings.ROUNDED_POST_CORNERS.save(true);
        Settings.ROUNDED_CORNER_SIZE.save(CornerSize.LARGE);
        RoundedCorners.made(holder);
        assertEquals(CornerSize.LARGE.dp * density(), radiusOf(frame), 0.01f);

        Settings.ROUNDED_CORNER_SIZE.save(CornerSize.SMALL);
        RoundedCorners.made(holder);
        assertEquals("a new choice reaches a frame made again", CornerSize.SMALL.dp * density(), radiusOf(frame), 0.01f);
        assertEquals(1, RoundedCorners.roundedForTests());
    }

    @Test
    public void aFrameAlreadyRoundedToTheChoiceIsNotTouchedTwice() {
        Settings.ROUNDED_POST_CORNERS.save(true);
        RoundedCorners.made(holder);
        ViewOutlineProvider ours = frame.getOutlineProvider();
        assertEquals(1, frame.providerCalls);
        RoundedCorners.made(holder);
        RoundedCorners.made(holder);
        assertEquals(1, frame.providerCalls);
        assertSame(ours, frame.getOutlineProvider());
    }

    @Test
    public void turnedOffTheNextHolderPutsInstagramsOutlineAndClippingBack() {
        frame.setClipToOutline(true);
        frame.providerCalls = 0;
        Settings.ROUNDED_POST_CORNERS.save(true);
        RoundedCorners.made(holder);
        assertNotSame(STOCK, frame.getOutlineProvider());

        Settings.ROUNDED_POST_CORNERS.save(false);
        RoundedCorners.made(otherHolder);
        assertSame(STOCK, frame.getOutlineProvider());
        assertTrue("Instagram's own clipping is back, not simply off", frame.getClipToOutline());
        assertEquals(0, RoundedCorners.roundedForTests());

        RoundedCorners.made(otherHolder);
        assertEquals("nothing left to put back", 2, frame.providerCalls);
    }

    @Test
    public void aFrameInstagramDidNotClipGoesBackUnclipped() {
        Settings.ROUNDED_POST_CORNERS.save(true);
        RoundedCorners.made(holder);
        assertTrue(frame.getClipToOutline());
        Settings.ROUNDED_POST_CORNERS.save(false);
        RoundedCorners.made(otherHolder);
        assertSame(STOCK, frame.getOutlineProvider());
        assertFalse(frame.getClipToOutline());
    }

    @Test
    public void pausedLeavesAFrameStockAndBringsARoundedOneBack() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Settings.ROUNDED_POST_CORNERS.save(true);
        RoundedCorners.made(holder);
        assertStock();
        assertTrue("the choice is kept while paused", Settings.ROUNDED_POST_CORNERS.savedValue());
        assertTrue("nothing was counted", HookStatus.report().isEmpty());
        PauseForTests.resume();

        RoundedCorners.made(holder);
        assertTrue(frame.getClipToOutline());
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        RoundedCorners.made(otherHolder);
        assertSame(STOCK, frame.getOutlineProvider());
        assertFalse(frame.getClipToOutline());
    }

    @Test
    public void beforeTheSettingsAreReadyTheFrameStaysStock() {
        Settings.ROUNDED_POST_CORNERS.save(true);
        SettingsContextRule.withoutContext(() -> RoundedCorners.made(holder));
        assertStock();
        assertTrue("nothing was counted", HookStatus.report().isEmpty());
    }

    @Test
    public void aHolderWithoutAFrameOrAReadThatThrowsLeavesThingsAlone() {
        Settings.ROUNDED_POST_CORNERS.save(true);
        RoundedCorners.made(otherHolder);
        assertStock();
        RoundedCorners.frames = ignored -> new Object();
        RoundedCorners.made(holder);
        assertStock();
        RoundedCorners.frames = ignored -> {
            throw new IllegalStateException("no frame");
        };
        RoundedCorners.made(holder);
        assertStock();
        assertFalse("the failure was reported", HookStatus.report().isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(FamilyNames.ROUNDED_POST_CORNERS));
    }

    @Test
    public void theOutlineFollowsTheFramesSize() {
        Settings.ROUNDED_POST_CORNERS.save(true);
        RoundedCorners.made(holder);
        frame.layout(0, 0, 0, 0);
        Outline empty = new Outline();
        frame.getOutlineProvider().getOutline(frame, empty);
        assertTrue("no size, no outline", empty.isEmpty());
        frame.layout(0, 0, 200, 150);
        Outline outline = new Outline();
        frame.getOutlineProvider().getOutline(frame, outline);
        assertFalse(outline.isEmpty());
        assertEquals(CornerSize.SMALL.dp * density(), outline.getRadius(), 0.01f);
    }
}
