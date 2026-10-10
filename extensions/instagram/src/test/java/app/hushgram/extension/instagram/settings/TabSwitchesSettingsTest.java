/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.StoryTimeSettingsTest.sectionOf;
import static org.junit.Assert.*;

import android.app.Activity;
import android.preference.Preference;
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

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;

/** The tab bar switches: Search, Create and Profile sit under Tab bar and start off, with the Reels tab's patch in the build. */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class TabSwitchesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(PatchFamily... families) throws Exception {
        PatchFamily.inBuildForTests = families.length == 0 ? EnumSet.noneOf(PatchFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private Preference row(String key) {
        return page.getPreferenceScreen().findPreference(key);
    }

    @Test public void withoutThePatchNoTabRowShows() throws Exception {
        open();
        for (BooleanSettingKey key : keys()) assertNull(key.key, row(key.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_SEARCH_TAB.key));
    }

    @Test public void withoutThePatchThereIsNoStartTab() throws Exception {
        open();
        assertNull(row(Settings.START_TAB.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.START_TAB.key));
    }

    @Test public void eachTabHasItsOwnSwitchUnderTabBarThatStartsOffAndAsksForARestart() throws Exception {
        open(PatchFamily.REELS_TAB);
        for (BooleanSettingKey key : keys()) {
            SwitchPreference toggle = (SwitchPreference) row(key.key);
            assertNotNull(key.key, toggle);
            assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
            assertFalse(key.key, toggle.isChecked());
            assertTrue(key.key, key.rebootApp);
            assertTrue(key.key, PatchFamily.REELS_TAB.switches.stream().anyMatch(setting -> setting.key.equals(key.key)));
            assertTrue(key.key, ConfigurationBackup.eligible().containsKey(key.key));
        }
        assertEquals("Hide the Search tab", String.valueOf(row(Settings.HIDE_SEARCH_TAB.key).getTitle()));
        assertEquals("Hide the Create tab", String.valueOf(row(Settings.HIDE_CREATE_TAB.key).getTitle()));
        assertEquals("Hide the Profile tab", String.valueOf(row(Settings.HIDE_PROFILE_TAB.key).getTitle()));
        assertNotNull("Hide the Reels tab stays under Reels", row(Settings.HIDE_REELS_TAB.key));
        assertNull("the glass bar's rows need their own patch", row(Settings.GLASS_TAB_BAR.key));
    }

    @Test public void theStartTabIsAChoiceUnderTabBarThatStartsAtHome() throws Exception {
        open(PatchFamily.REELS_TAB);
        Preference found = row(Settings.START_TAB.key);
        assertTrue(found instanceof HushgramPreferenceFragment.StartTabRow);
        HushgramPreferenceFragment.StartTabRow choice = (HushgramPreferenceFragment.StartTabRow) found;
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), choice).getTitle()));
        List<String> entries = new java.util.ArrayList<>();
        for (CharSequence entry : choice.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Home", "Search", "Messages", "Reels", "Profile"), entries);
        assertEquals("HOME", choice.getValue());
        assertEquals("Instagram opens the way it always has.", String.valueOf(choice.getSummary()));
        assertTrue(Settings.START_TAB.rebootApp);
        assertEquals(app.hushgram.extension.instagram.reels.StartTab.HOME, Settings.START_TAB.get());

        choice.setValue("SEARCH");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(app.hushgram.extension.instagram.reels.StartTab.SEARCH, Settings.START_TAB.savedValue());
        assertTrue(String.valueOf(choice.getSummary()).replaceAll("[\u2068\u2069]", "").startsWith("Instagram opens on Search when you start it from its icon."));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.START_TAB.key));
        Settings.START_TAB.resetToDefault();
    }

    private static final class BooleanSettingKey {
        final String key;
        final boolean rebootApp;

        BooleanSettingKey(app.hushgram.extension.shared.settings.BooleanSetting setting) {
            key = setting.key;
            rebootApp = setting.rebootApp;
        }
    }

    private static List<BooleanSettingKey> keys() {
        return Arrays.asList(new BooleanSettingKey(Settings.HIDE_SEARCH_TAB), new BooleanSettingKey(Settings.HIDE_CREATE_TAB),
                new BooleanSettingKey(Settings.HIDE_PROFILE_TAB));
    }
}
