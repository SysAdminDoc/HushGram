/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Pick the app icon (#284): the picked icon's alias goes on and Instagram's off as Instagram leaves the
 * screen, and Instagram's own calls can't bring its alias back while one of HushGram's is on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppIconTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private PackageManager manager;
    private String owner;

    @Before public void prepare() {
        Settings.APP_ICON.resetToDefault();
        PauseForTests.resume();
        AppIcon.forgetForTests();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        manager = activity.getPackageManager();
        owner = activity.getPackageName();
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        controller.close();
        AppIcon.forgetForTests();
        PauseForTests.resume();
        Settings.APP_ICON.resetToDefault();
    }

    private ComponentName alias(AppIconChoice icon) {
        return AppIcon.alias(owner, icon);
    }

    private int state(AppIconChoice icon) {
        return manager.getComponentEnabledSetting(alias(icon));
    }

    /** Instagram's screens leave the screen, and the icon changes behind them. */
    private void leave() throws Exception {
        AppIcon.started(activity);
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void instagramsOwnChangesNothing() throws Exception {
        leave();
        for (AppIconChoice icon : AppIconChoice.values()) assertEquals(icon.name(), COMPONENT_ENABLED_STATE_DEFAULT, state(icon));
    }

    @Test public void thePickedIconTakesOverOnTheWayOut() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHGRAM);
        AppIcon.started(activity);
        assertEquals("not while Instagram is on screen", COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.HUSHGRAM));
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.HUSHGRAM));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.INSTAGRAM));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.DARK));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.PLUM));
        assertEquals("Instagram keeps running", DONT_KILL_APP,
                shadowOf(manager).getComponentEnabledSettingFlags(alias(AppIconChoice.INSTAGRAM)));
        assertFalse("nothing left to change", AppIcon.apply(activity));
    }

    @Test public void oneScreenStillOpenIsntLeaving() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.DARK);
        AppIcon.started(activity);
        AppIcon.started(activity);
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.DARK));
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.DARK));
    }

    @Test public void anotherPickSwapsHushGramsIcons() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHGRAM);
        leave();
        Settings.APP_ICON.save(AppIconChoice.PLUM);
        leave();
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.PLUM));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, state(AppIconChoice.HUSHGRAM));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.INSTAGRAM));
    }

    @Test public void instagramsOwnComesBackAsShipped() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.DARK);
        leave();
        Settings.APP_ICON.save(AppIconChoice.INSTAGRAM);
        leave();
        for (AppIconChoice icon : AppIconChoice.values()) assertEquals(icon.name(), COMPONENT_ENABLED_STATE_DEFAULT, state(icon));
    }

    /** One of Instagram's own alternate icons turned its alias off, and that's Instagram's to undo. */
    @Test public void instagramsAlternateIconStays() {
        manager.setComponentEnabledSetting(alias(AppIconChoice.INSTAGRAM), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
        assertFalse(AppIcon.apply(activity));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.INSTAGRAM));
    }

    @Test public void pauseKeepsThePickedIcon() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHGRAM);
        leave();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        leave();
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.HUSHGRAM));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.INSTAGRAM));
    }

    /** Instagram turns its alias back on at start; with HushGram's on, that call goes nowhere. */
    @Test public void instagramCantBringItsIconBackWhileHushGramsIsOn() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHGRAM);
        leave();
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.INSTAGRAM), COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP);
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.INSTAGRAM), COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP);
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.INSTAGRAM));
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.HUSHGRAM), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
        assertEquals("nor touch HushGram's", COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.HUSHGRAM));

        ComponentName other = new ComponentName(owner, "com.instagram.notifications.push.SomeReceiver");
        AppIcon.setComponentEnabledSetting(manager, other, COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
        assertEquals("everything else goes through", COMPONENT_ENABLED_STATE_DISABLED, manager.getComponentEnabledSetting(other));
    }

    @Test public void withInstagramsOwnPickedItsCallsGoThrough() {
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.INSTAGRAM), COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP);
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.INSTAGRAM));
        AppIcon.setComponentEnabledSetting(manager, alias(AppIconChoice.INSTAGRAM), COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP);
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.INSTAGRAM));
    }

    @Test public void severalAtOnceLeavesOutOnlyTheOnesKeptOut() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.PLUM);
        leave();
        ComponentName other = new ComponentName(owner, "com.instagram.notifications.push.SomeReceiver");
        AppIcon.setComponentEnabledSettings(manager, Arrays.asList(
                new PackageManager.ComponentEnabledSetting(other, COMPONENT_ENABLED_STATE_DISABLED, DONT_KILL_APP),
                new PackageManager.ComponentEnabledSetting(alias(AppIconChoice.INSTAGRAM), COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP),
                new PackageManager.ComponentEnabledSetting(alias(AppIconChoice.PLUM), COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP)));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, manager.getComponentEnabledSetting(other));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, state(AppIconChoice.INSTAGRAM));
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, state(AppIconChoice.PLUM));
    }

    @Test public void anIconChangeChecksTheShortcutWhenInstagramIsBack() throws Exception {
        Settings.APP_ICON.save(AppIconChoice.HUSHGRAM);
        leave();
        assertTrue(AppIcon.shortcutStaleForTests());
        AppIcon.started(activity);
        assertFalse(AppIcon.shortcutStaleForTests());
        AppIcon.stopped(activity);
        Utils.awaitBackgroundTasksForTests();
        assertFalse("nothing changed this time", AppIcon.shortcutStaleForTests());
    }
}
