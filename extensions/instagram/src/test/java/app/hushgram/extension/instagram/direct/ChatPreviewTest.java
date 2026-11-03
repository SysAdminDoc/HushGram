/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Preview in a chat's long press menu: the row it borrows, its label, the tap, and the box with the
 * chat's newest messages and a reply field. What a phone's chat store holds, and the reply reaching
 * the other person, needs a check on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ChatPreviewTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** Instagram's chat menu actions, with the debug row the preview borrows. */
    enum Menu { MARK_AS_UNREAD, THREAD_LEVEL_DEBUG, DELETE }

    /** A build whose menu has no debug row. */
    enum Bare { MARK_AS_UNREAD, DELETE }

    private static final Object SESSION = new Object();
    private static final Object KEY = new Object();

    private FakeAccess fake;
    private Activity shown;

    @Before
    public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ShadowToast.reset();
        fake = new FakeAccess();
        ChatPreview.access = fake;
        shown = Utils.getActivity();
    }

    @After
    public void restore() {
        ChatPreview.access = ChatPreview.PATCHED;
        Utils.setActivity(shown);
        Settings.PREVIEW_CHATS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void previewGoesAtTheTopOfTheMenuOnce() {
        List<Object> rows = new ArrayList<>(Arrays.asList(Menu.MARK_AS_UNREAD, Menu.DELETE));
        ChatPreview.offer(rows, Menu.class, ON);
        assertEquals(Arrays.asList(Menu.THREAD_LEVEL_DEBUG, Menu.MARK_AS_UNREAD, Menu.DELETE), rows);
        ChatPreview.offer(rows, Menu.class, ON);
        assertEquals(3, rows.size());
    }

    @Test
    public void theMenuStaysAsItWasOffOrOnABuildWithoutTheRow() {
        List<Object> rows = new ArrayList<>(Arrays.asList(Menu.MARK_AS_UNREAD, Menu.DELETE));
        ChatPreview.offer(rows, Menu.class, OFF);
        ChatPreview.offer(rows, "not a class", ON);
        ChatPreview.offer(rows, Bare.class, ON);
        ChatPreview.offer(rows, Object.class, ON);
        ChatPreview.offer(null, Menu.class, ON);
        assertEquals(Arrays.asList(Menu.MARK_AS_UNREAD, Menu.DELETE), rows);
        assertTrue(HookStatus.missing(FamilyNames.PREVIEW_CHATS).isEmpty());

        ChatPreview.offer(rows, Menu.class, THROWS);
        assertEquals(2, rows.size());
        String missing = HookStatus.missing(FamilyNames.PREVIEW_CHATS).toString();
        assertTrue(missing, missing.contains("'" + ChatPreview.OFFER + "'"));
    }

    @Test
    public void theSwitchStartsOffAndTurnsTheRowOn() {
        assertFalse(Settings.PREVIEW_CHATS.defaultValue);
        List<Object> rows = new ArrayList<>(Collections.singletonList(Menu.DELETE));
        ChatPreview.offer(rows, Menu.class);
        assertEquals(1, rows.size());
        Settings.PREVIEW_CHATS.save(true);
        ChatPreview.offer(rows, Menu.class);
        assertEquals(Arrays.asList(Menu.THREAD_LEVEL_DEBUG, Menu.DELETE), rows);
    }

    @Test
    public void theBorrowedRowReadsPreview() {
        List<Object> rows = Arrays.asList(Menu.DELETE, Menu.THREAD_LEVEL_DEBUG);
        String[] labels = {"Delete", "[DEBUG] Thread info"};
        ChatPreview.label(rows, labels, OFF);
        assertArrayEquals(new String[] {"Delete", "[DEBUG] Thread info"}, labels);
        ChatPreview.label(rows, labels, ON);
        assertArrayEquals(new String[] {"Delete", "Preview"}, labels);

        String[] fewer = {"Delete"};
        ChatPreview.label(rows, fewer, ON);
        assertArrayEquals(new String[] {"Delete"}, fewer);
        ChatPreview.label(rows, null, ON);
        ChatPreview.label(null, labels, ON);
        assertTrue(HookStatus.missing(FamilyNames.PREVIEW_CHATS).isEmpty());
    }

    @Test
    public void otherRowsAndTheSwitchOffLeaveTheTapToInstagram() {
        assertFalse(ChatPreview.open(Menu.DELETE, SESSION, KEY, ON));
        assertFalse(ChatPreview.open(null, SESSION, KEY, ON));
        assertFalse(ChatPreview.open(Bare.DELETE, SESSION, KEY, ON));
        assertFalse(ChatPreview.open(Menu.THREAD_LEVEL_DEBUG, SESSION, KEY, OFF));
        assertFalse(ChatPreview.open(Menu.THREAD_LEVEL_DEBUG, SESSION, KEY, THROWS));
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    /** With no screen to show it on, the tap is still Preview's, and says the chat didn't load. */
    @Test
    public void withNoScreenThePreviewSaysSo() {
        Utils.setActivity(null);
        assertTrue(ChatPreview.open(Menu.THREAD_LEVEL_DEBUG, SESSION, KEY, ON));
        assertEquals("Couldn't load this chat", toast());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void theTapOpensTheBoxOverTheScreen() throws Exception {
        fake.messages.add(new Message("hey", 1_000, false));
        Utils.setActivity(Robolectric.buildActivity(Activity.class).setup().get());
        assertTrue(ChatPreview.open(Menu.THREAD_LEVEL_DEBUG, SESSION, KEY, ON));
        AlertDialog box = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(box.isShowing());
        settle();
        assertTrue(texts(box).contains("hey"));
        // Unpatched, Mark as read isn't offered.
        assertEquals(View.GONE, box.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
        assertEquals(Collections.singletonList(SESSION + "/" + KEY), fake.read);
    }

    @Test
    public void theNewestMessagesShowOldestFirst() {
        for (int at = 40; at >= 1; at--) fake.messages.add(new Message("m" + at, at * 1_000_000_000_000L, at % 2 == 0));
        fake.messages.add(null);
        fake.messages.add("not a message");
        Message log = new Message("liked a message", 99_000_000_000_000_000L, false);
        log.kind = "ACTION_LOG";
        fake.messages.add(log);

        List<ChatPreview.Line> lines = ChatPreview.read(SESSION, KEY, fake);
        assertEquals(ChatPreview.SHOWN, lines.size());
        assertEquals("m11", lines.get(0).text);
        assertEquals("m40", lines.get(lines.size() - 1).text);
        assertTrue(lines.get(lines.size() - 1).mine);
        assertFalse(lines.get(lines.size() - 2).mine);
        assertSame(fake.messages.get(0), ChatPreview.newest(lines));

        fake.messages = null;
        assertNull(ChatPreview.read(SESSION, KEY, fake));
    }

    /** Instagram doesn't mark a message just sent from this phone as the account's, so its sender says so. */
    @Test
    public void aReplyJustSentShowsAsYours() {
        fake.messages.add(new Message("from the server", 1_000_000L, true));
        fake.messages.add(new Message("their answer", 2_000_000L, false));
        Message sent = new Message("just sent", 3_000_000L, false);
        sent.sender = "me";
        fake.messages.add(sent);

        fake.account = "me";
        assertMine(true, false, true);
        fake.account = null;
        assertMine(true, false, true);
        fake.accountThrows = true;
        assertMine(true, false, true);

        // With no account id and nothing marked, there's nothing to tell a fresh reply by.
        fake.messages.remove(0);
        assertMine(false, false);
    }

    private void assertMine(boolean... expected) {
        List<ChatPreview.Line> lines = ChatPreview.read(SESSION, KEY, fake);
        assertEquals(expected.length, lines.size());
        for (int at = 0; at < expected.length; at++) assertEquals(lines.get(at).text, expected[at], lines.get(at).mine);
    }

    @Test
    public void aMessageWithNoTextShowsWhatItHolds() {
        assertEquals("hi", ChatPreview.describe("hi", "MEDIA"));
        assertEquals("View once message", ChatPreview.describe(null, "RAVEN_MEDIA"));
        assertEquals("View once message", ChatPreview.describe(" ", "VISUAL_MEDIA"));
        assertEquals("Voice message", ChatPreview.describe(null, "VOICE_MEDIA"));
        assertEquals("Reel", ChatPreview.describe(null, "CLIP"));
        assertEquals("Story", ChatPreview.describe(null, "STORY_SHARE"));
        assertEquals("GIF or sticker", ChatPreview.describe(null, "ANIMATED_MEDIA"));
        assertEquals(new String(Character.toChars(0x2764)), ChatPreview.describe(null, "LIKE"));
        assertEquals("Post", ChatPreview.describe(null, "XMA_MEDIA_SHARE"));
        assertEquals("Photo or video", ChatPreview.describe(null, "media"));
        assertEquals("Attachment", ChatPreview.describe(null, "PLACEHOLDER"));
        assertEquals("Attachment", ChatPreview.describe(null, null));
    }

    @Test
    public void messageTimesReadAsMicroseconds() {
        assertEquals(1_700_000_000_000_000L, ChatPreview.micros("1700000000000000"));
        assertEquals(1_700_000_000_000_000L, ChatPreview.micros(" 1700000000000 "));
        assertEquals(0, ChatPreview.micros(null));
        assertEquals(0, ChatPreview.micros("soon"));
        assertNull(ChatPreview.newest(null));
        assertNull(ChatPreview.newest(Collections.emptyList()));
    }

    @Test
    public void anEmptyOrUnreadableChatSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        TextView status = new TextView(activity);
        ChatPreview.fill(list, status, null);
        assertEquals("Couldn't load this chat", status.getText().toString());
        assertSame(status, list.getChildAt(0));
        ChatPreview.fill(list, status, Collections.emptyList());
        assertEquals("Nothing here yet", status.getText().toString());
        assertEquals(1, list.getChildCount());

        ChatPreview.fill(list, status, Arrays.asList(
                new ChatPreview.Line("yours", 0, true, null),
                new ChatPreview.Line("theirs", 1_700_000_000_000_000L, false, null)));
        assertEquals(2, list.getChildCount());
        LinearLayout mine = (LinearLayout) list.getChildAt(0);
        LinearLayout theirs = (LinearLayout) list.getChildAt(1);
        assertEquals(Gravity.END, mine.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
        assertEquals(Gravity.START, theirs.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
        assertEquals("yours", ((TextView) mine.getChildAt(0)).getText().toString());
        assertEquals("a message with no time shows none", 1, mine.getChildCount());
        assertEquals(2, theirs.getChildCount());
    }

    @Test
    public void theBoxMakesRoomForTheKeyboard() throws Exception {
        AlertDialog box = box(false);
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                box.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
    }

    @Test
    public void aReplySendsAndShowsInTheBox() throws Exception {
        fake.messages.add(new Message("hey", 1_000, false));
        AlertDialog box = box(false);
        assertEquals(Collections.singletonList("hey"), bubbles(box));

        EditText field = find(box.getWindow().getDecorView(), EditText.class);
        field.setText("   ");
        sendButton(box).performClick();
        assertTrue("a blank reply isn't sent", fake.sent.isEmpty());

        field.setText(" on my way ");
        sendButton(box).performClick();
        assertEquals(Collections.singletonList("on my way"), fake.sent);
        assertEquals("", field.getText().toString());
        assertEquals("Sent", toast());

        fake.messages.add(new Message("on my way", 2_000, true));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ChatPreview.RELOAD_MS));
        settle();
        assertEquals(Arrays.asList("hey", "on my way"), bubbles(box));
    }

    /** Tapping the reply field before the chat finishes loading: the load mustn't take the typing away. */
    @Test
    public void theChatLoadingLeavesTheReplyFieldFocused() throws Exception {
        for (int at = 0; at < 12; at++) fake.messages.add(new Message("line " + at, 1_000 + at, at % 2 == 0));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AlertDialog box = ChatPreview.show(activity, SESSION, KEY, false);
        EditText field = find(box.getWindow().getDecorView(), EditText.class);
        assertTrue(field.requestFocus());
        settle();
        assertTrue("the read landing keeps the field focused", field.isFocused());

        field.setText("still here");
        sendButton(box).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ChatPreview.RELOAD_MS));
        settle();
        assertTrue("the reload after Send keeps it too", field.isFocused());
    }

    /** The keyboard shortens the box: the chat gives up the room and the reply field stays on screen. */
    @Test
    public void theKeyboardShrinksTheChatNotTheReplyField() throws Exception {
        for (int at = 0; at < 30; at++) {
            fake.messages.add(new Message("message number " + at, 1_000 + at, at % 2 == 0));
        }
        AlertDialog box = box(false);
        EditText field = find(box.getWindow().getDecorView(), EditText.class);
        View composer = (View) field.getParent();
        LinearLayout root = (LinearLayout) composer.getParent();
        ChatPreview.Capped scroll = find(root, ChatPreview.Capped.class);
        View list = scroll.getChildAt(0);

        int width = root.getResources().getDisplayMetrics().widthPixels;
        int tall = root.getResources().getDisplayMetrics().heightPixels;
        lay(root, width, tall);
        int keyboardUp = tall * 2 / 5;
        lay(root, width, keyboardUp);

        assertTrue("the reply field keeps its height", field.getHeight() > 0);
        assertTrue("the composer fits in the box", composer.getHeight() > 0 && composer.getBottom() <= keyboardUp);
        assertTrue("a long chat scrolls", list.getHeight() > scroll.getHeight());
        assertEquals("the newest message stays in view", list.getHeight() - scroll.getHeight(), scroll.getScrollY());
    }

    @Test
    public void aReplyThatFailsSaysSoAndKeepsTheText() throws Exception {
        AlertDialog box = box(false);
        fake.sendThrows = true;
        EditText field = find(box.getWindow().getDecorView(), EditText.class);
        field.setText("hello");
        sendButton(box).performClick();
        assertEquals("Couldn't send", toast());
        assertEquals("hello", field.getText().toString());
        String missing = HookStatus.missing(FamilyNames.PREVIEW_CHATS).toString();
        assertTrue(missing, missing.contains("'" + ChatPreview.SEND + "'"));
    }

    @Test
    public void aChatTheStoreCantReadSaysSo() throws Exception {
        fake.messages = null;
        AlertDialog box = box(false);
        assertTrue(texts(box).contains("Couldn't load this chat"));
        fake.messages = new ArrayList<>();
        fake.readThrows = true;
        box = box(false);
        assertTrue(texts(box).contains("Couldn't load this chat"));
        String missing = HookStatus.missing(FamilyNames.PREVIEW_CHATS).toString();
        assertTrue(missing, missing.contains("'" + ChatPreview.LOAD + "'"));
    }

    /** Mark as read shows when the seen receipt is held, and the box stays up when nothing was sent. */
    @Test
    public void markAsReadShowsWhenItCanAndStaysUpWhenItCouldnt() throws Exception {
        fake.messages.add(new Message("hey", 1_000, false));
        AlertDialog box = box(true);
        Button mark = box.getButton(AlertDialog.BUTTON_POSITIVE);
        assertEquals(View.VISIBLE, mark.getVisibility());
        assertEquals("Mark as read", mark.getText().toString());
        mark.performClick();
        assertTrue("unpatched, nothing was marked", box.isShowing());
    }

    @Test
    public void unpatchedMarkReadSendsNothing() {
        List<ChatPreview.Line> lines = Collections.singletonList(
                new ChatPreview.Line("hey", 1_000, false, new Message("hey", 1_000, false)));
        assertFalse(ChatPreview.markRead(SESSION, KEY, lines, fake));
        assertFalse(ChatPreview.markRead(SESSION, KEY, null, fake));
        fake.idThrows = true;
        assertFalse(ChatPreview.markRead(SESSION, KEY, lines, fake));
        String missing = HookStatus.missing(FamilyNames.PREVIEW_CHATS).toString();
        assertTrue(missing, missing.contains("'" + ChatPreview.MARK + "'"));
    }

    /** Unpatched, the bridges read nothing and send nothing, and the box says the chat didn't load. */
    @Test
    public void unpatchedBridgesReadNothing() {
        assertNull(ChatPreview.read(SESSION, KEY, ChatPreview.PATCHED));
        ChatPreview.PATCHED.send(SESSION, KEY, "hi");
        assertFalse(ChatPreview.PATCHED.isMessage(new Object()));
    }

    private AlertDialog box(boolean canMark) throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AlertDialog box = ChatPreview.show(activity, SESSION, KEY, canMark);
        settle();
        return box;
    }

    private static void lay(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    /** Lets the box's read finish and its answer land on the main thread. */
    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static String toast() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    private static Button sendButton(AlertDialog box) {
        for (View view : all(box.getWindow().getDecorView())) {
            if (view instanceof Button && "Send".contentEquals(((Button) view).getText())) return (Button) view;
        }
        throw new AssertionError("the box has no Send button");
    }

    /** The bubbles in the box, in the order shown. */
    private static List<String> bubbles(AlertDialog box) {
        List<String> found = new ArrayList<>();
        for (View view : all(box.getWindow().getDecorView())) {
            if (view instanceof TextView && ((TextView) view).isTextSelectable()) {
                found.add(((TextView) view).getText().toString());
            }
        }
        return found;
    }

    private static List<String> texts(AlertDialog box) {
        List<String> found = new ArrayList<>();
        for (View view : all(box.getWindow().getDecorView())) {
            if (view instanceof TextView) found.add(((TextView) view).getText().toString());
        }
        return found;
    }

    private static <T extends View> T find(View root, Class<T> type) {
        for (View view : all(root)) {
            if (type.isInstance(view)) return type.cast(view);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in the box");
    }

    private static List<View> all(View root) {
        List<View> found = new ArrayList<>();
        found.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int at = 0; at < group.getChildCount(); at++) found.addAll(all(group.getChildAt(at)));
        }
        return found;
    }

    static final class Message {
        final String text;
        final long micros;
        final boolean mine;
        String kind = "TEXT";
        String sender;

        Message(String text, long micros, boolean mine) {
            this.text = text;
            this.micros = micros;
            this.mine = mine;
            this.sender = mine ? "me" : "them";
        }
    }

    /** A chat store and sender standing in for Instagram's. */
    static final class FakeAccess implements ChatPreview.Access {
        List<Object> messages = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        final List<String> read = new ArrayList<>();
        boolean sendThrows;
        boolean readThrows;
        boolean idThrows;
        String account;
        boolean accountThrows;

        @Override
        public List<Object> messages(Object session, Object key) {
            read.add(session + "/" + key);
            if (readThrows) throw new ClassCastException("not a chat key");
            return messages;
        }

        @Override
        public void send(Object session, Object key, String text) {
            if (sendThrows) throw new IllegalStateException("the composer went away");
            sent.add(text);
        }

        @Override
        public boolean isMessage(Object message) {
            return message instanceof Message;
        }

        @Override
        public String text(Object message) {
            return ((Message) message).text;
        }

        @Override
        public String time(Object message) {
            return Long.toString(((Message) message).micros);
        }

        @Override
        public String kind(Object message) {
            return ((Message) message).kind;
        }

        @Override
        public boolean mine(Object message) {
            return ((Message) message).mine;
        }

        @Override
        public String id(Object message) {
            if (idThrows) throw new ClassCastException("not a message");
            return "m-" + ((Message) message).text;
        }

        @Override
        public String sender(Object message) {
            return ((Message) message).sender;
        }

        @Override
        public String account(Object session) {
            if (accountThrows) throw new ClassCastException("not a session");
            return account;
        }
    }
}
