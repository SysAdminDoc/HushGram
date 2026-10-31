/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.preference.ListPreference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumSet;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.misc.EmojiChoice;
import app.hushgram.extension.instagram.misc.EmojiFonts;
import app.hushgram.extension.instagram.misc.EmojiFontsForTests;
import app.hushgram.extension.instagram.misc.FontsForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Emoji style's switch: under Layout after the bottom space, off to start, off while paused, applied on
 * restart. And its Emoji pack row (#166): a pack or file is chosen only once its font is kept.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class EmojiStyleSettingsTest {
    private static final Uri DOCUMENT = Uri.parse("content://test-documents/emoji.ttf");
    private static final byte[] COLOR = EmojiFontsForTests.font("cmap", "CBDT", "CBLC");
    private static final byte[] PLAIN = EmojiFontsForTests.font("cmap", "glyf");

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.NOTO_EMOJI.resetToDefault();
        Settings.EMOJI_FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        FontsForTests.reset();
        EmojiFontsForTests.reset();
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.NOTO_EMOJI.resetToDefault();
        Settings.EMOJI_FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        FontsForTests.reset();
        EmojiFontsForTests.reset();
        EmojiFontsForTests.removeKept();
    }
    private ListPreference emojiRow() {
        return (ListPreference) page.getPreferenceScreen().findPreference(Settings.EMOJI_FONT.key);
    }
    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoEmojiSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE));
        assertNull(page.getPreferenceScreen().findPreference(Settings.NOTO_EMOJI.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.REMOVE_BOTTOM_SPACE.key));
    }
    /** Without Remove the empty space at the bottom, Layout still opens for Emoji style alone. */
    @Test public void emojiAloneStillGetsLayout() throws Exception {
        open(EnumSet.of(PatchFamily.EMOJI_STYLE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.NOTO_EMOJI.key);
        assertNotNull(row);
        assertEquals("Layout", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.REMOVE_BOTTOM_SPACE.key));
    }
    @Test public void emojiSwitchStartsOffUnderLayoutPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE, PatchFamily.EMOJI_STYLE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.NOTO_EMOJI.key);
        assertNotNull(row);
        assertEquals("Google's emoji everywhere", row.getTitle().toString());
        assertEquals("Shows every emoji in Google's style instead of your phone's own. Restart Instagram to see the "
                + "change.", row.getSummary().toString());
        PreferenceGroup layout = row.getParent();
        assertEquals("Layout", layout.getTitle().toString());
        String[] keys = new String[layout.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = layout.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.REMOVE_BOTTOM_SPACE.key, Settings.NOTO_EMOJI.key, Settings.EMOJI_FONT.key, LayoutCategory.PURE_BLACK_HINT_ROW),
                Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.NOTO_EMOJI.get());
        assertTrue("text already drawn keeps its style until a restart", Settings.NOTO_EMOJI.rebootApp);
        assertEquals(java.util.Collections.singletonList(Settings.NOTO_EMOJI), PatchFamily.EMOJI_STYLE.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.NOTO_EMOJI.key));

        Settings.NOTO_EMOJI.save(true);
        assertTrue(Settings.NOTO_EMOJI.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.NOTO_EMOJI.get());
        assertTrue(Settings.NOTO_EMOJI.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.NOTO_EMOJI.get());
    }

    /** The Emoji pack row lists the phone's, the three packs and a file, starts on the phone's and is backed up. */
    @Test public void theEmojiPackRowStartsOnThePhonesEmoji() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE));
        assertNull(emojiRow());
        controller.close();
        controller = null;

        open(EnumSet.of(PatchFamily.EMOJI_STYLE));
        ListPreference row = emojiRow();
        assertNotNull(row);
        assertEquals("Layout", row.getParent().getTitle().toString());
        assertEquals("Emoji pack", row.getTitle().toString());
        String[] entries = new String[row.getEntries().length];
        for (int i = 0; i < entries.length; i++) entries[i] = row.getEntries()[i].toString();
        assertArrayEquals(new String[] {"Your phone's emoji", "Twemoji", "OpenMoji", "Blobmoji", "An emoji font file"}, entries);
        assertEquals(EmojiChoice.PHONE.name(), row.getValue());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Your phone's emoji, or Google's"));
        assertEquals(EmojiChoice.PHONE, Settings.EMOJI_FONT.defaultValue);
        assertTrue("the font is read once per process", Settings.EMOJI_FONT.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.EMOJI_FONT.key));
        assertTrue(PatchFamily.EMOJI_STYLE.choices().contains(Settings.EMOJI_FONT));
        assertTrue("the phone's is chosen right away", row.getOnPreferenceChangeListener().onPreferenceChange(row, EmojiChoice.PHONE.name()));
    }

    /** A pack that can't be downloaded changes nothing and says so. One that downloads is kept and chosen, and isn't downloaded twice. */
    @Test public void aPackIsChosenOnlyOnceItsKept() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.EMOJI_STYLE));
        ListPreference row = emojiRow();

        EmojiFontsForTests.unreachable();
        int before = ShadowToast.shownToastCount();
        assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, EmojiChoice.OPENMOJI.name()));
        finish();
        assertEquals("one toast as it starts, one when it ends", before + 2, ShadowToast.shownToastCount());
        assertEquals("Couldn't download the emoji. Try again.", ShadowToast.getTextOfLatestToast());
        assertEquals(EmojiChoice.PHONE, Settings.EMOJI_FONT.savedValue());
        assertEquals(EmojiChoice.PHONE.name(), row.getValue());
        assertFalse(EmojiFontsForTests.kept().exists());
        assertFalse(EmojiFonts.restartPending());

        EmojiFontsForTests.serve(PLAIN);
        row.getOnPreferenceChangeListener().onPreferenceChange(row, EmojiChoice.OPENMOJI.name());
        finish();
        assertEquals("not a color emoji font", "Couldn't download the emoji. Try again.", ShadowToast.getTextOfLatestToast());
        assertEquals(EmojiChoice.PHONE, Settings.EMOJI_FONT.savedValue());
        assertFalse(EmojiFontsForTests.kept().exists());

        EmojiFontsForTests.serve(COLOR);
        assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, EmojiChoice.OPENMOJI.name()));
        finish();
        assertEquals("Emoji saved. Restart Instagram to see them.", ShadowToast.getTextOfLatestToast());
        assertEquals(EmojiChoice.OPENMOJI, Settings.EMOJI_FONT.savedValue());
        assertEquals(EmojiChoice.OPENMOJI.name(), row.getValue());
        assertArrayEquals(COLOR, Files.readAllBytes(EmojiFontsForTests.kept().toPath()));
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("OpenMoji"));
        assertTrue(EmojiFonts.restartPending());

        int toasts = ShadowToast.shownToastCount();
        EmojiFontsForTests.unreachable();
        assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, EmojiChoice.OPENMOJI.name()));
        finish();
        assertEquals("the pack kept isn't downloaded again", toasts, ShadowToast.shownToastCount());
        assertEquals(EmojiChoice.OPENMOJI, Settings.EMOJI_FONT.savedValue());
    }

    /** A file opens the picker and is chosen only when it has color emoji. */
    @Test public void aFileIsChosenOnlyWithColorEmoji() throws Exception {
        FontsForTests.loadEveryFile(Typeface.SERIF);
        open(EnumSet.of(PatchFamily.EMOJI_STYLE));
        ListPreference row = emojiRow();

        assertFalse(row.getOnPreferenceChangeListener().onPreferenceChange(row, EmojiChoice.FILE.name()));
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(picked);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked.intent.getAction());
        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(PLAIN));
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
        finish();
        assertEquals("No color emoji in that file. Nothing changed.", ShadowToast.getTextOfLatestToast());
        assertEquals(EmojiChoice.PHONE, Settings.EMOJI_FONT.savedValue());
        assertFalse(EmojiFontsForTests.kept().exists());

        row.getOnPreferenceChangeListener().onPreferenceChange(row, EmojiChoice.FILE.name());
        picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT, new ByteArrayInputStream(COLOR));
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
        finish();
        assertEquals("Emoji saved. Restart Instagram to see them.", ShadowToast.getTextOfLatestToast());
        assertEquals(EmojiChoice.FILE, Settings.EMOJI_FONT.savedValue());
        assertEquals(EmojiChoice.FILE.name(), row.getValue());
        assertArrayEquals(COLOR, Files.readAllBytes(EmojiFontsForTests.kept().toPath()));
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("emoji font file you picked"));
    }
}
