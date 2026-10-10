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
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The tab long press that picks the playback quality (#93): the chosen tab shows a list over the
 * screen, a pick saves the setting the settings screen holds, and everything else keeps its action.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class QualityTabPickerTest {
    enum Tab { FEED, SEARCH, CLIPS, DIRECT, PROFILE }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout bar;
    private View clips;
    private View profile;
    private final AtomicInteger stockClips = new AtomicInteger();
    private final AtomicInteger stockProfile = new AtomicInteger();

    @Before public void setUp() {
        PauseForTests.resume();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PLAYBACK_QUALITY);
        SettingsEntry.onClosedByUser();
        ShadowAlertDialog.reset();
        NavigationSettings.forgetTabsForTests();
        controller = Robolectric.buildActivity(Activity.class).setup();
        controller.windowFocusChanged(true);
        activity = controller.get();
        bar = new LinearLayout(activity);
        clips = new View(activity);
        profile = new View(activity);
        bar.addView(clips, new LinearLayout.LayoutParams(60, 60));
        bar.addView(profile, new LinearLayout.LayoutParams(60, 60));
        activity.setContentView(bar);
        bar.layout(0, 0, 300, 100);
        clips.layout(0, 0, 60, 60);
        profile.layout(60, 0, 120, 60);
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        Settings.QUALITY_TAB_TARGET.save(NavigationTarget.CLIPS);
        Settings.DEFAULT_PLAYBACK_QUALITY.save(true);
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.AUTO);
        bind(clips, Tab.CLIPS, stockClips);
        bind(profile, Tab.PROFILE, stockProfile);
    }

    @After public void tearDown() throws Exception {
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        SettingsEntry.onClosedByUser();
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        Settings.QUALITY_TAB_TARGET.resetToDefault();
        Settings.DEFAULT_PLAYBACK_QUALITY.resetToDefault();
        Settings.PLAYBACK_QUALITY.resetToDefault();
        Utils.awaitBackgroundTasksForTests();
    }

    private static void bind(View button, Tab tab, AtomicInteger stock) {
        button.setOnLongClickListener(NavigationSettings.remember(button, tab, view -> {
            stock.incrementAndGet();
            return true;
        }));
        NavigationSettings.bind(button, tab);
    }

    private AlertDialog picker() {
        shadowOf(Looper.getMainLooper()).idle();
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    @Test public void theChosenTabShowsTheListWithTheSavedChoiceChecked() {
        assertTrue(clips.performLongClick());
        AlertDialog dialog = picker();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertEquals(PlaybackQuality.values().length, dialog.getListView().getCount());
        assertEquals(PlaybackQuality.AUTO.ordinal(), dialog.getListView().getCheckedItemPosition());
        assertEquals(0, stockClips.get());
    }

    @Test public void aPickSavesTheQualityAndTellsYou() {
        assertTrue(clips.performLongClick());
        AlertDialog dialog = picker();
        int at = PlaybackQuality.P720.ordinal();
        dialog.getListView().performItemClick(null, at, at);
        assertEquals(PlaybackQuality.P720, Settings.PLAYBACK_QUALITY.savedValue());
        assertFalse(dialog.isShowing());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("720p"));
    }

    @Test public void theSavedChoiceIsCheckedTheNextTime() {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        assertTrue(clips.performLongClick());
        assertEquals(PlaybackQuality.HIGHEST.ordinal(), picker().getListView().getCheckedItemPosition());
    }

    @Test public void anotherTabKeepsItsOwnAction() {
        assertTrue(profile.performLongClick());
        assertEquals(1, stockProfile.get());
        assertNull(picker());
    }

    @Test public void withTheSwitchOffTheTabKeepsItsOwnAction() {
        Settings.DEFAULT_PLAYBACK_QUALITY.save(false);
        assertTrue(clips.performLongClick());
        assertEquals(1, stockClips.get());
        assertNull(picker());
    }

    @Test public void withoutThePatchTheTabKeepsItsOwnAction() {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        assertTrue(clips.performLongClick());
        assertEquals(1, stockClips.get());
        assertNull(picker());
    }

    @Test public void pausedHushGramLeavesTheTabAlone() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        NavigationSettings.applyChoice();
        assertTrue(clips.performLongClick());
        assertEquals(1, stockClips.get());
        assertNull(picker());
    }

    @Test public void offLeavesEveryTabAlone() {
        Settings.QUALITY_TAB_TARGET.save(NavigationTarget.OFF);
        NavigationSettings.applyChoice();
        assertTrue(clips.performLongClick());
        assertEquals(1, stockClips.get());
        assertNull(picker());
    }

    @Test public void aNewChoiceReachesTheTabsAlreadyBuilt() {
        Settings.QUALITY_TAB_TARGET.save(NavigationTarget.PROFILE);
        NavigationSettings.applyChoice();
        assertTrue(clips.performLongClick());
        assertEquals(1, stockClips.get());
        assertNull(picker());
        assertTrue(profile.performLongClick());
        assertEquals(0, stockProfile.get());
        assertNotNull(picker());
    }

    @Test public void openingHushGramStillWorksOnItsOwnTab() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        NavigationSettings.applyChoice();
        assertTrue(profile.performLongClick());
        assertEquals(0, stockProfile.get());
        assertNull(picker());
        assertTrue(clips.performLongClick());
        assertNotNull(picker());
    }

    @Test public void whenBothChoicesNameOneTabHushGramOpens() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.CLIPS);
        NavigationSettings.applyChoice();
        assertTrue(clips.performLongClick());
        assertNull(picker());
        assertEquals(0, stockClips.get());
    }

    @Test public void thePickerIsNotShownWithoutAnActivity() {
        assertFalse(QualityPicker.show(null));
    }
}
