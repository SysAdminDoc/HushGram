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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.SharedPrefCategory;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ConfigurationBackupTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private SharedPreferences original;

    @Before public void setup() {
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
        assertEquals(5, values.length());
        assertTrue(values.getJSONObject(Settings.HIDE_ADS.key).getBoolean("value"));
        assertTrue(values.has(BaseSettings.DEBUG.key));
        assertEquals("OFF", values.getJSONObject(Settings.NAVIGATION_SETTINGS_TARGET.key).getString("value"));
        assertFalse(values.getJSONObject(Settings.HIDE_MENU_ROW.key).getBoolean("value"));
        assertFalse(values.getJSONObject(Settings.CATEGORY_PAGES.key).getBoolean("value"));
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

    @Test public void malformedSchemaDuplicatesUtf8AndValuesCannotPartiallyApply() throws Exception {
        String first = entry(Settings.HIDE_ADS.key, "boolean", false);
        String[] entries = {
                first + "," + first,
                first + "," + entry(Settings.PLAYBACK_QUALITY.key, "enum", "P9999"),
                first + "," + entry(Settings.HIDE_REELS_TAB.key, "boolean", "true"),
                first + "," + entry(Settings.SAVE_FOLDER.key, "string", "../../Outside"),
                first + "," + entry(Settings.FILENAME_TEMPLATE.key, "string", "../bad.mp4"),
                first + ",\"unknown\":{\"type\":\"boolean\",\"value\":false,\"value\":true}",
                first + ",\"unknown\":{\"type\":\"boolean\",\"value\":null}",
                first + ",\"unknown\":{\"type\":\"enum\",\"value\":99}",
        };
        for (String malformed : entries) rejected(file(malformed));
        rejected(new String(file(first), StandardCharsets.UTF_8).replace("\"schema\":1", "\"schema\":2").getBytes(StandardCharsets.UTF_8));
        rejected(new String(file(first), StandardCharsets.UTF_8).replace("\"schema\":1", "\"schema\":1.0").getBytes(StandardCharsets.UTF_8));
        rejected(new String(file(first), StandardCharsets.UTF_8).replace("HushGram", "OtherProject").getBytes(StandardCharsets.UTF_8));
        rejected(new byte[]{(byte) 0xc3, (byte) 0x28});
        rejected((new String(file(first), StandardCharsets.UTF_8) + "{}").getBytes(StandardCharsets.UTF_8));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS);
        rejected(file(first + "," + entry(Settings.PLAYBACK_QUALITY.key, "enum", "P9999")));
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
        ConfigurationBackup.Result applied = ConfigurationBackup.restore(file(entry(Settings.DISABLE_ANALYTICS.key, "boolean", false)));
        assertTrue(applied.restart);
        assertFalse(Settings.DISABLE_ANALYTICS.savedValue());
        assertTrue(ConfigurationBackup.undo().restart);
        assertTrue(Settings.DISABLE_ANALYTICS.savedValue());
        assertNull(ConfigurationBackup.undo());
        assertFalse(ConfigurationBackup.restore(file(entry(Settings.DISABLE_ANALYTICS.key, "boolean", true))).restart);
        assertFalse(ConfigurationBackup.canUndo());
    }

    @Test public void documentedRestartChoicesShareMetadataWithRowAndImportFeedback() throws Exception {
        Setting<?>[] choices = {Settings.START_ON_FOLLOWING, Settings.ONLY_FOLLOWING,
                Settings.STORY_RING, Settings.STORY_RING_SCALE, Settings.HIDE_META_AI_SEARCH,
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
