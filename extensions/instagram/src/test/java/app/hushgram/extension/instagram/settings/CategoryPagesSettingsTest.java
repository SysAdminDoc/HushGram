/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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

/** Categories expand on one screen, retain their choices, and return after search or recreation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h480dp-mdpi")
@SuppressWarnings("deprecation")
public class CategoryPagesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private SettingsDialog dialog;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.ONLY_FOLLOWING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED,
                PatchFamily.NOTIFICATION_GROUPS);
    }

    @After public void close() throws Exception {
        if (dialog != null) dialog.dismissAllowingStateLoss();
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.ONLY_FOLLOWING.resetToDefault();
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

    /** Titles of sections with visible children, or of expandable headers, in screen order. */
    private List<String> shown(Class<? extends Preference> type) {
        PreferenceScreen screen = page.getPreferenceScreen();
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            boolean row = item instanceof HushgramPreferenceFragment.Heading
                    && ((HushgramPreferenceFragment.Heading) item).collapsible;
            if (type == PreferenceCategory.class ? item instanceof PreferenceCategory
                    && ((PreferenceCategory) item).getPreferenceCount() > 0 : row) {
                titles.add(String.valueOf(item.getTitle()));
            }
        }
        return titles;
    }

    private HushgramPreferenceFragment.Heading categoryRow(String title) {
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)
                    && title.contentEquals(item.getTitle())) return (HushgramPreferenceFragment.Heading) item;
        }
        throw new AssertionError("no row for " + title + " in " + shown(Preference.class));
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    private int positionOf(Preference preference) {
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) == preference) return i;
        }
        throw new AssertionError("no visible row for " + preference.getTitle());
    }

    private String toolbarTitle() {
        LinearLayout bar = (LinearLayout) ((LinearLayout) dialog.getView()).getChildAt(0);
        return ((TextView) bar.getChildAt(1)).getText().toString();
    }

    private void openDialog() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "category_pages");
        controller.get().getFragmentManager().executePendingTransactions();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        layoutPage();
    }

    private void layoutPage() {
        // PreferenceGroupAdapter applies hierarchy changes on its next main-thread turn.
        ShadowLooper.idleMainLooper();
        // Lay out the actual dialog viewport. Measuring only its child page at a different
        // height lets the attached window's next traversal undo the scroll under test.
        View root = dialog.getView();
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(480 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        ShadowLooper.idleMainLooper();
    }

    @Test public void categoriesStartCollapsedWithSearchAndTheListOptionFirst() throws Exception {
        open();
        SwitchPreference pages = (SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key);
        assertNotNull(pages);
        assertEquals("Expandable categories", pages.getTitle().toString());
        assertTrue(pages.isChecked());
        assertTrue(shown(Preference.class).contains("Feed"));
        assertFalse(categoryRow("Feed").expanded);
        assertEquals(0, categoryRow("Feed").getPreferenceCount());
        assertEquals("hushgram_settings_search", page.getPreferenceScreen().getPreference(0).getKey());
        assertEquals("hushgram_view_toggle", page.getPreferenceScreen().getPreference(1).getKey());
    }

    @Test @Config(qualifiers = "zh-rTW-w320dp-h480dp-mdpi")
    public void traditionalChineseReachesTheSettingsCategoryAndBoundSwitch() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        Settings.HIDE_ADS.save(false);
        try {
            open();
            SwitchPreference ads = (SwitchPreference) page.findPreference(Settings.HIDE_ADS.key);
            assertNotNull(ads);
            assertEquals("廣告與隱私", ads.getParent().getTitle().toString());
            assertEquals("隱藏廣告", ads.getTitle().toString());
            assertFalse(ads.isChecked());

            ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
            View bound = null;
            for (int i = 0; i < adapter.getCount(); i++) {
                if (adapter.getItem(i) == ads) bound = adapter.getView(i, null, new FrameLayout(controller.get()));
            }
            assertNotNull("the real settings adapter bound the translated switch", bound);
            assertEquals("隱藏廣告", ((TextView) bound.findViewById(android.R.id.title)).getText().toString());
            assertEquals("隱藏贊助貼文、Reels、限時動態和留言中的廣告，原本顯示廣告的位置不會留下空白。",
                    ((TextView) bound.findViewById(android.R.id.summary)).getText().toString());
            ads.setChecked(true);
            assertTrue(Settings.HIDE_ADS.get());
        } finally {
            Settings.HIDE_ADS.resetToDefault();
        }
    }

    @Test public void theTopButtonSwitchesBetweenListAndCategories() throws Exception {
        open();
        HushgramPreferenceFragment.Heading feed = categoryRow("Feed");
        HushgramPreferenceFragment.Heading ads = categoryRow("Ads and privacy");
        tap(feed);
        Preference following = page.findPreference(Settings.START_ON_FOLLOWING.key);
        Preference button = page.findPreference("hushgram_view_toggle");
        assertEquals("Show all settings as a list", button.getTitle().toString());
        tap(button);
        assertFalse(Settings.CATEGORY_PAGES.savedValue());
        assertEquals("Show settings by category", button.getTitle().toString());
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(
                List.of("Settings entry", "Ads and privacy", "Feed", "Notifications", "Pause and diagnostics")));
        assertFalse(feed.collapsible);
        assertSame(following, page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        // The switch the long list shows says the same as the button did, so a tap on it changes the view.
        SwitchPreference pages = (SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key);
        assertSame(pages, page.getPreferenceScreen().findPreference(Settings.CATEGORY_PAGES.key));
        assertFalse(pages.isChecked());
        tap(button);
        assertTrue(Settings.CATEGORY_PAGES.savedValue());
        assertTrue(shown(Preference.class).contains("Feed"));
        assertTrue(pages.isChecked());
        assertSame(feed, categoryRow("Feed"));
        assertTrue(feed.expanded);
        assertSame(following, page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        assertFalse(ads.expanded);
        assertEquals(0, ads.getPreferenceCount());
        assertSame(button, page.getPreferenceScreen().getPreference(1));
    }

    @Test public void offTheCategoriesStayOneList() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        open();
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(
                List.of("Settings entry", "Ads and privacy", "Feed", "Notifications", "Pause and diagnostics")));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CATEGORY_PAGES.key));
    }

    /** Everyday controls lead; Settings entry and the upkeep sections follow them, About last. */
    @Test public void settingsAccessAndUpkeepComeAfterTheEverydayControls() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        Settings.CATEGORY_PAGES.save(true);
        open();
        List<String> categories = shown(Preference.class);
        assertEquals(categories.toString(), "Ads and privacy", categories.get(0));
        int entry = categories.indexOf("Settings entry");
        for (String everyday : List.of("Feed", "Messages", "Notifications", "Downloads", "Battery", "Version warnings")) {
            assertTrue(everyday + " before Settings entry: " + categories, categories.indexOf(everyday) >= 0
                    && categories.indexOf(everyday) < entry);
        }
        assertEquals(categories.toString(), List.of("Settings entry", "Set when you patched", "Settings backup",
                "Pause and diagnostics", "About"), categories.subList(entry, categories.size()));
        assertEquals("hushgram_settings_search", page.getPreferenceScreen().getPreference(0).getKey());
        assertEquals("hushgram_view_toggle", page.getPreferenceScreen().getPreference(1).getKey());
    }

    @Test public void onTheCategoriesAreListedWithPauseFoldedLikeTheRest() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        List<String> rows = shown(Preference.class);
        assertTrue(rows.toString(), rows.containsAll(List.of("Settings entry", "Ads and privacy", "Feed", "Notifications",
                "Pause and diagnostics")));
        assertEquals("nothing opens by itself while HushGram runs normally", List.of(), shown(PreferenceCategory.class));
        assertNull(page.getPreferenceScreen().findPreference(BaseSettings.DEBUG.key));
        assertTrue(categoryRow("Pause and diagnostics").getSummary().toString().contains("Pause HushGram"));
        assertTrue(categoryRow("Ads and privacy").getSummary().toString().contains("Hide ads"));
        // Rows off the screen are still the page's own, for syncing and saved choices.
        assertNotNull(page.findPreference(Settings.HIDE_ADS.key));
    }

    @Test public void eachHeaderExpandsItsSettingsBelowItAndCollapsesIndependently() throws Exception {
        open();
        PreferenceScreen screen = page.getPreferenceScreen();
        List<String> headers = shown(Preference.class);
        HushgramPreferenceFragment.Heading feed = categoryRow("Feed");
        HushgramPreferenceFragment.Heading ads = categoryRow("Ads and privacy");
        Preference following = page.findPreference(Settings.START_ON_FOLLOWING.key);
        Preference hideAds = page.findPreference(Settings.HIDE_ADS.key);
        tap(feed);
        assertSame(screen, page.getPreferenceScreen());
        assertTrue(feed.expanded);
        assertSame(feed, following.getParent());
        assertEquals(positionOf(feed) + 1, positionOf(feed.getPreference(0)));
        assertEquals(headers, shown(Preference.class));
        assertEquals(List.of("Feed"), shown(PreferenceCategory.class));

        tap(ads);
        assertTrue(feed.expanded);
        assertTrue(ads.expanded);
        assertSame(ads, hideAds.getParent());
        assertEquals(positionOf(ads) + 1, positionOf(ads.getPreference(0)));
        assertEquals(headers, shown(Preference.class));

        tap(feed);
        assertFalse(feed.expanded);
        assertEquals(0, feed.getPreferenceCount());
        assertNull(screen.findPreference(Settings.START_ON_FOLLOWING.key));
        assertSame(following, page.findPreference(Settings.START_ON_FOLLOWING.key));
        assertTrue(ads.expanded);
        assertSame(hideAds, screen.findPreference(Settings.HIDE_ADS.key));
        assertFalse("expanded categories add no Back level", page.closeCategory());

        tap(feed);
        assertSame(following, screen.findPreference(Settings.START_ON_FOLLOWING.key));
        assertEquals(headers, shown(Preference.class));
    }

    @Test public void collapsedRowsKeepTheirSavedChoicesAndParentAvailability() throws Exception {
        Settings.START_ON_FOLLOWING.save(true);
        open();
        HushgramPreferenceFragment.Heading feed = categoryRow("Feed");
        tap(feed);
        SwitchPreference parent = (SwitchPreference) page.findPreference(Settings.START_ON_FOLLOWING.key);
        SwitchPreference child = (SwitchPreference) page.findPreference(Settings.ONLY_FOLLOWING.key);
        assertTrue("an expanded heading must not disable its children", parent.isEnabled());
        assertTrue(child.isEnabled());
        child.setChecked(true);
        assertTrue(Settings.ONLY_FOLLOWING.savedValue());

        tap(feed);
        Settings.START_ON_FOLLOWING.save(false);
        ShadowLooper.idleMainLooper();
        assertSame(child, page.findPreference(Settings.ONLY_FOLLOWING.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ONLY_FOLLOWING.key));
        tap(feed);
        assertSame(parent, page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        assertSame(child, page.getPreferenceScreen().findPreference(Settings.ONLY_FOLLOWING.key));
        assertTrue(parent.isEnabled());
        assertFalse(parent.isChecked());
        assertFalse(child.isEnabled());
        assertTrue(child.isChecked());
        assertTrue(Settings.ONLY_FOLLOWING.savedValue());
    }

    @Test public void expansionKeepsTheSignInNoticeAndNavigationOnTheMainScreen() throws Exception {
        Settings.SIGN_IN_NOTICE_HIDDEN.save(false);
        Settings.CATEGORY_PAGES.save(true);
        open();
        PreferenceScreen screen = page.getPreferenceScreen();
        Preference notice = screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY);
        assertNotNull(notice);
        tap(categoryRow("Feed"));
        assertSame("expansion keeps the notice attached", notice,
                screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY));
        assertEquals("Search leads the page", "hushgram_settings_search", screen.getPreference(0).getKey());
        assertEquals("the list switch follows Search", "hushgram_view_toggle", screen.getPreference(1).getKey());
        assertEquals("the notice follows navigation", HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY,
                screen.getPreference(2).getKey());

        assertTrue(notice.getOnPreferenceClickListener().onPreferenceClick(notice));
        assertTrue(Settings.SIGN_IN_NOTICE_HIDDEN.savedValue());
        tap(categoryRow("Feed"));
        tap(categoryRow("Feed"));
        assertNull("a notice hidden by a tap stays hidden",
                screen.findPreference(HushgramPreferenceFragment.SIGN_IN_NOTICE_KEY));
    }

    @Test public void searchRevealsCollapsedMatchesThenRestoresEveryExpansion() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        tap(categoryRow("Feed"));
        tap(categoryRow("Notifications"));
        List<String> headers = shown(Preference.class);
        Preference feed = categoryRow("Feed");
        Preference ads = page.findPreference(Settings.HIDE_ADS.key);
        page.searchSettings("Hide ads");
        assertTrue(shown(PreferenceCategory.class).contains("Ads and privacy"));
        assertFalse(shown(PreferenceCategory.class).contains("Feed"));
        assertTrue(shown(Preference.class).isEmpty());
        assertSame(ads, page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));

        page.searchSettings("");
        assertEquals(List.of("Feed", "Notifications"), shown(PreferenceCategory.class));
        assertEquals(headers, shown(Preference.class));
        assertSame(feed, categoryRow("Feed"));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        assertSame(ads, page.findPreference(Settings.HIDE_ADS.key));
        assertFalse(categoryRow("Ads and privacy").expanded);
    }

    @Test public void expansionAndSearchKeepTheSameLiveInput() throws Exception {
        openDialog();
        EditText input = page.getView().findViewWithTag("hushgram-settings-search");
        tap(categoryRow("Feed"));
        layoutPage();
        assertNotNull(page.getPreferenceScreen().findPreference("hushgram_settings_search"));
        assertNotNull(input);
        assertSame(input, page.getView().findViewWithTag("hushgram-settings-search"));
        assertEquals("HushGram", toolbarTitle());
        assertTrue(input.isShown());
        assertEquals("Search settings", input.getContentDescription());
        touchMode(input, true);
        assertTrue(input.requestFocus());
        layoutPage();
        input.setText("Hide ads");
        layoutPage();
        assertSame("typing keeps the live input", input,
                page.getView().findViewWithTag("hushgram-settings-search"));
        assertTrue(input.hasFocus());
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        assertFalse(shown(PreferenceCategory.class).contains("Feed"));
        assertTrue("a search shows the descriptions it matched (#130)", DescriptionInfo.searching);
        input.setText("");
        layoutPage();
        assertFalse(DescriptionInfo.searching);
        assertEquals(List.of("Feed"), shown(PreferenceCategory.class));
        assertSame(input, page.getView().findViewWithTag("hushgram-settings-search"));
        assertTrue(input.hasFocus());
        assertEquals("HushGram", toolbarTitle());
    }

    @Test public void backClearsSearchWithoutCollapsingTheExpandedSections() throws Exception {
        openDialog();
        tap(categoryRow("Feed"));
        tap(categoryRow("Notifications"));
        layoutPage();
        EditText input = page.getView().findViewWithTag("hushgram-settings-search");
        assertNotNull(input);
        touchMode(input, true);
        assertTrue(input.requestFocus());
        layoutPage();
        input.setText("Hide ads");
        layoutPage();
        assertTrue(shown(PreferenceCategory.class).contains("Ads and privacy"));

        assertTrue("Back ends the search", page.closeCategory());
        layoutPage();
        assertEquals(List.of("Feed", "Notifications"), shown(PreferenceCategory.class));
        input = page.getView().findViewWithTag("hushgram-settings-search");
        assertNotNull(input);
        assertEquals("", input.getText().toString());
        assertEquals("HushGram", toolbarTitle());
        assertFalse("the next Back closes settings", page.closeCategory());
        assertTrue(categoryRow("Feed").expanded);
        assertTrue(categoryRow("Notifications").expanded);

        tap(categoryRow("Feed"));
        tap(categoryRow("Notifications"));
        page.searchSettings("Hide ads");
        layoutPage();
        assertFalse("the search took the category rows off", shown(Preference.class).contains("Feed"));
        assertTrue("Back ends a search started from the list too", page.closeCategory());
        layoutPage();
        assertTrue(shown(Preference.class).contains("Feed"));
        assertFalse("and the Back after it closes settings", page.closeCategory());
    }

    @Test public void aLaterHeaderKeepsItsViewportWhenExpandedAndCollapsedOutsideTouchMode() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        openDialog();
        touchMode(page.getView(), false);
        List<String> categories = shown(Preference.class);
        assertEquals(23, categories.size());
        HushgramPreferenceFragment.Heading header = categoryRow(categories.get(categories.size() / 2));
        ListView list = page.getView().findViewById(android.R.id.list);
        list.setSelectionFromTop(positionOf(header), -8);
        layoutPage();
        assertTrue("the test must start below Search", list.getFirstVisiblePosition() > 0);
        assertSame(header, list.getItemAtPosition(list.getFirstVisiblePosition()));
        int top = list.getChildAt(0).getTop() - list.getPaddingTop();
        for (boolean expanded : new boolean[]{true, false}) {
            int position = positionOf(header);
            View row = list.getChildAt(position - list.getFirstVisiblePosition());
            assertNotNull("the header must be visible for its tap", row);
            assertTrue(list.performItemClick(row, position, list.getItemIdAtPosition(position)));
            layoutPage();
            assertEquals(expanded, header.expanded);
            assertSame("the tapped header remains the viewport anchor", header,
                    list.getItemAtPosition(list.getFirstVisiblePosition()));
            assertEquals(top, list.getChildAt(0).getTop() - list.getPaddingTop());
            assertEquals(categories, shown(Preference.class));
            assertEquals("HushGram", toolbarTitle());
        }
    }

    /**
     * The two views that hold the focus for touch aren't stops under a keyboard. Left focusable, the
     * screen's root took the focus a field gave up, the arrows couldn't leave it, and its focus
     * highlight grayed the whole screen (S25, 2026-10-13).
     */
    @Test public void aKeyboardNeverLandsOnTheViewsThatHoldFocusForTouch() throws Exception {
        openDialog();
        layoutPage();
        View root = dialog.getView();
        View body = page.getView();
        ListView list = body.findViewById(android.R.id.list);
        touchMode(body, true);
        assertTrue(root.isFocusableInTouchMode());
        assertTrue(body.isFocusableInTouchMode());
        assertFalse(root.getDefaultFocusHighlightEnabled());
        assertFalse(body.getDefaultFocusHighlightEnabled());
        assertTrue("touch keeps the focus off the rows: " + root.findFocus(), body.requestFocus() && body.isFocused());

        touchMode(body, false);
        assertFalse(root.isFocusable());
        assertFalse(body.isFocusable());
        assertTrue("the focus a rest gives up goes to the rows: " + root.findFocus(), list.hasFocus());

        EditText input = body.findViewWithTag("hushgram-settings-search");
        assertNotNull(input);
        assertTrue(input.requestFocus());
        input.clearFocus();
        assertTrue("a field giving its focus up lands in the rows: " + root.findFocus(), list.hasFocus());

        tap(categoryRow("Feed"));
        layoutPage();
        assertFalse(body.isFocusable());
        assertTrue("expansion by keyboard keeps focus in the rows: " + root.findFocus(), list.hasFocus());

        touchMode(body, true);
        assertTrue(root.isFocusableInTouchMode());
        assertTrue(body.isFocusableInTouchMode());
    }

    /**
     * A search that starts brings its field to the top of the list. Under the status card and the
     * sign-in notice it sat two thirds down a phone's screen, so the matches came up behind the
     * keyboard (S25, 2026-10-13).
     */
    @Test public void startingASearchBringsItsFieldToTheTopOfTheList() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        openDialog();
        ListView list = page.getView().findViewById(android.R.id.list);
        EditText input = page.getView().findViewWithTag("hushgram-settings-search");
        assertNotNull(input);
        touchMode(input, true);
        assertTrue(input.requestFocus());
        input.setText("hide");
        layoutPage();
        Preference field = page.getPreferenceScreen().findPreference("hushgram_settings_search");
        int at = -1;
        for (int i = 0; i < list.getAdapter().getCount(); i++) if (list.getAdapter().getItem(i) == field) at = i;
        assertEquals("Search remains the first row while typing", 0, at);
        assertEquals(at, list.getFirstVisiblePosition());
        assertEquals(list.getPaddingTop(), list.getChildAt(0).getTop());
        assertTrue("moving the list lost typing focus: " + page.getView().findFocus(), input.hasFocus());

        list.setSelectionFromTop(0, 0);
        layoutPage();
        input.setText("hide s");
        layoutPage();
        assertEquals("typing on leaves the list where the reader put it", 0, list.getFirstVisiblePosition());
    }

    private static void touchMode(View view, boolean value) {
        Object root = org.robolectric.util.ReflectionHelpers.callInstanceMethod(view, "getViewRootImpl");
        assertNotNull("The test must change the attached window's touch mode", root);
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(root, "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, value));
        assertEquals(value, view.isInTouchMode());
    }

    /**
     * As the page's first row, Restart now was two screens above a switch far down the Tab bar page,
     * and putting it in moved the rows under the finger (S25, 2026-10-13). It's the screen's bar
     * under the page now: in view wherever the page is scrolled, and no row goes in or out.
     */
    @Test public void aPendingRestartShowsItsBarBelowTheListAndMovesNoRow() throws Exception {
        openDialog();
        Preference homeControl = page.getPreferenceScreen().findPreference("hushgram_view_toggle");
        View bar = dialog.getView().findViewById(SettingsDialog.RESTART_ID);
        tap(categoryRow("Feed"));
        layoutPage();
        assertSame(page.getPreferenceScreen(), homeControl.getParent());
        assertEquals("nothing waits on a restart yet", View.GONE, bar.getVisibility());
        ListView list = page.getView().findViewById(android.R.id.list);
        int rows = list.getAdapter().getCount();
        SwitchPreference following = (SwitchPreference) page.findPreference(Settings.START_ON_FOLLOWING.key);
        boolean before = following.isChecked();
        following.setChecked(!before);
        layoutPage();
        assertEquals("the bar offers the restart", View.VISIBLE, bar.getVisibility());
        assertTrue(bar.isClickable());
        assertEquals("Some changes apply when Instagram restarts. Tap to restart it now.",
                ((android.widget.TextView) bar).getText().toString());
        assertTrue("under the page, not over its rows",
                bar.getTop() >= dialog.getView().findViewById(SettingsDialog.CONTAINER_ID).getBottom());
        assertSame("the list option stays attached", page.getPreferenceScreen(), homeControl.getParent());
        assertEquals(rows, list.getAdapter().getCount());
        assertEquals("hushgram_settings_search", page.getPreferenceScreen().getPreference(0).getKey());
        following.setChecked(before);
        layoutPage();
        assertEquals("a resolved restart takes the bar off", View.GONE, bar.getVisibility());
        assertEquals(rows, list.getAdapter().getCount());
    }

    @Test public void recreationKeepsExpandedSectionsAndScrollWithoutSavingTheSearchText() throws Exception {
        PatchFamily.inBuildForTests.add(PatchFamily.REEL_DECLUTTER);
        openDialog();
        tap(categoryRow("Feed"));
        tap(categoryRow("Reels"));
        layoutPage();
        ListView list = page.getView().findViewById(android.R.id.list);
        list.setSelectionFromTop(positionOf(categoryRow("Reels")) + 2, -8);
        layoutPage();
        int position = list.getFirstVisiblePosition();
        int top = list.getChildAt(0).getTop() - list.getPaddingTop();
        assertTrue("the test must leave the top of the page: first=" + position
                + ", last=" + list.getLastVisiblePosition() + ", count=" + list.getCount()
                + ", height=" + list.getHeight() + ", focus=" + page.getView().findFocus(), position > 0);
        Bundle state = new Bundle();
        page.onSaveInstanceState(state);
        assertFalse(state.containsKey("hushgram_settings_query"));
        List<String> expanded = state.getStringArrayList("hushgram_expanded_categories");
        assertNotNull(expanded);
        assertEquals(2, expanded.size());
        assertTrue(expanded.containsAll(
                List.of(categoryRow("Feed").getKey(), categoryRow("Reels").getKey())));

        controller.recreate();
        ShadowLooper.idleMainLooper();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("category_pages");
        assertNotNull(dialog);
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        layoutPage();
        assertEquals(List.of("Feed", "Reels"), shown(PreferenceCategory.class));
        assertTrue(categoryRow("Feed").expanded);
        assertTrue(categoryRow("Reels").expanded);
        assertEquals("HushGram", toolbarTitle());
        list = page.getView().findViewById(android.R.id.list);
        assertEquals(position, list.getFirstVisiblePosition());
        assertEquals(top, list.getChildAt(0).getTop() - list.getPaddingTop());
        page.searchSettings("location setup");
        controller.recreate();
        ShadowLooper.idleMainLooper();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("category_pages");
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                .findFragmentById(SettingsDialog.CONTAINER_ID);
        layoutPage();
        assertEquals("expansions return without retaining what was searched",
                List.of("Feed", "Reels"),
                shown(PreferenceCategory.class));
        EditText input = page.getView().findViewWithTag("hushgram-settings-search");
        assertNotNull("the restored page starts at its search field", input);
        assertEquals("", input.getText().toString());
        assertFalse(input.isSaveEnabled());
        assertFalse(page.closeCategory());
        assertTrue(shown(Preference.class).contains("Reels"));
        assertEquals("HushGram", toolbarTitle());
    }

    @Test public void recreationAlsoKeepsOverviewAndFlatListScroll() throws Exception {
        for (boolean pages : new boolean[]{true, false}) {
            Settings.CATEGORY_PAGES.save(pages);
            openDialog();
            ListView list = page.getView().findViewById(android.R.id.list);
            list.setSelectionFromTop(3, -8);
            layoutPage();
            int position = list.getFirstVisiblePosition();
            int top = list.getChildAt(0).getTop() - list.getPaddingTop();
            assertTrue("the test must scroll the " + (pages ? "overview" : "flat list"), position > 0);
            controller.recreate();
            ShadowLooper.idleMainLooper();
            dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("category_pages");
            page = (HushgramPreferenceFragment) dialog.getChildFragmentManager()
                    .findFragmentById(SettingsDialog.CONTAINER_ID);
            layoutPage();
            list = page.getView().findViewById(android.R.id.list);
            assertEquals(position, list.getFirstVisiblePosition());
            assertEquals(top, list.getChildAt(0).getTop() - list.getPaddingTop());
            if (pages) assertEquals(List.of(), shown(PreferenceCategory.class));
            assertFalse("restoration adds no Back level", page.closeCategory());
            dialog.dismissAllowingStateLoss();
            controller.close();
            dialog = null;
            controller = null;
        }
    }

    @Test public void theSettingsSwitchChangesModeAndKeepsTheExpandedSections() throws Exception {
        Settings.CATEGORY_PAGES.save(false);
        open();
        ((SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.CATEGORY_PAGES.savedValue());
        assertTrue(shown(Preference.class).contains("Feed"));

        tap(categoryRow("Settings entry"));
        tap(categoryRow("Feed"));
        ((SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key)).setChecked(false);
        ShadowLooper.idleMainLooper();
        assertTrue(shown(Preference.class).isEmpty());
        assertTrue(shown(PreferenceCategory.class).containsAll(List.of("Settings entry", "Feed", "Ads and privacy")));
        assertFalse(page.closeCategory());
        ((SwitchPreference) page.findPreference(Settings.CATEGORY_PAGES.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(categoryRow("Settings entry").expanded);
        assertTrue(categoryRow("Feed").expanded);
        assertFalse(categoryRow("Ads and privacy").expanded);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        assertFalse(page.closeCategory());
    }

    @Test public void pauseLeavesTheListAsChosen() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        BaseSettings.PAUSED.save(true);
        try {
            open();
            assertTrue(shown(Preference.class).contains("Feed"));
        } finally {
            BaseSettings.PAUSED.save(false);
        }
    }

    @Test public void theDialogsBackClosesSettingsWithExpandedCategories() throws Exception {
        openDialog();
        tap(categoryRow("Notifications"));
        tap(categoryRow("Feed"));
        Dialog window = dialog.getDialog();

        window.onBackPressed();
        ShadowLooper.idleMainLooper();
        assertFalse("expansion adds no separate screen to close", window.isShowing());
        dialog = null;
    }

    @Test public void theToolbarBackClosesSettingsWithExpandedCategories() throws Exception {
        openDialog();
        tap(categoryRow("Feed"));
        Dialog window = dialog.getDialog();
        LinearLayout bar = (LinearLayout) ((LinearLayout) dialog.getView()).getChildAt(0);
        assertTrue(bar.getChildAt(0).performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(window.isShowing());
        dialog = null;
    }
}
