/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertFalse;
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

/** What the chat reels hook answers (#350). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ChatReelsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SWIPE_CHAT_REELS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        Settings.STOP_AFTER_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The patch is in Manager's default selection, so its switch starts off. */
    @Test
    public void theSwitchStartsOff() {
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        assertFalse(Settings.SWIPE_CHAT_REELS.get());
        assertFalse("off to start, Instagram's no stands", ChatReels.chain(0));
    }

    /** With the switch on, the chat reels test says yes whatever Instagram's server said. */
    @Test
    public void onEveryChatChainsItsReels() {
        assertTrue("the server said no", ChatReels.chain(0));
        assertTrue("the server said yes", ChatReels.chain(1));
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());
    }

    /** Off, paused or before the settings are read, the test keeps Instagram's answer. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.SWIPE_CHAT_REELS.save(false);
        assertFalse("off", ChatReels.chain(0));
        assertTrue("off, Instagram's own yes", ChatReels.chain(1));
        Settings.SWIPE_CHAT_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ChatReels.chain(0));
        assertTrue("paused, Instagram's own yes", ChatReels.chain(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertFalse("no context", ChatReels.chain(0));
            assertTrue("no context, Instagram's own yes", ChatReels.chain(1));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertFalse("pause undecided", ChatReels.chain(0));
            assertTrue("pause undecided, Instagram's own yes", ChatReels.chain(1));
        });

        assertTrue("back on", ChatReels.chain(0));
    }

    /** The stop starts off, so the viewer goes on to Instagram's suggested reels (#390). */
    @Test
    public void theStopStartsOff() {
        assertFalse(Settings.STOP_AFTER_CHAT_REELS.get());
        assertFalse("off to start, the suggestions come", ChatReels.stop());
    }

    /** On, the viewer stops after the chat's reels, whether or not the swipe switch is on. */
    @Test
    public void onTheViewerStopsAfterTheChatsReels() {
        Settings.STOP_AFTER_CHAT_REELS.save(true);
        assertTrue(ChatReels.stop());
        Settings.SWIPE_CHAT_REELS.save(false);
        assertTrue("Instagram's own chain stops too", ChatReels.stop());
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());
    }

    /** Off, paused or before the settings are read, the viewer goes on to the suggestions. */
    @Test
    public void offPausedAndUnreadyGoOnToTheSuggestions() {
        Settings.STOP_AFTER_CHAT_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ChatReels.stop());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", ChatReels.stop()));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse("pause undecided", ChatReels.stop()));

        assertTrue("back on", ChatReels.stop());
        Settings.STOP_AFTER_CHAT_REELS.save(false);
        assertFalse("off", ChatReels.stop());
    }

    /** Stand-ins for Instagram's viewer sources, named as its enum names them. */
    private enum Source { DIRECT, DIRECT_SEARCH, CLIPS_TAB }

    /** A cached reel is skipped only in a viewer opened from a chat, and only with the stop on (#390). */
    @Test
    public void aCachedReelIsSkippedOnlyAfterAChatsReels() {
        assertFalse("off to start, the cached reel goes in", ChatReels.stopCached(Source.DIRECT));
        Settings.STOP_AFTER_CHAT_REELS.save(true);
        assertTrue("a chat's viewer", ChatReels.stopCached(Source.DIRECT));
        assertFalse("the Reels tab", ChatReels.stopCached(Source.CLIPS_TAB));
        assertFalse("search in chats", ChatReels.stopCached(Source.DIRECT_SEARCH));
        assertFalse("no source", ChatReels.stopCached(null));
        assertFalse("not a source", ChatReels.stopCached("DIRECT"));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ChatReels.stopCached(Source.DIRECT));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue("back on", ChatReels.stopCached(Source.DIRECT));
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());
    }

    /** The loading placeholder is left out only after a chat's reels with the stop on, never added (#390). */
    @Test
    public void thePlaceholderIsLeftOutOnlyAfterAChatsReels() {
        assertTrue("off to start, Instagram's placeholder stays", ChatReels.placeholder(Source.DIRECT, 1));
        Settings.STOP_AFTER_CHAT_REELS.save(true);
        assertFalse("a chat's viewer", ChatReels.placeholder(Source.DIRECT, 1));
        assertTrue("the Reels tab", ChatReels.placeholder(Source.CLIPS_TAB, 1));
        assertTrue("search in chats", ChatReels.placeholder(Source.DIRECT_SEARCH, 1));
        assertTrue("no source", ChatReels.placeholder(null, 1));
        for (Object source : new Object[] {Source.DIRECT, Source.CLIPS_TAB, null}) {
            assertFalse("Instagram asked for none: " + source, ChatReels.placeholder(source, 0));
        }

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", ChatReels.placeholder(Source.DIRECT, 1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertTrue("no context", ChatReels.placeholder(Source.DIRECT, 1)));
        assertFalse("back on", ChatReels.placeholder(Source.DIRECT, 1));
    }
}
