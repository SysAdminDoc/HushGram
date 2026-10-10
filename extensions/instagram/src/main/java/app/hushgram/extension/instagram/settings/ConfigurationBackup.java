/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import app.hushgram.extension.instagram.download.FileNameTemplate;
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.SettingsJson;

/** A portable, typed settings file. It never reads the host's preferences or private ledgers. */
public final class ConfigurationBackup {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_ENTRIES = 512;
    public static final long UNDO_WINDOW_MS = 10_000;
    private static final SettingsJson.Limits LIMITS =
            new SettingsJson.Limits(4, 2048, 4096, MAX_ENTRIES, MAX_BYTES);
    private static Map<Setting<?>, Object> undoValues;
    private static Map<Setting<?>, Object> importedValues;
    private static Map<Setting<?>, Long> importedRevisions;
    private static long undoUntil;
    private static long undoId;

    private ConfigurationBackup() {}

    /** Counts are safe for feedback. Neither keys nor values reach a report. */
    public static final class Result {
        public final int applied;
        public final int skipped;
        public final boolean restart;

        Result(int applied, int skipped, boolean restart) {
            this.applied = applied;
            this.skipped = skipped;
            this.restart = restart;
        }
    }

    /** Deliberate allowlist: installed switches, their value controls, shared save choices, Debug. */
    static Map<String, Setting<?>> eligible() {
        Map<String, Setting<?>> settings = new LinkedHashMap<>();
        for (PatchFamily family : PatchFamily.inThisBuild()) {
            for (Setting<?> setting : family.switches) settings.put(setting.key, setting);
        }
        if (PatchFamily.STORY_RING.inBuild()) settings.put(Settings.STORY_RING_SCALE.key, Settings.STORY_RING_SCALE);
        if (PatchFamily.SPOOF_LOCATION.inBuild()) settings.put(Settings.SPOOF_LOCATION_PLACE.key, Settings.SPOOF_LOCATION_PLACE);
        if (PatchFamily.SANITIZE_SHARING_LINKS.inBuild()) settings.put(Settings.SHARING_DOMAIN.key, Settings.SHARING_DOMAIN);
        if (PatchFamily.LIKE_ANIMATION.inBuild()) settings.put(Settings.LIKE_ANIMATION.key, Settings.LIKE_ANIMATION);
        if (PatchFamily.NOTIFICATION_GROUPS.inBuild()) {
            settings.put(Settings.GROUP_NOTIFICATIONS_BY_TYPE.key, Settings.GROUP_NOTIFICATIONS_BY_TYPE);
        }
        if (PatchFamily.PLAYBACK_QUALITY.inBuild()) {
            settings.put(Settings.PLAYBACK_QUALITY.key, Settings.PLAYBACK_QUALITY);
            settings.put(Settings.QUALITY_TAB_TARGET.key, Settings.QUALITY_TAB_TARGET);
        }
        if (PatchFamily.DATA_SAVER.inBuild()) {
            settings.put(Settings.DATA_SAVER_MOBILE_DATA_ONLY.key, Settings.DATA_SAVER_MOBILE_DATA_ONLY);
        }
        if (PatchFamily.TAP_TO_PLAY.inBuild()) settings.put(Settings.TAP_TO_PLAY_SCOPE.key, Settings.TAP_TO_PLAY_SCOPE);
        if (PatchFamily.REEL_TAP_AND_VOLUME.inBuild()) settings.put(Settings.REEL_TAP_CHOICE.key, Settings.REEL_TAP_CHOICE);
        if (PatchFamily.STORY_TIME.inBuild()) settings.put(Settings.STORY_TIME_MODE.key, Settings.STORY_TIME_MODE);
        if (PatchFamily.REELS_TAB.inBuild()) {
            settings.put(Settings.START_TAB.key, Settings.START_TAB);
            settings.put(Settings.TAB_ORDER.key, Settings.TAB_ORDER);
        }
        if (PatchFamily.GLASS_TAB_BAR.inBuild()) {
            settings.put(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key, Settings.GLASS_TAB_BAR_HAPTIC_STYLE);
        }
        if (PatchFamily.REEL_DOWNLOAD.inBuild()) settings.put(Settings.DOWNLOAD_REEL_COVER.key, Settings.DOWNLOAD_REEL_COVER);
        if (PatchFamily.VIDEO_DOWNLOAD.inBuild()) settings.put(Settings.DOWNLOAD_FEED_COVER.key, Settings.DOWNLOAD_FEED_COVER);
        if (PatchFamily.VIDEO_DOWNLOAD.inBuild()) {
            settings.put(Settings.FEED_DOWNLOAD_BUTTON.key, Settings.FEED_DOWNLOAD_BUTTON);
        }
        if (PatchFamily.REEL_DOWNLOAD.inBuild() || PatchFamily.VIDEO_DOWNLOAD.inBuild()) {
            settings.put(Settings.OPEN_IN_PLAYER.key, Settings.OPEN_IN_PLAYER);
        }
        if (PatchFamily.REEL_DOWNLOAD.inBuild() || PatchFamily.STORY_DOWNLOAD.inBuild()
                || PatchFamily.VIDEO_DOWNLOAD.inBuild()) {
            settings.put(Settings.DOWNLOAD_COMPATIBLE.key, Settings.DOWNLOAD_COMPATIBLE);
            settings.put(Settings.SEND_DOWNLOADS_TO_APP.key, Settings.SEND_DOWNLOADS_TO_APP);
            settings.put(Settings.DOWNLOAD_QUALITY.key, Settings.DOWNLOAD_QUALITY);
            settings.put(Settings.SAVE_FOLDER.key, Settings.SAVE_FOLDER);
            settings.put(Settings.SAVE_FOLDER_PER_ACCOUNT.key, Settings.SAVE_FOLDER_PER_ACCOUNT);
            settings.put(Settings.SAVE_NAME_BY_POST.key, Settings.SAVE_NAME_BY_POST);
            settings.put(Settings.FILENAME_TEMPLATE.key, Settings.FILENAME_TEMPLATE);
        }
        settings.put(BaseSettings.DEBUG.key, BaseSettings.DEBUG);
        settings.put(Settings.NAVIGATION_SETTINGS_TARGET.key, Settings.NAVIGATION_SETTINGS_TARGET);
        settings.put(Settings.HIDE_MENU_ROW.key, Settings.HIDE_MENU_ROW);
        settings.put(Settings.CATEGORY_PAGES.key, Settings.CATEGORY_PAGES);
        return settings;
    }

    /** Export saved choices even during Pause, never the neutral answers the hooks see. */
    public static synchronized byte[] export() throws IOException {
        synchronized (Setting.class) {
            try {
                JSONObject values = new JSONObject();
                for (Setting<?> setting : eligible().values()) {
                    Object value = setting.savedValue();
                    values.put(setting.key, new JSONObject().put("type", type(value))
                            .put("value", value instanceof Enum ? ((Enum<?>) value).name() : value));
                }
                byte[] bytes = new JSONObject().put("project", "HushGram").put("schema", 1)
                        .put("settings", values).toString(2).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES || values.length() > MAX_ENTRIES) throw invalid();
                return bytes;
            } catch (JSONException failure) {
                throw invalid();
            }
        }
    }

    /** Read at most the bound plus one byte; a dishonest provider cannot grow an unbounded buffer. */
    public static byte[] read(InputStream input) throws IOException {
        if (input == null) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - bytes.size()));
            if (count == -1) return bytes.toByteArray();
            if (count == 0) throw invalid();
            bytes.write(buffer, 0, count);
            if (bytes.size() > MAX_BYTES) throw invalid();
        }
    }

    /** Validate every entry before starting the only write transaction. Unknown/ineligible keys skip. */
    public static synchronized Result restore(byte[] bytes) throws IOException {
        final Map<Setting<?>, Object> updates = new LinkedHashMap<>();
        final Map<String, Setting<?>> allowed = eligible();
        int skipped = 0;
        try {
            JSONObject root = SettingsJson.parseObject(bytes, LIMITS);
            if (root.length() != 3 || !"HushGram".equals(root.get("project"))
                    || !(root.get("schema") instanceof Integer) || root.getInt("schema") != 1
                    || !(root.get("settings") instanceof JSONObject)) throw invalid();
            JSONObject values = root.getJSONObject("settings");
            if (values.length() > MAX_ENTRIES) throw invalid();
            for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!key.matches("[A-Za-z0-9_.-]{1,128}") || !(values.get(key) instanceof JSONObject)) throw invalid();
                JSONObject entry = values.getJSONObject(key);
                if (entry.length() != 2 || !(entry.get("type") instanceof String)) throw invalid();
                String declared = entry.getString("type");
                Object value = entry.get("value");
                if (!declared.equals(type(value)) && !(declared.equals("enum") && value instanceof String)) throw invalid();
                Setting<?> known = Setting.getSettingFromPath(key);
                if (known != null && (known.defaultValue instanceof Boolean
                        || known.defaultValue instanceof Enum || known.defaultValue instanceof String)
                        && !declared.equals(type(known.defaultValue))) throw invalid();
                if (known != null && known.defaultValue instanceof Enum) {
                    Object matched = null;
                    for (Object choice : ((Enum<?>) known.defaultValue).getDeclaringClass().getEnumConstants()) {
                        if (((Enum<?>) choice).name().equals(value)) matched = choice;
                    }
                    if (matched == null) throw invalid();
                    value = matched;
                }
                if (known == Settings.SAVE_FOLDER && !SaveFolder.isImportable((String) value)) throw invalid();
                if (known == Settings.FILENAME_TEMPLATE && !FileNameTemplate.isImportable((String) value)) throw invalid();
                Setting<?> setting = allowed.get(key);
                if (setting == null || !setting.includeWithImportExport) {
                    skipped++;
                    continue;
                }
                updates.put(setting, value);
            }
        } catch (JSONException | IllegalArgumentException failure) {
            throw invalid();
        }
        synchronized (Setting.class) {
            Map<Setting<?>, Object> before = new LinkedHashMap<>();
            boolean restart = false;
            for (var entry : updates.entrySet()) {
                Setting<?> setting = entry.getKey();
                if (!entry.getValue().equals(setting.savedValue())) {
                    before.put(setting, setting.savedValue());
                    restart |= setting.rebootApp;
                }
            }
            if (!before.isEmpty()) {
                forgetUndo();
                try {
                    Setting.saveAll(updates);
                    offerUndo(before);
                } catch (Setting.BatchFailed failure) {
                    if (!failure.restored) offerUndo(before);
                    throw failure;
                }
            }
            return new Result(updates.size(), skipped, restart);
        }
    }

    /** Expiry and process lifetime bound the snapshot; there is no disk copy of Undo. */
    public static synchronized boolean canUndo() {
        if (undoValues != null && SystemClock.elapsedRealtime() >= undoUntil) forgetUndo();
        return undoValues != null;
    }

    /** Consumed before a commit, including a failed Undo. The caller reports rollback explicitly. */
    public static synchronized Result undo() throws IOException {
        return undo(undoToken());
    }

    public static synchronized long undoToken() {
        return canUndo() ? undoId : 0;
    }

    public static synchronized long undoDeadline(long token) {
        return token != 0 && token == undoToken() ? undoUntil : 0;
    }

    /** A queued action belongs to the import it offered, never a later restore point. */
    public static synchronized Result undo(long token) throws IOException {
        if (undoDeadline(token) == 0) return null;
        Map<Setting<?>, Object> values = undoValues;
        Map<Setting<?>, Object> expected = importedValues;
        Map<Setting<?>, Long> revisions = importedRevisions;
        forgetUndo();
        synchronized (Setting.class) {
            boolean restart = false;
            int skipped = 0;
            for (var entries = values.entrySet().iterator(); entries.hasNext();) {
                var entry = entries.next();
                Setting<?> setting = entry.getKey();
                if (revisions.get(setting) != setting.savedRevision()
                        || !expected.get(setting).equals(setting.savedValue())) {
                    entries.remove();
                    skipped++;
                } else restart |= setting.rebootApp && !entry.getValue().equals(setting.savedValue());
            }
            Setting.saveAll(values);
            return new Result(values.size(), skipped, restart);
        }
    }

    private static void offerUndo(Map<Setting<?>, Object> values) {
        undoValues = values;
        importedValues = new LinkedHashMap<>();
        importedRevisions = new LinkedHashMap<>();
        for (Setting<?> setting : values.keySet()) {
            importedValues.put(setting, setting.savedValue());
            importedRevisions.put(setting, setting.savedRevision());
        }
        int timeout = (int) UNDO_WINDOW_MS;
        Context context = Utils.getContext();
        if (Build.VERSION.SDK_INT >= 29 && context != null) {
            AccessibilityManager accessibility = context.getSystemService(AccessibilityManager.class);
            if (accessibility != null) timeout = accessibility.getRecommendedTimeoutMillis(timeout,
                    AccessibilityManager.FLAG_CONTENT_TEXT | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        undoId++;
        undoUntil = SystemClock.elapsedRealtime() + timeout;
        Utils.runOnMainThreadDelayed(ConfigurationBackup::canUndo, timeout);
    }

    static synchronized void forgetUndo() {
        undoValues = null;
        importedValues = null;
        importedRevisions = null;
        undoUntil = 0;
    }

    private static String type(Object value) throws IOException {
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Enum) return "enum";
        if (value instanceof String) return "string";
        throw invalid();
    }

    private static IOException invalid() {
        // Parser causes can carry input values. Keep untrusted content out of diagnostic logs.
        return new IOException("Invalid HushGram configuration file");
    }
}
