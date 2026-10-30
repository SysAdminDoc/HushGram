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
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;

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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.misc.AppIconChoice;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** App icon (#284) is a list at the end of Layout, starts on Instagram's own, says when it shows, keeps its pick through Pause and is backed up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class AppIconSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        Settings.APP_ICON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.APP_ICON.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void itEndsLayoutAndStartsOnInstagramsOwn() throws Exception {
        open(EnumSet.of(PatchFamily.LAUNCH_SCREEN, PatchFamily.APP_ICON));
        Preference found = page.getPreferenceScreen().findPreference(Settings.APP_ICON.key);
        assertTrue(found instanceof HushgramPreferenceFragment.AppIconRow);
        ListPreference row = (ListPreference) found;
        assertEquals("App icon", row.getTitle().toString());
        PreferenceGroup layout = (PreferenceGroup) row.getParent();
        assertEquals("Layout", layout.getTitle().toString());
        assertEquals("after the launch screen, last", Settings.APP_ICON.key,
                layout.getPreference(layout.getPreferenceCount() - 1).getKey());
        assertEquals(Settings.LAUNCH_SCREEN.key, layout.getPreference(layout.getPreferenceCount() - 2).getKey());

        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(entry.toString());
        assertEquals(Arrays.asList("Instagram's own", "HushGram", "Instagram on black", "Instagram on plum",
                "Instagram in black on white", "Hand-drawn Instagram"), entries);
        assertEquals(AppIconChoice.INSTAGRAM, Settings.APP_ICON.get());
        assertEquals("INSTAGRAM", row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Instagram's own. "));

        row.setValue("PLUM");
        ShadowLooper.idleMainLooper();
        assertEquals(AppIconChoice.PLUM, Settings.APP_ICON.savedValue());
        assertEquals("Instagram on plum. It shows once you leave Instagram. If the icon is gone from your home screen, "
                + "add it again from the app drawer.", row.getSummary().toString());
        row.setValue("LIGHT");
        ShadowLooper.idleMainLooper();
        assertEquals(AppIconChoice.LIGHT, Settings.APP_ICON.savedValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Instagram in black on white. "));
        row.setValue("SKETCH");
        ShadowLooper.idleMainLooper();
        assertEquals(AppIconChoice.SKETCH, Settings.APP_ICON.savedValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Hand-drawn Instagram. "));

        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.APP_ICON.key));
    }

    @Test public void onlyItsOwnPatchBringsTheLayoutCategory() throws Exception {
        open(EnumSet.of(PatchFamily.APP_ICON));
        Preference row = page.getPreferenceScreen().findPreference(Settings.APP_ICON.key);
        assertNotNull(row);
        assertEquals("Layout", row.getParent().getTitle().toString());
    }

    @Test public void searchFindsItUnderItsPatch() throws Exception {
        open(EnumSet.of(PatchFamily.APP_ICON));
        Preference row = page.getPreferenceScreen().findPreference(Settings.APP_ICON.key);
        page.searchSettings("pick the app icon");
        assertNotNull("the patch's name finds it", row.getParent());
        page.searchSettings("app icon");
        assertNotNull("its own name finds it", row.getParent());
        page.searchSettings("burn-in");
        assertNull(row.getParent());
        page.searchSettings("");
        assertNotNull(row.getParent());
    }

    /** Changing the icon can take it off the home screen, so Pause doesn't change it. */
    @Test public void pauseKeepsThePick() {
        Settings.APP_ICON.save(AppIconChoice.HUSHGRAM);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(AppIconChoice.HUSHGRAM, Settings.APP_ICON.get());
    }

    @Test public void withoutThePatchThereIsNoRowAndNothingToBackUp() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.APP_ICON.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.APP_ICON.key));
    }
}
