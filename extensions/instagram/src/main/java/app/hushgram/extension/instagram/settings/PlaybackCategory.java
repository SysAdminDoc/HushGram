/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.playbackQualityRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.qualityTabRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.reelTapChoiceRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.tapToPlayScopeRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.Row;
import app.hushgram.extension.shared.L10n;

/** Builds the Playback category of HushGram settings. */
final class PlaybackCategory {
    private PlaybackCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.HDR_BOOST) || build.contains(PatchFamily.TAP_TO_PLAY)
                || build.contains(PatchFamily.RESUME_LONG_VIDEOS) || build.contains(PatchFamily.REEL_TAP_AND_VOLUME)
                || build.contains(PatchFamily.REEL_SOUND)
                || build.contains(PatchFamily.PLAYBACK_QUALITY) || build.contains(PatchFamily.DATA_SAVER)
                || build.contains(PatchFamily.STOP_LOOPING)) {
            PreferenceCategory playback = category(screen, L10n.t("Playback"), SettingsIcons.PLAYBACK,
                    L10n.t("Media quality, auto-play and controls."));
            if (build.contains(PatchFamily.TAP_TO_PLAY)) {
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY, L10n.t("Tap to play"),
                        L10n.t("Videos wait for your tap where the choice below says. Feed videos show a play button, "
                                + "as they do when you use less mobile data.")));
                playback.addPreference(tapToPlayScopeRow(context));
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY_AUTO_SCROLL_WAITS,
                        L10n.t("Wait after auto scroll"),
                        L10n.t("A reel Instagram's auto scroll moves to waits for your tap too. "
                                + "Off, auto scroll plays reel after reel.")));
            }
            if (build.contains(PatchFamily.REEL_TAP_AND_VOLUME)) {
                playback.addPreference(reelTapChoiceRow(context));
                playback.addPreference(toggle(context, Settings.KEEP_REELS_MUTED, L10n.t("Keep reels muted on the volume keys"),
                        L10n.t("Volume up still raises the phone's volume, but a muted reel stays muted until you unmute it.")));
            }
            if (build.contains(PatchFamily.REEL_SOUND)) {
                playback.addPreference(toggle(context, Settings.START_REELS_WITH_SOUND, L10n.t("Start reels with sound"),
                        L10n.t("A reel you tap open from a post on Home plays with sound, and Home is muted again when "
                                + "you go back. Your phone's ringer and volume still count.")));
                playback.addPreference(toggle(context, Settings.START_REELS_MUTED, L10n.t("Start reels muted"),
                        L10n.t("Every reel starts silent, wherever you open it from. The volume keys or the speaker "
                                + "icon unmute it. Wins over Start reels with sound.")));
            }
            if (build.contains(PatchFamily.STOP_LOOPING)) {
                playback.addPreference(toggle(context, Settings.STOP_VIDEO_LOOPING, L10n.t("Stop videos looping"),
                        L10n.t("Feed videos and reels stop at their last frame instead of playing again. Reels still "
                                + "loop while auto scroll is on, so it can move on.")));
            }
            if (build.contains(PatchFamily.RESUME_LONG_VIDEOS)) {
                playback.addPreference(toggle(context, Settings.RESUME_LONG_VIDEOS, L10n.t("Resume long videos"),
                        L10n.t("Videos and reels over two minutes pick up where you left off. Seek to start elsewhere. "
                                + "Live videos and ads start as usual.")));
                page.clearPositions = new Row(context);
                page.clearPositions.setKey("hushgram_clear_resume_points");
                page.clearPositions.setPersistent(false);
                page.clearPositions.actsAtOnce = true;
                playback.addPreference(page.clearPositions);
                page.showClearPositions();
            }
            if (build.contains(PatchFamily.PLAYBACK_QUALITY)) {
                playback.addPreference(toggle(context, Settings.DEFAULT_PLAYBACK_QUALITY, L10n.t("Default playback quality"),
                        L10n.t("Videos, reels and stories play at the quality below, starting with the next one you open.")));
                playback.addPreference(playbackQualityRow(context));
                playback.addPreference(qualityTabRow(context));
            }
            if (build.contains(PatchFamily.DATA_SAVER)) {
                playback.addPreference(toggle(context, Settings.DATA_SAVER, L10n.t("Data saver"),
                        L10n.t("Loads smaller photos and starts videos, reels and stories at the lowest quality. "
                                + "Grid thumbnails stay as they are. It takes priority over Full resolution photos "
                                + "and the quality above.")));
                playback.addPreference(toggle(context, Settings.DATA_SAVER_MOBILE_DATA_ONLY,
                        L10n.t("Only on mobile data"),
                        L10n.t("Wi-Fi stays as it is. Turn this off to save data on every network.")));
            }
            if (build.contains(PatchFamily.HDR_BOOST)) {
                playback.addPreference(toggle(context, Settings.TURN_OFF_HDR_BOOSTS, L10n.t("Turn off HDR brightness boosts"),
                        L10n.t("HDR photos and reels stop brightening the screen above everything else. They show "
                                + "at the same brightness as the rest of Instagram.")));
            }
        }
    }
}
