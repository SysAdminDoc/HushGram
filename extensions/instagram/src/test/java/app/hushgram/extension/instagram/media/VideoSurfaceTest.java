/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Each switch that reads video, or wants it off HDR, keeps it on a TextureView. With none on, Instagram chooses. */
@RunWith(RobolectricTestRunner.class)
public class VideoSurfaceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static BooleanSetting[] readers() {
        return new BooleanSetting[] {
                Settings.TURN_OFF_HDR_BOOSTS,
                Settings.GLASS_TAB_BAR,
                Settings.SEE_THROUGH_HOME_TOP_BAR,
                Settings.BLUR_REEL_BARS,
                Settings.AMBIENT_LIGHT,
                Settings.AMBIENT_LIGHT_STORIES,
        };
    }

    @Before
    public void allOff() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        for (BooleanSetting reader : readers()) reader.save(false);
        HookStatus.clear();
    }

    @After
    public void restore() {
        for (BooleanSetting reader : readers()) reader.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static void assertKeptFor(BooleanSetting reader, String family) {
        reader.save(true);
        assertTrue(reader.key, VideoSurface.textureOnly());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(family));
        assertTrue(report, report.contains(VideoSurface.KEPT + " 1"));
        reader.save(false);
        HookStatus.clear();
    }

    /** #85: HDR only renders on a SurfaceView, so holding HDR back keeps video off one. */
    @Test
    public void holdingBackHdrKeepsVideoOnATexture() {
        assertKeptFor(Settings.TURN_OFF_HDR_BOOSTS, FamilyNames.HDR_BOOST);
    }

    /** #430, #136, #144: the glass can't read a SurfaceView, so the video showed sharp through it. */
    @Test
    public void theGlassBarsKeepVideoOnATexture() {
        assertKeptFor(Settings.GLASS_TAB_BAR, FamilyNames.GLASS_TAB_BAR);
        assertKeptFor(Settings.SEE_THROUGH_HOME_TOP_BAR, FamilyNames.HOME_TOP_BAR);
    }

    /** The reel bars' blur and ambient light copy pixels from a TextureView and find none on a SurfaceView. */
    @Test
    public void whatCopiesAVideosPixelsKeepsItOnATexture() {
        assertKeptFor(Settings.BLUR_REEL_BARS, FamilyNames.REEL_BLUR_BARS);
        assertKeptFor(Settings.AMBIENT_LIGHT, FamilyNames.AMBIENT_LIGHT);
        assertKeptFor(Settings.AMBIENT_LIGHT_STORIES, FamilyNames.AMBIENT_LIGHT);
    }

    @Test
    public void withEverySwitchOffInstagramChooses() {
        assertFalse(VideoSurface.textureOnly());
        assertFalse(String.join("\n", HookStatus.report()).contains(VideoSurface.KEPT));
    }

    @Test
    public void whilePausedInstagramChooses() {
        for (BooleanSetting reader : readers()) reader.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(VideoSurface.textureOnly());
        assertFalse(String.join("\n", HookStatus.report()).contains(VideoSurface.KEPT));
    }
}
