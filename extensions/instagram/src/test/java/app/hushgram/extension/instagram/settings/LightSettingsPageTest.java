/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListAdapter;

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
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Light settings page (#762): off to start, white rows and dialogs with HushGram's name at the top
 * while it's on, in place of the black look, only while a settings screen is up, and the screen's
 * own parts, the window and its bar icons white or dark the moment it's flipped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class LightSettingsPageTest {
    private static final String TAG = "light_settings";
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private SettingsDialog dialog;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.CATEGORY_PAGES.save(true);
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.SETTINGS_LIGHT_LOOK.resetToDefault();
        ScreenColors.settingsClosedForTests();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.NOTIFICATION_GROUPS);
    }

    @After public void close() throws Exception {
        // The white look and the open count are process-wide: put them back even when closing throws.
        try {
            if (dialog != null) dialog.dismissAllowingStateLoss();
            if (controller != null) controller.close();
            Utils.awaitBackgroundTasksForTests();
        } finally {
            PatchFamily.inBuildForTests = null;
            ScreenColors.shown = null;
            ScreenColors.settingsClosedForTests();
            Settings.SETTINGS_LIGHT_LOOK.resetToDefault();
            Settings.SETTINGS_BLACK_LOOK.resetToDefault();
            Settings.CATEGORY_PAGES.resetToDefault();
            Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
            SettingsEntry.onClosedByUser();
        }
    }

    /** The page alone in a screen, counted as a settings screen the way the dialog counts itself. */
    private void open() throws Exception {
        ScreenColors.settingsOpened();
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    /** The real settings screen, as the shortcut and the logo open it. */
    private void openDialog() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), TAG);
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertNotNull("the page is up", page);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private SwitchPreference lightSwitch() {
        Preference row = page.findPreference(Settings.SETTINGS_LIGHT_LOOK.key);
        assertTrue("no Light settings page switch", row instanceof SwitchPreference);
        return (SwitchPreference) row;
    }

    /** What a tap on the switch does: its change listener, then the new state. */
    private void flip(boolean on) {
        SwitchPreference row = lightSwitch();
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, on));
        row.setChecked(on);
        ShadowLooper.idleMainLooper();
    }

    private HushgramPreferenceFragment.Heading categoryRow(String title) {
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading && title.contentEquals(item.getTitle())) {
                return (HushgramPreferenceFragment.Heading) item;
            }
        }
        throw new AssertionError("no row for " + title);
    }

    private int positionOf(Preference wanted) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) if (adapter.getItem(i) == wanted) return i;
        throw new AssertionError("not on the page: " + wanted.getKey());
    }

    private View render(Preference wanted, View recycled) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        View row = adapter.getView(positionOf(wanted), recycled, new FrameLayout(controller.get()));
        int width = Math.round(320 * row.getResources().getDisplayMetrics().density);
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());
        return row;
    }

    private static int cardColor(View row) {
        Drawable background = row.getBackground().getCurrent();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        assertTrue("no inset card behind the row: " + background, background instanceof InsetDrawable);
        Drawable surface = ((InsetDrawable) background).getDrawable();
        assertTrue(surface instanceof GradientDrawable);
        return ((GradientDrawable) surface).getColor().getDefaultColor();
    }

    private static EditText field(View row) {
        if (row instanceof EditText) return (EditText) row;
        if (row instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) row;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = field(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean darkBarIcons(Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            return (window.getInsetsController().getSystemBarsAppearance() & both) == both;
        }
        int both = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        return (window.getDecorView().getSystemUiVisibility() & both) == both;
    }

    private static int windowColor(Window window) {
        Drawable background = window.getDecorView().getBackground();
        assertTrue("a plain window colour: " + background, background instanceof ColorDrawable);
        return ((ColorDrawable) background).getColor();
    }

    @Test public void itStartsOffUnderTheBlackSwitch() throws Exception {
        open();
        SwitchPreference light = lightSwitch();
        assertEquals("Light settings page", light.getTitle().toString());
        assertFalse("off to start", light.isChecked());
        assertSame(ScreenColors.BLACK, ScreenColors.current());
        Preference black = page.findPreference(Settings.SETTINGS_BLACK_LOOK.key);
        assertNotNull(black);
        assertSame("right under Black settings page", black.getParent(), light.getParent());
        assertEquals(black.getOrder() + 1, light.getOrder());
    }

    @Test public void onThePageGoesWhiteWithTheHeaderEvenWithBlackOff() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(false);
        Settings.SETTINGS_LIGHT_LOOK.save(true);
        open();
        assertSame(ScreenColors.LIGHT, ScreenColors.current());
        assertEquals("the header leads the page", SettingsHeader.KEY,
                page.getPreferenceScreen().getPreference(0).getKey());
        assertEquals(ScreenColors.LIGHT.card, cardColor(render(categoryRow("Feed"), null)));
        assertEquals(Color.WHITE, ScreenColors.LIGHT.background);
        assertSame("the name takes the gradient made for white", ShimmerTitle.LIGHT_COLORS, ShimmerTitle.stops());
    }

    @Test public void itTakesThePlaceOfTheBlackLook() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(true);
        Settings.SETTINGS_LIGHT_LOOK.save(true);
        open();
        assertSame(ScreenColors.LIGHT, ScreenColors.current());
        assertEquals(ScreenColors.LIGHT.card, cardColor(render(categoryRow("Feed"), null)));
    }

    @Test public void itKeepsToTheSettingsScreen() {
        Settings.SETTINGS_LIGHT_LOOK.save(true);
        ScreenColors.settingsClosedForTests();
        assertSame("a dialog over Instagram keeps the black look", ScreenColors.BLACK, ScreenColors.current());
        Settings.SETTINGS_BLACK_LOOK.save(false);
        assertSame(ScreenColors.DEFAULT, ScreenColors.current());
        assertSame("the shortcut's cover still matches the page about to open", ScreenColors.LIGHT, ScreenColors.settingsPage());
        assertSame(ShimmerTitle.COLORS, ShimmerTitle.stops());
        ScreenColors.settingsOpened();
        assertSame(ScreenColors.LIGHT, ScreenColors.current());
        assertEquals(android.R.style.Theme_Material_Light_NoActionBar, ScreenColors.themeFor(null));
        ScreenColors.settingsClosed();
        assertSame(ScreenColors.DEFAULT, ScreenColors.current());
        ScreenColors.settingsClosed();
        ScreenColors.settingsOpened();
        assertSame("one close too many doesn't leave the count below nothing", ScreenColors.LIGHT, ScreenColors.current());
    }

    @Test public void turningItOnRepaintsTheCardsAndTheSearchField() throws Exception {
        open();
        Preference search = page.findPreference("hushgram_settings_search");
        View row = render(search, null);
        assertEquals(Color.BLACK, cardColor(row));
        flip(true);
        assertTrue(Settings.SETTINGS_LIGHT_LOOK.savedValue());
        assertSame(ScreenColors.LIGHT, ScreenColors.current());
        View light = render(search, row);
        assertEquals(ScreenColors.LIGHT.card, cardColor(light));
        EditText field = field(light);
        assertNotNull(field);
        assertEquals("dark words in the white field", ScreenColors.LIGHT.title, field.getCurrentTextColor());
        assertEquals(ScreenColors.LIGHT.card, cardColor(render(categoryRow("Feed"), null)));
        flip(false);
        assertSame(ScreenColors.BLACK, ScreenColors.current());
        assertEquals(Color.BLACK, cardColor(render(search, light)));
        assertEquals(ScreenColors.BLACK.title, field(render(search, light)).getCurrentTextColor());
    }

    @Test public void theRealScreenOpensWhiteWithDarkBarIcons() throws Exception {
        Settings.SETTINGS_LIGHT_LOOK.save(true);
        openDialog();
        assertSame(ScreenColors.LIGHT, ScreenColors.current());
        assertEquals(android.R.style.Theme_Material_Light_NoActionBar, dialog.getTheme());
        Window window = dialog.getDialog().getWindow();
        assertEquals(Color.WHITE, windowColor(window));
        assertTrue("dark status and navigation icons over white", darkBarIcons(window));
        assertEquals(Color.WHITE, ((ColorDrawable) dialog.getView().getBackground()).getColor());

        dialog.dismissAllowingStateLoss();
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        dialog = null;
        assertNotSame("closed, the white look is gone", ScreenColors.LIGHT, ScreenColors.current());
    }

    @Test public void aFlipOnTheRealScreenRepaintsItsWindowAndBars() throws Exception {
        openDialog();
        Window window = dialog.getDialog().getWindow();
        assertEquals(Color.BLACK, windowColor(window));
        assertFalse(darkBarIcons(window));
        flip(true);
        assertEquals(Color.WHITE, windowColor(window));
        assertTrue(darkBarIcons(window));
        assertEquals(Color.WHITE, ((ColorDrawable) dialog.getView().getBackground()).getColor());
        TextViewCheck.restartColour(dialog, ScreenColors.LIGHT.title);
        flip(false);
        assertEquals(Color.BLACK, windowColor(window));
        assertFalse(darkBarIcons(window));
        TextViewCheck.restartColour(dialog, ScreenColors.BLACK.title);
    }

    @Test public void aStoppedScreenLetsTheWhiteLookGo() throws Exception {
        Settings.SETTINGS_LIGHT_LOOK.save(true);
        openDialog();
        assertSame(ScreenColors.LIGHT, ScreenColors.current());
        controller.pause().stop();
        ShadowLooper.idleMainLooper();
        assertSame("another screen covers the settings", ScreenColors.BLACK, ScreenColors.current());
        controller.start().resume();
        ShadowLooper.idleMainLooper();
        assertSame("back in front", ScreenColors.LIGHT, ScreenColors.current());
    }

    /** Restart now, under the page, in the palette's title colour. */
    private static final class TextViewCheck {
        static void restartColour(SettingsDialog dialog, int expected) {
            android.widget.TextView restart = dialog.getView().findViewById(SettingsDialog.RESTART_ID);
            assertNotNull(restart);
            assertEquals(expected, restart.getCurrentTextColor());
        }
    }
}
