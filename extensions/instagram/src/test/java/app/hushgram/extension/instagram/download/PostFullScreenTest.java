/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.VideoView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.function.Function;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Long press a post for full screen (#505): the viewer itself. A photo shows the picture it's given, pinches
 * and closes on a tap. A video plays from its address, asks for the sound only for a while, follows a slider
 * that seeks when let go, pauses on a tap and stops when closed. No file or a player error closes with a toast.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PostFullScreenTest {
    private static final String ADDRESS = "https://scontent.cdninstagram.com/v/t16/video.mp4";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Function<Context, VideoView> stockViews = PostFullScreen.videoViews;
    private final Function<Object, String> stockAddresses = PostFullScreen.addresses;
    private ActivityController<Activity> controller;
    private Activity activity;
    private Player video;

    /** A VideoView with no player in it, answering what the viewer asks. */
    private static final class Player extends VideoView {
        int duration = 10_000;
        int current;
        int seekedTo = -1;
        int focus = -1;
        boolean playing;
        boolean stopped;
        Uri address;
        MediaPlayer.OnErrorListener errors;

        Player(Context context) {
            super(context);
        }

        @Override public int getDuration() {
            return duration;
        }

        @Override public int getCurrentPosition() {
            return current;
        }

        @Override public void seekTo(int to) {
            seekedTo = to;
            current = to;
        }

        @Override public boolean isPlaying() {
            return playing;
        }

        @Override public void start() {
            playing = true;
        }

        @Override public void pause() {
            playing = false;
        }

        @Override public void stopPlayback() {
            stopped = true;
            playing = false;
        }

        @Override public void setVideoURI(Uri uri) {
            address = uri;
        }

        @Override public void setAudioFocusRequest(int request) {
            focus = request;
        }

        @Override public void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
            errors = listener;
        }
    }

    /** Notes whether a bitmap got drawn on it. */
    private static final class Noting extends Canvas {
        boolean drew;

        Noting() {
            super(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888));
        }

        @Override public void drawBitmap(Bitmap bitmap, Rect from, Rect to, Paint paint) {
            drew = true;
        }

        @Override public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
            drew = true;
        }
    }

    @Before
    public void open() {
        HookStatus.clear();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        PostFullScreen.videoViews = context -> video = new Player(context);
    }

    @After
    public void restore() {
        PostFullScreen.videoViews = stockViews;
        PostFullScreen.addresses = stockAddresses;
        Dialog left = ShadowDialog.getLatestDialog();
        if (left != null) left.dismiss();
        idle();
        controller.close();
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tick() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PostFullScreen.TICK_MS));
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    /** A screen on show whose video is ready and playing. */
    private PostFullScreen.VideoScreen playing() {
        PostFullScreen.VideoScreen screen = new PostFullScreen.VideoScreen(activity, ADDRESS);
        screen.dialog.show();
        idle();
        screen.prepared(null);
        return screen;
    }

    private static void tapVideo(PostFullScreen.VideoScreen screen) {
        ((View) screen.video.getParent()).performClick();
    }

    @Test
    public void aPhotoShowsThePictureItsGivenAndATapClosesIt() {
        BitmapDrawable picture = new BitmapDrawable(activity.getResources(), Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888));
        assertTrue(PostFullScreen.photo(activity, picture));
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        ProfilePictureViewer.ZoomView view = zoomViewIn(dialog);
        assertSame(picture, view.getDrawable());

        view.performClick();
        idle();
        assertFalse(dialog.isShowing());
    }

    @Test
    public void noPictureOrAGoneScreenOpensNothing() {
        assertFalse(PostFullScreen.photo(activity, null));
        activity.finish();
        BitmapDrawable picture = new BitmapDrawable(activity.getResources(), Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888));
        assertFalse(PostFullScreen.photo(activity, picture));
        PostFullScreen.addresses = media -> ADDRESS;
        assertFalse(PostFullScreen.video(activity, new Object()));
        assertNull(ShadowDialog.getLatestDialog());
    }

    /** A picture Instagram let go of while the viewer is up draws nothing rather than crash. */
    @Test
    public void aPictureInstagramLetGoOfDrawsNothing() {
        Bitmap bitmap = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888);
        assertTrue(PostFullScreen.photo(activity, new BitmapDrawable(activity.getResources(), bitmap)));
        ProfilePictureViewer.ZoomView view = zoomViewIn(ShadowDialog.getLatestDialog());

        Noting before = new Noting();
        view.onDraw(before);
        assertTrue("a live picture is drawn", before.drew);

        bitmap.recycle();
        Noting after = new Noting();
        view.onDraw(after);
        assertFalse(after.drew);
    }

    private static ProfilePictureViewer.ZoomView zoomViewIn(Dialog dialog) {
        android.view.ViewGroup content = dialog.findViewById(android.R.id.content);
        return (ProfilePictureViewer.ZoomView) content.getChildAt(0);
    }

    /** The feed drops to silence behind the viewer and gets its sound back after, from Instagram's own handling. */
    @Test
    public void aVideoPlaysItsAddressAndAsksForTheSoundOnlyForAWhile() {
        PostFullScreen.addresses = media -> ADDRESS;
        assertTrue(PostFullScreen.video(activity, new Object()));
        assertTrue(ShadowDialog.getLatestDialog().isShowing());
        assertEquals(Uri.parse(ADDRESS), video.address);
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, video.focus);
        assertFalse("nothing plays before it's ready", video.playing);
    }

    @Test
    public void aVideoWithNoFileSaysSoAndDoesntOpen() {
        PostFullScreen.addresses = media -> null;
        assertFalse(PostFullScreen.video(activity, new Object()));
        idle();
        assertNull(ShadowDialog.getLatestDialog());
        assertEquals("Couldn't play this video", ShadowToast.getTextOfLatestToast());
        assertTrue(report(), report().contains(PostFullScreen.NO_FILE));
    }

    @Test
    public void readyItPlaysWithTheSliderSetToItsLength() {
        PostFullScreen.VideoScreen screen = new PostFullScreen.VideoScreen(activity, ADDRESS);
        screen.dialog.show();
        idle();
        assertFalse("no slider until it's ready", screen.slider.isEnabled());
        assertEquals(View.VISIBLE, screen.spinner.getVisibility());

        screen.prepared(null);

        assertTrue(video.playing);
        assertTrue(screen.slider.isEnabled());
        assertEquals(10_000, screen.slider.getMax());
        assertEquals(View.GONE, screen.spinner.getVisibility());
    }

    @Test
    public void theSliderFollowsTheVideoWhileItPlays() {
        PostFullScreen.VideoScreen screen = playing();
        video.current = 2_000;
        tick();
        assertEquals(2_000, screen.slider.getProgress());

        video.playing = false;
        video.current = 4_000;
        tick();
        assertEquals("a paused video leaves it where it is", 2_000, screen.slider.getProgress());
    }

    /** While it's dragged the video stays paused and the slider stays with the finger. It seeks on letting go. */
    @Test
    public void draggingSeeksOnLettingGoAndPlaysAgain() {
        PostFullScreen.VideoScreen screen = playing();
        screen.onStartTrackingTouch(screen.slider);
        assertFalse(video.playing);
        screen.slider.setProgress(5_000);
        video.current = 1_000;
        tick();
        assertEquals(5_000, screen.slider.getProgress());
        assertEquals("no seek until it's let go", -1, video.seekedTo);

        screen.onStopTrackingTouch(screen.slider);
        assertEquals(5_000, video.seekedTo);
        assertTrue(video.playing);
    }

    /** A video paused before the drag stays paused after it. */
    @Test
    public void draggingAPausedVideoLeavesItPaused() {
        PostFullScreen.VideoScreen screen = playing();
        tapVideo(screen);
        screen.onStartTrackingTouch(screen.slider);
        screen.slider.setProgress(3_000);
        screen.onStopTrackingTouch(screen.slider);
        assertEquals(3_000, video.seekedTo);
        assertFalse(video.playing);
    }

    @Test
    public void aTapPausesAndPlays() {
        PostFullScreen.VideoScreen screen = playing();
        tapVideo(screen);
        assertFalse(video.playing);
        tapVideo(screen);
        assertTrue(video.playing);
    }

    @Test
    public void aTapBeforeItsReadyDoesNothing() {
        PostFullScreen.VideoScreen screen = new PostFullScreen.VideoScreen(activity, ADDRESS);
        screen.dialog.show();
        idle();
        tapVideo(screen);
        assertFalse(video.playing);
    }

    /** After Home and back the VideoView prepares again from the start, so it goes back to where it was. */
    @Test
    public void preparedAgainItGoesBackToWhereItWas() {
        PostFullScreen.VideoScreen screen = playing();
        assertEquals("a first start stays at the start", -1, video.seekedTo);
        video.current = 3_000;
        tick();
        video.playing = false;
        screen.prepared(null);
        assertEquals(3_000, video.seekedTo);
        assertTrue(video.playing);
    }

    @Test
    public void closeStopsTheVideoAndTheSlider() {
        PostFullScreen.VideoScreen screen = playing();
        screen.close.performClick();
        idle();
        assertFalse(screen.dialog.isShowing());
        assertTrue(video.stopped);

        video.playing = true;
        video.current = 6_000;
        tick();
        assertEquals("nothing follows it any more", 0, screen.slider.getProgress());
        screen.prepared(null);
        assertEquals("a late ready doesn't start it", 0, screen.slider.getProgress());
        assertEquals(-1, video.seekedTo);
    }

    /** A player error closes with a toast, and answers yes so Android's own dialog about it stays away. */
    @Test
    public void aPlayerErrorClosesAndSaysSo() {
        PostFullScreen.VideoScreen screen = playing();
        assertTrue(video.errors.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0));
        idle();
        assertFalse(screen.dialog.isShowing());
        assertTrue(video.stopped);
        assertEquals("Couldn't play this video", ShadowToast.getTextOfLatestToast());
        assertTrue(report(), report().contains(PostFullScreen.NOT_PLAYED));
    }
}
