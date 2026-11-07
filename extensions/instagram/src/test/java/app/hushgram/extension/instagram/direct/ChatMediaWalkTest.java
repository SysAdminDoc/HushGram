/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Swipe through a chat's photos and posts (#350): which pages a walk holds and what its pager does. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ChatMediaWalkTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final Object VIEWER = new Object();
    private static final Object KEY = new Object();

    private ActivityController<Activity> controller;

    private static final Object DELEGATE = new Object();
    private static final Object RECT = new Object();

    private static final class Message {
        final String id;
        final String kind;
        final long time;
        int type = 1;
        boolean expiring;
        boolean broken;
        boolean empty;
        boolean noPlainPage;

        Message(String id, String kind, long time) {
            this.id = id;
            this.kind = kind;
            this.time = time;
        }
    }

    private static final class Page {
        final String id;
        final int type;

        Page(String id) {
            this(id, 1);
        }

        Page(String id, int type) {
            this.id = id;
            this.type = type;
        }
    }

    private static final class Chat implements ChatMediaWalk.Messages {
        final List<Message> messages = new ArrayList<>();
        boolean unreadable;
        int reads;

        Chat(Message... held) {
            messages.addAll(Arrays.asList(held));
        }

        @Override public List<?> thread(Object key) {
            if (unreadable) throw new IllegalStateException("no thread");
            assertSame(KEY, key);
            reads++;
            return messages;
        }

        @Override public String kind(Object message) {
            return ((Message) message).kind;
        }

        @Override public long time(Object message) {
            return ((Message) message).time;
        }

        @Override public boolean expiring(Object message) {
            return ((Message) message).expiring;
        }

        @Override public Object plainPage(Object message) {
            return ((Message) message).noPlainPage ? null : page((Message) message);
        }

        @Override public Object sharedPage(Object message) {
            return page((Message) message);
        }

        private static Object page(Message message) {
            if (message.broken) throw new IllegalStateException("broken");
            return message.empty ? null : new Page(message.id, message.type);
        }

        @Override public String id(Object page) {
            return ((Page) page).id;
        }

        @Override public int type(Object page) {
            return ((Page) page).type;
        }
    }

    /** A chat's tap delegate: finds a tap's message by its id and opens the viewer the way Instagram's would. */
    private static final class Delegate implements ChatMediaWalk.Taps {
        final Chat chat;
        boolean bails;
        boolean failsBeforeOpening;
        boolean failsAfterOpening;
        int shows;
        Object shown;
        List<?> opened;

        Delegate(Chat chat) {
            this.chat = chat;
        }

        @Override public Object key(Object delegate) {
            assertSame(DELEGATE, delegate);
            return KEY;
        }

        @Override public Object message(Object delegate, Object key, Object identifier) {
            for (Message message : chat.messages) {
                if (message.id.equals(identifier)) return message;
            }
            return null;
        }

        @Override public ChatMediaWalk.Messages chat(Object delegate, View view) {
            return chat;
        }

        @Override public void show(Object delegate, Object rect, View view, Object page) {
            assertSame(RECT, rect);
            shows++;
            shown = page;
            if (failsBeforeOpening) throw new IllegalStateException("no viewer");
            if (bails) return;
            opened = ChatMediaWalk.open(VIEWER, KEY, Collections.singletonList(page), 0);
            if (failsAfterOpening) throw new IllegalStateException("the viewer failed after its opener");
        }
    }

    private static Delegate delegate(Message... held) {
        Chat chat = new Chat(held);
        ChatMediaWalk.chatForTests = chat;
        Delegate delegate = new Delegate(chat);
        ChatMediaWalk.tapsForTests = delegate;
        return delegate;
    }

    private static Message post(String id, long time) {
        return new Message(id, "XMA_MEDIA_SHARE", time);
    }

    private static int tapPost(String id) {
        return ChatMediaWalk.tap(DELEGATE, RECT, new View(RuntimeEnvironment.getApplication()), id);
    }

    private static Message plain(String id, long time) {
        return new Message(id, "MEDIA", time);
    }

    private static List<String> ids(List<?> pages) {
        List<String> ids = new ArrayList<>();
        for (Object page : pages) ids.add(((Page) page).id);
        return ids;
    }

    private static List<Object> tap(String id) {
        return Collections.singletonList(new Page(id));
    }

    private FrameLayout shownPager() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        FrameLayout pager = new FrameLayout(controller.get());
        pager.addView(new View(controller.get()));
        controller.get().setContentView(pager);
        return pager;
    }

    private static void startWalk() {
        ChatMediaWalk.chatForTests = new Chat(plain("a", 1), plain("b", 2));
        List<Object> tapped = tap("a");
        assertNotSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));
    }

    private static void offset(FrameLayout pager, float x, float y) {
        pager.getChildAt(0).setTranslationX(x);
        pager.getChildAt(0).setTranslationY(y);
    }

    private static void assertOffset(FrameLayout pager, float x, float y) {
        assertEquals(x, pager.getChildAt(0).getTranslationX(), 0f);
        assertEquals(y, pager.getChildAt(0).getTranslationY(), 0f);
    }

    @Before public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Settings.SWIPE_CHAT_MEDIA.save(true);
    }

    @After public void restore() {
        if (controller != null) controller.close();
        ChatMediaWalk.chatForTests = null;
        ChatMediaWalk.tapsForTests = null;
        ChatMediaWalk.pager(null);
        Settings.SWIPE_CHAT_MEDIA.resetToDefault();
        Settings.SWIPE_CHAT_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The acceptance case: three sent photos and a shared post, newest first as the store holds them. */
    @Test public void aChatWalksInChatOrderWithTheTappedPageInItsOwnSlot() {
        ChatMediaWalk.chatForTests = new Chat(
                plain("fourth", 40), new Message("post", "XMA_MEDIA_SHARE", 30), plain("second", 20), plain("first", 10));
        List<Object> tapped = tap("second");

        List<?> pages = ChatMediaWalk.open(VIEWER, KEY, tapped, 0);

        assertEquals(Arrays.asList("first", "second", "post", "fourth"), ids(pages));
        assertSame("the page Instagram built for the tap stays", tapped.get(0), pages.get(1));
        assertEquals(1, ChatMediaWalk.startIndex());
    }

    @Test public void theOldKindOfSharedPostGetsAPageToo() {
        ChatMediaWalk.chatForTests = new Chat(plain("first", 10), new Message("post", "MEDIA_SHARE", 20));
        assertEquals(Arrays.asList("first", "post"), ids(ChatMediaWalk.open(VIEWER, KEY, tap("first"), 0)));
        assertEquals(0, ChatMediaWalk.startIndex());
    }

    /** View once and disappearing media never get a page, nor anything else that isn't a photo, video or post. */
    @Test public void onlyPhotosVideosAndSharedPostsThatStayGetAPage() {
        Message disappearing = plain("disappearing", 25);
        disappearing.expiring = true;
        Message disappearingPost = new Message("disappearing post", "XMA_MEDIA_SHARE", 26);
        disappearingPost.expiring = true;
        ChatMediaWalk.chatForTests = new Chat(
                plain("first", 10), new Message("view once", "EXPIRING_MEDIA", 11), new Message("visual", "VISUAL_MEDIA", 12),
                new Message("text", "TEXT", 13), new Message("reel", "XMA_CLIPS_SHARE", 14), new Message("collage", "XMA_COLLAGE", 15),
                new Message("nameless", null, 16), disappearing, disappearingPost, plain("last", 30));

        assertEquals(Arrays.asList("first", "last"), ids(ChatMediaWalk.open(VIEWER, KEY, tap("first"), 0)));
    }

    /** A tap on view once or disappearing media isn't in the chat's pages, so it opens as Instagram has it. */
    @Test public void aTapTheWalkCantFindOpensAsInstagramHasIt() {
        Message disappearing = plain("disappearing", 25);
        disappearing.expiring = true;
        ChatMediaWalk.chatForTests = new Chat(plain("first", 10), plain("second", 20), disappearing);
        List<Object> tapped = tap("disappearing");

        assertSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));
    }

    @Test public void aLonePictureOpensAsInstagramHasIt() {
        ChatMediaWalk.chatForTests = new Chat(plain("only", 10), new Message("text", "TEXT", 20));
        List<Object> tapped = tap("only");
        assertSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));
    }

    @Test public void aMessageThatCantBeReadLosesOnlyItsOwnPage() {
        Message broken = plain("broken", 15);
        broken.broken = true;
        Message empty = new Message("empty", "XMA_MEDIA_SHARE", 16);
        empty.empty = true;
        ChatMediaWalk.chatForTests = new Chat(plain("first", 10), broken, empty, plain("last", 20));

        assertEquals(Arrays.asList("first", "last"), ids(ChatMediaWalk.open(VIEWER, KEY, tap("last"), 0)));
        assertEquals(1, ChatMediaWalk.startIndex());
    }

    /** Seen on 454: the builder read one field of a sent video and answered null, and the tap lost its walk. */
    @Test public void aPlainVideoTheBuilderCantReadStillGetsItsPage() {
        Message video = plain("video", 10);
        video.noPlainPage = true;
        ChatMediaWalk.chatForTests = new Chat(video, new Message("post", "XMA_MEDIA_SHARE", 20));
        List<Object> tapped = tap("video");

        List<?> pages = ChatMediaWalk.open(VIEWER, KEY, tapped, 0);

        assertEquals(Arrays.asList("video", "post"), ids(pages));
        assertSame(tapped.get(0), pages.get(0));
        assertEquals(0, ChatMediaWalk.startIndex());
    }

    @Test public void aChatThatCantBeReadOpensAsInstagramHasIt() {
        Chat chat = new Chat(plain("first", 10), plain("second", 20));
        chat.unreadable = true;
        ChatMediaWalk.chatForTests = chat;
        List<Object> tapped = tap("first");

        assertSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));
    }

    @Test public void anythingButOneTappedPageInAChatLeavesTheListAlone() {
        ChatMediaWalk.chatForTests = new Chat(plain("first", 10), plain("second", 20));
        List<Object> tapped = tap("first");
        List<Object> album = Arrays.asList(new Page("first"), new Page("second"));

        assertSame(album, ChatMediaWalk.open(VIEWER, KEY, album, 0));
        assertSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 1));
        assertSame(tapped, ChatMediaWalk.open(VIEWER, null, tapped, 0));
        assertSame(tapped, ChatMediaWalk.open(null, KEY, tapped, 0));
        assertNull(ChatMediaWalk.open(VIEWER, KEY, null, 0));
        List<Object> nothing = Collections.singletonList(null);
        assertSame(nothing, ChatMediaWalk.open(VIEWER, KEY, nothing, 0));
    }

    @Test public void switchedOffOrPausedAPictureOpensAsInstagramHasIt() {
        ChatMediaWalk.chatForTests = new Chat(plain("first", 10), plain("second", 20));
        List<Object> tapped = tap("first");

        Settings.SWIPE_CHAT_MEDIA.save(false);
        assertSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));

        Settings.SWIPE_CHAT_MEDIA.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertNotSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));
    }

    /** The pager lays pages out along one axis and leaves the other, so turning it clears what's left there. */
    @Test public void aWalkTurnsThePagerUprightAndHoldsDragToDismissWhileItsShown() {
        FrameLayout pager = shownPager();
        offset(pager, 40f, 0f);
        startWalk();

        ChatMediaWalk.pager(pager);

        assertOffset(pager, 0f, 0f);
        assertEquals(1, ChatMediaWalk.holdDismiss());

        pager.setVisibility(View.GONE);
        assertEquals("gone from the screen, the drag is Instagram's again", 0, ChatMediaWalk.holdDismiss());
    }

    @Test public void aPauseMidWalkHandsDragToDismissBack() {
        FrameLayout pager = shownPager();
        startWalk();
        ChatMediaWalk.pager(pager);
        assertEquals(1, ChatMediaWalk.holdDismiss());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        assertEquals(0, ChatMediaWalk.holdDismiss());
    }

    /** The viewer keeps its pager between opens, so what opens next sideways gets it back sideways. */
    @Test public void whatOpensAfterAWalkTurnsThePagerBack() {
        FrameLayout pager = shownPager();
        startWalk();
        ChatMediaWalk.pager(pager);
        offset(pager, 0f, 60f);
        ChatMediaWalk.chatForTests = new Chat();
        List<Object> album = Arrays.asList(new Page("first"), new Page("second"));

        assertSame(album, ChatMediaWalk.open(VIEWER, KEY, album, 0));
        ChatMediaWalk.pager(pager);

        assertOffset(pager, 0f, 0f);
        assertEquals(0, ChatMediaWalk.holdDismiss());
    }

    @Test public void aPagerNoWalkTurnedIsLeftAlone() {
        FrameLayout pager = shownPager();
        offset(pager, 40f, 60f);
        ChatMediaWalk.chatForTests = new Chat();
        List<Object> tapped = tap("first");

        assertSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));
        ChatMediaWalk.pager(pager);

        assertOffset(pager, 40f, 60f);
        assertEquals(0, ChatMediaWalk.holdDismiss());
    }

    /** A walk that didn't reach its pager, since the viewer returned early, doesn't turn the next one. */
    @Test public void aFailedOpenClearsAWalkThatNeverReachedItsPager() {
        FrameLayout pager = shownPager();
        offset(pager, 40f, 60f);
        startWalk();
        ChatMediaWalk.chatForTests = new Chat();
        List<Object> tapped = tap("first");

        assertSame(tapped, ChatMediaWalk.open(VIEWER, KEY, tapped, 0));
        ChatMediaWalk.pager(pager);

        assertOffset(pager, 40f, 60f);
    }

    @Test public void noPagerAndNoWalkHoldNothing() {
        ChatMediaWalk.pager(null);
        assertEquals(0, ChatMediaWalk.holdDismiss());
    }

    /** The issue's own case: a tap on a post shared to the chat opens the walk at that post, read once. */
    @Test public void aTapOnASharedPostOpensTheWalkAtThatPost() {
        Delegate delegate = delegate(plain("last", 30), post("post", 20), plain("first", 10));

        assertEquals(1, tapPost("post"));

        assertEquals(1, delegate.shows);
        assertEquals(Arrays.asList("first", "post", "last"), ids(delegate.opened));
        assertSame("the viewer gets the post's own page", delegate.shown, delegate.opened.get(1));
        assertEquals(1, ChatMediaWalk.startIndex());
        assertEquals("the viewer's opener takes the pages the tap read", 1, delegate.chat.reads);
    }

    @Test public void theOldKindOfSharedPostOpensItsWalkToo() {
        Delegate delegate = delegate(plain("first", 10), new Message("post", "MEDIA_SHARE", 20));

        assertEquals(1, tapPost("post"));
        assertEquals(Arrays.asList("first", "post"), ids(delegate.opened));
    }

    @Test public void aTapOnAnythingButASharedPostIsInstagrams() {
        Message disappearing = post("disappearing", 25);
        disappearing.expiring = true;
        Delegate delegate = delegate(plain("first", 10), new Message("reel", "XMA_CLIPS_SHARE", 20), disappearing, post("post", 30));

        assertEquals("a sent photo goes through the viewer's own opener", 0, tapPost("first"));
        assertEquals(0, tapPost("reel"));
        assertEquals(0, tapPost("disappearing"));
        assertEquals("not in the chat", 0, tapPost("gone"));
        assertEquals(0, delegate.shows);
    }

    /** This viewer shows only an album's cover, and a reel has Swipe through a chat's reels when that's on. */
    @Test public void anAlbumAndAReelWhileChatReelsAreOnStayInstagrams() {
        Message album = post("album", 20);
        album.type = ChatMediaWalk.ALBUM;
        Message reel = post("reel", 30);
        reel.type = ChatMediaWalk.VIDEO;
        Delegate delegate = delegate(plain("first", 10), album, reel);

        assertEquals(0, tapPost("album"));
        Settings.SWIPE_CHAT_REELS.save(true);
        assertEquals(0, tapPost("reel"));
        assertEquals(0, delegate.shows);

        Settings.SWIPE_CHAT_REELS.save(false);
        assertEquals(1, tapPost("reel"));
        assertEquals(Arrays.asList("first", "album", "reel"), ids(delegate.opened));
    }

    @Test public void aLoneSharedPostIsInstagrams() {
        Delegate delegate = delegate(post("post", 10), new Message("text", "TEXT", 20));

        assertEquals(0, tapPost("post"));
        assertEquals(0, delegate.shows);
    }

    @Test public void switchedOffOrPausedASharedPostIsInstagrams() {
        Delegate delegate = delegate(plain("first", 10), post("post", 20));

        Settings.SWIPE_CHAT_MEDIA.save(false);
        assertEquals(0, tapPost("post"));

        Settings.SWIPE_CHAT_MEDIA.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, tapPost("post"));
        assertEquals(0, delegate.shows);

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(1, tapPost("post"));
    }

    @Test public void aTapMissingItsPartsIsInstagrams() {
        delegate(plain("first", 10), post("post", 20));
        View view = new View(RuntimeEnvironment.getApplication());

        assertEquals(0, ChatMediaWalk.tap(null, RECT, view, "post"));
        assertEquals(0, ChatMediaWalk.tap(DELEGATE, null, view, "post"));
        assertEquals(0, ChatMediaWalk.tap(DELEGATE, RECT, null, "post"));
        assertEquals(0, ChatMediaWalk.tap(DELEGATE, RECT, view, null));
    }

    /** Instagram's delegate can return before its viewer opens, and then the tap is Instagram's after all. */
    @Test public void aViewerThatNeverOpensLeavesTheTapToInstagram() {
        Delegate delegate = delegate(plain("first", 10), post("post", 20));
        delegate.bails = true;

        assertEquals(0, tapPost("post"));
        assertEquals(1, delegate.shows);

        List<Object> tapped = tap("first");
        assertEquals("a photo's tap after it reads the chat afresh", Arrays.asList("first", "post"), ids(ChatMediaWalk.open(VIEWER, KEY, tapped, 0)));
        assertEquals(0, ChatMediaWalk.startIndex());
    }

    /** A viewer that fails once its opener ran is on screen, so the tap stays the walk's. One that fails before isn't. */
    @Test public void aFailingViewerAnswersWhetherItOpened() {
        Delegate delegate = delegate(plain("first", 10), post("post", 20));

        delegate.failsBeforeOpening = true;
        assertEquals(0, tapPost("post"));

        delegate.failsBeforeOpening = false;
        delegate.failsAfterOpening = true;
        assertEquals(1, tapPost("post"));
    }
}
