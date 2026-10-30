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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide captions (#395): the caption host of a post and of a reel go, the rest of the post stays. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HideCaptionsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout screen;
    private View postCaption;
    private View reelCaption;
    private TextView author;
    private View actions;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        HideCaptions.hider().resetForTests();
        int postId = View.generateViewId();
        int reelId = View.generateViewId();
        HideCaptions.hider().idForTests(activity, HideCaptions.POST_CAPTION, postId);
        HideCaptions.hider().idForTests(activity, HideCaptions.REEL_CAPTION, reelId);

        screen = new LinearLayout(activity);
        screen.setOrientation(LinearLayout.VERTICAL);
        author = new TextView(activity);
        actions = new View(activity);
        postCaption = new View(activity);
        postCaption.setId(postId);
        reelCaption = new View(activity);
        reelCaption.setId(reelId);
        screen.addView(author);
        screen.addView(actions);
        screen.addView(postCaption);
        screen.addView(reelCaption);
        FrameLayout root = new FrameLayout(activity);
        root.addView(screen);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_CAPTIONS.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        HideCaptions.hider().resetForTests();
        Settings.HIDE_CAPTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private boolean frame() {
        return screen.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test
    public void theSwitchStartsOff() {
        assertEquals(Boolean.FALSE, Settings.HIDE_CAPTIONS.defaultValue);
    }

    @Test
    public void offToStartLeavesCaptionsAlone() {
        HideCaptions.hider().watch(activity);

        assertFalse(HideCaptions.hider().listeningForTests(activity));
        assertFalse(frame());
        assertEquals(View.VISIBLE, postCaption.getVisibility());
        assertEquals(View.VISIBLE, reelCaption.getVisibility());
    }

    @Test
    public void onHidesBothCaptionsAndLeavesTheRest() {
        Settings.HIDE_CAPTIONS.save(true);

        HideCaptions.hider().watch(activity);

        assertTrue("the frame that would show them is canceled", frame());
        assertEquals(View.GONE, postCaption.getVisibility());
        assertEquals(View.GONE, reelCaption.getVisibility());
        assertEquals("the author stays", View.VISIBLE, author.getVisibility());
        assertEquals("the buttons stay", View.VISIBLE, actions.getVisibility());
        assertEquals("nothing is taken out", 4, screen.getChildCount());
        assertTrue(HookStatus.missing(FamilyNames.HIDE_CAPTIONS).toString(),
                HookStatus.missing(FamilyNames.HIDE_CAPTIONS).isEmpty());
    }

    @Test
    public void aCaptionInstagramShowsAgainIsHiddenBeforeTheFrame() {
        Settings.HIDE_CAPTIONS.save(true);
        HideCaptions.hider().watch(activity);
        frame();

        postCaption.setVisibility(View.VISIBLE);
        assertTrue(frame());

        assertEquals(View.GONE, postCaption.getVisibility());
    }

    @Test
    public void flippingItOffStopsHidingAndAReboundCaptionStays() {
        Settings.HIDE_CAPTIONS.save(true);
        HideCaptions.hider().watch(activity);
        frame();

        Settings.HIDE_CAPTIONS.save(false);
        frame();
        reelCaption.setVisibility(View.VISIBLE);

        assertFalse("no longer listening after the next frame", frame());
        assertEquals(View.VISIBLE, reelCaption.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_CAPTIONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        HideCaptions.hider().watch(activity);

        assertFalse(HideCaptions.hider().listeningForTests(activity));
        assertEquals(View.VISIBLE, postCaption.getVisibility());
        assertEquals(View.VISIBLE, reelCaption.getVisibility());
    }

    /** 453's feed caption is a component with no view: its render asks, and each yes is counted. */
    @Test
    public void withTheSwitchOnAFeedCaptionIsLeftOutAndCounted() {
        Settings.HIDE_CAPTIONS.save(true);

        assertTrue(HideCaptions.hideFeedCaption());
        assertTrue(HideCaptions.hideFeedCaption());

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HideCaptions.COUNT_HIDDEN + " 2"));
        assertTrue(HookStatus.missing(FamilyNames.HIDE_CAPTIONS).toString(),
                HookStatus.missing(FamilyNames.HIDE_CAPTIONS).isEmpty());
    }

    @Test
    public void offPausedAndUnreadyDrawTheFeedCaption() {
        assertFalse("off to start", HideCaptions.hideFeedCaption());

        Settings.HIDE_CAPTIONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", HideCaptions.hideFeedCaption());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("unready", HideCaptions.hideFeedCaption()));

        assertTrue("on again once resumed and ready", HideCaptions.hideFeedCaption());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HideCaptions.COUNT_HIDDEN + " 1"));
    }
}
