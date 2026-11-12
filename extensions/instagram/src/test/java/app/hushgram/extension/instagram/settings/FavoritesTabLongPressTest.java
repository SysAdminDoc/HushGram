/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.StoryTimeSettingsTest.sectionOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.LinearLayout;

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
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.download.SavedProfilesRows;
import app.hushgram.extension.instagram.feed.FavoritesFeedForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The tab long press that opens Favorites and the switch that keeps the tab bar there (#643): the
 * chosen tab hands Favorites to Home's feed switch, and every other tab, the choice off, Pause, a
 * build without the hooks and a switch from an older activity keep Instagram's own action.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class FavoritesTabLongPressTest {
    enum Tab { FEED, SEARCH, CLIPS, DIRECT, PROFILE }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private View search;
    private View profile;
    private final AtomicInteger stockSearch = new AtomicInteger();
    private final AtomicInteger stockProfile = new AtomicInteger();
    private final List<Object[]> shown = new ArrayList<>();

    @Before public void setUp() {
        PauseForTests.resume();
        BaseSettings.PAUSED.save(false);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FOLLOWING_FEED);
        SettingsEntry.onClosedByUser();
        ShadowDialog.reset();
        NavigationSettings.forgetTabsForTests();
        controller = Robolectric.buildActivity(Activity.class).setup();
        controller.windowFocusChanged(true);
        Activity activity = controller.get();
        LinearLayout bar = new LinearLayout(activity);
        search = new View(activity);
        profile = new View(activity);
        bar.addView(search, new LinearLayout.LayoutParams(60, 60));
        bar.addView(profile, new LinearLayout.LayoutParams(60, 60));
        activity.setContentView(bar);
        bar.layout(0, 0, 300, 100);
        search.layout(0, 0, 60, 60);
        profile.layout(60, 0, 120, 60);
        FavoritesFeedForTests.use(shown);
        FavoritesFeedForTests.switchBuiltFor(activity);
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        Settings.QUALITY_TAB_TARGET.save(NavigationTarget.OFF);
        Settings.GHOST_TAB_TARGET.save(NavigationTarget.OFF);
        Settings.SAVED_PROFILES_TAB_TARGET.save(NavigationTarget.OFF);
        Settings.FAVORITES_TAB_TARGET.save(NavigationTarget.SEARCH);
        bind(search, Tab.SEARCH, stockSearch);
        bind(profile, Tab.PROFILE, stockProfile);
    }

    @After public void tearDown() throws Exception {
        Dialog open = ShadowDialog.getLatestDialog();
        if (open != null) open.dismiss();
        if (controller != null) controller.close();
        FavoritesFeedForTests.reset();
        PatchFamily.inBuildForTests = null;
        PatchFamily.favoritesFeedForTests = null;
        PauseForTests.resume();
        SettingsEntry.onClosedByUser();
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        Settings.QUALITY_TAB_TARGET.resetToDefault();
        Settings.GHOST_TAB_TARGET.resetToDefault();
        Settings.SAVED_PROFILES_TAB_TARGET.resetToDefault();
        Settings.SAVED_PROFILES.resetToDefault();
        Settings.FAVORITES_TAB_TARGET.resetToDefault();
        Settings.FAVORITES_TAB_BAR.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Utils.awaitBackgroundTasksForTests();
        SavedProfilesRows.clear(RuntimeEnvironment.getApplication());
        SavedProfilesRows.forget();
        SavedProfilesPage.forgetPictures();
    }

    private static void bind(View button, Tab tab, AtomicInteger stock) {
        button.setOnLongClickListener(NavigationSettings.remember(button, tab, view -> {
            stock.incrementAndGet();
            return true;
        }));
        NavigationSettings.bind(button, tab);
    }

    private void assertOpenedFavorites() {
        assertEquals(1, shown.size());
        assertSame(FavoritesFeedForTests.Feed.FAVORITES, shown.get(0)[1]);
    }

    @Test public void theChosenTabOpensFavorites() {
        assertTrue(search.performLongClick());
        assertEquals("the tab's own hold waits", 0, stockSearch.get());
        assertOpenedFavorites();
    }

    @Test public void anotherTabKeepsItsOwnAction() {
        assertTrue(profile.performLongClick());
        assertEquals(1, stockProfile.get());
        assertTrue(shown.isEmpty());
    }

    @Test public void offLeavesEveryTabAlone() {
        Settings.FAVORITES_TAB_TARGET.save(NavigationTarget.OFF);
        NavigationSettings.applyChoice();
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertTrue(shown.isEmpty());
    }

    @Test public void theProfileTabCanBeChosen() {
        Settings.FAVORITES_TAB_TARGET.save(NavigationTarget.PROFILE);
        NavigationSettings.applyChoice();
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertTrue(shown.isEmpty());
        assertTrue(profile.performLongClick());
        assertEquals("the account switcher waits", 0, stockProfile.get());
        assertOpenedFavorites();
    }

    @Test public void pausedHushGramLeavesTheTabAlone() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertTrue(shown.isEmpty());
    }

    @Test public void withoutTheHooksTheTabKeepsItsOwnAction() {
        PatchFamily.favoritesFeedForTests = false;
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertTrue(shown.isEmpty());
    }

    /** A feed switch Instagram built for an activity that's gone can't open pages here, so the tab's own hold runs. */
    @Test public void aSwitchFromAnotherActivityHandsThePressBack() {
        ActivityController<Activity> older = Robolectric.buildActivity(Activity.class).setup();
        try {
            FavoritesFeedForTests.switchBuiltFor(older.get());
            assertTrue(search.performLongClick());
            assertEquals(1, stockSearch.get());
            assertTrue(shown.isEmpty());
        } finally {
            older.close();
        }
    }

    @Test public void savedProfilesOnTheSameTabComesFirst() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.FOLLOWING_FEED, PatchFamily.PROFILE_PICTURE);
        Settings.SAVED_PROFILES.save(true);
        Settings.SAVED_PROFILES_TAB_TARGET.save(NavigationTarget.SEARCH);
        assertTrue(search.performLongClick());
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
        Dialog page = ShadowDialog.getLatestDialog();
        assertTrue("Your saved profiles is open", page != null && page.isShowing());
        assertTrue(shown.isEmpty());
        assertEquals(0, stockSearch.get());
    }

    /** The haptic answer is HushGram's while the press opens Favorites, and Instagram's otherwise. */
    @Test @Config(sdk = 37) public void theHapticIsInstagramsWheneverFavoritesIsNotOffered() {
        AtomicInteger asked = new AtomicInteger();
        View.OnLongClickListener own = new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                stockSearch.incrementAndGet();
                return true;
            }

            @Override public boolean onLongClickUseDefaultHapticFeedback(View view) {
                asked.incrementAndGet();
                return false;
            }
        };
        search.setOnLongClickListener(NavigationSettings.remember(search, Tab.SEARCH, own));
        NavigationSettings.bind(search, Tab.SEARCH);
        View.OnLongClickListener press = shadowOf(search).getOnLongClickListener();
        assertTrue("Favorites' own press", press.onLongClickUseDefaultHapticFeedback(search));
        assertEquals(0, asked.get());
        PatchFamily.favoritesFeedForTests = false;
        assertFalse("not in this build", press.onLongClickUseDefaultHapticFeedback(search));
        PatchFamily.favoritesFeedForTests = null;
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", press.onLongClickUseDefaultHapticFeedback(search));
        assertEquals(2, asked.get());
    }

    private HushgramPreferenceFragment page() throws Exception {
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        return page;
    }

    /** Both rows sit under Feed, the switch first, start off, say what they do and are backed up. */
    @Test public void theRowsSitUnderFeedAndStartOff() throws Exception {
        Settings.FAVORITES_TAB_TARGET.resetToDefault();
        HushgramPreferenceFragment page = page();
        SwitchPreference bar = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FAVORITES_TAB_BAR.key);
        assertNotNull(bar);
        assertEquals("Feed", String.valueOf(sectionOf(page.getPreferenceScreen(), bar).getTitle()));
        assertEquals("Keep the tab bar on Favorites", String.valueOf(bar.getTitle()));
        assertFalse(bar.isChecked());
        assertFalse("the host asks again on every page change", Settings.FAVORITES_TAB_BAR.rebootApp);
        assertTrue(PatchFamily.FOLLOWING_FEED.switches.contains(Settings.FAVORITES_TAB_BAR));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FAVORITES_TAB_BAR.key));

        Preference found = page.getPreferenceScreen().findPreference(Settings.FAVORITES_TAB_TARGET.key);
        assertTrue(found instanceof HushgramPreferenceFragment.NavigationRow);
        ListPreference row = (ListPreference) found;
        assertEquals("Open Favorites with a tab long press", row.getTitle().toString());
        assertEquals("OFF", row.getValue());
        assertEquals("Tab long presses keep Instagram's own action. Choose one to open Favorites instead.",
                row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertNotNull(feed);
        int at = -1;
        for (int i = 0; i < feed.getPreferenceCount(); i++) {
            if (feed.getPreference(i) == bar) at = i;
        }
        assertTrue(at >= 0);
        assertSame(row, feed.getPreference(at + 1));

        row.setValue("PROFILE");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(NavigationTarget.PROFILE, Settings.FAVORITES_TAB_TARGET.savedValue());
        assertTrue(row.getSummary().toString(),
                row.getSummary().toString().startsWith("Long press Profile to open Favorites"));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FAVORITES_TAB_TARGET.key));
    }

    @Test public void withoutTheHooksThereAreNoRowsAndTheReportSaysWhy() throws Exception {
        PatchFamily.favoritesFeedForTests = false;
        HushgramPreferenceFragment page = page();
        assertNull(page.getPreferenceScreen().findPreference(Settings.FAVORITES_TAB_TARGET.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FAVORITES_TAB_BAR.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.FAVORITES_TAB_TARGET.key));
        assertTrue(PatchFamily.reportLines(EnumSet.of(PatchFamily.FOLLOWING_FEED), false).contains(
                "  Favorites from the tab bar: not in this build (Home's feed switch or the tab bar didn't match)"));
        PatchFamily.favoritesFeedForTests = true;
        assertFalse(String.join("\n", PatchFamily.reportLines(EnumSet.of(PatchFamily.FOLLOWING_FEED), false))
                .contains("Favorites from the tab bar"));
    }
}
