/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Text size (#682): with a size other than 100%, Instagram's text draws at the phone's size times
 * that share, in the application's resources and in every screen's, and stays so after the phone's
 * settings change or a screen comes back. At 100%, paused or without the patch, nothing changes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 33})
public class TextSizeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;

    @Before
    public void prepare() {
        TextSize.resetForTests();
        TextSize.inBuildForTests = true;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @After
    public void restore() {
        if (controller != null) controller.close();
        TextSize.apply(RuntimeEnvironment.getApplication().getResources(), 1f);
        TextSize.resetForTests();
        Settings.TEXT_SIZE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private static float phone() {
        return Resources.getSystem().getConfiguration().fontScale;
    }

    private static float scaleOf(Resources resources) {
        return resources.getConfiguration().fontScale;
    }

    private Activity screen() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        return controller.get();
    }

    @Test
    public void applySetsTheFontScaleAndTheTextDensity() {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        assertTrue(TextSize.apply(resources, 1.5f));
        assertEquals(phone() * 1.5f, scaleOf(resources), 0.001f);
        DisplayMetrics metrics = resources.getDisplayMetrics();
        assertEquals("sp sizes follow", metrics.density * phone() * 1.5f, metrics.scaledDensity, 0.01f);
        assertFalse("already so, nothing to do", TextSize.apply(resources, 1.5f));
        assertFalse("never throws", TextSize.apply(null, 1.5f));
        assertTrue(TextSize.apply(resources, 1f));
        assertEquals(phone(), scaleOf(resources), 0.001f);
    }

    @Test
    public void startsAt100WhichChangesNothing() {
        assertEquals(100, (int) Settings.TEXT_SIZE.defaultValue);
        assertEquals(1f, TextSize.scale(), 0f);
        Application application = RuntimeEnvironment.getApplication();
        TextSize.install(application);
        assertEquals(phone(), scaleOf(application.getResources()), 0.001f);
        assertEquals(phone(), scaleOf(screen().getResources()), 0.001f);
    }

    @Test
    public void aBiggerSizeReachesTheApplicationAndEveryScreen() {
        Settings.TEXT_SIZE.save(150);
        Application application = RuntimeEnvironment.getApplication();
        TextSize.install(application);
        assertEquals(phone() * 1.5f, scaleOf(application.getResources()), 0.001f);

        Activity activity = screen();
        assertEquals("the screen made after", phone() * 1.5f, scaleOf(activity.getResources()), 0.001f);
        TextView text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals("10 sp text draws half again as big", 10 * density * phone() * 1.5f, text.getTextSize(), 0.5f);
    }

    @Test
    public void aSmallerSizeWorksTheSameWay() {
        Settings.TEXT_SIZE.save(80);
        TextSize.install(RuntimeEnvironment.getApplication());
        assertEquals(phone() * 0.8f, scaleOf(screen().getResources()), 0.001f);
    }

    @Test
    public void aScreenComingBackIsSizedAgain() {
        Settings.TEXT_SIZE.save(150);
        TextSize.install(RuntimeEnvironment.getApplication());
        Activity activity = screen();
        TextSize.apply(activity.getResources(), 1f);
        assertEquals(phone(), scaleOf(activity.getResources()), 0.001f);
        controller.pause().resume();
        assertEquals(phone() * 1.5f, scaleOf(activity.getResources()), 0.001f);
    }

    @Test
    public void aChangeOfThePhonesSettingsIsSizedAgain() {
        Settings.TEXT_SIZE.save(150);
        Application application = RuntimeEnvironment.getApplication();
        TextSize.install(application);
        // The phone's change puts its own scale back in the application's resources.
        TextSize.apply(application.getResources(), 1f);
        application.onConfigurationChanged(new Configuration(application.getResources().getConfiguration()));
        ShadowLooper.idleMainLooper();
        assertEquals(phone() * 1.5f, scaleOf(application.getResources()), 0.001f);
    }

    @Test
    public void installsOnceAndOnlyOnTheApplication() {
        Settings.TEXT_SIZE.save(150);
        Application application = RuntimeEnvironment.getApplication();
        TextSize.install(screenContext());
        assertEquals("a screen's context isn't the application", phone(), scaleOf(application.getResources()), 0.001f);
        TextSize.install(application);
        TextSize.apply(application.getResources(), 1f);
        TextSize.install(application);
        assertEquals("a second install does nothing", phone(), scaleOf(application.getResources()), 0.001f);
    }

    private android.content.Context screenContext() {
        return RuntimeEnvironment.getApplication().getBaseContext();
    }

    @Test
    public void pausedOrWithoutThePatchNothingChanges() {
        Settings.TEXT_SIZE.save(150);
        Application application = RuntimeEnvironment.getApplication();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        TextSize.install(application);
        assertEquals("paused", phone(), scaleOf(application.getResources()), 0.001f);
        PauseForTests.resume();

        TextSize.inBuildForTests = false;
        TextSize.install(application);
        assertEquals("not in the build", phone(), scaleOf(application.getResources()), 0.001f);
        assertEquals("the saved size stays", 150, (int) Settings.TEXT_SIZE.savedValue());
        Configuration phones = new Configuration(Resources.getSystem().getConfiguration());
        assertSame("Instagram's resources take the phone's as is", phones, TextSize.sized(phones));
    }

    /**
     * Instagram's own resources, which keep a configuration of their own, as the Font patch leaves
     * them: each configuration they take goes through {@link TextSize#sized} first (#782).
     */
    private static final class InstagramResources extends Resources {
        @SuppressWarnings("deprecation")
        InstagramResources(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
        }

        @Override
        @SuppressWarnings("deprecation")
        public void updateConfiguration(Configuration config, DisplayMetrics metrics) {
            super.updateConfiguration(TextSize.sized(config), metrics);
        }
    }

    @Test
    public void sizedAnswersACopyAtThePhonesSizeTimesTheShare() {
        Settings.TEXT_SIZE.save(125);
        TextSize.install(RuntimeEnvironment.getApplication());
        Configuration phones = new Configuration(Resources.getSystem().getConfiguration());
        Configuration sized = TextSize.sized(phones);
        assertNotSame(phones, sized);
        assertEquals(phone() * 1.25f, sized.fontScale, 0.001f);
        assertEquals("the phone's own stays as it was", phone(), phones.fontScale, 0.001f);
        assertEquals("nothing else changes", phones.densityDpi, sized.densityDpi);
        assertSame("already so, nothing to do", sized, TextSize.sized(sized));
        assertNull(TextSize.sized(null));
    }

    @Test
    public void sizedLeavesEveryConfigurationAloneUntilInstalledAndAt100() {
        Configuration phones = new Configuration(Resources.getSystem().getConfiguration());
        assertSame("not installed yet", phones, TextSize.sized(phones));
        TextSize.install(RuntimeEnvironment.getApplication());
        assertSame("100% installs nothing", phones, TextSize.sized(phones));
    }

    @Test
    public void instagramsResourcesKeepTheSizeWhenThePhonesConfigurationIsCopiedIn() {
        Settings.TEXT_SIZE.save(125);
        Application application = RuntimeEnvironment.getApplication();
        TextSize.install(application);
        Resources instagrams = new InstagramResources(application.getResources());
        TextSize.apply(instagrams, 1.25f);
        assertEquals("sized first", phone() * 1.25f, scaleOf(instagrams), 0.001f);
        // A change of the phone's settings: Instagram copies the phone's configuration back in.
        Configuration phones = new Configuration(Resources.getSystem().getConfiguration());
        //noinspection deprecation
        instagrams.updateConfiguration(phones, instagrams.getDisplayMetrics());
        assertEquals(phone() * 1.25f, scaleOf(instagrams), 0.001f);
        DisplayMetrics metrics = instagrams.getDisplayMetrics();
        assertEquals("sp sizes follow", metrics.density * phone() * 1.25f, metrics.scaledDensity, 0.01f);
        assertEquals("the phone's own stays as it was", phone(), phones.fontScale, 0.001f);
    }
}
