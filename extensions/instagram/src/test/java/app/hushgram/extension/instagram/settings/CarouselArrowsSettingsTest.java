/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
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
import app.hushgram.extension.instagram.feed.CarouselArrows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Carousel arrows (#417): a Feed switch that starts off, is kept in a backup, reaches the screen under settings and reads off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CarouselArrowsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CAROUSEL_ARROWS.resetToDefault();
        Settings.CAROUSEL_DOTS_PILL.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        CarouselArrows.resetForTests();
        Settings.CAROUSEL_ARROWS.resetToDefault();
        Settings.CAROUSEL_DOTS_PILL.resetToDefault();
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
    @Test public void theSwitchIsUnderFeedAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.CAROUSEL_ARROWS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.CAROUSEL_ARROWS.key);
        assertNotNull(row);
        assertEquals("Carousel arrows", row.getTitle().toString());
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.CAROUSEL_ARROWS.get());
        assertEquals("Carousel arrows", PatchFamily.CAROUSEL_ARROWS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CAROUSEL_ARROWS.key));
    }
    /** Settings are a dialog over Instagram's screen, which doesn't resume when they close, so flipping the switch on reaches it at once. */
    @Test public void flippingTheSwitchOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.CAROUSEL_ARROWS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.CAROUSEL_ARROWS.key);
        assertFalse(CarouselArrows.listeningForTests(controller.get()));
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
        // The switch saves after its listener, so the helper reads the value once the change is posted.
        Settings.CAROUSEL_ARROWS.save(true);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(CarouselArrows.listeningForTests(controller.get()));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.CAROUSEL_ARROWS));
        Settings.CAROUSEL_ARROWS.save(true);
        assertTrue(Settings.CAROUSEL_ARROWS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.CAROUSEL_ARROWS.get());
        assertTrue(Settings.CAROUSEL_ARROWS.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.CAROUSEL_ARROWS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.CAROUSEL_ARROWS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.CAROUSEL_DOTS_PILL.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.CAROUSEL_DOTS_PILL.key));
    }
    /** Page dots on a pill (#419) is the patch's second switch, right under the arrows, and starts off. */
    @Test public void thePillSwitchFollowsTheArrowsAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.CAROUSEL_ARROWS));
        Preference arrows = page.getPreferenceScreen().findPreference(Settings.CAROUSEL_ARROWS.key);
        Preference row = page.getPreferenceScreen().findPreference(Settings.CAROUSEL_DOTS_PILL.key);
        assertNotNull(row);
        assertEquals("Page dots on a pill", row.getTitle().toString());
        assertEquals("Moves a carousel post's page dots onto a round pill at the bottom of its photo or video, with "
                + "the page you're on in blue.", row.getSummary().toString());
        PreferenceGroup feed = arrows.getParent();
        assertSame(feed, row.getParent());
        int at = -1;
        for (int i = 0; i < feed.getPreferenceCount(); i++) if (feed.getPreference(i) == arrows) at = i;
        assertSame(row, feed.getPreference(at + 1));
        assertFalse(Settings.CAROUSEL_DOTS_PILL.get());
        assertTrue(PatchFamily.CAROUSEL_ARROWS.switches.contains(Settings.CAROUSEL_DOTS_PILL));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CAROUSEL_DOTS_PILL.key));
    }
    @Test public void flippingThePillOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.CAROUSEL_ARROWS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.CAROUSEL_DOTS_PILL.key);
        assertFalse(CarouselArrows.listeningForTests(controller.get()));
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
        Settings.CAROUSEL_DOTS_PILL.save(true);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(CarouselArrows.listeningForTests(controller.get()));
    }
}
