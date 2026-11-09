/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** A reel's profile goes on the tab only with the switch on and a tab bar to keep (#385). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelProfileTabBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int BAR = 11;
    private final Object factory = new Object();
    private final Object session = new Object();
    private final Object config = new Object();
    private Activity activity;
    private int pushes;

    @Before
    public void screen() {
        activity = Robolectric.buildActivity(Activity.class).create().get();
        FrameLayout root = new FrameLayout(activity);
        View bar = new View(activity);
        bar.setId(BAR);
        root.addView(bar);
        activity.setContentView(root);
        Settings.REEL_PROFILE_TAB_BAR.save(true);
    }

    @After
    public void reset() {
        Settings.REEL_PROFILE_TAB_BAR.resetToDefault();
    }

    private void count(Object on, Object from, Object as, Object what) {
        assertSame(activity, on);
        assertSame(factory, from);
        assertSame(session, as);
        assertSame(config, what);
        pushes++;
    }

    private boolean open(int bar) {
        return ReelProfileTabBar.open(activity, bar, factory, session, config, this::count);
    }

    @Test
    public void theProfileGoesOnTheTabWhenTheScreenHasATabBar() {
        assertTrue(open(BAR));
        assertEquals(1, pushes);
    }

    @Test
    public void theSwitchStartsOffAndLeavesTheProfileToInstagram() {
        Settings.REEL_PROFILE_TAB_BAR.resetToDefault();

        assertFalse(open(BAR));
        assertEquals(0, pushes);
    }

    /** Instagram's own screen for a profile has no tab bar, so a reel playing in one keeps its route. */
    @Test
    public void aScreenWithNoTabBarLeavesTheProfileToInstagram() {
        assertFalse(open(99));
        assertFalse(open(0));
        assertEquals(0, pushes);
    }

    @Test
    public void aFinishingScreenLeavesTheProfileToInstagram() {
        activity.finish();

        assertFalse(open(BAR));
        assertEquals(0, pushes);
    }

    /** A push that fails still opens the profile, in Instagram's own screen. */
    @Test
    public void aPushThatThrowsLeavesTheProfileToInstagram() {
        assertFalse(ReelProfileTabBar.open(activity, BAR, factory, session, config, (on, from, as, what) -> {
            throw new IllegalStateException("state already saved");
        }));
    }

    @Test
    public void somethingOtherThanAnActivityIsLeftAlone() {
        assertFalse(ReelProfileTabBar.open(new Object(), factory, session, config));
    }
}
