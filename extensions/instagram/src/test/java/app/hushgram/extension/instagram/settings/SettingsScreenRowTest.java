/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The HushGram settings row on Instagram's Settings and activity screen. */
@RunWith(RobolectricTestRunner.class)
public class SettingsScreenRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Instagram 449's settings screens, by the names its enum keeps; the constants are renamed. */
    enum Screen { MAIN_SETTINGS_SCREEN, NOTIFICATIONS }

    /** Stands in for Instagram's tab enum, whose constant names are kept. */
    enum NativeTab { FEED, PROFILE }

    @Before
    public void setUp() {
        PauseForTests.resume();
        NavigationSettings.forgetTabsForTests();
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        Settings.HIDE_MENU_ROW.resetToDefault();
    }

    @After
    public void tearDown() throws Exception {
        SettingsEntry.onClosedByUser();
        PauseForTests.resume();
        NavigationSettings.forgetTabsForTests();
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        Settings.HIDE_MENU_ROW.resetToDefault();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test
    public void theTopScreenGetsTheRowAboveIt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View screen = new FrameLayout(activity);

        View shown = SettingsEntry.withSettingsRow(arguments(Screen.MAIN_SETTINGS_SCREEN), screen);

        assertTrue("no column around the screen", shown instanceof LinearLayout);
        ViewGroup column = (ViewGroup) shown;
        assertEquals(3, column.getChildCount());
        View row = column.getChildAt(0);
        assertEquals(SettingsScreenRow.TAG, row.getTag());
        assertTrue("the row doesn't take taps", row.isClickable());
        assertEquals("HushGram settings", ((TextView) ((ViewGroup) row).getChildAt(1)).getText().toString());
        assertSame("Instagram's screen isn't last", screen, column.getChildAt(2));
        assertEquals("Instagram's screen doesn't fill what's left", 1f,
                ((LinearLayout.LayoutParams) screen.getLayoutParams()).weight, 0f);
    }

    @Test
    public void everyOtherScreenStaysAsItCame() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View screen = new FrameLayout(activity);

        assertSame(screen, SettingsEntry.withSettingsRow(arguments(Screen.NOTIFICATIONS), screen));
        assertSame(screen, SettingsEntry.withSettingsRow(new Bundle(), screen));
        assertSame(screen, SettingsEntry.withSettingsRow(null, screen));
        Bundle named = new Bundle();
        named.putString(SettingsScreenRow.SCREEN_ID, "MAIN_SETTINGS_SCREEN");
        assertSame("a string isn't the screen's enum", screen, SettingsEntry.withSettingsRow(named, screen));
        FrameLayout parent = new FrameLayout(activity);
        parent.addView(screen);
        assertSame("a view in a layout was moved", screen,
                SettingsEntry.withSettingsRow(arguments(Screen.MAIN_SETTINGS_SCREEN), screen));
    }

    @Test
    @SuppressWarnings("deprecation") // Framework fragments, as the entry uses.
    public void aTapOpensHushGramSettings() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ViewGroup column = (ViewGroup) SettingsEntry.withSettingsRow(
                arguments(Screen.MAIN_SETTINGS_SCREEN), new FrameLayout(activity));

        column.getChildAt(0).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        activity.getFragmentManager().executePendingTransactions();

        assertNotNull("the tap opened nothing", activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG));
    }

    /**
     * #84: with a tab long press chosen and the switch on, Instagram's menu leaves the row out.
     * Turning the long press off, or the switch, brings it back, so there's always one way in.
     */
    @Test
    public void theRowStepsAsideOnlyWhileTheChosenTabOpensHushGram() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        controller.windowFocusChanged(true);
        Activity activity = controller.get();
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        View profile = tabBar(activity, NativeTab.PROFILE);
        Settings.HIDE_MENU_ROW.save(true);

        View screen = new FrameLayout(activity);
        assertSame("the row is still in Instagram's menu", screen,
                SettingsEntry.withSettingsRow(arguments(Screen.MAIN_SETTINGS_SCREEN), screen));
        assertTrue("the chosen tab no longer opens HushGram", profile.performLongClick());
        shadowOf(Looper.getMainLooper()).idle();
        activity.getFragmentManager().executePendingTransactions();
        assertNotNull("the long press opened nothing", activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG));

        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        NavigationSettings.applyChoice();
        assertRow("turning the long press off left no way in", activity);
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        assertRow("the choice hasn't reached the button yet", activity);
        NavigationSettings.applyChoice();
        assertSame("the choice reached the button and the row stayed", screen,
                SettingsEntry.withSettingsRow(arguments(Screen.MAIN_SETTINGS_SCREEN), screen));

        Settings.HIDE_MENU_ROW.save(false);
        assertRow("the switch is off", activity);
    }

    /** A chosen tab the account doesn't show, or a button that's out of sight, can't stand in for the row. */
    @Test
    public void theRowStaysWhenTheChosenTabCantOpenHushGram() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.HIDE_MENU_ROW.save(true);
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        assertRow("no tab was ever built", activity);

        tabBar(activity, NativeTab.FEED);
        assertRow("only Home is on the bar", activity);

        View profile = tabBar(activity, NativeTab.PROFILE);
        profile.setVisibility(View.GONE);
        assertRow("the Profile button is hidden", activity);
        profile.setVisibility(View.VISIBLE);
        assertFalse("a visible Profile button kept the row",
                SettingsEntry.withSettingsRow(arguments(Screen.MAIN_SETTINGS_SCREEN), new FrameLayout(activity)) instanceof LinearLayout);

        ((ViewGroup) profile.getParent()).removeView(profile);
        assertRow("a button off the screen kept the row away", activity);
    }

    @Test
    public void pauseKeepsTheRow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        tabBar(activity, NativeTab.PROFILE);
        Settings.HIDE_MENU_ROW.save(true);
        assertFalse("the row stayed before Pause", SettingsEntry.withSettingsRow(
                arguments(Screen.MAIN_SETTINGS_SCREEN), new FrameLayout(activity)) instanceof LinearLayout);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        assertRow("Pause left the menu without the row", activity);
    }

    /** A tab bar with one button, bound the way Instagram's tab factory binds it under the patch. */
    private static View tabBar(Activity activity, NativeTab tab) {
        LinearLayout bar = new LinearLayout(activity);
        View button = new View(activity);
        bar.addView(button, new LinearLayout.LayoutParams(60, 60));
        activity.setContentView(bar);
        bar.layout(0, 0, 300, 100);
        button.layout(0, 0, 60, 60);
        button.setOnLongClickListener(NavigationSettings.remember(button, tab, view -> true));
        NavigationSettings.bind(button, tab);
        return button;
    }

    private static void assertRow(String why, Activity activity) {
        View shown = SettingsEntry.withSettingsRow(arguments(Screen.MAIN_SETTINGS_SCREEN), new FrameLayout(activity));
        assertTrue(why, shown instanceof LinearLayout);
        assertEquals(why, SettingsScreenRow.TAG, ((ViewGroup) shown).getChildAt(0).getTag());
    }

    private static Bundle arguments(Screen screen) {
        Bundle arguments = new Bundle();
        arguments.putSerializable(SettingsScreenRow.SCREEN_ID, screen);
        arguments.putSerializable("new_settings_session", Boolean.TRUE);
        return arguments;
    }
}
