/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When HushGram launch screen tells Android which launch screen to show, and what it tells it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
public class LaunchScreenTest {
    private static final int THEME = 0x7f1405b7;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Android's side: the theme this build has, and every theme it's told to use. */
    private static final class FakeSplash implements LaunchScreen.Splash {
        int theme = THEME;
        RuntimeException failure;
        /** Runs while Android is being told, as the activity going away at that moment would. */
        Runnable whileTelling;
        final List<Integer> told = new ArrayList<>();
        /** Every theme looked up by name. */
        final List<String> asked = new ArrayList<>();

        @Override public int theme(Activity activity, String name) {
            asked.add(name);
            return theme;
        }

        @Override public void use(Activity activity, int theme) {
            if (failure != null) throw failure;
            told.add(theme);
            if (whileTelling != null) whileTelling.run();
        }
    }

    private final FakeSplash splash = new FakeSplash();
    private ActivityController<Activity> controller;
    private Activity activity;

    @Before
    public void prepare() {
        reset();
        LaunchScreen.splash = splash;
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
    }

    @After
    public void restore() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        reset();
        LaunchScreen.splash = new LaunchScreen.AndroidSplash();
    }

    private void reset() {
        Settings.LAUNCH_SCREEN.resetToDefault();
        Settings.LAUNCH_SCREEN_ACCENT.resetToDefault();
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_CUSTOM.resetToDefault();
        LaunchScreen.accentInBuildForTests = null;
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        PauseForTests.resume();
        File marker = HushgramPause.markerFile(Utils.getContext());
        if (marker != null) marker.delete();
        Utils.getContext().getSharedPreferences(LaunchScreen.STORE, Context.MODE_PRIVATE).edit().clear().commit();
        LaunchScreen.forgetForTests();
        HookStatus.clear();
    }

    private String counted(String what) {
        return FamilyNames.LAUNCH_SCREEN + ": invoked 1, 0 found, 0 missing. Counted: " + what + " 1";
    }

    private static android.content.SharedPreferences store() {
        return Utils.getContext().getSharedPreferences(LaunchScreen.STORE, Context.MODE_PRIVATE);
    }

    private String lastAsked() {
        return splash.asked.get(splash.asked.size() - 1);
    }

    /** Off to start, the way a patched Instagram first opens, and so is its accent color child. */
    @Test
    public void offToStart() {
        assertEquals(Boolean.FALSE, Settings.LAUNCH_SCREEN.defaultValue);
        assertTrue("Android shows the change at the next start", Settings.LAUNCH_SCREEN.rebootApp);
        assertEquals("", LaunchScreen.wanted(activity));
        assertEquals(Boolean.FALSE, Settings.LAUNCH_SCREEN_ACCENT.defaultValue);
        assertTrue(Settings.LAUNCH_SCREEN_ACCENT.rebootApp);
    }

    /**
     * With the child switch on, the theme filled with the accent color is handed over instead (#528),
     * a new color hands its own over, and switching the child off brings the plain theme back.
     */
    @Test
    public void theAccentSwitchHandsTheFilledThemeOver() {
        LaunchScreen.accentInBuildForTests = true;
        Settings.LAUNCH_SCREEN.save(true);
        Settings.LAUNCH_SCREEN_ACCENT.save(true);
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.PINE);

        assertEquals(LaunchScreen.THEME + "_PINE", LaunchScreen.wanted(activity));
        assertTrue(LaunchScreen.follow(activity));
        assertEquals(LaunchScreen.THEME + "_PINE", lastAsked());
        assertEquals(LaunchScreen.THEME + "_PINE", store().getString(LaunchScreen.HANDED, null));
        assertEquals(Collections.singletonList(counted(LaunchScreen.SET)), HookStatus.report());
        assertFalse(LaunchScreen.follow(activity));

        Settings.ACCENT_HUE.save(AccentHue.ORANGE);
        assertTrue("a new color is handed over", LaunchScreen.follow(activity));
        assertEquals(LaunchScreen.THEME + "_ORANGE", lastAsked());

        Settings.LAUNCH_SCREEN_ACCENT.save(false);
        assertTrue(LaunchScreen.follow(activity));
        assertEquals(LaunchScreen.THEME, lastAsked());
        assertEquals(LaunchScreen.THEME, store().getString(LaunchScreen.HANDED, null));
        assertEquals(List.of(THEME, THEME, THEME), splash.told);

        Settings.LAUNCH_SCREEN_ACCENT.save(true);
        Settings.LAUNCH_SCREEN.save(false);
        assertEquals("the child alone shows nothing", "", LaunchScreen.wanted(activity));
    }

    /**
     * Instagram's blue fills the screen while Accent color is off, blue is picked, no color has
     * been typed, or the build has no Accent color. Material You keeps its own theme.
     */
    @Test
    public void theFillIsInstagramsBlueWithoutAnAccent() {
        LaunchScreen.accentInBuildForTests = true;
        Settings.ACCENT_HUE.save(AccentHue.PINK);
        assertSame("Accent color off", AccentHue.BLUE, LaunchScreen.fill());
        Settings.ACCENT_COLOR.save(true);
        assertSame(AccentHue.PINK, LaunchScreen.fill());
        Settings.ACCENT_HUE.save(AccentHue.BLUE);
        assertSame(AccentHue.BLUE, LaunchScreen.fill());
        Settings.ACCENT_HUE.save(AccentHue.CUSTOM);
        assertSame("no color typed", AccentHue.BLUE, LaunchScreen.fill());
        Settings.ACCENT_HUE.save(AccentHue.WALLPAPER);
        assertSame(AccentHue.WALLPAPER, LaunchScreen.fill());
        LaunchScreen.accentInBuildForTests = false;
        assertSame("no Accent color in this build", AccentHue.BLUE, LaunchScreen.fill());
    }

    /** A typed color takes the nearest color with a theme of its own, Instagram's blue among them. */
    @Test
    public void aTypedColorTakesTheNearestColor() {
        LaunchScreen.accentInBuildForTests = true;
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.CUSTOM);
        Settings.ACCENT_CUSTOM.save("#ff1010");
        assertSame(AccentHue.RED, LaunchScreen.fill());
        Settings.ACCENT_CUSTOM.save("2a6f69");
        assertSame(AccentHue.PINE, LaunchScreen.fill());
        Settings.ACCENT_CUSTOM.save("#0a90f0");
        assertSame(AccentHue.BLUE, LaunchScreen.fill());
        Settings.ACCENT_CUSTOM.save("not a color");
        assertSame(AccentHue.BLUE, LaunchScreen.fill());

        for (AccentHue hue : AccentHue.values()) {
            if (hue.fill != 0) assertSame(hue, LaunchScreen.nearest(hue.fill));
        }
        assertSame(AccentHue.BLUE, LaunchScreen.nearest(LaunchScreen.INSTAGRAM_BLUE));
    }

    /**
     * A build before #528 kept true or false under its old key, which counts as the plain theme or
     * Instagram's own, and goes once the answer is written again.
     */
    @Test
    public void anOldAnswerCarriesOver() {
        store().edit().putBoolean(LaunchScreen.APPLIED, false).commit();
        assertFalse("Instagram's own was already handed back", LaunchScreen.follow(activity));
        assertTrue(splash.told.isEmpty());

        LaunchScreen.forgetForTests();
        store().edit().putBoolean(LaunchScreen.APPLIED, true).commit();
        assertTrue("HushGram's was handed over, so Instagram's goes back", LaunchScreen.follow(activity));
        assertEquals(Collections.singletonList(0), splash.told);
        assertEquals("", store().getString(LaunchScreen.HANDED, null));
        assertFalse("the old key goes", store().contains(LaunchScreen.APPLIED));
    }

    /**
     * Switched on, HushGram's theme is handed to Android once, counted and kept, and the next
     * resume in the same process asks Android for nothing.
     */
    @Test
    public void switchedOnHushGramsThemeIsHandedOverOnce() {
        Settings.LAUNCH_SCREEN.save(true);

        assertTrue(LaunchScreen.follow(activity));
        assertEquals(Collections.singletonList(THEME), splash.told);
        assertEquals(Collections.singletonList(counted(LaunchScreen.SET)), HookStatus.report());
        assertEquals(LaunchScreen.THEME, lastAsked());
        assertEquals(LaunchScreen.THEME, store().getString(LaunchScreen.HANDED, null));

        assertFalse(LaunchScreen.follow(activity));
        assertFalse(LaunchScreen.follow(activity));
        assertEquals(Collections.singletonList(THEME), splash.told);
    }

    /** A new process with the switch still on tells Android again, in case it lost the answer. */
    @Test
    public void eachProcessHandsHushGramsThemeOverOnce() {
        Settings.LAUNCH_SCREEN.save(true);
        assertTrue(LaunchScreen.follow(activity));

        LaunchScreen.forgetForTests();

        assertTrue(LaunchScreen.follow(activity));
        assertFalse(LaunchScreen.follow(activity));
        assertEquals(List.of(THEME, THEME), splash.told);
    }

    /** Switched off again, Instagram's own screen is handed back once, and stays back in later processes. */
    @Test
    public void switchedOffInstagramsScreenIsHandedBackOnce() {
        Settings.LAUNCH_SCREEN.save(true);
        assertTrue(LaunchScreen.follow(activity));
        HookStatus.clear();

        Settings.LAUNCH_SCREEN.save(false);
        assertTrue(LaunchScreen.follow(activity));
        assertEquals(List.of(THEME, 0), splash.told);
        assertEquals(Collections.singletonList(counted(LaunchScreen.CLEARED)), HookStatus.report());

        assertFalse(LaunchScreen.follow(activity));
        LaunchScreen.forgetForTests();
        assertFalse("a later process has nothing to hand back", LaunchScreen.follow(activity));
        assertEquals(List.of(THEME, 0), splash.told);
    }

    /**
     * With nothing kept, as on a first start, Instagram's screen is handed back once, so a theme
     * Android kept from an earlier install doesn't linger. After that, nothing.
     */
    @Test
    public void withNothingKeptInstagramsScreenIsHandedBackOnce() {
        assertTrue(LaunchScreen.follow(activity));
        assertEquals(Collections.singletonList(0), splash.told);
        LaunchScreen.forgetForTests();
        assertFalse(LaunchScreen.follow(activity));
        assertEquals(Collections.singletonList(0), splash.told);
    }

    /** A start that will run paused shows Instagram's screen, whichever way it's paused, and the switch is kept. */
    @Test
    public void aPausedNextStartGetsInstagramsScreen() throws Exception {
        Settings.LAUNCH_SCREEN.save(true);
        assertTrue(LaunchScreen.follow(activity));

        BaseSettings.PAUSED.save(true);
        assertEquals("", LaunchScreen.wanted(activity));
        assertTrue(LaunchScreen.follow(activity));
        assertEquals(List.of(THEME, 0), splash.told);
        BaseSettings.PAUSED.save(false);
        assertTrue(LaunchScreen.follow(activity));

        BaseSettings.SAFE_MODE.save(true);
        assertEquals("", LaunchScreen.wanted(activity));
        BaseSettings.SAFE_MODE.save(false);

        File marker = HushgramPause.markerFile(activity);
        assertTrue(marker.getParentFile().isDirectory() || marker.getParentFile().mkdirs());
        assertTrue(marker.createNewFile());
        assertEquals("", LaunchScreen.wanted(activity));
        assertTrue(LaunchScreen.follow(activity));
        assertEquals(List.of(THEME, 0, THEME, 0), splash.told);
        assertTrue("the switch itself stays on", Settings.LAUNCH_SCREEN.savedValue());
    }

    /** A build without the theme names the miss and leaves Instagram's screen. */
    @Test
    public void aBuildWithoutTheThemeNamesTheMiss() {
        Settings.LAUNCH_SCREEN.save(true);
        splash.theme = 0;

        assertTrue(LaunchScreen.follow(activity));

        assertEquals(Collections.singletonList(0), splash.told);
        assertEquals(1, HookStatus.missing(FamilyNames.LAUNCH_SCREEN).size());
        assertTrue(HookStatus.missing(FamilyNames.LAUNCH_SCREEN).get(0).contains(LaunchScreen.THEME));
    }

    /** The test app has no such theme, so Android's own lookup answers 0 rather than another theme. */
    @Test
    public void androidsLookupFindsNoThemeInABuildWithout() {
        assertEquals(0, new LaunchScreen.AndroidSplash().theme(activity, LaunchScreen.THEME));
    }

    /** Android refusing is recorded and nothing is kept, so a later resume tries again. */
    @Test
    public void aRefusalIsRecordedAndTriedAgain() {
        Settings.LAUNCH_SCREEN.save(true);
        splash.failure = new IllegalStateException("refused");

        assertFalse(LaunchScreen.follow(activity));
        assertEquals(1, HookStatus.missing(FamilyNames.LAUNCH_SCREEN).size());
        assertFalse(store().contains(LaunchScreen.HANDED));

        splash.failure = null;
        assertTrue(LaunchScreen.follow(activity));
        assertEquals(Collections.singletonList(THEME), splash.told);
    }

    /**
     * Android ignores the call for an activity that's gone, so an activity destroyed while Android
     * is told keeps nothing and counts nothing, and the next activity to resume tells it again. One
     * already destroyed isn't used at all.
     */
    @Test
    public void anActivityGoneByTheEndOfTheCallIsToldAgain() {
        Settings.LAUNCH_SCREEN.save(true);
        splash.whileTelling = () -> controller.pause().stop().destroy();

        assertFalse(LaunchScreen.follow(activity));
        assertEquals(Collections.singletonList(THEME), splash.told);
        assertFalse(store().contains(LaunchScreen.HANDED));
        assertFalse(HookStatus.report().toString().contains(LaunchScreen.SET));

        splash.whileTelling = null;
        assertFalse("a destroyed activity isn't used", LaunchScreen.follow(activity));
        assertEquals(Collections.singletonList(THEME), splash.told);

        controller = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(LaunchScreen.follow(controller.get()));
        assertEquals(List.of(THEME, THEME), splash.told);
        assertFalse(LaunchScreen.follow(controller.get()));
    }

    /** Before settings can be read, nothing is asked or counted. */
    @Test
    public void nothingBeforeSettingsCanBeRead() {
        Settings.LAUNCH_SCREEN.save(true);
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(LaunchScreen.follow(activity)));
        assertTrue(splash.told.isEmpty());
        assertTrue(HookStatus.report().isEmpty());
    }

    /** The watcher follows on a background thread when an activity resumes or pauses, not when it finishes. */
    @Test
    public void theWatcherFollowsOnResumeAndPause() throws Exception {
        LaunchScreen.Watcher watcher = new LaunchScreen.Watcher();
        Settings.LAUNCH_SCREEN.save(true);
        watcher.onActivityResumed(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(Collections.singletonList(THEME), splash.told);

        Settings.LAUNCH_SCREEN.save(false);
        watcher.onActivityPaused(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(List.of(THEME, 0), splash.told);

        Settings.LAUNCH_SCREEN.save(true);
        activity.finish();
        watcher.onActivityPaused(activity);
        Utils.awaitBackgroundTasksForTests();
        assertEquals("a finishing activity is left alone", List.of(THEME, 0), splash.told);
    }

    /**
     * With the switch on, Instagram's own logo animation after the launch screen is skipped and
     * counted, and Android is told nothing for it. Off, or with nothing to skip, Instagram's answer stands.
     */
    @Test
    public void instagramsLogoIsSkippedWhileTheSwitchIsOn() {
        assertEquals("off, Instagram's answer stands", 1, LaunchScreen.instagramLogo(1));
        HookStatus.clear();

        Settings.LAUNCH_SCREEN.save(true);
        assertEquals(0, LaunchScreen.instagramLogo(1));
        assertEquals(Collections.singletonList(counted(LaunchScreen.LOGO_SKIPPED)), HookStatus.report());
        assertEquals("nothing to skip", 0, LaunchScreen.instagramLogo(0));
        assertEquals("nothing more counted", Collections.singletonList(counted(LaunchScreen.LOGO_SKIPPED)), HookStatus.report());
        assertTrue("Android is told nothing", splash.told.isEmpty());
    }

    /** The 452 check answers with an Integer object; skipping is null, and no animation stays null. */
    @Test
    public void instagramsBoxedLogoAnswerIsNulledWhileTheSwitchIsOn() {
        Integer mode = Integer.valueOf(0);
        assertSame("off, Instagram's answer stands", mode, LaunchScreen.instagramLogo(mode));
        Settings.LAUNCH_SCREEN.save(true);
        assertNull(LaunchScreen.instagramLogo(mode));
        assertNull("nothing to skip", LaunchScreen.instagramLogo((Integer) null));
    }

    /** Paused, Instagram's logo follows as it ships, though the switch is on. */
    @Test
    public void pausedInstagramsLogoStays() {
        Settings.LAUNCH_SCREEN.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(1, LaunchScreen.instagramLogo(1));
        assertFalse(HookStatus.report().toString().contains(LaunchScreen.LOGO_SKIPPED));
    }

    /** Before settings can be read, Instagram's answer stands and nothing is counted. */
    @Test
    public void instagramsLogoStaysBeforeSettingsCanBeRead() {
        Settings.LAUNCH_SCREEN.save(true);
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals(1, LaunchScreen.instagramLogo(1)));
        assertTrue(HookStatus.report().isEmpty());
    }

    /**
     * Android 12 and older have no way to name a launch theme at run time, so nothing is asked, and
     * Instagram's logo still follows its own launch screen there.
     */
    @Test
    @Config(sdk = 28)
    public void androidTwelveAndOlderAreLeftAlone() {
        Settings.LAUNCH_SCREEN.save(true);
        assertFalse(LaunchScreen.follow(activity));
        LaunchScreen.followLater(activity);
        assertEquals(1, LaunchScreen.instagramLogo(1));
        assertTrue(splash.told.isEmpty());
        assertTrue(HookStatus.report().isEmpty());
    }
}
