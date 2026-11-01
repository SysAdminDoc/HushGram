/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.StoryTimeSettingsTest.sectionOf;
import static org.junit.Assert.*;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import org.robolectric.shadows.ShadowSeekBar;

import app.hushgram.extension.instagram.misc.GlassCorners;
import app.hushgram.extension.instagram.misc.GlassHeight;
import app.hushgram.extension.instagram.misc.GlassLift;
import app.hushgram.extension.instagram.misc.GlassReelsHide;
import app.hushgram.extension.instagram.misc.HapticStyle;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Glass tab bar's section, Saved on your profile's switch and Stop background wake-ups' Battery section: each
 * shows only with its patch in the build, and each main switch starts off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class TabBarAndBatterySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        reset();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        reset();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private static void reset() {
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_HAPTICS.resetToDefault();
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.resetToDefault();
        Settings.GLASS_TAB_BAR_SLIDE.resetToDefault();
        Settings.GLASS_TAB_BAR_LIQUID.resetToDefault();
        Settings.GLASS_TAB_BAR_KEEP_SIZE.resetToDefault();
        Settings.GLASS_TAB_BAR_REFRACT.resetToDefault();
        Settings.GLASS_TAB_BAR_HAPTIC_STYLE.resetToDefault();
        Settings.GLASS_TAB_BAR_OPACITY.resetToDefault();
        Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.resetToDefault();
        Settings.GLASS_TAB_BAR_HEIGHT.resetToDefault();
        Settings.GLASS_TAB_BAR_CORNERS.resetToDefault();
        Settings.GLASS_TAB_BAR_LIFT.resetToDefault();
        Settings.GLASS_TAB_BAR_BLUR_RADIUS.resetToDefault();
        Settings.GLASS_TAB_BAR_REELS_HIDE.resetToDefault();
        Settings.GLASS_TAB_BAR_FLOAT.resetToDefault();
        Settings.SAVED_ON_PROFILE.resetToDefault();
        Settings.STOP_HEARTBEAT.resetToDefault();
        Settings.STOP_UPLOAD_ALARM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private void open(PatchFamily... families) throws Exception {
        PatchFamily.inBuildForTests = families.length == 0 ? EnumSet.noneOf(PatchFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private Preference row(String key) {
        return page.getPreferenceScreen().findPreference(key);
    }

    @Test public void withoutThePatchesNoneOfTheirRowsShow() throws Exception {
        open();
        for (String key : List.of(Settings.GLASS_TAB_BAR.key, Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key, Settings.GLASS_TAB_BAR_OPACITY.key, Settings.GLASS_TAB_BAR_HEIGHT.key,
                Settings.GLASS_TAB_BAR_CORNERS.key, Settings.GLASS_TAB_BAR_LIFT.key, Settings.GLASS_TAB_BAR_BLUR_RADIUS.key, Settings.GLASS_TAB_BAR_REELS_HIDE.key, Settings.SAVED_ON_PROFILE.key, Settings.STOP_HEARTBEAT.key,
                Settings.STOP_UPLOAD_ALARM.key)) {
            assertNull(key, row(key));
        }
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key));
    }

    @Test public void theTabBarSectionHoldsItsSwitchesAndTheTickChoice() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        SwitchPreference glass = (SwitchPreference) row(Settings.GLASS_TAB_BAR.key);
        assertNotNull(glass);
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), glass).getTitle()));
        assertFalse(glass.isChecked());
        assertTrue(Settings.GLASS_TAB_BAR.rebootApp);
        assertEquals(Arrays.asList(Settings.GLASS_TAB_BAR, Settings.GLASS_TAB_BAR_BLUR, Settings.GLASS_TAB_BAR_FLOAT,
                Settings.GLASS_TAB_BAR_HAPTICS, Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL, Settings.GLASS_TAB_BAR_SLIDE,
                Settings.GLASS_TAB_BAR_LIQUID, Settings.GLASS_TAB_BAR_KEEP_SIZE, Settings.GLASS_TAB_BAR_REFRACT,
                Settings.GLASS_TAB_BAR_REELS_BEHIND),
                PatchFamily.GLASS_TAB_BAR.switches);
        for (String key : List.of(Settings.GLASS_TAB_BAR_BLUR.key, Settings.GLASS_TAB_BAR_HAPTICS.key, Settings.GLASS_TAB_BAR_FLOAT.key,
                Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.key, Settings.GLASS_TAB_BAR_SLIDE.key, Settings.GLASS_TAB_BAR_LIQUID.key,
                Settings.GLASS_TAB_BAR_KEEP_SIZE.key, Settings.GLASS_TAB_BAR_REFRACT.key)) {
            assertNotNull(key, row(key));
        }

        Preference found = row(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key);
        assertTrue(found instanceof HushgramPreferenceFragment.HapticStyleRow);
        HushgramPreferenceFragment.HapticStyleRow tick = (HushgramPreferenceFragment.HapticStyleRow) found;
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : tick.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Short tick", "System tick", "Soft tick", "Full tick"), entries);
        assertEquals("SYSTEM", tick.getValue());
        assertEquals("System tick", String.valueOf(tick.getSummary()));
        tick.setValue("SOFT");
        ShadowLooper.idleMainLooper();
        assertEquals(HapticStyle.SOFT, Settings.GLASS_TAB_BAR_HAPTIC_STYLE.savedValue());
        assertEquals("Soft tick", String.valueOf(tick.getSummary()));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key));
    }

    /** Tint intensity (#391) is a slider in steps of 5%, from see-through to past solid, starting on today's tint. */
    @Test public void opacityIsASliderAndHeightAListBothStartingOnTodaysLookAndBackedUp() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        Preference opacityFound = row(Settings.GLASS_TAB_BAR_OPACITY.key);
        assertTrue(opacityFound instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow opacity = (HushgramPreferenceFragment.SliderRow) opacityFound;
        assertEquals("Tint intensity", String.valueOf(opacity.getTitle()));
        assertEquals("100% (standard)", String.valueOf(opacity.getSummary()));
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), opacity).getTitle()));
        assertFalse("a change shows on the bar at once", Settings.GLASS_TAB_BAR_OPACITY.rebootApp);

        SeekBar slider = sliderOf(opacity);
        assertEquals("0% to 150% in 5% notches", 30, slider.getMax());
        assertEquals(20, slider.getProgress());
        opacity.choose(50);
        ShadowLooper.idleMainLooper();
        assertEquals(50, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        assertEquals("50%", String.valueOf(opacity.getSummary()));
        assertFalse(AbstractPreferenceFragment.restartPending.contains(Settings.GLASS_TAB_BAR_OPACITY.key));
        assertEquals(10, sliderOf(opacity).getProgress());

        Preference heightFound = row(Settings.GLASS_TAB_BAR_HEIGHT.key);
        assertTrue(heightFound instanceof HushgramPreferenceFragment.GlassHeightRow);
        HushgramPreferenceFragment.GlassHeightRow height = (HushgramPreferenceFragment.GlassHeightRow) heightFound;
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : height.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Compact", "Standard", "Tall"), entries);
        assertEquals("STANDARD", height.getValue());
        height.setValue("TALL");
        ShadowLooper.idleMainLooper();
        assertEquals(GlassHeight.TALL, Settings.GLASS_TAB_BAR_HEIGHT.savedValue());
        assertEquals("Tall", String.valueOf(height.getSummary()));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_OPACITY.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_HEIGHT.key));
    }

    /**
     * Tab bar corners (#144) is a list right after Tab bar height, starting on the corners the bar has always
     * had, backed up, and a pick waits on a restart. It rests while the liquid look, always fully round, is on.
     */
    @Test public void tabBarCornersFollowTheHeightAndRestUnderTheLiquidLook() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        Preference found = row(Settings.GLASS_TAB_BAR_CORNERS.key);
        assertTrue(found instanceof HushgramPreferenceFragment.GlassCornersRow);
        HushgramPreferenceFragment.GlassCornersRow corners = (HushgramPreferenceFragment.GlassCornersRow) found;
        assertEquals("Tab bar corners", String.valueOf(corners.getTitle()));
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), corners);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int heightAt = -1;
        int cornersAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.GLASS_TAB_BAR_HEIGHT.key.equals(key)) heightAt = i;
            if (Settings.GLASS_TAB_BAR_CORNERS.key.equals(key)) cornersAt = i;
        }
        assertEquals("right after Tab bar height", heightAt + 1, cornersAt);
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : corners.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Standard", "Rounder", "Fully round"), entries);
        assertEquals("STANDARD", corners.getValue());
        assertEquals("Standard", String.valueOf(corners.getSummary()));
        assertTrue(Settings.GLASS_TAB_BAR_CORNERS.rebootApp);
        corners.setValue("ROUND");
        ShadowLooper.idleMainLooper();
        assertEquals(GlassCorners.ROUND, Settings.GLASS_TAB_BAR_CORNERS.savedValue());
        assertEquals("Fully round", String.valueOf(corners.getSummary()));
        assertTrue(AbstractPreferenceFragment.restartPending.contains(Settings.GLASS_TAB_BAR_CORNERS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_CORNERS.key));

        Settings.GLASS_TAB_BAR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_CORNERS.isAvailable());
        Settings.GLASS_TAB_BAR_LIQUID.save(true);
        assertFalse("the liquid look is always round", Settings.GLASS_TAB_BAR_CORNERS.isAvailable());
        Settings.GLASS_TAB_BAR_LIQUID.save(false);
        Settings.GLASS_TAB_BAR.save(false);
        assertFalse("no glass, no corners to pick", Settings.GLASS_TAB_BAR_CORNERS.isAvailable());
        assertEquals(Collections.singletonList(Settings.GLASS_TAB_BAR), Settings.GLASS_TAB_BAR_CORNERS.getParentSettings());
    }

    /**
     * Blur strength (#289, a slider since #391) sits right after Blur behind the tab bar, starts on the blur the
     * pill has always had, is backed up, shows on the bar without a restart, and is offered only where that
     * blur applies.
     */
    @Test public void blurStrengthFollowsTheBlurSwitchStartsOnStandardAndIsBackedUp() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        Preference found = row(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key);
        assertTrue(found instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow strength = (HushgramPreferenceFragment.SliderRow) found;
        assertEquals("Blur strength", String.valueOf(strength.getTitle()));
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), strength);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int blurAt = -1;
        int strengthAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.GLASS_TAB_BAR_BLUR.key.equals(key)) blurAt = i;
            if (Settings.GLASS_TAB_BAR_BLUR_RADIUS.key.equals(key)) strengthAt = i;
        }
        assertEquals("right after Blur behind the tab bar", blurAt + 1, strengthAt);
        assertEquals("18 dp (standard)", String.valueOf(strength.getSummary()));
        assertFalse("the row opens no dialog, the slider is the control", strength.isSelectable());
        assertFalse(Settings.GLASS_TAB_BAR_BLUR_RADIUS.rebootApp);

        SeekBar slider = sliderOf(strength);
        assertEquals("2 dp to 48 dp a notch at a time", 46, slider.getMax());
        assertEquals(16, slider.getProgress());
        assertEquals("Blur strength", String.valueOf(slider.getContentDescription()));
        SeekBar.OnSeekBarChangeListener listener = ((ShadowSeekBar) org.robolectric.Shadows.shadowOf(slider)).getOnSeekBarChangeListener();
        assertNotNull(listener);
        // A drag shows each value as it goes and saves once it's let go.
        listener.onStartTrackingTouch(slider);
        slider.setProgress(28);
        listener.onProgressChanged(slider, 28, true);
        TextView summary = (TextView) ((View) slider.getParent()).findViewById(android.R.id.summary);
        assertEquals("30 dp", String.valueOf(summary.getText()));
        assertEquals("not saved mid-drag", 18, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        listener.onStopTrackingTouch(slider);
        ShadowLooper.idleMainLooper();
        assertEquals(30, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertEquals("30 dp", String.valueOf(strength.getSummary()));
        // A key or a screen reader moves it a notch with no drag, and each notch saves.
        slider.setProgress(29);
        listener.onProgressChanged(slider, 29, true);
        ShadowLooper.idleMainLooper();
        assertEquals(31, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertFalse(AbstractPreferenceFragment.restartPending.contains(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key));

        Settings.GLASS_TAB_BAR.save(false);
        Settings.GLASS_TAB_BAR_BLUR.save(true);
        assertFalse("no glass bar, no blur to size", Settings.GLASS_TAB_BAR_BLUR_RADIUS.isAvailable());
        Settings.GLASS_TAB_BAR.save(true);
        Settings.GLASS_TAB_BAR_BLUR.save(false);
        assertFalse("blur is off", Settings.GLASS_TAB_BAR_BLUR_RADIUS.isAvailable());
        Settings.GLASS_TAB_BAR_BLUR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_BLUR_RADIUS.isAvailable());
        assertEquals(Collections.singletonList(Settings.GLASS_TAB_BAR_BLUR), Settings.GLASS_TAB_BAR_BLUR_RADIUS.getParentSettings());
    }

    /** The slider the row puts under its summary, from a freshly bound view of the row. */
    private SeekBar sliderOf(Preference row) {
        View view = row.getView(null, (ViewGroup) controller.get().findViewById(android.R.id.content));
        View slider = view.findViewWithTag("hushgram_slider");
        assertTrue("the row carries its slider", slider instanceof SeekBar);
        return (SeekBar) slider;
    }

    /**
     * Space under the tab bar (#87) is a list right after Show content behind the tab bar, starting on
     * where the pill has always sat, offered only while that switch is on, and backed up.
     */
    @Test public void spaceUnderTheTabBarFollowsShowContentBehindItAndIsBackedUp() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        Preference found = row(Settings.GLASS_TAB_BAR_LIFT.key);
        assertTrue(found instanceof HushgramPreferenceFragment.GlassLiftRow);
        HushgramPreferenceFragment.GlassLiftRow lift = (HushgramPreferenceFragment.GlassLiftRow) found;
        assertEquals("Space under the tab bar", String.valueOf(lift.getTitle()));
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), lift).getTitle()));
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), lift);
        int floatAt = -1;
        int liftAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.GLASS_TAB_BAR_FLOAT.key.equals(key)) floatAt = i;
            if (Settings.GLASS_TAB_BAR_LIFT.key.equals(key)) liftAt = i;
        }
        assertEquals("right after Show content behind the tab bar", floatAt + 1, liftAt);
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : lift.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Standard", "A little more", "More"), entries);
        assertEquals("STANDARD", lift.getValue());
        assertEquals("Standard", String.valueOf(lift.getSummary()));
        lift.setValue("MOST");
        ShadowLooper.idleMainLooper();
        assertEquals(GlassLift.MOST, Settings.GLASS_TAB_BAR_LIFT.savedValue());
        assertEquals("More", String.valueOf(lift.getSummary()));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_LIFT.key));

        Settings.GLASS_TAB_BAR_FLOAT.save(true);
        assertFalse("the pill floats, but there's no glass bar", Settings.GLASS_TAB_BAR_LIFT.isAvailable());
        Settings.GLASS_TAB_BAR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_LIFT.isAvailable());
        Settings.GLASS_TAB_BAR_FLOAT.save(false);
        assertFalse("nothing floats, so there's no space to choose", Settings.GLASS_TAB_BAR_LIFT.isAvailable());
    }

    /**
     * Hide the tab bar on Reels (#170) is a list right after Hide the tab bar as you scroll, starting on
     * Never. Its summary says what the pick does and how the bar comes back, it's backed up, and a pick
     * waits on a restart.
     */
    @Test public void hideTheTabBarOnReelsStartsOnNeverSaysHowTheBarComesBackAndIsBackedUp() throws Exception {
        assertEquals(GlassReelsHide.NEVER, Settings.GLASS_TAB_BAR_REELS_HIDE.defaultValue);
        assertEquals(0L, GlassReelsHide.NEVER.millis);
        open(PatchFamily.GLASS_TAB_BAR);
        Preference found = row(Settings.GLASS_TAB_BAR_REELS_HIDE.key);
        assertTrue(found instanceof HushgramPreferenceFragment.GlassReelsHideRow);
        HushgramPreferenceFragment.GlassReelsHideRow reels = (HushgramPreferenceFragment.GlassReelsHideRow) found;
        assertEquals("Hide the tab bar on Reels", String.valueOf(reels.getTitle()));
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), reels);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int scrollAt = -1;
        int reelsAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.key.equals(key)) scrollAt = i;
            if (Settings.GLASS_TAB_BAR_REELS_HIDE.key.equals(key)) reelsAt = i;
        }
        assertTrue(scrollAt >= 0);
        assertEquals("right after Hide the tab bar as you scroll", scrollAt + 1, reelsAt);
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : reels.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Never", "After 2 seconds", "After 3 seconds", "After 5 seconds", "After 8 seconds"), entries);
        List<String> values = new ArrayList<>();
        for (CharSequence value : reels.getEntryValues()) values.add(String.valueOf(value));
        assertEquals(Arrays.asList("NEVER", "TWO_SECONDS", "THREE_SECONDS", "FIVE_SECONDS", "EIGHT_SECONDS"), values);
        assertEquals("NEVER", reels.getValue());
        assertEquals("Never. Pick a time and the pill slides away once a reel has played that long without a touch. "
                + "Restart Instagram after changing it.", String.valueOf(reels.getSummary()));
        assertFalse("nothing owed before a pick",
                AbstractPreferenceFragment.restartPending.contains(Settings.GLASS_TAB_BAR_REELS_HIDE.key));

        reels.setValue("THREE_SECONDS");
        ShadowLooper.idleMainLooper();
        assertEquals(GlassReelsHide.THREE_SECONDS, Settings.GLASS_TAB_BAR_REELS_HIDE.savedValue());
        assertEquals(3_000L, Settings.GLASS_TAB_BAR_REELS_HIDE.savedValue().millis);
        assertEquals("On Reels, the pill slides away after 3 seconds without a touch. Tap the bottom edge of the screen, "
                + "change tab or press Back to bring it back. Restart Instagram after changing it.",
                String.valueOf(reels.getSummary()));
        assertTrue(AbstractPreferenceFragment.restartPending.contains(Settings.GLASS_TAB_BAR_REELS_HIDE.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_REELS_HIDE.key));

        reels.setValue("NEVER");
        ShadowLooper.idleMainLooper();
        assertEquals(GlassReelsHide.NEVER, Settings.GLASS_TAB_BAR_REELS_HIDE.savedValue());
        assertTrue(String.valueOf(reels.getSummary()), String.valueOf(reels.getSummary()).startsWith("Never. "));
        assertFalse("back where it started, nothing is owed",
                AbstractPreferenceFragment.restartPending.contains(Settings.GLASS_TAB_BAR_REELS_HIDE.key));
    }

    /** It goes by Glass tab bar alone. The scroll switch above it has no say either way. */
    @Test public void hideTheTabBarOnReelsGoesByTheGlassBarNotTheScrollSwitch() {
        Settings.GLASS_TAB_BAR.save(true);
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(false);
        assertTrue("it needs the glass bar, not the scroll switch", Settings.GLASS_TAB_BAR_REELS_HIDE.isAvailable());
        Settings.GLASS_TAB_BAR.save(false);
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.save(true);
        assertFalse("no glass bar, nothing to slide away", Settings.GLASS_TAB_BAR_REELS_HIDE.isAvailable());
    }

    /**
     * Slide across the tabs (#136) is a switch right before Glass tab bar, since Instagram's own bar slides too
     * (#201), off to start, offered with or without the glass, backed up, and read as the bar is built, so it
     * asks for a restart. Pause turns it off.
     */
    @Test public void slideAcrossTheTabsStartsOffBeforeTheGlassAndAsksForARestart() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        SwitchPreference slide = (SwitchPreference) row(Settings.GLASS_TAB_BAR_SLIDE.key);
        assertNotNull(slide);
        assertEquals("Slide across the tabs", String.valueOf(slide.getTitle()));
        assertEquals("Put a finger on the tab bar and slide it sideways. A highlight follows your finger, and letting "
                + "go opens the tab under it. Taps and long presses on a tab work as before. It works with or without "
                + "Glass tab bar. Restart Instagram after changing it.", String.valueOf(slide.getSummary()));
        assertFalse(slide.isChecked());
        assertFalse(Settings.GLASS_TAB_BAR_SLIDE.defaultValue);
        assertTrue(Settings.GLASS_TAB_BAR_SLIDE.rebootApp);
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), slide);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int slideAt = -1;
        int glassAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.GLASS_TAB_BAR_SLIDE.key.equals(key)) slideAt = i;
            if (Settings.GLASS_TAB_BAR.key.equals(key)) glassAt = i;
        }
        assertTrue(glassAt >= 0);
        assertEquals("right before Glass tab bar", glassAt - 1, slideAt);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_SLIDE.key));

        Settings.GLASS_TAB_BAR.save(false);
        assertTrue("Instagram's own bar slides too", Settings.GLASS_TAB_BAR_SLIDE.isAvailable());
        Settings.GLASS_TAB_BAR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_SLIDE.isAvailable());

        Settings.GLASS_TAB_BAR_SLIDE.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_SLIDE.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, no slide", Settings.GLASS_TAB_BAR_SLIDE.get());
        assertTrue(Settings.GLASS_TAB_BAR_SLIDE.savedValue());
    }

    /**
     * The liquid glass look (#65) is a switch right after Blur behind the tab bar, off to start, offered with the
     * glass bar, backed up, and read as the bar is built, so it asks for a restart. Pause turns it off.
     */
    @Test public void theLiquidLookStartsOffAfterTheBlurAndAsksForARestart() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        SwitchPreference liquid = (SwitchPreference) row(Settings.GLASS_TAB_BAR_LIQUID.key);
        assertNotNull(liquid);
        assertEquals("Liquid glass look", String.valueOf(liquid.getTitle()));
        assertEquals("Rounds the pill and its highlight and puts Search in a glass circle of its own. Scrolling a list "
                + "down shrinks the bar to the tab you're on and Search, unless Hide the tab bar as you scroll is on. "
                + "Restart Instagram after changing it.", String.valueOf(liquid.getSummary()));
        assertFalse(liquid.isChecked());
        assertFalse(Settings.GLASS_TAB_BAR_LIQUID.defaultValue);
        assertTrue(Settings.GLASS_TAB_BAR_LIQUID.rebootApp);
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), liquid);
        assertEquals("Tab bar", String.valueOf(section.getTitle()));
        int blurAt = -1;
        int tintAt = -1;
        int liquidAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.GLASS_TAB_BAR_BLUR_RADIUS.key.equals(key)) blurAt = i;
            if (Settings.GLASS_TAB_BAR_OPACITY.key.equals(key)) tintAt = i;
            if (Settings.GLASS_TAB_BAR_LIQUID.key.equals(key)) liquidAt = i;
        }
        assertEquals("Tint right after Blur strength, so both share a screen with the preview", blurAt + 1, tintAt);
        assertEquals("right after Tint", tintAt + 1, liquidAt);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_LIQUID.key));

        Settings.GLASS_TAB_BAR.save(false);
        assertFalse("no glass bar, no liquid look", Settings.GLASS_TAB_BAR_LIQUID.isAvailable());
        Settings.GLASS_TAB_BAR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_LIQUID.isAvailable());

        Settings.GLASS_TAB_BAR_LIQUID.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_LIQUID.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, the floating bar of before", Settings.GLASS_TAB_BAR_LIQUID.get());
        assertTrue(Settings.GLASS_TAB_BAR_LIQUID.savedValue());
    }

    /**
     * Keep the pill full size (#256) is a switch right after the liquid look's rim and its slider, off to start,
     * offered while the liquid look is on, backed up, and read as the bar is built, so it asks for a restart.
     */
    @Test public void keepThePillFullSizeSitsUnderTheLiquidLookAndStartsOff() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        SwitchPreference keep = (SwitchPreference) row(Settings.GLASS_TAB_BAR_KEEP_SIZE.key);
        assertNotNull(keep);
        assertEquals("Keep the pill full size", String.valueOf(keep.getTitle()));
        assertEquals("Scrolling a list down leaves the liquid pill as it is, every tab in place. Restart Instagram "
                + "after changing it.", String.valueOf(keep.getSummary()));
        assertFalse(keep.isChecked());
        assertFalse(Settings.GLASS_TAB_BAR_KEEP_SIZE.defaultValue);
        assertTrue(Settings.GLASS_TAB_BAR_KEEP_SIZE.rebootApp);
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), keep);
        int strengthAt = -1;
        int keepAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.key.equals(key)) strengthAt = i;
            if (Settings.GLASS_TAB_BAR_KEEP_SIZE.key.equals(key)) keepAt = i;
        }
        assertTrue(strengthAt >= 0);
        assertEquals("right after the refraction slider", strengthAt + 1, keepAt);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_KEEP_SIZE.key));

        assertFalse("no liquid look, nothing to keep", Settings.GLASS_TAB_BAR_KEEP_SIZE.isAvailable());
        Settings.GLASS_TAB_BAR_LIQUID.save(true);
        assertFalse("the liquid switch is on, but the glass bar above it is off",
                Settings.GLASS_TAB_BAR_KEEP_SIZE.isAvailable());
        Settings.GLASS_TAB_BAR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_KEEP_SIZE.isAvailable());
    }

    /**
     * Bend light at the edges (#369) is a switch right after the liquid look, off to start, offered
     * while the liquid look is on, backed up, and read as the bar is built, so it asks for a restart.
     */
    @Test public void bendLightAtTheEdgesSitsUnderTheLiquidLookAndStartsOff() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        SwitchPreference refract = (SwitchPreference) row(Settings.GLASS_TAB_BAR_REFRACT.key);
        assertNotNull(refract);
        assertEquals("Bend light at the edges", String.valueOf(refract.getTitle()));
        assertEquals("The liquid pill's rim bends what's behind it like real glass, so what passes under the edge "
                + "shows upside down. Android 13 and newer. Restart Instagram after changing it.",
                String.valueOf(refract.getSummary()));
        assertFalse(refract.isChecked());
        assertFalse(Settings.GLASS_TAB_BAR_REFRACT.defaultValue);
        assertTrue(Settings.GLASS_TAB_BAR_REFRACT.rebootApp);
        PreferenceGroup section = sectionOf(page.getPreferenceScreen(), refract);
        int liquidAt = -1;
        int refractAt = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            String key = section.getPreference(i).getKey();
            if (Settings.GLASS_TAB_BAR_LIQUID.key.equals(key)) liquidAt = i;
            if (Settings.GLASS_TAB_BAR_REFRACT.key.equals(key)) refractAt = i;
        }
        assertTrue(liquidAt >= 0);
        assertEquals("right after Liquid glass look", liquidAt + 1, refractAt);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_REFRACT.key));

        assertFalse("no liquid look, no rim", Settings.GLASS_TAB_BAR_REFRACT.isAvailable());
        Settings.GLASS_TAB_BAR.save(true);
        Settings.GLASS_TAB_BAR_LIQUID.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_REFRACT.isAvailable());
    }

    /**
     * A choice two levels under the glass bar rests while the bar is off, even with the switch right
     * above it on, so settings search doesn't offer it (found 2026-10-19: parent() asked one level up).
     */
    @Test public void aChoiceTwoLevelsDownRestsWhileTheGlassBarIsOff() throws Exception {
        assertTrue("Haptics starts on", Settings.GLASS_TAB_BAR_HAPTICS.savedValue());
        assertFalse(Settings.GLASS_TAB_BAR.savedValue());
        assertFalse(Settings.GLASS_TAB_BAR_HAPTICS.isAvailable());
        assertFalse("its switch is on, but the glass bar is off", Settings.GLASS_TAB_BAR_HAPTIC_STYLE.isAvailable());
        open(PatchFamily.GLASS_TAB_BAR);
        Preference style = row(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key);
        assertNotNull(style);
        assertFalse(style.isEnabled());

        Settings.GLASS_TAB_BAR.save(true);
        assertTrue(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.isAvailable());
        Settings.GLASS_TAB_BAR_HAPTICS.save(false);
        assertFalse(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.isAvailable());
    }

    /** Every wait on the list is a whole number of seconds, shortest first, so the label and the timer agree. */
    @Test public void theReelsWaitsAreWholeSecondsInOrder() {
        long last = -1;
        for (GlassReelsHide delay : GlassReelsHide.values()) {
            assertTrue(delay.name(), delay.millis > last);
            assertEquals(delay.name(), 0L, delay.millis % 1000);
            last = delay.millis;
            String label = HushgramPreferenceFragment.glassReelsHideLabel(delay);
            String summary = HushgramPreferenceFragment.glassReelsHideSummary(delay);
            if (delay == GlassReelsHide.NEVER) {
                assertEquals("Never", label);
            } else {
                assertEquals("After " + delay.millis / 1000 + " seconds", label);
                assertTrue(summary, summary.contains("after " + delay.millis / 1000 + " seconds"));
                assertTrue(summary, summary.contains("press Back"));
            }
            assertTrue(summary, summary.endsWith("Restart Instagram after changing it."));
        }
    }

    /**
     * The pill's height, its corners, the space under it and the wait on Reels are read as Instagram builds
     * the bar, so a pick waits on a restart. The page says so and the screen offers the restart; without
     * that, a pick looked like it did nothing (#87, on the S22). The opacity and blur sliders are read on
     * every draw (#391), so moving them owes nothing.
     */
    @Test public void theGlassChoicesAskForARestart() throws Exception {
        for (Setting<?> setting : List.<Setting<?>>of(Settings.GLASS_TAB_BAR_HEIGHT,
                Settings.GLASS_TAB_BAR_CORNERS, Settings.GLASS_TAB_BAR_LIFT, Settings.GLASS_TAB_BAR_REELS_HIDE)) {
            assertTrue(setting.key, setting.rebootApp);
        }
        for (Setting<?> setting : List.<Setting<?>>of(Settings.GLASS_TAB_BAR_OPACITY, Settings.GLASS_TAB_BAR_BLUR_RADIUS)) {
            assertFalse(setting.key, setting.rebootApp);
        }
        open(PatchFamily.GLASS_TAB_BAR);
        assertFalse("nothing owed yet", page.restartActionAvailable());

        ((HushgramPreferenceFragment.SliderRow) row(Settings.GLASS_TAB_BAR_OPACITY.key)).choose(70);
        ((HushgramPreferenceFragment.SliderRow) row(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key)).choose(40);
        ShadowLooper.idleMainLooper();
        assertEquals(70, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        assertEquals(40, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertFalse("the sliders show at once", page.restartActionAvailable());

        HushgramPreferenceFragment.GlassLiftRow lift = (HushgramPreferenceFragment.GlassLiftRow) row(Settings.GLASS_TAB_BAR_LIFT.key);
        lift.setValue("MORE");
        ShadowLooper.idleMainLooper();
        assertTrue(AbstractPreferenceFragment.restartPending.contains(Settings.GLASS_TAB_BAR_LIFT.key));
        assertTrue("the screen offers the restart", page.restartActionAvailable());

        lift.setValue("STANDARD");
        ShadowLooper.idleMainLooper();
        assertFalse("back where it started, nothing is owed",
                AbstractPreferenceFragment.restartPending.contains(Settings.GLASS_TAB_BAR_LIFT.key));
        assertFalse(page.restartActionAvailable());
    }

    @Test public void savedOnYourProfileStartsOffUnderProfiles() throws Exception {
        open(PatchFamily.SAVED_ON_PROFILE);
        SwitchPreference saved = (SwitchPreference) row(Settings.SAVED_ON_PROFILE.key);
        assertNotNull(saved);
        assertEquals("Saved tab on your profile", String.valueOf(saved.getTitle()));
        assertEquals("Profiles", String.valueOf(sectionOf(page.getPreferenceScreen(), saved).getTitle()));
        assertFalse(saved.isChecked());
        assertTrue(Settings.SAVED_ON_PROFILE.rebootApp);
    }

    @Test public void batteryHoldsBothAlarmSwitchesAndBothStartOff() throws Exception {
        open(PatchFamily.STOP_HEARTBEAT);
        SwitchPreference heartbeat = (SwitchPreference) row(Settings.STOP_HEARTBEAT.key);
        SwitchPreference upload = (SwitchPreference) row(Settings.STOP_UPLOAD_ALARM.key);
        assertNotNull(heartbeat);
        assertNotNull(upload);
        assertEquals("Battery", String.valueOf(sectionOf(page.getPreferenceScreen(), heartbeat).getTitle()));
        assertSame(sectionOf(page.getPreferenceScreen(), heartbeat), sectionOf(page.getPreferenceScreen(), upload));
        assertFalse(heartbeat.isChecked());
        assertFalse(upload.isChecked());
        assertEquals(Arrays.asList(Settings.STOP_HEARTBEAT, Settings.STOP_UPLOAD_ALARM), PatchFamily.STOP_HEARTBEAT.switches);
    }
}
