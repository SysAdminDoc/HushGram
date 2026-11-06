/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.ui.Dim;

/**
 * Long press a post for full screen (#505): the photo or video of a post, on black, over the feed.
 *
 * <p>A photo shows the picture the post already has on screen, so it opens at once and fetches nothing.
 * Pinch and double tap zoom it, and a tap or Back closes it. A video plays from the same address Open in
 * another player hands over, the single file its post lists at the Download quality, and loops as it does
 * in the feed. A tap pauses and plays it, the slider jumps to where it's let go, and Close or Back shuts it.
 * A video listed only as a DASH manifest has no such file, so it doesn't open.
 *
 * <p>The feed goes on behind the viewer. Its video would talk over this one, so the viewer asks for the sound
 * for a short while, the kind of request Instagram's feed audio ({@code onAudioFocusChange}) answers by
 * dropping to silence and coming back from when the viewer lets go. The plain request would leave the feed
 * muted after it.
 */
public final class PostFullScreen {
    /** How often the slider follows the video, in milliseconds. */
    static final int TICK_MS = 250;

    /** What {@link HookStatus} counts. */
    static final String NO_FILE = "full screen videos with no file";
    static final String NOT_PLAYED = "full screen videos that wouldn't play";

    /** The step a failure is reported under. */
    private static final String HOOK = "post full screen";

    /** What the video plays in. Tests swap it. */
    @VisibleForTesting
    static Function<Context, VideoView> videoViews = VideoView::new;

    /** The address of a post's video. Tests swap it. */
    @VisibleForTesting
    static Function<Object, String> addresses = ExternalPlayer::address;

    private PostFullScreen() {
    }

    /** Opens [picture] full screen over the screen [context] belongs to. Answers whether it opened. Never throws. */
    public static boolean photo(Context context, Drawable picture) {
        try {
            Activity activity = activity(context);
            if (activity == null || picture == null) return false;
            Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            ProfilePictureViewer.ZoomView view = new ProfilePictureViewer.ZoomView(activity);
            view.setImageDrawable(picture);
            view.setBackgroundColor(Color.BLACK);
            view.setContentDescription(L10n.t(activity, "Photo or video"));
            view.setOnClickListener(tap -> dialog.dismiss());
            dialog.setContentView(view);
            ProfilePictureViewer.Screen.fill(dialog.getWindow(), Color.BLACK);
            dialog.show();
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            return false;
        }
    }

    /**
     * Plays [media]'s video full screen over the screen [context] belongs to. Answers whether it opened.
     * A video with no file to play says so and answers no. Never throws.
     */
    public static boolean video(Context context, Object media) {
        try {
            Activity activity = activity(context);
            if (activity == null) return false;
            String address = addresses.apply(media);
            if (address == null) {
                HookStatus.counted(FamilyNames.LONG_PRESS_ZOOM, NO_FILE);
                couldNotPlay(activity);
                return false;
            }
            new VideoScreen(activity, address).dialog.show();
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            return false;
        }
    }

    @Nullable
    private static Activity activity(Context context) {
        Activity activity = ProfilePictureViewer.activityOf(context);
        if (activity == null) activity = Utils.getActivity();
        return activity == null || activity.isFinishing() || activity.isDestroyed() ? null : activity;
    }

    private static void couldNotPlay(Context context) {
        Feedback.show(context.getApplicationContext(), L10n.t(context, "Couldn't play this video"), false);
    }

    /** The full screen dialog playing a video, and what follows it. Made and used on the main thread. */
    static final class VideoScreen implements Runnable, SeekBar.OnSeekBarChangeListener {
        final Dialog dialog;
        final VideoView video;
        final SeekBar slider;
        final ProgressBar spinner;
        final TextView close;
        private final Activity activity;
        private final FrameLayout root;
        private boolean ready;
        private boolean dragging;
        private boolean playAfterDrag;
        private boolean shut;

        /** Where the video last was, to go back to after the system takes its surface away. */
        private int position;

        VideoScreen(Activity activity, String address) {
            this.activity = activity;
            dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            root = new FrameLayout(activity);
            root.setBackgroundColor(Color.BLACK);
            root.setContentDescription(L10n.t(activity, "Photo or video"));
            root.setOnClickListener(tap -> tapped());

            video = videoViews.apply(activity);
            video.setKeepScreenOn(true);
            video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            video.setOnPreparedListener(this::prepared);
            video.setOnErrorListener((player, what, extra) -> failed());
            // Given the whole screen, a VideoView fits itself to it and keeps the video's shape. Asked to
            // wrap its content it stops at the file's own size, a third short of full screen on a 720p file.
            root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

            spinner = new ProgressBar(activity);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
            spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(spinner, new FrameLayout.LayoutParams(Dim.dp(48), Dim.dp(48), Gravity.CENTER));

            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            // A tap on the bar that misses the slider and Close is no tap on the video.
            bar.setClickable(true);
            slider = new SeekBar(activity);
            slider.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
            slider.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            slider.setProgressBackgroundTintList(ColorStateList.valueOf(0x80FFFFFF));
            slider.setContentDescription(L10n.t(activity, "Seek bar"));
            slider.setEnabled(false);
            slider.setOnSeekBarChangeListener(this);
            bar.addView(slider, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            close = new Button(activity);
            close.setText(L10n.t(activity, "Close"));
            ScreenColors.styleAction(close, false);
            close.setOnClickListener(tap -> dialog.dismiss());
            LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            closeSize.setMarginStart(Dim.dp(8));
            bar.addView(close, closeSize);
            root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            // The video keeps the whole screen, and the bar keeps clear of navigation and edge gestures.
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Rect safe = ProfilePictureViewer.Screen.safeInsets(insets);
                bar.setPadding(safe.left + Dim.dp(16), Dim.dp(12), safe.right + Dim.dp(16), safe.bottom + Dim.dp(12));
                return insets;
            });

            dialog.setContentView(root);
            ProfilePictureViewer.Screen.fill(dialog.getWindow(), Color.BLACK);
            dialog.setOnShowListener(shown -> root.requestApplyInsets());
            dialog.setOnDismissListener(gone -> closed());
            video.setVideoURI(Uri.parse(address));
        }

        /** The video is ready: plays it, from where it was if the system had taken it away. Never throws. */
        void prepared(@Nullable MediaPlayer player) {
            try {
                if (shut) return;
                if (player != null) player.setLooping(true);
                spinner.setVisibility(View.GONE);
                slider.setMax(Math.max(video.getDuration(), 0));
                slider.setEnabled(true);
                ready = true;
                if (position > 0) video.seekTo(position);
                video.start();
                root.removeCallbacks(this);
                run();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            }
        }

        /** Moves the slider with the video until the viewer closes. Never throws. */
        @Override public void run() {
            try {
                if (shut) return;
                if (!dragging && video.isPlaying()) {
                    position = video.getCurrentPosition();
                    slider.setProgress(position);
                }
                root.postDelayed(this, TICK_MS);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            }
        }

        /** A tap on the video pauses or plays it. Never throws. */
        private void tapped() {
            try {
                if (!ready) return;
                if (video.isPlaying()) video.pause();
                else video.start();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            }
        }

        /**
         * The player couldn't go on: closes and says so. Answers yes, which keeps Android's own dialog about
         * it away. Never throws.
         */
        private boolean failed() {
            try {
                HookStatus.counted(FamilyNames.LONG_PRESS_ZOOM, NOT_PLAYED);
                dialog.dismiss();
                if (!activity.isFinishing() && !activity.isDestroyed()) couldNotPlay(activity);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            }
            return true;
        }

        private void closed() {
            try {
                shut = true;
                root.removeCallbacks(this);
                video.stopPlayback();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            }
        }

        @Override public void onStartTrackingTouch(SeekBar bar) {
            try {
                dragging = true;
                playAfterDrag = video.isPlaying();
                if (playAfterDrag) video.pause();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            }
        }

        @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        }

        @Override public void onStopTrackingTouch(SeekBar bar) {
            try {
                dragging = false;
                position = bar.getProgress();
                video.seekTo(position);
                if (playAfterDrag) video.start();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, HOOK, failure);
            }
        }
    }
}
