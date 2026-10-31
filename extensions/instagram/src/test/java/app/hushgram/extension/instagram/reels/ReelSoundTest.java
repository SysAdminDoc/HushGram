/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.AudioManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the Reels viewer's sound hook answers (#342): only a viewer opened from Home starts with
 * sound, off and paused are Instagram's own, the phone's ringer and volume count, and the sound
 * goes back to off on the way out unless the reel was muted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelSoundTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Instagram's ClipsViewerSource, whose constant names its build keeps. */
    enum Source { FEED_TIMELINE, FEED_TIMELINE_POST_TAP, CLIPS_TAB, FEED_CONTEXTUAL_PROFILE, DIRECT_THREAD }

    private AudioManager audio;

    @Before
    public void clean() {
        Settings.START_REELS_WITH_SOUND.resetToDefault();
        Settings.START_REELS_MUTED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ReelSound.resetForTests();
        audio = (AudioManager) RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0);
    }

    @After
    public void restore() {
        Settings.START_REELS_WITH_SOUND.resetToDefault();
        Settings.START_REELS_MUTED.resetToDefault();
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

    /** Off to start: the hook says no before anything is read from the viewer, and only the call is counted. */
    @Test
    public void itStartsOffAndReadsNothing() {
        assertFalse(Settings.START_REELS_WITH_SOUND.get());
        assertEquals(0, ReelSound.wanted());
        assertEquals(0, ReelSound.startWithSound(new Object(), Source.FEED_TIMELINE));
        String report = report();
        assertTrue(report, report.contains(FamilyNames.REEL_SOUND + ": invoked 1"));
        assertFalse(report, report.contains(ReelSound.STARTED));
    }

    /** On, a reel opened from a post on Home starts with sound; the Reels tab, profiles and chats keep Instagram's own. */
    @Test
    public void onlyAReelOpenedFromHomeStartsWithSound() {
        Settings.START_REELS_WITH_SOUND.save(true);
        assertEquals(1, ReelSound.wanted());
        assertEquals(1, ReelSound.startWithSound(new Object(), Source.FEED_TIMELINE));
        assertEquals(1, ReelSound.startWithSound(new Object(), Source.FEED_TIMELINE_POST_TAP));
        assertEquals("the Reels tab", 0, ReelSound.startWithSound(new Object(), Source.CLIPS_TAB));
        assertEquals("a profile", 0, ReelSound.startWithSound(new Object(), Source.FEED_CONTEXTUAL_PROFILE));
        assertEquals("a chat", 0, ReelSound.startWithSound(new Object(), Source.DIRECT_THREAD));
        assertEquals("not a source", 0, ReelSound.startWithSound(new Object(), "FEED_TIMELINE"));
        assertEquals("no source", 0, ReelSound.startWithSound(new Object(), null));
        assertTrue(report(), report().contains(ReelSound.STARTED + " 2"));
    }

    /** A silent or vibrating phone, or a media volume of zero, keeps Instagram's own choice. */
    @Test
    public void aSilentPhoneOrNoVolumeIsInstagramsOwnChoice() {
        Settings.START_REELS_WITH_SOUND.save(true);
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        assertEquals("silent", 0, ReelSound.startWithSound(new Object(), Source.FEED_TIMELINE));
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        assertEquals("volume down", 0, ReelSound.startWithSound(new Object(), Source.FEED_TIMELINE));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0);
        assertEquals("back up", 1, ReelSound.startWithSound(new Object(), Source.FEED_TIMELINE));
    }

    /** Paused, or before the settings are ready, a saved switch answers no. */
    @Test
    public void pausedOrNotReadyItSaysNo() {
        Settings.START_REELS_WITH_SOUND.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, ReelSound.wanted());
        assertTrue("the saved choice is kept", Settings.START_REELS_WITH_SOUND.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(1, ReelSound.wanted());
        SettingsContextRule.withoutContext(() -> assertEquals(0, ReelSound.wanted()));
    }

    /** A sound the viewer turned on from off goes back to off at its pause and on again at its resume. */
    @Test
    public void theSoundGoesBackToOffOnTheWayOutAndOnAgainOnTheWayBack() {
        Settings.START_REELS_WITH_SOUND.save(true);
        Object viewer = new Object();
        ReelSound.opened(viewer, 0);
        assertEquals("pausing asks", 1, ReelSound.pausing(viewer));
        assertEquals("still on: set it off", 1, ReelSound.paused(viewer, 1));
        assertEquals("already put back", 0, ReelSound.pausing(viewer));
        assertEquals("resume turns it on", 1, ReelSound.resuming(viewer));
        assertEquals("and the next pause puts it back again", 1, ReelSound.pausing(viewer));
        assertTrue(report(), report().contains(ReelSound.RESTORED + " 1"));
    }

    /** A reel muted by hand stays muted: the pause finds it off and forgets the viewer. */
    @Test
    public void aReelMutedByHandStaysMuted() {
        Settings.START_REELS_WITH_SOUND.save(true);
        Object viewer = new Object();
        ReelSound.opened(viewer, 0);
        assertEquals(1, ReelSound.pausing(viewer));
        assertEquals("muted: leave it", 0, ReelSound.paused(viewer, 0));
        assertEquals(0, ReelSound.pausing(viewer));
        assertEquals(0, ReelSound.resuming(viewer));
    }

    /** A sound that was on already isn't the viewer's to put back. */
    @Test
    public void aSoundThatWasOnAlreadyIsLeftAlone() {
        Settings.START_REELS_WITH_SOUND.save(true);
        Object viewer = new Object();
        ReelSound.opened(viewer, 1);
        assertEquals(0, ReelSound.pausing(viewer));
        assertEquals(0, ReelSound.paused(viewer, 1));
    }

    /** The switch turned off while away: the resume leaves the sound off. */
    @Test
    public void theSwitchTurnedOffWhileAwayLeavesTheSoundOff() {
        Settings.START_REELS_WITH_SOUND.save(true);
        Object viewer = new Object();
        ReelSound.opened(viewer, 0);
        assertEquals(1, ReelSound.paused(viewer, 1));
        Settings.START_REELS_WITH_SOUND.save(false);
        assertEquals(0, ReelSound.resuming(viewer));
        assertEquals(0, ReelSound.pausing(viewer));
    }

    /** No viewer, no answer. */
    @Test
    public void withoutAViewerItSaysNo() {
        Settings.START_REELS_WITH_SOUND.save(true);
        assertEquals(0, ReelSound.startWithSound(null, Source.FEED_TIMELINE));
        ReelSound.opened(null, 0);
        assertEquals(0, ReelSound.pausing(null));
        assertEquals(0, ReelSound.paused(null, 1));
        assertEquals(0, ReelSound.resuming(null));
    }

    /** Start reels muted: any source starts muted, the state to set is off, and nothing is put back. */
    @Test
    public void mutedStartsEveryReelSilentFromAnySource() {
        assertFalse(Settings.START_REELS_MUTED.get());
        Settings.START_REELS_MUTED.save(true);
        assertEquals("muted alone asks the viewer", 1, ReelSound.wanted());
        for (Source source : Source.values()) {
            Object viewer = new Object();
            assertEquals(source.name(), 1, ReelSound.startWithSound(viewer, source));
            assertEquals(source.name() + ": set the state to off", 0, ReelSound.opened(viewer, 1));
            assertEquals("never put back", 0, ReelSound.pausing(viewer));
            assertEquals(0, ReelSound.resuming(viewer));
        }
        assertTrue(report(), report().contains(ReelSound.STARTED_MUTED + " 5"));
    }

    @Test
    public void mutedWinsOverSoundAndTheRingerDoesNotMatter() {
        Settings.START_REELS_WITH_SOUND.save(true);
        Settings.START_REELS_MUTED.save(true);
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        Object viewer = new Object();
        assertEquals(1, ReelSound.startWithSound(viewer, Source.FEED_TIMELINE));
        assertEquals(0, ReelSound.opened(viewer, 0));
        assertEquals(0, ReelSound.pausing(viewer));
    }

    @Test
    public void soundStillSetsOnAndMutedOffAndPausedIsInstagrams() {
        Settings.START_REELS_WITH_SOUND.save(true);
        Object viewer = new Object();
        assertEquals(1, ReelSound.startWithSound(viewer, Source.FEED_TIMELINE));
        assertEquals("sound: set the state to on", 1, ReelSound.opened(viewer, 0));
        assertEquals(1, ReelSound.pausing(viewer));
        Settings.START_REELS_MUTED.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused is Instagram's own", 0, ReelSound.wanted());
        assertEquals(0, ReelSound.startWithSound(new Object(), Source.FEED_TIMELINE));
    }

    /**
     * A reel opened from a link as Instagram starts was set muted just before Instagram, coming to
     * the front for the first time, cleared the sound to its defaults and played it with sound (#342).
     * With Start reels muted on, only a clear of a muted state is skipped.
     */
    @Test
    public void mutedKeepsAMutedStateThroughInstagramsClear() {
        Settings.START_REELS_MUTED.save(true);
        assertEquals("muted, cleared: kept", 0, ReelSound.letsReset(Boolean.FALSE, null));
        assertEquals("muted, turned on: kept", 0, ReelSound.letsReset(Boolean.FALSE, Boolean.TRUE));
        assertEquals("muted, set muted: Instagram's own", 1, ReelSound.letsReset(Boolean.FALSE, Boolean.FALSE));
        assertEquals("on, cleared: Instagram's own", 1, ReelSound.letsReset(Boolean.TRUE, null));
        assertEquals("nothing set yet: Instagram's own", 1, ReelSound.letsReset(null, null));
        assertTrue(report(), report().contains(ReelSound.KEPT_MUTED + " 2"));
    }

    /** Instagram turning the sound on by itself is held back while Start reels muted is on; an off goes through. */
    @Test
    public void mutedHoldsBackInstagramsOwnTurnOn() {
        Settings.START_REELS_MUTED.save(true);
        assertEquals(0, ReelSound.letsSoundOn(1));
        assertEquals("any non-zero is on", 0, ReelSound.letsSoundOn(2));
        assertEquals("off goes through", 1, ReelSound.letsSoundOn(0));
        assertTrue(report(), report().contains(ReelSound.HELD_OFF + " 2"));
    }

    /** Off, Start reels with sound alone, paused and before the settings are read, Instagram changes its sound as it does. */
    @Test
    public void offSoundOnlyPausedAndUnreadyLeaveInstagramsChangesAlone() {
        assertEquals(1, ReelSound.letsReset(Boolean.FALSE, null));
        assertEquals(1, ReelSound.letsSoundOn(1));
        Settings.START_REELS_WITH_SOUND.save(true);
        assertEquals(1, ReelSound.letsReset(Boolean.FALSE, null));
        assertEquals(1, ReelSound.letsSoundOn(1));
        Settings.START_REELS_MUTED.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1, ReelSound.letsReset(Boolean.FALSE, null));
        assertEquals("paused", 1, ReelSound.letsSoundOn(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertEquals("no context", 1, ReelSound.letsReset(Boolean.FALSE, null));
            assertEquals("no context", 1, ReelSound.letsSoundOn(1));
        });
        assertFalse(report(), report().contains(ReelSound.KEPT_MUTED));
        assertFalse(report(), report().contains(ReelSound.HELD_OFF));
    }
}
