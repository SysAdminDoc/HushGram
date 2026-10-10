/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.shared.L10n;
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
 * With Default playback quality in the build, the Playback section has its switch and the list of
 * qualities right below it, which offers Auto first, says what the chosen one does, and reaches the
 * setting the way the list's own dialog sends a pick. Without the patch there's neither row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class PlaybackQualitySettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.DEFAULT_PLAYBACK_QUALITY.resetToDefault();
        Settings.PLAYBACK_QUALITY.resetToDefault();
    }

    @Test
    public void thePlaybackQualityRowOffersAutoAndEachQuality() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY, PatchFamily.TAP_TO_PLAY, PatchFamily.HIDE_ADS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            List<Preference> rows = rowsOf(page);
            int toggle = indexOfKey(rows, Settings.DEFAULT_PLAYBACK_QUALITY.key);
            assertTrue("no Default playback quality switch", toggle >= 0);
            PreferenceGroup section = null;
            for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
                Preference top = page.getPreferenceScreen().getPreference(i);
                if (top instanceof PreferenceGroup
                        && ((PreferenceGroup) top).findPreference(Settings.DEFAULT_PLAYBACK_QUALITY.key) != null) {
                    section = (PreferenceGroup) top;
                }
            }
            assertNotNull(section);
            assertEquals("Playback", String.valueOf(section.getTitle()));
            // Tap to play first with its choice of where, then the switch, its list and the tab that opens the list.
            assertEquals(5, section.getPreferenceCount());
            assertEquals(Settings.QUALITY_TAB_TARGET.key, section.getPreference(4).getKey());
            assertEquals(Settings.TAP_TO_PLAY.key, section.getPreference(0).getKey());
            assertEquals(Settings.TAP_TO_PLAY_SCOPE.key, section.getPreference(1).getKey());

            Preference sw = rows.get(toggle);
            assertTrue(sw instanceof SwitchPreference);
            assertTrue("picking the patch is the choice to use it", ((SwitchPreference) sw).isChecked());
            assertEquals("Default playback quality", String.valueOf(sw.getTitle()));
            assertEquals("Videos, reels and stories play at the quality below, starting with the next one you open.",
                    String.valueOf(sw.getSummary()));
            assertTrue(rows.get(toggle + 1) instanceof HushgramPreferenceFragment.PlaybackQualityRow);
            HushgramPreferenceFragment.PlaybackQualityRow quality =
                    (HushgramPreferenceFragment.PlaybackQualityRow) rows.get(toggle + 1);
            assertEquals(Settings.PLAYBACK_QUALITY.key, quality.getKey());
            assertEquals("Playback quality", String.valueOf(quality.getTitle()));

            List<String> entries = new ArrayList<>();
            for (CharSequence entry : quality.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Auto", "Data saver", "Up to " + L10n.isolate("480p"),
                    "Up to " + L10n.isolate("720p"), "Highest"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : quality.getEntryValues()) values.add(String.valueOf(value));
            List<String> names = new ArrayList<>();
            for (PlaybackQuality each : PlaybackQuality.values()) names.add(each.name());
            assertEquals(names, values);

            assertEquals("AUTO", quality.getValue());
            assertEquals("Instagram picks the quality as each video plays, from your connection.",
                    String.valueOf(quality.getSummary()));

            // A pick in the list, the way its dialog sends one.
            quality.setValue("DATA_SAVER");
            ShadowLooper.idleMainLooper();
            assertEquals(PlaybackQuality.DATA_SAVER, Settings.PLAYBACK_QUALITY.savedValue());
            assertEquals("Videos play at the lowest quality Instagram offers for each.", String.valueOf(quality.getSummary()));

            quality.setValue("HIGHEST");
            ShadowLooper.idleMainLooper();
            assertEquals(PlaybackQuality.HIGHEST, Settings.PLAYBACK_QUALITY.savedValue());
            assertEquals("Videos play at the highest quality Instagram offers for each.", String.valueOf(quality.getSummary()));

            // A value saved behind the row shows on it once the page hears of it.
            Settings.PLAYBACK_QUALITY.save(PlaybackQuality.P720);
            ShadowLooper.idleMainLooper();
            assertEquals("P720", quality.getValue());
            assertEquals("Videos play at the best quality up to " + L10n.isolate("720p")
                    + " that Instagram offers for each, or the closest above.", String.valueOf(quality.getSummary()));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.TAP_TO_PLAY, PatchFamily.HIDE_ADS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(DownloadSettingsTest.pageIn(controller))) {
                assertFalse("a playback quality row with no Default playback quality in the build",
                        row instanceof HushgramPreferenceFragment.PlaybackQualityRow);
                assertFalse(Settings.DEFAULT_PLAYBACK_QUALITY.key.equals(row.getKey()));
            }
        }
    }

    /** On its own, the patch still brings the Playback section. */
    @Test
    public void thePatchAloneBringsThePlaybackSection() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference row = page.findPreference(Settings.PLAYBACK_QUALITY.key);
            assertNotNull("no playback quality row with only the patch in the build", row);
            List<Preference> rows = rowsOf(page);
            assertEquals(indexOfKey(rows, Settings.DEFAULT_PLAYBACK_QUALITY.key) + 1, indexOfKey(rows, Settings.PLAYBACK_QUALITY.key));
        }
    }

    private static List<Preference> rowsOf(HushgramPreferenceFragment page) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        assertFalse("the screen has no rows", rows.isEmpty());
        return rows;
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                collect((PreferenceGroup) preference, rows);
            } else {
                rows.add(preference);
            }
        }
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }
}
