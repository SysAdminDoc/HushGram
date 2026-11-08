/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.robolectric.RuntimeEnvironment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** How Search comments walks a comments list (#560), against a list that scrolls and loads like Instagram's. */
@RunWith(RobolectricTestRunner.class)
public class CommentFinderTest {
    private static final int ROW = 100;
    private static final int HEIGHT = 1000;

    /**
     * A comments list: rows 100 high in a window 1000 high, made once per comment and kept while on
     * screen, as a RecyclerView keeps its children. When a scroll reaches the bottom with comments
     * still to come, the next page arrives [delay] steps later.
     */
    private static class FakeList implements CommentFinder.Host {
        final Context context = RuntimeEnvironment.getApplication();
        final FrameLayout parent = new FrameLayout(context);
        final List<String> all;
        final int page;
        final int delay;
        final Map<Integer, View> views = new HashMap<>();
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<Integer> statuses = new ArrayList<>();
        int loaded;
        int offset;
        int loadIn = -1;
        int scrolls;
        View found;
        int foundIndex = -1;
        int rowHeight = ROW;
        int height = HEIGHT;
        int shownFrom;
        boolean drawnWhileShowing;

        FakeList(List<String> all, int loaded, int page, int delay) {
            this.all = all;
            this.loaded = Math.min(loaded, all.size());
            this.page = page;
            this.delay = delay;
            layOut();
        }

        /**
         * Rows [rowHeight] high in a list [height] high whose top [shownFrom] sits under the box, each
         * row's words drawn only while they show, the way Instagram's are.
         */
        static FakeList drawnWhileShowing(List<String> all, int rowHeight, int height, int shownFrom) {
            FakeList list = new FakeList(all, all.size(), 0, 0);
            list.rowHeight = rowHeight;
            list.height = height;
            list.shownFrom = shownFrom;
            list.drawnWhileShowing = true;
            for (View view : list.views.values()) list.parent.removeView(view);
            list.views.clear();
            list.layOut();
            return list;
        }

        View make(int index) {
            if (drawnWhileShowing) return new DrawnWhileShowing(this, all.get(index));
            TextView text = new TextView(context);
            text.setText(all.get(index));
            return text;
        }

        private int max() {
            return Math.max(0, loaded * rowHeight - height);
        }

        private List<View> layOut() {
            List<View> out = new ArrayList<>();
            for (int i = 0; i < loaded; i++) {
                int top = i * rowHeight - offset;
                boolean on = top + rowHeight > 0 && top < height;
                View view = views.get(i);
                if (on) {
                    if (view == null) {
                        view = make(i);
                        views.put(i, view);
                    }
                    if (view.getParent() == null) parent.addView(view);
                    view.layout(0, top, 500, top + rowHeight);
                    out.add(view);
                } else if (view != null && view.getParent() != null) {
                    parent.removeView(view);
                }
            }
            return out;
        }

        @Override
        public int rows() {
            return layOut().size();
        }

        @Override
        public View row(int index) {
            return layOut().get(index);
        }

        @Override
        public int top() {
            return shownFrom;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public boolean canScroll(int direction) {
            return direction > 0 ? offset < max() : offset > 0;
        }

        @Override
        public void scrollBy(int dy) {
            scrolls++;
            offset = Math.max(0, Math.min(max(), offset + dy));
            if (offset == max() && loaded < all.size() && loadIn < 0) loadIn = delay;
            layOut();
        }

        @Override
        public void later(Runnable step, long delayMillis) {
            queue.add(step);
        }

        @Override
        public boolean alive() {
            return true;
        }

        @Override
        public void found(View row) {
            found = row;
            foundIndex = -1;
            for (Map.Entry<Integer, View> entry : views.entrySet()) {
                if (entry.getValue() == row) foundIndex = entry.getKey();
            }
        }

        @Override
        public void status(int state) {
            statuses.add(state);
        }

        /** Runs the finder's later steps, the page loads with them, until none are left. */
        void settle() {
            for (int guard = 0; guard < 10_000 && !queue.isEmpty(); guard++) {
                if (loadIn == 0) {
                    loaded = Math.min(all.size(), loaded + page);
                    loadIn = -1;
                    layOut();
                } else if (loadIn > 0) {
                    loadIn--;
                }
                queue.poll().run();
            }
            assertTrue("the walk never ended", queue.isEmpty());
        }

        int last() {
            return statuses.isEmpty() ? -1 : statuses.get(statuses.size() - 1);
        }
    }

    private static List<String> comments(int count, Map<Integer, String> special) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(special.containsKey(i) ? special.get(i) : "comment number " + i);
        return out;
    }

    /** A comment row drawing its words, 150 down and 30 high, only while that line shows in the list. */
    public static final class DrawnWhileShowing extends View {
        private static final int LINE = 150;
        private static final int LINE_HEIGHT = 30;
        private final FakeList list;
        private final String words;

        DrawnWhileShowing(FakeList list, String words) {
            super(list.context);
            this.list = list;
            this.words = words;
        }

        public CharSequence getText() {
            int line = getTop() + LINE;
            return line + LINE_HEIGHT > list.shownFrom && line < list.height ? words : "";
        }
    }

    /**
     * With the keyboard up and the list slid under the box only a sliver shows, and Instagram draws a
     * comment's words only while they show. A row cut off by an edge is read again until all of it
     * has shown, both ways, and the next press still goes past a match whose words scrolled away.
     */
    @Test
    public void aRowCutOffByAnEdgeIsReadAgainUntilAllOfItHasShown() {
        Map<Integer, String> worth = new HashMap<>();
        worth.put(4, "wonder if it's worth it");
        worth.put(9, "worth the climb");
        FakeList list = FakeList.drawnWhileShowing(comments(30, worth), 300, 125, 50);
        CommentFinder finder = new CommentFinder(list);
        finder.search("worth");

        finder.find(1);
        list.settle();
        assertEquals(4, list.foundIndex);
        assertEquals("the match starts where rows start showing", 50, list.found.getTop());

        finder.find(1);
        list.settle();
        assertEquals(9, list.foundIndex);

        finder.find(-1);
        list.settle();
        assertEquals(4, list.foundIndex);
        assertEquals(CommentFinder.CLEAR, list.last());
    }

    /** Down finds the nearest match, scrolls it to the top, and the next press goes past it, off screen too. Up comes back. */
    @Test
    public void stepsThroughMatchesBothWays() {
        Map<Integer, String> cats = new HashMap<>();
        cats.put(3, "my cat did this");
        cats.put(25, "CAT tax");
        cats.put(40, "a cat again");
        FakeList list = new FakeList(comments(50, cats), 50, 0, 0);
        CommentFinder finder = new CommentFinder(list);
        finder.search("cat");

        finder.find(1);
        list.settle();
        assertEquals(3, list.foundIndex);
        assertEquals("the match is scrolled to the top", 0, list.found.getTop());

        finder.find(1);
        list.settle();
        assertEquals(25, list.foundIndex);
        assertEquals(0, list.found.getTop());

        finder.find(1);
        list.settle();
        assertEquals(40, list.foundIndex);

        finder.find(-1);
        list.settle();
        assertEquals(25, list.foundIndex);

        finder.find(-1);
        list.settle();
        assertEquals(3, list.foundIndex);
        assertEquals(CommentFinder.CLEAR, list.last());
    }

    /** A match further down than what's loaded is reached by scrolling, the way more comments load for a person. */
    @Test
    public void loadsMoreToReachAMatch() {
        Map<Integer, String> one = new HashMap<>();
        one.put(97, "found the needle here");
        FakeList list = new FakeList(comments(140, one), 20, 20, 3);
        CommentFinder finder = new CommentFinder(list);
        finder.search("needle");
        finder.find(1);
        assertEquals("it says it's looking", CommentFinder.SEARCHING, list.last());
        list.settle();
        assertEquals(97, list.foundIndex);
        assertEquals(CommentFinder.CLEAR, list.last());
    }

    /** With nothing more to load and no match below, it says so instead of walking forever. */
    @Test
    public void saysWhenThereAreNoMore() {
        FakeList list = new FakeList(comments(60, new HashMap<>()), 20, 20, 1);
        CommentFinder finder = new CommentFinder(list);
        finder.search("nowhere");
        finder.find(1);
        list.settle();
        assertNull(list.found);
        assertEquals(CommentFinder.NO_MORE, list.last());
        assertEquals("every comment was loaded on the way", 60, list.loaded);

        list.statuses.clear();
        finder.find(-1);
        list.settle();
        assertNull(list.found);
        assertEquals("going up ends at the top without waiting", CommentFinder.NO_MORE, list.last());
    }

    /** Past the last match, down says there are no more, and the last match stays where it was. */
    @Test
    public void pastTheLastMatchSaysNoMore() {
        Map<Integer, String> two = new HashMap<>();
        two.put(1, "hello");
        two.put(5, "hello again");
        FakeList list = new FakeList(comments(30, two), 30, 0, 0);
        CommentFinder finder = new CommentFinder(list);
        finder.search("hello");
        finder.find(1);
        finder.find(1);
        list.settle();
        assertEquals(5, list.foundIndex);
        View last = list.found;
        finder.find(1);
        list.settle();
        assertEquals(CommentFinder.NO_MORE, list.last());
        assertTrue(last == list.found);
    }

    /** Typing again stops a walk under way: its next step does nothing. */
    @Test
    public void aNewSearchStopsTheWalk() {
        Map<Integer, String> far = new HashMap<>();
        far.put(80, "target");
        FakeList list = new FakeList(comments(100, far), 100, 0, 0);
        CommentFinder finder = new CommentFinder(list);
        finder.search("target");
        finder.find(1);
        int scrolled = list.scrolls;
        finder.search("targe");
        list.settle();
        assertNull("the old walk found nothing after the search changed", list.found);
        assertEquals("and scrolled no further", scrolled, list.scrolls);
        assertEquals(CommentFinder.CLEAR, list.last());
    }

    /** An empty search finds nothing and doesn't move the list. */
    @Test
    public void anEmptySearchDoesNothing() {
        FakeList list = new FakeList(comments(30, new HashMap<>()), 30, 0, 0);
        CommentFinder finder = new CommentFinder(list);
        finder.search("   ");
        assertFalse(finder.searching());
        finder.find(1);
        list.settle();
        assertEquals(0, list.scrolls);
        assertNull(list.found);
    }

    /** Case and accents don't matter, so jose finds José. */
    @Test
    public void foldsCaseAndAccents() {
        assertEquals("jose", CommentFinder.fold("  José "));
        Map<Integer, String> name = new HashMap<>();
        name.put(2, "José said hi");
        FakeList list = new FakeList(comments(10, name), 10, 0, 0);
        CommentFinder finder = new CommentFinder(list);
        finder.search("JOSE");
        finder.find(1);
        list.settle();
        assertEquals(2, list.foundIndex);
    }

    /** A row's buttons aren't its words: Reply under every comment never matches. */
    @Test
    public void buttonsAreLeftOut() {
        Context context = RuntimeEnvironment.getApplication();
        LinearLayout row = new LinearLayout(context);
        TextView body = new TextView(context);
        body.setText("nice photo");
        Button reply = new Button(context);
        reply.setText("Reply");
        row.addView(body);
        row.addView(reply);
        String words = CommentFinder.text(row);
        assertTrue(words, words.contains("nice photo"));
        assertFalse(words, words.contains("Reply"));
    }

    /** A view that draws its own text is read by what it tells accessibility, its description here. */
    @Test
    public void readsDescriptionsOfViewsWithoutText() {
        Context context = RuntimeEnvironment.getApplication();
        LinearLayout row = new LinearLayout(context);
        View drawn = new View(context);
        drawn.setContentDescription("alex said the sunset was great");
        row.addView(drawn);
        assertTrue(CommentFinder.text(row).contains("sunset"));
    }

    /** Like Litho's ComponentHost: a host listing the text it draws, its children's text included. */
    public static final class DrawingHost extends FrameLayout {
        private final List<CharSequence> drawn;

        public DrawingHost(Context context, CharSequence... drawn) {
            super(context);
            this.drawn = java.util.Arrays.asList(drawn);
        }

        public List<CharSequence> getTextContentText() {
            return drawn;
        }
    }

    /** Like Instagram's RCTextView: a view drawing its own text, with no TextView behind it. */
    public static final class DrawnText extends FrameLayout {
        private final CharSequence text;

        public DrawnText(Context context, CharSequence text, boolean tappable) {
            super(context);
            this.text = text;
            setClickable(tappable);
        }

        public CharSequence getText() {
            return text;
        }
    }

    /**
     * Instagram draws a comment's name and words without a view and describes them only while a
     * screen reader runs, so a search used to find nothing on a phone. They're read from the host;
     * the Reply link under it, drawn and tappable, still isn't.
     */
    @Test
    public void readsTextLithoDrawsButNotItsLinks() {
        Context context = RuntimeEnvironment.getApplication();
        DrawingHost row = new DrawingHost(context, "alex", "my bike is red", "2h", "Reply");
        row.addView(new DrawnText(context, "Reply", true));
        String words = CommentFinder.text(row);
        assertTrue(words, words.contains("my bike is red"));
        assertTrue(words, words.contains("alex"));
        assertFalse(words, words.contains("Reply"));

        DrawingHost quiet = new DrawingHost(context);
        quiet.addView(new DrawnText(context, "a comment drawn on its own", false));
        assertTrue(CommentFinder.text(quiet).contains("drawn on its own"));
    }

    /** A drawn comment is found by the walk too, through the list's rows. */
    @Test
    public void findsADrawnComment() {
        Map<Integer, String> one = new HashMap<>();
        one.put(14, "my bike is red");
        FakeList list = new FakeList(comments(30, one), 30, 0, 0) {
            @Override
            View make(int index) {
                DrawingHost host = new DrawingHost(context, "someone", all.get(index), "Reply");
                host.addView(new DrawnText(context, "Reply", true));
                return host;
            }
        };
        CommentFinder finder = new CommentFinder(list);
        finder.search("bike");
        finder.find(1);
        list.settle();
        assertEquals(14, list.foundIndex);

        finder.search("reply");
        list.found = null;
        finder.find(1);
        list.settle();
        assertNull("Reply under every comment is never a match", list.found);
        assertEquals(CommentFinder.NO_MORE, list.last());
    }
}
