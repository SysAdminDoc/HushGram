/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Pinned chats lead the inbox, in pin order, on the phone only (#345). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PinnedChatsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ALICE = "340282366841710300949128";
    private static final String BOB = "340282366841710300949999";
    private static final String CY = "340282366841710300948888";

    private static final class Summary {
        final String id;

        Summary(String id) {
            this.id = id;
        }
    }

    private final List<Object[]> redrawn = new ArrayList<>();

    @Before
    public void enable() {
        MessagesLock.resetForTests();
        ChatAccounts.signInForTests(MessagesLockTest.ME);
        HiddenChats.reader = summary -> ((Summary) summary).id;
        HiddenChats.redrawer = (holder, summaries) -> redrawn.add(new Object[]{holder, summaries});
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        Settings.HIDDEN_CHATS.resetToDefault();
        Settings.PINNED_CHATS.resetToDefault();
        HiddenChats.resetForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void pinnedChatsComeFirstInPinOrderAndTheRestKeepInstagramsOrder() {
        assertTrue(PinnedChats.add(ChatAccounts.active(), CY, "Cy"));
        assertTrue(PinnedChats.add(ChatAccounts.active(), BOB, "Bob"));
        List<Object> inbox = list("1", ALICE, BOB, "2", CY);

        List<Object> shown = HiddenChats.inbox(null, inbox);

        assertEquals(Arrays.asList(CY, BOB, "1", ALICE, "2"), ids(shown));
        assertEquals("Instagram's own list is untouched", Arrays.asList("1", ALICE, BOB, "2", CY), ids(inbox));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("pinned chats moved to the top of the inbox"));
    }

    @Test
    public void nothingPinnedOrAlreadyOnTopAnswersTheSameList() {
        List<Object> inbox = list(ALICE, BOB);
        assertSame("nothing pinned", inbox, HiddenChats.inbox(null, inbox));
        PinnedChats.add(ChatAccounts.active(), ALICE, "Alice");
        assertSame("already first", inbox, HiddenChats.inbox(null, inbox));
        PinnedChats.add(ChatAccounts.active(), CY, "Cy");
        assertSame("a pin that isn't in this list", inbox, HiddenChats.inbox(null, inbox));
    }

    @Test
    public void aHiddenChatStaysHiddenEvenWhenPinned() {
        PinnedChats.add(ChatAccounts.active(), BOB, "Bob");
        HiddenChats.add(BOB, "Bob");

        assertEquals(Arrays.asList(ALICE), ids(HiddenChats.inbox(null, list(ALICE, BOB))));
    }

    @Test
    public void pinningAndUnpinningDrawsTheInboxAgain() {
        Object holder = new Object();
        List<Object> instagrams = list(ALICE, BOB, CY);
        HiddenChats.inbox(holder, instagrams);

        PinnedChats.add(ChatAccounts.active(), CY, "Cy");
        assertEquals(1, redrawn.size());
        assertSame(holder, redrawn.get(0)[0]);
        assertEquals(Arrays.asList(CY, ALICE, BOB), ids(rows(0)));

        PinnedChats.remove(ChatAccounts.active(), CY);
        assertEquals(2, redrawn.size());
        assertEquals(Arrays.asList(ALICE, BOB, CY), ids(rows(1)));
    }

    @Test
    public void pinsBelongToTheAccountTheyWereMadeOn() {
        PinnedChats.add(ChatAccounts.active(), CY, "Cy");
        ChatAccounts.signInForTests("5678");

        assertEquals(Arrays.asList(ALICE, CY), ids(HiddenChats.inbox(null, list(ALICE, CY))));
        ChatAccounts.signInForTests(MessagesLockTest.ME);
        assertEquals(Arrays.asList(CY, ALICE), ids(HiddenChats.inbox(null, list(ALICE, CY))));
    }

    @Test
    public void pausedOrNotReadyLeavesInstagramsOrder() {
        PinnedChats.add(ChatAccounts.active(), CY, "Cy");
        List<Object> inbox = list(ALICE, CY);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);

        assertSame("paused", inbox, HiddenChats.inbox(null, inbox));
        assertEquals("the list still shows what was chosen", 1, PinnedChats.saved(ChatAccounts.active()).size());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("settings not ready", inbox, HiddenChats.inbox(null, inbox)));
        assertFalse(HiddenChats.inbox(null, inbox) == inbox);
    }

    @Test
    public void aSummaryThatCantBeReadOrAThrowingReaderChangesNothing() {
        PinnedChats.add(ChatAccounts.active(), CY, "Cy");
        HiddenChats.reader = summary -> {
            throw new IllegalStateException("not a summary");
        };
        List<Object> inbox = list(ALICE, CY);

        assertSame(inbox, HiddenChats.inbox(null, inbox));
    }

    @Test
    public void theChatOpenedLastIsOfferedUntilItIsPinned() {
        Activity screen = Robolectric.buildActivity(Activity.class).setup().get();
        ChatLocks.reader = fragment -> CY;
        ChatLocks.opened(screen);
        ChatLocks.closed(screen);
        assertEquals(CY, PinnedChats.lastOpened().id);
        PinnedChats.add(ChatAccounts.active(), CY, "Cy");
        assertNull(PinnedChats.lastOpened());
    }

    private List<Object> rows(int draw) {
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) redrawn.get(draw)[1];
        return rows;
    }

    private static List<Object> list(String... ids) {
        List<Object> list = new ArrayList<>();
        for (String id : ids) list.add(new Summary(id));
        return list;
    }

    private static List<String> ids(List<Object> summaries) {
        List<String> ids = new ArrayList<>();
        for (Object summary : summaries) ids.add(((Summary) summary).id);
        return ids;
    }
}
