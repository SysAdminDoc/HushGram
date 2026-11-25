/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static app.hushgram.extension.instagram.direct.ViewerConfigs.BLEND;
import static app.hushgram.extension.instagram.direct.ViewerConfigs.CHAT;
import static app.hushgram.extension.instagram.direct.ViewerConfigs.CHATS_BLEND;
import static app.hushgram.extension.instagram.direct.ViewerConfigs.CLIPS_TAB;
import static app.hushgram.extension.instagram.direct.ViewerConfigs.NO_CHAT;
import static app.hushgram.extension.instagram.direct.ViewerConfigs.SEARCH;
import static app.hushgram.extension.instagram.direct.ViewerConfigs.THREAD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Whether a Reels viewer is a chat's, read off its config, for every switch keyed on a chat's viewer
 * (#804): a reel tapped in a chat that has a Blend opens as the Blend's once Instagram has loaded the
 * Blend, still holding the chat, so the source alone no longer tells.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ViewerChatTest {
    private static final String FAMILY = FamilyNames.CHAT_REELS;

    @Before
    public void readConfigs() {
        HookStatus.clear();
        ViewerConfigs.install();
    }

    @After
    public void putTheBridgesBack() {
        ViewerConfigs.reset();
        HookStatus.clear();
    }

    @Test
    public void aViewerHoldingTheChatItWasOpenedFromIsTheChats() {
        assertTrue("opened as the chat's", ViewerChat.holdsChat(CHAT, FAMILY));
        assertTrue("opened as its Blend's, still holding the chat", ViewerChat.holdsChat(CHATS_BLEND, FAMILY));
        assertFalse("a Blend from the inbox holds no chat", ViewerChat.holdsChat(BLEND, FAMILY));
        assertFalse("a chat's source holding no chat", ViewerChat.holdsChat(NO_CHAT, FAMILY));
        assertFalse("the Reels tab", ViewerChat.holdsChat(CLIPS_TAB, FAMILY));
        assertFalse("a chat's search", ViewerChat.holdsChat(SEARCH, FAMILY));
        assertFalse("no config", ViewerChat.holdsChat(null, FAMILY));
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());
    }

    @Test
    public void aViewersSourceIsReadOffItsConfig() {
        assertEquals("BLEND", ((Enum<?>) ViewerChat.sourceOf(CHATS_BLEND, FAMILY)).name());
        assertEquals("CLIPS_TAB", ((Enum<?>) ViewerChat.sourceOf(CLIPS_TAB, FAMILY)).name());
        assertNull(ViewerChat.sourceOf(null, FAMILY));
    }

    @Test
    public void anEmptyChatIdIsNoChat() {
        assertFalse(ViewerChat.isChat(ViewerConfigs.Source.DIRECT, ""));
        assertFalse(ViewerChat.isChat("DIRECT", THREAD));
        assertTrue(ViewerChat.isChat(ViewerConfigs.Source.BLEND, THREAD));
    }

    /** A config the bridges can't read is no chat's, counted against the asking switch, and never throws. */
    @Test
    public void aConfigThatCantBeReadIsNoChatsAndIsCounted() {
        ViewerChat.readConfigsWith(config -> {
            throw new ClassCastException("not a config");
        }, config -> THREAD);
        assertFalse(ViewerChat.holdsChat(CHAT, FAMILY));
        assertNull(ViewerChat.sourceOf(CHAT, FAMILY));
        assertFalse("counted", HookStatus.missing(FAMILY).isEmpty());

        ViewerConfigs.reset();
        assertFalse("the stubs read no viewer as a chat's", ViewerChat.holdsChat(CHAT, FAMILY));
    }
}
