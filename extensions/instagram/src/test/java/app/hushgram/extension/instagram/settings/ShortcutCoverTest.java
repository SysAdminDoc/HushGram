/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.DialogFragment;
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

import java.time.Duration;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * The launcher shortcut's screen stays black until the settings draw over it, so the tab Instagram
 * starts on (often a playing reel) doesn't show for a second first, and the cover never outlasts
 * the request.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30, 37})
public class ShortcutCoverTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void setUp() {
        // The entry's state is static and outlives a test in the same sandbox.
        SettingsEntry.onClosedByUser();
        Application app = RuntimeEnvironment.getApplication();
        app.getApplicationInfo().targetSdkVersion = 37;
        app.registerActivityLifecycleCallbacks(new SettingsEntry.OpenWhenResumed());
    }

    @After
    public void tearDown() throws Exception {
        SettingsEntry.drawsText = SettingsEntry::buildsText;
        Utils.awaitBackgroundTasksForTests();
    }

    @Test
    public void coversTheShortcutsScreenUntilTheSettingsDraw() {
        Activity activity = resume(shortcut()).get();
        assertNotNull("the shortcut's request did not open the settings", shown(activity));
        assertTrue("the screen under the settings showed before they drew", ShortcutCover.covers(activity));

        drawSettings(activity);
        idleFor(ShortcutCover.AFTER_DRAW_MS);

        assertFalse("the cover stayed after the settings drew", ShortcutCover.covers(activity));
    }

    /** A cold start keeps Instagram's main thread busy, so the settings' first frame can come late. */
    @Test
    public void waitsLongerForSettingsThatHaveStarted() {
        SettingsEntry.drawsText = context -> false;
        try {
            Activity activity = resume(shortcut()).get();
            assertTrue(ShortcutCover.covers(activity));
            // Robolectric draws a shown window as its clock moves, so the settings' window here is
            // one that hasn't drawn a frame yet: a dialog that was never shown.
            ShortcutCover.offOnceDrawn(new Dialog(activity));

            idleFor(ShortcutCover.COVER_MAX_MS);
            assertTrue("the cover came off before the settings drew", ShortcutCover.covers(activity));

            idleFor(ShortcutCover.DRAW_WAIT_MS - ShortcutCover.COVER_MAX_MS);
            assertFalse("the cover outlasted the settings' wait for a frame", ShortcutCover.covers(activity));
        } finally {
            letTheRequestLapse();
        }
    }

    /** Instagram in the background, the shortcut brings its screen back with a new intent. */
    @Test
    public void coversTheScreenTheShortcutBringsBack() {
        ActivityController<Activity> screen = resume(new Intent());
        Activity activity = screen.get();
        assertFalse("a start without the shortcut was covered", ShortcutCover.covers(activity));

        screen.pause();
        SettingsEntry.onNewIntent(activity, shortcut());
        screen.resume();
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("the shortcut's request did not open the settings", shown(activity));
        assertTrue("the screen came back uncovered", ShortcutCover.covers(activity));

        drawSettings(activity);
        idleFor(ShortcutCover.AFTER_DRAW_MS);
        assertFalse("the cover stayed after the settings drew", ShortcutCover.covers(activity));
    }

    /** The shortcut again while the settings are still up: they're what shows, so nothing is covered. */
    @Test
    public void leavesSettingsThatAreAlreadyUpUncovered() {
        ActivityController<Activity> screen = resume(shortcut());
        Activity activity = screen.get();
        drawSettings(activity);
        idleFor(ShortcutCover.AFTER_DRAW_MS);

        screen.pause();
        SettingsEntry.onNewIntent(activity, shortcut());
        screen.resume();
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(shown(activity));
        assertFalse("settings already up were covered", ShortcutCover.covers(activity));
    }

    /** The logo's long press and the other ways in open over the screen in front, which is already drawn. */
    @Test
    public void coversNothingForTheOtherWaysIn() {
        Activity activity = resume(new Intent()).get();
        assertTrue(SettingsEntry.requestOpen(activity));
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull("the request did not open the settings", shown(activity));
        assertFalse("a request from inside Instagram was covered", ShortcutCover.covers(activity));
    }

    @Test
    public void comesOffWhenTheSettingsNeverOpen() {
        SettingsEntry.drawsText = context -> false;
        try {
            Activity activity = resume(shortcut()).get();
            assertNull(shown(activity));
            assertTrue(ShortcutCover.covers(activity));

            idleFor(ShortcutCover.COVER_MAX_MS);
            assertFalse("the cover outlasted its time", ShortcutCover.covers(activity));
        } finally {
            letTheRequestLapse();
        }
    }

    @Test
    public void comesOffWhenItsScreenPauses() {
        SettingsEntry.drawsText = context -> false;
        try {
            ActivityController<Activity> screen = resume(shortcut());
            assertTrue(ShortcutCover.covers(screen.get()));

            screen.pause();
            assertFalse("the cover stayed on a screen that went away", ShortcutCover.covers(screen.get()));
        } finally {
            letTheRequestLapse();
        }
    }

    private static Intent shortcut() {
        return new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true);
    }

    private static ActivityController<Activity> resume(Intent intent) {
        ActivityController<Activity> screen = Robolectric.buildActivity(Activity.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        return screen;
    }

    private static void idleFor(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** The request waiting on a theme expires at its next look, so the next test starts clean. */
    private static void letTheRequestLapse() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
    }

    /** The settings window's next frame, as Android would draw it. */
    @SuppressWarnings("deprecation") // Framework fragments, as the entry uses.
    private static void drawSettings(Activity activity) {
        DialogFragment settings = (DialogFragment) shown(activity);
        assertNotNull(settings);
        settings.getDialog().getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
    }

    @SuppressWarnings("deprecation") // Framework fragments, as the entry uses.
    private static Fragment shown(Activity activity) {
        activity.getFragmentManager().executePendingTransactions();
        Fragment fragment = activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG);
        return fragment != null && !fragment.isRemoving() ? fragment : null;
    }
}
