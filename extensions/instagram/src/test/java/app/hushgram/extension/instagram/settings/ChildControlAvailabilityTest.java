/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.SwitchPreference;

import java.util.EnumSet;

import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.instagram.stories.StoryTimeMode;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ChildControlAvailabilityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        PauseForTests.resume();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.ONLY_FOLLOWING.resetToDefault();
        Settings.STORY_RING.resetToDefault();
        Settings.STORY_RING_SCALE.resetToDefault();
        Settings.DEFAULT_PLAYBACK_QUALITY.resetToDefault();
        Settings.PLAYBACK_QUALITY.resetToDefault();
        Settings.DOWNLOAD_REELS.resetToDefault();
        Settings.SHOW_STORY_TIME.resetToDefault();
        Settings.STORY_TIME_MODE.resetToDefault();
    }

    @Test public void followingKeepsItsSavedChoiceAcrossParentChanges() {
        Settings.ONLY_FOLLOWING.save(true);
        checkParent(Settings.START_ON_FOLLOWING, Settings.ONLY_FOLLOWING, PatchFamily.FOLLOWING_FEED,
                "Turn on Start Home on Following to use this choice.", "true");
    }

    @Test public void ringSizeKeepsItsSavedChoiceAcrossParentChanges() {
        Settings.STORY_RING_SCALE.save(StoryRingSize.LARGEST);
        checkParent(Settings.STORY_RING, Settings.STORY_RING_SCALE, PatchFamily.STORY_RING,
                "Turn on Story ring size to use this choice.", "LARGEST");
    }

    @Test public void storyTimeKeepsItsSavedChoiceAcrossParentChanges() {
        Settings.STORY_TIME_MODE.save(StoryTimeMode.TIME_LEFT);
        checkParent(Settings.SHOW_STORY_TIME, Settings.STORY_TIME_MODE, PatchFamily.STORY_TIME,
                "Turn on Show a story's exact time to use this choice.", "TIME_LEFT");
    }

    @Test public void playbackKeepsItsSavedChoiceAcrossParentChanges() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        checkParent(Settings.DEFAULT_PLAYBACK_QUALITY, Settings.PLAYBACK_QUALITY, PatchFamily.PLAYBACK_QUALITY,
                "Turn on Default playback quality to use this choice.", "HIGHEST");
    }

    @Test @Config(qualifiers = "es")
    public void unavailableExplanationsUseTheSelectedLanguage() {
        Settings.ONLY_FOLLOWING.save(true);
        Settings.STORY_RING_SCALE.save(StoryRingSize.LARGER);
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.DATA_SAVER);
        checkParent(Settings.START_ON_FOLLOWING, Settings.ONLY_FOLLOWING, PatchFamily.FOLLOWING_FEED,
                "Turn on Start Home on Following to use this choice.", "true");
        checkParent(Settings.STORY_RING, Settings.STORY_RING_SCALE, PatchFamily.STORY_RING,
                "Turn on Story ring size to use this choice.", "LARGER");
        checkParent(Settings.DEFAULT_PLAYBACK_QUALITY, Settings.PLAYBACK_QUALITY, PatchFamily.PLAYBACK_QUALITY,
                "Turn on Default playback quality to use this choice.", "DATA_SAVER");
        assertNotEquals("Turn on Story ring size to use this choice.",
                L10n.t("Turn on Story ring size to use this choice."));
    }

    @Test public void missingFamiliesHaveNoChildControlsAndDownloadsStayAvailableWhilePaused() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        Settings.DOWNLOAD_REELS.save(false);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            assertNull(page.findPreference(Settings.ONLY_FOLLOWING.key));
            assertNull(page.findPreference(Settings.STORY_RING_SCALE.key));
            assertNull(page.findPreference(Settings.PLAYBACK_QUALITY.key));
            assertTrue(page.findPreference(BaseSettings.PAUSED.key).isEnabled());
            assertTrue(page.findPreference(Settings.DOWNLOAD_QUALITY.key).isEnabled());
        }
    }

    private static void checkParent(BooleanSetting parent, Setting<?> child, PatchFamily family,
                                    String explanation, String saved) {
        PatchFamily.inBuildForTests = EnumSet.of(family);
        parent.save(false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference row = page.findPreference(child.key);
            assertNotNull(row);
            assertFalse(row.isEnabled());
            assertEquals(L10n.t(explanation), String.valueOf(row.getSummary()));
            assertEquals(saved, child.savedValue().toString());
            ((SwitchPreference) page.findPreference(parent.key)).setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(row.isEnabled());
            assertNotEquals(L10n.t(explanation), String.valueOf(row.getSummary()));
            assertEquals(saved, child.savedValue().toString());
            if (row instanceof ListPreference) assertEquals(saved, ((ListPreference) row).getValue());
            else assertTrue(((SwitchPreference) row).isChecked());
            ((SwitchPreference) page.findPreference(parent.key)).setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse(row.isEnabled());
            assertEquals(L10n.t(explanation), String.valueOf(row.getSummary()));
            assertEquals(saved, child.savedValue().toString());
        }
    }
}
