/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Don't send feed view history's switch (#664): under Feed after Hide the home feed, off to start, and one of Ghost mode's. */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class FeedViewHistorySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        Settings.DONT_SEND_FEED_VIEW_HISTORY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.DONT_SEND_FEED_VIEW_HISTORY.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void withoutThePatchThereIsNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.HOME_FEED));
        assertNull(page.getPreferenceScreen().findPreference(Settings.DONT_SEND_FEED_VIEW_HISTORY.key));
    }

    @Test public void itSitsUnderFeedAfterHideTheHomeFeedAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.HOME_FEED, PatchFamily.FEED_VIEW_HISTORY));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen()
                .findPreference(Settings.DONT_SEND_FEED_VIEW_HISTORY.key);
        assertNotNull(row);
        assertEquals("Don't send feed view history", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("which posts in Home you looked at"));
        PreferenceCategory feed = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(feed);
        assertEquals("Feed", feed.getTitle().toString());
        assertEquals(2, feed.getPreferenceCount());
        assertEquals(Settings.HIDE_HOME_FEED.key, feed.getPreference(0).getKey());
        assertEquals(row, feed.getPreference(1));
        assertFalse(row.isChecked());
        assertFalse(Settings.DONT_SEND_FEED_VIEW_HISTORY.get());
        assertEquals(Collections.singletonList(Settings.DONT_SEND_FEED_VIEW_HISTORY), PatchFamily.FEED_VIEW_HISTORY.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DONT_SEND_FEED_VIEW_HISTORY.key));
    }

    /** On its own it opens Feed, so a build carrying nothing else of Feed still shows the switch. */
    @Test public void onItsOwnItStillOpensFeed() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_VIEW_HISTORY));
        Preference row = page.getPreferenceScreen().findPreference(Settings.DONT_SEND_FEED_VIEW_HISTORY.key);
        assertNotNull(row);
        assertEquals("Feed", categoryHolding(page.getPreferenceScreen(), row).getTitle().toString());
    }

    /** Ghost mode turns it with the others, last, and only when its patch is in the build. */
    @Test public void ghostModeTurnsIt() throws Exception {
        assertEquals(Arrays.asList(Settings.HIDE_TYPING, Settings.DONT_SEND_FEED_VIEW_HISTORY),
                GhostMode.switches(EnumSet.of(PatchFamily.FEED_VIEW_HISTORY, PatchFamily.TYPING)));
        assertEquals(Collections.singletonList(Settings.HIDE_TYPING), GhostMode.switches(EnumSet.of(PatchFamily.TYPING)));
    }

    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
