/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the story swipe-up hook answers: only the switch on stops the swipe, everything else keeps Instagram's. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StorySwipeReplyTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void clean() {
        Settings.STOP_STORY_SWIPE_REPLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.STOP_STORY_SWIPE_REPLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static String report() {
        List<String> lines = HookStatus.report();
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(line).append('\n');
        return all.toString();
    }

    /** Off to start: the swipe opens the reply box as Instagram has it, and the call is counted. */
    @Test
    public void offToStartKeepsTheSwipe() {
        assertFalse(Settings.STOP_STORY_SWIPE_REPLY.get());
        assertTrue(StorySwipeReply.keepsSwipe());
        String report = report();
        assertTrue(report, report.contains(FamilyNames.STORY_SWIPE_REPLY + ": invoked 1"));
    }

    /** On, the swipe is stopped every time. */
    @Test
    public void onStopsTheSwipe() {
        Settings.STOP_STORY_SWIPE_REPLY.save(true);
        assertFalse(StorySwipeReply.keepsSwipe());
        assertFalse(StorySwipeReply.keepsSwipe());
        Settings.STOP_STORY_SWIPE_REPLY.save(false);
        assertTrue("a change applies at the next swipe", StorySwipeReply.keepsSwipe());
    }

    /** Paused, a saved switch keeps the swipe until HushGram is back on. */
    @Test
    public void pausedKeepsTheSwipe() {
        Settings.STOP_STORY_SWIPE_REPLY.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(StorySwipeReply.keepsSwipe());
        assertTrue("the saved choice is kept", Settings.STOP_STORY_SWIPE_REPLY.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertFalse(StorySwipeReply.keepsSwipe());
    }

    /** Before the settings are ready the switch can't be read, so the swipe stays Instagram's. */
    @Test
    public void beforeTheSettingsAreReadyItKeepsTheSwipe() {
        Settings.STOP_STORY_SWIPE_REPLY.save(true);
        SettingsContextRule.withoutContext(() -> assertTrue(StorySwipeReply.keepsSwipe()));
    }

    /** A switch that throws keeps the swipe and says the hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheSwipeAndIsReported() {
        assertTrue(StorySwipeReply.keepsSwipe(THROWS));
        String missing = HookStatus.missing(FamilyNames.STORY_SWIPE_REPLY).toString();
        assertTrue(missing, missing.contains("'" + StorySwipeReply.SWIPE + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
