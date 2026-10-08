/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
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
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.COPY_COMMENTS.resetToDefault();
        Settings.COPY_COMMENT_AUTHORS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.COPY_COMMENTS.resetToDefault();
        Settings.COPY_COMMENT_AUTHORS.resetToDefault();
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
        assertEquals(36, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
}
