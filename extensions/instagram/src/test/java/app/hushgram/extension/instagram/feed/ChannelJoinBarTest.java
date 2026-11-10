/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the Join bar on channel posts (#625): the binder's details come back null with the switch on,
 * which is the path where Instagram sets the bar gone itself, and the bar's row folds to no height so
 * the feed's list doesn't keep an empty band. With the switch off the details come back as they were
 * and a folded row gets its height back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ChannelJoinBarTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stand in for the binder's parameters (LX/0kcF, the post and LX/0YGI on 454). */
    private final Object holder = new Object();
    private final Object post = new Object();
    private final Object details = new Object();

    private Function<Object, View> savedRowOf;
    private View row;

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_CHANNEL_JOIN_BAR.resetToDefault();
        HookStatus.clear();
        savedRowOf = ChannelJoinBar.rowOf;
        row = new LinearLayout(RuntimeEnvironment.getApplication());
        row.setLayoutParams(new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ChannelJoinBar.rowOf = given -> given == holder ? row : null;
    }

    @After
    public void restore() {
        ChannelJoinBar.rowOf = savedRowOf;
        Settings.HIDE_CHANNEL_JOIN_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndKeepsTheBar() {
        assertEquals(Boolean.FALSE, Settings.HIDE_CHANNEL_JOIN_BAR.defaultValue);
        assertSame(details, ChannelJoinBar.details(holder, post, details));
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
    }

    @Test
    public void withTheSwitchOnTheBarGoesFoldsAndIsCounted() {
        Settings.HIDE_CHANNEL_JOIN_BAR.save(true);

        assertNull(ChannelJoinBar.details(holder, post, details));
        assertEquals("folded to no height", 0, row.getLayoutParams().height);
        assertNull(ChannelJoinBar.details(holder, post, details));
        assertEquals(0, row.getLayoutParams().height);

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ChannelJoinBar.COUNT_HIDDEN + " 2"));
        assertTrue(HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString(),
                HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).isEmpty());
    }

    @Test
    public void switchedOffTheRowGetsItsHeightBack() {
        Settings.HIDE_CHANNEL_JOIN_BAR.save(true);
        assertNull(ChannelJoinBar.details(holder, post, details));
        assertEquals(0, row.getLayoutParams().height);

        Settings.HIDE_CHANNEL_JOIN_BAR.save(false);
        assertSame(details, ChannelJoinBar.details(holder, post, details));
        assertEquals("its own height back", ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
    }

    @Test
    public void pausedAndUnreadyKeepTheBar() {
        Settings.HIDE_CHANNEL_JOIN_BAR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", details, ChannelJoinBar.details(holder, post, details));
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("unready", details, ChannelJoinBar.details(holder, post, details)));

        assertNull("gone again once resumed and ready", ChannelJoinBar.details(holder, post, details));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ChannelJoinBar.COUNT_HIDDEN + " 1"));
    }

    @Test
    public void noDetailsStayNoDetails() {
        Settings.HIDE_CHANNEL_JOIN_BAR.save(true);
        assertNull(ChannelJoinBar.details(holder, post, null));
        assertEquals("Instagram's own path, nothing folded", ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
        assertFalse(HookStatus.report().toString().contains(ChannelJoinBar.COUNT_HIDDEN));
    }

    @Test
    public void aHolderWithNoRowStillHidesTheBar() {
        Settings.HIDE_CHANNEL_JOIN_BAR.save(true);
        assertNull(ChannelJoinBar.details(new Object(), post, details));
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
    }
}
