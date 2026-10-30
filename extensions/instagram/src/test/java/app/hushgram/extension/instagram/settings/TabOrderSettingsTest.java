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
import android.os.SystemClock;
import android.preference.Preference;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;

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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/** Tab order's row under Tab bar: every tab with a box to show it and a handle to drag it (#207). */
@RunWith(RobolectricTestRunner.class)
@SuppressWarnings("deprecation")
public class TabOrderSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        reset();
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        reset();
    }

    private static void reset() {
        Settings.TAB_ORDER.resetToDefault();
        // Listed here, not in a static field, so the settings load once the rule has given them a context.
        BooleanSetting[] switches = {Settings.HIDE_HOME_TAB, Settings.HIDE_SEARCH_TAB, Settings.HIDE_REELS_TAB,
                Settings.SHOW_REELS_TAB, Settings.HIDE_CREATE_TAB, Settings.HIDE_PROFILE_TAB, Settings.SHOW_MESSAGES_TAB,
                Settings.SHOW_ACTIVITY_TAB, Settings.HIDE_MESSAGES_TAB};
        for (BooleanSetting setting : switches) setting.resetToDefault();
        // The list shows the bar as Instagram last built it, which other tests in this JVM may have built.
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

    private static LinearLayout list(AlertDialog dialog) {
        View home = dialog.getWindow().getDecorView().findViewWithTag(TabPicker.HOME);
        assertNotNull("the list has a Home row", home);
        return (LinearLayout) home.getParent();
    }

    private static List<String> shown(LinearLayout list) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            CheckBox box = TabPicker.box(list.getChildAt(i));
            labels.add((box.isChecked() ? "[x] " : "[ ] ") + box.getText() + (box.isEnabled() ? "" : " (fixed)"));
        }
        return labels;
    }

    private static CheckBox box(LinearLayout list, String tab) {
        return TabPicker.box(list.findViewWithTag(tab));
    }

    private static void press(AlertDialog dialog, int which) {
        dialog.getButton(which).performClick();
        ShadowLooper.idleMainLooper();
    }

    /** A drag on [tab]'s handle by [dy] pixels, in one move. */
    private static void drag(LinearLayout list, String tab, float dy) {
        View handle = TabPicker.handle(list.findViewWithTag(tab));
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 100, 0);
        MotionEvent move = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, 10, 100 + dy, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, 10, 100 + dy, 0);
        handle.dispatchTouchEvent(down);
        handle.dispatchTouchEvent(move);
        handle.dispatchTouchEvent(up);
        down.recycle();
        move.recycle();
        up.recycle();
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

    /** The row shows its own summary, so opening settings and saving the list log no patch error. */
    @Test public void theRowLogsNoPatchErrorWhenSettingsOpensOrTheListIsSaved() throws Exception {
        int before = unhandled(LogBufferManager.buildExportText());
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        drag(list(dialog), TabPicker.MESSAGES, -40);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        String log = LogBufferManager.buildExportText();
        assertEquals(log, before, unhandled(log));
    }

    private static int unhandled(String log) {
        String needle = "Setting cannot be handled";
        return (log.length() - log.replace(needle, "").length()) / needle.length();
    }

    /** Every tab is listed, the bar's first, with a box for whether it's on it. */
    @Test public void everyTabIsListedWithItsBox() throws Exception {
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        assertEquals("Show and reorder tabs", String.valueOf(shadowOf(dialog).getTitle()));
        assertEquals(Arrays.asList("[x] Home", "[x] Search", "[x] Reels", "[x] Messages", "[x] Create",
                "[x] Profile", "[ ] Activity"), shown(list(dialog)));
        assertEquals("Drag to move Home", String.valueOf(TabPicker.handle(list(dialog).getChildAt(0)).getContentDescription()));
    }

    /** #298: Messages on Instagram's own bar comes off with Hide the Messages tab, and its box puts it back. */
    @Test public void messagesOnInstagramsBarComesOffWithItsHideSwitch() throws Exception {
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        box(list(dialog), TabPicker.MESSAGES).setChecked(false);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue(Settings.HIDE_MESSAGES_TAB.get());
        assertFalse("Instagram's bar has it, so nothing was added to take away", Settings.SHOW_MESSAGES_TAB.get());

        dialog = openList();
        assertTrue(shown(list(dialog)).toString(), shown(list(dialog)).contains("[ ] Messages"));
        box(list(dialog), TabPicker.MESSAGES).setChecked(true);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertFalse(Settings.HIDE_MESSAGES_TAB.get());
        assertFalse(Settings.SHOW_MESSAGES_TAB.get());
    }

    /** A Messages tab HushGram added comes off by turning Show the Messages tab back off, not by hiding it. */
    @Test public void messagesHushgramAddedComesOffWithItsShowSwitch() throws Exception {
        Settings.SHOW_MESSAGES_TAB.save(true);
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        box(list(dialog), TabPicker.MESSAGES).setChecked(false);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertFalse(Settings.SHOW_MESSAGES_TAB.get());
        assertFalse(Settings.HIDE_MESSAGES_TAB.get());
    }

    /** #207: a drag moves a tab, the boxes turn tabs on and off, and OK saves both at once. */
    @Test public void aDragAndTheBoxesAreSavedOnOk() throws Exception {
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        LinearLayout list = list(dialog);
        drag(list, TabPicker.MESSAGES, -40);
        drag(list, TabPicker.MESSAGES, -40);
        drag(list, TabPicker.HOME, 40);
        box(list, TabPicker.SEARCH).setChecked(false);
        box(list, TabPicker.ACTIVITY).setChecked(true);
        assertEquals(Arrays.asList("[x] Messages", "[x] Home", "[ ] Search", "[x] Reels", "[x] Create",
                "[x] Profile", "[x] Activity"), shown(list));
        assertEquals("nothing is saved before OK", "", Settings.TAB_ORDER.get());

        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals("DIRECT,FEED,SEARCH,CLIPS,CREATION,PROFILE,NEWS", Settings.TAB_ORDER.get());
        assertTrue(Settings.HIDE_SEARCH_TAB.get());
        assertTrue(Settings.SHOW_ACTIVITY_TAB.get());
        assertFalse("an unchanged tab's switch stays", Settings.SHOW_MESSAGES_TAB.get());
        assertFalse(Settings.HIDE_HOME_TAB.get());
        // Before Instagram has built its bar the summary names every tab the order holds, the hidden one too.
        assertEquals("Tabs go in this order: Messages, Home, Search, Reels, Create, Profile, Activity. Restart Instagram to see the change.",
                String.valueOf(row().getSummary()).replaceAll("[⁦-⁩]", ""));

        dialog = openList();
        assertEquals("the list opens as it was saved", Arrays.asList("[x] Messages", "[x] Home", "[ ] Search",
                "[x] Reels", "[x] Create", "[x] Profile", "[x] Activity"), shown(list(dialog)));
    }

    @Test public void cancelLeavesEverythingAsItWas() throws Exception {
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        LinearLayout list = list(dialog);
        drag(list, TabPicker.PROFILE, -40);
        box(list, TabPicker.REELS).setChecked(false);
        press(dialog, DialogInterface.BUTTON_NEGATIVE);
        assertEquals("", Settings.TAB_ORDER.get());
        assertFalse(Settings.HIDE_REELS_TAB.get());
    }

    /** Reset puts the list back to Instagram's order and tabs without closing it, and OK saves that. */
    @Test public void resetGoesBackToInstagramsOrderAndTabs() throws Exception {
        Settings.TAB_ORDER.save("PROFILE,DIRECT");
        Settings.HIDE_CREATE_TAB.save(true);
        Settings.SHOW_ACTIVITY_TAB.save(true);
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        assertEquals(Arrays.asList("[x] Profile", "[x] Messages", "[x] Home", "[x] Search", "[x] Reels",
                "[x] Activity", "[ ] Create"), shown(list(dialog)));
        press(dialog, DialogInterface.BUTTON_NEUTRAL);
        assertTrue("the list stays open", dialog.isShowing());
        assertEquals(Arrays.asList("[x] Home", "[x] Search", "[x] Reels", "[x] Messages", "[ ] Activity",
                "[x] Create", "[x] Profile"), shown(list(dialog)));

        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals("Instagram's order saves as none", "", Settings.TAB_ORDER.get());
        assertFalse(Settings.HIDE_CREATE_TAB.get());
        assertFalse(Settings.SHOW_ACTIVITY_TAB.get());
        assertEquals("The tab bar keeps Instagram's order.", String.valueOf(row().getSummary()));
    }

    /**
     * S22, 451: Instagram's bar had no Create, and a box for it read ticked though nothing can add it.
     * A tab Instagram left off with only a hide switch is unticked and can't change, one hidden by
     * its switch can come back, and Reset ticks only what Instagram's bar holds.
     */
    @Test public void aTabInstagramLeftOffWithNoWayToAddItCantBeTicked() throws Exception {
        Settings.HIDE_SEARCH_TAB.save(true);
        TabOrder.builtForTests(Arrays.asList("FEED", "CLIPS", "DIRECT", "PROFILE"));
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        assertEquals(Arrays.asList("[x] Home", "[x] Reels", "[x] Messages", "[x] Profile", "[ ] Search",
                "[ ] Activity", "[ ] Create (fixed)"), shown(list(dialog)));
        box(list(dialog), TabPicker.SEARCH).setChecked(true);
        press(dialog, DialogInterface.BUTTON_NEUTRAL);
        assertEquals(Arrays.asList("[x] Home", "[x] Reels", "[x] Messages", "[x] Profile", "[x] Search",
                "[ ] Activity", "[ ] Create (fixed)"), shown(list(dialog)));
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertFalse(Settings.HIDE_SEARCH_TAB.get());
        assertFalse("nothing asked for Create", Settings.HIDE_CREATE_TAB.get());
    }

    /**
     * A tab's name takes the page's title colour. Left in the framework's own it was dark on a dark list
     * (#55). One whose box can't change takes the summary's, so it doesn't look like the others.
     */
    @Test public void aTabsNameReadsInThePagesTitleColour() throws Exception {
        open(PatchFamily.REELS_TAB);
        LinearLayout list = list(openList());
        ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
        for (int at = 0; at < list.getChildCount(); at++) {
            CheckBox name = TabPicker.box(list.getChildAt(at));
            assertEquals(String.valueOf(name.getText()), name.isEnabled() ? colors.title : colors.summary,
                    name.getCurrentTextColor());
        }
    }

    /**
     * Paused, Instagram gets its own bar, but the row and list still show the saved order and tabs,
     * so a change saves on top of them instead of over them.
     */
    @Test public void pausedTheRowAndListKeepTheChoice() throws Exception {
        Settings.TAB_ORDER.save("PROFILE,DIRECT");
        Settings.HIDE_CREATE_TAB.save(true);
        Settings.SHOW_ACTIVITY_TAB.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("", Settings.TAB_ORDER.get());
        open(PatchFamily.REELS_TAB);
        assertTrue(String.valueOf(row().getSummary()).replaceAll("[⁦-⁩]", "").startsWith("Tabs go in this order: Profile, Messages"));
        AlertDialog dialog = openList();
        assertEquals(Arrays.asList("[x] Profile", "[x] Messages", "[x] Home", "[x] Search", "[x] Reels",
                "[x] Activity", "[ ] Create"), shown(list(dialog)));

        box(list(dialog), TabPicker.SEARCH).setChecked(false);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue(Settings.TAB_ORDER.savedValue().startsWith("PROFILE,DIRECT,"));
        assertTrue(Settings.HIDE_SEARCH_TAB.savedValue());
        assertTrue("Create stays off", Settings.HIDE_CREATE_TAB.savedValue());
        assertTrue("Activity stays on", Settings.SHOW_ACTIVITY_TAB.savedValue());
    }

    /**
     * Paused, an order that happens to match the list's own is still the user's, so a change keeps it
     * as it is. Only Reset saves Instagram's order as none.
     */
    @Test public void pausedOnlyResetSavesInstagramsOrderAsNone() throws Exception {
        Settings.TAB_ORDER.save("FEED,SEARCH,CLIPS,DIRECT,CREATION,PROFILE,NEWS");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        open(PatchFamily.REELS_TAB);
        AlertDialog dialog = openList();
        box(list(dialog), TabPicker.SEARCH).setChecked(false);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals("FEED,SEARCH,CLIPS,DIRECT,CREATION,PROFILE,NEWS", Settings.TAB_ORDER.savedValue());
        assertTrue(Settings.HIDE_SEARCH_TAB.savedValue());

        dialog = openList();
        press(dialog, DialogInterface.BUTTON_NEUTRAL);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals("", Settings.TAB_ORDER.savedValue());
        assertFalse(Settings.HIDE_SEARCH_TAB.savedValue());
    }

    /** Activity is listed by its own name, ticked once Show the Activity tab puts it on the bar (#156). */
    @Test public void anAddedActivityTabIsListedAsActivity() throws Exception {
        Settings.SHOW_ACTIVITY_TAB.save(true);
        open(PatchFamily.REELS_TAB);
        assertTrue(box(list(openList()), TabPicker.ACTIVITY).isChecked());
    }
}
