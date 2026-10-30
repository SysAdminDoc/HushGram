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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Open comments without the keyboard switch: under Comments, off to start, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CommentKeyboardSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.COMMENT_KEYBOARD_DOWN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.COMMENT_KEYBOARD_DOWN.resetToDefault();
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
        open(EnumSet.of(PatchFamily.COMMENT_EMOJI_ROW));
        assertNull(page.getPreferenceScreen().findPreference(Settings.COMMENT_KEYBOARD_DOWN.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_COMMENT_EMOJI_ROW.key));
    }
    /** Without the other comment patches, Comments still opens for this switch alone. */
    @Test public void aloneItStillGetsComments() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_KEYBOARD));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COMMENT_KEYBOARD_DOWN.key);
        assertNotNull(row);
        assertEquals("Comments", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffUnderCommentsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.COMMENT_EMOJI_ROW, PatchFamily.COMMENT_KEYBOARD));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.COMMENT_KEYBOARD_DOWN.key);
        assertNotNull(row);
        assertEquals("Open comments without the keyboard", row.getTitle().toString());
        assertEquals("Comments open with the keyboard down. A tap in the comment box, or on Reply, still brings it up.",
                row.getSummary().toString());
        PreferenceGroup comments = row.getParent();
        assertEquals("Comments", comments.getTitle().toString());
        assertEquals("right after Hide the emoji row", Settings.HIDE_COMMENT_EMOJI_ROW.key,
                comments.getPreference(indexOf(comments, row) - 1).getKey());
        assertFalse(row.isChecked());
        assertFalse(Settings.COMMENT_KEYBOARD_DOWN.get());
        assertFalse("each sheet reads the switch as it opens, so no restart prompt", Settings.COMMENT_KEYBOARD_DOWN.rebootApp);
        assertEquals(Collections.singletonList(Settings.COMMENT_KEYBOARD_DOWN), PatchFamily.COMMENT_KEYBOARD.switches);
        assertEquals("Open comments without the keyboard", PatchFamily.COMMENT_KEYBOARD.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.COMMENT_KEYBOARD_DOWN.key));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
