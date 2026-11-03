/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowNotificationManager;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Notify me of unfollowers (#492): raised from the Followers list the person scrolled to the end, nowhere else. */
@RunWith(RobolectricTestRunner.class)
public class FollowerAlertTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.TRACK_UNFOLLOWERS.save(true);
        Settings.NOTIFY_UNFOLLOWS.save(true);
    }

    @After public void restore() {
        Settings.TRACK_UNFOLLOWERS.resetToDefault();
        Settings.NOTIFY_UNFOLLOWS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Unfollowers.clear(context);
    }

    private static Map<String, String> followers(String... idsAndNames) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < idsAndNames.length; i += 2) map.put(idsAndNames[i], idsAndNames[i + 1]);
        return map;
    }

    private ShadowNotificationManager notifications() {
        return shadowOf((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE));
    }

    @Test public void aListThatLostSomeoneRaisesOneNotificationNamingThem() {
        Unfollowers.take(context, "1001", followers("1", "ana", "2", "bo", "3", "cy"), 100);
        assertEquals("the first list has nothing to compare with", 0, notifications().size());
        Unfollowers.take(context, "1001", followers("1", "ana"), 200);
        assertEquals(1, notifications().size());
        Notification note = notifications().getAllNotifications().get(0);
        assertEquals("2 people stopped following you", note.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("@bo, @cy", note.extras.getString(Notification.EXTRA_TEXT));
    }

    @Test public void withTheSwitchOffOrPausedNothingIsRaised() {
        Unfollowers.take(context, "1001", followers("1", "ana", "2", "bo"), 100);
        Settings.NOTIFY_UNFOLLOWS.save(false);
        Unfollowers.take(context, "1001", followers("1", "ana"), 200);
        assertEquals(0, notifications().size());
        Settings.NOTIFY_UNFOLLOWS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        Unfollowers.take(context, "1001", followers(), 300);
        assertEquals(0, notifications().size());
    }

    @Test public void newAndReturningFollowersAreNotUnfollows() {
        Unfollowers.take(context, "1001", followers("1", "ana"), 100);
        Unfollowers.take(context, "1001", followers("1", "ana", "2", "bo"), 200);
        assertEquals(0, notifications().size());
    }
}
