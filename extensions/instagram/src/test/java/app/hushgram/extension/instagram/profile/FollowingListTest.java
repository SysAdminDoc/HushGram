/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the Following list hook does to a row Instagram has just bound: Doesn't follow you on your
 * own Following list for an account that doesn't follow you back, and Instagram's row everywhere else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FollowingListTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String MARK = "Doesn't follow you";
    private static final String ME = "1001";
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** Instagram's kinds of list as the binder holds them: enum constants, of which only the name is read. */
    enum Kind { FOLLOWING, FOLLOWING_SIMPLIFIED, FOLLOWERS, MUTUAL, SELF_FOLLOWING }

    private final Object notFollowing = new Object();
    private final Object following = new Object();
    private final Object unknown = new Object();
    private final Object binder = new Object();
    private Context context;

    @Before
    public void prepare() {
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.MARK_FOLLOWING_LIST.save(true);
        Settings.FOLLOWING_NOT_BACK_FIRST.save(false);
        HookStatus.clear();
        FollowingList.forgetAnswers();
    }

    @After
    public void restore() {
        FollowingList.forgetAnswers();
        Settings.MARK_FOLLOWING_LIST.resetToDefault();
        Settings.FOLLOWING_NOT_BACK_FIRST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On your own Following list, only an account Instagram says doesn't follow you gets the mark, after its name. */
    @Test
    public void ownFollowingListMarksOnlyWhoDoesNotFollowBack() {
        for (Kind kind : new Kind[] {Kind.FOLLOWING, Kind.FOLLOWING_SIMPLIFIED}) {
            Reader reader = new Reader(kind, ME, ME);
            Row ana = new Row();
            Row ben = new Row();

            ana.bind(notFollowing, "Ana", reader, ON);
            ben.bind(following, "Ben", reader, ON);

            assertEquals(kind.name(), "Ana" + FriendshipStatus.SEPARATOR + MARK, ana.text());
            assertEquals(View.VISIBLE, ana.name.getVisibility());
            assertEquals(kind.name(), "Ben", ben.text());
            assertEquals(View.VISIBLE, ben.name.getVisibility());
        }
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.FRIENDSHIP_STATUS));
    }

    /** An account with no name has its name line hidden by Instagram, and the mark takes the line on its own. */
    @Test
    public void aRowWithNoNameShowsTheMarkOnItsOwn() {
        Row row = new Row();

        row.bind(notFollowing, null, new Reader(Kind.FOLLOWING, ME, ME), ON);

        assertEquals(MARK, row.text());
        assertEquals(View.VISIBLE, row.name.getVisibility());
    }

    /** Before Instagram has checked, the row says nothing, whether it has a name or not. */
    @Test
    public void anUnknownAnswerStaysUnmarked() {
        Reader reader = new Reader(Kind.FOLLOWING, ME, ME);
        Row named = new Row();
        Row nameless = new Row();

        named.bind(unknown, "Cy", reader, ON);
        nameless.bind(unknown, null, reader, ON);

        assertEquals("Cy", named.text());
        assertEquals(View.VISIBLE, named.name.getVisibility());
        assertEquals(View.GONE, nameless.name.getVisibility());
        assertFalse(nameless.text().contains(MARK));
    }

    /**
     * #40: Instagram's cached status says no, from a feed or a reel, but the server hasn't answered a
     * request that asked. The row stays as Instagram drew it until it has.
     */
    @Test
    public void aCachedNoTheServerHasNotAnsweredStaysUnmarked() {
        Reader reader = new Reader(Kind.FOLLOWING, ME, ME);
        reader.answers.put(notFollowing, null);
        Row row = new Row();

        row.bind(notFollowing, "Ana", reader, ON);
        assertEquals("Ana", row.text());

        reader.answers.put(notFollowing, false);
        row.bind(notFollowing, "Ana", reader, ON);
        assertEquals("Ana" + FriendshipStatus.SEPARATOR + MARK, row.text());
    }

    /** A yes on the account's own status, from opening its profile say, overrules the server's earlier no. */
    @Test
    public void aLaterYesOverrulesAnAnsweredNo() {
        Reader reader = new Reader(Kind.FOLLOWING, ME, ME);
        reader.follows.put(notFollowing, true);
        Row row = new Row();

        row.bind(notFollowing, "Ana", reader, ON);

        assertEquals("Ana", row.text());
    }

    /** Answers are kept for the account signed in, only when the server said either way, and no more than the cap. */
    @Test
    public void answersAreKeptPerSignedInAccount() {
        FollowingList.remember(ME, "7", false);
        FollowingList.remember(ME, "8", true);
        FollowingList.remember(ME, "9", null);
        FollowingList.remember(null, "7", true);
        FollowingList.remember(ME, "", true);

        assertEquals(Boolean.FALSE, FollowingList.answer(ME, "7"));
        assertEquals(Boolean.TRUE, FollowingList.answer(ME, "8"));
        assertNull(FollowingList.answer(ME, "9"));
        assertNull(FollowingList.answer("2002", "7"));
        assertNull(FollowingList.answer(ME, null));

        FollowingList.remember(ME, "7", true);
        assertEquals(Boolean.TRUE, FollowingList.answer(ME, "7"));

        for (int i = 0; i < FollowingList.MAX_ANSWERS; i++) FollowingList.remember(ME, "u" + i, false);
        assertNull("the oldest answer goes first", FollowingList.answer(ME, "8"));
        assertEquals(Boolean.FALSE, FollowingList.answer(ME, "u" + (FollowingList.MAX_ANSWERS - 1)));
    }

    /** Only your own Following list, with the switch on, asks about rows Instagram's cache says it knows. */
    @Test
    public void ownFollowingListAsksAboutEveryRow() {
        Object friendship = new Object();
        Object[][] lists = {
                {Kind.FOLLOWING, ME, ME, null},
                {Kind.FOLLOWING_SIMPLIFIED, ME, ME, null},
                {Kind.FOLLOWERS, ME, ME, friendship},
                {Kind.FOLLOWING, "2002", ME, friendship},
                {Kind.FOLLOWING, null, ME, friendship},
                {Kind.FOLLOWING, ME, null, friendship},
                {null, ME, ME, friendship},
        };
        for (Object[] list : lists) {
            Object answered = FollowingList.known(friendship, binder, l -> list[0], l -> list[1], l -> list[2], ON);
            assertEquals(java.util.Arrays.toString(list), list[3], answered);
        }
        assertEquals(friendship, FollowingList.known(friendship, binder, l -> Kind.FOLLOWING, l -> ME, l -> ME, OFF));
        assertNull("a row with nothing cached is asked about anyway", FollowingList.known(null, binder, l -> Kind.FOLLOWING, l -> ME, l -> ME, OFF));

        assertEquals(friendship, FollowingList.known(friendship, binder, l -> { throw new IllegalStateException("gone"); },
                l -> ME, l -> ME, ON));
        String missing = HookStatus.missing(FamilyNames.FRIENDSHIP_STATUS).toString();
        assertTrue(missing, missing.contains("'" + FollowingList.REFETCH + "'"));
    }

    /** Followers, someone else's Following list, any other list and a list that can't be told apart stay as they are. */
    @Test
    public void otherListsStayUnmarked() {
        Reader[] others = {
                new Reader(Kind.FOLLOWERS, ME, ME),
                new Reader(Kind.MUTUAL, ME, ME),
                new Reader(Kind.SELF_FOLLOWING, ME, ME),
                new Reader(Kind.FOLLOWING, "2002", ME),
                new Reader(Kind.FOLLOWING_SIMPLIFIED, "2002", ME),
                new Reader(Kind.FOLLOWING, null, ME),
                new Reader(Kind.FOLLOWING, ME, null),
                new Reader(Kind.FOLLOWING, "", ""),
                new Reader(null, ME, ME),
                new Reader("FOLLOWING", ME, ME),
        };
        for (Reader reader : others) {
            Row named = new Row();
            Row nameless = new Row();

            named.bind(notFollowing, "Ana", reader, ON);
            nameless.bind(notFollowing, null, reader, ON);

            assertEquals(reader.toString(), "Ana", named.text());
            assertEquals(reader.toString(), View.GONE, nameless.name.getVisibility());
        }
    }

    /** Off, paused or before the settings are read, the row is Instagram's, and once on again it's marked. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsRow() {
        Reader reader = new Reader(Kind.FOLLOWING, ME, ME);

        Settings.MARK_FOLLOWING_LIST.save(false);
        Row off = new Row();
        off.bind(notFollowing, "Ana", reader, FollowingList::switchedOn);
        assertEquals("Ana", off.text());
        Settings.MARK_FOLLOWING_LIST.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Row paused = new Row();
        paused.bind(notFollowing, null, reader, FollowingList::switchedOn);
        assertEquals(View.GONE, paused.name.getVisibility());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        Row unready = new Row();
        SettingsContextRule.withoutContext(() -> unready.bind(notFollowing, "Ana", reader, FollowingList::switchedOn));
        assertEquals("Ana", unready.text());

        Row on = new Row();
        on.bind(notFollowing, "Ana", reader, FollowingList::switchedOn);
        assertEquals("Ana" + FriendshipStatus.SEPARATOR + MARK, on.text());
    }

    /** A switch, a reader or a name line that throws leaves the row as Instagram drew it, and the hook says so. */
    @Test
    public void aThrowingReaderOrSwitchKeepsTheRowAndIsReported() {
        Row switched = new Row();
        switched.bind(notFollowing, "Ana", new Reader(Kind.FOLLOWING, ME, ME), THROWS);
        assertEquals("Ana", switched.text());
        assertReported(IllegalStateException.class);

        Reader broken = new Reader(Kind.FOLLOWING, ME, ME) {
            @Override
            public Boolean followedBy(Object user) {
                throw new UnsupportedOperationException("no friendship status");
            }
        };
        Row read = new Row();
        read.bind(notFollowing, "Ana", broken, ON);
        assertEquals("Ana", read.text());
        assertReported(UnsupportedOperationException.class);

        TextView refusing = new TextView(context) {
            @Override
            public void setText(CharSequence text, BufferType type) {
                if (text != null && text.toString().contains(MARK)) throw new IllegalArgumentException("detached");
                super.setText(text, type);
            }
        };
        Row written = new Row(refusing);
        written.bind(notFollowing, null, new Reader(Kind.FOLLOWING, ME, ME), ON);
        assertEquals(View.GONE, written.name.getVisibility());
        assertFalse(written.text().contains(MARK));
        assertReported(IllegalArgumentException.class);
    }

    /** The hook's failure is on the family's line, naming the row hook and what it threw. Cleared for the next. */
    private static void assertReported(Class<? extends Throwable> thrown) {
        String missing = HookStatus.missing(FamilyNames.FRIENDSHIP_STATUS).toString();
        assertTrue(missing, missing.contains("'" + FollowingList.ROW + "'"));
        assertTrue(missing, missing.contains(thrown.getName()));
        HookStatus.clear();
    }

    /**
     * A recycled row brings its mark along. Instagram writes the name line again on each bind, and a
     * mark it didn't write over is taken back before the row is looked at for its new account.
     */
    @Test
    public void aRecycledRowLosesItsStaleMark() {
        Reader reader = new Reader(Kind.FOLLOWING, ME, ME);
        Row row = new Row();

        row.bind(notFollowing, "Ana", reader, ON);
        row.bind(following, "Ben", reader, ON);
        assertEquals("Ben", row.text());

        row.bind(notFollowing, null, reader, ON);
        assertEquals(MARK, row.text());
        row.bind(following, null, reader, ON);
        assertEquals(View.GONE, row.name.getVisibility());
        assertFalse(row.text(), row.text().contains(MARK));

        // Switched off with nothing written over the mark: the line goes back as it was.
        row.bind(notFollowing, "Ana", reader, ON);
        FollowingList.row(binder, row.view, notFollowing, reader, OFF);
        assertEquals("Ana", row.text());
        assertEquals(View.VISIBLE, row.name.getVisibility());

        row.bind(notFollowing, null, reader, ON);
        assertEquals(View.VISIBLE, row.name.getVisibility());
        FollowingList.row(binder, row.view, notFollowing, reader, OFF);
        assertEquals(View.GONE, row.name.getVisibility());
        assertFalse(row.text(), row.text().contains(MARK));
    }

    /** Unpatched, the stubs know nothing, so the hook leaves every row alone and nothing it's handed makes it throw. */
    @Test
    public void theHookNeverThrows() {
        Row row = new Row();
        row.name.setText("Ana");

        FollowingList.row(null, 0, null, null);
        FollowingList.row(new Object(), 0, new View(context), new Object());
        FollowingList.row(new Object(), 3, row.view, notFollowing);
        Object friendship = new Object();
        assertEquals(friendship, FollowingList.known(friendship, new Object()));
        FollowingList.answered(new Object(), new Object(), new Object());
        FollowingList.answered(null, null, null);
        Object loaded = new ArrayList<>();
        assertSame(loaded, FollowingList.ordered(loaded, new Object()));
        assertSame(loaded, FollowingList.ordered(loaded, null));
        assertNull(FollowingList.orderBinder(new Object()));
        FollowingList.rebuildRows(new Object(), false, false);

        assertEquals("Ana", row.text());
        assertNull(FollowingList.listKind(binder));
        assertNull(FollowingList.fetchKind(binder));
        assertNull(FollowingList.statusFollowedBy(new Object()));
        assertFalse(FollowingList.ownFollowingList(Kind.FOLLOWING, null, null));
    }

    /**
     * #40: on your own Following list, the accounts the server said don't follow you back come first,
     * each group in Instagram's order, and an account not yet asked about stays in the second group.
     */
    @Test
    public void ownFollowingListPutsWhoDoesNotFollowBackFirstAndKeepsTheOrder() {
        Object second = new Object();
        Object third = new Object();
        List<Object> loaded = Arrays.asList(unknown, following, notFollowing, second, third);

        for (Kind kind : new Kind[] {Kind.FOLLOWING, Kind.FOLLOWING_SIMPLIFIED}) {
            Reader reader = new Reader(kind, ME, ME);
            reader.answers.put(second, false);
            reader.answers.put(third, false);
            Object ordered = FollowingList.ordered(loaded, binder, binder, reader, ON);

            assertEquals(kind.name(), Arrays.asList(notFollowing, second, third, unknown, following), ordered);
        }
        assertEquals("Instagram's list is left as it was", Arrays.asList(unknown, following, notFollowing, second, third), loaded);
    }

    /** Nothing to move, or already in order: Instagram's own list comes back, not a copy. */
    @Test
    public void aListAlreadyInOrderComesBackAsItIs() {
        Reader reader = new Reader(Kind.FOLLOWING, ME, ME);
        List<Object> inOrder = Arrays.asList(notFollowing, following, unknown);
        List<Object> nobody = Arrays.asList(following, unknown);

        assertSame(inOrder, FollowingList.ordered(inOrder, binder, binder, reader, ON));
        assertSame(nobody, FollowingList.ordered(nobody, binder, binder, reader, ON));
        List<Object> empty = new ArrayList<>();
        assertSame(empty, FollowingList.ordered(empty, binder, binder, reader, ON));
    }

    /** A yes on the account's own status overrules the server's earlier no, as it does for the mark. */
    @Test
    public void aLaterYesKeepsAnAnsweredNoInTheSecondGroup() {
        Reader reader = new Reader(Kind.FOLLOWING, ME, ME);
        reader.follows.put(notFollowing, true);
        List<Object> loaded = Arrays.asList(following, notFollowing);

        assertSame(loaded, FollowingList.ordered(loaded, binder, binder, reader, ON));
    }

    /** Followers, someone else's list, any other list, a switch that's off and a list that isn't one stay as Instagram has them. */
    @Test
    public void otherListsAndAnOffSwitchKeepInstagramsOrder() {
        List<Object> loaded = Arrays.asList(following, notFollowing);
        Reader[] others = {
                new Reader(Kind.FOLLOWERS, ME, ME),
                new Reader(Kind.MUTUAL, ME, ME),
                new Reader(Kind.FOLLOWING, "2002", ME),
                new Reader(Kind.FOLLOWING, null, ME),
                new Reader(Kind.FOLLOWING, ME, null),
                new Reader(null, ME, ME),
        };
        for (Reader reader : others) assertSame(reader.toString(), loaded, FollowingList.ordered(loaded, binder, binder, reader, ON));

        Reader own = new Reader(Kind.FOLLOWING, ME, ME);
        assertSame(loaded, FollowingList.ordered(loaded, binder, binder, own, OFF));
        assertSame(loaded, FollowingList.ordered(loaded, binder, null, own, ON));
        assertSame(loaded, FollowingList.ordered(loaded, null, binder, own, ON));
        Object notAList = new Object();
        assertSame(notAList, FollowingList.ordered(notAList, binder, binder, own, ON));
    }

    /** A reader or a switch that throws hands Instagram its own list and the hook says so. */
    @Test
    public void aThrowingReaderKeepsTheListAndIsReported() {
        List<Object> loaded = Arrays.asList(following, notFollowing);
        Reader broken = new Reader(Kind.FOLLOWING, ME, ME) {
            @Override
            public Boolean followedBy(Object user) {
                throw new UnsupportedOperationException("no friendship status");
            }
        };

        assertSame(loaded, FollowingList.ordered(loaded, binder, binder, broken, ON));
        String missing = HookStatus.missing(FamilyNames.FRIENDSHIP_STATUS).toString();
        assertTrue(missing, missing.contains("'" + FollowingList.ORDER + "'") && missing.contains(UnsupportedOperationException.class.getName()));
        HookStatus.clear();

        assertSame(loaded, FollowingList.ordered(loaded, binder, binder, new Reader(Kind.FOLLOWING, ME, ME), THROWS));
        assertTrue(HookStatus.missing(FamilyNames.FRIENDSHIP_STATUS).toString().contains(IllegalStateException.class.getName()));
    }

    /** Either switch has the list ask about every row, and neither on or paused leaves it as Instagram asks. */
    @Test
    public void eitherSwitchHasTheListAskAboutEveryRow() {
        Settings.MARK_FOLLOWING_LIST.save(false);
        Settings.FOLLOWING_NOT_BACK_FIRST.save(false);
        assertFalse(FollowingList.asking());
        assertFalse(FollowingList.orderSwitchedOn());

        Settings.FOLLOWING_NOT_BACK_FIRST.save(true);
        assertTrue(FollowingList.asking());
        assertTrue(FollowingList.orderSwitchedOn());
        assertFalse("the mark has its own switch", FollowingList.switchedOn());

        Settings.FOLLOWING_NOT_BACK_FIRST.save(false);
        Settings.MARK_FOLLOWING_LIST.save(true);
        assertTrue(FollowingList.asking());
        assertFalse(FollowingList.orderSwitchedOn());

        Settings.FOLLOWING_NOT_BACK_FIRST.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(FollowingList.asking());
        assertFalse(FollowingList.orderSwitchedOn());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(FollowingList.asking()));
    }

    /** A row of the list: its view, whose tag is the holder, and the holder's name line. */
    private final class Row {
        final View view = new View(context);
        final TextView name;

        Row() {
            this(new TextView(context));
        }

        Row(TextView name) {
            this.name = name;
            view.setTag(new Holder(name));
        }

        /** What Instagram's binder does with the name line, then the hook. */
        void bind(Object user, String fullName, Reader reader, BooleanSupplier on) {
            if (fullName != null) {
                name.setText(fullName);
                name.setVisibility(View.VISIBLE);
            } else {
                name.setVisibility(View.GONE);
            }
            FollowingList.row(binder, view, user, reader, on);
        }

        String text() {
            return name.getText().toString();
        }
    }

    private static final class Holder {
        final TextView name;

        Holder(TextView name) {
            this.name = name;
        }
    }

    /** Instagram's objects as the patch's stubs would read them. */
    private class Reader implements FollowingList.Reader {
        private final Object kind;
        private final String owner;
        private final String viewer;
        /** What Instagram's cached status says. */
        final Map<Object, Boolean> follows = new HashMap<>();
        /** What the server answered when asked. */
        final Map<Object, Boolean> answers = new HashMap<>();

        Reader(Object kind, String owner, String viewer) {
            this.kind = kind;
            this.owner = owner;
            this.viewer = viewer;
            for (Map<Object, Boolean> said : java.util.Arrays.asList(follows, answers)) {
                said.put(notFollowing, false);
                said.put(following, true);
                said.put(unknown, null);
            }
        }

        @Override
        public Object listKind(Object binder) {
            return kind;
        }

        @Override
        public String listOwnerId(Object binder) {
            return owner;
        }

        @Override
        public String viewerId(Object binder) {
            return viewer;
        }

        @Override
        public Boolean followedBy(Object user) {
            return follows.get(user);
        }

        @Override
        public Boolean answer(String viewer, Object user) {
            return viewer == null ? null : answers.get(user);
        }

        @Override
        public TextView subtitle(Object holder) {
            return ((Holder) holder).name;
        }

        @Override
        public String toString() {
            return kind + " of " + owner + " seen by " + viewer;
        }
    }
}
