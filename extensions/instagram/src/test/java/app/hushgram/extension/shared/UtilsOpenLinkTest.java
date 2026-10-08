/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The shared link opener hands another app a web address with a host, and nothing else. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class UtilsOpenLinkTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void aWebAddressOpensInAnAppThatOpensWebLinks() {
        Application app = RuntimeEnvironment.getApplication();
        Utils.openLink("https://example.org/a?b=1");
        Intent started = shadowOf(app).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://example.org/a?b=1", started.getDataString());
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);

        Utils.openLink("HTTP://example.org/");
        assertEquals("HTTP://example.org/", shadowOf(app).getNextStartedActivity().getDataString());
    }

    @Test
    public void anythingElseIsDropped() {
        Application app = RuntimeEnvironment.getApplication();
        for (String url : new String[]{null, "", "intent://example.org#Intent;scheme=https;end", "javascript:alert(1)",
                "httpxyz://example.org/", "https:///path", "https:example.org", "file:///sdcard/x.html",
                "content://com.instagram.android.provider/x", "instagram://user?username=someone", " https://example.org/"}) {
            Utils.openLink(url);
            assertNull(String.valueOf(url), shadowOf(app).getNextStartedActivity());
        }
    }

    @Test
    public void aWebLinkIsHttpOrHttpsWithAHost() {
        assertTrue(Utils.isWebLink(Uri.parse("https://example.org")));
        assertTrue(Utils.isWebLink(Uri.parse("Http://EXAMPLE.org/x")));
        assertFalse(Utils.isWebLink(null));
        assertFalse(Utils.isWebLink(Uri.parse("https://")));
        assertFalse(Utils.isWebLink(Uri.parse("https:example.org")));
        assertFalse(Utils.isWebLink(Uri.parse("ftp://example.org")));
        assertFalse(Utils.isWebLink(Uri.parse("https2://example.org")));
    }
}
