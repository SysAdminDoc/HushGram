/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
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

/** The brand header, section title and search title use one stable monochrome settings chrome. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h480dp-mdpi")
@SuppressWarnings("deprecation")
public class SettingsTitleBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private SettingsDialog dialog;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED);
    }

    @After public void close() throws Exception {
        if (dialog != null) dialog.dismissAllowingStateLoss();
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    private void openDialog() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "title_bar");
        controller.get().getFragmentManager().executePendingTransactions();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        layoutPage();
    }

    private void layoutPage() {
        ShadowLooper.idleMainLooper();
        View root = dialog.getView();
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(480 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        ShadowLooper.idleMainLooper();
    }

    private LinearLayout bar() {
        return (LinearLayout) ((LinearLayout) dialog.getView()).getChildAt(0);
    }

    private TextView barTitle() { return (TextView) bar().getChildAt(1); }

    private ListView list() { return page.getView().findViewById(android.R.id.list); }

    private HushgramPreferenceFragment.Heading category(String title) {
        for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
            Preference row = page.getPreferenceScreen().getPreference(i);
            if (row instanceof HushgramPreferenceFragment.Heading && title.contentEquals(row.getTitle())) {
                return (HushgramPreferenceFragment.Heading) row;
            }
        }
        throw new AssertionError("no category row titled " + title);
    }

    private static int shown(View view, String wanted) {
        if (!view.isShown() || !view.getLocalVisibleRect(new Rect())) return 0;
        if (view instanceof TextView && wanted.contentEquals(((TextView) view).getText())) return 1;
        int count = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += shown(group.getChildAt(i), wanted);
        }
        return count;
    }

    private void scrollBy(int pixels) {
        list().scrollListBy(pixels);
        layoutPage();
    }

    private void scrollToTop() {
        ListView list = list();
        for (int i = 0; i < 40 && (list.getFirstVisiblePosition() > 0
                || list.getChildAt(0).getTop() < list.getPaddingTop()); i++) {
            scrollBy(-list.getHeight() / 3);
        }
        assertEquals("at the top", 0, list.getFirstVisiblePosition());
    }

    private void tap(Preference row) {
        assertNotNull(row.getOnPreferenceClickListener());
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
        layoutPage();
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h480dp-mdpi")
    public void rtlKeepsTheBackControlAndTitleInTheMirroredToolbar() throws Exception {
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        openDialog();
        TextView title = barTitle();
        View back = bar().getChildAt(0);
        assertEquals(View.LAYOUT_DIRECTION_RTL, title.getLayoutDirection());
        assertEquals(View.TEXT_ALIGNMENT_VIEW_START, title.getTextAlignment());
        assertTrue("the back control stays at the start edge", back.getLeft() > title.getLeft());
        assertNotNull("the title remains measured when hidden behind the in-page headline", title.getLayout());
        assertEquals("the settings window has no redundant Done button", 3,
                ((android.view.ViewGroup) dialog.getView()).getChildCount());
    }

    @Test public void overviewShowsTheExistingBrandAndVersionWithoutShimmer() throws Exception {
        openDialog();
        assertEquals(SettingsHeader.KEY, page.getPreferenceScreen().getPreference(0).getKey());
        TextView name = page.getView().findViewWithTag(SettingsHeader.NAME);
        TextView version = page.getView().findViewWithTag(SettingsHeader.VERSION);
        View icon = page.getView().findViewWithTag(SettingsHeader.ICON);
        assertNotNull(name);
        assertNotNull(version);
        assertNotNull(icon);
        assertEquals("HushGram", name.getText().toString());
        assertEquals("the brand heading uses the compact scale", 28f,
                name.getTextSize() / name.getResources().getDisplayMetrics().scaledDensity, 0.1f);
        assertEquals("the brand mark has a compact inset", 8,
                Math.round(page.getView().findViewWithTag(SettingsHeader.ICON).getLeft()
                        / page.getResources().getDisplayMetrics().density));
        assertFalse("the old shimmer treatment is no longer used", ShimmerTitle.wears(name));
        assertTrue(name.isAccessibilityHeading());
        assertTrue(version.getText().toString().startsWith("Version "));
        assertNotNull(((android.widget.ImageView) icon).getDrawable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
        assertEquals("the brand is shown once", 1, shown(dialog.getView(), "HushGram"));
        assertEquals("the toolbar leaves the visible brand to the header", View.INVISIBLE, barTitle().getVisibility());
    }

    @Test public void searchNamesItselfInTheToolbarAndTheBrandReturnsWhenCleared() throws Exception {
        openDialog();
        int height = bar().getHeight();
        page.searchSettings("feed");
        layoutPage();
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals("Search settings", barTitle().getText().toString());
        assertEquals(height, bar().getHeight());
        page.searchSettings("");
        layoutPage();
        scrollToTop();
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        assertEquals("the brand remains visible once", 1, shown(dialog.getView(), "HushGram"));
        assertEquals(height, bar().getHeight());
    }

    @Test public void scrollingTheBrandAwayRestoresTheToolbarTitle() throws Exception {
        openDialog();
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        scrollBy(list().getHeight());
        assertTrue(list().getFirstVisiblePosition() > 0);
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals("HushGram", barTitle().getText().toString());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
        scrollToTop();
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
    }

    @Test public void sectionPagesShowAHeadlineAndReturnToTheOverview() throws Exception {
        openDialog();
        tap(category("Feed"));
        HushgramPreferenceFragment.Heading section = category("Feed");
        assertTrue(section.pageHeader);
        assertFalse(section.collapsible);
        assertEquals("Feed", section.getTitle().toString());
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals("HushGram", barTitle().getText().toString());
        assertTrue(section.getPreferenceCount() > 0);
        assertTrue(page.closeCategory());
        layoutPage();
        assertFalse(category("Feed").pageHeader);
        assertTrue(category("Feed").collapsible);
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
    }

    @Test public void legacyPaletteSettingCannotChangeTheReferencePalette() throws Exception {
        openDialog();
        int surface = ScreenColors.current().card;
        int dialogSurface = ScreenColors.current().dialog;
        Settings.SETTINGS_BLACK_LOOK.save(false);
        assertEquals(ScreenColors.SURFACE_CONTAINER, surface);
        assertEquals(surface, ScreenColors.current().card);
        assertEquals(dialogSurface, ScreenColors.current().dialog);
        assertNotNull(page.getPreferenceScreen().findPreference(SettingsHeader.KEY));
        assertNull("the obsolete appearance toggle is not offered", page.getPreferenceScreen().findPreference(Settings.SETTINGS_BLACK_LOOK.key));
    }
}
