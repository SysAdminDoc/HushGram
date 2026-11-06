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

    private static final class Message {
        final String id;
        final String kind;
        final long time;
        boolean expiring;
        boolean broken;
        boolean empty;

        Message(String id, String kind, long time) {
            this.id = id;
            this.kind = kind;
            this.time = time;
        }
    }

    private static final class Page {
        final String id;

        Page(String id) {
            this.id = id;
        }
    }

    private static final class Chat implements ChatMediaWalk.Messages {
        final List<Message> messages = new ArrayList<>();
        boolean unreadable;

        Chat(Message... held) {
            messages.addAll(Arrays.asList(held));
        }

        @Override public List<?> thread(Object key) {
            if (unreadable) throw new IllegalStateException("no thread");
            assertSame(KEY, key);
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
            return page((Message) message);
        }

        @Override public Object sharedPage(Object message) {
            return page((Message) message);
        }

        private static Object page(Message message) {
            if (message.broken) throw new IllegalStateException("broken");
            return message.empty ? null : new Page(message.id);
        }

        @Override public String id(Object page) {
            return ((Page) page).id;
        }
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
        ChatMediaWalk.pager(null);
        Settings.SWIPE_CHAT_MEDIA.resetToDefault();
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
}
