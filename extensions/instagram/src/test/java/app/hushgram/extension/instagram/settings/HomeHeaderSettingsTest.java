/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.StoryTimeSettingsTest.sectionOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import java.util.Arrays;
import java.util.EnumSet;

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
import app.hushgram.extension.shared.settings.BooleanSetting;

/** The Home header switches sit under Tab bar with the Reels tab's patch in the build, and start off. */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class HomeHeaderSettingsTest {
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

    private static BooleanSetting[] switches() {
        return new BooleanSetting[] {Settings.HIDE_HOME_CREATE_BUTTON, Settings.HIDE_HOME_NOTIFICATIONS_BUTTON};
    }

    @Test public void withoutThePatchNoHeaderRowShows() throws Exception {
        open();
        for (BooleanSetting setting : switches()) {
            assertNull(setting.key, row(setting.key));
            assertFalse(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
        }
    }

    @Test public void theGhostButtonSwitchShowsUnderTabBarOnlyWithAGhostPatchInTheBuild() throws Exception {
        open(PatchFamily.REELS_TAB);
        assertNull(row(Settings.GHOST_BUTTON_ON_HOME.key));
        controller.close();
        controller = null;

        open(PatchFamily.REELS_TAB, PatchFamily.THREAD_SEEN);
        SwitchPreference toggle = (SwitchPreference) row(Settings.GHOST_BUTTON_ON_HOME.key);
        assertNotNull(toggle);
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
        assertEquals("Ghost mode button on Home's header", String.valueOf(toggle.getTitle()));
        assertFalse(toggle.isChecked());
        assertTrue(Settings.GHOST_BUTTON_ON_HOME.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.GHOST_BUTTON_ON_HOME));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GHOST_BUTTON_ON_HOME.key));
    }

    @Test public void theSearchButtonSwitchShowsUnderTabBarWithTheReelsTabPatchAndStartsOff() throws Exception {
        open();
        assertNull(row(Settings.SEARCH_BUTTON_ON_HOME.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.SEARCH_BUTTON_ON_HOME.key));
        controller.close();
        controller = null;

        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.SEARCH_BUTTON_ON_HOME.key);
        assertNotNull(toggle);
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
        assertEquals("Search button on Home's header", String.valueOf(toggle.getTitle()));
        assertFalse(toggle.isChecked());
        assertEquals(Boolean.FALSE, Settings.SEARCH_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.SEARCH_BUTTON_ON_HOME.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.SEARCH_BUTTON_ON_HOME));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SEARCH_BUTTON_ON_HOME.key));
    }

    /** Messages button on Home's header (#156) follows the Search one under Tab bar, starts off and asks for a restart. */
    @Test public void theMessagesButtonSwitchFollowsTheSearchOneAndStartsOff() throws Exception {
        open();
        assertNull(row(Settings.MESSAGES_BUTTON_ON_HOME.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.MESSAGES_BUTTON_ON_HOME.key));
        controller.close();
        controller = null;

        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.MESSAGES_BUTTON_ON_HOME.key);
        assertNotNull(toggle);
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), toggle);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int search = -1;
        int messages = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.SEARCH_BUTTON_ON_HOME.key.equals(key)) search = i;
            if (Settings.MESSAGES_BUTTON_ON_HOME.key.equals(key)) messages = i;
        }
        assertEquals("right after Search button on Home's header", search + 1, messages);
        assertEquals("Messages button on Home's header", String.valueOf(toggle.getTitle()));
        assertTrue(String.valueOf(toggle.getSummary()), String.valueOf(toggle.getSummary()).endsWith("Restart Instagram to see the change."));
        assertFalse(toggle.isChecked());
        assertEquals(Boolean.FALSE, Settings.MESSAGES_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.MESSAGES_BUTTON_ON_HOME.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.MESSAGES_BUTTON_ON_HOME));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.MESSAGES_BUTTON_ON_HOME.key));
    }

    /** Settings button on Home's header (#404) sits under Tab bar, starts off and asks for a restart. */
    @Test public void theSettingsButtonSwitchStartsOff() throws Exception {
        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.SETTINGS_BUTTON_ON_HOME.key);
        assertNotNull(toggle);
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
        assertEquals("Settings button on Home's header", String.valueOf(toggle.getTitle()));
        assertTrue(String.valueOf(toggle.getSummary()).endsWith("Restart Instagram to see the change."));
        assertFalse(toggle.isChecked());
        assertEquals(Boolean.FALSE, Settings.SETTINGS_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.SETTINGS_BUTTON_ON_HOME.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.SETTINGS_BUTTON_ON_HOME));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_BUTTON_ON_HOME.key));
    }

    /** Menu button on Home's header (#437) sits under Tab bar, starts off and asks for a restart. */
    @Test public void theMenuButtonSwitchStartsOff() throws Exception {
        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.MENU_BUTTON_ON_HOME.key);
        assertNotNull(toggle);
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
        assertEquals("Menu button on Home's header", String.valueOf(toggle.getTitle()));
        assertTrue(String.valueOf(toggle.getSummary()).endsWith("Restart Instagram to see the change."));
        assertFalse(toggle.isChecked());
        assertEquals(Boolean.FALSE, Settings.MENU_BUTTON_ON_HOME.defaultValue);
        assertTrue(Settings.MENU_BUTTON_ON_HOME.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.MENU_BUTTON_ON_HOME));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.MENU_BUTTON_ON_HOME.key));
    }

    /** Hide Messages on Home's header (#210) follows Messages button on Home's header, starts off and asks for a restart. */
    @Test public void theHideMessagesSwitchFollowsTheMessagesButtonOneAndStartsOff() throws Exception {
        open();
        assertNull(row(Settings.HIDE_HOME_MESSAGES_BUTTON.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_HOME_MESSAGES_BUTTON.key));
        controller.close();
        controller = null;

        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.HIDE_HOME_MESSAGES_BUTTON.key);
        assertNotNull(toggle);
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), toggle);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int messages = -1;
        int hide = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.MESSAGES_BUTTON_ON_HOME.key.equals(key)) messages = i;
            if (Settings.HIDE_HOME_MESSAGES_BUTTON.key.equals(key)) hide = i;
        }
        assertEquals("right after Messages button on Home's header", messages + 1, hide);
        assertEquals("Hide Messages on Home's header", String.valueOf(toggle.getTitle()));
        assertTrue(String.valueOf(toggle.getSummary()), String.valueOf(toggle.getSummary()).endsWith("Restart Instagram to see the change."));
        assertFalse(toggle.isChecked());
        assertEquals(Boolean.FALSE, Settings.HIDE_HOME_MESSAGES_BUTTON.defaultValue);
        assertTrue(Settings.HIDE_HOME_MESSAGES_BUTTON.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.HIDE_HOME_MESSAGES_BUTTON));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_HOME_MESSAGES_BUTTON.key));
    }

    @Test public void theCenteredLogoSwitchShowsUnderTabBarWithTheReelsTabPatchAndStartsOff() throws Exception {
        open();
        assertNull(row(Settings.CENTER_HOME_LOGO.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.CENTER_HOME_LOGO.key));
        controller.close();
        controller = null;

        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.CENTER_HOME_LOGO.key);
        assertNotNull(toggle);
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
        assertEquals("Center the logo on Home's header", String.valueOf(toggle.getTitle()));
        assertTrue(String.valueOf(toggle.getSummary()), String.valueOf(toggle.getSummary()).startsWith("Puts Instagram's logo in the middle"));
        assertFalse(toggle.isChecked());
        assertEquals(Boolean.FALSE, Settings.CENTER_HOME_LOGO.defaultValue);
        assertTrue(Settings.CENTER_HOME_LOGO.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.CENTER_HOME_LOGO));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CENTER_HOME_LOGO.key));
    }

    /** Center the name on profiles (#140) follows the centered logo under Tab bar, starts off and needs no restart. */
    @Test public void theCenteredProfileNameSwitchFollowsTheLogoOneAndStartsOff() throws Exception {
        open();
        assertNull(row(Settings.CENTER_PROFILE_NAME.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.CENTER_PROFILE_NAME.key));
        controller.close();
        controller = null;

        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.CENTER_PROFILE_NAME.key);
        assertNotNull(toggle);
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), toggle);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int logo = -1;
        int name = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.CENTER_HOME_LOGO.key.equals(key)) logo = i;
            if (Settings.CENTER_PROFILE_NAME.key.equals(key)) name = i;
        }
        assertEquals("right after Center the logo on Home's header", logo + 1, name);
        assertEquals("Center the name on profiles", String.valueOf(toggle.getTitle()));
        assertTrue(String.valueOf(toggle.getSummary()), String.valueOf(toggle.getSummary()).contains("The buttons stay where they are."));
        assertFalse(toggle.isChecked());
        assertEquals(Boolean.FALSE, Settings.CENTER_PROFILE_NAME.defaultValue);
        assertFalse("it's moved after each layout, so no restart", Settings.CENTER_PROFILE_NAME.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.CENTER_PROFILE_NAME));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CENTER_PROFILE_NAME.key));
    }

    /**
     * Center titles on other screens (#161) follows Center the name on profiles under Tab bar, starts
     * off and needs no restart. The two older switches keep their own keys, so a saved choice of either
     * never turns this one on.
     */
    @Test public void theCenteredTitlesSwitchFollowsTheProfileNameOneAndStartsOff() throws Exception {
        open();
        assertNull(row(Settings.CENTER_SCREEN_TITLES.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.CENTER_SCREEN_TITLES.key));
        controller.close();
        controller = null;

        Settings.CENTER_PROFILE_NAME.save(true);
        Settings.CENTER_HOME_LOGO.save(true);
        try {
            open(PatchFamily.REELS_TAB);
            SwitchPreference toggle = (SwitchPreference) row(Settings.CENTER_SCREEN_TITLES.key);
            assertNotNull(toggle);
            PreferenceGroup section = sectionOf(page.getPreferenceScreen(), toggle);
            assertEquals("Tab bar", String.valueOf(section.getTitle()));
            int name = -1;
            int titles = -1;
            for (int i = 0; i < section.getPreferenceCount(); i++) {
                String key = section.getPreference(i).getKey();
                if (Settings.CENTER_PROFILE_NAME.key.equals(key)) name = i;
                if (Settings.CENTER_SCREEN_TITLES.key.equals(key)) titles = i;
            }
            assertEquals("right after Center the name on profiles", name + 1, titles);
            assertEquals("Center titles on other screens", String.valueOf(toggle.getTitle()));
            assertTrue(String.valueOf(toggle.getSummary()), String.valueOf(toggle.getSummary()).contains("The buttons stay where they are."));
            assertFalse("the other two switches don't turn it on", toggle.isChecked());
            assertTrue(Settings.CENTER_PROFILE_NAME.get());
            assertTrue(Settings.CENTER_HOME_LOGO.get());
            assertEquals(Boolean.FALSE, Settings.CENTER_SCREEN_TITLES.defaultValue);
            assertFalse("it's moved after each layout, so no restart", Settings.CENTER_SCREEN_TITLES.rebootApp);
            assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.CENTER_SCREEN_TITLES));
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CENTER_SCREEN_TITLES.key));
        } finally {
            Settings.CENTER_PROFILE_NAME.resetToDefault();
            Settings.CENTER_HOME_LOGO.resetToDefault();
        }
    }

    /**
     * Logo and titles at the start (#310) follows Center titles on other screens under Tab bar, starts
     * off and asks for a restart, since Home's logo is placed as the header draws.
     */
    @Test public void theTitlesAtStartSwitchFollowsTheCenteringOnesAndStartsOff() throws Exception {
        open();
        assertNull(row(Settings.TITLES_AT_START.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.TITLES_AT_START.key));
        controller.close();
        controller = null;

        open(PatchFamily.REELS_TAB);
        SwitchPreference toggle = (SwitchPreference) row(Settings.TITLES_AT_START.key);
        assertNotNull(toggle);
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), toggle);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int titles = -1;
        int start = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.CENTER_SCREEN_TITLES.key.equals(key)) titles = i;
            if (Settings.TITLES_AT_START.key.equals(key)) start = i;
        }
        assertEquals("right after Center titles on other screens", titles + 1, start);
        assertEquals("Logo and titles at the start", String.valueOf(toggle.getTitle()));
        assertTrue(String.valueOf(toggle.getSummary()), String.valueOf(toggle.getSummary()).contains("It wins over the centering switches above."));
        assertFalse(toggle.isChecked());
        assertEquals(Boolean.FALSE, Settings.TITLES_AT_START.defaultValue);
        assertTrue("Home's logo is placed as the header draws", Settings.TITLES_AT_START.rebootApp);
        assertTrue(PatchFamily.REELS_TAB.switches.contains(Settings.TITLES_AT_START));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.TITLES_AT_START.key));
    }

    @Test public void eachHeaderButtonHasASwitchUnderTabBarThatStartsOffAndAsksForARestart() throws Exception {
        open(PatchFamily.REELS_TAB);
        for (BooleanSetting setting : switches()) {
            SwitchPreference toggle = (SwitchPreference) row(setting.key);
            assertNotNull(setting.key, toggle);
            assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), toggle).getTitle()));
            assertFalse(setting.key, toggle.isChecked());
            assertTrue(setting.key, setting.rebootApp);
            assertTrue(setting.key, PatchFamily.REELS_TAB.switches.stream().anyMatch(each -> each.key.equals(setting.key)));
            assertTrue(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
        }
        assertEquals("Hide Create on Home's header", String.valueOf(row(Settings.HIDE_HOME_CREATE_BUTTON.key).getTitle()));
        assertEquals("Hide notifications on Home's header", String.valueOf(row(Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.key).getTitle()));
    }
}
