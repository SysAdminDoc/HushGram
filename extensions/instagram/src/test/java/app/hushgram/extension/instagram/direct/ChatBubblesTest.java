/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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

/** Chat bubbles (#747): which notifications get Android's bubble, and that nothing else about them changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 37})
public class ChatBubblesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ME = "1234";
    private static final String ALICE = "340282366841710300949128100000000001";
    private static final String BOB = "340282366841710300949128100000000002";

    @Before
    public void enable() {
        MessagesLock.resetForTests();
        ChatAccounts.signInForTests(ME);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.CHAT_BUBBLES.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        Settings.CHAT_BUBBLES.resetToDefault();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCKED_CHATS.resetToDefault();
        Settings.HIDDEN_CHATS.resetToDefault();
        HiddenChats.resetForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void aChatsNotificationsGetTheirBubble() {
        Notification main = chat("alice-chat");
        Notification summary = summary();
        Notification other = chat("alice-chat");
        Map<String, Object> others = new HashMap<>();
        others.put("one", other);
        others.put("not a notification", "text");

        ChatBubbles.offer(main, summary, others, "direct_v2?id=" + ALICE + "&x=1", null, null, ME);

        assertNotNull("the message", main.getBubbleMetadata());
        assertEquals("on the chat's own shortcut", "alice-chat", main.getBubbleMetadata().getShortcutId());
        assertNotNull("the other one it posts with it", other.getBubbleMetadata());
        assertNull("the group summary stands for the group, not a chat", summary.getBubbleMetadata());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(ChatBubbles.BUBBLED + " 2"));
        assertTrue(HookStatus.missing(FamilyNames.CHAT_BUBBLES).toString(), HookStatus.missing(FamilyNames.CHAT_BUBBLES).isEmpty());
    }

    @Test
    public void theBubbleGoesOnTheSameNotificationWithEverythingElseKept() {
        Context context = RuntimeEnvironment.getApplication();
        Notification message = chat("alice-chat");
        CharSequence title = message.extras.getCharSequence(Notification.EXTRA_TITLE);
        Parcelable[] messages = message.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        PendingIntent open = message.contentIntent;
        long when = message.when;

        assertTrue(ChatBubbles.bubble(context, message));

        assertEquals(String.valueOf(title), String.valueOf(message.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertEquals("the chat's messages", messages.length, message.extras.getParcelableArray(Notification.EXTRA_MESSAGES).length);
        assertSame(open, message.contentIntent);
        assertEquals(when, message.when);
        assertEquals("ig_direct", message.getChannelId());
        assertEquals("direct", message.getGroup());
        assertEquals("alice-chat", message.getShortcutId());
        assertEquals(Notification.CATEGORY_MESSAGE, message.category);
        assertEquals("the reply action", 1, message.actions.length);
        assertFalse("a notification that already has one is left as it is", ChatBubbles.bubble(context, message));
    }

    @Test
    public void aNotificationAndroidCantBubbleIsLeftAlone() {
        Context context = RuntimeEnvironment.getApplication();
        Notification noShortcut = chat(null);
        Notification summary = summary();

        assertFalse(ChatBubbles.bubble(context, noShortcut));
        assertFalse(ChatBubbles.bubble(context, summary));
        assertFalse(ChatBubbles.bubble(context, null));
        assertNull(noShortcut.getBubbleMetadata());
        assertNull(summary.getBubbleMetadata());

        ChatBubbles.offer(noShortcut, summary, null, "direct_v2?id=" + ALICE, null, null, ME);
        String report = HookStatus.report().toString();
        assertTrue("a chat without a shortcut is counted once, the summary isn't: " + report,
                report.contains(ChatBubbles.NO_SHORTCUT + " 1"));
    }

    @Test
    public void hiddenAndLockedChatsNeverGetOne() {
        ChatLocks.add(ALICE, "Alice");
        HiddenChats.add(BOB, "Bob");
        Notification locked = chat("alice-chat");
        Notification lockedByThread = chat("alice-chat");
        Notification hidden = chat("bob-chat");
        Notification open = chat("carol-chat");

        ChatBubbles.offer(locked, null, null, "direct_v2?id=" + ALICE, null, null, ME);
        ChatBubbles.offer(lockedByThread, null, null, "direct_v2", "999", ALICE, ME);
        ChatBubbles.offer(hidden, null, null, "direct_v2?id=" + BOB, null, null, ME);
        ChatBubbles.offer(open, null, null, "direct_v2?id=777", null, null, ME);

        assertNull("a locked chat", locked.getBubbleMetadata());
        assertNull("a locked chat known by its thread id", lockedByThread.getBubbleMetadata());
        assertNull("a hidden chat", hidden.getBubbleMetadata());
        assertNotNull("any other chat", open.getBubbleMetadata());
    }

    @Test
    public void whileLockYourMessagesIsShutTheCopyItPostsHasNoBubble() {
        Notification message = chat("alice-chat");
        ChatBubbles.offer(message, null, null, "direct_v2?id=" + ALICE, null, null, ME);
        assertNotNull(message.getBubbleMetadata());

        Settings.LOCK_MESSAGES.save(true);
        Notification posted = MessagesLock.notification(message);

        assertNotSame("the lock hides the message", message, posted);
        assertNull("so no bubble shows whose chat it is", posted.getBubbleMetadata());
        assertNull(posted.getShortcutId());
    }

    @Test
    public void offOrPausedLeavesEveryNotificationAsInstagramMadeIt() {
        Settings.CHAT_BUBBLES.save(false);
        Notification off = chat("alice-chat");
        ChatBubbles.offer(off, null, null, "direct_v2?id=" + ALICE, null, null, ME);
        assertNull("switched off", off.getBubbleMetadata());

        Settings.CHAT_BUBBLES.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Notification paused = chat("alice-chat");
        ChatBubbles.offer(paused, null, null, "direct_v2?id=" + ALICE, null, null, ME);
        assertNull("paused", paused.getBubbleMetadata());
        assertTrue("the hook still says it ran", HookStatus.missing(FamilyNames.CHAT_BUBBLES).isEmpty());
    }

    @Test
    public void aPushThatIsntAChatsIsLeftAlone() {
        Notification like = chat("alice-chat");

        ChatBubbles.offer(like, null, null, "media?id=5", null, null, ME);
        ChatBubbles.offer(like, null, null, null, " ", "222,333", ME);

        assertNull(like.getBubbleMetadata());
    }

    @Test
    public void nothingItIsHandedMakesItThrow() {
        ChatBubbles.offer(null, null, null, null, null, null, null);
        ChatBubbles.offer(null, null, new HashMap<>(), "direct_v2?id=" + ALICE, null, null, null);
        Map<String, Object> odd = new HashMap<>();
        odd.put("null", null);
        ChatBubbles.offer(chat("alice-chat"), summary(), odd, "direct_v2?id=%zz", "111", null, "not an account");

        assertTrue("nothing threw: " + HookStatus.missing(FamilyNames.CHAT_BUBBLES),
                HookStatus.missing(FamilyNames.CHAT_BUBBLES).isEmpty());
    }

    /** A chat's message notification the way Instagram posts one, on [shortcut] when it has one. */
    private static Notification chat(String shortcut) {
        Context context = RuntimeEnvironment.getApplication();
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent("open"), PendingIntent.FLAG_IMMUTABLE);
        Person me = new Person.Builder().setName("Me").setKey(ME).build();
        Person alice = new Person.Builder().setName("Alice").setKey(ALICE).build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(me)
                .addMessage("meet me at 8", 1_000L, alice)
                .addMessage("by the door", 2_000L, alice);
        Notification.Builder builder = new Notification.Builder(context, "ig_direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Alice")
                .setContentText("by the door")
                .setContentIntent(open)
                .setGroup("direct")
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setStyle(style)
                .addAction(new Notification.Action.Builder(null, "Reply", open).build());
        if (shortcut != null) builder.setShortcutId(shortcut);
        return builder.build();
    }

    private static Notification summary() {
        Context context = RuntimeEnvironment.getApplication();
        return new Notification.Builder(context, "ig_direct")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setGroup("direct")
                .setGroupSummary(true)
                .setShortcutId("alice-chat")
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
    }
}
