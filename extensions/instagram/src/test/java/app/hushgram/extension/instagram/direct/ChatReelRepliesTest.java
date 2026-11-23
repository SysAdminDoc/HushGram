/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The reply bar under a reel swiped to from a chat gets that reel's own message (#488). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ChatReelRepliesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object first = new Object();
    private final Object second = new Object();
    private final Object again = new Object();
    /** Two chats' collectors. */
    private final Object here = new Object();
    private final Object there = new Object();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SWIPE_CHAT_REELS.save(true);
        ChatReels.chain(0);
        HookStatus.clear();
        ChatReels.forgetReplies();
        ChatReels.tapped();
    }

    @After
    public void restore() {
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ChatReels.chain(0);
        HookStatus.clear();
        ChatReels.forgetReplies();
    }

    /**
     * Instagram's own chat reels test chains a chat's reels with the switch off, and its reply bar
     * quotes the tapped message there too, so that viewer gets the swiped reels' own messages (#662).
     */
    @Test
    public void instagramsOwnChainGetsTheReelsOwnMessages() {
        Settings.SWIPE_CHAT_REELS.save(false);
        assertFalse("off, and Instagram doesn't chain", ChatReels.chaining());
        assertFalse("Instagram's no", ChatReels.chain(0));
        assertTrue("Instagram's yes", ChatReels.chain(1));
        assertTrue("chained by Instagram", ChatReels.chaining());
        ChatReels.chain(0);
        assertFalse("Instagram's no again", ChatReels.chaining());
        Settings.SWIPE_CHAT_REELS.save(true);
        assertTrue("the switch", ChatReels.chaining());
    }

    /**
     * Instagram keeps the tapped reel's message and answers it, or nothing, for every reel swiped to.
     * The swiped reel gets its own message instead, and the tapped one keeps Instagram's.
     */
    @Test
    public void aSwipedReelGetsItsOwnMessage() {
        ChatReels.rememberReply(here, "m1", "a1", first);
        ChatReels.rememberReply(here, "m2", "b2", second);
        assertSame("Instagram quoted the tapped message", second, ChatReels.reply("m2", "a1"));
        assertSame("Instagram hid the bar", second, ChatReels.reply("m2", null));
        assertNull("the tapped reel keeps Instagram's", ChatReels.reply("m1", "a1"));
        assertSame("the tapped reel with the bar hidden", first, ChatReels.reply("m1", null));
        assertNull("a reel the chat didn't have", ChatReels.reply("m3", "a1"));
        assertNull("no media", ChatReels.reply(null, "a1"));
    }

    /**
     * Instagram runs a chat's messages through the collector as the chat loads, before any tap, and
     * only one of the opener's ways in runs them again (#658). The replies collected before the tap
     * answer its viewer, and stay once the viewer, an activity of its own, closes and the chat's
     * activity comes back, for a second tap in the same chat.
     */
    @Test
    public void repliesCollectedBeforeTheTapAnswerTheViewer() {
        ActivityController<Activity> chat = Robolectric.buildActivity(Activity.class).setup();
        ChatReels.rememberReply(here, "m1", "a1", first);
        ChatReels.rememberReply(here, "m2", "b2", second);
        ChatReels.covered();
        assertTrue(ChatReels.open("reply-tap", new View(chat.get())));
        assertSame("collected as the chat loaded, before the tap", second, ChatReels.reply("m2", null));
        assertSame("Instagram quoted the tapped message", second, ChatReels.reply("m2", "a1"));
        chat.pause();
        chat.resume();
        assertTrue("a second tap in the same chat", ChatReels.open("reply-tap-again", new View(chat.get())));
        assertSame("still answers", second, ChatReels.reply("m2", "a1"));
        assertSame("with the bar hidden too", second, ChatReels.reply("m2", null));
    }

    /**
     * Each chat keeps its own reels' messages, so a reel shared in two chats answers from the viewer's
     * own chat, named by the message Instagram's info quotes, for the rest of that tap. A tap with
     * nothing named yet answers from the chat collected from last.
     */
    @Test
    public void eachChatAnswersItsOwnViewer() {
        ChatReels.rememberReply(here, "m1", "a1", first);
        ChatReels.rememberReply(here, "m2", "b2", second);
        ChatReels.rememberReply(there, "m9", "z9", first);
        ChatReels.rememberReply(there, "m2", "c3", again);
        assertSame("Instagram's info names this chat", second, ChatReels.reply("m2", "a1"));
        assertSame("and it stands for the rest of the tap", second, ChatReels.reply("m2", null));
        assertSame("the other chat's viewer", again, ChatReels.reply("m2", "z9"));
        ChatReels.tapped();
        assertSame("a new tap, nothing named", again, ChatReels.reply("m2", null));
        assertNull("the chat collected from last doesn't have it", ChatReels.reply("m1", null));
    }

    /** A reel shared twice answers the message remembered last, unless Instagram's is one of its own. */
    @Test
    public void aReelSharedTwiceAnswersItsLatestMessage() {
        ChatReels.rememberReply(here, "m2", "b2", second);
        ChatReels.rememberReply(here, "m2", "c3", again);
        assertSame(again, ChatReels.reply("m2", "a1"));
        assertNull("Instagram's is one of the reel's", ChatReels.reply("m2", "b2"));
        ChatReels.rememberReply(here, "m2", "b2", second);
        assertSame("met again, last again", second, ChatReels.reply("m2", "a1"));
        ChatReels.rememberReply(here, "m2", "", first);
        ChatReels.rememberReply(here, "m2", null, first);
        ChatReels.rememberReply(here, null, "d4", first);
        assertSame("no message, nothing remembered", second, ChatReels.reply("m2", "a1"));
    }

    /**
     * A reel paged in past the chat's loaded messages has no message remembered, and Instagram's own
     * lookup misses it too, so its quick reactions did nothing (#804). The info built from the message
     * the reel came with answers it, unless it names no message or the one Instagram already quotes.
     */
    @Test
    public void aReelPastTheLoadedMessagesAnswersTheMessageItCameWith() {
        Map<Object, String> named = new HashMap<>();
        named.put(first, "a1");
        named.put(second, "b2");
        named.put(again, "");
        assertSame("Instagram had nothing", second, ChatReels.carried(null, second, named::get));
        assertSame("Instagram quoted another message", second, ChatReels.carried("a1", second, named::get));
        assertNull("Instagram already quotes it", ChatReels.carried("b2", second, named::get));
        assertNull("the reel came with no message", ChatReels.carried(null, null, named::get));
        assertNull("an empty message", ChatReels.carried(null, again, named::get));
        assertNull("no message read", ChatReels.carried(null, new Object(), named::get));

        ChatReels.rememberReply(here, "m2", "b2", second);
        assertTrue("Instagram quotes one of the reel's remembered messages", ChatReels.remembers("m2", "b2"));
        assertFalse("another message", ChatReels.remembers("m2", "a1"));
        assertFalse("another reel", ChatReels.remembers("m3", "b2"));
        assertFalse("no reel", ChatReels.remembers(null, "b2"));
        assertFalse("Instagram quoted nothing", ChatReels.remembers("m2", null));
    }

    /** Only so many chats, reels of one chat and messages of one reel are kept. */
    @Test
    public void onlySoManyAreKept() {
        for (int i = 0; i <= ChatReels.REPLIES_KEPT; i++) ChatReels.rememberReply(here, "m" + i, "a" + i, first);
        assertNull("the oldest reel", ChatReels.reply("m0", null));
        assertSame("the newest reel", first, ChatReels.reply("m" + ChatReels.REPLIES_KEPT, null));
        for (int i = 0; i <= ChatReels.MESSAGES_KEPT; i++) ChatReels.rememberReply(here, "x", "b" + i, i == 0 ? second : first);
        assertSame("its oldest message is gone, so Instagram's no longer matches", first, ChatReels.reply("x", "b0"));
        assertNull("its newest", ChatReels.reply("x", "b" + ChatReels.MESSAGES_KEPT));

        ChatReels.forgetReplies();
        Object[] chats = new Object[ChatReels.CHATS_KEPT + 1];
        for (int i = 0; i < chats.length; i++) {
            chats[i] = new Object();
            ChatReels.rememberReply(chats[i], "c" + i, "k" + i, first);
            ChatReels.rememberReply(chats[i], "d" + i, "j" + i, second);
        }
        assertSame("the second oldest chat", second, ChatReels.reply("d1", "k1"));
        assertNull("the oldest chat is gone", ChatReels.reply("d0", "k0"));
        assertSame("the newest chat", second, ChatReels.reply("d" + ChatReels.CHATS_KEPT, "k" + ChatReels.CHATS_KEPT));
    }

    /**
     * Another viewer, the switch off, a pause, unread settings or a reel the bridges can't read keep
     * Instagram's info. The bridges are stubs here: no count, no media and no message.
     */
    @Test
    public void everythingElseKeepsInstagramsInfo() {
        Object instagram = new Object();
        ChatReels.rememberReply(here, "m2", "b2", second);
        assertSame("the stub's empty adapter", instagram, ChatReels.replyTo(instagram, Source.DIRECT, new Object(), 0));
        assertSame("no adapter", instagram, ChatReels.replyTo(instagram, Source.DIRECT, null, 0));
        assertSame("no page", instagram, ChatReels.replyTo(instagram, Source.DIRECT, new Object(), -1));
        assertSame("the Reels tab", instagram, ChatReels.replyTo(instagram, Source.CLIPS_TAB, new Object(), 0));
        assertSame("the stub's reel has no media", instagram, ChatReels.replyInfo(instagram, Source.DIRECT, new Object()));
        assertSame("no reel", instagram, ChatReels.replyInfo(instagram, Source.DIRECT, null));
        assertSame("the Reels tab", instagram, ChatReels.replyInfo(instagram, Source.CLIPS_TAB, new Object()));
        assertNull("nothing from Instagram", ChatReels.replyInfo(null, Source.CLIPS_TAB, new Object()));

        Settings.SWIPE_CHAT_REELS.save(false);
        assertSame("off", instagram, ChatReels.replyInfo(instagram, Source.DIRECT, new Object()));
        Settings.SWIPE_CHAT_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", instagram, ChatReels.replyTo(instagram, Source.DIRECT, new Object(), 0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("no context", instagram, ChatReels.replyInfo(instagram, Source.DIRECT, new Object())));

        ChatReels.collected(new Object(), new Object(), new Object());
        ChatReels.collected(null, null, null);
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());
    }

    private enum Source { DIRECT, CLIPS_TAB }
}
