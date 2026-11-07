/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Collections;
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
import app.hushgram.extension.instagram.feed.CommentGifs;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Hide GIFs in comments switch: under Comments, off to start, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CommentGifsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_COMMENT_GIFS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_COMMENT_GIFS.resetToDefault();
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
        open(EnumSet.of(PatchFamily.HIDE_COMMENTS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_GIFS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENTS.key));
    }
    /** Without the other comment patches, Comments still opens for this switch alone. */
    @Test public void aloneItStillGetsComments() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_GIFS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_GIFS.key);
        assertNotNull(row);
        assertEquals("Comments", row.getParent().getTitle().toString());
    }
    /** Settings are a dialog over Instagram's screen, which doesn't resume when they close, so flipping on reaches it at once. */
    @Test public void flippingOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_GIFS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_GIFS.key);
        try {
            assertFalse(CommentGifs.listeningForTests(controller.get()));
            assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
            // The switch saves after its listener, so the helper reads the value once the change is posted.
            Settings.HIDE_COMMENT_GIFS.save(true);
            ShadowLooper.idleMainLooper();
            assertTrue(CommentGifs.listeningForTests(controller.get()));
        } finally {
            CommentGifs.resetForTests();
        }
    }
    @Test public void switchStartsOffUnderCommentsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_KEYBOARD, PatchFamily.COMMENT_GIFS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_GIFS.key);
        assertNotNull(row);
        assertEquals("Hide GIFs in comments", row.getTitle().toString());
        assertEquals("Takes GIFs and stickers out of comments, and the GIF button off the comment box. A comment with words keeps them. One that's only a GIF goes, along with its replies.",
                row.getSummary().toString());
        PreferenceGroup comments = row.getParent();
        assertEquals("Comments", comments.getTitle().toString());
        assertEquals("right after the keyboard switch", Settings.COMMENT_KEYBOARD_DOWN.key,
                comments.getPreference(indexOf(comments, row) - 1).getKey());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_COMMENT_GIFS.get());
        assertFalse("the next comments loaded read the switch, so no restart prompt", Settings.HIDE_COMMENT_GIFS.rebootApp);
        assertEquals(Collections.singletonList(Settings.HIDE_COMMENT_GIFS), PatchFamily.COMMENT_GIFS.switches);
        assertEquals("Hide GIFs in comments", PatchFamily.COMMENT_GIFS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_COMMENT_GIFS.key));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
