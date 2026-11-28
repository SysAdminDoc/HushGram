/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Seek bar on feed videos (#389): the video a feed player is playing gets a bar along its bottom
 * that follows it, moves with the player to its next video and goes when the video does, and
 * letting go of a drag seeks; the switch off, a pause or unfilled stubs show none.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedSeekBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Feed feed;
    private FrameLayout first;
    private FrameLayout second;
    private View firstVideo;
    private View secondVideo;
    private View firstIcons;
    private final Object player = new Object();
    private final Stand stand = new Stand();

    /** The feed's player as the stubs reach it. */
    private final class Stand implements FeedSeekBar.Player {
        final List<Object> owners = new ArrayList<>();
        final List<View> showing = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        int reads;
        boolean viewThrows;
        boolean pauses = true;
        SeekSheet sheet;

        @Override public SeekSheet sheet(Object of) {
            return sheet;
        }

        @Override public View view(Object of) {
            reads++;
            if (viewThrows) throw new IllegalStateException("no holder");
            int at = owners.indexOf(of);
            return at < 0 ? null : showing.get(at);
        }

        @Override public boolean seekTo(Object of, int positionMs) {
            calls.add("seek " + positionMs);
            return true;
        }

        @Override public boolean pause(Object of) {
            calls.add("pause");
            return pauses;
        }

        @Override public boolean play(Object of) {
            calls.add("play");
            return true;
        }

        void plays(Object owner, View video) {
            int at = owners.indexOf(owner);
            if (at < 0) {
                owners.add(owner);
                showing.add(video);
            } else {
                showing.set(at, video);
            }
        }
    }

    /** The feed's list, noting when a child asks it not to take a touch. */
    private static final class Feed extends LinearLayout {
        boolean keptOut;
        /** Whether it scrolls the way Instagram's feed list does, so a SeekBar in it waits for a sideways move. */
        boolean scrolls;

        Feed(Context context) {
            super(context);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            keptOut = disallow;
            super.requestDisallowInterceptTouchEvent(disallow);
        }

        @Override
        public boolean shouldDelayChildPressedState() {
            return scrolls;
        }
    }

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        FeedSeekBar.resetForTests();
        TabSwipe.forgetForTests();
        FeedSeekBar.playerForTests(stand);
        feed = new Feed(activity);
        feed.setOrientation(LinearLayout.VERTICAL);
        first = new FrameLayout(activity);
        second = new FrameLayout(activity);
        firstVideo = new View(activity);
        secondVideo = new View(activity);
        firstIcons = new View(activity);
        first.addView(firstVideo, new FrameLayout.LayoutParams(720, 400));
        first.addView(firstIcons, new FrameLayout.LayoutParams(96, 96, Gravity.BOTTOM | Gravity.END));
        second.addView(secondVideo, new FrameLayout.LayoutParams(720, 400));
        feed.addView(first, new LinearLayout.LayoutParams(720, 400));
        feed.addView(second, new LinearLayout.LayoutParams(720, 400));
        activity.setContentView(feed, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stand.plays(player, firstVideo);
        idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.FEED_SEEK_BAR.resetToDefault();
        Settings.FEED_SEEK_BUTTON.resetToDefault();
        Settings.FEED_SEEK_BAR_LOOK.resetToDefault();
        Settings.FEED_SEEK_BAR_ON_EDGE.resetToDefault();
        Settings.SEEK_BAR_SHAPE.resetToDefault();
        Settings.FEED_SEEK_SHAPE.resetToDefault();
        Settings.SEEK_PREVIEW.resetToDefault();
        Settings.FEED_SEEK_TIME.resetToDefault();
        Settings.FEED_SEEK_TIME_LEFT.resetToDefault();
        Settings.FEED_SEEK_TIME_UNDER_TAGS.resetToDefault();
        Settings.FEED_SEEK_TIME_TOP.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedSeekBar.resetForTests();
        SeekPreview.resetForTests();
        Settings.SEEK_BAR_SHAPE.resetToDefault();
        Settings.FEED_SEEK_SHAPE.resetToDefault();
        Settings.SEEK_PREVIEW.resetToDefault();
        Settings.FEED_SEEK_TIME.resetToDefault();
        Settings.FEED_SEEK_TIME_LEFT.resetToDefault();
        Settings.FEED_SEEK_TIME_UNDER_TAGS.resetToDefault();
        Settings.FEED_SEEK_TIME_TOP.resetToDefault();
        TabSwipe.forgetForTests();
        Settings.FEED_SEEK_BAR.resetToDefault();
        Settings.FEED_SEEK_BUTTON.resetToDefault();
        Settings.FEED_SEEK_BAR_LOOK.resetToDefault();
        Settings.FEED_SEEK_BAR_ON_EDGE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tick() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.TICK_MS));
    }

    private static SeekBar bar() {
        SeekBar bar = FeedSeekBar.barForTests();
        assertNotNull("the video has a bar", bar);
        return bar;
    }

    @Test
    public void offToStartAddsNothing() {
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        assertNull(FeedSeekBar.barForTests());
        assertEquals("only the video and its icons", 2, first.getChildCount());
    }

    /** The toggle button (#437) is a small view after the video, and its tap shows or hides the bar. */
    @Test
    public void theToggleButtonShowsAndHidesTheBar() {
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_BUTTON.defaultValue);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        assertEquals("the bar stays hidden while its own switch is off", View.GONE, bar.getVisibility());
        FeedSeekBar.Toggle button = null;
        for (int at = 0; at < first.getChildCount(); at++) if (first.getChildAt(at) instanceof FeedSeekBar.Toggle) button = (FeedSeekBar.Toggle) first.getChildAt(at);
        assertNotNull("the video has a button", button);
        assertTrue("Instagram's icons stay drawn over it", first.indexOfChild(button) < first.indexOfChild(firstIcons));
        assertFalse(button.barShown());

        assertTrue(button.performClick());
        assertTrue(Settings.FEED_SEEK_BAR.savedValue());
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(button.barShown());

        assertTrue(button.performClick());
        assertFalse(Settings.FEED_SEEK_BAR.savedValue());
        assertEquals(View.GONE, bar.getVisibility());

        Settings.FEED_SEEK_BUTTON.save(false);
        tick();
        assertNull("the button goes with its switch", button.getParent());
    }

    /** #471: Seek bar look's choice reaches the bar as it follows the video. */
    @Test
    public void theBarTakesSeekBarLook() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        GlassSeekBar bar = (GlassSeekBar) bar();
        assertEquals("solid to start, as the bar always was", OverlayLook.SOLID, bar.look());

        Settings.FEED_SEEK_BAR_LOOK.save(OverlayLook.SEE_THROUGH);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals(OverlayLook.SEE_THROUGH, bar.look());
        assertSame("the same bar, restyled", bar, FeedSeekBar.barForTests());

        Settings.FEED_SEEK_BAR_LOOK.save(OverlayLook.SOLID);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertEquals(OverlayLook.SOLID, bar.look());
    }

    @Test
    public void withTheButtonOffTheBarKeepsToItself() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        for (int at = 0; at < first.getChildCount(); at++) assertFalse(first.getChildAt(at) instanceof FeedSeekBar.Toggle);
    }

    @Test
    public void aFeedVideoGetsABarAlongItsBottomThatFollowsIt() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        assertSame("in the frame holding the video", first, bar.getParent());
        assertEquals("just after the video", first.indexOfChild(firstVideo) + 1, first.indexOfChild(bar));
        assertTrue("Instagram's icons stay drawn over the bar", first.indexOfChild(bar) < first.indexOfChild(firstIcons));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
        assertEquals(Gravity.BOTTOM, params.gravity);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(15000, bar.getMax());
        assertEquals(3000, bar.getProgress());

        FeedSeekBar.progress(player, 9000, 15000);
        assertEquals(9000, bar.getProgress());
        assertSame("one bar per player", bar, FeedSeekBar.barForTests());
        assertEquals(3, first.getChildCount());
        assertTrue(stand.calls.isEmpty());
    }

    /**
     * #567: on the bottom edge the bar is only as tall as its round handle, so a scroll that starts low
     * on the video lands on the video, and the button moves down with it. Flipping it back restores both.
     */
    @Test
    public void onTheEdgeTheBarIsOnlyAsTallAsItsHandle() {
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_BAR_ON_EDGE.defaultValue);
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        float density = bar.getResources().getDisplayMetrics().density;
        int roomy = Math.round(FeedSeekBar.HEIGHT_DP * density);
        assertEquals(roomy, bar.getLayoutParams().height);
        FeedSeekBar.Toggle button = null;
        for (int at = 0; at < first.getChildCount(); at++) if (first.getChildAt(at) instanceof FeedSeekBar.Toggle) button = (FeedSeekBar.Toggle) first.getChildAt(at);
        assertNotNull(button);
        int gap = Math.round(4 * density);
        assertEquals(roomy + gap, ((FrameLayout.LayoutParams) button.getLayoutParams()).bottomMargin);

        Settings.FEED_SEEK_BAR_ON_EDGE.save(true);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertSame("the same bar, made thinner", bar, FeedSeekBar.barForTests());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
        int handle = bar.getThumb() != null ? bar.getThumb().getIntrinsicHeight() : 0;
        int edge = Math.max(handle, Math.round(FeedSeekBar.EDGE_MIN_DP * density));
        assertEquals(edge, params.height);
        assertTrue("thinner than the roomy bar", params.height < roomy);
        assertEquals("flush with the bottom", 0, params.bottomMargin);
        assertEquals(Gravity.BOTTOM, params.gravity);
        assertEquals(edge + gap, ((FrameLayout.LayoutParams) button.getLayoutParams()).bottomMargin);

        Settings.FEED_SEEK_BAR_ON_EDGE.save(false);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertEquals(roomy, bar.getLayoutParams().height);
        assertEquals(roomy + gap, ((FrameLayout.LayoutParams) button.getLayoutParams()).bottomMargin);
    }

    /**
     * A feed post as Instagram 456 lays it out (#794): the video's frame two levels under the post, and
     * beside it a row along the video's bottom holding a tags button at the start and the sound button,
     * in a group of its own, at the end.
     */
    private static final class Post {
        final FrameLayout root;
        final FrameLayout frame;
        final View video;
        final FrameLayout row;
        final View tags;
        final int sound = View.generateViewId();

        Post(Activity activity) {
            root = new FrameLayout(activity);
            FrameLayout holder = new FrameLayout(activity);
            frame = new FrameLayout(activity);
            video = new View(activity);
            frame.addView(video, new FrameLayout.LayoutParams(720, 400));
            holder.addView(frame, new FrameLayout.LayoutParams(720, 400));
            root.addView(holder, new FrameLayout.LayoutParams(720, 400));
            FrameLayout overlay = new FrameLayout(activity);
            row = new FrameLayout(activity);
            tags = new View(activity);
            FrameLayout.LayoutParams tagsAt = new FrameLayout.LayoutParams(64, 64, Gravity.START | Gravity.CENTER_VERTICAL);
            tagsAt.setMarginStart(12);
            row.addView(tags, tagsAt);
            FrameLayout soundGroup = new FrameLayout(activity);
            soundGroup.setClickable(true);
            LinearLayout padded = new LinearLayout(activity);
            View icon = new View(activity);
            icon.setId(sound);
            FeedSeekBar.idForTests(activity, FeedSeekBar.SOUND_BUTTON, sound);
            padded.addView(icon, new LinearLayout.LayoutParams(48, 48));
            soundGroup.addView(padded, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            row.addView(soundGroup, new FrameLayout.LayoutParams(116, 88, Gravity.END));
            overlay.addView(row, new FrameLayout.LayoutParams(720, 88, Gravity.BOTTOM));
            root.addView(overlay, new FrameLayout.LayoutParams(720, 400));
        }
    }

    private Post post() {
        Post post = new Post(controller.get());
        feed.addView(post.root, 0, new LinearLayout.LayoutParams(720, 400));
        stand.plays(player, post.video);
        idle();
        assertEquals("laid out", 88, post.row.getHeight());
        return post;
    }

    private static FrameLayout.LayoutParams buttonAt() {
        View button = FeedSeekBar.buttonForTests();
        assertNotNull("the video has a button", button);
        return (FrameLayout.LayoutParams) button.getLayoutParams();
    }

    /**
     * #794: while the bar shows, Instagram's row of tags and sound buttons is lifted clear of it, and the
     * toggle sits in that row, past the tags button and level with both, so nothing covers anything.
     */
    @Test
    public void theBarLiftsInstagramsButtonsAndTheToggleLinesUpWithThem() {
        Post post = post();
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        SeekBar bar = bar();
        assertSame(post.frame, bar.getParent());
        int lift = FeedSeekBar.barHeight(post.video, bar);
        assertTrue(lift > 0);
        assertEquals("the row sits over the bar", -lift, post.row.getTranslationY(), 0f);
        assertEquals("the tags button keeps its place in the row", 0f, post.tags.getTranslationY(), 0f);
        float density = post.video.getResources().getDisplayMetrics().density;
        int size = Math.round(FeedSeekBar.BUTTON_DP * density);
        int gap = Math.round(FeedSeekBar.BUTTON_GAP_DP * density);
        FrameLayout.LayoutParams at = buttonAt();
        assertEquals("past the tags button", 12 + 64 + gap, at.getMarginStart());
        assertEquals("level with the row's buttons, lifted with them", 88 - 44 + lift - size / 2, at.bottomMargin);

        assertTrue(FeedSeekBar.buttonForTests().performClick());
        assertFalse(Settings.FEED_SEEK_BAR.savedValue());
        assertEquals(View.GONE, bar.getVisibility());
        assertEquals("with the bar hidden the row is back", 0f, post.row.getTranslationY(), 0f);
        at = buttonAt();
        assertEquals(12 + 64 + gap, at.getMarginStart());
        assertEquals("still level with the row", 88 - 44 - size / 2, at.bottomMargin);

        assertTrue(FeedSeekBar.buttonForTests().performClick());
        assertEquals(-lift, post.row.getTranslationY(), 0f);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals("lifted once, not again on each tick", -lift, post.row.getTranslationY(), 0f);
    }

    /**
     * #711: with the time showing, Instagram's tags and sound buttons go up past it, so the time sits
     * under the tags button and a touch on it can't land on tags. The time is smaller than the reels'
     * 12sp. With the time off the row only clears the bar again.
     */
    @Test
    public void theTimeSitsUnderInstagramsButtons() {
        Post post = post();
        // The tags button slid out wide over the middle of the video, low in its row.
        FrameLayout.LayoutParams slidOut = (FrameLayout.LayoutParams) post.tags.getLayoutParams();
        slidOut.width = 400;
        slidOut.gravity = Gravity.START | Gravity.BOTTOM;
        slidOut.bottomMargin = 2;
        post.tags.setLayoutParams(slidOut);
        idle();
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        Settings.FEED_SEEK_TIME.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        SeekBar bar = bar();
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);
        float density = post.video.getResources().getDisplayMetrics().density;
        float scaled = post.video.getResources().getDisplayMetrics().scaledDensity;
        assertTrue("smaller than the reels' time", time.getTextSize() < 12f * scaled);
        int barHeight = FeedSeekBar.barHeight(post.video, bar);
        int gap = Math.round(FeedSeekBar.TIME_GAP_DP * density);
        assertEquals("the time stays just over the bar", barHeight + gap,
                ((FrameLayout.LayoutParams) time.getLayoutParams()).bottomMargin);
        float lift = -post.row.getTranslationY();
        assertTrue("the row goes up past the time, not just the bar", lift > barHeight);

        int[] at = new int[2];
        time.getLocationOnScreen(at);
        int timeTop = at[1];
        int timeLeft = at[0];
        post.tags.getLocationOnScreen(at);
        int tagsBottom = at[1] + post.tags.getHeight();
        assertTrue("the tags button reaches over the time", at[0] + post.tags.getWidth() > timeLeft);
        assertTrue("and ends above it: " + tagsBottom + " vs " + timeTop, tagsBottom + gap <= timeTop);
        assertEquals("by no more than it needs", timeTop - gap, tagsBottom);
        int size = Math.round(FeedSeekBar.BUTTON_DP * density);
        assertEquals("the toggle goes up with the row", 2 + 32 + Math.round(lift) - size / 2, buttonAt().bottomMargin);

        Settings.FEED_SEEK_TIME.save(false);
        tick();
        assertNull(FeedSeekBar.timeForTests());
        assertEquals("with the time off the row only clears the bar", -barHeight, post.row.getTranslationY(), 0f);
    }

    /**
     * #711: Time under the tags button moves the time from the bar's middle to the bottom start corner,
     * its text lined up with the tags button, which goes up past it with the rest of the row. With no
     * tags button it sits as far in as the sound button does from the other side, and with no row at
     * all it keeps clear of the toggle that has the corner then.
     */
    @Test
    public void theTimeCanSitUnderTheTagsButton() {
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_TIME_UNDER_TAGS.defaultValue);
        assertEquals(Settings.FEED_SEEK_TIME, Settings.FEED_SEEK_TIME_UNDER_TAGS.getParentSettings().get(0));
        Post post = post();
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_TIME.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals("the middle while it's off", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);
        int bottom = params.bottomMargin;

        Settings.FEED_SEEK_TIME_UNDER_TAGS.save(true);
        FeedSeekBar.progress(player, 4000, 15000);
        idle();
        assertSame("the same label, moved", time, FeedSeekBar.timeForTests());
        params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals(Gravity.BOTTOM | Gravity.START, params.gravity);
        assertEquals("as high over the bar as before", bottom, params.bottomMargin);
        int[] at = new int[2];
        time.getLocationOnScreen(at);
        int textStart = at[0] + time.getPaddingStart();
        int timeTop = at[1];
        post.tags.getLocationOnScreen(at);
        assertEquals("the text starts where the tags button does", at[0], textStart);
        float density = post.video.getResources().getDisplayMetrics().density;
        int gap = Math.round(FeedSeekBar.TIME_GAP_DP * density);
        assertTrue("and the tags button ends over it", at[1] + post.tags.getHeight() + gap <= timeTop);

        post.tags.setVisibility(View.GONE);
        idle();
        FeedSeekBar.progress(player, 5000, 15000);
        idle();
        assertEquals("no tags button: as far in as the sound icon is from the end, (116 - 48) / 2",
                34 - time.getPaddingStart(), ((FrameLayout.LayoutParams) time.getLayoutParams()).getMarginStart());

        Settings.FEED_SEEK_TIME_UNDER_TAGS.save(false);
        FeedSeekBar.progress(player, 6000, 15000);
        params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals("off again, back to the middle", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);
        assertEquals(0, params.getMarginStart());

        // A video with no row of Instagram's buttons around it.
        Settings.FEED_SEEK_TIME_UNDER_TAGS.save(true);
        stand.plays(player, firstVideo);
        FeedSeekBar.progress(player, 3000, 15000);
        TextView plain = FeedSeekBar.timeForTests();
        assertNotNull(plain);
        assertEquals("8 dp in", Math.round(8 * density) - plain.getPaddingStart(),
                ((FrameLayout.LayoutParams) plain.getLayoutParams()).getMarginStart());
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 4000, 15000);
        int pastToggle = Math.round(8 * density) + Math.round(FeedSeekBar.BUTTON_DP * density)
                + Math.round(FeedSeekBar.BUTTON_GAP_DP * density);
        assertEquals("past the toggle in the corner", pastToggle - plain.getPaddingStart(),
                ((FrameLayout.LayoutParams) plain.getLayoutParams()).getMarginStart());
    }

    /**
     * #788: Time in the top corner moves the time from over the bar to the video's top end corner, just
     * under Instagram's countdown there, its text ending where the countdown's does, and under the post's
     * menu button when the name row lies over the video. Instagram's row then only clears the bar. Time
     * under the tags button wins over it.
     */
    @Test
    public void theTimeCanSitInTheTopCornerUnderInstagramsCountdown() {
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_TIME_TOP.defaultValue);
        assertEquals(Settings.FEED_SEEK_TIME, Settings.FEED_SEEK_TIME_TOP.getParentSettings().get(0));
        Activity activity = controller.get();
        Post post = new Post(activity);
        // Instagram's countdown, wrapped in the post's media, in the video's top end corner.
        FrameLayout states = new FrameLayout(activity);
        TextView countdown = new TextView(activity);
        countdown.setText("0:38");
        countdown.setPadding(16, 8, 12, 8);
        states.addView(countdown, new FrameLayout.LayoutParams(94, 66, Gravity.TOP | Gravity.END));
        post.root.addView(states, 1, new FrameLayout.LayoutParams(720, 400));
        // The post around its media, taller than the video, and its name row with the menu button.
        FrameLayout item = new FrameLayout(activity);
        FrameLayout.LayoutParams media = new FrameLayout.LayoutParams(720, 400);
        item.addView(post.root, media);
        FrameLayout names = new FrameLayout(activity);
        View menu = new View(activity);
        menu.setId(View.generateViewId());
        FeedSeekBar.idForTests(activity, FeedSeekBar.MENU_BUTTON, menu.getId());
        menu.setPadding(20, 24, 22, 24);
        names.addView(menu, new FrameLayout.LayoutParams(88, 96, Gravity.END));
        // A suggested post's Follow button left of the menu, a TextView over the video in its media.
        TextView follow = new TextView(activity);
        follow.setText("Follow");
        follow.setPadding(24, 12, 24, 12);
        FrameLayout.LayoutParams followAt = new FrameLayout.LayoutParams(180, 64, Gravity.TOP | Gravity.END);
        followAt.topMargin = 16;
        followAt.setMarginEnd(100);
        follow.setVisibility(View.GONE);
        post.root.addView(follow, followAt);
        names.setVisibility(View.GONE);
        item.addView(names, new FrameLayout.LayoutParams(720, 96, Gravity.TOP));
        feed.addView(item, 0, new LinearLayout.LayoutParams(720, 600));
        stand.plays(player, post.video);
        idle();
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_TIME.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals("over the bar while it's off", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);
        assertSame(time, FeedSeekBar.shownTime());
        int barHeight = FeedSeekBar.barHeight(post.video, bar());
        assertTrue("the row clears the time too", -post.row.getTranslationY() > barHeight);

        Settings.FEED_SEEK_TIME_TOP.save(true);
        FeedSeekBar.progress(player, 4000, 15000);
        idle();
        assertSame("the same label, moved", time, FeedSeekBar.timeForTests());
        params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals(Gravity.TOP | Gravity.END, params.gravity);
        assertEquals("nothing under it to clear", 0, params.bottomMargin);
        int gap = Math.round(FeedSeekBar.TIME_GAP_DP * post.video.getResources().getDisplayMetrics().density);
        int[] clock = new int[2];
        int[] chip = new int[2];
        time.getLocationOnScreen(clock);
        countdown.getLocationOnScreen(chip);
        assertEquals("just under the countdown", chip[1] + 66 + gap, clock[1]);
        assertEquals("its text ends where the countdown's does", chip[0] + 94 - 12,
                clock[0] + time.getWidth() - time.getPaddingRight());
        assertTrue("and stays in the video", clock[0] + time.getWidth() <= chip[0] + 94);
        assertNull("the carousel pill has no time over the bar to clear", FeedSeekBar.shownTime());
        assertEquals("the row only clears the bar", -barHeight, post.row.getTranslationY(), 0f);

        // The name row over the top of the video, as Home lays out a reel: under its menu button too.
        names.setVisibility(View.VISIBLE);
        follow.setVisibility(View.VISIBLE);
        idle();
        FeedSeekBar.progress(player, 5000, 15000);
        idle();
        int[] dots = new int[2];
        menu.getLocationOnScreen(dots);
        time.getLocationOnScreen(clock);
        assertEquals("under the menu button", dots[1] + 96 + gap, clock[1]);
        assertEquals("still lined up with the countdown, the furthest out", chip[0] + 94 - 12,
                clock[0] + time.getWidth() - time.getPaddingRight());
        // No countdown showing: the text ends where the menu's icon does, not under the Follow button.
        countdown.setVisibility(View.GONE);
        idle();
        FeedSeekBar.progress(player, 5500, 15000);
        idle();
        time.getLocationOnScreen(clock);
        assertEquals("lined up with the menu's icon", dots[0] + 88 - 22,
                clock[0] + time.getWidth() - time.getPaddingRight());
        assertEquals("still under the menu button", dots[1] + 96 + gap, clock[1]);
        countdown.setVisibility(View.VISIBLE);
        follow.setVisibility(View.GONE);
        idle();
        // The name row above the video instead: only the countdown counts again.
        media.topMargin = 96;
        post.root.setLayoutParams(media);
        idle();
        FeedSeekBar.progress(player, 6000, 15000);
        idle();
        time.getLocationOnScreen(clock);
        countdown.getLocationOnScreen(chip);
        assertEquals("a menu above the video doesn't count", chip[1] + 66 + gap, clock[1]);

        Settings.FEED_SEEK_TIME_UNDER_TAGS.save(true);
        assertFalse("Time under the tags button wins", Settings.FEED_SEEK_TIME_TOP.isAvailable());
        FeedSeekBar.progress(player, 7000, 15000);
        idle();
        params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals(Gravity.BOTTOM | Gravity.START, params.gravity);
        assertEquals(0, params.topMargin);
        assertEquals(0, params.getMarginEnd());
        Settings.FEED_SEEK_TIME_UNDER_TAGS.save(false);

        Settings.FEED_SEEK_TIME_TOP.save(false);
        FeedSeekBar.progress(player, 9000, 15000);
        idle();
        params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals("off again, back over the bar", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);
        assertEquals(0, params.topMargin);
        assertEquals(0, params.getMarginEnd());
    }

    /** #711: a row's buttons give the corner its inset from either side, read as the toggle reads them, right to left too. */
    @Test
    public void theCornerLinesUpWithTheRowsButtons() {
        Activity activity = controller.get();
        FrameLayout row = new FrameLayout(activity);
        View tags = new View(activity);
        FrameLayout.LayoutParams tagsAt = new FrameLayout.LayoutParams(64, 64, Gravity.START | Gravity.BOTTOM);
        tagsAt.setMarginStart(20);
        row.addView(tags, tagsAt);
        View sound = new View(activity);
        FrameLayout.LayoutParams soundAt = new FrameLayout.LayoutParams(48, 48, Gravity.END | Gravity.BOTTOM);
        soundAt.setMarginEnd(30);
        row.addView(sound, soundAt);
        FrameLayout holder = new FrameLayout(activity);
        holder.addView(row, new FrameLayout.LayoutParams(720, 88));
        feed.addView(holder, 0, new LinearLayout.LayoutParams(720, 88));
        idle();
        assertEquals("the tags button's start", 20, FeedSeekBar.startInset(row, 0, 720));
        assertEquals("a row starting further in counts from the video", 30, FeedSeekBar.startInset(row, 10, 720));
        tags.setVisibility(View.GONE);
        assertEquals("no tags button: the sound button's distance from the end", 30, FeedSeekBar.startInset(row, 0, 720));
        sound.setVisibility(View.GONE);
        assertEquals("no buttons", -1, FeedSeekBar.startInset(row, 0, 720));

        tags.setVisibility(View.VISIBLE);
        sound.setVisibility(View.VISIBLE);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        idle();
        assertEquals("right to left, the tags button is on the right, 20 in", 20, FeedSeekBar.startInset(row, 0, 720));
    }

    /**
     * A carousel's video as Instagram 456 lays it out (#711): the video's frame four wrappers, each its
     * size, under the post's row, and beside them an overlay as big whose sound button sits in a small
     * holder of its own in the bottom end corner, at the holder's top.
     */
    private static final class Carousel {
        final FrameLayout root;
        final View video;
        final FrameLayout corner;
        final View icon;

        Carousel(Activity activity) {
            root = new FrameLayout(activity);
            FrameLayout frame = new FrameLayout(activity);
            video = new View(activity);
            frame.addView(video, new FrameLayout.LayoutParams(720, 400));
            ViewGroup wrapped = frame;
            for (int i = 0; i < 3; i++) {
                FrameLayout wrapper = new FrameLayout(activity);
                wrapper.addView(wrapped, new FrameLayout.LayoutParams(720, 400));
                wrapped = wrapper;
            }
            root.addView(wrapped, new FrameLayout.LayoutParams(720, 400));
            FrameLayout overlay = new FrameLayout(activity);
            FrameLayout layer = new FrameLayout(activity);
            corner = new FrameLayout(activity);
            LinearLayout padded = new LinearLayout(activity);
            padded.setGravity(Gravity.CENTER);
            icon = new View(activity);
            int sound = View.generateViewId();
            icon.setId(sound);
            FeedSeekBar.idForTests(activity, FeedSeekBar.SOUND_BUTTON, sound);
            // 456 at 2x: a 116 x 88 corner, a 60 px group at its top, a 30 px icon. Shrunk here so the
            // little room under the icon is less than the time needs, as it is on a phone.
            padded.addView(icon, new LinearLayout.LayoutParams(16, 16));
            corner.addView(padded, new FrameLayout.LayoutParams(24, 24, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            layer.addView(corner, new FrameLayout.LayoutParams(58, 30, Gravity.BOTTOM | Gravity.END));
            overlay.addView(layer, new FrameLayout.LayoutParams(720, 400));
            root.addView(overlay, new FrameLayout.LayoutParams(720, 400));
        }
    }

    /**
     * #711 on a carousel's video: its sound button, in a corner of the post's overlay rather than a row
     * beside the video, goes up past the bar and the time too, and the toggle lines up with it at the
     * start, where no button of Instagram's is.
     */
    @Test
    public void aCarouselsSoundButtonGoesUpPastTheBarAndTheTime() {
        Carousel post = new Carousel(controller.get());
        feed.addView(post.root, 0, new LinearLayout.LayoutParams(720, 400));
        stand.plays(player, post.video);
        idle();
        assertEquals("laid out", 30, post.corner.getHeight());
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        Settings.FEED_SEEK_TIME.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        SeekBar bar = bar();
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);
        float density = post.video.getResources().getDisplayMetrics().density;
        int barHeight = FeedSeekBar.barHeight(post.video, bar);
        int gap = Math.round(FeedSeekBar.TIME_GAP_DP * density);
        float lift = -post.corner.getTranslationY();
        assertTrue("the sound button's corner goes up past the time, not just the bar: " + lift, lift > barHeight);

        int[] at = new int[2];
        time.getLocationOnScreen(at);
        int timeTop = at[1];
        post.icon.getLocationOnScreen(at);
        assertEquals("the sound button ends a gap above the time", timeTop - gap, at[1] + post.icon.getHeight());

        int size = Math.round(FeedSeekBar.BUTTON_DP * density);
        FrameLayout.LayoutParams toggle = buttonAt();
        assertEquals("the toggle keeps the start", Math.round(8 * density), toggle.getMarginStart());
        assertEquals("level with the sound button, lifted with it", 30 - (4 + 8) + Math.round(lift) - size / 2, toggle.bottomMargin);

        Settings.FEED_SEEK_TIME.save(false);
        tick();
        assertEquals("with the time off it only clears the bar", -barHeight, post.corner.getTranslationY(), 0f);
        Settings.FEED_SEEK_BAR.save(false);
        tick();
        assertEquals("with the bar hidden it's back", 0f, post.corner.getTranslationY(), 0f);
    }

    /** #794: the row goes back where it was when the bar moves to another video or goes, and only then. */
    @Test
    public void instagramsButtonsGoBackWhenTheBarMovesOnOrGoes() {
        Post post = post();
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        int lift = FeedSeekBar.barHeight(post.video, bar());
        assertEquals(-lift, post.row.getTranslationY(), 0f);

        stand.plays(player, secondVideo);
        FeedSeekBar.progress(player, 4000, 15000);
        assertSame(second, bar().getParent());
        assertEquals("the post the bar left has its buttons back", 0f, post.row.getTranslationY(), 0f);

        stand.plays(player, post.video);
        FeedSeekBar.progress(player, 5000, 15000);
        assertEquals(-lift, post.row.getTranslationY(), 0f);
        FeedSeekBar.resetForTests();
        assertEquals("a bar taken away puts them back too", 0f, post.row.getTranslationY(), 0f);

        FeedSeekBar.playerForTests(stand);
        FeedSeekBar.idForTests(controller.get(), FeedSeekBar.SOUND_BUTTON, post.sound);
        FeedSeekBar.progress(player, 6000, 15000);
        assertEquals(-lift, post.row.getTranslationY(), 0f);
        post.row.setTranslationY(-5f);
        Settings.FEED_SEEK_BAR.save(false);
        FeedSeekBar.progress(player, 7000, 15000);
        assertEquals("a row Instagram moved itself stays where Instagram put it", -5f, post.row.getTranslationY(), 0f);
    }

    /** No row of buttons around the video, as on a post laid out some other way: the toggle keeps its corner. */
    @Test
    public void withoutInstagramsRowTheToggleKeepsItsCorner() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        float density = firstVideo.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams at = buttonAt();
        assertEquals(Math.round(8 * density), at.getMarginStart());
        assertEquals(FeedSeekBar.barHeight(firstVideo, bar()) + Math.round(4 * density), at.bottomMargin);
    }

    /**
     * A feed video as Instagram 456 and 457 lay it out on LustPriest's phone (#794, a 1080 px wide video
     * at 2.625x): the sound button in a row as wide as the video, and the tags button not in that row
     * but in a holder of its own beside it, across the start side up to the sound button's group. The
     * tags button is three wrappers deep in it, the last drawing its 68 px round back 36 px in.
     */
    private static final class TaggedPost {
        final FrameLayout root;
        final View video;
        final FrameLayout row;
        final FrameLayout holder;
        final FrameLayout back;

        TaggedPost(Activity activity) {
            root = new FrameLayout(activity);
            FrameLayout media = new FrameLayout(activity);
            FrameLayout frame = new FrameLayout(activity);
            video = new View(activity);
            frame.addView(video, new FrameLayout.LayoutParams(1080, 1350));
            media.addView(frame, new FrameLayout.LayoutParams(1080, 1350));
            root.addView(media, new FrameLayout.LayoutParams(1080, 1350));
            holder = new FrameLayout(activity);
            LinearLayout wrapper = new LinearLayout(activity);
            FrameLayout indicator = new FrameLayout(activity);
            back = new FrameLayout(activity);
            back.setClickable(true);
            back.addView(new View(activity), new FrameLayout.LayoutParams(30, 30, Gravity.CENTER));
            indicator.addView(back, new FrameLayout.LayoutParams(68, 68, Gravity.TOP | Gravity.END));
            wrapper.addView(indicator, new LinearLayout.LayoutParams(104, 104));
            holder.addView(wrapper, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
            root.addView(holder, new FrameLayout.LayoutParams(940, 107, Gravity.BOTTOM | Gravity.START));
            row = new FrameLayout(activity);
            FrameLayout group = new FrameLayout(activity);
            group.setClickable(true);
            LinearLayout round = new LinearLayout(activity);
            round.setGravity(Gravity.CENTER);
            View sound = new View(activity);
            int id = View.generateViewId();
            sound.setId(id);
            FeedSeekBar.idForTests(activity, FeedSeekBar.SOUND_BUTTON, id);
            round.addView(sound, new LinearLayout.LayoutParams(34, 34));
            group.addView(round, new FrameLayout.LayoutParams(68, 68, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            row.addView(group, new FrameLayout.LayoutParams(140, 104, Gravity.END));
            root.addView(row, new FrameLayout.LayoutParams(1080, 104, Gravity.BOTTOM));
        }
    }

    /** Where [view]'s middle is on screen, across and down. */
    private static int[] middle(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new int[]{at[0] + view.getWidth() / 2, at[1] + view.getHeight() / 2};
    }

    /**
     * #794 on 0.0.22: with the tags button in a holder beside the sound button's row, the toggle sat on
     * the tags button at 8 dp in and the bar showed over it. Now the holder goes up with the row and the
     * toggle sits a gap past the tags button's round back, level with it, the bar shown or hidden.
     */
    @Test
    public void theTagsButtonBesideTheSoundRowGoesUpAndTheTogglePassesIt() {
        TaggedPost post = new TaggedPost(controller.get());
        feed.addView(post.root, 0, new LinearLayout.LayoutParams(1080, 1350));
        stand.plays(player, post.video);
        idle();
        assertEquals("laid out", 107, post.holder.getHeight());
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        int lift = FeedSeekBar.barHeight(post.video, bar());
        assertEquals("the sound button's row sits over the bar", -lift, post.row.getTranslationY(), 0f);
        assertEquals("and the tags button's holder with it", -lift, post.holder.getTranslationY(), 0f);
        float density = post.video.getResources().getDisplayMetrics().density;
        int size = Math.round(FeedSeekBar.BUTTON_DP * density);
        int gap = Math.round(FeedSeekBar.BUTTON_GAP_DP * density);
        FrameLayout.LayoutParams at = buttonAt();
        assertEquals("a gap past the tags button's round back, 36 + 68", 104 + gap, at.getMarginStart());
        assertEquals("level with its middle, 70 px over the video's bottom", 70 + lift - size / 2, at.bottomMargin);
        int[] toggle = middle(FeedSeekBar.buttonForTests());
        int[] tags = middle(post.back);
        assertEquals("level with the tags button on screen", tags[1], toggle[1]);
        assertEquals("beside it", tags[0] + 34 + gap + size / 2, toggle[0]);

        assertTrue(FeedSeekBar.buttonForTests().performClick());
        idle();
        assertEquals("with the bar hidden the row is back", 0f, post.row.getTranslationY(), 0f);
        assertEquals("and the holder", 0f, post.holder.getTranslationY(), 0f);
        at = buttonAt();
        assertEquals(104 + gap, at.getMarginStart());
        assertEquals(70 - size / 2, at.bottomMargin);
        assertEquals("still level with the tags button", middle(post.back)[1], middle(FeedSeekBar.buttonForTests())[1]);

        assertTrue(FeedSeekBar.buttonForTests().performClick());
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals("lifted once, not again on each tick", -lift, post.holder.getTranslationY(), 0f);
        assertEquals(-lift, post.row.getTranslationY(), 0f);
        stand.plays(player, secondVideo);
        FeedSeekBar.progress(player, 5000, 15000);
        assertEquals("the post the bar left has both back", 0f, post.holder.getTranslationY(), 0f);
        assertEquals(0f, post.row.getTranslationY(), 0f);
    }

    /**
     * The picture of someone who reposted a post, as Instagram shows it over the video's bottom start
     * corner with a note over it (#891): an 80 px picture 30 px in and a 90 px note 20 px in, both
     * ending 110 px in, in a holder of their own beside the post's row.
     */
    private static final class Reposter {
        final FrameLayout holder;
        final ImageView picture;
        final TextView note;

        Reposter(Activity activity) {
            holder = new FrameLayout(activity);
            picture = new ImageView(activity);
            picture.setImageDrawable(new ColorDrawable(Color.GRAY));
            FrameLayout.LayoutParams pictureAt = new FrameLayout.LayoutParams(80, 80, Gravity.BOTTOM | Gravity.START);
            pictureAt.setMarginStart(30);
            pictureAt.bottomMargin = 6;
            holder.addView(picture, pictureAt);
            note = new TextView(activity);
            note.setText("ok");
            FrameLayout.LayoutParams noteAt = new FrameLayout.LayoutParams(90, 40, Gravity.BOTTOM | Gravity.START);
            noteAt.setMarginStart(20);
            noteAt.bottomMargin = 90;
            holder.addView(note, noteAt);
        }
    }

    /**
     * #891: a reposter's picture and note in the bottom start corner sat on the toggle and on the time
     * under the tags button. Both now start a gap past them, and go back when the picture goes.
     */
    @Test
    public void aRepostersPictureInTheCornerKeepsTheToggleAndTheTimeClear() {
        Post post = post();
        Reposter reposter = new Reposter(controller.get());
        post.root.addView(reposter.holder, new FrameLayout.LayoutParams(200, 200, Gravity.BOTTOM | Gravity.START));
        idle();
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        float density = post.video.getResources().getDisplayMetrics().density;
        int gap = Math.round(FeedSeekBar.BUTTON_GAP_DP * density);
        assertEquals("a gap past the picture and the note, not just the tags button", 110 + gap,
                buttonAt().getMarginStart());

        Settings.FEED_SEEK_TIME.save(true);
        Settings.FEED_SEEK_TIME_UNDER_TAGS.save(true);
        FeedSeekBar.progress(player, 4000, 15000);
        idle();
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);
        assertEquals("the time's text starts past them too", 110 + gap - time.getPaddingStart(),
                ((FrameLayout.LayoutParams) time.getLayoutParams()).getMarginStart());

        reposter.note.setVisibility(View.GONE);
        reposter.picture.setImageDrawable(null);
        FeedSeekBar.progress(player, 5000, 15000);
        idle();
        assertEquals("an empty picture draws nothing there: back past the tags button", 12 + 64 + gap,
                buttonAt().getMarginStart());
        assertEquals("and the time back under it", Math.max(0, 12 - time.getPaddingStart()),
                ((FrameLayout.LayoutParams) time.getLayoutParams()).getMarginStart());

        reposter.picture.setImageDrawable(new ColorDrawable(Color.GRAY));
        reposter.holder.setLayoutParams(new FrameLayout.LayoutParams(200, 200, Gravity.BOTTOM | Gravity.END));
        idle();
        FeedSeekBar.progress(player, 6000, 15000);
        idle();
        assertEquals("a picture in the end corner leaves the start alone", 12 + 64 + gap, buttonAt().getMarginStart());
    }

    /**
     * #891 with no row of Instagram's buttons: a picture in the corner moves the toggle past it, and the
     * time past the toggle; right to left, the corner is on the right and is measured from there.
     */
    @Test
    public void withoutARowTheToggleStillClearsAPictureInTheCorner() {
        ImageView picture = new ImageView(controller.get());
        picture.setImageDrawable(new ColorDrawable(Color.GRAY));
        FrameLayout.LayoutParams pictureAt = new FrameLayout.LayoutParams(60, 60, Gravity.BOTTOM | Gravity.START);
        pictureAt.setMarginStart(10);
        first.addView(picture, pictureAt);
        idle();
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_BUTTON.save(true);
        Settings.FEED_SEEK_TIME.save(true);
        Settings.FEED_SEEK_TIME_UNDER_TAGS.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        idle();
        float density = firstVideo.getResources().getDisplayMetrics().density;
        int gap = Math.round(FeedSeekBar.BUTTON_GAP_DP * density);
        int toggle = Math.max(Math.round(8 * density), 70 + gap);
        assertEquals(toggle, buttonAt().getMarginStart());
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);
        assertEquals("the time past the toggle", toggle + Math.round(FeedSeekBar.BUTTON_DP * density) + gap
                - time.getPaddingStart(), ((FrameLayout.LayoutParams) time.getLayoutParams()).getMarginStart());

        assertEquals(70, FeedSeekBar.startCornerEnd(first, Collections.emptyList(), FeedSeekBar.buttonForTests(), time,
                FeedSeekBar.barForTests()));
        first.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        first.requestLayout();
        idle();
        assertEquals("right to left, 70 in from the right", 70, FeedSeekBar.startCornerEnd(first,
                Collections.emptyList(), FeedSeekBar.buttonForTests(), time, FeedSeekBar.barForTests()));
        picture.setVisibility(View.GONE);
        assertEquals("gone, nothing there", -1, FeedSeekBar.startCornerEnd(first, Collections.emptyList(),
                FeedSeekBar.buttonForTests(), time, FeedSeekBar.barForTests()));
    }

    /** #472: like Instagram's own scrubber, the video pauses under the finger and plays on from where it lands. */
    @Test
    public void aDragPausesTheVideoThenSeeksItAndPlaysItOn() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        Listener drag = new Listener(bar);

        drag.start();
        assertEquals(Collections.singletonList("pause"), stand.calls);
        bar.setProgress(12000);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals("a drag isn't overwritten by the video", 12000, bar.getProgress());
        drag.stop();

        assertEquals(Arrays.asList("pause", "seek 12000", "play"), stand.calls);
        tick();
        assertEquals(12000, bar.getProgress());
    }

    /** A player whose pause stub wasn't filled is seeked but never played, so a video nobody paused is left alone. */
    @Test
    public void aDragThatPausedNothingPlaysNothing() {
        Settings.FEED_SEEK_BAR.save(true);
        stand.pauses = false;
        FeedSeekBar.progress(player, 3000, 15000);
        Listener drag = new Listener(bar());
        drag.start();
        bar().setProgress(9000);
        drag.stop();
        assertEquals(Arrays.asList("pause", "seek 9000"), stand.calls);
    }

    @Test
    public void aVideoOffScreenHidesItsBarThenTakesItAway() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();

        first.setVisibility(View.GONE);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
        first.setVisibility(View.VISIBLE);
        tick();
        assertEquals("back on screen in time keeps the bar", View.VISIBLE, bar.getVisibility());

        first.setVisibility(View.GONE);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.GONE_MS + FeedSeekBar.TICK_MS * 2));
        assertNull(FeedSeekBar.barForTests());
        assertNull(bar.getParent());
        int reads = stand.reads;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.TICK_MS * 8));
        assertEquals("a bar taken away stops ticking", reads, stand.reads);

        first.setVisibility(View.VISIBLE);
        FeedSeekBar.progress(player, 5000, 15000);
        assertSame("the player playing again brings a bar back", first, bar().getParent());
    }

    @Test
    public void aPlayingVideosHiddenCoverKeepsItsBar() {
        // The stub hands over the video's cover, which Instagram hides once the video plays.
        Settings.FEED_SEEK_BAR.save(true);
        firstVideo.setVisibility(View.GONE);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        assertSame(first, bar.getParent());
        assertEquals(first.indexOfChild(firstVideo) + 1, first.indexOfChild(bar));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.GONE_MS + FeedSeekBar.TICK_MS * 2));
        assertSame("still there while the video plays", bar, FeedSeekBar.barForTests());
        assertEquals(View.VISIBLE, bar.getVisibility());
    }

    @Test
    public void aFingerOnTheBarKeepsTheFeedFromTakingTheDrag() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        touch(bar, MotionEvent.ACTION_DOWN);
        assertTrue("the feed and the tab pager leave the drag to the bar", feed.keptOut);
    }

    @Test
    public void aFingerOnTheBarHoldsTheMainTabsUntilItLifts() {
        // The main tabs' list takes a sideways drag whatever the bar asks its parents, Stop swiping
        // between tabs off or not.
        Settings.FEED_SEEK_BAR.save(true);
        View mainList = mainTabs();
        assertTrue(TabSwipe.input(mainList, 1));
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();

        touch(bar, MotionEvent.ACTION_DOWN);
        assertFalse("held while the finger is down", TabSwipe.input(mainList, 1));
        touch(bar, MotionEvent.ACTION_MOVE);
        assertFalse(TabSwipe.input(mainList, 1));
        touch(bar, MotionEvent.ACTION_UP);
        assertTrue("let go when it lifts", TabSwipe.input(mainList, 1));

        touch(bar, MotionEvent.ACTION_DOWN);
        touch(bar, MotionEvent.ACTION_CANCEL);
        assertTrue("let go when the touch is taken away", TabSwipe.input(mainList, 1));

        touch(bar, MotionEvent.ACTION_DOWN);
        FeedSeekBar.resetForTests();
        assertTrue("let go when the bar goes away", TabSwipe.input(mainList, 1));
    }

    @Test
    public void aScrollThatStartsOnTheBarGoesBackToTheFeed() {
        feed.scrolls = true;
        Settings.FEED_SEEK_BAR.save(true);
        View mainList = mainTabs();
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        int progress = bar.getProgress();
        int slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();

        touch(bar, MotionEvent.ACTION_DOWN, 100, 10);
        assertTrue(feed.keptOut);
        touch(bar, MotionEvent.ACTION_MOVE, 102, 10 - slop / 2f);
        assertTrue("a move inside the slop keeps the bar's claim", feed.keptOut);
        touch(bar, MotionEvent.ACTION_MOVE, 104, 10 - slop - 4);
        assertFalse("the feed takes the scroll", feed.keptOut);
        assertTrue("and so do the main tabs", TabSwipe.input(mainList, 1));
        touch(bar, MotionEvent.ACTION_CANCEL, 104, 10 - slop - 4);
        assertEquals("nothing seeked", progress, bar.getProgress());
    }

    @Test
    public void aSidewaysDragOnTheBarKeepsItsClaim() {
        feed.scrolls = true;
        Settings.FEED_SEEK_BAR.save(true);
        View mainList = mainTabs();
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        int slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();

        touch(bar, MotionEvent.ACTION_DOWN, 100, 10);
        touch(bar, MotionEvent.ACTION_MOVE, 100 + slop * 3, 10 + slop / 2f);
        assertTrue(feed.keptOut);
        touch(bar, MotionEvent.ACTION_MOVE, 100 + slop * 3, 10 + slop * 4);
        assertTrue("a drag under way stays the bar's when the finger drifts down", feed.keptOut);
        assertFalse(TabSwipe.input(mainList, 1));
        touch(bar, MotionEvent.ACTION_UP, 100 + slop * 3, 10 + slop * 4);
        assertTrue(TabSwipe.input(mainList, 1));
    }

    @Test
    public void withTheSwitchOffAFingerOnTheBarLeavesTheMainTabs() {
        Settings.FEED_SEEK_BAR.save(true);
        View mainList = mainTabs();
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        Settings.FEED_SEEK_BAR.save(false);
        touch(bar, MotionEvent.ACTION_DOWN);
        assertTrue(TabSwipe.input(mainList, 1));
    }

    /** The main tabs' pager, handed to TabSwipe the way Instagram's setup does; returns its list. */
    private View mainTabs() {
        FrameLayout pager = new FrameLayout(controller.get());
        View list = new View(controller.get());
        pager.addView(list);
        TabSwipe.mainPager(pager);
        return list;
    }

    private static void touch(View view, int action) {
        touch(view, action, 100, 10);
    }

    private static void touch(View view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test
    public void theFeedHandingThePlayerAnotherVideoMovesTheBar() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        stand.plays(player, secondVideo);
        tick();
        assertSame(bar, FeedSeekBar.barForTests());
        assertSame("the bar follows the player's video", second, bar.getParent());
        assertEquals(second.indexOfChild(secondVideo) + 1, second.indexOfChild(bar));
        assertEquals("the old video's frame is as Instagram left it", 2, first.getChildCount());
    }

    @Test
    public void anotherPlayerTakesTheBarOver() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar old = bar();
        Object next = new Object();
        stand.plays(next, secondVideo);
        FeedSeekBar.progress(next, 1000, 6000);
        SeekBar bar = bar();
        assertNull("the old player's bar goes", old.getParent());
        assertSame(second, bar.getParent());
        assertEquals(6000, bar.getMax());
        new Listener(old).stop();
        assertTrue("the old bar seeks nothing", stand.calls.isEmpty());
    }

    @Test
    public void aVideoWithNoLengthYetOrOutsideAFrameGetsNoBar() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 0, 0);
        assertNull(FeedSeekBar.barForTests());
        FeedSeekBar.progress(player, 0, 15000);
        assertSame(first, bar().getParent());

        FeedSeekBar.resetForTests();
        FeedSeekBar.playerForTests(stand);
        stand.plays(player, first);
        FeedSeekBar.progress(player, 0, 15000);
        tick();
        assertNull("a view whose parent isn't a frame", FeedSeekBar.barForTests());
    }

    @Test
    public void unfilledStubsShowNothing() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.playerForTests(FeedSeekBar.PATCHED);
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        assertNull(FeedSeekBar.barForTests());
        assertEquals(2, first.getChildCount());
    }

    @Test
    public void aStubThrowingLeavesTheVideoAlone() {
        Settings.FEED_SEEK_BAR.save(true);
        stand.viewThrows = true;
        FeedSeekBar.progress(player, 3000, 15000);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertNull(FeedSeekBar.barForTests());
        assertEquals(2, first.getChildCount());
    }

    @Test
    public void switchingOffOrPausingHushgramHidesTheBarAndADragDoesNothing() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();

        Settings.FEED_SEEK_BAR.save(false);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
        new Listener(bar).start();
        new Listener(bar).stop();
        assertTrue(stand.calls.isEmpty());

        Settings.FEED_SEEK_BAR.save(true);
        tick();
        assertEquals(View.VISIBLE, bar.getVisibility());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        tick();
        assertEquals(View.GONE, bar.getVisibility());
    }

    @Test
    public void theBarHidingMidDragSeeksNothingButPlaysTheVideoOn() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        Listener drag = new Listener(bar);
        drag.start();
        first.setVisibility(View.GONE);
        tick();
        first.setVisibility(View.VISIBLE);
        tick();
        drag.stop();
        assertEquals(Arrays.asList("pause", "play"), stand.calls);
    }

    /** #586: a shape takes over the bar as it follows the video, and Standard hands it back. */
    @Test
    public void theBarTakesTheChosenShapeAndGivesItBack() {
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        assertFalse(bar.getProgressDrawable() instanceof SeekShapeDrawable);

        Settings.SEEK_BAR_SHAPE.save(SeekShape.ROUNDED);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertSame("the same bar, reshaped", bar, FeedSeekBar.barForTests());
        assertTrue(bar.getProgressDrawable() instanceof SeekShapeDrawable);
        assertEquals("a shape has no round handle", 0, bar.getThumb().getIntrinsicWidth());

        Settings.SEEK_BAR_SHAPE.save(SeekShape.STANDARD);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertFalse(bar.getProgressDrawable() instanceof SeekShapeDrawable);
    }

    /** #567: the thin line lies on the edge with no switch, and a shape with a tall handle gets room for it there. */
    @Test
    public void aLineSitsOnTheEdgeAndATallHandleGetsRoom() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.SEEK_BAR_SHAPE.save(SeekShape.LINE);
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        SeekBar bar = bar();
        float density = bar.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(FeedSeekBar.EDGE_MIN_DP * density), bar.getLayoutParams().height);
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.LINE, true));

        Settings.SEEK_BAR_SHAPE.save(SeekShape.WAVE);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertEquals("off the edge it keeps its room", Math.round(FeedSeekBar.HEIGHT_DP * density), bar.getLayoutParams().height);
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.WAVE, false));

        Settings.FEED_SEEK_BAR_ON_EDGE.save(true);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertEquals(Math.round(FeedSeekBar.EDGE_TALL_DP * density), bar.getLayoutParams().height);
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.WAVE, true));
    }

    /** #567: Feed seek bar shape gives feed videos a thin line while every other bar stays standard, and Same follows it again. */
    @Test
    public void theFeedKeepsAShapeOfItsOwn() {
        Settings.FEED_SEEK_BAR.save(true);
        assertEquals(FeedSeekShape.SAME, Settings.FEED_SEEK_SHAPE.defaultValue);
        Settings.FEED_SEEK_SHAPE.save(FeedSeekShape.LINE);
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        SeekBar bar = bar();
        float density = bar.getResources().getDisplayMetrics().density;
        assertEquals(SeekShape.STANDARD, Settings.SEEK_BAR_SHAPE.get());
        assertEquals("reels and stories keep the standard bar", SeekShape.STANDARD, SeekShapes.wanted());
        assertTrue(((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.LINE, true));
        assertEquals("the line lies on the edge", Math.round(FeedSeekBar.EDGE_MIN_DP * density), bar.getLayoutParams().height);

        Settings.FEED_SEEK_SHAPE.save(FeedSeekShape.SAME);
        Settings.SEEK_BAR_SHAPE.save(SeekShape.ROUNDED);
        FeedSeekBar.progress(player, 4000, 15000);
        tick();
        assertTrue("Same follows Seek bar shape", ((SeekShapeDrawable) bar.getProgressDrawable()).draws(SeekShape.ROUNDED, false));
        assertEquals(Math.round(FeedSeekBar.HEIGHT_DP * density), bar.getLayoutParams().height);

        Settings.FEED_SEEK_SHAPE.save(FeedSeekShape.STANDARD);
        FeedSeekBar.progress(player, 5000, 15000);
        tick();
        assertFalse("Standard for the feed alone", bar.getProgressDrawable() instanceof SeekShapeDrawable);
        assertEquals(SeekShape.ROUNDED, SeekShapes.wanted());
    }

    /**
     * #711: Time on the seek bar puts the time played and the length over the bar's middle, follows the
     * video and a drag, flips to the time left with a tap, and goes with the bar or its switch.
     */
    @Test
    public void theTimeSitsOverTheBarAndATapShowsWhatsLeft() {
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_TIME.defaultValue);
        assertEquals(Boolean.FALSE, Settings.FEED_SEEK_TIME_LEFT.defaultValue);
        assertEquals(Settings.FEED_SEEK_TIME, Settings.FEED_SEEK_TIME_LEFT.getParentSettings().get(0));
        assertFalse(Settings.FEED_SEEK_TIME.rebootApp);
        Settings.FEED_SEEK_BAR.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        tick();
        SeekBar bar = bar();
        int children = first.getChildCount();
        assertNull("off to start", FeedSeekBar.timeForTests());
        assertNull(FeedSeekBar.shownTime());

        Settings.FEED_SEEK_TIME.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);
        assertSame(first, time.getParent());
        assertEquals(children + 1, first.getChildCount());
        assertTrue("over the bar", first.indexOfChild(time) > first.indexOfChild(bar));
        assertEquals("0:03 / 0:15", time.getText().toString());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) time.getLayoutParams();
        assertEquals(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);
        float density = bar.getResources().getDisplayMetrics().density;
        assertEquals(bar.getLayoutParams().height + Math.round(FeedSeekBar.TIME_GAP_DP * density), params.bottomMargin);
        idle();
        assertSame(time, FeedSeekBar.shownTime());

        FeedSeekBar.progress(player, 4000, 15000);
        assertEquals("it follows the video", "0:04 / 0:15", time.getText().toString());
        assertTrue(time.performClick());
        assertTrue(Settings.FEED_SEEK_TIME_LEFT.get());
        assertEquals("a tap shows what's left", "-0:11 / 0:15", time.getText().toString());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains("Counted: " + FeedSeekBar.COUNT_TIME_FLIPS + " 1"));

        SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
        new Listener(bar).start();
        bar.setProgress(9000);
        listener.onProgressChanged(bar, 9000, true);
        assertEquals("it follows a drag", "-0:06 / 0:15", time.getText().toString());
        FeedSeekBar.progress(player, 4000, 15000);
        assertEquals("and the finger beats the paused video", "-0:06 / 0:15", time.getText().toString());
        new Listener(bar).stop();
        assertTrue(time.performClick());
        assertFalse(Settings.FEED_SEEK_TIME_LEFT.get());
        assertEquals("0:09 / 0:15", time.getText().toString());

        Settings.FEED_SEEK_BAR.save(false);
        tick();
        assertNull("it goes with the bar", FeedSeekBar.shownTime());
        assertNull(time.getParent());

        Settings.FEED_SEEK_BAR.save(true);
        tick();
        assertNotNull("and comes back with it", FeedSeekBar.timeForTests());
        Settings.FEED_SEEK_TIME.save(false);
        tick();
        assertNull("its switch off takes it away", FeedSeekBar.timeForTests());
        assertEquals(children, first.getChildCount());
    }

    /** #711: the time reads as the reels' does, the end counted as the end, and an hour or more gets its own place. */
    @Test
    public void theTimeReadsPlayedOrLeftOverTheLength() {
        assertEquals("0:03 / 0:15", FeedSeekBar.timeText(3000, 15000, false));
        assertEquals("-0:12 / 0:15", FeedSeekBar.timeText(3000, 15000, true));
        assertEquals("0:15 / 0:15", FeedSeekBar.timeText(16000, 15000, false));
        assertEquals("-0:00 / 0:15", FeedSeekBar.timeText(16000, 15000, true));
        assertEquals("0:00 / 0:15", FeedSeekBar.timeText(-500, 15000, false));
        assertEquals("1:05 / 1:02:05", FeedSeekBar.timeText(65000, 3725000, false));
    }

    /** #711: HushGram paused, the time goes with the bar, and nothing flips. */
    @Test
    public void aPauseTakesTheTimeAwayToo() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.FEED_SEEK_TIME.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        TextView time = FeedSeekBar.timeForTests();
        assertNotNull(time);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        tick();
        assertNull(FeedSeekBar.timeForTests());
        assertNull(time.getParent());
        time.performClick();
        assertFalse(Settings.FEED_SEEK_TIME_LEFT.savedValue());
    }

    /** #567: dragging shows the time above the finger; the player moving on its own doesn't. */
    @Test
    public void onlyADragShowsThePreview() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.SEEK_PREVIEW.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
        listener.onProgressChanged(bar, 4000, false);
        assertNull("nothing while it plays", SeekPreview.cardForTests());
        listener.onProgressChanged(bar, 4000, true);
        assertNull("nothing before the drag starts", SeekPreview.cardForTests());

        bar.setPressed(true);
        new Listener(bar).start();
        listener.onProgressChanged(bar, 5000, true);
        assertNotNull(SeekPreview.cardForTests());
        assertEquals("0:05 / 0:15", SeekPreview.timeForTests());
    }

    /** #567: most feed posts have no picture strip, so the paused video itself follows the drag, a move at a time. */
    @Test
    public void aPostWithNoStripShowsTheMomentInTheVideo() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.SEEK_PREVIEW.save(true);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
        bar.setPressed(true);
        new Listener(bar).start();
        listener.onProgressChanged(bar, 5000, true);
        listener.onProgressChanged(bar, 6000, true);
        listener.onProgressChanged(bar, 7000, true);
        assertEquals("the first move at once, the others wait", Arrays.asList("pause", "seek 5000"), stand.calls);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.FOLLOW_MS));
        assertEquals("then where the finger is by then", Arrays.asList("pause", "seek 5000", "seek 7000"), stand.calls);

        listener.onProgressChanged(bar, 8000, true);
        bar.setProgress(8000);
        new Listener(bar).stop();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.FOLLOW_MS));
        assertEquals("letting go seeks and plays, and a move still waiting doesn't come after",
                Arrays.asList("pause", "seek 5000", "seek 7000", "seek 8000", "play"), stand.calls);
    }

    /** A post with a strip shows its picture on the card instead, and the video stays where it was paused. */
    @Test
    public void aPostWithAStripLeavesTheVideoStill() {
        Settings.FEED_SEEK_BAR.save(true);
        Settings.SEEK_PREVIEW.save(true);
        Map<String, Object> info = new HashMap<>();
        info.put("sprite_urls", Collections.singletonList("https://example.invalid/sprite.jpg"));
        info.put("thumbnail_width", 100);
        info.put("thumbnail_height", 124);
        info.put("thumbnails_per_row", 15);
        info.put("max_thumbnails_per_sprite", 105);
        info.put("thumbnail_duration", 1.1d);
        stand.sheet = SeekSheet.of(info);
        assertNotNull(stand.sheet);
        FeedSeekBar.progress(player, 3000, 15000);
        SeekBar bar = bar();
        SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
        bar.setPressed(true);
        new Listener(bar).start();
        listener.onProgressChanged(bar, 5000, true);
        listener.onProgressChanged(bar, 9000, true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FeedSeekBar.FOLLOW_MS));
        assertEquals(Collections.singletonList("pause"), stand.calls);
    }

    /** The bar's own drag callbacks, as a finger on it gives them. */
    private static final class Listener {
        private final SeekBar bar;

        Listener(SeekBar bar) {
            this.bar = bar;
        }

        void start() {
            SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
            if (listener != null) listener.onStartTrackingTouch(bar);
        }

        void stop() {
            SeekBar.OnSeekBarChangeListener listener = shadowOf(bar).getOnSeekBarChangeListener();
            if (listener != null) listener.onStopTrackingTouch(bar);
        }
    }
}
