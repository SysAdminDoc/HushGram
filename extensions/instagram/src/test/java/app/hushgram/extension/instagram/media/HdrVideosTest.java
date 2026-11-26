/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Keep HDR on video uploads answers where Instagram's encoder search marks an encoder SDR (#532). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HdrVideosTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_HDR_ON_VIDEO_UPLOADS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_HDR_ON_VIDEO_UPLOADS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static String counts() {
        return String.join(" ", HookStatus.report());
    }

    /** The switch starts off, and then every encoder stays SDR as Instagram marked it. */
    @Test
    public void offTheEncoderStaysSdr() {
        assertFalse(Settings.KEEP_HDR_ON_VIDEO_UPLOADS.get());
        assertEquals("HLG asked for", 0, HdrVideos.hlg(1));
        assertEquals("HLG not asked for", 0, HdrVideos.hlg(0));
        assertFalse(counts(), counts().contains(HdrVideos.MARKED));
        assertTrue(HookStatus.missing(FamilyNames.HDR_VIDEOS).toString(), HookStatus.missing(FamilyNames.HDR_VIDEOS).isEmpty());
    }

    /** On, an encoder asked for HLG keeps it, and that's counted. One not asked for stays SDR. */
    @Test
    public void onAnEncoderAskedForHlgKeepsIt() {
        Settings.KEEP_HDR_ON_VIDEO_UPLOADS.save(true);
        assertEquals("HLG asked for", 1, HdrVideos.hlg(1));
        assertTrue(counts(), counts().contains(HdrVideos.MARKED));
        HookStatus.clear();
        assertEquals("HLG not asked for", 0, HdrVideos.hlg(0));
        assertFalse(counts(), counts().contains(HdrVideos.MARKED));
    }

    /** Paused, or before the settings are read, the encoder stays SDR with the switch on. */
    @Test
    public void pausedOrUnreadTheEncoderStaysSdr() {
        Settings.KEEP_HDR_ON_VIDEO_UPLOADS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, HdrVideos.hlg(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("no context", 0, HdrVideos.hlg(1)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals("pause undecided", 0, HdrVideos.hlg(1)));
        assertEquals("back on", 1, HdrVideos.hlg(1));
    }

    /** Android 11 is below Instagram's own floor for HLG, so the encoder stays SDR there with the switch on. */
    @Test
    @Config(sdk = 30)
    public void android11StaysSdr() {
        Settings.KEEP_HDR_ON_VIDEO_UPLOADS.save(true);
        assertEquals(0, HdrVideos.hlg(1));
        assertFalse(counts(), counts().contains(HdrVideos.MARKED));
    }

    /** Android 13 is Instagram's floor, and the switch works there. */
    @Test
    @Config(sdk = 33)
    public void android13KeepsHlg() {
        Settings.KEEP_HDR_ON_VIDEO_UPLOADS.save(true);
        assertEquals(1, HdrVideos.hlg(1));
    }
}
