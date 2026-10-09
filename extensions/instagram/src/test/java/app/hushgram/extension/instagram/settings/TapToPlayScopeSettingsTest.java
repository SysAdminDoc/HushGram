/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.media.TapToPlayScope;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.PauseForTests;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * With Tap to play in the build, its choice of where sits right below its switch (#39): it offers
 * Everywhere first, says what each choice holds, reaches the setting the way the list's dialog
 * sends a pick, asks for the switch while it's off, and travels with an exported configuration.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class TapToPlayScopeSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.TAP_TO_PLAY.resetToDefault();
        Settings.TAP_TO_PLAY_SCOPE.resetToDefault();
    }

    @Test
    public void theChoiceSitsBelowTheSwitchAndSaysWhatItHolds() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.TAP_TO_PLAY);
        Settings.TAP_TO_PLAY.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference found = page.findPreference(Settings.TAP_TO_PLAY_SCOPE.key);
            assertNotNull("no choice of where with Tap to play in the build", found);
            assertTrue(found instanceof HushgramPreferenceFragment.TapToPlayScopeRow);
            HushgramPreferenceFragment.TapToPlayScopeRow row = (HushgramPreferenceFragment.TapToPlayScopeRow) found;
            assertEquals("Where videos wait", String.valueOf(row.getTitle()));
            PreferenceGroup section = row.getParent();
            assertNotNull(section);
            int switchAt = -1;
            for (int i = 0; i < section.getPreferenceCount(); i++) {
                if (Settings.TAP_TO_PLAY.key.equals(section.getPreference(i).getKey())) switchAt = i;
            }
            assertTrue("the switch is in the choice's section", switchAt >= 0);
            assertEquals("right below the switch", row, section.getPreference(switchAt + 1));

            List<String> entries = new ArrayList<>();
            for (CharSequence entry : row.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Everywhere", "Everywhere but Reels", "Only in Reels"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : row.getEntryValues()) values.add(String.valueOf(value));
            List<String> names = new ArrayList<>();
            for (TapToPlayScope each : TapToPlayScope.values()) names.add(each.name());
            assertEquals(names, values);

            assertEquals("EVERYWHERE", row.getValue());
            assertEquals("Feed videos, reels and stories all wait for your tap.", String.valueOf(row.getSummary()));

            row.setValue("OUTSIDE_REELS");
            ShadowLooper.idleMainLooper();
            assertEquals(TapToPlayScope.OUTSIDE_REELS, Settings.TAP_TO_PLAY_SCOPE.savedValue());
            assertEquals("Feed videos and stories wait for your tap. Reels play as you swipe to them.",
                    String.valueOf(row.getSummary()));

            row.setValue("ONLY_REELS");
            ShadowLooper.idleMainLooper();
            assertEquals(TapToPlayScope.ONLY_REELS, Settings.TAP_TO_PLAY_SCOPE.savedValue());
            assertEquals("Reels wait for your tap. Feed videos and stories play as Instagram plays them.",
                    String.valueOf(row.getSummary()));

            // A value saved behind the row shows on it once the page hears of it.
            Settings.TAP_TO_PLAY_SCOPE.save(TapToPlayScope.OUTSIDE_REELS);
            ShadowLooper.idleMainLooper();
            assertEquals("OUTSIDE_REELS", row.getValue());

            // With the switch off, the choice asks for it.
            Settings.TAP_TO_PLAY.save(false);
            ShadowLooper.idleMainLooper();
            row.showSummary();
            assertEquals("Turn on Tap to play to use this choice.", String.valueOf(row.getSummary()));
        }
    }

    @Test
    public void withoutTapToPlayThereIsNoChoice() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY, PatchFamily.HIDE_ADS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            assertEquals(null, page.findPreference(Settings.TAP_TO_PLAY_SCOPE.key));
        }
    }

    /** An exported configuration carries the choice with the switch, and only when the patch is in the build. */
    @Test
    public void theChoiceTravelsWithTheSwitch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.TAP_TO_PLAY);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.TAP_TO_PLAY_SCOPE.key));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY);
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.TAP_TO_PLAY_SCOPE.key));
    }
}
