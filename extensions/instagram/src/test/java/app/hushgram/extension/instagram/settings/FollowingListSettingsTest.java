/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Following list mark's switch: in Profiles beside the profile label's, off to start, and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FollowingListSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.MARK_FOLLOWING_LIST.resetToDefault();
        Settings.FOLLOWING_NOT_BACK_FIRST.resetToDefault();
        Settings.SHOW_FRIENDSHIP_STATUS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PatchFamily.followingListMarkForTests = null;
        PatchFamily.unfollowersForTests = null;
        Settings.TRACK_UNFOLLOWERS.resetToDefault();
        Settings.MARK_FOLLOWING_LIST.resetToDefault();
        Settings.FOLLOWING_NOT_BACK_FIRST.resetToDefault();
        Settings.SHOW_FRIENDSHIP_STATUS.resetToDefault();
        Settings.FRIENDSHIP_STATUS_CHIP.resetToDefault();
        Settings.FOLLOW_LABEL_PLACE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.FRIENDSHIP_STATUS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoFollowingListSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_FOLLOWING_LIST.key));
        assertFalse(Settings.MARK_FOLLOWING_LIST.get());
    }
    @Test public void followingListSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference label = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_FRIENDSHIP_STATUS.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_FOLLOWING_LIST.key);
        assertNotNull(label);
        assertNotNull(row);
        assertTrue(label.isChecked());
        assertEquals("Mark who doesn't follow you back", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.MARK_FOLLOWING_LIST.get());
        List<BooleanSetting> switches = PatchFamily.FRIENDSHIP_STATUS.switches;
        assertEquals(Settings.SHOW_FRIENDSHIP_STATUS, switches.get(0));
        assertEquals(Settings.MARK_FOLLOWING_LIST, switches.get(1));
        Settings.MARK_FOLLOWING_LIST.save(true);
        assertTrue(Settings.MARK_FOLLOWING_LIST.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.MARK_FOLLOWING_LIST.get());
        assertTrue(Settings.MARK_FOLLOWING_LIST.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.MARK_FOLLOWING_LIST.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
    /** Put accounts that don't follow you back first sits right under the mark's switch, starts off and is off while paused. */
    @Test public void orderSwitchStartsOffUnderTheMarkAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference first = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FOLLOWING_NOT_BACK_FIRST.key);
        SwitchPreference mark = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_FOLLOWING_LIST.key);
        assertNotNull(first);
        assertEquals("Put accounts that don't follow you back first", first.getTitle().toString());
        assertFalse(first.isChecked());
        assertFalse(Settings.FOLLOWING_NOT_BACK_FIRST.get());
        assertEquals(mark.getOrder() + 1, first.getOrder());
        assertTrue(PatchFamily.FRIENDSHIP_STATUS.switches.contains(Settings.FOLLOWING_NOT_BACK_FIRST));
        Settings.FOLLOWING_NOT_BACK_FIRST.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FOLLOWING_NOT_BACK_FIRST.get());
        assertTrue(Settings.FOLLOWING_NOT_BACK_FIRST.savedValue());
        PauseForTests.resume();
        assertTrue(Settings.FOLLOWING_NOT_BACK_FIRST.get());
        controller.close();
        PatchFamily.followingListMarkForTests = false;
        open(true);
        assertNull(page.getPreferenceScreen().findPreference(Settings.FOLLOWING_NOT_BACK_FIRST.key));
    }

    /** Show it as a chip sits right under the label's switch, starts off and is off while paused. */
    @Test public void chipSwitchStartsOffUnderTheLabelAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference chip = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FRIENDSHIP_STATUS_CHIP.key);
        assertNotNull(chip);
        assertEquals("Show it as a chip", chip.getTitle().toString());
        assertFalse(chip.isChecked());
        assertFalse(Settings.FRIENDSHIP_STATUS_CHIP.get());
        SwitchPreference label = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_FRIENDSHIP_STATUS.key);
        assertEquals(label.getOrder() + 1, chip.getOrder());
        assertTrue(PatchFamily.FRIENDSHIP_STATUS.switches.contains(Settings.FRIENDSHIP_STATUS_CHIP));
        Settings.FRIENDSHIP_STATUS_CHIP.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FRIENDSHIP_STATUS_CHIP.get());
        assertTrue(Settings.FRIENDSHIP_STATUS_CHIP.savedValue());
        PauseForTests.resume();
        assertTrue(Settings.FRIENDSHIP_STATUS_CHIP.get());
        controller.close();
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.FRIENDSHIP_STATUS_CHIP.key));
    }

    /** Follow label sits right under Show it as a chip, starts under the counts and needs the chip (#157). */
    @Test public void followLabelChoiceSitsUnderTheChipAndStartsUnderTheCounts() throws Exception {
        Settings.FRIENDSHIP_STATUS_CHIP.save(true);
        open(true);
        android.preference.Preference found = page.getPreferenceScreen().findPreference(Settings.FOLLOW_LABEL_PLACE.key);
        assertTrue(found instanceof HushgramPreferenceFragment.FollowLabelPlaceRow);
        HushgramPreferenceFragment.FollowLabelPlaceRow row = (HushgramPreferenceFragment.FollowLabelPlaceRow) found;
        SwitchPreference chip = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FRIENDSHIP_STATUS_CHIP.key);
        assertEquals("right under the chip's switch", chip.getOrder() + 1, row.getOrder());
        assertEquals("Follow label", String.valueOf(row.getTitle()));
        List<String> entries = new java.util.ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(java.util.Arrays.asList("Under the counts", "Small, under the picture"), entries);
        assertEquals("UNDER_COUNTS", row.getValue());
        assertEquals(app.hushgram.extension.instagram.profile.FollowLabelPlace.UNDER_COUNTS, Settings.FOLLOW_LABEL_PLACE.get());
        assertEquals("The chip sits under the posts, followers and following counts.", String.valueOf(row.getSummary()));

        row.setValue("UNDER_PICTURE");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(app.hushgram.extension.instagram.profile.FollowLabelPlace.UNDER_PICTURE, Settings.FOLLOW_LABEL_PLACE.savedValue());
        assertTrue(String.valueOf(row.getSummary()).startsWith("A smaller chip, centered under the profile picture."));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FOLLOW_LABEL_PLACE.key));

        Settings.FRIENDSHIP_STATUS_CHIP.save(false);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        row.showSummary();
        assertEquals("Turn on Show it as a chip to use this choice.", String.valueOf(row.getSummary()));
        assertEquals("keeps what was picked", app.hushgram.extension.instagram.profile.FollowLabelPlace.UNDER_PICTURE,
                Settings.FOLLOW_LABEL_PLACE.savedValue());

        controller.close();
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.FOLLOW_LABEL_PLACE.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.FOLLOW_LABEL_PLACE.key));
    }

    @Test public void aBuildWhoseFollowListMovedKeepsTheLabelWithoutTheSecondSwitch() throws Exception {
        PatchFamily.followingListMarkForTests = false;
        open(true);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.SHOW_FRIENDSHIP_STATUS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_FOLLOWING_LIST.key));
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.FRIENDSHIP_STATUS), false);
        assertTrue(report.toString(), report.contains(
                "  Mark who doesn't follow you back: not in this build (Instagram's follow list didn't match)"));
    }
    /** Recent unfollowers (#208) sits under the order switch, starts off, and lists who left with when. */
    @Test public void unfollowersSwitchStartsOffWithItsListAndClear() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.hushgram.extension.instagram.profile.Unfollowers.clear(context);
        open(true);
        SwitchPreference track = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.TRACK_UNFOLLOWERS.key);
        SwitchPreference first = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FOLLOWING_NOT_BACK_FIRST.key);
        assertNotNull(track);
        assertEquals("Recent unfollowers", track.getTitle().toString());
        assertFalse(track.isChecked());
        assertFalse(Settings.TRACK_UNFOLLOWERS.get());
        assertEquals(first.getOrder() + 1, track.getOrder());
        assertTrue(PatchFamily.FRIENDSHIP_STATUS.switches.contains(Settings.TRACK_UNFOLLOWERS));
        android.preference.Preference list = page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.UNFOLLOWERS_ROW);
        assertNotNull(list);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(String.valueOf(list.getSummary()), String.valueOf(list.getSummary()).startsWith("Nothing to compare yet."));
        assertNotNull(page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.CLEAR_UNFOLLOWERS));
        Settings.TRACK_UNFOLLOWERS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.TRACK_UNFOLLOWERS.get());
        PauseForTests.resume();
        assertTrue(Settings.TRACK_UNFOLLOWERS.get());

        java.util.Map<String, String> before = new java.util.LinkedHashMap<>();
        before.put("1", "ana");
        before.put("2", "bo");
        app.hushgram.extension.instagram.profile.Unfollowers.take(context, "1001", before, 1_000L);
        app.hushgram.extension.instagram.profile.Unfollowers.take(context, "1001", java.util.Collections.singletonMap("1", "ana"), 2_000L);
        String summary = HushgramPreferenceFragment.unfollowersSummary(app.hushgram.extension.instagram.profile.Unfollowers.shown(context));
        String day = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(2_000L));
        assertEquals("@bo, gone by " + day + "\nLast seen in full on " + day + ".", summary);

        controller.close();
        open(true);
        list = page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.UNFOLLOWERS_ROW);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(summary, String.valueOf(list.getSummary()));
        // Cleared from a search that hides the list row, the row still empties (S22, 2026-10-15).
        page.searchSettings("Clear unfollower");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertNull("the search hides the list row", page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.UNFOLLOWERS_ROW));
        page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.CLEAR_UNFOLLOWERS).getOnPreferenceClickListener()
                .onPreferenceClick(page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.CLEAR_UNFOLLOWERS));
        Utils.awaitBackgroundTasksForTests();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        page.searchSettings("Who unfollowed");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(list, page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.UNFOLLOWERS_ROW));
        assertTrue(String.valueOf(list.getSummary()), String.valueOf(list.getSummary()).startsWith("Nothing to compare yet."));
        page.searchSettings("");
        assertEquals(0, app.hushgram.extension.instagram.profile.Unfollowers.shown(context).checked);
        Settings.TRACK_UNFOLLOWERS.resetToDefault();
    }

    @Test public void aBuildWithoutTheFollowersPagerLeavesUnfollowersOut() throws Exception {
        PatchFamily.unfollowersForTests = false;
        open(true);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.MARK_FOLLOWING_LIST.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.TRACK_UNFOLLOWERS.key));
        assertNull(page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.UNFOLLOWERS_ROW));
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.FRIENDSHIP_STATUS), false);
        assertTrue(report.toString(), report.contains(
                "  Recent unfollowers: not in this build (Instagram's follow list pager didn't match)"));
        PatchFamily.unfollowersForTests = null;
        PatchFamily.followingListMarkForTests = false;
        assertFalse("never without the Following list switches", PatchFamily.unfollowersInBuild());
    }
    @Test public void aBuildMarkingTheListReportsNothingMissing() throws Exception {
        open(true);
        assertTrue(PatchFamily.followingListMarkInBuild());
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.FRIENDSHIP_STATUS), false);
        for (String line : report) assertFalse(line, line.contains("not in this build ("));
    }
}
