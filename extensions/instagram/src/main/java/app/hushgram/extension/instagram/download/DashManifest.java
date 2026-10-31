/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/DashManifest.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tracks of a DASH manifest, and the best video track and audio track of them.
 *
 * <p>This class holds no Android type, like {@link RenditionPicker}. So {@code javac} alone can
 * test it against a manifest from a device.
 *
 * <p>Facebook writes each track as one {@code Representation} with one {@code BaseURL}. That is the
 * address of a whole MP4 file that holds only this track. So one plain fetch gets a track, and the
 * video and the audio are two files. A track with no {@code BaseURL} is not used, because one fetch
 * cannot get it.
 *
 * <p>This reads only that one shape. It is not a general XML parser.
 */
final class DashManifest {

    private DashManifest() {}

    /** One track of the manifest. */
    static final class Track {
        final String mime;
        final String codecs;
        final int width;
        final int height;
        final long bandwidth;
        final String url;

        /**
         * The quality Facebook's own menu names this track by, such as 480 for {@code 480p}, or 0
         * when the track carries no label. Facebook encodes several of a video's tracks at one
         * frame size and tells them apart by this: a 720 by 1280 track can be its 480p.
         */
        final int label;

        /**
         * Whether the picture is HDR, or 10-bit, which Meta streams only for HDR: PQ or HLG in the
         * codecs string or the manifest's cicp properties, BT.2020 primaries there, or a 10-bit
         * profile. An app that plays it as SDR gives it a yellow or green cast (#352).
         */
        final boolean hdr;

        Track(String mime, String codecs, int width, int height, long bandwidth, String url) {
            this(mime, codecs, width, height, bandwidth, url, 0);
        }

        Track(String mime, String codecs, int width, int height, long bandwidth, String url, int label) {
            this(mime, codecs, width, height, bandwidth, url, label, hdrCodecs(codecs));
        }

        Track(String mime, String codecs, int width, int height, long bandwidth, String url, int label, boolean hdr) {
            this.mime = mime;
            this.codecs = codecs;
            this.width = width;
            this.height = height;
            this.bandwidth = bandwidth;
            this.url = url;
            this.label = label;
            this.hdr = hdr;
        }

        boolean isVideo() {
            return mime.startsWith("video/");
        }

        boolean isAudio() {
            return mime.startsWith("audio/");
        }

        /** The quality in the same unit as {@code 720p}: the short side, in pixels. */
        int shortSide() {
            return Math.min(width, height);
        }

        /** The quality a setting below the best is held to: the label, else the short side. */
        int quality() {
            return label > 0 ? label : shortSide();
        }

        @Override
        public String toString() {
            return mime + " " + codecs + " " + width + "x" + height + " " + (bandwidth / 1000) + "kbps"
                + (label > 0 ? " " + label + "p" : "") + (hdr ? " HDR" : "");
        }
    }

    /**
     * Whether [codecs] names an HDR picture, or a 10-bit one, which Meta encodes only for HDR: PQ
     * or HLG (16 or 18) in the transfer field, AV1's eighth and VP9's seventh, 10 or 12 in the bit
     * depth field, AV1's and VP9's fourth, or H.265's Main 10 profile (2). A file can leave its
     * colour out of its own boxes, so the manifest's word is taken before a byte is fetched.
     */
    static boolean hdrCodecs(String codecs) {
        String[] fields = codecs.split("\\.");
        if (codecs.startsWith("av01")) {
            return (fields.length > 3 && number(fields[3]) > 8) || (fields.length > 7 && pqOrHlg(fields[7]));
        }
        if (codecs.startsWith("vp09")) {
            return (fields.length > 3 && number(fields[3]) > 8) || (fields.length > 6 && pqOrHlg(fields[6]));
        }
        if (codecs.startsWith("hvc1") || codecs.startsWith("hev1")) {
            // The profile may carry a general profile space letter in front, "A2".
            return fields.length > 1 && fields[1].replaceAll("[a-cA-C]", "").equals("2");
        }
        return false;
    }

    private static boolean pqOrHlg(String transfer) {
        return transfer.equals("16") || transfer.equals("18");
    }

    private static final Pattern PROPERTY = Pattern.compile("<(?:Supplemental|Essential)Property\\b([^>]*?)/?>");

    /**
     * Whether [body], the inside of an adaptation set with its representations taken out, or of one
     * representation, carries a cicp property that names HDR: TransferCharacteristics 16 (PQ) or 18
     * (HLG), or ColourPrimaries 9 (BT.2020). Meta writes all three cicp properties on every set,
     * value 1 for its SDR pictures.
     */
    static boolean hdrSignalled(String body) {
        if (body == null) return false;
        Matcher properties = PROPERTY.matcher(body);
        while (properties.find()) {
            String scheme = attribute(properties.group(1), "schemeIdUri");
            String value = attribute(properties.group(1), "value");
            if (scheme == null || value == null) continue;
            value = value.trim();
            if (scheme.endsWith(":cicp:TransferCharacteristics") && pqOrHlg(value)) return true;
            if (scheme.endsWith(":cicp:ColourPrimaries") && value.equals("9")) return true;
        }
        return false;
    }

    private static final Pattern ADAPTATION_SET =
        Pattern.compile("<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet>", Pattern.DOTALL);

    private static final Pattern REPRESENTATION =
        Pattern.compile("<Representation\\b([^>]*?)(?:/>|>(.*?)</Representation>)", Pattern.DOTALL);

    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:]+)\\s*=\\s*\"([^\"]*)\"");

    private static final Pattern BASE_URL = Pattern.compile("<BaseURL[^>]*>(.*?)</BaseURL>", Pattern.DOTALL);

    /**
     * The longest manifest that's kept or read, in characters. The captured one in the tests is
     * 6.7 KB with nine tracks, and a track with Facebook's full signed address runs to about 1.5 KB.
     */
    static final int MAX_CHARS = 128 * 1024;

    /** The most tracks, and track groups, a manifest that's kept or read may list. */
    static final int MAX_REPRESENTATIONS = 64;
    static final int MAX_ADAPTATION_SETS = 16;

    /**
     * Whether [manifest] is small enough to keep and to read. The expressions below scan ahead for
     * each tag they find, so an unbounded manifest could stall a save or hold megabytes for a
     * player long gone. Counting the tags is one pass, and stops at the limit.
     */
    static boolean withinLimits(String manifest) {
        return manifest != null && manifest.length() <= MAX_CHARS
            && atMost(manifest, "<Representation", MAX_REPRESENTATIONS)
            && atMost(manifest, "<AdaptationSet", MAX_ADAPTATION_SETS);
    }

    /** Whether [text] holds [tag] no more than [limit] times. */
    private static boolean atMost(String text, String tag, int limit) {
        int found = 0;
        for (int at = text.indexOf(tag); at >= 0; at = text.indexOf(tag, at + tag.length())) {
            if (++found > limit) return false;
        }
        return true;
    }

    /**
     * Every track with a single file address, or none for a manifest over the limits. Never throws,
     * and never returns {@code null}.
     */
    static List<Track> parse(String manifest) {
        List<Track> tracks = new ArrayList<>();
        if (!withinLimits(manifest)) return tracks;

        try {
            Matcher sets = ADAPTATION_SET.matcher(manifest);
            while (sets.find()) {
                String setAttributes = sets.group(1);
                // A set's own colour properties speak for every track in it (#352).
                boolean setHdr = hdrSignalled(REPRESENTATION.matcher(sets.group(2)).replaceAll(""));
                Matcher representations = REPRESENTATION.matcher(sets.group(2));

                while (representations.find()) {
                    Track track = track(setAttributes, setHdr, representations.group(1), representations.group(2));
                    if (track != null) tracks.add(track);
                }
            }
        } catch (Throwable ignored) {
            // A manifest of an unexpected shape answers with what was read so far.
        }

        return tracks;
    }

    /**
     * The best video track a save can write into an MP4, or {@code null}.
     *
     * <p>H.264 and H.265 are always permitted. AV1 is permitted only when [allowAv1] is true: a
     * story often lists only AV1 tracks, but a device with no AV1 decoder cannot play the file. VP9
     * in an MP4 is always permitted. {@code MediaMuxer} refuses it, also on Android 17, so
     * {@link Mp4Join} writes it, and every Android phone decodes VP9. At the same size, H.264 is
     * the first choice, because all players can play it, and VP9 the last, the one format no
     * Android muxer writes.
     */
    static Track bestVideo(List<Track> tracks, boolean allowAv1) {
        Track best = null;

        for (Track track : tracks) {
            if (!track.isVideo()) continue;

            int family = videoFamily(track, allowAv1);
            if (family == 0) continue;

            if (best == null) {
                best = track;
                continue;
            }

            if (track.shortSide() != best.shortSide()) {
                if (track.shortSide() > best.shortSide()) best = track;
                continue;
            }

            int bestFamily = videoFamily(best, allowAv1);
            if (family != bestFamily) {
                if (family > bestFamily) best = track;
                continue;
            }

            if (track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    /**
     * The video track that suits [quality] best, or {@code null}. The best quality is
     * {@link #bestVideo}, as it always was. Below it, the tracks are held to their labels: the
     * best one at or under the ceiling, else the nearest above it, and for the smallest file the
     * lowest. At one quality H.264 still goes first, then the higher bitrate, or the lower one for
     * the smallest file. A track the phone can't write is never picked, whatever it's labelled.
     */
    static Track pickVideo(List<Track> tracks, boolean allowAv1, DownloadQuality quality) {
        if (quality == null || quality == DownloadQuality.BEST) return bestVideo(tracks, allowAv1);

        Track best = null;

        for (Track track : tracks) {
            if (!track.isVideo()) continue;

            int family = videoFamily(track, allowAv1);
            if (family == 0) continue;

            if (best == null) {
                best = track;
                continue;
            }

            int order = quality.compare(track.quality(), best.quality());
            if (order != 0) {
                if (order < 0) best = track;
                continue;
            }

            int bestFamily = videoFamily(best, allowAv1);
            if (family != bestFamily) {
                if (family > bestFamily) best = track;
                continue;
            }

            boolean smaller = quality == DownloadQuality.SMALLEST;
            if (smaller ? track.bandwidth < best.bandwidth : track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    /**
     * Prefer AAC-LC or HE-AAC sound that gallery players can open, without lowering the video's
     * resolution. Use another AAC profile only when neither is offered. Stories can list only
     * xHE-AAC ({@code mp4a.40.42}), so discarding it would lose their sound.
     */
    static Track bestAudio(List<Track> tracks) {
        Track compatible = bestCompatibleAudio(tracks);
        if (compatible != null) return compatible;
        Track best = null;

        for (Track track : tracks) {
            if (!track.isAudio()) continue;
            if (!track.codecs.startsWith("mp4a")) continue;
            if (best == null || track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    // ---------------------------------------------------------------- files other apps can open

    /**
     * Whether a video track is H.264 ({@code avc1} or {@code avc3}), the one video format every app
     * that takes an MP4 plays. WhatsApp names H.264 with AAC sound as what it sends, and turned
     * down a reel saved as AV1 with xHE-AAC sound that Gallery and VLC played (issue #11).
     */
    static boolean isCompatibleVideo(Track track) {
        return track.isVideo() && (track.codecs.startsWith("avc1") || track.codecs.startsWith("avc3"));
    }

    /**
     * How widely an audio track plays: 2 for AAC-LC ({@code mp4a.40.2}), 1 for HE-AAC and HE-AAC v2
     * ({@code mp4a.40.5}, {@code mp4a.40.29}), which carry an AAC-LC core, and 0 for anything else.
     * xHE-AAC ({@code mp4a.40.42}, USAC) is a different codec under the same {@code mp4a.40} name,
     * and apps that take AAC refuse it.
     */
    static int compatibleAudioRank(Track track) {
        if (!track.isAudio() || !track.codecs.startsWith("mp4a.40.")) return 0;
        int objectType;
        try {
            objectType = Integer.parseInt(track.codecs.substring("mp4a.40.".length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        if (objectType == 2) return 2;
        if (objectType == 5 || objectType == 29) return 1;
        return 0;
    }

    /** The H.264 track that suits [quality] best, by the rules of {@link #pickVideo}, or null. */
    static Track pickCompatibleVideo(List<Track> tracks, DownloadQuality quality) {
        List<Track> h264 = new ArrayList<>();
        for (Track track : tracks) {
            if (isCompatibleVideo(track)) h264.add(track);
        }
        return pickVideo(h264, false, quality);
    }

    /**
     * The AAC-LC track with the highest bitrate, else the HE-AAC track with the highest, else
     * {@code null}. Never xHE-AAC or any other profile.
     */
    static Track bestCompatibleAudio(List<Track> tracks) {
        Track best = null;
        int bestRank = 0;

        for (Track track : tracks) {
            int rank = compatibleAudioRank(track);
            if (rank == 0) continue;
            if (best == null || rank > bestRank || (rank == bestRank && track.bandwidth > best.bandwidth)) {
                best = track;
                bestRank = rank;
            }
        }

        return best;
    }

    /** A video track and the audio track to join it with, or no audio for a video with no sound. */
    static final class Pick {
        final Track video;
        final Track audio;

        Pick(Track video, Track audio) {
            this.video = video;
            this.audio = audio;
        }
    }

    /**
     * The tracks a save of this manifest joins, or {@code null} when it has none to offer.
     *
     * <p>Not [compatible], that's {@link #pickVideo} and {@link #bestAudio}. [compatible] holds
     * both to what other apps open: an H.264 track
     * ({@link #pickCompatibleVideo}) and an AAC-LC or HE-AAC one ({@link #bestCompatibleAudio}).
     * A manifest that has sound but none of it in those profiles has no pick, rather than a silent
     * one: the caller saves the single file instead.
     */
    static Pick pick(List<Track> tracks, boolean allowAv1, DownloadQuality quality, boolean compatible) {
        if (!compatible) {
            Track video = pickVideo(tracks, allowAv1, quality);
            Track audio = bestAudio(tracks);
            // A supported picture beside unsupported sound must not become a silent save.
            if (video == null || (audio == null && hasSound(tracks))) return null;
            return new Pick(video, audio);
        }

        Track video = pickCompatibleVideo(tracks, quality);
        if (video == null) return null;

        Track audio = bestCompatibleAudio(tracks);
        if (audio == null && hasSound(tracks)) return null;
        return new Pick(video, audio);
    }

    /**
     * The VP9 or AV1 picture a save converts to H.264 for other apps (#236), by the rules of
     * {@link #pickVideo} among those [convertible] passes, with the sound
     * {@link #bestCompatibleAudio} gives. Null with no such picture, or with sound but none of it
     * in a profile other apps open.
     */
    static Pick pickToConvert(List<Track> tracks, boolean allowAv1, DownloadQuality quality,
            Predicate<Track> convertible) {
        return pickToConvert(tracks, allowAv1, quality, convertible, false);
    }

    /**
     * As above. With [soundElsewhere], sound none of which is in such a profile (xHE-AAC only) leaves
     * the pick without sound rather than without a pick: the caller takes the sound from elsewhere.
     */
    static Pick pickToConvert(List<Track> tracks, boolean allowAv1, DownloadQuality quality,
            Predicate<Track> convertible, boolean soundElsewhere) {
        List<Track> pictures = new ArrayList<>();
        for (Track track : tracks) {
            if (track.isVideo() && (track.codecs.startsWith("vp09") || track.codecs.startsWith("av01"))
                    && convertible.test(track)) {
                pictures.add(track);
            }
        }
        Track video = pickVideo(pictures, allowAv1, quality);
        if (video == null) return null;

        Track audio = bestCompatibleAudio(tracks);
        if (audio == null && hasSound(tracks) && !soundElsewhere) return null;
        return new Pick(video, audio);
    }

    /** Whether the manifest lists any sound at all, whatever its codec. */
    static boolean hasSound(List<Track> tracks) {
        for (Track track : tracks) {
            if (track.isAudio()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- internals

    /**
     * A higher number is a better choice: 4 for H.264, 3 for H.265, 2 for AV1, 1 for VP9, 0 for not
     * used. VP9 counts only in an MP4, the container {@link Mp4Join} copies it from.
     */
    private static int videoFamily(Track track, boolean allowAv1) {
        String codecs = track.codecs;
        if (codecs.startsWith("avc1") || codecs.startsWith("avc3")) return 4;
        if (codecs.startsWith("hvc1") || codecs.startsWith("hev1")) return 3;
        if (allowAv1 && codecs.startsWith("av01")) return 2;
        if (codecs.startsWith("vp09") && track.mime.equals("video/mp4")) return 1;
        return 0;
    }

    private static Track track(String setAttributes, boolean setHdr, String attributes, String body) {
        if (body == null) return null;

        Matcher base = BASE_URL.matcher(body);
        if (!base.find()) return null;

        String url = unescape(base.group(1).trim());
        if (!url.toLowerCase(Locale.US).startsWith("https://")) return null;

        // A track gives its type on itself or on its adaptation set.
        String mime = attribute(attributes, "mimeType");
        if (mime == null) mime = attribute(setAttributes, "mimeType");
        if (mime == null) {
            String content = attribute(setAttributes, "contentType");
            if (content != null) mime = content + "/mp4";
        }
        if (mime == null) return null;

        String codecs = attribute(attributes, "codecs");
        if (codecs == null) codecs = attribute(setAttributes, "codecs");
        codecs = codecs == null ? "" : codecs.toLowerCase(Locale.US);

        return new Track(
            mime.toLowerCase(Locale.US),
            codecs,
            number(attribute(attributes, "width")),
            number(attribute(attributes, "height")),
            number(attribute(attributes, "bandwidth")),
            url,
            qualityLabel(attribute(attributes, "FBQualityLabel")),
            setHdr || hdrSignalled(body) || hdrCodecs(codecs)
        );
    }

    /** The number in a label such as {@code 720p}, or 0 for anything else. */
    private static int qualityLabel(String label) {
        if (label == null) return 0;
        String text = label.trim().toLowerCase(Locale.US);
        int digits = text.length() - 1;
        if (digits < 3 || digits > 4 || text.charAt(digits) != 'p') return 0;
        for (int i = 0; i < digits; i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return 0;
        }
        return number(text.substring(0, digits));
    }

    private static String attribute(String attributes, String name) {
        if (attributes == null) return null;

        Matcher matcher = ATTRIBUTE.matcher(attributes);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) return unescape(matcher.group(2));
        }
        return null;
    }

    private static int number(String text) {
        if (text == null) return 0;
        try {
            long value = Long.parseLong(text.trim());
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The five entities of XML. {@code &amp;} is the last. Thus {@code &amp;lt;} becomes {@code &lt;}. */
    private static String unescape(String text) {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&");
    }
}
