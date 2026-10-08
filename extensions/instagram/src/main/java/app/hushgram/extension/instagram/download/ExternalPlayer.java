/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Open in another player: with the switch on, a reel's menu and a feed video's menu get a row that
 * hands the video to a player the person picks, such as VLC, instead of saving it.
 *
 * <p>The player gets the address a save would fetch: the single video file the Media lists that
 * suits the Download quality, on Meta's media servers. A video Instagram lists only as a DASH
 * manifest has no such file, so it gets no row. HushGram opens nothing itself; the player fetches
 * the video from that address.
 */
public final class ExternalPlayer {
    private ExternalPlayer() {
    }

    /** The source the hand off's lines carry in the diagnostic report. */
    private static final String SOURCE = "ExternalPlayer";

    /** The name of the menu option the row is made with, beside Download's in both menus. */
    static final String OPTION = "HUSHGRAM_OPEN_PLAYER";

    /** What the hand off counts in the diagnostic report, under the surface's family. */
    static final String OPENED = "opened in another player";
    static final String NO_FILE = "no file for another player";
    static final String NO_PLAYER = "no player";

    /** The type a player is asked to open. Meta's single video files are MP4. */
    static final String TYPE = "video/mp4";

    /** Whether the menus offer the row. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && Settings.OPEN_IN_PLAYER.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The address of [media]'s video a player can open: of the single files it lists on Meta's
     * media servers, the one a save at the Download quality picks. Null when it lists none, a
     * video with only a manifest among them. Never throws.
     */
    static String address(Object media) {
        try {
            if (media == null) return null;
            List<MediaSave.Rendition> files = new ArrayList<>();
            for (MediaSave.Rendition file : ReelDownload.renditions(media)) {
                if (RenditionPicker.isHttpUrl(file.url) && MediaUrlPolicy.shapeRefusal(file.url) == null) files.add(file);
            }
            MediaSave.Rendition picked = RenditionPicker.pickVideo(files, MediaSave.quality());
            return picked == null ? null : picked.url;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether [media] has a video a player can open, so its menu gets the row. Never throws. */
    static boolean offers(Object media) {
        return on() && address(media) != null;
    }

    /** The view of the video at [address] a player is asked to open. */
    static Intent view(String address) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(address), TYPE);
    }

    /**
     * Whether an app on the phone takes [view]. Android's chooser always opens, empty when nothing
     * does, so this is asked first. From Android 11 the answer covers only the apps Instagram may
     * see, which the patch widens to every app that views a video at a web address. A lookup that
     * fails leaves it to the chooser.
     */
    static boolean playable(Context context, Intent view) {
        try {
            PackageManager packages = context.getPackageManager();
            return packages == null || !packages.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
        } catch (RuntimeException lookupFailed) {
            return true;
        }
    }

    /** The chooser that asks for a player for [view], started from [context]. */
    static Intent chooser(Context context, Intent view) {
        Intent chooser = Intent.createChooser(view, L10n.t(context, "Open with"));
        if (!(context instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return chooser;
    }

    /**
     * Opens Android's chooser of players on [media]'s video, from [context], and answers whether it
     * opened. With no file to hand over, or no app on the phone that plays it, it says so in a toast.
     * [family] is the menu's, for the report. Never throws.
     */
    static boolean open(Context context, Object media, String family) {
        Context application = applicationOf(context);
        try {
            String address = address(media);
            if (address == null) {
                HookStatus.counted(family, NO_FILE);
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "no single video file to hand a player");
                say(application, L10n.t(application, "This video has no file another player can open"));
                return false;
            }
            Intent view = view(address);
            if (!playable(context, view)) return noPlayer(application, family);
            context.startActivity(chooser(context, view));
            HookStatus.counted(family, OPENED);
            return true;
        } catch (ActivityNotFoundException none) {
            // A phone without Android's chooser.
            return noPlayer(application, family);
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the chooser of players didn't open", t);
            say(application, L10n.t(application, "Couldn't open another player"));
            return false;
        }
    }

    private static boolean noPlayer(Context application, String family) {
        HookStatus.counted(family, NO_PLAYER);
        say(application, L10n.t(application, "No app on this phone can play this video"));
        return false;
    }

    private static Context applicationOf(Context context) {
        if (context == null) return Utils.getContext();
        Context application = context.getApplicationContext();
        return application != null ? application : context;
    }

    private static void say(Context context, String text) {
        if (context != null) Feedback.show(context, text, true);
    }
}
