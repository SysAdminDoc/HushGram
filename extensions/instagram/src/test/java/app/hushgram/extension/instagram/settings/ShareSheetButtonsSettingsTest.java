/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.widget.ListView;

import java.util.EnumSet;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.share.ShareSheetActions;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The share sheet's button choice rides in a backup only in a build with the share sheet patch (#252). */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class ShareSheetButtonsSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> controller;

    @After
    public void putBack() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    @Test public void everyButtonShowsToStart() {
        assertEquals("", Settings.HIDDEN_SHARE_SHEET_BUTTONS.get());
        assertTrue(PatchFamily.SHARE_SHEET.choices().contains(Settings.HIDDEN_SHARE_SHEET_BUTTONS));
    }

    @Test public void theChoiceIsInABackupOnlyWithThePatch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SHARE_SHEET);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_SHARE_SHEET_BUTTONS.key));
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_SHARE_SHEET_BUTTONS.key));
    }

    @Test public void onlyAKnownListIsTakenFromABackup() {
        assertTrue(ConfigurationBackup.textAllowed(Settings.HIDDEN_SHARE_SHEET_BUTTONS, "add_to_your_story,whatsapp"));
        assertTrue(ConfigurationBackup.textAllowed(Settings.HIDDEN_SHARE_SHEET_BUTTONS, ""));
        assertFalse(ConfigurationBackup.textAllowed(Settings.HIDDEN_SHARE_SHEET_BUTTONS, "whatsapp,nonsense"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.HIDDEN_SHARE_SHEET_BUTTONS, "whatsapp,add_to_your_story"));
    }

    /**
     * Paused, Instagram is answered every button, but the row and its list still show what was
     * unticked, so one more tick adds to the choice instead of saving over it.
     */
    @Test public void pausedTheRowAndListKeepTheChoice() throws Exception {
        Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("whatsapp,download_media");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        assertEquals("", Settings.HIDDEN_SHARE_SHEET_BUTTONS.get());
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SHARE_SHEET);
        controller = Robolectric.buildActivity(Activity.class).setup();
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();

        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDDEN_SHARE_SHEET_BUTTONS.key);
        String summary = row.getSummary().toString();
        assertTrue(summary, summary.startsWith("Left off the share sheet: ") && summary.contains("WhatsApp") && summary.contains("Download"));
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();
        int whatsapp = ShareSheetActions.ACTIONS.indexOf("whatsapp");
        int download = ShareSheetActions.ACTIONS.indexOf("download_media");
        int sms = ShareSheetActions.ACTIONS.indexOf("user_sms");
        assertFalse(list.isItemChecked(whatsapp));
        assertFalse(list.isItemChecked(download));
        assertTrue(list.isItemChecked(sms));

        assertTrue(list.performItemClick(list.getAdapter().getView(sms, null, list), sms, list.getItemIdAtPosition(sms)));
        ShadowLooper.idleMainLooper();
        assertEquals("whatsapp,download_media,user_sms", Settings.HIDDEN_SHARE_SHEET_BUTTONS.savedValue());
    }
}
