/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.StoryTimeSettingsTest.sectionOf;
import static org.junit.Assert.*;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import java.util.ArrayList;
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
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
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

    private Preference titledSection(String title) {
        for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
            Preference child = page.getPreferenceScreen().getPreference(i);
            if (child instanceof PreferenceGroup && title.equals(String.valueOf(child.getTitle()))) return child;
        }
        return null;
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
        Preference home = row(Settings.HIDE_HOME_TAB.key);
        assertEquals("Hide the Home tab", String.valueOf(home.getTitle()));
        assertTrue("says Home stays without a Start tab on the bar", String.valueOf(home.getSummary())
                .contains("pick one other than Home that's on the bar, or Home stays"));
        assertEquals("Hide the Search tab", String.valueOf(row(Settings.HIDE_SEARCH_TAB.key).getTitle()));
        assertEquals("Hide the Create tab", String.valueOf(row(Settings.HIDE_CREATE_TAB.key).getTitle()));
        assertEquals("Hide the Profile tab", String.valueOf(row(Settings.HIDE_PROFILE_TAB.key).getTitle()));
        assertEquals("Show the Messages tab", String.valueOf(row(Settings.SHOW_MESSAGES_TAB.key).getTitle()));
        Preference activity = row(Settings.SHOW_ACTIVITY_TAB.key);
        assertEquals("Show the Activity tab", String.valueOf(activity.getTitle()));
        assertTrue("names the switch that hides the heart", String.valueOf(activity.getSummary())
                .contains("Hide notifications on Home's header"));
        PreferenceGroup tabBar = sectionOf(page.getPreferenceScreen(), home);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < tabBar.getPreferenceCount(); i++) order.add(tabBar.getPreference(i).getKey());
        int after = order.indexOf(Settings.HIDE_HOME_TAB.key);
        assertEquals("the Reels tab's switches come right after Hide the Home tab: " + order,
                Arrays.asList(Settings.HIDE_REELS_TAB.key, Settings.SHOW_REELS_TAB.key), order.subList(after + 1, after + 3));
        assertEquals("Hide the Reels tab", String.valueOf(row(Settings.HIDE_REELS_TAB.key).getTitle()));
        assertEquals("Show the Reels tab", String.valueOf(row(Settings.SHOW_REELS_TAB.key).getTitle()));
        assertNull("Reels lists nothing with only the tab patch", titledSection("Reels"));
        assertNull("the glass bar's rows need their own patch", row(Settings.GLASS_TAB_BAR.key));
    }

    /** The Reels tab's switches sat under Reels before they moved to Tab bar, so a search for Reels still finds them. */
    @Test public void aSearchForReelsStillFindsTheTabSwitches() throws Exception {
        open(PatchFamily.REELS_TAB);
        page.searchSettings("Reels");
        assertNotNull(row(Settings.HIDE_REELS_TAB.key));
        assertNotNull(row(Settings.SHOW_REELS_TAB.key));
        page.searchSettings("Spoof location");
        assertNull(row(Settings.HIDE_REELS_TAB.key));
    }

    /** #298: Hide the Messages tab sits under Tab bar, starts off, needs no restart and points at the header's button. */
    @Test public void hideTheMessagesTabIsATabBarRowThatNeedsNoRestart() throws Exception {
        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.HIDE_MESSAGES_TAB.key);
        assertNotNull(toggle);
        assertEquals("Hide the Messages tab", String.valueOf(toggle.getTitle()));
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
        assertFalse(toggle.isChecked());
        assertFalse(Settings.HIDE_MESSAGES_TAB.rebootApp);
        assertTrue(String.valueOf(toggle.getSummary()).contains("Messages button on Home's header"));
        assertTrue(PatchFamily.REELS_TAB.switches.stream().anyMatch(setting -> setting.key.equals(Settings.HIDE_MESSAGES_TAB.key)));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_MESSAGES_TAB.key));
    }

    @Test public void theStartTabIsAChoiceUnderTabBarThatStartsAtHome() throws Exception {
        open(PatchFamily.REELS_TAB);
        Preference found = row(Settings.START_TAB.key);
        assertTrue(found instanceof HushgramPreferenceFragment.StartTabRow);
        HushgramPreferenceFragment.StartTabRow choice = (HushgramPreferenceFragment.StartTabRow) found;
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), choice).getTitle()));
        List<String> entries = new java.util.ArrayList<>();
        for (CharSequence entry : choice.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Home", "Always Home", "Search", "Messages", "Reels", "Profile"), entries);
        assertEquals("HOME", choice.getValue());
        assertEquals("Instagram opens the way it always has, which can be Reels if you left it there.",
                String.valueOf(choice.getSummary()));
        assertTrue(Settings.START_TAB.rebootApp);
        assertEquals(app.hushgram.extension.instagram.reels.StartTab.HOME, Settings.START_TAB.get());

        choice.setValue("SEARCH");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(app.hushgram.extension.instagram.reels.StartTab.SEARCH, Settings.START_TAB.savedValue());
        assertTrue(String.valueOf(choice.getSummary()).replaceAll("[\u2068\u2069]", "").startsWith("Instagram opens on Search when you start it from its icon."));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.START_TAB.key));

        choice.setValue("ALWAYS_HOME");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(app.hushgram.extension.instagram.reels.StartTab.ALWAYS_HOME, Settings.START_TAB.savedValue());
        assertTrue(String.valueOf(choice.getSummary()),
                String.valueOf(choice.getSummary()).startsWith("Instagram opens on Home when you start it from its icon, even if"));
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
        return Arrays.asList(new BooleanSettingKey(Settings.HIDE_HOME_TAB),
                new BooleanSettingKey(Settings.HIDE_SEARCH_TAB), new BooleanSettingKey(Settings.HIDE_CREATE_TAB),
                new BooleanSettingKey(Settings.HIDE_PROFILE_TAB), new BooleanSettingKey(Settings.SHOW_MESSAGES_TAB),
                new BooleanSettingKey(Settings.SHOW_ACTIVITY_TAB));
    }
}
