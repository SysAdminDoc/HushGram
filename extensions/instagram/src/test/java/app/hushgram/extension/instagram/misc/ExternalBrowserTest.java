/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;

import com.facebook.browser.iabcontext.IABAdsContext;
import com.facebook.browser.iabcontext.IABOrganicContext;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/** Which links leave Instagram's in-app browser, and where they go. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ExternalBrowserTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restoreSwitches() {
        Settings.OPEN_LINKS_EXTERNALLY.resetToDefault();
        Settings.SANITIZE_SHARING_LINKS.resetToDefault();
    }

    private static Activity browserWith(String url) {
        return browserWith(url, null);
    }

    /** The in-app browser as Instagram's launchers start it: the link as the data, the link's context in the extras. */
    private static Activity browserWith(String url, Parcelable linkContext) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (linkContext != null) intent.putExtra("EXTRA_IAB_CONTEXT", linkContext);
        return Robolectric.buildActivity(Activity.class, intent).create().get();
    }

    private static void assertStays(String url, Activity browser) {
        assertFalse(url, ExternalBrowser.redirect(browser, browser.getIntent()));
        assertNull(url, shadowOf(browser).getNextStartedActivity());
        assertFalse(url + ": the in-app browser closed with no link opened elsewhere", browser.isFinishing());
    }

    /** A bio link comes wrapped in Instagram's click tracker; the page goes out, the tracker doesn't. */
    @Test
    public void aBioLinkOpensItsPageInTheDefaultBrowser() {
        Activity browser = browserWith("https://l.instagram.com/?u=https%3A%2F%2Fexample.org%2Fshop%3Fitem%3D4&e=AT0x",
                new IABOrganicContext());

        assertTrue(ExternalBrowser.redirect(browser, browser.getIntent()));
        Intent started = shadowOf(browser).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://example.org/shop?item=4", started.getDataString());
        assertTrue("only an app that opens web links takes it", started.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertTrue("the link lives in a task of its own", (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("the in-app browser closes behind the link", browser.isFinishing());
    }

    /** With Sanitize sharing links off, nothing unwraps the tracker on the way in, and the redirect still does. */
    @Test
    public void theTrackerComesOffWhateverTheSharingSwitchSays() {
        Settings.SANITIZE_SHARING_LINKS.save(false);
        Activity browser = browserWith("https://l.instagram.com/?u=https%3A%2F%2Fexample.org%2F&e=AT0x");

        assertTrue(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertEquals("https://example.org/", shadowOf(browser).getNextStartedActivity().getDataString());
    }

    /**
     * A URL's scheme is case-insensitive (RFC 3986). A destination written HTTPS:// goes out as it
     * was written.
     */
    @Test
    public void anUppercaseSchemeGoesOut() {
        Activity browser = browserWith("https://l.instagram.com/?u=HTTPS%3A%2F%2Fexample.org%2F&e=AT0x");

        assertTrue(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertEquals("HTTPS://example.org/", shadowOf(browser).getNextStartedActivity().getDataString());
    }

    /**
     * The shim's u is decoded once, as a browser would, and fbclid leaves the destination: an
     * escaped percent inside it stays escaped, and every other key stays as it came, the utm keys
     * the site may read included.
     */
    @Test
    public void theDestinationLeavesWithoutFbclidAndIsDecodedOnce() {
        Activity browser = browserWith("https://l.instagram.com/?u=https%3A%2F%2Fexample.org%2Fa%3Fb%3D1"
                + "%26fbclid%3DPAZXh0bgNhZW0%26utm_source%3Dig%26c%3D%2525%23part&e=AT0x");

        assertTrue(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertEquals("https://example.org/a?b=1&utm_source=ig&c=%25#part",
                shadowOf(browser).getNextStartedActivity().getDataString());
    }

    /** A link that isn't wrapped goes out as it came, apart from fbclid. */
    @Test
    public void anUnwrappedLinkGoesOutAsItCame() {
        Activity browser = browserWith("http://example.org/a?fbclid=x&b=2");

        assertTrue(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertEquals("http://example.org/a?b=2", shadowOf(browser).getNextStartedActivity().getDataString());
    }

    /**
     * Instagram's, Facebook's, Messenger's, Meta's and Threads' pages need the in-app browser's
     * bridges, and a shim that forwards to one of them stays too, wrapped as it came.
     */
    @Test
    public void metasOwnPagesStayInTheApp() {
        for (String url : new String[]{
                "https://www.instagram.com/accounts/login/",
                "https://help.instagram.com/581066165581870",
                "https://instagr.am/p/abc/",
                "https://ig.me/m/someone",
                "https://www.facebook.com/help/1234",
                "https://fb.me/xyz",
                "https://www.messenger.com/t/1",
                "https://about.meta.com/",
                "https://www.threads.net/@someone",
                "https://www.threads.com/@someone",
                "https://www.instagram.com./p/abc/",
                "https://l.instagram.com/?u=https%3A%2F%2Fwww.instagram.com%2Fp%2Fabc%2F&e=AT0x",
                "https://l.instagram.com/?e=AT0x"}) {
            assertStays(url, browserWith(url));
        }
    }

    /** The dot keeps a look-alike from passing for Instagram. */
    @Test
    public void aLookAlikeHostGoesOut() {
        Activity browser = browserWith("https://notinstagram.com/x");

        assertTrue(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertEquals("https://notinstagram.com/x", shadowOf(browser).getNextStartedActivity().getDataString());
    }

    /** Instagram's ad launcher gives the browser an IABAdsContext, and an ad stays where Instagram opened it. */
    @Test
    public void anAdStaysInTheApp() {
        String url = "https://l.instagram.com/?u=https%3A%2F%2Fexample.org%2F&e=AT0x";
        assertStays(url, browserWith(url, new IABAdsContext()));
    }

    /** A link followed inside the browser reaches onNewIntent with no context; Instagram copies the page's onto it later. */
    private static Intent followedLink(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    /** A link followed inside an ad's page belongs to the ad, so it stays and the ad stays open. */
    @Test
    public void anAdsFollowUpLinkStaysInTheApp() {
        Activity browser = browserWith("https://example.org/landing", new IABAdsContext());

        assertFalse(ExternalBrowser.redirect(browser, followedLink("https://example.org/checkout")));
        assertNull(shadowOf(browser).getNextStartedActivity());
        assertFalse("the ad's browser stays open", browser.isFinishing());
    }

    /** A link of its own, opened organically while an ad's browser is up, carries its context and goes out. */
    @Test
    public void aNewOrganicLinkInAnAdsBrowserGoesOut() {
        Activity browser = browserWith("https://example.org/landing", new IABAdsContext());
        Intent link = followedLink("https://example.net/bio");
        link.putExtra("EXTRA_IAB_CONTEXT", new IABOrganicContext());

        assertTrue(ExternalBrowser.redirect(browser, link));
        assertEquals("https://example.net/bio", shadowOf(browser).getNextStartedActivity().getDataString());
    }

    /** A link followed inside an organic page goes out like the page did. */
    @Test
    public void aFollowUpLinkInAnOrganicBrowserGoesOut() {
        Activity browser = browserWith("https://example.org/", new IABOrganicContext());

        assertTrue(ExternalBrowser.redirect(browser, followedLink("https://example.org/next")));
        assertEquals("https://example.org/next", shadowOf(browser).getNextStartedActivity().getDataString());
    }

    /** Only http and https leave. */
    @Test
    public void otherSchemesStayInTheApp() {
        for (String url : new String[]{"instagram://user?username=someone", "mailto:someone@example.org",
                "intent://example.org#Intent;scheme=https;end", "javascript:alert(1)"}) {
            assertStays(url, browserWith(url));
        }
    }

    /**
     * Instagram's click tracker takes any address in its u, and a bio, a caption or a message can
     * put one there. Only a web address with a host comes out of it: the tracker wrapping anything
     * else stays in the in-app browser, as do a scheme that only starts with http and an address
     * with no host.
     */
    @Test
    public void aTrackerWrappingAnythingButAWebPageStaysInTheApp() {
        for (String url : new String[]{
                "https://l.instagram.com/?u=intent%3A%2F%2Fexample.org%23Intent%3Bscheme%3Dhttps%3Bend&e=AT0x",
                "https://l.instagram.com/?u=javascript%3Aalert(1)&e=AT0x",
                "https://l.instagram.com/?u=httpxyz%3A%2F%2Fexample.org%2F&e=AT0x",
                "https://l.instagram.com/?u=https%3A%2F%2F%2Fpath&e=AT0x",
                "https://l.instagram.com/?u=https%3Aexample.org&e=AT0x",
                "https://l.facebook.com/?u=https%3A%2F%2Fl.instagram.com%2F%3Fu%3Dintent%253A%252F%252Fx%2523Intent%253Bend&e=1",
                "httpxyz://example.org/",
                "https:///path",
                "https:example.org",
        }) {
            assertStays(url, browserWith(url, new IABOrganicContext()));
        }
    }

    /** Lower-cased in the phone's language, Turkish turned the I of INSTAGRAM into a dotless one. */
    @Test
    public void hostsCompareTheSameInTurkish() {
        Locale before = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            assertStays("https://INSTAGRAM.COM/x", browserWith("https://INSTAGRAM.COM/x"));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void theSwitchKeepsEveryLinkInTheApp() {
        Settings.OPEN_LINKS_EXTERNALLY.save(false);
        assertStays("https://example.org/", browserWith("https://example.org/"));
    }

    /**
     * Instagram's in-app browser on a phone where no app opens web links. The message quotes the
     * link with its scheme and, the way some Android versions print an intent, without one: only
     * leaving the message out keeps both out of the report.
     */
    public static final class NoBrowserAround extends Activity {
        @Override
        public void startActivity(Intent intent) {
            throw new ActivityNotFoundException("No Activity found to handle Intent { act=android.intent.action.VIEW "
                    + "dat=https://example.org/private/path host=example.org/private/path flg=0x10000000 }");
        }
    }

    /**
     * A link no browser took stays in the app and says so in the report, by the exception's class
     * alone: its message quotes the intent, and the intent carries the link.
     */
    @Test
    public void aLinkNoBrowserTookReachesTheReportWithoutTheLink() {
        LogBufferManager.clearLogBuffer();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/private/path"));
            Activity browser = Robolectric.buildActivity(NoBrowserAround.class, intent).create().get();

            assertFalse(ExternalBrowser.redirect(browser, browser.getIntent()));
            assertFalse("the in-app browser closed with no link opened elsewhere", browser.isFinishing());

            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("No external browser took the link (ActivityNotFoundException)"));
            assertTrue(report, report.contains("Open links in external browser: invoked"));
            assertFalse(report, report.contains("example.org"));
            assertFalse(report, report.contains("private/path"));
        } finally {
            LogBufferManager.clearLogBuffer();
        }
    }
}
