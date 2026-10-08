/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Arrays;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Change the like animation's rows: its switch, off to start, under Reels, and the Like animation
 * choice right under it, which waits for it and lists Instagram's animations by name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class LikeAnimationSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.CHANGE_LIKE_ANIMATION.resetToDefault();
        Settings.LIKE_ANIMATION.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CHANGE_LIKE_ANIMATION.resetToDefault();
        Settings.LIKE_ANIMATION.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed
                ? EnumSet.of(PatchFamily.LIKE_ANIMATION) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRows() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.CHANGE_LIKE_ANIMATION.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.LIKE_ANIMATION.key));
    }
    @Test public void theChoiceSitsUnderItsSwitchUnderReelsAndWaitsForIt() throws Exception {
        open(true);
        SwitchPreference change = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.CHANGE_LIKE_ANIMATION.key);
        ListPreference choice = (ListPreference) page.getPreferenceScreen().findPreference(Settings.LIKE_ANIMATION.key);
        assertNotNull(change);
        assertNotNull(choice);
        assertEquals("Change the like animation", change.getTitle().toString());
        assertFalse("off to start", change.isChecked());
        assertEquals("Like animation", choice.getTitle().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), change);
        assertNotNull(section);
        assertEquals("Reels", section.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), choice));
        assertEquals("right under its switch", indexIn(section, change) + 1, indexIn(section, choice));

        assertFalse("waits for the switch", choice.isEnabled());
        assertEquals("Turn on Change the like animation to use this choice.", choice.getSummary().toString());
        change.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(choice.isEnabled());
        assertEquals("Pick an animation. Until you do, the heart stays Instagram's.", choice.getSummary().toString());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.LIKE_ANIMATION.key));
    }
    @Test public void theChoiceListsInstagramsAnimationsByName() throws Exception {
        open(true);
        Settings.CHANGE_LIKE_ANIMATION.save(true);
        List<String> names = Arrays.asList("RINGS_LIKE_ADRIAN", "RINGS_LIKE_AKI_KOICHI", "ANYWAY_LIKE_ACTIVATION");
        HushgramPreferenceFragment.LikeAnimationRow row =
                HushgramPreferenceFragment.likeAnimationRow(controller.get(), names);
        assertArrayEquals(new CharSequence[]{"Adrian", "Aki Koichi", "Anyway"}, row.getEntries());
        assertArrayEquals(names.toArray(new CharSequence[0]), row.getEntryValues());
        assertEquals("Pick an animation. Until you do, the heart stays Instagram's.", row.getSummary().toString());

        row.setValue("RINGS_LIKE_AKI_KOICHI");
        assertEquals("The heart plays Aki Koichi when you double tap a post.",
                row.getSummary().toString().replaceAll("[⁦-⁩]", ""));
        Settings.LIKE_ANIMATION.save("RINGS_LIKE_GONE");
        assertEquals("a name this Instagram doesn't have", "Pick an animation. Until you do, the heart stays Instagram's.",
                HushgramPreferenceFragment.likeAnimationRow(controller.get(), names).getSummary().toString());
    }
    private static int indexIn(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == row) return i;
        }
        return -1;
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
