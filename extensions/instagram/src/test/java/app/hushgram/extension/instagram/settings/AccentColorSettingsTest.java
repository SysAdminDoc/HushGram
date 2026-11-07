/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.misc.AccentHue;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Accent color's switch and its color list: under Layout, off to start, off while paused, backed up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class AccentColorSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_CUSTOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_CUSTOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoAccentRows() throws Exception {
        open(EnumSet.of(PatchFamily.EMOJI_STYLE));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ACCENT_COLOR.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ACCENT_HUE.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_HUE.key));
    }
    /** Layout opens for Accent color alone, its switch first and the color list under it. */
    @Test public void accentAloneStillGetsLayout() throws Exception {
        open(EnumSet.of(PatchFamily.ACCENT_COLOR));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.ACCENT_COLOR.key);
        assertNotNull(row);
        PreferenceGroup layout = row.getParent();
        assertEquals("Layout", layout.getTitle().toString());
        String[] keys = new String[layout.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = layout.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.ACCENT_COLOR.key, Settings.ACCENT_HUE.key, Settings.ACCENT_CUSTOM.key, Settings.ACCENT_BADGES.key, Settings.ACCENT_ICONS.key,
                Settings.ACCENT_RINGS.key, Settings.ACCENT_RINGS_SOLID.key, Settings.ACCENT_BACKGROUNDS.key, LayoutCategory.PURE_BLACK_HINT_ROW), Arrays.asList(keys));
    }
    @Test public void switchStartsOffAfterEmojiPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE, PatchFamily.EMOJI_STYLE, PatchFamily.ACCENT_COLOR));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.ACCENT_COLOR.key);
        assertNotNull(row);
        assertEquals("Accent color", row.getTitle().toString());
        assertEquals("Turns Instagram's blue, on Follow buttons, links and the like, into the color you pick below. "
                + "Restart Instagram to see the change.", row.getSummary().toString());
        PreferenceGroup layout = row.getParent();
        String[] keys = new String[layout.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = layout.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.REMOVE_BOTTOM_SPACE.key, Settings.NOTO_EMOJI.key, Settings.EMOJI_FONT.key, Settings.ACCENT_COLOR.key,
                Settings.ACCENT_HUE.key, Settings.ACCENT_CUSTOM.key, Settings.ACCENT_BADGES.key, Settings.ACCENT_ICONS.key, Settings.ACCENT_RINGS.key, Settings.ACCENT_RINGS_SOLID.key,
                Settings.ACCENT_BACKGROUNDS.key, LayoutCategory.PURE_BLACK_HINT_ROW),
                Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.ACCENT_COLOR.get());
        assertEquals(Arrays.asList(Settings.ACCENT_COLOR, Settings.ACCENT_BADGES, Settings.ACCENT_ICONS, Settings.ACCENT_RINGS,
                Settings.ACCENT_RINGS_SOLID, Settings.ACCENT_BACKGROUNDS), PatchFamily.ACCENT_COLOR.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_BACKGROUNDS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_RINGS_SOLID.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_RINGS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_ICONS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_BADGES.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_COLOR.key));

        Settings.ACCENT_COLOR.save(true);
        assertTrue(Settings.ACCENT_COLOR.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.ACCENT_COLOR.get());
        assertTrue(Settings.ACCENT_COLOR.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.ACCENT_COLOR.get());
    }
    /** The color list offers Instagram blue first, starts on purple, saves a pick and is backed up. */
    @Test public void colorListStartsOnPurpleSavesAPickAndIsBackedUp() throws Exception {
        open(EnumSet.of(PatchFamily.ACCENT_COLOR));
        Preference found = page.getPreferenceScreen().findPreference(Settings.ACCENT_HUE.key);
        assertTrue(found instanceof HushgramPreferenceFragment.AccentHueRow);
        HushgramPreferenceFragment.AccentHueRow hue = (HushgramPreferenceFragment.AccentHueRow) found;
        assertEquals("Color", String.valueOf(hue.getTitle()));
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : hue.getEntries()) entries.add(String.valueOf(entry));
        List<String> offered = new ArrayList<>(Arrays.asList("Instagram blue", "Purple", "Pink", "Red", "Orange", "Green", "Teal",
                "Dusk blue", "Pine", "Slate"));
        // Material You (#187) is offered only where the phone has a wallpaper palette, Android 12 and newer.
        boolean materialYou = android.os.Build.VERSION.SDK_INT >= 31;
        // Before that the choice stays listed, greyed out in the dialog, with its reason as the text.
        offered.add(materialYou ? "Material You"
                : "Material You needs Android 12 or newer, so Instagram's blue stays.");
        offered.add("Custom");
        assertEquals(offered, entries);
        List<String> values = new ArrayList<>();
        for (CharSequence value : hue.getEntryValues()) values.add(String.valueOf(value));
        List<String> names = new ArrayList<>();
        for (AccentHue each : AccentHue.values()) names.add(each.name());
        assertEquals(names, values);
        assertEquals("PURPLE", hue.getValue());
        assertEquals("Purple", String.valueOf(hue.getSummary()));

        hue.setValue("TEAL");
        ShadowLooper.idleMainLooper();
        assertEquals(AccentHue.TEAL, Settings.ACCENT_HUE.savedValue());
        assertEquals("Teal", String.valueOf(hue.getSummary()));
        hue.setValue("BLUE");
        ShadowLooper.idleMainLooper();
        assertEquals(AccentHue.BLUE, Settings.ACCENT_HUE.savedValue());
        assertEquals("Instagram blue", String.valueOf(hue.getSummary()));
        // A backup from a newer phone can carry Material You onto an older one, which says why the blue stays.
        hue.setValue("WALLPAPER");
        ShadowLooper.idleMainLooper();
        assertEquals(AccentHue.WALLPAPER, Settings.ACCENT_HUE.savedValue());
        assertEquals(materialYou ? "Material You" : "Material You needs Android 12 or newer, so Instagram's blue stays.",
                String.valueOf(hue.getSummary()));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_HUE.key));
    }

    /**
     * #520: the Custom color row sits under the list, refuses what isn't a color under the field, saves
     * a color as #RRGGBB, picks Custom in the list as it saves, and is backed up that way.
     */
    @Test public void customColorRowSavesAHexColorAndPicksCustom() throws Exception {
        // The row hangs under Accent color, so it takes a color only while that switch is on.
        Settings.ACCENT_COLOR.save(true);
        open(EnumSet.of(PatchFamily.ACCENT_COLOR));
        Preference found = page.getPreferenceScreen().findPreference(Settings.ACCENT_CUSTOM.key);
        assertTrue(found instanceof HushgramPreferenceFragment.AccentCustomRow);
        HushgramPreferenceFragment.AccentCustomRow row = (HushgramPreferenceFragment.AccentCustomRow) found;
        HushgramPreferenceFragment.AccentHueRow hue =
                (HushgramPreferenceFragment.AccentHueRow) page.getPreferenceScreen().findPreference(Settings.ACCENT_HUE.key);
        assertEquals("Custom color", String.valueOf(row.getTitle()));
        assertEquals("None yet. Custom stays Instagram's blue until you type one.", String.valueOf(row.getSummary()));

        row.showDialog(null);
        android.app.AlertDialog dialog = (android.app.AlertDialog) row.getDialog();
        android.widget.EditText field = row.getEditText();
        field.setText("teal");
        field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        assertTrue("a word isn't a color", dialog.isShowing());
        assertEquals("That isn't a color. Try #4A6B82.", String.valueOf(field.getError()));
        assertEquals("", Settings.ACCENT_CUSTOM.savedValue());
        assertEquals(AccentHue.PURPLE, Settings.ACCENT_HUE.savedValue());

        field.setText(" 4a6b82 ");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals("#4A6B82", Settings.ACCENT_CUSTOM.savedValue());
        assertEquals("#4A6B82", String.valueOf(row.getSummary()));
        assertEquals("saving a color picks Custom", AccentHue.CUSTOM, Settings.ACCENT_HUE.savedValue());
        assertEquals("CUSTOM", hue.getValue());
        assertEquals("Custom", String.valueOf(hue.getSummary()));

        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ACCENT_CUSTOM.key));
        assertTrue(ConfigurationBackup.textAllowed(Settings.ACCENT_CUSTOM, ""));
        assertTrue(ConfigurationBackup.textAllowed(Settings.ACCENT_CUSTOM, "#4A6B82"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.ACCENT_CUSTOM, "#4a6b82"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.ACCENT_CUSTOM, "teal"));
    }
}
