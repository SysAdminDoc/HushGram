/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.widget.ListView;

import java.util.Collections;
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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Buttons in the message box row: under Messages on its own, every button ticked to start, the text box and Send never offered. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class MessageBoxSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.MESSAGE_BOX) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }

    private Preference row() {
        return page.getPreferenceScreen().findPreference(Settings.HIDDEN_MESSAGE_BOX_BUTTONS.key);
    }

    @Test public void missingPatchHasNoMessageBoxRow() throws Exception {
        open(false);
        assertNull(row());
    }

    /** The row alone opens Messages, offers the three buttons all ticked, and never the text box or Send. */
    @Test public void everyButtonStartsTicked() throws Exception {
        open(true);
        Preference row = row();
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertEquals("Buttons in the message box", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Every button in the message box shows"));
        assertEquals(Collections.singletonList(Settings.HIDDEN_MESSAGE_BOX_BUTTONS), PatchFamily.MESSAGE_BOX.choices());
        assertEquals("", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_MESSAGE_BOX_BUTTONS.key));

        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = dialog.getListView();
        assertEquals(3, list.getCount());
        assertEquals("Gallery", list.getItemAtPosition(0).toString());
        assertEquals("Stickers", list.getItemAtPosition(1).toString());
        assertEquals("Voice message", list.getItemAtPosition(2).toString());
        for (int i = 0; i < 3; i++) assertTrue("button " + i + " ticked", list.isItemChecked(i));
        assertNotNull(dialog.getButton(AlertDialog.BUTTON_NEUTRAL));
        assertEquals("Show all", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
    }

    /** Unticking saves at once under the button's name, and ticking it again puts it back. */
    @Test public void aChangeSavesAtOnceByKey() throws Exception {
        open(true);
        Preference row = row();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();

        tap(list, 1);
        assertEquals("STICKER", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
        tap(list, 0);
        assertEquals("GALLERY,STICKER", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Left off the message box: "));
        tap(list, 1);
        assertEquals("GALLERY", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
        tap(list, 0);
        assertEquals("", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
    }

    /** Show all ticks every button and clears the choice without closing the list. */
    @Test public void showAllPutsEveryButtonBack() throws Exception {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("GALLERY,VOICE_MESSAGE");
        open(true);
        Preference row = row();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = dialog.getListView();
        assertFalse(list.isItemChecked(0));
        assertTrue(list.isItemChecked(1));
        assertFalse(list.isItemChecked(2));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
        for (int i = 0; i < 3; i++) assertTrue("button " + i + " ticked", list.isItemChecked(i));
        assertTrue(dialog.isShowing());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Every button in the message box shows"));
    }

    /** A saved choice shows unticked, and Pause answers Instagram's own without touching the choice. */
    @Test public void aSavedChoiceShowsAndPauseKeepsIt() throws Exception {
        Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("VOICE_MESSAGE");
        open(true);
        Preference row = row();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();
        assertTrue(list.isItemChecked(0));
        assertTrue(list.isItemChecked(1));
        assertFalse(list.isItemChecked(2));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
        assertEquals("VOICE_MESSAGE", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals("VOICE_MESSAGE", Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
    }
}
