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
import android.preference.SwitchPreference;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.stories.StoryRingColor;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;

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
 * With Story ring size in the build, the Stories section has its switch and the list of sizes right
 * below it, which says what the chosen size does. A share such as 130% shows as it is: the list's
 * summary isn't run through String.format, which a percent sign breaks. The list of colors comes
 * right below that, and shows the color chosen. Without the patch there's none of the three.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class StoryRingSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        Settings.STORY_RING.resetToDefault();
        Settings.STORY_RING_SCALE.resetToDefault();
        Settings.STORY_RING_COLOR.resetToDefault();
    }

    /**
     * The color's list sits under the size's, offers Instagram's own colors first and then each
     * color by name, and saves a pick without touching the size. The switch above says what both
     * lists do.
     */
    @Test
    public void theRingColorRowOffersEachColorAndShowsTheOneChosen() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_RING, PatchFamily.STORIES_TRAY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            List<Preference> rows = rowsOf(page);
            int toggle = indexOfKey(rows, Settings.STORY_RING.key);
            assertTrue("no Story ring size switch", toggle >= 0);
            assertEquals("Draws the rings in the stories row at the top of Home at the size below, and the rings of "
                    + "stories you haven't watched in the color below. Restart Instagram to see a change.",
                    String.valueOf(rows.get(toggle).getSummary()));

            assertTrue("the size's list stays right under the switch",
                    rows.get(toggle + 1) instanceof HushgramPreferenceFragment.StoryRingRow);
            assertTrue(rows.get(toggle + 2) instanceof HushgramPreferenceFragment.StoryRingColorRow);
            HushgramPreferenceFragment.StoryRingColorRow color =
                    (HushgramPreferenceFragment.StoryRingColorRow) rows.get(toggle + 2);
            assertEquals(Settings.STORY_RING_COLOR.key, color.getKey());
            assertEquals("Ring color", String.valueOf(color.getTitle()));
            assertEquals("Ring color", String.valueOf(color.getDialogTitle()));
            List<String> entries = new ArrayList<>();
            for (CharSequence entry : color.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Instagram's colors", "Blue", "Teal", "Purple", "Red", "Orange", "Gold"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : color.getEntryValues()) values.add(String.valueOf(value));
            assertEquals(Arrays.asList("INSTAGRAM", "BLUE", "TEAL", "PURPLE", "RED", "ORANGE", "GOLD"), values);

            assertEquals("INSTAGRAM", color.getValue());
            assertEquals("Instagram's colors", String.valueOf(color.getSummary()));

            // Every pick, the way the list's dialog sends one, is saved and shown by its name.
            for (StoryRingColor each : StoryRingColor.values()) {
                color.setValue(each.name());
                ShadowLooper.idleMainLooper();
                assertEquals(each, Settings.STORY_RING_COLOR.savedValue());
                assertEquals(entries.get(each.ordinal()), String.valueOf(color.getSummary()));
                assertEquals("a color changed the size", StoryRingSize.INSTAGRAM, Settings.STORY_RING_SCALE.savedValue());
            }

            // And a size picked afterwards leaves the color where it was.
            color.setValue(StoryRingColor.PURPLE.name());
            ShadowLooper.idleMainLooper();
            ((HushgramPreferenceFragment.StoryRingRow) rows.get(toggle + 1)).setValue(StoryRingSize.LARGER.name());
            ShadowLooper.idleMainLooper();
            assertEquals(StoryRingSize.LARGER, Settings.STORY_RING_SCALE.savedValue());
            assertEquals(StoryRingColor.PURPLE, Settings.STORY_RING_COLOR.savedValue());
            assertEquals("Purple", String.valueOf(color.getSummary()));
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_RING_COLOR.key));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORIES_TRAY);
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_RING_COLOR.key));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(DownloadSettingsTest.pageIn(controller))) {
                assertFalse("a ring color row with no Story ring size in the build",
                        row instanceof HushgramPreferenceFragment.StoryRingColorRow);
                assertFalse(Settings.STORY_RING_COLOR.key.equals(row.getKey()));
            }
        }
    }

    /** Each color's name on the list is its own, so no two colors read the same. */
    @Test
    public void everyColorHasANameOfItsOwn() {
        List<String> names = new ArrayList<>();
        for (StoryRingColor each : StoryRingColor.values()) {
            String name = HushgramPreferenceFragment.storyRingColorLabel(each);
            assertFalse(each.name() + " shares the name " + name, names.contains(name));
            names.add(name);
        }
        assertEquals("Instagram's colors", HushgramPreferenceFragment.storyRingColorLabel(StoryRingColor.INSTAGRAM));
    }

    @Test
    public void theRingSizeRowOffersEachSizeAndSaysWhatItDoes() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_RING, PatchFamily.STORIES_TRAY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            List<Preference> rows = rowsOf(page);
            int toggle = indexOfKey(rows, Settings.STORY_RING.key);
            assertTrue("no Story ring size switch", toggle >= 0);
            Preference sw = rows.get(toggle);
            assertTrue(sw instanceof SwitchPreference);
            assertTrue("picking the patch is the choice to use it", ((SwitchPreference) sw).isChecked());
            assertEquals("Story ring size", String.valueOf(sw.getTitle()));
            assertTrue("the switch comes after the tray's two", toggle > indexOfKey(rows, Settings.HIDE_STORIES_TRAY.key));

            assertTrue(rows.get(toggle + 1) instanceof HushgramPreferenceFragment.StoryRingRow);
            HushgramPreferenceFragment.StoryRingRow size = (HushgramPreferenceFragment.StoryRingRow) rows.get(toggle + 1);
            assertEquals(Settings.STORY_RING_SCALE.key, size.getKey());
            assertEquals("Ring size", String.valueOf(size.getTitle()));
            List<String> entries = new ArrayList<>();
            for (CharSequence entry : size.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("50%", "60%", "70%", "80%", "85%", "90%", "Instagram's size",
                    "110%", "115%", "120%", "130%", "140%", "150%"), entries);

            assertEquals("INSTAGRAM", size.getValue());
            assertEquals("The rings are the size Instagram picks for your screen.", String.valueOf(size.getSummary()));

            // Every pick, the way the list's dialog sends one, is saved and read back as its share.
            for (StoryRingSize each : StoryRingSize.values()) {
                if (each == StoryRingSize.INSTAGRAM) continue;
                size.setValue(each.name());
                ShadowLooper.idleMainLooper();
                assertEquals(each, Settings.STORY_RING_SCALE.savedValue());
                String share = NumberFormat.getPercentInstance().format(each.percent() / 100.0);
                assertEquals("The rings are " + L10n.isolate(share) + " of the size Instagram picks for your screen.",
                        String.valueOf(size.getSummary()));
            }
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORIES_TRAY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(DownloadSettingsTest.pageIn(controller))) {
                assertFalse("a ring size row with no Story ring size in the build",
                        row instanceof HushgramPreferenceFragment.StoryRingRow);
                assertFalse(Settings.STORY_RING.key.equals(row.getKey()));
            }
        }
    }

    private static List<Preference> rowsOf(HushgramPreferenceFragment page) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        assertNotNull(page.getPreferenceScreen());
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
