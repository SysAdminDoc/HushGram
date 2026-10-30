/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide Follow on stories: the Follow button kept out of a story's header, and put back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryFollowButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final AtomicBoolean on = new AtomicBoolean(true);
    private final BooleanSupplier switches = on::get;
    private final AtomicInteger reads = new AtomicInteger();
    private ActivityController<Activity> controller;
    private Activity activity;
    private int rowId;
    private int followId;
    private int menuId;

    /** Reads a header's root the way the patch's stub reads a view holder's item view, and counts it. */
    private final StoryFollowButton.Reader reader = holder -> {
        reads.incrementAndGet();
        return ((Header) holder).root;
    };

    /** A story's view holder: the name outside the row, and the Follow button and menu in it. */
    private final class Header {
        final FrameLayout root = new FrameLayout(activity);
        final TextView name = new TextView(activity);
        final LinearLayout row = new LinearLayout(activity);
        View follow = new TextView(activity);
        final ImageView menu = new ImageView(activity);

        Header() {
            name.setText("someone");
            root.addView(name);
            row.setId(rowId);
            follow.setId(followId);
            ((TextView) follow).setText("Follow");
            menu.setId(menuId);
            row.addView(follow);
            row.addView(menu);
            root.addView(row);
        }

        /** The row before Instagram inflates the button: a ViewStub with the button's id. */
        Header stubbed() {
            row.removeView(follow);
            follow = new ViewStub(activity);
            follow.setId(followId);
            row.addView(follow, 0);
            return this;
        }

        /** The stub inflated: a TextView under the same id in its place. */
        void inflate() {
            row.removeView(follow);
            follow = new TextView(activity);
            follow.setId(followId);
            row.addView(follow, 0);
        }

        Header shown() {
            activity.setContentView(root);
            settle();
            return this;
        }

        /** One frame's pre-draw on the header's window. True when a listener canceled the frame. */
        boolean frame() {
            return row.getViewTreeObserver().dispatchOnPreDraw();
        }
    }

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        StoryFollowButton.resetForTests();
        rowId = View.generateViewId();
        followId = View.generateViewId();
        menuId = View.generateViewId();
        StoryFollowButton.putIdForTests(activity, StoryFollowButton.CONTAINER, rowId);
        StoryFollowButton.putIdForTests(activity, StoryFollowButton.FOLLOW, followId);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryFollowButton.resetForTests();
        Settings.HIDE_STORY_FOLLOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private void bind(Header header) {
        StoryFollowButton.bind(header, reader, switches);
    }

    private static void settle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void onHidesOnlyTheFollowButton() {
        Header header = new Header().shown();
        bind(header);

        header.frame();

        assertEquals(View.GONE, header.follow.getVisibility());
        assertEquals("the menu stays", View.VISIBLE, header.menu.getVisibility());
        assertEquals("the name stays", View.VISIBLE, header.name.getVisibility());
        assertEquals("the row stays", View.VISIBLE, header.row.getVisibility());
        assertEquals("nothing is taken out of the row", 2, header.row.getChildCount());
        assertFalse("a frame with nothing to hide goes ahead", header.frame());
        assertTrue(HookStatus.missing(FamilyNames.STORY_FOLLOW).toString(), HookStatus.missing(FamilyNames.STORY_FOLLOW).isEmpty());
    }

    /** The frame that would show the button is canceled and redrawn without it, so it never flashes. */
    @Test
    public void theFrameThatWouldShowItIsCanceled() {
        Header header = new Header().shown();
        bind(header);

        assertTrue(header.frame());
        assertFalse(header.frame());
    }

    @Test
    public void aLaterShowIsUndoneAtTheNextFrame() {
        Header header = new Header().shown();
        bind(header);
        header.frame();

        header.follow.setVisibility(View.VISIBLE);
        header.frame();

        assertEquals(View.GONE, header.follow.getVisibility());
    }

    /** A recycled header gets one keeper however often it's bound, and each header its own. */
    @Test
    public void eachHeaderGetsOneKeeper() {
        Header header = new Header().shown();
        bind(header);
        bind(header);
        bind(header);
        assertEquals(1, StoryFollowButton.keepersForTests());

        header.frame();
        assertEquals(View.GONE, header.follow.getVisibility());

        Header other = new Header();
        ((ViewGroup) header.root).addView(other.root);
        bind(other);
        assertEquals(2, StoryFollowButton.keepersForTests());
        other.frame();
        assertEquals(View.GONE, other.follow.getVisibility());
    }

    /** Each bind puts the button back first, so Instagram's own bind decides, then the next frame hides it again. */
    @Test
    public void eachBindPutsItBackForInstagramToDecide() {
        Header header = new Header().shown();
        bind(header);
        header.frame();

        bind(header);
        assertEquals("put back for Instagram's bind", View.VISIBLE, header.follow.getVisibility());
        header.frame();
        assertEquals(View.GONE, header.follow.getVisibility());
    }

    @Test
    public void offPutsItBackAtTheNextBind() {
        Header header = new Header().shown();
        bind(header);
        header.frame();

        on.set(false);
        assertFalse("off, a frame changes nothing", header.frame());
        assertEquals("not before the next bind", View.GONE, header.follow.getVisibility());

        bind(header);
        assertEquals(View.VISIBLE, header.follow.getVisibility());
        assertEquals("the keeper goes with the switch", 0, StoryFollowButton.keepersForTests());
        assertFalse(header.frame());
        assertEquals(View.VISIBLE, header.follow.getVisibility());

        on.set(true);
        bind(header);
        header.frame();
        assertEquals("back on, it's hidden again", View.GONE, header.follow.getVisibility());
    }

    /** What's put back is what Instagram had, and a button something else has shown since is left as it is. */
    @Test
    public void whatsPutBackIsWhatInstagramHad() {
        Header header = new Header().shown();
        header.follow.setVisibility(View.INVISIBLE);
        bind(header);
        header.frame();
        assertEquals(View.GONE, header.follow.getVisibility());
        on.set(false);
        bind(header);
        assertEquals(View.INVISIBLE, header.follow.getVisibility());

        on.set(true);
        header.follow.setVisibility(View.VISIBLE);
        bind(header);
        header.frame();
        on.set(false);
        header.follow.setVisibility(View.INVISIBLE);
        bind(header);
        assertEquals(View.INVISIBLE, header.follow.getVisibility());
    }

    /** A ViewStub under the button's id is never shown, which would inflate it; once inflated, the button is hidden. */
    @Test
    public void aViewStubIsLeftAlone() {
        Header header = new Header().stubbed().shown();
        View stub = header.follow;
        int before = stub.getVisibility();
        bind(header);

        assertFalse(header.frame());

        assertSame("still the stub, not inflated", stub, header.row.getChildAt(0));
        assertEquals(before, stub.getVisibility());

        header.inflate();
        header.frame();
        assertEquals(View.GONE, header.follow.getVisibility());

        on.set(false);
        Header stubbed = new Header().stubbed();
        ((ViewGroup) header.root).addView(stubbed.root);
        bind(stubbed);
        assertSame(stubbed.follow, stubbed.row.getChildAt(0));
    }

    /** A switch that was never on reads no header and adds no listener. */
    @Test
    public void aSwitchNeverOnCostsNothing() {
        on.set(false);
        Header header = new Header().shown();

        bind(header);
        bind(header);

        assertEquals(0, reads.get());
        assertEquals(0, StoryFollowButton.keepersForTests());
        assertFalse(StoryFollowButton.keptForTests());
        assertFalse(header.frame());
        assertEquals(View.VISIBLE, header.follow.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.HIDE_STORY_FOLLOW.save(true);
        assertTrue(StoryFollowButton.switchedOn());
        Header header = new Header().shown();
        StoryFollowButton.bind(header, reader, StoryFollowButton::switchedOn);
        header.frame();
        assertEquals(View.GONE, header.follow.getVisibility());

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(StoryFollowButton.switchedOn());
        StoryFollowButton.bind(header, reader, StoryFollowButton::switchedOn);
        header.frame();
        assertEquals("paused, Instagram's button is back", View.VISIBLE, header.follow.getVisibility());

        PauseForTests.resume();
        StoryFollowButton.bind(header, reader, StoryFollowButton::switchedOn);
        header.frame();
        assertEquals(View.GONE, header.follow.getVisibility());

        SettingsContextRule.withoutContext(() -> assertFalse("settings not read", StoryFollowButton.switchedOn()));
        Settings.HIDE_STORY_FOLLOW.resetToDefault();
        assertFalse("off to start", StoryFollowButton.switchedOn());
    }

    /** Detached, the row stops listening; attached again, it listens again. */
    @Test
    public void aDetachedRowStopsListening() {
        Header header = new Header().shown();
        bind(header);
        ViewTreeObserver window = header.row.getViewTreeObserver();

        FrameLayout elsewhere = new FrameLayout(activity);
        activity.setContentView(elsewhere);
        settle();
        assertFalse(header.row.isAttachedToWindow());
        assertFalse("a detached row isn't listened to", window.dispatchOnPreDraw());
        assertEquals(View.VISIBLE, header.follow.getVisibility());

        header.shown();
        header.frame();
        assertEquals(View.GONE, header.follow.getVisibility());
    }

    /** A row bound before it's on screen starts listening when it's attached. */
    @Test
    public void aRowBoundBeforeItsShownListensOnceAttached() {
        Header header = new Header();
        bind(header);
        assertEquals(1, StoryFollowButton.keepersForTests());

        header.shown();
        header.frame();

        assertEquals(View.GONE, header.follow.getVisibility());
    }

    @Test
    public void aBuildWithoutTheRowOrTheIdSaysSo() {
        Header header = new Header().shown();
        StoryFollowButton.putIdForTests(activity, StoryFollowButton.CONTAINER, View.generateViewId());
        bind(header);
        assertTrue(HookStatus.missing(FamilyNames.STORY_FOLLOW).toString().contains(StoryFollowButton.CONTAINER));
        assertEquals(0, StoryFollowButton.keepersForTests());

        HookStatus.clear();
        StoryFollowButton.putIdForTests(activity, StoryFollowButton.CONTAINER, rowId);
        StoryFollowButton.putIdForTests(activity, StoryFollowButton.FOLLOW, 0);
        bind(header);
        assertTrue(HookStatus.missing(FamilyNames.STORY_FOLLOW).toString().contains(StoryFollowButton.FOLLOW));
        assertEquals(0, StoryFollowButton.keepersForTests());
        assertEquals(View.VISIBLE, header.follow.getVisibility());
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        Header header = new Header().shown();
        StoryFollowButton.bind(header, holder -> {
            throw new IllegalStateException("holder went away");
        }, switches);
        StoryFollowButton.bind(null, reader, switches);
        StoryFollowButton.bind(header, reader, () -> {
            throw new IllegalStateException("settings went away");
        });

        bind(header);
        on.set(false);
        BooleanSupplier failing = () -> {
            throw new IllegalStateException("settings went away");
        };
        StoryFollowButton.bind(new Header(), reader, failing);
        assertEquals(View.VISIBLE, header.follow.getVisibility());
    }
}
