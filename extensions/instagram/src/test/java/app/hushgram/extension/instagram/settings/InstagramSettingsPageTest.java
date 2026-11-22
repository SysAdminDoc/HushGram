/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

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
 * Instagram settings page (#797): off to start, right under Light settings page, and while it's on
 * the settings take Instagram's own look in place of the black and light ones: flat rows on the
 * page, plain grey section names and Instagram's pill switch, dark or light as the Instagram screen
 * under them is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class InstagramSettingsPageTest {
    private static final String TAG = "instagram_settings";
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
        Settings.SETTINGS_INSTAGRAM_LOOK.resetToDefault();
        ScreenColors.settingsClosedForTests();
        ScreenColors.forgetNightForTests();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.NOTIFICATION_GROUPS);
    }

    @After public void close() throws Exception {
        // The looks, the night seen and the open count are process-wide: put them back even when closing throws.
        try {
            if (dialog != null) dialog.dismissAllowingStateLoss();
            if (controller != null) controller.close();
            Utils.awaitBackgroundTasksForTests();
        } finally {
            PatchFamily.inBuildForTests = null;
            ScreenColors.shown = null;
            ScreenColors.settingsClosedForTests();
            ScreenColors.forgetNightForTests();
            Settings.SETTINGS_INSTAGRAM_LOOK.resetToDefault();
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

    private SwitchPreference instagramSwitch() {
        Preference row = page.findPreference(Settings.SETTINGS_INSTAGRAM_LOOK.key);
        assertTrue("no Instagram settings page switch", row instanceof SwitchPreference);
        return (SwitchPreference) row;
    }

    /** What a tap on the switch does: its change listener, then the new state. */
    private void flip(boolean on) {
        SwitchPreference row = instagramSwitch();
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, on));
        row.setChecked(on);
        ShadowLooper.idleMainLooper();
    }

    private static Context night(boolean dark) {
        Context app = RuntimeEnvironment.getApplication();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return app.createConfigurationContext(config);
    }

    /** A switch row the way a SwitchPreference lays one out: its title, summary, icon and switch. */
    private static final class Row {
        final LinearLayout view;
        final TextView title;
        final ImageView icon;
        final Switch toggle;

        Row(Activity activity) {
            view = new LinearLayout(activity);
            FrameLayout iconFrame = new FrameLayout(activity);
            icon = new ImageView(activity);
            icon.setId(android.R.id.icon);
            iconFrame.addView(icon, new FrameLayout.LayoutParams(10, 10));
            view.addView(iconFrame, new LinearLayout.LayoutParams(10, 10));
            title = new TextView(activity);
            title.setId(android.R.id.title);
            view.addView(title);
            TextView summary = new TextView(activity);
            summary.setId(android.R.id.summary);
            view.addView(summary);
            toggle = new Switch(activity);
            toggle.setId(android.R.id.switch_widget);
            view.addView(toggle);
        }
    }

    private static int px(View view, int dp) {
        return Math.round(dp * view.getResources().getDisplayMetrics().density);
    }

    private static int state(android.content.res.ColorStateList colors, boolean checked) {
        return colors.getColorForState(checked
                ? new int[]{android.R.attr.state_enabled, android.R.attr.state_checked}
                : new int[]{android.R.attr.state_enabled}, 0);
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

    @Test public void itStartsOffUnderTheLightSwitch() throws Exception {
        open();
        SwitchPreference instagram = instagramSwitch();
        assertEquals("Instagram settings page", instagram.getTitle().toString());
        assertEquals("Plain rows, switches and colors like Instagram's own settings, dark or light as Instagram is. "
                + "Takes the place of Black and Light settings page while it's on.", instagram.getSummary().toString());
        assertFalse("off to start", instagram.isChecked());
        assertSame(ScreenColors.BLACK, ScreenColors.current());
        Preference light = page.findPreference(Settings.SETTINGS_LIGHT_LOOK.key);
        assertNotNull(light);
        assertSame("right under Light settings page", light.getParent(), instagram.getParent());
        assertEquals(light.getOrder() + 1, instagram.getOrder());
    }

    @Test public void itTakesThePlaceOfBothLooksAndFollowsInstagramsDarkOrLight() {
        Settings.SETTINGS_BLACK_LOOK.save(true);
        Settings.SETTINGS_LIGHT_LOOK.save(true);
        Settings.SETTINGS_INSTAGRAM_LOOK.save(true);
        ScreenColors.followNight(night(false));
        assertSame(ScreenColors.INSTAGRAM_LIGHT, ScreenColors.settingsPage());
        ScreenColors.followNight(night(true));
        assertSame("dark over a dark Instagram screen", ScreenColors.INSTAGRAM_DARK, ScreenColors.settingsPage());
        ScreenColors.followNight(null);
        assertSame("no screen to read keeps the last one seen", ScreenColors.INSTAGRAM_DARK, ScreenColors.settingsPage());
        ScreenColors.forgetNightForTests();
        assertSame("before any screen, the app's own night mode decides", ScreenColors.INSTAGRAM_LIGHT, ScreenColors.settingsPage());

        assertTrue(ScreenColors.INSTAGRAM_LIGHT.flat);
        assertTrue(ScreenColors.INSTAGRAM_LIGHT.light);
        assertEquals(Color.WHITE, ScreenColors.INSTAGRAM_LIGHT.background);
        assertEquals("Instagram's near-black text", 0xFF0C1014, ScreenColors.INSTAGRAM_LIGHT.title);
        assertTrue(ScreenColors.INSTAGRAM_DARK.flat);
        assertFalse(ScreenColors.INSTAGRAM_DARK.light);
        assertEquals("Instagram's dark page", 0xFF0C1014, ScreenColors.INSTAGRAM_DARK.background);
        for (ScreenColors look : new ScreenColors[]{ScreenColors.INSTAGRAM_LIGHT, ScreenColors.INSTAGRAM_DARK}) {
            assertEquals("the rows sit on the page", look.background, look.card);
            assertEquals("its switch is in its text colour", look.title, look.accent);
            assertEquals("and its handle in the page's", look.background, look.onAccent);
        }
        assertFalse(ScreenColors.DEFAULT.flat);
        assertFalse(ScreenColors.BLACK.flat);
        assertFalse(ScreenColors.LIGHT.flat);
    }

    @Test public void itKeepsToTheSettingsScreen() {
        Settings.SETTINGS_INSTAGRAM_LOOK.save(true);
        ScreenColors.settingsClosedForTests();
        assertSame("a dialog over Instagram keeps the black look", ScreenColors.BLACK, ScreenColors.current());
        ScreenColors.settingsOpened();
        assertSame(ScreenColors.INSTAGRAM_LIGHT, ScreenColors.current());
        assertEquals(android.R.style.Theme_Material_Light_NoActionBar, ScreenColors.themeFor(null));
        ScreenColors.settingsClosed();
        assertSame(ScreenColors.BLACK, ScreenColors.current());
    }

    @Test public void rowsSitFlatOnThePageWithInstagramsSwitch() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SwitchPreference preference = new SwitchPreference(activity);
        preference.setIcon(new ColorDrawable(Color.RED));
        for (ScreenColors look : new ScreenColors[]{ScreenColors.INSTAGRAM_LIGHT, ScreenColors.INSTAGRAM_DARK}) {
            Row row = new Row(activity);
            look.paintRow(row.view, preference);

            Drawable background = row.view.getBackground();
            assertTrue("a tap still ripples: " + background, background instanceof RippleDrawable);
            Drawable inset = ((RippleDrawable) background).getDrawable(0);
            assertTrue(inset instanceof InsetDrawable);
            Rect edges = new Rect();
            inset.getPadding(edges);
            assertEquals("edge to edge, no card", new Rect(0, 0, 0, 0), edges);
            GradientDrawable surface = (GradientDrawable) ((InsetDrawable) inset).getDrawable();
            assertEquals(look.background, surface.getColor().getDefaultColor());
            assertEquals("Instagram's 16 at the side", px(row.view, 16), row.view.getPaddingStart());
            assertEquals(px(row.view, 16), row.view.getPaddingEnd());

            Drawable track = row.toggle.getTrackDrawable();
            assertEquals("Instagram's 52 wide pill", px(row.view, 52), track.getIntrinsicWidth());
            assertEquals(px(row.view, 32), track.getIntrinsicHeight());
            assertEquals(px(row.view, 16), ((GradientDrawable) track).getCornerRadius(), 0.5f);
            Drawable thumb = row.toggle.getThumbDrawable();
            assertTrue("a round handle inset in the pill: " + thumb, thumb instanceof InsetDrawable);
            assertEquals(px(row.view, 32), thumb.getIntrinsicWidth());
            assertEquals(GradientDrawable.OVAL, ((GradientDrawable) ((InsetDrawable) thumb).getDrawable()).getShape());
            assertEquals(look.title, state(row.toggle.getTrackTintList(), true));
            assertEquals(look.switchOff, state(row.toggle.getTrackTintList(), false));
            assertEquals(look.background, state(row.toggle.getThumbTintList(), true));
            assertEquals("a white handle when off", Color.WHITE, state(row.toggle.getThumbTintList(), false));
            assertEquals("icons in the text's colour", look.title, row.icon.getImageTintList().getDefaultColor());
        }
        Row card = new Row(activity);
        ScreenColors.BLACK.paintRow(card.view, preference);
        Rect edges = new Rect();
        ((RippleDrawable) card.view.getBackground()).getDrawable(0).getPadding(edges);
        assertNotEquals("the black look keeps its cards", 0, edges.left);
        assertEquals(px(card.view, 40), card.toggle.getTrackDrawable().getIntrinsicWidth());
    }

    @Test public void sectionNamesArePlainGreyAsInInstagram() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (ScreenColors look : new ScreenColors[]{ScreenColors.INSTAGRAM_LIGHT, ScreenColors.INSTAGRAM_DARK}) {
            Row heading = new Row(activity);
            look.paintHeading(heading.view);
            assertEquals(look.summary, heading.title.getCurrentTextColor());
            assertFalse("sentence case", heading.title.isAllCaps());
            assertEquals(0f, heading.title.getLetterSpacing(), 0f);
            assertEquals(px(heading.view, 16), heading.view.getPaddingStart());
            Row group = new Row(activity);
            look.paintGroupLabel(group.view);
            assertEquals(px(group.view, 16), group.view.getPaddingStart());
        }
        Row heading = new Row(activity);
        ScreenColors.BLACK.paintHeading(heading.view);
        assertTrue("the black look keeps its capitals", heading.title.isAllCaps());
    }

    @Test public void theRealScreenOpensInInstagramsLightLook() throws Exception {
        Settings.SETTINGS_INSTAGRAM_LOOK.save(true);
        openDialog();
        assertSame(ScreenColors.INSTAGRAM_LIGHT, ScreenColors.current());
        assertEquals(android.R.style.Theme_Material_Light_NoActionBar, dialog.getTheme());
        Window window = dialog.getDialog().getWindow();
        assertEquals(Color.WHITE, windowColor(window));
        assertTrue("dark status and navigation icons over white", darkBarIcons(window));
    }

    @Test @Config(qualifiers = "+night")
    public void overADarkInstagramTheRealScreenOpensDark() throws Exception {
        Settings.SETTINGS_INSTAGRAM_LOOK.save(true);
        openDialog();
        assertSame(ScreenColors.INSTAGRAM_DARK, ScreenColors.current());
        assertEquals(android.R.style.Theme_Material_NoActionBar, dialog.getTheme());
        Window window = dialog.getDialog().getWindow();
        assertEquals(0xFF0C1014, windowColor(window));
        assertFalse("light bar icons over the dark page", darkBarIcons(window));
    }

    @Test public void aFlipRepaintsTheScreenAndItsBackedUpAndFound() throws Exception {
        openDialog();
        Window window = dialog.getDialog().getWindow();
        assertEquals(Color.BLACK, windowColor(window));
        flip(true);
        assertTrue(Settings.SETTINGS_INSTAGRAM_LOOK.savedValue());
        assertSame(ScreenColors.INSTAGRAM_LIGHT, ScreenColors.current());
        assertEquals(Color.WHITE, windowColor(window));
        flip(false);
        assertSame(ScreenColors.BLACK, ScreenColors.current());
        assertEquals(Color.BLACK, windowColor(window));

        assertTrue("a backup keeps it", ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_INSTAGRAM_LOOK.key));
        page.searchSettings("instagram settings page");
        assertNotNull("its name finds it", instagramSwitch().getParent());
        page.searchSettings("native");
        assertNotNull("so does a word people use for it", instagramSwitch().getParent());
        page.searchSettings("");
    }
}
