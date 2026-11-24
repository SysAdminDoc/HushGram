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
 * Keep a viewing history (#228): a switch, the list under it and Clear viewing history, in a
 * Viewing history category of their own with the download patches in the build. The switch starts
 * off and goes in a settings file, the list never does, and Clear empties the list at once and says so.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ViewingHistorySettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PatchFamily.inBuildForTests = null;
        Settings.VIEWING_HISTORY.resetToDefault();
        Settings.VIEWING_HISTORY_GRID.resetToDefault();
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
            assertEquals("Recently seen", String.valueOf(list.getTitle()));
            assertTrue("it opens the page", list.isSelectable());
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_VIEWING_HISTORY);
            assertNotNull(clear);
            assertEquals("Clear viewing history", String.valueOf(clear.getTitle()));
            assertTrue(((HushgramPreferenceFragment.Row) clear).actsOnTap());
            // The reporter asked for it out of Ads and privacy, as a category of its own.
            for (Preference row : Arrays.asList(toggle, list, clear)) {
                assertEquals(String.valueOf(row.getTitle()), "Viewing history",
                        String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
            }
            page.searchSettings("Ads and privacy");
            assertNotNull("a search for where it used to be still finds it",
                    page.getPreferenceScreen().findPreference(Settings.VIEWING_HISTORY.key));
            page.searchSettings("Spoof location");
            assertNull(page.getPreferenceScreen().findPreference(Settings.VIEWING_HISTORY.key));
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
            android.widget.LinearLayout words = ViewingHistoryPage.Rows.texts(shown);
            assertEquals(2, words.getChildCount());
            for (int at = 0; at < words.getChildCount(); at++) {
                assertEquals(android.text.TextUtils.TruncateAt.END, ((android.widget.TextView) words.getChildAt(at)).getEllipsize());
            }
            // #228: a picture at the row's start, the post's icon until its picture comes (this row has none).
            android.widget.ImageView picture = ViewingHistoryPage.Rows.picture(shown);
            assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO, picture.getImportantForAccessibility());
            assertNotNull(picture.getDrawable());
            assertEquals(ViewingHistoryPage.PICTURE_DP * context.getResources().getDisplayMetrics().density,
                    picture.getLayoutParams().width, 1f);

            page.search.setText("nobody");
            assertEquals("Nothing matches.", String.valueOf(page.empty.getText()));
            assertNotNull(page.empty.getCompoundDrawablesRelative()[1]);
        } finally {
            Settings.SETTINGS_BLACK_LOOK.resetToDefault();
        }
    }

    /** #228: a row with a picture shows it, fetched off the main thread, and a row without keeps its kind's icon. */
    @Test public void aRowShowsItsPictureOnceItsFetched() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        ViewingHistoryPage.forgetPictures();
        ViewingHistoryRows.add(context, Kind.PROFILE, "zed", "zed", "", now - 2_000L);
        ViewingHistoryRows.add(context, Kind.REEL, "2_2", "amy", "https://scontent.cdninstagram.com/r.jpg", now - 1_000L);
        android.graphics.Bitmap cover = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
        java.util.Set<String> asked = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        Runnable restore = ViewingHistoryRows.fetchAnswering(cover, asked);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            ViewingHistoryPage page = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            android.widget.LinearLayout reel = (android.widget.LinearLayout) page.list.getAdapter().getView(0, null, page.list);
            android.widget.LinearLayout profile = (android.widget.LinearLayout) page.list.getAdapter().getView(1, null, page.list);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            android.graphics.drawable.Drawable shown = ViewingHistoryPage.Rows.picture(reel).getDrawable();
            assertTrue(String.valueOf(shown), shown instanceof android.graphics.drawable.BitmapDrawable);
            assertTrue(((android.graphics.drawable.BitmapDrawable) shown).getBitmap() == cover);
            assertFalse("no picture, so its icon stays",
                    ViewingHistoryPage.Rows.picture(profile).getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                            && ((android.graphics.drawable.BitmapDrawable) ViewingHistoryPage.Rows.picture(profile).getDrawable()).getBitmap() == cover);
            assertEquals("only the row with an address was fetched",
                    java.util.Collections.singleton("https://scontent.cdninstagram.com/r.jpg"), new java.util.HashSet<>(asked));
        } finally {
            restore.run();
            ViewingHistoryPage.forgetPictures();
        }
    }

    /**
     * The list binds its rows as it lays out, so a row can have two views loading at once. They
     * share one fetch, and the later one never shows a copy read back from the first one's saved file.
     */
    @Test public void twoViewsOfARowShareOneFetch() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        ViewingHistoryPage.forgetPictures();
        ViewingHistoryRows.add(context, Kind.REEL, "3_3", "amy", "https://scontent.cdninstagram.com/s.jpg", System.currentTimeMillis());
        android.graphics.Bitmap cover = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
        java.util.List<String> asked = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable restore = ViewingHistoryRows.slowFetchAnswering(cover, asked, 500L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            ViewingHistoryPage page = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            android.widget.LinearLayout first = (android.widget.LinearLayout) page.list.getAdapter().getView(0, null, page.list);
            android.widget.LinearLayout second = (android.widget.LinearLayout) page.list.getAdapter().getView(0, null, page.list);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("one fetch for the row", Collections.singletonList("https://scontent.cdninstagram.com/s.jpg"), new java.util.ArrayList<>(asked));
            for (android.widget.LinearLayout view : Arrays.asList(first, second)) {
                android.graphics.drawable.Drawable shown = ViewingHistoryPage.Rows.picture(view).getDrawable();
                assertTrue(String.valueOf(shown), shown instanceof android.graphics.drawable.BitmapDrawable);
                assertTrue("the fetched picture, not a copy", ((android.graphics.drawable.BitmapDrawable) shown).getBitmap() == cover);
            }
        } finally {
            restore.run();
            ViewingHistoryPage.forgetPictures();
        }
    }

    /**
     * #773: the button by Clear shows the rows as a grid of big pictures, three across, and back.
     * The grid keeps the tabs, the search, a tap and a long press, and the page opens the way it
     * was left, a choice that stays out of backups.
     */
    @Test public void theGridShowsBigPicturesAndThePageOpensTheWayItWasLeft() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        ViewingHistoryPage.forgetPictures();
        ViewingHistoryRows.add(context, Kind.PROFILE, "zed", "zed", now - 3_000L);
        ViewingHistoryRows.add(context, Kind.REEL, "2_2", "amy", "https://scontent.cdninstagram.com/g.jpg", now - 2_000L);
        ViewingHistoryRows.add(context, Kind.POST, "1949525278281554174_2", "amy", now - 1_000L);
        android.graphics.Bitmap cover = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
        java.util.Set<String> asked = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        Runnable restore = ViewingHistoryRows.fetchAnswering(cover, asked);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        assertFalse("the page's own choice, not a setting to back up",
                ConfigurationBackup.eligible().containsKey(Settings.VIEWING_HISTORY_GRID.key));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            assertNull("not a row of the settings", settings.findPreference(Settings.VIEWING_HISTORY_GRID.key));
            Preference row = settings.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            ViewingHistoryPage page = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("the list to begin with", android.view.View.VISIBLE, page.list.getVisibility());
            assertEquals(android.view.View.GONE, page.grid.getVisibility());
            assertEquals("Show as grid", String.valueOf(page.layout.getContentDescription()));

            page.layout.performClick();
            assertTrue(Settings.VIEWING_HISTORY_GRID.savedValue());
            assertEquals(android.view.View.GONE, page.list.getVisibility());
            assertEquals(android.view.View.VISIBLE, page.grid.getVisibility());
            assertEquals(android.view.View.GONE, page.empty.getVisibility());
            assertEquals("Show as list", String.valueOf(page.layout.getContentDescription()));
            // The grid counts its columns when it's measured.
            page.grid.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY));
            assertEquals(ViewingHistoryPage.GRID_COLUMNS, page.grid.getNumColumns());
            assertEquals(3, page.grid.getAdapter().getCount());

            android.widget.FrameLayout reel = (android.widget.FrameLayout) page.grid.getAdapter().getView(1, null, page.grid);
            reel.measure(android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
            assertEquals("a square tile", 300, reel.getMeasuredHeight());
            assertEquals("@amy", String.valueOf(ViewingHistoryPage.Tiles.caption(reel).getText()));
            String said = String.valueOf(reel.getContentDescription());
            assertTrue(said, said.startsWith("@amy, Reel, "));
            assertEquals("the tile says it all", android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                    ViewingHistoryPage.Tiles.caption(reel).getImportantForAccessibility());
            assertNotNull("its kind in the corner", ViewingHistoryPage.Tiles.badge(reel).getDrawable());
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            android.graphics.drawable.Drawable shown = ViewingHistoryPage.Tiles.picture(reel).getDrawable();
            assertTrue(String.valueOf(shown), shown instanceof android.graphics.drawable.BitmapDrawable
                    && ((android.graphics.drawable.BitmapDrawable) shown).getBitmap() == cover);
            assertEquals(Collections.singleton("https://scontent.cdninstagram.com/g.jpg"), new java.util.HashSet<>(asked));

            page.tabs[2].performClick();
            assertEquals("the tabs sort the grid too", 1, page.grid.getAdapter().getCount());
            page.tabs[0].performClick();
            page.search.setText("zed");
            assertEquals("and the search", 1, page.grid.getAdapter().getCount());
            page.search.setText("nobody");
            assertEquals(android.view.View.GONE, page.grid.getVisibility());
            assertEquals(android.view.View.VISIBLE, page.empty.getVisibility());
            assertEquals("Nothing matches.", String.valueOf(page.empty.getText()));
            page.search.setText("");
            assertEquals(android.view.View.VISIBLE, page.grid.getVisibility());

            assertTrue(page.grid.getOnItemLongClickListener().onItemLongClick(page.grid, reel, 1, 1));
            android.widget.PopupMenu popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();
            assertNotNull("a long press opens the list's menu", popup);
            assertEquals(Arrays.asList("Show all from this account", "Open", "Copy link", "Open profile", "Copy username",
                    "Remove from history", "Remove all from this account"), titles(popup.getMenu()));
            popup.dismiss();
            page.dialog.dismiss();

            ViewingHistoryPage again = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("it opens the way it was left", android.view.View.VISIBLE, again.grid.getVisibility());
            assertEquals(android.view.View.GONE, again.list.getVisibility());
            again.layout.performClick();
            assertFalse(Settings.VIEWING_HISTORY_GRID.savedValue());
            assertEquals(android.view.View.VISIBLE, again.list.getVisibility());
            assertEquals(android.view.View.GONE, again.grid.getVisibility());
            assertEquals("Show as grid", String.valueOf(again.layout.getContentDescription()));

            again.layout.performClick();
            again.grid.performItemClick(null, 0, 0);
            android.content.Intent opened = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals("a tap opens it, as a row's does", "https://www.instagram.com/p/BsOGulcndj-/", String.valueOf(opened.getData()));
        } finally {
            restore.run();
            ViewingHistoryPage.forgetPictures();
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

    /** #228: a long press on a row offers its link to copy, its poster's profile, and taking it off the list. */
    @Test public void aLongPressCopiesTheLinkOpensTheProfileOrTakesTheRowOff() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        ViewingHistoryRows.add(context, Kind.PROFILE, "zed", "zed", now - 3_000L);
        ViewingHistoryRows.add(context, Kind.STORY, "3_3", "bob", now - 2_000L);
        ViewingHistoryRows.add(context, Kind.POST, "1949525278281554174_2", "amy", now - 1_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            ViewingHistoryPage page = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals(3, page.list.getAdapter().getCount());
            ViewingHistory.Row post = (ViewingHistory.Row) page.list.getAdapter().getItem(0);
            assertEquals("1949525278281554174_2", post.id);

            assertTrue(page.list.getOnItemLongClickListener().onItemLongClick(page.list, page.list, 0, 0));
            android.widget.PopupMenu popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();
            assertNotNull("a long press opens the menu", popup);
            android.view.Menu menu = popup.getMenu();
            assertEquals(Arrays.asList("Show all from this account", "Open", "Copy link", "Open profile", "Copy username",
                    "Remove from history", "Remove all from this account"), titles(menu));
            popup.dismiss();

            android.view.Menu story = new android.widget.PopupMenu(context, page.list).getMenu();
            ViewingHistoryPage.fill(story, ViewingHistoryRows.row(Kind.STORY, "3_3", "bob"));
            assertEquals("a story's tap already goes to its person", Arrays.asList("Show all from this account", "Open",
                    "Copy link", "Copy username", "Remove from history", "Remove all from this account"), titles(story));
            android.view.Menu nameless = new android.widget.PopupMenu(context, page.list).getMenu();
            ViewingHistoryPage.fill(nameless, ViewingHistoryRows.row(Kind.STORY, "4_4", ""));
            assertEquals("nothing to link to, so only the removal", Collections.singletonList("Remove from history"), titles(nameless));

            assertTrue(page.pick(ViewingHistoryPage.COPY_LINK, post));
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals("https://www.instagram.com/p/BsOGulcndj-/",
                    String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
            assertEquals("Copied", ShadowToast.getTextOfLatestToast());

            assertTrue(page.pick(ViewingHistoryPage.REMOVE, post));
            assertEquals("off the page at once", 2, page.list.getAdapter().getCount());
            Utils.awaitBackgroundTasksForTests();
            ViewingHistory.forgetForTests();
            assertEquals("and off the list kept on the phone", 2, ViewingHistory.count(context));
            assertFalse(ViewingHistory.remove(context, Kind.POST, post.id));

            assertTrue(page.pick(ViewingHistoryPage.OPEN_PROFILE, post));
            android.content.Intent opened = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals("https://www.instagram.com/amy/", String.valueOf(opened.getData()));
            assertEquals(context.getPackageName(), opened.getPackage());
        }
    }

    /**
     * #228's longer menu: everything kept from a row's account, opening it, copying the account's
     * username, and taking everything from that account off the list, its profile included, while
     * an account whose name only starts the same stays.
     */
    @Test public void aLongPressShowsCopiesOpensAndRemovesEverythingFromAnAccount() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        ViewingHistory.clear(context);
        ViewingHistoryRows.add(context, Kind.PROFILE, "amy", "amy", now - 5_000L);
        ViewingHistoryRows.add(context, Kind.POST, "5_5", "amyx", now - 4_000L);
        ViewingHistoryRows.add(context, Kind.STORY, "3_3", "bob", now - 3_000L);
        ViewingHistoryRows.add(context, Kind.REEL, "2_2", "amy", now - 2_000L);
        ViewingHistoryRows.add(context, Kind.POST, "1949525278281554174_2", "amy", now - 1_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.VIEWING_HISTORY_ROW);
            ViewingHistoryPage page = ViewingHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals(5, page.list.getAdapter().getCount());
            ViewingHistory.Row post = (ViewingHistory.Row) page.list.getAdapter().getItem(0);
            ViewingHistory.Row story = (ViewingHistory.Row) page.list.getAdapter().getItem(2);
            assertEquals("bob", story.owner);

            page.tabs[1].performClick();
            assertTrue(page.pick(ViewingHistoryPage.SHOW_ACCOUNT, story));
            assertTrue("every kind again", page.tabs[0].isSelected());
            assertEquals("@bob", String.valueOf(page.search.getText()));
            assertEquals(1, page.list.getAdapter().getCount());
            page.search.setText("");

            assertTrue(page.pick(ViewingHistoryPage.COPY_USERNAME, post));
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals("amy", String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
            assertEquals("Copied", ShadowToast.getTextOfLatestToast());

            assertTrue(page.pick(ViewingHistoryPage.REMOVE_ACCOUNT, post));
            assertEquals("amy's post, reel and profile off the page at once", 2, page.list.getAdapter().getCount());
            String said = ShadowToast.getTextOfLatestToast();
            assertTrue(said, said.startsWith("Removed ") && said.contains("3") && said.endsWith(" from history"));
            Utils.awaitBackgroundTasksForTests();
            ViewingHistory.forgetForTests();
            assertEquals("and off the list kept on the phone", 2, ViewingHistory.count(context));
            for (ViewingHistory.Row left : ViewingHistory.entries(context)) {
                assertFalse(left.id, ViewingHistory.of(left, "AMY"));
            }
            assertEquals("nothing of theirs is left", 0, ViewingHistory.removeAll(context, "amy"));
            assertEquals("no name, nothing goes", 0, ViewingHistory.removeAll(context, ""));

            assertTrue(page.pick(ViewingHistoryPage.OPEN, story));
            android.content.Intent opened = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals("a story opens its person, as a tap does", "https://www.instagram.com/bob/", String.valueOf(opened.getData()));
            assertEquals(context.getPackageName(), opened.getPackage());
        }
    }

    private static java.util.List<String> titles(android.view.Menu menu) {
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (int at = 0; at < menu.size(); at++) titles.add(String.valueOf(menu.getItem(at).getTitle()));
        return titles;
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
