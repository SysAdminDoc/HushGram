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
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.download.SavedProfiles;
import app.hushgram.extension.instagram.download.SavedProfilesRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Saved profiles (#629): a switch and the list, in the Profiles category with the profile picture
 * patch in the build. The switch starts off, and a settings file carries it and the list, which an
 * import adds back. Its page searches the accounts, opens the one tapped, and takes one off at once
 * with a cross or a long press.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SavedProfilesSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ConfigurationBackup.forgetUndo();
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
            assertEquals(3, menu.size());
            assertEquals("Copy username", String.valueOf(menu.getItem(0).getTitle()));
            assertEquals("Move to folder", String.valueOf(menu.getItem(1).getTitle()));
            assertEquals("Remove from saved", String.valueOf(menu.getItem(2).getTitle()));

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

    /**
     * Folders (#629): Move to folder on a long press, a chip for each folder under the search box that
     * shows just its accounts, and a chip's own menu that renames the folder or takes it away.
     */
    @Test public void foldersSortThePageAndAChipRenamesOrRemovesOne() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SavedProfilesRows.save(context, "1", "amy", "", "", 1_000L);
        SavedProfilesRows.save(context, "2", "zed", "", "", 2_000L);
        SavedProfilesRows.save(context, "3", "kim", "", "", 3_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            idle();
            Preference row = settings.findPreference(HushgramPreferenceFragment.SAVED_PROFILES_ROW);
            SavedProfilesPage page = SavedProfilesPage.open(controller.get(), row);
            idle();
            assertEquals("no folders, no chips", View.GONE, page.chips.getVisibility());

            SavedProfiles.Profile amy = (SavedProfiles.Profile) page.list.getAdapter().getItem(2);
            assertTrue(page.pick(SavedProfilesPage.MOVE, amy));
            ShadowLooper.idleMainLooper();
            AlertDialog choose = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(Arrays.asList("No folder", "New folder"), items(choose));
            assertEquals("where amy is now is marked", 0, choose.getListView().getCheckedItemPosition());
            choose.getListView().performItemClick(null, 1, 1);
            ShadowLooper.idleMainLooper();
            AlertDialog name = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    name.getWindow().getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            SavedProfilesRows.field(name.getWindow().getDecorView()).setText("  Close   friends ");
            name.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("Moved @amy to Close friends", ShadowToast.getTextOfLatestToast());
            assertEquals(View.VISIBLE, page.chips.getVisibility());
            assertEquals(Arrays.asList("All", "Close friends"), chips(page));
            assertTrue("All is shown", page.chipRow.getChildAt(0).isSelected());

            page.move((SavedProfiles.Profile) page.list.getAdapter().getItem(1), "close FRIENDS");
            ShadowLooper.idleMainLooper();
            assertEquals("a name in another case is that folder", "Moved @zed to Close friends",
                    ShadowToast.getTextOfLatestToast());
            page.chipRow.getChildAt(1).performClick();
            assertTrue(page.chipRow.getChildAt(1).isSelected());
            assertFalse(page.chipRow.getChildAt(0).isSelected());
            assertEquals("the folder's accounts, newest first", Arrays.asList("zed", "amy"), shown(page));
            page.search.setText("amy");
            assertEquals("a search stays in the folder", Arrays.asList("amy"), shown(page));
            page.search.setText("kim");
            assertTrue(shown(page).isEmpty());
            page.search.setText("");

            android.view.Menu menu = new android.widget.PopupMenu(context, new View(context)).getMenu();
            SavedProfilesPage.fillFolder(menu);
            assertEquals(2, menu.size());
            assertEquals("Rename folder", String.valueOf(menu.getItem(0).getTitle()));
            assertEquals("Remove folder", String.valueOf(menu.getItem(1).getTitle()));
            assertTrue(page.pickForFolder(SavedProfilesPage.RENAME_FOLDER, "Close friends"));
            ShadowLooper.idleMainLooper();
            AlertDialog rename = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText field = SavedProfilesRows.field(rename.getWindow().getDecorView());
            assertEquals("Close friends", String.valueOf(field.getText()));
            field.setText("Besties");
            rename.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("Renamed the folder to Besties", ShadowToast.getTextOfLatestToast());
            assertEquals(Arrays.asList("All", "Besties"), chips(page));
            assertTrue("the renamed folder is still the one shown", page.chipRow.getChildAt(1).isSelected());
            assertEquals(Arrays.asList("zed", "amy"), shown(page));

            amy = (SavedProfiles.Profile) page.list.getAdapter().getItem(1);
            page.pick(SavedProfilesPage.MOVE, amy);
            ShadowLooper.idleMainLooper();
            choose = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(Arrays.asList("No folder", "Besties", "New folder"), items(choose));
            assertEquals(1, choose.getListView().getCheckedItemPosition());
            choose.getListView().performItemClick(null, 0, 0);
            ShadowLooper.idleMainLooper();
            assertEquals("Moved @amy out of its folder", ShadowToast.getTextOfLatestToast());
            assertEquals(Arrays.asList("zed"), shown(page));

            assertTrue(page.pickForFolder(SavedProfilesPage.REMOVE_FOLDER, "Besties"));
            ShadowLooper.idleMainLooper();
            assertEquals("Removed the folder Besties", ShadowToast.getTextOfLatestToast());
            assertEquals("no folders left, no chips", View.GONE, page.chips.getVisibility());
            assertEquals("and every account shows again", Arrays.asList("kim", "zed", "amy"), shown(page));
        }
        Utils.awaitBackgroundTasksForTests();
        SavedProfilesRows.forget();
        assertEquals("the folder went, its accounts stayed", Arrays.asList("kim", "zed", "amy"),
                usernames(SavedProfiles.all(context)));
        assertTrue(SavedProfiles.folders(SavedProfiles.all(context)).isEmpty());
    }

    private static List<String> items(AlertDialog dialog) {
        List<String> items = new ArrayList<>();
        for (int at = 0; at < dialog.getListView().getAdapter().getCount(); at++) {
            items.add(String.valueOf(dialog.getListView().getAdapter().getItem(at)));
        }
        return items;
    }

    private static List<String> chips(SavedProfilesPage page) {
        List<String> labels = new ArrayList<>();
        for (int at = 0; at < page.chipRow.getChildCount(); at++) {
            labels.add(String.valueOf(((TextView) page.chipRow.getChildAt(at)).getText()));
        }
        return labels;
    }

    private static List<String> shown(SavedProfilesPage page) {
        List<String> usernames = new ArrayList<>();
        for (int at = 0; at < page.list.getAdapter().getCount(); at++) {
            usernames.add(((SavedProfiles.Profile) page.list.getAdapter().getItem(at)).username);
        }
        return usernames;
    }

    /** Opened from the menu on a profile, the page has no settings row, and the cross still takes one off. */
    @Test public void fromAProfilesMenuTheCrossStillTakesItOff() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SavedProfilesRows.save(context, "1", "amy", "", "", 1_000L);
        SavedProfilesRows.save(context, "2", "zed", "", "", 2_000L);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SavedProfilesPage page = SavedProfilesPage.open(controller.get(), null);
            idle();
            assertEquals(2, page.list.getAdapter().getCount());
            ((LinearLayout) page.list.getAdapter().getView(0, null, page.list)).getChildAt(2).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("Removed @zed from saved", ShadowToast.getTextOfLatestToast());
            assertEquals(1, page.list.getAdapter().getCount());
        }
        Utils.awaitBackgroundTasksForTests();
        SavedProfilesRows.forget();
        assertEquals(Collections.singletonList("amy"), usernames(SavedProfiles.all(context)));
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

    private static final String PICTURE = "https://scontent.cdninstagram.com/v/t51.2885-19/amy_n.jpg?oe=6800";

    /** The settings file carries the list, and an import puts back the accounts missing, newest first. */
    @Test public void theExportCarriesTheListAndAnImportPutsItBack() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        SavedProfilesRows.save(context, "11", "amy", "Amy Pond", PICTURE, 1_000L);
        SavedProfilesRows.save(context, "", "zed", "", "", 2_000L);
        Settings.SAVED_PROFILES.save(true);
        byte[] exported = ConfigurationBackup.export();
        JSONObject root = new JSONObject(new String(exported, StandardCharsets.UTF_8));
        assertEquals(4, root.length());
        assertTrue(root.getJSONObject("settings").has(Settings.SAVED_PROFILES.key));
        JSONArray listed = root.getJSONArray(ConfigurationBackup.PROFILES);
        assertEquals(2, listed.length());
        assertEquals("newest first", "zed", listed.getJSONObject(0).getString("username"));
        assertFalse("no picture, no address", listed.getJSONObject(0).has("url"));
        JSONObject amy = listed.getJSONObject(1);
        assertEquals("11", amy.getString("id"));
        assertEquals("Amy Pond", amy.getString("name"));
        assertEquals(PICTURE, amy.getString("url"));
        assertEquals(1_000L, amy.getLong("time"));

        // Another phone, with an account of its own saved since.
        Utils.awaitBackgroundTasksForTests();
        SavedProfilesRows.clear(context);
        SavedProfilesRows.save(context, "33", "kim", "", "", 5_000L);
        ConfigurationBackup.Result result = ConfigurationBackup.restore(exported);
        assertEquals(2, result.profiles);
        assertEquals("Imported " + result.applied + " settings. Saved profiles added: 2.",
                HushgramPreferenceFragment.configurationReceipt(false, result));
        List<SavedProfiles.Profile> all = SavedProfiles.all(context);
        assertEquals(Arrays.asList("kim", "zed", "amy"), usernames(all));
        assertEquals(PICTURE, all.get(2).url);
        assertEquals("Amy Pond", all.get(2).name);
        assertEquals(1_000L, all.get(2).time);

        assertTrue("an import of accounts alone still offers Undo", ConfigurationBackup.canUndo());
        assertEquals(2, ConfigurationBackup.undo().profiles);
        assertEquals("Undo takes back what the file added", Collections.singletonList("kim"), usernames(SavedProfiles.all(context)));

        assertEquals(2, ConfigurationBackup.restore(exported).profiles);
        assertEquals("the same file again adds nothing", 0, ConfigurationBackup.restore(exported).profiles);
        Utils.awaitBackgroundTasksForTests();
        SavedProfilesRows.forget();
        assertEquals("and the list's file has them", Arrays.asList("kim", "zed", "amy"), usernames(SavedProfiles.all(context)));
    }

    /** An import picked on the settings page, and its Undo, show on the list's row without reopening it. */
    @Test public void theListsRowFollowsAnImportAndItsUndo() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        SavedProfilesRows.save(context, "1", "kim", "", "", 5_000L);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            idle();
            Preference list = page.findPreference(HushgramPreferenceFragment.SAVED_PROFILES_ROW);
            assertEquals("@kim\nTap to see all 1, or search them.", String.valueOf(list.getSummary()));
            Uri document = Uri.parse("content://test-documents/settings.json");
            Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(document,
                    new ByteArrayInputStream(file(new JSONArray().put(account("2", "amy", "")))));
            Preference pick = page.findPreference("hushgram_import_configuration");
            pick.getOnPreferenceClickListener().onPreferenceClick(pick);
            ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
            Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(document));
            idle();
            idle();
            assertEquals("@kim\n@amy\nTap to see all 2, or search them.", String.valueOf(list.getSummary()));
            Preference undo = page.findPreference("hushgram_undo_configuration");
            assertTrue(undo.isEnabled());
            undo.getOnPreferenceClickListener().onPreferenceClick(undo);
            idle();
            idle();
            assertEquals("@kim\nTap to see all 1, or search them.", String.valueOf(list.getSummary()));
        }
    }

    /** A file with nothing saved keeps the shape a build from before reads, and so does a build without the list. */
    @Test public void nothingSavedOrNoListInTheBuildLeavesTheListOut() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        assertEquals(3, new JSONObject(new String(ConfigurationBackup.export(), StandardCharsets.UTF_8)).length());
        SavedProfilesRows.save(context, "1", "amy", "", "", 1_000L);
        byte[] listed = ConfigurationBackup.export();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VOICE_MESSAGE);
        assertEquals(3, new JSONObject(new String(ConfigurationBackup.export(), StandardCharsets.UTF_8)).length());
        Utils.awaitBackgroundTasksForTests();
        SavedProfilesRows.clear(context);
        assertEquals(0, ConfigurationBackup.restore(listed).profiles);
        assertTrue(SavedProfiles.all(context).isEmpty());
    }

    /**
     * An account the list couldn't have saved is left out and the rest come in. A field this build
     * doesn't know is left alone, and a picture address off Meta's servers is dropped.
     */
    @Test public void anImportLeavesOutAccountsTheListCouldntHaveSaved() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        SavedProfilesRows.save(context, "7", "kept", "", "", 9_000L);
        JSONArray entries = new JSONArray()
                .put(account("1", "good.one_2", "Good One").put("url", PICTURE).put("folder", "later")
                        .put("time", 1_762_800_000_000L))
                .put(account("2", "elsewhere", "").put("url", "https://example.com/a.jpg"))
                .put(account("3", "has space", ""))
                .put(account("4", new String(new char[31]).replace('\0', 'a'), ""))
                .put(account("5-5", "dash", ""))
                .put(account("6", "tab", "A\tB"))
                .put(account("8", "words", "").put("time", "yesterday"))
                .put(account("9", "early", "").put("time", -1))
                .put(account("10", "x", "").put("username", 12))
                .put(account("7", "renamed", ""))
                .put(new JSONObject().put("username", "bare"));
        assertEquals(3, ConfigurationBackup.restore(file(entries)).profiles);
        List<SavedProfiles.Profile> all = SavedProfiles.all(context);
        assertEquals(Arrays.asList("good.one_2", "kept", "elsewhere", "bare"), usernames(all));
        assertEquals(PICTURE, all.get(0).url);
        assertEquals("", all.get(2).url);
        assertEquals("", all.get(3).id);
        assertEquals(0L, all.get(3).time);

        int before = all.size();
        rejected(file(new JSONArray().put(account("20", "fine", "")).put("not.an.account")));
        rejected(("{\"project\":\"HushGram\",\"schema\":1,\"settings\":{},\"profiles\":{}}").getBytes(StandardCharsets.UTF_8));
        rejected(("{\"project\":\"HushGram\",\"schema\":1,\"settings\":{},\"extra\":[]}").getBytes(StandardCharsets.UTF_8));
        rejected(("{\"project\":\"HushGram\",\"schema\":1,\"settings\":{},\"profiles\":[],\"extra\":1}").getBytes(StandardCharsets.UTF_8));
        assertEquals("a refused file adds nobody", before, SavedProfiles.all(context).size());
    }

    /** A full list fits a settings file, without its picture addresses when they'd make it too big. */
    @Test public void aFullListFitsTheFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        String longPicture = PICTURE + "&" + new String(new char[500]).replace('\0', 'x');
        List<SavedProfiles.Profile> many = new ArrayList<>();
        for (int at = 0; at < SavedProfiles.MAX_PROFILES; at++) {
            many.add(SavedProfiles.fromBackup(account(String.valueOf(1_000_000_000L + at), "user_" + at,
                    "Someone Called " + at).put("url", longPicture)));
        }
        assertEquals(SavedProfiles.MAX_PROFILES, SavedProfiles.merge(context, many).size());
        assertTrue("a full list takes no more", SavedProfiles.merge(context,
                Collections.singletonList(SavedProfiles.fromBackup(account("1", "one.more", "")))).isEmpty());
        byte[] exported = ConfigurationBackup.export();
        assertTrue(exported.length + " bytes", exported.length <= ConfigurationBackup.MAX_BYTES);
        JSONArray listed = new JSONObject(new String(exported, StandardCharsets.UTF_8)).getJSONArray(ConfigurationBackup.PROFILES);
        assertEquals(SavedProfiles.MAX_PROFILES, listed.length());
        assertFalse(listed.getJSONObject(0).has("url"));
        Utils.awaitBackgroundTasksForTests();
        SavedProfilesRows.clear(context);
        assertEquals(SavedProfiles.MAX_PROFILES, ConfigurationBackup.restore(exported).profiles);
    }

    private static JSONObject account(String id, String username, String name) throws Exception {
        return new JSONObject().put("id", id).put("username", username).put("name", name).put("time", 1_000L);
    }

    private static byte[] file(JSONArray profiles) throws Exception {
        return new JSONObject().put("project", "HushGram").put("schema", 1).put("settings", new JSONObject())
                .put(ConfigurationBackup.PROFILES, profiles).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void rejected(byte[] file) {
        try {
            ConfigurationBackup.restore(file);
            fail("a file of the wrong shape was taken");
        } catch (IOException expected) {
        }
    }

    private static List<String> usernames(List<SavedProfiles.Profile> profiles) {
        List<String> names = new ArrayList<>();
        for (SavedProfiles.Profile profile : profiles) names.add(profile.username);
        return names;
    }
}
