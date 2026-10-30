/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A long press on the Instagram logo at the top of Home opens HushGram, on a stand-in of Instagram
 * 450's header title: the view holding the logo, with Instagram's tap and the long
 * press Instagram gives it, which only takes the press on some accounts (an Instagram Plus account
 * gets its Change your app icon sheet there). The patch sends
 * Instagram's call giving that long press to {@link SettingsEntry#setLogoLongClickListener}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LogoPressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Activity activity;
    private FrameLayout title;
    private final AtomicInteger taps = new AtomicInteger();

    @Before public void setUp() {
        PauseForTests.resume();
        SettingsEntry.onClosedByUser();
        ((Application) RuntimeEnvironment.getApplication()).registerActivityLifecycleCallbacks(new SettingsEntry.OpenWhenResumed());
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        controller.windowFocusChanged(true);
        activity = controller.get();

        FrameLayout root = new FrameLayout(activity);
        title = new FrameLayout(activity);
        title.addView(new ImageView(activity));
        title.setOnClickListener(view -> taps.incrementAndGet());
        root.addView(title);
        activity.setContentView(root);
    }

    @After public void tearDown() throws Exception {
        SettingsEntry.onClosedByUser();
        PauseForTests.resume();
        Utils.awaitBackgroundTasksForTests();
    }

    /** Instagram's long press on the title, answering [takes] and counting each time it's asked. */
    private static View.OnLongClickListener instagrams(AtomicInteger asked, boolean takes) {
        return view -> {
            asked.incrementAndGet();
            return takes;
        };
    }

    private int shown() {
        shadowOf(Looper.getMainLooper()).idle();
        activity.getFragmentManager().executePendingTransactions();
        int count = 0;
        for (android.app.Fragment fragment : activity.getFragmentManager().getFragments()) {
            if (SettingsEntry.DIALOG_TAG.equals(fragment.getTag()) && !fragment.isRemoving()) count++;
        }
        return count;
    }

    private void closeSettings() {
        SettingsEntry.onClosedByUser();
        android.app.Fragment open = activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG);
        if (open != null) activity.getFragmentManager().beginTransaction().remove(open).commit();
        activity.getFragmentManager().executePendingTransactions();
    }

    @Test public void aLongPressOnTheLogoOpensHushGramAndATapStaysInstagrams() {
        AtomicInteger asked = new AtomicInteger();
        SettingsEntry.setLogoLongClickListener(title, instagrams(asked, false));

        assertTrue(title.isLongClickable());
        assertTrue(title.performLongClick());
        assertEquals("Instagram's long press ran as well as HushGram's", 0, asked.get());
        assertEquals(1, shown());
        assertEquals("the long press was also a tap", 0, taps.get());

        closeSettings();
        assertTrue(title.performClick());
        assertEquals("a tap is still Instagram's", 1, taps.get());
        assertEquals(0, shown());
    }

    /**
     * On an Instagram Plus account Instagram's long press on the logo opens its Change your app
     * icon sheet and takes the press. Asked first, it kept the logo from ever opening HushGram.
     */
    @Test public void instagramsAppIconSheetCantTakeTheLogoFromHushGram() {
        AtomicInteger asked = new AtomicInteger();
        SettingsEntry.setLogoLongClickListener(title, instagrams(asked, true));

        assertTrue(title.performLongClick());
        assertEquals("Instagram's app icon sheet took the long press", 0, asked.get());
        assertEquals(1, shown());
    }

    @Test public void drawingTheHeaderAgainKeepsTheLongPressWithInstagramsNewestListener() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        SettingsEntry.setLogoLongClickListener(title, instagrams(first, false));
        SettingsEntry.setLogoLongClickListener(title, instagrams(second, false));

        assertTrue(title.performLongClick());
        assertEquals(1, shown());
        closeSettings();

        // HushGram's own listener handed back in wraps Instagram's once, not twice.
        View.OnLongClickListener current = shadowOf(title).getOnLongClickListener();
        SettingsEntry.setLogoLongClickListener(title, current);
        View.OnLongClickListener listener = shadowOf(title).getOnLongClickListener();
        assertTrue(listener instanceof SettingsEntry.LogoPress);
        assertFalse("HushGram's listener wrapped its own",
                ((SettingsEntry.LogoPress) listener).instagrams instanceof SettingsEntry.LogoPress);

        // Once the logo is off screen the press is Instagram's, and only its newest listener hears it.
        ((ViewGroup) title.getParent()).removeView(title);
        assertFalse(listener.onLongClick(title));
        assertEquals("the listener Instagram replaced was asked", 0, first.get());
        assertEquals("Instagram's listener was asked more than once for one press", 1, second.get());
        assertEquals(0, shown());
    }

    @Test public void pausedTheLogoStillOpensHushGram() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        SettingsEntry.setLogoLongClickListener(title, instagrams(new AtomicInteger(), false));

        assertTrue(title.performLongClick());
        assertEquals("Pause shut the way back to its own switch", 1, shown());
    }

    @Test public void withoutAListenerFromInstagramTheLogoStillOpensHushGram() {
        SettingsEntry.setLogoLongClickListener(title, null);

        assertTrue(title.isLongClickable());
        assertTrue(title.performLongClick());
        assertEquals(1, shown());
    }

    /**
     * Home's newer header keeps its logo in an image view with no listener of Instagram's; the
     * patch hands that view over once, as the header finds it.
     */
    @Test public void theNewerHeadersLogoOpensHushGram() {
        ImageView logo = new ImageView(activity);
        ((ViewGroup) title.getParent()).addView(logo);
        assertFalse(logo.isLongClickable());

        SettingsEntry.watchLogo(logo);

        assertTrue(logo.isLongClickable());
        assertTrue(logo.performLongClick());
        assertEquals(1, shown());
        assertEquals("a tap on the older header's title came with it", 0, taps.get());
    }

    @Test public void noLogoViewIsLeftAlone() {
        SettingsEntry.watchLogo(null);
        assertEquals(0, shown());
    }

    @Test public void aLogoNoLongerOnScreenLeavesThePressToInstagram() {
        AtomicInteger asked = new AtomicInteger();
        SettingsEntry.setLogoLongClickListener(title, instagrams(asked, false));
        View.OnLongClickListener listener = shadowOf(title).getOnLongClickListener();
        assertNotNull(listener);
        ((ViewGroup) title.getParent()).removeView(title);
        assertNull("the real logo was removed from its header", title.getParent());

        // A callback already in flight can outlive removal. performLongClick also runs Android's
        // unhandled context-menu fallback, which requires a parent that this view no longer has.
        assertFalse(listener.onLongClick(title));
        assertEquals(1, asked.get());
        assertEquals(0, shown());
    }
}
