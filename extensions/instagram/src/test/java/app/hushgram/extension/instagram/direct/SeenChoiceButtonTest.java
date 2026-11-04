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
import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

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

import app.hushgram.extension.instagram.direct.SeenChoices.Choice;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The eye in a chat's header while Choose Seen in each chat is on (#13). */
@RunWith(RobolectricTestRunner.class)
public class SeenChoiceButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private LinearLayout row;
    private MarkReadButtonTest.FakeChatScreen fragment;
    private ThreadSeenTest.FakeChats chats;

    /** The chat laid out as in MarkReadButtonTest, signed in to the first account, with the switch on. */
    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        Settings.SEEN_PER_CHAT.save(true);
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
        fragment = new MarkReadButtonTest.FakeChatScreen(chat);

        Map<String, Integer> ids = new HashMap<>();
        ids.put(ChatPager.BUTTON_ROW, row.getId());
        MessagesLock.idsForTests = ids;
        Map<String, Drawable> icons = new HashMap<>();
        icons.put(MarkReadButton.ICONS[0], new ColorDrawable(Color.WHITE));
        icons.put(SeenChoiceButton.SENDS[0], new ColorDrawable(Color.GREEN));
        icons.put(SeenChoiceButton.HOLDS[0], new ColorDrawable(Color.RED));
        ChatPager.iconsForTests = icons;
        ChatLocks.reader = screen -> "t1";
        ChatAccounts.signInForTests("a1");
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
        Settings.SEEN_PER_CHAT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ThreadSeen.forgetMarks();
        ShadowLooper.idleMainLooper();
        HookStatus.clear();
    }

    /** The chat comes to the front, the chat lock reading its id and account first. */
    private void open() {
        ChatLocks.opened(fragment);
        MarkReadButton.opened(fragment);
    }

    /** Instagram sends a receipt of [thread] on the first account, and the hold keeps it for the check mark. */
    private void held(String thread) {
        assertTrue(ThreadSeen.hold(new ThreadSeenTest.Receipt(thread, "m1"), new ThreadSeenTest.Session("a1"),
                () -> true, () -> false, () -> false, () -> true, chats, () -> 5_000L));
        ShadowLooper.idleMainLooper();
    }

    private ImageView eye() {
        return (ImageView) row.findViewWithTag(SeenChoiceButton.TAG);
    }

    private View checkMark() {
        return row.findViewWithTag(MarkReadButton.TAG);
    }

    private static int color(ImageView view) {
        return ((ColorDrawable) view.getDrawable()).getColor();
    }

    private static String toast() {
        ShadowLooper.idleMainLooper();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    private static void assertNothingReported() {
        assertTrue(HookStatus.missing(FamilyNames.THREAD_SEEN).toString(),
                HookStatus.missing(FamilyNames.THREAD_SEEN).isEmpty());
    }

    @Test
    public void theEyeSitsAfterTheCheckMarkAndShowsWhatTheChatDoes() {
        open();
        assertEquals(3, row.getChildCount());
        assertEquals(MarkReadButton.TAG, row.getChildAt(0).getTag());
        ImageView eye = eye();
        assertEquals(1, row.indexOfChild(eye));
        assertEquals("laid out like the call buttons", 66, eye.getLayoutParams().width);
        assertEquals(View.VISIBLE, eye.getVisibility());
        assertEquals("the switch holds Seen", "Seen held in this chat", String.valueOf(eye.getContentDescription()));
        assertEquals(Color.RED, color(eye));

        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        SeenChoiceButton.refresh();
        assertEquals("Seen goes out in this chat", String.valueOf(eye.getContentDescription()));
        assertEquals(Color.GREEN, color(eye));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        SeenChoiceButton.refresh();
        assertEquals("paused, the eye goes", View.GONE, eye.getVisibility());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SeenChoiceButton.refresh();
        assertEquals(View.VISIBLE, eye.getVisibility());
        assertNothingReported();
    }

    @Test
    public void withItsSwitchOffThereIsNoEye() {
        Settings.SEEN_PER_CHAT.save(false);
        open();
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        assertNull(eye());
        assertNotNull("the check mark is its own switch's", checkMark());
        assertEquals(2, row.getChildCount());
    }

    @Test
    public void aChatWhoseAccountIsntKnownShowsNoEye() {
        ChatAccounts.reader = screen -> null;
        open();
        ImageView eye = eye();
        assertNotNull(eye);
        assertEquals("a choice would belong to no chat", View.GONE, eye.getVisibility());
    }

    @Test
    public void aTapOpensTheChatsThreeChoicesWithItsOwnChecked() {
        open();
        ImageView eye = eye();
        assertTrue(eye.performClick());
        PopupMenu popup = SeenChoiceButton.shownForTests();
        assertNotNull(popup);
        Menu menu = popup.getMenu();
        assertEquals(3, menu.size());
        assertEquals("Use the HushGram setting", String.valueOf(menu.getItem(0).getTitle()));
        assertEquals("Hold Seen in this chat", String.valueOf(menu.getItem(1).getTitle()));
        assertEquals("Send Seen in this chat", String.valueOf(menu.getItem(2).getTitle()));
        assertTrue(menu.getItem(0).isChecked());
        assertFalse(menu.getItem(1).isChecked());
        assertFalse(menu.getItem(2).isChecked());
        popup.dismiss();

        assertTrue(ThreadSeen.choose("a1", "t1", Choice.SEND));
        assertTrue(eye.performClick());
        menu = SeenChoiceButton.shownForTests().getMenu();
        assertFalse(menu.getItem(0).isChecked());
        assertTrue(menu.getItem(2).isChecked());
        assertNothingReported();
    }

    @Test
    public void pickingAChoiceChangesTheEyeAndSaysSo() {
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(false);
        open();
        ImageView eye = eye();
        assertEquals(Color.GREEN, color(eye));

        assertTrue(eye.performClick());
        assertTrue(SeenChoiceButton.shownForTests().getMenu().performIdentifierAction(1, 0));
        assertEquals("Seen held in this chat", toast());
        assertEquals(Choice.HOLD, ThreadSeen.choiceOf("a1", "t1"));
        assertEquals(Color.RED, color(eye));
        assertEquals("Seen held in this chat", String.valueOf(eye.getContentDescription()));

        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        SeenChoiceButton.pick("a1", "t1", Choice.SEND);
        assertEquals("Seen goes out in this chat", toast());
        assertEquals("the chat's own choice beats the switch", Color.GREEN, color(eye));

        SeenChoiceButton.pick("a1", "t1", Choice.SWITCH);
        assertEquals("This chat uses the setting", toast());
        assertEquals(Choice.SWITCH, ThreadSeen.choiceOf("a1", "t1"));
        assertEquals(Color.RED, color(eye));
        assertTrue("choosing sends nothing by itself", chats.sent.isEmpty());
        assertNothingReported();
    }

    @Test
    public void choosingSendHidesAHeldChatsCheckMark() {
        open();
        held("t1");
        View check = checkMark();
        assertEquals(View.VISIBLE, check.getVisibility());

        SeenChoiceButton.pick("a1", "t1", Choice.SEND);
        assertEquals(View.GONE, check.getVisibility());
        assertTrue("the held receipt doesn't go out", chats.sent.isEmpty());
        SeenChoiceButton.pick("a1", "t1", Choice.SWITCH);
        assertEquals(View.VISIBLE, check.getVisibility());
    }

    @Test
    public void aChoiceForNoChatIsntSaved() {
        SeenChoiceButton.pick(null, "t1", Choice.HOLD);
        assertEquals("Couldn't save the choice", toast());
        assertEquals(Choice.SWITCH, ThreadSeen.choiceOf("a1", "t1"));

        open();
        ImageView eye = eye();
        ChatLocks.resetForTests();
        assertTrue(eye.performClick());
        assertNull("no menu for a chat that's gone", SeenChoiceButton.shownForTests());
        assertEquals("Couldn't save the choice", toast());
    }

    @Test
    public void leavingTheChatTakesTheEyeAndItsMenuOut() {
        open();
        assertTrue(eye().performClick());
        assertNotNull(SeenChoiceButton.shownForTests());
        MarkReadButton.closed(fragment);
        assertNull(eye());
        assertNull(SeenChoiceButton.shownForTests());
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        assertNull("it doesn't come back once the chat is gone", eye());
    }

    @Test
    public void anEyeTakenOutOfTheRowComesBackAfterTheCheckMark() {
        open();
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        row.removeAllViews();
        ShadowLooper.idleMainLooper();
        assertNotNull("the eye didn't come back", eye());
        assertEquals(0, row.indexOfChild(checkMark()));
        assertEquals(1, row.indexOfChild(eye()));
        assertEquals(2, row.getChildCount());
    }
}
