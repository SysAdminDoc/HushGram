/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Arrays;
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
import app.hushgram.extension.instagram.feed.AmbientLight;
import app.hushgram.extension.instagram.feed.AmbientSpeed;
import app.hushgram.extension.instagram.feed.AmbientSpread;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Ambient light (#416): a Feed switch after Carousel arrows that starts off, with its Glow spread and Glow
 * speed lists under it, all kept in a backup, reaching the screen under settings and reading off while paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class AmbientLightSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    private static void reset() {
        Settings.AMBIENT_LIGHT.resetToDefault();
        Settings.AMBIENT_LIGHT_STORIES.resetToDefault();
        Settings.AMBIENT_LIGHT_SPREAD.resetToDefault();
        Settings.AMBIENT_LIGHT_SPEED.resetToDefault();
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        AmbientLight.resetForTests();
        reset();
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
    @Test public void theSwitchAndItsListsAreUnderFeedAfterCarouselArrowsAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.CAROUSEL_ARROWS, PatchFamily.AMBIENT_LIGHT));
        Preference row = page.getPreferenceScreen().findPreference(Settings.AMBIENT_LIGHT.key);
        assertNotNull(row);
        assertEquals("Ambient light", row.getTitle().toString());
        assertEquals("Puts a soft glow in a post's own colors around its photo or video, and in the bands around a "
                + "reel that doesn't fill the screen. The glow follows a video as it plays.", row.getSummary().toString());
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.AMBIENT_LIGHT.get());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.AMBIENT_LIGHT.key);
        assertEquals(Arrays.toString(keys), Settings.CAROUSEL_DOTS_PILL.key, keys[at - 1]);
        assertEquals(Arrays.toString(keys), Settings.AMBIENT_LIGHT_SPREAD.key, keys[at + 1]);
        assertEquals(Arrays.toString(keys), Settings.AMBIENT_LIGHT_SPEED.key, keys[at + 2]);

        HushgramPreferenceFragment.AmbientSpreadRow spread = (HushgramPreferenceFragment.AmbientSpreadRow) feed.getPreference(at + 1);
        assertEquals("Glow spread", spread.getTitle().toString());
        assertEquals("Glow spread", String.valueOf(spread.getDialogTitle()));
        assertEquals(Arrays.asList("Narrow", "Medium", "Wide"), labels(spread.getEntries()));
        assertEquals(Arrays.asList("NARROW", "MEDIUM", "WIDE"), labels(spread.getEntryValues()));
        assertEquals("MEDIUM", spread.getValue());
        assertEquals("Medium", spread.getSummary().toString());
        HushgramPreferenceFragment.AmbientSpeedRow speed = (HushgramPreferenceFragment.AmbientSpeedRow) feed.getPreference(at + 2);
        assertEquals("Glow speed", speed.getTitle().toString());
        assertEquals(Arrays.asList("Slow", "Medium", "Fast"), labels(speed.getEntries()));
        assertEquals(Arrays.asList("SLOW", "MEDIUM", "FAST"), labels(speed.getEntryValues()));
        assertEquals("MEDIUM", speed.getValue());
        assertEquals("Medium", speed.getSummary().toString());

        assertFalse("the lists wait for the switch", spread.isEnabled() || speed.isEnabled());
        ((SwitchPreference) row).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(spread.isEnabled() && speed.isEnabled());
        spread.setValue("WIDE");
        speed.setValue("FAST");
        ShadowLooper.idleMainLooper();
        assertEquals(AmbientSpread.WIDE, Settings.AMBIENT_LIGHT_SPREAD.savedValue());
        assertEquals(AmbientSpeed.FAST, Settings.AMBIENT_LIGHT_SPEED.savedValue());
        assertEquals("Wide", spread.getSummary().toString());
        assertEquals("Fast", speed.getSummary().toString());

        assertEquals("Ambient light", PatchFamily.AMBIENT_LIGHT.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.AMBIENT_LIGHT.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.AMBIENT_LIGHT_SPREAD.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.AMBIENT_LIGHT_SPEED.key));
    }
    private static java.util.List<String> labels(CharSequence[] texts) {
        String[] out = new String[texts.length];
        for (int i = 0; i < texts.length; i++) out[i] = texts[i].toString();
        return Arrays.asList(out);
    }
    @Test public void eachChoiceIsWiderOrQuickerThanTheLast() {
        assertTrue(AmbientSpread.NARROW.dp < AmbientSpread.MEDIUM.dp && AmbientSpread.MEDIUM.dp < AmbientSpread.WIDE.dp);
        assertTrue(AmbientSpeed.SLOW.sampleMillis > AmbientSpeed.MEDIUM.sampleMillis
                && AmbientSpeed.MEDIUM.sampleMillis > AmbientSpeed.FAST.sampleMillis);
        assertTrue(AmbientSpeed.SLOW.fadeMillis > AmbientSpeed.MEDIUM.fadeMillis
                && AmbientSpeed.MEDIUM.fadeMillis > AmbientSpeed.FAST.fadeMillis);
        assertEquals("Narrow", HushgramPreferenceFragment.ambientSpreadLabel(AmbientSpread.NARROW));
        assertEquals("Fast", HushgramPreferenceFragment.ambientSpeedLabel(AmbientSpeed.FAST));
    }
    /** Settings are a dialog over Instagram's screen, which doesn't resume when they close, so flipping the switch on reaches it at once. */
    @Test public void flippingTheSwitchOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.AMBIENT_LIGHT));
        Preference row = page.getPreferenceScreen().findPreference(Settings.AMBIENT_LIGHT.key);
        assertFalse(AmbientLight.listeningForTests(controller.get()));
        assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
        // The switch saves after its listener, so the helper reads the value once the change is posted.
        Settings.AMBIENT_LIGHT.save(true);
        ShadowLooper.idleMainLooper();
        assertTrue(AmbientLight.listeningForTests(controller.get()));
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.AMBIENT_LIGHT));
        Settings.AMBIENT_LIGHT.save(true);
        assertTrue(Settings.AMBIENT_LIGHT.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.AMBIENT_LIGHT.get());
        assertTrue(Settings.AMBIENT_LIGHT.savedValue());
    }
    /** #420: a Stories switch that starts off, shares the lists, and wakes them alone. */
    @Test public void theStoriesSwitchIsUnderStoriesStartsOffAndWakesTheSharedLists() throws Exception {
        open(EnumSet.of(PatchFamily.AMBIENT_LIGHT));
        Preference row = page.getPreferenceScreen().findPreference(Settings.AMBIENT_LIGHT_STORIES.key);
        assertNotNull(row);
        assertEquals("Ambient light in stories", row.getTitle().toString());
        assertEquals("Stories", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse(Settings.AMBIENT_LIGHT_STORIES.get());
        Preference spread = page.getPreferenceScreen().findPreference(Settings.AMBIENT_LIGHT_SPREAD.key);
        Preference speed = page.getPreferenceScreen().findPreference(Settings.AMBIENT_LIGHT_SPEED.key);
        assertFalse(spread.isEnabled() || speed.isEnabled());
        ((SwitchPreference) row).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue("the story switch alone wakes the lists", spread.isEnabled() && speed.isEnabled());
        assertTrue(PatchFamily.AMBIENT_LIGHT.switches.contains(Settings.AMBIENT_LIGHT_STORIES));
        Settings.AMBIENT_LIGHT_STORIES.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.AMBIENT_LIGHT_STORIES.get());
    }
    @Test public void missingPatchHasNeitherRowsNorBackupKeys() throws Exception {
        open(EnumSet.of(PatchFamily.CAROUSEL_ARROWS));
        for (String key : Arrays.asList(Settings.AMBIENT_LIGHT.key, Settings.AMBIENT_LIGHT_SPREAD.key, Settings.AMBIENT_LIGHT_SPEED.key)) {
            assertNull(key, page.getPreferenceScreen().findPreference(key));
            assertFalse(key, ConfigurationBackup.eligible().containsKey(key));
        }
    }
}
