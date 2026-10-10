/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
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

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the Reels tap hook answers for each choice, and that off, paused and not ready are Instagram's own. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelTapAndVolumeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void clean() {
        Settings.REEL_TAP_CHOICE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.REEL_TAP_CHOICE.resetToDefault();
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

    /** The choice starts at Instagram's own tap, so a tap pauses as it always has and nothing is counted. */
    @Test
    public void theChoiceStartsAtInstagramsDefault() {
        assertEquals(ReelTapChoice.DEFAULT, Settings.REEL_TAP_CHOICE.get());
        assertFalse(ReelTapAndVolume.muteInsteadOfPause());
        String report = report();
        assertTrue(report, report.contains(FamilyNames.REEL_TAP_AND_VOLUME + ": invoked 1"));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_MUTED));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_PAUSED));
    }

    /** Mute turns the pause into a mute and counts it each time. */
    @Test
    public void muteSendsTheTapToTheAudioToggleAndCountsIt() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.MUTE);
        assertTrue(ReelTapAndVolume.muteInsteadOfPause());
        assertTrue(ReelTapAndVolume.muteInsteadOfPause());
        String report = report();
        assertTrue(report, report.contains(ReelTapAndVolume.TAP_MUTED + " 2"));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_PAUSED));
    }

    /** Pause leaves Instagram's pause alone and counts the taps it left to it. */
    @Test
    public void pauseLeavesTheTapToInstagramsPauseAndCountsIt() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.PAUSE);
        assertFalse(ReelTapAndVolume.muteInsteadOfPause());
        String report = report();
        assertTrue(report, report.contains(ReelTapAndVolume.TAP_PAUSED + " 1"));
        assertFalse(report, report.contains(ReelTapAndVolume.TAP_MUTED));
    }

    /** Paused, a saved Mute answers Instagram's own: the pause happens and nothing is counted. */
    @Test
    public void pausedHushGramIsInstagramsOwnTap() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.MUTE);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelTapAndVolume.muteInsteadOfPause());
        assertEquals("the saved choice is kept", ReelTapChoice.MUTE, Settings.REEL_TAP_CHOICE.savedValue());
        assertFalse(report(), report().contains(ReelTapAndVolume.TAP_MUTED));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(ReelTapAndVolume.muteInsteadOfPause());
    }

    /** Before the settings are ready the hook can't read the choice and takes Instagram's own tap. */
    @Test
    public void beforeTheSettingsAreReadyItIsInstagramsOwnTap() {
        Settings.REEL_TAP_CHOICE.save(ReelTapChoice.MUTE);
        SettingsContextRule.withoutContext(() -> assertFalse(ReelTapAndVolume.muteInsteadOfPause()));
    }
}
