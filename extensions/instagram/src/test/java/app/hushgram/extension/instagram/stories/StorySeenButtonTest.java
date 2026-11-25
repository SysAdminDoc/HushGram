/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Mark as seen button in a story's header: where it goes, what a tap does, and when it's hidden. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StorySeenButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stories of someone else's, account 905: the account signed in is {@link StoryMarksTest#ME}. */
    private static final String STORY = "111_905";
    private static final String OTHER = "222_905";
    /** A story of the account signed in, as on your own story or highlight. */
    private static final String MINE = "333_" + StoryMarksTest.ME;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private final AtomicBoolean on = new AtomicBoolean(true);
    private final BooleanSupplier switches = on::get;
    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final StoryMarksTest.Batches batches = new StoryMarksTest.Batches();
    /** The account signed in. In these tests a session, and the store sending for it, are its user ID. */
    private final Object session = StoryMarksTest.ME;
    private final List<Object> sends = new ArrayList<>();
    private final List<Object> sentBatches = new ArrayList<>();
    private StoryMarks marks;
    private Context context;
    private int rowId;
    private int menuId;
    private int stubId;

    /** Instagram's send, through the hook, as a tap starts it: an empty batch the hook fills with what's marked. */
    private final StorySeenButton.Sender sender = account -> {
        sends.add(account);
        sentBatches.add(StorySeen.toSend(account, new StoryMarksTest.Batch(), batches, () -> true, () -> true, marks, StorySeen.COUNTED));
    };

    /** A story's view holder: its root, the header's row of buttons and what Instagram keeps in the row. */
    private final class Header {
        final FrameLayout root = new FrameLayout(context);
        final LinearLayout row = new LinearLayout(context);
        final View follow = new View(context);
        final View menu;

        Header(boolean hasRow, String menuKind) {
            row.setId(rowId);
            follow.setId(View.generateViewId());
            row.addView(follow);
            if ("stub".equals(menuKind)) {
                menu = new ViewStub(context);
                menu.setId(stubId);
                row.addView(menu);
            } else if ("menu".equals(menuKind)) {
                menu = new ImageView(context);
                menu.setId(menuId);
                row.addView(menu);
            } else {
                menu = null;
            }
            if (hasRow) root.addView(row);
        }

        Header() {
            this(true, "menu");
        }

        ImageView button() {
            ImageView found = null;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof StorySeenButton.Eye) {
                    assertNull("one button per header", found);
                    found = (ImageView) child;
                }
            }
            return found;
        }
    }

    private static StorySeenButton.Reader reader(String id) {
        return new StorySeenButton.Reader() {
            @Override
            public String storyId(Object item) {
                return id;
            }

            @Override
            public View itemView(Object holder) {
                return ((Header) holder).root;
            }

            @Override
            public String account(Object session) {
                return session instanceof String ? (String) session : null;
            }
        };
    }

    @Before
    public void prepare() {
        context = RuntimeEnvironment.getApplication();
        StorySeenButton.resetForTests();
        rowId = View.generateViewId();
        menuId = View.generateViewId();
        stubId = View.generateViewId();
        StorySeenButton.putIdForTests(context, StorySeenButton.CONTAINER, rowId);
        StorySeenButton.putIdForTests(context, StorySeenButton.MENU, menuId);
        StorySeenButton.putIdForTests(context, StorySeenButton.MENU_STUB, stubId);
        marks = new StoryMarks(now::get);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
    }

    @After
    public void restore() {
        StorySeenButton.resetForTests();
        StoryButtonRows.resetForTests();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private void bind(Header header, String id) {
        bind(header, id, switches);
    }

    private void bind(Header header, String id, BooleanSupplier switchedOn) {
        StorySeenButton.bind(session, new Object(), header, reader(id), switchedOn, marks, sender);
    }

    private static StoryMarks.State drawn(ImageView button) {
        return ((StorySeenButton.Eye) button.getDrawable()).state();
    }

    /** The button goes before the three-dot menu, or its stub, or last, and says what it does. */
    @Test
    public void theButtonGoesBeforeTheMenuAndSaysWhatItDoes() {
        Header header = new Header();
        bind(header, STORY);
        ImageView button = header.button();
        assertNotNull(button);
        assertEquals(1, header.row.indexOfChild(button));
        assertEquals(2, header.row.indexOfChild(header.menu));
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals("Mark as seen", button.getContentDescription().toString());
        assertEquals(StoryMarks.State.UNMARKED, drawn(button));
        assertTrue(button.isClickable());
        assertTrue(button.isEnabled());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, button.getImportantForAccessibility());
        // A 48dp target that takes 40dp of the row's height, so the header is no taller for it.
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        assertEquals(Math.round(48 * density), params.width);
        assertEquals(Math.round(48 * density), params.height);
        assertEquals(Math.round(40 * density), params.height + params.topMargin + params.bottomMargin);

        Header stubbed = new Header(true, "stub");
        bind(stubbed, STORY);
        assertEquals(1, stubbed.row.indexOfChild(stubbed.button()));

        Header bare = new Header(true, "none");
        bind(bare, STORY);
        assertEquals(1, bare.row.indexOfChild(bare.button()));
        assertEquals(2, bare.row.getChildCount());
        assertTrue(HookStatus.missing(FamilyNames.STORY_SEEN).toString(), HookStatus.missing(FamilyNames.STORY_SEEN).isEmpty());
    }

    /** By the thumb (#647), the eye goes before Like in the bottom row, still marks, and goes back before the menu when that's off. */
    @Test
    public void byTheThumbTheEyeGoesBeforeLikeAndBackUpWhenOff() {
        int footerId = View.generateViewId();
        int likeId = View.generateViewId();
        StoryButtonRows.putIdForTests(context, StoryButtonRows.FOOTER, footerId);
        StoryButtonRows.putIdForTests(context, StoryButtonRows.LIKE, likeId);
        Header header = new Header();
        LinearLayout bottom = new LinearLayout(context);
        bottom.setId(footerId);
        View like = new View(context);
        like.setId(likeId);
        bottom.addView(like);
        bottom.addView(new View(context));
        header.root.addView(bottom);
        AtomicBoolean thumb = new AtomicBoolean(true);
        StorySeenButton.bind(session, new Object(), header, reader(STORY), switches, thumb::get, marks, sender);
        assertNull(header.button());
        assertEquals(3, bottom.getChildCount());
        ImageView button = (ImageView) bottom.getChildAt(0);
        assertTrue(button.getDrawable() instanceof StorySeenButton.Eye);
        assertSame(like, bottom.getChildAt(1));
        button.performClick();
        assertEquals(StoryMarks.State.MARKED, drawn(button));
        assertTrue(sends.isEmpty());

        thumb.set(false);
        StorySeenButton.bind(session, new Object(), header, reader(STORY), switches, thumb::get, marks, sender);
        assertSame(button, header.button());
        assertEquals(1, header.row.indexOfChild(button));
        assertEquals(2, bottom.getChildCount());
        assertEquals(StoryMarks.State.MARKED, drawn(button));
    }

    /**
     * Your own story or highlight (#850), whose id names the account signed in as its owner, gets no
     * eye, and a recycled header that held one for someone else's story hides it and forgets it.
     */
    @Test
    public void yourOwnStoryOrHighlightGetsNoEye() {
        Header header = new Header();
        bind(header, MINE);
        assertNull(header.button());
        assertEquals(2, header.row.getChildCount());

        bind(header, STORY);
        ImageView button = header.button();
        assertEquals(View.VISIBLE, button.getVisibility());
        bind(header, MINE);
        assertEquals(View.GONE, button.getVisibility());
        assertNull("it forgot the last story", button.getTag());
        StorySeenButton.tapped(button, switches, marks, sender);
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "333"));
        assertTrue(sends.isEmpty());
    }

    /**
     * By the thumb, a bottom row that shows with Like and Share hidden in it, as Instagram leaves it over
     * your own toolbar (#850), isn't a place for the eye: it stays before the menu at the top.
     */
    @Test
    public void byTheThumbABottomRowWithoutLikeOrShareLeavesTheEyeAtTheTop() {
        int footerId = View.generateViewId();
        int likeId = View.generateViewId();
        int shareId = View.generateViewId();
        StoryButtonRows.putIdForTests(context, StoryButtonRows.FOOTER, footerId);
        StoryButtonRows.putIdForTests(context, StoryButtonRows.LIKE, likeId);
        StoryButtonRows.putIdForTests(context, StoryButtonRows.SHARE, shareId);
        Header header = new Header();
        LinearLayout bottom = new LinearLayout(context);
        bottom.setId(footerId);
        View like = new View(context);
        like.setId(likeId);
        like.setVisibility(View.GONE);
        View share = new View(context);
        share.setId(shareId);
        share.setVisibility(View.GONE);
        bottom.addView(like);
        bottom.addView(share);
        header.root.addView(bottom);
        StorySeenButton.bind(session, new Object(), header, reader(STORY), switches, () -> true, marks, sender);
        ImageView button = header.button();
        assertNotNull(button);
        assertEquals(1, header.row.indexOfChild(button));
        assertEquals(2, bottom.getChildCount());

        share.setVisibility(View.VISIBLE);
        StorySeenButton.bind(session, new Object(), header, reader(STORY), switches, () -> true, marks, sender);
        assertNull("Share showing, it goes down", header.button());
        assertSame(button, bottom.getChildAt(0));
    }

    /** A tap marks the story and the eye turns solid; a second takes the mark back. Nothing is sent from the tap. */
    @Test
    public void aTapMarksTheStoryAndASecondTapUndoesIt() {
        Header header = new Header();
        bind(header, STORY);
        ImageView button = header.button();

        assertTrue(button.performClick());
        assertEquals(StoryMarks.State.MARKED, marks.state(StoryMarksTest.ME, "111"));
        assertEquals(StoryMarks.State.MARKED, drawn(button));
        assertEquals("Marked as seen. Tap again to undo.", button.getContentDescription().toString());
        assertTrue("the story's batch hasn't gone yet, so nothing is sent now", sends.isEmpty());

        assertTrue(button.performClick());
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "111"));
        assertEquals(StoryMarks.State.UNMARKED, drawn(button));
        assertEquals("Mark as seen", button.getContentDescription().toString());
        assertTrue(sends.isEmpty());
    }

    /** A story whose batch was held back before the tap goes straight away, through Instagram's send, and alone. */
    @Test
    public void aTapOnAStoryAlreadyHeldBackSendsItRightAway() {
        assertNull(StorySeen.toSend(StoryMarksTest.ME, new StoryMarksTest.Batch().with("111_905_905", "222_905_905"), batches, () -> true, () -> true, marks, StorySeen.COUNTED));
        Header header = new Header();
        bind(header, STORY);
        ImageView button = header.button();

        assertTrue(button.performClick());
        assertEquals(1, sends.size());
        assertSame("sent for the account the header was bound with", session, sends.get(0));
        StoryMarksTest.Batch sent = (StoryMarksTest.Batch) sentBatches.get(0);
        assertNotNull(sent);
        assertEquals(1, sent.stories.size());
        assertTrue(sent.stories.containsKey("111_905_905"));
        assertEquals(StoryMarks.State.SENT, drawn(button));
        assertEquals("Marked as seen and sent", button.getContentDescription().toString());
        assertFalse("a sent story can't be taken back", button.isEnabled());
        assertEquals(0.6f, button.getAlpha(), 0.001f);
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "222"));
    }

    /** Holders are recycled: the one button follows the story the header shows now. */
    @Test
    public void aRecycledHeaderFollowsItsNewStory() {
        Header header = new Header();
        bind(header, STORY);
        header.button().performClick();
        bind(header, OTHER);
        ImageView button = header.button();
        assertEquals(StoryMarks.State.UNMARKED, drawn(button));
        assertEquals("222", ((StorySeenButton.Bound) button.getTag()).story);
        bind(header, STORY);
        assertSame(button, header.button());
        assertEquals(StoryMarks.State.MARKED, drawn(button));
        assertEquals(3, header.row.getChildCount());
    }

    /** A story Instagram's id doesn't name as a post, such as a live video, gets no button. */
    @Test
    public void aStoryThatIsntAPostHasNoButton() {
        Header header = new Header();
        bind(header, "live_123");
        assertNull(header.button());
        bind(header, STORY);
        bind(header, "live_123");
        assertEquals(View.GONE, header.button().getVisibility());
    }

    /**
     * With either switch off, HushGram paused or the settings not read, there's no button: none is
     * added, and one already in a header is hidden. A tap on a button after the switch went off
     * marks nothing.
     */
    @Test
    public void offPausedOrUnreadyShowsNoButton() {
        Header untouched = new Header();
        bind(untouched, STORY, () -> false);
        assertEquals(2, untouched.row.getChildCount());

        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        Settings.MARK_STORIES_SEEN.save(false);
        bind(untouched, STORY, StorySeenButton::switchedOn);
        assertNull("the button's switch starts off", untouched.button());

        Settings.MARK_STORIES_SEEN.save(true);
        Header header = new Header();
        bind(header, STORY, StorySeenButton::switchedOn);
        ImageView button = header.button();
        assertEquals(View.VISIBLE, button.getVisibility());

        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        bind(header, STORY, StorySeenButton::switchedOn);
        assertEquals("anonymous viewing off", View.GONE, button.getVisibility());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        bind(header, STORY, StorySeenButton::switchedOn);
        assertEquals(View.VISIBLE, button.getVisibility());

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        bind(header, STORY, StorySeenButton::switchedOn);
        assertEquals("paused", View.GONE, button.getVisibility());
        PauseForTests.resume();
        bind(header, STORY, StorySeenButton::switchedOn);
        assertEquals(View.VISIBLE, button.getVisibility());

        SettingsContextRule.withoutContext(() -> bind(header, STORY, StorySeenButton::switchedOn));
        assertEquals("settings not read", View.GONE, button.getVisibility());

        Header tapped = new Header();
        bind(tapped, STORY);
        on.set(false);
        tapped.button().performClick();
        assertEquals(View.GONE, tapped.button().getVisibility());
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "111"));
        assertTrue(sends.isEmpty());
    }

    /** A header without Instagram's row of buttons is reported while the button is wanted, and cleared once one has it. */
    @Test
    public void aHeaderWithoutTheRowIsReported() {
        Header missing = new Header(false, "menu");
        bind(missing, STORY);
        String report = HookStatus.missing(FamilyNames.STORY_SEEN).toString();
        assertTrue(report, report.contains(StorySeenButton.CONTAINER));

        Header header = new Header();
        bind(header, STORY);
        assertNotNull(header.button());
        assertTrue(HookStatus.missing(FamilyNames.STORY_SEEN).toString(), HookStatus.missing(FamilyNames.STORY_SEEN).isEmpty());
    }

    /** A reader or switch that throws is reported, and the header stays as Instagram drew it. */
    @Test
    public void aThrowingReaderOrSwitchIsReportedAndLeavesTheHeader() {
        Header header = new Header();
        bind(header, STORY, THROWS);
        assertEquals(2, header.row.getChildCount());
        assertReported();

        HookStatus.clear();
        StorySeenButton.bind(session, new Object(), header, new StorySeenButton.Reader() {
            @Override
            public String storyId(Object item) {
                throw new UnsupportedOperationException("no id");
            }

            @Override
            public View itemView(Object holder) {
                return header.root;
            }

            @Override
            public String account(Object session) {
                return StoryMarksTest.ME;
            }
        }, switches, marks, sender);
        assertNull(header.button());
        assertReported();

        HookStatus.clear();
        bind(header, STORY);
        ImageView button = header.button();
        StorySeenButton.tapped(button, THROWS, marks, sender);
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "111"));
        assertReported();
    }

    /** A story marked while signed in to one account is never sent in another account's batch. */
    @Test
    public void aMarkOnOneAccountIsNeverSentForAnother() {
        Header header = new Header();
        bind(header, STORY);
        header.button().performClick();
        assertEquals(StoryMarks.State.MARKED, marks.state(StoryMarksTest.ME, "111"));
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.OTHER, "111"));

        StoryMarksTest.Batch theirs = new StoryMarksTest.Batch().with("111_905_905");
        assertNull("the other account's views are held back, the marked story too",
                StorySeen.toSend(StoryMarksTest.OTHER, theirs, batches, () -> true, () -> true, marks, StorySeen.COUNTED));
        StorySeenButton.bind(StoryMarksTest.OTHER, new Object(), header, reader(STORY), switches, marks, sender);
        assertEquals("the other account's button shows the story unmarked", StoryMarks.State.UNMARKED, drawn(header.button()));

        StoryMarksTest.Batch mine = new StoryMarksTest.Batch().with("111_905_905");
        StoryMarksTest.Batch sent = (StoryMarksTest.Batch) StorySeen.toSend(StoryMarksTest.ME, mine, batches, () -> true, () -> true, marks, StorySeen.COUNTED);
        assertNotNull("this account's send carries it", sent);
        assertTrue(sent.stories.containsKey("111_905_905"));
    }

    /** Without the account's user ID a story can't be marked, so it has no button. */
    @Test
    public void withoutTheAccountThereIsNoButton() {
        Header header = new Header();
        bind(header, STORY);
        ImageView button = header.button();
        StorySeenButton.bind(new Object(), new Object(), header, reader(STORY), switches, marks, sender);
        assertEquals(View.GONE, button.getVisibility());
        assertNull("it forgot its story", button.getTag());
    }

    /** A hidden button forgets its story: even a tap that reaches it marks nothing. */
    @Test
    public void aHiddenButtonForgetsItsStory() {
        Header header = new Header();
        bind(header, STORY);
        ImageView button = header.button();
        bind(header, "live_123");
        assertEquals(View.GONE, button.getVisibility());
        assertNull(button.getTag());
        StorySeenButton.tapped(button, switches, marks, sender);
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "111"));

        bind(header, STORY);
        on.set(false);
        button.performClick();
        assertEquals(View.GONE, button.getVisibility());
        assertNull(button.getTag());
        on.set(true);
        StorySeenButton.tapped(button, switches, marks, sender);
        assertEquals("the tap after it was hidden marks nothing", StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "111"));
        assertTrue(sends.isEmpty());
    }

    private static void assertReported() {
        String report = HookStatus.missing(FamilyNames.STORY_SEEN).toString();
        assertTrue(report, report.contains("'" + StorySeenButton.HOOK + "'"));
    }
}
