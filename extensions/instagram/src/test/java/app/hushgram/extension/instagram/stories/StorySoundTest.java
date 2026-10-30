/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

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

/** What the story viewer's sound hook answers: off, paused and not ready are Instagram's own, and the phone's ringer and volume count. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StorySoundTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private AudioManager audio;

    @Before
    public void clean() {
        Settings.START_STORIES_WITH_SOUND.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        StorySound.resetForTests();
        audio =(AudioManager) RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0);
    }

    @After
    public void restore() {
        Settings.START_STORIES_WITH_SOUND.resetToDefault();
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

    /** Off to start: Instagram decides, and only the call itself is counted. */
    @Test
    public void itStartsOffAndLeavesInstagramsOwnSoundAlone() {
        assertFalse(Settings.START_STORIES_WITH_SOUND.get());
        assertEquals(0, StorySound.startWithSound(new Object()));
        String report = report();
        assertTrue(report, report.contains(FamilyNames.STORY_SOUND + ": invoked 1"));
        assertFalse(report, report.contains(StorySound.STARTED));
    }

    /** On, with the ringer on and the volume up, it says yes and counts each start. */
    @Test
    public void onWithTheRingerOnAndTheVolumeUpItStartsWithSound() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        assertEquals(1, StorySound.startWithSound(new Object()));
        assertEquals(1, StorySound.startWithSound(new Object()));
        assertTrue(report(), report().contains(StorySound.STARTED + " 2"));
    }

    /** A silent or vibrating phone, or a media volume of zero, keeps Instagram's own choice. */
    @Test
    public void aSilentPhoneOrNoVolumeIsInstagramsOwnChoice() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        assertEquals("silent", 0, StorySound.startWithSound(new Object()));
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        assertEquals("vibrate", 0, StorySound.startWithSound(new Object()));
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        assertEquals("volume down", 0, StorySound.startWithSound(new Object()));
        assertFalse(report(), report().contains(StorySound.STARTED));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0);
        assertEquals("back up", 1, StorySound.startWithSound(new Object()));
    }

    /** Paused, a saved switch answers Instagram's own and counts nothing. */
    @Test
    public void pausedHushGramLeavesTheSoundAlone() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, StorySound.startWithSound(new Object()));
        assertTrue("the saved choice is kept", Settings.START_STORIES_WITH_SOUND.savedValue());
        assertFalse(report(), report().contains(StorySound.STARTED));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(1, StorySound.startWithSound(new Object()));
    }

    /** Before the settings are ready the hook can't read the switch and takes Instagram's own sound. */
    @Test
    public void beforeTheSettingsAreReadyItIsInstagramsOwnSound() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals(0, StorySound.startWithSound(new Object())));
    }

    /** No viewer, no answer. */
    @Test
    public void withoutAViewerItSaysNo() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        assertEquals(0, StorySound.startWithSound(null));
        StorySound.opened(null, 0);
        assertEquals(0, StorySound.pausing(null));
        assertEquals(0, StorySound.paused(null, 1));
        assertEquals(0, StorySound.resuming(null));
    }

    /**
     * #342: a story that turned the account's sound on from off puts it back to off as its viewer
     * pauses, so the feed it returns to stays muted, and turns it on again if the viewer resumes.
     */
    @Test
    public void aViewerThatTurnedTheSoundOnPutsItBackAsItIsLeft() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        Object viewer = new Object();
        assertEquals(1, StorySound.startWithSound(viewer));
        StorySound.opened(viewer, 0);

        assertEquals("the pause reads the sound", 1, StorySound.pausing(viewer));
        assertEquals("still on, so back to off", 1, StorySound.paused(viewer, 1));
        assertEquals("already put back", 0, StorySound.pausing(viewer));
        assertTrue(report(), report().contains(StorySound.RESTORED + " 1"));

        assertEquals("back in the story, sound on again", 1, StorySound.resuming(viewer));
        assertEquals("a second pause puts it back again", 1, StorySound.pausing(viewer));
        assertEquals(1, StorySound.paused(viewer, 1));
    }

    /** A sound that was already on when the story opened is the user's own, and leaving the story keeps it. */
    @Test
    public void aSoundAlreadyOnIsLeftOn() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        Object viewer = new Object();
        StorySound.opened(viewer, 1);
        assertEquals(0, StorySound.pausing(viewer));
        assertEquals(0, StorySound.paused(viewer, 1));
        assertEquals(0, StorySound.resuming(viewer));
    }

    /** A story muted by hand stays muted: the pause forgets the viewer and the resume doesn't turn it back on. */
    @Test
    public void aStoryMutedByHandStaysMuted() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        Object viewer = new Object();
        StorySound.opened(viewer, 0);
        assertEquals(1, StorySound.pausing(viewer));
        assertEquals("already off", 0, StorySound.paused(viewer, 0));
        assertEquals(0, StorySound.resuming(viewer));
        assertEquals(0, StorySound.pausing(viewer));
    }

    /** With the switch off, HushGram paused or the phone silent by the time the story resumes, the sound stays off. */
    @Test
    public void aResumeWithoutTheSwitchOrTheRingerKeepsTheSoundOff() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        Object switchedOff = new Object();
        StorySound.opened(switchedOff, 0);
        assertEquals(1, StorySound.paused(switchedOff, 1));
        Settings.START_STORIES_WITH_SOUND.save(false);
        assertEquals(0, StorySound.resuming(switchedOff));
        assertEquals("forgotten", 0, StorySound.pausing(switchedOff));

        Settings.START_STORIES_WITH_SOUND.save(true);
        Object silenced = new Object();
        StorySound.opened(silenced, 0);
        assertEquals(1, StorySound.paused(silenced, 1));
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        assertEquals(0, StorySound.resuming(silenced));

        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Object paused = new Object();
        StorySound.opened(paused, 0);
        assertEquals(1, StorySound.paused(paused, 1));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, StorySound.resuming(paused));
    }

    /** Each viewer keeps its own record: one left behind doesn't change what another puts back. */
    @Test
    public void viewersAreTrackedOneByOne() {
        Settings.START_STORIES_WITH_SOUND.save(true);
        Object first = new Object();
        Object second = new Object();
        StorySound.opened(first, 0);
        StorySound.opened(second, 1);
        assertEquals(1, StorySound.pausing(first));
        assertEquals(0, StorySound.pausing(second));
    }
}
