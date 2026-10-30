/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * One switch over the switches that keep what you do on Instagram to yourself. It keeps no value of
 * its own: it's on while every one of them it turns is on, and flipping it saves the same value to
 * each. Which ones it turns is your pick ({@link #picked}, #251), all of them to start. Pause still
 * wins, since each switch reads as off while HushGram is paused.
 */
final class GhostMode {
    private GhostMode() { }

    /**
     * The ones of [switches] Ghost mode turns: those {@link Settings#GHOST_MODE_PICKS} names, or all
     * of them when it names none of them.
     */
    static List<BooleanSetting> picked(List<BooleanSetting> switches) {
        Set<String> keys = pickedKeys();
        if (keys.isEmpty()) return switches;
        List<BooleanSetting> picked = new ArrayList<>();
        for (BooleanSetting setting : switches) {
            if (keys.contains(setting.key)) picked.add(setting);
        }
        return picked.isEmpty() ? switches : picked;
    }

    /** The keys {@link Settings#GHOST_MODE_PICKS} holds, as saved. */
    static Set<String> pickedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        String saved = Settings.GHOST_MODE_PICKS.savedValue();
        if (saved == null) return keys;
        for (String key : saved.split(",")) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) keys.add(trimmed);
        }
        return keys;
    }

    /**
     * Saves the ones of [switches] whose keys are in [keys] as what Ghost mode turns. All of them saves
     * nothing, the same as all, so a ghost patch added to the build later is turned too.
     */
    static void pick(List<BooleanSetting> switches, Set<String> keys) {
        StringBuilder saved = new StringBuilder();
        boolean every = true;
        for (BooleanSetting setting : switches) {
            if (!keys.contains(setting.key)) {
                every = false;
                continue;
            }
            if (saved.length() > 0) saved.append(',');
            saved.append(setting.key);
        }
        Settings.GHOST_MODE_PICKS.save(every ? "" : saved.toString());
    }

    /** The ghost switches in the order their families list, each one only when its patch is in the build. */
    static List<BooleanSetting> switches(Set<PatchFamily> build) {
        if (build == null) return Collections.emptyList();
        List<BooleanSetting> switches = new ArrayList<>();
        if (build.contains(PatchFamily.STORY_SEEN)) switches.add(Settings.VIEW_STORIES_ANONYMOUSLY);
        if (build.contains(PatchFamily.LIVE_SEEN)) switches.add(Settings.VIEW_LIVE_ANONYMOUSLY);
        if (build.contains(PatchFamily.THREAD_SEEN)) switches.add(Settings.READ_WITHOUT_SEEN_RECEIPT);
        if (build.contains(PatchFamily.DM_MEDIA_SEEN)) switches.add(Settings.VIEW_DM_MEDIA_ANONYMOUSLY);
        if (build.contains(PatchFamily.TYPING)) switches.add(Settings.HIDE_TYPING);
        if (build.contains(PatchFamily.ACTIVE_STATUS)) switches.add(Settings.HIDE_ACTIVE_STATUS);
        if (build.contains(PatchFamily.SCREENSHOT_REPORTS)) switches.add(Settings.HIDE_SCREENSHOTS);
        return switches;
    }

    /** Whether the master switch shows: only when it would turn at least two switches. */
    static boolean offered(List<BooleanSetting> switches) {
        return switches.size() >= 2;
    }

    /** On while every ghost switch is saved on. */
    static boolean on(List<BooleanSetting> switches) {
        return !switches.isEmpty() && all(switches, true);
    }

    /**
     * Saves the opposite of what the switches show now to each of them, so a build that has them all
     * on turns them all off and any other turns them all on, then toasts which way it went. This is
     * the flip for a place with no settings page open, so the failure toast names HushGram settings.
     * Returns the value it saved.
     */
    static boolean flip(List<BooleanSetting> switches) {
        boolean on = !on(switches);
        for (BooleanSetting setting : switches) setting.save(on);
        Utils.showToastShort(message(switches, on,
                L10n.t("Couldn't change every Ghost mode switch. Open HushGram settings to check them.")));
        return on;
    }

    /** What to tell the person after [switches] were saved as [on]: which way they went, or [failure]. */
    static String message(List<BooleanSetting> switches, boolean on, String failure) {
        return all(switches, on)
                ? on ? L10n.t("Ghost mode is on, and so is each of its switches.")
                        : L10n.t("Ghost mode is off, and so is each of its switches.")
                : failure;
    }

    /** Whether every one of [switches] is saved as [value]. */
    static boolean all(List<BooleanSetting> switches, boolean value) {
        for (BooleanSetting setting : switches) {
            if (setting.savedValue() != value) return false;
        }
        return true;
    }
}
