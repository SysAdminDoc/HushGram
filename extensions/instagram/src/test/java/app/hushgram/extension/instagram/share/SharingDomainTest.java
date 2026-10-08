/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
import app.hushgram.extension.shared.settings.PauseForTests;

/** Sharing domain: which links move to the picked domain, and what the row takes as a domain. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SharingDomainTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SANITIZE_SHARING_LINKS.save(true);
        Settings.SHARING_DOMAIN.resetToDefault();
    }

    @After
    public void restore() {
        Settings.SANITIZE_SHARING_LINKS.resetToDefault();
        Settings.SHARING_DOMAIN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test
    public void aDomainIsTakenBareAndAnythingElseIsRefused() {
        assertEquals("", SharingDomain.normalize(null));
        assertEquals("", SharingDomain.normalize("   "));
        assertEquals("example.com", SharingDomain.normalize("example.com"));
        assertEquals("example.com", SharingDomain.normalize("  Example.COM "));
        assertEquals("example.com", SharingDomain.normalize("https://example.com/"));
        assertEquals("example.com", SharingDomain.normalize("http://example.com"));
        assertEquals("example.com", SharingDomain.normalize("example.com."));
        assertEquals("www.my-mirror.example", SharingDomain.normalize("www.my-mirror.example"));
        assertEquals("xn--bcher-kva.example", SharingDomain.normalize("xn--bcher-kva.example"));

        assertNull("one label", SharingDomain.normalize("localhost"));
        assertNull("a path", SharingDomain.normalize("example.com/p"));
        assertNull("a port", SharingDomain.normalize("example.com:8080"));
        assertNull("a user", SharingDomain.normalize("me@example.com"));
        assertNull("a space", SharingDomain.normalize("exam ple.com"));
        assertNull("another scheme", SharingDomain.normalize("javascript://example.com"));
        assertNull("an empty label", SharingDomain.normalize("example..com"));
        assertNull("a hyphen at an end", SharingDomain.normalize("-example.com"));
        assertNull("an IP address", SharingDomain.normalize("192.168.1.10"));
        assertNull("a query", SharingDomain.normalize("example.com?x=1"));
    }

    @Test
    public void onlyInstagramComLinksMoveAndKeepEverythingButTheHost() {
        String to = "example.com";
        assertEquals("https://example.com/p/ABC123/", SharingDomain.moved("https://www.instagram.com/p/ABC123/", to));
        assertEquals("https://example.com/reel/XYZ/?a=1#top", SharingDomain.moved("https://instagram.com/reel/XYZ/?a=1#top", to));
        assertEquals("http://example.com/someone", SharingDomain.moved("http://WWW.Instagram.com/someone", to));
        assertEquals("https://example.com", SharingDomain.moved("https://www.instagram.com", to));
        assertEquals("https://example.com?igsh=1", SharingDomain.moved("https://instagram.com.?igsh=1", to));

        for (String kept : new String[]{
                "https://l.instagram.com/?u=https%3A%2F%2Fexample.org",
                "https://ig.me/m/someone",
                "https://instagr.am/p/ABC/",
                "https://help.instagram.com/123",
                "https://notinstagram.com/p/ABC/",
                "https://www.instagram.com.evil.example/p/ABC/",
                "https://www.instagram.com:443/p/ABC/",
                "https://me@www.instagram.com/p/ABC/",
                "ftp://www.instagram.com/p/ABC/",
                "instagram://media?id=1",
                "www.instagram.com/p/ABC/",
        }) {
            assertEquals(kept, kept, SharingDomain.moved(kept, to));
        }
        assertEquals("blank keeps the link", "https://www.instagram.com/p/A/", SharingDomain.moved("https://www.instagram.com/p/A/", ""));
        assertNull(SharingDomain.moved(null, to));
    }

    @Test
    public void theSavedDomainIsReadNormalizedAndAnythingElseReadsBlank() {
        assertEquals("", SharingDomain.chosen());
        Settings.SHARING_DOMAIN.save(" https://Example.com/ ");
        assertEquals("example.com", SharingDomain.chosen());
        Settings.SHARING_DOMAIN.save("not a domain");
        assertEquals("", SharingDomain.chosen());
        Settings.SHARING_DOMAIN.save("example.com");
        SettingsContextRule.withoutContext(() -> assertEquals("", SharingDomain.chosen()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertEquals("", SharingDomain.chosen()));
        assertEquals("example.com", SharingDomain.chosen());
    }
}
