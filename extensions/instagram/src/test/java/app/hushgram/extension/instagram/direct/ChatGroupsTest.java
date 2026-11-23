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
import android.app.AlertDialog;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ListView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

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

/** Chat groups (discussion #838): what the inbox shows, what the menu row and a long press on Messages do. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ChatGroupsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ME = MessagesLockTest.ME;
    private static final String ALICE = "340282366841710300949128";
    private static final String BOB = "340282366841710300949999";
    private static final String CAROL = "340282366841710300947777";

    /** Instagram's menu actions, cut down: a Details row it builds and never offers among rows it does. */
    enum Menu { MUTE, DETAILS, DELETE }

    /** A thread summary standing in for Instagram's: the id is all the extension reads from it. */
    private static final class Summary {
        final String id;

        Summary(String id) {
            this.id = id;
        }
    }

    /** Instagram's key for a chat: the id is all the extension reads from it. */
    private static final class Key {
        final String id;

        Key(String id) {
            this.id = id;
        }
    }

    private final List<Object[]> redrawn = new ArrayList<>();
    private final List<Object[]> grouped = new ArrayList<>();
    private final List<Activity> picked = new ArrayList<>();

    @Before
    public void prepare() {
        MessagesLock.resetForTests();
        HiddenChats.resetForTests();
        HiddenChatsMenu.resetForTests();
        HiddenChatsTap.resetForTests();
        ChatAccounts.signInForTests(ME);
        HiddenChats.reader = summary -> ((Summary) summary).id;
        HiddenChats.redrawer = (holder, summaries) -> redrawn.add(new Object[]{holder, summaries});
        HiddenChatsMenu.reader = key -> ((Key) key).id;
        HiddenChatsMenu.grouper = (account, id, name, hiding) -> grouped.add(new Object[]{account, id, name, hiding});
        HiddenChatsTap.buttons = (shown, x, y) -> true;
        HiddenChatsTap.grouper = picked::add;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.CHAT_GROUPS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        HiddenChatsTap.resetForTests();
        HiddenChatsMenu.resetForTests();
        HiddenChats.resetForTests();
        MessagesLock.resetForTests();
        Settings.CHAT_GROUPS.resetToDefault();
        Settings.GROUPED_CHATS.resetToDefault();
        Settings.HIDDEN_CHATS.resetToDefault();
        Settings.DOUBLE_TAP_HIDDEN_CHATS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    // ---------------------------------------------------------------- the list

    @Test
    public void theListKeepsEachChatsAccountGroupAndName() {
        List<ChatGroups.Row> rows = ChatGroups.parse(ChatGroups.format(Arrays.asList(
                new ChatGroups.Row(ME, ALICE, "Work", "Alice"),
                new ChatGroups.Row("99", BOB, "Family", "Bob\tand\nco"))));

        assertEquals(2, rows.size());
        assertEquals(ME, rows.get(0).account);
        assertEquals(ALICE, rows.get(0).id);
        assertEquals("Work", rows.get(0).group);
        assertEquals("Alice", rows.get(0).name);
        assertEquals("a tab or line break in a name can't split its line", "Bob and co", rows.get(1).name);
        assertTrue("a line with no group is skipped", ChatGroups.parse("#groups 1\n1\t2\t \tx\n\n1\t\tWork\tx").isEmpty());
    }

    @Test
    public void aTypedNameIsTrimmedRunTogetherAndCut() {
        assertEquals("Close friends", ChatGroups.clean("  Close \t friends\n"));
        assertNull(ChatGroups.clean(" \n "));
        assertNull(ChatGroups.clean(null));
        String longName = String.join("", Collections.nCopies(60, "a"));
        assertEquals(ChatGroups.MAX_NAME, ChatGroups.clean(longName).length());
    }

    @Test
    public void aGroupTypedInAnotherCaseIsTheSameGroup() {
        assertTrue(ChatGroups.move(ME, ALICE, "Alice", "Work"));
        assertTrue(ChatGroups.move(ME, BOB, "Bob", "work"));

        assertEquals(Collections.singletonList("Work"), ChatGroups.groups(ME));
        assertEquals("Work", ChatGroups.groupOf(ME, BOB));
        assertEquals(2, ChatGroups.in(ME, "WORK").size());
    }

    // ---------------------------------------------------------------- the inbox

    @Test
    public void aGroupedChatLeavesTheMainInbox() {
        assertTrue(ChatGroups.move(ME, ALICE, "Alice", "Work"));
        List<Object> inbox = Collections.unmodifiableList(list(ALICE, BOB, CAROL));

        assertEquals(Arrays.asList(BOB, CAROL), ids(HiddenChats.inbox(null, inbox)));
        assertEquals("Instagram's own list is untouched", Arrays.asList(ALICE, BOB, CAROL), ids(inbox));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("grouped chats left out of the inbox 1"));
        assertSame("the folder counts still see it", inbox, HiddenChats.folder(inbox));
    }

    @Test
    public void aGroupShownByItselfHasOnlyItsChatsAndAllChatsBringsTheRestBack() {
        ChatGroups.move(ME, ALICE, "Alice", "Work");
        ChatGroups.move(ME, BOB, "Bob", "Family");
        List<Object> inbox = list(ALICE, BOB, CAROL);

        ChatGroups.show("Work");
        assertEquals(Collections.singletonList(ALICE), ids(HiddenChats.inbox(null, inbox)));
        assertEquals("Work", ChatGroups.showing(ME));

        ChatGroups.show(null);
        assertEquals(Collections.singletonList(CAROL), ids(HiddenChats.inbox(null, inbox)));
    }

    @Test
    public void aHiddenChatStaysOutOfItsGroupUntilTheHiddenChatsShow() {
        ChatGroups.move(ME, ALICE, "Alice", "Work");
        ChatGroups.move(ME, BOB, "Bob", "Work");
        HiddenChats.add(BOB, "Bob");
        List<Object> inbox = list(ALICE, BOB, CAROL);

        ChatGroups.show("Work");
        assertEquals(Collections.singletonList(ALICE), ids(HiddenChats.inbox(null, inbox)));

        HiddenChats.reveal();
        assertNull("the hidden chats take the inbox over", ChatGroups.showing(ME));
        assertEquals(Collections.singletonList(BOB), ids(HiddenChats.inbox(null, inbox)));
    }

    @Test
    public void groupsChangeNothingWhileSwitchedOffOrPaused() {
        ChatGroups.move(ME, ALICE, "Alice", "Work");
        List<Object> inbox = list(ALICE, BOB);

        Settings.CHAT_GROUPS.save(false);
        assertSame(inbox, HiddenChats.inbox(null, inbox));
        assertFalse("nothing is filed while it's off", ChatGroups.move(ME, BOB, "Bob", "Work"));

        Settings.CHAT_GROUPS.save(true);
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertSame(reason.name(), inbox, HiddenChats.inbox(null, inbox));

            PauseForTests.resume();
            assertEquals(reason.name(), Collections.singletonList(BOB), ids(HiddenChats.inbox(null, inbox)));
        }
    }

    @Test
    public void anotherAccountsGroupsLeaveThisInboxAlone() {
        ChatGroups.move(ME, ALICE, "Alice", "Work");
        ChatAccounts.signInForTests("5678");

        List<Object> inbox = list(ALICE, BOB);
        assertSame(inbox, HiddenChats.inbox(null, inbox));
        assertTrue(ChatGroups.groups("5678").isEmpty());
        assertNull(ChatGroups.groupOf("5678", ALICE));
    }

    @Test
    public void movingAChatDrawsTheInboxAgainAndAnEmptiedGroupGoesBackToAllChats() {
        Object holder = new Object();
        ChatGroups.move(ME, ALICE, "Alice", "Work");
        ChatGroups.show("Work");
        assertEquals(Collections.singletonList(ALICE), ids(HiddenChats.inbox(holder, list(ALICE, BOB))));
        redrawn.clear();

        assertTrue("out of its group", ChatGroups.move(ME, ALICE, "Alice", null));
        shadowOf(Looper.getMainLooper()).idle();

        assertNull("no chat left in it", ChatGroups.showing(ME));
        assertEquals(Settings.GROUPED_CHATS.defaultValue, Settings.GROUPED_CHATS.get());
        assertFalse(redrawn.isEmpty());
        assertSame(holder, redrawn.get(redrawn.size() - 1)[0]);
        assertEquals(Arrays.asList(ALICE, BOB), ids(rows(redrawn.size() - 1)));
    }

    @Test
    public void leavingInstagramGoesBackToAllChats() {
        ChatGroups.move(ME, ALICE, "Alice", "Work");
        ChatGroups.show("Work");

        MessagesLock.left();

        assertNull(ChatGroups.showing(ME));
        assertEquals(Collections.singletonList(BOB), ids(HiddenChats.inbox(null, list(ALICE, BOB))));
    }

    // ---------------------------------------------------------------- the chat's menu row

    @Test
    public void theMenuRowMovesAChatToAGroupWithGroupsOn() {
        List<Object> rows = new ArrayList<>(Arrays.asList(Menu.MUTE, Menu.DELETE));
        HiddenChatsMenu.offer(rows, Menu.class);
        assertEquals("offered with only Chat groups on", Arrays.asList(Menu.MUTE, Menu.DELETE, Menu.DETAILS), rows);

        String[] labels = {"Mute", "Delete", "Details"};
        HiddenChatsMenu.label(rows, labels);
        assertEquals("Move to group", labels[2]);
        Settings.DOUBLE_TAP_HIDDEN_CHATS.save(true);
        HiddenChatsMenu.label(rows, labels);
        assertEquals("Group or hide chat", labels[2]);
        assertEquals("Mute", labels[0]);

        HiddenChatsMenu.shown(new Key(ALICE), " Alice ");
        assertTrue("taken from Instagram", HiddenChatsMenu.tap(Menu.DETAILS, new Key(ALICE)));
        assertEquals(1, grouped.size());
        assertEquals(Arrays.asList(ME, ALICE, "Alice", true), Arrays.asList(grouped.get(0)));
        assertTrue("nothing hidden by the tap itself", HiddenChats.saved().isEmpty());
    }

    @Test
    public void theChatsGroupsListFilesItAndSaysWhere() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ChatGroups.move(ME, BOB, "Bob", "Work");

        ChatGroups.pickFor(activity, ME, ALICE, "Alice", null, null);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Alice", shadowOf(dialog).getTitle().toString());
        ListView list = dialog.getListView();
        assertEquals(2, list.getCount());
        assertEquals("No group", list.getItemAtPosition(0).toString());
        assertEquals("Work", list.getItemAtPosition(1).toString());
        assertEquals("not in a group yet", 0, list.getCheckedItemPosition());

        shadowOf(dialog).clickOnItem(1);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Work", ChatGroups.groupOf(ME, ALICE));
        String toast = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(toast, toast.contains("Alice") && toast.contains("Work"));
    }

    // ---------------------------------------------------------------- a long press on Messages

    @Test
    public void aLongPressOnMessagesOpensTheGroupsAndStillReachesInstagram() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        long held = ViewConfiguration.getLongPressTimeout();

        assertFalse(HiddenChatsTap.decide(activity, MotionEvent.ACTION_DOWN, 100, 1500, 0));
        assertFalse("the up goes on to Instagram", HiddenChatsTap.decide(activity, MotionEvent.ACTION_UP, 100, 1500, held + 10));
        assertEquals(1, picked.size());
        assertSame(activity, picked.get(0));

        assertFalse(HiddenChatsTap.decide(activity, MotionEvent.ACTION_DOWN, 100, 1500, 2000));
        assertFalse(HiddenChatsTap.decide(activity, MotionEvent.ACTION_UP, 100, 1500, 2050));
        assertEquals("a plain tap opens Messages alone", 1, picked.size());

        Settings.CHAT_GROUPS.save(false);
        HiddenChatsTap.decide(activity, MotionEvent.ACTION_DOWN, 100, 1500, 4000);
        HiddenChatsTap.decide(activity, MotionEvent.ACTION_UP, 100, 1500, 4000 + held + 10);
        assertEquals("switched off, a long press is Instagram's", 1, picked.size());
    }

    @Test
    public void theGroupsListShowsTheGroupPicked() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ChatGroups.pick(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Long press a chat to group it", String.valueOf(ShadowToast.getTextOfLatestToast()));

        ChatGroups.move(ME, ALICE, "Alice", "Work");
        ChatGroups.pick(activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Chat groups", shadowOf(dialog).getTitle().toString());
        assertEquals("All chats", dialog.getListView().getItemAtPosition(0).toString());

        shadowOf(dialog).clickOnItem(1);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Work", ChatGroups.showing(ME));
        assertEquals(Collections.singletonList(ALICE), ids(HiddenChats.inbox(null, list(ALICE, BOB))));
    }

    private List<Object> rows(int redraw) {
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) redrawn.get(redraw)[1];
        return rows;
    }

    private static ArrayList<Object> list(String... ids) {
        ArrayList<Object> list = new ArrayList<>();
        for (String id : ids) list.add(new Summary(id));
        return list;
    }

    private static List<String> ids(List<Object> summaries) {
        List<String> ids = new ArrayList<>();
        for (Object summary : summaries) ids.add(((Summary) summary).id);
        return ids;
    }
}
