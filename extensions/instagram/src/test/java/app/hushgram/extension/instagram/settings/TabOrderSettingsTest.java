/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.StoryTimeSettingsTest.sectionOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.preference.Preference;
import android.widget.ListAdapter;
import android.widget.ListView;

import java.util.ArrayList;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.reels.TabOrder;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;

/** Tab order's row under Tab bar: the list of tabs, moved up a place by a tap. */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class TabOrderSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.TAB_ORDER.resetToDefault();
        // The list shows the bar as Instagram last built it, which other tests in this JVM may have built.
        TabOrder.forgetForTests();
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.TAB_ORDER.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        TabOrder.forgetForTests();
    }

    private void open(PatchFamily... families) throws Exception {
        PatchFamily.inBuildForTests = families.length == 0 ? EnumSet.noneOf(PatchFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private Preference row() {
        return page.getPreferenceScreen().findPreference(Settings.TAB_ORDER.key);
    }

    private AlertDialog openList() {
        assertTrue(row().getOnPreferenceClickListener().onPreferenceClick(row()));
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        return dialog;
    }

    private static List<String> shown(AlertDialog dialog) {
        ListAdapter adapter = dialog.getListView().getAdapter();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < adapter.getCount(); i++) labels.add(String.valueOf(adapter.getItem(i)));
        return labels;
    }

    private static void tap(ListView list, int at) {
        assertTrue(list.performItemClick(list.getAdapter().getView(at, null, list), at, list.getItemIdAtPosition(at)));
        ShadowLooper.idleMainLooper();
    }

    @Test public void theRowIsUnderTabBarWithTheReelsTabPatchAndStartsOnInstagramsOrder() throws Exception {
        open();
        assertNull(row());
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.TAB_ORDER.key));
        controller.close();
        controller = null;

        open(PatchFamily.REELS_TAB);
        assertNotNull(row());
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), row()).getTitle()));
        assertEquals("Tab order", String.valueOf(row().getTitle()));
        assertEquals("The tab bar keeps Instagram's order.", String.valueOf(row().getSummary()));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.TAB_ORDER.key));
    }

    @Test public void aTapMovesATabUpAtOnceAndKeepsTheListOpen() throws Exception {
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        assertEquals("Tap a tab to move it up", String.valueOf(shadowOf(dialog).getTitle()));
        assertEquals(Arrays.asList("Home", "Search", "Reels", "Messages", "Create", "Profile"), shown(dialog));

        tap(dialog.getListView(), 3);
        assertTrue("the list stays open", dialog.isShowing());
        assertEquals(Arrays.asList("Home", "Search", "Messages", "Reels", "Create", "Profile"), shown(dialog));
        assertEquals("FEED,SEARCH,DIRECT,CLIPS,CREATION,PROFILE", Settings.TAB_ORDER.get());

        tap(dialog.getListView(), 2);
        tap(dialog.getListView(), 1);
        assertEquals(Arrays.asList("Messages", "Home", "Search", "Reels", "Create", "Profile"), shown(dialog));
        assertEquals("DIRECT,FEED,SEARCH,CLIPS,CREATION,PROFILE", Settings.TAB_ORDER.get());
        assertEquals("Tabs go in this order: Messages, Home, Search, Reels, Create, Profile. Restart Instagram to see the change.",
                String.valueOf(row().getSummary()).replaceAll("[⁦-⁩]", ""));
    }

    @Test public void instagramsOrderClearsTheChoice() throws Exception {
        Settings.TAB_ORDER.save("PROFILE,DIRECT");
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        assertEquals(Arrays.asList("Profile", "Messages", "Home", "Search", "Reels", "Create"), shown(dialog));
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("", Settings.TAB_ORDER.get());
        assertEquals("The tab bar keeps Instagram's order.", String.valueOf(row().getSummary()));
    }
}
