/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.graphics.drawable.GradientDrawable;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * The Downloads section and the reel switch: there with the reel download in the build and gone
 * without it, each row keeping the one clean value a save would use, and each dialog in
 * Instagram's language, in the screen's colours and above the keyboard.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class DownloadSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        PauseForTests.resume();
        Settings.SAVE_FOLDER.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        Settings.FILENAME_TEMPLATE.resetToDefault();
        Settings.SAVE_FOLDER_PER_ACCOUNT.resetToDefault();
        Settings.SAVE_NAME_BY_POST.resetToDefault();
        Settings.SEND_DOWNLOADS_TO_APP.resetToDefault();
        Settings.OPEN_IN_PLAYER.resetToDefault();
        Settings.POST_DETAILS.resetToDefault();
        Settings.DOWNLOAD_REELS.resetToDefault();
        Settings.DOWNLOAD_REEL_COVER.resetToDefault();
    }

    /** The reel switch sits in Reels, starts on, and says Instagram's menu comes back when it's off. */
    @Test
    public void theReelSwitchIsInReelsWithTheReelDownloadIn() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            PreferenceScreen screen = page.getPreferenceScreen();
            PreferenceGroup reels = section(screen, "Reels");
            assertNotNull("no Reels section with the reel download in", reels);
            Preference row = reels.findPreference(Settings.DOWNLOAD_REELS.key);
            assertTrue("the reel switch isn't in Reels", row instanceof SwitchPreference);
            assertTrue(((SwitchPreference) row).isChecked());
            assertEquals("Download on reels", String.valueOf(row.getTitle()));
            assertEquals("Adds Download to every reel's more menu, saved at your download quality. Off or paused, "
                    + "Instagram's own menu returns.", String.valueOf(row.getSummary()));
            assertNotNull("no Downloads section with the reel download in", section(screen, "Downloads"));

            Preference cover = reels.findPreference(Settings.DOWNLOAD_REEL_COVER.key);
            assertTrue("Download cover isn't in Reels", cover instanceof SwitchPreference);
            assertEquals("Download cover", String.valueOf(cover.getTitle()));
            assertFalse("Download cover starts off", ((SwitchPreference) cover).isChecked());
            assertTrue(cover.isEnabled());
            ((SwitchPreference) row).setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse("Download cover waits for Download on reels", cover.isEnabled());
            ((SwitchPreference) row).setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(cover.isEnabled());
            assertEquals(java.util.Collections.singletonList(Settings.DOWNLOAD_REELS), PatchFamily.REEL_DOWNLOAD.switches);
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DOWNLOAD_REEL_COVER.key));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            assertNull("a reel switch with no reel download in the build", page.findPreference(Settings.DOWNLOAD_REELS.key));
            assertNull("a Downloads section with no download in the build", section(page.getPreferenceScreen(), "Downloads"));
        }
    }

    /**
     * Saves other apps can open is a switch every save reads, so it's under Downloads with the
     * download patch in, right above the quality it keeps within, and starts off.
     */
    @Test
    public void theCompatibleSwitchSitsAboveTheQuality() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int compatible = indexOfKey(rows, Settings.DOWNLOAD_COMPATIBLE.key);
            assertTrue("no row for saves other apps can open", compatible >= 0);
            Preference row = rows.get(compatible);
            assertTrue(row instanceof SwitchPreference);
            assertFalse(((SwitchPreference) row).isChecked());
            assertEquals(indexOfKey(rows, Settings.DOWNLOAD_QUALITY.key) - 1, compatible);
            assertEquals("Save videos other apps can open", String.valueOf(row.getTitle()));
            assertEquals("For WhatsApp, video editors such as CapCut and InShot, or a gallery or player that plays saves "
                    + "without sound. May lower quality.", String.valueOf(row.getSummary()));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals("a compatible-saves row with no download in the build",
                    -1, indexOfKey(rowsOf(pageIn(controller)), Settings.DOWNLOAD_COMPATIBLE.key));
        }
    }

    /**
     * The download quality's row offers every quality, says what the chosen one does, and a pick
     * reaches the setting the way the list's own dialog sends it. It sits above the folder.
     */
    @Test
    public void theQualityRowOffersEveryQualityAndSaysWhatItDoes() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int qualityAt = indexOfKey(rows, Settings.DOWNLOAD_QUALITY.key);
            assertTrue("no quality row with the download in the build",
                    qualityAt >= 0 && rows.get(qualityAt) instanceof HushgramPreferenceFragment.QualityRow);
            HushgramPreferenceFragment.QualityRow quality = (HushgramPreferenceFragment.QualityRow) rows.get(qualityAt);
            assertEquals("the quality row isn't next to the folder", indexOfKey(rows, Settings.SAVE_FOLDER.key) - 1, qualityAt);
            assertEquals("Download quality", String.valueOf(quality.getTitle()));

            List<String> entries = new ArrayList<>();
            for (CharSequence entry : quality.getEntries()) entries.add(String.valueOf(entry));
            assertEquals(Arrays.asList("Best", L10n.isolate("1080p"), L10n.isolate("720p"), L10n.isolate("480p"),
                    L10n.isolate("360p"), "Smallest"), entries);
            List<String> values = new ArrayList<>();
            for (CharSequence value : quality.getEntryValues()) values.add(String.valueOf(value));
            List<String> names = new ArrayList<>();
            for (DownloadQuality each : DownloadQuality.values()) names.add(each.name());
            assertEquals(names, values);

            assertEquals("BEST", quality.getValue());
            assertEquals("Each video saves at the best quality the player streams.", String.valueOf(quality.getSummary()));

            // A pick in the list, the way its dialog sends one.
            quality.setValue("P480");
            ShadowLooper.idleMainLooper();
            assertEquals(DownloadQuality.P480, Settings.DOWNLOAD_QUALITY.savedValue());
            // A cap prefers anything at or under it, so the summary can't promise the nearest
            // quality: under 720p a video with 1080p and 240p saves at 240p.
            assertEquals("Each video saves at " + L10n.isolate("480p") + " or the closest quality below it. A video "
                            + "with nothing that low saves at the closest quality above.",
                    String.valueOf(quality.getSummary()));

            quality.setValue("SMALLEST");
            ShadowLooper.idleMainLooper();
            assertEquals(DownloadQuality.SMALLEST, Settings.DOWNLOAD_QUALITY.savedValue());
            assertEquals("Each video saves at its lowest quality, for the smallest file.",
                    String.valueOf(quality.getSummary()));

            // A value saved behind the row shows on it once the page hears of it.
            Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
            ShadowLooper.idleMainLooper();
            assertEquals("P720", quality.getValue());
            assertEquals(HushgramPreferenceFragment.qualitySummary(DownloadQuality.P720),
                    String.valueOf(quality.getSummary()));
        }
    }

    /**
     * The save folder's row keeps the one clean folder name a save would use, whatever is typed
     * into it, and says where videos and photos go.
     */
    /** Send downloads to another app is under Downloads, above the switch every save reads, and starts off. */
    @Test
    public void sendToAnotherAppSitsAboveTheCompatibleSwitch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int send = indexOfKey(rows, Settings.SEND_DOWNLOADS_TO_APP.key);
            assertEquals(indexOfKey(rows, Settings.DOWNLOAD_COMPATIBLE.key) - 1, send);
            Preference row = rows.get(send);
            assertEquals("Send downloads to another app", String.valueOf(row.getTitle()));
            assertFalse(((SwitchPreference) row).isChecked());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SEND_DOWNLOADS_TO_APP.key));
        }
    }

    /**
     * Open in another player is under Downloads, above Send downloads to another app, with a reel or
     * a feed video download in the build, starts off and goes in a settings file. A build with only
     * story downloads has no menu it joins, so no row.
     */
    @Test
    public void openInAnotherPlayerSitsAboveSendToAnotherApp() {
        for (PatchFamily family : EnumSet.of(PatchFamily.REEL_DOWNLOAD, PatchFamily.VIDEO_DOWNLOAD)) {
            PatchFamily.inBuildForTests = EnumSet.of(family);
            try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
                List<Preference> rows = rowsOf(pageIn(controller));
                int player = indexOfKey(rows, Settings.OPEN_IN_PLAYER.key);
                assertEquals(family.name(), indexOfKey(rows, Settings.SEND_DOWNLOADS_TO_APP.key) - 1, player);
                Preference row = rows.get(player);
                assertEquals("Open in another player", String.valueOf(row.getTitle()));
                assertFalse(((SwitchPreference) row).isChecked());
                assertTrue(ConfigurationBackup.eligible().containsKey(Settings.OPEN_IN_PLAYER.key));
            }
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(pageIn(controller)), Settings.OPEN_IN_PLAYER.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.OPEN_IN_PLAYER.key));
        }
    }

    /**
     * Details in a post's menu is under Downloads right below Download feed photos, with the feed
     * video download in the build, starts off, belongs to that patch and goes in a settings file.
     * A build with only reel downloads has no feed menu for it, so no row.
     */
    @Test
    public void postDetailsSitsUnderDownloadFeedPhotos() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int details = indexOfKey(rows, Settings.POST_DETAILS.key);
            assertEquals(indexOfKey(rows, Settings.DOWNLOAD_PHOTOS.key) + 1, details);
            Preference row = rows.get(details);
            assertEquals("Details in a post's menu", String.valueOf(row.getTitle()));
            assertFalse(((SwitchPreference) row).isChecked());
            assertTrue(PatchFamily.VIDEO_DOWNLOAD.switches.contains(Settings.POST_DETAILS));
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.POST_DETAILS.key));
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(pageIn(controller)), Settings.POST_DETAILS.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.POST_DETAILS.key));
        }
    }

    /** Folder per account sits under the folder and the file name, starts off and goes in a settings file. */
    @Test
    public void folderPerAccountSitsUnderTheFolder() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            PreferenceGroup downloads = section(page.getPreferenceScreen(), "Downloads");
            Preference row = downloads.findPreference(Settings.SAVE_FOLDER_PER_ACCOUNT.key);
            assertTrue(row instanceof SwitchPreference);
            assertEquals("Folder per account", String.valueOf(row.getTitle()));
            assertFalse(((SwitchPreference) row).isChecked());
            int name = -1;
            int account = -1;
            for (int i = 0; i < downloads.getPreferenceCount(); i++) {
                String key = downloads.getPreference(i).getKey();
                if (Settings.FILENAME_TEMPLATE.key.equals(key)) name = i;
                if (Settings.SAVE_FOLDER_PER_ACCOUNT.key.equals(key)) account = i;
            }
            assertEquals(name + 1, account);
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SAVE_FOLDER_PER_ACCOUNT.key));
        }
    }

    /** Name saves by account and post time sits right under Folder per account, starts off and goes in a settings file. */
    @Test
    public void nameSavesByPostSitsUnderFolderPerAccount() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            PreferenceGroup downloads = section(page.getPreferenceScreen(), "Downloads");
            Preference row = downloads.findPreference(Settings.SAVE_NAME_BY_POST.key);
            assertTrue(row instanceof SwitchPreference);
            assertEquals("Name saves by account and post time", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()), String.valueOf(row.getSummary()).contains("username_20261005_143012"));
            assertFalse(((SwitchPreference) row).isChecked());
            int account = -1;
            int byPost = -1;
            for (int i = 0; i < downloads.getPreferenceCount(); i++) {
                String key = downloads.getPreference(i).getKey();
                if (Settings.SAVE_FOLDER_PER_ACCOUNT.key.equals(key)) account = i;
                if (Settings.SAVE_NAME_BY_POST.key.equals(key)) byPost = i;
            }
            assertEquals(account + 1, byPost);
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SAVE_NAME_BY_POST.key));
        }
    }

    @Test
    public void theFolderRowKeepsOneCleanNameAndSaysWhereSavesGo() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment.FolderRow folder = (HushgramPreferenceFragment.FolderRow)
                    pageIn(controller).findPreference(Settings.SAVE_FOLDER.key);
            assertNotNull("no folder row with a download in the build", folder);
            assertEquals("Videos go to " + L10n.isolate("Movies/Instagram") + " and photos to "
                    + L10n.isolate("Pictures/Instagram") + ".", String.valueOf(folder.getSummary()));
            assertEquals("Save", String.valueOf(folder.getPositiveButtonText()));
            assertEquals("Folder name", String.valueOf(folder.getEditText().getHint()));
            assertTrue(String.valueOf(folder.getDialogMessage()).contains(L10n.isolate("Instagram")));

            // What's typed reaches the row's check the way the dialog's OK sends it.
            Preference.OnPreferenceChangeListener ok = folder.getOnPreferenceChangeListener();
            assertFalse("a path was kept as typed", ok.onPreferenceChange(folder, "../My/Clips"));
            ShadowLooper.idleMainLooper();
            assertEquals("My_Clips", folder.getText());
            assertEquals("My_Clips", Settings.SAVE_FOLDER.savedValue());
            assertEquals(HushgramPreferenceFragment.folderSummary("My_Clips"), String.valueOf(folder.getSummary()));
            assertEquals("Folder set to " + L10n.isolate("My_Clips") + ".", ShadowToast.getTextOfLatestToast());

            assertTrue("a clean name was changed", ok.onPreferenceChange(folder, "Clips"));
            folder.setText("Clips");
            ShadowLooper.idleMainLooper();
            assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());

            assertFalse("an empty name was kept", ok.onPreferenceChange(folder, "  "));
            ShadowLooper.idleMainLooper();
            assertEquals("Instagram", folder.getText());
            assertEquals("Instagram", Settings.SAVE_FOLDER.savedValue());
        }
    }

    /**
     * The video file name's row, next to the folder, keeps the one clean template a save would use,
     * whatever is typed into it, says so in a toast when it changed what was typed, says what videos
     * and photos are named, and names every token in its dialog.
     */
    @Test
    public void theFileNameRowKeepsOneCleanTemplateNextToTheFolder() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int nameAt = indexOfKey(rows, Settings.FILENAME_TEMPLATE.key);
            assertTrue("no file name row with a download in the build",
                    nameAt >= 0 && rows.get(nameAt) instanceof HushgramPreferenceFragment.FileNameRow);
            HushgramPreferenceFragment.FileNameRow name = (HushgramPreferenceFragment.FileNameRow) rows.get(nameAt);
            assertEquals("the file name row isn't next to the folder", indexOfKey(rows, Settings.SAVE_FOLDER.key) + 1, nameAt);
            assertEquals("Video file name", String.valueOf(name.getTitle()));
            // The photo prefix is HushGram's own, so the summary doesn't call it Instagram's.
            assertEquals("Videos are named " + L10n.isolate("IG_VID_{date}") + ". Photos are always named "
                    + L10n.isolate("IG_IMG_") + " followed by the date and time.", String.valueOf(name.getSummary()));
            String message = String.valueOf(name.getDialogMessage());
            assertTrue(message, message.contains(L10n.isolate("{date}")) && message.contains(L10n.isolate("{video_id}"))
                    && message.contains(L10n.isolate("{owner}")) && message.contains(L10n.isolate("{posted}"))
                    && message.contains(L10n.isolate("IG_VID_{date}")) && message.contains("number on Instagram"));
            assertEquals("Save", String.valueOf(name.getPositiveButtonText()));
            assertEquals("File name", String.valueOf(name.getEditText().getHint()));

            Preference.OnPreferenceChangeListener ok = name.getOnPreferenceChangeListener();
            assertFalse("a path was kept as typed", ok.onPreferenceChange(name, "../Reels/{video_id}"));
            ShadowLooper.idleMainLooper();
            assertEquals("Reels_{video_id}", name.getText());
            assertEquals("Reels_{video_id}", Settings.FILENAME_TEMPLATE.savedValue());
            assertEquals(HushgramPreferenceFragment.fileNameSummary("Reels_{video_id}"), String.valueOf(name.getSummary()));
            assertEquals("File name set to " + L10n.isolate("Reels_{video_id}") + ".", ShadowToast.getTextOfLatestToast());

            // A name that would be the same for every video gets the date.
            assertFalse("a name with no token was kept", ok.onPreferenceChange(name, "Clip"));
            ShadowLooper.idleMainLooper();
            assertEquals("Clip_{date}", name.getText());
            assertEquals("Clip_{date}", Settings.FILENAME_TEMPLATE.savedValue());

            ShadowToast.reset();
            assertTrue("a clean template was changed", ok.onPreferenceChange(name, "{date} {video_id}"));
            name.setText("{date} {video_id}");
            ShadowLooper.idleMainLooper();
            assertEquals("{date} {video_id}", Settings.FILENAME_TEMPLATE.savedValue());
            assertNull("a clean template raised a toast", ShadowToast.getLatestToast());

            // A photo's own prefix can't name a video.
            assertFalse(ok.onPreferenceChange(name, "IG_IMG_{date}"));
            ShadowLooper.idleMainLooper();
            assertEquals("IG_VID_{date}", name.getText());

            assertFalse("an empty template was kept", ok.onPreferenceChange(name, " . "));
            ShadowLooper.idleMainLooper();
            assertEquals("IG_VID_{date}", name.getText());
            assertEquals("IG_VID_{date}", Settings.FILENAME_TEMPLATE.savedValue());
        }
    }

    /** The dialog's example is the name the save pipeline would give, and Cancel keeps the saved template. */
    @Test
    public void theFileNameExampleIsThePipelinesNameAndCancelKeepsTheTemplate() {
        assertEquals("Example_123456.mp4",
                HushgramPreferenceFragment.FileNameRow.previewName("Example_{video_id}", new java.util.Date(0)));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment.FileNameRow name = (HushgramPreferenceFragment.FileNameRow)
                    pageIn(controller).findPreference(Settings.FILENAME_TEMPLATE.key);
            String before = Settings.FILENAME_TEMPLATE.savedValue();
            name.showDialog(null);
            ShadowLooper.idleMainLooper();
            name.getEditText().setText("Example_{video_id}");
            ((AlertDialog) name.getDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(before, Settings.FILENAME_TEMPLATE.savedValue());
        }
    }

    /**
     * Unset, a dialog's Cancel is Android's own, in the activity's language rather than
     * Instagram's, and it read "Cancel" next to "Speichern". Every download dialog sets its own.
     */
    @Test
    @Config(qualifiers = "de")
    public void everyDownloadDialogCancelsInInstagramsLanguage() {
        assertEquals("Abbrechen", L10n.t("Cancel"));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals("Abbrechen", String.valueOf(
                    HushgramPreferenceFragment.folderRow(controller.get()).getNegativeButtonText()));
            HushgramPreferenceFragment.FileNameRow name = HushgramPreferenceFragment.fileNameRow(controller.get());
            assertEquals(L10n.t("Save"), String.valueOf(name.getPositiveButtonText()));
            assertEquals("Abbrechen", String.valueOf(name.getNegativeButtonText()));
            assertEquals("Abbrechen", String.valueOf(
                    HushgramPreferenceFragment.qualityRow(controller.get()).getNegativeButtonText()));
        }
    }

    /** At a large font size the file name's long message pushed Save and Cancel under the keyboard. */
    @Test
    public void bothEditDialogsResizeAboveTheKeyboardInTheScreensColours() {
        assertResizes(Settings.SAVE_FOLDER.key);
        assertResizes(Settings.FILENAME_TEMPLATE.key);
        HushgramPreferenceFragment.fitAboveKeyboard(null);
    }

    /** The quality list takes the screen's colours and offers each quality. */
    @Test
    public void theQualityListTakesTheScreensColours() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment.QualityRow row = (HushgramPreferenceFragment.QualityRow)
                    pageInDialog(controller).findPreference(Settings.DOWNLOAD_QUALITY.key);
            row.showDialog(null);
            ShadowLooper.idleMainLooper();
            try {
                AlertDialog list = (AlertDialog) row.getDialog();
                int titleId = list.getContext().getResources().getIdentifier("alertTitle", "id", "android");
                TextView title = list.findViewById(titleId);
                assertEquals("Download quality", String.valueOf(title.getText()));
                assertEquals(ScreenColors.DEFAULT.title, title.getCurrentTextColor());
                assertEquals(DownloadQuality.values().length, list.getListView().getAdapter().getCount());
            } finally {
                row.getDialog().dismiss();
            }
        }
    }

    private static void assertResizes(String key) {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            EditTextPreference row = (EditTextPreference) pageInDialog(controller).findPreference(key);
            assertNotNull("no row for " + key, row);
            if (row instanceof HushgramPreferenceFragment.FolderRow) {
                ((HushgramPreferenceFragment.FolderRow) row).showDialog(null);
            } else {
                ((HushgramPreferenceFragment.FileNameRow) row).showDialog(null);
            }
            ShadowLooper.idleMainLooper();
            try {
                int mode = row.getDialog().getWindow().getAttributes().softInputMode;
                assertEquals(key + " doesn't resize above the keyboard", WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                        mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
                assertTrue(key + "'s field has no outlined surface", row.getEditText().getBackground() instanceof GradientDrawable);
                assertEquals(ScreenColors.DEFAULT.dialog,
                        ((GradientDrawable) row.getEditText().getBackground()).getColor().getDefaultColor());
            } finally {
                row.getDialog().dismiss();
            }
        }
    }

    /** The page on its own in the activity. */
    static HushgramPreferenceFragment pageIn(ActivityController<Activity> controller) {
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        return page;
    }

    /** The page as a person opens it, inside the settings dialog. */
    private static HushgramPreferenceFragment pageInDialog(ActivityController<Activity> controller) {
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "hushgram_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        Fragment page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue("no preference page", page instanceof HushgramPreferenceFragment);
        return (HushgramPreferenceFragment) page;
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

    private static PreferenceGroup section(PreferenceScreen screen, String title) {
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (preference instanceof PreferenceGroup && title.equals(String.valueOf(preference.getTitle()))) {
                return (PreferenceGroup) preference;
            }
        }
        return null;
    }
}
