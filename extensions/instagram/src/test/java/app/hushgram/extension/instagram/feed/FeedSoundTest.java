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
        Settings.START_FEED_VIDEOS_MUTED.resetToDefault();
        Settings.START_FEED_VIDEOS_ON_SILENT.resetToDefault();
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
        Settings.START_FEED_VIDEOS_MUTED.resetToDefault();
        Settings.START_FEED_VIDEOS_ON_SILENT.resetToDefault();
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

    /**
     * Instagram forgets the account's audio state after a while in the background, writing null (#762). Home's
     * controller is the same object afterwards, and its next video gets a turn again, as after a cold start. A
     * true or false written to the state, a tap on the speaker or the hook's own turn, leaves the turns alone.
     */
    @Test
    public void afterInstagramResetsTheSoundTheSameControllerGetsItsTurnAgain() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        Object home = new Object();
        assertEquals("first video", 1, FeedSound.startWithSound(home));
        FeedSound.audioStateChanging(Boolean.TRUE);
        FeedSound.audioStateChanging(Boolean.FALSE);
        assertEquals("a true or false keeps the turn used", 0, FeedSound.startWithSound(home));
        FeedSound.audioStateChanging(null);
        assertEquals("after the reset", 1, FeedSound.startWithSound(home));
        assertEquals("and then one turn again", 0, FeedSound.startWithSound(home));
        String report = report();
        assertTrue(report, report.contains(FeedSound.STARTED + " 2"));
        assertTrue(report, report.contains(FeedSound.RESET + " 1"));
    }

    /** A reset with the switch off or paused still forgets the turns, and never throws. */
    @Test
    public void aResetWhileOffStillForgetsTheTurns() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        Object home = new Object();
        assertEquals(1, FeedSound.startWithSound(home));
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(false);
        FeedSound.audioStateChanging(null);
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        assertEquals(1, FeedSound.startWithSound(home));
    }

    /** Going back to a feed that already had its turn, after another one, doesn't give it a second one. */
    @Test
    public void goingBackToAFeedAfterAnotherDoesntTurnItsSoundBackOn() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        Object home = new Object();
        Object profilePosts = new Object();
        assertEquals("Home's first video", 1, FeedSound.startWithSound(home));
        assertEquals("a profile's posts", 1, FeedSound.startWithSound(profilePosts));
        assertEquals("back on Home", 0, FeedSound.startWithSound(home));
        assertEquals("back on the profile's posts", 0, FeedSound.startWithSound(profilePosts));
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

    /** Even on silent leaves the ringer out: silent and vibrate start with sound, a media volume of zero still doesn't (#762). */
    @Test
    public void evenOnSilentOnlyTheMediaVolumeCounts() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        Settings.START_FEED_VIDEOS_ON_SILENT.save(true);
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        Object feed = new Object();
        assertEquals("silent with the volume down", 0, FeedSound.startWithSound(feed));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0);
        assertEquals("silent with the volume up", 1, FeedSound.startWithSound(feed));
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        assertEquals("vibrate with the volume up", 1, FeedSound.startWithSound(new Object()));
        assertTrue(report(), report().contains(FeedSound.STARTED + " 2"));
    }

    /**
     * A photo post's song (#762) follows the same switch: off, Start feed videos muted, a silent ringer or a
     * zero volume answer no, Even on silent leaves only the volume, and every song asks again, since the
     * patch asks only while Instagram's audio state is unset and Instagram's own play turns it on.
     */
    @Test
    public void aPhotoPostsSongFollowsTheSwitch() {
        Object music = new Object();
        assertEquals("off to start", 0, FeedSound.startSongWithSound(music));
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        assertEquals(1, FeedSound.startSongWithSound(music));
        assertEquals("no turn to use up", 1, FeedSound.startSongWithSound(music));
        assertEquals("no controller", 0, FeedSound.startSongWithSound(null));
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        assertEquals("silent", 0, FeedSound.startSongWithSound(music));
        Settings.START_FEED_VIDEOS_ON_SILENT.save(true);
        assertEquals("silent, Even on silent", 1, FeedSound.startSongWithSound(music));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        assertEquals("volume down", 0, FeedSound.startSongWithSound(music));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0);
        Settings.START_FEED_VIDEOS_MUTED.save(true);
        assertEquals("Start feed videos muted wins", 0, FeedSound.startSongWithSound(music));
        Settings.START_FEED_VIDEOS_MUTED.save(false);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, FeedSound.startSongWithSound(music));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("not ready", 0, FeedSound.startSongWithSound(new Object())));
        assertTrue(report(), report().contains(FeedSound.SONG_STARTED + " 3"));
        assertFalse("a song isn't a video", report().contains(FeedSound.STARTED + " "));
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

    /** Start feed videos muted starts off: a video the account's sound turned on keeps it. */
    @Test
    public void keepingFeedVideosMutedStartsOff() {
        assertFalse(Settings.START_FEED_VIDEOS_MUTED.get());
        assertEquals(0, FeedSound.keepsMuted());
        assertFalse(report(), report().contains(FeedSound.KEPT_MUTED));
    }

    /** On, every feed video the account's sound turned on starts muted, with no turns, and each is counted (#718). */
    @Test
    public void onEveryFeedVideoStartsMuted() {
        Settings.START_FEED_VIDEOS_MUTED.save(true);
        assertEquals("first video", 1, FeedSound.keepsMuted());
        assertEquals("second video", 1, FeedSound.keepsMuted());
        assertTrue(report(), report().contains(FeedSound.KEPT_MUTED + " 2"));
    }

    /** With both on, muted wins, and the controller's turn is still there once muted goes off. */
    @Test
    public void mutedWinsOverStartingWithSound() {
        Settings.START_FEED_VIDEOS_WITH_SOUND.save(true);
        Settings.START_FEED_VIDEOS_MUTED.save(true);
        Object feed = new Object();
        assertEquals("muted wins", 0, FeedSound.startWithSound(feed));
        assertFalse(report(), report().contains(FeedSound.STARTED));
        Settings.START_FEED_VIDEOS_MUTED.save(false);
        assertEquals("the turn was kept", 1, FeedSound.startWithSound(feed));
    }

    /** Paused or before the settings are ready, a video keeps the sound Instagram gave it. */
    @Test
    public void pausedOrNotReadyFeedVideosKeepTheirSound() {
        Settings.START_FEED_VIDEOS_MUTED.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals("not ready", 0, FeedSound.keepsMuted()));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, FeedSound.keepsMuted());
        assertTrue("the saved choice is kept", Settings.START_FEED_VIDEOS_MUTED.savedValue());
        assertFalse(report(), report().contains(FeedSound.KEPT_MUTED));
    }
}
