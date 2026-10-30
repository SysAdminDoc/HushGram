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
import android.view.ViewStub;
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

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide Follow on feed posts: the buttons beside the author go, the name, time and menu stay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedFollowButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private LinearLayout screen;
    private LinearLayout header;
    private TextView name;
    private TextView time;
    private View menu;
    private View inline;
    private View inHeader;
    private View subscribe;
    private ViewStub stub;
    private View storyFollow;
    private View shortcut;
    private View shortcutByStubId;
    private ViewStub shortcutStub;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        FeedFollowButton.hider().resetForTests();
        int headerId = View.generateViewId();
        int inlineId = View.generateViewId();
        int inHeaderId = View.generateViewId();
        int subscribeId = View.generateViewId();
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.HEADER, headerId);
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.INLINE_FOLLOW, inlineId);
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.HEADER_FOLLOW, inHeaderId);
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.SUBSCRIBE, subscribeId);
        int shortcutId = View.generateViewId();
        int shortcutStubId = View.generateViewId();
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.SUBSCRIBE_SHORTCUT, shortcutId);
        FeedFollowButton.hider().idForTests(activity, FeedFollowButton.SUBSCRIBE_SHORTCUT_STUB, shortcutStubId);

        screen = new LinearLayout(activity);
        screen.setOrientation(LinearLayout.VERTICAL);
        header = new LinearLayout(activity);
        header.setId(headerId);
        name = new TextView(activity);
        time = new TextView(activity);
        menu = new View(activity);
        inline = new View(activity);
        inline.setId(inlineId);
        inHeader = new View(activity);
        inHeader.setId(inHeaderId);
        subscribe = new View(activity);
        subscribe.setId(subscribeId);
        stub = new ViewStub(activity);
        stub.setId(View.generateViewId());
        header.addView(name);
        header.addView(time);
        header.addView(inline);
        header.addView(inHeader);
        header.addView(subscribe);
        header.addView(stub);
        header.addView(menu);
        storyFollow = new View(activity);
        storyFollow.setId(inlineId);
        // The Subscribe shortcut is in the post's own row, outside the header: once inflated under its
        // own id, and once under the stub's id, which a stub without an inflated id hands on.
        shortcut = new View(activity);
        shortcut.setId(shortcutId);
        shortcutByStubId = new View(activity);
        shortcutByStubId.setId(shortcutStubId);
        shortcutStub = new ViewStub(activity);
        shortcutStub.setId(shortcutStubId);
        screen.addView(header);
        screen.addView(storyFollow);
        screen.addView(shortcut);
        screen.addView(shortcutByStubId);
        screen.addView(shortcutStub);
        FrameLayout root = new FrameLayout(activity);
        root.addView(screen);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_FEED_FOLLOW.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        FeedFollowButton.hider().resetForTests();
        Settings.HIDE_FEED_FOLLOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private boolean frame() {
        return screen.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test
    public void offToStartLeavesTheButtonsAlone() {
        assertFalse(Settings.HIDE_FEED_FOLLOW.get());

        FeedFollowButton.hider().watch(activity);

        assertFalse(FeedFollowButton.hider().listeningForTests(activity));
        assertFalse(frame());
        assertEquals(View.VISIBLE, inline.getVisibility());
        assertEquals(View.VISIBLE, subscribe.getVisibility());
    }

    @Test
    public void onTakesTheButtonsOffAndLeavesTheNameTimeAndMenu() {
        Settings.HIDE_FEED_FOLLOW.save(true);

        FeedFollowButton.hider().watch(activity);

        assertTrue("the frame that would show the buttons is canceled", frame());
        assertEquals(View.GONE, inline.getVisibility());
        assertEquals(View.GONE, inHeader.getVisibility());
        assertEquals(View.GONE, subscribe.getVisibility());
        assertEquals("the name stays", View.VISIBLE, name.getVisibility());
        assertEquals("the time stays", View.VISIBLE, time.getVisibility());
        assertEquals("the menu stays", View.VISIBLE, menu.getVisibility());
        assertEquals("the stub is not touched", header, stub.getParent());
        assertEquals("nothing is taken out", 7, header.getChildCount());
        assertTrue(HookStatus.missing("Hide Follow on feed posts").toString(),
                HookStatus.missing("Hide Follow on feed posts").isEmpty());
    }

    @Test
    public void theSubscribeShortcutOutsideTheHeaderGoesToo() {
        Settings.HIDE_FEED_FOLLOW.save(true);

        FeedFollowButton.hider().watch(activity);

        assertTrue(frame());
        assertEquals("the shortcut's button", View.GONE, shortcut.getVisibility());
        assertEquals("a view that took the stub's id", View.GONE, shortcutByStubId.getVisibility());
        assertEquals(screen, shortcutStub.getParent());
        assertTrue(HookStatus.missing("Hide Follow on feed posts").toString(),
                HookStatus.missing("Hide Follow on feed posts").isEmpty());
    }

    @Test
    public void theShortcutStaysWithTheSwitchOff() {
        FeedFollowButton.hider().watch(activity);

        assertFalse(frame());
        assertEquals(View.VISIBLE, shortcut.getVisibility());
        assertEquals(View.VISIBLE, shortcutByStubId.getVisibility());
    }

    @Test
    public void theSameIdOutsideAFeedPostHeaderStays() {
        Settings.HIDE_FEED_FOLLOW.save(true);

        FeedFollowButton.hider().watch(activity);
        frame();

        assertEquals("a story's Follow button is not a feed post's", View.VISIBLE, storyFollow.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_FEED_FOLLOW.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);

        FeedFollowButton.hider().watch(activity);

        assertFalse(FeedFollowButton.hider().listeningForTests(activity));
        assertEquals(View.VISIBLE, inline.getVisibility());
    }

    @Test
    public void nativeSubscribeEligibilityUsesTheSameSwitch() {
        assertTrue("off preserves an eligible Subscribe", FeedFollowButton.showSubscribe(1));
        assertTrue("native booleans arrive as ints", FeedFollowButton.showSubscribe(-1));
        assertFalse("native ineligibility stays ineligible", FeedFollowButton.showSubscribe(0));
        Settings.HIDE_FEED_FOLLOW.save(true);
        assertFalse("on omits just that native child", FeedFollowButton.showSubscribe(1));
        assertFalse(FeedFollowButton.showSubscribe(0));
        Settings.HIDE_FEED_FOLLOW.save(false);
        assertTrue("turning it off restores native eligibility", FeedFollowButton.showSubscribe(1));
    }

    @Test
    public void pausedAndUnreadyKeepNativeSubscribe() {
        Settings.HIDE_FEED_FOLLOW.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        assertTrue("Pause leaves the native Subscribe", FeedFollowButton.showSubscribe(1));
        assertFalse(FeedFollowButton.showSubscribe(0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertTrue("unready leaves the native Subscribe", FeedFollowButton.showSubscribe(1));
            assertFalse(FeedFollowButton.showSubscribe(0));
        });
    }

    @Test
    public void aFailingSubscribeChoiceKeepsNativeEligibility() {
        assertTrue(FeedFollowButton.showSubscribe(1, () -> { throw new IllegalStateException("unreadable choice"); }));
        String failures = HookStatus.missing(FamilyNames.FEED_FOLLOW).toString();
        assertTrue(failures, failures.contains("'" + FeedFollowButton.HOOK + "'"));
        assertFalse(FeedFollowButton.showSubscribe(0, () -> { throw new AssertionError("must keep the native false"); }));
    }
}
