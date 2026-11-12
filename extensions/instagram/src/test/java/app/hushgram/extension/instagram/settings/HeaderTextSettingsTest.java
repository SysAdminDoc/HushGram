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
import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;

import org.junit.After;
import org.junit.Before;
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

import app.hushgram.extension.instagram.misc.HeaderPicture;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Home header text (#454): the text row and the two picture rows follow the launch screen in Layout,
 * start on Instagram's logo, save what's typed as one short line, and the remove row takes a picked
 * picture away. The words are backed up, the picture isn't.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class HeaderTextSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        Settings.HEADER_TEXT.resetToDefault();
        Settings.HEADER_PICTURE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.HEADER_TEXT.resetToDefault();
        Settings.HEADER_PICTURE.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        HeaderPicture.forget();
        File copy = copy();
        if (copy.exists()) assertTrue(copy.delete());
    }

    private static File copy() {
        return HeaderPicture.file(RuntimeEnvironment.getApplication());
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private Preference find(String key) {
        return page.getPreferenceScreen().findPreference(key);
    }

    /** [text] without the direction marks L10n.isolate puts around a name. */
    private static String bare(CharSequence text) {
        StringBuilder kept = new StringBuilder();
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c < 0x2066 || c > 0x2069) kept.append(c);
        }
        return kept.toString();
    }

    @Test public void aBuildWithoutThePatchHasNoRows() throws Exception {
        open(EnumSet.of(PatchFamily.LAUNCH_SCREEN));
        assertNull(find(Settings.HEADER_TEXT.key));
        assertNull(find(HushgramPreferenceFragment.HEADER_PICTURE_KEY));
        assertNull(find(HushgramPreferenceFragment.REMOVE_HEADER_PICTURE_KEY));
    }

    @Test public void theRowsFollowTheLaunchScreenAndStartOnInstagramsLogo() throws Exception {
        open(EnumSet.of(PatchFamily.LAUNCH_SCREEN, PatchFamily.HEADER_TEXT, PatchFamily.APP_ICON));
        Preference text = find(Settings.HEADER_TEXT.key);
        assertTrue(text instanceof HushgramPreferenceFragment.HeaderTextRow);
        PreferenceGroup layout = (PreferenceGroup) text.getParent();
        assertEquals("Layout", layout.getTitle().toString());
        int launch = indexIn(layout, find(Settings.LAUNCH_SCREEN.key));
        assertTrue(launch >= 0);
        assertEquals("right after the launch screen", launch + 1, indexIn(layout, text));
        Preference picture = find(HushgramPreferenceFragment.HEADER_PICTURE_KEY);
        Preference remove = find(HushgramPreferenceFragment.REMOVE_HEADER_PICTURE_KEY);
        assertEquals(launch + 2, indexIn(layout, picture));
        assertEquals(launch + 3, indexIn(layout, remove));

        assertEquals("Home header text", text.getTitle().toString());
        assertEquals("Instagram's logo stays at the top of Home.", text.getSummary().toString());
        assertEquals("Home header picture", picture.getTitle().toString());
        assertEquals("Pick a picture to show at the top of Home, in place of Instagram's logo or your text.",
                picture.getSummary().toString());
        assertEquals("Remove the header picture", remove.getTitle().toString());
        assertFalse("nothing to remove yet", remove.isEnabled());

        assertTrue("the words are backed up", ConfigurationBackup.eligible().containsKey(Settings.HEADER_TEXT.key));
        assertFalse("the picture's copy stays on this phone",
                ConfigurationBackup.eligible().containsKey(Settings.HEADER_PICTURE.key));
    }

    @Test public void whatsTypedIsSavedAsOneShortLine() throws Exception {
        open(EnumSet.of(PatchFamily.HEADER_TEXT));
        HushgramPreferenceFragment.HeaderTextRow row =
                (HushgramPreferenceFragment.HeaderTextRow) find(Settings.HEADER_TEXT.key);

        save(row, "  My \t  feed  ");
        assertEquals("My feed", Settings.HEADER_TEXT.savedValue());
        assertEquals("My feed", row.getText());
        assertEquals("The top of Home says My feed.", bare(row.getSummary()));

        save(row, "abcdefghij0123456789ABCDEFGHIJ-cut");
        assertEquals("abcdefghij0123456789ABCDEFGHIJ", Settings.HEADER_TEXT.savedValue());

        save(row, "   ");
        assertEquals("blank keeps the logo", "", Settings.HEADER_TEXT.savedValue());
        assertEquals("Instagram's logo stays at the top of Home.", row.getSummary().toString());
    }

    @Test public void aPickedPictureShowsItsNameAndTheRemoveRowTakesItAway() throws Exception {
        Settings.HEADER_PICTURE.save("cat.png");
        Files.write(copy().toPath(), "kept".getBytes(StandardCharsets.UTF_8));
        open(EnumSet.of(PatchFamily.HEADER_TEXT));
        Preference picture = find(HushgramPreferenceFragment.HEADER_PICTURE_KEY);
        Preference remove = find(HushgramPreferenceFragment.REMOVE_HEADER_PICTURE_KEY);
        assertEquals("The top of Home shows cat.png. Tap to pick another.", bare(picture.getSummary()));
        assertTrue(remove.isEnabled());

        assertNotNull(remove.getOnPreferenceClickListener());
        remove.getOnPreferenceClickListener().onPreferenceClick(remove);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();

        assertEquals("", Settings.HEADER_PICTURE.savedValue());
        assertFalse("the copy is gone too", copy().exists());
        assertFalse(remove.isEnabled());
        assertEquals("Pick a picture to show at the top of Home, in place of Instagram's logo or your text.",
                picture.getSummary().toString());
        assertEquals("Header picture removed.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void eachRefusalSaysWhatWentWrong() {
        assertEquals("That isn't a picture.",
                HushgramPreferenceFragment.headerPictureRefusal(HeaderPicture.Refusal.NOT_A_PICTURE));
        assertEquals("That picture is over 20 MB.",
                HushgramPreferenceFragment.headerPictureRefusal(HeaderPicture.Refusal.TOO_LARGE));
        assertEquals("Couldn't save the picture.",
                HushgramPreferenceFragment.headerPictureRefusal(HeaderPicture.Refusal.NOT_SAVED));
        assertEquals("Couldn't read that picture.",
                HushgramPreferenceFragment.headerPictureRefusal(HeaderPicture.Refusal.UNREADABLE));
    }

    /** Types [typed] into the row's editor and taps Save. */
    private static void save(HushgramPreferenceFragment.HeaderTextRow row, String typed) {
        row.showDialog(null);
        AlertDialog dialog = (AlertDialog) row.getDialog();
        row.getEditText().setText(typed);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse("nothing typed is refused", dialog.isShowing());
    }

    private static int indexIn(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == row) return i;
        }
        return -1;
    }
}
