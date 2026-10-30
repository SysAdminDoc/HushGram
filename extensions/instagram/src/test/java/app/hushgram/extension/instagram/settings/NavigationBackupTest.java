/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static app.hushgram.extension.instagram.settings.ConfigurationBackupTest.entry;
import static app.hushgram.extension.instagram.settings.ConfigurationBackupTest.file;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class NavigationBackupTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    @Before public void setup() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37; // Instagram 453's target on every runtime.
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        ConfigurationBackup.forgetUndo();
    }

    @After public void restore() {
        PauseForTests.resume();
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        ConfigurationBackup.forgetUndo();
        PatchFamily.inBuildForTests = null;
    }

    @Test public void everyTabRoundTripsEvenWithoutAnOptionalPatch() throws Exception {
        for (NavigationTarget target : NavigationTarget.values()) {
            Settings.NAVIGATION_SETTINGS_TARGET.save(target);
            byte[] exported = ConfigurationBackup.export();
            JSONObject saved = new JSONObject(new String(exported, StandardCharsets.UTF_8))
                    .getJSONObject("settings").getJSONObject(Settings.NAVIGATION_SETTINGS_TARGET.key);
            assertEquals("enum", saved.getString("type"));
            assertEquals(target.name(), saved.getString("value"));
            Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
            ConfigurationBackup.Result result = ConfigurationBackup.restore(exported);
            assertEquals(0, result.skipped);
            assertEquals(target, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
            // The open settings page hands a restored choice to its row, which rebinds the tabs at
            // once (#82), so a restore never asks for a restart.
            assertFalse(result.restart);
            assertFalse(ConfigurationBackup.restore(exported).restart);
        }
    }

    @Test public void pauseExportsTheSavedChoiceAndUndoRestoresIt() throws Exception {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.FEED);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        byte[] exported = ConfigurationBackup.export();
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.CLIPS);
        assertFalse(ConfigurationBackup.restore(exported).restart);
        assertEquals(NavigationTarget.FEED, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
        assertEquals(NavigationTarget.OFF, Settings.NAVIGATION_SETTINGS_TARGET.get());
        assertFalse(ConfigurationBackup.undo().restart);
        assertEquals(NavigationTarget.CLIPS, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
    }

    @Test public void undoKeepsALaterNavigationChoice() throws Exception {
        ConfigurationBackup.restore(file(entry(Settings.NAVIGATION_SETTINGS_TARGET.key, "enum", "FEED")));
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.CLIPS);
        ConfigurationBackup.Result undone = ConfigurationBackup.undo();
        assertNotNull(undone);
        assertEquals(1, undone.skipped);
        assertFalse(undone.restart);
        assertEquals(NavigationTarget.CLIPS, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
    }

    @Test public void olderFilesLeaveTheNewChoiceAlone() throws Exception {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.FEED);
        assertFalse(ConfigurationBackup.restore(file("")).restart);
        assertEquals(NavigationTarget.FEED, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
    }

    /** A tab a newer HushGram knows is skipped and counted, like any choice this version can't use, and changes nothing. */
    @Test public void unknownTabIsSkippedAndCannotApplyOrOfferUndo() throws Exception {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.FEED);
        ConfigurationBackup.Result result = ConfigurationBackup.restore(
                file(entry(Settings.NAVIGATION_SETTINGS_TARGET.key, "enum", "FUTURE_TAB")));
        assertEquals("nothing applied", 0, result.applied);
        assertEquals("the tab counted as skipped", 1, result.skipped);
        assertFalse(result.restart);
        assertEquals(NavigationTarget.FEED, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
        assertFalse(ConfigurationBackup.canUndo());
    }
}
