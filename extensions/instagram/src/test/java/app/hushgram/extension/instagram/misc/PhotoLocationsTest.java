/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

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

/** When Instagram can't read where a gallery photo or video was taken (#628), and when it keeps its own answer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PhotoLocationsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Settings.KEEP_PHOTO_LOCATIONS.save(true);
    }

    @After
    public void restore() {
        Settings.KEEP_PHOTO_LOCATIONS.resetToDefault();
        BaseSettings.PAUSED.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void startsOff() {
        Settings.KEEP_PHOTO_LOCATIONS.resetToDefault();
        assertFalse(Settings.KEEP_PHOTO_LOCATIONS.get());
        assertTrue(PhotoLocations.original(1));
        assertFalse(PhotoLocations.galleryItem());
        assertFalse(PhotoLocations.exifTags());
    }

    @Test
    public void onOpensGalleryFilesWithoutTheOriginal() {
        assertFalse(PhotoLocations.original(1));
    }

    @Test
    public void onFindsNoPlace() {
        assertTrue(PhotoLocations.galleryItem());
        assertTrue(PhotoLocations.exifTags());
    }

    @Test
    public void offLeavesInstagramsAnswer() {
        Settings.KEEP_PHOTO_LOCATIONS.save(false);
        assertTrue(PhotoLocations.original(1));
        assertFalse(PhotoLocations.original(0));
        assertFalse(PhotoLocations.galleryItem());
        assertFalse(PhotoLocations.exifTags());
    }

    @Test
    public void pausedLeavesInstagramsAnswer() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(PhotoLocations.original(1));
        assertFalse(PhotoLocations.galleryItem());
        assertFalse(PhotoLocations.exifTags());
    }

    @Test
    public void withoutThePermissionNothingIsAskedOrCounted() {
        assertFalse(PhotoLocations.original(0));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(PhotoLocations.OPENED_WITHOUT));
    }

    @Test
    public void anyNonZeroIsTheAsk() {
        assertFalse(PhotoLocations.original(-1));
        assertFalse(PhotoLocations.original(255));
    }

    @Test
    public void eachPlaceKeptIsCounted() {
        PhotoLocations.original(1);
        PhotoLocations.galleryItem();
        PhotoLocations.exifTags();
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FamilyNames.PHOTO_LOCATIONS));
        assertTrue(report, report.contains(PhotoLocations.OPENED_WITHOUT + " 1"));
        assertTrue(report, report.contains(PhotoLocations.KEPT_BACK + " 2"));
    }
}
