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
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.download.FileNameTemplate;
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.instagram.direct.MessageBox;
import app.hushgram.extension.instagram.profile.ProfileTabs;
import app.hushgram.extension.instagram.misc.GlassBlur;
import app.hushgram.extension.instagram.misc.GlassOpacity;
import app.hushgram.extension.instagram.misc.NotificationGroups;
import app.hushgram.extension.instagram.misc.SettingsRows;
import app.hushgram.extension.instagram.misc.SpoofLocation;
import app.hushgram.extension.instagram.reels.TabOrder;
import app.hushgram.extension.instagram.share.ShareSheetActions;
import app.hushgram.extension.instagram.share.SharingDomain;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.IntegerSetting;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.SettingsJson;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;

/** A portable, typed settings file. It never reads the host's preferences or private ledgers. */
public final class ConfigurationBackup {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_ENTRIES = 512;
    public static final long UNDO_WINDOW_MS = 10_000;
    /** The ordinary text bound. Selection and blocked-word lists have the larger bound below. */
    static final int MAX_TEXT = 256;
    /**
     * The bounded reader's longest string, also used by blocked words and native settings-row
     * selections. The current native row ids join to 2,953 characters.
     */
    static final int MAX_WORDS_TEXT = 4096;
    private static final SettingsJson.Limits LIMITS =
            new SettingsJson.Limits(4, 2048, MAX_WORDS_TEXT, MAX_ENTRIES, MAX_BYTES);
    private static Map<Setting<?>, Object> undoValues;
    private static Map<Setting<?>, Object> importedValues;
    private static Map<Setting<?>, Long> importedRevisions;
    private static long undoUntil;
    private static long undoId;

    private ConfigurationBackup() {}

    /** The suggested document name, with optional local time in safe, locale-independent digits. */
    static String fileName(Date when, boolean dated) {
        return "HushGram-settings" + (dated
                ? "-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(when) : "") + ".json";
    }

    /** The page no longer permits a queued change. Nothing was written or consumed. */
    public static final class Held extends IOException {
        private Held() { super("The settings change is no longer permitted"); }
    }

    /** Counts are safe for feedback. Neither keys nor values reach a report. */
    public static final class Result {
        public final int applied;
        public final int skipped;
        /**
         * Lock switches the file (or Undo) would have changed but {@link #heldByLock} kept as they
         * are. They're known settings, so they aren't counted with the skipped ones.
         */
        public final int held;
        public final boolean restart;
        /**
         * Each setting this changed, with the value it held before, so the page can offer Restart
         * now for the ones that wait on a restart, as it does when their own rows are flipped.
         */
        public final Map<Setting<?>, Object> before;

        Result(int applied, int skipped, int held, boolean restart, Map<Setting<?>, Object> before) {
            this.applied = applied;
            this.skipped = skipped;
            this.held = held;
            this.restart = restart;
            this.before = Collections.unmodifiableMap(before);
        }
    }

    /** Deliberate allowlist: installed switches, their value controls, shared save choices, Debug. */
    static Map<String, Setting<?>> eligible() {
        Map<String, Setting<?>> settings = new LinkedHashMap<>();
        for (PatchFamily family : PatchFamily.inThisBuild()) {
            for (Setting<?> setting : family.switches) settings.put(setting.key, setting);
        }
        if (PatchFamily.STORY_RING.inBuild()) {
            settings.put(Settings.STORY_RING_SCALE.key, Settings.STORY_RING_SCALE);
            settings.put(Settings.STORY_RING_COLOR.key, Settings.STORY_RING_COLOR);
        }
        if (PatchFamily.ROUNDED_POST_CORNERS.inBuild()) settings.put(Settings.ROUNDED_CORNER_SIZE.key, Settings.ROUNDED_CORNER_SIZE);
        if (PatchFamily.STORY_PROGRESS_BAR.inBuild()) settings.put(Settings.STORY_BAR_THICKNESS.key, Settings.STORY_BAR_THICKNESS);
        if (PatchFamily.STORIES_TRAY.inBuild()) settings.put(Settings.TRAY_BADGE.key, Settings.TRAY_BADGE);
        if (PatchFamily.SPOOF_LOCATION.inBuild()) settings.put(Settings.SPOOF_LOCATION_PLACE.key, Settings.SPOOF_LOCATION_PLACE);
        if (PatchFamily.CAPTION_WORD_REELS.inBuild()) settings.put(Settings.REELS_BLOCKED_WORDS.key, Settings.REELS_BLOCKED_WORDS);
        if (PatchFamily.SANITIZE_SHARING_LINKS.inBuild()) settings.put(Settings.SHARING_DOMAIN.key, Settings.SHARING_DOMAIN);
        if (PatchFamily.LIKE_ANIMATION.inBuild()) settings.put(Settings.LIKE_ANIMATION.key, Settings.LIKE_ANIMATION);
        if (PatchFamily.NOTIFICATION_GROUPS.inBuild()) {
            settings.put(Settings.GROUP_NOTIFICATIONS_BY_TYPE.key, Settings.GROUP_NOTIFICATIONS_BY_TYPE);
            settings.put(Settings.MUTED_NOTIFICATION_CHANNELS.key, Settings.MUTED_NOTIFICATION_CHANNELS);
        }
        if (PatchFamily.PLAYBACK_QUALITY.inBuild()) {
            settings.put(Settings.PLAYBACK_QUALITY.key, Settings.PLAYBACK_QUALITY);
            settings.put(Settings.QUALITY_TAB_TARGET.key, Settings.QUALITY_TAB_TARGET);
        }
        if (PatchFamily.DATA_SAVER.inBuild()) {
            settings.put(Settings.DATA_SAVER_MOBILE_DATA_ONLY.key, Settings.DATA_SAVER_MOBILE_DATA_ONLY);
        }
        if (PatchFamily.TAP_TO_PLAY.inBuild()) {
            settings.put(Settings.TAP_TO_PLAY_SCOPE.key, Settings.TAP_TO_PLAY_SCOPE);
            settings.put(Settings.TAP_TO_PLAY_AUTO_SCROLL_WAITS.key, Settings.TAP_TO_PLAY_AUTO_SCROLL_WAITS);
        }
        if (PatchFamily.REEL_TAP_AND_VOLUME.inBuild()) settings.put(Settings.REEL_TAP_CHOICE.key, Settings.REEL_TAP_CHOICE);
        if (PatchFamily.REEL_SCROLLING.inBuild()) settings.put(Settings.SCROLL_BREAK.key, Settings.SCROLL_BREAK);
        if (PatchFamily.REEL_DECLUTTER.inBuild()) {
            settings.put(Settings.BURN_IN_GUARD.key, Settings.BURN_IN_GUARD);
            settings.put(Settings.BURN_IN_WAIT.key, Settings.BURN_IN_WAIT);
            settings.put(Settings.BURN_IN_STAY_DIM.key, Settings.BURN_IN_STAY_DIM);
        }
        if (GhostMode.offered(GhostMode.switches(PatchFamily.inThisBuild()))) {
            settings.put(Settings.GHOST_MODE_PICKS.key, Settings.GHOST_MODE_PICKS);
            settings.put(Settings.GHOST_TAB_TARGET.key, Settings.GHOST_TAB_TARGET);
        }
        if (PatchFamily.MESSAGE_BOX.inBuild()) settings.put(Settings.HIDDEN_MESSAGE_BOX_BUTTONS.key, Settings.HIDDEN_MESSAGE_BOX_BUTTONS);
        if (PatchFamily.PROFILE_TABS.inBuild()) {
            settings.put(Settings.HIDDEN_OWN_PROFILE_TABS.key, Settings.HIDDEN_OWN_PROFILE_TABS);
            settings.put(Settings.HIDDEN_PROFILE_TABS.key, Settings.HIDDEN_PROFILE_TABS);
        }
        if (PatchFamily.STORY_TIME.inBuild()) settings.put(Settings.STORY_TIME_MODE.key, Settings.STORY_TIME_MODE);
        if (PatchFamily.FRIENDSHIP_STATUS.inBuild()) settings.put(Settings.FOLLOW_LABEL_PLACE.key, Settings.FOLLOW_LABEL_PLACE);
        if (PatchFamily.FULL_SCREEN_BARS.inBuild()) settings.put(Settings.FULL_SCREEN_HIDES.key, Settings.FULL_SCREEN_HIDES);
        if (PatchFamily.TAB_BAR_LAYOUT.inBuild()) settings.put(Settings.TAB_BAR_LAYOUT.key, Settings.TAB_BAR_LAYOUT);
        if (PatchFamily.REELS_TAB.inBuild()) {
            settings.put(Settings.START_TAB.key, Settings.START_TAB);
            settings.put(Settings.TAB_ORDER.key, Settings.TAB_ORDER);
        }
        if (PatchFamily.GLASS_TAB_BAR.inBuild()) {
            settings.put(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key, Settings.GLASS_TAB_BAR_HAPTIC_STYLE);
            settings.put(Settings.GLASS_TAB_BAR_BLUR_RADIUS.key, Settings.GLASS_TAB_BAR_BLUR_RADIUS);
            settings.put(Settings.GLASS_TAB_BAR_OPACITY.key, Settings.GLASS_TAB_BAR_OPACITY);
            settings.put(Settings.GLASS_TAB_BAR_HEIGHT.key, Settings.GLASS_TAB_BAR_HEIGHT);
            settings.put(Settings.GLASS_TAB_BAR_CORNERS.key, Settings.GLASS_TAB_BAR_CORNERS);
            settings.put(Settings.GLASS_TAB_BAR_LIFT.key, Settings.GLASS_TAB_BAR_LIFT);
            settings.put(Settings.GLASS_TAB_BAR_REELS_HIDE.key, Settings.GLASS_TAB_BAR_REELS_HIDE);
        }
        if (PatchFamily.SHARE_SHEET.inBuild()) {
            settings.put(Settings.HIDDEN_SHARE_SHEET_BUTTONS.key, Settings.HIDDEN_SHARE_SHEET_BUTTONS);
        }
        if (PatchFamily.ACCENT_COLOR.inBuild()) settings.put(Settings.ACCENT_HUE.key, Settings.ACCENT_HUE);
        if (PatchFamily.FONT.inBuild()) settings.put(Settings.FONT.key, Settings.FONT);
        if (PatchFamily.APP_ICON.inBuild()) settings.put(Settings.APP_ICON.key, Settings.APP_ICON);
        if (PatchFamily.SETTINGS_ROWS.inBuild()) settings.put(Settings.HIDDEN_SETTINGS_ROWS.key, Settings.HIDDEN_SETTINGS_ROWS);
        if (PatchFamily.REEL_DOWNLOAD.inBuild()) {
            settings.put(Settings.DOWNLOAD_REEL_COVER.key, Settings.DOWNLOAD_REEL_COVER);
            settings.put(Settings.DOWNLOAD_REEL_AUDIO.key, Settings.DOWNLOAD_REEL_AUDIO);
            settings.put(Settings.REEL_DOWNLOAD_BUTTON.key, Settings.REEL_DOWNLOAD_BUTTON);
            settings.put(Settings.REEL_NOT_INTERESTED_BUTTON.key, Settings.REEL_NOT_INTERESTED_BUTTON);
            settings.put(Settings.REEL_BLOCK_BUTTON.key, Settings.REEL_BLOCK_BUTTON);
            settings.put(Settings.HIDE_SOUND_REELS.key, Settings.HIDE_SOUND_REELS);
        }
        if (PatchFamily.REEL_POST_TIME.inBuild()) {
            settings.put(Settings.REEL_POST_TIME_SECONDS.key, Settings.REEL_POST_TIME_SECONDS);
            settings.put(Settings.REEL_POST_TIME_AGO.key, Settings.REEL_POST_TIME_AGO);
        }
        if (PatchFamily.VIDEO_DOWNLOAD.inBuild()) settings.put(Settings.DOWNLOAD_FEED_COVER.key, Settings.DOWNLOAD_FEED_COVER);
        if (PatchFamily.VIDEO_DOWNLOAD.inBuild()) settings.put(Settings.DOWNLOAD_CHOOSE_PAGES.key, Settings.DOWNLOAD_CHOOSE_PAGES);
        if (PatchFamily.VIDEO_DOWNLOAD.inBuild()) {
            settings.put(Settings.FEED_DOWNLOAD_BUTTON.key, Settings.FEED_DOWNLOAD_BUTTON);
            settings.put(Settings.FEED_DOWNLOAD_ALL_PAGES.key, Settings.FEED_DOWNLOAD_ALL_PAGES);
            settings.put(Settings.HOLD_TO_DOWNLOAD.key, Settings.HOLD_TO_DOWNLOAD);
            settings.put(Settings.HOLD_FINGERS.key, Settings.HOLD_FINGERS);
            settings.put(Settings.HOLD_TIME.key, Settings.HOLD_TIME);
        }
        if (PatchFamily.REEL_DOWNLOAD.inBuild() || PatchFamily.VIDEO_DOWNLOAD.inBuild()) {
            settings.put(Settings.OPEN_IN_PLAYER.key, Settings.OPEN_IN_PLAYER);
        }
        if (PatchFamily.REEL_DOWNLOAD.inBuild() || PatchFamily.STORY_DOWNLOAD.inBuild()
                || PatchFamily.VIDEO_DOWNLOAD.inBuild()) {
            settings.put(Settings.DOWNLOAD_COMPATIBLE.key, Settings.DOWNLOAD_COMPATIBLE);
            settings.put(Settings.DOWNLOAD_CONVERT.key, Settings.DOWNLOAD_CONVERT);
            // The switch only. What it remembered stays in DownloadHistory's own file, never exported.
            settings.put(Settings.DOWNLOAD_SKIP_SAVED.key, Settings.DOWNLOAD_SKIP_SAVED);
            // The switch only, like the one above. The list itself stays on the phone and is never exported.
            settings.put(Settings.VIEWING_HISTORY.key, Settings.VIEWING_HISTORY);
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
        settings.put(Settings.PROFILE_MENU_LONG_PRESS.key, Settings.PROFILE_MENU_LONG_PRESS);
        // A backup with every way in off still leaves the menu row ({@link SettingsEntry#menuRowLeftOut}).
        settings.put(Settings.SETTINGS_SHORTCUT.key, Settings.SETTINGS_SHORTCUT);
        settings.put(Settings.SETTINGS_LOGO_PRESS.key, Settings.SETTINGS_LOGO_PRESS);
        settings.put(Settings.CATEGORY_PAGES.key, Settings.CATEGORY_PAGES);
        settings.put(Settings.DESCRIPTIONS_BEHIND_INFO.key, Settings.DESCRIPTIONS_BEHIND_INFO);
        settings.put(Settings.DATE_SETTINGS_EXPORT.key, Settings.DATE_SETTINGS_EXPORT);
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

    /**
     * Validate every entry before starting the only write transaction. A file whose shape is wrong
     * is refused whole. An entry this build can't take is skipped and counted: an unknown or
     * ineligible key, a value of another type than its setting's, an enum constant this build
     * doesn't have, or text its setting's own check refuses. A file from a newer HushGram can carry
     * any of these, and one of them no longer costs every other choice in it.
     */
    public static synchronized Result restore(byte[] bytes) throws IOException {
        return restore(bytes, () -> true);
    }

    /** Rechecks the calling page's permission at the transaction boundary, after file validation. */
    public static synchronized Result restore(byte[] bytes, java.util.function.BooleanSupplier mayCommit) throws IOException {
        final Map<Setting<?>, Object> updates = new LinkedHashMap<>();
        final Map<String, Setting<?>> allowed = eligible();
        int skipped = 0;
        int held = 0;
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
                if (entry.length() != 2 || !(entry.get("type") instanceof String) || !entry.has("value")) throw invalid();
                Setting<?> setting = allowed.get(key);
                Object value = setting == null ? null : accepted(setting, entry.getString("type"), entry.get("value"));
                if (setting == null) {
                    // A file from before the glass sliders carries a step, which comes back as the value it drew (#391).
                    Map.Entry<Setting<?>, Object> step = oldGlassStep(key, entry.getString("type"), entry.get("value"), allowed);
                    if (step != null) {
                        setting = step.getKey();
                        value = step.getValue();
                        // A file with both the step and the slider is a hand-made one; the slider wins.
                        if (values.has(setting.key)) {
                            skipped++;
                            continue;
                        }
                    }
                }
                if (value == null || !setting.includeWithImportExport) {
                    skipped++;
                    continue;
                }
                if (heldByLock(setting)) {
                    // Counted only when the file asked for a change: one matching what's saved changed nothing.
                    if (!value.equals(setting.savedValue())) held++;
                    continue;
                }
                updates.put(setting, value);
            }
        } catch (JSONException | IllegalArgumentException failure) {
            throw invalid();
        }
        synchronized (Setting.class) {
            requirePermission(mayCommit);
            // Authentication can change while a file is being validated. Hold lock switches at
            // the same boundary as the writes, even when the calling page itself stays open.
            for (var entries = updates.entrySet().iterator(); entries.hasNext();) {
                var entry = entries.next();
                if (heldByLock(entry.getKey())) {
                    if (!entry.getValue().equals(entry.getKey().savedValue())) held++;
                    entries.remove();
                }
            }
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
                for (var changed : before.entrySet()) AbstractPreferenceFragment.noteRunningValue(changed.getKey(), changed.getValue());
                try {
                    Setting.saveAll(updates);
                    offerUndo(before);
                } catch (Setting.BatchFailed failure) {
                    if (!failure.restored) offerUndo(before);
                    throw failure;
                }
            }
            return new Result(updates.size(), skipped, held, restart, before);
        }
    }

    /**
     * [value] as [setting] would save it, declared as [declared], or null when this build can't take
     * it: another type than the setting's, an enum constant it doesn't have, text over
     * {@link #MAX_TEXT} characters ({@link #MAX_WORDS_TEXT} for blocked words, settings rows and muted
     * notification types) or text {@link #textAllowed} refuses.
     */
    @Nullable
    static Object accepted(Setting<?> setting, String declared, Object value) {
        Object fallback = setting.defaultValue;
        if (fallback instanceof Boolean) return "boolean".equals(declared) && value instanceof Boolean ? value : null;
        // A slider's whole number, inside the range its row offers.
        if (setting instanceof IntegerSetting) {
            IntegerSetting number = (IntegerSetting) setting;
            if (!"integer".equals(declared) || !(value instanceof Integer)) return null;
            int given = (Integer) value;
            return given < number.minimum() || given > number.maximum() ? null : value;
        }
        int longest = setting == Settings.REELS_BLOCKED_WORDS || setting == Settings.HIDDEN_SETTINGS_ROWS
                || setting == Settings.MUTED_NOTIFICATION_CHANNELS ? MAX_WORDS_TEXT : MAX_TEXT;
        if (!(value instanceof String) || ((String) value).length() > longest) return null;
        if (fallback instanceof Enum) {
            if (!"enum".equals(declared)) return null;
            for (Object choice : ((Enum<?>) fallback).getDeclaringClass().getEnumConstants()) {
                if (((Enum<?>) choice).name().equals(value)) return choice;
            }
            return null;
        }
        if (fallback instanceof String) return "string".equals(declared) && textAllowed(setting, (String) value) ? value : null;
        return null;
    }

    /**
     * The slider and its value for an entry under the key a glass step had before its slider, from a file
     * an older build exported, or null for any other entry or a step this build never had (#391).
     */
    @Nullable
    static Map.Entry<Setting<?>, Object> oldGlassStep(String key, String declared, Object value,
                                                       Map<String, Setting<?>> allowed) {
        if (!"enum".equals(declared) || !(value instanceof String)) return null;
        IntegerSetting slider = null;
        Integer drawn = null;
        if (Settings.OLD_GLASS_BLUR_KEY.equals(key)) {
            slider = Settings.GLASS_TAB_BAR_BLUR_RADIUS;
            drawn = GlassBlur.dpOf((String) value);
        } else if (Settings.OLD_GLASS_OPACITY_KEY.equals(key)) {
            slider = Settings.GLASS_TAB_BAR_OPACITY;
            drawn = GlassOpacity.percentOf((String) value);
        }
        if (slider == null || drawn == null || allowed.get(slider.key) != slider) return null;
        return new AbstractMap.SimpleImmutableEntry<>(slider, drawn);
    }

    /** Whether [text] is a list of blocked words its row could have saved: no control characters but line breaks. */
    static boolean blockedWordsAllowed(String text) {
        for (int at = 0; at < text.length(); at++) {
            char character = text.charAt(at);
            if (Character.isISOControl(character) && character != '\n' && character != '\r') return false;
        }
        return true;
    }

    /**
     * Whether [text] may be saved as [setting], by the same rule that setting's own reader or row
     * uses. A text setting with no check here isn't taken from a file at all.
     */
    static boolean textAllowed(Setting<?> setting, String text) {
        if (setting == Settings.SAVE_FOLDER) return SaveFolder.isImportable(text);
        if (setting == Settings.FILENAME_TEMPLATE) return FileNameTemplate.isImportable(text);
        if (setting == Settings.SHARING_DOMAIN) return SharingDomain.normalize(text) != null;
        // The Place row keeps what was typed, spaces too, and takes blank as no place.
        if (setting == Settings.SPOOF_LOCATION_PLACE) return text.trim().isEmpty() || SpoofLocation.parse(text) != null;
        if (setting == Settings.TAB_ORDER) return text.equals(TabOrder.join(TabOrder.parse(text)));
        // The share sheet buttons to leave out, as Instagram's names for them with commas between.
        if (setting == Settings.HIDDEN_SHARE_SHEET_BUTTONS) {
            return text.equals(ShareSheetActions.join(ShareSheetActions.hidden(text)));
        }
        // The message box buttons left off, as the names of Instagram's enum with commas between, in the order the dialog lists them.
        if (setting == Settings.HIDDEN_MESSAGE_BOX_BUTTONS) return text.equals(MessageBox.join(MessageBox.parse(text)));
        // The rows of Instagram's settings left off, as the names of Instagram's row ids with commas between, in name order.
        if (setting == Settings.HIDDEN_SETTINGS_ROWS) return text.equals(SettingsRows.join(SettingsRows.parse(text)));
        // The notification channels muted, as Instagram's channel ids with commas between, in id order.
        if (setting == Settings.MUTED_NOTIFICATION_CHANNELS) {
            return text.equals(NotificationGroups.joinChannels(NotificationGroups.mutedChannels(text)));
        }
        // The profile tabs left off, as Instagram's keys with commas between, in the order the dialog lists them.
        if (setting == Settings.HIDDEN_PROFILE_TABS) return text.equals(ProfileTabs.join(ProfileTabs.parse(text)));
        // The same for your own profile, or the mark that it still follows other profiles.
        if (setting == Settings.HIDDEN_OWN_PROFILE_TABS) {
            return text.equals(ProfileTabs.SAME_AS_OTHERS) || text.equals(ProfileTabs.join(ProfileTabs.parse(text)));
        }
        // The words Hide reels by caption words looks for: any text without control characters but line breaks.
        if (setting == Settings.REELS_BLOCKED_WORDS) return blockedWordsAllowed(text);
        // An enum constant name of Instagram's; a name this Instagram lacks keeps its heart.
        if (setting == Settings.LIKE_ANIMATION) return text.matches("[A-Za-z0-9_$]{0,64}");
        // The ghost switches Ghost mode turns, as their keys with commas between; blank means all of them.
        if (setting == Settings.GHOST_MODE_PICKS) return text.matches("[a-z0-9_,]{0,2048}");
        return false;
    }

    /**
     * With a HushGram PIN behind the locks, a file (or an Undo) can't switch Lock your messages or
     * Lock all of Instagram, so a backup can't be a way round the PIN. With the phone's lock behind
     * them, it can't while they're locked either: settings left open from before the lock came back
     * ask for a new unlock first, and this holds at the write itself.
     */
    private static boolean heldByLock(Setting<?> setting) {
        return (setting == Settings.LOCK_MESSAGES || setting == Settings.LOCK_APP)
                && (app.hushgram.extension.instagram.direct.PinLock.protects()
                        || app.hushgram.extension.instagram.direct.MessagesLock.locked());
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
        return undo(token, () -> true);
    }

    /** A refused page keeps its still-valid Undo token and deadline. */
    public static synchronized Result undo(long token, java.util.function.BooleanSupplier mayCommit) throws IOException {
        if (undoDeadline(token) == 0) return null;
        synchronized (Setting.class) {
            requirePermission(mayCommit);
            Map<Setting<?>, Object> values = undoValues;
            Map<Setting<?>, Object> expected = importedValues;
            Map<Setting<?>, Long> revisions = importedRevisions;
            forgetUndo();
            Map<Setting<?>, Object> before = new LinkedHashMap<>();
            boolean restart = false;
            int skipped = 0;
            int held = 0;
            for (var entries = values.entrySet().iterator(); entries.hasNext();) {
                var entry = entries.next();
                Setting<?> setting = entry.getKey();
                if (heldByLock(setting)) {
                    entries.remove();
                    if (!entry.getValue().equals(setting.savedValue())) held++;
                } else if (revisions.get(setting) != setting.savedRevision()
                        || !expected.get(setting).equals(setting.savedValue())) {
                    entries.remove();
                    skipped++;
                } else if (!entry.getValue().equals(setting.savedValue())) {
                    before.put(setting, setting.savedValue());
                    restart |= setting.rebootApp;
                }
            }
            for (var changed : before.entrySet()) AbstractPreferenceFragment.noteRunningValue(changed.getKey(), changed.getValue());
            Setting.saveAll(values);
            return new Result(values.size(), skipped, held, restart, before);
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
        if (value instanceof Integer) return "integer";
        if (value instanceof Enum) return "enum";
        if (value instanceof String) return "string";
        throw invalid();
    }

    private static IOException invalid() {
        // Parser causes can carry input values. Keep untrusted content out of diagnostic logs.
        return new IOException("Invalid HushGram configuration file");
    }

    private static void requirePermission(java.util.function.BooleanSupplier mayCommit) throws Held {
        try {
            if (mayCommit != null && mayCommit.getAsBoolean()) return;
        } catch (RuntimeException unavailable) {
            // Unreadable authorization must never grant a queued write.
        }
        throw new Held();
    }
}
