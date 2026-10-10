/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide About this reel and Hide Ask Meta AI in About this reel (#42): two switches of their own
 * under Meta AI, after the search and posts switches, off to start, off while paused, and carried
 * by an exported configuration.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class AboutThisReelSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        Settings.HIDE_ABOUT_THIS_REEL.resetToDefault();
        Settings.HIDE_ASK_META_AI.resetToDefault();
        Settings.HIDE_META_AI_SHARE_TARGET.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_ABOUT_THIS_REEL.resetToDefault();
        Settings.HIDE_ASK_META_AI.resetToDefault();
        Settings.HIDE_META_AI_SHARE_TARGET.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = DownloadSettingsTest.pageIn(controller);
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void withoutHideMetaAiThereAreNoSwitches() throws Exception {
        open(EnumSet.of(PatchFamily.HIDE_ADS));
        assertNull(page.findPreference(Settings.HIDE_ABOUT_THIS_REEL.key));
        assertNull(page.findPreference(Settings.HIDE_ASK_META_AI.key));
    }

    @Test public void bothSwitchesSitUnderMetaAiAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.META_AI));
        SwitchPreference about = (SwitchPreference) page.findPreference(Settings.HIDE_ABOUT_THIS_REEL.key);
        SwitchPreference ask = (SwitchPreference) page.findPreference(Settings.HIDE_ASK_META_AI.key);
        assertNotNull(about);
        assertNotNull(ask);
        assertEquals("Hide About this reel", String.valueOf(about.getTitle()));
        assertEquals("Removes the summary, Sources and Ask Meta AI box from a reel's more menu. In your feed the "
                + "audio row goes too. Other options stay.", String.valueOf(about.getSummary()));
        assertEquals("Hide Ask Meta AI in About this reel", String.valueOf(ask.getTitle()));
        assertEquals("About this reel keeps its summary and Sources, and only the Ask Meta AI box under them goes.",
                String.valueOf(ask.getSummary()));
        assertFalse(about.isChecked());
        assertFalse(ask.isChecked());

        PreferenceGroup section = about.getParent();
        assertEquals("Meta AI", String.valueOf(section.getTitle()));
        assertEquals(section, ask.getParent());
        String[] keys = new String[section.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) {
            Preference row = section.getPreference(i);
            keys[i] = row.getKey();
        }
        assertEquals(Arrays.asList(Settings.HIDE_META_AI_SEARCH.key, Settings.HIDE_META_AI_POSTS.key,
                Settings.HIDE_ABOUT_THIS_REEL.key, Settings.HIDE_ASK_META_AI.key, Settings.HIDE_META_AI_SHARE_TARGET.key,
                Settings.HIDE_META_AI_PROFILE_BANNER.key, Settings.HIDE_META_AI_BLEND.key),
                Arrays.asList(keys));
        SwitchPreference share = (SwitchPreference) page.findPreference(Settings.HIDE_META_AI_SHARE_TARGET.key);
        assertEquals("Hide Meta AI in the share sheet", String.valueOf(share.getTitle()));
        assertEquals("Removes Meta AI from the row at the bottom of the share sheet. Some accounts see it as Muse.",
                String.valueOf(share.getSummary()));
        assertFalse("the share sheet switch starts off", share.isChecked());
        SwitchPreference banner = (SwitchPreference) page.findPreference(Settings.HIDE_META_AI_PROFILE_BANNER.key);
        assertEquals("Hide the Muse card on profiles", String.valueOf(banner.getTitle()));
        assertEquals("Removes the Meet Muse card, with its Try Muse button, from profiles. Your other profile cards stay.",
                String.valueOf(banner.getSummary()));
        assertTrue("the Muse card switch starts on", banner.isChecked());
        SwitchPreference blend = (SwitchPreference) page.findPreference(Settings.HIDE_META_AI_BLEND.key);
        assertEquals("Hide Blend on reels", String.valueOf(blend.getTitle()));
        assertEquals("Removes the Blend invite button from reels. Everything else on a reel stays.",
                String.valueOf(blend.getSummary()));
        assertFalse("the Blend switch starts off", blend.isChecked());
    }

    /** Each switch reaches only its own setting. */
    @Test public void theSwitchesAreIndependent() throws Exception {
        open(EnumSet.of(PatchFamily.META_AI));
        SwitchPreference about = (SwitchPreference) page.findPreference(Settings.HIDE_ABOUT_THIS_REEL.key);
        SwitchPreference ask = (SwitchPreference) page.findPreference(Settings.HIDE_ASK_META_AI.key);
        about.setChecked(true);
        assertTrue(Settings.HIDE_ABOUT_THIS_REEL.get());
        assertFalse(Settings.HIDE_ASK_META_AI.get());
        about.setChecked(false);
        ask.setChecked(true);
        assertFalse(Settings.HIDE_ABOUT_THIS_REEL.get());
        assertTrue(Settings.HIDE_ASK_META_AI.get());
    }

    @Test public void pauseTurnsThemOffAndExportCarriesThem() {
        for (BooleanSetting setting : new BooleanSetting[] {Settings.HIDE_ABOUT_THIS_REEL, Settings.HIDE_ASK_META_AI,
                Settings.HIDE_META_AI_SHARE_TARGET}) {
            assertTrue(setting.key, PatchFamily.META_AI.switches.contains(setting));
            assertFalse(setting.key, setting.rebootApp);
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.META_AI);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_ABOUT_THIS_REEL.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_ASK_META_AI.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_META_AI_SHARE_TARGET.key));

        Settings.HIDE_ABOUT_THIS_REEL.save(true);
        Settings.HIDE_ASK_META_AI.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_ABOUT_THIS_REEL.get());
        assertFalse(Settings.HIDE_ASK_META_AI.get());
        assertTrue(Settings.HIDE_ABOUT_THIS_REEL.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_ABOUT_THIS_REEL.get());
        assertTrue(Settings.HIDE_ASK_META_AI.get());
    }
}
