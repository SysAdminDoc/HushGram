/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Walks a comments list for the rows that hold a search, for {@link CommentSearch}. It reads only
 * the rows on screen: when none below (or above) the last match holds the search, it scrolls the
 * list three quarters of what shows and looks again, so Instagram loads the next comments the way it
 * does for a person scrolling. Instagram draws a comment's words only while that part of its row
 * shows, so a row cut off by the list's edge, or by the box over it, is read again on the next look
 * until all of it has shown. At the bottom it waits a moment for a page still loading, then says
 * there's nothing more.
 *
 * <p>Everything it touches goes through {@link Host}, so a test can drive it one step at a time.
 */
final class CommentFinder {
    /** What the finder needs from the list and the bar. */
    interface Host {
        /** The list's rows on screen now. */
        int rows();

        View row(int index);

        /** Where the list's rows start, inside the list. */
        int top();

        int height();

        boolean canScroll(int direction);

        void scrollBy(int dy);

        /** Runs [step] after [delayMillis], on the list's thread. */
        void later(Runnable step, long delayMillis);

        /** False once the list has left the screen, which ends a walk. */
        boolean alive();

        /** [row] is the match just scrolled to. */
        void found(View row);

        /** One of {@link #CLEAR}, {@link #SEARCHING} or {@link #NO_MORE}. */
        void status(int state);
    }

    static final int CLEAR = 0;
    static final int SEARCHING = 1;
    static final int NO_MORE = 2;

    /** The pause between a scroll and the next look, for the list to lay out the rows it brought in. */
    static final long STEP_MILLIS = 140;
    /** At the bottom, how long one wait for the next page is, and how many in a row before giving up. */
    static final long WAIT_MILLIS = 300;
    static final int WAITS = 8;
    /** A walk scrolls at most this many times, so a search for nothing ends on a long post too. */
    static final int PAGES = 400;

    /** How deep in a row its words are looked for. */
    private static final int DEPTH = 16;
    private static final String BUTTON = "android.widget.Button";
    private static final String IMAGE = "android.widget.ImageView";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** RCTextView, Instagram's drawn text view, keeps this name. */
    private static final String TEXT = "getText";
    /** Litho's ComponentHost keeps this name; it lists the text the host draws. */
    private static final String DRAWN = "getTextContentText";
    private static final Method NONE = Object.class.getMethods()[0];
    private static final Map<Class<?>, Method> TEXT_METHODS = new HashMap<>();
    private static final Map<Class<?>, Method> DRAWN_METHODS = new HashMap<>();

    private final Host host;
    private String needle = "";
    @Nullable
    private View last;
    /** Where [last] was scrolled to: while it's still there, the list hasn't moved and it's the same comment. */
    private int lastTop;
    private int walk;

    CommentFinder(Host host) {
        this.host = host;
    }

    /** The search was changed to [text]: any walk stops and the next find starts from the rows on screen. */
    void search(@Nullable CharSequence text) {
        needle = fold(text);
        last = null;
        walk++;
        host.status(CLEAR);
    }

    boolean searching() {
        return !needle.isEmpty();
    }

    /** Looks for the next row holding the search below the last match ([direction] 1) or above it (-1). */
    void find(int direction) {
        if (needle.isEmpty()) return;
        int dir = direction < 0 ? -1 : 1;
        step(dir, ++walk, start(dir), unread(dir), 0, 0);
    }

    /** Stops any walk under way. */
    void stop() {
        walk++;
    }

    private int start(int dir) {
        View previous = last;
        // A row's words can be half drawn, so it's known by staying put, or else by still holding the search.
        if (previous != null && previous.getParent() != null
                && (previous.getTop() == lastTop || fold(text(previous)).contains(needle))) {
            return previous.getTop();
        }
        return edge(dir);
    }

    /** The bound that lets every row on screen in, for a walk in [dir]. */
    private int edge(int dir) {
        return dir > 0 ? host.top() - 1 : host.height() + 1;
    }

    /** A [seen] for a walk in [dir] that hasn't read anything yet. */
    private static int unread(int dir) {
        return dir > 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
    }

    /**
     * One look. Only rows past [bound] count, the last match's top or the edge a walk starts from.
     * [seen] is how far along [dir] rows have shown at an earlier look: a row all on that side of it
     * has been read whole, and one reaching past it is read again.
     */
    private void step(final int dir, final int id, final int bound, final int seen, final int pages, final int waits) {
        if (id != walk || !host.alive()) return;
        View hit = scan(dir, bound, seen);
        if (hit != null) {
            last = hit;
            host.scrollBy(hit.getTop() - host.top());
            lastTop = hit.getTop();
            host.found(hit);
            host.status(CLEAR);
            return;
        }
        if (!host.canScroll(dir)) {
            if (dir > 0 && waits < WAITS) {
                host.status(SEARCHING);
                host.later(() -> step(dir, id, bound, seen, pages, waits + 1), WAIT_MILLIS);
                return;
            }
            host.status(NO_MORE);
            return;
        }
        if (pages >= PAGES) {
            host.status(NO_MORE);
            return;
        }
        // Everything showing has been read: down to the list's bottom, or up to where rows start showing.
        final int shown = dir > 0 ? host.height() : host.top();
        final View marker = outermost(dir);
        final int before = marker == null ? 0 : marker.getTop();
        host.status(SEARCHING);
        // Less than what shows, so every line of every row shows at one look or another.
        host.scrollBy(dir * Math.max(1, (host.height() - host.top()) * 3 / 4));
        host.later(() -> {
            if (marker == null || marker.getParent() == null) {
                step(dir, id, edge(dir), unread(dir), pages + 1, 0);
                return;
            }
            // The rows moved with the scroll, the marker by as much as any; the bounds move with them.
            int moved = marker.getTop() - before;
            step(dir, id, bound + moved, shown + moved, pages + 1, 0);
        }, STEP_MILLIS);
    }

    /** The nearest row past [bound] in [dir] that holds the search and hasn't all shown yet, or null. */
    @Nullable
    private View scan(int dir, int bound, int seen) {
        View best = null;
        for (int i = 0, count = host.rows(); i < count; i++) {
            View row = host.row(i);
            if (row == null || row.getHeight() <= 0 || row.getVisibility() != View.VISIBLE) continue;
            int top = row.getTop();
            boolean past = dir > 0 ? top > bound : top < bound;
            boolean unseen = dir > 0 ? row.getBottom() > seen : top < seen;
            if (!past || !unseen) continue;
            if (best != null && (dir > 0 ? top >= best.getTop() : top <= best.getTop())) continue;
            if (fold(text(row)).contains(needle)) best = row;
        }
        return best;
    }

    /** The row farthest along [dir] on screen, or null when there are none. */
    @Nullable
    private View outermost(int dir) {
        View far = null;
        for (int i = 0, count = host.rows(); i < count; i++) {
            View row = host.row(i);
            if (row == null) continue;
            if (far == null || (dir > 0 ? row.getTop() > far.getTop() : row.getTop() < far.getTop())) far = row;
        }
        return far;
    }

    /**
     * A row's words: its text views' text, the text Instagram's comment rows draw without a view
     * (the name and the comment), and the descriptions Instagram gives its other views. Buttons,
     * pictures and tappable drawn text are left out, so Reply, See translation and the like count
     * never match.
     */
    static String text(View row) {
        StringBuilder out = new StringBuilder();
        gather(row, out, 0);
        return out.toString();
    }

    private static void gather(View view, StringBuilder out, int depth) {
        if (depth > DEPTH || view.getVisibility() != View.VISIBLE) return;
        CharSequence kind = view.getAccessibilityClassName();
        if (kind != null && (BUTTON.contentEquals(kind) || IMAGE.contentEquals(kind))) return;
        if (view instanceof TextView) {
            append(out, ((TextView) view).getText());
        } else {
            Method own = method(view.getClass(), TEXT, CharSequence.class);
            if (own != null) {
                // A view drawing its own text: a tappable one is a link like Reply.
                if (view.isClickable()) return;
                append(out, call(own, view));
            } else {
                CharSequence said = view.getContentDescription();
                String drawn = drawn(view, depth);
                if (TextUtils.isEmpty(said) && drawn.isEmpty()) said = described(view);
                append(out, said);
                if (!drawn.isEmpty()) out.append(drawn);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) gather(group.getChildAt(i), out, depth + 1);
        }
    }

    private static void append(StringBuilder out, @Nullable CharSequence text) {
        if (!TextUtils.isEmpty(text)) out.append(text).append('\n');
    }

    /**
     * The text a Litho host draws itself, one line each, without what its child views say (they're
     * read on their own, and a tappable one not at all). Instagram's comment rows draw the name and
     * the comment this way; accessibility only describes them while a service such as a screen
     * reader runs, so without this a search finds nothing.
     */
    private static String drawn(View view, int depth) {
        Method texts = method(view.getClass(), DRAWN, List.class);
        if (texts == null) return "";
        Object items = call(texts, view);
        if (!(items instanceof List) || ((List<?>) items).isEmpty()) return "";
        List<String> theirs = new ArrayList<>();
        if (view instanceof ViewGroup) childTexts((ViewGroup) view, theirs, depth + 1);
        StringBuilder out = new StringBuilder();
        for (Object item : (List<?>) items) {
            if (!(item instanceof CharSequence) || TextUtils.isEmpty((CharSequence) item)) continue;
            String line = item.toString();
            if (theirs.remove(line)) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void childTexts(ViewGroup group, List<String> into, int depth) {
        if (depth > DEPTH) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            CharSequence text = null;
            if (child instanceof TextView) {
                text = ((TextView) child).getText();
            } else {
                Method own = method(child.getClass(), TEXT, CharSequence.class);
                if (own != null) text = call(own, child);
            }
            if (!TextUtils.isEmpty(text)) into.add(text.toString());
            if (child instanceof ViewGroup) childTexts((ViewGroup) child, into, depth + 1);
        }
    }

    /** [type]'s public no-argument [name] returning a [result], or null; looked up once per class. */
    @Nullable
    private static Method method(Class<?> type, String name, Class<?> result) {
        Map<Class<?>, Method> cache = TEXT.equals(name) ? TEXT_METHODS : DRAWN_METHODS;
        Method known = cache.get(type);
        if (known == null) {
            known = NONE;
            try {
                Method found = type.getMethod(name);
                if (result.isAssignableFrom(found.getReturnType())) known = found;
            } catch (NoSuchMethodException | SecurityException | LinkageError none) {
                // Not a view that draws text.
            }
            cache.put(type, known);
        }
        return known == NONE ? null : known;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T call(Method method, View view) {
        try {
            return (T) method.invoke(view);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** What a view that draws its own text tells accessibility it says. */
    @Nullable
    private static CharSequence described(View view) {
        AccessibilityNodeInfo node = null;
        try {
            node = view.createAccessibilityNodeInfo();
            CharSequence said = node.getText();
            if (TextUtils.isEmpty(said)) said = node.getContentDescription();
            return said == null ? null : said.toString();
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            if (node != null) node.recycle();
        }
    }

    /** [text] without case or accents, so "jose" finds "José". */
    static String fold(@Nullable CharSequence text) {
        if (text == null) return "";
        String plain = MARKS.matcher(Normalizer.normalize(text.toString().trim(), Normalizer.Form.NFD)).replaceAll("");
        return plain.toLowerCase(Locale.ROOT);
    }
}
