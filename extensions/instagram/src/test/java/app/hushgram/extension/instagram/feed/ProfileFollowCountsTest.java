/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide Followers and Following on profiles (#771): the numbers alone, or the whole buttons with no gap. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfileFollowCountsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout row;
    private LinearLayout posts;
    private TextView postCount;
    private LinearLayout followers;
    private TextView followersCount;
    private TextView followersLabel;
    private LinearLayout following;
    private TextView followingCount;
    private TextView followingLabel;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        int followersId = View.generateViewId();
        int followingId = View.generateViewId();
        int followersCountId = View.generateViewId();
        int followingCountId = View.generateViewId();
        for (ViewHider hider : new ViewHider[] {ProfileFollowCounts.buttons(), ProfileFollowCounts.counts()}) {
            hider.resetForTests();
            hider.idForTests(activity, ProfileFollowCounts.FOLLOWERS, followersId);
            hider.idForTests(activity, ProfileFollowCounts.FOLLOWING, followingId);
            hider.idForTests(activity, ProfileFollowCounts.FOLLOWERS_COUNT, followersCountId);
            hider.idForTests(activity, ProfileFollowCounts.FOLLOWING_COUNT, followingCountId);
        }

        // The profile header's count row as 456 lays it out: three stacks, each a number over its label.
        row = new LinearLayout(activity);
        posts = stack();
        postCount = new TextView(activity);
        posts.addView(postCount);
        posts.addView(new TextView(activity));
        followers = stack();
        followers.setId(followersId);
        followersCount = new TextView(activity);
        followersCount.setId(followersCountId);
        followersLabel = new TextView(activity);
        followers.addView(followersCount);
        followers.addView(followersLabel);
        following = stack();
        following.setId(followingId);
        followingCount = new TextView(activity);
        followingCount.setId(followingCountId);
        followingLabel = new TextView(activity);
        following.addView(followingCount);
        following.addView(followingLabel);
        row.addView(posts);
        row.addView(followers);
        row.addView(following);
        FrameLayout root = new FrameLayout(activity);
        root.addView(row);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.resetToDefault();
        Settings.HIDE_PROFILE_FOLLOW_BUTTONS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        ProfileFollowCounts.resetForTests();
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.resetToDefault();
        Settings.HIDE_PROFILE_FOLLOW_BUTTONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private LinearLayout stack() {
        LinearLayout stack = new LinearLayout(activity);
        stack.setOrientation(LinearLayout.VERTICAL);
        return stack;
    }

    private boolean frame() {
        return row.getViewTreeObserver().dispatchOnPreDraw();
    }

    private void watch() {
        ProfileFollowCounts.buttons().watch(activity);
        ProfileFollowCounts.counts().watch(activity);
    }

    @Test
    public void offToStartLeavesTheProfileAlone() {
        assertFalse(Settings.HIDE_PROFILE_FOLLOW_COUNTS.get());
        assertFalse(Settings.HIDE_PROFILE_FOLLOW_BUTTONS.get());

        watch();

        assertFalse(ProfileFollowCounts.listeningForTests(activity));
        assertFalse(frame());
        assertEquals(View.VISIBLE, followers.getVisibility());
        assertEquals(View.VISIBLE, followersCount.getVisibility());
        assertEquals(View.VISIBLE, following.getVisibility());
        assertEquals(View.VISIBLE, followingCount.getVisibility());
    }

    @Test
    public void countsOnTakesTheNumbersAndKeepsTheWords() {
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.save(true);

        watch();

        assertTrue("the frame that would show the numbers is canceled", frame());
        assertEquals(View.GONE, followersCount.getVisibility());
        assertEquals(View.GONE, followingCount.getVisibility());
        assertEquals("the word stays", View.VISIBLE, followersLabel.getVisibility());
        assertEquals("the word stays", View.VISIBLE, followingLabel.getVisibility());
        assertEquals("the button stays, so a tap opens the list", View.VISIBLE, followers.getVisibility());
        assertEquals(View.VISIBLE, following.getVisibility());
        assertEquals("the post count stays", View.VISIBLE, postCount.getVisibility());
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_FOLLOW_COUNTS).toString(),
                HookStatus.missing(FamilyNames.PROFILE_FOLLOW_COUNTS).isEmpty());
    }

    @Test
    public void buttonsOnTakesBothButtonsWithNoGap() {
        Settings.HIDE_PROFILE_FOLLOW_BUTTONS.save(true);

        watch();

        assertTrue(frame());
        assertEquals("gone, not invisible, so the row closes up", View.GONE, followers.getVisibility());
        assertEquals(View.GONE, following.getVisibility());
        assertEquals("the post count stays", View.VISIBLE, posts.getVisibility());
        assertEquals("nothing is taken out", 3, row.getChildCount());
        assertTrue(HookStatus.missing(FamilyNames.PROFILE_FOLLOW_COUNTS).toString(),
                HookStatus.missing(FamilyNames.PROFILE_FOLLOW_COUNTS).isEmpty());
    }

    /** With the buttons gone their numbers go too, so the window is searched once. */
    @Test
    public void countsStandDownUnderButtons() {
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.save(true);
        Settings.HIDE_PROFILE_FOLLOW_BUTTONS.save(true);

        watch();

        assertFalse(ProfileFollowCounts.counts().listeningForTests(activity));
        assertTrue(ProfileFollowCounts.buttons().listeningForTests(activity));
        assertTrue(frame());
        assertEquals(View.GONE, followers.getVisibility());
        assertEquals(View.GONE, following.getVisibility());
    }

    /** Instagram binds the header again on a refresh; a number it shows again is put back to gone before the frame. */
    @Test
    public void aNumberShownAgainGoesAgain() {
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.save(true);
        watch();
        assertTrue(frame());

        followersCount.setVisibility(View.VISIBLE);

        assertTrue(frame());
        assertEquals(View.GONE, followersCount.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.save(true);
        Settings.HIDE_PROFILE_FOLLOW_BUTTONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);

        watch();

        assertFalse(ProfileFollowCounts.listeningForTests(activity));
        assertEquals(View.VISIBLE, followers.getVisibility());
        assertEquals(View.VISIBLE, followersCount.getVisibility());
    }
}
