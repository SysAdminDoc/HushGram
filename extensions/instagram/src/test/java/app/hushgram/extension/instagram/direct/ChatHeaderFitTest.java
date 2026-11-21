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
import android.view.ViewGroup;
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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.direct.SeenChoices.Choice;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A chat's header with HushGram's buttons in it (#802): on a narrow phone the check mark, the eye and the
 * chat menu pushed the name out of the bar and the last call button past the screen's edge. They fold into
 * More there, and stay as they were where the bar has room.
 */
@RunWith(RobolectricTestRunner.class)
public class ChatHeaderFitTest {
    /** Every size here is at density 3, so 48dp is 144 pixels. */
    private static final int BUTTON = 144;
    private static final int TALL = 2400;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private int wide;
    private FrameLayout root;
    private LinearLayout bar;
    private LinearLayout nameArea;
    private LinearLayout row;
    private final List<ImageView> calls = new ArrayList<>();
    private MarkReadButtonTest.FakeChatScreen fragment;
    private ThreadSeenTest.FakeChats chats;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        Settings.SEEN_PER_CHAT.save(true);
        Settings.JUMP_IN_CHAT.save(true);
        HookStatus.clear();
        ThreadSeen.forgetMarks();
        ShadowToast.reset();
        chats = new ThreadSeenTest.FakeChats();
        Map<String, Drawable> icons = new HashMap<>();
        icons.put(MarkReadButton.ICONS[0], new ColorDrawable(Color.WHITE));
        icons.put(SeenChoiceButton.SENDS[0], new ColorDrawable(Color.GREEN));
        icons.put(SeenChoiceButton.HOLDS[0], new ColorDrawable(Color.RED));
        icons.put(ChatPager.BUTTON_ICONS[0], new ColorDrawable(Color.BLUE));
        icons.put(ChatHeaderFit.MORE_ICONS[0], new ColorDrawable(Color.GRAY));
        ChatPager.iconsForTests = icons;
        ChatLocks.reader = screen -> "t1";
        ChatAccounts.signInForTests("a1");
    }

    @After
    public void restore() {
        if (fragment != null) {
            ChatPager.closed(fragment);
            MarkReadButton.closed(fragment);
        }
        MarkReadButton.resetForTests();
        ChatLocks.resetForTests();
        MessagesLock.idsForTests = null;
        ChatPager.iconsForTests = null;
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.MARK_READ_IN_CHAT.resetToDefault();
        Settings.SEEN_PER_CHAT.resetToDefault();
        Settings.JUMP_IN_CHAT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ThreadSeen.forgetMarks();
        ShadowLooper.idleMainLooper();
        HookStatus.clear();
    }

    /**
     * Instagram's chat header on a phone [dp] wide: back and the picture at 48dp each, the area holding the
     * name weighted to fill what's left, then the row of three 48dp call buttons. The chat's own view sits
     * under the bar.
     */
    private void header(int dp) {
        RuntimeEnvironment.setQualifiers("w" + dp + "dp-h800dp-xxhdpi");
        wide = dp * 3;
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(new ImageView(activity), new LinearLayout.LayoutParams(BUTTON, BUTTON));
        bar.addView(new ImageView(activity), new LinearLayout.LayoutParams(BUTTON, BUTTON));
        nameArea = new LinearLayout(activity);
        nameArea.setId(View.generateViewId());
        bar.addView(nameArea, new LinearLayout.LayoutParams(0, BUTTON, 1f));
        row = new LinearLayout(activity);
        row.setId(View.generateViewId());
        calls.clear();
        for (int i = 0; i < 3; i++) {
            ImageView call = new ImageView(activity);
            call.setPadding(36, 36, 36, 36);
            calls.add(call);
            row.addView(call, new LinearLayout.LayoutParams(BUTTON, BUTTON));
        }
        bar.addView(row, new LinearLayout.LayoutParams(WRAP, BUTTON));
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(bar, new LinearLayout.LayoutParams(MATCH, BUTTON));
        FrameLayout chat = new FrameLayout(activity);
        column.addView(chat, new LinearLayout.LayoutParams(MATCH, MATCH));
        root = new FrameLayout(activity);
        root.addView(column, new FrameLayout.LayoutParams(MATCH, MATCH));
        activity.setContentView(root);
        fragment = new MarkReadButtonTest.FakeChatScreen(chat);

        Map<String, Integer> ids = new HashMap<>();
        ids.put(ChatPager.BUTTON_ROW, row.getId());
        ids.put(ChatPager.NAME_AREA, nameArea.getId());
        MessagesLock.idsForTests = ids;
        settle();
    }

    /** The chat comes to the front: the chat lock reads it, then the check mark, the eye and the chat menu go in. */
    private void open() {
        ChatLocks.opened(fragment);
        MarkReadButton.opened(fragment);
        ChatPager.opened(fragment);
        settle();
    }

    /** Instagram sends a receipt of the chat on the first account, and the hold keeps it for the check mark. */
    private void held() {
        assertTrue(ThreadSeen.hold(new ThreadSeenTest.Receipt("t1", "m1"), new ThreadSeenTest.Session("a1"),
                () -> true, () -> false, () -> false, () -> true, chats, () -> 5_000L));
        settle();
    }

    /** Lays the screen out and draws until the bar stops changing, the way frames follow each other. */
    private void settle() {
        for (int frame = 0; frame < 4; frame++) {
            ShadowLooper.idleMainLooper();
            root.measure(View.MeasureSpec.makeMeasureSpec(wide, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(TALL, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, wide, TALL);
            root.getViewTreeObserver().dispatchOnPreDraw();
        }
        ChatHeaderFit.dirty(row);
        assertFalse("the bar kept changing", root.getViewTreeObserver().dispatchOnPreDraw());
    }

    private View ours(String tag) {
        View found = row.findViewWithTag(tag);
        assertNotNull(tag + " isn't in the row", found);
        return found;
    }

    private View more() {
        return row.findViewWithTag(ChatHeaderFit.MORE_TAG);
    }

    private static boolean shows(View view) {
        return view != null && view.getVisibility() == View.VISIBLE;
    }

    /** Every call button shows whole, inside the row and the bar. */
    private void assertCallButtonsFit() {
        assertTrue("the row runs past the bar: " + row.getRight() + " of " + bar.getWidth(), row.getRight() <= bar.getWidth());
        for (ImageView call : calls) {
            assertEquals(View.VISIBLE, call.getVisibility());
            assertTrue("a call button is cut off: it ends at " + call.getRight() + " in a row " + row.getWidth() + " wide",
                    call.getRight() <= row.getWidth());
        }
    }

    private List<String> titles(Menu menu) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) titles.add(String.valueOf(menu.getItem(i).getTitle()));
        return titles;
    }

    private Menu tapMore() {
        assertTrue(more().performClick());
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull("More opened nothing", popup);
        return popup.getMenu();
    }

    /** The reporter's phone, 360dp wide with a receipt held: all three of ours fold into More and the name comes back. */
    @Test
    public void aNarrowPhoneFoldsOursIntoMoreAndEveryCallButtonFits() {
        header(360);
        open();
        held();

        View more = more();
        assertTrue("no More", shows(more));
        assertEquals("More sits where ours did", 0, row.indexOfChild(more));
        assertEquals("laid out like the call buttons", BUTTON, more.getLayoutParams().width);
        assertEquals("More", String.valueOf(more.getContentDescription()));
        assertFalse(shows(ours(MarkReadButton.TAG)));
        assertFalse(shows(ours(SeenChoiceButton.TAG)));
        assertFalse(shows(ours(ChatPager.BUTTON_TAG)));
        assertCallButtonsFit();
        assertEquals("the name gets what's left of the bar", wide - 2 * BUTTON - 4 * BUTTON, nameArea.getWidth());
    }

    @Test
    public void aWideHeaderKeepsEveryButton() {
        header(600);
        open();
        held();

        assertFalse(shows(more()));
        assertTrue(shows(ours(MarkReadButton.TAG)));
        assertTrue(shows(ours(SeenChoiceButton.TAG)));
        assertTrue(shows(ours(ChatPager.BUTTON_TAG)));
        assertCallButtonsFit();
        assertTrue("the name has room: " + nameArea.getWidth(), nameArea.getWidth() >= 240);
    }

    /** At 420dp the eye and the menu fit, and the check mark showing up for a held receipt is one too many. */
    @Test
    public void theCheckMarkComingAndGoingFoldsAndUnfolds() {
        header(420);
        open();
        assertFalse(shows(more()));
        assertTrue(shows(ours(SeenChoiceButton.TAG)));
        assertTrue(shows(ours(ChatPager.BUTTON_TAG)));
        assertFalse("nothing held yet", shows(ours(MarkReadButton.TAG)));

        held();
        assertTrue(shows(more()));
        assertFalse(shows(ours(MarkReadButton.TAG)));
        assertFalse(shows(ours(SeenChoiceButton.TAG)));
        assertFalse(shows(ours(ChatPager.BUTTON_TAG)));
        assertCallButtonsFit();

        Menu menu = tapMore();
        assertEquals("Mark as read", titles(menu).get(0));
        assertTrue(menu.performIdentifierAction(menu.getItem(0).getItemId(), 0));
        settle();
        assertEquals("Marked as read", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertFalse("let go through Instagram's own sender", ThreadSeen.hasHeld("a1", "t1", 0));
        assertFalse("the receipt went, so the bar has room again", shows(more()));
        assertTrue(shows(ours(SeenChoiceButton.TAG)));
        assertTrue(shows(ours(ChatPager.BUTTON_TAG)));
        assertFalse(shows(ours(MarkReadButton.TAG)));
        assertCallButtonsFit();
    }

    @Test
    public void moreHoldsEverythingTheFoldedButtonsDo() {
        header(360);
        open();
        held();

        Menu menu = tapMore();
        List<String> titles = titles(menu);
        assertEquals(6, titles.size());
        assertEquals("Mark as read", titles.get(0));
        assertEquals("Use the HushGram setting", titles.get(1));
        assertEquals("Hold Seen in this chat", titles.get(2));
        assertEquals("Send Seen in this chat", titles.get(3));
        assertEquals("Jump to the first message", titles.get(4));
        assertEquals("Jump to a date", titles.get(5));
        assertTrue("the chat's own choice is checked", menu.getItem(1).isChecked());
        assertFalse(menu.getItem(2).isChecked());
        assertNotNull("the Seen choices carry glyphs, so they line up with the chat menu's lines", menu.getItem(2).getIcon());
        assertNotNull(menu.getItem(4).getIcon());

        assertTrue(menu.performIdentifierAction(menu.getItem(3).getItemId(), 0));
        ShadowLooper.idleMainLooper();
        assertEquals("Seen goes out in this chat", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertEquals(Choice.SEND, ThreadSeen.choiceOf("a1", "t1"));
        assertTrue("choosing sends nothing by itself", chats.sent.isEmpty());

        menu = tapMore();
        assertTrue("the new choice is checked", menu.getItem(menu.size() - 3).isChecked());
    }

    /** Folding one button into one button gains nothing, so a lone button of ours stays itself. */
    @Test
    public void aLoneButtonOfOursStays() {
        Settings.SEEN_PER_CHAT.save(false);
        header(360);
        open();

        assertNull(more());
        assertTrue(shows(ours(ChatPager.BUTTON_TAG)));
    }

    @Test
    public void leavingTheChatTakesMoreOut() {
        header(360);
        open();
        held();
        assertTrue(shows(more()));

        ChatPager.closed(fragment);
        MarkReadButton.closed(fragment);
        settle();
        assertNull(more());
        assertEquals("only Instagram's buttons are left", 3, row.getChildCount());
        fragment = null;
    }

    /** Without the name's area in the header nothing is known about the room, so nothing folds, as before #802. */
    @Test
    public void withNoNameInTheHeaderNothingFolds() {
        header(360);
        Map<String, Integer> ids = new HashMap<>(MessagesLock.idsForTests);
        ids.remove(ChatPager.NAME_AREA);
        MessagesLock.idsForTests = ids;
        open();
        held();

        assertNull(more());
        assertTrue(shows(ours(MarkReadButton.TAG)));
        assertTrue(shows(ours(SeenChoiceButton.TAG)));
        assertTrue(shows(ours(ChatPager.BUTTON_TAG)));
    }
}
