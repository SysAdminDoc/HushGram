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
import android.widget.LinearLayout;
import android.widget.TextView;

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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import app.hushgram.extension.instagram.profile.SentRequests;
import app.hushgram.extension.instagram.profile.SentRequestsRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Sent follow requests (#690): a switch, the list and Clear, in the Profiles category with the patch
 * in the build. The switch starts off and a settings file carries it but never the list. Its page
 * searches the requests, opens the profile tapped, where Instagram's own button cancels, and takes
 * one off the list on a long press, which the settings lock holds like Clear.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SentRequestsSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final SettingsLockTest.Phone phone = new SettingsLockTest.Phone();

    @Before public void prepare() {
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        SettingsLock.clocks = phone;
        SentRequestsRows.clear();
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ConfigurationBackup.forgetUndo();
        PatchFamily.inBuildForTests = null;
        Settings.SENT_FOLLOW_REQUESTS.resetToDefault();
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        SettingsLock.clocks = SettingsLock.PHONE;
        SentRequestsRows.clear();
        SentRequestsPage.forgetPictures();
    }

    private static void idle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    @Test public void theSwitchStartsOffIsItsPatchsAndIsBackedUpWithoutTheList() throws Exception {
        assertFalse(Settings.SENT_FOLLOW_REQUESTS.get());
        assertTrue(PatchFamily.SENT_FOLLOW_REQUESTS.switches.contains(Settings.SENT_FOLLOW_REQUESTS));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SENT_FOLLOW_REQUESTS);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SENT_FOLLOW_REQUESTS.key));
        SentRequestsRows.request("me", "1", "someone.private", "A Name", "", 1_000L);
        Settings.SENT_FOLLOW_REQUESTS.save(true);
        String exported = new String(ConfigurationBackup.export(), StandardCharsets.UTF_8);
        assertTrue(exported.contains(Settings.SENT_FOLLOW_REQUESTS.key));
        assertFalse("the list went into a settings file", exported.contains("someone.private"));
    }

    @Test public void theRowsAreInProfilesWithThePatchAndGoneWithoutIt() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SENT_FOLLOW_REQUESTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            Preference toggle = page.findPreference(Settings.SENT_FOLLOW_REQUESTS.key);
            assertTrue(toggle instanceof SwitchPreference);
            assertEquals("Sent follow requests", String.valueOf(toggle.getTitle()));
            assertFalse(((SwitchPreference) toggle).isChecked());
            Preference list = page.findPreference(HushgramPreferenceFragment.SENT_REQUESTS_ROW);
            assertNotNull(list);
            assertEquals("Your sent requests", String.valueOf(list.getTitle()));
            assertTrue("it opens the page", list.isSelectable());
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_SENT_REQUESTS);
            assertNotNull(clear);
            assertEquals("Clear sent requests", String.valueOf(clear.getTitle()));
            assertTrue(((HushgramPreferenceFragment.Row) clear).actsOnTap());
            for (Preference row : Arrays.asList(toggle, list, clear)) {
                assertEquals(String.valueOf(row.getTitle()), "Profiles",
                        String.valueOf(StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row).getTitle()));
            }
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.VOICE_MESSAGE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            assertNull(page.findPreference(Settings.SENT_FOLLOW_REQUESTS.key));
            assertNull(page.findPreference(HushgramPreferenceFragment.SENT_REQUESTS_ROW));
            assertNull(page.findPreference(HushgramPreferenceFragment.CLEAR_SENT_REQUESTS));
        }
    }

    @Test public void theRowNamesTheNewestThreeAndSaysHowOneGetsThereWhenEmpty() {
        assertEquals("No requests waiting. With Sent follow requests on, a request you send, or one whose follow "
                        + "button you see, shows up here.",
                HushgramPreferenceFragment.sentRequestsSummary(Collections.emptyList()));
        SentRequestsRows.request("me", "1", "ann", "", "", 1_000L);
        SentRequestsRows.request("me", "2", "bo", "", "", 2_000L);
        SentRequestsRows.request("me", "3", "cy", "", "", 3_000L);
        SentRequestsRows.request("me", "4", "dee", "", "", 4_000L);
        SentRequestsRows.request("other", "5", "eve", "", "", 5_000L);
        assertEquals("@dee\n@cy\n@bo\nTap to see all 4, or search them.",
                HushgramPreferenceFragment.sentRequestsSummary(SentRequests.all("me")));
        assertEquals("@ann\nTap to see all 1, or search them.",
                HushgramPreferenceFragment.sentRequestsSummary(SentRequests.all("me").subList(3, 4)));
    }

    /** The page: a search box over the account's requests, and a tap opens that profile. */
    @Test public void thePageSearchesAndOpensTheProfileTapped() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SENT_FOLLOW_REQUESTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            Preference row = settings.findPreference(HushgramPreferenceFragment.SENT_REQUESTS_ROW);
            SentRequestsPage none = SentRequestsPage.open(controller.get(), row);
            idle();
            assertTrue(String.valueOf(none.empty.getText()), String.valueOf(none.empty.getText()).startsWith("No requests waiting."));
            assertNotNull("an empty page shows a mark over its words", none.empty.getCompoundDrawablesRelative()[1]);
            none.dialog.dismiss();

            SentRequestsRows.request("other", "9", "kim", "", "", 500L);
            SentRequestsRows.request("me", "1", "amy", "Amy Pond", "", 1_000L);
            SentRequestsRows.request("me", "2", "zed", "", "", 2_000L);
            SentRequestsPage page = SentRequestsPage.open(controller.get(), row);
            idle();
            assertTrue(page.dialog.isShowing());
            assertEquals("the keyboard pushes the list up rather than covering it",
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    page.dialog.getWindow().getAttributes().softInputMode & android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals("only the account the buttons were seen for", 2, page.list.getAdapter().getCount());
            assertEquals("the newest first", "zed", ((SentRequests.Request) page.list.getAdapter().getItem(0)).username);

            LinearLayout zed = (LinearLayout) page.list.getAdapter().getView(0, null, page.list);
            LinearLayout amy = (LinearLayout) page.list.getAdapter().getView(1, null, page.list);
            LinearLayout words = (LinearLayout) amy.getChildAt(1);
            assertEquals("@amy", String.valueOf(((TextView) words.getChildAt(0)).getText()));
            assertEquals("Amy Pond", String.valueOf(((TextView) words.getChildAt(1)).getText()));
            assertEquals(View.VISIBLE, words.getChildAt(1).getVisibility());
            assertEquals("no name, no second line", View.GONE, ((LinearLayout) zed.getChildAt(1)).getChildAt(1).getVisibility());
            assertEquals(SentRequestsPage.PICTURE_DP * context.getResources().getDisplayMetrics().density,
                    amy.getChildAt(0).getLayoutParams().width, 1f);

            page.search.setText("@AMY");
            assertEquals("a search goes by the username, in any case, with or without the at", 1, page.list.getAdapter().getCount());
            page.search.setText("pond");
            assertEquals("and by the name", 1, page.list.getAdapter().getCount());
            page.search.setText("nobody");
            assertEquals(0, page.list.getAdapter().getCount());
            assertEquals("Nothing matches.", String.valueOf(page.empty.getText()));
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

    /** A long press copies the username or takes the account off the list, saying so; the request stays sent. */
    @Test public void aLongPressCopiesOrTakesItOffTheListAndSaysSo() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SentRequestsRows.request("me", "1", "amy", "", "", 1_000L);
        SentRequestsRows.request("me", "2", "zed", "", "", 2_000L);
        SentRequestsRows.request("me", "3", "kim", "", "", 3_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SENT_FOLLOW_REQUESTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            idle();
            Preference row = settings.findPreference(HushgramPreferenceFragment.SENT_REQUESTS_ROW);
            assertEquals("@kim\n@zed\n@amy\nTap to see all 3, or search them.", String.valueOf(row.getSummary()));
            SentRequestsPage page = SentRequestsPage.open(controller.get(), row);
            idle();

            android.view.Menu menu = new android.widget.PopupMenu(context, new View(context)).getMenu();
            SentRequestsPage.fill(menu);
            assertEquals(2, menu.size());
            assertEquals("Copy username", String.valueOf(menu.getItem(0).getTitle()));
            assertEquals("Remove from the list", String.valueOf(menu.getItem(1).getTitle()));

            SentRequests.Request zed = (SentRequests.Request) page.list.getAdapter().getItem(1);
            assertTrue(page.pick(SentRequestsPage.COPY_USERNAME, zed));
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals("zed", String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
            ShadowLooper.idleMainLooper();
            assertEquals("Copied", ShadowToast.getTextOfLatestToast());

            assertTrue(page.pick(SentRequestsPage.REMOVE, zed));
            ShadowLooper.idleMainLooper();
            assertEquals("Removed @zed from the list", ShadowToast.getTextOfLatestToast());
            assertEquals(2, page.list.getAdapter().getCount());
            assertEquals("@kim\n@amy\nTap to see all 2, or search them.", String.valueOf(row.getSummary()));
            assertFalse("a menu id it doesn't know does nothing", page.pick(99, zed));
        }
        Utils.awaitBackgroundTasksForTests();
        SentRequestsRows.forget();
        assertEquals("and the file has it too", Arrays.asList("kim", "amy"), usernames());
    }

    /** While settings are locked a long press takes nothing off and Clear forgets nothing. */
    @Test public void theSettingsLockHoldsRemoveAndClear() throws Exception {
        SentRequestsRows.request("me", "1", "amy", "", "", 1_000L);
        SentRequestsRows.request("me", "2", "zed", "", "", 2_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SENT_FOLLOW_REQUESTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment settings = DownloadSettingsTest.pageIn(controller);
            idle();
            Preference row = settings.findPreference(HushgramPreferenceFragment.SENT_REQUESTS_ROW);
            SentRequestsPage page = SentRequestsPage.open(controller.get(), row);
            idle();
            assertTrue(SettingsLock.lock(1));
            page.pick(SentRequestsPage.REMOVE, (SentRequests.Request) page.list.getAdapter().getItem(0));
            idle();
            assertEquals(2, page.list.getAdapter().getCount());
            page.dialog.dismiss();
            Preference clear = settings.findPreference(HushgramPreferenceFragment.CLEAR_SENT_REQUESTS);
            assertTrue(clear.getOnPreferenceClickListener().onPreferenceClick(clear));
            idle();
        }
        Utils.awaitBackgroundTasksForTests();
        SentRequestsRows.forget();
        assertEquals(Arrays.asList("zed", "amy"), usernames());
    }

    @Test public void clearForgetsEveryAccountsRequestsAndSaysSo() throws Exception {
        SentRequestsRows.request("me", "1", "amy", "", "", 1_000L);
        SentRequestsRows.request("other", "2", "zed", "", "", 2_000L);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SENT_FOLLOW_REQUESTS);
        Settings.SENT_FOLLOW_REQUESTS.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(controller);
            idle();
            Preference list = page.findPreference(HushgramPreferenceFragment.SENT_REQUESTS_ROW);
            assertEquals("@zed\nTap to see all 1, or search them.", String.valueOf(list.getSummary()));
            Preference clear = page.findPreference(HushgramPreferenceFragment.CLEAR_SENT_REQUESTS);
            assertTrue(clear.getOnPreferenceClickListener().onPreferenceClick(clear));
            idle();
            assertEquals("Sent requests cleared.", ShadowToast.getTextOfLatestToast());
            assertTrue(String.valueOf(list.getSummary()).startsWith("No requests waiting."));
            assertTrue("the switch stays as it is", Settings.SENT_FOLLOW_REQUESTS.get());
        }
        SentRequestsRows.forget();
        assertTrue("and the file went with it", SentRequests.all(null).isEmpty());
    }

    private static java.util.List<String> usernames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (SentRequests.Request each : SentRequests.all(null)) names.add(each.username);
        return names;
    }
}
