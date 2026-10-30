/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the caught up card (#307): the binder's hook folds the card's row to no height with the switch
 * on, keeps a top of feed card, and puts back the row's own height when the switch goes off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CaughtUpCardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for 452's end card kinds (LX/06SL): V2, TOP_OF_FEED, TOF_HOMECOMING_ALL and the rest. */
    enum Kind { V2, HOMECOMING, TOP_OF_FEED, TOF_HOMECOMING_ALL }

    /** Stands in for the card's details (LX/06SZ), whose one enum field is its kind. */
    static final class Details {
        Kind kind;
        String title = "You've seen the latest posts from accounts you follow";

        Details(Kind kind) {
            this.kind = kind;
        }
    }

    /** Stands in for the card (LX/02bG), which keeps its details. */
    static final class Card {
        final Details details;

        Card(Kind kind) {
            details = new Details(kind);
        }
    }

    private Function<Object, Object> savedDetails;
    private View row;

    @Before
    public void prepare() {
        savedDetails = CaughtUpCard.detailsOf;
        CaughtUpCard.detailsOf = card -> ((Card) card).details;
        reset();
        row = new FrameLayout(RuntimeEnvironment.getApplication());
        row.setLayoutParams(new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @After
    public void restore() {
        CaughtUpCard.detailsOf = savedDetails;
        reset();
    }

    private static void reset() {
        Settings.HIDE_CAUGHT_UP_CARD.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static String report() {
        return HookStatus.report().toString();
    }

    @Test
    public void offToStartTheRowStays() {
        assertFalse("starts off", Settings.HIDE_CAUGHT_UP_CARD.defaultValue);
        CaughtUpCard.bind(row, new Card(Kind.V2));
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertFalse(report(), report().contains(CaughtUpCard.COUNT_FOLDED));
    }

    @Test
    public void onTheCardAfterTheFollowedPostsFoldsAndComesBackWhenTurnedOff() {
        Settings.HIDE_CAUGHT_UP_CARD.save(true);
        CaughtUpCard.bind(row, new Card(Kind.V2));
        assertEquals("folded to no height", 0, row.getLayoutParams().height);
        assertEquals(View.GONE, row.getVisibility());
        CaughtUpCard.bind(row, new Card(Kind.HOMECOMING));
        assertEquals(0, row.getLayoutParams().height);
        assertTrue(report(), report().contains(CaughtUpCard.COUNT_FOLDED + " 2"));

        Settings.HIDE_CAUGHT_UP_CARD.save(false);
        CaughtUpCard.bind(row, new Card(Kind.V2));
        assertEquals("its own height back", ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
        assertEquals(View.VISIBLE, row.getVisibility());
    }

    @Test
    public void aTopOfFeedCardKeepsItsRowSoHomeIsNeverBlank() {
        Settings.HIDE_CAUGHT_UP_CARD.save(true);
        CaughtUpCard.bind(row, new Card(Kind.TOP_OF_FEED));
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
        assertEquals(View.VISIBLE, row.getVisibility());
        CaughtUpCard.bind(row, new Card(Kind.TOF_HOMECOMING_ALL));
        assertEquals(View.VISIBLE, row.getVisibility());
        assertTrue(report(), report().contains(CaughtUpCard.COUNT_KEPT_TOP + " 2"));

        // A recycled row that held a folded card unfolds for a top of feed one.
        CaughtUpCard.bind(row, new Card(Kind.V2));
        assertEquals(0, row.getLayoutParams().height);
        CaughtUpCard.bind(row, new Card(Kind.TOP_OF_FEED));
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
        assertEquals(View.VISIBLE, row.getVisibility());
    }

    @Test
    public void pausedTheRowStays() {
        Settings.HIDE_CAUGHT_UP_CARD.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        CaughtUpCard.bind(row, new Card(Kind.V2));
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
    }

    @Test
    public void aRowWithoutParamsGetsOnesTheListCopiesTheHeightFrom() {
        Settings.HIDE_CAUGHT_UP_CARD.save(true);
        View bare = new FrameLayout(RuntimeEnvironment.getApplication());
        CaughtUpCard.bind(bare, new Card(Kind.V2));
        assertEquals(0, bare.getLayoutParams().height);
        assertEquals(View.GONE, bare.getVisibility());
    }

    @Test
    public void aCardWhoseDetailsCantBeReadFoldsAndNothingThrows() {
        Settings.HIDE_CAUGHT_UP_CARD.save(true);
        CaughtUpCard.detailsOf = card -> null;
        CaughtUpCard.bind(row, new Card(Kind.TOP_OF_FEED));
        assertEquals(View.GONE, row.getVisibility());
        CaughtUpCard.bind(row, null);
        assertEquals("no card, nothing folded", View.VISIBLE, row.getVisibility());

        CaughtUpCard.detailsOf = card -> {
            throw new IllegalStateException("moved");
        };
        CaughtUpCard.bind(null, new Card(Kind.V2));
        CaughtUpCard.bind(row, new Card(Kind.V2));
        assertEquals("a failure leaves the row as Instagram drew it", View.VISIBLE, row.getVisibility());
    }
}
