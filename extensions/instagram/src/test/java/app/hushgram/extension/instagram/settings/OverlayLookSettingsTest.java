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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.EnumSetting;
import app.hushgram.extension.shared.settings.IntegerSetting;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.hushgram.extension.instagram.feed.OverlayLook;
import app.hushgram.extension.instagram.feed.SeekShape;

/**
 * Pill look and the two Seek bar looks (#471): each sits right under the switch it styles, starts solid,
 * offers see-through and frosted glass, asks for its switch while that's off and travels with an
 * exported configuration. The two double tap skips' Skip length sliders (#645) sit under their switches the
 * same way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class OverlayLookSettingsTest {
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
        Settings.CAROUSEL_DOTS_PILL.resetToDefault();
        Settings.CAROUSEL_PILL_LOOK.resetToDefault();
        Settings.FEED_SEEK_BAR.resetToDefault();
        Settings.FEED_SEEK_BUTTON.resetToDefault();
        Settings.FEED_SEEK_BAR_LOOK.resetToDefault();
        Settings.STORY_SEEK_BAR.resetToDefault();
        Settings.STORY_SEEK_BAR_LOOK.resetToDefault();
        Settings.SEEK_BAR_SHAPE.resetToDefault();
        Settings.FEED_DOUBLE_TAP_SKIP.resetToDefault();
        Settings.FEED_DOUBLE_TAP_SKIP_SECONDS.resetToDefault();
        Settings.DOUBLE_TAP_SKIP_REELS.resetToDefault();
        Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.resetToDefault();
    }

    /**
     * #645: each double tap skip gets a Skip length slider right under its switch, 5 to 30 seconds in 5 second
     * notches, starting at the 10 seconds it always was, asking for its switch while that's off, saving at once
     * and travelling with an export.
     */
    @Test public void skipLengthSitsUnderEachDoubleTapSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SEEK_BAR, PatchFamily.REEL_DOUBLE_TAP_SKIP));
        assertSkipLength(Settings.FEED_DOUBLE_TAP_SKIP, Settings.FEED_DOUBLE_TAP_SKIP_SECONDS, "Double tap to skip on feed videos");
        assertSkipLength(Settings.DOUBLE_TAP_SKIP_REELS, Settings.DOUBLE_TAP_SKIP_REELS_SECONDS, "Double tap to skip");
    }

    @Test public void withoutThePatchesThereIsNoSkipLength() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        for (String key : Arrays.asList(Settings.FEED_DOUBLE_TAP_SKIP_SECONDS.key, Settings.DOUBLE_TAP_SKIP_REELS_SECONDS.key)) {
            assertNull(key, page.getPreferenceScreen().findPreference(key));
            assertFalse(key, ConfigurationBackup.eligible().containsKey(key));
        }
    }

    private void assertSkipLength(BooleanSetting toggle, IntegerSetting seconds, String toggleTitle) {
        Preference found = page.getPreferenceScreen().findPreference(seconds.key);
        assertTrue(seconds.key, found instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow row = (HushgramPreferenceFragment.SliderRow) found;
        PreferenceGroup group = (PreferenceGroup) row.getParent();
        int toggleAt = -1;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (toggle.key.equals(group.getPreference(i).getKey())) toggleAt = i;
        }
        assertTrue("its switch is in the same section", toggleAt >= 0);
        assertEquals("right under its switch", row, group.getPreference(toggleAt + 1));
        assertEquals("Skip length", String.valueOf(row.getTitle()));
        assertEquals("Turn on " + toggleTitle + " to use this slider.", String.valueOf(row.getSummary()));

        SwitchPreference parent = (SwitchPreference) page.getPreferenceScreen().findPreference(toggle.key);
        parent.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertEquals("10 seconds (standard)", String.valueOf(row.getSummary()));
        SeekBar slider = sliderOf(row);
        assertEquals("5 to 30 seconds in 5 second notches", 5, slider.getMax());
        assertEquals(1, slider.getProgress());
        row.choose(25);
        ShadowLooper.idleMainLooper();
        assertEquals(25, (int) seconds.savedValue());
        assertEquals("25 seconds", String.valueOf(row.getSummary()));
        assertFalse("shows on the next double tap", seconds.rebootApp);
        assertFalse(AbstractPreferenceFragment.restartPending.contains(seconds.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(seconds.key));
    }

    private SeekBar sliderOf(Preference row) {
        View view = row.getView(null, (ViewGroup) controller.get().findViewById(android.R.id.content));
        View slider = view.findViewWithTag("hushgram_slider");
        assertTrue("the row carries its slider", slider instanceof SeekBar);
        return (SeekBar) slider;
    }

    /** #586: the shape sits right under the feed's look, covers every seek bar and travels with an export. */
    @Test public void seekShapeSitsUnderTheFeedLookAndTakesAnySeekBar() throws Exception {
        Settings.STORY_SEEK_BAR.save(true);
        open(EnumSet.of(PatchFamily.FEED_SEEK_BAR));
        Preference found = page.getPreferenceScreen().findPreference(Settings.SEEK_BAR_SHAPE.key);
        assertTrue(found instanceof HushgramPreferenceFragment.ChoiceRow);
        HushgramPreferenceFragment.ChoiceRow<?> row = (HushgramPreferenceFragment.ChoiceRow<?>) found;
        PreferenceGroup group = (PreferenceGroup) row.getParent();
        assertEquals("Feed", String.valueOf(group.getTitle()));
        int lookAt = -1;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (Settings.FEED_SEEK_BAR_LOOK.key.equals(group.getPreference(i).getKey())) lookAt = i;
        }
        assertTrue(lookAt >= 0);
        assertEquals("right under the look", row, group.getPreference(lookAt + 1));
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Standard", "Thin line", "Rounded", "Expressive", "Wave"), entries);
        assertEquals("STANDARD", row.getValue());
        assertEquals("The bar and round handle you know.", String.valueOf(row.getSummary()));

        row.setValue("WAVE");
        ShadowLooper.idleMainLooper();
        assertEquals(SeekShape.WAVE, Settings.SEEK_BAR_SHAPE.savedValue());
        assertEquals("A wavy line for the part played, with a tall handle.", String.valueOf(row.getSummary()));

        // #630: the shape also takes Instagram's own story progress segments and a chat video's bar,
        // which are always there, so it stays a live choice with every seek bar switch off.
        Settings.STORY_SEEK_BAR.save(false);
        ShadowLooper.idleMainLooper();
        row.showSummary();
        assertTrue(Settings.SEEK_BAR_SHAPE.isAvailable());
        assertEquals("A wavy line for the part played, with a tall handle.", String.valueOf(row.getSummary()));
        assertEquals("the choice is kept", SeekShape.WAVE, Settings.SEEK_BAR_SHAPE.savedValue());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SEEK_BAR_SHAPE.key));
    }

    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    /** The look row for [setting], checked to sit right under [above] in [section]. */
    private HushgramPreferenceFragment.OverlayLookRow rowUnder(EnumSetting<OverlayLook> setting, String above, String section) {
        Preference found = page.getPreferenceScreen().findPreference(setting.key);
        assertTrue("no look to pick with the patch in the build", found instanceof HushgramPreferenceFragment.OverlayLookRow);
        HushgramPreferenceFragment.OverlayLookRow row = (HushgramPreferenceFragment.OverlayLookRow) found;
        PreferenceGroup group = (PreferenceGroup) row.getParent();
        assertEquals(section, String.valueOf(group.getTitle()));
        int aboveAt = -1;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (above.equals(group.getPreference(i).getKey())) aboveAt = i;
        }
        assertTrue("its switch is in the same section", aboveAt >= 0);
        assertEquals("right under its switch", row, group.getPreference(aboveAt + 1));
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : row.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Solid", "See-through", "Frosted glass"), entries);
        List<String> values = new ArrayList<>();
        for (CharSequence value : row.getEntryValues()) values.add(String.valueOf(value));
        assertEquals(Arrays.asList("SOLID", "SEE_THROUGH", "FROSTED"), values);
        assertEquals("SOLID", row.getValue());
        return row;
    }

    @Test public void missingPatchesHaveNoLooks() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        for (EnumSetting<OverlayLook> each : Arrays.asList(Settings.CAROUSEL_PILL_LOOK, Settings.FEED_SEEK_BAR_LOOK,
                Settings.STORY_SEEK_BAR_LOOK)) {
            assertNull(each.key, page.getPreferenceScreen().findPreference(each.key));
            assertFalse(each.key, ConfigurationBackup.eligible().containsKey(each.key));
        }
    }

    @Test public void pillLookSitsUnderThePillSwitchAndSaysWhatEachLookIs() throws Exception {
        Settings.CAROUSEL_DOTS_PILL.save(true);
        open(EnumSet.of(PatchFamily.CAROUSEL_ARROWS));
        HushgramPreferenceFragment.OverlayLookRow row = rowUnder(Settings.CAROUSEL_PILL_LOOK, Settings.CAROUSEL_DOTS_PILL.key, "Feed");
        assertEquals("Pill look", String.valueOf(row.getTitle()));
        assertEquals("A light pill with dark dots, as it's always been.", String.valueOf(row.getSummary()));

        row.setValue("SEE_THROUGH");
        ShadowLooper.idleMainLooper();
        assertEquals(OverlayLook.SEE_THROUGH, Settings.CAROUSEL_PILL_LOOK.savedValue());
        assertEquals("A see-through dark pill with white dots, so more of the photo shows.", String.valueOf(row.getSummary()));

        row.setValue("FROSTED");
        ShadowLooper.idleMainLooper();
        assertEquals(OverlayLook.FROSTED, Settings.CAROUSEL_PILL_LOOK.savedValue());
        assertEquals("A frosted glass pill that blurs the photo under it. Before Android 12 it's see-through instead.",
                String.valueOf(row.getSummary()));

        Settings.CAROUSEL_DOTS_PILL.save(false);
        ShadowLooper.idleMainLooper();
        row.showSummary();
        assertEquals("Turn on Page dots on a pill to use this choice.", String.valueOf(row.getSummary()));
        assertEquals(OverlayLook.FROSTED, Settings.CAROUSEL_PILL_LOOK.savedValue());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CAROUSEL_PILL_LOOK.key));
    }

    /** The feed's look styles the bar whichever of its two switches shows it, so either one is enough. */
    @Test public void feedSeekBarLookSitsUnderTheButtonSwitchAndTakesEitherSwitch() throws Exception {
        Settings.FEED_SEEK_BUTTON.save(true);
        open(EnumSet.of(PatchFamily.FEED_SEEK_BAR));
        HushgramPreferenceFragment.OverlayLookRow row = rowUnder(Settings.FEED_SEEK_BAR_LOOK, Settings.FEED_SEEK_BUTTON.key, "Feed");
        assertEquals("Seek bar look", String.valueOf(row.getTitle()));
        assertEquals("A white bar and handle, as they've always been.", String.valueOf(row.getSummary()));

        row.setValue("FROSTED");
        ShadowLooper.idleMainLooper();
        assertEquals(OverlayLook.FROSTED, Settings.FEED_SEEK_BAR_LOOK.savedValue());
        assertEquals("A white bar on a strip of frosted glass that blurs the video under it. Before Android 12 the strip is see-through instead.",
                String.valueOf(row.getSummary()));

        Settings.FEED_SEEK_BUTTON.save(false);
        Settings.FEED_SEEK_BAR.save(true);
        ShadowLooper.idleMainLooper();
        row.showSummary();
        assertTrue(Settings.FEED_SEEK_BAR_LOOK.isAvailable());

        Settings.FEED_SEEK_BAR.save(false);
        ShadowLooper.idleMainLooper();
        row.showSummary();
        assertFalse(Settings.FEED_SEEK_BAR_LOOK.isAvailable());
        assertEquals("Turn on Seek bar on feed videos or its button to use this choice.", String.valueOf(row.getSummary()));
        assertEquals(OverlayLook.FROSTED, Settings.FEED_SEEK_BAR_LOOK.savedValue());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FEED_SEEK_BAR_LOOK.key));
    }

    @Test public void storySeekBarLookSitsUnderTheStorySwitch() throws Exception {
        Settings.STORY_SEEK_BAR.save(true);
        open(EnumSet.of(PatchFamily.STORY_SEEK_BAR));
        HushgramPreferenceFragment.OverlayLookRow row = rowUnder(Settings.STORY_SEEK_BAR_LOOK, Settings.STORY_SEEK_BAR.key, "Stories");
        assertEquals("Seek bar look", String.valueOf(row.getTitle()));

        row.setValue("SEE_THROUGH");
        ShadowLooper.idleMainLooper();
        assertEquals(OverlayLook.SEE_THROUGH, Settings.STORY_SEEK_BAR_LOOK.savedValue());
        assertEquals("A fainter bar and handle, so more of the video shows through.", String.valueOf(row.getSummary()));

        Settings.STORY_SEEK_BAR.save(false);
        ShadowLooper.idleMainLooper();
        row.showSummary();
        assertEquals("Turn on Seek bar on stories to use this choice.", String.valueOf(row.getSummary()));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_SEEK_BAR_LOOK.key));
    }
}
