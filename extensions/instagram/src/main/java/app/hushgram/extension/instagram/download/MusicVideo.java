/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A photo post that comes with music, saved as a video: the photo, held for as long as the post's
 * music plays, with that music as its sound (#71).
 *
 * <p>Instagram keeps no video of such a post. Its Media lists the picture's sizes and, in its music
 * metadata, the track as one file and the part of it the post plays. The save fetches the largest
 * picture and the track through the same checks as every other save, then builds an MP4 in the
 * cache. The picture is encoded as H.264 at about a frame a second, and the track's AAC frames for
 * the post's part are copied in as they are, so only the picture is encoded. MediaCodec encodes it,
 * MediaExtractor reads the track and MediaMuxer writes the file, and nothing is added to the app.
 * The file reaches the gallery the way any saved video does, and the work files go whatever
 * happens, a cancel included.
 */
final class MusicVideo {
    private MusicVideo() {
    }

    /** The source this save's lines carry in the diagnostic report. */
    private static final String SOURCE = "MusicVideo";

    /** How the build went, counted under Download any reel with these fixed labels. */
    static final String PICTURE_FETCH_FAILED = "picture fetch failed";
    static final String AUDIO_FETCH_FAILED = "audio fetch failed";
    static final String AUDIO_UNREADABLE = "audio unreadable";
    static final String ENCODER_FAILED = "encoder failed";
    static final String VIDEO_BUILT = "video built";

    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AAC = MediaFormat.MIMETYPE_AUDIO_AAC;

    /** The longest video it builds, for a track the post doesn't cut short: ten minutes. */
    static final long MAX_LENGTH_US = 10L * 60L * 1_000_000L;

    /** The sides of the largest video it builds: 1080p, the size of Instagram's own reels. */
    static final int MAX_LONG_SIDE = 1920;
    static final int MAX_SHORT_SIDE = 1080;

    /** The smallest short side it tries on an encoder that takes none of the larger ones. */
    private static final int MIN_SHORT_SIDE = 160;

    /**
     * A key frame every 30 seconds. The picture never changes, so the frames between key frames
     * come to almost nothing, and a player that starts part way decodes at most 30 of them. Key
     * frames are most of the file, so they're kept few.
     */
    private static final int KEY_FRAME_EVERY_S = 30;

    /** One wait on the encoder, and how long it may give nothing back before the build stops. */
    private static final long WAIT_US = 10_000L;
    private static final long STALL_MS = 15_000L;

    /** One AAC frame, 1,024 samples, at 48 kHz: the length of one for a track that doesn't give its rate. */
    private static final long AAC_FRAME_US = 1024L * 1_000_000L / 48_000L;

    /** The buffer one AAC frame is read into, unless the track declares a larger one. */
    private static final int SOUND_BUFFER = 64 * 1024;

    /** Room the video file keeps beside its frames and sound, for its boxes. */
    private static final long FILE_BOXES = 1024L * 1024L;

    /**
     * The post's music: the address of its track, and the part of it the post plays, from
     * [startMs] for [lengthMs], each negative when the post doesn't say.
     */
    static final class Music {
        final String url;
        final long startMs;
        final long lengthMs;

        Music(String url, long startMs, long lengthMs) {
            this.url = url;
            this.startMs = startMs;
            this.lengthMs = lengthMs;
        }

        @Override
        public String toString() {
            // Never the address: this can end up in a diagnostic line.
            return "Music(" + (url == null ? "no address" : "an address") + ", from " + startMs + " ms for "
                + lengthMs + " ms)";
        }
    }

    /**
     * The music [media] comes with, read from its music metadata or else its reel metadata, or null
     * when it has none. The answer's url is null when the track has no address a save can fetch.
     * Throws only what a bridge throws.
     */
    static Music music(Object media) {
        Object info = musicInfo(media);
        if (info == null) return null;
        Object track = InstagramMedia.musicTrack(info);
        String url = null;
        if (track != null) {
            url = address(InstagramMedia.trackUrl(track));
            if (url == null) url = address(InstagramMedia.trackFastStartUrl(track));
        }
        Object part = InstagramMedia.musicConsumption(info);
        Integer start = part == null ? null : InstagramMedia.musicStartMs(part);
        Integer length = part == null ? null : InstagramMedia.musicLengthMs(part);
        return new Music(url, start == null ? -1 : start, length == null ? -1 : length);
    }

    private static Object musicInfo(Object media) {
        Object metadata = InstagramMedia.musicMetadata(media);
        Object info = metadata == null ? null : InstagramMedia.metadataMusic(metadata);
        if (info != null) return info;
        Object clips = InstagramMedia.clipsMetadata(media);
        return clips == null ? null : InstagramMedia.clipsMusic(clips);
    }

    private static String address(String url) {
        return RenditionPicker.isHttpUrl(url) ? url : null;
    }

    /** The picture the video shows: the largest of [pictures] on Meta's media servers, or null. */
    static MediaSave.Rendition picture(List<MediaSave.Rendition> pictures) {
        List<MediaSave.Rendition> meta = new ArrayList<>();
        for (MediaSave.Rendition picture : pictures) {
            if (picture != null && MediaUrlPolicy.shapeRefusal(picture.url) == null) meta.add(picture);
        }
        return RenditionPicker.pickImage(meta);
    }

    // ---------------------------------------------------------------- the save

    /**
     * Fetches [picture] and [music]'s track, builds the video and writes it to [sink], telling
     * [progress] how far it has got and stopping when it's cancelled. Both files go through
     * [policy], and [maxBytes] holds them together, the way it holds a DASH save's two tracks, and
     * then the video. Blocks, and never throws.
     */
    static Downloader.Result save(Context application, MediaSave.Rendition picture, Music music, Downloader.Sink sink,
            MediaUrlPolicy policy, long maxBytes, Downloader.Progress progress) {
        File pictureFile = null;
        File soundFile = null;
        File video = null;
        try {
            File folder = DashSave.workFolder(application);
            if (folder == null) return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "no cache folder");

            pictureFile = File.createTempFile("picture", ".part", folder);
            Downloader.Result result = DashSave.fetchWork(picture.url, Downloader.Kind.IMAGE, pictureFile, policy,
                maxBytes, progress);
            if (!result.ok()) return failed(result, PICTURE_FETCH_FAILED);

            soundFile = File.createTempFile("music", ".mp4", folder);
            // One count for the pair: the sound's bytes go on from the picture's.
            result = DashSave.fetchWork(music.url, Downloader.Kind.AUDIO, soundFile, policy,
                maxBytes - pictureFile.length(), Downloader.after(pictureFile.length(), progress));
            if (!result.ok()) return failed(result, AUDIO_FETCH_FAILED);

            if (progress.cancelled()) return cancelled();
            progress.joining();
            video = File.createTempFile("video", ".mp4", folder);
            Downloader.Result built = build(pictureFile, soundFile, music, video, progress);
            if (!built.ok()) return built;
            // Both are in the video now. Kept, they'd sit beside it and the gallery's copy of it.
            pictureFile = DashSave.discard(pictureFile);
            soundFile = DashSave.discard(soundFile);
            if (video.length() > maxBytes) {
                return Downloader.Result.fail(Downloader.Status.TOO_LARGE,
                    "the video is " + video.length() + " bytes, more than " + maxBytes);
            }

            Downloader.Result published = Downloader.publish(video, "video/mp4", sink, progress);
            if (published.ok()) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, VIDEO_BUILT);
                String holds = DashSave.savedFormat(video);
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the saved file holds " + holds);
            }
            return published;
        } catch (Throwable t) {
            if (progress.cancelled()) return cancelled();
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the video of a photo with music failed", t);
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "the video could not be built");
        } finally {
            DashSave.discard(pictureFile);
            DashSave.discard(soundFile);
            DashSave.discard(video);
        }
    }

    /** [result] of a fetch that failed, counted under [label] unless the person saving stopped it. */
    private static Downloader.Result failed(Downloader.Result result, String label) {
        if (result.status != Downloader.Status.CANCELLED) HookStatus.counted(FamilyNames.REEL_DOWNLOAD, label);
        return result;
    }

    private static Downloader.Result cancelled() {
        return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled while building the video");
    }

    /**
     * Builds the video into [out] from the picture in [pictureFile] and the track in [soundFile].
     * A failure is counted as the music that couldn't be read or the video that couldn't be
     * encoded, whichever step it came in.
     */
    private static Downloader.Result build(File pictureFile, File soundFile, Music music, File out,
            Downloader.Progress progress) {
        String[] step = { AUDIO_UNREADABLE };
        try {
            return encode(pictureFile, soundFile, music, out, progress, step);
        } catch (Throwable t) {
            // A cancel can end the build in a failure of its own, a muxer stopped with no frame.
            if (progress.cancelled()) return cancelled();
            final String label = step[0];
            final String what = AUDIO_UNREADABLE.equals(label) ? "the music could not be read" : "the video could not be built";
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, label);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> what, t);
            return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, what);
        }
    }

    /**
     * Writes the video to [out]. The part of the track is checked and measured first, then the
     * picture is encoded for that long, and its frames go in beside the sound's in order of time.
     * [step] holds the label a throw from here counts under. Answers how it went, or throws.
     */
    private static Downloader.Result encode(File pictureFile, File soundFile, Music music, File out,
            Downloader.Progress progress, String[] step) throws IOException {
        MediaExtractor sound = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean encoding = false;
        boolean muxing = false;
        Throwable failure = null;

        try {
            sound = new MediaExtractor();
            sound.setDataSource(soundFile.getPath());
            MediaFormat soundFormat = DashSave.selectTrack(sound, "audio/");
            String soundType = soundFormat == null ? null : soundFormat.getString(MediaFormat.KEY_MIME);
            if (!AAC.equals(soundType)) {
                throw new IOException("the track holds " + (soundType == null ? "no sound" : soundType) + ", not AAC");
            }
            Part part = part(sound, soundFormat, music, progress);
            if (part == null) return cancelled();

            step[0] = ENCODER_FAILED;
            BitmapFactory.Options bounds = bounds(pictureFile);
            int[] wanted = videoSize(bounds.outWidth, bounds.outHeight, MAX_LONG_SIDE, MAX_SHORT_SIDE);
            if (wanted == null) throw new IOException("the picture has no size");
            encoder = MediaCodec.createEncoderByType(AVC);
            MediaCodecInfo.VideoCapabilities capabilities =
                encoder.getCodecInfo().getCapabilitiesForType(AVC).getVideoCapabilities();
            int[] size = fit(wanted, capabilities);
            if (size == null) throw new IOException("the encoder takes no size of " + wanted[0] + "x" + wanted[1]);
            Planes planes = planes(pictureFile, bounds, size);

            long[] times = frameTimes(part.durationUs);
            int perSecond = (int) Math.max(1L, Math.round(times.length * 1_000_000.0 / part.durationUs));
            int bitrate = capabilities.getBitrateRange().clamp(bitrate(size[0], size[1]));
            // Every frame at the full rate, twice over, with the sound and the boxes: more than the
            // file can come to, so a save never runs out of room half way.
            long room = 2L * (bitrate / 8L) * (part.durationUs / 1_000_000L + KEY_FRAME_EVERY_S)
                + soundFile.length() + FILE_BOXES;
            if (DashSave.reserve(out, room) < room) {
                return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "not enough free space to build the video");
            }

            MediaFormat format = MediaFormat.createVideoFormat(AVC, size[0], size[1]);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, perSecond);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_EVERY_S);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            encoding = true;

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Copy copy = new Copy(sound, part, Math.max(SOUND_BUFFER, DashSave.maxInputSize(soundFormat, "audio")));
            int videoTrack = -1;
            int soundTrack = -1;
            int next = 0;
            int written = 0;
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo frame = new MediaCodec.BufferInfo();
            long answered = System.currentTimeMillis();

            while (true) {
                if (progress.cancelled()) return cancelled();
                if (!inputDone) {
                    int in = encoder.dequeueInputBuffer(WAIT_US);
                    if (in >= 0) {
                        if (next < times.length) {
                            // The size the codec reads, taken before the picture's view of the same
                            // buffer, which replaces it.
                            ByteBuffer buffer = encoder.getInputBuffer(in);
                            int length = buffer != null && buffer.capacity() > 0 ? buffer.capacity()
                                : planes.width * planes.height * 3 / 2;
                            Image image = encoder.getInputImage(in);
                            if (image == null) throw new IOException("the encoder gives no picture to fill");
                            fill(image, planes);
                            encoder.queueInputBuffer(in, 0, length, times[next++], 0);
                        } else {
                            encoder.queueInputBuffer(in, 0, 0, times[times.length - 1], MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                        answered = System.currentTimeMillis();
                    }
                }

                int ready = encoder.dequeueOutputBuffer(info, WAIT_US);
                if (ready == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IOException("the encoder changed its format after it began");
                    videoTrack = muxer.addTrack(encoder.getOutputFormat());
                    soundTrack = muxer.addTrack(soundFormat);
                    muxer.start();
                    muxing = true;
                    answered = System.currentTimeMillis();
                } else if (ready >= 0) {
                    ByteBuffer data = encoder.getOutputBuffer(ready);
                    boolean setup = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!setup && info.size > 0 && data != null) {
                        if (!muxing) throw new IOException("the encoder sent a frame before its format");
                        // The sound up to this frame goes first, so the two stay in order of time.
                        if (!copy.until(info.presentationTimeUs, muxer, soundTrack, progress)) return cancelled();
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        frame.set(info.offset, info.size, info.presentationTimeUs,
                            info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
                        muxer.writeSampleData(videoTrack, data, frame);
                        written++;
                    }
                    encoder.releaseOutputBuffer(ready, false);
                    answered = System.currentTimeMillis();
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                if (System.currentTimeMillis() - answered > STALL_MS) {
                    throw new IOException("the encoder gave nothing back for " + STALL_MS + " ms");
                }
            }
            if (written == 0) throw new IOException("the encoder wrote no frame");
            if (!copy.until(Long.MAX_VALUE, muxer, soundTrack, progress)) return cancelled();
            final int frames = written;
            final int[] shown = size;
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "built " + frames + " frame(s) at "
                + shown[0] + "x" + shown[1] + " beside " + copy.copied + " AAC frame(s), " + part);
            return Downloader.Result.ok("video/mp4");
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            // Each one is released whatever the one before it did, as a DASH save's join does.
            Throwable closing = failure;
            if (encoder != null) {
                if (encoding) closing = DashSave.attempt(closing, encoder::stop);
                closing = DashSave.attempt(closing, encoder::release);
            }
            if (muxer != null) {
                if (muxing) closing = DashSave.attempt(closing, muxer::stop);
                closing = DashSave.attempt(closing, muxer::release);
            }
            if (sound != null) closing = DashSave.attempt(closing, sound::release);
            if (failure == null && closing != null) {
                throw new IOException("the video could not be finished: " + closing.getClass().getSimpleName(), closing);
            }
        }
    }

    // ---------------------------------------------------------------- the part of the track

    /**
     * The part of the track to copy, as {from, to} in microseconds of the track's own time, for a
     * post that plays [lengthMs] of it from [startMs], each negative when the post doesn't say,
     * and a track [trackUs] long, 0 or less when the file doesn't say. A start past the track's end
     * starts at its beginning, a post that gives no length runs to the end, and nothing runs past
     * the end or longer than {@link #MAX_LENGTH_US}.
     */
    static long[] window(long startMs, long lengthMs, long trackUs) {
        boolean known = trackUs > 0;
        long from = Math.max(0L, startMs) * 1000L;
        if (known && from >= trackUs) from = 0L;
        long to = lengthMs > 0 ? from + lengthMs * 1000L : known ? trackUs : from + MAX_LENGTH_US;
        if (known) to = Math.min(to, trackUs);
        return new long[] { from, Math.min(to, from + MAX_LENGTH_US) };
    }

    /**
     * The part of the track the video holds: the frames from [fromUs] on the track's clock up to
     * [toUs], which play for [durationUs] from the first one.
     */
    static final class Part {
        final long fromUs;
        final long toUs;
        final long durationUs;

        Part(long fromUs, long toUs, long durationUs) {
            this.fromUs = fromUs;
            this.toUs = toUs;
            this.durationUs = durationUs;
        }

        @Override
        public String toString() {
            return "the music from " + fromUs / 1000L + " ms for " + durationUs / 1000L + " ms";
        }
    }

    /**
     * The part of the selected track of [sound] that [music] says the post plays. A part with no
     * frame in it, as a start past the end of the file the post lists can give, is taken from the
     * top instead. Null when cancelled. Throws when the track has no frame there either.
     */
    private static Part part(MediaExtractor sound, MediaFormat format, Music music, Downloader.Progress progress)
            throws IOException {
        long trackUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : -1L;
        long frameUs = frameLength(format);
        long[] window = window(music.startMs, music.lengthMs, trackUs);
        Part part = measure(sound, window, frameUs, progress);
        if (part != null && part.durationUs == 0 && window[0] > 0) {
            part = measure(sound, window(0, music.lengthMs, trackUs), frameUs, progress);
        }
        if (part != null && part.durationUs == 0) throw new IOException("the track has no sound in the part the post plays");
        return part;
    }

    /** One AAC frame of [format]'s track: 1,024 samples at its rate. */
    private static long frameLength(MediaFormat format) {
        int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
        return rate > 0 ? 1024L * 1_000_000L / rate : AAC_FRAME_US;
    }

    /**
     * The frames of [sound]'s track in [window]: from the first at or after its start, up to its
     * end. Null when cancelled, and a part 0 long when the window holds none. A track's priming
     * frames come before 0 and are left out with everything else before the start.
     */
    private static Part measure(MediaExtractor sound, long[] window, long frameUs, Downloader.Progress progress) {
        sound.seekTo(window[0], MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long first = -1L;
        long last = -1L;
        while (sound.getSampleTrackIndex() >= 0) {
            if (progress.cancelled()) return null;
            long time = sound.getSampleTime();
            if (time >= window[1]) break;
            if (time >= window[0]) {
                if (first < 0) first = time;
                last = time;
            }
            if (!sound.advance()) break;
        }
        return first < 0 ? new Part(window[0], window[1], 0L) : new Part(first, window[1], last + frameUs - first);
    }

    /**
     * Copies the part's AAC frames, as they are, into the video's sound track, each moved back so
     * the part starts at 0 beside the picture's first frame.
     */
    private static final class Copy {
        private final MediaExtractor sound;
        private final Part part;
        private final ByteBuffer buffer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private boolean done;
        int copied;

        Copy(MediaExtractor sound, Part part, int bufferSize) {
            this.sound = sound;
            this.part = part;
            this.buffer = ByteBuffer.allocate(bufferSize);
            sound.seekTo(part.fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }

        /**
         * Writes to [track] of [muxer] every frame that starts by [videoUs] on the video's clock.
         * Answers false when [progress] was cancelled first.
         */
        boolean until(long videoUs, MediaMuxer muxer, int track, Downloader.Progress progress) {
            while (!done) {
                if (progress.cancelled()) return false;
                if (sound.getSampleTrackIndex() < 0) {
                    done = true;
                    break;
                }
                long time = sound.getSampleTime();
                if (time >= part.toUs) {
                    done = true;
                    break;
                }
                if (time >= part.fromUs) {
                    long at = time - part.fromUs;
                    if (at > videoUs) break;
                    buffer.clear();
                    int size = sound.readSampleData(buffer, 0);
                    if (size < 0) {
                        done = true;
                        break;
                    }
                    info.set(0, size, at, (sound.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    muxer.writeSampleData(track, buffer, info);
                    copied++;
                }
                if (!sound.advance()) done = true;
            }
            return true;
        }
    }

    /**
     * The times, in microseconds from 0, of the frames that hold the picture for [durationUs]:
     * about one a second and never fewer than two, evenly spaced. The muxer gives the last frame
     * the length of the one before it, so the picture lasts as long as the sound.
     */
    static long[] frameTimes(long durationUs) {
        long seconds = Math.round(durationUs / 1_000_000.0);
        int count = (int) Math.max(2L, Math.min(MAX_LENGTH_US / 1_000_000L, seconds));
        long[] times = new long[count];
        for (int i = 0; i < count; i++) times[i] = durationUs * i / count;
        return times;
    }

    // ---------------------------------------------------------------- the picture

    /**
     * The bit rate asked of the encoder for a [width] by [height] picture: a bit a pixel each
     * second, at about a frame a second. Enough for a sharp key frame of a still picture, and the
     * frames after it repeat that picture, so they need next to none.
     */
    static int bitrate(int width, int height) {
        return (int) Math.min(Integer.MAX_VALUE, (long) width * height);
    }

    /**
     * The size to encode a [width] by [height] picture at: as it is, or smaller to fit within
     * [maxLong] by [maxShort], with both sides even and its shape kept. Null for a picture with no
     * size.
     */
    static int[] videoSize(int width, int height, int maxLong, int maxShort) {
        if (width <= 0 || height <= 0) return null;
        double scale = Math.min(1.0, Math.min((double) maxLong / Math.max(width, height),
            (double) maxShort / Math.min(width, height)));
        int w = even(width * scale);
        int h = even(height * scale);
        return w < 2 || h < 2 ? null : new int[] { w, h };
    }

    private static int even(double side) {
        return (int) Math.round(side) & ~1;
    }

    /**
     * [size], or the largest size of about its shape below it that [capabilities] can encode, each
     * side a multiple of what the encoder asks for. Null when there's none down to
     * {@link #MIN_SHORT_SIDE} pixels on the short side.
     */
    static int[] fit(int[] size, MediaCodecInfo.VideoCapabilities capabilities) {
        int widthStep = step(capabilities.getWidthAlignment());
        int heightStep = step(capabilities.getHeightAlignment());
        for (double scale = 1.0; Math.min(size[0], size[1]) * scale >= MIN_SHORT_SIDE; scale *= 0.9) {
            int w = (int) (size[0] * scale) / widthStep * widthStep;
            int h = (int) (size[1] * scale) / heightStep * heightStep;
            if (w > 0 && h > 0 && capabilities.isSizeSupported(w, h)) return new int[] { w, h };
        }
        return null;
    }

    /** An alignment the encoder asks for, made even, since the picture's color is kept at half its size. */
    private static int step(int alignment) {
        int step = Math.max(1, alignment);
        return step % 2 == 0 ? step : step * 2;
    }

    /** The largest power of two a [width] by [height] picture can be decoded down by and still cover [targetWidth] by [targetHeight]. */
    static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2;
        return sample;
    }

    private static BitmapFactory.Options bounds(File picture) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        PictureFetch.decodeFile(picture, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("the picture could not be read");
        return bounds;
    }

    /** The picture in [file], [bounds] in size, at exactly [size], as the encoder takes it. */
    private static Planes planes(File file, BitmapFactory.Options bounds, int[] size) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size[0], size[1]);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = PictureFetch.decodeFile(file, options);
        if (decoded == null) throw new IOException("the picture could not be decoded");
        Bitmap scaled = decoded;
        try {
            if (decoded.getWidth() != size[0] || decoded.getHeight() != size[1]) {
                scaled = Bitmap.createScaledBitmap(decoded, size[0], size[1], true);
            }
            int[] pixels = new int[size[0] * size[1]];
            scaled.getPixels(pixels, 0, size[0], 0, 0, size[0], size[1]);
            return planes(pixels, size[0], size[1]);
        } finally {
            if (scaled != decoded) scaled.recycle();
            decoded.recycle();
        }
    }

    /** A picture as the encoder takes it: Y at full size, then Cb and Cr at half size each way. */
    static final class Planes {
        final int width;
        final int height;
        final byte[] luma;
        final byte[] blue;
        final byte[] red;

        Planes(int width, int height, byte[] luma, byte[] blue, byte[] red) {
            this.width = width;
            this.height = height;
            this.luma = luma;
            this.blue = blue;
            this.red = red;
        }
    }

    /**
     * The [width] by [height] pixels [argb], both sides even, in BT.709 video range, the colors
     * the encoder is told the picture has. Each color sample is the average of the four pixels it
     * covers.
     */
    static Planes planes(int[] argb, int width, int height) {
        byte[] luma = new byte[width * height];
        for (int i = 0; i < luma.length; i++) {
            int pixel = argb[i];
            luma[i] = (byte) luma((pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF);
        }
        int halfWidth = width / 2;
        int halfHeight = height / 2;
        byte[] blue = new byte[halfWidth * halfHeight];
        byte[] red = new byte[halfWidth * halfHeight];
        for (int y = 0; y < halfHeight; y++) {
            for (int x = 0; x < halfWidth; x++) {
                int at = 2 * y * width + 2 * x;
                int[] four = { argb[at], argb[at + 1], argb[at + width], argb[at + width + 1] };
                int r = 0;
                int g = 0;
                int b = 0;
                for (int pixel : four) {
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                }
                r = (r + 2) >> 2;
                g = (g + 2) >> 2;
                b = (b + 2) >> 2;
                blue[y * halfWidth + x] = (byte) blue(r, g, b);
                red[y * halfWidth + x] = (byte) red(r, g, b);
            }
        }
        return new Planes(width, height, luma, blue, red);
    }

    /** Y, Cb and Cr of one color in BT.709 video range: Y from 16 to 235, Cb and Cr from 16 to 240. */
    static int luma(int r, int g, int b) {
        return ((47 * r + 157 * g + 16 * b + 128) >> 8) + 16;
    }

    static int blue(int r, int g, int b) {
        return ((-26 * r - 86 * g + 112 * b + 128) >> 8) + 128;
    }

    static int red(int r, int g, int b) {
        return ((112 * r - 102 * g - 10 * b + 128) >> 8) + 128;
    }

    /** Copies [planes] into [image], the encoder's input, whatever row and pixel strides it has. */
    private static void fill(Image image, Planes planes) {
        Image.Plane[] target = image.getPlanes();
        copy(target[0], planes.luma, planes.width, planes.height);
        copy(target[1], planes.blue, planes.width / 2, planes.height / 2);
        copy(target[2], planes.red, planes.width / 2, planes.height / 2);
    }

    private static void copy(Image.Plane plane, byte[] from, int width, int height) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        for (int y = 0; y < height; y++) {
            int row = y * rowStride;
            if (pixelStride == 1) {
                buffer.position(row);
                buffer.put(from, y * width, width);
            } else {
                for (int x = 0; x < width; x++) buffer.put(row + x * pixelStride, from[y * width + x]);
            }
        }
    }
}
