/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.widget.Button;
import android.widget.ListView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.SettingsLock.State;
import app.hushgram.extension.instagram.misc.FontChoice;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Lock these settings (#301) on the page: locked, every row that changes something is greyed out,
 * Pause included, while Debug logging, the exports and the lock itself stay open.
 * The rows come back when the wait is over and lock again when the open time ends.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsLockPageTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final long MINUTE = 60_000L;
    private final SettingsLockTest.Phone phone = new SettingsLockTest.Phone();

    @Before public void prepare() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        // Every row on one page, so a test reads them without opening a category.
        Settings.CATEGORY_PAGES.save(false);
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.FONT.resetToDefault();
        Settings.HIDDEN_SETTINGS_ROWS.resetToDefault();
        Settings.SPOOF_LOCATION.resetToDefault();
        Settings.SPOOF_LOCATION_PLACE.resetToDefault();
        ConfigurationBackup.forgetUndo();
        HushgramPreferenceFragment.importFeedback = null;
        BaseSettings.PAUSED.save(false);
        BaseSettings.DEBUG.save(false);
        PauseForTests.resume();
        SettingsLock.clocks = phone;
        ShadowToast.reset();
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        SettingsLock.clocks = SettingsLock.PHONE;
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.FONT.resetToDefault();
        Settings.HIDDEN_SETTINGS_ROWS.resetToDefault();
        Settings.SPOOF_LOCATION.resetToDefault();
        Settings.SPOOF_LOCATION_PLACE.resetToDefault();
        ConfigurationBackup.forgetUndo();
        HushgramPreferenceFragment.importFeedback = null;
        BaseSettings.PAUSED.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        PauseForTests.resume();
        Settings.CATEGORY_PAGES.resetToDefault();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
    }

    @Test public void theLockSitsRightUnderPause() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            List<Preference> rows = rowsOf(DownloadSettingsTest.pageIn(host));
            int pause = indexOfKey(rows, BaseSettings.PAUSED.key);
            assertTrue("no Pause row", pause >= 0);
            assertEquals(pause + 1, indexOfKey(rows, HushgramPreferenceFragment.LOCK_ROW_KEY));
            assertEquals("Lock these settings", String.valueOf(rows.get(pause + 1).getTitle()));
        }
    }

    @Test public void lockingGreysEveryChangeButLeavesTheExitsOpen() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            Preference lock = lockRow(page);
            tap(lock);
            ListView waits = latest().getListView();
            assertEquals(5, waits.getAdapter().getCount());
            assertEquals("1 hour", String.valueOf(waits.getAdapter().getItem(0)));
            assertEquals("24 hours", String.valueOf(waits.getAdapter().getItem(2)));
            assertEquals("72 hours", String.valueOf(waits.getAdapter().getItem(4)));
            pick(waits, 0);
            assertEquals(State.LOCKED, SettingsLock.state());
            assertEquals("Settings locked", ShadowToast.getTextOfLatestToast());

            assertFalse(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());
            assertFalse("Pause would undo the lock", row(page, BaseSettings.PAUSED.key).isEnabled());
            assertFalse("a settings file would undo the lock", page.importConfiguration.isEnabled());
            assertTrue(row(page, BaseSettings.DEBUG.key).isEnabled());
            assertTrue(page.exportConfiguration.isEnabled());
            assertTrue(lock.isEnabled());
            assertEquals("Ask to unlock", String.valueOf(lock.getTitle()));

            // Another refresh, the way a search or a switch's own rules run, keeps them greyed.
            page.searchSettings("reels");
            page.searchSettings("");
            assertFalse(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());
            assertFalse(row(page, BaseSettings.PAUSED.key).isEnabled());
        }
    }

    @Test public void askingOpensThePageAfterTheWaitAndItLocksAgainTenMinutesLater() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            assertTrue(SettingsLock.lock(1));
            page.showLock();
            Preference lock = lockRow(page);
            tap(lock);
            assertEquals(State.WAITING, SettingsLock.state());
            assertEquals("Unlock asked", String.valueOf(lock.getTitle()));
            assertTrue(ShadowToast.getTextOfLatestToast().startsWith("These settings open at "));

            // Setting the time of day forward doesn't open it.
            phone.wall += 2 * 60 * MINUTE;
            phone.pass(59 * MINUTE);
            page.showLock();
            assertFalse(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());

            phone.pass(MINUTE);
            page.showLock();
            assertEquals(State.OPEN, SettingsLock.state());
            assertEquals("Settings are open", String.valueOf(lock.getTitle()));
            assertTrue(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());
            assertTrue(row(page, BaseSettings.PAUSED.key).isEnabled());
            assertTrue(page.importConfiguration.isEnabled());

            phone.pass(SettingsLock.OPEN_MS);
            page.showLock();
            assertEquals(State.LOCKED, SettingsLock.state());
            assertEquals("Ask to unlock", String.valueOf(lock.getTitle()));
            assertFalse(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());
            assertEquals("the spent ask is dropped", "", Settings.SETTINGS_LOCK_ASKED.savedValue());
        }
    }

    @Test public void whileOpenTheLockCanBeTurnedOff() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            assertTrue(SettingsLock.lock(1));
            assertTrue(SettingsLock.ask());
            phone.pass(60 * MINUTE);
            page.showLock();
            tap(lockRow(page));
            ListView choices = latest().getListView();
            assertEquals(6, choices.getAdapter().getCount());
            assertEquals("Turn the lock off", String.valueOf(choices.getAdapter().getItem(5)));
            pick(choices, 5);
            assertEquals(State.OFF, SettingsLock.state());
            assertEquals("Lock turned off", ShadowToast.getTextOfLatestToast());
            assertEquals("Lock these settings", String.valueOf(lockRow(page).getTitle()));

            phone.pass(SettingsLock.OPEN_MS);
            page.showLock();
            assertTrue(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());
        }
    }

    @Test public void aRunningWaitCanBeStoppedAndTheSettingsStayLocked() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            assertTrue(SettingsLock.lock(72));
            assertTrue(SettingsLock.ask());
            phone.pass(60 * MINUTE);
            page.showLock();
            Preference lock = lockRow(page);
            assertTrue(String.valueOf(lock.getSummary()), String.valueOf(lock.getSummary()).endsWith("Tap to stop the wait."));

            // Keep waiting leaves the wait running.
            tap(lock);
            latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(State.WAITING, SettingsLock.state());

            tap(lock);
            AlertDialog asked = latest();
            assertEquals("Stop the wait", String.valueOf(asked.getButton(AlertDialog.BUTTON_POSITIVE).getText()));
            asked.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(State.LOCKED, SettingsLock.state());
            assertEquals("Wait stopped. These settings stay locked.", ShadowToast.getTextOfLatestToast());
            assertEquals("Ask to unlock", String.valueOf(lock.getTitle()));
            assertFalse(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());

            // The 71 hours the stopped wait had left don't open anything.
            phone.pass(72 * 60 * MINUTE);
            page.showLock();
            assertEquals(State.LOCKED, SettingsLock.state());
            assertFalse(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());
        }
    }

    @Test public void whilePausedTurningHushGramBackOnStaysOpen() {
        BaseSettings.PAUSED.save(true);
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            assertTrue(SettingsLock.lock(24));
            page.showLock();
            assertTrue(row(page, BaseSettings.PAUSED.key).isEnabled());
            assertFalse(row(page, Settings.HIDE_REELS_TOP_BAR.key).isEnabled());
        }
    }

    @Test public void aFolderEditorCannotSaveAfterTheClockExpiresBeforeTheRefresh() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            openTimedLock(page);
            HushgramPreferenceFragment.FolderRow folder =
                    (HushgramPreferenceFragment.FolderRow) row(page, Settings.SAVE_FOLDER.key);
            String before = Settings.SAVE_FOLDER.savedValue();
            folder.showDialog(null);
            AlertDialog editor = latest();
            folder.getEditText().setText("After the deadline");
            phone.pass(SettingsLock.OPEN_MS);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(before, Settings.SAVE_FOLDER.savedValue());
            assertEquals("These settings are locked. Ask to unlock first.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void aChoiceChangeListenerRefusesADifferentValueAfterTheClockExpires() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            HushgramPreferenceFragment.FontRow font =
                    (HushgramPreferenceFragment.FontRow) row(page, Settings.FONT.key);
            assertEquals("Your phone's font", String.valueOf(font.getEntries()[1]));
            assertEquals(FontChoice.SYSTEM.name(), String.valueOf(font.getEntryValues()[1]));
            assertTrue(font.getOnPreferenceChangeListener().onPreferenceChange(font, FontChoice.SYSTEM.name()));
            font.setValue(FontChoice.SYSTEM.name());
            ShadowLooper.idleMainLooper();
            assertEquals("the accepted control must change the stored value", FontChoice.SYSTEM, Settings.FONT.savedValue());
            font.setValue(FontChoice.INSTAGRAM.name());
            ShadowLooper.idleMainLooper();
            assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
            openTimedLock(page);
            phone.pass(SettingsLock.OPEN_MS);
            boolean accepted = font.getOnPreferenceChangeListener().onPreferenceChange(font, FontChoice.SYSTEM.name());
            if (accepted) font.setValue(FontChoice.SYSTEM.name());
            ShadowLooper.idleMainLooper();
            assertFalse("the native preference close must not be authorized", accepted);
            assertEquals(FontChoice.INSTAGRAM, Settings.FONT.savedValue());
            assertEquals(FontChoice.INSTAGRAM.name(), font.getValue());
            assertEquals("These settings are locked. Ask to unlock first.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void aValidatedPlaceCannotSaveAfterTheClockExpiresBeforeTheRefresh() {
        Settings.SPOOF_LOCATION.save(true);
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            openTimedLock(page);
            HushgramPreferenceFragment.PlaceRow place =
                    (HushgramPreferenceFragment.PlaceRow) row(page, Settings.SPOOF_LOCATION_PLACE.key);
            assertTrue(place.isEnabled());
            String before = Settings.SPOOF_LOCATION_PLACE.savedValue();
            place.showDialog(null);
            place.getEditText().setText("40.758, -73.9855");
            phone.pass(SettingsLock.OPEN_MS);
            latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(before, Settings.SPOOF_LOCATION_PLACE.savedValue());
            assertEquals("These settings are locked. Ask to unlock first.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void aMultichoiceCannotSaveAfterTheClockExpiresBeforeTheRefresh() {
        Settings.HIDDEN_SETTINGS_ROWS.save("ARCHIVE");
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            openTimedLock(page);
            tap(row(page, Settings.HIDDEN_SETTINGS_ROWS.key));
            AlertDialog dialog = latest();
            ListView choices = dialog.getListView();
            assertNotNull(choices);
            assertFalse(choices.isItemChecked(0));
            phone.pass(SettingsLock.OPEN_MS);
            pick(choices, 0);
            assertEquals("ARCHIVE", Settings.HIDDEN_SETTINGS_ROWS.savedValue());
            assertTrue("a refused choice must not remain visibly changed",
                    !dialog.isShowing() || !choices.isItemChecked(0));
            assertEquals("These settings are locked. Ask to unlock first.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void relockingClosesAnOpenMutatingEditorAndItsRetainedSaveCannotRun() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            openTimedLock(page);
            HushgramPreferenceFragment.FolderRow folder =
                    (HushgramPreferenceFragment.FolderRow) row(page, Settings.SAVE_FOLDER.key);
            String before = Settings.SAVE_FOLDER.savedValue();
            folder.showDialog(null);
            AlertDialog editor = latest();
            Button retainedSave = editor.getButton(AlertDialog.BUTTON_POSITIVE);
            folder.getEditText().setText("After the deadline");
            phone.pass(SettingsLock.OPEN_MS);
            page.showLock();
            assertFalse("the locked settings left a mutating editor above them", editor.isShowing());
            retainedSave.performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(before, Settings.SAVE_FOLDER.savedValue());
        }
    }

    @Test public void anImportCrossingTheProviderCloseCannotApplyAfterTheSettingsRelock() throws Exception {
        Settings.HIDE_ADS.save(false);
        byte[] file = new JSONObject().put("project", "HushGram").put("schema", 1)
                .put("settings", new JSONObject().put(Settings.HIDE_ADS.key,
                        new JSONObject().put("type", "boolean").put("value", true)))
                .toString().getBytes(StandardCharsets.UTF_8);
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            openTimedLock(page);
            page.pickConfiguration(true);
            ShadowActivity.IntentForResult picked = Shadows.shadowOf(host.get()).getNextStartedActivityForResult();
            assertNotNull(picked);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
            Uri document = Uri.parse("content://test-documents/locked-settings.json");
            CloseGate input = new CloseGate(file);
            try {
                Shadows.shadowOf(host.get().getContentResolver()).registerInputStream(document, input);
                Shadows.shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK,
                        new Intent().setData(document));
                assertTrue("the settings reader never reached provider close", input.entered.await(10, TimeUnit.SECONDS));
                phone.pass(SettingsLock.OPEN_MS);
            } finally {
                input.release.countDown();
            }
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertFalse(Settings.HIDE_ADS.savedValue());
            assertFalse("a held import must not create an Undo", ConfigurationBackup.canUndo());
            assertFalse(page.restartActionAvailable());
            assertEquals("These settings are locked. Ask to unlock first.",
                    HushgramPreferenceFragment.importFeedback);
        }
    }

    @Test public void aLockedPageStillExportsItsSettings() throws Exception {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            assertTrue(SettingsLock.lock(1));
            page.showLock();
            assertTrue(page.exportConfiguration.isEnabled());
            page.pickConfiguration(false);
            ShadowActivity.IntentForResult picked = Shadows.shadowOf(host.get()).getNextStartedActivityForResult();
            assertNotNull(picked);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picked.intent.getAction());
            Uri document = Uri.parse("content://test-documents/locked-export.json");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Shadows.shadowOf(host.get().getContentResolver()).registerOutputStream(document, output);
            Shadows.shadowOf(host.get()).receiveResult(picked.intent, Activity.RESULT_OK,
                    new Intent().setData(document));
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("HushGram", new JSONObject(output.toString(StandardCharsets.UTF_8.name())).getString("project"));
            assertEquals(State.LOCKED, SettingsLock.state());
        }
    }

    private void openTimedLock(HushgramPreferenceFragment page) {
        assertTrue(SettingsLock.lock(1));
        assertTrue(SettingsLock.ask());
        phone.pass(60 * MINUTE);
        page.showLock();
        assertEquals(State.OPEN, SettingsLock.state());
    }

    private static final class CloseGate extends ByteArrayInputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        CloseGate(byte[] bytes) { super(bytes); }

        @Override public void close() throws IOException {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("the provider close was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            super.close();
        }
    }

    private static Preference lockRow(HushgramPreferenceFragment page) {
        return row(page, HushgramPreferenceFragment.LOCK_ROW_KEY);
    }

    private static Preference row(HushgramPreferenceFragment page, String key) {
        for (Preference row : rowsOf(page)) {
            if (key.equals(row.getKey())) return row;
        }
        Preference found = page.findPreference(key);
        assertNotNull("no row " + key, found);
        return found;
    }

    private static void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    private static AlertDialog latest() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no dialog", dialog);
        return dialog;
    }

    private static void pick(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }

    private static List<Preference> rowsOf(HushgramPreferenceFragment page) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        return rows;
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) collect((PreferenceGroup) preference, rows);
            else rows.add(preference);
        }
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }
}
