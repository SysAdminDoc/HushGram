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

/** What the story side tap hook answers: only the switch on keeps the story, everything else lets the tap move. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryTapsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void clean() {
        Settings.STOP_STORY_SIDE_TAPS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.STOP_STORY_SIDE_TAPS.resetToDefault();
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

    /** Off to start: the tap moves the way Instagram has it, and the call is counted. */
    @Test
    public void offToStartLetsTheTapMove() {
        assertFalse(Settings.STOP_STORY_SIDE_TAPS.get());
        assertTrue(StoryTaps.keepsTap());
        String report = report();
        assertTrue(report, report.contains(FamilyNames.STORY_SWIPE_REPLY + ": invoked 1"));
    }

    /** On, every tap keeps the story, and a change applies at the next tap. */
    @Test
    public void onKeepsTheStory() {
        Settings.STOP_STORY_SIDE_TAPS.save(true);
        assertFalse(StoryTaps.keepsTap());
        assertFalse(StoryTaps.keepsTap());
        Settings.STOP_STORY_SIDE_TAPS.save(false);
        assertTrue("a change applies at the next tap", StoryTaps.keepsTap());
    }

    /** Paused, a saved switch lets taps move until HushGram is back on. */
    @Test
    public void pausedLetsTheTapMove() {
        Settings.STOP_STORY_SIDE_TAPS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(StoryTaps.keepsTap());
        assertTrue("the saved choice is kept", Settings.STOP_STORY_SIDE_TAPS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertFalse(StoryTaps.keepsTap());
    }

    /** Before the settings are ready the switch can't be read, so the tap stays Instagram's. */
    @Test
    public void beforeTheSettingsAreReadyTheTapMoves() {
        Settings.STOP_STORY_SIDE_TAPS.save(true);
        SettingsContextRule.withoutContext(() -> assertTrue(StoryTaps.keepsTap()));
    }

    /** A switch that throws lets the tap move and says the hook threw. */
    @Test
    public void aThrowingSwitchLetsTheTapMoveAndIsReported() {
        assertTrue(StoryTaps.keepsTap(THROWS));
        String missing = HookStatus.missing(FamilyNames.STORY_SWIPE_REPLY).toString();
        assertTrue(missing, missing.contains("'" + StoryTaps.TAP + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
