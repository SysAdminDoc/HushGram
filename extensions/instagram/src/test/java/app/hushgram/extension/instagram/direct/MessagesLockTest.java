/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

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
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What Lock your messages covers, hides and holds, and when it leaves everything to Instagram. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class MessagesLockTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int FRAME = 0x7f0b0001;
    private static final int LIST = 0x7f0b0002;
    private static final int CHAT = 0x7f0b0003;

    /** Each ask's two answers, oldest first. */
    private final List<Runnable[]> asks = new ArrayList<>();

    @Before
    public void enable() {
        MessagesLock.resetForTests();
        Map<String, Integer> ids = new HashMap<>();
        ids.put(MessagesLock.INBOX_FRAME, FRAME);
        ids.put(MessagesLock.INBOX_LIST, LIST);
        ids.put(MessagesLock.CHAT_ROOT, CHAT);
        MessagesLock.idsForTests = ids;
        MessagesLock.asker = (activity, confirmed, notConfirmed) -> asks.add(new Runnable[]{confirmed, notConfirmed});
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.LOCK_MESSAGES.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCK_APP.resetToDefault();
        Settings.LOCK_AGAIN.resetToDefault();
        Settings.LOCKED_CHATS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void lockedMessageNotificationsSayOnlyThatAMessageCame() {
        Notification original = message("ig_direct", Notification.CATEGORY_MESSAGE);

        Notification hidden = MessagesLock.notification(original);

        assertNotSame(original, hidden);
        assertEquals("New message", hidden.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertFalse(String.valueOf(hidden.extras.getCharSequence(Notification.EXTRA_TITLE)).contains("Alice"));
        assertNull(hidden.actions);
        assertSame(original.contentIntent, hidden.contentIntent);
        assertEquals(original.getGroup(), hidden.getGroup());
        assertEquals(original.getChannelId(), hidden.getChannelId());
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());

        // Marked by its channel alone, a message is still a message; a call and anything else aren't.
        Notification byChannel = message("ig_direct", null);
        assertNotSame(byChannel, MessagesLock.notification(byChannel));
        Notification call = message("ig_direct", Notification.CATEGORY_CALL);
        assertSame(call, MessagesLock.notification(call));
        Notification like = message("ig_other", Notification.CATEGORY_SOCIAL);
        assertSame(like, MessagesLock.notification(like));
    }

    @Test
    public void offUnreadyAndUnlockedLeaveEverythingToInstagram() {
        Notification original = message("ig_direct", Notification.CATEGORY_MESSAGE);

        Settings.LOCK_MESSAGES.resetToDefault();
        assertFalse(Settings.LOCK_MESSAGES.get());
        assertSame(original, MessagesLock.notification(original));
        assertFalse(MessagesLock.holdBanner());
        Settings.LOCK_MESSAGES.save(true);

        SettingsContextRule.withoutContext(() -> {
            assertSame(original, MessagesLock.notification(original));
            assertFalse(MessagesLock.holdBanner());
        });

        assertTrue(MessagesLock.holdBanner());
        MessagesLock.confirmThen(Robolectric.buildActivity(Activity.class).setup().get(), () -> { });
        asks.get(0)[0].run();
        assertSame(original, MessagesLock.notification(original));
        assertFalse(MessagesLock.holdBanner());
    }

    @Test
    public void theInboxIsCoveredAndThePhoneAskedOnce() {
        ActivityController<Activity> controller = inbox();
        Activity activity = controller.get();
        View frame = activity.findViewById(FRAME);

        MessagesLock.check(activity);
        MessagesLock.check(activity);

        View cover = cover(activity);
        assertNotNull("no cover over the inbox", cover);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(visible(frame).width(), cover.getWidth());
        assertEquals(visible(frame).height(), cover.getHeight());
        assertEquals(1, asks.size());
        assertTrue(MessagesLock.holdBanner());

        // Cancelled: the cover stays, and isn't asked again until the inbox shows again.
        asks.get(0)[1].run();
        MessagesLock.check(activity);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(1, asks.size());
    }

    @Test
    public void confirmedOpensUntilInstagramLeavesTheScreen() {
        Application app = RuntimeEnvironment.getApplication();
        MessagesLock.watch(app);
        ActivityController<Activity> controller = inbox();
        Activity activity = controller.get();
        MessagesLock.check(activity);
        View cover = cover(activity);
        assertNotNull(cover);

        asks.get(0)[0].run();
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.GONE, cover.getVisibility());
        assertFalse(MessagesLock.locked());

        controller.pause().stop();
        assertTrue("leaving Instagram didn't lock the messages again", MessagesLock.locked());
        controller.start().resume();
        layout(activity);
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, cover(activity).getVisibility());
        assertEquals(2, asks.size());
    }

    @Test
    public void aChatIsCoveredTooAndNothingElseIs() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View other = new View(activity);
        root.addView(other, new FrameLayout.LayoutParams(400, 400));
        activity.setContentView(root);
        layout(activity);
        MessagesLock.check(activity);
        assertNull("a screen that isn't the messages got a cover", cover(activity));
        assertEquals(0, asks.size());

        FrameLayout chat = new FrameLayout(activity);
        chat.setId(CHAT);
        root.addView(chat, new FrameLayout.LayoutParams(300, 500));
        layout(activity);
        MessagesLock.check(activity);
        assertNotNull("no cover over the chat", cover(activity));
        assertEquals(visible(chat).height(), cover(activity).getHeight());
        assertEquals(1, asks.size());
    }

    @Test
    public void lockAllOfInstagramCoversTheWholeScreen() {
        Settings.LOCK_MESSAGES.save(false);
        Settings.LOCK_APP.save(true);
        Activity activity = inbox().get();
        View content = activity.findViewById(android.R.id.content);

        MessagesLock.check(activity);

        View cover = cover(activity);
        assertNotNull("no cover over Instagram", cover);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(visible(content).width(), cover.getWidth());
        assertEquals(visible(content).height(), cover.getHeight());
        assertEquals("the inbox got its own cover too", 1, covers(activity));
        assertEquals(1, asks.size());
        Notification message = message("ig_direct", Notification.CATEGORY_MESSAGE);
        assertNotSame(message, MessagesLock.notification(message));

        asks.get(0)[0].run();
        MessagesLock.check(activity);
        assertEquals(View.GONE, cover.getVisibility());
        assertEquals(1, covers(activity));
    }

    @Test
    public void lockAgainWaitsAsLongAsYouPicked() {
        Settings.LOCK_AGAIN.save(LockDelay.FIVE_MINUTES);
        MessagesLock.watch(RuntimeEnvironment.getApplication());
        ActivityController<Activity> controller = inbox();
        MessagesLock.check(controller.get());
        asks.get(0)[0].run();

        controller.pause().stop();
        SystemClock.sleep(4 * 60_000);
        assertFalse("locked before five minutes away", MessagesLock.locked());
        // Restarted, as Android brings a stopped activity back, so the next stop is a real one.
        controller.restart().resume();
        assertFalse(MessagesLock.locked());
        assertEquals(1, asks.size());

        // Away again: the time starts over, and once it's up the notifications are hidden at once.
        controller.pause().stop();
        SystemClock.sleep(4 * 60_000);
        assertFalse(MessagesLock.locked());
        SystemClock.sleep(60_000);
        assertTrue("five minutes away didn't lock", MessagesLock.locked());
        Notification message = message("ig_direct", Notification.CATEGORY_MESSAGE);
        assertNotSame(message, MessagesLock.notification(message));
    }

    @Test
    public void turningALockOnWaitsUntilYouLeave() {
        Settings.LOCK_MESSAGES.save(false);
        MessagesLock.openUntilLeft();
        Settings.LOCK_APP.save(true);
        assertFalse(MessagesLock.locked());

        // Turned on while the other lock is locked, it opens nothing.
        MessagesLock.relock(true);
        MessagesLock.openUntilLeft();
        assertTrue(MessagesLock.locked());
    }

    @Test
    public void aPhoneWithoutAScreenLockLeavesTheMessagesOpenAndSaysWhy() {
        MessagesLock.asker = MessagesLock.realAskerForTests();
        Activity activity = inbox().get();

        MessagesLock.check(activity);

        assertFalse(MessagesLock.locked());
        ShadowLooper.idleMainLooper();
        assertNotNull(ShadowToast.getTextOfLatestToast());
        MessagesLock.check(activity);
        assertEquals(View.GONE, cover(activity).getVisibility());
    }

    /**
     * Neither Pause nor safe mode, the marker file's included, gets around a lock: it still locks,
     * and covers all of Instagram, which needs none of Instagram's view ids.
     */
    @Test
    public void pausedOrInSafeModeALockStillLocksAndCoversEverything() {
        Activity activity = inbox().get();
        View content = activity.findViewById(android.R.id.content);
        Notification message = message("ig_direct", Notification.CATEGORY_MESSAGE);
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{
                HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP, HushgramPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertTrue(reason.name(), MessagesLock.locked());
            assertNotSame(reason.name(), message, MessagesLock.notification(message));
            assertTrue(reason.name(), MessagesLock.holdBanner());
            MessagesLock.check(activity);
            View cover = cover(activity);
            assertNotNull(reason.name(), cover);
            assertEquals(reason.name(), View.VISIBLE, cover.getVisibility());
            assertEquals(reason.name(), visible(content).height(), cover.getHeight());
            PauseForTests.resume();
        }
        assertEquals("asked once, not once per check", 1, asks.size());
    }

    /** A second activity coming up leaves the covers in the window under it where they are. */
    @Test
    public void eachWindowKeepsItsOwnCovers() {
        Activity under = inbox().get();
        MessagesLock.check(under);
        View inboxCover = cover(under);
        assertNotNull(inboxCover);
        Activity above = Robolectric.buildActivity(Activity.class).setup().get();
        layout(above);
        MessagesLock.check(above);
        assertNull("a screen with no messages got a cover", cover(above));
        assertEquals("the inbox under it showed through", View.VISIBLE, inboxCover.getVisibility());

        Settings.LOCK_APP.save(true);
        MessagesLock.check(under);
        View appCover = cover(under, MessagesLock.APP);
        assertEquals("the inbox's cover stayed up", View.GONE, inboxCover.getVisibility());
        MessagesLock.check(above);
        assertNotNull("the activity above has no cover of its own", cover(above));
        assertNotSame(appCover, cover(above));
        assertSame("the cover was pulled out of the window under it", under.getWindow().getDecorView(), appCover.getParent());
        assertEquals(View.VISIBLE, appCover.getVisibility());
    }

    /** Screen readers skip what's under a cover, and read it as before once it's gone. */
    @Test
    public void screenReadersSkipWhatsCovered() {
        Activity activity = inbox().get();
        View frame = activity.findViewById(FRAME);
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        MessagesLock.check(activity);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, frame.getImportantForAccessibility());

        asks.get(0)[0].run();
        MessagesLock.check(activity);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, frame.getImportantForAccessibility());
    }

    /**
     * The cover's title and Unlock link get the room their text needs. A column that wrapped its
     * content shrank both to the lock icon's width, so a chat's cover read "This" and had no visible
     * way to unlock (seen on the emulator, 2026-10-10).
     */
    @Test
    public void theCoverShowsItsWholeTitleAndTheUnlockLink() {
        Activity activity = inbox().get();
        MessagesLock.check(activity);
        layout(activity);
        ViewGroup cover = (ViewGroup) cover(activity);
        assertNotNull(cover);
        List<TextView> texts = new ArrayList<>();
        collectTexts(cover, texts);
        assertEquals("a title and an Unlock link", 2, texts.size());
        TextView title = texts.get(0);
        TextView unlock = texts.get(1);
        assertEquals("Unlock", unlock.getText().toString());

        int icon = Math.round(48 * activity.getResources().getDisplayMetrics().density);
        assertTrue("the title was squeezed to " + title.getMeasuredWidth() + "px",
                title.getMeasuredWidth() > icon * 2);
        assertTrue("the Unlock link has no room for its text",
                unlock.getMeasuredWidth() > unlock.getPaddingLeft() + unlock.getPaddingRight());
        assertTrue("the title is cut off", title.getLayout().getHeight()
                <= title.getMeasuredHeight() - title.getPaddingTop() - title.getPaddingBottom());

        // A finger's worth of height, words that read against the cover, and a button to a keyboard.
        assertTrue("the Unlock link is " + unlock.getMeasuredHeight() + "px tall, under 48dp", unlock.getMeasuredHeight() >= icon);
        int behind = ((ColorDrawable) cover.getBackground()).getColor();
        double contrast = contrast(unlock.getCurrentTextColor(), behind);
        assertTrue("the Unlock link reads at " + contrast + ":1 on the cover, under 4.5:1", contrast >= 4.5);
        assertTrue(unlock.isFocusable());
    }

    /** WCAG's contrast ratio between two opaque colors. */
    private static double contrast(int one, int other) {
        double a = Color.luminance(one) + 0.05, b = Color.luminance(other) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }

    private static void collectTexts(View view, List<TextView> texts) {
        if (view instanceof TextView) texts.add((TextView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), texts);
    }

    /** A hidden inbox list found first doesn't keep the one showing from being covered. */
    @Test
    public void theListShowingIsTheOneCovered() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View first = new View(activity);
        first.setId(LIST);
        root.addView(first, new FrameLayout.LayoutParams(1080, 300));
        View second = new View(activity);
        second.setId(LIST);
        FrameLayout.LayoutParams lower = new FrameLayout.LayoutParams(1080, 900);
        lower.topMargin = 400;
        root.addView(second, lower);
        activity.setContentView(root);
        layout(activity);
        MessagesLock.check(activity);
        assertEquals(visible(first).height(), cover(activity).getHeight());

        first.setVisibility(View.GONE);
        layout(activity);
        MessagesLock.check(activity);
        View cover = cover(activity);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(visible(second).height(), cover.getHeight());
        assertEquals(visible(second).top, cover.getTop());
    }

    /**
     * While the messages are open, the recent apps picture doesn't show them: Android 13 and up are
     * told so, and older Android gets the secure flag, which comes off only if the lock put it there.
     */
    @Test
    public void openMessagesStayOutOfTheRecentAppsPicture() {
        Activity activity = inbox().get();
        MessagesLock.check(activity);
        asks.get(0)[0].run();
        MessagesLock.check(activity);
        boolean secure = (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        assertEquals("the secure flag is for Android 12 and older", Build.VERSION.SDK_INT < 33, secure);

        MessagesLock.relock(true);
        MessagesLock.check(activity);
        assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);

        // Instagram's own mark stays when the lock has nothing to take back.
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        asks.get(1)[0].run();
        MessagesLock.check(activity);
        MessagesLock.relock(true);
        MessagesLock.check(activity);
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    /** When the lock comes back, message notifications already in the shade lose their text. */
    @Test
    public void lockingAgainHidesWhatTheShadeShows() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Activity activity = inbox().get();
        MessagesLock.check(activity);
        asks.get(0)[0].run();
        manager.notify("thread", 7, message("ig_direct", Notification.CATEGORY_MESSAGE));
        manager.notify("like", 8, message("ig_other", Notification.CATEGORY_SOCIAL));

        MessagesLock.left();

        Notification shown = Shadows.shadowOf(manager).getNotification("thread", 7);
        assertTrue(MessagesLock.isHidden(shown));
        assertEquals("New message", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertTrue("it rang again", (shown.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertFalse("not a message", MessagesLock.isHidden(Shadows.shadowOf(manager).getNotification("like", 8)));
        assertSame("hidden twice", shown, MessagesLock.notification(shown));
    }

    /**
     * One message notification that can't be written over (here, one with no small icon) is
     * reported, and the message notifications around it still lose their text.
     */
    @Test
    public void oneNotificationThatFailsLeavesTheRestHidden() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Activity activity = inbox().get();
        MessagesLock.check(activity);
        asks.get(0)[0].run();
        for (int id = 1; id <= 7; id++) manager.notify("thread", id, message("ig_direct", Notification.CATEGORY_MESSAGE));
        // The shade's first message is replaced by one that fails, so the others come after it.
        int brokenId = manager.getActiveNotifications()[0].getId();
        Notification broken = new Notification.Builder(context, "ig_direct").setContentTitle("Bob")
                .setContentText("see you there").setCategory(Notification.CATEGORY_MESSAGE).build();
        manager.notify("thread", brokenId, broken);
        StatusBarNotification[] order = manager.getActiveNotifications();
        assertEquals(7, order.length);
        assertNotEquals("the one that fails comes before another message", brokenId, order[order.length - 1].getId());

        MessagesLock.left();

        for (int id = 1; id <= 7; id++) {
            Notification shown = Shadows.shadowOf(manager).getNotification("thread", id);
            if (id == brokenId) {
                assertFalse("the one that failed is left as it was", MessagesLock.isHidden(shown));
                continue;
            }
            assertTrue("message " + id, MessagesLock.isHidden(shown));
            assertEquals("New message", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        }
        String missing = HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString();
        assertTrue(missing, missing.contains(IllegalArgumentException.class.getName()));
    }

    /** Only your own cancel ends an ask; a prompt that couldn't ask goes on to the phone's own check. */
    @Test
    public void onlyYourCancelEndsAnAsk() {
        assertTrue(MessagesLock.cancelledByYou(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED));
        assertTrue(MessagesLock.cancelledByYou(BiometricPrompt.BIOMETRIC_ERROR_CANCELED));
        assertFalse(MessagesLock.cancelledByYou(BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT));
        assertFalse(MessagesLock.cancelledByYou(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT));
        assertFalse(MessagesLock.cancelledByYou(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE));
    }

    private static final String ALICE = "340282366841710300949128111";
    private static final String BOB = "340282366841710300949128222";

    /** A chat screen: its root, as Instagram names it, with the name in the header. */
    private static Activity chat(String name) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout screen = new FrameLayout(activity);
        screen.setId(CHAT);
        android.widget.TextView header = new android.widget.TextView(activity);
        header.setText(name);
        screen.addView(header, new FrameLayout.LayoutParams(300, 80));
        root.addView(screen, new FrameLayout.LayoutParams(300, 500));
        activity.setContentView(root);
        layout(activity);
        return activity;
    }

    /** The chat on screen is the one [id] names, as the patch's bridge would answer. */
    private static void open(String id) {
        ChatLocks.reader = fragment -> id;
        ChatLocks.opened(new Object());
    }

    @Test
    public void aListedChatIsCoveredWhetherOrNotTheMessagesAreLocked() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Activity activity = chat("Alice");
        open(ALICE);

        MessagesLock.check(activity);
        MessagesLock.check(activity);

        View cover = cover(activity);
        assertNotNull("no cover over the listed chat", cover);
        assertEquals(View.VISIBLE, cover.getVisibility());
        assertEquals(visible(activity.findViewById(CHAT)).height(), cover.getHeight());
        assertEquals(1, asks.size());
        assertFalse("the messages aren't locked", MessagesLock.locked());
        assertTrue(MessagesLock.chatListLocked());
        assertTrue("the banner could show the chat", MessagesLock.holdBanner());
        assertTrue(texts(cover).contains("This chat is locked"));

        asks.get(0)[0].run();
        MessagesLock.check(activity);
        assertEquals(View.GONE, cover.getVisibility());
        assertFalse(MessagesLock.holdBanner());
        assertEquals("asked once", 1, asks.size());
    }

    @Test
    public void otherChatsOpenAsBefore() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Activity activity = chat("Bob");
        open(BOB);

        MessagesLock.check(activity);

        assertNull("a chat that isn't listed got a cover", cover(activity));
        assertEquals(0, asks.size());
        assertFalse(MessagesLock.locked());
    }

    @Test
    public void cancellingShowsNothing() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Activity activity = chat("Alice");
        open(ALICE);
        MessagesLock.check(activity);

        asks.get(0)[1].run();
        MessagesLock.check(activity);

        assertEquals("the cover stays after a cancel", View.VISIBLE, cover(activity).getVisibility());
        assertEquals("and the phone isn't asked again", 1, asks.size());
        assertTrue(MessagesLock.chatListLocked());
    }

    @Test
    public void removingAChatClearsItsLock() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Activity activity = chat("Alice");
        open(ALICE);
        MessagesLock.check(activity);
        View cover = cover(activity);
        Notification message = message("ig_direct", Notification.CATEGORY_MESSAGE);
        ChatLocks.track(message, null, null, "direct_v2?id=" + ALICE, null, null);
        assertNotSame(message, MessagesLock.notification(message));

        ChatLocks.remove(ALICE);
        MessagesLock.check(activity);

        assertEquals(View.GONE, cover.getVisibility());
        assertFalse(MessagesLock.chatListLocked());
        assertTrue(ChatLocks.chats().isEmpty());
        assertSame("its notifications show again", message, MessagesLock.notification(message));
    }

    @Test
    public void aListedChatsNotificationsLoseTheirTextAndOthersDont() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Notification alice = message("ig_direct", Notification.CATEGORY_MESSAGE);
        ChatLocks.track(alice, null, null, "instagram://direct_v2?id=" + ALICE + "&x=1", null, null);
        Notification bob = message("ig_direct", Notification.CATEGORY_MESSAGE);
        ChatLocks.track(bob, null, null, "direct_v2?id=" + BOB, BOB, null);
        Notification unknown = message("ig_direct", Notification.CATEGORY_MESSAGE);

        Notification hidden = MessagesLock.notification(alice);

        assertNotSame(alice, hidden);
        assertEquals("New message", hidden.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertTrue(MessagesLock.isHidden(hidden));
        assertEquals("the copy still says which chat", ALICE, ChatLocks.idsOf(hidden));
        assertSame(bob, MessagesLock.notification(bob));
        assertSame("a message no chat is known for", unknown, MessagesLock.notification(unknown));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());

        MessagesLock.confirmChatsThen(Robolectric.buildActivity(Activity.class).setup().get(), () -> { });
        asks.get(0)[0].run();
        assertSame("opened by the phone's lock", alice, MessagesLock.notification(alice));
    }

    @Test
    public void aPushIsMarkedWithEveryIdItCarries() {
        ChatLocks.add(ALICE, "Alice");
        Notification main = message("ig_direct", Notification.CATEGORY_MESSAGE);
        Notification summary = message("ig_direct", Notification.CATEGORY_MESSAGE);
        Notification other = message("ig_direct", Notification.CATEGORY_MESSAGE);
        Map<String, Notification> others = new HashMap<>();
        others.put("one", other);

        ChatLocks.track(main, summary, others, "direct_v2?id=" + ALICE + "&x=9", "111", "222,333");

        assertEquals(ALICE + ",111", ChatLocks.idsOf(main));
        assertEquals(ChatLocks.idsOf(main), ChatLocks.idsOf(summary));
        assertEquals(ChatLocks.idsOf(main), ChatLocks.idsOf(other));
        Notification like = message("ig_other", Notification.CATEGORY_SOCIAL);
        ChatLocks.track(like, null, null, "media?id=5", null, null);
        assertNull("a link that isn't a chat's", ChatLocks.idsOf(like));
        ChatLocks.track(null, null, null, null, null, null);
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void aPushIsLeftUntouchedWhileNoChatIsLocked() {
        for (boolean lockMessages : new boolean[]{false, true}) {
            Settings.LOCK_MESSAGES.save(lockMessages);
            Notification main = message("ig_direct", Notification.CATEGORY_MESSAGE);
            Notification summary = message("ig_direct", Notification.CATEGORY_MESSAGE);
            Notification other = message("ig_direct", Notification.CATEGORY_MESSAGE);
            Map<String, Notification> others = new HashMap<>();
            others.put("one", other);

            assertFalse(ChatLocks.track(main, summary, others, "direct_v2?id=" + ALICE, "111", ALICE));

            assertNull("lock messages " + lockMessages, ChatLocks.idsOf(main));
            assertNull(ChatLocks.idsOf(summary));
            assertNull(ChatLocks.idsOf(other));
            assertFalse(main.extras.containsKey(ChatLocks.CHAT_EXTRA));
        }
        HiddenChats.add(BOB, "Bob");
        Notification alice = message("ig_direct", Notification.CATEGORY_MESSAGE);

        assertFalse("a hidden list alone doesn't mark other chats", ChatLocks.track(alice, null, null, "direct_v2?id=" + ALICE, null, null));
        assertNull(ChatLocks.idsOf(alice));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void aThreadIdFromThePushIsEnoughToMatchTheChat() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Notification message = message("ig_direct", Notification.CATEGORY_MESSAGE);

        ChatLocks.track(message, null, null, "direct_v2", "999", ALICE);

        assertNotSame(message, MessagesLock.notification(message));
    }

    @Test
    public void aChatLeavingTheFrontIsForgottenButOnlyByItself() {
        Object first = new Object();
        Object second = new Object();
        ChatLocks.reader = fragment -> fragment == first ? ALICE : BOB;

        ChatLocks.opened(first);
        assertEquals(ALICE, ChatLocks.current());
        ChatLocks.opened(second);
        ChatLocks.closed(first);
        assertEquals("the chat that took its place stays", BOB, ChatLocks.current());
        ChatLocks.closed(second);
        assertNull(ChatLocks.current());

        ChatLocks.reader = fragment -> null;
        ChatLocks.opened(first);
        assertNull("a chat with no id yet is nobody's", ChatLocks.current());
        ChatLocks.reader = fragment -> { throw new IllegalStateException("unreadable"); };
        ChatLocks.opened(first);
        assertNull(ChatLocks.current());
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString().contains(IllegalStateException.class.getName()));
    }

    @Test
    public void lockingAgainHidesTheListedChatsInTheShade() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Activity activity = chat("Alice");
        open(ALICE);
        MessagesLock.check(activity);
        asks.get(0)[0].run();
        Notification alice = message("ig_direct", Notification.CATEGORY_MESSAGE);
        ChatLocks.track(alice, null, null, "direct_v2?id=" + ALICE, null, null);
        Notification bob = message("ig_direct", Notification.CATEGORY_MESSAGE);
        ChatLocks.track(bob, null, null, "direct_v2?id=" + BOB, null, null);
        manager.notify("alice", 1, alice);
        manager.notify("bob", 2, bob);

        MessagesLock.left();

        assertTrue(MessagesLock.isHidden(Shadows.shadowOf(manager).getNotification("alice", 1)));
        assertFalse("another chat's stays as it was", MessagesLock.isHidden(Shadows.shadowOf(manager).getNotification("bob", 2)));
    }

    @Test
    public void aStartedAppHidesWhatAListedChatLeftInTheShade() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification alice = message("ig_direct", Notification.CATEGORY_MESSAGE);
        ChatLocks.track(alice, null, null, "direct_v2?id=" + ALICE, null, null);
        manager.notify("alice", 1, alice);

        MessagesLock.watch(RuntimeEnvironment.getApplication());

        assertTrue(MessagesLock.isHidden(Shadows.shadowOf(manager).getNotification("alice", 1)));
    }

    /** Instagram's tag for a chat's message: its kind, a bar, the account and the chat's id. */
    private static String tag(String chat) {
        return "direct|1234" + ChatLocks.TAG_THREAD + chat;
    }

    @Test
    public void aMessageFromBeforeItsChatWasLockedIsHiddenWhenTheLockComesBack() {
        Settings.LOCK_MESSAGES.save(false);
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification alice = message("ig_direct", Notification.CATEGORY_MESSAGE);
        Notification bob = message("ig_direct", Notification.CATEGORY_MESSAGE);
        assertFalse(ChatLocks.track(alice, null, null, "direct_v2?id=" + ALICE, null, null));
        assertFalse(ChatLocks.track(bob, null, null, "direct_v2?id=" + BOB, null, null));
        assertNull("no chat was locked when it came", ChatLocks.idsOf(alice));
        manager.notify(tag(ALICE), 1, alice);
        manager.notify(tag(BOB) + ";type:rr", 2, bob);
        ChatLocks.add(ALICE, "Alice");
        Activity activity = chat("Alice");
        open(ALICE);
        MessagesLock.check(activity);
        asks.get(0)[0].run();

        MessagesLock.left();

        Notification shown = Shadows.shadowOf(manager).getNotification(tag(ALICE), 1);
        assertTrue("found by Instagram's own tag", MessagesLock.isHidden(shown));
        assertEquals("New message", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertFalse("another chat's stays as it was", MessagesLock.isHidden(Shadows.shadowOf(manager).getNotification(tag(BOB) + ";type:rr", 2)));
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void aStartedAppHidesAMessageFromBeforeItsChatWasLocked() {
        Settings.LOCK_MESSAGES.save(false);
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification alice = message("ig_direct", Notification.CATEGORY_MESSAGE);
        ChatLocks.track(alice, null, null, "direct_v2?id=" + ALICE, null, null);
        Notification bob = message("ig_direct", Notification.CATEGORY_MESSAGE);
        manager.notify(tag(ALICE) + ";type:rr", 1, alice);
        manager.notify(tag(BOB), 2, bob);
        ChatLocks.add(ALICE, "Alice");

        MessagesLock.watch(RuntimeEnvironment.getApplication());

        assertTrue(MessagesLock.isHidden(Shadows.shadowOf(manager).getNotification(tag(ALICE) + ";type:rr", 1)));
        assertFalse(MessagesLock.isHidden(Shadows.shadowOf(manager).getNotification(tag(BOB), 2)));
    }

    /**
     * Locking a chat in the list takes the text off its notifications in the shade right away, and
     * leaves other chats' alone even with Lock your messages on (the messages may be open). Before,
     * they kept their text until the next lock (seen on the emulator, 2026-10-10).
     */
    @Test
    public void lockingAChatHidesItsNotificationsInTheShadeNow() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.notify(tag(ALICE), 1, message("ig_direct", Notification.CATEGORY_MESSAGE));
        manager.notify(tag(BOB), 2, message("ig_direct", Notification.CATEGORY_MESSAGE));

        ChatLocks.add(ALICE, "Alice");
        MessagesLock.chatListed();

        Notification alice = Shadows.shadowOf(manager).getNotification(tag(ALICE), 1);
        assertTrue(MessagesLock.isHidden(alice));
        assertEquals("New message", alice.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertFalse("another chat's lost its text", MessagesLock.isHidden(Shadows.shadowOf(manager).getNotification(tag(BOB), 2)));
    }

    @Test
    public void theChatIsReadFromInstagramsTagAndNothingElseIs() {
        assertEquals(ALICE, ChatLocks.tagThread(tag(ALICE)));
        assertEquals("a reply reminder's", ALICE, ChatLocks.tagThread(tag(ALICE) + ";type:rr"));
        assertEquals("with no account before it", ALICE, ChatLocks.tagThread("direct|" + ChatLocks.TAG_THREAD + " " + ALICE + " "));
        for (String tag : new String[]{null, "", "alice", "direct|1234", "direct|1234" + ChatLocks.TAG_THREAD,
                "direct|1234" + ChatLocks.TAG_THREAD + ";type:rr", "direct|1234" + ChatLocks.TAG_THREAD + "1,2"}) {
            assertNull(tag, ChatLocks.tagThread(tag));
        }
        ChatLocks.add(ALICE, "Alice");
        Notification unmarked = message("ig_direct", Notification.CATEGORY_MESSAGE);
        assertTrue(ChatLocks.listedIn(unmarked, tag(ALICE)));
        assertFalse(ChatLocks.listedIn(unmarked, tag(BOB)));
        assertFalse("no tag and no mark", ChatLocks.listedIn(unmarked, null));
        ChatLocks.track(unmarked, null, null, "direct_v2?id=" + ALICE, null, null);
        assertTrue("the mark still counts under any tag", ChatLocks.listedIn(unmarked, "other"));
    }

    @Test
    public void theChatsNameIsFoundForTheList() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Chat 1");
        Activity activity = chat("Alice Smith");
        open(ALICE);

        MessagesLock.check(activity);

        assertEquals("Alice Smith", ChatLocks.chats().get(0).name);
        ChatLocks.remove(ALICE);
        MessagesLock.check(activity);
        assertEquals("what was opened last is offered under its name", "Alice Smith", ChatLocks.lastOpened().name);
    }

    /**
     * On 450 the chat's name sits in a header beside the chat's root, not in it, and the first text
     * high in a long chat is a message. The list takes the header's name, waits while the header is
     * still empty, and never takes the message (the emulator's list said "Chat 6194", 2026-10-10).
     */
    @Test
    public void theNameComesFromTheHeaderBesideTheChat() {
        int headerTitle = 0x7f0b0004;
        MessagesLock.idsForTests.put(ChatLocks.TITLES[0], headerTitle);
        Settings.LOCK_MESSAGES.save(false);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.widget.LinearLayout fragment = new android.widget.LinearLayout(activity);
        fragment.setOrientation(android.widget.LinearLayout.VERTICAL);
        FrameLayout screen = new FrameLayout(activity);
        screen.setId(CHAT);
        TextView message = new TextView(activity);
        message.setText("see you at 5");
        screen.addView(message, new FrameLayout.LayoutParams(300, 80));
        fragment.addView(screen, new android.widget.LinearLayout.LayoutParams(300, 500));
        activity.setContentView(fragment);
        layout(activity);
        open(ALICE);

        MessagesLock.check(activity);
        assertEquals("a message was taken before the header came",
                ChatList.placeholder(ALICE), ChatLocks.lastOpened().name);

        FrameLayout header = new FrameLayout(activity);
        TextView title = new TextView(activity);
        title.setId(headerTitle);
        header.addView(title, new FrameLayout.LayoutParams(300, 60));
        fragment.addView(header, 0, new android.widget.LinearLayout.LayoutParams(300, 60));
        layout(activity);
        // With nothing locked and no cover up, the chat is looked at every IDLE_LOOK_MS, not every frame.
        SystemClock.sleep(MessagesLock.IDLE_LOOK_MS);
        MessagesLock.check(activity);
        assertEquals("a message was taken while the header was empty",
                ChatList.placeholder(ALICE), ChatLocks.lastOpened().name);

        title.setText("Alice Smith");
        MessagesLock.check(activity);
        assertEquals("the window was walked again within half a second of the last look",
                ChatList.placeholder(ALICE), ChatLocks.lastOpened().name);
        SystemClock.sleep(MessagesLock.IDLE_LOOK_MS);
        MessagesLock.check(activity);
        assertEquals("Alice Smith", ChatLocks.lastOpened().name);
    }

    @Test
    public void theListReadsWhatWasSavedAndNothingElse() {
        Settings.LOCKED_CHATS.save("1\tAnn\n\n2\n 3 \t Cy \n\t\nbad,id\tX");

        List<ChatLocks.Chat> chats = ChatLocks.chats();

        assertEquals(4, chats.size());
        assertEquals("Ann", chats.get(0).name);
        assertEquals("a chat saved with no name", "Chat 2", chats.get(1).name);
        assertEquals("3", chats.get(2).id);
        assertEquals("Cy", chats.get(2).name);
        assertTrue(ChatLocks.listed("1"));
        assertFalse(ChatLocks.listed("4"));
        ChatLocks.add("1", "Anna");
        ChatLocks.add("5", "Eve");
        ChatLocks.remove("2");
        assertEquals(java.util.Arrays.asList("1", "3", "bad,id", "5"), ids());
        assertEquals("Anna", ChatLocks.chats().get(0).name);
    }

    @Test
    public void aListedChatStaysLockedWhilePausedAndWithoutContext() {
        Settings.LOCK_MESSAGES.save(false);
        ChatLocks.add(ALICE, "Alice");
        Activity activity = chat("Alice");
        open(ALICE);
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertTrue(reason.name(), MessagesLock.chatListLocked());
            MessagesLock.check(activity);
            assertEquals(reason.name(), View.VISIBLE, cover(activity).getVisibility());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse("nothing to read yet", ChatLocks.any()));
    }

    private static List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (ChatLocks.Chat chat : ChatLocks.chats()) ids.add(chat.id);
        return ids;
    }

    /** Every text in [view], to see what a cover says. */
    private static List<String> texts(View view) {
        List<String> found = new ArrayList<>();
        if (view instanceof android.widget.TextView) found.add(((android.widget.TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) found.addAll(texts(((ViewGroup) view).getChildAt(i)));
        }
        return found;
    }

    private static ActivityController<Activity> inbox() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(FRAME);
        View list = new View(activity);
        list.setId(LIST);
        frame.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600));
        root.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 800));
        activity.setContentView(root);
        layout(activity);
        return controller;
    }

    private static void layout(Activity activity) {
        ShadowLooper.idleMainLooper();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
    }

    /** Where [view] shows in its window: the part a cover has to hide. */
    private static Rect visible(View view) {
        Rect area = new Rect();
        assertTrue(view.getGlobalVisibleRect(area));
        return area;
    }

    /** The cover the lock put in the activity's window, if any. */
    /** The cover for [screen] the lock put in the activity's window, if any. */
    private static View cover(Activity activity, String screen) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (child instanceof MessagesLock.Cover && ((MessagesLock.Cover) child).screen.equals(screen)) return child;
        }
        return null;
    }

    private static View cover(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (MessagesLock.COVER_TAG.equals(child.getTag())) return child;
        }
        return null;
    }

    private static int covers(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        int count = 0;
        for (int i = 0; i < decor.getChildCount(); i++) {
            if (MessagesLock.COVER_TAG.equals(decor.getChildAt(i).getTag())) count++;
        }
        return count;
    }

    private static Notification message(String channel, String category) {
        Context context = RuntimeEnvironment.getApplication();
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent("open"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Alice")
                .setContentText("meet me at 8")
                .setContentIntent(open)
                .setGroup("direct")
                .addAction(new Notification.Action.Builder(null, "Reply", open).build());
        if (category != null) builder.setCategory(category);
        return builder.build();
    }
}
