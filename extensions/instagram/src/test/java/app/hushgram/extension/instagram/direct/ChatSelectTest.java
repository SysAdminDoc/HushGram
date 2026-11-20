/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** Select messages (#778): the picker over a chat's loaded messages, and what Copy and Share hand over. */
@RunWith(RobolectricTestRunner.class)
public class ChatSelectTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final long T1 = 1_730_000_000_000L;
    private static final long T2 = T1 + 60_000;
    private static final long T3 = T2 + 60_000;

    /** A chat with Sam: one message of yours, then two of Sam's, oldest first. */
    private static List<ChatHistory.Entry> chat() {
        return Arrays.asList(
                new ChatHistory.Entry(0, "1", "me", "first", "TEXT", T1, true),
                new ChatHistory.Entry(1, "2", "sam", "second\nwith a second line", "TEXT", T2, false),
                new ChatHistory.Entry(2, "3", "sam", "third", "TEXT", T3, false));
    }

    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

    @After
    public void restore() {
        HookStatus.clear();
    }

    private AlertDialog open() {
        ChatSelect.show(activity, "Sam", chat());
        AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no picker opened", dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static void tick(AlertDialog dialog, int row) {
        ListView list = dialog.getListView();
        list.performItemClick(null, row, list.getItemIdAtPosition(row));
    }

    private static TextView selectAll(AlertDialog dialog) {
        TextView all = dialog.getWindow().getDecorView().findViewWithTag(ChatSelect.ALL_TAG);
        assertNotNull("the title has no Select all", all);
        return all;
    }

    @Test public void theListShowsEveryLoadedMessageOnOneLineEach() {
        AlertDialog dialog = open();
        ListView list = dialog.getListView();
        assertEquals(3, list.getCount());
        assertEquals(ChatHistory.stamp(T1) + " You: first", String.valueOf(list.getItemAtPosition(0)));
        assertEquals(ChatHistory.stamp(T2) + " Sam: second with a second line", String.valueOf(list.getItemAtPosition(1)));
        assertEquals("Select all", String.valueOf(selectAll(dialog).getText()));
    }

    /** With nothing ticked, Copy says so and the picker stays, so the ticks aren't lost to a stray tap. */
    @Test public void nothingTickedKeepsThePickerOpen() {
        AlertDialog dialog = open();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("Pick a message first.", ShadowToast.getTextOfLatestToast());
        assertTrue(dialog.isShowing());
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertFalse(clipboard.hasPrimaryClip());
    }

    @Test public void copyPutsTheTickedMessagesOnTheClipboardInChatOrder() {
        AlertDialog dialog = open();
        tick(dialog, 2);
        tick(dialog, 0);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        assertNotNull(clip);
        assertEquals(ChatHistory.stamp(T1) + " You: first\n" + ChatHistory.stamp(T3) + " Sam: third",
                String.valueOf(clip.getItemAt(0).getText()));
        assertEquals("Copied 2 messages.", ShadowToast.getTextOfLatestToast());
        assertFalse(dialog.isShowing());
    }

    /** A message unticked again stays out. */
    @Test public void aTickTakenBackStaysOut() {
        AlertDialog dialog = open();
        tick(dialog, 1);
        tick(dialog, 0);
        tick(dialog, 1);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(ChatHistory.stamp(T1) + " You: first", String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
        assertEquals("Copied 1 message.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void selectAllTicksEveryMessageAndClearUnticksThem() {
        AlertDialog dialog = open();
        TextView all = selectAll(dialog);
        ListView list = dialog.getListView();

        assertTrue(all.performClick());
        for (int i = 0; i < 3; i++) assertTrue("row " + i + " wasn't ticked", list.isItemChecked(i));
        assertEquals("Clear", String.valueOf(all.getText()));

        assertTrue(all.performClick());
        for (int i = 0; i < 3; i++) assertFalse("row " + i + " stayed ticked", list.isItemChecked(i));
        assertEquals("Select all", String.valueOf(all.getText()));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("Pick a message first.", ShadowToast.getTextOfLatestToast());
    }

    /** Ticking every row by hand reads as all ticked too. */
    @Test public void tickingEveryRowByHandOffersClear() {
        AlertDialog dialog = open();
        for (int i = 0; i < 3; i++) tick(dialog, i);
        assertEquals("Clear", String.valueOf(selectAll(dialog).getText()));
    }

    @Test public void shareHandsTheTickedMessagesToAndroidsShareSheet() {
        AlertDialog dialog = open();
        tick(dialog, 1);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();

        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("nothing was shared", started);
        assertEquals(Intent.ACTION_CHOOSER, started.getAction());
        Intent send = started.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(send);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(ChatHistory.stamp(T2) + " Sam: second\n  with a second line", send.getStringExtra(Intent.EXTRA_TEXT));
        assertFalse(dialog.isShowing());
    }

    /**
     * In a group, a message picked from one member is signed with their id, as the whole export signs
     * it: the names come from the whole chat, not from the few messages ticked.
     */
    @Test public void aGroupMessageKeepsItsSendersNameWhenPickedAlone() {
        List<ChatHistory.Entry> group = Arrays.asList(
                new ChatHistory.Entry(0, "1", "ana", "hi", "TEXT", T1, false),
                new ChatHistory.Entry(1, "2", "ben", "hey", "TEXT", T2, false),
                new ChatHistory.Entry(2, "3", "me", "yo", "TEXT", T3, true));
        List<String> names = ChatHistory.names(group, "Trip", "You");
        boolean[] ticked = {false, true, false};

        String saved = ChatHistory.document(ChatSelect.picked(group, ticked), ChatSelect.picked(names, ticked), "Trip");

        assertEquals("Chat with Trip\n1 message\n\n" + ChatHistory.stamp(T2) + " ben: hey\n", saved);
    }

    @Test public void theHelpersKeepOrderAndCutLongRows() {
        boolean[] ticked = {true, false, true};
        assertEquals(Arrays.asList("a", "c"), ChatSelect.picked(Arrays.asList("a", "b", "c"), ticked));
        assertEquals(2, ChatSelect.count(ticked));
        assertEquals("a\nc", ChatSelect.text(Arrays.asList("a", "c")));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 40; i++) longText.append("word ");
        String row = ChatSelect.row(longText.toString());
        assertTrue(row, row.length() <= ChatSelect.ROW_CHARS + 3);
        assertTrue(row, row.endsWith("..."));
        assertEquals("short", ChatSelect.row("short"));
    }

    /** The export of the whole chat is unchanged by the names being worked out apart from it. */
    @Test public void theWholeExportStillSignsEachSide() {
        String whole = ChatHistory.document(chat(), "Sam", "You");
        assertTrue(whole, whole.startsWith("Chat with Sam\n3 messages\n\n"));
        assertTrue(whole, whole.contains(ChatHistory.stamp(T1) + " You: first\n"));
        assertTrue(whole, whole.contains(ChatHistory.stamp(T3) + " Sam: third\n"));
    }
}
