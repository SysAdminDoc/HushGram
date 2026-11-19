/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The DASH manifest Instagram's website plays a public post or reel from, for Ask for VP9 instead
 * of AV1 (#546). The app's manifest can come as AV1 only even with the device status saying the
 * phone has no AV1 decoder, while the website, which can't count on AV1 in a browser, offers the
 * same video as VP9 at full size. A save whose own manifest would be AV1 asks the website, logged
 * out, the way its post page does: the home page for its LSD token and cookies, then the post's
 * GraphQL query by media id.
 *
 * <p>The query answers only with a browser's fetch headers ({@code Sec-Fetch-Site},
 * {@code -Mode} and {@code -Dest}); without them it hands back an HTML page, whatever the TLS. A
 * token stays good for a while, so it's kept for {@link #SESSION_MS} and a query answered without
 * JSON gets one more try on a fresh one. Stories have no public post, so they never come here.
 *
 * <p>Nothing here throws, and nothing it logs holds an address, token or cookie: the report is
 * pasted into public issues.
 */
final class WebManifest {

    static final String HOME = "https://www.instagram.com/";
    static final String GRAPHQL = "https://www.instagram.com/api/graphql";
    static final String QUERY = "PolarisLoggedOutDesktopWWWPostRootContentQuery";
    static final String DOC_ID = "27130156389949648";
    static final String APP_ID = "936619743392459";
    static final String ASBD_ID = "359341";

    /** What Chrome on any Android phone sends since its user agent reduction, so no phone is named. */
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/146.0.0.0 Mobile Safari/537.36";

    /** Where the home page keeps the token the query sends back, in its server-side config. */
    static final String LSD_MARKER = "[\"LSD\",[],{\"token\":\"";

    /** The home page runs about 540 KB and a reel's query about 400 KB; more is something else. */
    static final int MAX_BODY = 4 * 1024 * 1024;

    static final long SESSION_MS = 10 * 60 * 1000L;
    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 15_000;

    /** One request: a GET when [form] is null, else a POST of the form. */
    interface Http {
        Reply exchange(String url, Map<String, String> headers, byte[] form) throws IOException;
    }

    /** An answer's status, content type, body (empty unless 200) and Set-Cookie values. */
    static final class Reply {
        final int status;
        final String type;
        final String body;
        final List<String> cookies;

        Reply(int status, String type, String body, List<String> cookies) {
            this.status = status;
            this.type = type;
            this.body = body == null ? "" : body;
            this.cookies = cookies == null ? Collections.emptyList() : cookies;
        }
    }

    /** The home page's token and cookies, and when they were fetched. */
    private static final class Session {
        final String token;
        final String cookies;
        final String csrf;
        final long at;

        Session(String token, String cookies, String csrf, long at) {
            this.token = token;
            this.cookies = cookies;
            this.csrf = csrf;
            this.at = at;
        }
    }

    private static final WebManifest SHARED = new WebManifest(WebManifest::connect, System::currentTimeMillis);

    /** A website a test answers for, in place of the real one. */
    static volatile WebManifest forTests;

    private final Http http;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private Session session;

    WebManifest(Http http, LongSupplier clock) {
        this.http = http;
        this.clock = clock;
    }

    /**
     * [tracks], the app's manifest of the media [details] names, with the website's VP9 pictures
     * and sound added when the app's best picture is AV1 and it has no VP9. The pick ranks AV1 over
     * VP9 at one size and frame rate, so the app's AV1 pictures go when the website's largest VP9
     * is at least as large. Answers [tracks] itself when the website isn't asked or has no VP9.
     */
    static List<DashManifest.Track> withVp9(String label, List<DashManifest.Track> tracks, PostDetails details) {
        WebManifest web = forTests;
        return withVp9(label, tracks, details, web != null ? web : SHARED);
    }

    static List<DashManifest.Track> withVp9(String label, List<DashManifest.Track> tracks, PostDetails details,
            WebManifest web) {
        try {
            if (!wanted(tracks)) return tracks;
            long media = ExternalDownload.pk(details == null ? null : details.videoId);
            long post = details == null || !details.hasShortcode() ? 0 : pk(details.shortcode);
            if (media <= 0 || post <= 0) {
                MediaSave.info(() -> "the manifest of " + label + " is AV1 and it has no public post to ask the "
                    + "website for VP9");
                return tracks;
            }
            String manifest = web.manifest(post, media);
            if (manifest == null) return tracks;
            if (!DashManifest.withinLimits(manifest)) {
                MediaSave.info(() -> "the website's manifest of " + label + " is over the limits a save reads");
                return tracks;
            }
            List<DashManifest.Track> found = new ArrayList<>();
            for (DashManifest.Track track : DashManifest.parse(manifest)) {
                if (MediaUrlPolicy.shapeRefusal(track.url) == null) found.add(track);
            }
            List<DashManifest.Track> merged = merged(tracks, found);
            if (merged == tracks) {
                MediaSave.info(() -> "the website has no VP9 of " + label + " either: " + found);
                return tracks;
            }
            int added = 0;
            boolean av1Left = false;
            for (DashManifest.Track track : merged) {
                if (!tracks.contains(track)) added++;
                av1Left |= track.isVideo() && isAv1(track);
            }
            int count = added;
            boolean beside = av1Left;
            MediaSave.info(() -> "the website offers VP9 of " + label + ", adding its " + count + " VP9 and sound "
                + "track(s)" + (beside ? " beside the app's larger AV1" : " in place of the app's AV1"));
            return merged;
        } catch (Throwable t) {
            MediaSave.failure(() -> "asking the website for VP9 of " + label + " failed", t);
            return tracks;
        }
    }

    /** Whether [tracks]' best picture is AV1 and none is VP9, so the website could do better. */
    static boolean wanted(List<DashManifest.Track> tracks) {
        for (DashManifest.Track track : tracks) {
            if (track.isVideo() && isVp9(track)) return false;
        }
        DashManifest.Track best = DashManifest.bestVideo(tracks, true);
        return best != null && isAv1(best);
    }

    /**
     * [tracks] with [web]'s VP9 pictures and its sound, less [tracks]' AV1 pictures when the largest
     * VP9 is at least as large as the largest of them; [tracks] itself when [web] has no VP9.
     */
    static List<DashManifest.Track> merged(List<DashManifest.Track> tracks, List<DashManifest.Track> web) {
        int vp9 = 0;
        for (DashManifest.Track track : web) {
            if (track.isVideo() && isVp9(track)) vp9 = Math.max(vp9, track.shortSide());
        }
        if (vp9 == 0) return tracks;
        int av1 = 0;
        for (DashManifest.Track track : tracks) {
            if (track.isVideo() && isAv1(track)) av1 = Math.max(av1, track.shortSide());
        }
        boolean replace = vp9 >= av1;
        List<DashManifest.Track> merged = new ArrayList<>(tracks.size() + web.size());
        for (DashManifest.Track track : tracks) {
            if (!(replace && track.isVideo() && isAv1(track))) merged.add(track);
        }
        for (DashManifest.Track track : web) {
            if (track.isAudio() || (track.isVideo() && isVp9(track))) merged.add(track);
        }
        return merged;
    }

    private static boolean isAv1(DashManifest.Track track) {
        return track.codecs.startsWith("av01");
    }

    private static boolean isVp9(DashManifest.Track track) {
        return track.codecs.startsWith("vp09") && track.mime.equals("video/mp4");
    }

    /**
     * The number a post's shortcode spells in Instagram's base 64, or 0 when [code] isn't one that
     * fits a long. A public post's code is at most 11 characters, the first of them small.
     */
    static long pk(String code) {
        if (code == null || code.isEmpty() || code.length() > 11) return 0;
        long pk = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ExternalDownload.ALPHABET.indexOf(code.charAt(i));
            if (digit < 0 || pk > (Long.MAX_VALUE >> 6)) return 0;
            pk = (pk << 6) | digit;
        }
        return pk;
    }

    /**
     * The website's manifest of media [media] in post [post], the same number for a post or reel
     * that isn't a carousel, or null when it has none to give. Never throws.
     */
    String manifest(long post, long media) {
        try {
            Session current = cached();
            boolean fresh = current == null;
            if (fresh) current = open();
            if (current == null) return null;
            JSONObject answer = query(current, post);
            if (answer == null && !fresh) {
                forget(current);
                current = open();
                if (current == null) return null;
                answer = query(current, post);
            }
            if (answer == null) {
                forget(current);
                MediaSave.info(() -> "the website answered the VP9 question with no data");
                return null;
            }
            JSONObject data = answer.optJSONObject("data");
            JSONObject polaris = data == null ? null : data.optJSONObject("xig_polaris_media");
            JSONObject found = polaris == null ? null : polaris.optJSONObject("if_not_gated_logged_out");
            if (found == null) {
                MediaSave.info(() -> "the website shows nothing of that post logged out");
                return null;
            }
            String manifest = dash(found, media);
            if (manifest == null) MediaSave.info(() -> "the website's post has no manifest for that video");
            return manifest;
        } catch (Throwable t) {
            MediaSave.failure(() -> "the website's VP9 manifest could not be read", t);
            return null;
        }
    }

    /** [found]'s own manifest when it's media [media], else its carousel page's that is, or null. */
    static String dash(JSONObject found, long media) {
        String wanted = Long.toString(media);
        if (wanted.equals(String.valueOf(found.opt("pk")))) return nonEmpty(found.optString("video_dash_manifest", ""));
        JSONArray pages = found.optJSONArray("carousel_media");
        if (pages == null) return null;
        for (int i = 0; i < pages.length(); i++) {
            JSONObject page = pages.optJSONObject(i);
            if (page != null && wanted.equals(String.valueOf(page.opt("pk")))) {
                return nonEmpty(page.optString("video_dash_manifest", ""));
            }
        }
        return null;
    }

    private static String nonEmpty(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private Session cached() {
        synchronized (lock) {
            if (session == null) return null;
            long age = clock.getAsLong() - session.at;
            return age >= 0 && age < SESSION_MS ? session : null;
        }
    }

    private void forget(Session stale) {
        synchronized (lock) {
            if (session == stale) session = null;
        }
    }

    /** A fresh token and cookies from the home page, kept for the next save, or null. */
    private Session open() throws IOException {
        synchronized (lock) {
            Reply home = http.exchange(HOME, pageHeaders(), null);
            if (home.status != HttpURLConnection.HTTP_OK) {
                MediaSave.info(() -> "the website's home page answered " + home.status);
                return null;
            }
            String token = lsd(home.body);
            if (token == null) {
                MediaSave.info(() -> "the website's home page has no token to ask with");
                return null;
            }
            Map<String, String> jar = cookies(home.cookies);
            StringBuilder header = new StringBuilder();
            for (Map.Entry<String, String> cookie : jar.entrySet()) {
                if (header.length() > 0) header.append("; ");
                header.append(cookie.getKey()).append('=').append(cookie.getValue());
            }
            session = new Session(token, header.toString(), jar.get("csrftoken"), clock.getAsLong());
            return session;
        }
    }

    /** The post's query answer as JSON, or null when the website sent anything else. */
    private JSONObject query(Session current, long post) throws IOException {
        Map<String, String> headers = fetchHeaders();
        headers.put("Accept", "*/*");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Origin", "https://www.instagram.com");
        headers.put("Referer", HOME);
        headers.put("X-IG-App-ID", APP_ID);
        headers.put("X-ASBD-ID", ASBD_ID);
        headers.put("X-IG-WWW-Claim", "0");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("X-FB-Friendly-Name", QUERY);
        headers.put("X-FB-LSD", current.token);
        if (current.csrf != null) headers.put("X-CSRFToken", current.csrf);
        if (!current.cookies.isEmpty()) headers.put("Cookie", current.cookies);
        Reply reply = http.exchange(GRAPHQL, headers, form(current.token, post));
        if (reply.status != HttpURLConnection.HTTP_OK) {
            MediaSave.info(() -> "the website's query answered " + reply.status);
            return null;
        }
        String body = reply.body.trim();
        if (body.startsWith("for (;;);")) body = body.substring("for (;;);".length());
        if (!body.startsWith("{")) return null;
        try {
            return new JSONObject(body);
        } catch (JSONException notJson) {
            return null;
        }
    }

    static byte[] form(String token, long post) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("lsd", token);
        fields.put("fb_api_caller_class", "RelayModern");
        fields.put("fb_api_req_friendly_name", QUERY);
        fields.put("server_timestamps", "true");
        fields.put("variables", "{\"media_id\":\"" + post + "\"}");
        fields.put("doc_id", DOC_ID);
        StringBuilder form = new StringBuilder();
        try {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (form.length() > 0) form.append('&');
                form.append(field.getKey()).append('=').append(URLEncoder.encode(field.getValue(), "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
        return form.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> pageHeaders() {
        Map<String, String> headers = fetchHeaders();
        headers.put("Accept", "text/html");
        return headers;
    }

    /** What every request carries: a browser's name, language and fetch headers. */
    private static Map<String, String> fetchHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Dest", "empty");
        return headers;
    }

    /** The LSD token in [page], or null when it has none or it isn't a plain token. */
    static String lsd(String page) {
        int start = page.indexOf(LSD_MARKER);
        if (start < 0) return null;
        start += LSD_MARKER.length();
        int end = page.indexOf('"', start);
        if (end <= start || end - start > 256) return null;
        String token = page.substring(start, end);
        return plain(token) ? token : null;
    }

    /**
     * The name and value of each of [setCookies], the last of a name winning, leaving out any that
     * would break a Cookie header.
     */
    static Map<String, String> cookies(List<String> setCookies) {
        Map<String, String> jar = new LinkedHashMap<>();
        for (String line : setCookies) {
            if (line == null) continue;
            int end = line.indexOf(';');
            String pair = (end < 0 ? line : line.substring(0, end)).trim();
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!plain(name) || value.length() > 1024 || !cookieValue(value)) continue;
            jar.put(name, value);
            if (jar.size() >= 32) break;
        }
        return jar;
    }

    /** Whether [text] is letters, digits, {@code -}, {@code _} and {@code .} only, and not empty. */
    private static boolean plain(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    /** Whether [value] holds only what a cookie value may: printable ASCII without a space, quote, comma, semicolon or backslash. */
    private static boolean cookieValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c >= 0x7f || c == '"' || c == ',' || c == ';' || c == '\\') return false;
        }
        return true;
    }

    /** One request over HttpURLConnection, never following a redirect. */
    private static Reply connect(String url, Map<String, String> headers, byte[] form) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (form != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(form.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(form);
                }
            }
            int status = connection.getResponseCode();
            List<String> cookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
                if (field.getKey() != null && "Set-Cookie".equalsIgnoreCase(field.getKey()) && field.getValue() != null) {
                    cookies.addAll(field.getValue());
                }
            }
            String body = "";
            if (status == HttpURLConnection.HTTP_OK) {
                try (InputStream in = connection.getInputStream()) {
                    body = read(in);
                }
            }
            return new Reply(status, connection.getContentType(), body, cookies);
        } finally {
            connection.disconnect();
        }
    }

    /** [in] as UTF-8, or an IOException past {@link #MAX_BODY}. */
    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        for (int n; (n = in.read(buffer)) != -1; ) {
            total += n;
            if (total > MAX_BODY) throw new IOException("the answer is over " + MAX_BODY + " bytes");
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
