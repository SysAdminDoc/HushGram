/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Send downloads to another app: with the switch on, Download on a reel, a feed post or a story
 * hands the item's link to an app the person picks, a downloader such as Seal or YTDLnis, instead
 * of saving it here.
 *
 * <p>The link is built from what the Media already holds. A post's and a reel's short code is its
 * pk written in base 64 with Instagram's alphabet, and a story's link is its poster's name and its
 * pk. An item whose link can't be built is saved here as before, so a tap is never lost.
 */
public final class ExternalDownload {
    private ExternalDownload() {
    }

    /** The source the hand off's lines carry in the diagnostic report. */
    private static final String SOURCE = "ExternalDownload";

    /** What a hand off counts in the diagnostic report, under the surface's family. */
    static final String SENT = "sent to another app";

    /** The digits of a short code, 0 to 63. */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static final String SITE = "https://www.instagram.com/";

    /** Whether Download hands the link off. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && Settings.SEND_DOWNLOADS_TO_APP.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The short code of the post with [mediaId], {@code <media pk>_<owner's pk>} or the pk alone, or
     * null when it doesn't start with a positive number.
     */
    static String shortcode(String mediaId) {
        long pk = pk(mediaId);
        if (pk <= 0) return null;
        StringBuilder code = new StringBuilder();
        while (pk > 0) {
            code.append(ALPHABET.charAt((int) (pk & 63)));
            pk >>>= 6;
        }
        return code.reverse().toString();
    }

    /** The media pk at the start of [mediaId], or 0 when there's none. */
    static long pk(String mediaId) {
        if (mediaId == null) return 0;
        int end = mediaId.indexOf('_');
        String digits = end < 0 ? mediaId : mediaId.substring(0, end);
        if (digits.isEmpty() || digits.length() > 19) return 0;
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return 0;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException overflow) {
            return 0;
        }
    }

    /** The link of [media], a post, or a reel when [reel], or null when its id doesn't say. */
    static String postLink(Object media, boolean reel) {
        String code = shortcode(InstagramMedia.mediaId(media));
        return code == null ? null : SITE + (reel ? "reel/" : "p/") + code + "/";
    }

    /** The link of [media], a story, or null when its id or its poster's name doesn't say. */
    static String storyLink(Object media) {
        long pk = pk(InstagramMedia.mediaId(media));
        Object user = InstagramMedia.owner(media);
        String name = user == null ? null : InstagramMedia.username(user);
        if (pk <= 0 || name == null || !name.matches("[A-Za-z0-9._]{1,30}")) return null;
        return SITE + "stories/" + name + "/" + pk + "/";
    }

    /**
     * Hands [link] to an app the person picks from Android's share sheet when the switch is on, and
     * answers whether it did. Off, with no link, or when the sheet can't open, it answers false and
     * the caller saves here. [family] is the surface's, for the report. Never throws.
     */
    static boolean handOff(Context context, String link, String family) {
        if (context == null || !on()) return false;
        if (link == null) {
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "no link to send, so it saves here");
            return false;
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, link);
            Intent chooser = Intent.createChooser(send, L10n.t(context, "Send to"));
            if (!(context instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            HookStatus.counted(family, SENT);
            return true;
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the share sheet didn't open, so it saves here", t);
            return false;
        }
    }
}
