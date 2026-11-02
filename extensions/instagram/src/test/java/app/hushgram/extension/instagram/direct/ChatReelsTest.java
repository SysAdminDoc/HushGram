/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
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
import org.robolectric.shadows.ShadowSystemClock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        Settings.ONLY_RECEIVED_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ChatReels.covered();
        ChatReels.forgetSent();
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

    /** Only received reels starts off, and a sent reel leaves the chain only with it on (#460). */
    @Test
    public void aSentReelLeavesTheChainOnlyWithOnlyReceivedOn() {
        assertFalse(Settings.ONLY_RECEIVED_CHAT_REELS.get());
        assertTrue("off, an unsent message", ChatReels.received(new Object(), new Object()));
        Settings.ONLY_RECEIVED_CHAT_REELS.save(true);
        assertTrue("on, a message the stub says wasn't sent", ChatReels.received(new Object(), new Object()));
        assertTrue("null", ChatReels.received(null, null));
        assertTrue("a received reel", ChatReels.received(false, "a1"));
        assertFalse("a sent reel", ChatReels.received(true, "a1"));
        assertFalse("a sent reel with no id", ChatReels.received(true, null));
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());
    }

    /** The reel just tapped stays in the chain even when you sent it. */
    @Test
    public void theTappedReelStaysEvenWhenSent() {
        Settings.ONLY_RECEIVED_CHAT_REELS.save(true);
        Object tapped = new Object() {
            @Override public String toString() {
                return "MessageIdentifier{itemId=a1}";
            }
        };
        assertTrue(ChatReels.open(tapped, null));
        assertTrue("the tapped one", ChatReels.received(true, "a1"));
        assertFalse("another sent one", ChatReels.received(true, "b2"));
        ChatReels.covered();
        assertFalse("after the viewer opened", ChatReels.received(true, "a1"));
    }

    /** Off, paused or before the settings are read, every message joins the chain. */
    @Test
    public void pausedAndUnreadyKeepEveryMessage() {
        Settings.ONLY_RECEIVED_CHAT_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", ChatReels.received(new Object(), new Object()));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertTrue("no context", ChatReels.received(new Object(), new Object())));
    }

    /** A page of a chat's reels from the server, read by media id: the item itself here. */
    private static List<?> page(Object source, String... media) {
        return ChatReels.leaveOutSent(Arrays.asList(media), source, String::valueOf);
    }

    /**
     * The chat's reels the viewer pages from the server leave out the ones you sent, once the opener
     * has seen them, only from a chat and only with the switch on (#460).
     */
    @Test
    public void aServerPageLeavesOutTheReelsYouSentOnlyFromAChatWithTheSwitchOn() {
        ChatReels.rememberSent("m1", "a1");
        List<String> items = Arrays.asList("m0", "m1", "m2");
        assertSame("off, the page stands", items, ChatReels.leaveOutSent(items, Source.DIRECT, String::valueOf));

        Settings.ONLY_RECEIVED_CHAT_REELS.save(true);
        assertEquals(Arrays.asList("m0", "m2"), page(Source.DIRECT, "m0", "m1", "m2"));
        assertEquals("a page of only sent reels", Collections.emptyList(), page(Source.DIRECT, "m1"));
        assertEquals("a sent reel first", Collections.singletonList("m2"), page(Source.DIRECT, "m1", "m2"));
        assertSame("the Reels tab", items, ChatReels.leaveOutSent(items, Source.CLIPS_TAB, String::valueOf));
        assertSame("no source", items, ChatReels.leaveOutSent(items, null, String::valueOf));
        List<String> received = Arrays.asList("m0", "m2");
        assertSame("nothing you sent", received, ChatReels.leaveOutSent(received, Source.DIRECT, String::valueOf));
        assertSame("no page", null, ChatReels.leaveOutSent(null, Source.DIRECT, String::valueOf));
        assertEquals("a null item stays", Arrays.asList(null, "m2"),
            ChatReels.leaveOutSent(Arrays.asList(null, "m1", "m2"), Source.DIRECT, String::valueOf));
        assertSame("items with no media", items, ChatReels.leaveOutSent(items, Source.DIRECT, item -> null));
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());
    }

    /** The reel you tapped is forgotten as sent, so a page holding it keeps it; other sent ones go. */
    @Test
    public void theTappedReelStaysInAServerPageEvenWhenSent() {
        Settings.ONLY_RECEIVED_CHAT_REELS.save(true);
        ChatReels.rememberSent("m1", "a1");
        ChatReels.rememberSent("m2", "b2");
        Object tapped = new Object() {
            @Override public String toString() {
                return "MessageIdentifier{itemId=a1}";
            }
        };
        assertTrue(ChatReels.open(tapped, null));
        ChatReels.rememberSent("m1", "a1");
        ChatReels.covered();
        assertEquals(Arrays.asList("m0", "m1"), page(Source.DIRECT, "m0", "m1", "m2"));
    }

    /** Paused, before the settings are read or on a failure, the page stands. */
    @Test
    public void pausedUnreadyAndFailingPagesStand() {
        Settings.ONLY_RECEIVED_CHAT_REELS.save(true);
        ChatReels.rememberSent("m1", "a1");
        List<String> items = Arrays.asList("m0", "m1");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", items, ChatReels.leaveOutSent(items, Source.DIRECT, String::valueOf));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("no context", items, ChatReels.leaveOutSent(items, Source.DIRECT, String::valueOf)));
        assertSame("a failing read", items, ChatReels.leaveOutSent(items, Source.DIRECT, item -> {
            throw new IllegalStateException("no media");
        }));
        assertTrue("the stub's page stands", ChatReels.leaveOutSent(items, Source.DIRECT) == items);
    }

    /** Only the latest sent reels are remembered. */
    @Test
    public void onlyTheLatestSentReelsAreRemembered() {
        for (int i = 0; i <= ChatReels.SENT_KEPT; i++) ChatReels.rememberSent("m" + i, "a" + i);
        assertFalse("the oldest", ChatReels.isSent("m0"));
        assertTrue("the second oldest", ChatReels.isSent("m1"));
        assertTrue("the newest", ChatReels.isSent("m" + ChatReels.SENT_KEPT));
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

    /** A pull down refreshes everywhere but a chat's viewer with the stop on, where it would bring suggestions (#390). */
    @Test
    public void aPullDownRefreshesEverywhereButAChatsViewer() {
        assertTrue("off to start, Instagram refreshes", ChatReels.refresh(Source.DIRECT));
        Settings.STOP_AFTER_CHAT_REELS.save(true);
        assertFalse("a chat's viewer", ChatReels.refresh(Source.DIRECT));
        assertTrue("the Reels tab", ChatReels.refresh(Source.CLIPS_TAB));
        assertTrue("search in chats", ChatReels.refresh(Source.DIRECT_SEARCH));
        assertTrue("no source", ChatReels.refresh(null));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", ChatReels.refresh(Source.DIRECT));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertTrue("no context", ChatReels.refresh(Source.DIRECT)));
        assertFalse("back on", ChatReels.refresh(Source.DIRECT));
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

    /** A message as Instagram hands it: equal to another tap's, never the same object. */
    private static Object message(String id) {
        return new StringBuilder(id).toString();
    }

    /** A repeat tap on a chat's media while its viewer loads is dropped and counted, whatever the switches (#390). */
    @Test
    public void aRepeatTapWaitsForTheFirstViewer() {
        Settings.SWIPE_CHAT_REELS.save(false);
        assertTrue("the first tap", ChatReels.open(message("m1"), null));
        assertFalse("the second tap", ChatReels.open(message("m1"), null));
        assertFalse("a third", ChatReels.open(message("m1"), null));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("repeat tap on a chat's media"));
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());

        assertTrue("another message", ChatReels.open(message("m2"), null));
        assertFalse("the other message again", ChatReels.open(message("m2"), null));
    }

    /** A viewer that never shows doesn't hold the message forever. */
    @Test
    public void aRepeatTapOpensOnceTheWaitIsOver() {
        assertTrue(ChatReels.open(message("m1"), null));
        ShadowSystemClock.advanceBy(ChatReels.OPENING_MS - 1, TimeUnit.MILLISECONDS);
        assertFalse("still loading", ChatReels.open(message("m1"), null));
        ShadowSystemClock.advanceBy(2, TimeUnit.MILLISECONDS);
        assertTrue("past the wait", ChatReels.open(message("m1"), null));
    }

    /** The viewer is an activity of its own, so the chat's pausing means it's up and Back opens it again. */
    @Test
    public void theViewersActivityLetsTheNextTapOpen() {
        ActivityController<Activity> chat = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(ChatReels.open(message("m1"), null));
        assertFalse("loading", ChatReels.open(message("m1"), null));
        chat.pause();
        assertTrue("the viewer is up", ChatReels.open(message("m1"), null));
    }

    /** A viewer pushed in the chat's place takes the bubble off the screen, which lets the next tap open. */
    @Test
    public void theBubbleLeavingLetsTheNextTapOpen() {
        Activity chat = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout thread = new FrameLayout(chat);
        View bubble = new View(chat);
        thread.addView(bubble);
        chat.setContentView(thread);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the bubble is on the screen", bubble.isAttachedToWindow());

        assertTrue(ChatReels.open(message("m1"), bubble));
        assertFalse("loading", ChatReels.open(message("m1"), bubble));
        thread.removeView(bubble);
        assertTrue("the viewer is up", ChatReels.open(message("m1"), bubble));
    }

    /** Paused, before the settings are read and for no message, every tap opens. */
    @Test
    public void pausedUnreadyAndNoMessageAlwaysOpen() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", ChatReels.open(message("m1"), null));
        assertTrue("paused, again", ChatReels.open(message("m1"), null));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertTrue("no context", ChatReels.open(message("m1"), null));
            assertTrue("no context, again", ChatReels.open(message("m1"), null));
        });
        assertTrue("no message", ChatReels.open(null, null));
        assertTrue("no message, again", ChatReels.open(null, null));

        assertTrue("back on", ChatReels.open(message("m1"), null));
        assertFalse("back on, again", ChatReels.open(message("m1"), null));
    }
}
