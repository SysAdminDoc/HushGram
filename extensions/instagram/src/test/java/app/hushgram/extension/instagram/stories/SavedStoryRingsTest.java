/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

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

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which posts Story rings on saved posts lets ask for their author's story (#790). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SavedStoryRingsTest {
    /** Instagram's post surfaces, by the names its own enum gives them. */
    enum Surface { MAIN_FEED, EXPLORE_FEED, SAVE_FEED, SAVE_HOME, LIKED_FEED, CLIPS_VIEWER }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SAVED_STORY_RINGS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start, so nothing changes until the switch is turned on. */
    @Test
    public void offTheSavedFeedsStayAsInstagramHasThem() {
        assertFalse(Settings.SAVED_STORY_RINGS.get());
        assertFalse(SavedStoryRings.shows(Surface.SAVE_FEED));
        assertFalse(SavedStoryRings.shows(Surface.SAVE_HOME));
    }

    /** On, both saved feeds ask for the ring, and each is counted. */
    @Test
    public void onTheSavedFeedsAskForTheRing() {
        Settings.SAVED_STORY_RINGS.save(true);
        assertTrue(SavedStoryRings.shows(Surface.SAVE_FEED));
        assertTrue(SavedStoryRings.shows(Surface.SAVE_HOME));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(SavedStoryRings.LET_THROUGH));
    }

    /** Every other surface is left to Instagram's own list, Home and Explore included, whatever the switch says. */
    @Test
    public void otherSurfacesAreLeftToInstagram() {
        Settings.SAVED_STORY_RINGS.save(true);
        for (Surface surface : new Surface[] {Surface.MAIN_FEED, Surface.EXPLORE_FEED, Surface.LIKED_FEED, Surface.CLIPS_VIEWER}) {
            assertFalse(surface.name(), SavedStoryRings.shows(surface));
        }
        assertFalse(SavedStoryRings.shows("SAVE_FEED"));
        assertFalse(SavedStoryRings.shows(null));
        assertFalse(SavedStoryRings.shows(new Object()));
    }

    /** A pause leaves the saved feeds as Instagram has them. */
    @Test
    public void pausedTheSavedFeedsStayAsInstagramHasThem() {
        Settings.SAVED_STORY_RINGS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(SavedStoryRings.shows(Surface.SAVE_FEED));
        PauseForTests.resume();
        assertTrue(SavedStoryRings.shows(Surface.SAVE_FEED));
    }

    /** The saved feeds are the two surfaces Instagram names for them, nothing else. */
    @Test
    public void theSavedSurfacesAreInstagramsNames() {
        assertEquals(2, SavedStoryRings.SAVED.size());
        assertTrue(SavedStoryRings.SAVED.contains("SAVE_FEED"));
        assertTrue(SavedStoryRings.SAVED.contains("SAVE_HOME"));
        assertFalse(FamilyNames.SAVED_STORY_RINGS.isEmpty());
    }
}
