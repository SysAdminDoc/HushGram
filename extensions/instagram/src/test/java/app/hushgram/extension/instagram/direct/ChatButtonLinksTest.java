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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.widget.ListView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

import static org.robolectric.Shadows.shadowOf;

/** Copy link in a held chat message's menu, for the link buttons automated accounts send (#852). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ChatButtonLinksTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A button as the fakes model it: its words and where it goes. */
    private static final class Button {
        final String title;
        final String url;

        Button(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    /** A message as the fakes model it: buttons under its text and its cards' buttons. */
    private static final class Message {
        final List<Button> text;
        final List<List<Button>> cards;

        Message(List<Button> text, List<List<Button>> cards) {
            this.text = text;
            this.cards = cards;
        }
    }

    /** Instagram's card row as the fakes model it. */
    static final class Row {
        final String title;
        final Integer icon;
        final String payload;

        Row(String title, Integer icon, String payload) {
            this.title = title;
            this.icon = icon;
            this.payload = payload;
        }
    }

    private static class FakeParts implements ChatButtonLinks.Parts {
        int rowsMade;

        @Override
        public List<?> textButtons(Object message) {
            return ((Message) message).text;
        }

        @Override
        public List<?> cards(Object message) {
            return ((Message) message).cards;
        }

        @Override
        public List<?> cardButtons(Object card) {
            return (List<?>) card;
        }

        @Override
        public String title(Object button) {
            return ((Button) button).title;
        }

        @Override
        public String url(Object button) {
            return ((Button) button).url;
        }

        @Override
        public Object row(String title, Integer icon, String payload) {
            rowsMade++;
            return new Row(title, icon, payload);
        }

        @Override
        public Integer icon() {
            return 0x7f0827a4;
        }
    }

    private final FakeParts parts = new FakeParts();
    private final List<String> copied = new ArrayList<>();
    private final ChatButtonLinks.Copier copier = (context, url) -> copied.add(url);

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.COPY_CHAT_BUTTON_LINKS.save(true);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.COPY_CHAT_BUTTON_LINKS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static Message message(List<Button> text, List<List<Button>> cards) {
        return new Message(text, cards);
    }

    @Test
    public void aMessageWithLinkButtonsGetsOneCopyLinkRowAfterInstagramsRows() {
        List<String> stock = Collections.singletonList("Instagram's card row");
        Message held = message(
                Collections.singletonList(new Button("Website", "https://example.com/a")),
                Collections.singletonList(Arrays.asList(
                        new Button("Viral\tAmazon Product", "https://l.instagram.com/?u=https%3A%2F%2Fshop.example%2Fitem%3Fid%3D7&e=AT0x"),
                        new Button("Open the app", "instagram://user?username=someone"))));

        List<?> rows = ChatButtonLinks.rows(stock, held, parts, () -> true);

        assertEquals(2, rows.size());
        assertSame("Instagram's rows stay first", stock.get(0), rows.get(0));
        Row row = (Row) rows.get(1);
        assertEquals("Copy link", row.title);
        assertEquals(Integer.valueOf(0x7f0827a4), row.icon);
        List<ChatButtonLinks.Link> links = ChatButtonLinks.decode(row.payload);
        assertEquals(2, links.size());
        assertEquals("Website", links.get(0).title);
        assertEquals("https://example.com/a", links.get(0).url);
        assertEquals("a button's words keep to one line", "Viral Amazon Product", links.get(1).title);
        assertEquals("a link redirect gives where it really goes", "https://shop.example/item?id=7", links.get(1).url);
        assertTrue(String.join("\n", HookStatus.report()), String.join("\n", HookStatus.report()).contains(ChatButtonLinks.COUNT_ADDED));
    }

    @Test
    public void aMessageWithNoCardRowsStillGetsTheRow() {
        Message held = message(null, Collections.singletonList(Collections.singletonList(new Button("Shop now", "http://shop.example/"))));
        List<?> rows = ChatButtonLinks.rows(null, held, parts, () -> true);
        assertNotNull(rows);
        assertEquals(1, rows.size());
        assertEquals("http://shop.example/", ChatButtonLinks.decode(((Row) rows.get(0)).payload).get(0).url);
    }

    @Test
    public void offPausedOrWithoutLinksTheMenuIsInstagrams() {
        List<String> stock = Collections.singletonList("row");
        Message held = message(Collections.singletonList(new Button("Website", "https://example.com/")), null);
        assertSame(stock, ChatButtonLinks.rows(stock, held, parts, () -> false));

        Settings.COPY_CHAT_BUTTON_LINKS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, ChatButtonLinks.rows(stock, held, parts, ChatButtonLinks::switchedOn));
        PauseForTests.resume();
        Settings.COPY_CHAT_BUTTON_LINKS.save(false);
        assertSame(stock, ChatButtonLinks.rows(stock, held, parts, ChatButtonLinks::switchedOn));
        Settings.COPY_CHAT_BUTTON_LINKS.save(true);
        assertEquals(2, ChatButtonLinks.rows(stock, held, parts, ChatButtonLinks::switchedOn).size());

        Message inApp = message(Collections.singletonList(new Button("Reply", "instagram://direct")),
                Collections.singletonList(Arrays.asList(new Button("Nothing", null), null)));
        assertSame(stock, ChatButtonLinks.rows(stock, inApp, parts, () -> true));
        assertNull(ChatButtonLinks.rows(null, message(null, null), parts, () -> true));
        assertSame(stock, ChatButtonLinks.rows(stock, null, parts, () -> true));
        // A build without the patch keeps the stubs, which find no buttons.
        assertSame(stock, ChatButtonLinks.rows(stock, held));
    }

    @Test
    public void aButtonGoingWhereAnotherGoesIsListedOnce() {
        Message held = message(
                Arrays.asList(new Button("Website", "https://example.com/"), new Button("Again", " https://example.com/ ")),
                Collections.singletonList(Collections.singletonList(new Button("Same", "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2F"))));
        List<?> rows = ChatButtonLinks.rows(null, held, parts, () -> true);
        assertEquals(1, ChatButtonLinks.decode(((Row) rows.get(0)).payload).size());
    }

    @Test
    public void aThrowFindingButtonsLeavesTheMenuAloneAndIsReported() {
        List<String> stock = Collections.singletonList("row");
        FakeParts broken = new FakeParts() {
            @Override
            public List<?> cards(Object message) {
                throw new IllegalStateException("cards moved");
            }
        };
        assertSame(stock, ChatButtonLinks.rows(stock, message(null, null), broken, () -> true));
        assertTrue(String.join("\n", HookStatus.report()).contains(ChatButtonLinks.ROWS));
        FakeParts noRow = new FakeParts() {
            @Override
            public Object row(String title, Integer icon, String payload) {
                return null;
            }
        };
        Message held = message(Collections.singletonList(new Button("Website", "https://example.com/")), null);
        assertSame(stock, ChatButtonLinks.rows(stock, held, noRow, () -> true));
    }

    @Test
    public void tappingTheRowWithOneLinkCopiesItAtOnce() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        String payload = ChatButtonLinks.payload(Collections.singletonList(new ChatButtonLinks.Link("Website", "https://example.com/a")));
        assertTrue(ChatButtonLinks.picked(activity, payload, copier));
        assertEquals(Collections.singletonList("https://example.com/a"), copied);
        assertNull("no list for one link", ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void theRealCopyPutsTheLinkOnTheClipboardAndSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        String payload = ChatButtonLinks.payload(Collections.singletonList(new ChatButtonLinks.Link("Website", "https://example.com/a")));
        assertTrue(ChatButtonLinks.picked(activity, new Object(), payload));
        shadowOf(Looper.getMainLooper()).idle();
        Context context = RuntimeEnvironment.getApplication();
        ClipData clip = ((ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
        assertNotNull(clip);
        assertEquals("https://example.com/a", clip.getItemAt(0).getText().toString());
        assertEquals("Link copied", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void tappingTheRowWithSeveralLinksListsThemAndCopiesThePick() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        String payload = ChatButtonLinks.payload(Arrays.asList(
                new ChatButtonLinks.Link("Website", "https://example.com/a"),
                new ChatButtonLinks.Link("", "https://shop.example/b")));
        assertTrue(ChatButtonLinks.picked(activity, payload, copier));
        assertTrue("nothing is copied before a pick", copied.isEmpty());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        ListView list = dialog.getListView();
        assertEquals(2, list.getAdapter().getCount());
        assertEquals("Website\nhttps://example.com/a", String.valueOf(list.getAdapter().getItem(0)));
        assertEquals("a button with no words shows its link", "https://shop.example/b", String.valueOf(list.getAdapter().getItem(1)));
        shadowOf(dialog).clickOnItem(1);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList("https://shop.example/b"), copied);
    }

    @Test
    public void instagramsOwnRowsAreLeftToInstagram() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertFalse(ChatButtonLinks.picked(activity, null, copier));
        assertFalse(ChatButtonLinks.picked(activity, "instagram://xma_action?id=1", copier));
        assertFalse(ChatButtonLinks.picked(activity, "https://example.com/", copier));
        assertTrue(copied.isEmpty());
        // A Copy link row with nothing left in it is still ours: Instagram mustn't act on it.
        assertTrue(ChatButtonLinks.picked(activity, ChatButtonLinks.PREFIX, copier));
        assertTrue(copied.isEmpty());
    }

    @Test
    public void onlyWebPagesCountAsLinks() {
        assertEquals("https://example.com/x", ChatButtonLinks.webLink(" https://example.com/x "));
        assertEquals("HTTP://EXAMPLE.COM", ChatButtonLinks.webLink("HTTP://EXAMPLE.COM"));
        assertNull(ChatButtonLinks.webLink("instagram://user?username=x"));
        assertNull(ChatButtonLinks.webLink("mailto:someone@example.com"));
        assertNull(ChatButtonLinks.webLink("https://"));
        assertNull(ChatButtonLinks.webLink(""));
        assertNull(ChatButtonLinks.webLink(null));
        assertEquals("a redirect with nothing to go to stays as it is", "https://l.instagram.com/?e=1",
                ChatButtonLinks.webLink("https://l.instagram.com/?e=1"));
        assertEquals("a redirect to the app stays as it is", "https://l.instagram.com/?u=instagram%3A%2F%2Fx",
                ChatButtonLinks.webLink("https://l.instagram.com/?u=instagram%3A%2F%2Fx"));
    }

    @Test
    public void theRowCarriesItsLinksBackWhole() {
        List<ChatButtonLinks.Link> links = Arrays.asList(
                new ChatButtonLinks.Link("Website", "https://example.com/a?b=1&c=2#d"),
                new ChatButtonLinks.Link("Shop", "https://shop.example/"));
        List<ChatButtonLinks.Link> back = ChatButtonLinks.decode(ChatButtonLinks.payload(links));
        assertEquals(2, back.size());
        assertEquals("https://example.com/a?b=1&c=2#d", back.get(0).url);
        assertEquals("Shop", back.get(1).title);
        assertTrue(ChatButtonLinks.decode("https://example.com/").isEmpty());
        assertEquals("Copy links from chat buttons", FamilyNames.CHAT_BUTTON_LINKS);
    }
}
