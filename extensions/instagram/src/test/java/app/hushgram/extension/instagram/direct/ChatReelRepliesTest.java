/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

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

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SWIPE_CHAT_REELS.save(true);
        HookStatus.clear();
        ChatReels.forgetReplies();
        ChatReels.tapped();
    }

    @After
    public void restore() {
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ChatReels.forgetReplies();
    }

    /**
     * Instagram keeps the tapped reel's message and answers it, or nothing, for every reel swiped to.
     * The swiped reel gets its own message instead, and the tapped one keeps Instagram's.
     */
    @Test
    public void aSwipedReelGetsItsOwnMessage() {
        ChatReels.rememberReply("m1", "a1", first);
        ChatReels.rememberReply("m2", "b2", second);
        assertSame("Instagram quoted the tapped message", second, ChatReels.reply("m2", "a1"));
        assertSame("Instagram hid the bar", second, ChatReels.reply("m2", null));
        assertNull("the tapped reel keeps Instagram's", ChatReels.reply("m1", "a1"));
        assertSame("the tapped reel with the bar hidden", first, ChatReels.reply("m1", null));
        assertNull("a reel the chat didn't have", ChatReels.reply("m3", "a1"));
        assertNull("no media", ChatReels.reply(null, "a1"));
    }

    /** A reel shared twice answers the message remembered last, unless Instagram's is one of its own. */
    @Test
    public void aReelSharedTwiceAnswersItsLatestMessage() {
        ChatReels.rememberReply("m2", "b2", second);
        ChatReels.rememberReply("m2", "c3", again);
        assertSame(again, ChatReels.reply("m2", "a1"));
        assertNull("Instagram's is one of the reel's", ChatReels.reply("m2", "b2"));
        ChatReels.rememberReply("m2", "b2", second);
        assertSame("met again, last again", second, ChatReels.reply("m2", "a1"));
        ChatReels.rememberReply("m2", "", first);
        ChatReels.rememberReply("m2", null, first);
        ChatReels.rememberReply(null, "d4", first);
        assertSame("no message, nothing remembered", second, ChatReels.reply("m2", "a1"));
    }

    /** A new tap, in this chat or another, forgets the last one's messages until the opener adds the new ones. */
    @Test
    public void aNewTapStartsOver() {
        ChatReels.rememberReply("m2", "b2", second);
        ChatReels.tapped();
        assertNull("the last chat's", ChatReels.reply("m2", "a1"));
        ChatReels.rememberReply("m3", "c3", again);
        assertSame(again, ChatReels.reply("m3", "a1"));
        assertNull("forgotten for good", ChatReels.reply("m2", "a1"));
    }

    /**
     * The viewer is an activity of its own, so the tapped chat's activity coming back after it paused
     * means the viewer closed, and its replies don't stay in memory until the next tap.
     */
    @Test
    public void theChatComingBackForgetsTheViewersReplies() {
        ActivityController<Activity> chat = Robolectric.buildActivity(Activity.class).setup();
        ActivityController<Activity> other = Robolectric.buildActivity(Activity.class).setup();
        ChatReels.covered();
        assertTrue(ChatReels.open("reply-tap", new View(chat.get())));
        ChatReels.rememberReply("m2", "b2", second);
        other.pause();
        other.resume();
        assertSame("another activity coming back", second, ChatReels.reply("m2", "a1"));
        chat.pause();
        assertSame("the viewer is up", second, ChatReels.reply("m2", "a1"));
        chat.resume();
        assertNull("the viewer closed", ChatReels.reply("m2", "a1"));
        ChatReels.rememberReply("m2", "b2", second);
        chat.pause();
        chat.resume();
        assertSame("only once per tap", second, ChatReels.reply("m2", "a1"));
    }

    /** Only so many reels, and so many messages of one reel, are kept. */
    @Test
    public void onlySoManyAreKept() {
        for (int i = 0; i <= ChatReels.REPLIES_KEPT; i++) ChatReels.rememberReply("m" + i, "a" + i, first);
        assertNull("the oldest reel", ChatReels.reply("m0", null));
        assertSame("the newest reel", first, ChatReels.reply("m" + ChatReels.REPLIES_KEPT, null));
        for (int i = 0; i <= ChatReels.MESSAGES_KEPT; i++) ChatReels.rememberReply("x", "b" + i, i == 0 ? second : first);
        assertSame("its oldest message is gone, so Instagram's no longer matches", first, ChatReels.reply("x", "b0"));
        assertNull("its newest", ChatReels.reply("x", "b" + ChatReels.MESSAGES_KEPT));
    }

    /**
     * Another viewer, the switch off, a pause, unread settings or a reel the bridges can't read keep
     * Instagram's info. The bridges are stubs here: no count, no media and no message.
     */
    @Test
    public void everythingElseKeepsInstagramsInfo() {
        Object instagram = new Object();
        ChatReels.rememberReply("m2", "b2", second);
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

        ChatReels.collected(new Object(), new Object());
        ChatReels.collected(null, null);
        assertTrue(HookStatus.missing(FamilyNames.CHAT_REELS).toString(), HookStatus.missing(FamilyNames.CHAT_REELS).isEmpty());
    }

    private enum Source { DIRECT, CLIPS_TAB }
}
