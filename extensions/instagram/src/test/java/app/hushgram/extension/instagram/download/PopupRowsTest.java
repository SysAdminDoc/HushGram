/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The rows added to Instagram's newer pop-up list: a copy of a plain row the list already has, with
 * a callback that runs the listener and closes the list.
 */
public class PopupRowsTest {
    /** What the patch writes for {@link Item}, whose fields are named in constructor order. */
    static final String ITEM_LAYOUT = Item.class.getName()
            + "|A00,A01,A02,A03,A04,A05,A06,A07,A08,A09,A0A,A0B,A0C,A0D,A0E,A0F,A0G,A0H,A0I,A0J,A0K,A0L,A0M,A0N";
    /** What the patch writes for 450's X.0juk, read off its constructor, here for {@link Scrambled}. */
    static final String SCRAMBLED_LAYOUT = Scrambled.class.getName()
            + "|A00,A01,A02,A03,A04,A05,A06,A07,A08,A09,A0C,A0B,A0F,A0D,A0E,A0A,A0G,A0H,A0L,A0M,A0N,A0K,A0J,A0I";

    @Before public void itemLayout() {
        PopupRows.reset();
        PopupRows.layout = () -> ITEM_LAYOUT;
    }

    @After public void patchedLayout() {
        PopupRows.reset();
        PopupRows.layout = ProfilePicture::popupItemFields;
    }

    /** Shaped like 450's pop-up item: 24 fields named in constructor order, an interface callback fifth, the label seventeenth. */
    static final class Item {
        final Object A00, A01, A02, A03;
        final Callback A04, A05, A06;
        final Object A07, A08;
        final Integer A09, A0A, A0B, A0C, A0D, A0E, A0F;
        final String A0G, A0H;
        final boolean A0I, A0J, A0K, A0L, A0M, A0N;

        Item(Object a00, Object a01, Object a02, Object a03, Callback a04, Callback a05, Callback a06, Object a07, Object a08,
                Integer a09, Integer a0a, Integer a0b, Integer a0c, Integer a0d, Integer a0e, Integer a0f, String a0g, String a0h,
                boolean a0i, boolean a0j, boolean a0k, boolean a0l, boolean a0m, boolean a0n) {
            A00 = a00; A01 = a01; A02 = a02; A03 = a03; A04 = a04; A05 = a05; A06 = a06; A07 = a07; A08 = a08;
            A09 = a09; A0A = a0a; A0B = a0b; A0C = a0c; A0D = a0d; A0E = a0e; A0F = a0f; A0G = a0g; A0H = a0h;
            A0I = a0i; A0J = a0j; A0K = a0k; A0L = a0l; A0M = a0m; A0N = a0n;
        }
    }

    /**
     * Shaped like 450's X.0juk itself: the same arguments, stored where its constructor stores them.
     * The eleventh argument, the highlight color, goes to A0C, the sixteenth to A0A, and the six
     * booleans in reverse.
     */
    static final class Scrambled {
        final Object A00, A01, A02, A03;
        final Callback A04, A05, A06;
        final Object A07, A08;
        final Integer A09, A0A, A0B, A0C, A0D, A0E, A0F;
        final String A0G, A0H;
        final boolean A0I, A0J, A0K, A0L, A0M, A0N;

        Scrambled(Object a0, Object a1, Object a2, Object a3, Callback a4, Callback a5, Callback a6, Object a7, Object a8,
                Integer a9, Integer a10, Integer a11, Integer a12, Integer a13, Integer a14, Integer a15, String a16, String a17,
                boolean a18, boolean a19, boolean a20, boolean a21, boolean a22, boolean a23) {
            A00 = a0; A01 = a1; A02 = a2; A03 = a3; A04 = a4; A05 = a5; A06 = a6; A07 = a7; A08 = a8;
            A09 = a9; A0C = a10; A0B = a11; A0F = a12; A0D = a13; A0E = a14; A0A = a15; A0G = a16; A0H = a17;
            A0L = a18; A0M = a19; A0N = a20; A0K = a21; A0J = a22; A0I = a23;
        }
    }

    /** A 450 row as its menu builds it: color in the tenth argument, highlight in the eleventh, a number in the sixteenth. */
    static Scrambled scrambled(String label, Integer color, Integer highlight) {
        return new Scrambled(null, null, null, null, null, null, null, null, null, color, highlight, 21, 22, 23, 24, 25,
                label, "about", true, false, false, true, true, false);
    }

    /** Shaped like 0qhV: one void method and one boolean one. */
    public interface Callback {
        boolean closes();
        void run();
    }

    static Item row(String label, Integer color, Integer highlight) {
        return new Item(null, null, null, null, null, null, null, null, null, color, highlight, null, highlight, 3, null, 3,
                label, null, false, true, false, false, true, false);
    }

    static List<String> labels(List<?> items) {
        List<String> labels = new ArrayList<>();
        for (Object item : items) labels.add(((Item) item).A0G);
        return labels;
    }

    static void tap(Object item) {
        Callback click = ((Item) item).A04;
        click.run();
        assertTrue("the list closes after a tap", click.closes());
    }

    @Test public void aPlainRowIsCopiedWithTheNewLabelAndCallback() {
        List<Object> items = new ArrayList<>();
        Item plain = row("Mute", -1, null);
        items.add(plain);
        int[] taps = {0};
        assertTrue(PopupRows.add(items, view -> taps[0]++, "Copy username"));
        assertEquals(Arrays.asList("Mute", "Copy username"), labels(items));
        Item added = (Item) items.get(1);
        assertEquals("the plain row's own values", Integer.valueOf(3), added.A0D);
        assertEquals(Integer.valueOf(-1), added.A09);
        assertEquals(plain.A0J, added.A0J);
        assertEquals(plain.A0M, added.A0M);
        assertEquals("no icon", null, added.A00);
        assertEquals("no subtitle", null, added.A0H);
        tap(added);
        assertEquals(1, taps[0]);
    }

    @Test public void aListHandedOverAgainKeepsOneRowPerLabel() {
        List<Object> items = new ArrayList<>();
        items.add(row("Mute", -1, null));
        assertTrue(PopupRows.add(items, view -> { }, "Copy username"));
        assertTrue(PopupRows.add(items, view -> { }, "Copy username"));
        assertTrue(PopupRows.add(items, view -> { }, "Copy bio"));
        assertEquals(Arrays.asList("Mute", "Copy username", "Copy bio"), labels(items));
    }

    @Test public void anInstagramRowWithTheSameLabelIsNotMistakenForOurs() {
        List<Object> items = new ArrayList<>();
        items.add(row("Copy username", -1, null));
        assertTrue(PopupRows.add(items, view -> { }, "Copy username"));
        assertEquals(Arrays.asList("Copy username", "Copy username"), labels(items));
    }

    @Test public void theTemplateIsThePlainRowNotAHighlightedOne() {
        List<Object> items = new ArrayList<>();
        items.add(row("Report", 7, 7));
        Item plain = row("Mute", -1, null);
        items.add(plain);
        assertTrue(PopupRows.add(items, view -> { }, "Copy bio"));
        assertEquals(Arrays.asList("Report", "Mute", "Copy bio"), labels(items));
    }

    @Test public void withNoPlainRowNothingIsAdded() {
        List<Object> items = new ArrayList<>();
        items.add(row("Report", 7, 7));
        assertFalse(PopupRows.add(items, view -> { }, "Copy bio"));
        assertEquals(1, items.size());
        assertFalse(PopupRows.add(new ArrayList<>(), view -> { }, "Copy bio"));
    }

    @Test public void anItemOfAnotherShapeIsLeftAlone() {
        List<Object> items = new ArrayList<>();
        items.add("not an item");
        items.add(null);
        assertFalse(PopupRows.add(items, view -> { }, "Copy bio"));
        assertEquals(2, items.size());
    }

    /**
     * On 450 the field named A0A holds the sixteenth argument, never null, so a reader going by the
     * names took every row for a highlighted one and added nothing. With the constructor's own order
     * the plain row is found, and the copy gets every argument back in its place.
     */
    @Test public void a450ItemIsReadInItsConstructorsOrder() {
        PopupRows.layout = () -> SCRAMBLED_LAYOUT;
        List<Object> items = new ArrayList<>();
        Scrambled report = scrambled("Report", 7, 7);
        Scrambled plain = scrambled("About this account", -1, null);
        items.add(plain);
        items.add(report);
        int[] taps = {0};
        assertTrue(PopupRows.add(items, view -> taps[0]++, "Save profile picture"));
        assertEquals(3, items.size());
        Scrambled added = (Scrambled) items.get(2);
        assertEquals("Save profile picture", added.A0G);
        assertEquals("no subtitle", null, added.A0H);
        assertEquals("no icon", null, added.A00);
        assertEquals("the plain color", Integer.valueOf(-1), added.A09);
        assertEquals("no highlight", null, added.A0C);
        for (String name : new String[] {"A0A", "A0B", "A0D", "A0E", "A0F"}) {
            assertEquals(name, field(plain, name), field(added, name));
        }
        for (String name : new String[] {"A0I", "A0J", "A0K", "A0L", "A0M", "A0N"}) {
            assertEquals(name, field(plain, name), field(added, name));
        }
        added.A04.run();
        assertTrue("the list closes after a tap", added.A04.closes());
        assertEquals(1, taps[0]);
    }

    @Test public void withoutThePatchesLayoutNothingIsAdded() {
        PopupRows.layout = () -> null;
        List<Object> items = new ArrayList<>();
        items.add(row("Mute", -1, null));
        assertFalse(PopupRows.add(items, view -> { }, "Copy bio"));
        assertEquals(1, items.size());
    }

    @Test public void aLayoutForAnotherClassIsNotUsed() {
        PopupRows.layout = () -> SCRAMBLED_LAYOUT;
        List<Object> items = new ArrayList<>();
        items.add(row("Mute", -1, null));
        assertFalse(PopupRows.add(items, view -> { }, "Copy bio"));
        assertEquals(1, items.size());
    }

    @Test public void aLayoutNamingAFieldTwiceIsNotUsed() {
        PopupRows.layout = () -> ITEM_LAYOUT.replace("A0B", "A0A");
        List<Object> items = new ArrayList<>();
        items.add(row("Mute", -1, null));
        assertFalse(PopupRows.add(items, view -> { }, "Copy bio"));
    }

    private static Object field(Object item, String name) {
        try {
            java.lang.reflect.Field field = item.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(item);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test public void theCallbackIsAProxyThatKeepsItsIdentity() {
        Object proxy = PopupRows.callback(Callback.class, view -> { });
        assertNotNull(proxy);
        assertSame(proxy, proxy);
        assertTrue(proxy.equals(proxy));
        assertEquals(System.identityHashCode(proxy), proxy.hashCode());
        assertFalse(proxy.equals(new Object()));
    }

    @Test public void aCallbackThatIsntOneBooleanAndOneVoidIsRefused() {
        assertEquals(null, PopupRows.callback(Runnable.class, view -> { }));
    }
}
