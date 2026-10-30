/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.widget.ListView;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Ghost mode: one switch over the ghost switches, first under Ads and privacy, keeping no value of its own. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class GhostModeSettingsTest {
    /** A method, not a static field, so the settings load once the rule has given them a context. */
    private static BooleanSetting[] ghostSwitches() {
        return new BooleanSetting[] {Settings.VIEW_STORIES_ANONYMOUSLY, Settings.VIEW_LIVE_ANONYMOUSLY,
                Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.VIEW_DM_MEDIA_ANONYMOUSLY, Settings.HIDE_TYPING, Settings.HIDE_SCREENSHOTS};
    }
    private static final EnumSet<PatchFamily> ALL_GHOST = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.STORY_SEEN,
            PatchFamily.LIVE_SEEN, PatchFamily.THREAD_SEEN, PatchFamily.DM_MEDIA_SEEN, PatchFamily.TYPING, PatchFamily.SCREENSHOT_REPORTS);

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        for (BooleanSetting setting : ghostSwitches()) setting.resetToDefault();
        Settings.GHOST_MODE_PICKS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        for (BooleanSetting setting : ghostSwitches()) setting.resetToDefault();
        Settings.GHOST_MODE_PICKS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    private SwitchPreference ghost() {
        PreferenceGroup screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference section = screen.getPreference(i);
            if (!(section instanceof PreferenceGroup)) continue;
            PreferenceGroup group = (PreferenceGroup) section;
            for (int j = 0; j < group.getPreferenceCount(); j++) {
                Preference row = group.getPreference(j);
                if ("Ghost mode".equals(String.valueOf(row.getTitle()))) return (SwitchPreference) row;
            }
        }
        return null;
    }
    private SwitchPreference own(BooleanSetting setting) {
        return (SwitchPreference) page.findPreference(setting.key);
    }
    private static AlertDialog latest() {
        ShadowLooper.idleMainLooper();
        return ShadowAlertDialog.getLatestAlertDialog();
    }
    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }

    @Test public void theSwitchListFollowsTheBuild() {
        assertEquals(Arrays.asList(ghostSwitches()), GhostMode.switches(ALL_GHOST));
        assertEquals(Arrays.asList(Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.HIDE_TYPING),
                GhostMode.switches(EnumSet.of(PatchFamily.TYPING, PatchFamily.NOTES_ROW, PatchFamily.THREAD_SEEN)));
        assertTrue(GhostMode.switches(null).isEmpty());
        assertFalse(GhostMode.offered(GhostMode.switches(EnumSet.of(PatchFamily.TYPING))));
        assertFalse(GhostMode.on(GhostMode.switches(EnumSet.noneOf(PatchFamily.class))));
    }

    @Test public void oneGhostPatchGetsNoMasterSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.TYPING));
        assertNull(ghost());
        assertNotNull(own(Settings.HIDE_TYPING));
    }

    @Test public void itSitsFirstUnderAdsAndPrivacyAndStartsAsTheSwitchesAre() throws Exception {
        open(ALL_GHOST);
        SwitchPreference row = ghost();
        assertNotNull(row);
        assertFalse("every ghost switch starts off", row.isChecked());
        assertFalse(row.isPersistent());
        assertNull("it keeps no value, so it has no setting key", row.getKey());
        assertEquals("Turns the switches that keep what you do to yourself on or off in one go, like View stories "
                + "anonymously and Hide that you're typing. Each one keeps its own switch.", row.getSummary().toString());
        PreferenceGroup privacy = row.getParent();
        assertEquals("Ads and privacy", privacy.getTitle().toString());
        assertSame(row, privacy.getPreference(0));
        assertEquals(HushgramPreferenceFragment.GHOST_PICKS_ROW, privacy.getPreference(1).getKey());
        assertEquals(Settings.GHOST_TAB_TARGET.key, privacy.getPreference(2).getKey());
        assertEquals(Settings.HIDE_ADS.key, privacy.getPreference(3).getKey());
    }

    /** #251: Ghost mode turns all its switches until you pick some, and a pick naming none of them means all. */
    @Test public void itTurnsAllOfThemUntilYouPick() {
        List<BooleanSetting> all = GhostMode.switches(ALL_GHOST);
        assertEquals(all, GhostMode.picked(all));
        Settings.GHOST_MODE_PICKS.save("hushgram_no_such_switch");
        assertEquals("a pick naming none of them means all", all, GhostMode.picked(all));

        GhostMode.pick(all, new HashSet<>(Arrays.asList(Settings.HIDE_TYPING.key, Settings.VIEW_STORIES_ANONYMOUSLY.key)));
        assertEquals(Settings.VIEW_STORIES_ANONYMOUSLY.key + "," + Settings.HIDE_TYPING.key,
                Settings.GHOST_MODE_PICKS.savedValue());
        assertEquals(Arrays.asList(Settings.VIEW_STORIES_ANONYMOUSLY, Settings.HIDE_TYPING), GhostMode.picked(all));
        assertEquals("a switch not in this build is left out",
                Arrays.asList(Settings.HIDE_TYPING), GhostMode.picked(GhostMode.switches(EnumSet.of(PatchFamily.TYPING))));

        Set<String> every = new HashSet<>();
        for (BooleanSetting setting : all) every.add(setting.key);
        GhostMode.pick(all, every);
        assertEquals("every one picked saves nothing, so a later ghost patch is turned too", "",
                Settings.GHOST_MODE_PICKS.savedValue());
    }

    /** #251: the row under Ghost mode picks its switches, and Ghost mode then turns only those. */
    @Test public void theRowUnderItPicksWhichSwitchesItTurns() throws Exception {
        open(ALL_GHOST);
        Preference picks = page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.GHOST_PICKS_ROW);
        assertNotNull(picks);
        assertEquals("Switches Ghost mode turns", picks.getTitle().toString());
        assertEquals("All of them. Tap to leave some out, and Ghost mode leaves those as you set them.",
                picks.getSummary().toString());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GHOST_MODE_PICKS.key));

        page.pickGhostMode();
        AlertDialog dialog = latest();
        ListView list = dialog.getListView();
        assertEquals(ghostSwitches().length, list.getCount());
        assertEquals("View stories anonymously", String.valueOf(list.getAdapter().getItem(0)));
        for (int i = 0; i < ghostSwitches().length; i++) assertTrue("every one starts checked", list.isItemChecked(i));
        // Leave out everything but View stories anonymously and Hide that you're typing.
        for (int i = 0; i < ghostSwitches().length; i++) {
            if (ghostSwitches()[i] != Settings.VIEW_STORIES_ANONYMOUSLY && ghostSwitches()[i] != Settings.HIDE_TYPING) tap(list, i);
        }
        assertEquals(Arrays.asList(Settings.VIEW_STORIES_ANONYMOUSLY, Settings.HIDE_TYPING),
                GhostMode.picked(GhostMode.switches(ALL_GHOST)));
        assertEquals(own(Settings.VIEW_STORIES_ANONYMOUSLY).getTitle() + ", " + own(Settings.HIDE_TYPING).getTitle()
                + ". The others stay as you set them.", picks.getSummary().toString());

        SwitchPreference row = ghost();
        row.getOnPreferenceChangeListener().onPreferenceChange(row, true);
        assertTrue(Settings.VIEW_STORIES_ANONYMOUSLY.savedValue());
        assertTrue(Settings.HIDE_TYPING.savedValue());
        assertTrue(own(Settings.HIDE_TYPING).isChecked());
        assertFalse("a switch left out keeps what you set", Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
        assertFalse(own(Settings.HIDE_SCREENSHOTS).isChecked());
        assertTrue("on while the picked ones are on", row.isChecked());
        assertEquals("Ghost mode is on, and so is each of its switches.", ShadowToast.getTextOfLatestToast());

        own(Settings.READ_WITHOUT_SEEN_RECEIPT).setChecked(true);
        Utils.awaitBackgroundTasksForTests();
        row.getOnPreferenceChangeListener().onPreferenceChange(row, false);
        assertFalse(Settings.VIEW_STORIES_ANONYMOUSLY.savedValue());
        assertFalse(Settings.HIDE_TYPING.savedValue());
        assertTrue("turning it off leaves a switch it doesn't turn on", Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
    }

    /** #251: the last check can't come off, since Ghost mode would have nothing left to turn. */
    @Test public void theLastPickStays() throws Exception {
        open(ALL_GHOST);
        page.pickGhostMode();
        ListView list = latest().getListView();
        for (int i = 1; i < ghostSwitches().length; i++) tap(list, i);
        assertEquals(Arrays.asList(ghostSwitches()[0]), GhostMode.picked(GhostMode.switches(ALL_GHOST)));
        tap(list, 0);
        assertTrue("the last check stays", list.isItemChecked(0));
        assertEquals("Ghost mode needs at least one switch to turn.", ShadowToast.getTextOfLatestToast());
        assertEquals(Arrays.asList(ghostSwitches()[0]), GhostMode.picked(GhostMode.switches(ALL_GHOST)));
        tap(list, 3);
        assertEquals(Arrays.asList(ghostSwitches()[0], ghostSwitches()[3]), GhostMode.picked(GhostMode.switches(ALL_GHOST)));
    }

    @Test public void aTapTurnsEverySwitchAndEachRowShowsIt() throws Exception {
        open(ALL_GHOST);
        SwitchPreference row = ghost();
        row.getOnPreferenceChangeListener().onPreferenceChange(row, true);
        for (BooleanSetting setting : ghostSwitches()) {
            assertTrue(setting.key, setting.savedValue());
            assertTrue(setting.key, own(setting).isChecked());
        }
        assertTrue(row.isChecked());
        assertEquals("Ghost mode is on, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
        assertEquals("Hide ads isn't a ghost switch", Settings.HIDE_ADS.defaultValue, Settings.HIDE_ADS.savedValue());

        row.getOnPreferenceChangeListener().onPreferenceChange(row, false);
        for (BooleanSetting setting : ghostSwitches()) {
            assertFalse(setting.key, setting.savedValue());
            assertFalse(setting.key, own(setting).isChecked());
        }
        assertFalse(row.isChecked());
        assertEquals("Ghost mode is off, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void itFollowsTheOwnSwitchesAndPauseStillWins() throws Exception {
        open(ALL_GHOST);
        SwitchPreference row = ghost();
        for (BooleanSetting setting : ghostSwitches()) own(setting).setChecked(true);
        Utils.awaitBackgroundTasksForTests();
        assertTrue("every switch on by hand turns Ghost mode on", row.isChecked());
        own(Settings.HIDE_TYPING).setChecked(false);
        Utils.awaitBackgroundTasksForTests();
        assertFalse("one switch off turns it off", row.isChecked());

        row.getOnPreferenceChangeListener().onPreferenceChange(row, true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        for (BooleanSetting setting : ghostSwitches()) {
            assertFalse(setting.key, setting.get());
            assertTrue(setting.key, setting.savedValue());
        }
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        for (BooleanSetting setting : ghostSwitches()) assertTrue(setting.key, setting.get());
    }

    @Test public void searchFindsItByName() throws Exception {
        open(ALL_GHOST);
        page.searchSettings("ghost");
        assertNotNull(ghost());
        assertNull(own(Settings.HIDE_ADS).getParent());
        page.searchSettings("");
        assertNotNull(own(Settings.HIDE_ADS).getParent());
    }
}
