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
import app.hushgram.extension.instagram.stories.StoryRingThickness;
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
 * right below that, and shows the color chosen, then the two colors typed for Custom, then the list
 * of thicknesses. Without the patch there's none of the six.
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
        Settings.STORY_RING_CUSTOM.resetToDefault();
        Settings.STORY_RING_CUSTOM_END.resetToDefault();
        Settings.STORY_RING_THICKNESS.resetToDefault();
    }

    /**
     * #696: the Custom ring color rows sit under the color's list. The first refuses what isn't a
     * color under the field, saves a color as #RRGGBB and picks Custom in the list as it saves. The
     * end color takes a second color or nothing, and doesn't pick anything. Both are backed up that
     * way, and neither is there without the patch.
     */
    @Test
    public void theCustomRingColorRowsSaveHexColorsAndPickCustom() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_RING, PatchFamily.STORIES_TRAY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            List<Preference> rows = rowsOf(page);
            int toggle = indexOfKey(rows, Settings.STORY_RING.key);
            assertTrue("no Story ring size switch", toggle >= 0);
            HushgramPreferenceFragment.StoryRingColorRow color =
                    (HushgramPreferenceFragment.StoryRingColorRow) rows.get(toggle + 2);
            assertTrue("the custom color comes right under the color's list",
                    rows.get(toggle + 3) instanceof HushgramPreferenceFragment.StoryRingCustomRow);
            assertTrue("then its end color",
                    rows.get(toggle + 4) instanceof HushgramPreferenceFragment.StoryRingCustomRow);
            HushgramPreferenceFragment.StoryRingCustomRow start = (HushgramPreferenceFragment.StoryRingCustomRow) rows.get(toggle + 3);
            HushgramPreferenceFragment.StoryRingCustomRow end = (HushgramPreferenceFragment.StoryRingCustomRow) rows.get(toggle + 4);
            assertEquals(Settings.STORY_RING_CUSTOM.key, start.getKey());
            assertEquals(Settings.STORY_RING_CUSTOM_END.key, end.getKey());
            assertEquals("Custom ring color", String.valueOf(start.getTitle()));
            assertEquals("Custom ring end color", String.valueOf(end.getTitle()));
            assertEquals("None yet. Custom keeps Instagram's colors until you type one.", String.valueOf(start.getSummary()));
            assertEquals("None. The ring is one solid color.", String.valueOf(end.getSummary()));

            start.showDialog(null);
            android.app.AlertDialog dialog = (android.app.AlertDialog) start.getDialog();
            android.widget.EditText field = start.getEditText();
            field.setText("teal");
            field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            assertTrue("a word isn't a color", dialog.isShowing());
            assertEquals("That isn't a color. Try #4A6B82.", String.valueOf(field.getError()));
            field.setText("");
            field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            assertTrue("the first color can't be left empty", dialog.isShowing());
            assertEquals("", Settings.STORY_RING_CUSTOM.savedValue());
            assertEquals(StoryRingColor.INSTAGRAM, Settings.STORY_RING_COLOR.savedValue());

            field.setText(" 4a6b82 ");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(dialog.isShowing());
            assertEquals("#4A6B82", Settings.STORY_RING_CUSTOM.savedValue());
            assertEquals("#4A6B82", String.valueOf(start.getSummary()));
            assertEquals("saving a color picks Custom", StoryRingColor.CUSTOM, Settings.STORY_RING_COLOR.savedValue());
            assertEquals("CUSTOM", color.getValue());
            assertEquals("Custom", String.valueOf(color.getSummary()));

            end.showDialog(null);
            dialog = (android.app.AlertDialog) end.getDialog();
            field = end.getEditText();
            field.setText("orange");
            field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            assertTrue("a word isn't an end color either", dialog.isShowing());
            assertEquals("That isn't a color. Try #4A6B82.", String.valueOf(field.getError()));
            field.setText("#ff8a00");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(dialog.isShowing());
            assertEquals("#FF8A00", Settings.STORY_RING_CUSTOM_END.savedValue());
            assertEquals("#FF8A00", String.valueOf(end.getSummary()));

            end.showDialog(null);
            dialog = (android.app.AlertDialog) end.getDialog();
            end.getEditText().setText("  ");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse("an empty end color is one solid color", dialog.isShowing());
            assertEquals("", Settings.STORY_RING_CUSTOM_END.savedValue());
            assertEquals("None. The ring is one solid color.", String.valueOf(end.getSummary()));
            assertEquals("the end color picks nothing", StoryRingColor.CUSTOM, Settings.STORY_RING_COLOR.savedValue());

            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_RING_CUSTOM.key));
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_RING_CUSTOM_END.key));
            for (app.hushgram.extension.shared.settings.StringSetting typed
                    : Arrays.asList(Settings.STORY_RING_CUSTOM, Settings.STORY_RING_CUSTOM_END)) {
                assertTrue(ConfigurationBackup.textAllowed(typed, ""));
                assertTrue(ConfigurationBackup.textAllowed(typed, "#4A6B82"));
                assertFalse(ConfigurationBackup.textAllowed(typed, "#4a6b82"));
                assertFalse(ConfigurationBackup.textAllowed(typed, "teal"));
            }
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORIES_TRAY);
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_RING_CUSTOM.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_RING_CUSTOM_END.key));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(DownloadSettingsTest.pageIn(controller))) {
                assertFalse("a custom ring color row with no Story ring size in the build",
                        row instanceof HushgramPreferenceFragment.StoryRingCustomRow);
                assertFalse(Settings.STORY_RING_CUSTOM.key.equals(row.getKey()));
                assertFalse(Settings.STORY_RING_CUSTOM_END.key.equals(row.getKey()));
            }
        }
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
                    + "stories you haven't watched in the color below. Story rings take the thickness below. "
                    + "Restart Instagram to see a change.",
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
            assertEquals(Arrays.asList("Instagram's colors", "Blue", "Teal", "Purple", "Red", "Orange", "Gold", "Custom"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : color.getEntryValues()) values.add(String.valueOf(value));
            assertEquals(Arrays.asList("INSTAGRAM", "BLUE", "TEAL", "PURPLE", "RED", "ORANGE", "GOLD", "CUSTOM"), values);

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

    /**
     * The thickness's list sits under the color's (#528), offers each share thinnest first with
     * Instagram's own in the middle, and saves a pick without touching the size or the color.
     */
    @Test
    public void theRingThicknessRowOffersEachThicknessAndShowsTheOneChosen() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_RING, PatchFamily.STORIES_TRAY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            List<Preference> rows = rowsOf(page);
            int toggle = indexOfKey(rows, Settings.STORY_RING.key);
            assertTrue("no Story ring size switch", toggle >= 0);
            assertTrue("the thickness comes right under the custom colors",
                    rows.get(toggle + 5) instanceof HushgramPreferenceFragment.StoryRingThicknessRow);
            HushgramPreferenceFragment.StoryRingThicknessRow thickness =
                    (HushgramPreferenceFragment.StoryRingThicknessRow) rows.get(toggle + 5);
            assertEquals(Settings.STORY_RING_THICKNESS.key, thickness.getKey());
            assertEquals("Ring thickness", String.valueOf(thickness.getTitle()));
            assertEquals("Ring thickness", String.valueOf(thickness.getDialogTitle()));
            List<String> entries = new ArrayList<>();
            for (CharSequence entry : thickness.getEntries()) entries.add(String.valueOf(entry));
            NumberFormat percent = NumberFormat.getPercentInstance(L10n.locale());
            assertEquals(Arrays.asList(percent.format(0.5), percent.format(0.75), "Instagram's thickness",
                    percent.format(1.5), percent.format(2.0)), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : thickness.getEntryValues()) values.add(String.valueOf(value));
            assertEquals(Arrays.asList("PERCENT_50", "PERCENT_75", "INSTAGRAM", "PERCENT_150", "PERCENT_200"), values);

            assertEquals("INSTAGRAM", thickness.getValue());
            assertEquals("Instagram's thickness", String.valueOf(thickness.getSummary()));

            for (StoryRingThickness each : StoryRingThickness.values()) {
                thickness.setValue(each.name());
                ShadowLooper.idleMainLooper();
                assertEquals(each, Settings.STORY_RING_THICKNESS.savedValue());
                assertEquals(entries.get(each.ordinal()), String.valueOf(thickness.getSummary()));
                assertEquals("a thickness changed the size", StoryRingSize.INSTAGRAM, Settings.STORY_RING_SCALE.savedValue());
                assertEquals("a thickness changed the color", StoryRingColor.INSTAGRAM, Settings.STORY_RING_COLOR.savedValue());
            }
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_RING_THICKNESS.key));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORIES_TRAY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(DownloadSettingsTest.pageIn(controller))) {
                assertFalse("a ring thickness row with no Story ring size in the build",
                        row instanceof HushgramPreferenceFragment.StoryRingThicknessRow);
                assertFalse(Settings.STORY_RING_THICKNESS.key.equals(row.getKey()));
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
                String share = NumberFormat.getPercentInstance(L10n.locale()).format(each.percent() / 100.0);
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
