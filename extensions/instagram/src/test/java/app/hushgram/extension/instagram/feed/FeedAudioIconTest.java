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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide the audio icon on posts (#464): the flag the post state stores is set on a yes and left alone otherwise. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedAudioIconTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.HIDE_FEED_AUDIO_ICON.resetToDefault();
        Settings.HIDE_FEED_TAG_ICON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_FEED_AUDIO_ICON.resetToDefault();
        Settings.HIDE_FEED_TAG_ICON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertEquals(Boolean.FALSE, Settings.HIDE_FEED_AUDIO_ICON.defaultValue);
        assertFalse(Settings.HIDE_FEED_AUDIO_ICON.rebootApp);
    }

    @Test
    public void offToStartKeepsInstagramsFlag() {
        assertFalse(FeedAudioIcon.hides(0));
        assertTrue("a flag Instagram set stays set", FeedAudioIcon.hides(1));
    }

    @Test
    public void onSetsTheFlagAndCountsIt() {
        Settings.HIDE_FEED_AUDIO_ICON.save(true);

        assertTrue(FeedAudioIcon.hides(0));
        assertTrue(FeedAudioIcon.hides(0));

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FeedAudioIcon.COUNT_HIDDEN + " 2"));
        assertTrue(HookStatus.missing(FamilyNames.FEED_AUDIO_ICON).toString(),
                HookStatus.missing(FamilyNames.FEED_AUDIO_ICON).isEmpty());
    }

    /** The hook is handed an int, so any non-zero value is Instagram's own yes. */
    @Test
    public void anyNonZeroValueReadsAsYes() {
        assertTrue(FeedAudioIcon.hides(2));
        assertTrue(FeedAudioIcon.hides(-1));
    }

    @Test
    public void pausedAndUnreadyReadAsOff() {
        Settings.HIDE_FEED_AUDIO_ICON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", FeedAudioIcon.hides(0));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("unready", FeedAudioIcon.hides(0)));

        assertTrue("on again once resumed and ready", FeedAudioIcon.hides(0));
    }

    /** The tag icon (#762): a people tag's state comes back as no state while its switch is on. */
    @Test
    public void tagIconSwitchDropsOnlyPeopleTags() {
        Object state = new Object();
        assertSame("off to start", state, FeedAudioIcon.tagIndicator(state, Kind.PEOPLE));
        assertEquals(Boolean.FALSE, Settings.HIDE_FEED_TAG_ICON.defaultValue);
        assertFalse(Settings.HIDE_FEED_TAG_ICON.rebootApp);

        Settings.HIDE_FEED_TAG_ICON.save(true);

        assertNull(FeedAudioIcon.tagIndicator(state, Kind.PEOPLE));
        assertNull(FeedAudioIcon.tagIndicator(state, Kind.FB_USER));
        assertSame("product tags keep their icon", state, FeedAudioIcon.tagIndicator(state, Kind.PRODUCTS));
        assertSame("an unknown kind is left alone", state, FeedAudioIcon.tagIndicator(state, "PEOPLE"));
        assertSame("no kind is left alone", state, FeedAudioIcon.tagIndicator(state, null));
        assertNull("no state stays none", FeedAudioIcon.tagIndicator(null, Kind.PRODUCTS));
        assertFalse("the audio icon switch is its own", FeedAudioIcon.hides(0));

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FeedAudioIcon.COUNT_TAG_HIDDEN + " 2"));
    }

    @Test
    public void tagIconPausedAndUnreadyKeepTheState() {
        Object state = new Object();
        Settings.HIDE_FEED_TAG_ICON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", state, FeedAudioIcon.tagIndicator(state, Kind.PEOPLE));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("unready", state, FeedAudioIcon.tagIndicator(state, Kind.PEOPLE)));

        assertNull("on again once resumed and ready", FeedAudioIcon.tagIndicator(state, Kind.PEOPLE));
    }

    /** Stands in for Instagram's indicator kinds, which the hook reads by name. */
    private enum Kind { PEOPLE, FB_USER, PRODUCTS }

    /** Flipping the switch off puts the next state Instagram builds back as it would be. */
    @Test
    public void flippingItOffStopsSettingTheFlag() {
        Settings.HIDE_FEED_AUDIO_ICON.save(true);
        assertTrue(FeedAudioIcon.hides(0));

        Settings.HIDE_FEED_AUDIO_ICON.save(false);

        assertFalse(FeedAudioIcon.hides(0));
    }
}
