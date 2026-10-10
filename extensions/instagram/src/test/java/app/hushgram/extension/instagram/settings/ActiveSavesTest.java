/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.TreeSet;

import app.hushgram.extension.instagram.download.SavesForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.PauseForTests;

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
import org.robolectric.shadows.ShadowLooper;

/**
 * The running saves under Downloads: each one with what it's doing and a Cancel button, whether
 * or not Instagram may post notifications, gone once it ends, and listed once after the page is
 * rebuilt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
@SuppressWarnings("deprecation")
public class ActiveSavesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final long MIB = 1024L * 1024L;
    private static final String PAGE_TAG = "hushgram_page";

    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void open() {
        SavesForTests.resetInterruption();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        PauseForTests.resume();
        // With the sign-in notice and the view button both above the list, Downloads sits under
        // the fold of this small screen. The notice has nothing to do with saves.
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    private void show() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page, PAGE_TAG).commitNow();
        layout();
    }

    @After public void close() throws Exception {
        SavesForTests.endAll();
        app.hushgram.extension.shared.Utils.awaitBackgroundTasksForTests();
        SavesForTests.resetInterruption();
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private static NotificationManager notifications() {
        return RuntimeEnvironment.getApplication().getSystemService(NotificationManager.class);
    }

    private ListView list() {
        return page.getView().findViewById(android.R.id.list);
    }

    /**
     * Lays the whole window out at the screen's size, as the window's own pass does. Laying out
     * only the page at another size made the two passes disagree, and a list whose size changes
     * binds every row again.
     */
    private void layout() {
        ShadowLooper.idleMainLooper();
        View root = controller.get().getWindow().getDecorView();
        for (int pass = 0; pass < 2; pass++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1688, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 780, 1688);
            ShadowLooper.idleMainLooper();
        }
    }

    /** The shown row of save [id], or null. */
    private View row(int id) {
        for (int i = 0; i < list().getChildCount(); i++) {
            int position = list().getFirstVisiblePosition() + i;
            Preference item = (Preference) list().getItemAtPosition(position);
            if (("running_save_" + id).equals(item.getKey())) return list().getChildAt(i);
        }
        return null;
    }

    /** Where save [id] is in the list, or -1. */
    private int positionOf(int id) {
        for (int i = 0; i < list().getCount(); i++) {
            if (("running_save_" + id).equals(((Preference) list().getItemAtPosition(i)).getKey())) return i;
        }
        return -1;
    }

    private int savesListed() {
        int listed = 0;
        for (int i = 0; i < list().getCount(); i++) {
            String key = ((Preference) list().getItemAtPosition(i)).getKey();
            if (key != null && key.startsWith("running_save_")) listed++;
        }
        return listed;
    }

    private static Button cancelOf(View row) {
        ViewGroup frame = row.findViewById(android.R.id.widget_frame);
        assertEquals(1, frame.getChildCount());
        return (Button) frame.getChildAt(0);
    }

    private static String summaryOf(View row) {
        return String.valueOf(((TextView) row.findViewById(android.R.id.summary)).getText());
    }

    /** With every Instagram notification off, the settings page is the one way to see and stop a save. */
    @Test public void withNotificationsOffTheDownloadsSectionListsTheSaveAndCancelsIt() {
        Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
        int id = SavesForTests.begin(RuntimeEnvironment.getApplication(), true);
        SavesForTests.transferred(id, (long) (4.2 * MIB), 100 * MIB);
        show();

        assertEquals("a notification went up with notifications off", 0,
                Shadows.shadowOf(notifications()).getAllNotifications().size());
        View row = row(id);
        assertNotNull("the running save isn't under Downloads", row);
        int at = positionOf(id);
        assertEquals("the save isn't first under Downloads", "Downloads",
                String.valueOf(((Preference) list().getItemAtPosition(at - 1)).getTitle()));
        assertEquals("Saving a video", String.valueOf(((TextView) row.findViewById(android.R.id.title)).getText()));
        assertEquals("Downloading\n4.2 MB of 100 MB", summaryOf(row));
        Button cancel = cancelOf(row);
        assertEquals("Cancel", cancel.getText().toString());
        assertEquals("Cancel saving this video", String.valueOf(cancel.getContentDescription()));
        assertEquals(Button.class.getName(), cancel.createAccessibilityNodeInfo().getClassName());
        int touch = Math.round(48 * cancel.getResources().getDisplayMetrics().density);
        assertTrue(cancel.getHeight() >= touch && cancel.getWidth() >= touch);
        assertNull("saving asked for a permission", Shadows.shadowOf(controller.get()).getLastRequestedPermission());

        assertTrue(cancel.performClick());
        assertTrue("Cancel didn't reach the save", SavesForTests.cancelled(id));
        layout();
        assertNull("a cancelled save is still listed", row(id));
        assertEquals(0, savesListed());
    }

    /** Only the saves channel switched off: no notification, and the page lists the save as before. */
    @Test public void withOnlyTheSavesChannelOffTheSaveIsStillListed() {
        notifications().createNotificationChannel(new NotificationChannel("hushgram_saves", "HushGram saves",
                NotificationManager.IMPORTANCE_NONE));
        int id = SavesForTests.begin(RuntimeEnvironment.getApplication(), false);
        show();

        assertEquals(0, Shadows.shadowOf(notifications()).getAllNotifications().size());
        View row = row(id);
        assertNotNull(row);
        assertEquals("Saving a photo", String.valueOf(((TextView) row.findViewById(android.R.id.title)).getText()));
        assertEquals("Downloading", summaryOf(row));
        assertEquals("Cancel saving this photo", String.valueOf(cancelOf(row).getContentDescription()));
        assertTrue(cancelOf(row).performClick());
        assertTrue(SavesForTests.cancelled(id));
    }

    /**
     * Joining and saving show on the row as they happen, and the row is changed where it is: a row
     * rebuilt under a finger would lose the tap on its Cancel.
     */
    @Test public void aSavesPhaseChangesOnItsRowInPlaceAndTheRowGoesWhenItEnds() {
        int id = SavesForTests.begin(RuntimeEnvironment.getApplication(), true);
        show();
        View row = row(id);
        Button cancel = cancelOf(row);

        SavesForTests.joining(id);
        ShadowLooper.idleMainLooper();
        assertSame("the row was rebuilt", cancel, cancelOf(row(id)));
        assertEquals("Joining the picture and sound", summaryOf(row));
        SavesForTests.saving(id);
        ShadowLooper.idleMainLooper();
        assertEquals("Copying to the gallery", summaryOf(row));

        SavesForTests.end(id);
        layout();
        assertNull("a finished save is still listed", row(id));
    }

    @Test public void aFilteredSavesPhaseChangesKeepTheSameCancelButton() {
        int id = SavesForTests.begin(RuntimeEnvironment.getApplication(), true);
        show();
        page.searchSettings("zzzz-no-match");
        layout();
        Button cancel = cancelOf(row(id));

        SavesForTests.joining(id);
        ShadowLooper.idleMainLooper();
        assertSame("filtering rebound the live Cancel button", cancel, cancelOf(row(id)));
        assertEquals("Joining the picture and sound", summaryOf(row(id)));
        SavesForTests.saving(id);
        ShadowLooper.idleMainLooper();
        assertSame(cancel, cancelOf(row(id)));
        assertTrue(cancel.performClick());
        assertTrue(SavesForTests.cancelled(id));
    }

    /** A save that starts while the page is open is listed, and one that starts after it closed isn't followed. */
    @Test public void aSaveStartedWhileThePageIsOpenIsListedAndAClosedPageStopsFollowing() {
        show();
        assertEquals(0, savesListed());
        int id = SavesForTests.begin(RuntimeEnvironment.getApplication(), true);
        layout();
        assertNotNull("a save started with the page open isn't listed", row(id));

        controller.pause();
        int later = SavesForTests.begin(RuntimeEnvironment.getApplication(), false);
        ShadowLooper.idleMainLooper();
        assertEquals("a paused page still followed the saves", -1, positionOf(later));
        controller.resume();
        layout();
        assertTrue("a resumed page didn't catch up", positionOf(later) >= 0);
    }

    /** Rebuilt for a rotation, the page lists each save once, and goes on following it. */
    @Test public void aRebuiltPageListsEachSaveOnceAndKeepsFollowingIt() {
        int first = SavesForTests.begin(RuntimeEnvironment.getApplication(), true);
        int second = SavesForTests.begin(RuntimeEnvironment.getApplication(), false);
        show();
        assertEquals(2, savesListed());

        controller.recreate();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) controller.get().getFragmentManager().findFragmentByTag(PAGE_TAG);
        layout();

        assertEquals("a rebuilt page listed a save twice", 2, savesListed());
        SavesForTests.joining(first);
        ShadowLooper.idleMainLooper();
        assertEquals("Joining the picture and sound", summaryOf(row(first)));
        SavesForTests.end(second);
        layout();
        assertEquals(1, savesListed());
    }

    /** Nothing of a save is written anywhere: no address, no name and no record once it's over. */
    @Test public void aSaveLeavesNothingStored() {
        show();
        File prefs = new File(RuntimeEnvironment.getApplication().getApplicationInfo().dataDir, "shared_prefs");
        TreeSet<String> before = new TreeSet<>(Arrays.asList(prefs.list() == null ? new String[0] : prefs.list()));
        int id = SavesForTests.begin(RuntimeEnvironment.getApplication(), true);
        layout();
        assertNotNull(row(id));
        SavesForTests.end(id);
        layout();
        TreeSet<String> after = new TreeSet<>(Arrays.asList(prefs.list() == null ? new String[0] : prefs.list()));
        assertEquals("a save stored something", before, after);
    }

    @Test public void theCompleteInterruptionNoticeStaysReadableWithoutADownloadPatch() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        SavesForTests.interrupt(RuntimeEnvironment.getApplication());
        show();
        Preference notice = page.findPreference("hushgram_interrupted_saves");
        assertNotNull(notice);
        assertEquals("A save was interrupted", String.valueOf(notice.getTitle()));
        assertEquals("Reopen the media and save again.", String.valueOf(notice.getSummary()));
        assertFalse(notice.isPersistent());
        assertFalse(notice.isSelectable());
        assertTrue(app.hushgram.extension.instagram.download.SaveControl.running().isEmpty());
        controller.recreate();
        ShadowLooper.idleMainLooper();
        page = (HushgramPreferenceFragment) controller.get().getFragmentManager().findFragmentByTag(PAGE_TAG);
        layout();
        assertNotNull(page.findPreference("hushgram_interrupted_saves"));
    }

    @Test @Config(qualifiers = "en-rXA-w390dp-h844dp-night-xhdpi")
    public void theInterruptionExplanationUsesTheLocalizationCatalog() throws Exception {
        SavesForTests.interrupt(RuntimeEnvironment.getApplication());
        show();
        Preference notice = page.findPreference("hushgram_interrupted_saves");
        assertNotNull(notice);
        assertTrue(String.valueOf(notice.getTitle()).startsWith("["));
        assertTrue(String.valueOf(notice.getSummary()).startsWith("["));
    }
}
