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
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.DocumentsContract;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.download.DownloadHistory;
import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.download.SaveTree;
import app.hushgram.extension.instagram.download.SaveTreeTest;
import app.hushgram.extension.instagram.download.SavesForTests;
import app.hushgram.extension.instagram.reels.HiddenSounds;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.PauseForTests;

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
import org.robolectric.shadows.ShadowAlertDialog;
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
        Settings.DOWNLOAD_REEL_AUDIO.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.DOWNLOAD_FEED_COVER.resetToDefault();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.DOWNLOAD_CHOOSE_PAGES.resetToDefault();
        Settings.FEED_DOWNLOAD_BUTTON.resetToDefault();
        Settings.FEED_DOWNLOAD_ALL_PAGES.resetToDefault();
        Settings.REEL_DOWNLOAD_BUTTON.resetToDefault();
        Settings.REEL_NOT_INTERESTED_BUTTON.resetToDefault();
        Settings.REEL_BLOCK_BUTTON.resetToDefault();
        Settings.HIDE_SOUND_REELS.resetToDefault();
        Settings.HIDDEN_SOUNDS.resetToDefault();
        Settings.HOLD_TO_DOWNLOAD.resetToDefault();
        Settings.HOLD_FINGERS.resetToDefault();
        Settings.HOLD_TIME.resetToDefault();
        Settings.DOWNLOAD_SKIP_SAVED.resetToDefault();
        DownloadHistory.clear(RuntimeEnvironment.getApplication());
        SavesForTests.forgetHistoryForTests();
    }

    /**
     * Download button on reels (#178) sits under Downloads, right after the feed post one when that
     * is in the build too. It starts off, waits for Download on reels and goes in a settings file. A
     * build without the reel download has none.
     */
    @Test
    public void theReelButtonSitsUnderDownloadsAndWaitsForDownloadOnReels() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            PreferenceGroup downloads = section(page.getPreferenceScreen(), "Downloads");
            Preference row = downloads.findPreference(Settings.REEL_DOWNLOAD_BUTTON.key);
            assertTrue("the reel button isn't under Downloads", row instanceof SwitchPreference);
            assertEquals("Download button on reels", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()).contains("Needs Download on reels"));
            assertFalse("it starts off", ((SwitchPreference) row).isChecked());
            assertTrue(row.isEnabled());
            ((SwitchPreference) page.findPreference(Settings.DOWNLOAD_REELS.key)).setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse("it waits for Download on reels", row.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_DOWNLOAD_BUTTON.key));
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD, PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            assertEquals(indexOfKey(rows, Settings.FEED_DOWNLOAD_ALL_PAGES.key) + 1, indexOfKey(rows, Settings.REEL_DOWNLOAD_BUTTON.key));
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(pageIn(controller)), Settings.REEL_DOWNLOAD_BUTTON.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.REEL_DOWNLOAD_BUTTON.key));
        }
    }

    /**
     * Not interested button on reels (#191) sits under Reels with the reel download in the build. It
     * starts off, has its own switch and goes in a settings file. A build without the reel download has none.
     */
    @Test
    public void theNotInterestedButtonSitsUnderReelsWithItsOwnSwitch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            PreferenceGroup reels = section(page.getPreferenceScreen(), "Reels");
            Preference row = reels.findPreference(Settings.REEL_NOT_INTERESTED_BUTTON.key);
            assertTrue("the Not interested button isn't under Reels", row instanceof SwitchPreference);
            assertEquals("Not interested button on reels", String.valueOf(row.getTitle()));
            assertFalse("it starts off", ((SwitchPreference) row).isChecked());
            ((SwitchPreference) page.findPreference(Settings.DOWNLOAD_REELS.key)).setChecked(false);
            ShadowLooper.idleMainLooper();
            assertTrue("it doesn't wait for Download on reels", row.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_NOT_INTERESTED_BUTTON.key));
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(pageIn(controller)), Settings.REEL_NOT_INTERESTED_BUTTON.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.REEL_NOT_INTERESTED_BUTTON.key));
        }
    }

    /**
     * Block button on reels (#191) sits right after the Not interested one under Reels. It starts off,
     * has its own switch and goes in a settings file. A build without the reel download has none.
     */
    @Test
    public void theBlockButtonSitsAfterNotInterestedWithItsOwnSwitch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            PreferenceGroup reels = section(page.getPreferenceScreen(), "Reels");
            Preference row = reels.findPreference(Settings.REEL_BLOCK_BUTTON.key);
            assertTrue("the Block button isn't under Reels", row instanceof SwitchPreference);
            assertEquals("Block button on reels", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()).contains("confirm"));
            assertFalse("it starts off", ((SwitchPreference) row).isChecked());
            List<Preference> rows = rowsOf(page);
            assertEquals(indexOfKey(rows, Settings.REEL_NOT_INTERESTED_BUTTON.key) + 1, indexOfKey(rows, Settings.REEL_BLOCK_BUTTON.key));
            ((SwitchPreference) page.findPreference(Settings.DOWNLOAD_REELS.key)).setChecked(false);
            ShadowLooper.idleMainLooper();
            assertTrue("it doesn't wait for Download on reels", row.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_BLOCK_BUTTON.key));
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(pageIn(controller)), Settings.REEL_BLOCK_BUTTON.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.REEL_BLOCK_BUTTON.key));
        }
    }

    /**
     * Hide reels by sound (#191) sits right after the Block button under Reels, with the Hidden sounds
     * row under it. The switch starts off and goes in a settings file, the list stays out of one, and
     * a build without the reel download has neither.
     */
    @Test
    public void hideReelsBySoundSitsAfterBlockWithItsListUnderIt() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            PreferenceGroup reels = section(page.getPreferenceScreen(), "Reels");
            Preference row = reels.findPreference(Settings.HIDE_SOUND_REELS.key);
            assertTrue("Hide reels by sound isn't under Reels", row instanceof SwitchPreference);
            assertEquals("Hide reels by sound", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()).contains("music icon"));
            assertFalse("it starts off", ((SwitchPreference) row).isChecked());
            Preference list = reels.findPreference(HushgramPreferenceFragment.HIDDEN_SOUNDS_ROW);
            assertNotNull("no Hidden sounds row", list);
            assertEquals("Hidden sounds", String.valueOf(list.getTitle()));
            List<Preference> rows = rowsOf(page);
            assertEquals(indexOfKey(rows, Settings.REEL_BLOCK_BUTTON.key) + 1, indexOfKey(rows, Settings.HIDE_SOUND_REELS.key));
            assertEquals(indexOfKey(rows, Settings.HIDE_SOUND_REELS.key) + 1, indexOfKey(rows, HushgramPreferenceFragment.HIDDEN_SOUNDS_ROW));
            ((SwitchPreference) page.findPreference(Settings.DOWNLOAD_REELS.key)).setChecked(false);
            ShadowLooper.idleMainLooper();
            assertTrue("it doesn't wait for Download on reels", row.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_SOUND_REELS.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_SOUNDS.key));
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            assertEquals(-1, indexOfKey(rows, Settings.HIDE_SOUND_REELS.key));
            assertEquals(-1, indexOfKey(rows, HushgramPreferenceFragment.HIDDEN_SOUNDS_ROW));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.HIDE_SOUND_REELS.key));
        }
    }

    /** Hidden sounds lists each sound by title and artist, checked; unchecking one brings its reels back and checking it hides them again. */
    @Test
    public void hiddenSoundsListsEachSoundAndUncheckingOneBringsItsReelsBack() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            Preference row = page.findPreference(HushgramPreferenceFragment.HIDDEN_SOUNDS_ROW);
            assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
            ShadowLooper.idleMainLooper();
            AlertDialog empty = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(String.valueOf(Shadows.shadowOf(empty).getMessage()).startsWith("No sounds are hidden yet."));
            empty.dismiss();

            HiddenSounds.add(new HiddenSounds.Sound("111", "Espresso", "Sabrina Carpenter"));
            HiddenSounds.add(new HiddenSounds.Sound("222", "Original audio", "@nasa"));
            page.showHiddenSounds();
            ShadowLooper.idleMainLooper();
            ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();
            assertEquals(2, list.getCount());
            assertEquals(L10n.isolate("Espresso by Sabrina Carpenter"), list.getItemAtPosition(0).toString());
            assertTrue(list.isItemChecked(0));

            assertTrue(list.performItemClick(list.getAdapter().getView(0, null, list), 0, list.getItemIdAtPosition(0)));
            ShadowLooper.idleMainLooper();
            assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()).startsWith("Showing reels with"));
            assertFalse(HiddenSounds.hides("111"));
            assertTrue(HiddenSounds.hides("222"));

            assertTrue(list.performItemClick(list.getAdapter().getView(0, null, list), 0, list.getItemIdAtPosition(0)));
            ShadowLooper.idleMainLooper();
            assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()).startsWith("Hiding reels with"));
            assertTrue(HiddenSounds.hides("111"));
        }
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

            Preference audio = reels.findPreference(Settings.DOWNLOAD_REEL_AUDIO.key);
            assertTrue("Download audio isn't in Reels", audio instanceof SwitchPreference);
            assertEquals("Download audio", String.valueOf(audio.getTitle()));
            assertFalse("Download audio starts off", ((SwitchPreference) audio).isChecked());
            ((SwitchPreference) row).setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse("Download audio waits for Download on reels", audio.isEnabled());
            ((SwitchPreference) row).setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(audio.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DOWNLOAD_REEL_AUDIO.key));
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
     * download patch in, above the quality it keeps within, and starts off. The switch that converts
     * a VP9 or AV1 full size picture for those saves sits right under it, also off (#236).
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
            assertEquals("Save videos other apps can open", String.valueOf(row.getTitle()));
            assertEquals("For WhatsApp, video editors such as CapCut and InShot, or a gallery or player that plays saves "
                    + "without sound. May lower quality.", String.valueOf(row.getSummary()));

            int convert = indexOfKey(rows, Settings.DOWNLOAD_CONVERT.key);
            assertEquals("the convert switch isn't right under the compatible one", compatible + 1, convert);
            Preference convertRow = rows.get(convert);
            assertTrue(convertRow instanceof SwitchPreference);
            assertFalse(((SwitchPreference) convertRow).isChecked());
            assertEquals("Convert to keep the full size", String.valueOf(convertRow.getTitle()));
            assertEquals("Saves videos other apps can open, at full size. A video Instagram only has as VP9 or AV1 "
                    + "is converted to H.264 on your phone instead of saved smaller. Slower, and videos over "
                    + "3 minutes stay smaller.", String.valueOf(convertRow.getSummary()));

            int noAv1 = indexOfKey(rows, Settings.DOWNLOAD_NO_AV1.key);
            assertEquals("the AV1 switch isn't right under the convert one (#379)", convert + 1, noAv1);
            Preference noAv1Row = rows.get(noAv1);
            assertTrue(noAv1Row instanceof SwitchPreference);
            assertFalse(((SwitchPreference) noAv1Row).isChecked());
            assertEquals("Never save AV1 video", String.valueOf(noAv1Row.getTitle()));
            assertEquals("For phones that play AV1 badly. A video Instagram has at full size only as AV1 is saved "
                    + "as VP9 when it has one, or at its largest H.264 size. Convert to keep the full size "
                    + "then converts only VP9.", String.valueOf(noAv1Row.getSummary()));

            int noHdr = indexOfKey(rows, Settings.DOWNLOAD_NO_HDR.key);
            assertEquals("the HDR switch isn't right under the AV1 one (#352)", noAv1 + 1, noHdr);
            assertEquals(indexOfKey(rows, Settings.DOWNLOAD_QUALITY.key) - 1, noHdr);
            Preference noHdrRow = rows.get(noHdr);
            assertTrue(noHdrRow instanceof SwitchPreference);
            assertFalse(((SwitchPreference) noHdrRow).isChecked());
            assertEquals("Never save HDR video", String.valueOf(noHdrRow.getTitle()));
            assertEquals("For apps that show HDR with a yellow or green cast, such as Messenger. A video Instagram "
                    + "has at full size only in HDR is saved at its largest standard size instead.",
                    String.valueOf(noHdrRow.getSummary()));
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            assertEquals("a compatible-saves row with no download in the build",
                    -1, indexOfKey(rows, Settings.DOWNLOAD_COMPATIBLE.key));
            assertEquals("a convert row with no download in the build",
                    -1, indexOfKey(rows, Settings.DOWNLOAD_CONVERT.key));
            assertEquals("an AV1 row with no download in the build",
                    -1, indexOfKey(rows, Settings.DOWNLOAD_NO_AV1.key));
            assertEquals("an HDR row with no download in the build",
                    -1, indexOfKey(rows, Settings.DOWNLOAD_NO_HDR.key));
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
            assertEquals("Each video saves at the best quality Instagram offers for it.", String.valueOf(quality.getSummary()));

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

    /**
     * Download video covers sits right below Download feed videos, which it waits for, with the
     * feed video download in the build (#94). It starts off and goes in a settings file. A build
     * with only reel downloads has no feed menu for it, so no row.
     */
    @Test
    public void videoCoversSitUnderDownloadFeedVideos() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int cover = indexOfKey(rows, Settings.DOWNLOAD_FEED_COVER.key);
            assertEquals(indexOfKey(rows, Settings.DOWNLOAD_VIDEOS.key) + 1, cover);
            SwitchPreference row = (SwitchPreference) rows.get(cover);
            assertEquals("Download video covers", String.valueOf(row.getTitle()));
            assertFalse("Download video covers starts off", row.isChecked());
            SwitchPreference videos = (SwitchPreference) rows.get(cover - 1);
            assertFalse("Download feed videos starts off", videos.isChecked());
            assertFalse("Download video covers waits for Download feed videos", row.isEnabled());
            videos.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(row.isEnabled());
            videos.setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse(row.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DOWNLOAD_FEED_COVER.key));
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(pageIn(controller)), Settings.DOWNLOAD_FEED_COVER.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.DOWNLOAD_FEED_COVER.key));
        }
    }

    /**
     * Choose carousel pages sits right under Details, with the feed menu's other rows. It starts
     * off, waits for Download feed videos or Download feed photos, and goes in a settings file. A
     * build with only reel downloads has no feed menu for it, so no row.
     */
    @Test
    public void chooseCarouselPagesSitsUnderDetailsAndWaitsForAFeedSwitch() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int choose = indexOfKey(rows, Settings.DOWNLOAD_CHOOSE_PAGES.key);
            assertEquals(indexOfKey(rows, Settings.POST_DETAILS.key) + 1, choose);
            SwitchPreference row = (SwitchPreference) rows.get(choose);
            assertEquals("Choose carousel pages", String.valueOf(row.getTitle()));
            assertFalse("Choose carousel pages starts off", row.isChecked());
            assertFalse("it waits for a feed download switch", row.isEnabled());
            SwitchPreference photos = (SwitchPreference) rows.get(indexOfKey(rows, Settings.DOWNLOAD_PHOTOS.key));
            photos.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue("the photo switch alone is enough", row.isEnabled());
            photos.setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse(row.isEnabled());
            SwitchPreference videos = (SwitchPreference) rows.get(indexOfKey(rows, Settings.DOWNLOAD_VIDEOS.key));
            videos.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue("the video switch alone is enough", row.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DOWNLOAD_CHOOSE_PAGES.key));
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertEquals(-1, indexOfKey(rowsOf(pageIn(controller)), Settings.DOWNLOAD_CHOOSE_PAGES.key));
            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.DOWNLOAD_CHOOSE_PAGES.key));
        }
    }

    /**
     * Save every page without asking sits right under the feed's Download button, starts off, waits
     * for the button and goes in a settings file with it (#190).
     */
    @Test
    public void saveEveryPageSitsUnderTheFeedButtonAndWaitsForIt() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int at = indexOfKey(rows, Settings.FEED_DOWNLOAD_ALL_PAGES.key);
            assertEquals(indexOfKey(rows, Settings.FEED_DOWNLOAD_BUTTON.key) + 1, at);
            SwitchPreference all = (SwitchPreference) rows.get(at);
            assertEquals("Save every page without asking", String.valueOf(all.getTitle()));
            assertFalse("it starts off", all.isChecked());
            assertFalse("it waits for the Download button", all.isEnabled());
            ((SwitchPreference) rows.get(indexOfKey(rows, Settings.DOWNLOAD_PHOTOS.key))).setChecked(true);
            ((SwitchPreference) rows.get(indexOfKey(rows, Settings.FEED_DOWNLOAD_BUTTON.key))).setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(all.isEnabled());
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FEED_DOWNLOAD_ALL_PAGES.key));
        }
    }

    /** Searching the patch's name finds the feed button and the switch under it, though neither says it. */
    @Test
    public void searchingThePatchsNameFindsTheFeedButtonRows() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            List<Preference> rows = rowsOf(page);
            Preference button = rows.get(indexOfKey(rows, Settings.FEED_DOWNLOAD_BUTTON.key));
            Preference all = rows.get(indexOfKey(rows, Settings.FEED_DOWNLOAD_ALL_PAGES.key));
            page.searchSettings("download any video");
            assertNotNull("the feed button", button.getParent());
            assertNotNull("save every page", all.getParent());
        }
    }

    /**
     * Hold to download sits right below the Download button, starts off and waits for Download feed
     * videos or photos, as the button does. Its Fingers and Hold time lists follow it, start on two
     * fingers and a second, wait for it, and go in a settings file with it. A build with only reel
     * downloads has none of the three.
     */
    @Test
    @Config(sdk = {28, 37})
    public void holdToDownloadSitsUnderTheDownloadButtonWithItsTwoLists() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            int at = indexOfKey(rows, Settings.HOLD_TO_DOWNLOAD.key);
            assertEquals(indexOfKey(rows, Settings.FEED_DOWNLOAD_ALL_PAGES.key) + 1, at);
            SwitchPreference hold = (SwitchPreference) rows.get(at);
            assertEquals("Hold to download", String.valueOf(hold.getTitle()));
            assertTrue(String.valueOf(hold.getSummary()).contains("Needs Download feed videos or photos"));
            assertFalse("it starts off", hold.isChecked());
            assertFalse("it waits for a download switch", hold.isEnabled());

            assertTrue(rows.get(at + 1) instanceof HushgramPreferenceFragment.HoldFingersRow);
            HushgramPreferenceFragment.HoldFingersRow fingers = (HushgramPreferenceFragment.HoldFingersRow) rows.get(at + 1);
            assertTrue(rows.get(at + 2) instanceof HushgramPreferenceFragment.HoldTimeRow);
            HushgramPreferenceFragment.HoldTimeRow time = (HushgramPreferenceFragment.HoldTimeRow) rows.get(at + 2);
            assertEquals("Fingers", String.valueOf(fingers.getTitle()));
            assertEquals(Arrays.asList("Two fingers", "Three fingers"), texts(fingers.getEntries()));
            assertEquals("TWO", fingers.getValue());
            assertEquals("Two fingers", String.valueOf(fingers.getSummary()));
            assertEquals("Hold time", String.valueOf(time.getTitle()));
            assertEquals(Arrays.asList("Half a second", "1 second", "1.5 seconds"), texts(time.getEntries()));
            assertEquals("ONE_SECOND", time.getValue());
            assertEquals("1 second", String.valueOf(time.getSummary()));
            assertFalse("the lists wait for the switch", fingers.isEnabled());

            ((SwitchPreference) rows.get(indexOfKey(rows, Settings.DOWNLOAD_PHOTOS.key))).setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(hold.isEnabled());
            hold.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(fingers.isEnabled());
            assertTrue(time.isEnabled());
            fingers.setValue("THREE");
            time.setValue("HALF_SECOND");
            ShadowLooper.idleMainLooper();
            assertEquals(app.hushgram.extension.instagram.download.HoldFingers.THREE, Settings.HOLD_FINGERS.savedValue());
            assertEquals(app.hushgram.extension.instagram.download.HoldTime.HALF_SECOND, Settings.HOLD_TIME.savedValue());
            assertEquals("Three fingers", String.valueOf(fingers.getSummary()));
            assertEquals("Half a second", String.valueOf(time.getSummary()));
            for (String key : List.of(Settings.HOLD_TO_DOWNLOAD.key, Settings.HOLD_FINGERS.key, Settings.HOLD_TIME.key)) {
                assertTrue(key, ConfigurationBackup.eligible().containsKey(key));
            }
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(pageIn(controller));
            for (String key : List.of(Settings.HOLD_TO_DOWNLOAD.key, Settings.HOLD_FINGERS.key, Settings.HOLD_TIME.key)) {
                assertEquals(key, -1, indexOfKey(rows, key));
                assertFalse(key, ConfigurationBackup.eligible().containsKey(key));
            }
        }
    }

    private static List<String> texts(CharSequence[] entries) {
        List<String> texts = new ArrayList<>();
        for (CharSequence entry : entries) texts.add(String.valueOf(entry));
        return texts;
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

    /**
     * Choose a folder and Use Movies and Pictures sit right under Save folder (#333). Choose opens
     * Android's folder picker, the folder picked becomes where saves go and the row says so, a
     * cancelled picker changes nothing, and Use Movies and Pictures, there only once a folder is
     * picked, puts saves back. The folder picked never goes in a settings file.
     */
    @Test
    public void chooseAFolderPicksWhereSavesGoAndUseMoviesAndPicturesPutsThemBack() throws Exception {
        SaveTreeTest.install();
        Uri tree = SaveTreeTest.TREE;
        String label = L10n.isolate("Download/.Insta");
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            PreferenceGroup downloads = section(page.getPreferenceScreen(), "Downloads");
            int choose = -1;
            int gallery = -1;
            for (int i = 0; i < downloads.getPreferenceCount(); i++) {
                String key = downloads.getPreference(i).getKey();
                if (Settings.SAVE_TREE.key.equals(key)) choose = i;
                if (HushgramPreferenceFragment.GALLERY_FOLDERS.equals(key)) gallery = i;
            }
            assertEquals("the folder choice isn't the last pair in Downloads", downloads.getPreferenceCount() - 2, choose);
            assertEquals(choose + 1, gallery);
            Preference chooseRow = downloads.getPreference(choose);
            Preference galleryRow = downloads.getPreference(gallery);
            assertEquals("Choose a folder", String.valueOf(chooseRow.getTitle()));
            assertTrue(String.valueOf(chooseRow.getSummary()),
                    String.valueOf(chooseRow.getSummary()).startsWith("Saves go to Movies and Pictures."));
            assertEquals("Use Movies and Pictures", String.valueOf(galleryRow.getTitle()));
            assertFalse("Use Movies and Pictures works with no folder picked", galleryRow.isEnabled());

            assertTrue(chooseRow.getOnPreferenceClickListener().onPreferenceClick(chooseRow));
            ShadowActivity.IntentForResult asked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
            assertNotNull("no folder picker opened", asked);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, asked.intent.getAction());
            assertTrue("the picker's grant can't be kept",
                    (asked.intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
            assertFalse("Choose a folder works again while the picker is open", chooseRow.isEnabled());
            page.onActivityResult(asked.requestCode, Activity.RESULT_OK, new Intent().setData(tree));
            ShadowLooper.idleMainLooper();
            assertEquals(tree.toString(), Settings.SAVE_TREE.get());
            assertEquals("Folder set to " + label + ".", ShadowToast.getTextOfLatestToast());
            assertEquals(L10n.f("Photos and videos go to %1$s. Tap to pick another folder.", label),
                    String.valueOf(chooseRow.getSummary()));
            assertTrue(chooseRow.isEnabled());
            assertTrue(galleryRow.isEnabled());

            assertFalse(ConfigurationBackup.eligible().containsKey(Settings.SAVE_TREE.key));
            String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);
            assertFalse("the folder picked went into a settings file", exported.contains(Settings.SAVE_TREE.key));
            assertFalse("the folder picked went into a settings file", exported.contains("externalstorage"));

            assertTrue(chooseRow.getOnPreferenceClickListener().onPreferenceClick(chooseRow));
            asked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
            assertEquals("the picker doesn't start in the folder picked", SaveTree.root(tree),
                    asked.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
            page.onActivityResult(asked.requestCode, Activity.RESULT_CANCELED, null);
            assertEquals("a cancelled picker changed the folder", tree.toString(), Settings.SAVE_TREE.get());

            assertTrue(galleryRow.getOnPreferenceClickListener().onPreferenceClick(galleryRow));
            ShadowLooper.idleMainLooper();
            assertEquals("", Settings.SAVE_TREE.get());
            assertEquals("Saves go to Movies and Pictures again.", ShadowToast.getTextOfLatestToast());
            assertFalse(galleryRow.isEnabled());
            assertTrue(String.valueOf(chooseRow.getSummary()).startsWith("Saves go to Movies and Pictures."));
        } finally {
            SaveTree.forget(RuntimeEnvironment.getApplication());
        }
    }

    /** A folder whose grant went: the row says saves went back, and to pick it again. */
    @Test
    public void aFolderOutOfReachSaysSoOnItsRow() {
        SaveTreeTest.install();
        Context context = RuntimeEnvironment.getApplication();
        assertTrue(SaveTree.keep(context, SaveTreeTest.TREE));
        context.getContentResolver().releasePersistableUriPermission(SaveTreeTest.TREE,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Preference row = pageIn(controller).findPreference(Settings.SAVE_TREE.key);
            assertEquals(L10n.f("HushGram can't reach %1$s anymore, so saves go to Movies and Pictures. Pick the folder again.",
                    L10n.isolate("Download/.Insta")), String.valueOf(row.getSummary()));
        } finally {
            SaveTree.forget(context);
        }
    }

    /** A folder deleted in Files keeps its grant, but its row says saves went back, not that they go there. */
    @Test
    public void aDeletedFolderSaysSoOnItsRow() {
        SaveTreeTest.Tree tree = SaveTreeTest.install();
        Context context = RuntimeEnvironment.getApplication();
        assertTrue(SaveTree.keep(context, SaveTreeTest.TREE));
        tree.deleteFolder();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            Preference row = page.findPreference(Settings.SAVE_TREE.key);
            assertEquals(L10n.f("HushGram can't reach %1$s anymore, so saves go to Movies and Pictures. Pick the folder again.",
                    L10n.isolate("Download/.Insta")), String.valueOf(row.getSummary()));
            assertTrue("Use Movies and Pictures can't be tapped for a deleted folder",
                    page.findPreference(HushgramPreferenceFragment.GALLERY_FOLDERS).isEnabled());
        } finally {
            SaveTree.forget(context);
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

    /**
     * Skip what's already saved sits in the download naming rows, under Name saves by account and
     * post time and the Add the source to saved photos row that follows it, with Clear download
     * history directly under Skip. The switch starts off and goes in a settings file, the list it
     * keeps never does, and Clear forgets the list at once and says so (#150).
     */
    @Test
    public void skipWhatsAlreadySavedSitsUnderTheNameSwitchWithItsClearRow() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SavesForTests.rememberSave(context, "3100000000000000042", "content://media/external/video/media/42");
        assertEquals(1, SavesForTests.historyCount(context));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VIDEO_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            PreferenceGroup downloads = section(pageIn(controller).getPreferenceScreen(), "Downloads");
            int byPost = -1;
            int metadata = -1;
            int skip = -1;
            int clear = -1;
            for (int i = 0; i < downloads.getPreferenceCount(); i++) {
                String key = downloads.getPreference(i).getKey();
                if (Settings.SAVE_NAME_BY_POST.key.equals(key)) byPost = i;
                if (Settings.SAVE_PHOTO_METADATA.key.equals(key)) metadata = i;
                if (Settings.DOWNLOAD_SKIP_SAVED.key.equals(key)) skip = i;
                if (HushgramPreferenceFragment.CLEAR_DOWNLOAD_HISTORY.equals(key)) clear = i;
            }
            assertEquals(byPost + 1, metadata);
            assertEquals(metadata + 1, skip);
            assertEquals(skip + 1, clear);

            Preference row = downloads.getPreference(skip);
            assertTrue(row instanceof SwitchPreference);
            assertEquals("Skip what's already saved", String.valueOf(row.getTitle()));
            assertTrue(String.valueOf(row.getSummary()), String.valueOf(row.getSummary()).contains("30 seconds"));
            assertFalse(((SwitchPreference) row).isChecked());
            assertFalse(Settings.DOWNLOAD_SKIP_SAVED.get());
            assertTrue(PatchFamily.VIDEO_DOWNLOAD.switches.contains(Settings.DOWNLOAD_SKIP_SAVED));
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DOWNLOAD_SKIP_SAVED.key));

            String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);
            assertTrue(exported.contains(Settings.DOWNLOAD_SKIP_SAVED.key));
            assertFalse("the download history went into a settings file", exported.contains("3100000000000000042"));
            assertFalse("the download history went into a settings file", exported.contains("content://media"));

            Preference clearRow = downloads.getPreference(clear);
            assertEquals("Clear download history", String.valueOf(clearRow.getTitle()));
            assertTrue("Clear download history doesn't act at once",
                    ((HushgramPreferenceFragment.Row) clearRow).actsOnTap());
            assertTrue(clearRow.getOnPreferenceClickListener().onPreferenceClick(clearRow));
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Download history cleared.", ShadowToast.getTextOfLatestToast());
            assertEquals(0, SavesForTests.historyCount(context));
        }

        // Reels alone still save by the media's id, so the rows are there.
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            assertNotNull(page.findPreference(Settings.DOWNLOAD_SKIP_SAVED.key));
            assertNotNull(page.findPreference(HushgramPreferenceFragment.CLEAR_DOWNLOAD_HISTORY));
            assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DOWNLOAD_SKIP_SAVED.key));
        }

        // Voice messages and profile pictures are never remembered, so alone they don't offer it.
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VOICE_MESSAGE, PatchFamily.PROFILE_PICTURE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = pageIn(controller);
            assertNotNull(section(page.getPreferenceScreen(), "Downloads"));
            assertNull(page.findPreference(Settings.DOWNLOAD_SKIP_SAVED.key));
            assertNull(page.findPreference(HushgramPreferenceFragment.CLEAR_DOWNLOAD_HISTORY));
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
            assertEquals("Download file name", String.valueOf(name.getTitle()));
            assertTrue(String.valueOf(name.getDialogMessage()).contains("{shortcode}"));
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

            assertTrue(ok.onPreferenceChange(name, "{shortcode}"));
            name.setText("{shortcode}");
            ShadowLooper.idleMainLooper();
            assertEquals("{shortcode}", Settings.FILENAME_TEMPLATE.savedValue());
            assertEquals("Videos and photos use " + L10n.isolate("{shortcode}")
                    + ". Unknown codes are left out.", name.getSummary().toString());
        }
    }

    /** The dialog's example is the name the save pipeline would give, and Cancel keeps the saved template. */
    @Test
    public void theFileNameExampleIsThePipelinesNameAndCancelKeepsTheTemplate() {
        assertEquals("Example_123456.mp4",
                HushgramPreferenceFragment.FileNameRow.previewName("Example_{video_id}", new java.util.Date(0)));
        assertEquals("eJA.mp4",
                HushgramPreferenceFragment.FileNameRow.previewName("{shortcode}", new java.util.Date(0)));
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
                assertTrue("dialog titles wear HushGram's gradient", ShimmerTitle.wears(title));
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
