/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

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

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.download.SavedProfilesRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The tab long press that opens Your saved profiles (#629): the chosen tab opens the list while
 * Saved profiles is on, and every other tab, the switch off, Pause and a build without the profile
 * picture patch keep Instagram's own action.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SavedProfilesTabLongPressTest {
    enum Tab { FEED, SEARCH, CLIPS, DIRECT, PROFILE }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private View search;
    private View profile;
    private final AtomicInteger stockSearch = new AtomicInteger();
    private final AtomicInteger stockProfile = new AtomicInteger();

    @Before public void setUp() {
        PauseForTests.resume();
        BaseSettings.PAUSED.save(false);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
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
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        Settings.QUALITY_TAB_TARGET.save(NavigationTarget.OFF);
        Settings.GHOST_TAB_TARGET.save(NavigationTarget.OFF);
        Settings.SAVED_PROFILES.save(true);
        Settings.SAVED_PROFILES_TAB_TARGET.save(NavigationTarget.SEARCH);
        bind(search, Tab.SEARCH, stockSearch);
        bind(profile, Tab.PROFILE, stockProfile);
    }

    @After public void tearDown() throws Exception {
        Dialog open = ShadowDialog.getLatestDialog();
        if (open != null) open.dismiss();
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        SettingsEntry.onClosedByUser();
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        Settings.QUALITY_TAB_TARGET.resetToDefault();
        Settings.GHOST_TAB_TARGET.resetToDefault();
        Settings.SAVED_PROFILES_TAB_TARGET.resetToDefault();
        Settings.SAVED_PROFILES.resetToDefault();
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

    private static Dialog shownPage() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
        Dialog page = ShadowDialog.getLatestDialog();
        return page != null && page.isShowing() ? page : null;
    }

    @Test public void theChosenTabOpensTheListEvenBeforeAnythingIsSaved() throws Exception {
        assertTrue(search.performLongClick());
        assertEquals("the tab's own hold waits", 0, stockSearch.get());
        assertNotNull("Your saved profiles is open", shownPage());

        shownPage().dismiss();
        SavedProfilesRows.save(RuntimeEnvironment.getApplication(), "1", "ann", "", "", 1_000L);
        assertTrue(search.performLongClick());
        assertNotNull(shownPage());
        assertEquals(0, stockSearch.get());
    }

    @Test public void anotherTabKeepsItsOwnAction() throws Exception {
        assertTrue(profile.performLongClick());
        assertEquals(1, stockProfile.get());
        assertNull(shownPage());
    }

    @Test public void offLeavesEveryTabAlone() throws Exception {
        Settings.SAVED_PROFILES_TAB_TARGET.save(NavigationTarget.OFF);
        NavigationSettings.applyChoice();
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertNull(shownPage());
    }

    @Test public void theProfileTabCanBeChosen() throws Exception {
        Settings.SAVED_PROFILES_TAB_TARGET.save(NavigationTarget.PROFILE);
        NavigationSettings.applyChoice();
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertNull(shownPage());
        assertTrue(profile.performLongClick());
        assertEquals("the account switcher waits", 0, stockProfile.get());
        assertNotNull(shownPage());
    }

    @Test public void withSavedProfilesOffTheTabKeepsItsOwnAction() throws Exception {
        Settings.SAVED_PROFILES.save(false);
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertNull(shownPage());
    }

    @Test public void pausedHushGramLeavesTheTabAlone() throws Exception {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertNull(shownPage());
    }

    @Test public void withoutTheProfilePicturePatchTheTabKeepsItsOwnAction() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        assertTrue(search.performLongClick());
        assertEquals(1, stockSearch.get());
        assertNull(shownPage());
    }

    /** The haptic answer is HushGram's while the press opens the list, and Instagram's otherwise. */
    @Test @Config(sdk = 37) public void theHapticIsInstagramsWheneverTheListIsNotOffered() {
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
        assertTrue("the list's own press", press.onLongClickUseDefaultHapticFeedback(search));
        assertEquals(0, asked.get());
        Settings.SAVED_PROFILES.save(false);
        assertFalse("switched off", press.onLongClickUseDefaultHapticFeedback(search));
        Settings.SAVED_PROFILES.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", press.onLongClickUseDefaultHapticFeedback(search));
        assertEquals(2, asked.get());
    }

    /** The row sits right after Your saved profiles, starts off, says what it does and is backed up. */
    @Test public void theRowSitsUnderYourSavedProfilesAndStartsOff() throws Exception {
        Settings.SAVED_PROFILES_TAB_TARGET.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        Preference found = page.getPreferenceScreen().findPreference(Settings.SAVED_PROFILES_TAB_TARGET.key);
        assertTrue(found instanceof HushgramPreferenceFragment.NavigationRow);
        ListPreference row = (ListPreference) found;
        assertEquals("Open Saved profiles with a tab long press", row.getTitle().toString());
        assertEquals("OFF", row.getValue());
        assertEquals("Tab long presses keep Instagram's own action. Choose one to open Saved profiles instead.",
                row.getSummary().toString());
        PreferenceGroup profiles = row.getParent();
        assertNotNull(profiles);
        Preference list = page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.SAVED_PROFILES_ROW);
        int at = -1;
        for (int i = 0; i < profiles.getPreferenceCount(); i++) {
            if (profiles.getPreference(i) == list) at = i;
        }
        assertTrue(at >= 0);
        assertSame(row, profiles.getPreference(at + 1));

        row.setValue("PROFILE");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(NavigationTarget.PROFILE, Settings.SAVED_PROFILES_TAB_TARGET.savedValue());
        assertTrue(row.getSummary().toString(),
                row.getSummary().toString().startsWith("Long press Profile to open Your saved profiles"));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SAVED_PROFILES_TAB_TARGET.key));
    }

    @Test public void withoutTheProfilePicturePatchThereIsNoRowAndNothingToBackUp() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        assertNull(page.getPreferenceScreen().findPreference(Settings.SAVED_PROFILES_TAB_TARGET.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.SAVED_PROFILES_TAB_TARGET.key));
    }
}
