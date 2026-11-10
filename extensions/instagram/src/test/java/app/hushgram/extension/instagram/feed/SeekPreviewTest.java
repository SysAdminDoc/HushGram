/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.Locale;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The card above your finger while you drag a seek bar (#567, #592). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SeekPreviewTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private FrameLayout content;
    private SeekBar bar;

    @Before
    public void createBar() {
        Settings.SEEK_PREVIEW.resetToDefault();
        controller = Robolectric.buildActivity(Activity.class).setup();
        content = new FrameLayout(controller.get());
        controller.get().setContentView(content);
        bar = bar();
        Locale.setDefault(Locale.US);
    }

    @After
    public void restore() {
        SeekPreview.resetForTests();
        PauseForTests.resume();
        Settings.SEEK_PREVIEW.resetToDefault();
        controller.pause().stop().destroy();
    }

    private SeekBar bar() {
        SeekBar seek = new SeekBar(controller.get());
        seek.setMax(200_000);
        content.addView(seek, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40, Gravity.BOTTOM));
        return seek;
    }

    private static void check() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SeekPreview.CHECK_MS));
    }

    private void drag(int position) {
        bar.setPressed(true);
        SeekPreview.dragged(bar, position, null, "test");
    }

    @Test
    public void offToStartShowsNothing() {
        assertEquals(Boolean.FALSE, Settings.SEEK_PREVIEW.defaultValue);
        drag(65_000);
        assertNull(SeekPreview.cardForTests());
    }

    @Test
    public void aDragShowsTheTimeInTheWindowsTopView() {
        Settings.SEEK_PREVIEW.save(true);
        drag(65_000);
        View card = SeekPreview.cardForTests();
        assertNotNull(card);
        assertSame("nothing in the post can cut it off", bar.getRootView(), card.getParent());
        assertEquals("1:05 / 3:20", SeekPreview.timeForTests());
        assertEquals("not read out over the seek bar", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, card.getImportantForAccessibility());
    }

    /** Near a bar's ends the card sits on Instagram's own drag times, so nothing under it may show through. */
    @Test
    public void theCardHidesWhatsUnderIt() {
        Settings.SEEK_PREVIEW.save(true);
        drag(5_000);
        GradientDrawable fill = (GradientDrawable) SeekPreview.cardForTests().getBackground();
        assertEquals(0xFF, Color.alpha(fill.getColor().getDefaultColor()));
    }

    /** #592: a reel or story played faster or slower says so beside the time. */
    @Test
    public void aSpeedThatIsntNormalShowsBesideTheTime() {
        Settings.SEEK_PREVIEW.save(true);
        bar.setPressed(true);
        SeekPreview.dragged(bar, 65_000, null, 1.5f, "test");
        assertEquals("1:05 / 3:20 " + SeekPreview.DOT + " 1.5x", SeekPreview.timeForTests());
        SeekPreview.dragged(bar, 65_000, null, 1.25f, "test");
        assertEquals("1:05 / 3:20 " + SeekPreview.DOT + " 1.25x", SeekPreview.timeForTests());
        SeekPreview.dragged(bar, 65_000, null, 1f, "test");
        assertEquals("1:05 / 3:20", SeekPreview.timeForTests());
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("in the phone's decimal mark", "1:05 / 3:20 " + SeekPreview.DOT + " 0,5x", SeekPreview.text(65_000, 200_000, 0.5f));
        } finally {
            Locale.setDefault(Locale.US);
        }
    }

    @Test
    public void theSameCardFollowsTheDrag() {
        Settings.SEEK_PREVIEW.save(true);
        drag(65_000);
        View card = SeekPreview.cardForTests();
        drag(70_000);
        assertSame(card, SeekPreview.cardForTests());
        assertEquals("1:10 / 3:20", SeekPreview.timeForTests());
    }

    @Test
    public void itStaysWhileHeldAndLeavesWhenLetGo() {
        Settings.SEEK_PREVIEW.save(true);
        drag(65_000);
        View card = SeekPreview.cardForTests();
        check();
        assertSame("still held", card, SeekPreview.cardForTests());
        assertNotNull(card.getParent());

        bar.setPressed(false);
        check();
        assertNull(SeekPreview.cardForTests());
        assertNull("gone from the window", card.getParent());
    }

    @Test
    public void itLeavesWhenTheBarDoes() {
        Settings.SEEK_PREVIEW.save(true);
        drag(65_000);
        View card = SeekPreview.cardForTests();
        content.removeView(bar);
        check();
        assertNull(card.getParent());
    }

    @Test
    public void turningItOffOrAPauseTakesItAway() {
        Settings.SEEK_PREVIEW.save(true);
        drag(65_000);
        View card = SeekPreview.cardForTests();
        Settings.SEEK_PREVIEW.save(false);
        check();
        assertNull(card.getParent());

        Settings.SEEK_PREVIEW.save(true);
        drag(65_000);
        card = SeekPreview.cardForTests();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        check();
        assertNull(card.getParent());
    }

    @Test
    public void anotherBarGetsItsOwnCard() {
        Settings.SEEK_PREVIEW.save(true);
        drag(65_000);
        View first = SeekPreview.cardForTests();
        SeekBar other = bar();
        other.setPressed(true);
        SeekPreview.dragged(other, 5_000, null, "test");
        assertNotSame(first, SeekPreview.cardForTests());
        assertNull("only one card at a time", first.getParent());
    }

    @Test
    public void aBarWithNoLengthShowsNothing() {
        Settings.SEEK_PREVIEW.save(true);
        bar.setMax(0);
        drag(0);
        assertNull(SeekPreview.cardForTests());
    }

    @Test
    public void theTimeIsWrittenTheWayAVideoShowsIt() {
        assertEquals("0:00 / 0:05", SeekPreview.text(0, 5_000));
        assertEquals("1:05 / 3:20", SeekPreview.text(65_999, 200_000));
        assertEquals("1:02:05 / 1:02:05", SeekPreview.text(3_725_000, 3_725_000));
        assertEquals("never before the start", "0:00 / 0:05", SeekPreview.text(-40, 5_000));
        assertEquals("never past the end", "0:05 / 0:05", SeekPreview.text(9_000, 5_000));
    }

    @Test
    public void thePictureKeepsItsShape() {
        assertArrayEquals(new int[] {71, 128}, SeekPreview.pictureSize(100, 178, 128));
        assertArrayEquals(new int[] {128, 64}, SeekPreview.pictureSize(200, 100, 128));
        assertArrayEquals(new int[] {128, 128}, SeekPreview.pictureSize(50, 50, 128));
    }

    @Test
    public void theCardStaysOnTheScreen() {
        assertEquals("centered on the finger", 450, SeekPreview.left(500f, 100, 1000, 16));
        assertEquals("kept in at the left", 16, SeekPreview.left(0f, 100, 1000, 16));
        assertEquals("kept in at the right", 884, SeekPreview.left(1000f, 100, 1000, 16));
        assertEquals("a card too wide starts at the margin", 16, SeekPreview.left(10f, 500, 400, 16));
    }
}
