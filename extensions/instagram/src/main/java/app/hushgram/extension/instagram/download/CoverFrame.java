/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A reel's cover at the size of its video (#214). Instagram's payload lists a reel's cover at 360
 * or 480 wide now, while the video is often 1080 wide. When the cover is the reel's first frame,
 * which a first frame still sharing the cover's file says ({@link StoryDownload.Cover#firstFrame}),
 * that frame is taken from the largest video the reel lists: fetched through the same checks as a
 * video save, read with MediaMetadataRetriever and saved as a JPEG. Nothing is asked of Instagram
 * beyond the video file a video save fetches too. A custom cover is another picture, so it never
 * goes this way.
 */
final class CoverFrame {
    private CoverFrame() {
    }

    /** The source this save's lines carry in the diagnostic report. */
    private static final String SOURCE = "CoverFrame";

    /** Counted under Download any reel: a cover taken from its video, or one that fell back to its still. */
    static final String FROM_VIDEO = "cover taken from the video";
    static final String FRAME_FAILED = "cover frame failed";

    /** The JPEG quality the frame is saved at, about what Instagram's own covers use. */
    static final int JPEG_QUALITY = 92;

    /** Reads a video file's first frame in place of MediaMetadataRetriever. Never set on a phone. */
    static volatile Function<File, Bitmap> firstFrameForTests;

    /**
     * The video to take the picture of [still] from: of [videos] and the video tracks in [manifest]
     * the phone decodes, the one with the most pixels, and of two that size the smaller file, when
     * it has more pixels than [still]. Null when none has, or [still]'s size is unknown. Only addresses
     * on Meta's media servers count.
     */
    static MediaSave.Rendition source(List<MediaSave.Rendition> videos, String manifest, MediaSave.Rendition still) {
        MediaSave.Rendition best = null;
        if (videos != null) {
            for (MediaSave.Rendition video : videos) best = larger(video, best);
        }
        if (manifest != null) {
            for (DashManifest.Track track : DashManifest.parse(manifest)) {
                if (!track.isVideo() || !decodes(track.codecs)) continue;
                // Never save HDR video (#352) keeps a frame of an HDR picture out of the gallery too.
                if (track.hdr && MediaSave.skipsHdr()) continue;
                best = larger(new MediaSave.Rendition(track.url, track.width, track.height, track.bandwidth), best);
            }
        }
        return best != null && pixels(still) > 0 && pixels(best) > pixels(still) ? best : null;
    }

    private static MediaSave.Rendition larger(MediaSave.Rendition video, MediaSave.Rendition best) {
        if (video == null || !RenditionPicker.isHttpUrl(video.url) || MediaUrlPolicy.shapeRefusal(video.url) != null) {
            return best;
        }
        if (best == null || pixels(video) > pixels(best)) return video;
        boolean smaller = video.bitrate > 0 && (best.bitrate == 0 || video.bitrate < best.bitrate);
        return pixels(video) == pixels(best) && smaller ? video : best;
    }

    /**
     * Whether the phone decodes a picture of [codecs]: H.264 and VP9 every phone does, AV1 one with
     * a decoder, and not with Never save AV1 video on (#379), which is for a phone whose decoder is
     * there but plays AV1 badly, a frame of it included.
     */
    private static boolean decodes(String codecs) {
        return codecs.startsWith("avc1") || codecs.startsWith("vp09")
            || (codecs.startsWith("av01") && DashSave.canWriteAv1() && !MediaSave.skipsAv1());
    }

    private static long pixels(MediaSave.Rendition rendition) {
        return (long) rendition.width * rendition.height;
    }

    /**
     * Fetches [video], takes its first frame and writes it to [sink] as a JPEG, telling [progress]
     * how far the fetch has got and stopping when it's cancelled. The fetch goes through [policy]
     * and is held to [maxBytes]. Blocks, and never throws.
     */
    static Downloader.Result save(Context application, MediaSave.Rendition video, Downloader.Sink sink,
            MediaUrlPolicy policy, long maxBytes, Downloader.Progress progress) {
        File videoFile = null;
        File picture = null;
        try {
            File folder = DashSave.workFolder(application);
            if (folder == null) return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "no cache folder");

            videoFile = File.createTempFile("frame", ".mp4", folder);
            Downloader.Result result = DashSave.fetchWork(video.url, Downloader.Kind.VIDEO, videoFile, policy, maxBytes,
                progress);
            if (!result.ok()) return result;
            if (progress.cancelled()) return cancelled();

            Bitmap frame = firstFrame(videoFile);
            if (frame == null) {
                if (progress.cancelled()) return cancelled();
                return Downloader.Result.fail(Downloader.Status.BUILD_ERROR, "the video gave no first frame");
            }
            videoFile = DashSave.discard(videoFile);
            final int width = frame.getWidth();
            final int height = frame.getHeight();
            try {
                picture = File.createTempFile("cover", ".jpg", folder);
                try (OutputStream out = new FileOutputStream(picture)) {
                    if (!frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        return Downloader.Result.fail(Downloader.Status.BUILD_ERROR, "the first frame could not be written");
                    }
                }
            } finally {
                frame.recycle();
            }

            Downloader.Result published = Downloader.publish(picture, "image/jpeg", sink, progress);
            if (published.ok()) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, FROM_VIDEO);
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "the cover was taken from the video's first frame at " + width + "x" + height);
            }
            return published;
        } catch (Throwable t) {
            if (progress.cancelled()) return cancelled();
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the cover could not be taken from the video", t);
            return Downloader.Result.fail(Downloader.Status.BUILD_ERROR, "the cover could not be taken from the video");
        } finally {
            DashSave.discard(videoFile);
            DashSave.discard(picture);
        }
    }

    /** The first frame of the video in [file], upright, or null when it gives none. */
    private static Bitmap firstFrame(File file) throws IOException {
        Function<File, Bitmap> forTests = firstFrameForTests;
        if (forTests != null) return forTests.apply(file);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } finally {
            retriever.release();
        }
    }

    private static Downloader.Result cancelled() {
        return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled while taking the cover from the video");
    }
}
