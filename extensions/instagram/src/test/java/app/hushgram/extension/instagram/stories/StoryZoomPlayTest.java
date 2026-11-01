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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When a pinch's pause is skipped: switch on, not paused, and the story not held first. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryZoomPlayTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        StoryZoomPlay.reset();
    }

    @After
    public void restore() {
        Settings.KEEP_STORIES_PLAYING_ON_ZOOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        StoryZoomPlay.reset();
    }

    @Test
    public void offTheStoryPausesForAPinchAsInstagramDoes() {
        assertFalse(Settings.KEEP_STORIES_PLAYING_ON_ZOOM.get());
        assertFalse(StoryZoomPlay.keepPlaying());
    }

    @Test
    public void onAPinchOnAPlayingStoryDoesNotPauseIt() {
        Settings.KEEP_STORIES_PLAYING_ON_ZOOM.save(true);
        assertTrue(StoryZoomPlay.keepPlaying());
    }

    @Test
    public void aStoryHeldFirstStaysPausedForTheZoom() {
        Settings.KEEP_STORIES_PLAYING_ON_ZOOM.save(true);
        StoryZoomPlay.paused("long_press");
        assertFalse(StoryZoomPlay.keepPlaying());
        StoryZoomPlay.resumed();
        assertTrue(StoryZoomPlay.keepPlaying());
    }

    @Test
    public void thePinchsOwnPauseIsNotAHold() {
        Settings.KEEP_STORIES_PLAYING_ON_ZOOM.save(true);
        StoryZoomPlay.paused(StoryZoomPlay.PINCH);
        assertTrue(StoryZoomPlay.keepPlaying());
    }

    @Test
    public void pausedHushgramLeavesInstagramsPauseAlone() {
        Settings.KEEP_STORIES_PLAYING_ON_ZOOM.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(StoryZoomPlay.keepPlaying());
    }
}
