/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.After;
import org.junit.Before;
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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import app.hushgram.extension.instagram.misc.FontsForTests;
import app.hushgram.extension.instagram.stories.StoryEmoji;
import app.hushgram.extension.instagram.stories.StoryFonts;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;

/** Story text fonts: a Stories switch after Upload quality that starts off, with the row listing the picked files and the emoji switch under it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class StoryFontsSettingsTest {
    private static final Uri DOCUMENT = Uri.parse("content://test-documents/story-font.ttf");
    private static final byte[] FONT = {0, 1, 0, 0, 0, 12, 0, (byte) 128};
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final SettingsLockTest.Phone phone = new SettingsLockTest.Phone();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.STORY_FONTS.resetToDefault();
        Settings.STORY_EMOJI.resetToDefault();
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        SettingsLock.clocks = phone;
        resetFontFiles();
        FontsForTests.reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.STORY_FONTS.resetToDefault();
        Settings.STORY_EMOJI.resetToDefault();
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        SettingsLock.clocks = SettingsLock.PHONE;
        resetFontFiles();
        FontsForTests.reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void theSwitchIsUnderStoriesAfterUploadQualityAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_UPLOAD_QUALITY, PatchFamily.STORY_FONTS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STORY_FONTS.key);
        assertNotNull(row);
        assertEquals("Your fonts in story text", row.getTitle().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.STORY_FONTS.get());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.STORY_FONTS.key);
        assertEquals(Arrays.toString(keys), Settings.UPLOAD_STORIES_AT_FULL_QUALITY.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), HushgramPreferenceFragment.STORY_FONTS_ROW, keys[at + 1]);
        Preference list = stories.getPreference(at + 1);
        assertEquals("Your story fonts", list.getTitle().toString());
        assertEquals("Story text fonts", PatchFamily.STORY_FONTS.patchName);
        assertEquals(Arrays.asList(Settings.STORY_FONTS, Settings.STORY_EMOJI), PatchFamily.STORY_FONTS.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_FONTS.key));
    }
    @Test public void theEmojiSwitchFollowsTheFontsAndAsksForAFileFirst() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_UPLOAD_QUALITY, PatchFamily.STORY_FONTS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.STORY_EMOJI.key);
        assertNotNull(row);
        assertEquals("Emoji font in story text", row.getTitle().toString());
        assertFalse(row.isChecked());
        PreferenceGroup stories = row.getParent();
        String[] keys = new String[stories.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = stories.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.STORY_EMOJI.key);
        assertEquals(Arrays.toString(keys), HushgramPreferenceFragment.STORY_FONTS_ROW, keys[at - 1]);
        assertFalse("turning it on waits for a file", row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
        assertFalse(Settings.STORY_EMOJI.get());
        Intent picker = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
        assertTrue("turning it off needs nothing", row.getOnPreferenceChangeListener().onPreferenceChange(row, false));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_EMOJI.key));
    }
    @Test public void missingPatchHasNoRows() throws Exception {
        open(EnumSet.of(PatchFamily.STORY_UPLOAD_QUALITY));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_FONTS.key));
        assertNull(page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.STORY_FONTS_ROW));
        assertNull(page.getPreferenceScreen().findPreference(Settings.STORY_EMOJI.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.key));
    }

    @Test public void aStoryFontCloseFailureAddsNothingAndKeepsExistingFonts() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        String existing = StoryFonts.add(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(FONT), "Existing.ttf");
        assertNotNull(existing);
        open(EnumSet.of(PatchFamily.STORY_FONTS));
        receive(pickStoryFont(), failingClose(font(2)));
        finish();
        assertEquals(List.of(existing), StoryFonts.names(RuntimeEnvironment.getApplication()));
        assertFalse(Settings.STORY_FONTS.savedValue());
        assertEquals("That file isn't a font HushGram can use. Nothing was added.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void anEmojiFontCloseFailureKeepsThePreviousFile() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        assertTrue(StoryEmoji.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(FONT)));
        open(EnumSet.of(PatchFamily.STORY_FONTS));
        receive(pickEmojiFont(), failingClose(font(2)));
        finish();
        assertArrayEquals(FONT, Files.readAllBytes(emojiFile().toPath()));
        assertFalse(Settings.STORY_EMOJI.savedValue());
        assertEquals("That file isn't a font HushGram can use. Story emoji haven't changed.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aStoryFontResultAfterTheSettingsRelockAddsNothing() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.STORY_FONTS));
        openTimedLock();
        ShadowActivity.IntentForResult picked = pickStoryFont();
        phone.pass(SettingsLock.OPEN_MS);
        receive(picked, new ByteArrayInputStream(FONT));
        finish();
        assertTrue(StoryFonts.names(RuntimeEnvironment.getApplication()).isEmpty());
        assertFalse(Settings.STORY_FONTS.savedValue());
        assertEquals("These settings are locked. Ask to unlock first.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void anEmojiFontResultAfterTheSettingsRelockChangesNothing() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.STORY_FONTS));
        openTimedLock();
        ShadowActivity.IntentForResult picked = pickEmojiFont();
        phone.pass(SettingsLock.OPEN_MS);
        receive(picked, new ByteArrayInputStream(FONT));
        finish();
        assertFalse(emojiFile().exists());
        assertFalse(Settings.STORY_EMOJI.savedValue());
        assertEquals("These settings are locked. Ask to unlock first.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aRefusedStoryFontReceiptRestoresTheDisabledChoice() throws Exception {
        storyReceipt(false);
    }

    @Test public void aReleasedStoryFontReceiptKeepsTheEnabledChoice() throws Exception {
        storyReceipt(true);
    }

    private void storyReceipt(boolean accept) throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        Context context = RuntimeEnvironment.getApplication();
        String existing = StoryFonts.add(context, new ByteArrayInputStream(FONT), "Existing.ttf");
        File directory = ReflectionHelpers.callStaticMethod(StoryFonts.class, "directory", ClassParameter.from(Context.class, context));
        open(EnumSet.of(PatchFamily.STORY_FONTS));
        try (FontSettingsTest.JournalAfterCommit receipt = new FontSettingsTest.JournalAfterCommit(
                Settings.STORY_FONTS, true, directory, !accept)) {
            receive(pickStoryFont(), new ByteArrayInputStream(font(2)));
            finish();
            assertTrue("the barrier follows the committed enable choice", receipt.observed && receipt.deleteDenied);
            assertEquals(Boolean.TRUE, receipt.committed);
            assertEquals(accept, Settings.STORY_FONTS.savedValue());
            assertEquals(accept, Setting.preferences.getBoolean(Settings.STORY_FONTS.key, false));
            assertEquals(accept, ((SwitchPreference) page.findPreference(Settings.STORY_FONTS.key)).isChecked());
            assertArrayEquals(FONT, Files.readAllBytes(new File(directory, existing).toPath()));
            File[] added = directory.listFiles((parent, name) -> !name.endsWith(".tmp") && !name.equals(existing));
            assertNotNull(added);
            assertEquals(accept ? 1 : 0, added.length);
            if (!accept) assertEquals("The setting couldn't finish updating. Its saved value is shown.", ShadowToast.getTextOfLatestToast());
            else assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Added "));
        }
    }

    @Test public void aRefusedEmojiReceiptRestoresTheDisabledChoice() throws Exception {
        emojiReceipt(false);
    }

    @Test public void aReleasedEmojiReceiptKeepsTheEnabledChoice() throws Exception {
        emojiReceipt(true);
    }

    private void emojiReceipt(boolean accept) throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        assertTrue(StoryEmoji.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(FONT)));
        open(EnumSet.of(PatchFamily.STORY_FONTS));
        try (FontSettingsTest.JournalAfterCommit receipt = new FontSettingsTest.JournalAfterCommit(
                Settings.STORY_EMOJI, true, emojiFile().getParentFile(), !accept)) {
            receive(pickEmojiFont(), new ByteArrayInputStream(font(2)));
            finish();
            assertTrue("the barrier follows the committed enable choice", receipt.observed && receipt.deleteDenied);
            assertEquals(Boolean.TRUE, receipt.committed);
            assertEquals(accept, Settings.STORY_EMOJI.savedValue());
            assertEquals(accept, Setting.preferences.getBoolean(Settings.STORY_EMOJI.key, false));
            assertEquals(accept, ((SwitchPreference) page.findPreference(Settings.STORY_EMOJI.key)).isChecked());
            assertArrayEquals(accept ? font(2) : FONT, Files.readAllBytes(emojiFile().toPath()));
            assertEquals(accept ? "Emoji font saved. Emoji in story text draw in it."
                    : "The setting couldn't finish updating. Its saved value is shown.", ShadowToast.getTextOfLatestToast());
        }
    }

    private void openTimedLock() {
        assertTrue(SettingsLock.lock(1));
        assertTrue(SettingsLock.ask());
        phone.pass(3_600_000L);
        page.showLock();
        assertEquals(SettingsLock.State.OPEN, SettingsLock.state());
    }

    private ShadowActivity.IntentForResult pickStoryFont() {
        page.showStoryFonts();
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(list);
        list.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(picked);
        return picked;
    }

    private ShadowActivity.IntentForResult pickEmojiFont() {
        Preference row = page.findPreference(Settings.STORY_EMOJI.key);
        assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(picked);
        return picked;
    }

    private void receive(ShadowActivity.IntentForResult picked, InputStream input) {
        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT, input);
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
    }

    private static InputStream failingClose(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            @Override public void close() throws IOException {
                throw new IOException("injected provider close failure");
            }
        };
    }

    private static byte[] font(int marker) {
        byte[] bytes = Arrays.copyOf(FONT, FONT.length + 1);
        bytes[bytes.length - 1] = (byte) marker;
        return bytes;
    }

    private static File emojiFile() {
        return ReflectionHelpers.callStaticMethod(StoryEmoji.class, "kept",
                ClassParameter.from(Context.class, RuntimeEnvironment.getApplication()));
    }

    private static void resetFontFiles() {
        Context context = RuntimeEnvironment.getApplication();
        File stories = ReflectionHelpers.callStaticMethod(StoryFonts.class, "directory", ClassParameter.from(Context.class, context));
        File[] fonts = stories.listFiles();
        if (fonts != null) for (File file : fonts) if (file.isFile()) file.delete();
        ReflectionHelpers.callStaticMethod(StoryFonts.class, "resetForTests");
        File emoji = emojiFile();
        File[] files = emoji.getParentFile().listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        ReflectionHelpers.callStaticMethod(StoryEmoji.class, "resetForTests");
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}
