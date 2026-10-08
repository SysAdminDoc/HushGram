/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * One switch over the switches that keep what you do on Instagram to yourself. It keeps no value of
 * its own: it's on while every one of them is on, and flipping it saves the same value to each.
 * Pause still wins, since each switch reads as off while HushGram is paused.
 */
final class GhostMode {
    private GhostMode() { }

    /** The ghost switches in the order their families list, each one only when its patch is in the build. */
    static List<BooleanSetting> switches(Set<PatchFamily> build) {
        if (build == null) return Collections.emptyList();
        List<BooleanSetting> switches = new ArrayList<>();
        if (build.contains(PatchFamily.STORY_SEEN)) switches.add(Settings.VIEW_STORIES_ANONYMOUSLY);
        if (build.contains(PatchFamily.LIVE_SEEN)) switches.add(Settings.VIEW_LIVE_ANONYMOUSLY);
        if (build.contains(PatchFamily.THREAD_SEEN)) switches.add(Settings.READ_WITHOUT_SEEN_RECEIPT);
        if (build.contains(PatchFamily.DM_MEDIA_SEEN)) switches.add(Settings.VIEW_DM_MEDIA_ANONYMOUSLY);
        if (build.contains(PatchFamily.TYPING)) switches.add(Settings.HIDE_TYPING);
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

    /** Whether every one of [switches] is saved as [value]. */
    static boolean all(List<BooleanSetting> switches, boolean value) {
        for (BooleanSetting setting : switches) {
            if (setting.savedValue() != value) return false;
        }
        return true;
    }
}
