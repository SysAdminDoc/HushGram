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
public class CommentPhotoSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SAVE_COMMENT_PHOTOS.resetToDefault();
        Settings.COPY_COMMENTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SAVE_COMMENT_PHOTOS.resetToDefault();
        Settings.COPY_COMMENTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> installed) throws Exception {
        PatchFamily.inBuildForTests = installed;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoCommentPhotoSwitch() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SAVE_COMMENT_PHOTOS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS.key));
        assertFalse(Settings.SAVE_COMMENT_PHOTOS.get());
    }
    @Test public void commentPhotoSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_PHOTO));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SAVE_COMMENT_PHOTOS.key);
        assertNotNull(row);
        assertEquals("Save comment photo", row.getTitle().toString());
        assertEquals("Comments", row.getParent().getTitle().toString());
        assertFalse(row.isChecked());
        assertNull(page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS.key));
        assertEquals(Settings.SAVE_COMMENT_PHOTOS, PatchFamily.COMMENT_PHOTO.switches.get(0));
        Settings.SAVE_COMMENT_PHOTOS.save(true);
        assertTrue(Settings.SAVE_COMMENT_PHOTOS.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.SAVE_COMMENT_PHOTOS.get());
        assertTrue(Settings.SAVE_COMMENT_PHOTOS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.SAVE_COMMENT_PHOTOS.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
    @Test public void bothCommentSwitchesShareOneCategoryAndStayIndependent() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_COPY, PatchFamily.COMMENT_PHOTO));
        SwitchPreference copy = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COPY_COMMENTS.key);
        SwitchPreference photo = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SAVE_COMMENT_PHOTOS.key);
        assertNotNull(copy);
        assertNotNull(photo);
        assertSame(copy.getParent(), photo.getParent());
        assertEquals("Comments", copy.getParent().getTitle().toString());
        assertFalse(copy.isChecked());
        assertFalse(photo.isChecked());
        Settings.COPY_COMMENTS.save(true);
        assertFalse(Settings.SAVE_COMMENT_PHOTOS.get());
        Settings.SAVE_COMMENT_PHOTOS.save(true);
        Settings.COPY_COMMENTS.save(false);
        assertTrue(Settings.SAVE_COMMENT_PHOTOS.get());
    }
}
