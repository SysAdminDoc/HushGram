/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
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

/** The settings surface stays monochrome and preserves HushGram's brand and saved configuration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class LegacySettingsAppearanceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.NOTIFICATION_GROUPS);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.CATEGORY_GRID.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    private void open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
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

    /** Draws [wanted] through the settings adapter at the test screen's width. */
    private View render(Preference wanted) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) != wanted) continue;
            View row = adapter.getView(i, null, new FrameLayout(controller.get()));
            int width = Math.round(320 * row.getResources().getDisplayMetrics().density);
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, width, row.getMeasuredHeight());
            return row;
        }
        throw new AssertionError("not on the page: " + wanted.getKey());
    }

    private static int cardColor(View row) {
        Drawable background = row.getBackground().getCurrent();
        if (background instanceof RippleDrawable) background = ((RippleDrawable) background).getDrawable(0);
        assertTrue("no inset card behind the row: " + background, background instanceof InsetDrawable);
        Drawable surface = ((InsetDrawable) background).getDrawable();
        assertTrue(surface instanceof GradientDrawable);
        return ((GradientDrawable) surface).getColor().getDefaultColor();
    }

    @Test public void overviewUsesReferencePaletteAndKeepsEverySection() throws Exception {
        open();
        PreferenceScreen screen = page.getPreferenceScreen();
        assertSame(ScreenColors.DEFAULT, ScreenColors.current());
        assertEquals(0xFF0E0E0E, ScreenColors.current().background);
        assertEquals(0xFF1B1B1B, ScreenColors.current().card);
        assertEquals(0xFF262626, ScreenColors.current().iconSurface);
        assertEquals(0xFFF4F4F4, ScreenColors.current().title);
        assertEquals(0xFFABABAB, ScreenColors.current().summary);
        assertEquals(SettingsHeader.KEY, screen.getPreference(0).getKey());
        assertEquals("hushgram_settings_search", screen.getPreference(1).getKey());
        assertEquals("Feed", categoryRow("Feed").getTitle().toString());
        assertNull("category descriptions are not displayed", categoryRow("Feed").getSummary());
        assertEquals(ScreenColors.SURFACE_CONTAINER, cardColor(render(categoryRow("Feed"))));
        assertEquals("all HushGram settings categories stay reachable", 23,
                countCategories(screen));
    }

    private static int countCategories(PreferenceScreen screen) {
        int total = 0;
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference row = screen.getPreference(i);
            if (row instanceof HushgramPreferenceFragment.Heading
                    && row.getKey() != null && row.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)) total++;
        }
        return total;
    }

    @Test public void legacyAppearancePreferenceCannotChangeThePaletteAndRemainsInBackups() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(false);
        open();
        int card = ScreenColors.current().card;
        int surface = ScreenColors.current().background;
        assertNull("the retired appearance switch is not shown", page.getPreferenceScreen()
                .findPreference(Settings.SETTINGS_BLACK_LOOK.key));
        assertTrue("existing backups still recognize the old key",
                ConfigurationBackup.eligible().containsKey(Settings.SETTINGS_BLACK_LOOK.key));
        Settings.SETTINGS_BLACK_LOOK.save(true);
        ShadowLooper.idleMainLooper();
        assertEquals(card, ScreenColors.current().card);
        assertEquals(surface, ScreenColors.current().background);
        assertSame(ScreenColors.DEFAULT, ScreenColors.current());
        assertNotNull("the HushGram header stays visible", page.getPreferenceScreen().findPreference(SettingsHeader.KEY));
        assertNull("no second layout mode is exposed", page.getPreferenceScreen().findPreference("hushgram_view_toggle"));
        assertNull(page.getPreferenceScreen().findPreference("hushgram_grid_toggle"));
    }

    @Test public void headerPreservesTheHushGramBrandWithoutShimmer() throws Exception {
        open();
        Preference header = page.getPreferenceScreen().getPreference(0);
        assertTrue(header instanceof SettingsHeader);
        assertFalse("the brand header is not a button", header.isSelectable());
        View view = render(header);
        TextView name = view.findViewWithTag(SettingsHeader.NAME);
        assertEquals("HushGram", name.getText().toString());
        assertFalse("the old shimmer treatment is removed", ShimmerTitle.wears(name));
        assertTrue(name.isAccessibilityHeading());
        TextView version = view.findViewWithTag(SettingsHeader.VERSION);
        assertTrue(version.getText().toString(), version.getText().toString().startsWith("Version "));
        ImageView icon = view.findViewWithTag(SettingsHeader.ICON);
        assertNotNull(icon.getDrawable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
    }

    @Test public void searchFindsFeatureSettingsButDoesNotExposeTheRetiredAppearanceOption() throws Exception {
        open();
        page.searchSettings("Hide ads");
        ShadowLooper.idleMainLooper();
        assertNotNull("search still finds the real feature setting",
                page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        page.searchSettings("amoled");
        ShadowLooper.idleMainLooper();
        assertNull(page.getPreferenceScreen().findPreference(Settings.SETTINGS_BLACK_LOOK.key));
        assertNotNull("an obsolete display alias returns no setting", page.getPreferenceScreen()
                .findPreference("hushgram_settings_search_empty"));
    }

    @Test public void theSearchFieldUsesTheSameFixedSurfaceAsTheOverviewRows() throws Exception {
        open();
        View field = render(page.getPreferenceScreen().findPreference("hushgram_settings_search"));
        assertEquals(ScreenColors.SURFACE_CONTAINER, cardColor(field));
        Settings.SETTINGS_BLACK_LOOK.save(true);
        assertEquals(ScreenColors.SURFACE_CONTAINER,
                cardColor(render(page.getPreferenceScreen().findPreference("hushgram_settings_search"))));
    }
}
