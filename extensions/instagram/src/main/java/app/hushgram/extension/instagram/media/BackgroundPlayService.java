/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The foreground service that keeps Instagram's process up while a video plays on in the
 * background, with the media notification that controls it ({@link BackgroundPlay}).
 *
 * <p>The patch adds this service to Instagram's manifest, not exported and typed
 * {@code mediaPlayback}. Every start goes into the foreground first, whatever it was started for,
 * since Android ends an app whose {@code startForegroundService} isn't followed by one. It stops
 * itself once there's no session left. The notification holds a media session, so the lock
 * screen and the quick settings player show the same Pause or Play and Stop, and tapping it brings
 * Instagram back where it was. The service never asks for the audio focus: Instagram's player
 * already holds it, and keeps handling a call or another app's sound as it always has.
 */
public final class BackgroundPlayService extends Service {
    static final String ACTION_SHOW = "app.hushgram.extension.instagram.BACKGROUND_PLAY_SHOW";
    static final String ACTION_PAUSE = "app.hushgram.extension.instagram.BACKGROUND_PLAY_PAUSE";
    static final String ACTION_PLAY = "app.hushgram.extension.instagram.BACKGROUND_PLAY_PLAY";
    static final String ACTION_STOP = "app.hushgram.extension.instagram.BACKGROUND_PLAY_STOP";
    static final String CHANNEL = "hushgram_background_play";
    static final int NOTIFICATION_ID = 0x48477042;

    /** How often a running session is checked, for a player that went or a pause left too long. */
    static final long TICK_MS = 2_000;

    /** How long after a button the notification looks at the player again. */
    static final long SETTLE_MS = 300;

    private static final String STOP_ACTION = "hushgram_stop";

    /** The package the buttons' icons come from: the system's own, so they need no resources of ours. */
    private static final String SYSTEM_ICONS = "android";

    @Nullable
    private static BackgroundPlayService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;
    private final Runnable settle = this::show;
    @Nullable
    private MediaSession session;
    private int lastStartId;
    @Nullable
    private Boolean shownPlaying;

    /** Starts the service for a session that just went to the background. */
    static void start(Context context) {
        Intent intent = new Intent(context, BackgroundPlayService.class).setAction(ACTION_SHOW);
        context.startForegroundService(intent);
    }

    /** Takes the notification down and stops the service, if it runs. */
    static void finish() {
        BackgroundPlayService service = running;
        if (service != null) service.end();
    }

    /** Shows the held video's state again, if the service runs. */
    static void refresh() {
        BackgroundPlayService service = running;
        if (service != null) service.show();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (!goForeground()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            String action = intent != null ? intent.getAction() : null;
            if (ACTION_PAUSE.equals(action)) BackgroundPlay.pauseFromNotification();
            else if (ACTION_PLAY.equals(action)) BackgroundPlay.playFromNotification();
            else if (ACTION_STOP.equals(action)) BackgroundPlay.stopFromNotification();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "notification button", t);
        }
        if (!BackgroundPlay.active()) {
            end();
            return START_NOT_STICKY;
        }
        show();
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, SETTLE_MS);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            BackgroundPlay.taskRemoved();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "task removed", t);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Throwable ignored) {
                // The session is going either way.
            }
            session = null;
        }
        if (running == this) running = null;
        try {
            BackgroundPlay.serviceGone();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "service stop", t);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Puts the service in the foreground with the notification. False when Android refuses. */
    private boolean goForeground() {
        try {
            // A button's start keeps what's shown, so the notification doesn't flick before show().
            Boolean shown = shownPlaying;
            boolean playing = shown != null ? shown : BackgroundPlay.heldPlaying();
            Notification notification = notification(playing);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            shownPlaying = playing;
            return true;
        } catch (Throwable t) {
            Logger.printInfo(() -> "Android wouldn't run the background playback notification", t instanceof Exception ? (Exception) t : null);
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "foreground", t);
            return false;
        }
    }

    private void onTick() {
        try {
            BackgroundPlay.tick();
            if (!BackgroundPlay.active()) {
                end();
                return;
            }
            show();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "tick", t);
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    /** Posts the notification again when the video went from playing to paused or back. */
    private void show() {
        try {
            if (!BackgroundPlay.active()) return;
            boolean playing = BackgroundPlay.heldPlaying();
            if (shownPlaying != null && shownPlaying == playing) return;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.notify(NOTIFICATION_ID, notification(playing));
            shownPlaying = playing;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "notification", t);
        }
    }

    private void end() {
        handler.removeCallbacksAndMessages(null);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // Not in the foreground yet; stopSelf below still ends it.
        }
        stopSelf(lastStartId);
    }

    private Notification notification(boolean playing) {
        String label = appLabel();
        String text = L10n.t(this, playing ? "Playing in the background" : "Paused in the background");
        MediaSession media = session(label, text, playing);

        Notification.Action toggle = playing
                ? action(android.R.drawable.ic_media_pause, L10n.t(this, "Pause"), ACTION_PAUSE, 1)
                : action(android.R.drawable.ic_media_play, L10n.t(this, "Play"), ACTION_PLAY, 2);
        Notification.Action stop = action(android.R.drawable.ic_menu_close_clear_cancel, L10n.t(this, "Stop"), ACTION_STOP, 3);

        Notification.Builder builder = new Notification.Builder(this, channel())
                .setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle(label)
                .setContentText(text)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(service(ACTION_STOP, 4))
                .addAction(toggle)
                .addAction(stop)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(media.getSessionToken())
                        .setShowActionsInCompactView(0, 1));
        PendingIntent open = openInstagram();
        if (open != null) builder.setContentIntent(open);
        return builder.build();
    }

    /** The media session the notification and the system's player controls go through. */
    private MediaSession session(String label, String text, boolean playing) {
        MediaSession media = session;
        if (media == null) {
            media = new MediaSession(this, "HushGram background playback");
            media.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    button(ACTION_PLAY);
                }

                @Override
                public void onPause() {
                    button(ACTION_PAUSE);
                }

                @Override
                public void onStop() {
                    button(ACTION_STOP);
                }

                @Override
                public void onCustomAction(String action, @Nullable android.os.Bundle extras) {
                    if (STOP_ACTION.equals(action)) button(ACTION_STOP);
                }
            }, handler);
            PendingIntent open = openInstagram();
            if (open != null) media.setSessionActivity(open);
            session = media;
        }
        media.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, label)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
                .build());
        media.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        STOP_ACTION, L10n.t(this, "Stop"), android.R.drawable.ic_menu_close_clear_cancel).build())
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                .build());
        media.setActive(true);
        return media;
    }

    /** A button from the media session, handled the way the notification's own buttons are. */
    private void button(String action) {
        try {
            if (ACTION_PAUSE.equals(action)) BackgroundPlay.pauseFromNotification();
            else if (ACTION_PLAY.equals(action)) BackgroundPlay.playFromNotification();
            else BackgroundPlay.stopFromNotification();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "media button", t);
        }
        if (!BackgroundPlay.active()) {
            end();
            return;
        }
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, SETTLE_MS);
    }

    private Notification.Action action(int icon, String title, String action, int requestCode) {
        return new Notification.Action.Builder(Icon.createWithResource(SYSTEM_ICONS, icon), title, service(action, requestCode)).build();
    }

    private PendingIntent service(String action, int requestCode) {
        Intent intent = new Intent(this, BackgroundPlayService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Nullable
    private PendingIntent openInstagram() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch == null) return null;
            launch.setPackage(getPackageName());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return PendingIntent.getActivity(this, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The notification channel, made again when the phone's language changed its name. */
    private String channel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return CHANNEL;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        String name = L10n.t(this, "Background playback");
        if (channel == null || !name.contentEquals(channel.getName())) {
            NotificationChannel named = new NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW);
            named.setDescription(L10n.t(this,
                    "The video playing while Instagram is in the background, with buttons to pause or stop it"));
            named.setShowBadge(false);
            manager.createNotificationChannel(named);
        }
        return CHANNEL;
    }

    private String appLabel() {
        try {
            CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
            // Falls back to the name below.
        }
        return "Instagram";
    }
}
