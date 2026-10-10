/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.preference.Preference;
import android.widget.EditText;
import android.widget.ListView;
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
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hidden accounts' row under Feed: the list of usernames, added by typing and removed by a tap. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HiddenAccountsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        clear();
        Settings.FEED_ACCOUNT.save("1");
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        PatchFamily.feedTypesForTests = null;
        clear();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void clear() {
        Settings.HIDDEN_ACCOUNTS.resetToDefault();
        Settings.FEED_ACCOUNT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HiddenAccounts.resetForTests();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.FEED_SUGGESTIONS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    private Preference row() {
        return page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.HIDDEN_ACCOUNTS_ROW);
    }
    private static AlertDialog latest() {
        ShadowLooper.idleMainLooper();
        return ShadowAlertDialog.getLatestAlertDialog();
    }
    /** Types [typed] into the box Add a username opens and presses Hide their posts. */
    private void type(String typed) {
        page.askHiddenAccount();
        AlertDialog ask = latest();
        EditText field = ask.getWindow().getDecorView().findViewWithTag(HushgramPreferenceFragment.HIDDEN_ACCOUNTS_ROW);
        assertNotNull("the box has a field", field);
        field.setText(typed);
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
    }
    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }

    @Test public void theRowIsUnderFeedOnlyWhereHomeFiltersByPost() throws Exception {
        open(true);
        Preference row = row();
        assertNotNull(row);
        assertEquals("Hidden accounts", row.getTitle().toString());
        assertTrue(row.getSummary().toString().contains("Each account you sign in to keeps its own list."));
        controller.close();

        open(false);
        assertNull("no patch", row());
        controller.close();

        PatchFamily.feedTypesForTests = false;
        open(true);
        assertNull("Home's reads moved", row());
    }

    @Test public void anEmptyListSaysHowToAddOne() throws Exception {
        open(true);
        assertTrue(row().getOnPreferenceClickListener().onPreferenceClick(row()));
        AlertDialog list = latest();
        assertEquals("Hidden accounts", shadowOf(list).getTitle().toString());
        assertTrue(shadowOf(list).getMessage().toString().startsWith("No accounts are hidden yet."));
        list.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        AlertDialog ask = latest();
        assertNotSame(list, ask);
        assertNotNull(ask.getWindow().getDecorView().findViewWithTag(HushgramPreferenceFragment.HIDDEN_ACCOUNTS_ROW));
    }

    @Test public void aTypedNameIsHiddenForTheSignedInAccountAndListed() throws Exception {
        open(true);
        type("  @NASA ");
        assertEquals("Posts from @nasa are hidden", ShadowToast.getTextOfLatestToast());
        assertEquals(Collections.singletonList("nasa"), HiddenAccounts.saved());
        assertEquals("1\tnasa", Settings.HIDDEN_ACCOUNTS.savedValue());

        ListView list = latest().getListView();
        assertEquals(1, list.getCount());
        assertEquals("@nasa", list.getItemAtPosition(0).toString());
        assertTrue(list.isItemChecked(0));
    }

    @Test public void tappingANameShowsItsPostsAgainAndTappingAgainHidesThem() throws Exception {
        HiddenAccounts.add("nasa");
        HiddenAccounts.add("esa");
        open(true);
        page.showHiddenAccounts();
        ListView list = latest().getListView();
        assertEquals(2, list.getCount());

        tap(list, 0);
        assertEquals("Posts from @nasa show again", ShadowToast.getTextOfLatestToast());
        assertEquals(Collections.singletonList("esa"), HiddenAccounts.saved());

        tap(list, 0);
        assertEquals("Posts from @nasa are hidden", ShadowToast.getTextOfLatestToast());
        assertTrue(HiddenAccounts.saved().contains("nasa"));
    }

    @Test public void aNameThatCantBeAUsernameIsTurnedDown() throws Exception {
        open(true);
        type("two words");
        assertEquals("A username is up to 30 letters, numbers, dots and underscores", ShadowToast.getTextOfLatestToast());
        assertTrue(HiddenAccounts.saved().isEmpty());
        assertEquals("", Settings.HIDDEN_ACCOUNTS.savedValue());
        assertTrue("back on the list", shadowOf(latest()).getMessage().toString().startsWith("No accounts are hidden yet."));
    }

    @Test public void theListIsLeftOutOfABackupAndStartsEmpty() {
        assertEquals("", Settings.HIDDEN_ACCOUNTS.defaultValue);
        assertEquals("", Settings.FEED_ACCOUNT.defaultValue);
        assertFalse(Settings.HIDDEN_ACCOUNTS.includeWithImportExport);
        assertFalse(Settings.FEED_ACCOUNT.includeWithImportExport);
    }
}
