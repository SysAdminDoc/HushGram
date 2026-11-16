/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.widget.ListView;

import java.util.Arrays;
import java.util.Collections;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Items in the three-dot menus (#697, #707): a Feed row of its own, every item ticked to start, a
 * change saved at once by name, Show all putting everything back, and kept in a backup.
 */
@RunWith(RobolectricTestRunner.class)
// Tall enough that the list draws all eight items, since a list only ticks the rows it has drawn.
@Config(sdk = {28, 37}, qualifiers = "w1080dp-h1920dp")
@SuppressWarnings("deprecation")
public class MenuItemsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDDEN_MENU_ITEMS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDDEN_MENU_ITEMS.resetToDefault();
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

    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }

    private Preference row() {
        return page.getPreferenceScreen().findPreference(Settings.HIDDEN_MENU_ITEMS.key);
    }

    private ListView openList() {
        Preference row = row();
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowLooper.idleMainLooper();
        return ShadowAlertDialog.getLatestAlertDialog().getListView();
    }

    @Test public void missingPatchHasNoRow() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SAVE));
        assertNull(row());
    }

    /** The row alone makes the Feed section, comes after Save in the post menu, and lists every item ticked. */
    @Test public void everyItemStartsTickedUnderFeed() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SAVE, PatchFamily.MENU_ITEMS));
        Preference row = row();
        assertNotNull(row);
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        assertEquals("Items in the three-dot menus", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Every item shows."));
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.HIDDEN_MENU_ITEMS.key);
        assertEquals(Arrays.toString(keys), Settings.HIDE_FEED_LIKE_BUTTON.key, keys[at - 1]);
        assertEquals(Collections.singletonList(Settings.HIDDEN_MENU_ITEMS), PatchFamily.MENU_ITEMS.choices());
        assertEquals("Three-dot menu items", PatchFamily.MENU_ITEMS.patchName);
        assertEquals("", Settings.HIDDEN_MENU_ITEMS.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDDEN_MENU_ITEMS.key));

        ListView list = openList();
        String[] expected = {"Report", "Mute", "Unfollow", "Not interested", "Interested", "Why you're seeing this post",
                "Playback speed", "Playback"};
        assertEquals(expected.length, list.getCount());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], list.getItemAtPosition(i).toString());
            assertTrue("item " + i + " ticked", list.isItemChecked(i));
        }
        assertEquals("Show all", ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
    }

    @Test public void theRowAloneMakesTheFeedSection() throws Exception {
        open(EnumSet.of(PatchFamily.MENU_ITEMS));
        assertEquals("Feed", row().getParent().getTitle().toString());
    }

    /** Unticking saves at once under the item's name, the summary names it, and ticking it again puts it back. */
    @Test public void aChangeSavesAtOnceByName() throws Exception {
        open(EnumSet.of(PatchFamily.MENU_ITEMS));
        ListView list = openList();
        tap(list, 1);
        assertEquals("MUTE", Settings.HIDDEN_MENU_ITEMS.get());
        tap(list, 0);
        assertEquals("REPORT,MUTE", Settings.HIDDEN_MENU_ITEMS.get());
        assertEquals("Left off the three-dot menus: " + L10n.isolate("Report") + ", " + L10n.isolate("Mute") + ".",
                row().getSummary().toString());
        tap(list, 1);
        tap(list, 0);
        assertEquals("", Settings.HIDDEN_MENU_ITEMS.get());
        assertTrue(row().getSummary().toString().startsWith("Every item shows."));
    }

    /** Show all ticks every item and clears the choice without closing the list. */
    @Test public void showAllPutsEveryItemBack() throws Exception {
        Settings.HIDDEN_MENU_ITEMS.save("WHY,PLAYBACK");
        open(EnumSet.of(PatchFamily.MENU_ITEMS));
        ListView list = openList();
        assertFalse(list.isItemChecked(5));
        assertFalse(list.isItemChecked(7));
        assertTrue(list.isItemChecked(0));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("", Settings.HIDDEN_MENU_ITEMS.get());
        for (int i = 0; i < list.getCount(); i++) assertTrue("item " + i + " ticked", list.isItemChecked(i));
        assertTrue(dialog.isShowing());
    }

    /** Pause answers nothing left out without touching the saved choice. */
    @Test public void pauseKeepsTheChoice() throws Exception {
        Settings.HIDDEN_MENU_ITEMS.save("REPORT");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("", Settings.HIDDEN_MENU_ITEMS.get());
        assertEquals("REPORT", Settings.HIDDEN_MENU_ITEMS.savedValue());
    }
}
