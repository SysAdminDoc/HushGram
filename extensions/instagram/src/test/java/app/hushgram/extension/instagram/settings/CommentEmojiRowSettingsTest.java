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
import app.hushgram.extension.instagram.feed.CommentEmojiRow;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Hide the emoji row switch: under Comments, off to start, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CommentEmojiRowSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_COMMENT_EMOJI_ROW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_COMMENT_EMOJI_ROW.resetToDefault();
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
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_EMOJI_ROW.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENTS.key));
    }
    /** Without the other comment patches, Comments still opens for this switch alone. */
    @Test public void aloneItStillGetsComments() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_EMOJI_ROW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_EMOJI_ROW.key);
        assertNotNull(row);
        assertEquals("Comments", row.getParent().getTitle().toString());
    }
    /** Settings are a dialog over Instagram's screen, which doesn't resume when they close, so flipping on reaches it at once. */
    @Test public void flippingOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_EMOJI_ROW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_EMOJI_ROW.key);
        try {
            assertFalse(CommentEmojiRow.listeningForTests(controller.get()));
            assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
            // The switch saves after its listener, so the helper reads the value once the change is posted.
            Settings.HIDE_COMMENT_EMOJI_ROW.save(true);
            ShadowLooper.idleMainLooper();
            assertTrue(CommentEmojiRow.listeningForTests(controller.get()));
        } finally {
            CommentEmojiRow.resetForTests();
        }
    }
    @Test public void switchStartsOffUnderCommentsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.HIDE_COMMENTS, PatchFamily.COMMENT_EMOJI_ROW));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_EMOJI_ROW.key);
        assertNotNull(row);
        assertEquals("Hide the emoji row", row.getTitle().toString());
        assertEquals("Takes the row of quick emoji off above the comment box. The box itself stays.",
                row.getSummary().toString());
        PreferenceGroup comments = row.getParent();
        assertEquals("Comments", comments.getTitle().toString());
        assertEquals("right after Hide comments", Settings.HIDE_COMMENTS.key,
                comments.getPreference(indexOf(comments, row) - 1).getKey());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_COMMENT_EMOJI_ROW.get());
        assertFalse("each frame reads the switch, so no restart prompt", Settings.HIDE_COMMENT_EMOJI_ROW.rebootApp);
        assertEquals(Collections.singletonList(Settings.HIDE_COMMENT_EMOJI_ROW), PatchFamily.COMMENT_EMOJI_ROW.switches);
        assertEquals("Hide the comment emoji row", PatchFamily.COMMENT_EMOJI_ROW.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_COMMENT_EMOJI_ROW.key));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
