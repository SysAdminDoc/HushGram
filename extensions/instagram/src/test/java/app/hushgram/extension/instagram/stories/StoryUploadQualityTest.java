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
        Settings.UPLOAD_PHOTO_SIZE.resetToDefault();
        StoryUploadQuality.resetForTests();
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
        Settings.UPLOAD_PHOTO_SIZE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        StoryUploadQuality.resetForTests();
        HookStatus.clear();
    }

    /** A phone whose encoder takes 2160x3840 at up to 120 fps. */
    private static boolean fourK(int width, int height, int fps) {
        return width <= 2160 && height <= 3840 && fps <= 120;
    }

    /** Like the emulator's software H.264 encoder: no side past 2048, and 245760 macroblocks a second. */
    private static boolean emulator(int width, int height, int fps) {
        if (width > 2048 || height > 2048) return false;
        long blocks = (long) ((width + 15) / 16) * ((height + 15) / 16);
        return fps <= 0 || blocks * fps <= 245_760;
    }

    private static String counts() {
        return String.join(" ", HookStatus.report());
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

    /** 2160p asks Instagram's width step for the 1440 it knows, and after its cap the width goes to 2160, no wider than the clip. */
    @Test
    public void a2160WidthIsRaisedPastInstagramsCap() {
        StoryUploadQuality.encoderForTests(StoryUploadQualityTest::fourK);
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P2160);
        assertEquals("asks the step", 1440, StoryUploadQuality.resolution(ShareType.STORY, 1080));
        assertEquals("after the cap", 2160, StoryUploadQuality.renderWidth(1440, 2160));
        assertTrue(counts(), counts().contains(StoryUploadQuality.RAISED_PAST_STEP + " 1"));

        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals("a narrower clip", 1800, StoryUploadQuality.renderWidth(1440, 1800));
        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals("a clip the cap already fits", 1080, StoryUploadQuality.renderWidth(1080, 1080));
        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals("an odd width is made even", 1798, StoryUploadQuality.renderWidth(1440, 1799));
    }

    /** A width the phone's encoder can't take steps down to the widest of 1440 and 1080 it can, 1440p included. */
    @Test
    public void aWidthTheEncoderCantTakeStepsDown() {
        StoryUploadQuality.encoderForTests(StoryUploadQualityTest::emulator);
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P2160);
        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals("2160p", 1080, StoryUploadQuality.renderWidth(1440, 2160));
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P1440);
        assertEquals(1440, StoryUploadQuality.resolution(ShareType.STORY, 1080));
        assertEquals("1440p", 1080, StoryUploadQuality.renderWidth(1440, 1440));
        assertTrue(counts(), counts().contains(StoryUploadQuality.STEPPED_DOWN + " 2"));

        StoryUploadQuality.encoderForTests((width, height, fps) -> false);
        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals("an encoder taking none of them", 1440, StoryUploadQuality.renderWidth(1440, 1440));
    }

    /** Only a width step the hook raised changes after the cap, once, and a phone with no H.264 encoder keeps the cap's width. */
    @Test
    public void onlyARaisedUploadChangesAfterTheCap() {
        StoryUploadQuality.encoderForTests(StoryUploadQualityTest::fourK);
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P2160);
        assertEquals("never asked", 720, StoryUploadQuality.renderWidth(720, 2160));
        assertEquals("a post, stories only", 0, StoryUploadQuality.resolution(ShareType.FEED, 0));
        assertEquals("a post, stories only", 720, StoryUploadQuality.renderWidth(720, 2160));

        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals("no share type", 0, StoryUploadQuality.resolution(null, 0));
        assertEquals("a later null upload", 720, StoryUploadQuality.renderWidth(720, 2160));

        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals(2160, StoryUploadQuality.renderWidth(1440, 2160));
        assertEquals("asked once", 1440, StoryUploadQuality.renderWidth(1440, 2160));

        StoryUploadQuality.encoderForTests(null);
        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals("no encoder", 1440, StoryUploadQuality.renderWidth(1440, 2160));
        assertTrue(HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).toString(),
                HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).isEmpty());
    }

    /** 120 fps is asked where the encoder takes it at the width the upload got, and 60 otherwise. */
    @Test
    public void frameRatePast60NeedsTheEncoder() {
        StoryUploadQuality.encoderForTests(StoryUploadQualityTest::fourK);
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_FRAME_RATE.save(UploadFrameRate.FPS_120);
        assertEquals(120, StoryUploadQuality.frameRate(ShareType.STORY, 30));
        assertEquals("already higher", 240, StoryUploadQuality.frameRate(ShareType.STORY, 240));

        StoryUploadQuality.encoderForTests((width, height, fps) -> fps <= 60 || width <= 1080);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P2160);
        StoryUploadQuality.resolution(ShareType.STORY, 1080);
        assertEquals(2160, StoryUploadQuality.renderWidth(1440, 2160));
        assertEquals("too wide for 120", 60, StoryUploadQuality.frameRate(ShareType.STORY, 30));
        assertEquals("Instagram's 60 kept", 60, StoryUploadQuality.frameRate(ShareType.STORY, 60));

        StoryUploadQuality.encoderForTests(StoryUploadQualityTest::emulator);
        assertEquals("the emulator", 60, StoryUploadQuality.frameRate(ShareType.STORY, 30));
        StoryUploadQuality.encoderForTests(null);
        assertEquals("no encoder", 60, StoryUploadQuality.frameRate(ShareType.STORY, 30));
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

    /** The server's transcode size is raised to the chosen width for a covered upload, on whichever side it names. */
    @Test
    public void uploadSizeRaisesTheServersSize() {
        StoryUploadQuality.encoderForTests(StoryUploadQualityTest::fourK);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P2160);
        assertEquals("switch off", 1080, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        assertEquals(2160, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        assertEquals("the long side", 3840, StoryUploadQuality.uploadSize(ShareType.STORY, 1920, 0));
        assertTrue(counts(), counts().contains(StoryUploadQuality.RAISED_UPLOAD_SIZE + " 2"));

        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P1440);
        assertEquals("1440p", 1440, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P1080);
        assertEquals("the server's already", 1080, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        assertEquals("the server's bigger", 1440, StoryUploadQuality.uploadSize(ShareType.STORY, 1440, 1));
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.INSTAGRAM);
        assertEquals("Instagram's own", 1080, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));

        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P2160);
        assertEquals("no size", 0, StoryUploadQuality.uploadSize(ShareType.STORY, 0, 1));
        assertEquals("unset", -1, StoryUploadQuality.uploadSize(ShareType.STORY, -1, 1));
        assertEquals("post, stories only", 1080, StoryUploadQuality.uploadSize(ShareType.FEED, 1080, 1));
        assertEquals("no share type", 1080, StoryUploadQuality.uploadSize(null, 1080, 1));
        assertEquals("not an enum", 1080, StoryUploadQuality.uploadSize("STORY", 1080, 1));
        Settings.UPLOAD_QUALITY_APPLIES_TO.save(UploadScope.ALL);
        assertEquals("post, everything", 2160, StoryUploadQuality.uploadSize(ShareType.FEED, 1080, 1));
        assertTrue(HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).toString(),
                HookStatus.missing(FamilyNames.STORY_UPLOAD_QUALITY).isEmpty());
    }

    /** A size the phone's encoder can't take steps down to the widest of 1440 and 1080 above the server's, or keeps the server's. */
    @Test
    public void uploadSizeStepsDownToWhatTheEncoderTakes() {
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P2160);
        StoryUploadQuality.encoderForTests((width, height, fps) -> width <= 1440);
        assertEquals(1440, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        assertEquals("the long side", 2560, StoryUploadQuality.uploadSize(ShareType.STORY, 1920, 0));
        assertTrue(counts(), counts().contains(StoryUploadQuality.STEPPED_DOWN + " 2"));

        StoryUploadQuality.encoderForTests(StoryUploadQualityTest::emulator);
        assertEquals("the emulator", 1080, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        assertEquals("the emulator, a smaller server size", 1080, StoryUploadQuality.uploadSize(ShareType.STORY, 720, 1));
        StoryUploadQuality.encoderForTests((width, height, fps) -> false);
        assertEquals("an encoder taking none of them", 1080, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        StoryUploadQuality.encoderForTests(null);
        assertEquals("no encoder", 1080, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
    }

    /** A frame rate past 60 is checked at the size the server's encode got, raised or not. */
    @Test
    public void frameRateIsCheckedAtTheUploadSize() {
        StoryUploadQuality.encoderForTests((width, height, fps) -> fps <= 60 || width <= 1080);
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P2160);
        Settings.UPLOAD_FRAME_RATE.save(UploadFrameRate.FPS_120);
        assertEquals(2160, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        assertEquals("too wide for 120", 60, StoryUploadQuality.frameRate(ShareType.STORY, 30));

        Settings.UPLOAD_VIDEO_RESOLUTION.save(UploadResolution.P1080);
        assertEquals(1080, StoryUploadQuality.uploadSize(ShareType.STORY, 1080, 1));
        assertEquals("the server's size", 120, StoryUploadQuality.frameRate(ShareType.STORY, 30));
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

    /** Photo size turns on Instagram's sizes up to the one chosen, with the switch on, and leaves the rest as Instagram set them. */
    @Test
    public void photoSizeTurnsOnTheSizesUpToTheChoice() {
        assertEquals(UploadPhotoSize.INSTAGRAM, Settings.UPLOAD_PHOTO_SIZE.get());
        Settings.UPLOAD_PHOTO_SIZE.save(UploadPhotoSize.E4096);
        assertEquals("switch off", 0, StoryUploadQuality.photoTier(3072, 0));
        Settings.UPLOAD_STORIES_AT_FULL_QUALITY.save(true);
        assertEquals("3072", 1, StoryUploadQuality.photoTier(3072, 0));
        assertEquals("4096", 1, StoryUploadQuality.photoTier(4096, 0));
        assertEquals("8192, past the choice", 0, StoryUploadQuality.photoTier(8192, 0));
        assertEquals("already on", 1, StoryUploadQuality.photoTier(8192, 1));
        assertTrue(counts(), counts().contains(StoryUploadQuality.RAISED_PHOTO_SIZE + " 1"));

        Settings.UPLOAD_PHOTO_SIZE.save(UploadPhotoSize.INSTAGRAM);
        assertEquals("Instagram's own", 0, StoryUploadQuality.photoTier(3072, 0));
        Settings.UPLOAD_PHOTO_SIZE.save(UploadPhotoSize.E8192);
        assertEquals("8192", 1, StoryUploadQuality.photoTier(8192, 0));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, StoryUploadQuality.photoTier(3072, 0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("no context", 0, StoryUploadQuality.photoTier(3072, 0)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals("pause undecided", 0, StoryUploadQuality.photoTier(3072, 0)));
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
