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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The tab long press that turns Ghost mode (#255): the chosen tab flips the switches Ghost mode
 * turns and toasts which way, and every other tab, Pause and a build without ghost patches keep
 * Instagram's own action.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class GhostTabLongPressTest {
    enum Tab { FEED, SEARCH, CLIPS, DIRECT, PROFILE }

    /** A method, since a static field would load the settings before the rule gives them a context. */
    private static EnumSet<PatchFamily> ghostBuild() {
        return EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.THREAD_SEEN, PatchFamily.TYPING);
    }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private View feed;
    private View profile;
    private final AtomicInteger stockFeed = new AtomicInteger();
    private final AtomicInteger stockProfile = new AtomicInteger();

    @Before public void setUp() {
        PauseForTests.resume();
        BaseSettings.PAUSED.save(false);
        PatchFamily.inBuildForTests = ghostBuild();
        SettingsEntry.onClosedByUser();
        ShadowAlertDialog.reset();
        ShadowToast.reset();
        NavigationSettings.forgetTabsForTests();
        controller = Robolectric.buildActivity(Activity.class).setup();
        controller.windowFocusChanged(true);
        Activity activity = controller.get();
        LinearLayout bar = new LinearLayout(activity);
        feed = new View(activity);
        profile = new View(activity);
        bar.addView(feed, new LinearLayout.LayoutParams(60, 60));
        bar.addView(profile, new LinearLayout.LayoutParams(60, 60));
        activity.setContentView(bar);
        bar.layout(0, 0, 300, 100);
        feed.layout(0, 0, 60, 60);
        profile.layout(60, 0, 120, 60);
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        Settings.QUALITY_TAB_TARGET.save(NavigationTarget.OFF);
        Settings.GHOST_TAB_TARGET.save(NavigationTarget.FEED);
        bind(feed, Tab.FEED, stockFeed);
        bind(profile, Tab.PROFILE, stockProfile);
    }

    @After public void tearDown() throws Exception {
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        SettingsEntry.onClosedByUser();
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        Settings.QUALITY_TAB_TARGET.resetToDefault();
        Settings.GHOST_TAB_TARGET.resetToDefault();
        Settings.GHOST_MODE_PICKS.resetToDefault();
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.HIDE_TYPING.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Utils.awaitBackgroundTasksForTests();
    }

    private static void bind(View button, Tab tab, AtomicInteger stock) {
        button.setOnLongClickListener(NavigationSettings.remember(button, tab, view -> {
            stock.incrementAndGet();
            return true;
        }));
        NavigationSettings.bind(button, tab);
    }

    @Test public void theChosenTabTurnsGhostModeOnThenOff() {
        assertTrue(feed.performLongClick());
        assertEquals(0, stockFeed.get());
        assertTrue(Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
        assertTrue(Settings.HIDE_TYPING.savedValue());
        assertEquals("Ghost mode is on, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
        assertEquals("Hide ads isn't a ghost switch", Settings.HIDE_ADS.defaultValue, Settings.HIDE_ADS.savedValue());

        assertTrue(feed.performLongClick());
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
        assertFalse(Settings.HIDE_TYPING.savedValue());
        assertEquals("Ghost mode is off, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
        assertEquals(0, stockFeed.get());
    }

    @Test public void itTurnsOnlyThePickedSwitches() {
        Settings.GHOST_MODE_PICKS.save(Settings.HIDE_TYPING.key);
        assertTrue(feed.performLongClick());
        assertTrue(Settings.HIDE_TYPING.savedValue());
        assertFalse("left out, so left alone", Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
    }

    @Test public void anotherTabKeepsItsOwnAction() {
        assertTrue(profile.performLongClick());
        assertEquals(1, stockProfile.get());
        assertFalse(Settings.HIDE_TYPING.savedValue());
    }

    @Test public void offLeavesEveryTabAlone() {
        Settings.GHOST_TAB_TARGET.save(NavigationTarget.OFF);
        NavigationSettings.applyChoice();
        assertTrue(feed.performLongClick());
        assertEquals(1, stockFeed.get());
        assertFalse(Settings.HIDE_TYPING.savedValue());
    }

    @Test public void aNewChoiceReachesTheTabsAlreadyBuilt() {
        Settings.GHOST_TAB_TARGET.save(NavigationTarget.PROFILE);
        NavigationSettings.applyChoice();
        assertTrue(feed.performLongClick());
        assertEquals(1, stockFeed.get());
        assertTrue(profile.performLongClick());
        assertEquals(0, stockProfile.get());
        assertTrue(Settings.HIDE_TYPING.savedValue());
    }

    @Test public void pausedHushGramLeavesTheTabAlone() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(feed.performLongClick());
        assertEquals(1, stockFeed.get());
        assertFalse(Settings.HIDE_TYPING.savedValue());
    }

    @Test public void withoutAGhostPatchTheTabKeepsItsOwnAction() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        assertTrue(feed.performLongClick());
        assertEquals(1, stockFeed.get());
    }

    @Test public void whenOpeningHushGramNamesTheSameTabThatComesFirst() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.FEED);
        NavigationSettings.applyChoice();
        assertTrue(feed.performLongClick());
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("HushGram opened instead", Settings.HIDE_TYPING.savedValue());
        assertEquals(0, stockFeed.get());
    }

    /** The haptic answer is HushGram's while the press turns Ghost mode, and Instagram's otherwise. */
    @Test @Config(sdk = 37) public void theHapticIsInstagramsWheneverGhostModeIsNotOffered() {
        AtomicInteger asked = new AtomicInteger();
        View.OnLongClickListener own = new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                stockFeed.incrementAndGet();
                return true;
            }

            @Override public boolean onLongClickUseDefaultHapticFeedback(View view) {
                asked.incrementAndGet();
                return false;
            }
        };
        feed.setOnLongClickListener(NavigationSettings.remember(feed, Tab.FEED, own));
        NavigationSettings.bind(feed, Tab.FEED);
        View.OnLongClickListener press = shadowOf(feed).getOnLongClickListener();
        assertTrue("Ghost mode's own press", press.onLongClickUseDefaultHapticFeedback(feed));
        assertEquals(0, asked.get());
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        assertFalse("without a ghost patch", press.onLongClickUseDefaultHapticFeedback(feed));
        PatchFamily.inBuildForTests = ghostBuild();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", press.onLongClickUseDefaultHapticFeedback(feed));
        assertEquals(2, asked.get());
    }

    /** The row sits right after the picks row under Ghost mode, starts off, says what it does and is backed up. */
    @Test public void theRowSitsUnderGhostModeAndStartsOff() throws Exception {
        Settings.GHOST_TAB_TARGET.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        Preference found = page.getPreferenceScreen().findPreference(Settings.GHOST_TAB_TARGET.key);
        assertTrue(found instanceof HushgramPreferenceFragment.NavigationRow);
        ListPreference row = (ListPreference) found;
        assertEquals("Turn Ghost mode on or off with a tab long press", row.getTitle().toString());
        assertEquals("OFF", row.getValue());
        assertEquals("Tab long presses keep Instagram's own action. Choose one to turn Ghost mode on or off instead.",
                row.getSummary().toString());
        PreferenceGroup privacy = row.getParent();
        assertNotNull(privacy);
        assertSame(page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.GHOST_PICKS_ROW),
                privacy.getPreference(1));
        assertSame(row, privacy.getPreference(2));

        row.setValue("PROFILE");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(NavigationTarget.PROFILE, Settings.GHOST_TAB_TARGET.savedValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Long press Profile to turn Ghost mode"));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GHOST_TAB_TARGET.key));
    }

    @Test public void withOneGhostPatchThereIsNoRowAndNothingToBackUp() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.TYPING);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        assertNull(page.getPreferenceScreen().findPreference(Settings.GHOST_TAB_TARGET.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.GHOST_TAB_TARGET.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.GHOST_MODE_PICKS.key));
    }
}
