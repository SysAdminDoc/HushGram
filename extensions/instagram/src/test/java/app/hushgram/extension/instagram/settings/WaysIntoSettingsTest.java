/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Looper;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Every way into HushGram has a switch (HushFacebook#146): the launcher shortcut, the logo's long
 * press and the menu row's hide switch sit with the tab and profile menu long presses, and the last
 * way in still on stays on, greyed out under a line saying why.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class WaysIntoSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        reset();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        SettingsEntry.onClosedByUser();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        reset();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private static void reset() {
        Settings.SETTINGS_SHORTCUT.resetToDefault();
        Settings.SETTINGS_LOGO_PRESS.resetToDefault();
        Settings.HIDE_MENU_ROW.resetToDefault();
        Settings.PROFILE_MENU_LONG_PRESS.resetToDefault();
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private void open() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private SwitchPreference switchRow(String key) {
        Preference found = page.getPreferenceScreen().findPreference(key);
        assertNotNull(key, found);
        return (SwitchPreference) found;
    }

    private static final String LAST = "The last way into these settings stays on.";

    @Test public void theShortcutAndTheLogoPressStartOnWithTheirOwnRows() throws Exception {
        assertTrue(Settings.SETTINGS_SHORTCUT.savedValue());
        assertTrue(Settings.SETTINGS_LOGO_PRESS.savedValue());
        assertTrue("a pause would take the shortcut away", Settings.SETTINGS_SHORTCUT.isKeptWhenPaused());
        assertTrue("a pause would take the logo press away", Settings.SETTINGS_LOGO_PRESS.isKeptWhenPaused());
        open();
        SwitchPreference shortcut = switchRow(Settings.SETTINGS_SHORTCUT.key);
        SwitchPreference logo = switchRow(Settings.SETTINGS_LOGO_PRESS.key);
        SwitchPreference hide = switchRow(Settings.HIDE_MENU_ROW.key);
        assertEquals("Settings shortcut on the app icon", String.valueOf(shortcut.getTitle()));
        assertEquals("Open settings from the logo", String.valueOf(logo.getTitle()));
        assertTrue(shortcut.isChecked() && shortcut.isEnabled());
        assertTrue(logo.isChecked() && logo.isEnabled());
        assertFalse(hide.isChecked());
        assertTrue(hide.isEnabled());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_SHORTCUT.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_LOGO_PRESS.key));
    }

    @Test public void theLastWayInStillOnIsGreyedOutAndComesBackWithASecond() throws Exception {
        open();
        SwitchPreference shortcut = switchRow(Settings.SETTINGS_SHORTCUT.key);
        SwitchPreference logo = switchRow(Settings.SETTINGS_LOGO_PRESS.key);
        SwitchPreference hide = switchRow(Settings.HIDE_MENU_ROW.key);

        shortcut.setChecked(false);
        ShadowLooper.idleMainLooper();
        assertTrue("two ways are still on", logo.isEnabled() && hide.isEnabled());
        logo.setChecked(false);
        ShadowLooper.idleMainLooper();
        assertFalse(Settings.SETTINGS_LOGO_PRESS.savedValue());
        assertFalse("the menu row is the last way in, and its hide switch can still be turned on", hide.isEnabled());
        assertEquals(LAST, String.valueOf(hide.getSummary()));
        assertTrue("an off way can come back", shortcut.isEnabled() && logo.isEnabled());

        shortcut.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(hide.isEnabled());
        assertNotEquals(LAST, String.valueOf(hide.getSummary()));
        hide.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.HIDE_MENU_ROW.savedValue());
        assertFalse("the shortcut is the last way in, and it can still be turned off", shortcut.isEnabled());
        assertEquals(LAST, String.valueOf(shortcut.getSummary()));

        // The long press on your profile's menu is a way in too, so the shortcut can go.
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        page.updateUIAvailability();
        assertTrue(shortcut.isEnabled());
        assertNotEquals(LAST, String.valueOf(shortcut.getSummary()));
    }

    @Test public void theHideSwitchSaysWhenNothingElseOpensHushGram() throws Exception {
        Settings.SETTINGS_SHORTCUT.save(false);
        Settings.SETTINGS_LOGO_PRESS.save(false);
        Settings.HIDE_MENU_ROW.save(true);
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        open();
        SwitchPreference hide = switchRow(Settings.HIDE_MENU_ROW.key);
        assertTrue(hide.isEnabled());

        // A tab long press was the only way in, and turning it off leaves the row as the way back.
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        page.updateUIAvailability();
        assertEquals("No other way into HushGram is on, so the row stays in Instagram's menu.",
                String.valueOf(hide.getSummary()));
        Activity activity = controller.get();
        assertTrue("nothing else opens HushGram, yet the row went",
                SettingsEntry.withSettingsRow(SettingsScreenRowTest.mainScreen(), new FrameLayout(activity))
                        instanceof android.widget.LinearLayout);
    }

    @Test public void aShortcutTheLauncherNeverShowsIsNoWayIn() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        shadowOf(manager).setIsRequestPinShortcutSupported(false);
        Settings.SETTINGS_LOGO_PRESS.save(false);
        Settings.HIDE_MENU_ROW.save(true);
        open();
        SwitchPreference shortcut = switchRow(Settings.SETTINGS_SHORTCUT.key);
        SwitchPreference hide = switchRow(Settings.HIDE_MENU_ROW.key);
        assertTrue("a shortcut the launcher never shows was kept on as the last way in", shortcut.isEnabled());
        assertEquals("No other way into HushGram is on, so the row stays in Instagram's menu.",
                String.valueOf(hide.getSummary()));
        assertTrue("the row went with only a shortcut nobody sees left",
                SettingsEntry.withSettingsRow(SettingsScreenRowTest.mainScreen(), new FrameLayout(controller.get()))
                        instanceof android.widget.LinearLayout);

        // Pinned to the home screen, the shortcut opens HushGram from there, so it counts again.
        manager.requestPinShortcut(new ShortcutInfo.Builder(context, SettingsEntry.SHORTCUT_ID)
                .setShortLabel("HushGram").setIntent(new Intent(Intent.ACTION_VIEW)).build(), null);
        page.updateUIAvailability();
        assertFalse("the pinned shortcut is the last way in, and it can still be turned off", shortcut.isEnabled());
        assertEquals(LAST, String.valueOf(shortcut.getSummary()));
        assertFalse("the pinned shortcut opens HushGram and the row stayed",
                SettingsEntry.withSettingsRow(SettingsScreenRowTest.mainScreen(), new FrameLayout(controller.get()))
                        instanceof android.widget.LinearLayout);
    }

    @Test public void offTheShortcutLeavesTheLauncherAndInstagramsCallsDontBringItBack() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        assertTrue(hasOurs(manager));

        Settings.SETTINGS_SHORTCUT.save(false);
        SettingsEntry.publishShortcutNow(context);
        assertFalse("the shortcut stayed with its switch off", hasOurs(manager));
        SettingsEntry.keepFirstNow(context);
        assertFalse("Instagram's shortcut call put it back", hasOurs(manager));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        SettingsEntry.publishShortcutNow(context);
        assertFalse("a pause put it back", hasOurs(manager));
        PauseForTests.resume();

        Settings.SETTINGS_SHORTCUT.save(true);
        SettingsEntry.publishShortcutNow(context);
        assertTrue(hasOurs(manager));
    }

    @Test public void offTheLogosLongPressIsInstagramsAndAPauseLeavesItThatWay() {
        SettingsEntry.onClosedByUser();
        ((Application) RuntimeEnvironment.getApplication()).registerActivityLifecycleCallbacks(new SettingsEntry.OpenWhenResumed());
        ActivityController<Activity> logoScreen = Robolectric.buildActivity(Activity.class).setup();
        logoScreen.windowFocusChanged(true);
        Activity activity = logoScreen.get();
        FrameLayout root = new FrameLayout(activity);
        ImageView logo = new ImageView(activity);
        root.addView(logo);
        activity.setContentView(root);
        AtomicInteger asked = new AtomicInteger();
        SettingsEntry.setLogoLongClickListener(logo, view -> {
            asked.incrementAndGet();
            return true;
        });

        Settings.SETTINGS_LOGO_PRESS.save(false);
        assertTrue(logo.performLongClick());
        assertEquals("Instagram's long press wasn't asked", 1, asked.get());
        assertEquals(0, shown(activity));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(logo.performLongClick());
        assertEquals(2, asked.get());
        assertEquals("a pause turned the logo press back on", 0, shown(activity));
        PauseForTests.resume();

        Settings.SETTINGS_LOGO_PRESS.save(true);
        assertTrue(logo.performLongClick());
        assertEquals("Instagram's long press took the press back", 2, asked.get());
        assertEquals(1, shown(activity));
    }

    private static boolean hasOurs(ShortcutManager manager) {
        for (ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (SettingsEntry.SHORTCUT_ID.equals(shortcut.getId())) return true;
        }
        return false;
    }

    private static int shown(Activity activity) {
        shadowOf(Looper.getMainLooper()).idle();
        activity.getFragmentManager().executePendingTransactions();
        int count = 0;
        for (android.app.Fragment fragment : activity.getFragmentManager().getFragments()) {
            if (SettingsEntry.DIALOG_TAG.equals(fragment.getTag()) && !fragment.isRemoving()) count++;
        }
        return count;
    }
}
