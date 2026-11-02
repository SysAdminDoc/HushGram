/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The check mark in a chat's header while its seen receipt is held (#458). */
@RunWith(RobolectricTestRunner.class)
public class MarkReadButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** The chat screen the patch hands over: only its view is read, by name. */
    public static final class FakeChatScreen {
        private final View view;

        FakeChatScreen(View view) {
            this.view = view;
        }

        public View getView() {
            return view;
        }
    }

    private LinearLayout row;
    private FakeChatScreen fragment;
    private ThreadSeenTest.FakeChats chats;

    /** An activity laid out like Instagram's chat: the action bar with its row of call buttons beside the chat's own view. */
    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        HookStatus.clear();
        ThreadSeen.forgetMarks();
        ShadowToast.reset();
        chats = new ThreadSeenTest.FakeChats();

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout actionBar = new FrameLayout(activity);
        row = new LinearLayout(activity);
        row.setId(View.generateViewId());
        ImageView call = new ImageView(activity);
        call.setPadding(9, 9, 9, 9);
        row.addView(call, new LinearLayout.LayoutParams(66, 66));
        actionBar.addView(row);
        FrameLayout chat = new FrameLayout(activity);
        column.addView(actionBar);
        column.addView(chat);
        activity.setContentView(column);
        fragment = new FakeChatScreen(chat);

        Map<String, Integer> ids = new HashMap<>();
        ids.put(ChatPager.BUTTON_ROW, row.getId());
        MessagesLock.idsForTests = ids;
        Map<String, Drawable> icons = new HashMap<>();
        icons.put(MarkReadButton.ICONS[0], new ColorDrawable(Color.WHITE));
        ChatPager.iconsForTests = icons;
        ChatLocks.reader = screen -> "t1";
    }

    @After
    public void restore() {
        MarkReadButton.closed(fragment);
        MarkReadButton.resetForTests();
        ChatLocks.resetForTests();
        MessagesLock.idsForTests = null;
        ChatPager.iconsForTests = null;
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.MARK_READ_IN_CHAT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ThreadSeen.forgetMarks();
        ShadowLooper.idleMainLooper();
        HookStatus.clear();
    }

    /** The chat comes to the front, the chat lock reading its id first. */
    private void open() {
        ChatLocks.opened(fragment);
        MarkReadButton.opened(fragment);
    }

    /** Instagram sends a receipt of [thread] on the first account, and the hold keeps it. */
    private void held(String thread) {
        assertTrue(ThreadSeen.hold(new ThreadSeenTest.Receipt(thread, "m1"), new ThreadSeenTest.Session("a1"),
                () -> true, () -> false, () -> false, () -> true, chats, () -> 5_000L));
        ShadowLooper.idleMainLooper();
    }

    private View checkMark() {
        return row.findViewWithTag(MarkReadButton.TAG);
    }

    @Test
    public void theCheckMarkSitsFirstInTheRowAndShowsOnlyWhileTheChatsReceiptIsHeld() {
        open();
        assertEquals(2, row.getChildCount());
        View ours = row.getChildAt(0);
        assertEquals(MarkReadButton.TAG, ours.getTag());
        assertEquals("laid out like the call buttons", 66, ours.getLayoutParams().width);
        assertEquals("Mark as read", String.valueOf(ours.getContentDescription()));
        assertEquals("nothing held yet", View.GONE, ours.getVisibility());

        held("t2");
        assertEquals("another chat's receipt", View.GONE, ours.getVisibility());
        held("t1");
        assertEquals(View.VISIBLE, ours.getVisibility());
        assertTrue(HookStatus.missing(FamilyNames.THREAD_SEEN).toString(),
                HookStatus.missing(FamilyNames.THREAD_SEEN).isEmpty());
    }

    @Test
    public void aTapSendsTheReceiptAndTheCheckMarkGoes() {
        open();
        held("t1");
        View ours = checkMark();
        assertEquals(View.VISIBLE, ours.getVisibility());

        assertTrue(ours.performClick());
        ShadowLooper.idleMainLooper();

        assertEquals("Marked as read", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertEquals(View.GONE, ours.getVisibility());
        assertFalse("let go through Instagram's own sender", ThreadSeen.hasHeld(null, "t1", 0));
    }

    @Test
    public void withItsSwitchOffThereIsNoCheckMark() {
        Settings.MARK_READ_IN_CHAT.save(false);
        open();
        held("t1");
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        assertNull(checkMark());
        assertEquals(1, row.getChildCount());
    }

    @Test
    public void leavingTheChatTakesTheCheckMarkOut() {
        open();
        held("t1");
        assertNotNull(checkMark());
        MarkReadButton.closed(fragment);
        assertNull(checkMark());
        held("t1");
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        assertNull("it doesn't come back once the chat is gone", checkMark());
    }

    @Test
    public void aCheckMarkTakenOutOfTheRowComesBack() {
        open();
        held("t1");
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);

        row.removeAllViews();
        ShadowLooper.idleMainLooper();

        View back = checkMark();
        assertNotNull("the check mark didn't come back", back);
        assertEquals(View.VISIBLE, back.getVisibility());
    }

    @Test
    public void theHeaderKeepsOneCheckMark() {
        open();
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        open();
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        assertEquals(2, row.getChildCount());
    }
}
