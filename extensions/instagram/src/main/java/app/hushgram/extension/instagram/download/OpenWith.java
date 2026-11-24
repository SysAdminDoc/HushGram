/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Open with and Read text with Lens, under the facts in Details (#839).
 *
 * <p>Open with hands the post's photo, or its video, to another app on the phone through Android's
 * chooser. Read text with Lens hands the photo, or a video's cover, to Google Lens, which reads the
 * words in it, or to the share sheet when Lens isn't there. HushGram reads no text itself.
 *
 * <p>Each fetches the one file Copy media link copies, the media address Download would fetch, on a
 * worker, through the same address check and size limit a save goes through. It lands in Instagram's
 * cache and goes out through Instagram's own FileProvider, as Copy text's picture does
 * ({@link TextGrab}), so nothing lands in the gallery and no permission is asked. Only the last file
 * of each is kept: the next one deletes it. Nothing here throws into Instagram.
 */
final class OpenWith {
    /** The folder under Instagram's cache Open with's file goes in. Lens's goes in {@link TextGrab#FOLDER}. */
    static final String FOLDER = "hushgram-open";
    static final String COUNT_OPENED = "details file offered to another app";
    static final String COUNT_TO_LENS = "details picture sent to Lens";
    static final String COUNT_NOT_FETCHED = "details file not fetched";
    static final String COUNT_NO_APP = "details file found no app to open it";
    /** The type a video is offered as when the server didn't say. */
    static final String VIDEO_TYPE = "video/mp4";

    private OpenWith() {
    }

    /** How the file is fetched. Tests stand in, since they have no network. */
    interface Fetcher {
        /**
         * Fetches [url], a [kind], into [into], replacing what it held. Answers the type fetched, or null
         * when it wasn't. Blocks on the network: called on a worker.
         */
        @Nullable
        String fetch(Context application, String url, Downloader.Kind kind, File into);
    }

    static volatile Fetcher fetcher = OpenWith::fetchFile;

    /**
     * Open with: fetches [file], a video when [video], and offers it to the apps that open it, through
     * the chooser, from [activity]. Says when it can't. Main thread. Never throws.
     */
    static void open(Activity activity, MediaSave.Rendition file, boolean video) {
        fetchThen(activity, file, video ? Downloader.Kind.VIDEO : Downloader.Kind.IMAGE, FOLDER, "open with",
                (fetched, type) -> offer(activity, fetched, type));
    }

    /**
     * Read text with Lens: fetches [picture], the photo or a video's cover, and sends it to Google
     * Lens from [activity], or to the share sheet when Lens isn't there. Main thread. Never throws.
     */
    static void lens(Activity activity, MediaSave.Rendition picture) {
        fetchThen(activity, picture, Downloader.Kind.IMAGE, TextGrab.FOLDER, "read text with lens", (fetched, type) -> {
            HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, COUNT_TO_LENS);
            TextGrab.sendPicture(activity, fetched, type, FamilyNames.VIDEO_DOWNLOAD);
        });
    }

    /** What to do on the main thread with the file fetched and its type. */
    private interface Then {
        void accept(File fetched, String type);
    }

    /**
     * Says the file is on its way, fetches [file] as [kind] into a new file in [folder] under the
     * cache on a worker, the one there from last time deleted, then runs [then] on the main thread
     * while [activity] is still open. A fetch that fails says so.
     */
    private static void fetchThen(Activity activity, MediaSave.Rendition file, Downloader.Kind kind, String folder,
            String what, Then then) {
        try {
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            Context application = activity.getApplicationContext() != null ? activity.getApplicationContext() : activity;
            Utils.showToastShort(L10n.t(activity, "Getting the file ready"));
            boolean queued = Utils.runOnBackgroundThread(() -> {
                File fetched = null;
                String type = null;
                try {
                    synchronized (OpenWith.class) {
                        File into = emptied(application, folder);
                        if (into != null) {
                            File part = new File(into, "post-" + System.currentTimeMillis());
                            type = fetcher.fetch(application, file.url, kind, part);
                            fetched = type == null ? null : named(part, type);
                        }
                    }
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, what, failure);
                }
                File ready = fetched;
                String readyType = type;
                Logger.printDebug(() -> what + ": " + (ready == null ? "the file wasn't fetched" : "fetched " + ready.getName() + " as " + readyType));
                Utils.runOnMainThread(() -> {
                    try {
                        if (ready == null) {
                            notFetched(activity);
                        } else if (activity.isFinishing() || activity.isDestroyed()) {
                            Logger.printDebug(() -> what + ": the screen closed before it was ready");
                            Downloader.delete(ready);
                        } else {
                            then.accept(ready, readyType);
                        }
                    } catch (Throwable failure) {
                        HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, what, failure);
                    }
                });
            });
            if (!queued) notFetched(activity);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, what, failure);
        }
    }

    private static void notFetched(Context context) {
        HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, COUNT_NOT_FETCHED);
        Utils.showToastShort(L10n.t(context, "Couldn't get the file"));
    }

    /** [folder] under [context]'s cache with every file in it deleted, made when it's missing, or null when it can't be. */
    @Nullable
    static File emptied(Context context, String folder) {
        File dir = new File(context.getCacheDir(), folder);
        if (!dir.mkdirs() && !dir.isDirectory()) return null;
        File[] old = dir.listFiles();
        if (old != null) {
            for (File file : old) {
                if (!file.delete()) Logger.printDebug(() -> "open with: an old file wouldn't delete");
            }
        }
        return dir;
    }

    /** [part] renamed with the extension [type] reads as, so the app it goes to knows it by name too. */
    static File named(File part, String type) {
        String extension = extension(type);
        if (extension.isEmpty()) return part;
        File named = new File(part.getParentFile(), part.getName() + extension);
        return part.renameTo(named) ? named : part;
    }

    /** The file extension for [type], or "" for one this doesn't know. */
    static String extension(String type) {
        switch (type) {
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/webp": return ".webp";
            case "image/heic": return ".heic";
            case "image/heif": return ".heif";
            case "image/gif": return ".gif";
            case "image/avif": return ".avif";
            case "video/mp4": return ".mp4";
            default: return "";
        }
    }

    /**
     * Offers [file], of [type], to the apps that view it through the chooser, or to the share sheet
     * when nothing takes a view. Says so when nothing takes either.
     */
    static void offer(Activity activity, File file, String type) {
        String provider = TextGrab.authority.of(activity);
        if (provider == null) {
            HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, COUNT_NO_APP);
            Utils.showToastShort(L10n.t(activity, "No app here can open it"));
            return;
        }
        Uri uri = new Uri.Builder().scheme("content").authority(provider)
                .appendPath(TextGrab.CACHE_ROOT).appendPath(FOLDER).appendPath(file.getName()).build();
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("", uri));
        Intent send = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("", uri));
        for (Intent target : new Intent[]{view, send}) {
            Intent chooser = Intent.createChooser(target, L10n.t(activity, "Open with"));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                TextGrab.starter.start(activity, chooser);
                HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, COUNT_OPENED);
                Logger.printDebug(() -> "open with: offered " + file.getName() + " as " + type + " through " + target.getAction());
                return;
            } catch (ActivityNotFoundException none) {
                // The share sheet, then the toast.
            }
        }
        HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, COUNT_NO_APP);
        Utils.showToastShort(L10n.t(activity, "No app here can open it"));
    }

    /**
     * Fetches [url] as a save does, through the address check and the size limit, into [into]. A
     * picture's type is read from its first bytes. Answers the type, or null when it wasn't fetched.
     * Never throws.
     */
    @Nullable
    static String fetchFile(Context application, String url, Downloader.Kind kind, File into) {
        File work = null;
        try {
            File folder = DashSave.workFolder(application);
            if (folder == null) return null;
            work = File.createTempFile("open", ".part", folder);
            Downloader.Result fetched = DashSave.fetchWork(url, kind, work, MediaSave.policyFor(application), MediaSave.cap(),
                    Downloader.SILENT);
            if (!fetched.ok()) {
                Logger.printDebug(() -> "open with: the file wasn't fetched: " + fetched.reason);
                return null;
            }
            String type = kind == Downloader.Kind.IMAGE ? PictureFetch.mime(work) : fetched.mime;
            if (type == null && kind == Downloader.Kind.VIDEO) type = VIDEO_TYPE;
            if (type == null) return null;
            Files.move(work.toPath(), into.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return type;
        } catch (Throwable failure) {
            Logger.printDebug(() -> "open with: the file wasn't fetched: " + failure);
            return null;
        } finally {
            DashSave.discard(work);
        }
    }
}
