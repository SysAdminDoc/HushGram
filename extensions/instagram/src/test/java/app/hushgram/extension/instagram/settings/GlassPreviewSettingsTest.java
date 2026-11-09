/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.misc.GlassPreview;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;

/** The glass tab bar's sample and its refraction slider on the settings page (#391). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class GlassPreviewSettingsTest {
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
        Settings.GLASS_TAB_BAR_LIQUID.resetToDefault();
        Settings.GLASS_TAB_BAR_REFRACT.resetToDefault();
        Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.resetToDefault();
        Settings.GLASS_TAB_BAR_OPACITY.resetToDefault();
        Settings.GLASS_TAB_BAR_BLUR.resetToDefault();
        Settings.GLASS_TAB_BAR_BLUR_RADIUS.resetToDefault();
    }

    private void open() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.copyOf(Arrays.asList(PatchFamily.GLASS_TAB_BAR));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private Preference row(String key) {
        return page.getPreferenceScreen().findPreference(key);
    }

    /** Turns the switch at [key] on the way a tap does, so the rows under it hear about it. */
    private void check(String key) {
        ((SwitchPreference) row(key)).setChecked(true);
        ShadowLooper.idleMainLooper();
    }

    @Test public void theRefractionSliderStartsOnTheStandardBendAndSavesWithoutARestart() throws Exception {
        open();
        Preference found = row(Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.key);
        assertTrue(found instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow slider = (HushgramPreferenceFragment.SliderRow) found;
        assertEquals("Refraction intensity", String.valueOf(slider.getTitle()));
        // Greyed out, the slider names the nearest switch that would wake it, from the bar itself inward.
        assertFalse(slider.isEnabled());
        assertEquals("Turn on Glass tab bar to use this slider.", String.valueOf(slider.getSummary()));
        check(Settings.GLASS_TAB_BAR.key);
        // The standard pill bends too, so the liquid look isn't in the way (#391); the blur starts on.
        assertFalse(Settings.GLASS_TAB_BAR_LIQUID.savedValue());
        assertEquals("Turn on Bend light at the edges to use this slider.", String.valueOf(slider.getSummary()));
        check(Settings.GLASS_TAB_BAR_REFRACT.key);
        assertTrue(slider.isEnabled());
        assertEquals("100% (standard)", String.valueOf(slider.getSummary()));
        assertEquals(100, (int) Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.defaultValue);
        assertEquals(0, slider.valueAt(0));
        assertEquals(200, slider.valueAt(20));
        assertEquals(200, slider.valueAt(99));
        assertFalse(Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.key));

        slider.choose(140);
        ShadowLooper.idleMainLooper();
        assertEquals(140, (int) Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.savedValue());
        assertEquals("140%", String.valueOf(slider.getSummary()));
        Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.save(900);
        assertEquals(200, (int) Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.get());
    }

    @Test public void theTintSliderIsNamedForWhatItDoes() throws Exception {
        open();
        assertEquals("Tint intensity", String.valueOf(row(Settings.GLASS_TAB_BAR_OPACITY.key).getTitle()));
    }

    /** A greyed-out slider says which switch to turn on, in place of a value it can't change. */
    @Test public void aGreyedOutSliderNamesTheSwitchItWaitsOn() throws Exception {
        open();
        HushgramPreferenceFragment.SliderRow tint = (HushgramPreferenceFragment.SliderRow) row(Settings.GLASS_TAB_BAR_OPACITY.key);
        HushgramPreferenceFragment.SliderRow blur = (HushgramPreferenceFragment.SliderRow) row(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key);
        assertFalse(tint.isEnabled());
        assertEquals("Turn on Glass tab bar to use this slider.", String.valueOf(tint.getSummary()));
        assertEquals("the switch two levels up is the one resting",
                "Turn on Glass tab bar to use this slider.", String.valueOf(blur.getSummary()));

        ((SwitchPreference) row(Settings.GLASS_TAB_BAR.key)).setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(tint.isEnabled());
        assertEquals("100% (standard)", String.valueOf(tint.getSummary()));
        assertTrue(blur.isEnabled());
        assertEquals(blur.label(Settings.GLASS_TAB_BAR_BLUR_RADIUS.get()), String.valueOf(blur.getSummary()));

        ((SwitchPreference) row(Settings.GLASS_TAB_BAR_BLUR.key)).setChecked(false);
        ShadowLooper.idleMainLooper();
        assertFalse(blur.isEnabled());
        assertEquals("Turn on Blur behind the tab bar to use this slider.", String.valueOf(blur.getSummary()));
        assertEquals("the tint slider waits on nothing that's off", "100% (standard)", String.valueOf(tint.getSummary()));
    }

    /** A switch that's greyed out itself can't be turned on, so it isn't named even when nothing above it can be. */
    @Test public void aGreyedOutSwitchIsNeverTheOneNamed() throws Exception {
        open();
        HushgramPreferenceFragment.SliderRow blur = (HushgramPreferenceFragment.SliderRow) row(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key);
        // A row with no title can't name its switch. Removing the row wouldn't do it, since the
        // page still finds rows kept for search.
        row(Settings.GLASS_TAB_BAR.key).setTitle("");
        assertFalse(Settings.GLASS_TAB_BAR_BLUR.isAvailable());

        page.updatePreferenceAvailability(blur, Settings.GLASS_TAB_BAR_BLUR_RADIUS);
        assertFalse(blur.isEnabled());
        assertEquals(blur.label(Settings.GLASS_TAB_BAR_BLUR_RADIUS.get()), String.valueOf(blur.getSummary()));
    }

    /** The sample's glass sits on what the bar sits on: black in Instagram's dark theme, white in its light one. */
    @Test @Config(qualifiers = "night") public void theSampleIsDarkGlassInTheDarkTheme() throws Exception {
        open();
        GlassPreviewRow sample = (GlassPreviewRow) row("hushgram_glass_preview");
        GlassPreview view = (GlassPreview) sample.onCreateView(null);
        sample.onBindView(view);
        assertEquals(Color.BLACK, view.base());
    }

    @Test @Config(qualifiers = "notnight") public void theSampleIsLightGlassInTheLightTheme() throws Exception {
        open();
        GlassPreviewRow sample = (GlassPreviewRow) row("hushgram_glass_preview");
        GlassPreview view = (GlassPreview) sample.onCreateView(null);
        sample.onBindView(view);
        assertEquals(Color.WHITE, view.base());
    }

    @Test public void theSampleSitsAboveTheBlurTintAndBendSlidersAndFollowsThem() throws Exception {
        open();
        Preference sample = row("hushgram_glass_preview");
        assertTrue(sample instanceof GlassPreviewRow);
        int at = indexOf(sample);
        assertTrue("above the blur slider", at < indexOf(row(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key)));
        assertTrue("above the tint slider", at < indexOf(row(Settings.GLASS_TAB_BAR_OPACITY.key)));
        assertTrue("above the bend slider", at < indexOf(row(Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.key)));

        View made = ((GlassPreviewRow) sample).onCreateView(null);
        assertTrue(made instanceof GlassPreview);
        GlassPreview view = (GlassPreview) made;
        HushgramPreferenceFragment.SliderRow blur = (HushgramPreferenceFragment.SliderRow) row(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key);
        HushgramPreferenceFragment.SliderRow tint = (HushgramPreferenceFragment.SliderRow) row(Settings.GLASS_TAB_BAR_OPACITY.key);
        HushgramPreferenceFragment.SliderRow bend = (HushgramPreferenceFragment.SliderRow) row(Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.key);

        int savedBlur = Settings.GLASS_TAB_BAR_BLUR_RADIUS.get();
        blur.live.accept(7);
        tint.live.accept(30);
        bend.live.accept(160);
        assertEquals(7, view.blurDp());
        assertEquals(30, view.tintPercent());
        assertEquals(160, view.bendPercent());
        assertEquals("the saved values wait for the thumb to lift", savedBlur, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.get());
        blur.live.accept(-1);
        tint.live.accept(-1);
        bend.live.accept(-1);
        assertEquals(savedBlur, view.blurDp());
        assertEquals(100, view.tintPercent());
        assertEquals("no bend while its switch is off", 0, view.bendPercent());
        Settings.GLASS_TAB_BAR_REFRACT.save(true);
        assertEquals(100, view.bendPercent());
    }

    @Test public void theSampleDrawsTheTintTheSliderHolds() throws Exception {
        open();
        GlassPreview view = (GlassPreview) ((GlassPreviewRow) row("hushgram_glass_preview")).onCreateView(null);
        view.setBase(Color.BLACK);
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.live(GlassPreview.TINT, 0);
        view.draw(new Canvas(bitmap));
        assertEquals(0, Color.alpha(view.pillTint()));
        view.live(GlassPreview.TINT, 100);
        view.draw(new Canvas(bitmap));
        int standard = Color.alpha(view.pillTint());
        view.live(GlassPreview.TINT, 150);
        view.draw(new Canvas(bitmap));
        assertTrue(standard > 0);
        assertTrue(Color.alpha(view.pillTint()) > standard);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theBackdropStaysBrightWhenTheSampleRedraws() throws Exception {
        open();
        GlassPreview view = (GlassPreview) ((GlassPreviewRow) row("hushgram_glass_preview")).onCreateView(null);
        view.setBase(Color.BLACK);
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        int[] corners = new int[3];
        int[] tints = {100, 40, 0};
        for (int draw = 0; draw < tints.length; draw++) {
            // The black settings page under the row, as on the phone.
            bitmap.eraseColor(Color.BLACK);
            view.live(GlassPreview.TINT, tints[draw]);
            view.draw(new Canvas(bitmap));
            corners[draw] = bitmap.getPixel(2, 2);
        }
        assertTrue("the first draw shows the blue end of the scene: " + Integer.toHexString(corners[0]),
                Color.blue(corners[0]) > 200);
        assertEquals("a redraw keeps the scene as bright", corners[0], corners[1]);
        assertEquals("a redraw keeps the scene as bright", corners[0], corners[2]);
    }

    private int indexOf(Preference preference) {
        for (int category = 0; category < page.getPreferenceScreen().getPreferenceCount(); category++) {
            Preference candidate = page.getPreferenceScreen().getPreference(category);
            if (candidate instanceof PreferenceGroup) {
                PreferenceGroup group = (PreferenceGroup) candidate;
                for (int at = 0; at < group.getPreferenceCount(); at++) {
                    if (group.getPreference(at) == preference) return at;
                }
            }
        }
        fail("not on the page: " + preference.getKey());
        return -1;
    }
}
