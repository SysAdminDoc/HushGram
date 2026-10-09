/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowActivity;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/**
 * What a shared Instagram link loses. The first two links are what Copy link and the share
 * sheet gave on Instagram 449 on a phone, 2026-09-29: a per-share stkn and no igsh.
 */
@RunWith(RobolectricTestRunner.class)
public class LinkCleanerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void copyLinkOn449LosesItsShareToken() {
        assertEquals("https://www.instagram.com/p/DdbkeGLCNhO/",
                LinkCleaner.clean("https://www.instagram.com/p/DdbkeGLCNhO/?stkn=MWJkaHlkendyazhjbQ=="));
    }

    @Test
    public void shareSheetOn449LosesItsShareToken() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://www.instagram.com/p/DdzLinuo3pE/?stkn=NzlxbDd5dGp2bHl5");
        assertEquals("https://www.instagram.com/p/DdzLinuo3pE/",
                LinkCleaner.sanitizedShare(share).getStringExtra(Intent.EXTRA_TEXT));
    }

    @Test
    public void clipboardCopyLosesItsShareToken() {
        ClipData clip = ClipData.newPlainText("link", "https://www.instagram.com/reel/C1/?stkn=abc&igsh=xyz");
        assertEquals("https://www.instagram.com/reel/C1/",
                LinkCleaner.sanitizedClip(clip).getItemAt(0).getText().toString());
    }

    /** WhatsApp's button in Instagram's share sheet: an ACTION_SEND for one package, no chooser. */
    @Test
    public void aShareSentStraightToOneAppLosesItsTrackingKeys() {
        Application app = RuntimeEnvironment.getApplication();
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.whatsapp")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_TEXT, "https://www.instagram.com/p/C1/?stkn=abc&igsh=xyz");
        LinkCleaner.startActivity(app, share);
        Intent started = shadowOf(app).getNextStartedActivity();
        assertEquals("com.whatsapp", started.getPackage());
        assertEquals("https://www.instagram.com/p/C1/", started.getStringExtra(Intent.EXTRA_TEXT));
    }

    @Test
    public void aShareStartedWithOptionsKeepsThemAndLosesItsTrackingKeys() {
        Application app = RuntimeEnvironment.getApplication();
        Bundle options = new Bundle();
        options.putString("marker", "kept");
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_TEXT, "Look https://instagram.com/someone?igsh=xyz");
        LinkCleaner.startActivity(app, share, options);
        ShadowActivity.IntentForResult started = shadowOf(app).getNextStartedActivityForResult();
        assertEquals("Look https://instagram.com/someone", started.intent.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals("kept", started.options.getString("marker"));
    }

    /**
     * The SMS button in Instagram's share sheet opens {@code sms:} with the link in sms_body. With
     * the switch off, 449 sent a reel's link with its stkn this way (S22, 2026-09-29).
     */
    @Test
    public void aTextMessageLosesItsTrackingKeys() {
        Application app = RuntimeEnvironment.getApplication();
        Intent message = new Intent(Intent.ACTION_VIEW, Uri.parse("sms:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("sms_body", "https://www.instagram.com/reel/DdyHxJPOEyo/?stkn=MWdqeHZvbnF2OGY0bw==");
        LinkCleaner.startActivity(app, message);
        Intent started = shadowOf(app).getNextStartedActivity();
        assertEquals("sms:", started.getDataString());
        assertEquals("https://www.instagram.com/reel/DdyHxJPOEyo/", started.getStringExtra("sms_body"));
    }

    @Test
    public void aTextMessageKeepsItsKeysWhileTheSwitchIsOff() {
        Settings.SANITIZE_SHARING_LINKS.save(false);
        try {
            String link = "https://www.instagram.com/reel/DdyHxJPOEyo/?stkn=MWdqeHZvbnF2OGY0bw==";
            Intent message = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:5551234")).putExtra("sms_body", link);
            assertEquals(link, LinkCleaner.sanitizedStart(message).getStringExtra("sms_body"));
        } finally {
            Settings.SANITIZE_SHARING_LINKS.save(true);
        }
    }

    /** Only a share changes. Anything else Instagram starts keeps its text, links and all. */
    @Test
    public void anythingElseStartedGoesAsItCame() {
        Application app = RuntimeEnvironment.getApplication();
        String link = "https://www.instagram.com/p/C1/?igsh=xyz";
        Intent view = new Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_TEXT, link);
        LinkCleaner.startActivity(app, view);
        assertEquals(link, shadowOf(app).getNextStartedActivity().getStringExtra(Intent.EXTRA_TEXT));
    }

    /** A profile's Copy link on 449 with the switch off, seen on a phone, 2026-09-29. */
    @Test
    public void aProfileLinkLosesItsShareToken() {
        assertEquals("https://www.instagram.com/matt24256",
                LinkCleaner.clean("https://www.instagram.com/matt24256?stkn=MTB5YXBwdm41bzM2dg=="));
    }

    /**
     * A bio link on 449 starts the in-app browser with an l.instagram.com address as its data, seen
     * on a phone, 2026-09-29. It opens the page itself, with the browser's own extras kept.
     */
    @Test
    public void aBioLinkOpensItsPageNotInstagramsClickTracker() {
        Application app = RuntimeEnvironment.getApplication();
        ComponentName browser = new ComponentName(app, "com.instagram.inappbrowser.fragments.BrowserLiteInMainProcessIGActivity");
        Intent open = new Intent().setComponent(browser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(Uri.parse("https://l.instagram.com/?u=https%3A%2F%2Fvisitstore.bio%2Fnatgeo%3Fref%3Dig&e=AT0abc"))
                .putExtra("marker", "kept");
        LinkCleaner.startActivity(app, open);
        Intent started = shadowOf(app).getNextStartedActivity();
        assertEquals("https://visitstore.bio/natgeo?ref=ig", started.getDataString());
        assertEquals(browser, started.getComponent());
        assertEquals("kept", started.getStringExtra("marker"));
    }

    @Test
    public void facebookShimsInstagramKnowsOpenTheirPageToo() {
        assertEquals("https://example.com/a", unwrapped("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2Fa&h=x"));
        assertEquals("https://example.com/b", unwrapped(
                "https://L.Instagram.com/?u=https%3A%2F%2Fl.facebook.com%2Fl.php%3Fu%3Dhttps%253A%252F%252Fexample.com%252Fb"));
    }

    /** Only a shim forwarding to a web page is skipped; anything else opens as Instagram asked. */
    @Test
    public void everythingElseOpensAsItCame() {
        String[] kept = {
                "https://l.instagram.com/?u=javascript%3Aalert(1)&e=x",
                "https://l.instagram.com/?e=x",
                "https://www.instagram.com/?u=https%3A%2F%2Fexample.com",
                "https://notl.instagram.com/?u=https%3A%2F%2Fexample.com",
                "instagram://user?username=someone",
        };
        for (String link : kept) assertEquals(link, unwrapped(link));
    }

    @Test
    public void withTheSwitchOffTheShimStays() {
        Settings.SANITIZE_SHARING_LINKS.save(false);
        try {
            String shim = "https://l.instagram.com/?u=https%3A%2F%2Fexample.com&e=x";
            assertEquals(shim, unwrapped(shim));
        } finally {
            Settings.SANITIZE_SHARING_LINKS.save(true);
        }
    }

    private static String unwrapped(String link) {
        return LinkCleaner.sanitizedStart(new Intent(Intent.ACTION_VIEW, Uri.parse(link))).getDataString();
    }

    @Test
    public void theOlderKeysGoAndEverythingElseStaysInOrder() {
        assertEquals("https://instagram.com/stories/someone/123?hl=en&x=1#top",
                LinkCleaner.clean("https://instagram.com/stories/someone/123?igsh=a&hl=en&utm_source=ig_story_item_share"
                        + "&x=1&igshid=b&fbclid=c#top"));
    }

    /*
     * Instagram 450 (385611438, 385611440), 2026-10-08: the per-share id came back under a random
     * four-letter key, with base64 of a short [a-z0-9] id as its value. Codes and ids here are made up.
     */
    @Test
    public void theShareIdUnderARotatingKeyGoesOnEveryShape() {
        assertEquals("https://www.instagram.com/p/Abc123xyz_Q/",
                LinkCleaner.clean("https://www.instagram.com/p/Abc123xyz_Q/?obrf=a2k3NmE5MnUzcGFi"));
        assertEquals("https://www.instagram.com/p/Abc123xyz_Q/",
                LinkCleaner.clean("https://www.instagram.com/p/Abc123xyz_Q/?exln=a2k3NmE5MnUzcGFi"));
        assertEquals("https://www.instagram.com/p/Abc123xyz_Q-AbCdEfGhI-JkLmNoP-QrStUvWxYz012/",
                LinkCleaner.clean("https://www.instagram.com/p/Abc123xyz_Q-AbCdEfGhI-JkLmNoP-QrStUvWxYz012/?dlrf=ejVjNGdpY2lpNnBv"));
        assertEquals("https://www.instagram.com/reel/Abc123xyz_Q/",
                LinkCleaner.clean("https://www.instagram.com/reel/Abc123xyz_Q/?mdxt=MXY5Z21sZmQwZnZldg=="));
        assertEquals("https://www.instagram.com/someuser",
                LinkCleaner.clean("https://www.instagram.com/someuser?obrf=MXJwNGhqa3U4OG84ZA=="));
    }

    @Test
    public void aShareIdWithEscapedPaddingGoesToo() {
        // %3D is "=": a 13 or 14 character id always pads, so the link carries it escaped.
        assertEquals("https://www.instagram.com/reel/Abc123xyz_Q/",
                LinkCleaner.clean("https://www.instagram.com/reel/Abc123xyz_Q/?obrf=MXY5Z21sZmQwZnZldg%3D%3D"));
        assertEquals("https://www.instagram.com/reel/Abc123xyz_Q/?img_index=2",
                LinkCleaner.clean("https://www.instagram.com/reel/Abc123xyz_Q/?img_index=2&obrf=MXY5Z21sZmQwZnZldg%3d%3d"));
    }

    @Test
    public void aValueWithAMalformedEscapeStays() {
        for (String pair : new String[] {"obrf=MXY5Z21sZmQwZnZldg%G1", "obrf=MXY5Z21sZmQwZnZldg%3", "obrf=MXY5Z21sZmQwZnZldg%"}) {
            String link = "https://www.instagram.com/p/Abc123xyz_Q/?" + pair;
            assertEquals(pair, link, LinkCleaner.clean(link));
        }
    }

    @Test
    public void cleaningTheRotatingKeyTwiceChangesNothing() {
        String once = LinkCleaner.clean("https://www.instagram.com/reel/Abc123xyz_Q/?mdxt=MXY5Z21sZmQwZnZldg==");
        assertEquals(once, LinkCleaner.clean(once));
    }

    @Test
    public void theRotatingKeyGoesAndTheOtherPairsStay() {
        assertEquals("https://www.instagram.com/p/Abc123xyz_Q/?img_index=2",
                LinkCleaner.clean("https://www.instagram.com/p/Abc123xyz_Q/?img_index=2&obrf=a2k3NmE5MnUzcGFi"));
        assertEquals("https://www.instagram.com/p/Abc123xyz_Q/?img_index=2&hl=en#c",
                LinkCleaner.clean("https://www.instagram.com/p/Abc123xyz_Q/?img_index=2&obrf=a2k3NmE5MnUzcGFi&hl=en#c"));
    }

    @Test
    public void aLookAlikePairOnAnotherSiteStays() {
        String other = "https://example.com/p/Abc123xyz_Q/?obrf=a2k3NmE5MnUzcGFi";
        assertEquals(other, LinkCleaner.clean(other));
    }

    @Test
    public void aFourLetterKeyWithoutABase64IdStays() {
        // Not base64, base64 of something with capitals, too short once decoded, a real key.
        for (String pair : new String[] {"obrf=hello", "obrf=QWJjMTIzeHl6UTEy", "obrf=YWJj", "obrf=a2k3NmE5MnUzcGFi!",
                "obrf=", "OBRF=a2k3NmE5MnUzcGFi", "obrfx=a2k3NmE5MnUzcGFi", "next=a2k3NmE5MnUzcGFi"}) {
            String link = "https://www.instagram.com/p/Abc123xyz_Q/?" + pair;
            assertEquals(pair, link, LinkCleaner.clean(link));
        }
    }

    @Test
    public void theShareSheetTextWithARotatingKeyIsCleaned() {
        assertEquals("Look at this https://www.instagram.com/p/Abc123xyz_Q/ and that.",
                LinkCleaner.cleanText("Look at this https://www.instagram.com/p/Abc123xyz_Q/?obrf=a2k3NmE5MnUzcGFi and that."));
    }

    @Test
    public void everyInstagramHostIsCleaned() {
        assertEquals("https://instagr.am/p/C1/", LinkCleaner.clean("https://instagr.am/p/C1/?stkn=abc"));
        assertEquals("https://ig.me/m/someone", LinkCleaner.clean("https://ig.me/m/someone?igsh=abc"));
    }

    @Test
    public void otherSitesAreLeftAlone() {
        String link = "https://example.com/p/C1/?stkn=abc&igsh=xyz";
        assertEquals(link, LinkCleaner.clean(link));
    }

    @Test
    public void aCleanLinkComesBackAsTheSameString() {
        String link = "https://www.instagram.com/p/C1/?hl=en";
        assertSame(link, LinkCleaner.clean(link));
    }

    @Test
    public void linksInsideSharedTextAreCleanedAndTheTextKept() {
        assertEquals("Look at this https://www.instagram.com/p/C1/ and that.",
                LinkCleaner.cleanText("Look at this https://www.instagram.com/p/C1/?stkn=abc and that."));
    }

    /** With a Sharing domain set, a shared or copied link to instagram.com goes out on it, without its tracking keys. */
    @Test
    public void sharedAndCopiedLinksMoveToTheSharingDomain() {
        Settings.SHARING_DOMAIN.save("example.com");
        try {
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "Look https://www.instagram.com/reel/C1/?igsh=abc&utm_source=ig_web_copy_link. And https://example.org/?igsh=1");
            assertEquals("Look https://example.com/reel/C1/. And https://example.org/?igsh=1",
                    LinkCleaner.sanitizedShare(share).getStringExtra(Intent.EXTRA_TEXT));
            ClipData clip = ClipData.newPlainText("link", "https://instagram.com/p/C1/?stkn=abc");
            assertEquals("https://example.com/p/C1/", LinkCleaner.sanitizedClip(clip).getItemAt(0).getText().toString());
            assertEquals("Instagram's other hosts stay", "https://ig.me/m/someone", LinkCleaner.cleanText("https://ig.me/m/someone?igsh=a"));
            assertEquals("a link the server hands out keeps its host", "https://www.instagram.com/p/C1/",
                    LinkCleaner.sanitizeShared("https://www.instagram.com/p/C1/?stkn=abc"));
        } finally {
            Settings.SHARING_DOMAIN.resetToDefault();
        }
    }

    /** With Sanitize sharing links off, a Sharing domain changes nothing. */
    @Test
    public void theSharingDomainWaitsForSanitizeSharingLinks() {
        Settings.SHARING_DOMAIN.save("example.com");
        Settings.SANITIZE_SHARING_LINKS.save(false);
        try {
            String link = "https://www.instagram.com/p/C1/?stkn=abc";
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link);
            assertEquals(link, LinkCleaner.sanitizedShare(share).getStringExtra(Intent.EXTRA_TEXT));
            ClipData clip = ClipData.newPlainText("link", link);
            assertSame(clip, LinkCleaner.sanitizedClip(clip));
        } finally {
            Settings.SANITIZE_SHARING_LINKS.resetToDefault();
            Settings.SHARING_DOMAIN.resetToDefault();
        }
    }

    /** A tracker that forwards to anything but a web page opens as it came, never as the address inside it. */
    @Test
    public void aTrackerWrappingAnotherSchemeOpensAsItCame() {
        Application app = RuntimeEnvironment.getApplication();
        for (String shim : new String[]{
                "https://l.instagram.com/?u=intent%3A%2F%2Fexample.org%23Intent%3Bscheme%3Dhttps%3Bend&e=AT0x",
                "https://l.instagram.com/?u=javascript%3Aalert(1)&e=AT0x",
                "https://l.instagram.com/?u=httpxyz%3A%2F%2Fexample.org%2F&e=AT0x",
        }) {
            Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(shim)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            LinkCleaner.startActivity(app, open);
            assertEquals(shim, shadowOf(app).getNextStartedActivity().getDataString());
        }
    }
}
