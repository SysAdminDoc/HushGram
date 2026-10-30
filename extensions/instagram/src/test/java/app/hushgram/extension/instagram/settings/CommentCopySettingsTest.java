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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CommentCopySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.COPY_COMMENTS.resetToDefault();
        Settings.COPY_COMMENT_AUTHORS.resetToDefault();
        Settings.COPY_COMMENTS_WITHOUT_MENTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PatchFamily.commentAuthorForTests = null;
        Settings.COPY_COMMENTS.resetToDefault();
        Settings.COPY_COMMENT_AUTHORS.resetToDefault();
        Settings.COPY_COMMENTS_WITHOUT_MENTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.COMMENT_COPY) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoCommentSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.COPY_COMMENT_AUTHORS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS_WITHOUT_MENTIONS.key));
        assertFalse(Settings.COPY_COMMENTS.get());
    }
    /** #35: the commenter's username has its own switch beside Copy comment, off to start. */
    @Test public void commenterUsernameSwitchSitsBesideCopyAndStartsOff() throws Exception {
        open(true);
        SwitchPreference copy = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS.key);
        SwitchPreference author = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COPY_COMMENT_AUTHORS.key);
        assertNotNull(author);
        assertEquals("Copy the commenter's username", author.getTitle().toString());
        assertSame(copy.getParent(), author.getParent());
        assertFalse(author.isChecked());
        assertFalse(Settings.COPY_COMMENT_AUTHORS.get());
        assertEquals(Settings.COPY_COMMENT_AUTHORS, PatchFamily.COMMENT_COPY.switches.get(1));
        Settings.COPY_COMMENT_AUTHORS.save(true);
        assertFalse("one switch doesn't turn on the other", Settings.COPY_COMMENTS.get());
    }
    @Test public void commentsSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS.key);
        assertNotNull(row);
        assertEquals("Copy comment", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertEquals(Settings.COPY_COMMENTS, PatchFamily.COMMENT_COPY.switches.get(0));
        Settings.COPY_COMMENTS.save(true);
        assertTrue(Settings.COPY_COMMENTS.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.COPY_COMMENTS.get());
        assertTrue(Settings.COPY_COMMENTS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.COPY_COMMENTS.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
    /** #35: a build whose Copy username didn't go in keeps Copy comment and doesn't offer a switch that does nothing. */
    @Test public void aBuildWithoutCopyUsernameKeepsCopyWithoutTheSecondSwitch() throws Exception {
        PatchFamily.commentAuthorForTests = false;
        open(true);
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.COPY_COMMENT_AUTHORS.key));
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.COMMENT_COPY), false);
        assertTrue(report.toString(), report.contains("  Copy the commenter's username: not in this build "
                + "(the comment menu's label or the comment's author didn't match)"));
    }
    /** #270: leaving out a reply's @names sits right under Copy comment, off to start and waiting for it. */
    @Test public void theMentionSwitchSitsUnderCopyAndWaitsForIt() throws Exception {
        open(true);
        SwitchPreference copy = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS.key);
        SwitchPreference mentions = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS_WITHOUT_MENTIONS.key);
        assertNotNull(mentions);
        assertEquals("Copy without the @names at the start", mentions.getTitle().toString());
        PreferenceGroup section = copy.getParent();
        assertSame(section, mentions.getParent());
        int at = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) if (section.getPreference(i) == copy) at = i;
        assertSame("right under Copy comment", mentions, section.getPreference(at + 1));
        assertFalse("off to start", mentions.isChecked());
        assertFalse("waits for Copy comment", mentions.isEnabled());
        copy.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(mentions.isEnabled());
        assertEquals("added after the switches tests count on",
                Settings.COPY_COMMENTS_WITHOUT_MENTIONS, PatchFamily.COMMENT_COPY.switches.get(2));
        page.searchSettings("copy comment");
        assertNotNull("the patch's name finds it", mentions.getParent());
        page.searchSettings("");
    }
    @Test public void aBuildCopyingUsernamesReportsNothingMissing() throws Exception {
        open(true);
        assertTrue(PatchFamily.commentAuthorInBuild());
        List<String> report = PatchFamily.reportLines(EnumSet.of(PatchFamily.COMMENT_COPY), false);
        for (String line : report) assertFalse(line, line.contains("not in this build ("));
    }
}
