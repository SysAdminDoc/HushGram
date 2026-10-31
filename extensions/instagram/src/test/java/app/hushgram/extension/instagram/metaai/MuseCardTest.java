/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.metaai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the Muse card's second net: which views a look folds away, Home's feed and your profile shaped the
 * way Instagram 453 lays them out, and what's put back.
 */
@RunWith(RobolectricTestRunner.class)
public class MuseCardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int BLOKS = View.generateViewId();
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        MuseCard.bloksId = BLOKS;
        Settings.HIDE_META_AI_PROFILE_BANNER.save(true);
    }

    @After
    public void tearDown() {
        Settings.HIDE_META_AI_PROFILE_BANNER.resetToDefault();
        MuseCard.folded.clear();
        MuseCard.bloksId = 0;
    }

    /** Home: the Muse card's row folds to nothing, and the card and post under it stay. */
    @Test
    public void homesMuseRowFoldsAndTheRestStays() {
        FrameLayout root = new FrameLayout(context);
        LinearLayout feed = list(root);
        View muse = row(feed, bloks(text("Meet Muse, your new personal AI agent"), text("Try Muse")));
        View other = row(feed, bloks(text("Add your birthday"), text("Not now")));
        View post = row(feed, described("Photo by a friend. My muse at the museum"));

        MuseCard.look(root);

        assertEquals(View.GONE, muse.getVisibility());
        assertEquals("the list closes up under it", 0, muse.getLayoutParams().height);
        assertShown(other);
        assertShown(post);
    }

    /** Bloks says what a card shows in its description, or on the nodes it draws itself. */
    @Test
    public void aCardThatOnlySaysMuseIsFoundToo() {
        FrameLayout root = new FrameLayout(context);
        LinearLayout feed = list(root);
        View described = row(feed, bloks(described("Announcement from instagram, Meet Muse, your new personal AI agent")));
        View drawn = row(feed, bloks(drawn("Meet Muse")));

        MuseCard.look(root);

        assertEquals(View.GONE, described.getVisibility());
        assertEquals(View.GONE, drawn.getVisibility());
    }

    /** Your profile: the card in the header goes, and the header's other pieces stay. */
    @Test
    public void theProfilesCardGoesAndTheHeaderStays() {
        FrameLayout root = new FrameLayout(context);
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        root.addView(header);
        TextView bio = text("Coffee, film and my muse");
        header.addView(bio);
        View card = bloks(text("Meet Muse"), text("Try Muse"));
        header.addView(card);

        MuseCard.look(root);

        assertEquals(View.GONE, card.getVisibility());
        assertEquals("not in a list, its height is left alone",
                ViewGroup.LayoutParams.WRAP_CONTENT, card.getLayoutParams().height);
        assertShown(header);
        assertShown(bio);
    }

    /** A folded row reused for another card is put back the way it was. */
    @Test
    public void aRowReusedForAnotherCardComesBack() {
        FrameLayout root = new FrameLayout(context);
        LinearLayout feed = list(root);
        TextView title = text("Meet Muse");
        View row = row(feed, bloks(title));
        MuseCard.look(root);
        assertEquals(View.GONE, row.getVisibility());

        title.setText("Add your birthday");
        MuseCard.look(root);

        assertShown(row);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
        assertTrue(MuseCard.folded.isEmpty());
    }

    /** Off, or paused, Instagram decides: nothing is folded, and what was folded comes back. */
    @Test
    public void offOrPausedEverythingShows() {
        FrameLayout root = new FrameLayout(context);
        LinearLayout feed = list(root);
        View row = row(feed, bloks(text("Meet Muse")));
        MuseCard.look(root);
        assertEquals(View.GONE, row.getVisibility());

        Settings.HIDE_META_AI_PROFILE_BANNER.save(false);
        MuseCard.look(root);
        assertShown(row);

        Settings.HIDE_META_AI_PROFILE_BANNER.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try {
            MuseCard.look(root);
            assertShown(row);
        } finally {
            PauseForTests.resume();
        }
    }

    /** Muse inside another word, or a Bloks card without it, is never a Muse card. */
    @Test
    public void onlyTheWholeWordCounts() {
        FrameLayout root = new FrameLayout(context);
        LinearLayout feed = list(root);
        View museum = row(feed, bloks(text("Plan a trip to the museum")));
        View amused = row(feed, bloks(described("You look amused")));

        MuseCard.look(root);

        assertShown(museum);
        assertShown(amused);
    }

    private static void assertShown(View view) {
        assertEquals(View.VISIBLE, view.getVisibility());
    }

    private LinearLayout list(ViewGroup root) {
        LinearLayout list = new LinearLayout(context);
        list.setId(android.R.id.list);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        return list;
    }

    private View row(ViewGroup list, View content) {
        FrameLayout row = new FrameLayout(context);
        row.addView(content);
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View bloks(View... pieces) {
        LinearLayout card = new LinearLayout(context);
        card.setId(BLOKS);
        for (View piece : pieces) card.addView(piece);
        card.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private TextView text(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        return view;
    }

    private View described(String description) {
        View view = new View(context);
        view.setContentDescription(description);
        return view;
    }

    /** A view that draws its text itself and describes it as an accessibility node, like Bloks does. */
    private View drawn(String label) {
        return new View(context) {
            @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
                View host = this;
                return new AccessibilityNodeProvider() {
                    @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(host);
                        node.setContentDescription(label);
                        return node;
                    }
                };
            }
        };
    }
}
