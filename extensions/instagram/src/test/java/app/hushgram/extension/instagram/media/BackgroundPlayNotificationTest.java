/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The background playback notification: its buttons, its tap, and the service ending (#497). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class BackgroundPlayNotificationTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object player = new Object();
    private final Set<Object> playing = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private ServiceController<BackgroundPlayService> service;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_PLAYING_IN_BACKGROUND.save(true);
        HookStatus.clear();
        BackgroundPlay.resetForTests();
        BackgroundPlay.serviceForTests = true;
        BackgroundPlay.controlForTests = new BackgroundPlay.Control() {
            @Override
            public boolean playing(Object video) {
                return playing.contains(video);
            }

            @Override
            public void pause(Object video) {
                playing.remove(video);
                calls.add("pause");
            }

            @Override
            public void play(Object video) {
                playing.add(video);
                calls.add("play");
            }
        };
    }

    @After
    public void restore() {
        if (service != null) service.destroy();
        BackgroundPlay.resetForTests();
        Settings.KEEP_PLAYING_IN_BACKGROUND.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The service goes into the foreground with Pause and Stop, and a tap opens Instagram. */
    @Test
    public void showsPauseAndStop() {
        Application app = RuntimeEnvironment.getApplication();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.getPackageName());
        ResolveInfo launcher = new ResolveInfo();
        launcher.activityInfo = new ActivityInfo();
        launcher.activityInfo.packageName = app.getPackageName();
        launcher.activityInfo.name = "com.instagram.mainactivity.InstagramMainActivity";
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(main, launcher);
        background();

        start(BackgroundPlayService.ACTION_SHOW, 1);

        Notification shown = shadowOf(service.get()).getLastForegroundNotification();
        assertNotNull("in the foreground", shown);
        assertEquals(BackgroundPlayService.NOTIFICATION_ID, shadowOf(service.get()).getLastForegroundNotificationId());
        assertEquals(BackgroundPlayService.CHANNEL, shown.getChannelId());
        assertEquals("Playing in the background", text(shown));
        assertEquals(List.of("Pause", "Stop"), titles(shown));
        assertNotNull("tapping it opens Instagram", shown.contentIntent);
        assertTrue(shadowOf(shown.contentIntent).isActivityIntent());
        assertEquals(app.getPackageName(), shadowOf(shown.contentIntent).getSavedIntent().getPackage());
        assertNotNull("swiping it away stops it", shown.deleteIntent);
        assertFalse(shadowOf(service.get()).isStoppedBySelf());
    }

    /** Pause pauses the video and the notification offers Play, which plays it again. */
    @Test
    public void pauseThenPlay() {
        background();
        start(BackgroundPlayService.ACTION_SHOW, 1);

        start(BackgroundPlayService.ACTION_PAUSE, 2);
        assertEquals(List.of("pause"), calls);
        Notification paused = posted();
        assertEquals("Paused in the background", text(paused));
        assertEquals(List.of("Play", "Stop"), titles(paused));
        assertFalse("a paused one can be swiped away", (paused.flags & Notification.FLAG_ONGOING_EVENT) != 0);

        start(BackgroundPlayService.ACTION_PLAY, 3);
        assertEquals(List.of("pause", "play"), calls);
        assertEquals(List.of("Pause", "Stop"), titles(posted()));
        assertFalse(shadowOf(service.get()).isStoppedBySelf());
    }

    /** Stop pauses the video, ends the session and takes the notification down. */
    @Test
    public void stopEndsEverything() {
        background();
        start(BackgroundPlayService.ACTION_SHOW, 1);

        start(BackgroundPlayService.ACTION_STOP, 2);

        assertEquals(List.of("pause"), calls);
        assertFalse(BackgroundPlay.active());
        assertTrue(shadowOf(service.get()).isForegroundStopped());
        assertTrue(shadowOf(service.get()).isStoppedBySelf());
    }

    /** Started with no session left, it stops at once. */
    @Test
    public void noSessionNoService() {
        start(BackgroundPlayService.ACTION_SHOW, 1);

        assertTrue(shadowOf(service.get()).isStoppedBySelf());
        assertTrue(calls.isEmpty());
    }

    /** The regular check ends a session left paused too long. */
    @Test
    public void theTickEndsALongPause() {
        background();
        start(BackgroundPlayService.ACTION_SHOW, 1);
        start(BackgroundPlayService.ACTION_PAUSE, 2);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackgroundPlayService.TICK_MS));
        assertTrue(BackgroundPlay.active());
        BackgroundPlay.pausedSinceForTests(SystemClock.uptimeMillis() - BackgroundPlay.PAUSED_LIMIT_MS);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackgroundPlayService.TICK_MS));

        assertFalse(BackgroundPlay.active());
        assertTrue(shadowOf(service.get()).isStoppedBySelf());
    }

    /** Swiping Instagram away from recents, or the service dying, ends the session with the video paused. */
    @Test
    public void swipedAwayOrDestroyedEndsIt() {
        background();
        start(BackgroundPlayService.ACTION_SHOW, 1);
        service.get().onTaskRemoved(new Intent());
        assertFalse(BackgroundPlay.active());

        background();
        service.destroy();
        service = null;
        assertFalse(BackgroundPlay.active());
        assertEquals(List.of("pause", "pause"), calls);
    }

    private void background() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        BackgroundPlay.started(player);
        BackgroundPlay.volume(player, 1f);
        playing.add(player);
        BackgroundPlay.leaving(screen);
        BackgroundPlay.stopped(screen);
        assertTrue(BackgroundPlay.active());
    }

    private void start(String action, int startId) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), BackgroundPlayService.class).setAction(action);
        if (service == null) service = Robolectric.buildService(BackgroundPlayService.class, intent).create();
        else service.withIntent(intent);
        service.startCommand(0, startId);
    }

    private Notification posted() {
        NotificationManager manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager.class);
        Notification notification = shadowOf(manager).getNotification(BackgroundPlayService.NOTIFICATION_ID);
        assertNotNull(notification);
        return notification;
    }

    private static String text(Notification notification) {
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        return text == null ? null : text.toString();
    }

    private static List<String> titles(Notification notification) {
        List<String> titles = new ArrayList<>();
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) titles.add(action.title.toString());
        }
        return titles;
    }
}
