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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.download.ProfileHistory;
import app.hushgram.extension.instagram.download.ProfileHistory.Field;
import app.hushgram.extension.instagram.download.ProfileHistoryRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Keep profile history (#590, #611): a switch, the list of changes and Clear profile history, in the
 * Profiles category with the profile picture patch in the build. The switch starts off and goes in a
 * settings file, the list never does. Its page searches the changes and opens the profile tapped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ProfileHistorySettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.PROFILE_HISTORY.resetToDefault();
        ProfileHistory.clear(RuntimeEnvironment.getApplication());
        ProfileHistoryRows.forget();
        ProfileHistoryPage.forgetPictures();
    }

    @Test public void theSwitchStartsOffBelongsToTheProfilePicturePatchAndIsBackedUp() {
        assertFalse(Settings.PROFILE_HISTORY.get());
        assertTrue(PatchFamily.PROFILE_PICTURE.switches.contains(Settings.PROFILE_HISTORY));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PROFILE_HISTORY.key));
    }

    @Test public void theRowsAreInProfilesWithTheProfilePicturePatchAndGoneWithoutIt() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference toggle = page.findPreference(Settings.PROFILE_HISTORY.key);
            assertTrue(toggle instanceof SwitchPreference);
            assertEquals("Keep profile history", String.valueOf(toggle.getTitle()));
            assertFalse(((SwitchPreference) toggle).isChecked());
            Preference list = page.findPreference(HushgramPreferenceFragment.PROFILE_HISTORY_ROW);
            assertNotNull(list);
            assertEquals("Profile changes", String.valueOf(list.getTitle()));
            assertTrue("it opens the page", list.isSelectable());
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_PROFILE_HISTORY);
            assertNotNull(clear);
            assertEquals("Clear profile history", String.valueOf(clear.getTitle()));
            assertTrue(((HushgramPreferenceFragment.Row) clear).actsOnTap());
            for (Preference row : Arrays.asList(toggle, list, clear)) {
                assertEquals(String.valueOf(row.getTitle()), "Profiles",
                        String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
            }
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VOICE_MESSAGE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            assertNull(page.findPreference(Settings.PROFILE_HISTORY.key));
            assertNull(page.findPreference(HushgramPreferenceFragment.PROFILE_HISTORY_ROW));
            assertNull(page.findPreference(HushgramPreferenceFragment.CLEAR_PROFILE_HISTORY));
        }
    }

    @Test public void theRowReadsTheNewestChangesFirstAndNothingWhenEmpty() {
        long now = 10_000_000L;
        assertEquals("Nothing here yet. With Keep profile history on, the old usernames, bios and pictures "
                        + "of profiles you open show up here.",
                HushgramPreferenceFragment.profileHistorySummary(Collections.emptyList(), now));
        Context context = RuntimeEnvironment.getApplication();
        ProfileHistoryRows.visit(context, "1", "ann", null, "", now - 9 * 60_000L);
        ProfileHistoryRows.visit(context, "1", "ann.new", null, "", now - 4 * 60_000L);
        ProfileHistoryRows.visit(context, "2", "bo", "old bio", "", now - 9 * 60_000L);
        ProfileHistoryRows.visit(context, "2", "bo", "new bio", "", now - 3 * 60_000L);
        ProfileHistoryRows.visit(context, "3", "cy", null, "", now - 9 * 60_000L);
        ProfileHistoryRows.visit(context, "3", "cy2", null, "", now - 2 * 60_000L);
        ProfileHistoryRows.visit(context, "4", "dee", null, "", now - 9 * 60_000L);
        ProfileHistoryRows.visit(context, "4", "dee2", null, "", now - 60_000L);
        String[] lines = HushgramPreferenceFragment.profileHistorySummary(ProfileHistory.past(context), now).split("\n");
        assertEquals("the newest three, then how many in all", 4, lines.length);
        assertTrue(lines[0], lines[0].startsWith("@dee2: Was @dee, "));
        assertTrue(lines[1], lines[1].startsWith("@cy2: Was @cy, "));
        assertTrue(lines[2], lines[2].startsWith("@bo: Old bio, "));
        assertEquals("Tap to see all 4, or search them.", lines[3]);
    }

    /** #611's page: a search box over every change, and a tap opens the profile by the username it has now. */
    @Test public void thePageSearchesAndOpensTheProfileTapped() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.PROFILE_HISTORY_ROW);
            ProfileHistoryPage none = ProfileHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue(String.valueOf(none.empty.getText()), String.valueOf(none.empty.getText()).startsWith("Nothing here yet."));
            assertNotNull("an empty page shows a mark over its words", none.empty.getCompoundDrawablesRelative()[1]);
            none.dialog.dismiss();

            ProfileHistoryRows.visit(context, "1", "amy", "loves cats", "", now - 5_000L);
            ProfileHistoryRows.visit(context, "1", "amy", "loves dogs", "", now - 4_000L);
            ProfileHistoryRows.visit(context, "2", "zed", null, "", now - 3_000L);
            ProfileHistoryRows.visit(context, "2", "zed.real", null, "", now - 2_000L);
            ProfileHistoryPage page = ProfileHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue(page.dialog.isShowing());
            assertEquals("the keyboard pushes the list up rather than covering it",
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    page.dialog.getWindow().getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            // Dim keeps the density of whichever test loaded it first, so this reads Dim too.
            assertEquals("where the settings search sits", app.hushgram.extension.shared.ui.Dim.dp(18),
                    ((android.view.ViewGroup.MarginLayoutParams) page.search.getLayoutParams()).getMarginStart());
            assertEquals(2, page.list.getAdapter().getCount());
            assertEquals("the newest change first", "zed.real", ((ProfileHistory.Past) page.list.getAdapter().getItem(0)).username);

            android.widget.LinearLayout shown = (android.widget.LinearLayout) page.list.getAdapter().getView(1, null, page.list);
            android.widget.LinearLayout words = (android.widget.LinearLayout) shown.getChildAt(1);
            assertEquals("@amy", String.valueOf(((android.widget.TextView) words.getChildAt(0)).getText()));
            String what = String.valueOf(((android.widget.TextView) words.getChildAt(1)).getText());
            assertTrue(what, what.startsWith("Old bio, ") && what.endsWith("\nloves cats"));

            page.search.setText("@AMY");
            assertEquals("a search goes by the username, in any case, with or without the at", 1, page.list.getAdapter().getCount());
            page.search.setText("zed");
            assertEquals("the old username matches too", 1, page.list.getAdapter().getCount());
            page.search.setText("cats");
            assertEquals("and the old bio's words", 1, page.list.getAdapter().getCount());
            page.search.setText("nobody");
            assertEquals(0, page.list.getAdapter().getCount());
            assertEquals("Nothing matches.", String.valueOf(page.empty.getText()));
            assertNotNull(page.empty.getCompoundDrawablesRelative()[1]);
            page.search.setText("");

            android.app.FragmentManager fragments = controller.get().getFragmentManager();
            new android.app.DialogFragment().show(fragments, SettingsEntry.DIALOG_TAG);
            fragments.executePendingTransactions();
            page.list.performItemClick(null, 0, 0);
            android.content.Intent opened = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals("the username it has now, since the old one may be someone else's",
                    "https://www.instagram.com/zed.real/", String.valueOf(opened.getData()));
            assertEquals("its own package, so a copy next to Instagram opens itself", context.getPackageName(), opened.getPackage());
            assertFalse(page.dialog.isShowing());
            fragments.executePendingTransactions();
            assertNull("the settings make way for the profile", fragments.findFragmentByTag(SettingsEntry.DIALOG_TAG));
        }
    }

    /** A picture's row shows the copy kept of it, read off the main thread, and the other rows a person. */
    @Test public void aPictureRowShowsTheCopyKeptOfIt() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        Runnable restore = ProfileHistoryRows.fetchAnswering(
                android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            ProfileHistoryRows.visit(context, "1", "amy", null, "https://cdn.example/v/one_n.jpg", now - 3_000L);
            ProfileHistoryRows.visit(context, "1", "amy", null, "https://cdn.example/v/two_n.jpg", now - 2_000L);
            ProfileHistoryRows.visit(context, "2", "zed", null, "", now - 5_000L);
            ProfileHistoryRows.visit(context, "2", "zed2", null, "", now - 1_000L);
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.PROFILE_HISTORY_ROW);
            ProfileHistoryPage page = ProfileHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            android.widget.LinearLayout username = (android.widget.LinearLayout) page.list.getAdapter().getView(0, null, page.list);
            android.widget.LinearLayout picture = (android.widget.LinearLayout) page.list.getAdapter().getView(1, null, page.list);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals(Field.PICTURE, ((ProfileHistory.Past) page.list.getAdapter().getItem(1)).entry.field);
            // The copy fills its circle edge to edge. The person drawn for a username sits padded inside it.
            android.widget.ImageView kept = (android.widget.ImageView) picture.getChildAt(0);
            android.widget.ImageView person = (android.widget.ImageView) username.getChildAt(0);
            assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP, kept.getScaleType());
            assertEquals(0, kept.getPaddingLeft());
            assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, person.getScaleType());
            assertTrue(person.getPaddingLeft() > 0);
            assertEquals(ProfileHistoryPage.PICTURE_DP * context.getResources().getDisplayMetrics().density,
                    picture.getChildAt(0).getLayoutParams().width, 1f);
        } finally {
            restore.run();
        }
    }

    /** A long press copies the old username or the old bio. A picture has nothing to copy. */
    @Test public void aLongPressCopiesTheOldUsernameOrBio() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        Runnable restore = ProfileHistoryRows.fetchAnswering(null);
        try {
            ProfileHistoryRows.visit(context, "1", "amy", "first bio", "https://cdn.example/a_n.jpg", now - 3_000L);
            ProfileHistoryRows.visit(context, "1", "amy.b", "second bio", "https://cdn.example/b_n.jpg", now - 2_000L);
        } finally {
            restore.run();
        }
        List<ProfileHistory.Past> past = ProfileHistory.past(context);
        assertEquals(3, past.size());
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<String> offered = new ArrayList<>();
            for (ProfileHistory.Past change : past) {
                android.view.Menu menu = new android.widget.PopupMenu(context, new android.view.View(context)).getMenu();
                ProfileHistoryPage.fill(menu, change);
                offered.add(change.entry.field + "=" + (menu.size() == 0 ? "" : String.valueOf(menu.getItem(0).getTitle())));
            }
            Collections.sort(offered);
            assertEquals(Arrays.asList("BIO=Copy bio", "PICTURE=", "USERNAME=Copy username"), offered);

            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            ProfileHistoryPage page = ProfileHistoryPage.open(controller.get(),
                    settings.findPreference(HushgramPreferenceFragment.PROFILE_HISTORY_ROW));
            ProfileHistory.Past username = null;
            for (ProfileHistory.Past change : past) if (change.entry.field == Field.USERNAME) username = change;
            assertTrue(page.pick(ProfileHistoryPage.COPY_USERNAME, username));
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals("amy", String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
            assertEquals("Copied", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void clearEmptiesTheListAtOnceAndSaysSoAndTheExportNeverHoldsIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        ProfileHistoryRows.visit(context, "1", "someone", "a secret bio", "", now - 2_000L);
        ProfileHistoryRows.visit(context, "1", "someone.else", "a secret bio", "", now - 1_000L);
        assertEquals(1, ProfileHistory.count(context));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        Settings.PROFILE_HISTORY.save(true);
        String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);
        assertTrue(exported.contains(Settings.PROFILE_HISTORY.key));
        assertFalse("the profile history went into a settings file", exported.contains("someone"));
        assertFalse("the profile history went into a settings file", exported.contains("a secret bio"));

        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            Preference list = page.findPreference(HushgramPreferenceFragment.PROFILE_HISTORY_ROW);
            assertTrue(String.valueOf(list.getSummary()), String.valueOf(list.getSummary()).startsWith("@someone.else: Was @someone, "));
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_PROFILE_HISTORY);
            assertTrue(clear.getOnPreferenceClickListener().onPreferenceClick(clear));
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Profile history cleared.", ShadowToast.getTextOfLatestToast());
            assertEquals(0, ProfileHistory.count(context));
            assertTrue(String.valueOf(list.getSummary()).startsWith("Nothing here yet."));
            assertTrue("the switch stays as it is", Settings.PROFILE_HISTORY.get());
        }
        ProfileHistoryRows.forget();
        assertEquals("and the file went with it", 0, ProfileHistory.count(context));
    }
}
