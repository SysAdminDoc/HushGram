/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.feed.CommentEmojiRow;
import app.hushgram.extension.instagram.feed.ReelCorners;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StoryProgressBar;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** One activity's frames shared by every helper: one listener of each kind, each member told of its own layouts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ViewWatcherTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private final List<Member> members = new ArrayList<>();

    /** A member that notes what each pre-draw told it, and asks for a redraw when told to. */
    private final class Member implements ViewWatcher.Member {
        final List<Boolean> laidOut = new ArrayList<>();
        boolean wanted = true;
        boolean redraw;
        boolean leaveInPreDraw;

        Member() {
            members.add(this);
        }

        @Override
        public boolean wanted() {
            return wanted;
        }

        @Override
        public boolean preDraw(Activity owner, boolean laidOut) {
            this.laidOut.add(laidOut);
            if (leaveInPreDraw) ViewWatcher.leave(owner, this);
            return redraw;
        }
    }

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        for (Member member : members) ViewWatcher.leave(activity, member);
        CommentEmojiRow.resetForTests();
        ReelCorners.resetForTests();
        StoryProgressBar.resetForTests();
        Settings.HIDE_COMMENT_EMOJI_ROW.resetToDefault();
        Settings.ROUNDED_REEL_CORNERS.resetToDefault();
        Settings.THICKER_STORY_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private ViewTreeObserver tree() {
        return activity.getWindow().getDecorView().getViewTreeObserver();
    }

    /** How many listeners the window's observer holds in [field], one of its listener lists. */
    private int listeners(String field) throws Exception {
        Field list = ViewTreeObserver.class.getDeclaredField(field);
        list.setAccessible(true);
        Object held = list.get(tree());
        if (held == null) return 0;
        Method size = held.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        return (Integer) size.invoke(held);
    }

    private int preDraws() throws Exception {
        return listeners("mOnPreDrawListeners");
    }

    private int layouts() throws Exception {
        return listeners("mOnGlobalLayoutListeners");
    }

    /** One frame's pre-draw on the window. True when a listener canceled the frame. */
    private boolean frame() {
        return tree().dispatchOnPreDraw();
    }

    @Test
    public void threeMembersShareOneListenerOfEachKindUntilTheLastLeaves() throws Exception {
        int preDraws = preDraws();
        int layouts = layouts();
        Member first = new Member();
        Member second = new Member();
        Member third = new Member();

        ViewWatcher.join(activity, first);
        ViewWatcher.join(activity, second);
        ViewWatcher.join(activity, third);
        ViewWatcher.join(activity, second);

        assertEquals(preDraws + 1, preDraws());
        assertEquals(layouts + 1, layouts());
        assertTrue(ViewWatcher.listening(activity, first));
        assertTrue(ViewWatcher.listening(activity, third));

        ViewWatcher.leave(activity, first);
        ViewWatcher.leave(activity, third);
        assertEquals("one member left keeps the listeners", preDraws + 1, preDraws());
        assertFalse(ViewWatcher.joined(activity, first));
        assertTrue(ViewWatcher.listening(activity, second));

        ViewWatcher.leave(activity, second);
        assertEquals(preDraws, preDraws());
        assertEquals(layouts, layouts());
        assertFalse(ViewWatcher.joined(activity, second));
    }

    @Test
    public void eachMemberHearsOfALayoutOnceAndFromItsFirstFrame() {
        Member early = new Member();
        ViewWatcher.join(activity, early);
        frame();
        frame();

        Member late = new Member();
        ViewWatcher.join(activity, late);
        frame();
        tree().dispatchOnGlobalLayout();
        frame();
        frame();

        assertEquals(List.of(true, false, false, true, false), early.laidOut);
        assertEquals(List.of(true, true, false), late.laidOut);
    }

    @Test
    public void anyMemberThatChangedTheScreenCancelsTheFrameAndOthersStillRun() {
        Member quiet = new Member();
        Member changer = new Member();
        Member after = new Member();
        ViewWatcher.join(activity, quiet);
        ViewWatcher.join(activity, changer);
        ViewWatcher.join(activity, after);

        assertFalse(frame());
        changer.redraw = true;
        assertTrue(frame());
        assertEquals(2, quiet.laidOut.size());
        assertEquals(2, after.laidOut.size());
    }

    @Test
    public void aMemberLeavingInItsOwnPreDrawLeavesTheOthersListening() throws Exception {
        int preDraws = preDraws();
        Member leaving = new Member();
        Member staying = new Member();
        leaving.leaveInPreDraw = true;
        ViewWatcher.join(activity, leaving);
        ViewWatcher.join(activity, staying);

        frame();
        frame();

        assertEquals(1, leaving.laidOut.size());
        assertEquals(2, staying.laidOut.size());
        assertTrue(ViewWatcher.listening(activity, staying));
        assertEquals(preDraws + 1, preDraws());

        staying.leaveInPreDraw = true;
        frame();
        assertEquals(preDraws, preDraws());
    }

    @Test
    public void aWindowNotYetAttachedIsWaitedForAndAMemberNoLongerWantedIsDropped() throws Exception {
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(Activity.class).create();
        activity = controller.get();
        View decor = activity.getWindow().getDecorView();
        assertFalse(decor.isAttachedToWindow());
        Member wanted = new Member();
        Member unwanted = new Member();
        ViewWatcher.join(activity, wanted);
        ViewWatcher.join(activity, unwanted);
        assertTrue(ViewWatcher.joined(activity, wanted));
        assertFalse(ViewWatcher.listening(activity, wanted));
        unwanted.wanted = false;

        controller.start().resume().visible();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(decor.isAttachedToWindow());
        assertTrue(ViewWatcher.listening(activity, wanted));
        assertFalse(ViewWatcher.joined(activity, unwanted));
    }

    @Test
    public void theThreeViewHelpersOnTogetherAddOnePreDrawListener() throws Exception {
        int preDraws = preDraws();
        int layouts = layouts();
        Settings.HIDE_COMMENT_EMOJI_ROW.save(true);
        Settings.ROUNDED_REEL_CORNERS.save(true);
        Settings.THICKER_STORY_BAR.save(true);

        CommentEmojiRow.switchChanged(activity);
        ReelCorners.switchChanged(activity);
        StoryProgressBar.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(CommentEmojiRow.listeningForTests(activity));
        assertTrue(ReelCorners.listeningForTests(activity));
        assertTrue(StoryProgressBar.listeningForTests(activity));
        assertEquals(preDraws + 1, preDraws());
        assertEquals(layouts + 1, layouts());
        assertFalse("nothing to change, so the frame is drawn", frame());

        Settings.ROUNDED_REEL_CORNERS.save(false);
        frame();
        assertFalse(ReelCorners.listeningForTests(activity));
        assertTrue(StoryProgressBar.listeningForTests(activity));
        assertEquals(preDraws + 1, preDraws());

        Settings.HIDE_COMMENT_EMOJI_ROW.save(false);
        Settings.THICKER_STORY_BAR.save(false);
        frame();
        assertEquals("the last helper off takes the listener with it", preDraws, preDraws());
        assertEquals(layouts, layouts());
    }
}
