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
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.profile.Unfollowers;
import app.hushgram.extension.instagram.stories.StoryViewers;
import app.hushgram.extension.instagram.stories.StoryViewersRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Keep story viewers (#710): a switch, the Story viewers row and Clear story viewers, in the Stories
 * category with the patch in the build. The switch starts off and goes in a settings file, the lists
 * never do. Its page picks a period and a view, opens a story on who viewed it and which followers
 * aren't on its list, searches, and opens the profile tapped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class StoryViewersSettingsTest {
    private static final long DAY = StoryViewers.DAY;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.STORY_VIEWERS.resetToDefault();
        Context context = RuntimeEnvironment.getApplication();
        StoryViewers.clear(context);
        Unfollowers.clear(context);
        StoryViewersRows.forget();
    }

    @Test public void theSwitchStartsOffBelongsToItsPatchAndIsBackedUp() {
        assertFalse(Settings.STORY_VIEWERS.get());
        assertTrue(PatchFamily.STORY_VIEWERS.switches.contains(Settings.STORY_VIEWERS));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_VIEWERS);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_VIEWERS.key));
    }

    @Test public void theRowsAreInStoriesWithThePatchAndGoneWithoutIt() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_VIEWERS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference toggle = page.findPreference(Settings.STORY_VIEWERS.key);
            assertTrue(toggle instanceof SwitchPreference);
            assertEquals("Keep story viewers", String.valueOf(toggle.getTitle()));
            assertFalse(((SwitchPreference) toggle).isChecked());
            Preference list = page.findPreference(HushgramPreferenceFragment.STORY_VIEWERS_ROW);
            assertNotNull(list);
            assertEquals("Story viewers", String.valueOf(list.getTitle()));
            assertTrue("it opens the page", list.isSelectable());
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_STORY_VIEWERS);
            assertNotNull(clear);
            assertEquals("Clear story viewers", String.valueOf(clear.getTitle()));
            assertTrue(((HushgramPreferenceFragment.Row) clear).actsOnTap());
            for (Preference row : Arrays.asList(toggle, list, clear)) {
                assertEquals(String.valueOf(row.getTitle()), "Stories",
                        String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
            }
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VOICE_MESSAGE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            assertNull(page.findPreference(Settings.STORY_VIEWERS.key));
            assertNull(page.findPreference(HushgramPreferenceFragment.STORY_VIEWERS_ROW));
            assertNull(page.findPreference(HushgramPreferenceFragment.CLEAR_STORY_VIEWERS));
        }
    }

    @Test public void theRowCountsTheStoriesKeptInEitherForm() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("Nothing here yet. With Keep story viewers on, open who viewed one of your stories in "
                        + "Instagram and the list is kept here.",
                HushgramPreferenceFragment.storyViewersSummary(context, StoryViewers.summary(context, 0, Collections.emptyMap())));
        long now = System.currentTimeMillis();
        StoryViewersRows.keep(context, "1", now - DAY, 1, now, "11", "ana");
        String one = HushgramPreferenceFragment.storyViewersSummary(context, StoryViewers.summary(context, 0, Collections.emptyMap()));
        assertTrue(one, one.startsWith("1 story kept since ") && one.endsWith(". Tap to see who viewed it and who has no view recorded."));
        StoryViewersRows.keep(context, "2", now, 1, now, "11", "ana");
        String two = HushgramPreferenceFragment.storyViewersSummary(context, StoryViewers.summary(context, 0, Collections.emptyMap()));
        assertTrue(two, two.startsWith("2 stories kept since "));
    }

    /** Periods and views over the kept lists beside the followers, a story opened, a search and a profile tap. */
    @Test public void thePageSetsTheViewersBesideTheFollowers() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        StoryViewersRows.keep(context, "100", now - 40 * DAY, 1, now, "14", "dee");
        StoryViewersRows.keep(context, "101", now - 2 * DAY, 2, now, "11", "ana", "12", "bo");
        StoryViewersRows.keep(context, "102", now - DAY, 3, now, "11", "ana");
        Map<String, String> followers = new LinkedHashMap<>();
        followers.put("11", "ana");
        followers.put("12", "bo");
        followers.put("14", "dee");
        Unfollowers.take(context, "1001", followers, now);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_VIEWERS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.STORY_VIEWERS_ROW);
            StoryViewersPage page = StoryViewersPage.open(controller.get(), row);
            settle();
            assertTrue(page.dialog.isShowing());
            assertEquals("the keyboard pushes the list up rather than covering it",
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    page.dialog.getWindow().getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals(Arrays.asList("Last 7 days", "Last 30 days", "Last 90 days", "All kept"), labels(page.periods));
            assertEquals(Arrays.asList("Viewers", "No view recorded", "Frequent", "Stories"), labels(page.views));
            assertTrue("the last 30 days to start", page.periods.getChildAt(1).isSelected());
            assertEquals("Stories kept: 2. Viewers: 2. 67% of your followers viewed at least one.",
                    String.valueOf(page.overview.getText()));

            assertEquals(Arrays.asList("@ana", "@bo"), titles(page));
            String ana = entry(page, 0).detail;
            assertTrue(ana, ana.startsWith("Stories viewed: 2 of 2, the newest posted "));
            assertTrue("two of two is frequent", entry(page, 0).accent);
            assertFalse(entry(page, 1).accent);

            page.views.getChildAt(1).performClick();
            assertEquals(Arrays.asList("@dee"), titles(page));
            String dee = entry(page, 0).detail;
            assertTrue("dee viewed a story older than the period: " + dee,
                    dee.startsWith("None in this period. The last story they viewed is from "));

            page.views.getChildAt(2).performClick();
            assertEquals(Arrays.asList("@ana"), titles(page));

            page.views.getChildAt(3).performClick();
            assertEquals("newest first", 2, page.list.getAdapter().getCount());
            assertEquals("Viewers kept: 1 of 3", entry(page, 0).detail);
            assertEquals("Viewers: 2. Followers not on the list: 1", entry(page, 1).detail);
            assertNull("a story has no profile to open", StoryViewersPage.link(entry(page, 1)));

            page.list.performItemClick(null, 1, 1);
            assertNotNull(page.opened);
            assertEquals(Arrays.asList("@ana", "@bo", "@dee"), titles(page));
            assertEquals("Viewed this story", entry(page, 0).detail);
            assertEquals("Not on this story's list", entry(page, 2).detail);
            assertFalse("only the story's own viewers are marked", entry(page, 2).accent);
            assertTrue(String.valueOf(page.overview.getText()), String.valueOf(page.overview.getText()).startsWith("Story from "));
            page.dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            page.dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            assertNull("back goes to the stories, not off the page", page.opened);
            assertTrue(page.dialog.isShowing());
            assertEquals(2, page.list.getAdapter().getCount());

            page.periods.getChildAt(3).performClick();
            settle();
            assertTrue(page.periods.getChildAt(3).isSelected());
            assertEquals("all kept", 3, page.list.getAdapter().getCount());
            page.views.getChildAt(1).performClick();
            assertEquals(0, page.list.getAdapter().getCount());
            assertEquals("Every follower viewed at least one story in this period.", String.valueOf(page.empty.getText()));

            page.views.getChildAt(0).performClick();
            page.search.setText("@BO");
            assertEquals("a search goes by the username, in any case, with or without the at", Arrays.asList("@bo"), titles(page));
            page.search.setText("nobody");
            assertEquals("Nothing matches.", String.valueOf(page.empty.getText()));
            page.search.setText("");

            android.app.FragmentManager fragments = controller.get().getFragmentManager();
            new android.app.DialogFragment().show(fragments, SettingsEntry.DIALOG_TAG);
            fragments.executePendingTransactions();
            page.list.performItemClick(null, 0, 0);
            android.content.Intent opened = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals("https://www.instagram.com/ana/", String.valueOf(opened.getData()));
            assertEquals("its own package, so a copy next to Instagram opens itself", context.getPackageName(), opened.getPackage());
            assertFalse(page.dialog.isShowing());
        }
    }

    @Test public void withoutAFollowersListThePageSaysHowToGetOne() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_VIEWERS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.STORY_VIEWERS_ROW);
            StoryViewersPage none = StoryViewersPage.open(controller.get(), row);
            settle();
            assertTrue(String.valueOf(none.empty.getText()), String.valueOf(none.empty.getText()).startsWith("Nothing here yet."));
            assertNotNull("an empty page shows a mark over its words", none.empty.getCompoundDrawablesRelative()[1]);
            none.dialog.dismiss();

            StoryViewersRows.keep(context, "101", now - DAY, 1, now, "11", "ana");
            StoryViewersPage page = StoryViewersPage.open(controller.get(), row);
            settle();
            assertEquals("Stories kept: 1. Viewers: 1. To see followers with no view recorded, turn on Recent "
                    + "unfollowers and open your Followers list once.", String.valueOf(page.overview.getText()));
            page.views.getChildAt(1).performClick();
            assertTrue(String.valueOf(page.empty.getText()).startsWith("To see followers with no view recorded"));
        }
    }

    @Test public void clearEmptiesTheListsAtOnceAndSaysSoAndTheExportNeverHoldsThem() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        StoryViewersRows.keep(context, "101", now - DAY, 1, now, "11", "someone");
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_VIEWERS);
        Settings.STORY_VIEWERS.save(true);
        String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);
        assertTrue(exported.contains(Settings.STORY_VIEWERS.key));
        assertFalse("the story viewers went into a settings file", exported.contains("someone"));

        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            settle();
            Preference list = page.findPreference(HushgramPreferenceFragment.STORY_VIEWERS_ROW);
            assertTrue(String.valueOf(list.getSummary()), String.valueOf(list.getSummary()).startsWith("1 story kept since "));
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_STORY_VIEWERS);
            assertTrue(clear.getOnPreferenceClickListener().onPreferenceClick(clear));
            settle();
            assertEquals("Story viewers cleared.", ShadowToast.getTextOfLatestToast());
            assertEquals(0, StoryViewers.count(context));
            assertTrue(String.valueOf(list.getSummary()).startsWith("Nothing here yet."));
            assertTrue("the switch stays as it is", Settings.STORY_VIEWERS.get());
        }
        StoryViewersRows.forget();
        assertEquals("and the file went with it", 0, StoryViewers.count(context));
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private static StoryViewersPage.Entry entry(StoryViewersPage page, int position) {
        return (StoryViewersPage.Entry) page.list.getAdapter().getItem(position);
    }

    private static List<String> titles(StoryViewersPage page) {
        List<String> titles = new ArrayList<>();
        for (int at = 0; at < page.list.getAdapter().getCount(); at++) titles.add(entry(page, at).title);
        return titles;
    }

    private static List<String> labels(LinearLayout chips) {
        List<String> labels = new ArrayList<>();
        for (int at = 0; at < chips.getChildCount(); at++) labels.add(String.valueOf(((TextView) chips.getChildAt(at)).getText()));
        return labels;
    }
}
