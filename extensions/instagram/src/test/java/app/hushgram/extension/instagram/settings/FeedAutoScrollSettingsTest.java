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
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * The Auto scroll in Home switch (#660): under Feed, off to start, no restart needed, with a Time on each
 * post slider right under it from 3 to 30 seconds that starts at 6.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedAutoScrollSettingsTest {
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

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private static void reset() {
        Settings.FEED_AUTO_SCROLL.resetToDefault();
        Settings.FEED_AUTO_SCROLL_SECONDS.resetToDefault();
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FEED_AUTO_SCROLL.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FEED_AUTO_SCROLL_SECONDS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.FEED_AUTO_SCROLL_SECONDS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HOME.key));
    }

    /** Without the other Feed patches, Feed still opens for this switch alone. */
    @Test public void aloneItStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_AUTO_SCROLL));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FEED_AUTO_SCROLL.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
    }

    @Test public void switchStartsOffUnderFeed() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS, PatchFamily.FEED_AUDIO_ICON, PatchFamily.FEED_AUTO_SCROLL));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FEED_AUTO_SCROLL.key);
        assertNotNull(row);
        assertEquals("Auto scroll in Home", row.getTitle().toString());
        assertEquals("Moves Home on to the next post by itself. A video moves on when it ends, a photo or carousel after "
                + "Time on each post. Touch the screen to hold it.", row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        assertNotNull(feed.findPreference(Settings.HIDE_FEED_AUDIO_ICON.key));
        assertFalse(row.isChecked());
        assertFalse(Settings.FEED_AUTO_SCROLL.get());
        assertFalse("the list reads the switch on every look, so no restart prompt", Settings.FEED_AUTO_SCROLL.rebootApp);
        assertEquals(Collections.singletonList(Settings.FEED_AUTO_SCROLL), PatchFamily.FEED_AUTO_SCROLL.switches);
        assertEquals("Auto scroll in Home", PatchFamily.FEED_AUTO_SCROLL.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FEED_AUTO_SCROLL.key));
    }

    /**
     * The slider sits right under the switch, asks for it while it's off, starts at 6 seconds, goes from 3 to 30
     * a second at a time, saves at once without a restart and travels with an export.
     */
    @Test public void timeOnEachPostSitsUnderTheSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_AUTO_SCROLL));
        Preference found = page.getPreferenceScreen().findPreference(Settings.FEED_AUTO_SCROLL_SECONDS.key);
        assertTrue(found instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow row = (HushgramPreferenceFragment.SliderRow) found;
        PreferenceGroup group = (PreferenceGroup) row.getParent();
        int toggleAt = -1;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (Settings.FEED_AUTO_SCROLL.key.equals(group.getPreference(i).getKey())) toggleAt = i;
        }
        assertTrue("its switch is in the same section", toggleAt >= 0);
        assertEquals("right under its switch", row, group.getPreference(toggleAt + 1));
        assertEquals("Time on each post", String.valueOf(row.getTitle()));
        assertEquals("Turn on Auto scroll in Home to use this slider.", String.valueOf(row.getSummary()));

        SwitchPreference parent = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FEED_AUTO_SCROLL.key);
        parent.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertEquals("6 seconds (standard)", String.valueOf(row.getSummary()));
        SeekBar slider = sliderOf(row);
        assertEquals("3 to 30 seconds a second at a time", 27, slider.getMax());
        assertEquals(3, slider.getProgress());
        row.choose(10);
        ShadowLooper.idleMainLooper();
        assertEquals(10, (int) Settings.FEED_AUTO_SCROLL_SECONDS.savedValue());
        assertEquals("10 seconds", String.valueOf(row.getSummary()));
        assertFalse("read at each post", Settings.FEED_AUTO_SCROLL_SECONDS.rebootApp);
        assertFalse(AbstractPreferenceFragment.restartPending.contains(Settings.FEED_AUTO_SCROLL_SECONDS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FEED_AUTO_SCROLL_SECONDS.key));
    }

    private SeekBar sliderOf(Preference row) {
        View view = row.getView(null, (ViewGroup) controller.get().findViewById(android.R.id.content));
        View slider = view.findViewWithTag("hushgram_slider");
        assertTrue("the row carries its slider", slider instanceof SeekBar);
        return (SeekBar) slider;
    }
}
