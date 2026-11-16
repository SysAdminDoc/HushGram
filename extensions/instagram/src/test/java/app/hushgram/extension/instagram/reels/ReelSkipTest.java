/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;

import app.hushgram.extension.instagram.feed.FeedDoubleTapSkip;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Instagram's double tap to skip flags answer through the hooks (#554). */
@RunWith(RobolectricTestRunner.class)
public class ReelSkipTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.DOUBLE_TAP_SKIP_REELS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedDoubleTapSkip.resetForTests();
        Utils.setActivity(null);
        Settings.DOUBLE_TAP_SKIP_REELS.resetToDefault();
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.resetToDefault();
        Settings.REEL_SKIP_ARROWS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** It starts off, and needs no restart: Instagram asks again for the next reel and the next double tap. */
    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        Settings.DOUBLE_TAP_SKIP_REELS.resetToDefault();
        assertFalse(Settings.DOUBLE_TAP_SKIP_REELS.get());
        assertFalse(Settings.DOUBLE_TAP_SKIP_REELS.rebootApp);
    }

    /** On, every reel skips, 10 seconds a double tap, whatever the server said. */
    @Test
    public void onEveryReelSkipsTenSeconds() {
        assertTrue("the server said no", ReelSkip.skip(0));
        assertTrue("the server said yes", ReelSkip.skip(1));
        assertEquals(10f, ReelSkip.seconds(5f), 0f);
        assertEquals(10f, ReelSkip.seconds(0f), 0f);
        assertEquals(10d, ReelSkip.secondsDouble(15d), 0d);
        assertTrue(HookStatus.missing(FamilyNames.REEL_DOUBLE_TAP_SKIP).toString(),
                HookStatus.missing(FamilyNames.REEL_DOUBLE_TAP_SKIP).isEmpty());
    }

    /** #645: Skip length's seconds are what every reel skips, read at each answer, and kept to 5 through 30. */
    @Test
    public void skipLengthSetsTheSeconds() {
        assertEquals(10, (int) Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.defaultValue);
        assertFalse(Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.rebootApp);
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.save(25);
        assertEquals(25f, ReelSkip.seconds(5f), 0f);
        assertEquals(25d, ReelSkip.secondsDouble(15d), 0d);
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.save(5);
        assertEquals("no restart", 5f, ReelSkip.seconds(10f), 0f);
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.save(90);
        assertEquals("a value from an old export is kept in range", 30f, ReelSkip.seconds(10f), 0f);

        Settings.DOUBLE_TAP_SKIP_REELS.save(false);
        assertEquals("off, Instagram's own", 7f, ReelSkip.seconds(7f), 0f);
    }

    /** On, Instagram's strip stays when it leaves a middle for the like, and a third of the reel replaces it otherwise. */
    @Test
    public void onTheStripLeavesAMiddleForTheLike() {
        assertEquals(0.25f, ReelSkip.edge(0.25f), 0f);
        assertEquals(0.25d, ReelSkip.edgeDouble(0.25d), 0d);
        for (float unusable : new float[] {0f, -1f, 0.5f, 0.9f, 1f, Float.NaN}) {
            assertEquals("float " + unusable, 1f / 3f, ReelSkip.edge(unusable), 0f);
            assertEquals("double " + unusable, 1f / 3f, ReelSkip.edgeDouble(unusable), 0d);
        }
    }

    /** Off, paused or before the settings are read, every answer stays Instagram's. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswers() {
        Settings.DOUBLE_TAP_SKIP_REELS.save(false);
        assertInstagrams("off");
        Settings.DOUBLE_TAP_SKIP_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertInstagrams("paused");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertInstagrams("no context"));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertInstagrams("pause undecided"));

        assertTrue("back on", ReelSkip.skip(0));
        assertEquals("back on", 10f, ReelSkip.seconds(5f), 0f);
    }

    /** The way a skip goes is Instagram's enum, which keeps its constants' names. */
    private enum Way { FORWARD, BACKWARD, NONE }

    /** #691: after a skip the arrows flash over the reel on the side it went, with Skip length's seconds. */
    @Test
    public void aSkipFlashesTheArrowsOnItsSide() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        FrameLayout content = activity.findViewById(android.R.id.content);
        assertTrue("on to start", Settings.REEL_SKIP_ARROWS.get());
        assertEquals(Settings.DOUBLE_TAP_SKIP_REELS, Settings.REEL_SKIP_ARROWS.getParentSettings().get(0));
        assertFalse(Settings.REEL_SKIP_ARROWS.rebootApp);

        ReelSkip.skipped(Way.BACKWARD);
        FeedDoubleTapSkip.Flash back = FeedDoubleTapSkip.flashForTests();
        assertNotNull("a skip back shows arrows", back);
        assertEquals("on the left", -1, back.side);
        assertEquals(10, back.seconds);
        assertSame("over the reel", content, back.getParent());

        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.save(25);
        ReelSkip.skipped(Way.FORWARD);
        FeedDoubleTapSkip.Flash ahead = FeedDoubleTapSkip.flashForTests();
        assertNotNull("a skip ahead shows arrows", ahead);
        assertEquals("on the right", 1, ahead.side);
        assertEquals("Skip length's seconds", 25, ahead.seconds);
        assertNull("the last arrows go", back.getParent());
        assertTrue(HookStatus.missing(FamilyNames.REEL_DOUBLE_TAP_SKIP).toString(),
                HookStatus.missing(FamilyNames.REEL_DOUBLE_TAP_SKIP).isEmpty());
    }

    /**
     * #554: quick skips the same way add up under the arrows, 10, 20, 30. A skip the other way, or
     * one after the arrows went, starts again from Skip length.
     */
    @Test
    public void quickSkipsTheSameWayAddUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);

        ReelSkip.skipped(Way.FORWARD);
        ReelSkip.skipped(Way.FORWARD);
        assertEquals("two skips ahead", 20, FeedDoubleTapSkip.flashForTests().seconds);
        ReelSkip.skipped(Way.FORWARD);
        assertEquals("three skips ahead", 30, FeedDoubleTapSkip.flashForTests().seconds);
        assertEquals("one set of arrows", 1, countFlashes(activity.findViewById(android.R.id.content)));

        ReelSkip.skipped(Way.BACKWARD);
        FeedDoubleTapSkip.Flash back = FeedDoubleTapSkip.flashForTests();
        assertEquals("the other way starts again", -1, back.side);
        assertEquals("the other way starts again", 10, back.seconds);

        // Well past the arrows' flash, whose length is the feed package's own.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertNull("the arrows go", FeedDoubleTapSkip.flashForTests());
        ReelSkip.skipped(Way.BACKWARD);
        assertEquals("after they went it starts again", 10, FeedDoubleTapSkip.flashForTests().seconds);
    }

    private static int countFlashes(FrameLayout content) {
        int count = 0;
        for (int child = 0; child < content.getChildCount(); child++) {
            if (content.getChildAt(child) instanceof FeedDoubleTapSkip.Flash) count++;
        }
        return count;
    }

    /** No arrows with them turned off, the switch off, paused, a way it doesn't know or no screen, and never a throw. */
    @Test
    public void noArrowsWhenTheyAreOffOrTheWayIsUnknown() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);

        Settings.REEL_SKIP_ARROWS.save(false);
        ReelSkip.skipped(Way.BACKWARD);
        assertNull("arrows off", FeedDoubleTapSkip.flashForTests());
        Settings.REEL_SKIP_ARROWS.save(true);

        Settings.DOUBLE_TAP_SKIP_REELS.save(false);
        ReelSkip.skipped(Way.BACKWARD);
        assertNull("switch off", FeedDoubleTapSkip.flashForTests());
        Settings.DOUBLE_TAP_SKIP_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelSkip.skipped(Way.BACKWARD);
        assertNull("paused", FeedDoubleTapSkip.flashForTests());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        ReelSkip.skipped(Way.NONE);
        ReelSkip.skipped("BACKWARD");
        ReelSkip.skipped(null);
        assertNull("a way it doesn't know", FeedDoubleTapSkip.flashForTests());

        Utils.setActivity(null);
        ReelSkip.skipped(Way.FORWARD);
        assertNull("no screen", FeedDoubleTapSkip.flashForTests());
        assertEquals(-1, ReelSkip.side(Way.BACKWARD));
        assertEquals(1, ReelSkip.side(Way.FORWARD));
        assertEquals(0, ReelSkip.side(Way.NONE));
    }

    private static void assertInstagrams(String when) {
        assertFalse(when, ReelSkip.skip(0));
        assertTrue(when + ", Instagram's own yes", ReelSkip.skip(1));
        assertEquals(when, 5f, ReelSkip.seconds(5f), 0f);
        assertEquals(when, 5d, ReelSkip.secondsDouble(5d), 0d);
        assertEquals(when, 0.9f, ReelSkip.edge(0.9f), 0f);
        assertEquals(when, 0.9d, ReelSkip.edgeDouble(0.9d), 0d);
    }
}
