/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Arrays;
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
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;

/**
 * Hide reels with few views' rows (#444, #780): the switch, off to start, and under it the number
 * rows (views, likes, likes and comments per 1,000 views, length, age), Newest first, and Also on
 * Home when Home's reads are in the build.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class LowViewReelsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    /** Every setting under the switch, in the order its rows show. */
    private static List<Setting<?>> rows() {
        return Arrays.asList(Settings.MIN_REEL_VIEWS, Settings.MAX_REEL_VIEWS, Settings.MIN_REEL_LIKES,
                Settings.MIN_REEL_LIKES_PER_1000, Settings.MIN_REEL_COMMENTS_PER_1000, Settings.MIN_REEL_SECONDS,
                Settings.MAX_REEL_SECONDS, Settings.MAX_REEL_AGE_DAYS, Settings.REELS_NEWEST_FIRST, Settings.HIDE_LOW_VIEW_HOME);
    }

    private static void resetAll() {
        Settings.HIDE_LOW_VIEW_REELS.resetToDefault();
        for (Setting<?> row : rows()) row.resetToDefault();
    }

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        resetAll();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        resetAll();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(PatchFamily... installed) throws Exception {
        PatchFamily.inBuildForTests = installed.length == 0
                ? EnumSet.noneOf(PatchFamily.class) : EnumSet.copyOf(Arrays.asList(installed));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    private Preference row(Setting<?> setting) {
        return page.getPreferenceScreen().findPreference(setting.key);
    }
    private HushgramPreferenceFragment.MinCountRow count(Setting<?> setting) {
        return (HushgramPreferenceFragment.MinCountRow) row(setting);
    }
    @Test public void missingPatchHasNoRows() throws Exception {
        open();
        assertNull(row(Settings.HIDE_LOW_VIEW_REELS));
        for (Setting<?> setting : rows()) {
            assertNull(setting.key, row(setting));
            assertFalse(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
        }
    }
    @Test public void theRowsSitUnderTheirSwitchUnderReelsAndWaitForIt() throws Exception {
        open(PatchFamily.LOW_VIEW_REELS, PatchFamily.FEED_SUGGESTIONS);
        SwitchPreference hide = (SwitchPreference) row(Settings.HIDE_LOW_VIEW_REELS);
        assertNotNull(hide);
        assertEquals("Hide reels with few views", hide.getTitle().toString());
        assertFalse("off to start", hide.isChecked());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), hide);
        assertNotNull(section);
        assertEquals("Reels", section.getTitle().toString());

        List<String> titles = new ArrayList<>();
        int at = indexIn(section, hide);
        for (Setting<?> setting : rows()) {
            Preference row = row(setting);
            assertNotNull(setting.key, row);
            assertSame(setting.key, section, categoryHolding(page.getPreferenceScreen(), row));
            assertEquals(setting.key + " follows the row before it", ++at, indexIn(section, row));
            assertFalse(setting.key + " waits for the switch", row.isEnabled());
            titles.add(row.getTitle().toString());
        }
        assertEquals(Arrays.asList("Minimum views", "Maximum views", "Minimum likes", "Likes per 1,000 views",
                "Comments per 1,000 views", "Shortest video", "Longest video", "Oldest post", "Newest first",
                "Also on Home"), titles);
        assertFalse("Newest first starts off", ((SwitchPreference) row(Settings.REELS_NEWEST_FIRST)).isChecked());
        assertFalse("Also on Home starts off", ((SwitchPreference) row(Settings.HIDE_LOW_VIEW_HOME)).isChecked());

        hide.setChecked(true);
        ShadowLooper.idleMainLooper();
        for (Setting<?> setting : rows()) {
            assertTrue(setting.key, row(setting).isEnabled());
            assertTrue(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
        }
    }
    @Test public void alsoOnHomeNeedsHomesReads() throws Exception {
        open(PatchFamily.LOW_VIEW_REELS);
        assertNotNull(row(Settings.REELS_NEWEST_FIRST));
        assertNull(row(Settings.HIDE_LOW_VIEW_HOME));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_LOW_VIEW_HOME.key));
    }
    @Test public void theSummaryFormatsTheNumberAndTheValueStaysInRange() throws Exception {
        open(PatchFamily.LOW_VIEW_REELS);
        HushgramPreferenceFragment.MinCountRow min = count(Settings.MIN_REEL_VIEWS);
        assertEquals(String.format("Fewer than %s views", java.text.NumberFormat.getIntegerInstance().format(1000)),
                min.getSummary().toString());
        min.setText("25000");
        assertEquals(String.format("Fewer than %s views", java.text.NumberFormat.getIntegerInstance().format(25000)),
                min.getSummary().toString());
        min.setText("0");
        assertEquals("Views aren't looked at", min.getSummary().toString());
        assertEquals(0, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MIN_REEL_VIEWS, "0"));
        assertEquals(100_000_000, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MIN_REEL_VIEWS, "999999999"));
        assertEquals(1000, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MIN_REEL_VIEWS, "abc"));
        assertEquals(1000, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MIN_REEL_VIEWS, null));
    }
    @Test public void theLikesSummarySaysWhenLikesArentLookedAt() throws Exception {
        open(PatchFamily.LOW_VIEW_REELS);
        HushgramPreferenceFragment.MinCountRow likes = count(Settings.MIN_REEL_LIKES);
        assertEquals("Fewer than 10 likes", likes.getSummary().toString());
        likes.setText("0");
        assertEquals("Likes aren't looked at", likes.getSummary().toString());
        likes.setText("2500");
        assertEquals(String.format("Fewer than %s likes", java.text.NumberFormat.getIntegerInstance().format(2500)),
                likes.getSummary().toString());
        assertEquals(0, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MIN_REEL_LIKES, "-3"));
        assertEquals(10, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MIN_REEL_LIKES, ""));
    }
    @Test public void theNewNumbersSayWhatZeroMeansAndCountInWords() throws Exception {
        open(PatchFamily.LOW_VIEW_REELS);
        String million = java.text.NumberFormat.getIntegerInstance().format(1_000_000);
        HushgramPreferenceFragment.MinCountRow max = count(Settings.MAX_REEL_VIEWS);
        assertEquals("No maximum", max.getSummary().toString());
        max.setText("1000000");
        assertEquals("More than " + million + " views", max.getSummary().toString());
        assertEquals(999_999_999, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MAX_REEL_VIEWS, "999999999"));

        HushgramPreferenceFragment.MinCountRow likes = count(Settings.MIN_REEL_LIKES_PER_1000);
        assertEquals("Not looked at", likes.getSummary().toString());
        likes.setText("20");
        assertEquals("Fewer than 20 likes per 1,000 views", likes.getSummary().toString());
        assertEquals(1000, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MIN_REEL_LIKES_PER_1000, "5000"));

        HushgramPreferenceFragment.MinCountRow comments = count(Settings.MIN_REEL_COMMENTS_PER_1000);
        assertEquals("Not looked at", comments.getSummary().toString());
        comments.setText("3");
        assertEquals("Fewer than 3 comments per 1,000 views", comments.getSummary().toString());

        HushgramPreferenceFragment.MinCountRow shortest = count(Settings.MIN_REEL_SECONDS);
        assertEquals("No shortest", shortest.getSummary().toString());
        shortest.setText("1");
        assertEquals("Shorter than 1 second", shortest.getSummary().toString());
        shortest.setText("15");
        assertEquals("Shorter than 15 seconds", shortest.getSummary().toString());

        HushgramPreferenceFragment.MinCountRow longest = count(Settings.MAX_REEL_SECONDS);
        assertEquals("No longest", longest.getSummary().toString());
        longest.setText("90");
        assertEquals("Longer than 90 seconds", longest.getSummary().toString());
        assertEquals(86_400, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MAX_REEL_SECONDS, "99999999"));

        HushgramPreferenceFragment.MinCountRow oldest = count(Settings.MAX_REEL_AGE_DAYS);
        assertEquals("Any age", oldest.getSummary().toString());
        oldest.setText("1");
        assertEquals("Posted more than 1 day ago", oldest.getSummary().toString());
        oldest.setText("30");
        assertEquals("Posted more than 30 days ago", oldest.getSummary().toString());
        assertEquals(3650, HushgramPreferenceFragment.MinCountRow.valueOf(Settings.MAX_REEL_AGE_DAYS, "100000"));
    }
    @Test public void theEditDialogResizesAboveTheKeyboard() throws Exception {
        open(PatchFamily.LOW_VIEW_REELS);
        HushgramPreferenceFragment.MinCountRow min = count(Settings.MAX_REEL_AGE_DAYS);
        min.showDialog(null);
        ShadowLooper.idleMainLooper();
        try {
            int mode = min.getDialog().getWindow().getAttributes().softInputMode;
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        } finally {
            min.getDialog().dismiss();
        }
    }
    private static int indexIn(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == row) return i;
        }
        return -1;
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
