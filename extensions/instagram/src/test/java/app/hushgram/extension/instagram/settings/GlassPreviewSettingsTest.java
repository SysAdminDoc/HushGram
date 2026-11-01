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

    @Test public void theRefractionSliderStartsOnTheStandardBendAndSavesWithoutARestart() throws Exception {
        open();
        Preference found = row(Settings.GLASS_TAB_BAR_REFRACT_STRENGTH.key);
        assertTrue(found instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow slider = (HushgramPreferenceFragment.SliderRow) found;
        assertEquals("Refraction intensity", String.valueOf(slider.getTitle()));
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
