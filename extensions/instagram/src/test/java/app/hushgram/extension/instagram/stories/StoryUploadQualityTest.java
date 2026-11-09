/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Upload stories at full quality answers at the start of Instagram's high quality upload check. */
@RunWith(RobolectricTestRunner.class)
public class StoryUploadQualityTest {
    /** Stand-in for Instagram's ShareType, named as its enum names them. */
    private enum ShareType { FEED, STORY, STORY_AND_DIRECT_STORY_SHARE, CLIPS, SCHEDULED_STORY }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.resetToDefault();
        Settings.UPLOAD_QUALITY_APPLIES_TO.resetToDefault();
        Settings.UPLOAD_VIDEO_BITRATE.resetToDefault();
        Settings.UPLOAD_VIDEO_RESOLUTION.resetToDefault();
        Settings.UPLOAD_FRAME_RATE.resetToDefault();
        Settings.UPLOAD_PHOTO_QUALITY.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.resetToDefault();
        Settings.UPLOAD_QUALITY_APPLIES_TO.resetToDefault();
        Settings.UPLOAD_VIDEO_BITRATE.resetToDefault();
        Settings.UPLOAD_VIDEO_RESOLUTION.resetToDefault();
        Settings.UPLOAD_FRAME_RATE.resetToDefault();
        Settings.UPLOAD_PHOTO_QUALITY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The switch starts off, and then Instagram's own check runs for every upload. */
    @Test
    public void theSwitchStartsOffAndInstagramDecides() {
        assertFalse(Settings.UPLOAD_STORIES_AT_FULL_QUALITY.get());
        for (ShareType type : ShareType.values()) assertFalse(type.name(), StoryUploadQuality.fullQuality(type));
        assertTrue(HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).toString(),
                HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).isEmpty());
    }

    /** On, a story gets a yes, and every other upload, a story shared to messages too, gets Instagram's check. */
    @Test
    public void onOnlyAStoryIsRaised() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        assertTrue("story", StoryUploadQuality.fullQuality(ShareType.STORY));
        assertFalse("post", StoryUploadQuality.fullQuality(ShareType.FEED));
        assertFalse("reel", StoryUploadQuality.fullQuality(ShareType.CLIPS));
        assertFalse("story and messages", StoryUploadQuality.fullQuality(ShareType.STORY_AND_DIRECT_STORY_SHARE));
        assertFalse("scheduled story", StoryUploadQuality.fullQuality(ShareType.SCHEDULED_STORY));
        assertFalse("no share type", StoryUploadQuality.fullQuality(null));
        assertFalse("not an enum", StoryUploadQuality.fullQuality("STORY"));
        assertTrue(HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).toString(),
                HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).isEmpty());
    }

    /** With Stories, posts and reels, every share type gets the yes, and it still needs the switch. */
    @Test
    public void everyUploadWhenAskedFor() {
        Settings.UPLOAD_QUALITY_APPLIES_TO.save(UploadScope.ALL);
        assertFalse("switch off", StoryUploadQuality.fullQuality(ShareType.FEED));
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        for (ShareType type : ShareType.values()) assertTrue(type.name(), StoryUploadQuality.fullQuality(type));
        assertFalse("no share type", StoryUploadQuality.fullQuality(null));
        assertFalse("not an enum", StoryUploadQuality.fullQuality("FEED"));
    }

    /** The bitrate stays Instagram's own with the choice at Instagram's own, whatever the switch and share type. */
    @Test
    public void bitrateStartsAtInstagramsOwn() {
        assertEquals(UploadBitrate.INSTAGRAM, Settings.UPLOAD_VIDEO_BITRATE.get());
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        assertEquals(9_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 9_000_000));
    }

    /** A chosen bitrate is a floor for a covered upload: raised when Instagram's is lower, kept when it's higher. */
    @Test
    public void bitrateRaisesACoveredUpload() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_BITRATE.save(UploadBitrate.MBPS_25);
        assertEquals(25_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 9_000_000));
        assertEquals("already higher", 30_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 30_000_000));
        assertEquals("no bitrate", 0, StoryUploadQuality.bitrate(ShareType.STORY, 0));
        Settings.UPLOAD_VIDEO_BITRATE.save(UploadBitrate.MBPS_50);
        assertEquals(50_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 1_200_000));
        assertTrue(HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).toString(),
                HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).isEmpty());
    }

    /** Posts and reels keep Instagram's bitrate under Stories only, and get the choice under Stories, posts and reels. */
    @Test
    public void bitrateFollowsWhereItApplies() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_BITRATE.save(UploadBitrate.MBPS_16);
        assertEquals("post", 4_000_000, StoryUploadQuality.bitrate(ShareType.FEED, 4_000_000));
        assertEquals("reel", 4_000_000, StoryUploadQuality.bitrate(ShareType.CLIPS, 4_000_000));
        assertEquals("not an enum", 4_000_000, StoryUploadQuality.bitrate("STORY", 4_000_000));
        assertEquals("no share type", 4_000_000, StoryUploadQuality.bitrate(null, 4_000_000));
        Settings.UPLOAD_QUALITY_APPLIES_TO.save(UploadScope.ALL);
        assertEquals("post", 16_000_000, StoryUploadQuality.bitrate(ShareType.FEED, 4_000_000));
        assertEquals("reel", 16_000_000, StoryUploadQuality.bitrate(ShareType.CLIPS, 4_000_000));
    }

    /** Switch off, paused, or before the settings are read, the bitrate is Instagram's. */
    @Test
    public void bitrateFailsOpen() {
        Settings.UPLOAD_VIDEO_BITRATE.save(UploadBitrate.MBPS_50);
        assertEquals("switch off", 9_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 9_000_000));
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 9_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 9_000_000));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals("no context", 9_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 9_000_000)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals("pause undecided", 9_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 9_000_000)));

        assertEquals("back on", 50_000_000, StoryUploadQuality.bitrate(ShareType.STORY, 9_000_000));
    }

    /** The three new choices start at Instagram's own, and then nothing moves even with the switch on. */
    @Test
    public void theNewChoicesStartAtInstagramsOwn() {
        assertEquals(UploadResolution.INSTAGRAM, Settings.UPLOAD_VIDEO_RESOLUTION.get());
        assertEquals(UploadFrameRate.INSTAGRAM, Settings.UPLOAD_FRAME_RATE.get());
        assertEquals(UploadPhotoQuality.INSTAGRAM, Settings.UPLOAD_PHOTO_QUALITY.get());
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        assertEquals(1080, StoryUploadQuality.resolution(ShareType.STORY, 1080));
        assertEquals(30, StoryUploadQuality.frameRate(ShareType.STORY, 30));
        assertEquals(84, StoryUploadQuality.photoQuality(84));
    }

    /** A chosen width is a floor for a covered upload, and raises the width Instagram didn't ask for too. */
    @Test
    public void resolutionRaisesACoveredUpload() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P1440);
        assertEquals(1440, StoryUploadQuality.resolution(ShareType.STORY, 1080));
        assertEquals("not asked", 1440, StoryUploadQuality.resolution(ShareType.STORY, 0));
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P1080);
        assertEquals("already wider", 1440, StoryUploadQuality.resolution(ShareType.STORY, 1440));
        assertEquals("post, stories only", 0, StoryUploadQuality.resolution(ShareType.FEED, 0));
        Settings.UPLOAD_QUALITY_APPLIES_TO.save(UploadScope.ALL);
        assertEquals("post, everything", 1080, StoryUploadQuality.resolution(ShareType.FEED, 0));
        assertEquals("not an enum", 0, StoryUploadQuality.resolution("STORY", 0));
        assertEquals("no share type", 0, StoryUploadQuality.resolution(null, 0));
        assertTrue(HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).toString(),
                HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).isEmpty());
    }

    /** A chosen frame rate is a floor for a covered upload, and fills in one Instagram didn't set. */
    @Test
    public void frameRateRaisesACoveredUpload() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_FRAME_RATE.save(UploadFrameRate.FPS_60);
        assertEquals(60, StoryUploadQuality.frameRate(ShareType.STORY, 30));
        assertEquals("not set", 60, StoryUploadQuality.frameRate(ShareType.STORY, 0));
        assertEquals("already higher", 120, StoryUploadQuality.frameRate(ShareType.STORY, 120));
        assertEquals("reel, stories only", 30, StoryUploadQuality.frameRate(ShareType.CLIPS, 30));
        Settings.UPLOAD_QUALITY_APPLIES_TO.save(UploadScope.ALL);
        assertEquals("reel, everything", 60, StoryUploadQuality.frameRate(ShareType.CLIPS, 30));
    }

    /** A chosen photo quality is a floor with the switch on, for every photo, and never touches an unset one. */
    @Test
    public void photoQualityRaisesWithTheSwitchOn() {
        Settings.UPLOAD_PHOTO_QUALITY.save(UploadPhotoQuality.Q100);
        assertEquals("switch off", 84, StoryUploadQuality.photoQuality(84));
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        assertEquals(100, StoryUploadQuality.photoQuality(84));
        assertEquals("nothing to raise", 0, StoryUploadQuality.photoQuality(0));
        Settings.UPLOAD_PHOTO_QUALITY.save(UploadPhotoQuality.Q90);
        assertEquals("already higher", 95, StoryUploadQuality.photoQuality(95));
    }

    /** Paused, or before the settings are read, the three new choices are Instagram's. */
    @Test
    public void theNewChoicesFailOpen() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P1440);
        Settings.UPLOAD_FRAME_RATE.save(UploadFrameRate.FPS_60);
        Settings.UPLOAD_PHOTO_QUALITY.save(UploadPhotoQuality.Q100);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1080, StoryUploadQuality.resolution(ShareType.STORY, 1080));
        assertEquals("paused", 30, StoryUploadQuality.frameRate(ShareType.STORY, 30));
        assertEquals("paused", 84, StoryUploadQuality.photoQuality(84));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertEquals("no context", 1080, StoryUploadQuality.resolution(ShareType.STORY, 1080));
            assertEquals("no context", 30, StoryUploadQuality.frameRate(ShareType.STORY, 30));
            assertEquals("no context", 84, StoryUploadQuality.photoQuality(84));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertEquals("pause undecided", 1080, StoryUploadQuality.resolution(ShareType.STORY, 1080));
            assertEquals("pause undecided", 30, StoryUploadQuality.frameRate(ShareType.STORY, 30));
            assertEquals("pause undecided", 84, StoryUploadQuality.photoQuality(84));
        });

        assertEquals("back on", 1440, StoryUploadQuality.resolution(ShareType.STORY, 1080));
        assertEquals("back on", 60, StoryUploadQuality.frameRate(ShareType.STORY, 30));
        assertEquals("back on", 100, StoryUploadQuality.photoQuality(84));
    }

    /** Paused, or before the settings are read, Instagram's own check runs. */
    @Test
    public void pausedAndUnreadyLeaveInstagramsCheck() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoryUploadQuality.fullQuality(ShareType.STORY));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", StoryUploadQuality.fullQuality(ShareType.STORY)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", StoryUploadQuality.fullQuality(ShareType.STORY)));

        assertTrue("back on", StoryUploadQuality.fullQuality(ShareType.STORY));
    }
}
