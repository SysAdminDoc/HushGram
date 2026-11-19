/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Arrays;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Hide the line under names switch (#771): under Feed right after Hide Made with Edits, off to start, no restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HeaderLineSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_FEED_HEADER_LINE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_FEED_HEADER_LINE.resetToDefault();
        Settings.HIDE_REEL_HEADER_LINE.resetToDefault();
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
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_HEADER_LINE.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HOME.key));
    }
    /** Without the other Feed patches, Feed still opens for this switch alone. */
    @Test public void aloneItStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_HEADER_LINE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_HEADER_LINE.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffAfterMadeWithEdits() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS, PatchFamily.EDITS_LABEL, PatchFamily.FEED_HEADER_LINE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_HEADER_LINE.key);
        SwitchPreference edits = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_EDITS_LABEL.key);
        assertNotNull(row);
        assertEquals("Hide the line under names", row.getTitle().toString());
        assertEquals("Takes the song, the place and the other labels off the line under the name "
                + "on posts in your feed. Sponsored and Paid partnership stay. Posts already on screen change when they "
                + "load again.", row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        assertSame(feed, edits.getParent());
        assertEquals(edits.getOrder() + 1, row.getOrder());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_FEED_HEADER_LINE.get());
        assertFalse("each header reads the switch as it's built, so no restart prompt", Settings.HIDE_FEED_HEADER_LINE.rebootApp);
        assertEquals(Arrays.asList(Settings.HIDE_FEED_HEADER_LINE, Settings.HIDE_REEL_HEADER_LINE), PatchFamily.FEED_HEADER_LINE.switches);
        assertEquals("Hide the line under names", PatchFamily.FEED_HEADER_LINE.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_FEED_HEADER_LINE.key));
    }
    /** The reel switch sits in Reels with the same patch, before Clean up Reels' rows, off to start. */
    @Test public void reelSwitchStartsOffInReelsBeforeCleanUp() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_HEADER_LINE, PatchFamily.LOW_VIEW_REELS, PatchFamily.REEL_DECLUTTER));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_HEADER_LINE.key);
        SwitchPreference follow = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_FOLLOW_BUTTON.key);
        assertNotNull(row);
        assertEquals("Hide the line under names on reels", row.getTitle().toString());
        assertEquals("Takes the song, the place and the other labels off the line under the name on reels. "
                + "Sponsored and Paid partnership stay. Reels already on screen change when they load again.", row.getSummary().toString());
        assertEquals("Reels", row.getParent().getTitle().toString());
        assertSame(row.getParent(), follow.getParent());
        assertEquals(row.getOrder() + 1, follow.getOrder());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_REEL_HEADER_LINE.get());
        assertFalse("each reel's author row reads the switch as it's built, so no restart prompt", Settings.HIDE_REEL_HEADER_LINE.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_REEL_HEADER_LINE.key));
    }
    @Test public void reelSwitchNeedsThePatch() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_DECLUTTER));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_HEADER_LINE.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_REEL_FOLLOW_BUTTON.key));
    }
}
