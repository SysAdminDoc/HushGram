/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.preference.Preference;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.misc.DeveloperOptions;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/** The manual native action must preserve settings on failure and close only after success. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = OverrideNavigationTest.NativeOptions.class)
@SuppressWarnings("deprecation")
public class OverrideNavigationTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        NativeOptions.result = 0;
        NativeOptions.failure = false;
        NativeOptions.calls = 0;
        NativeOptions.host = null;
        NativeOptions.whitehat = 0;
        Settings.OPEN_DEVELOPER_OPTIONS.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.OPEN_DEVELOPER_OPTIONS.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.DEVELOPER_OPTIONS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), SettingsEntry.DIALOG_TAG);
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        Utils.awaitBackgroundTasksForTests();
    }

    private Preference row() { return page.findPreference("hushgram_open_overrides"); }
    private Preference whitehat() { return page.findPreference("hushgram_open_whitehat"); }
    private void tap() { tap(row()); }
    private void tap(Preference row) { assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row)); ShadowLooper.idleMainLooper(); }

    @Test public void missingPatchHasNoNativeAction() throws Exception {
        open(false);
        assertNull(row());
        assertEquals(0, NativeOptions.calls);
    }

    @Test public void unavailableSessionOrNavigationKeepsTheDialogAndShowsRecovery() throws Exception {
        open(true);
        tap();
        assertTrue(dialog.isAdded());
        assertFalse(row().isPersistent());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("signed in"));
        // The toast is cut to two lines on a phone; the row keeps the whole reason.
        assertTrue(String.valueOf(row().getSummary()), String.valueOf(row().getSummary()).endsWith("from Home while signed in."));
        assertSame(controller.get(), NativeOptions.host);
        NativeOptions.failure = true;
        tap();
        assertTrue(dialog.isAdded());
        assertEquals(2, NativeOptions.calls);
        assertFalse(Settings.OPEN_DEVELOPER_OPTIONS.get());
    }

    @Test public void successfulNavigationClosesTheDialogWithoutEnablingLongPress() throws Exception {
        open(true);
        NativeOptions.result = 1;
        tap();
        controller.get().getFragmentManager().executePendingTransactions();
        assertFalse(dialog.isAdded());
        assertEquals(1, NativeOptions.calls);
        assertFalse(Settings.OPEN_DEVELOPER_OPTIONS.get());
    }

    @Test public void finishingDestroyedOrSavedHostsNeverCallNativeNavigation() throws Exception {
        assertFalse(DeveloperOptions.openOverrides(null));
        open(true);
        controller.saveInstanceState(new android.os.Bundle());
        assertFalse(DeveloperOptions.openOverrides(controller.get()));
        controller.get().finish();
        assertFalse(DeveloperOptions.openOverrides(controller.get()));
        controller.pause().stop().destroy();
        assertFalse(DeveloperOptions.openOverrides(controller.get()));
        assertEquals(0, NativeOptions.calls);
    }

    @Test public void missingPatchHasNoWhitehatAction() throws Exception {
        open(false);
        assertNull(whitehat());
        assertEquals(0, NativeOptions.whitehat);
    }

    @Test public void unavailableWhitehatKeepsTheDialogAndShowsRecovery() throws Exception {
        open(true);
        assertFalse(whitehat().isPersistent());
        assertTrue(String.valueOf(whitehat().getSummary()).contains("24 hours"));
        tap(whitehat());
        assertTrue(dialog.isAdded());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("signed in"));
        assertTrue(String.valueOf(whitehat().getSummary()), String.valueOf(whitehat().getSummary()).startsWith("Whitehat settings are unavailable"));
        assertSame(controller.get(), NativeOptions.host);
        NativeOptions.failure = true;
        tap(whitehat());
        assertTrue(dialog.isAdded());
        assertEquals(2, NativeOptions.whitehat);
        assertEquals("the MetaConfig editor isn't asked", 0, NativeOptions.calls);
    }

    @Test public void openedWhitehatClosesTheDialogWithoutEnablingLongPress() throws Exception {
        open(true);
        NativeOptions.result = 1;
        tap(whitehat());
        controller.get().getFragmentManager().executePendingTransactions();
        assertFalse(dialog.isAdded());
        assertEquals(1, NativeOptions.whitehat);
        assertEquals(0, NativeOptions.calls);
        assertFalse(Settings.OPEN_DEVELOPER_OPTIONS.get());
    }

    @Test public void finishingDestroyedOrSavedHostsNeverOpenWhitehat() throws Exception {
        assertFalse(DeveloperOptions.openWhitehat(null));
        open(true);
        controller.saveInstanceState(new android.os.Bundle());
        assertFalse(DeveloperOptions.openWhitehat(controller.get()));
        controller.get().finish();
        assertFalse(DeveloperOptions.openWhitehat(controller.get()));
        controller.pause().stop().destroy();
        assertFalse(DeveloperOptions.openWhitehat(controller.get()));
        assertEquals(0, NativeOptions.whitehat);
    }

    @Implements(value = DeveloperOptions.class, isInAndroidSdk = false)
    public static class NativeOptions {
        static int result, calls, whitehat;
        static boolean failure;
        static Object host;
        @Implementation protected static int openOverridesNative(Object activity) {
            calls++;
            host = activity;
            if (failure) throw new IllegalStateException("native navigation failed");
            return result;
        }
        @Implementation protected static int openWhitehatNative(Object activity) {
            whitehat++;
            host = activity;
            if (failure) throw new IllegalStateException("native navigation failed");
            return result;
        }
    }
}
