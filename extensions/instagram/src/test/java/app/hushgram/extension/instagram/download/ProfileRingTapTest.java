/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A story ring opens the story (#823): the patch passes the profile picture tap's read of
 * Instagram's flag for looking up the account's story first through {@link ProfileRingTap#storyFirst}.
 * Off or paused, Instagram's answer stands; on, the answer is yes, so a tap on a ringed picture
 * opens the story Instagram holds instead of the picture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfileRingTapTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        HookStatus.clear();
    }

    @After public void restore() {
        Settings.RING_TAP_OPENS_STORY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test public void theSwitchStartsOff() {
        assertFalse(Settings.RING_TAP_OPENS_STORY.get());
        assertEquals("hushgram_ring_tap_opens_story", Settings.RING_TAP_OPENS_STORY.key);
    }

    @Test public void offInstagramsAnswerStands() {
        assertFalse("Instagram's no stays no", ProfileRingTap.storyFirst(0));
        assertTrue("Instagram's yes stays yes", ProfileRingTap.storyFirst(1));
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE + ": invoked 2, 0 found, 0 missing"),
                HookStatus.report());
    }

    @Test public void onTheTapLooksForTheStoryFirst() {
        Settings.RING_TAP_OPENS_STORY.save(true);
        assertTrue("Instagram's no becomes a look for the story", ProfileRingTap.storyFirst(0));
        assertTrue(ProfileRingTap.storyFirst(1));
        assertEquals(Collections.singletonList(FamilyNames.PROFILE_PICTURE
                        + ": invoked 2, 0 found, 0 missing. Counted: " + ProfileRingTap.COUNT_STORY_FIRST + " 1"),
                HookStatus.report());
    }

    @Test public void anyNonZeroIsInstagramsYes() {
        assertTrue(ProfileRingTap.storyFirst(-1));
        assertTrue(ProfileRingTap.storyFirst(2));
    }

    @Test public void theSwitchTakesEffectAtTheNextTap() {
        Settings.RING_TAP_OPENS_STORY.save(true);
        assertTrue(ProfileRingTap.storyFirst(0));
        Settings.RING_TAP_OPENS_STORY.save(false);
        assertFalse("off again, the next tap is Instagram's", ProfileRingTap.storyFirst(0));
    }

    @Test public void pausedInstagramsAnswerStands() {
        Settings.RING_TAP_OPENS_STORY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ProfileRingTap.storyFirst(0));
        assertTrue(ProfileRingTap.storyFirst(1));
    }
}
