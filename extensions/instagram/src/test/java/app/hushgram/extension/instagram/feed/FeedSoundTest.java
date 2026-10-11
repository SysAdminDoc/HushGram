/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

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

/** What the feed video start hook answers: off, paused and not ready are Instagram's own, the phone's ringer and volume count, and each controller gets one turn. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedSoundTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private AudioManager audio;

    @Before
    public void clean() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedSound.resetForTests();
        audio = (AudioManager) RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE);
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0);
    }

    @After
    public void restore() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedSound.resetForTests();
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
        assertFalse(Settings.START_FEED_VIDEOS_WITH_SOUND.get());
        assertEquals(0, FeedSound.startWithSound(new Object()));
        String report = report();
        assertTrue(report, report.contains(FamilyNames.FEED_SOUND + ": invoked 1"));
        assertFalse(report, report.contains(FeedSound.STARTED));
    }

    /** On, with the ringer on and the volume up, the first video of a controller gets a yes and it is counted. */
    @Test
    public void onWithTheRingerOnAndTheVolumeUpTheFirstVideoStartsWithSound() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        assertEquals(1, FeedSound.startWithSound(new Object()));
        assertTrue(report(), report().contains(FeedSound.STARTED + " 1"));
    }

    /** One controller gets one turn, so a video muted with the speaker isn't switched back on by the next one. */
    @Test
    public void aControllerGetsOneTurnAndTheNextFeedGetsItsOwn() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        Object feed = new Object();
        assertEquals("first video", 1, FeedSound.startWithSound(feed));
        assertEquals("second video", 0, FeedSound.startWithSound(feed));
        assertEquals("third video", 0, FeedSound.startWithSound(feed));
        assertEquals("a new feed", 1, FeedSound.startWithSound(new Object()));
        assertTrue(report(), report().contains(FeedSound.STARTED + " 2"));
    }

    /** A silent or vibrating phone, or a media volume of zero, keeps Instagram's own choice and keeps the turn. */
    @Test
    public void aSilentPhoneOrNoVolumeIsInstagramsOwnChoice() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        Object feed = new Object();
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        assertEquals("silent", 0, FeedSound.startWithSound(feed));
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        assertEquals("vibrate", 0, FeedSound.startWithSound(feed));
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        assertEquals("volume down", 0, FeedSound.startWithSound(feed));
        assertFalse(report(), report().contains(FeedSound.STARTED));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0);
        assertEquals("back up, the turn was kept", 1, FeedSound.startWithSound(feed));
    }

    /** Paused, a saved switch answers Instagram's own and counts nothing. */
    @Test
    public void pausedHushGramLeavesTheSoundAlone() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Object feed = new Object();
        assertEquals(0, FeedSound.startWithSound(feed));
        assertTrue("the saved choice is kept", Settings.START_FEED_VIDEOS_WITH_SOUND.savedValue());
        assertFalse(report(), report().contains(FeedSound.STARTED));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(1, FeedSound.startWithSound(feed));
    }

    /** Before the settings are ready the hook can't read the switch and takes Instagram's own sound. */
    @Test
    public void beforeTheSettingsAreReadyItIsInstagramsOwnSound() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals(0, FeedSound.startWithSound(new Object())));
    }

    /** No controller, no answer. */
    @Test
    public void withoutAControllerItSaysNo() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        assertEquals(0, FeedSound.startWithSound(null));
    }
}
