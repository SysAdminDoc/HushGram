/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.shared.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Turn off double tap to like and the two switches under it, one for posts and one for reels: where
 * they sit, when they can be changed, and what the diagnostic report says the patch is doing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class DoubleTapLikeSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.resetToDefault();
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.resetToDefault();
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.resetToDefault();
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS.resetToDefault();
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES.resetToDefault();
    }

    /** Posts, then reels, right under the switch, each on from the start. */
    @Test
    public void postsAndReelsSitUnderTheSwitch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DOUBLE_TAP_LIKE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(DownloadSettingsTest.pageIn(controller));
            int main = indexOfKey(rows, Settings.TURN_OFF_DOUBLE_TAP_LIKE.key);
            assertTrue("no double tap switch", main >= 0);
            assertEquals(main + 1, indexOfKey(rows, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.key));
            assertEquals(main + 2, indexOfKey(rows, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.key));
            assertEquals("On posts", String.valueOf(rows.get(main + 1).getTitle()));
            assertEquals("On reels", String.valueOf(rows.get(main + 2).getTitle()));
            for (int i = main; i <= main + 2; i++) {
                assertTrue(rows.get(i).getKey(), ((SwitchPreference) rows.get(i)).isChecked());
                assertTrue(rows.get(i).getKey() + " can't be changed", rows.get(i).isEnabled());
            }
            assertEquals(main + 3, indexOfKey(rows, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS.key));
            assertEquals("On comments", String.valueOf(rows.get(main + 3).getTitle()));
            assertFalse("comments start off", ((SwitchPreference) rows.get(main + 3)).isChecked());
            assertTrue(rows.get(main + 3).isEnabled());
            assertEquals(main + 4, indexOfKey(rows, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES.key));
            assertEquals("On messages", String.valueOf(rows.get(main + 4).getTitle()));
            assertFalse("messages start off", ((SwitchPreference) rows.get(main + 4)).isChecked());
            assertTrue(rows.get(main + 4).isEnabled());
        }
    }

    /** With the switch off, the two under it are greyed out and keep what they hold. */
    @Test
    public void withTheSwitchOffTheTwoUnderItAreGreyedOut() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.DOUBLE_TAP_LIKE);
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(DownloadSettingsTest.pageIn(controller));
            Preference posts = rows.get(indexOfKey(rows, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.key));
            Preference reels = rows.get(indexOfKey(rows, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.key));
            assertFalse("posts can be changed with the switch off", posts.isEnabled());
            assertFalse("reels can be changed with the switch off", reels.isEnabled());
            assertTrue(((SwitchPreference) posts).isChecked());
            assertTrue(((SwitchPreference) reels).isChecked());
        }
    }

    /**
     * The report calls the patch on only while it holds back a double tap somewhere: the switch on
     * and at least one of the two under it on.
     */
    @Test
    public void theReportSaysOnOnlyWhileADoubleTapIsHeldBack() {
        assertTrue(line(), line().contains(": on ("));

        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.save(false);
        assertTrue(line(), line().contains(": on ("));
        assertTrue(line(), line().contains(Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.key + "=off"));

        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.save(false);
        assertTrue(line(), line().contains(": disabled by its switch ("));

        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS.save(true);
        Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.save(true);
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(false);
        assertTrue(line(), line().contains(": disabled by its switch ("));
    }

    private static String line() {
        return PatchFamily.reportLines(EnumSet.of(PatchFamily.DOUBLE_TAP_LIKE), false).get(0);
    }

    private static List<Preference> rowsOf(HushgramPreferenceFragment page) {
        List<Preference> rows = new ArrayList<>();
        collect(page.getPreferenceScreen(), rows);
        assertFalse("the screen has no rows", rows.isEmpty());
        return rows;
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                collect((PreferenceGroup) preference, rows);
            } else {
                rows.add(preference);
            }
        }
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }
}
