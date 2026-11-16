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
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.direct.OnlineHistory;
import app.hushgram.extension.instagram.direct.OnlineHistoryRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Keep online history (#590): a switch, the list of changes and Clear online history, in the
 * Profiles category with the patch in the build. The switch starts off and goes in a settings file,
 * the list never does. Its page searches the changes and opens the profile tapped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class OnlineHistorySettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.ONLINE_HISTORY.resetToDefault();
        OnlineHistory.clear(RuntimeEnvironment.getApplication());
        OnlineHistoryRows.forget();
    }

    @Test public void theSwitchStartsOffBelongsToItsPatchAndIsBackedUp() {
        assertFalse(Settings.ONLINE_HISTORY.get());
        assertTrue(PatchFamily.ONLINE_HISTORY.switches.contains(Settings.ONLINE_HISTORY));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ONLINE_HISTORY);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ONLINE_HISTORY.key));
    }

    @Test public void theRowsAreInProfilesWithThePatchAndGoneWithoutIt() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ONLINE_HISTORY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference toggle = page.findPreference(Settings.ONLINE_HISTORY.key);
            assertTrue(toggle instanceof SwitchPreference);
            assertEquals("Keep online history", String.valueOf(toggle.getTitle()));
            assertFalse(((SwitchPreference) toggle).isChecked());
            Preference list = page.findPreference(HushgramPreferenceFragment.ONLINE_HISTORY_ROW);
            assertNotNull(list);
            assertEquals("Online history", String.valueOf(list.getTitle()));
            assertTrue("it opens the page", list.isSelectable());
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_ONLINE_HISTORY);
            assertNotNull(clear);
            assertEquals("Clear online history", String.valueOf(clear.getTitle()));
            assertTrue(((HushgramPreferenceFragment.Row) clear).actsOnTap());
            for (Preference row : Arrays.asList(toggle, list, clear)) {
                assertEquals(String.valueOf(row.getTitle()), "Profiles",
                        String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
            }
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VOICE_MESSAGE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            assertNull(page.findPreference(Settings.ONLINE_HISTORY.key));
            assertNull(page.findPreference(HushgramPreferenceFragment.ONLINE_HISTORY_ROW));
            assertNull(page.findPreference(HushgramPreferenceFragment.CLEAR_ONLINE_HISTORY));
        }
    }

    @Test public void theRowReadsTheNewestChangesFirstAndNothingWhenEmpty() {
        long now = 10_000_000L;
        assertEquals("Nothing here yet. With Keep online history on, the times people in your messages "
                        + "come online and go offline show up here.",
                HushgramPreferenceFragment.onlineHistorySummary(Collections.emptyList(), now));
        Context context = RuntimeEnvironment.getApplication();
        OnlineHistoryRows.note(context, "1", "ann", true, 0, now - 9 * 60_000L);
        OnlineHistoryRows.note(context, "2", "bo", true, 0, now - 8 * 60_000L);
        OnlineHistoryRows.note(context, "1", "ann", false, now - 4 * 60_000L, now - 3 * 60_000L);
        OnlineHistoryRows.note(context, "3", "", true, 0, now - 60_000L);
        String[] lines = HushgramPreferenceFragment.onlineHistorySummary(OnlineHistory.events(context), now).split("\n");
        assertEquals("the newest three, then how many in all", 4, lines.length);
        assertTrue(lines[0], lines[0].startsWith("Unknown account: Came online, "));
        assertTrue(lines[1], lines[1].startsWith("@ann: Went offline, "));
        assertTrue(lines[2], lines[2].startsWith("@bo: Came online, "));
        assertEquals("Tap to see all 4, or search them.", lines[3]);
    }

    /** A search box over every change, and a tap opens the profile by its username. */
    @Test public void thePageSearchesAndOpensTheProfileTapped() throws Exception {
        long now = System.currentTimeMillis();
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ONLINE_HISTORY);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.ONLINE_HISTORY_ROW);
            OnlineHistoryPage none = OnlineHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue(String.valueOf(none.empty.getText()), String.valueOf(none.empty.getText()).startsWith("Nothing here yet."));
            assertNotNull("an empty page shows a mark over its words", none.empty.getCompoundDrawablesRelative()[1]);
            none.dialog.dismiss();

            OnlineHistoryRows.note(context, "1", "amy", true, 0, now - 5_000L);
            OnlineHistoryRows.note(context, "2", "", true, 0, now - 4_000L);
            OnlineHistoryRows.note(context, "3", "zed", true, 0, now - 3_000L);
            OnlineHistoryPage page = OnlineHistoryPage.open(controller.get(), row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertTrue(page.dialog.isShowing());
            assertEquals("the keyboard pushes the list up rather than covering it",
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    page.dialog.getWindow().getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals(3, page.list.getAdapter().getCount());
            assertEquals("the newest change first", "zed", ((OnlineHistory.Event) page.list.getAdapter().getItem(0)).username);

            android.widget.LinearLayout shown = (android.widget.LinearLayout) page.list.getAdapter().getView(2, null, page.list);
            android.widget.LinearLayout words = (android.widget.LinearLayout) shown.getChildAt(1);
            assertEquals("@amy", String.valueOf(((android.widget.TextView) words.getChildAt(0)).getText()));
            String what = String.valueOf(((android.widget.TextView) words.getChildAt(1)).getText());
            assertTrue(what, what.startsWith("Came online, ") && what.contains("\n"));
            android.widget.LinearLayout unknown = (android.widget.LinearLayout) page.list.getAdapter().getView(1, null, page.list);
            assertEquals("Unknown account",
                    String.valueOf(((android.widget.TextView) ((android.widget.LinearLayout) unknown.getChildAt(1)).getChildAt(0)).getText()));
            assertNull("no username, no profile to open", OnlineHistoryPage.link((OnlineHistory.Event) page.list.getAdapter().getItem(1)));

            page.search.setText("@AMY");
            assertEquals("a search goes by the username, in any case, with or without the at", 1, page.list.getAdapter().getCount());
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
            assertEquals("https://www.instagram.com/zed/", String.valueOf(opened.getData()));
            assertEquals("its own package, so a copy next to Instagram opens itself", context.getPackageName(), opened.getPackage());
            assertFalse(page.dialog.isShowing());
        }
    }

    @Test public void clearEmptiesTheListAtOnceAndSaysSoAndTheExportNeverHoldsIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        OnlineHistoryRows.note(context, "1", "someone", true, 0, now - 2_000L);
        List<OnlineHistory.Event> events = OnlineHistory.events(context);
        assertEquals(1, events.size());
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.ONLINE_HISTORY);
        Settings.ONLINE_HISTORY.save(true);
        String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);
        assertTrue(exported.contains(Settings.ONLINE_HISTORY.key));
        assertFalse("the online history went into a settings file", exported.contains("someone"));

        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            Preference list = page.findPreference(HushgramPreferenceFragment.ONLINE_HISTORY_ROW);
            assertTrue(String.valueOf(list.getSummary()), String.valueOf(list.getSummary()).startsWith("@someone: Came online, "));
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_ONLINE_HISTORY);
            assertTrue(clear.getOnPreferenceClickListener().onPreferenceClick(clear));
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertEquals("Online history cleared.", ShadowToast.getTextOfLatestToast());
            assertEquals(Collections.emptyList(), OnlineHistory.events(context));
            assertTrue(String.valueOf(list.getSummary()).startsWith("Nothing here yet."));
            assertTrue("the switch stays as it is", Settings.ONLINE_HISTORY.get());
        }
        OnlineHistoryRows.forget();
        assertEquals("and the file went with it", Collections.emptyList(), OnlineHistory.events(context));
    }
}
