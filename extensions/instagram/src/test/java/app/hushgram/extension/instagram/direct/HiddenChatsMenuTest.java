/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Hide chat and Unhide chat in a chat's long press menu (#661): when the row shows, what it reads, what its tap does. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HiddenChatsMenuTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ALICE = "340282366841710300949128";
    private static final String BOB = "340282366841710300949999";
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    /** Instagram's menu actions, cut down: a Details row it builds and never offers among rows it does. */
    enum Menu { MUTE, DETAILS, DELETE }

    /** A build whose menu has no Details row. */
    enum Bare { MUTE, DELETE }

    /** Instagram's key for a chat: the id is all the extension reads from it. */
    private static final class Key {
        final String id;

        Key(String id) {
            this.id = id;
        }
    }

    @Before
    public void prepare() {
        MessagesLock.resetForTests();
        ChatAccounts.signInForTests(MessagesLockTest.ME);
        HiddenChats.redrawer = (holder, summaries) -> { };
        HiddenChatsMenu.resetForTests();
        HiddenChatsMenu.reader = key -> ((Key) key).id;
        HookStatus.clear();
    }

    @After
    public void restore() {
        HiddenChatsMenu.resetForTests();
        MessagesLock.resetForTests();
        Settings.HIDDEN_CHATS.resetToDefault();
        Settings.DOUBLE_TAP_HIDDEN_CHATS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theMenuBuilderGetsTheRowOnceAfterItsOwn() {
        List<Object> rows = new ArrayList<>(Arrays.asList(Menu.MUTE, Menu.DELETE));

        HiddenChatsMenu.offer(rows, Menu.class, ON);
        HiddenChatsMenu.offer(rows, Menu.class, ON);

        assertEquals(Arrays.asList(Menu.MUTE, Menu.DELETE, Menu.DETAILS), rows);
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void noRowSwitchedOffOrInABuildWithoutIt() {
        List<Object> rows = new ArrayList<>(Arrays.asList(Menu.MUTE, Menu.DELETE));

        HiddenChatsMenu.offer(rows, Menu.class, OFF);
        HiddenChatsMenu.offer(rows, Menu.MUTE, ON);
        HiddenChatsMenu.offer(null, Menu.class, ON);
        assertEquals(Arrays.asList(Menu.MUTE, Menu.DELETE), rows);

        List<Object> bare = new ArrayList<>(Arrays.asList(Bare.MUTE, Bare.DELETE));
        HiddenChatsMenu.offer(bare, Bare.class, ON);
        assertEquals(Arrays.asList(Bare.MUTE, Bare.DELETE), bare);
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void theRealEntryFollowsTheDoubleTapSwitch() {
        List<Object> rows = new ArrayList<>(Arrays.asList(Menu.MUTE));
        Settings.DOUBLE_TAP_HIDDEN_CHATS.save(false);
        HiddenChatsMenu.offer(rows, Menu.class);
        assertEquals("off, so Instagram's menu as it was", Arrays.asList(Menu.MUTE), rows);
        assertFalse(HiddenChatsMenu.tap(Menu.DETAILS, new Key(ALICE)));

        Settings.DOUBLE_TAP_HIDDEN_CHATS.save(true);
        HiddenChatsMenu.offer(rows, Menu.class);
        HiddenChatsMenu.shown(new Key(ALICE), "Alice");
        assertEquals(Arrays.asList(Menu.MUTE, Menu.DETAILS), rows);
        String[] labels = {"Mute", "[DEBUG] Thread info"};
        HiddenChatsMenu.label(rows, labels);
        assertEquals("Hide chat", labels[1]);
        assertTrue(HiddenChatsMenu.tap(Menu.DETAILS, new Key(ALICE)));
        assertTrue(HiddenChats.savedListed(ChatAccounts.active(), ALICE));
    }

    @Test
    public void theRowReadsHideChatThenUnhideChatAndTheOthersKeepTheirs() {
        List<Object> rows = new ArrayList<>(Arrays.asList(Menu.MUTE, Menu.DELETE));
        HiddenChatsMenu.offer(rows, Menu.class, ON);
        HiddenChatsMenu.shown(new Key(ALICE), "Alice");
        String[] labels = {"Mute", "Delete", "[DEBUG] Thread info"};

        HiddenChatsMenu.label(rows, labels, ON);
        assertEquals(Arrays.asList("Mute", "Delete", "Hide chat"), Arrays.asList(labels));

        HiddenChats.add(ALICE, "Alice");
        HiddenChatsMenu.label(rows, labels, ON);
        assertEquals(Arrays.asList("Mute", "Delete", "Unhide chat"), Arrays.asList(labels));

        String[] off = {"Mute", "Delete", "[DEBUG] Thread info"};
        HiddenChatsMenu.label(rows, off, OFF);
        assertEquals("switched off, Instagram's own", "[DEBUG] Thread info", off[2]);
        HiddenChatsMenu.label(rows, new String[]{"Mute"}, ON);
        HiddenChatsMenu.label(null, labels, ON);
        assertTrue(HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString(), HookStatus.missing(FamilyNames.MESSAGES_LOCK).isEmpty());
    }

    @Test
    public void tappingHidesTheChatUnderTheNameOverItsMenu() {
        HiddenChatsMenu.shown(new Key(ALICE), " Alice ");

        assertTrue("taken from Instagram", HiddenChatsMenu.tap(Menu.DETAILS, new Key(ALICE), ON));

        List<ChatLocks.Chat> saved = HiddenChats.saved(ChatAccounts.active());
        assertEquals(1, saved.size());
        assertEquals(ALICE, saved.get(0).id);
        assertEquals("Alice", saved.get(0).name);
        String toast = toast();
        assertTrue(toast, toast.contains("Alice") && toast.endsWith(" is hidden"));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("hide chat tap 1"));
    }

    @Test
    public void anotherChatsMenuNameIsNeverBorrowed() {
        HiddenChatsMenu.shown(new Key(BOB), "Bob");

        assertTrue(HiddenChatsMenu.tap(Menu.DETAILS, new Key(ALICE), ON));

        List<ChatLocks.Chat> saved = HiddenChats.saved(ChatAccounts.active());
        assertEquals(ALICE, saved.get(0).id);
        assertEquals(ChatLocks.placeholder(ALICE), saved.get(0).name);
    }

    @Test
    public void unhidingTheLastHiddenChatHidesTheRestOfTheShowAgain() {
        HiddenChats.add(ALICE, "Alice");
        HiddenChats.add(BOB, "Bob");
        HiddenChats.reveal();

        HiddenChatsMenu.shown(new Key(ALICE), "Alice");
        assertTrue(HiddenChatsMenu.tap(Menu.DETAILS, new Key(ALICE), ON));
        assertFalse(HiddenChats.savedListed(ChatAccounts.active(), ALICE));
        String toast = toast();
        assertTrue(toast, toast.contains("Alice") && toast.endsWith(" is shown again"));
        assertTrue("Bob is still hidden, so the hidden chats still show", HiddenChats.revealed());

        HiddenChatsMenu.shown(new Key(BOB), "Bob");
        assertTrue(HiddenChatsMenu.tap(Menu.DETAILS, new Key(BOB), ON));
        assertTrue(HiddenChats.saved(ChatAccounts.active()).isEmpty());
        assertFalse("nothing left to show, so the inbox is back", HiddenChats.revealed());
    }

    @Test
    public void otherRowsAndASwitchedOffRowGoToInstagram() {
        assertFalse(HiddenChatsMenu.tap(Menu.MUTE, new Key(ALICE), ON));
        assertFalse(HiddenChatsMenu.tap("DETAILS", new Key(ALICE), ON));
        assertFalse(HiddenChatsMenu.tap(null, new Key(ALICE), ON));
        assertFalse(HiddenChatsMenu.tap(Menu.DETAILS, new Key(ALICE), OFF));
        assertTrue(HiddenChats.saved(ChatAccounts.active()).isEmpty());
    }

    @Test
    public void aKeyWithNoIdSaysSoAndOpensNothing() {
        assertTrue("Instagram's Details has nothing to open", HiddenChatsMenu.tap(Menu.DETAILS, new Key(null), ON));
        assertEquals("Couldn't save the chat.", toast());
        assertTrue(HiddenChats.saved(ChatAccounts.active()).isEmpty());
    }

    @Test
    public void aKeyThatCantBeReadIsReportedAndBorrowsNoEarlierName() {
        HiddenChatsMenu.shown(new Key(ALICE), "Alice");
        HiddenChatsMenu.reader = key -> {
            throw new IllegalStateException("moved");
        };

        HiddenChatsMenu.shown(new Key(BOB), "Bob");

        String missing = HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString();
        assertTrue(missing, missing.contains("'hide chat menu'"));
        assertTrue(HiddenChatsMenu.tap(Menu.DETAILS, new Key(ALICE), ON));
        assertEquals("Couldn't save the chat.", toast());
        assertTrue(HiddenChats.saved(ChatAccounts.active()).isEmpty());
    }

    /** Instagram hands the open menu a read-only copy of the rows, so the row can't go in there (#661). */
    @Test
    public void aReadOnlyListIsReportedNotThrown() {
        HiddenChatsMenu.offer(Collections.unmodifiableList(new ArrayList<>(Arrays.asList(Menu.MUTE))), Menu.class, ON);

        String missing = HookStatus.missing(FamilyNames.MESSAGES_LOCK).toString();
        assertTrue(missing, missing.contains("'hide chat row'"));
    }

    private static String toast() {
        shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }
}
