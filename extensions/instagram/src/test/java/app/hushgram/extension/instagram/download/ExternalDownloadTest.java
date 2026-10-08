/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Send downloads to another app: the link each Download hands off, and the save it stands in for. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ExternalDownloadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (MediaSave.savesInFlight() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        MediaSave.policyForTests = null;
        Settings.SEND_DOWNLOADS_TO_APP.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Post.id = null;
        Post.name = null;
        HookStatus.clear();
    }

    /** A short code is the pk in base 64, Instagram's alphabet, with no leading zero digit. */
    @Test
    public void aShortCodeIsThePkInBase64() {
        assertEquals("B", ExternalDownload.shortcode("1"));
        assertEquals("_", ExternalDownload.shortcode("63"));
        assertEquals("BA", ExternalDownload.shortcode("64_1"));
        assertEquals("C_4-0q4Vmw1", ExternalDownload.shortcode("3456789012345678901_25025320"));
        assertEquals("H__________", ExternalDownload.shortcode(String.valueOf(Long.MAX_VALUE)));
        for (String id : new String[]{null, "", "_1", "0_1", "-5_1", "12a_1", "99999999999999999999_1", " 1_2"}) {
            assertNull(id, ExternalDownload.shortcode(id));
        }
    }

    @Test
    @Config(shadows = Post.class)
    public void eachSurfaceGetsItsLink() {
        Post.id = "3456789012345678901_25025320";
        Post.name = "some.one_2";
        assertEquals("https://www.instagram.com/p/C_4-0q4Vmw1/", ExternalDownload.postLink(new Object(), false));
        assertEquals("https://www.instagram.com/reel/C_4-0q4Vmw1/", ExternalDownload.postLink(new Object(), true));
        assertEquals("https://www.instagram.com/stories/some.one_2/3456789012345678901/", ExternalDownload.storyLink(new Object()));

        Post.name = "../x";
        assertNull("a name that isn't one", ExternalDownload.storyLink(new Object()));
        Post.name = null;
        assertNull(ExternalDownload.storyLink(new Object()));
        Post.id = null;
        assertNull(ExternalDownload.postLink(new Object(), false));
    }

    /** On, the link goes to Android's share sheet and counts. Off, paused or with no link, nothing opens. */
    @Test
    public void theLinkGoesToTheShareSheetOnlyWhenOn() {
        Application application = RuntimeEnvironment.getApplication();
        String link = "https://www.instagram.com/p/B/";
        assertFalse("starts off", Settings.SEND_DOWNLOADS_TO_APP.get());
        assertFalse(ExternalDownload.handOff(application, link, FamilyNames.REEL_DOWNLOAD));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());

        Settings.SEND_DOWNLOADS_TO_APP.save(true);
        assertFalse("no link", ExternalDownload.handOff(application, null, FamilyNames.REEL_DOWNLOAD));
        assertNull(Shadows.shadowOf(application).getNextStartedActivity());

        assertTrue(ExternalDownload.handOff(application, link, FamilyNames.REEL_DOWNLOAD));
        Intent chooser = Shadows.shadowOf(application).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertTrue("an application context needs a new task", (chooser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(link, send.getStringExtra(Intent.EXTRA_TEXT));
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains(ExternalDownload.SENT + " 1"));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ExternalDownload.handOff(application, link, FamilyNames.REEL_DOWNLOAD));
    }

    /** A reel's Download hands its link off and saves nothing here; with the switch off it saves. */
    @Test
    @Config(shadows = Post.class)
    public void aReelsDownloadSendsItsLinkInsteadOfSaving() {
        Post.id = "64_1";
        Settings.SEND_DOWNLOADS_TO_APP.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        assertTrue(ReelDownload.save(null, new Object(), activity));
        Intent chooser = Shadows.shadowOf(activity).getNextStartedActivity();
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals("https://www.instagram.com/reel/BA/", send.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals(0, chooser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertEquals("nothing saved here", 0, MediaSave.savesInFlight());

        Post.id = null;
        assertTrue("no link, so it tries the save here, which finds no file", ReelDownload.save(null, new Object(), activity));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    /** A Media whose id and poster's name are whatever the test sets. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Post {
        static String id;
        static String name;

        @Implementation protected static String mediaId(Object media) { return id; }
        @Implementation protected static Object owner(Object media) { return name == null ? null : "user"; }
        @Implementation protected static String username(Object user) { return name; }
    }
}
