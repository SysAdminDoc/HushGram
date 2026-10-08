/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the address of a page loses when the in-app browser's menu shares or copies it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BrowserLinkTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /**
     * Shaped like the Reels ad page a test phone's Share via... handed out on 2026-10-05: every
     * utm_ key, two keys the advertiser named itself, and Meta's click id last. The values are
     * made up.
     */
    private static final String AD_PAGE = "https://shop.example.com/spring/?utm_source=ig&utm_medium=paid"
            + "&utm_campaign=120200000000&utm_content=120200000001&utm_id=120200000002&utm_term=120200000003"
            + "&Marketing+Channel=Instagram&Ad+Set=Spring&fbclid=PAZXh0bgNhZW0BMABhZGlkAasT";

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SANITIZE_SHARING_LINKS.save(true);
    }

    @After
    public void restore() {
        Settings.SANITIZE_SHARING_LINKS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test
    public void anAdPageLosesItsClickIdAndUtmKeysAndKeepsTheRest() {
        assertEquals("https://shop.example.com/spring/?Marketing+Channel=Instagram&Ad+Set=Spring",
                LinkCleaner.browserLink(AD_PAGE));
    }

    @Test
    public void everyUtmKeyGoesHoweverItsWritten() {
        assertEquals("https://example.org/a?b=1", LinkCleaner.browserLink("https://example.org/a?utm_placement=feed&b=1&UTM_Source=ig"));
        assertEquals("encoded", "https://example.org/a?b=1", LinkCleaner.browserLink("https://example.org/a?utm%5Fsource=ig&b=1"));
        assertEquals("the fragment stays", "https://example.org/a#top", LinkCleaner.browserLink("https://example.org/a?fbclid=x#top"));
        assertEquals("the only key", "http://example.org/", LinkCleaner.browserLink("http://example.org/?utm_source=ig"));
    }

    @Test
    public void anInstagramPageLosesInstagramsKeysToo() {
        assertEquals("https://www.instagram.com/p/C1/",
                LinkCleaner.browserLink("https://www.instagram.com/p/C1/?igsh=xyz&utm_source=ig_web_copy_link"));
    }

    /** A page with none of these keys, the usual case, comes back as the very same string. */
    @Test
    public void anOrdinaryPageComesBackAsItCame() {
        for (String page : new String[]{
                "https://example.org/article?id=7#top",
                "https://example.org/?utmost=1&fbclid2=2&q=utm_source",
                "https://example.org/",
                "",
        }) {
            assertSame(page, LinkCleaner.browserLink(page));
        }
        assertNull(LinkCleaner.browserLink(null));
    }

    /** Off, paused or before the settings are ready, the menu hands out the address as Instagram wrote it. */
    @Test
    public void offOrPausedTheAddressStays() {
        Settings.SANITIZE_SHARING_LINKS.save(false);
        assertSame(AD_PAGE, LinkCleaner.browserLink(AD_PAGE));
        Settings.SANITIZE_SHARING_LINKS.save(true);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(AD_PAGE, LinkCleaner.browserLink(AD_PAGE));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame(AD_PAGE, LinkCleaner.browserLink(AD_PAGE)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame(AD_PAGE, LinkCleaner.browserLink(AD_PAGE)));
        assertEquals("https://shop.example.com/spring/?Marketing+Channel=Instagram&Ad+Set=Spring",
                LinkCleaner.browserLink(AD_PAGE));
    }

    /** The share sheet and Copy link stand-ins still leave a link to another site alone. */
    @Test
    public void elsewhereInInstagramAnotherSitesLinkKeepsItsKeys() {
        String page = "https://example.org/a?utm_source=ig&fbclid=x";
        assertSame(page, LinkCleaner.cleanText(page));
    }
}
