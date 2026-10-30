/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.stories.StoryRingColor;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.SharedPrefCategory;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 37})
public class ConfigurationBackupTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private SharedPreferences original;

    @Before public void setup() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37; // Instagram 453's target on every runtime.
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        original = Setting.preferences.preferences;
        ConfigurationBackup.forgetUndo();
        for (Setting<?> setting : ConfigurationBackup.eligible().values()) setting.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    @After public void restore() throws Exception {
        setStore(original);
        Utils.awaitBackgroundTasksForTests();
        PauseForTests.resume();
        ConfigurationBackup.forgetUndo();
        for (Setting<?> setting : ConfigurationBackup.eligible().values()) setting.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test @Config(sdk = {28, 29, 37})
    public void staleActionAndExpiryCannotConsumeTheNextImport() throws Exception {
        ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)));
        long old = ConfigurationBackup.undoToken();
        long firstDeadline = ConfigurationBackup.undoDeadline(old);
        SystemClock.sleep(5_000);
        ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", true)));
        long current = ConfigurationBackup.undoToken();
        assertNotEquals(old, current);
        assertEquals(0, ConfigurationBackup.undoDeadline(old));
        assertNull(ConfigurationBackup.undo(old));
        SystemClock.sleep(firstDeadline - SystemClock.elapsedRealtime());
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(ConfigurationBackup.canUndo());
        assertNotNull(ConfigurationBackup.undo(current));
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertNull(ConfigurationBackup.undo(current));
        assertEquals(0, ConfigurationBackup.undoToken());
    }

    @Test public void undoPreservesAChoiceChangedAwayAndBackAfterImport() throws Exception {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.AUTO);
        ConfigurationBackup.restore(file(entry(Settings.PLAYBACK_QUALITY.key, "enum", "HIGHEST")));
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.DATA_SAVER);
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        ConfigurationBackup.Result result = ConfigurationBackup.undo();
        assertEquals(PlaybackQuality.HIGHEST, Settings.PLAYBACK_QUALITY.savedValue());
        assertEquals(1, result.skipped);
        assertFalse(ConfigurationBackup.canUndo());
    }

    @Test public void processStateLossCannotLeaveAnAvailableUndo() throws Exception {
        ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)));
        long token = ConfigurationBackup.undoToken();
        ConfigurationBackup.forgetUndo();
        assertEquals(0, ConfigurationBackup.undoToken());
        assertEquals(0, ConfigurationBackup.undoDeadline(token));
        assertNull(ConfigurationBackup.undo(token));
        assertFalse(Settings.HIDE_ADS.savedValue());
    }

    @Test public void exportUsesSavedChoicesAndOnlyInstalledControls() throws Exception {
        Settings.HIDE_ADS.save(true);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        original.edit().putString("private-session", "secret-token").commit();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        JSONObject values = new JSONObject(new String(ConfigurationBackup.export(), StandardCharsets.UTF_8)).getJSONObject("settings");
        assertEquals(10, values.length());
        assertFalse(values.getJSONObject(Settings.DATE_SETTINGS_EXPORT.key).getBoolean("value"));
        assertFalse(values.getJSONObject(Settings.DESCRIPTIONS_BEHIND_INFO.key).getBoolean("value"));
        assertTrue(values.getJSONObject(Settings.HIDE_ADS.key).getBoolean("value"));
        assertTrue(values.has(BaseSettings.DEBUG.key));
        assertEquals("OFF", values.getJSONObject(Settings.NAVIGATION_SETTINGS_TARGET.key).getString("value"));
        assertFalse(values.getJSONObject(Settings.HIDE_MENU_ROW.key).getBoolean("value"));
        assertFalse(values.getJSONObject(Settings.PROFILE_MENU_LONG_PRESS.key).getBoolean("value"));
        assertTrue("a pause doesn't change what the shortcut's switch exports",
                values.getJSONObject(Settings.SETTINGS_SHORTCUT.key).getBoolean("value"));
        assertTrue(values.getJSONObject(Settings.SETTINGS_LOGO_PRESS.key).getBoolean("value"));
        assertTrue("categories are the default page now (#80)", values.getJSONObject(Settings.CATEGORY_PAGES.key).getBoolean("value"));
        assertFalse(values.has(BaseSettings.PAUSED.key));
        assertFalse(values.has(BaseSettings.SAFE_MODE.key));
        assertFalse(values.has(BaseSettings.FIRST_TIME_APP_LAUNCHED.key));
        assertFalse(values.has(Settings.SIGN_IN_NOTICE_HIDDEN.key));
        assertFalse(new String(ConfigurationBackup.export(), StandardCharsets.UTF_8).contains("secret-token"));
        assertFalse(values.has(Settings.DOWNLOAD_QUALITY.key));
    }

    @Test public void roundTripRestoresBooleansEnumsAndNamesTogetherEvenDuringPause() throws Exception {
        Settings.HIDE_ADS.save(false);
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.HIGHEST);
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.BEST);
        Settings.SAVE_FOLDER.save("Clips");
        Settings.FILENAME_TEMPLATE.save("Trip_{date}");
        byte[] file = ConfigurationBackup.export();
        Settings.HIDE_ADS.save(true);
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.AUTO);
        Settings.SAVE_FOLDER.resetToDefault();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ConfigurationBackup.Result result = ConfigurationBackup.restore(file);
        assertEquals(0, result.skipped);
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertEquals(PlaybackQuality.HIGHEST, Settings.PLAYBACK_QUALITY.savedValue());
        assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());
        assertEquals("Trip_{date}", Settings.FILENAME_TEMPLATE.savedValue());
        assertEquals("HIGHEST", original.getString(Settings.PLAYBACK_QUALITY.key, ""));
        assertTrue(ConfigurationBackup.canUndo());
    }

    @Test public void shortcodeTemplatesImportAndRoundTripWithoutAnAddedDate() throws Exception {
        ConfigurationBackup.Result imported = ConfigurationBackup.restore(file(
                entry(Settings.FILENAME_TEMPLATE.key, "string", "{shortcode}")));
        assertEquals(1, imported.applied);
        assertEquals(0, imported.skipped);
        assertEquals("{shortcode}", Settings.FILENAME_TEMPLATE.savedValue());
        byte[] exported = ConfigurationBackup.export();
        Settings.FILENAME_TEMPLATE.save("Old_{date}");
        assertEquals(0, ConfigurationBackup.restore(exported).skipped);
        assertEquals("{shortcode}", Settings.FILENAME_TEMPLATE.savedValue());
    }

    @Test public void unknownAndAbsentFamilyKeysSkipWithoutChangingThemOrPause() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        Settings.DOWNLOAD_PHOTOS.save(false);
        BaseSettings.PAUSED.save(false);
        String entries = entry(Settings.HIDE_ADS.key, "boolean", false) + ","
                + entry(Settings.DOWNLOAD_PHOTOS.key, "boolean", true) + ","
                + entry(BaseSettings.PAUSED.key, "boolean", true) + ","
                + entry("future_key", "string", "ignored");
        ConfigurationBackup.Result result = ConfigurationBackup.restore(file(entries));
        assertEquals(1, result.applied);
        assertEquals(3, result.skipped);
        assertFalse(Settings.DOWNLOAD_PHOTOS.savedValue());
        assertFalse(BaseSettings.PAUSED.savedValue());
    }

    @Test public void malformedShapeSchemaDuplicatesAndUtf8CannotPartiallyApply() throws Exception {
        String first = entry(Settings.HIDE_ADS.key, "boolean", false);
        String[] entries = {
                first + "," + first,
                first + ",\"unknown\":{\"type\":\"boolean\",\"value\":false,\"value\":true}",
                first + ",\"unknown\":{\"type\":\"boolean\"}",
                first + ",\"unknown\":{\"type\":\"boolean\",\"value\":true,\"extra\":1}",
                first + ",\"unknown\":{\"type\":7,\"value\":true}",
                first + ",\"unknown\":true",
                first + ",\"bad key!\":{\"type\":\"boolean\",\"value\":true}",
        };
        for (String malformed : entries) rejected(file(malformed));
        rejected(new String(file(first), StandardCharsets.UTF_8).replace("\"schema\":1", "\"schema\":2").getBytes(StandardCharsets.UTF_8));
        rejected(new String(file(first), StandardCharsets.UTF_8).replace("\"schema\":1", "\"schema\":1.0").getBytes(StandardCharsets.UTF_8));
        rejected(new String(file(first), StandardCharsets.UTF_8).replace("HushGram", "OtherProject").getBytes(StandardCharsets.UTF_8));
        rejected(new byte[]{(byte) 0xc3, (byte) 0x28});
        rejected((new String(file(first), StandardCharsets.UTF_8) + "{}").getBytes(StandardCharsets.UTF_8));
    }

    // A file from a newer HushGram can name a choice this build lacks or carry a value it can't
    // read. Each of those used to refuse the whole file, so one entry cost every other choice in it.
    @Test public void entriesThisBuildCannotTakeSkipWhileTheRestApply() throws Exception {
        Settings.HIDE_REELS_TAB.save(false);
        String[] unusable = {
                entry(Settings.PLAYBACK_QUALITY.key, "enum", "P9999"),
                entry(Settings.PLAYBACK_QUALITY.key, "string", "HIGHEST"),
                entry(Settings.HIDE_REELS_TAB.key, "boolean", "true"),
                entry(Settings.HIDE_REELS_TAB.key, "enum", "TRUE"),
                "\"" + Settings.HIDE_REELS_TAB.key + "\":{\"type\":\"boolean\",\"value\":null}",
                entry(Settings.SAVE_FOLDER.key, "string", "../../Outside"),
                entry(Settings.SAVE_FOLDER.key, "boolean", true),
                entry(Settings.FILENAME_TEMPLATE.key, "string", "../bad.mp4"),
                entry(Settings.SHARING_DOMAIN.key, "string", "not a domain"),
                entry(Settings.SHARING_DOMAIN.key, "string", "192.168.1.1"),
                entry(Settings.SPOOF_LOCATION_PLACE.key, "string", "91,0"),
                entry(Settings.SPOOF_LOCATION_PLACE.key, "string", "somewhere"),
                entry(Settings.TAB_ORDER.key, "string", "FEED,FEED"),
                entry(Settings.TAB_ORDER.key, "string", "FEED, SEARCH"),
                entry(Settings.TAB_ORDER.key, "string", "FEED,NOT_A_TAB"),
                entry(Settings.LIKE_ANIMATION.key, "string", "<heart>"),
                entry(Settings.LIKE_ANIMATION.key, "string", new String(new char[ConfigurationBackup.MAX_TEXT + 1]).replace('\0', 'A')),
                "\"future_key\":{\"type\":\"boolean\",\"value\":null}",
                "\"future_key\":{\"type\":\"enum\",\"value\":99}",
                "\"future_key\":{\"type\":\"number\",\"value\":3}",
        };
        for (String entry : unusable) {
            ConfigurationBackup.Result result = ConfigurationBackup.restore(
                    file(entry(Settings.HIDE_ADS.key, "boolean", false) + "," + entry));
            assertEquals(entry, 1, result.applied);
            assertEquals(entry, 1, result.skipped);
            assertFalse(entry, Settings.HIDE_ADS.savedValue());
            Settings.HIDE_ADS.resetToDefault();
            ConfigurationBackup.forgetUndo();
        }
        assertEquals(PlaybackQuality.AUTO, Settings.PLAYBACK_QUALITY.savedValue());
        assertFalse(Settings.HIDE_REELS_TAB.savedValue());
        for (Setting<?> text : new Setting<?>[]{Settings.SAVE_FOLDER, Settings.FILENAME_TEMPLATE,
                Settings.SHARING_DOMAIN, Settings.SPOOF_LOCATION_PLACE, Settings.TAB_ORDER, Settings.LIKE_ANIMATION}) {
            assertEquals(text.key, text.defaultValue, text.savedValue());
        }
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        ConfigurationBackup.Result absent = ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)
                + "," + entry(Settings.PLAYBACK_QUALITY.key, "enum", "P9999")));
        assertEquals(1, absent.applied);
        assertEquals(1, absent.skipped);
    }

    @Test public void textChoicesThatPassTheirOwnChecksApply() throws Exception {
        String entries = entry(Settings.SHARING_DOMAIN.key, "string", "dd.example.com") + ","
                + entry(Settings.SPOOF_LOCATION_PLACE.key, "string", "40.7128, -74.006") + ","
                + entry(Settings.TAB_ORDER.key, "string", "SEARCH,FEED") + ","
                + entry(Settings.LIKE_ANIMATION.key, "string", "FIRE") + ","
                + entry(Settings.SAVE_FOLDER.key, "string", "Clips");
        ConfigurationBackup.Result result = ConfigurationBackup.restore(file(entries));
        assertEquals(5, result.applied);
        assertEquals(0, result.skipped);
        assertEquals("dd.example.com", Settings.SHARING_DOMAIN.savedValue());
        assertEquals("40.7128, -74.006", Settings.SPOOF_LOCATION_PLACE.savedValue());
        assertEquals("SEARCH,FEED", Settings.TAB_ORDER.savedValue());
        assertEquals("FIRE", Settings.LIKE_ANIMATION.savedValue());
        assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());
        // The Place row saves blank text as typed, so a file of one restores it.
        assertEquals(1, ConfigurationBackup.restore(file(entry(Settings.SPOOF_LOCATION_PLACE.key, "string", "  "))).applied);
        assertEquals("  ", Settings.SPOOF_LOCATION_PLACE.savedValue());
    }

    /**
     * The blocked words come back with their line breaks and up to the row's own limit, which is
     * longer than any other text's, and a list holding another control character is skipped (#192).
     */
    @Test public void blockedWordsRoundTripUpToTheirRowsLimit() throws Exception {
        String words = "spoiler, #ad\ngiveaway\r\nlink in bio";
        Settings.REELS_BLOCKED_WORDS.save(words);
        byte[] exported = ConfigurationBackup.export();
        Settings.REELS_BLOCKED_WORDS.resetToDefault();
        ConfigurationBackup.Result result = ConfigurationBackup.restore(exported);
        assertEquals(0, result.skipped);
        assertEquals(words, Settings.REELS_BLOCKED_WORDS.savedValue());
        ConfigurationBackup.forgetUndo();

        String longest = new String(new char[ConfigurationBackup.MAX_WORDS_TEXT]).replace('\0', 'a');
        assertEquals(1, ConfigurationBackup.restore(file(entry(Settings.REELS_BLOCKED_WORDS.key, "string", longest))).applied);
        assertEquals(longest, Settings.REELS_BLOCKED_WORDS.savedValue());
        ConfigurationBackup.forgetUndo();

        assertNull(ConfigurationBackup.accepted(Settings.REELS_BLOCKED_WORDS, "string", longest + "a"));
        assertNull(ConfigurationBackup.accepted(Settings.REELS_BLOCKED_WORDS, "string", "spoiler\tgiveaway"));
        assertNull(ConfigurationBackup.accepted(Settings.REELS_BLOCKED_WORDS, "string", "spoiler\u0000"));
        assertNull("other text keeps its own limit",
                ConfigurationBackup.accepted(Settings.LIKE_ANIMATION, "string", new String(new char[ConfigurationBackup.MAX_TEXT + 1]).replace('\0', 'A')));
        Settings.REELS_BLOCKED_WORDS.save("cat");
        ConfigurationBackup.Result refused = ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)
                + "," + entry(Settings.REELS_BLOCKED_WORDS.key, "string", "dog\u0007")));
        assertEquals(1, refused.applied);
        assertEquals(1, refused.skipped);
        assertEquals("cat", Settings.REELS_BLOCKED_WORDS.savedValue());
    }

    // A text choice added to the file without a check of its own would never restore; its default
    // must pass the check too, or a file exported before it was ever changed would lose it.
    @Test public void everyExportedTextChoiceHasACheckItsDefaultPasses() {
        for (Setting<?> setting : ConfigurationBackup.eligible().values()) {
            if (!(setting.defaultValue instanceof String)) continue;
            assertTrue(setting.key, ConfigurationBackup.textAllowed(setting, (String) setting.defaultValue));
            assertNotNull(setting.key, ConfigurationBackup.accepted(setting, "string", setting.defaultValue));
        }
    }

    @Test public void byteAndEntryBoundsRejectOversizeAndAcceptExactly512Entries() throws Exception {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            if (i != 0) entries.append(',');
            entries.append(entry("future_" + i, "boolean", false));
        }
        assertEquals(512, ConfigurationBackup.restore(file(entries.toString())).skipped);
        rejected(file(entries + "," + entry("extra", "boolean", true)));
        rejected(new byte[ConfigurationBackup.MAX_BYTES + 1]);
        assertEquals(ConfigurationBackup.MAX_BYTES, ConfigurationBackup.read(
                new ByteArrayInputStream(new byte[ConfigurationBackup.MAX_BYTES])).length);
        try {
            ConfigurationBackup.read(new ByteArrayInputStream(new byte[ConfigurationBackup.MAX_BYTES + 1]));
            fail("Oversized provider stream accepted");
        } catch (IOException expected) { }
    }

    @Test public void undoIsOneUseAndRestartRequirementsDescribeActualChanges() throws Exception {
        ConfigurationBackup.Result applied = ConfigurationBackup.restore(file(entry(Settings.HIDE_META_AI_SEARCH.key, "boolean", false)));
        assertTrue(applied.restart);
        assertFalse(Settings.HIDE_META_AI_SEARCH.savedValue());
        assertTrue(ConfigurationBackup.undo().restart);
        assertTrue(Settings.HIDE_META_AI_SEARCH.savedValue());
        assertNull(ConfigurationBackup.undo());
        assertFalse(ConfigurationBackup.restore(file(entry(Settings.HIDE_META_AI_SEARCH.key, "boolean", true))).restart);
        assertFalse(ConfigurationBackup.canUndo());
    }

    // The ring's color travels beside its size: a file carries it by name and gives it back, and a
    // name this build has no color for is skipped while the size beside it applies.
    @Test public void ringColorIsCarriedAndRestoredBesideTheSize() throws Exception {
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.STORY_RING_COLOR.key));
        Settings.STORY_RING_COLOR.save(StoryRingColor.GOLD);
        JSONObject values = new JSONObject(new String(ConfigurationBackup.export(), StandardCharsets.UTF_8)).getJSONObject("settings");
        assertEquals("GOLD", values.getJSONObject(Settings.STORY_RING_COLOR.key).getString("value"));
        assertEquals("INSTAGRAM", values.getJSONObject(Settings.STORY_RING_SCALE.key).getString("value"));
        Settings.STORY_RING_COLOR.resetToDefault();

        ConfigurationBackup.Result restored = ConfigurationBackup.restore(file(
                entry(Settings.STORY_RING_COLOR.key, "enum", "BLUE") + "," + entry(Settings.STORY_RING_SCALE.key, "enum", "LARGER")));
        assertEquals(2, restored.applied);
        assertTrue("a new color shows after a restart", restored.restart);
        assertEquals(StoryRingColor.BLUE, Settings.STORY_RING_COLOR.savedValue());
        assertEquals(StoryRingSize.LARGER, Settings.STORY_RING_SCALE.savedValue());
        ConfigurationBackup.forgetUndo();

        ConfigurationBackup.Result unknown = ConfigurationBackup.restore(file(
                entry(Settings.STORY_RING_COLOR.key, "enum", "GREEN") + "," + entry(Settings.STORY_RING_SCALE.key, "enum", "SMALLER")));
        assertEquals(1, unknown.applied);
        assertEquals(1, unknown.skipped);
        assertEquals(StoryRingColor.BLUE, Settings.STORY_RING_COLOR.savedValue());
        assertEquals(StoryRingSize.SMALLER, Settings.STORY_RING_SCALE.savedValue());
    }

    // The glass sliders (#391) travel as whole numbers and come back without a restart. A number outside
    // the slider's range, or one that isn't a whole number, is skipped while the rest of the file applies.
    @Test public void glassSlidersRoundTripAsWholeNumbersInsideTheirRange() throws Exception {
        Settings.GLASS_TAB_BAR_OPACITY.save(65);
        Settings.GLASS_TAB_BAR_BLUR_RADIUS.save(36);
        byte[] exported = ConfigurationBackup.export();
        JSONObject values = new JSONObject(new String(exported, StandardCharsets.UTF_8)).getJSONObject("settings");
        assertEquals("integer", values.getJSONObject(Settings.GLASS_TAB_BAR_OPACITY.key).getString("type"));
        assertEquals(65, values.getJSONObject(Settings.GLASS_TAB_BAR_OPACITY.key).getInt("value"));
        assertEquals(36, values.getJSONObject(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key).getInt("value"));
        Settings.GLASS_TAB_BAR_OPACITY.resetToDefault();
        Settings.GLASS_TAB_BAR_BLUR_RADIUS.resetToDefault();

        ConfigurationBackup.Result restored = ConfigurationBackup.restore(exported);
        assertEquals(0, restored.skipped);
        assertFalse("the sliders show at once", restored.restart);
        assertEquals(65, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        assertEquals(36, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertEquals("65", original.getString(Settings.GLASS_TAB_BAR_OPACITY.key, ""));
        ConfigurationBackup.forgetUndo();

        for (String entry : new String[]{
                entry(Settings.GLASS_TAB_BAR_OPACITY.key, "integer", 151),
                entry(Settings.GLASS_TAB_BAR_OPACITY.key, "integer", -1),
                entry(Settings.GLASS_TAB_BAR_OPACITY.key, "integer", 12.5),
                entry(Settings.GLASS_TAB_BAR_OPACITY.key, "integer", "80"),
                entry(Settings.GLASS_TAB_BAR_OPACITY.key, "enum", "FROSTED"),
                entry(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key, "integer", 1),
                entry(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key, "integer", 49),
                entry(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key, "string", "20"),
        }) {
            ConfigurationBackup.Result refused = ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false) + "," + entry));
            assertEquals(entry, 1, refused.skipped);
            assertFalse(entry, Settings.HIDE_ADS.savedValue());
            assertEquals(entry, 65, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
            assertEquals(entry, 36, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
            Settings.HIDE_ADS.resetToDefault();
            ConfigurationBackup.forgetUndo();
        }
        assertEquals(0, ConfigurationBackup.accepted(Settings.GLASS_TAB_BAR_OPACITY, "integer", 0));
        assertEquals(150, ConfigurationBackup.accepted(Settings.GLASS_TAB_BAR_OPACITY, "integer", 150));
        assertEquals(2, ConfigurationBackup.accepted(Settings.GLASS_TAB_BAR_BLUR_RADIUS, "integer", 2));
        assertEquals(48, ConfigurationBackup.accepted(Settings.GLASS_TAB_BAR_BLUR_RADIUS, "integer", 48));
    }

    // A file an older build exported carries the glass steps under their old keys. Each comes back as the
    // value it drew, a step this build never had is skipped, and a file that carries a slider too keeps the slider.
    @Test public void glassStepsFromAnOlderFileComeBackAsTheValueTheyDrew() throws Exception {
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.OLD_GLASS_BLUR_KEY));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.OLD_GLASS_OPACITY_KEY));
        ConfigurationBackup.Result restored = ConfigurationBackup.restore(file(
                entry(Settings.OLD_GLASS_BLUR_KEY, "enum", "STRONG") + "," + entry(Settings.OLD_GLASS_OPACITY_KEY, "enum", "FROSTED")));
        assertEquals(2, restored.applied);
        assertEquals(0, restored.skipped);
        assertEquals(30, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertEquals(135, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        assertFalse("nothing is saved under the old keys", original.contains(Settings.OLD_GLASS_BLUR_KEY));
        assertFalse(original.contains(Settings.OLD_GLASS_OPACITY_KEY));
        ConfigurationBackup.forgetUndo();

        ConfigurationBackup.Result light = ConfigurationBackup.restore(file(
                entry(Settings.OLD_GLASS_BLUR_KEY, "enum", "LIGHT") + "," + entry(Settings.OLD_GLASS_OPACITY_KEY, "enum", "CLEAR")));
        assertEquals(2, light.applied);
        assertEquals(9, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertEquals(50, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        ConfigurationBackup.forgetUndo();

        ConfigurationBackup.Result unknown = ConfigurationBackup.restore(file(
                entry(Settings.OLD_GLASS_BLUR_KEY, "enum", "HEAVY") + "," + entry(Settings.OLD_GLASS_OPACITY_KEY, "string", "STANDARD")
                        + "," + entry(Settings.HIDE_ADS.key, "boolean", false)));
        assertEquals(1, unknown.applied);
        assertEquals(2, unknown.skipped);
        assertEquals(9, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertEquals(50, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        ConfigurationBackup.forgetUndo();

        ConfigurationBackup.Result both = ConfigurationBackup.restore(file(
                entry(Settings.OLD_GLASS_BLUR_KEY, "enum", "STRONG") + "," + entry(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key, "integer", 12)));
        assertEquals(1, both.applied);
        assertEquals(1, both.skipped);
        assertEquals(12, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        ConfigurationBackup.forgetUndo();

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        assertNull("no glass bar in the build, no slider to carry a step to", ConfigurationBackup.oldGlassStep(
                Settings.OLD_GLASS_BLUR_KEY, "enum", "STRONG", ConfigurationBackup.eligible()));
    }

    // A step saved on the phone by an older build becomes the slider's value the first time this build's
    // settings load, its key goes, and a slider the user already moved is left alone (#391).
    @Test public void glassStepsSavedByAnOlderBuildCarryOverOnceToTheSliders() {
        original.edit().putString(Settings.OLD_GLASS_BLUR_KEY, "STRONG").putString(Settings.OLD_GLASS_OPACITY_KEY, "CLEAR").commit();
        Settings.carryOverGlassSteps();
        assertEquals(30, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertEquals(50, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        assertFalse(original.contains(Settings.OLD_GLASS_BLUR_KEY));
        assertFalse(original.contains(Settings.OLD_GLASS_OPACITY_KEY));

        original.edit().putString(Settings.OLD_GLASS_BLUR_KEY, "LIGHT").putString(Settings.OLD_GLASS_OPACITY_KEY, "FROSTED").commit();
        Settings.carryOverGlassSteps();
        assertEquals("the slider already moved, so the step is dropped", 30, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertEquals(50, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        assertFalse(original.contains(Settings.OLD_GLASS_BLUR_KEY));
        assertFalse(original.contains(Settings.OLD_GLASS_OPACITY_KEY));

        Settings.GLASS_TAB_BAR_BLUR_RADIUS.resetToDefault();
        Settings.GLASS_TAB_BAR_OPACITY.resetToDefault();
        original.edit().putString(Settings.OLD_GLASS_BLUR_KEY, "HEAVY").putString(Settings.OLD_GLASS_OPACITY_KEY, "STANDARD").commit();
        Settings.carryOverGlassSteps();
        assertEquals("a step this build never had draws the standard", 18, (int) Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue());
        assertEquals(100, (int) Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        assertFalse(original.contains(Settings.OLD_GLASS_BLUR_KEY));
        assertFalse(original.contains(Settings.OLD_GLASS_OPACITY_KEY));
        assertFalse("standard saves nothing", original.contains(Settings.GLASS_TAB_BAR_OPACITY.key));
    }

    @Test public void documentedRestartChoicesShareMetadataWithRowAndImportFeedback() throws Exception {
        Setting<?>[] choices = {Settings.START_ON_FOLLOWING, Settings.ONLY_FOLLOWING,
                Settings.STORY_RING, Settings.STORY_RING_SCALE, Settings.STORY_RING_COLOR, Settings.HIDE_META_AI_SEARCH,
                Settings.REMOVE_BOTTOM_SPACE};
        for (Setting<?> setting : choices) assertTrue(setting.key, setting.rebootApp);
        assertFalse(Settings.HIDE_ADS.rebootApp);
        ConfigurationBackup.Result imported = ConfigurationBackup.restore(file(entry(Settings.ONLY_FOLLOWING.key, "boolean", true)));
        assertTrue(imported.restart);
        assertTrue(ConfigurationBackup.undo().restart);
    }

    @Test public void exactExpiryAndProcessRestartDiscardUndoWithoutRevertingImport() throws Exception {
        ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)));
        SystemClock.sleep(ConfigurationBackup.UNDO_WINDOW_MS - 1);
        assertTrue(ConfigurationBackup.canUndo());
        SystemClock.sleep(1);
        assertFalse(ConfigurationBackup.canUndo());
        assertNull(ConfigurationBackup.undo());
        assertFalse(Settings.HIDE_ADS.savedValue());
        ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", true)));
        ConfigurationBackup.forgetUndo();
        assertNull(ConfigurationBackup.undo());
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test public void failedCommitReportsSuccessfulRollbackWithNoPartialApplication() throws Exception {
        failCommits(1);
        try {
            ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)));
            fail("Commit failure wasn't reported");
        } catch (Setting.BatchFailed failed) { assertTrue(failed.restored); }
        assertTrue(Settings.HIDE_ADS.savedValue());
        assertTrue(original.getBoolean(Settings.HIDE_ADS.key, true));
        assertFalse(ConfigurationBackup.canUndo());
    }

    @Test public void failedRollbackIsReportedAndTheSnapshotCanRecoverOnce() throws Exception {
        failCommits(2);
        try {
            ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)));
            fail("Commit failure wasn't reported");
        } catch (Setting.BatchFailed failed) { assertFalse(failed.restored); }
        assertTrue(ConfigurationBackup.canUndo());
        setStore(original);
        assertNotNull(ConfigurationBackup.undo());
        assertTrue(Settings.HIDE_ADS.savedValue());
        assertNull(ConfigurationBackup.undo());
    }

    @Test public void failedUndoIsConsumedAndReportsItsRollbackVerdict() throws Exception {
        ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)));
        failCommits(1);
        try { ConfigurationBackup.undo(); fail("Undo commit failure wasn't reported"); }
        catch (Setting.BatchFailed failed) { assertTrue(failed.restored); }
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertNull(ConfigurationBackup.undo());
    }

    @Test @Config(sdk = {28, 29, 37})
    public void undoUsesTheRecommendedInteractiveWindowOrTheLegacyFallback() throws Exception {
        AccessibilityManager manager = RuntimeEnvironment.getApplication()
                .getSystemService(AccessibilityManager.class);
        for (int recommendation : new int[]{10_000, 30_000, 120_000}) {
            if (Build.VERSION.SDK_INT >= 29) {
                Shadows.shadowOf(manager).setInteractiveUiTimeout(recommendation);
                Shadows.shadowOf(manager).setNonInteractiveUiTimeout(0);
            }
            Settings.HIDE_ADS.save(true);
            ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)));
            int window = Build.VERSION.SDK_INT >= 29 ? recommendation : 10_000;
            SystemClock.sleep(window - 1);
            assertTrue("Undo ended before the recommended window", ConfigurationBackup.canUndo());
            SystemClock.sleep(1);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertFalse(ConfigurationBackup.canUndo());
        }
    }

    @Test @Config(sdk = {29, 37})
    public void undoAlsoRespectsTheRecommendedReadingTime() throws Exception {
        AccessibilityManager manager = RuntimeEnvironment.getApplication()
                .getSystemService(AccessibilityManager.class);
        Shadows.shadowOf(manager).setInteractiveUiTimeout(10_000);
        Shadows.shadowOf(manager).setNonInteractiveUiTimeout(120_000);
        ConfigurationBackup.restore(file(entry(Settings.HIDE_ADS.key, "boolean", false)));
        SystemClock.sleep(30_000);
        assertTrue("Undo omitted the text content flag", ConfigurationBackup.canUndo());
    }

    @Test public void undoPreservesAChoiceChangedAfterTheImport() throws Exception {
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.AUTO);
        ConfigurationBackup.restore(file(entry(Settings.PLAYBACK_QUALITY.key, "enum", "HIGHEST")));
        Settings.PLAYBACK_QUALITY.save(PlaybackQuality.DATA_SAVER);
        ConfigurationBackup.undo();
        assertEquals(PlaybackQuality.DATA_SAVER, Settings.PLAYBACK_QUALITY.savedValue());
        assertFalse(ConfigurationBackup.canUndo());
    }

    private void rejected(byte[] bytes) throws Exception {
        try { ConfigurationBackup.restore(bytes); fail("Invalid file accepted"); }
        catch (IOException expected) { }
        assertTrue("Invalid file partially applied", Settings.HIDE_ADS.savedValue());
        assertTrue(original.getBoolean(Settings.HIDE_ADS.key, true));
        assertFalse(ConfigurationBackup.canUndo());
    }

    static byte[] file(String entries) {
        return ("{\"project\":\"HushGram\",\"schema\":1,\"settings\":{" + entries + "}}").getBytes(StandardCharsets.UTF_8);
    }

    static String entry(String key, String type, Object value) throws Exception {
        return JSONObject.quote(key) + ":" + new JSONObject().put("type", type).put("value", value);
    }

    private static void setStore(SharedPreferences store) throws Exception {
        Field field = SharedPrefCategory.class.getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(Setting.preferences, store);
    }

    private void failCommits(int failures) throws Exception {
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences proxy = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (object, method, args) -> {
                    Object result = method.invoke(original, args);
                    if (!method.getName().equals("edit")) return result;
                    SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (self, action, parameters) -> {
                                if (action.getName().equals("commit") && commits.incrementAndGet() <= failures) {
                                    // The first failed commit may land. The failed rollback does not.
                                    if (commits.get() == 1) editor.commit();
                                    return false;
                                }
                                Object answer = action.invoke(editor, parameters);
                                return answer == editor ? self : answer;
                            });
                });
        setStore(proxy);
    }
}
