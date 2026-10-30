/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.biometrics.BiometricManager;
import android.widget.EditText;

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
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowBiometricManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.direct.MessagesLockForTests;
import app.hushgram.extension.instagram.direct.PinLock;
import app.hushgram.extension.instagram.direct.UnlockWith;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.FailingStore;
import app.hushgram.extension.shared.settings.Setting;

/** Authentication permits the change, but its saved value still decides what the row shows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SettingsDeferredSaveTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void prepare() {
        resetLocks();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.MESSAGES_LOCK);
    }

    @After public void restore() {
        resetLocks();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
    }

    @Test public void aSavedPinDoesNotSelectPinUnlockWhenThatChoiceFailsToSave() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            HushgramPreferenceFragment.UnlockWithRow row =
                    (HushgramPreferenceFragment.UnlockWithRow) page.findPreference(Settings.UNLOCK_WITH.key);
            assertNotNull(row);
            assertEquals(UnlockWith.PHONE_LOCK, Settings.UNLOCK_WITH.savedValue());
            AlertDialog form = choosePin(row);
            try (FailingStore store = FailingStore.loseNextStringCommit(Settings.UNLOCK_WITH.key, UnlockWith.PIN.name())) {
                form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                ShadowLooper.idleMainLooper();
                assertTrue("the deferred PIN unlock choice was attempted and refused", store.refusedStringCommit.get());
            }
            assertFalse(form.isShowing());
            assertTrue("the successfully saved PIN remains available", PinLock.matches("5678"));
            assertEquals(UnlockWith.PHONE_LOCK, Settings.UNLOCK_WITH.savedValue());
            assertEquals(UnlockWith.PHONE_LOCK, Setting.preferences.getEnum(Settings.UNLOCK_WITH.key, UnlockWith.PHONE_LOCK));
            assertEquals(UnlockWith.PHONE_LOCK.name(), row.getValue());
            assertEquals("The setting couldn't finish updating. Its saved value is shown.", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void aSavedPinSelectsPinUnlockWhenTheChoiceCanBeSaved() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
            HushgramPreferenceFragment.UnlockWithRow row =
                    (HushgramPreferenceFragment.UnlockWithRow) page.findPreference(Settings.UNLOCK_WITH.key);
            assertNotNull(row);
            assertEquals(UnlockWith.PHONE_LOCK, Settings.UNLOCK_WITH.savedValue());
            AlertDialog form = choosePin(row);
            form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(form.isShowing());
            assertTrue(PinLock.matches("5678"));
            assertEquals(UnlockWith.PIN, Settings.UNLOCK_WITH.savedValue());
            assertEquals(UnlockWith.PIN, Setting.preferences.getEnum(Settings.UNLOCK_WITH.key, UnlockWith.PHONE_LOCK));
            assertEquals(UnlockWith.PIN.name(), row.getValue());
        }
    }

    /**
     * Fingerprint or face only is refused, with the reason, while the phone has none set up, and
     * saved once it has one. Its summary says the screen lock takes over if they're removed.
     */
    @Config(sdk = 37)
    @Test public void fingerprintOrFaceOnlyWaitsForOneToBeSetUp() {
        ShadowBiometricManager biometrics = Shadow.extract(
                RuntimeEnvironment.getApplication().getSystemService(BiometricManager.class));
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HushgramPreferenceFragment.UnlockWithRow row = unlockWith(host);
            assertEquals(Arrays.asList(UnlockWith.PHONE_LOCK.name(), UnlockWith.PIN.name(), UnlockWith.BIOMETRICS.name()),
                    Arrays.asList(row.getEntryValues()));

            biometrics.setCanAuthenticate(true);
            biometrics.setAuthenticatorType(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED);
            assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, UnlockWith.BIOMETRICS.name()));
            ShadowLooper.idleMainLooper();
            assertEquals("Add a fingerprint or face in your phone's settings first.", ShadowToast.getTextOfLatestToast());
            assertEquals(UnlockWith.PHONE_LOCK, Settings.UNLOCK_WITH.savedValue());
            assertEquals(UnlockWith.PHONE_LOCK.name(), row.getValue());

            biometrics.setAuthenticatorType(0);
            row.getOnPreferenceChangeListener().onPreferenceChange(row, UnlockWith.BIOMETRICS.name());
            ShadowLooper.idleMainLooper();
            assertEquals(UnlockWith.BIOMETRICS, Settings.UNLOCK_WITH.savedValue());
            assertEquals(UnlockWith.BIOMETRICS.name(), row.getValue());
            assertEquals("Only your fingerprint or face unlocks your messages. If the phone has none set up, your screen lock does.",
                    String.valueOf(row.getSummary()));
        } finally {
            ShadowBiometricManager.reset();
        }
    }

    /** Android 9 and 10 can't leave the screen lock out, so the row offers only the first two. */
    @Config(sdk = 28)
    @Test public void beforeAndroid11ThereAreTwoChoices() {
        try (ActivityController<Activity> host = Robolectric.buildActivity(Activity.class).setup().visible()) {
            assertEquals(Arrays.asList(UnlockWith.PHONE_LOCK.name(), UnlockWith.PIN.name()),
                    Arrays.asList(unlockWith(host).getEntryValues()));
        }
    }

    private static HushgramPreferenceFragment.UnlockWithRow unlockWith(ActivityController<Activity> host) {
        HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(host);
        HushgramPreferenceFragment.UnlockWithRow row =
                (HushgramPreferenceFragment.UnlockWithRow) page.findPreference(Settings.UNLOCK_WITH.key);
        assertNotNull(row);
        return row;
    }

    private static AlertDialog choosePin(HushgramPreferenceFragment.UnlockWithRow row) {
        assertFalse(PinLock.isSet());
        assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, UnlockWith.PIN.name()));
        // OnShow installs the validation and persistence handler after the native dialog is shown.
        ShadowLooper.idleMainLooper();
        AlertDialog form = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(form);
        assertTrue(form.isShowing());
        ((EditText) form.getWindow().getDecorView().findViewWithTag("hushgram_pin_field")).setText("5678");
        ((EditText) form.getWindow().getDecorView().findViewWithTag("hushgram_pin_confirm")).setText("5678");
        return form;
    }

    private static void resetLocks() {
        MessagesLockForTests.reset();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCK_APP.resetToDefault();
        Settings.UNLOCK_WITH.resetToDefault();
        Settings.LOCK_PIN.resetToDefault();
        Settings.LOCK_PIN_MISSES.resetToDefault();
        Settings.LOCK_PIN_WAIT_UNTIL.resetToDefault();
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
    }
}
