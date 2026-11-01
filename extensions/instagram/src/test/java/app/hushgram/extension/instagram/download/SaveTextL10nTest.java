/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.L10nTablesForTests;
import app.hushgram.extension.shared.SettingsContextRule;

/**
 * What a save says, drawn under en-XA: every word the catalog draws is bracketed there, so plain
 * English in a toast or the notification is text that never went through it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "en-rXA")
public class SaveTextL10nTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @After
    public void clear() {
        notifications().cancelAll();
    }

    @Test
    public void everyResultAndTheProgressLineComeFromTheCatalog() {
        List<String> said = new ArrayList<>();
        for (Downloader.Status status : Downloader.Status.values()) {
            for (boolean lower : new boolean[] { false, true }) {
                said.add(MediaSave.message(context, status, null, lower));
                said.add(MediaSave.message(context, status, "Movies/Instagram", lower));
            }
        }
        said.add(SaveControl.progressText(5_000_000, 50_000_000));
        said.add(SaveControl.progressText(5_000_000, -1));
        for (String text : said) {
            assertTrue("not from the catalog: " + text, text.startsWith("[") && text.endsWith("]"));
        }
        // The folder is a value, and a value is never drawn accented.
        assertTrue(MediaSave.message(context, Downloader.Status.OK, "Movies/Instagram", false).contains("Movies/Instagram"));
        assertTrue(MediaSave.message(context, Downloader.Status.OK, "Movies/Instagram", true).contains("Movies/Instagram"));
    }

    @Test
    public void theNotificationAndItsChannelComeFromTheCatalog() {
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            Notification shown = null;
            for (Notification notification : Shadows.shadowOf(notifications()).getAllNotifications()) {
                if (SaveControl.CHANNEL.equals(notification.getChannelId())) shown = notification;
            }
            assertNotNull("no save notification", shown);
            String title = String.valueOf(shown.extras.getCharSequence(Notification.EXTRA_TITLE));
            assertTrue(title, title.startsWith("[") && title.endsWith("]"));
            // Cancel comes from the catalog too, so it's in the title's language.
            String cancel = String.valueOf(shown.actions[0].title);
            assertTrue(cancel, cancel.startsWith("[") && cancel.endsWith("]"));

            NotificationChannel channel = notifications().getNotificationChannel(SaveControl.CHANNEL);
            assertTrue(String.valueOf(channel.getName()), String.valueOf(channel.getName()).startsWith("["));
            assertTrue(channel.getDescription(), channel.getDescription().startsWith("["));
        } finally {
            save.end();
        }
    }

    /** The numbers are written the phone's way, in whatever language the sentence comes out in. */
    @Test
    @Config(qualifiers = "de")
    public void progressNumbersAreWrittenThePhonesWay() {
        long mib = 1024L * 1024L;
        String german = L10nTablesForTests.of("de").get("%1$s of %2$s");
        assertEquals(String.format(german, "4,2 MB", "100 MB"), SaveControl.progressText((long) (4.2 * mib), 100 * mib));
    }

    /** A French phone has no French table: an English sentence, French numbers. */
    @Test
    @Config(qualifiers = "fr-rFR")
    public void aPhoneWithNoTableReadsEnglishWithItsOwnNumbers() {
        long mib = 1024L * 1024L;
        assertEquals("4,2 MB of 100 MB", SaveControl.progressText((long) (4.2 * mib), 100 * mib));
        assertEquals("12 MB so far", SaveControl.progressText(12 * mib, -1));
    }

    /** A save's result reaches a Spanish phone in Spanish, with Instagram named in it. */
    @Test
    @Config(qualifiers = "es")
    public void aResultIsSaidInThePhonesLanguage() {
        String lower = L10nTablesForTests.of("es").get("Saved to %1$s in lower quality than on Instagram");
        assertNotNull(lower);
        assertTrue(lower, lower.contains("Instagram"));
        // The folder goes in isolated, so a right-to-left sentence can't reorder it.
        assertEquals(String.format(lower, L10n.isolate("Movies/Instagram")),
                MediaSave.message(context, Downloader.Status.OK, "Movies/Instagram", true));
        assertEquals(L10nTablesForTests.of("es").get("Saved to the gallery"),
                MediaSave.message(context, Downloader.Status.OK, null, false));
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    public void traditionalChineseFormatsSaveResultsAndErrors() {
        assertEquals("已儲存至相簿", MediaSave.message(context, Downloader.Status.OK, null, false));
        assertEquals("已儲存至 " + L10n.isolate("Movies/Instagram"),
                MediaSave.message(context, Downloader.Status.OK, "Movies/Instagram", false));
        assertEquals("目前共 12 MB", SaveControl.progressText(12 * 1024L * 1024L, -1));
        assertEquals("下載失敗，請檢查連線後再試一次。", L10n.t("Download failed. Check your connection and try again."));
    }

    @Test
    @Config(qualifiers = "vi")
    public void vietnameseFormatsSaveResults() {
        assertEquals(L10nTablesForTests.of("vi").get("Saved to the gallery"),
                MediaSave.message(context, Downloader.Status.OK, null, false));
        assertEquals(String.format(L10nTablesForTests.of("vi").get("Saved to %1$s"), L10n.isolate("Movies/Instagram")),
                MediaSave.message(context, Downloader.Status.OK, "Movies/Instagram", false));
    }

    /** A channel named in one language is renamed in the next, keeping what the person set. */
    @Test
    @Config(qualifiers = "de")
    public void aChannelNamedInAnotherLanguageIsRenamed() {
        NotificationChannel old = new NotificationChannel(SaveControl.CHANNEL, "HushGram saves",
                NotificationManager.IMPORTANCE_LOW);
        notifications().createNotificationChannel(old);

        SaveControl.begin(context, false).end();
        NotificationChannel renamed = notifications().getNotificationChannel(SaveControl.CHANNEL);
        assertEquals(L10nTablesForTests.of("de").get("HushGram saves"), String.valueOf(renamed.getName()));
        assertEquals(NotificationManager.IMPORTANCE_LOW, renamed.getImportance());
    }

    private NotificationManager notifications() {
        return context.getSystemService(NotificationManager.class);
    }
}
