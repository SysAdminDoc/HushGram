/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Messages after a reel (#489): a banner over a chat's reels when the other person wrote after the reel on screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w1080dp-h1920dp")
public class ChatReelsNoticeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** The Reels viewer's activity, counting each Back it's handed. */
    public static class Viewer extends Activity {
        int backs;

        @SuppressWarnings("deprecation")
        @Override public void onBackPressed() {
            backs++;
        }
    }

    /** Stand-ins for Instagram's viewer sources, named as its enum names them. */
    private enum Source { DIRECT }

    private ActivityController<Viewer> controller;
    private Viewer activity;
    private FrameLayout root;
    private FrameLayout pager;
    /** Two chats' collectors. */
    private final Object here = new Object();
    private final Object there = new Object();

    @Before
    public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SWIPE_CHAT_REELS.save(true);
        Settings.MESSAGES_AFTER_CHAT_REELS.save(true);
        Settings.ONLY_RECEIVED_CHAT_REELS.save(false);
        HookStatus.clear();
        ChatReelsExit.resetForTests();
        ChatReelsNotice.resetForTests();
        ChatReels.forgetReplies();
        controller = Robolectric.buildActivity(Viewer.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        pager = new FrameLayout(activity);
        pager.addView(new RecyclerView(activity), new FrameLayout.LayoutParams(1080, 1920));
        root.addView(pager, new FrameLayout.LayoutParams(1080, 1920));
    }

    @After
    public void restore() {
        ChatReelsNotice.resetForTests();
        ChatReels.forgetReplies();
        ChatReelsExit.resetForTests();
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        Settings.MESSAGES_AFTER_CHAT_REELS.resetToDefault();
        Settings.ONLY_RECEIVED_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.close();
    }

    private void show() {
        activity.setContentView(root, new FrameLayout.LayoutParams(1080, 1920));
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void reel(String id, long millis, boolean viewer, String media) {
        reel(here, id, millis, viewer, media);
    }

    private static void reel(Object chat, String id, long millis, boolean viewer, String media) {
        ChatReelsNotice.remember(chat, id, millis, viewer, true, media);
    }

    private void text(String id, long millis, boolean viewer) {
        text(here, id, millis, viewer);
    }

    private static void text(Object chat, String id, long millis, boolean viewer) {
        ChatReelsNotice.remember(chat, id, millis, viewer, false, null);
    }

    private static String after(String media) {
        ChatReelsNotice.Note note = ChatReelsNotice.after(media);
        return note == null ? null : note.id;
    }

    /** The first message the other person sent after the reel and before the chain's next reel, in time order. */
    @Test
    public void theOtherPersonsFirstMessageBeforeTheNextReelIsPointedOut() {
        text("m3", 4000, false);
        reel("r2", 3000, false, "B");
        text("m2", 2000, false);
        text("m1", 1500, true);
        reel("r1", 1000, false, "A");
        assertEquals("your own message is passed over", "m2", after("A"));
        assertEquals("m3", after("B"));
        assertNull("a reel the chat doesn't hold", after("C"));
        assertNull(ChatReelsNotice.after(null));
    }

    @Test
    public void theNextReelEndsTheStretch() {
        reel("r1", 1000, false, "A");
        text("m1", 1500, true);
        reel("r2", 2000, false, "B");
        text("m2", 3000, false);
        assertNull("the message after the next reel isn't this one's", after("A"));
        assertEquals("m2", after("B"));
    }

    /** A reel you sent is in the chain, and ends the stretch, unless Only received reels leaves it out. */
    @Test
    public void aReelYouSentEndsTheStretchOnlyWhileItsInTheChain() {
        reel("r1", 1000, false, "A");
        reel("s1", 1500, true, "S");
        text("m1", 2000, false);
        assertNull(after("A"));
        assertEquals("m1", after("S"));
        Settings.ONLY_RECEIVED_CHAT_REELS.save(true);
        assertEquals("passed over with Only received reels on", "m1", after("A"));
    }

    @Test
    public void aReelSharedTwiceIsLookedAfterAtEachShare() {
        reel("r1", 1000, false, "A");
        reel("r2", 2000, false, "B");
        reel("r3", 3000, true, "A");
        text("m1", 4000, false);
        assertEquals("m1", after("A"));
        assertNull(after("B"));
    }

    @Test
    public void aMessageWithNoIdOrTimeIsLeftOut() {
        reel("r1", 1000, false, "A");
        text(null, 2000, false);
        text("", 2000, false);
        text("m0", 0, false);
        assertNull(after("A"));
        text("m1", 2000, false);
        assertEquals("m1", after("A"));
    }

    /**
     * Each chat keeps its own messages, and the banner looks only in the viewer's chat: the one the
     * reply bar's info named, or the chat noted from last while nothing names one.
     */
    @Test
    public void eachChatKeepsItsOwnMessages() {
        reel(here, "r1", 1000, false, "A");
        text(here, "m1", 2000, false);
        reel(there, "r9", 1000, false, "A");
        text(there, "m9", 3000, false);
        assertEquals("nothing named, the chat noted from last", "m9", after("A"));
        ChatReels.rememberReply(here, "A", "r1", new Object());
        ChatReels.rememberReply(there, "A", "r9", new Object());
        ChatReels.reply("A", "r1");
        assertEquals("the reply bar's info named this chat", "m1", after("A"));
        ChatReels.reply("A", "r9");
        assertEquals("and then the other", "m9", after("A"));
        Object other = new Object();
        ChatReels.rememberReply(other, "A", "x1", new Object());
        ChatReels.reply("A", "x1");
        assertNull("a viewer's chat with nothing noted", after("A"));
    }

    /**
     * Instagram runs a chat's messages through the collector as the chat loads, before the tap that
     * opens one of its reels (#658), so the notes taken then answer that tap's viewer.
     */
    @Test
    public void notesTakenBeforeTheTapAnswerItsViewer() {
        reel("r1", 1000, false, "A");
        text("m1", 2000, false);
        ChatReels.tapped();
        assertEquals("noted as the chat loaded", "m1", after("A"));
    }

    /** Without the patch's bridges, a page hands nothing on: no banner, no failure. */
    @Test
    public void aPageWithNothingToReadPutsUpNoBanner() {
        show();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        ChatReelsNotice.page(null, 0);
        ChatReelsNotice.page(new Object(), -1);
        ChatReelsNotice.page(new Object(), 0);
        ChatReelsNotice.note(null, null, null);
        ChatReelsNotice.note(new Object(), new Object(), null);
        ChatReelsNotice.note(new Object(), new Object(), new Object());
        idle();
        assertNull(ChatReelsNotice.shownBanner());
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains("chat reels message after"));
    }

    /** The banner goes over the chat's viewer near its top, comes down for a page with nothing after it, and a tap goes Back. */
    @Test
    public void theBannerShowsOverTheChatsViewerAndATapGoesBack() {
        show();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        ChatReelsNotice.point(new ChatReelsNotice.Note("m1", 2000, false, false, null));
        idle();
        TextView banner = ChatReelsNotice.shownBanner();
        assertNotNull("the banner is up", banner);
        assertEquals("New message after this reel", banner.getText().toString());
        assertSame("over the viewer, in the activity's content", activity.findViewById(android.R.id.content), banner.getParent());
        FrameLayout.LayoutParams place = (FrameLayout.LayoutParams) banner.getLayoutParams();
        assertTrue("below the viewer's top row: " + place.topMargin, place.topMargin >= ChatReelsNotice.TOP_DP);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ChatReelsNotice.SHOWN));

        ChatReelsNotice.point(null);
        idle();
        assertNull("down for a reel with nothing after it", ChatReelsNotice.shownBanner());
        assertNull(banner.getParent());

        ChatReelsNotice.point(new ChatReelsNotice.Note("m1", 2000, false, false, null));
        idle();
        banner = ChatReelsNotice.shownBanner();
        assertNotNull(banner);
        assertTrue(banner.performClick());
        assertEquals("Back to the chat", 1, activity.backs);
        assertNull("the banner goes with the tap", ChatReelsNotice.shownBanner());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ChatReelsNotice.TAPPED));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ChatReelsNotice.TRY_MS * (ChatReelsNotice.BACK_TRIES + 1)));
        assertTrue("the viewer's own list isn't the chat's: " + HookStatus.report(),
                HookStatus.report().toString().contains(ChatReelsNotice.NO_LIST));
    }

    @Test
    public void theBannerWaitsForTheSwitchesAndTheChatsViewer() {
        show();
        ChatReelsNotice.point(new ChatReelsNotice.Note("m1", 2000, false, false, null));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ChatReelsNotice.TRY_MS * (ChatReelsNotice.SHOW_TRIES + 1)));
        assertNull("no chat viewer, no banner", ChatReelsNotice.shownBanner());

        Settings.MESSAGES_AFTER_CHAT_REELS.save(false);
        assertFalse(ChatReelsNotice.on());
        Settings.MESSAGES_AFTER_CHAT_REELS.save(true);
        Settings.SWIPE_CHAT_REELS.save(false);
        assertFalse("rests with the swipe switch off", ChatReelsNotice.on());
        Settings.SWIPE_CHAT_REELS.save(true);
        assertTrue(ChatReelsNotice.on());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("rests while paused", ChatReelsNotice.on());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    /** A tap goes back to the list the tapped bubble sits in, never a list inside a chat's viewer. */
    @Test
    public void theChatsListIsTheTappedBubblesOutsideEveryChatViewer() {
        RecyclerView chat = new RecyclerView(activity);
        FrameLayout bubble = new FrameLayout(activity);
        chat.addView(bubble);
        root.addView(chat, 0, new FrameLayout.LayoutParams(1080, 1920));
        show();
        ChatReelsExit.viewer(pager, Source.DIRECT);
        assertSame("the biggest list outside the viewer", chat, ChatReelsNotice.chatList(activity));
        ChatReelsNotice.opened(bubble);
        assertSame(chat, ChatReelsNotice.chatList(activity));
        chat.setVisibility(View.GONE);
        assertNull("a hidden list and the viewer's own list aren't the chat's", ChatReelsNotice.chatList(activity));
        assertNull(ChatReelsNotice.chatList(null));
    }

    @Test
    public void aMessageIsFoundOnItsRow() {
        ChatHistory.Entry off = new ChatHistory.Entry(-1, "m1", null, null, null, 1000, false);
        ChatHistory.Entry on = new ChatHistory.Entry(4, "m2", null, null, null, 2000, false);
        assertEquals(4, ChatReelsNotice.positionOf(Arrays.asList(off, on), "m2"));
        assertEquals("not on a known row", -1, ChatReelsNotice.positionOf(Arrays.asList(off, on), "m1"));
        assertEquals(-1, ChatReelsNotice.positionOf(Collections.emptyList(), "m2"));
        assertEquals(-1, ChatReelsNotice.positionOf(null, "m2"));
        assertEquals(-1, ChatReelsNotice.positionOf(Arrays.asList(on), null));
    }
}
