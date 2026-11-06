/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Seek bar on photos with music (#10): on its own switch, a reel that's a photo with a song gets the bar
 * and the time, its length from the player, and no other reel changes. Off or paused, Instagram's answers stand.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37})
public class ReelPhotoSeekBarTest {
    /** Instagram's kinds of reel keep their names through its build, as this enum's do. */
    private enum Kind { CLIPS, CAROUSEL_WITH_MUSIC, PHOTO_WITHOUT_MUSIC, PHOTO_WITH_MUSIC }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> activity;
    private FrameLayout root;

    @Before public void photosOnly() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.REEL_SEEK_BAR.save(false);
        Settings.PHOTO_REEL_SEEK_BAR.save(true);
        HookStatus.clear();
        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        root = new FrameLayout(activity.get());
        activity.get().setContentView(root);
    }

    @After public void restore() {
        activity.close();
        Settings.PHOTO_REEL_SEEK_BAR.resetToDefault();
        Settings.REEL_SEEK_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test public void onlyAPhotoWithASongIsAnsweredYes() {
        assertTrue(ReelSeekBar.photoBarOn());
        assertTrue(ReelSeekBar.photoReel(0, Kind.PHOTO_WITH_MUSIC, "yes"));
        assertTrue("without an id it still gets the bar", ReelSeekBar.photoReel(0, Kind.PHOTO_WITH_MUSIC, null));
        assertFalse(ReelSeekBar.photoReel(0, Kind.CLIPS, "video"));
        assertFalse(ReelSeekBar.photoReel(0, Kind.PHOTO_WITHOUT_MUSIC, "silent"));
        assertFalse(ReelSeekBar.photoReel(0, Kind.CAROUSEL_WITH_MUSIC, "carousel"));
        assertFalse("no kind", ReelSeekBar.photoReel(0, null, "none"));
        assertFalse("not a kind at all", ReelSeekBar.photoReel(0, "PHOTO_WITH_MUSIC", "text"));
        assertTrue("Instagram's own yes stands", ReelSeekBar.photoReel(1, Kind.CLIPS, "video"));
        assertTrue(ReelSeekBar.photoShown("clips_scrubber_yes"));
        assertFalse(ReelSeekBar.photoShown("clips_scrubber_video"));
        assertFalse(ReelSeekBar.photoShown(null));
    }

    /** With no length, a photo counts as a short reel; other short reels keep Instagram's hidden kind on this switch alone. */
    @Test public void aPhotosRowIsNeverTheHiddenKindAndOtherReelsKeepTheirs() {
        ReelSeekBar.photoRow(Kind.PHOTO_WITH_MUSIC, "row");
        assertFalse(ReelSeekBar.lazy(1));
        ReelSeekBar.photoRow(Kind.CLIPS, "short");
        assertTrue("a short video reel", ReelSeekBar.lazy(1));
        assertFalse(ReelSeekBar.lazy(0));
        assertEquals("nor does this switch shorten the server's minimum", 30L, ReelSeekBar.minSeconds(30L));

        ReelSeekBar.photoRow(Kind.PHOTO_WITH_MUSIC, "row");
        Settings.PHOTO_REEL_SEEK_BAR.save(false);
        assertTrue("off, Instagram's answer", ReelSeekBar.lazy(1));
    }

    /** A photo's post gives its bar a length of 0. The player's length goes to that reel's bar and nobody else's. */
    @Test public void aPhotosBarTakesItsLengthFromThePlayerAndShowsTheTime() {
        ReelSeekBar.photoReel(0, Kind.PHOTO_WITH_MUSIC, "sized");
        SeekBar photo = bar("clips_scrubber_sized", 0);
        SeekBar video = bar("clips_scrubber_other", 12_000);

        ReelSeekBar.photoProgress("other", 3_000, 99_000);
        assertEquals("a video reel's bar is left alone", 12_000, video.getMax());
        ReelSeekBar.photoProgress("sized", 0, 0);
        assertEquals("the player doesn't know yet", 0, photo.getMax());
        ReelSeekBar.photoProgress(null, 3_000, 15_000);
        assertEquals(0, photo.getMax());

        ReelSeekBar.photoProgress("sized", 3_000, 15_000);
        assertEquals(15_000, photo.getMax());
        photo.setProgress(3_000);
        ReelSeekBar.progress(photo, 3_000);
        ShadowLooper.idleMainLooper();
        layout();
        ShadowLooper.idleMainLooper();
        TextView label = ReelTimeLabel.labelOf(photo);
        assertNotNull("the photo's bar gets the time", label);
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals("0:03 / 0:15", label.getText().toString());
        ReelSeekBar.progress(video, 3_000);
        ShadowLooper.idleMainLooper();
        assertNull("a video reel's doesn't, on this switch alone", ReelTimeLabel.labelOf(video));

        photo.setPressed(true);
        ReelSeekBar.photoProgress("sized", 4_000, 16_000);
        assertEquals("not under a finger", 15_000, photo.getMax());
        photo.setPressed(false);
        ReelSeekBar.photoProgress("sized", 4_000, 16_000);
        assertEquals(16_000, photo.getMax());
        assertFalse("the hooks were counted", HookStatus.snapshot().isEmpty());
    }

    @Test public void offOrPausedNothingHappens() {
        ReelSeekBar.photoReel(0, Kind.PHOTO_WITH_MUSIC, "quiet");
        SeekBar photo = bar("clips_scrubber_quiet", 5_000);
        Settings.PHOTO_REEL_SEEK_BAR.save(false);
        assertFalse(ReelSeekBar.photoBarOn());
        assertFalse(ReelSeekBar.photoShown("clips_scrubber_quiet"));
        ReelSeekBar.progress(photo, 1_000);
        ShadowLooper.idleMainLooper();
        assertNoTime("off, no time", photo);

        Settings.PHOTO_REEL_SEEK_BAR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused, the hooks ask and hear no", ReelSeekBar.photoBarOn());
        assertFalse(ReelSeekBar.photoShown("clips_scrubber_quiet"));
        ReelSeekBar.progress(photo, 1_000);
        ShadowLooper.idleMainLooper();
        assertNoTime("paused, no time", photo);
    }

    /** Binding while on makes the label; once off it stays out of sight. */
    private static void assertNoTime(String why, SeekBar bar) {
        TextView label = ReelTimeLabel.labelOf(bar);
        assertTrue(why, label == null || label.getVisibility() != View.VISIBLE);
    }

    /** A kind or id the hook can't read, or a bar it can't find, leaves Instagram's answer. */
    @Test public void nothingStrangeReachesInstagram() {
        ReelSeekBar.photoRow(null, null);
        assertTrue(ReelSeekBar.lazy(1));
        ReelSeekBar.photoProgress("never seen", 1_000, 2_000);
        assertFalse(ReelSeekBar.photoReel(0, new Object(), null));
        assertTrue(ReelSeekBar.photoReel(1, new Object(), null));
    }

    /** A bar of [max] at the foot of a container tagged as Instagram tags it, bound to an ordinary reel, with room for its time above. */
    private SeekBar bar(String tag, int max) {
        FrameLayout container = new FrameLayout(activity.get());
        container.setTag(tag);
        SeekBar bar = new SeekBar(activity.get());
        bar.setMax(max);
        container.addView(bar, new FrameLayout.LayoutParams(400, 28, Gravity.BOTTOM));
        root.addView(container, new FrameLayout.LayoutParams(400, 400));
        ReelSeekBar.bind(container, 0);
        ShadowLooper.idleMainLooper();
        layout();
        return bar;
    }

    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 400);
    }
}
