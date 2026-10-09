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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Emoji style's switch: under Layout after the bottom space, off to start, off while paused, applied on restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class EmojiStyleSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.NOTO_EMOJI.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.NOTO_EMOJI.resetToDefault();
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
    @Test public void missingPatchHasNoEmojiSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE));
        assertNull(page.getPreferenceScreen().findPreference(Settings.NOTO_EMOJI.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.REMOVE_BOTTOM_SPACE.key));
    }
    /** Without Remove the empty space at the bottom, Layout still opens for Emoji style alone. */
    @Test public void emojiAloneStillGetsLayout() throws Exception {
        open(EnumSet.of(PatchFamily.EMOJI_STYLE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.NOTO_EMOJI.key);
        assertNotNull(row);
        assertEquals("Layout", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.REMOVE_BOTTOM_SPACE.key));
    }
    @Test public void emojiSwitchStartsOffUnderLayoutPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE, PatchFamily.EMOJI_STYLE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.NOTO_EMOJI.key);
        assertNotNull(row);
        assertEquals("Google's emoji everywhere", row.getTitle().toString());
        assertEquals("Shows every emoji in Google's style instead of your phone's own. Restart Instagram to see the "
                + "change.", row.getSummary().toString());
        PreferenceGroup layout = row.getParent();
        assertEquals("Layout", layout.getTitle().toString());
        String[] keys = new String[layout.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = layout.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.REMOVE_BOTTOM_SPACE.key, Settings.NOTO_EMOJI.key), Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.NOTO_EMOJI.get());
        assertTrue("text already drawn keeps its style until a restart", Settings.NOTO_EMOJI.rebootApp);
        assertEquals(java.util.Collections.singletonList(Settings.NOTO_EMOJI), PatchFamily.EMOJI_STYLE.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.NOTO_EMOJI.key));

        Settings.NOTO_EMOJI.save(true);
        assertTrue(Settings.NOTO_EMOJI.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.NOTO_EMOJI.get());
        assertTrue(Settings.NOTO_EMOJI.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.NOTO_EMOJI.get());
    }
}
