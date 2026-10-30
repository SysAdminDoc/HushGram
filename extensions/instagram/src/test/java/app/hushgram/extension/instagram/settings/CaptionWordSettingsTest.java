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
import android.text.InputFilter;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide reels by caption words' rows (#192): its switch, off to start, under Reels, and the Blocked
 * words list right under it, which waits for it and says how many words it holds.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CaptionWordSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_CAPTION_WORD_REELS.resetToDefault();
        Settings.REELS_BLOCKED_WORDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_CAPTION_WORD_REELS.resetToDefault();
        Settings.REELS_BLOCKED_WORDS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_HOME.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed
                ? EnumSet.of(PatchFamily.CAPTION_WORD_REELS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRows() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_REELS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.REELS_BLOCKED_WORDS.key));
    }
    @Test public void theListSitsUnderItsSwitchUnderReelsAndWaitsForIt() throws Exception {
        open(true);
        SwitchPreference hide = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_REELS.key);
        HushgramPreferenceFragment.WordsRow words =
                (HushgramPreferenceFragment.WordsRow) page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key);
        assertNotNull(hide);
        assertNotNull(words);
        assertEquals("Hide reels by caption words", hide.getTitle().toString());
        assertFalse("off to start", hide.isChecked());
        assertEquals("Blocked words", words.getTitle().toString());
        assertEquals("No words yet", words.getSummary().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), hide);
        assertNotNull(section);
        assertEquals("Reels", section.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), words));
        assertEquals("right under its switch", indexIn(section, hide) + 1, indexIn(section, words));

        assertFalse("waits for the switch", words.isEnabled());
        hide.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(words.isEnabled());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REELS_BLOCKED_WORDS.key));
    }
    @Test public void theRowCountsItsWordsAndHoldsToTheFilesLimit() throws Exception {
        open(true);
        HushgramPreferenceFragment.WordsRow words =
                (HushgramPreferenceFragment.WordsRow) page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key);
        words.setText("spoiler");
        assertEquals("1 word", words.getSummary().toString());
        words.setText("spoiler, #ad\nGiveaway\n\nspoiler");
        assertEquals("a repeat and a blank line don't count", "3 words", words.getSummary().toString());
        assertEquals("spoiler, #ad\nGiveaway\n\nspoiler", Settings.REELS_BLOCKED_WORDS.savedValue());
        words.setText(" , ");
        assertEquals("No words yet", words.getSummary().toString());

        Settings.REELS_BLOCKED_WORDS.save("cat\ndog");
        assertEquals("2 words", HushgramPreferenceFragment.blockedWordsRow(controller.get()).getSummary().toString());
        InputFilter[] filters = words.getEditText().getFilters();
        assertEquals(1, filters.length);
        assertEquals(ConfigurationBackup.MAX_WORDS_TEXT, ((InputFilter.LengthFilter) filters[0]).getMax());
    }
    /** Also on Home sits under the list, off to start and waiting for the switch, only with Home's reads in the build. */
    @Test public void alsoOnHomeSitsUnderTheListWithHomesReadsInTheBuild() throws Exception {
        PatchFamily.feedTypesForTests = true;
        try {
            open(true);
            SwitchPreference hide = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_REELS.key);
            Preference words = page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key);
            SwitchPreference home = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_HOME.key);
            assertNotNull(home);
            assertEquals("Also on Home", home.getTitle().toString());
            assertFalse("off to start", home.isChecked());
            PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), words);
            assertEquals("right under the list", indexIn(section, words) + 1, indexIn(section, home));
            assertFalse("waits for the switch", home.isEnabled());
            hide.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(home.isEnabled());
            page.searchSettings("hide reels by caption words");
            assertNotNull("the patch's name finds it", home.getParent());
            page.searchSettings("");
            controller.close();
            controller = null;

            PatchFamily.feedTypesForTests = false;
            open(true);
            assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_HOME.key));
        } finally {
            PatchFamily.feedTypesForTests = null;
        }
    }
    /** Searching the patch's name finds the list too, which says neither word of it itself. */
    @Test public void searchingThePatchsNameFindsBothRows() throws Exception {
        open(true);
        Preference hide = page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_REELS.key);
        Preference words = page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key);
        page.searchSettings("hide reels by caption words");
        assertNotNull("the switch", hide.getParent());
        assertNotNull("the list", words.getParent());
        page.searchSettings("blocked words");
        assertNull(hide.getParent());
        assertNotNull(words.getParent());
        page.searchSettings("");
        assertNotNull(hide.getParent());
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
