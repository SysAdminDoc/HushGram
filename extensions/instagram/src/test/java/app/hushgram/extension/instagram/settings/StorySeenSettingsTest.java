/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
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

/** The Mark as seen button's switch: under Stories after View stories anonymously, off to start, and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StorySeenSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.MARK_STORIES_SEEN.resetToDefault();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.MARK_STORIES_SEEN.resetToDefault();
        Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.resetToDefault();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.STORY_SEEN) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoMarkAsSeenSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_STORIES_SEEN.key));
        assertFalse(Settings.MARK_STORIES_SEEN.get());
    }
    @Test public void markAsSeenSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference label = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.VIEW_STORIES_ANONYMOUSLY.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_STORIES_SEEN.key);
        assertNotNull(label);
        assertNotNull(row);
        assertFalse("View stories anonymously starts off", label.isChecked());
        assertEquals("Mark as seen button", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.MARK_STORIES_SEEN.get());
        List<BooleanSetting> switches = PatchFamily.STORY_SEEN.switches;
        assertEquals(Settings.VIEW_STORIES_ANONYMOUSLY, switches.get(0));
        assertEquals(Settings.MARK_STORIES_SEEN, switches.get(1));
        assertEquals(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY, switches.get(2));
        assertEquals(Settings.GRAY_OUT_WATCHED_STORIES, switches.get(3));
        SwitchPreference gray = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.GRAY_OUT_WATCHED_STORIES.key);
        assertNotNull(gray);
        assertEquals("Gray out stories you've watched", gray.getTitle().toString());
        assertFalse("Gray out stories you've watched starts off", gray.isChecked());
        Settings.MARK_STORIES_SEEN.save(true);
        assertTrue(Settings.MARK_STORIES_SEEN.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.MARK_STORIES_SEEN.get());
        assertTrue(Settings.MARK_STORIES_SEEN.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.MARK_STORIES_SEEN.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }

    /**
     * Mark a story seen when you like or reply (#876) comes with the patch, right after Mark as seen
     * button, starts off, says what it does, rests until View stories anonymously is on, and reads
     * off while paused.
     */
    @Test public void theLikeOrReplySwitchFollowsMarkAsSeenAndStartsOff() throws Exception {
        try {
            open(false);
            assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.key));
            controller.close();
            controller = null;

            open(true);
            SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.key);
            SwitchPreference button = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_STORIES_SEEN.key);
            assertNotNull(row);
            assertEquals("Mark a story seen when you like or reply", row.getTitle().toString());
            String summary = row.getSummary().toString();
            assertTrue(summary, summary.contains("liking a story") && summary.contains("quick reaction") && summary.contains("other stories stay hidden"));
            assertFalse(row.isChecked());
            assertEquals(Boolean.FALSE, Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.defaultValue);
            assertFalse("rests with View stories anonymously off", row.isEnabled());
            assertFalse(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.isAvailable());
            PreferenceGroup group = row.getParent();
            assertNotNull(group);
            assertSame(group, button.getParent());
            int buttonAt = -1;
            int rowAt = -1;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                if (group.getPreference(i) == button) buttonAt = i;
                if (group.getPreference(i) == row) rowAt = i;
            }
            assertEquals("right after Mark as seen button", buttonAt + 1, rowAt);
            Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
            assertTrue(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.isAvailable());

            Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.save(true);
            assertTrue(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.get());
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertFalse(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.get());
            assertTrue(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.savedValue());
            PauseForTests.resume();
            assertTrue(Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.get());
        } finally {
            Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.resetToDefault();
        }
    }

    /**
     * Story buttons at the bottom (#647) comes with either button's patch, after the Download rows,
     * starts off, and rests until a button it moves is on.
     */
    @Test public void theBottomRowSwitchComesWithTheSeenButtonAndRestsUntilItsOn() throws Exception {
        try {
            open(false);
            assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_BUTTONS_BY_THUMB.key));
            controller.close();
            controller = null;

            open(true);
            SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STORY_BUTTONS_BY_THUMB.key);
            assertNotNull(row);
            assertEquals("Story buttons at the bottom", row.getTitle().toString());
            assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("bottom right"));
            assertFalse(row.isChecked());
            assertFalse("rests with Mark as seen button off", row.isEnabled());
            Settings.MARK_STORIES_SEEN.save(true);
            assertTrue(Settings.STORY_BUTTONS_BY_THUMB.isAvailable());
        } finally {
            Settings.STORY_BUTTONS_BY_THUMB.resetToDefault();
        }
    }
}
