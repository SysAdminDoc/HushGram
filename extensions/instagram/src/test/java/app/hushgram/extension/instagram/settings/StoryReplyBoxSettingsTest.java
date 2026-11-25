/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.stories.StoryCommentLine;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the reply box on stories (#486): a Stories switch that starts off, right after Stop swipe up
 * to reply and before Show a story's exact time, kept in a backup, and off while paused. The Like,
 * Comment and Share switches (#618) follow it in that order, each off to start.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryReplyBoxSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        resetSwitches();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        resetSwitches();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private static void resetSwitches() {
        Settings.HIDE_STORY_REPLY_BOX.resetToDefault();
        Settings.HIDE_STORY_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_STORY_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_STORY_SHARE_BUTTON.resetToDefault();
        Settings.HIDE_STORY_COMMENTS.resetToDefault();
        Settings.STORY_COMMENT_LINE.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void theSwitchSitsUnderStoriesAfterSwipeReplyAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SWIPE_REPLY, PatchFamily.STORY_REPLY_BOX, PatchFamily.STORY_TIME));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_REPLY_BOX.key);
        assertNotNull(row);
        assertEquals("Hide the reply box on stories", row.getTitle().toString());
        assertEquals("Takes the Send message box off the bottom of other people's stories. The like and share "
                + "buttons next to it stay where they are.", row.getSummary().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.HIDE_STORY_REPLY_BOX.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.HIDE_STORY_REPLY_BOX.key);
        assertEquals(Arrays.toString(keys), Settings.STOP_STORY_SWIPE_REPLY.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.HIDE_STORY_LIKE_BUTTON.key, keys[at + 1]);
        assertEquals(Arrays.toString(keys), Settings.HIDE_STORY_COMMENT_BUTTON.key, keys[at + 2]);
        assertEquals(Arrays.toString(keys), Settings.HIDE_STORY_SHARE_BUTTON.key, keys[at + 3]);
        assertEquals(Arrays.toString(keys), Settings.HIDE_STORY_COMMENTS.key, keys[at + 4]);
        assertEquals(Arrays.toString(keys), Settings.STORY_COMMENT_LINE.key, keys[at + 5]);
        assertEquals(Arrays.toString(keys), Settings.SHOW_STORY_TIME.key, keys[at + 6]);
        assertEquals("Hide the reply box on stories", PatchFamily.STORY_REPLY_BOX.patchName);
        assertEquals(Arrays.asList(Settings.HIDE_STORY_REPLY_BOX, Settings.HIDE_STORY_LIKE_BUTTON,
                Settings.HIDE_STORY_COMMENT_BUTTON, Settings.HIDE_STORY_SHARE_BUTTON, Settings.HIDE_STORY_COMMENTS),
                PatchFamily.STORY_REPLY_BOX.switches);

        ((SwitchPreference) row).setChecked(true);
        assertTrue(Settings.HIDE_STORY_REPLY_BOX.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_STORY_REPLY_BOX.key));
    }

    /** #618: Like, Comment and Share each get a switch of their own under the reply box, all off to start. */
    @Test public void theButtonSwitchesSitUnderTheReplyBoxAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_REPLY_BOX));
        String[][] rows = {
                {Settings.HIDE_STORY_LIKE_BUTTON.key, "Hide the Like button on stories",
                        "Takes the heart off the bottom of other people's stories, so nothing gets liked by mistake. "
                                + "No empty space is left behind."},
                {Settings.HIDE_STORY_COMMENT_BUTTON.key, "Hide the Comment button on stories",
                        "Takes the Comment button off the bottom of other people's stories that have one. "
                                + "No empty space is left behind."},
                {Settings.HIDE_STORY_SHARE_BUTTON.key, "Hide the Share button on stories",
                        "Takes the paper plane off the bottom of other people's stories. No empty space is left behind."},
                {Settings.HIDE_STORY_COMMENTS.key, "Hide comments on stories",
                        "Takes the comments floating over stories off them, along with the faces of the people who "
                                + "wrote them. The Comment button still opens them."}};
        for (String[] expected : rows) {
            Preference row = page.getPreferenceScreen().findPreference(expected[0]);
            assertNotNull(expected[0], row);
            assertEquals(expected[1], row.getTitle().toString());
            assertEquals(expected[2], row.getSummary().toString());
            assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
            assertFalse(expected[0], ((SwitchPreference) row).isChecked());
            assertTrue(expected[0], ConfigurationBackup.eligible().containsKey(expected[0]));
        }
        assertFalse(Settings.HIDE_STORY_LIKE_BUTTON.get());
        assertFalse(Settings.HIDE_STORY_COMMENT_BUTTON.get());
        assertFalse(Settings.HIDE_STORY_SHARE_BUTTON.get());
        assertFalse(Settings.HIDE_STORY_COMMENTS.get());

        ((SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_SHARE_BUTTON.key)).setChecked(true);
        assertTrue(Settings.HIDE_STORY_SHARE_BUTTON.get());
        assertFalse("each switch is its own", Settings.HIDE_STORY_LIKE_BUTTON.get());
        assertFalse(Settings.HIDE_STORY_REPLY_BOX.get());
    }
    /** #826: the comment line's height is a choice under Hide comments on stories, Instagram's place to start. */
    @Test public void theCommentLineHeightIsAChoiceThatStartsWhereInstagramPutsIt() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_REPLY_BOX));
        Preference found = page.getPreferenceScreen().findPreference(Settings.STORY_COMMENT_LINE.key);
        assertTrue(String.valueOf(found), found instanceof HushgramPreferenceFragment.ChoiceRow);
        HushgramPreferenceFragment.ChoiceRow<?> row = (HushgramPreferenceFragment.ChoiceRow<?>) found;
        assertEquals("Comment line height on stories", row.getTitle().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertEquals(Arrays.asList("Where Instagram puts it", "A little higher", "Higher"),
                Arrays.asList(Arrays.stream(row.getEntries()).map(CharSequence::toString).toArray()));
        assertEquals(Arrays.asList("INSTAGRAM", "HIGHER", "HIGHEST"),
                Arrays.asList(Arrays.stream(row.getEntryValues()).map(CharSequence::toString).toArray()));
        assertEquals("INSTAGRAM", row.getValue());
        assertEquals("The line for writing a comment sits where Instagram puts it, at the bottom of the story.",
                row.getSummary().toString());
        assertEquals(StoryCommentLine.INSTAGRAM, Settings.STORY_COMMENT_LINE.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_COMMENT_LINE.key));

        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, "HIGHEST"));
        row.setValue("HIGHEST");
        ShadowLooper.idleMainLooper();
        assertEquals(StoryCommentLine.HIGHEST, Settings.STORY_COMMENT_LINE.get());
        assertEquals("The line for writing a comment sits higher on the story, well clear of the bottom edge.",
                row.getSummary().toString());
        assertEquals(0, StoryCommentLine.INSTAGRAM.px(2f));
        assertEquals(48, StoryCommentLine.HIGHER.px(2f));
        assertEquals(96, StoryCommentLine.HIGHEST.px(2f));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("Instagram's place while paused", StoryCommentLine.INSTAGRAM, Settings.STORY_COMMENT_LINE.get());
        assertEquals(StoryCommentLine.HIGHEST, Settings.STORY_COMMENT_LINE.savedValue());
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_REPLY_BOX));
        Settings.HIDE_STORY_REPLY_BOX.save(true);
        assertTrue(Settings.HIDE_STORY_REPLY_BOX.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_STORY_REPLY_BOX.get());
        assertTrue(Settings.HIDE_STORY_REPLY_BOX.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_SWIPE_REPLY));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_REPLY_BOX.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_STORY_REPLY_BOX.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_LIKE_BUTTON.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_STORY_SHARE_BUTTON.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_STORY_COMMENTS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_STORY_COMMENTS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_COMMENT_LINE.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.STORY_COMMENT_LINE.key));
    }
}
