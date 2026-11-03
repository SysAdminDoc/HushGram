/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Rect;
import android.preference.Preference;
import android.preference.SwitchPreference;
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

/**
 * The main page shows HushGram's name once: the header's while Black settings page has it on the
 * screen, and the bar's otherwise. A search names itself in the bar either way.
 */
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

    private TextView barTitle() {
        return (TextView) bar().getChildAt(1);
    }

    private ListView list() {
        return page.getView().findViewById(android.R.id.list);
    }

    /**
     * Scrolls the page by [pixels], down for more than zero, the way a finger does. A selection
     * would skip the header, which isn't selectable, and land on the first row that is.
     */
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

    /** How many views a person sees reading [wanted]: shown, and not scrolled or clipped away. */
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

    /** What a tap on the Black settings page switch does: its change listener, then the new state. */
    private void flip(boolean on) {
        Preference row = page.findPreference(Settings.SETTINGS_BLACK_LOOK.key);
        assertTrue("no Black settings page switch", row instanceof SwitchPreference);
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, on));
        ((SwitchPreference) row).setChecked(on);
        layoutPage();
    }

    @Test public void theBlackPageShowsTheNameOnceInItsHeader() throws Exception {
        openDialog();
        assertTrue("Black settings page is on by default", Settings.SETTINGS_BLACK_LOOK.savedValue());
        assertEquals(SettingsHeader.KEY, page.getPreferenceScreen().getPreference(0).getKey());
        TextView name = page.getView().findViewWithTag(SettingsHeader.NAME);
        assertNotNull("the header's name is on the page", name);
        assertTrue(name.isShown());
        assertEquals("the bar leaves the name to the header", View.INVISIBLE, barTitle().getVisibility());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
        assertTrue("Back stays in the bar", bar().getChildAt(0).isShown());
    }

    @Test public void aSearchNamesItselfInTheBarAndClearingItHandsTheNameBack() throws Exception {
        openDialog();
        int height = bar().getHeight();
        page.searchSettings("feed");
        layoutPage();
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals("Search settings", barTitle().getText().toString());
        assertEquals("the rows don't move when the title shows", height, bar().getHeight());
        page.searchSettings("");
        layoutPage();
        // The search scrolled its field to the top and the header away, so the bar names the page.
        assertEquals("the page is named once after a search", 1, shown(dialog.getView(), "HushGram"));
        scrollToTop();
        assertEquals("back at the top the header has the name", View.INVISIBLE, barTitle().getVisibility());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
        assertEquals(height, bar().getHeight());
    }

    @Test public void scrollingTheHeaderAwayPutsTheNameBackInTheBar() throws Exception {
        openDialog();
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        scrollBy(list().getHeight());
        assertTrue("the header row has scrolled off", list().getFirstVisiblePosition() > 0);
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals("HushGram", barTitle().getText().toString());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
        scrollToTop();
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
    }

    @Test public void theClassicPageKeepsTheNameInTheBar() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(false);
        openDialog();
        assertNull("no header", page.getPreferenceScreen().findPreference(SettingsHeader.KEY));
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals("HushGram", barTitle().getText().toString());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
    }

    @Test public void flippingBlackSettingsPageMovesTheName() throws Exception {
        Settings.SETTINGS_BLACK_LOOK.save(false);
        openDialog();
        flip(true);
        assertEquals(SettingsHeader.KEY, page.getPreferenceScreen().getPreference(0).getKey());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
        scrollToTop();
        assertEquals(View.INVISIBLE, barTitle().getVisibility());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
        flip(false);
        assertNull(page.getPreferenceScreen().findPreference(SettingsHeader.KEY));
        assertEquals(View.VISIBLE, barTitle().getVisibility());
        assertEquals(1, shown(dialog.getView(), "HushGram"));
    }
}
