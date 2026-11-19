/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * Ask for VP9 instead of AV1 asking Instagram's website (#546). A save whose manifest would be AV1
 * fetches the website's manifest of the same public post, logged out and with a browser's fetch
 * headers, and its VP9 takes the AV1's place when it's as large. Stories and saves with the switch
 * off never ask, and a website that answers with anything else leaves the app's manifest as it was.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class WebManifestTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** The reel in the #546 thread, and its shortcode on the website. */
    private static final long REEL = 4015859002104765753L;
    private static final String CODE = "De7MnxIgl05";
    private static final String TOKEN = "AVq1x-Token_9";
    private static final String AV1 = "av01.0.08M.08.0.110.01.01.01.0";
    /** The parser keeps codecs in lower case, so the save report names AV1 this way. */
    private static final String AV1_SAVED = "av01.0.08m.08.0.110.01.01.01.0";
    private static final String VP9 = "vp09.00.40.08.00.01.01.01.00";

    /** What the reporter's app was sent: AV1 pictures only, and xHE-AAC sound. */
    private static final String APP = manifest(
            video(AV1, 720, 1280, 900_000, "a720", "fbcdn.net") + video(AV1, 1080, 1920, 1_400_000, "a1080", "fbcdn.net"),
            sound("mp4a.40.42", 84_000, "xhe", "fbcdn.net"));

    /** What the website sends for the same reel: VP9 up to 1080p, and HE-AAC sound. */
    private static final String WEB = manifest(
            video(VP9, 360, 640, 149_000, "w360", "cdninstagram.com")
                + video(VP9, 1080, 1920, 1_507_000, "w1080", "cdninstagram.com"),
            sound("mp4a.40.5", 76_000, "he", "cdninstagram.com"));

    private static final String WEB_PAIR = "video/mp4 " + VP9 + " 1080x1920 1507kbps + audio/mp4 mp4a.40.5 0x0 76kbps";

    private Context context;
    private Boolean av1Before;

    private static String manifest(String videos, String sounds) {
        return "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">" + videos
            + "</AdaptationSet><AdaptationSet mimeType=\"audio/mp4\">" + sounds + "</AdaptationSet></Period></MPD>";
    }

    private static String video(String codecs, int width, int height, long bandwidth, String name, String domain) {
        return "<Representation codecs=\"" + codecs + "\" width=\"" + width + "\" height=\"" + height
            + "\" bandwidth=\"" + bandwidth + "\">" + base(name, domain) + "</Representation>";
    }

    private static String sound(String codecs, long bandwidth, String name, String domain) {
        return "<Representation codecs=\"" + codecs + "\" bandwidth=\"" + bandwidth + "\">" + base(name, domain)
            + "</Representation>";
    }

    private static String base(String name, String domain) {
        return "<BaseURL>https://scontent-mia5-1." + domain + "/o1/v/t16/f2/m69/" + name + ".mp4?oh=1&amp;oe=2</BaseURL>";
    }

    /** One request the website was sent. */
    private static final class Sent {
        final String url;
        final Map<String, String> headers;
        final String form;

        Sent(String url, Map<String, String> headers, byte[] form) {
            this.url = url;
            this.headers = headers;
            this.form = form == null ? null : new String(form, StandardCharsets.UTF_8);
        }

        String field(String name) throws IOException {
            for (String pair : form.split("&")) {
                int equals = pair.indexOf('=');
                if (pair.substring(0, equals).equals(name)) return URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
            }
            return null;
        }
    }

    /** A website that answers from a queue per address and keeps what it was sent. */
    private static final class Website implements WebManifest.Http {
        final List<Sent> sent = new ArrayList<>();
        final Deque<WebManifest.Reply> home = new ArrayDeque<>();
        final Deque<WebManifest.Reply> query = new ArrayDeque<>();

        @Override public WebManifest.Reply exchange(String url, Map<String, String> headers, byte[] form) throws IOException {
            sent.add(new Sent(url, headers, form));
            Deque<WebManifest.Reply> replies = url.equals(WebManifest.HOME) ? home : query;
            if (replies.isEmpty()) throw new IOException("nothing more to answer " + url);
            return replies.size() == 1 ? replies.peek() : replies.poll();
        }

        int count(String url) {
            int found = 0;
            for (Sent request : sent) if (request.url.equals(url)) found++;
            return found;
        }
    }

    private static WebManifest.Reply homePage(String token) {
        String page = "<html><script>requireLazy([[\"LSD\",[],{\"token\":\"" + token + "\"},323]])</script></html>";
        return new WebManifest.Reply(200, "text/html; charset=utf-8", page, Arrays.asList(
            "csrftoken=Csrf42; Path=/; Secure; SameSite=Lax",
            "mid=\"Zz9_\"; Domain=.instagram.com",
            "spaced=a b; Path=/",
            "broken=a\r\nX-Evil: 1"));
    }

    private static WebManifest.Reply answer(JSONObject media) throws Exception {
        JSONObject body = new JSONObject().put("data", new JSONObject().put("xig_polaris_media",
            new JSONObject().put("if_not_gated_logged_out", media)));
        return new WebManifest.Reply(200, "text/javascript; charset=utf-8", body.toString(), null);
    }

    private static JSONObject reel(String manifest) throws Exception {
        return new JSONObject().put("pk", Long.toString(REEL)).put("code", CODE).put("media_type", 2)
            .put("video_dash_manifest", manifest);
    }

    private static WebManifest.Reply page(String body) {
        return new WebManifest.Reply(200, "text/html", body, null);
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        LogBufferManager.clearLogBuffer();
        PauseForTests.resume();
        MediaSave.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
        av1Before = ReflectionHelpers.getStaticField(DashSave.class, "canWriteAv1");
        ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", Boolean.TRUE);
    }

    @After
    public void tearDown() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaSave.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        WebManifest.forTests = null;
        MediaSave.policyForTests = null;
        ReflectionHelpers.setStaticField(DashSave.class, "canWriteAv1", av1Before);
        Settings.ASK_FOR_VP9.resetToDefault();
        PauseForTests.resume();
        LogBufferManager.clearLogBuffer();
    }

    private String reelSave(PostDetails details) throws InterruptedException {
        LogBufferManager.clearLogBuffer();
        assertTrue(MediaSave.saveVideo(context, Collections.emptyList(), APP, details));
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaSave.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        return LogBufferManager.buildExportText();
    }

    private static PostDetails reelDetails() {
        return PostDetails.of(Long.toString(REEL)).withPostId(Long.toString(REEL));
    }

    @Test
    public void aShortcodeReadsBackAsThePostsNumber() {
        assertEquals(CODE, ExternalDownload.shortcode(Long.toString(REEL)));
        assertEquals(REEL, WebManifest.pk(CODE));
        assertEquals(REEL, WebManifest.pk(reelDetails().shortcode));
        assertEquals(0, WebManifest.pk("DE7MnxIgl05x"));
        assertEquals("past a long", 0, WebManifest.pk("_" + CODE.substring(1)));
        assertEquals(0, WebManifest.pk("De7Mn+Igl05"));
        assertEquals(0, WebManifest.pk(""));
        assertEquals(0, WebManifest.pk(null));
    }

    @Test
    public void theWebsiteIsAskedTheWayItsPostPageAsks() throws Exception {
        Website site = new Website();
        site.home.add(homePage(TOKEN));
        site.query.add(answer(reel(WEB)));
        assertEquals(WEB, new WebManifest(site, () -> 1_000L).manifest(REEL, REEL));

        assertEquals(2, site.sent.size());
        Sent home = site.sent.get(0);
        assertEquals(WebManifest.HOME, home.url);
        assertNull("the home page is a GET", home.form);
        Sent query = site.sent.get(1);
        assertEquals(WebManifest.GRAPHQL, query.url);
        for (Sent request : site.sent) {
            // Without all three the query hands back an HTML page.
            assertEquals("same-origin", request.headers.get("Sec-Fetch-Site"));
            assertEquals("cors", request.headers.get("Sec-Fetch-Mode"));
            assertEquals("empty", request.headers.get("Sec-Fetch-Dest"));
            assertEquals(WebManifest.USER_AGENT, request.headers.get("User-Agent"));
        }
        assertEquals(TOKEN, query.headers.get("X-FB-LSD"));
        assertEquals("Csrf42", query.headers.get("X-CSRFToken"));
        assertEquals("the cookies go back without their attributes, and a broken one not at all",
            "csrftoken=Csrf42; mid=Zz9_", query.headers.get("Cookie"));
        assertEquals(WebManifest.APP_ID, query.headers.get("X-IG-App-ID"));
        assertEquals(WebManifest.QUERY, query.headers.get("X-FB-Friendly-Name"));
        assertEquals(TOKEN, query.field("lsd"));
        assertEquals(WebManifest.DOC_ID, query.field("doc_id"));
        assertEquals(WebManifest.QUERY, query.field("fb_api_req_friendly_name"));
        assertEquals("{\"media_id\":\"" + REEL + "\"}", query.field("variables"));
        for (Sent request : site.sent) {
            assertFalse("no account cookie goes out", String.valueOf(request.headers.get("Cookie")).contains("sessionid"));
        }
    }

    @Test
    public void aCarouselPageIsFoundByItsOwnNumber() throws Exception {
        long post = 3900000000000000001L;
        long page = 3900000000000000002L;
        JSONObject media = new JSONObject().put("pk", Long.toString(post)).put("carousel_media", new JSONArray()
            .put(new JSONObject().put("pk", Long.toString(post + 7)).put("video_dash_manifest", APP))
            .put(new JSONObject().put("pk", page).put("video_dash_manifest", WEB)));
        Website site = new Website();
        site.home.add(homePage(TOKEN));
        site.query.add(answer(media));
        WebManifest web = new WebManifest(site, () -> 1_000L);
        assertEquals(WEB, web.manifest(post, page));
        assertEquals("{\"media_id\":\"" + post + "\"}", site.sent.get(1).field("variables"));
        assertNull("a page the post doesn't hold", web.manifest(post, page + 1));
    }

    @Test
    public void theTokenIsKeptForAWhileAndRenewedWhenItStopsWorking() throws Exception {
        Website site = new Website();
        site.home.add(homePage(TOKEN));
        site.query.add(answer(reel(WEB)));
        AtomicLong now = new AtomicLong(1_000L);
        WebManifest web = new WebManifest(site, now::get);
        assertEquals(WEB, web.manifest(REEL, REEL));
        assertEquals(WEB, web.manifest(REEL, REEL));
        assertEquals("a second save reuses the token", 1, site.count(WebManifest.HOME));

        now.addAndGet(WebManifest.SESSION_MS);
        assertEquals(WEB, web.manifest(REEL, REEL));
        assertEquals("an old token is renewed", 2, site.count(WebManifest.HOME));

        // A kept token the website no longer takes gets one fresh try.
        site.query.clear();
        site.query.add(page("<html>Log in</html>"));
        site.query.add(answer(reel(WEB)));
        assertEquals(WEB, web.manifest(REEL, REEL));
        assertEquals(3, site.count(WebManifest.HOME));
        assertEquals(5, site.count(WebManifest.GRAPHQL));
    }

    @Test
    public void aFreshTokenAnsweredWithAPageGivesUpAtOnce() throws Exception {
        Website site = new Website();
        site.home.add(homePage(TOKEN));
        site.query.add(page("<!DOCTYPE html><html>Log in</html>"));
        assertNull(new WebManifest(site, () -> 1_000L).manifest(REEL, REEL));
        assertEquals(1, site.count(WebManifest.HOME));
        assertEquals(1, site.count(WebManifest.GRAPHQL));
    }

    @Test
    public void anythingElseTheWebsiteSendsIsNoManifest() throws Exception {
        Website site = new Website();
        site.home.add(homePage("bad token\""));
        assertNull("a token that isn't plain", new WebManifest(site, () -> 1_000L).manifest(REEL, REEL));
        assertEquals("nothing is asked without a token", 0, site.count(WebManifest.GRAPHQL));

        site = new Website();
        site.home.add(new WebManifest.Reply(302, null, "", null));
        assertNull("a redirect", new WebManifest(site, () -> 1_000L).manifest(REEL, REEL));

        site = new Website();
        site.home.add(homePage(TOKEN));
        site.query.add(new WebManifest.Reply(200, "text/javascript", "{\"data\":{\"xig_polaris_media\":{}}}", null));
        assertNull("a post the website won't show logged out", new WebManifest(site, () -> 1_000L).manifest(REEL, REEL));

        site = new Website();
        site.home.add(homePage(TOKEN));
        site.query.add(answer(reel("")));
        assertNull("a post with no manifest", new WebManifest(site, () -> 1_000L).manifest(REEL, REEL));

        site = new Website();
        site.home.add(homePage(TOKEN));
        site.query.add(new WebManifest.Reply(200, "text/javascript", "for (;;);" + answer(reel(WEB)).body, null));
        assertEquals("the guard some answers start with", WEB, new WebManifest(site, () -> 1_000L).manifest(REEL, REEL));

        Website down = new Website();
        assertNull("no connection", new WebManifest(down, () -> 1_000L).manifest(REEL, REEL));
    }

    @Test
    public void anAnswerPastTheCapIsRefused() throws Exception {
        byte[] small = "{}".getBytes(StandardCharsets.UTF_8);
        assertEquals("{}", WebManifest.read(new ByteArrayInputStream(small)));
        byte[] big = new byte[WebManifest.MAX_BODY + 1];
        try {
            WebManifest.read(new ByteArrayInputStream(big));
            throw new AssertionError("an answer past the cap was read");
        } catch (IOException expected) {
            // refused
        }
    }

    @Test
    public void theWebsitesVp9TakesTheAv1sPlaceWhenItsAsLarge() {
        List<DashManifest.Track> app = DashManifest.parse(APP);
        List<DashManifest.Track> web = DashManifest.parse(WEB);
        assertTrue(WebManifest.wanted(app));
        List<DashManifest.Track> merged = WebManifest.merged(app, web);
        for (DashManifest.Track track : merged) assertFalse("an AV1 picture stayed: " + track, track.codecs.startsWith("av01"));
        assertEquals("both VP9 pictures and both sounds", 4, merged.size());
        DashManifest.Pick pick = DashManifest.pick(merged, true, DownloadQuality.BEST, false);
        assertEquals(WEB_PAIR, pick.video + " + " + pick.audio);

        // A website VP9 smaller than the app's AV1 stays beside it, and the pick keeps the larger.
        List<DashManifest.Track> small = DashManifest.parse(manifest(video(VP9, 720, 1280, 800_000, "w720",
            "cdninstagram.com"), ""));
        merged = WebManifest.merged(app, small);
        assertEquals(app.size() + 1, merged.size());
        assertEquals(1080, DashManifest.bestVideo(merged, true).shortSide());

        List<DashManifest.Track> h264 = DashManifest.parse(manifest(video("avc1.640028", 1080, 1920, 2_000_000,
            "h1080", "cdninstagram.com"), ""));
        assertSame("no VP9, nothing changes", app, WebManifest.merged(app, h264));
    }

    @Test
    public void onlyAnAv1ManifestWithNoVp9AsksTheWebsite() {
        assertFalse("VP9 already there", WebManifest.wanted(DashManifest.parse(manifest(
            video(AV1, 1080, 1920, 1_400_000, "a1080", "fbcdn.net") + video(VP9, 720, 1280, 800_000, "v720", "fbcdn.net"),
            ""))));
        assertFalse("H.264 at the same size wins anyway", WebManifest.wanted(DashManifest.parse(manifest(
            video(AV1, 1080, 1920, 1_400_000, "a1080", "fbcdn.net")
                + video("avc1.640028", 1080, 1920, 2_000_000, "h1080", "fbcdn.net"), ""))));
        assertTrue("a larger AV1 beside H.264", WebManifest.wanted(DashManifest.parse(manifest(
            video(AV1, 1080, 1920, 1_400_000, "a1080", "fbcdn.net")
                + video("avc1.64001f", 720, 1280, 1_200_000, "h720", "fbcdn.net"), ""))));
        assertFalse("no picture at all", WebManifest.wanted(Collections.emptyList()));
    }

    @Test
    public void aSaveWithTheSwitchOnTakesTheWebsitesVp9() throws Exception {
        Website site = new Website();
        site.home.add(homePage(TOKEN));
        site.query.add(answer(reel(WEB)));
        WebManifest.forTests = new WebManifest(site, () -> 1_000L);

        Settings.ASK_FOR_VP9.save(true);
        String report = reelSave(reelDetails());
        assertTrue(report, report.contains("the website offers VP9 of the video, adding its 3 VP9 and sound track(s) "
            + "in place of the app's AV1"));
        assertTrue(report, report.contains("saving the video from its DASH manifest: " + WEB_PAIR));
        assertFalse("an address or the token reached the report: " + report,
            report.contains("cdninstagram.com/") || report.contains(TOKEN) || report.contains("Csrf42"));

        Settings.ASK_FOR_VP9.save(false);
        int asked = site.sent.size();
        report = reelSave(reelDetails());
        assertEquals("the switch off asks nothing", asked, site.sent.size());
        assertTrue(report, report.contains("saving the video from its DASH manifest: video/mp4 " + AV1_SAVED + " 1080x1920"));
    }

    @Test
    public void aStoryHasNoPostToAskAbout() throws Exception {
        Website site = new Website();
        WebManifest.forTests = new WebManifest(site, () -> 1_000L);
        Settings.ASK_FOR_VP9.save(true);
        String report = reelSave(PostDetails.of(Long.toString(REEL)).withPostId(null));
        assertEquals(0, site.sent.size());
        assertTrue(report, report.contains("the manifest of the video is AV1 and it has no public post to ask the website "
            + "for VP9"));
        assertTrue(report, report.contains("saving the video from its DASH manifest: video/mp4 " + AV1_SAVED + " 1080x1920"));
    }

    @Test
    public void aWebsiteThatFailsLeavesTheAppsManifest() throws Exception {
        WebManifest.forTests = new WebManifest(new Website(), () -> 1_000L);
        Settings.ASK_FOR_VP9.save(true);
        String report = reelSave(reelDetails());
        assertTrue(report, report.contains("saving the video from its DASH manifest: video/mp4 " + AV1_SAVED + " 1080x1920"));
    }
}
