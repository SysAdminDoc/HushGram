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
import org.junit.Test;

/**
 * The rows added to Instagram's newer pop-up list: a copy of a plain row the list already has, with
 * a callback that runs the listener and closes the list.
 */
public class PopupRowsTest {
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
