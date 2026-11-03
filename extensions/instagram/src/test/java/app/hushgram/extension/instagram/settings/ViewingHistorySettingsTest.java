/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.preference.SwitchPreference;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import app.hushgram.extension.instagram.download.ViewingHistory;
import app.hushgram.extension.instagram.download.ViewingHistory.Kind;
import app.hushgram.extension.instagram.download.ViewingHistoryRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Keep a viewing history (#228): a switch, the list under it and Clear viewing history, in Ads and
 * privacy with the download patches in the build. The switch starts off and goes in a settings
 * file, the list never does, and Clear empties the list at once and says so.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ViewingHistorySettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PatchFamily.inBuildForTests = null;
        Settings.VIEWING_HISTORY.resetToDefault();
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
    }

    @Test public void theSwitchStartsOffBelongsToTheDownloadPatchAndIsBackedUp() {
        assertFalse(Settings.VIEWING_HISTORY.get());
        assertTrue(PatchFamily.VIDEO_DOWNLOAD.switches.contains(Settings.VIEWING_HISTORY));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.VIEWING_HISTORY.key));
    }

    @Test public void theRowsAreThereWithTheDownloadPatchAndGoneWithoutIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference toggle = page.findPreference(Settings.VIEWING_HISTORY.key);
            assertTrue(toggle instanceof SwitchPreference);
            assertEquals("Keep a viewing history", String.valueOf(toggle.getTitle()));
            assertFalse(((SwitchPreference) toggle).isChecked());
            Preference list = page.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            assertNotNull(list);
            assertTrue("it opens the page", list.isSelectable());
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_VIEWING_HISTORY);
            assertNotNull(clear);
            assertEquals("Clear viewing history", String.valueOf(clear.getTitle()));
            assertTrue(((HushgramPreferenceFragment.Row) clear).actsOnTap());
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VOICE_MESSAGE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            assertNull(page.findPreference(Settings.VIEWING_HISTORY.key));
            assertNull(page.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW));
            assertNull(page.findPreference(HushgramPreferenceFragment.CLEAR_VIEWING_HISTORY));
        }
    }

    @Test public void theListReadsNewestFirstWithWhoAndWhatAndNothingWhenEmpty() {
        long now = 10_000_000L;
        assertEquals("Nothing here yet. With Keep a viewing history on, what you look at shows up here.",
                HushgramPreferenceFragment.viewingHistorySummary(Collections.emptyList(), now));
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        ViewingHistory.forgetForTests();
        ViewingHistoryRows.add(context, Kind.PROFILE, "zed", "zed", now - 2 * 60_000L);
        ViewingHistoryRows.add(context, Kind.REEL, "1_2", "amy", now - 60_000L);
        String text = HushgramPreferenceFragment.viewingHistorySummary(ViewingHistory.entries(context), now);
        String[] lines = text.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0], lines[0].startsWith("@amy, Reel, "));
        assertTrue(lines[1], lines[1].startsWith("@zed, Profile, "));
        assertEquals("Tap to see all 2, by kind or by search.", lines[2]);
    }

    /** The reporter's page: a tab for each kind, a search box, and a tap opens the row in Instagram (#228). */
    @Test public void thePageSortsByKindSearchesAndOpensWhatYouTap() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        ViewingHistoryRows.add(context, Kind.PROFILE, "zed", "zed", now - 4_000L);
        ViewingHistoryRows.add(context, Kind.STORY, "3_3", "bob", now - 3_000L);
        ViewingHistoryRows.add(context, Kind.REEL, "2_2", "amy", now - 2_000L);
        ViewingHistoryRows.add(context, Kind.POST, "1_1", "amy", now - 1_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            ViewingHistoryPage page = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue(page.dialog.isShowing());
            assertEquals("every row on the first tab", 4, page.list.getAdapter().getCount());
            String[] labels = new String[page.tabs.length];
            for (int at = 0; at < labels.length; at++) labels[at] = String.valueOf(page.tabs[at].getText());
            assertEquals(Arrays.asList("All", "Posts", "Reels", "Stories", "Profiles"), Arrays.asList(labels));
            int[] counts = {4, 1, 1, 1, 1};
            for (int at = 0; at < counts.length; at++) {
                page.tabs[at].performClick();
                assertEquals(labels[at], counts[at], page.list.getAdapter().getCount());
                assertTrue(labels[at], page.tabs[at].isSelected());
            }
            page.tabs[0].performClick();
            page.search.setText("@AMY");
            assertEquals("a search goes by the poster, in any case, with or without the at", 2, page.list.getAdapter().getCount());
            page.search.setText("nobody");
            assertEquals(0, page.list.getAdapter().getCount());
            assertEquals("Nothing matches.", String.valueOf(page.empty.getText()));
            page.search.setText("");
            android.app.FragmentManager fragments = controller.get().getFragmentManager();
            new android.app.DialogFragment().show(fragments, SettingsEntry.DIALOG_TAG);
            fragments.executePendingTransactions();
            page.list.performItemClick(null, 0, 0);
            android.content.Intent opened = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals("https://www.instagram.com/p/B/", String.valueOf(opened.getData()));
            assertEquals("its own package, so a copy next to Instagram opens itself", context.getPackageName(), opened.getPackage());
            assertFalse("the post opens under the page, so the page gets out of its way", page.dialog.isShowing());
            fragments.executePendingTransactions();
            assertNull("and the settings do too, or they'd follow the post to the front",
                    fragments.findFragmentByTag(SettingsEntry.DIALOG_TAG));
        }
    }

    /**
     * The page wears the settings' own look, Black settings page included: the search is the
     * settings' field with a magnifier, the picked tab is underlined, a long row ends in an
     * ellipsis, and an empty page shows a mark over its words.
     */
    @Test public void thePageFollowsTheSettingsLookAndShowsEachState() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        Settings.SETTINGS_BLACK_LOOK.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            ViewingHistoryPage none = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue(String.valueOf(none.empty.getText()), String.valueOf(none.empty.getText()).startsWith("Nothing here yet."));
            assertNotNull("an empty page shows a mark over its words", none.empty.getCompoundDrawablesRelative()[1]);
            none.dialog.dismiss();

            ViewingHistoryRows.add(context, Kind.POST, "1_1", "someone_with_a_very_long_name_that_runs_on_and_on", now - 1_000L);
            ViewingHistoryPage page = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            ScreenColors black = ScreenColors.current();
            assertEquals("the field takes the black look's surface", android.graphics.Color.BLACK,
                    ((android.graphics.drawable.GradientDrawable) page.search.getBackground()).getColor().getDefaultColor());
            assertEquals(black.hint, page.search.getHintTextColors()
                    .getColorForState(new int[]{android.R.attr.state_enabled}, 0));
            assertNotNull("a magnifier at the field's start", page.search.getCompoundDrawablesRelative()[0]);

            page.tabs[2].performClick();
            for (int at = 0; at < page.tabs.length; at++) {
                android.graphics.drawable.Drawable line = ((android.graphics.drawable.RippleDrawable) page.tabs[at].getBackground())
                        .getDrawable(0).getCurrent();
                if (at == 2) assertNotNull("the picked tab is underlined", line);
                else assertNull("tab " + at + " isn't picked, so no line", line);
            }

            page.tabs[0].performClick();
            android.widget.LinearLayout shown = (android.widget.LinearLayout) page.list.getAdapter().getView(0, null, page.list);
            for (int at = 0; at < shown.getChildCount(); at++) {
                assertEquals(android.text.TextUtils.TruncateAt.END, ((android.widget.TextView) shown.getChildAt(at)).getEllipsize());
            }

            page.search.setText("nobody");
            assertEquals("Nothing matches.", String.valueOf(page.empty.getText()));
            assertNotNull(page.empty.getCompoundDrawablesRelative()[1]);
        } finally {
            Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        }
    }

    @Test public void aRowLeadsToItsPostOrItsPerson() {
        assertEquals("the world record egg's real address", "https://www.instagram.com/p/BsOGulcndj-/",
                String.valueOf(ViewingHistoryPage.link(ViewingHistoryRows.row(Kind.POST, "1949525278281554174_2", "amy"))));
        assertEquals("https://www.instagram.com/reel/BsOGulcndj-/",
                String.valueOf(ViewingHistoryPage.link(ViewingHistoryRows.row(Kind.REEL, "1949525278281554174_2", "amy"))));
        assertEquals("a story is gone in a day, so its person", "https://www.instagram.com/amy/",
                String.valueOf(ViewingHistoryPage.link(ViewingHistoryRows.row(Kind.STORY, "1_2", "amy"))));
        assertEquals("https://www.instagram.com/zed/", String.valueOf(ViewingHistoryPage.link(ViewingHistoryRows.row(Kind.PROFILE, "zed", "zed"))));
        assertNull("a story with no name to go to", ViewingHistoryPage.link(ViewingHistoryRows.row(Kind.STORY, "1_2", "")));
        assertNull("a post whose id isn't a number", ViewingHistoryPage.link(ViewingHistoryRows.row(Kind.POST, "nope", "amy")));
    }

    @Test public void clearEmptiesTheListAtOnceAndSaysSoAndTheExportNeverHoldsIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        ViewingHistoryRows.add(context, Kind.POST, "99887766_1", "someone", System.currentTimeMillis());
        assertEquals(1, ViewingHistory.count(context));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        Settings.VIEWING_HISTORY.save(true);
        String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);
        assertTrue(exported.contains(Settings.VIEWING_HISTORY.key));
        assertFalse("the viewing history went into a settings file", exported.contains("99887766_1"));
        assertFalse("the viewing history went into a settings file", exported.contains("someone"));

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            Preference list = page.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            assertTrue(String.valueOf(list.getSummary()), String.valueOf(list.getSummary()).contains("@someone, Post"));
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_VIEWING_HISTORY);
            assertTrue(clear.getOnPreferenceClickListener().onPreferenceClick(clear));
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Viewing history cleared.", ShadowToast.getTextOfLatestToast());
            assertEquals(0, ViewingHistory.count(context));
            assertTrue(String.valueOf(list.getSummary()).startsWith("Nothing here yet."));
            assertTrue(Settings.VIEWING_HISTORY.get());
        }
        assertEquals(Arrays.asList(), ViewingHistory.entries(context));
    }
}
