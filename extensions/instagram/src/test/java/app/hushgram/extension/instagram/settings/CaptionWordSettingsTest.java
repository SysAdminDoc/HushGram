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
        // Other classes' filters count what they hide in these, and a count adds a line to the row.
        Settings.CAPTION_WORD_HIDDEN_TOTAL.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_HOURS.resetToDefault();
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
        Settings.CAPTION_WORDS_AS_PATTERNS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_HOME.resetToDefault();
        Settings.HIDE_CAPTION_WORD_COMMENTS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_SEARCH.resetToDefault();
        Settings.HIDE_OTHER_LANGUAGE_REELS.resetToDefault();
        Settings.HIDE_OTHER_LANGUAGE_HOME.resetToDefault();
        Settings.REELS_KEPT_LANGUAGES.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_TOTAL.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_HOURS.resetToDefault();
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
        assertNull(page.getPreferenceScreen().findPreference(Settings.CAPTION_WORDS_AS_PATTERNS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.REELS_BLOCKED_WORDS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.CAPTION_WORDS_AS_PATTERNS.key));
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
    /** Once the list has hidden something, the row says how much on a line under its count of words (#807). */
    @Test public void theRowSaysHowMuchTheListHid() throws Exception {
        long hour = System.currentTimeMillis() / (60L * 60L * 1000L);
        Settings.CAPTION_WORD_HIDDEN_TOTAL.save(340L);
        Settings.CAPTION_WORD_HIDDEN_HOURS.save((hour - 30) + ":200," + (hour - 2) + ":5," + hour + ":7");
        open(true);
        HushgramPreferenceFragment.WordsRow words =
                (HushgramPreferenceFragment.WordsRow) page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key);
        words.setText("spoiler\n@clickbait.daily");
        assertEquals("the hours more than a day old don't count", "2 words\n12 hidden in the last 24 hours, 340 in all",
                words.getSummary().toString());

        Settings.CAPTION_WORD_HIDDEN_TOTAL.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_HOURS.resetToDefault();
        words.setText("spoiler");
        assertEquals("nothing hidden yet, no line for it", "1 word", words.getSummary().toString());
    }
    /**
     * #883: Blocked words as regular expressions sits right under the list, off to start, waits for
     * the switch and goes in a backup. While it's on, the list names each entry it matches as plain words.
     */
    @Test public void thePatternsSwitchSitsUnderTheListWhichNamesWhatIsntAPattern() throws Exception {
        open(true);
        SwitchPreference hide = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_REELS.key);
        HushgramPreferenceFragment.WordsRow words =
                (HushgramPreferenceFragment.WordsRow) page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key);
        SwitchPreference patterns = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.CAPTION_WORDS_AS_PATTERNS.key);
        assertNotNull(patterns);
        assertEquals("Blocked words as regular expressions", patterns.getTitle().toString());
        assertFalse("off to start", patterns.isChecked());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), words);
        assertSame(section, categoryHolding(page.getPreferenceScreen(), patterns));
        assertEquals("right under the list", indexIn(section, words) + 1, indexIn(section, patterns));
        assertFalse("waits for the switch", patterns.isEnabled());
        hide.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(patterns.isEnabled());
        assertTrue("a backup keeps it", ConfigurationBackup.eligible().containsKey(Settings.CAPTION_WORDS_AS_PATTERNS.key));

        words.setText("cr[iy]pto\n(unclosed\n(a+)+b");
        assertEquals("off, every entry is plain words", "3 words", words.getSummary().toString());
        assertTrue(patterns.getOnPreferenceChangeListener().onPreferenceChange(patterns, true));
        patterns.setChecked(true);
        assertEquals("flipping it on names them at once", "3 words\nMatched as plain words: (unclosed and (a+)+b",
                words.getSummary().toString());
        words.setText("cr[iy]pto, ok\\d");
        assertEquals("every entry a pattern, no line", "2 words", words.getSummary().toString());
        words.setText("(unclosed");
        assertEquals("1 word\nMatched as plain words: (unclosed", words.getSummary().toString());
        assertTrue(patterns.getOnPreferenceChangeListener().onPreferenceChange(patterns, false));
        patterns.setChecked(false);
        assertEquals("flipping it off drops the line", "1 word", words.getSummary().toString());
        page.searchSettings("hide reels by caption words");
        assertNotNull("the patch's name finds it", patterns.getParent());
        page.searchSettings("");
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
            Preference patterns = page.getPreferenceScreen().findPreference(Settings.CAPTION_WORDS_AS_PATTERNS.key);
            PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), words);
            assertEquals("right under the list's patterns switch", indexIn(section, patterns) + 1, indexIn(section, home));
            assertFalse("waits for the switch", home.isEnabled());
            hide.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(home.isEnabled());
            assertTrue("a backup keeps it", ConfigurationBackup.eligible().containsKey(Settings.HIDE_CAPTION_WORD_HOME.key));
            page.searchSettings("hide reels by caption words");
            assertNotNull("the patch's name finds it", home.getParent());
            page.searchSettings("");
            controller.close();
            controller = null;

            PatchFamily.feedTypesForTests = false;
            open(true);
            assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_HOME.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_CAPTION_WORD_HOME.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_OTHER_LANGUAGE_HOME.key));
        } finally {
            PatchFamily.feedTypesForTests = null;
        }
    }
    /** #807: Also in comments sits under the list, off to start, waits for the switch and goes in a backup. */
    @Test public void alsoInCommentsSitsUnderTheListAndWaitsForTheSwitch() throws Exception {
        PatchFamily.feedTypesForTests = false;
        try {
            open(true);
            SwitchPreference hide = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_REELS.key);
            Preference words = page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key);
            SwitchPreference comments = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_COMMENTS.key);
            assertNotNull(comments);
            assertEquals("Also in comments", comments.getTitle().toString());
            assertEquals("Hides comments that hold a word or phrase from the list too, and comments by an account "
                    + "the list names with @. A hidden comment's replies go with it.", comments.getSummary().toString());
            assertFalse("off to start", comments.isChecked());
            Preference patterns = page.getPreferenceScreen().findPreference(Settings.CAPTION_WORDS_AS_PATTERNS.key);
            PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), words);
            assertEquals("right under the list's patterns switch without Home's reads",
                    indexIn(section, patterns) + 1, indexIn(section, comments));
            assertFalse("waits for the switch", comments.isEnabled());
            hide.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(comments.isEnabled());
            assertTrue("a backup keeps it", ConfigurationBackup.eligible().containsKey(Settings.HIDE_CAPTION_WORD_COMMENTS.key));
            page.searchSettings("hide reels by caption words");
            assertNotNull("the patch's name finds it", comments.getParent());
            page.searchSettings("");
        } finally {
            PatchFamily.feedTypesForTests = null;
        }
    }
    /** #807: Also in search sits under Also in comments, off to start, waits for the switch and goes in a backup. */
    @Test public void alsoInSearchSitsUnderAlsoInCommentsAndWaitsForTheSwitch() throws Exception {
        PatchFamily.feedTypesForTests = false;
        try {
            open(true);
            SwitchPreference hide = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_REELS.key);
            Preference comments = page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_COMMENTS.key);
            SwitchPreference search = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_SEARCH.key);
            assertNotNull(search);
            assertEquals("Also in search", search.getTitle().toString());
            assertEquals("Takes rows of the Explore grid and of search results out when a post in them holds a "
                    + "word or phrase from the list, or comes from an account the list names with @.", search.getSummary().toString());
            assertFalse("off to start", search.isChecked());
            PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), comments);
            assertEquals("right under Also in comments", indexIn(section, comments) + 1, indexIn(section, search));
            assertFalse("waits for the switch", search.isEnabled());
            hide.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(search.isEnabled());
            assertTrue("a backup keeps it", ConfigurationBackup.eligible().containsKey(Settings.HIDE_CAPTION_WORD_SEARCH.key));
            page.searchSettings("hide reels by caption words");
            assertNotNull("the patch's name finds it", search.getParent());
            page.searchSettings("");
        } finally {
            PatchFamily.feedTypesForTests = null;
        }
    }
    /** Hide reels in other languages (#495): its switch, the languages row and Also on Home sit under the caption word rows and wait for the switch. */
    @Test public void theLanguageRowsSitUnderTheCaptionWordRowsAndWaitForTheirSwitch() throws Exception {
        PatchFamily.feedTypesForTests = true;
        try {
            open(true);
            Preference words = page.getPreferenceScreen().findPreference(Settings.REELS_BLOCKED_WORDS.key);
            SwitchPreference home = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_HOME.key);
            SwitchPreference hide = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_OTHER_LANGUAGE_REELS.key);
            HushgramPreferenceFragment.WordsRow kept =
                    (HushgramPreferenceFragment.WordsRow) page.getPreferenceScreen().findPreference(Settings.REELS_KEPT_LANGUAGES.key);
            SwitchPreference otherHome = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_OTHER_LANGUAGE_HOME.key);
            assertNotNull(hide);
            assertNotNull(kept);
            assertNotNull(otherHome);
            assertEquals("Hide reels in other languages", hide.getTitle().toString());
            assertEquals("Languages to keep", kept.getTitle().toString());
            assertFalse("off to start", hide.isChecked());
            assertFalse("off to start", otherHome.isChecked());
            PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), words);
            assertSame(section, categoryHolding(page.getPreferenceScreen(), hide));
            Preference comments = page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_COMMENTS.key);
            Preference search = page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTION_WORD_SEARCH.key);
            assertEquals("Also in comments after Also on Home", indexIn(section, home) + 1, indexIn(section, comments));
            assertEquals("Also in search after Also in comments", indexIn(section, comments) + 1, indexIn(section, search));
            assertEquals("right after the caption word rows", indexIn(section, search) + 1, indexIn(section, hide));
            assertEquals(indexIn(section, hide) + 1, indexIn(section, kept));
            assertEquals(indexIn(section, kept) + 1, indexIn(section, otherHome));

            assertFalse(kept.isEnabled());
            assertFalse(otherHome.isEnabled());
            hide.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(kept.isEnabled());
            assertTrue(otherHome.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REELS_KEPT_LANGUAGES.key));
            assertTrue("a backup keeps Also on Home", ConfigurationBackup.eligible().containsKey(Settings.HIDE_OTHER_LANGUAGE_HOME.key));
            page.searchSettings("hide reels by caption words");
            assertNotNull("the patch's name finds the languages row", kept.getParent());
            page.searchSettings("");
        } finally {
            PatchFamily.feedTypesForTests = null;
        }
    }
    /** The languages row says what it understood and names a piece it didn't. */
    @Test public void theLanguagesRowSummaryShowsWhatWasUnderstood() throws Exception {
        open(true);
        HushgramPreferenceFragment.WordsRow kept =
                (HushgramPreferenceFragment.WordsRow) page.getPreferenceScreen().findPreference(Settings.REELS_KEPT_LANGUAGES.key);
        assertTrue(kept.getSummary().toString(), kept.getSummary().toString().startsWith("Keeps your phone's languages: "));
        kept.setText("en, Hindi");
        assertEquals("Keeps English, Hindi", kept.getSummary().toString());
        kept.setText("English, Klingonese");
        assertEquals("Keeps English. Didn't recognize: Klingonese", kept.getSummary().toString());
        assertEquals("English, Klingonese", Settings.REELS_KEPT_LANGUAGES.savedValue());
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
