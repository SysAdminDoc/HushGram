/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Converts a VP9 or AV1 picture to H.264 on the phone, for Save videos other apps can open with
 * Convert to keep the full size on (#236). Instagram often has a reel's full size only as VP9 or
 * AV1, and its H.264 a step smaller.
 *
 * <p>The way AOSP's MediaTranscoder does it: MediaExtractor feeds the picture's samples to a
 * decoder, which draws each frame onto the input surface of an H.264 encoder at the same size, and
 * MediaMuxer writes what the encoder gives back. Nothing is drawn in between, so the frames never
 * pass through this process. The sound isn't touched: the save joins it to the converted picture
 * as it joins any picture.
 *
 * <p>Pictures over {@link #MAX_LENGTH_US} and HDR ones aren't converted, and the save keeps the
 * H.264 the manifest has. Cancel is read on every turn of the loop.
 */
final class VideoConvert {
    private VideoConvert() {
    }

    /** The source this conversion's lines carry in the diagnostic report. */
    private static final String SOURCE = "VideoConvert";

    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String VP9 = MediaFormat.MIMETYPE_VIDEO_VP9;
    private static final String AV1 = "video/av01";

    /** The longest picture it converts: three minutes, a little over a minute's work on an S22. */
    static final long MAX_LENGTH_US = 3L * 60L * 1_000_000L;

    /** The bitrate a conversion asks of the encoder stays within these. */
    static final int MIN_BITRATE = 2_000_000;
    static final int MAX_BITRATE = 12_000_000;

    /** The frame rate asked for when the picture doesn't give its own. */
    private static final int DEFAULT_FRAME_RATE = 30;

    /** A key frame every second, about what Instagram's own H.264 tracks have. */
    private static final int KEY_FRAME_EVERY_S = 1;

    /** One wait on a codec, and how long both may give nothing back before the conversion stops. */
    private static final long WAIT_US = 10_000L;
    private static final long STALL_MS = 15_000L;

    /** Room the converted file keeps beside its frames, for its boxes. */
    private static final long FILE_BOXES = 1024L * 1024L;

    /** What a test says the phone can convert. Never set on a phone. */
    static volatile Predicate<DashManifest.Track> convertibleForTests;

    /** The decoder and encoder found for a type and size, or {@link #NONE}. */
    private static final Map<String, String[]> FOUND = new ConcurrentHashMap<>();
    private static final String[] NONE = new String[0];

    /** Whether a picture of [codecs] is one this converts: VP9 or AV1. */
    static boolean needsConversion(String codecs) {
        return codecs.startsWith("vp09") || codecs.startsWith("av01");
    }

    /**
     * Whether this phone can convert [track]: an SDR VP9 or AV1 picture it has a decoder for, beside
     * an H.264 encoder that takes its size. Looked up once for each type and size.
     */
    static boolean canConvert(DashManifest.Track track) {
        Predicate<DashManifest.Track> forTests = convertibleForTests;
        if (forTests != null) return forTests.test(track);
        if (!track.isVideo() || !needsConversion(track.codecs) || hdr(track.codecs)) return false;
        if (track.width <= 0 || track.height <= 0) return false;
        return codecs(track.codecs.startsWith("vp09") ? VP9 : AV1, track.width, track.height) != null;
    }

    /**
     * Why a save that converts passes over [track], for the report, or null when nothing on the
     * phone's side stops it. A save can still keep a smaller file that suits the quality as well.
     */
    static String whyNot(DashManifest.Track track, boolean allowAv1) {
        if (!track.isVideo() || !needsConversion(track.codecs)) return "it isn't VP9 or AV1";
        if (track.codecs.startsWith("vp09") && !"video/mp4".equals(track.mime)) return "its VP9 isn't in an MP4";
        if (track.codecs.startsWith("av01") && !allowAv1) return "this phone can't read AV1 tracks";
        if (canConvert(track)) return null;
        if (hdr(track.codecs)) return "it's HDR";
        if (track.width <= 0 || track.height <= 0) return "the manifest gives no size";
        return "this phone has no " + (track.codecs.startsWith("vp09") ? "VP9" : "AV1")
            + " decoder and H.264 encoder for " + track.width + "x" + track.height;
    }

    /**
     * Whether [codecs] names an HDR picture: PQ or HLG (16 or 18) in the transfer field, AV1's
     * eighth and VP9's seventh. A file can leave its colour out of its own boxes, so the manifest's
     * word is taken before a byte is fetched.
     */
    static boolean hdr(String codecs) {
        String[] fields = codecs.split("\\.");
        int at = codecs.startsWith("av01") ? 7 : 6;
        return fields.length > at && (fields[at].equals("16") || fields[at].equals("18"));
    }

    /**
     * The names of a decoder of [type] and an H.264 encoder that both take [width] by [height], or
     * null when the phone has no such pair.
     */
    private static String[] codecs(String type, int width, int height) {
        String[] found = FOUND.computeIfAbsent(type + " " + width + "x" + height, key -> {
            String decoder = null;
            String encoder = null;
            try {
                for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    String wanted = codec.isEncoder() ? AVC : type;
                    if ((codec.isEncoder() ? encoder : decoder) != null) continue;
                    for (String supported : codec.getSupportedTypes()) {
                        if (!supported.equalsIgnoreCase(wanted)) continue;
                        MediaCodecInfo.VideoCapabilities video =
                            codec.getCapabilitiesForType(supported).getVideoCapabilities();
                        if (video == null || !video.isSizeSupported(width, height)) continue;
                        if (codec.isEncoder()) encoder = codec.getName(); else decoder = codec.getName();
                    }
                }
            } catch (Throwable ignored) {
                // No codec list, so nothing to convert with.
            }
            return decoder == null || encoder == null ? NONE : new String[] { decoder, encoder };
        });
        return found.length == 0 ? null : found;
    }

    /**
     * The bitrate to ask the encoder for: twice [sourceBps], since H.264 needs about that much to
     * keep what VP9 and AV1 hold in it, or a tenth of a bit for every pixel of every second when the
     * manifest doesn't say. Kept within {@link #MIN_BITRATE} and {@link #MAX_BITRATE}.
     */
    static int bitrate(long sourceBps, int width, int height, int frameRate) {
        long wanted = sourceBps > 0 ? sourceBps * 2L : (long) width * height * frameRate / 10L;
        return (int) Math.max(MIN_BITRATE, Math.min(MAX_BITRATE, wanted));
    }

    /**
     * The rate control asked of an encoder that [supports] the modes it's asked about: variable,
     * then constant, or -1 to leave it to the encoder.
     */
    static int bitrateMode(IntPredicate supports) {
        if (supports.test(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
            return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
        }
        if (supports.test(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
            return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
        }
        return -1;
    }

    private static int bitrateMode(MediaCodecInfo.EncoderCapabilities encoder) {
        return encoder == null ? -1 : bitrateMode(encoder::isBitrateModeSupported);
    }

    /** How the report names a {@link #bitrateMode} answer. */
    static String modeName(int mode) {
        if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) return "VBR";
        if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) return "CBR";
        return "in the encoder's own mode";
    }

    /** The kbps [bytes] of frames come to over the picture's length, or its last frame's time when it gave none. */
    static long achievedKbps(long bytes, long lastUs, long durationUs) {
        long us = durationUs > 0 ? durationUs : lastUs;
        return us <= 0 ? 0 : bytes * 8L * 1000L / us;
    }

    /**
     * The encoder's format, naming {@code mode} only when it is one (MediaFormat.removeKey is API 29).
     * It names BT.709 limited range SDR, as {@link MusicVideo}'s encoder does: a surface encoder left
     * to itself picks its own matrix and may leave the file untagged, and another app that reads the
     * picture with a different matrix shifts its colors.
     */
    static MediaFormat encoderFormat(int width, int height, int bitrate, int mode, int frameRate) {
        MediaFormat format = MediaFormat.createVideoFormat(AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        if (mode >= 0) format.setInteger(MediaFormat.KEY_BITRATE_MODE, mode);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_EVERY_S);
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        return format;
    }

    /**
     * Why the picture [format] describes isn't converted, or null when it is: no picture, no size,
     * longer than {@link #MAX_LENGTH_US}, or HDR, which the encoder would write as washed out SDR.
     */
    static String refusal(MediaFormat format) {
        if (format == null) return "the file holds no picture";
        Integer width = integer(format, MediaFormat.KEY_WIDTH);
        Integer height = integer(format, MediaFormat.KEY_HEIGHT);
        if (width == null || height == null || width <= 0 || height <= 0) return "the picture has no size";
        if (duration(format) > MAX_LENGTH_US) {
            return "the picture runs " + duration(format) / 1_000_000L + " s, longer than the "
                + MAX_LENGTH_US / 1_000_000L + " s a conversion takes";
        }
        Integer transfer = integer(format, MediaFormat.KEY_COLOR_TRANSFER);
        if (transfer != null && (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG)) {
            return "the picture is HDR";
        }
        return null;
    }

    /**
     * Writes the picture of [in] as H.264 to [out], reporting how far it has got to [progress].
     * [sourceBps] is the bitrate the manifest gives the picture, or 0. Blocks and never throws: a
     * failure that isn't a cancel is a build error, and the save keeps the H.264 the manifest has.
     */
    static Downloader.Result convert(File in, File out, long sourceBps, Downloader.Progress progress) {
        try {
            return transcode(in, out, sourceBps, progress);
        } catch (Throwable t) {
            // A cancel can end the codecs in a failure of their own. It's still the person's cancel.
            if (progress.cancelled()) return cancelled();
            String why = t instanceof Stop ? t.getMessage() : t.getClass().getSimpleName();
            // A reason of this class's own needs no trace.
            if (t instanceof Stop) MediaSave.failure(() -> "the picture was not converted to H.264: " + why, null);
            else Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the conversion to H.264 failed", t);
            return Downloader.Result.fail(Downloader.Status.BUILD_ERROR, "the picture could not be converted to H.264: " + why);
        }
    }

    /** A reason of this class's own, which the report repeats as it is. */
    private static final class Stop extends IOException {
        Stop(String reason) {
            super(reason);
        }
    }

    private static Downloader.Result cancelled() {
        return Downloader.Result.fail(Downloader.Status.CANCELLED, "cancelled during the conversion");
    }

    private static Downloader.Result transcode(File in, File out, long sourceBps, Downloader.Progress progress)
            throws IOException {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        Surface surface = null;
        MediaMuxer muxer = null;
        boolean decoding = false;
        boolean encoding = false;
        boolean muxing = false;
        Throwable failure = null;
        String done = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(in.getPath());
            MediaFormat source = DashSave.selectTrack(extractor, "video/");
            String refused = refusal(source);
            if (refused != null) throw new Stop(refused);
            String type = source.getString(MediaFormat.KEY_MIME);
            int width = source.getInteger(MediaFormat.KEY_WIDTH);
            int height = source.getInteger(MediaFormat.KEY_HEIGHT);
            String[] names = type == null ? null : codecs(type, width, height);
            if (names == null) throw new Stop("the phone has no decoder of " + type + " beside an H.264 encoder at " + width + "x" + height);
            long durationUs = duration(source);
            Integer rate = integer(source, MediaFormat.KEY_FRAME_RATE);
            int frameRate = rate == null || rate <= 0 ? DEFAULT_FRAME_RATE : rate;
            Integer rotation = integer(source, MediaFormat.KEY_ROTATION);
            // A decoder drawing onto a surface turns its frames by this, and the encoder's surface is
            // the picture's own size. So the frames go across as stored and the file says to turn them.
            source.setInteger(MediaFormat.KEY_ROTATION, 0);
            // The muxer refuses a time before zero, and a picture shifted here would lose its gap to
            // the sound, which the join measures from the converted file. VP9 and AV1 start at zero.
            if (extractor.getSampleTime() < 0) throw new Stop("the picture starts before zero");

            encoder = MediaCodec.createByCodecName(names[1]);
            MediaCodecInfo.CodecCapabilities avc = encoder.getCodecInfo().getCapabilitiesForType(AVC);
            MediaCodecInfo.VideoCapabilities capabilities = avc.getVideoCapabilities();
            int bitrate = capabilities.getBitrateRange().clamp(bitrate(sourceBps, width, height, frameRate));
            // The whole picture at twice its bitrate, with the boxes: more than the file can come to.
            long seconds = (durationUs > 0 ? durationUs : MAX_LENGTH_US) / 1_000_000L + 1L;
            long room = 2L * (bitrate / 8L) * seconds + FILE_BOXES;
            if (DashSave.reserve(out, room) < room) {
                return Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "not enough free space to convert the picture");
            }

            // Left to itself, the S22's encoder wrote about 7.9 Mbps whatever it was asked for, so
            // the mode is named and the rate is asked again once it runs.
            int mode = bitrateMode(avc.getEncoderCapabilities());
            try {
                encoder.configure(encoderFormat(width, height, bitrate, mode, frameRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (IllegalArgumentException | MediaCodec.CodecException e) {
                if (mode < 0) throw e;
                // An encoder can list a mode and still refuse it at this size. It keeps its own then.
                encoder.reset();
                mode = -1;
                encoder.configure(encoderFormat(width, height, bitrate, mode, frameRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            surface = encoder.createInputSurface();
            encoder.start();
            encoding = true;
            Bundle rateNow = new Bundle();
            rateNow.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
            encoder.setParameters(rateNow);

            decoder = MediaCodec.createByCodecName(names[0]);
            decoder.configure(source, surface, null, 0);
            decoder.start();
            decoding = true;

            muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rotation != null && rotation != 0) muxer.setOrientationHint(rotation);
            progress.converting(durationUs > 0 ? 0 : -1);

            MediaCodec.BufferInfo decoded = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encoded = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo frame = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean decoderDone = false;
            int track = -1;
            int written = 0;
            long bytes = 0L;
            long lastUs = 0L;
            int shown = 0;
            long answered = System.currentTimeMillis();

            while (true) {
                if (progress.cancelled()) return cancelled();
                if (!inputDone) {
                    int at = decoder.dequeueInputBuffer(WAIT_US);
                    if (at >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(at);
                        if (buffer == null) throw new Stop("the decoder gave no buffer to fill");
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(at, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(at, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        answered = System.currentTimeMillis();
                    }
                }

                if (!decoderDone) {
                    int at = decoder.dequeueOutputBuffer(decoded, WAIT_US);
                    if (at >= 0) {
                        // Drawn onto the encoder's surface at the frame's own time.
                        decoder.releaseOutputBuffer(at, decoded.size > 0);
                        if ((decoded.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                        }
                        answered = System.currentTimeMillis();
                    }
                }

                int ready = encoder.dequeueOutputBuffer(encoded, WAIT_US);
                if (ready == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new Stop("the encoder changed its format after it began");
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                    answered = System.currentTimeMillis();
                } else if (ready >= 0) {
                    ByteBuffer data = encoder.getOutputBuffer(ready);
                    boolean setup = (encoded.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!setup && encoded.size > 0 && data != null) {
                        if (!muxing) throw new Stop("the encoder sent a frame before its format");
                        long time = encoded.presentationTimeUs;
                        // A picture that didn't give its length is held to the limit as it goes.
                        if (time > MAX_LENGTH_US) throw new Stop("the picture runs past " + MAX_LENGTH_US / 1_000_000L + " s");
                        data.position(encoded.offset);
                        data.limit(encoded.offset + encoded.size);
                        frame.set(encoded.offset, encoded.size, time, encoded.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
                        muxer.writeSampleData(track, data, frame);
                        written++;
                        bytes += encoded.size;
                        lastUs = Math.max(lastUs, time);
                        if (durationUs > 0) {
                            int percent = (int) Math.min(99L, Math.max(0L, time * 100L / durationUs));
                            if (percent != shown) {
                                shown = percent;
                                progress.converting(percent);
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(ready, false);
                    answered = System.currentTimeMillis();
                    if ((encoded.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                if (System.currentTimeMillis() - answered > STALL_MS) {
                    throw new Stop("the codecs gave nothing back for " + STALL_MS + " ms");
                }
            }
            if (written == 0) throw new Stop("the encoder wrote no frame");
            // Said once the muxer has finished the file, below.
            done = "converted " + written + " frame(s) of " + type + " at " + width + "x" + height
                + (rate == null || rate <= 0 ? " (" + frameRate + " fps assumed)" : " (" + frameRate + " fps)")
                + " to H.264 at " + bitrate / 1000 + "kbps " + modeName(mode) + ", wrote "
                + achievedKbps(bytes, lastUs, durationUs) + "kbps";
            return Downloader.Result.ok("video/mp4");
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            // Each one is released whatever the one before it did, as a join does. The decoder goes
            // first, since it draws onto the encoder's surface.
            Throwable closing = failure;
            if (decoder != null) {
                if (decoding) closing = DashSave.attempt(closing, decoder::stop);
                closing = DashSave.attempt(closing, decoder::release);
            }
            if (encoder != null) {
                if (encoding) closing = DashSave.attempt(closing, encoder::stop);
                closing = DashSave.attempt(closing, encoder::release);
            }
            if (surface != null) closing = DashSave.attempt(closing, surface::release);
            if (muxer != null) {
                if (muxing) closing = DashSave.attempt(closing, muxer::stop);
                closing = DashSave.attempt(closing, muxer::release);
            }
            if (extractor != null) closing = DashSave.attempt(closing, extractor::release);
            if (failure == null && closing != null) {
                throw new IOException("the converted file could not be finished: " + closing.getClass().getSimpleName(),
                    closing);
            }
            String converted = done;
            if (converted != null) MediaSave.info(() -> converted);
        }
    }

    /** How long the picture [format] runs, in microseconds, or -1 when it doesn't say. */
    private static long duration(MediaFormat format) {
        try {
            return format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static Integer integer(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
