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
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import app.hushgram.extension.instagram.download.SavedProfiles;
import app.hushgram.extension.instagram.download.SavedProfilesRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Saved profiles (#629): a switch and the list, in the Profiles category with the profile picture
 * patch in the build. The switch starts off and goes in a settings file, the list never does. Its
 * page searches the accounts, opens the one tapped, and takes one off at once with a cross or a long
 * press.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SavedProfilesSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SAVED_PROFILES.resetToDefault();
        SavedProfilesRows.clear(RuntimeEnvironment.getApplication());
        SavedProfilesRows.forget();
        SavedProfilesPage.forgetPictures();
    }

    private static void idle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    @Test public void theSwitchStartsOffBelongsToTheProfilePicturePatchAndIsBackedUp() {
        assertFalse(Settings.SAVED_PROFILES.get());
        assertTrue(PatchFamily.PROFILE_PICTURE.switches.contains(Settings.SAVED_PROFILES));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SAVED_PROFILES.key));
    }

    @Test public void theRowsAreInProfilesWithTheProfilePicturePatchAndGoneWithoutIt() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference toggle = page.findPreference(Settings.SAVED_PROFILES.key);
            assertTrue(toggle instanceof SwitchPreference);
            assertEquals("Saved profiles", String.valueOf(toggle.getTitle()));
            assertFalse(((SwitchPreference) toggle).isChecked());
            Preference list = page.findPreference(HushgramPreferenceFragment.SAVED_PROFILES_ROW);
            assertNotNull(list);
            assertEquals("Your saved profiles", String.valueOf(list.getTitle()));
            assertTrue("it opens the page", list.isSelectable());
            for (Preference row : Arrays.asList(toggle, list)) {
                assertEquals(String.valueOf(row.getTitle()), "Profiles",
                        String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
            }
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VOICE_MESSAGE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            assertNull(page.findPreference(Settings.SAVED_PROFILES.key));
            assertNull(page.findPreference(HushgramPreferenceFragment.SAVED_PROFILES_ROW));
        }
    }

    @Test public void theRowNamesTheNewestThreeAndSaysHowToSaveWhenEmpty() {
        assertEquals("Nothing saved yet. With Saved profiles on, tap Save profile in the menu on someone's profile "
                        + "and it shows up here.",
                HushgramPreferenceFragment.savedProfilesSummary(Collections.emptyList()));
        Context context = RuntimeEnvironment.getApplication();
        SavedProfilesRows.save(context, "1", "ann", "", "", 1_000L);
        SavedProfilesRows.save(context, "2", "bo", "", "", 2_000L);
        SavedProfilesRows.save(context, "3", "cy", "", "", 3_000L);
        SavedProfilesRows.save(context, "4", "dee", "", "", 4_000L);
        assertEquals("@dee\n@cy\n@bo\nTap to see all 4, or search them.",
                HushgramPreferenceFragment.savedProfilesSummary(SavedProfiles.all(context)));
        assertEquals("@ann\nTap to see all 1, or search them.",
                HushgramPreferenceFragment.savedProfilesSummary(SavedProfiles.all(context).subList(3, 4)));
    }

    /** The page: a search box over every saved account, and a tap opens that profile. */
    @Test public void thePageSearchesAndOpensTheProfileTapped() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.SAVED_PROFILES_ROW);
            SavedProfilesPage none = SavedProfilesPage.open(controller.get(), row);
            idle();
            assertTrue(String.valueOf(none.empty.getText()), String.valueOf(none.empty.getText()).startsWith("Nothing saved yet."));
            assertNotNull("an empty page shows a mark over its words", none.empty.getCompoundDrawablesRelative()[1]);
            none.dialog.dismiss();

            SavedProfilesRows.save(context, "1", "amy", "Amy Pond", "", 1_000L);
            SavedProfilesRows.save(context, "2", "zed", "", "", 2_000L);
            SavedProfilesPage page = SavedProfilesPage.open(controller.get(), row);
            idle();
            assertTrue(page.dialog.isShowing());
            assertEquals("the keyboard pushes the list up rather than covering it",
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    page.dialog.getWindow().getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals(2, page.list.getAdapter().getCount());
            assertEquals("the newest first", "zed", ((SavedProfiles.Profile) page.list.getAdapter().getItem(0)).username);

            LinearLayout zed = (LinearLayout) page.list.getAdapter().getView(0, null, page.list);
            LinearLayout amy = (LinearLayout) page.list.getAdapter().getView(1, null, page.list);
            LinearLayout words = (LinearLayout) amy.getChildAt(1);
            assertEquals("@amy", String.valueOf(((TextView) words.getChildAt(0)).getText()));
            assertEquals("Amy Pond", String.valueOf(((TextView) words.getChildAt(1)).getText()));
            assertEquals(View.VISIBLE, words.getChildAt(1).getVisibility());
            assertEquals("no name, no second line", View.GONE, ((LinearLayout) zed.getChildAt(1)).getChildAt(1).getVisibility());
            assertEquals(SavedProfilesPage.PICTURE_DP * context.getResources().getDisplayMetrics().density,
                    amy.getChildAt(0).getLayoutParams().width, 1f);
            ImageButton cross = (ImageButton) amy.getChildAt(2);
            assertEquals("Remove @amy from saved", String.valueOf(cross.getContentDescription()));
            assertFalse("a tap on the rest of the row still reaches the list", cross.isFocusable());

            page.search.setText("@AMY");
            assertEquals("a search goes by the username, in any case, with or without the at", 1, page.list.getAdapter().getCount());
            page.search.setText("pond");
            assertEquals("and by the name", 1, page.list.getAdapter().getCount());
            page.search.setText("nobody");
            assertEquals(0, page.list.getAdapter().getCount());
            assertEquals("Nothing matches.", String.valueOf(page.empty.getText()));
            assertNotNull(page.empty.getCompoundDrawablesRelative()[1]);
            page.search.setText("");

            android.app.FragmentManager fragments = controller.get().getFragmentManager();
            new android.app.DialogFragment().show(fragments, SettingsEntry.DIALOG_TAG);
            fragments.executePendingTransactions();
            page.list.performItemClick(null, 1, 1);
            android.content.Intent opened = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals("https://www.instagram.com/amy/", String.valueOf(opened.getData()));
            assertEquals("its own package, so a copy next to Instagram opens itself", context.getPackageName(), opened.getPackage());
            assertFalse(page.dialog.isShowing());
            fragments.executePendingTransactions();
            assertNull("the settings make way for the profile", fragments.findFragmentByTag(SettingsEntry.DIALOG_TAG));
        }
    }

    /** The cross takes the account off at once, says so and brings the settings row up to date. */
    @Test public void theCrossOrALongPressTakesItOffAtOnceAndSaysSo() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SavedProfilesRows.save(context, "1", "amy", "", "", 1_000L);
        SavedProfilesRows.save(context, "2", "zed", "", "", 2_000L);
        SavedProfilesRows.save(context, "3", "kim", "", "", 3_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            idle();
            Preference row = settings.findPreference(HushgramPreferenceFragment.SAVED_PROFILES_ROW);
            assertEquals("@kim\n@zed\n@amy\nTap to see all 3, or search them.", String.valueOf(row.getSummary()));
            SavedProfilesPage page = SavedProfilesPage.open(controller.get(), row);
            idle();

            LinearLayout zed = (LinearLayout) page.list.getAdapter().getView(1, null, page.list);
            zed.getChildAt(2).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("Removed @zed from saved", ShadowToast.getTextOfLatestToast());
            assertEquals(2, page.list.getAdapter().getCount());
            assertEquals("@kim\n@amy\nTap to see all 2, or search them.", String.valueOf(row.getSummary()));

            android.view.Menu menu = new android.widget.PopupMenu(context, new View(context)).getMenu();
            SavedProfilesPage.fill(menu);
            assertEquals(2, menu.size());
            assertEquals("Copy username", String.valueOf(menu.getItem(0).getTitle()));
            assertEquals("Remove from saved", String.valueOf(menu.getItem(1).getTitle()));

            SavedProfiles.Profile amy = (SavedProfiles.Profile) page.list.getAdapter().getItem(1);
            assertTrue(page.pick(SavedProfilesPage.COPY_USERNAME, amy));
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals("amy", String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
            ShadowLooper.idleMainLooper();
            assertEquals("Copied", ShadowToast.getTextOfLatestToast());

            assertTrue(page.pick(SavedProfilesPage.REMOVE, amy));
            ShadowLooper.idleMainLooper();
            assertEquals("Removed @amy from saved", ShadowToast.getTextOfLatestToast());
            assertEquals(1, page.list.getAdapter().getCount());
            assertEquals("@kim\nTap to see all 1, or search them.", String.valueOf(row.getSummary()));
        }
        Utils.awaitBackgroundTasksForTests();
        SavedProfilesRows.forget();
        assertEquals("and the file has it too", 1, SavedProfiles.all(context).size());
        assertEquals("kim", SavedProfiles.all(context).get(0).username);
    }

    /** A row with a picture shows the copy kept of it, read off the main thread. */
    @Test public void aRowShowsThePictureKeptOfTheAccount() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Runnable restore = SavedProfilesRows.fetchAnswering(
                android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SavedProfilesRows.save(context, "1", "amy", "", "https://cdn.example/v/one_n.jpg", 1_000L);
            SavedProfilesRows.save(context, "2", "zed", "", "", 2_000L);
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            SavedProfilesPage page = SavedProfilesPage.open(controller.get(),
                    settings.findPreference(HushgramPreferenceFragment.SAVED_PROFILES_ROW));
            idle();
            LinearLayout none = (LinearLayout) page.list.getAdapter().getView(0, null, page.list);
            LinearLayout picture = (LinearLayout) page.list.getAdapter().getView(1, null, page.list);
            idle();
            ImageView kept = (ImageView) picture.getChildAt(0);
            ImageView person = (ImageView) none.getChildAt(0);
            assertEquals(ImageView.ScaleType.CENTER_CROP, kept.getScaleType());
            assertEquals(0, kept.getPaddingLeft());
            assertEquals(ImageView.ScaleType.FIT_CENTER, person.getScaleType());
            assertTrue(person.getPaddingLeft() > 0);
        } finally {
            restore.run();
        }
    }

    @Test public void theExportNeverHoldsTheList() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SavedProfilesRows.save(context, "1", "someone.secret", "A Secret Name", "", 1_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        Settings.SAVED_PROFILES.save(true);
        String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);
        assertTrue(exported.contains(Settings.SAVED_PROFILES.key));
        assertFalse("the saved profiles went into a settings file", exported.contains("someone.secret"));
        assertFalse("the saved profiles went into a settings file", exported.contains("A Secret Name"));
    }
}
