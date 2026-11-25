/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Keep HDR on photo uploads answers Instagram's checks for keeping Ultra HDR on photos (#532). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HdrPhotosTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_HDR_ON_PHOTO_UPLOADS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_HDR_ON_PHOTO_UPLOADS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static String counts() {
        return String.join(" ", HookStatus.report());
    }

    /** The switch starts off, and then both checks keep Instagram's answers. */
    @Test
    public void offInstagramDecides() {
        assertFalse(Settings.KEEP_HDR_ON_PHOTO_UPLOADS.get());
        assertEquals(0, HdrPhotos.ultraHdr(0));
        assertEquals(1, HdrPhotos.ultraHdr(1));
        assertEquals(0, HdrPhotos.colorGainmap(0));
        assertEquals(1, HdrPhotos.colorGainmap(1));
        assertFalse(counts(), counts().contains(HdrPhotos.ANSWERED));
        assertTrue(HookStatus.missing(FamilyNames.HDR_PHOTOS).toString(), HookStatus.missing(FamilyNames.HDR_PHOTOS).isEmpty());
    }

    /** On, on Android 14, both checks answer yes, and that's counted. */
    @Test
    public void onBothChecksAnswerYes() {
        Settings.KEEP_HDR_ON_PHOTO_UPLOADS.save(true);
        assertEquals("the Ultra HDR check", 1, HdrPhotos.ultraHdr(0));
        assertEquals("the color gain map check", 1, HdrPhotos.colorGainmap(0));
        assertEquals("a yes stays a yes", 1, HdrPhotos.ultraHdr(1));
        assertTrue(counts(), counts().contains(HdrPhotos.ANSWERED));
    }

    /** Paused, or before the settings are read, Instagram's answers stand with the switch on. */
    @Test
    public void pausedOrUnreadInstagramDecides() {
        Settings.KEEP_HDR_ON_PHOTO_UPLOADS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, HdrPhotos.ultraHdr(0));
        assertEquals("paused", 0, HdrPhotos.colorGainmap(0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("no context", 0, HdrPhotos.ultraHdr(0)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals("pause undecided", 0, HdrPhotos.ultraHdr(0)));
        assertEquals("back on", 1, HdrPhotos.ultraHdr(0));
    }

    /** Android 13 has no gain maps in Bitmap, so Instagram's no stands there with the switch on. */
    @Test
    @Config(sdk = 33)
    public void android13KeepsInstagramsAnswer() {
        Settings.KEEP_HDR_ON_PHOTO_UPLOADS.save(true);
        assertEquals(0, HdrPhotos.ultraHdr(0));
        assertEquals(0, HdrPhotos.colorGainmap(0));
        HdrPhotos.saved(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), "/nowhere.jpg", 1);
        assertFalse(counts(), counts().contains(HdrPhotos.KEPT));
    }

    /** A picture without a gain map, a missing file or no picture at all is logged or skipped, never counted and never thrown. */
    @Test
    public void aSaveWithoutAGainmapIsntCounted() throws Exception {
        Settings.KEEP_HDR_ON_PHOTO_UPLOADS.save(true);
        File file = File.createTempFile("photo", ".jpg");
        try {
            Bitmap picture = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                assertTrue(picture.compress(Bitmap.CompressFormat.JPEG, 90, out));
            }
            HdrPhotos.saved(picture, file.getPath(), 1);
            HdrPhotos.saved(picture, null, 0);
            HdrPhotos.saved(null, file.getPath(), 1);
            assertFalse("a plain JPEG has no gain map", HdrPhotos.fileHasGainmap(file.getPath()));
            assertFalse("nor does a missing file", HdrPhotos.fileHasGainmap(file.getPath() + ".gone"));
            assertFalse("nor no path", HdrPhotos.fileHasGainmap(null));
        } finally {
            assertTrue(file.delete());
        }
        assertFalse(counts(), counts().contains(HdrPhotos.KEPT));
        assertTrue(HookStatus.missing(FamilyNames.HDR_PHOTOS).toString(), HookStatus.missing(FamilyNames.HDR_PHOTOS).isEmpty());
    }
}
