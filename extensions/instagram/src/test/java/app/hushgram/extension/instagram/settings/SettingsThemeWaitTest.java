/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.content.Intent;
import android.os.Looper;

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

import java.lang.reflect.Field;
import java.time.Duration;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * The shortcut's screen over a host whose theme can't draw text yet. On 2026-10-09 a relaunch left
 * Instagram's main activity on its launcher theme, and the settings' first TextView threw
 * "Failed to resolve attribute" (igds_color_secondary_text). A test can't build Instagram's
 * theme, so the host's answer is swapped: the screen has to wait for a host that can, not open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30, 37})
public class SettingsThemeWaitTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final SettingsEntry.OpenWhenResumed callbacks = new SettingsEntry.OpenWhenResumed();

    @Before
    public void setUp() {
        SettingsEntry.onClosedByUser();
        Application app = RuntimeEnvironment.getApplication();
        app.getApplicationInfo().targetSdkVersion = 36;
        app.registerActivityLifecycleCallbacks(callbacks);
    }

    @After
    public void tearDown() throws Exception {
        SettingsEntry.drawsText = SettingsEntry::buildsText;
        SettingsEntry.onClosedByUser();
        Utils.awaitBackgroundTasksForTests();
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(callbacks);
    }

    @Test
    public void waitsForAThemeThatCanDrawText() {
        SettingsEntry.drawsText = context -> false;
        Activity activity = resume(new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true)).get();

        assertNull("the settings opened over a theme that can't draw them", shown(activity));

        SettingsEntry.drawsText = context -> true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));

        assertNotNull("the settings never opened once the theme could draw them", shown(activity));
    }

    @Test
    public void aScreenPutBackOverAnUnthemedHostClosesAndComesBack() {
        ActivityController<Activity> controller = resume(new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true));
        assertNotNull("the shortcut's request did not open the settings", shown(controller.get()));

        SettingsEntry.drawsText = context -> false;
        Activity recreated = controller.recreate().get();
        shadowOf(Looper.getMainLooper()).idle();

        assertNull("the settings stayed over a theme that can't draw them", shown(recreated));

        SettingsEntry.drawsText = context -> true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));

        assertNotNull("the settings didn't come back once the theme could draw them", shown(recreated));
    }

    /** A screen showing its failure page saves that, and put back over an unthemed host it must not build the page. */
    @Test
    public void aFailedScreenPutBackOverAnUnthemedHostClosesAndComesBack() throws Exception {
        ActivityController<Activity> controller = resume(new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true));
        Fragment dialog = shown(controller.get());
        assertNotNull("the shortcut's request did not open the settings", dialog);
        Field failed = SettingsDialog.class.getDeclaredField("failed");
        failed.setAccessible(true);
        failed.setBoolean(dialog, true);

        SettingsEntry.drawsText = context -> false;
        Activity recreated = controller.recreate().get();
        shadowOf(Looper.getMainLooper()).idle();

        assertNull("the settings stayed over a theme that can't draw them", shown(recreated));

        SettingsEntry.drawsText = context -> true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));

        assertNotNull("the settings didn't come back once the theme could draw them", shown(recreated));
    }

    @Test
    public void stopsWaitingWhenTheRequestRunsOut() {
        SettingsEntry.drawsText = context -> false;
        Activity activity = resume(new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true)).get();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));

        SettingsEntry.drawsText = context -> true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));

        assertNull("a request that ran out opened later", shown(activity));
    }

    private static ActivityController<Activity> resume(Intent intent) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        return controller;
    }

    @SuppressWarnings("deprecation") // Framework fragments, as the entry uses.
    private static Fragment shown(Activity activity) {
        activity.getFragmentManager().executePendingTransactions();
        Fragment fragment = activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG);
        return fragment != null && !fragment.isRemoving() ? fragment : null;
    }
}
