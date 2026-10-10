/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.FailingStore;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** The Before you sign in notice under the status card, and the tap that hides it for good. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SignInNoticeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        ScreenColors.shown = null;
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    /** A first look at the screen has the notice right under the status card, as a row a tap acts on. */
    @Test
    public void aFreshScreenShowsTheNoticeUnderTheStatusCard() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            PreferenceScreen screen = DownloadSettingsTest.pageIn(controller).getPreferenceScreen();
            Preference notice = screen.getPreference(1);
            assertEquals(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY, notice.getKey());
            assertEquals("Before you sign in", String.valueOf(notice.getTitle()));
            String summary = String.valueOf(notice.getSummary());
            assertTrue(summary, summary.contains("sign-in"));
            assertTrue(summary, summary.startsWith("Nobody outside Meta knows what gets an account suspended."));
            assertTrue(summary, summary.contains("same signing key"));
            assertTrue(summary, summary.endsWith("Tap to hide this."));
            assertTrue("a tap on it acts at once", ((HushgramPreferenceFragment.Row) notice).actsOnTap());
        }
    }

    /** A tap takes the notice off the screen at once, and it doesn't come back when the screen opens again. */
    @Test
    public void aTapHidesTheNoticeForGood() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            PreferenceScreen screen = DownloadSettingsTest.pageIn(controller).getPreferenceScreen();
            Preference notice = screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY);
            assertNotNull(notice);
            assertTrue(notice.getOnPreferenceClickListener().onPreferenceClick(notice));
            assertNull("still on screen after the tap", screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY));
            assertTrue(Settings.SIGN_IN_NOTICE_HIDDEN.savedValue());
        }
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            PreferenceScreen screen = DownloadSettingsTest.pageIn(controller).getPreferenceScreen();
            assertNull("back on a new screen", screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY));
            assertFalse(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY.equals(screen.getPreference(1).getKey()));
        }
    }

    /** A pause makes every switch answer off, and that mustn't read as the notice never having been hidden. */
    @Test
    public void aPauseDoesntBringAHiddenNoticeBack() {
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            PreferenceScreen screen = DownloadSettingsTest.pageIn(controller).getPreferenceScreen();
            assertNull(screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY));
        }
    }

    @Test
    @Config(sdk = {28, 29, 30, 37})
    public void aFailedDismissalKeepsTheNoticeAndExplainsTheFailure() {
        org.robolectric.RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.DEBUG.save(false);
        ShadowToast.reset();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            PreferenceScreen screen = DownloadSettingsTest.pageIn(controller).getPreferenceScreen();
            Preference notice = screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY);
            assertNotNull(notice);
            try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_FALSE)) {
                assertTrue(notice.getOnPreferenceClickListener().onPreferenceClick(notice));
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertFalse(Settings.SIGN_IN_NOTICE_HIDDEN.savedValue());
            assertSame("a refused dismissal must leave the retry target on screen", notice,
                    screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY));
            assertEquals("Couldn't hide this notice. Try again.",
                    ShadowToast.getTextOfLatestToast());
        }
    }

    @Test
    @Config(sdk = {28, 29, 30, 37})
    public void aFailedDismissalDoesNotClaimAnUnprovenRollback() {
        org.robolectric.RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.DEBUG.save(false);
        ShadowToast.reset();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            PreferenceScreen screen = DownloadSettingsTest.pageIn(controller).getPreferenceScreen();
            Preference notice = screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY);
            assertNotNull(notice);
            try (FailingStore ignored = FailingStore.install(
                    FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING, FailingStore.Fault.LOST)) {
                assertTrue(notice.getOnPreferenceClickListener().onPreferenceClick(notice));
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertFalse("the scalar setting retained its previous live value", Settings.SIGN_IN_NOTICE_HIDDEN.savedValue());
            assertTrue("the injected commit landed and rollback did not", Setting.preferences
                    .getBoolean(Settings.SIGN_IN_NOTICE_HIDDEN.key, false));
            assertSame("the failed dismissal must leave a retry target", notice,
                    screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY));
            assertEquals("Couldn't hide this notice. Try again.",
                    ShadowToast.getTextOfLatestToast());
        } finally {
            Setting.preferences.removeKey(Settings.SIGN_IN_NOTICE_HIDDEN.key);
        }
    }
}
