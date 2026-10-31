/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ui.Dim;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** See who a story mentions: the pill under the name, the list it opens, and a row opening a profile. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryMentionsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** An account as a stand-in mention holds it. */
    static final class Account {
        final String username;
        final String fullName;
        final String picture;

        Account(String username, String fullName, String picture) {
            this.username = username;
            this.fullName = fullName;
            this.picture = picture;
        }
    }

    /** The page is its media view, the story is its list of accounts, and each mention is its account. */
    static final class Reads implements StoryMentions.Reads {
        boolean throwing;

        @Override public View itemView(Object page) {
            return (View) page;
        }

        @Override public Object media(Object item) {
            if (throwing) throw new IllegalStateException("the story went away");
            return item;
        }

        @Override public List<?> mentions(Object media) {
            return (List<?>) media;
        }

        @Override public Object mentionUser(Object mention) {
            return mention;
        }

        @Override public String username(Object user) {
            return ((Account) user).username;
        }

        @Override public String fullName(Object user) {
            return ((Account) user).fullName;
        }

        @Override public String picture(Object user) {
            return ((Account) user).picture;
        }
    }

    private static final Account ANA = new Account("ana", "Ana Lima", "https://scontent.cdninstagram.com/ana.jpg");
    private static final Account BO = new Account("bo.k", null, null);

    private final Reads reads = new Reads();
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private LinearLayout header;
    private FrameLayout itemView;

    @Before
    public void open() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHOW_STORY_MENTIONS.save(true);
        StoryMentions.resetForTests();
        StoryMentions.readsForTests = reads;
        HookStatus.clear();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setId(View.generateViewId());
        StoryMentions.headerIdForTests = header.getId();
        TextView name = new TextView(activity);
        name.setText("someone");
        header.addView(name);
        itemView = new FrameLayout(activity);
        root.addView(itemView);
        root.addView(header);
        activity.setContentView(root);
    }

    @After
    public void close() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        controller.close();
        StoryMentions.readsForTests = null;
        StoryMentions.picturesForTests = null;
        StoryMentions.headerIdForTests = 0;
        StoryMentions.titleIdForTests = 0;
        StoryMentions.resetForTests();
        Settings.SHOW_STORY_MENTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndIsTheFamilysOnly() {
        Settings.SHOW_STORY_MENTIONS.resetToDefault();
        assertFalse(Settings.SHOW_STORY_MENTIONS.get());
        assertEquals(Collections.singletonList(Settings.SHOW_STORY_MENTIONS), PatchFamily.STORY_MENTIONS.switches);
        assertEquals(FamilyNames.STORY_MENTIONS, PatchFamily.STORY_MENTIONS.patchName);
    }

    @Test
    public void twoMentionsPutAPillUnderTheName() {
        bind(ANA, BO);
        StoryMentions.Pill pill = pill();
        assertNotNull(pill);
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals("2 mentions", pill.getText().toString());
        assertEquals("2 mentions, see who this story mentions", pill.getContentDescription().toString());
        assertEquals("the pill goes after the name", header.getChildCount() - 1, header.indexOfChild(pill));
        assertTrue(HookStatus.missing(FamilyNames.STORY_MENTIONS).toString(), HookStatus.missing(FamilyNames.STORY_MENTIONS).isEmpty());
    }

    @Test
    public void oneMentionIsSingular() {
        bind(ANA);
        assertEquals("1 mention", pill().getText().toString());
    }

    /** The same account twice, or one with no username, counts once or not at all. */
    @Test
    public void repeatsAndBlankUsernamesCountOnce() {
        bind(ANA, ANA, new Account("  ", "No one", null), new Account(null, null, null));
        assertEquals("1 mention", pill().getText().toString());
    }

    @Test
    public void aStoryWithNoMentionsGetsNoPill() {
        bind();
        assertNull(pill());
        StoryMentions.bind(itemView, null);
        idle();
        assertNull(pill());
    }

    /** The page shows a story with mentions and then one without, and the pill goes away. */
    @Test
    public void aLaterStoryWithNoneHidesThePill() {
        bind(ANA, BO);
        StoryMentions.Pill pill = pill();
        bind();
        assertEquals(View.GONE, pill.getVisibility());
        bind(BO);
        assertEquals("the same pill comes back", pill, pill());
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals("1 mention", pill.getText().toString());
    }

    @Test
    public void switchedOffOrPausedThePillHides() {
        bind(ANA);
        StoryMentions.Pill pill = pill();
        Settings.SHOW_STORY_MENTIONS.save(false);
        bind(ANA);
        assertEquals(View.GONE, pill.getVisibility());

        Settings.SHOW_STORY_MENTIONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        bind(ANA);
        assertEquals(View.GONE, pill.getVisibility());
    }

    /** Instagram builds the header after the bind; the pill goes in once it's there. */
    @Test
    public void aHeaderBuiltLateStillGetsThePill() {
        root.removeView(header);
        StoryMentions.bind(itemView, Arrays.asList(ANA, BO));
        idle();
        assertNull(pill());
        root.addView(header);
        shadowOf(Looper.getMainLooper()).idleFor(StoryMentions.TRIES[1], TimeUnit.MILLISECONDS);
        assertNotNull(pill());
        assertEquals("2 mentions", pill().getText().toString());
    }

    /** A page bound again before its header showed gets the newer story's pill, not the older one's. */
    @Test
    public void aNewerBindWins() {
        StoryMentions.bind(itemView, Arrays.asList(ANA, BO));
        StoryMentions.bind(itemView, Collections.singletonList(BO));
        idle();
        assertEquals("1 mention", pill().getText().toString());
    }

    @Test
    public void tappingThePillListsTheAccountsAndARowOpensTheProfile() {
        bind(ANA, BO);
        assertTrue(pill().performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        View content = dialog.getWindow().getDecorView();
        assertNotNull("Ana's full name", find(content, view -> view instanceof TextView && "Ana Lima".contentEquals(((TextView) view).getText())));
        assertNotNull("Ana's username", find(content, view -> view instanceof TextView && "@ana".contentEquals(((TextView) view).getText())));
        assertNotNull("Bo's username alone", find(content, view -> view instanceof TextView && "@bo.k".contentEquals(((TextView) view).getText())));

        View row = find(content, view -> "Ana Lima, @ana".contentEquals(String.valueOf(view.getContentDescription())));
        assertNotNull(row);
        assertTrue(row.performClick());
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://www.instagram.com/ana/", started.getDataString());
        assertEquals("opens inside Instagram, with no chooser", activity.getPackageName(), started.getPackage());
        assertFalse("the list closes", dialog.isShowing());
    }

    /**
     * Instagram's story viewer takes every touch on a story before the header sees it (#125), so a
     * tap on the pill is caught where the activity dispatches it: each of its events is answered as
     * handled, Instagram never sees them, and the list opens.
     */
    @Test
    public void aTapOnThePillOpensTheListBeforeInstagramSeesIt() {
        bind(ANA, BO);
        float[] center = layOut(pill());
        assertTrue("the finger going down", StoryMentions.touch(activity, event(MotionEvent.ACTION_DOWN, center[0], center[1])));
        assertTrue("a wobble inside the slop", StoryMentions.touch(activity, event(MotionEvent.ACTION_MOVE, center[0] + 1, center[1])));
        assertNull("nothing opens before the finger comes up", ShadowDialog.getLatestDialog());
        assertTrue("the finger coming up", StoryMentions.touch(activity, event(MotionEvent.ACTION_UP, center[0] + 1, center[1])));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(StoryMentions.PILL_TAPPED + " 1"));
        assertTrue(report, report.contains(StoryMentions.LIST_SHOWN + " 1"));
        assertTrue(report, report.contains(StoryMentions.PILL_SHOWN + " "));
        assertFalse("the next touch elsewhere is Instagram's", StoryMentions.touch(activity, event(MotionEvent.ACTION_DOWN, 1, 1)));
    }

    /**
     * On 450 the story viewer's views carry a context that doesn't lead back to the activity (the
     * emulator's report counted "list not shown, no activity" for a tap). The pill is found by the
     * window it's in, and the list opens on the activity the touch went through.
     */
    @Test
    public void aPillWhoseContextIsntTheActivitysStillOpensTheList() {
        Context plain = new ContextThemeWrapper(activity.getApplicationContext(), android.R.style.Theme_DeviceDefault);
        root.removeView(header);
        header = new LinearLayout(plain);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setId(StoryMentions.headerIdForTests);
        TextView name = new TextView(plain);
        name.setText("someone");
        header.addView(name);
        root.addView(header);
        bind(ANA, BO);
        float[] center = layOut(pill());
        assertTrue("the finger going down", StoryMentions.touch(activity, event(MotionEvent.ACTION_DOWN, center[0], center[1])));
        assertTrue("the finger coming up", StoryMentions.touch(activity, event(MotionEvent.ACTION_UP, center[0], center[1])));
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(StoryMentions.PILL_TAPPED + " 1"));
        assertTrue(report, report.contains(StoryMentions.LIST_SHOWN + " 1"));
        assertFalse(report, report.contains(StoryMentions.LIST_NO_ACTIVITY));
    }

    /** A touch that doesn't go down on the pill is Instagram's, every event of it. */
    @Test
    public void aTouchBesideThePillIsInstagrams() {
        bind(ANA);
        float[] center = layOut(pill());
        float below = center[1] + pill().getHeight() * 2;
        assertFalse(StoryMentions.touch(activity, event(MotionEvent.ACTION_DOWN, center[0], below)));
        assertFalse("it ends on the pill, but didn't start there", StoryMentions.touch(activity, event(MotionEvent.ACTION_UP, center[0], center[1])));
        assertNull(ShadowDialog.getLatestDialog());
    }

    /** A finger that goes down on the pill and drags off is no tap: the touch stays HushGram's and nothing opens. */
    @Test
    public void aDragFromThePillOpensNothing() {
        bind(ANA);
        float[] center = layOut(pill());
        assertTrue(StoryMentions.touch(activity, event(MotionEvent.ACTION_DOWN, center[0], center[1])));
        assertTrue(StoryMentions.touch(activity, event(MotionEvent.ACTION_MOVE, center[0], center[1] + 200)));
        assertTrue(StoryMentions.touch(activity, event(MotionEvent.ACTION_UP, center[0], center[1])));
        assertNull(ShadowDialog.getLatestDialog());
    }

    /** A hidden pill, on a story with no mentions, takes no touch. */
    @Test
    public void aHiddenPillTakesNoTouch() {
        bind(ANA);
        float[] center = layOut(pill());
        bind();
        assertEquals(View.GONE, pill().getVisibility());
        assertFalse(StoryMentions.touch(activity, event(MotionEvent.ACTION_DOWN, center[0], center[1])));
        assertFalse(StoryMentions.touch(activity, event(MotionEvent.ACTION_UP, center[0], center[1])));
        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test
    public void aRowShowsTheAccountsPicture() throws Exception {
        List<String> asked = new ArrayList<>();
        StoryMentions.picturesForTests = (context, url, size) -> {
            asked.add(url);
            return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        };
        bind(ANA, BO);
        pill().performClick();
        Utils.awaitBackgroundTasksForTests();
        idle();
        assertEquals("only Ana has a picture", Collections.singletonList(ANA.picture), asked);
        View content = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNotNull(find(content, view -> view instanceof ImageView && ((ImageView) view).getDrawable() instanceof BitmapDrawable));
    }

    @Test
    public void aThrowingReadKeepsInstagramsHeaderAndIsReported() {
        reads.throwing = true;
        bind(ANA);
        assertNull(pill());
        String missing = HookStatus.missing(FamilyNames.STORY_MENTIONS).toString();
        assertTrue(missing, missing.contains("'story header'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void aProfileLinkKeepsTheUsernameWhole() {
        assertEquals("https://www.instagram.com/a.b_c/", StoryMentions.profileLink("a.b_c").toString());
        assertEquals("https://www.instagram.com/a%2Fb/", StoryMentions.profileLink("a/b").toString());
    }

    /**
     * #151: with the name in a row of its own, the pill goes at the end of that row, so the column
     * doesn't grow and the name stays where Instagram put it.
     */
    @Test
    public void withTheNameInARowThePillGoesAtTheEndOfIt() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(activity);
        title.setId(View.generateViewId());
        title.setText("someone");
        TextView time = new TextView(activity);
        time.setText("2h");
        row.addView(title);
        row.addView(time);
        header.removeAllViews();
        header.addView(row);
        StoryMentions.titleIdForTests = title.getId();

        bind(ANA, BO);

        StoryMentions.Pill pill = pill();
        assertNotNull(pill);
        assertSame("the pill isn't in the name's row", row, pill.getParent());
        assertEquals("the pill goes after the time", row.getChildCount() - 1, row.indexOfChild(pill));
        assertEquals("the column grew", 1, header.getChildCount());
        layOut(pill);
        idle();
        assertSame("a pill that fits left the name's row", row, pill.getParent());
        assertFalse(StoryMentions.crowdsName(row));
        bind(BO);
        assertSame("a later story made a second pill", pill, pill());
        assertEquals("1 mention", pill.getText().toString());
    }

    /**
     * Instagram's name row shrinks the name to make room, so on the S22 a highlight's "January 16"
     * plus the pill left "creatives" showing only its verified badge. When a layout shows the name
     * cut short, the pill goes under the header, lined up with the name, and the name gets its
     * whole width back.
     */
    @Test
    public void aPillThatCutsTheNameShortGoesUnderTheHeader() {
        root.removeView(header);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout headerRow = new FrameLayout(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(activity);
        title.setId(View.generateViewId());
        title.setSingleLine(true);
        title.setText("creatives");
        TextView time = new TextView(activity);
        time.setSingleLine(true);
        time.setText("January 16");
        // Like Instagram's: the name takes what the row has left once everything else is measured.
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(time);
        header.removeAllViews();
        header.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Room for the name and the time, not the pill too.
        int room = wanted(title) + wanted(time) + 2;
        FrameLayout.LayoutParams besideTheAvatar = new FrameLayout.LayoutParams(room, ViewGroup.LayoutParams.WRAP_CONTENT);
        besideTheAvatar.leftMargin = 88;
        headerRow.addView(header, besideTheAvatar);
        column.addView(headerRow);
        root.addView(column);
        StoryMentions.titleIdForTests = title.getId();
        layOut(header);
        assertFalse("the name is cut short before there's a pill", StoryMentions.crowdsName(row));

        bind(ANA, BO);
        StoryMentions.Pill pill = pill();
        assertNotNull(pill);
        // The first layout with the pill in the row is what moves it.
        layOut(header);
        idle();

        assertSame("the pill stayed in the row", column, pill.getParent());
        assertEquals("the pill isn't right under the header's row", column.indexOfChild(headerRow) + 1, column.indexOfChild(pill));
        // A name view that starts at its row's edge puts the pill at the header's edge too.
        assertEquals("the pill doesn't line up with the name", 88,
                ((LinearLayout.LayoutParams) pill.getLayoutParams()).getMarginStart());
        layOut(pill);
        assertTrue("the row had room for the pill, so this proves nothing",
                wanted(title) + wanted(time) + pill.getWidth() > room);
        assertFalse("the name is still cut short", StoryMentions.crowdsName(row));
        assertTrue("the pill isn't below the header", pill.getTop() >= headerRow.getBottom());
        String missing = HookStatus.missing(FamilyNames.STORY_MENTIONS).toString();
        assertFalse(missing, missing.contains("threw"));
        bind(BO);
        assertSame("a later story made a second pill", pill, pill());
        assertSame("a later story moved the pill back", column, pill.getParent());
        assertEquals("1 mention", pill.getText().toString());
        bind();
        assertEquals(View.GONE, pill.getVisibility());
    }

    /** What [text] needs across to show whole. */
    private static int wanted(TextView text) {
        return (int) Math.ceil(android.text.Layout.getDesiredWidth(text.getText(), text.getPaint()))
                + text.getCompoundPaddingLeft() + text.getCompoundPaddingRight();
    }

    /**
     * #405: on Instagram 453 the name's row is a Compose view with no name view in it, and the
     * header centers the name's column, so a pill under the name moved the name up under the
     * progress bars. With the header's row inside a column, the pill goes right under that row,
     * lined up with the name's text (the row keeps an inset before it, so lined up with the row's
     * edge the pill sat left of the name on the S22), and the name's column keeps what it had.
     */
    @Test
    public void withAComposeNameRowThePillGoesUnderTheHeader() {
        root.removeView(header);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        View progress = new View(activity);
        column.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 3));
        FrameLayout headerRow = new FrameLayout(activity);
        header.removeAllViews();
        View composeTitleRow = new View(activity);
        header.addView(composeTitleRow, new LinearLayout.LayoutParams(280, 34));
        FrameLayout attribution = new FrameLayout(activity);
        header.addView(attribution, new LinearLayout.LayoutParams(362, 75));
        FrameLayout.LayoutParams besideTheAvatar = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        besideTheAvatar.leftMargin = 88;
        headerRow.addView(header, besideTheAvatar);
        column.addView(headerRow);
        root.addView(column);
        layOut(header);
        assertEquals(88, header.getLeft());

        bind(ANA, BO);

        StoryMentions.Pill pill = pill();
        assertNotNull(pill);
        assertSame("the pill isn't under the header", column, pill.getParent());
        assertEquals("the pill isn't right under the header's row", column.indexOfChild(headerRow) + 1, column.indexOfChild(pill));
        assertEquals("the name's column grew", 2, header.getChildCount());
        // The Compose row keeps a little room before its text, and the pill lines up with the text, not the row.
        assertEquals("the pill doesn't line up with the name's text", 88 + Dim.dp(StoryMentions.COMPOSE_NAME_INSET_DP),
                ((LinearLayout.LayoutParams) pill.getLayoutParams()).getMarginStart());
        layOut(pill);
        assertTrue("the pill isn't below the header", pill.getTop() >= headerRow.getBottom());
        assertEquals("2 mentions", pill.getText().toString());
        bind(BO);
        assertSame("a later story made a second pill", pill, pill());
        assertEquals("1 mention", pill.getText().toString());
        bind();
        assertEquals(View.GONE, pill.getVisibility());
    }

    /**
     * The pill is drawn small, and a finger gets the room around it, up to the 48dp Android asks of
     * a target. A tap on the name just above it is still Instagram's.
     */
    @Test
    public void aTapJustAboveThePillTapsItButNotOnTheName() {
        bind(ANA);
        StoryMentions.Pill pill = pill();
        // Robolectric's text measures the pill 47 px tall at any size, all but a whole target,
        // so it gets the 22dp a phone draws it at.
        pill.getLayoutParams().height = Dim.dp(22);
        float[] center = layOut(pill);
        int target = Dim.dp(StoryMentions.MIN_TARGET_DP);
        assertTrue("the pill is drawn smaller than a target", pill.getHeight() < target);
        int[] at = new int[2];
        pill.getLocationInWindow(at);
        int pillTop = at[1];
        int pillRight = at[0] + pill.getWidth();
        View name = header.getChildAt(0);
        name.getLocationInWindow(at);
        int nameBottom = at[1] + name.getHeight();
        // Straight above the pill: the name runs the column's whole width, so its own middle is
        // far to the side of a pill that starts at the left.
        float nameX = center[0];
        assertTrue("the name isn't above the pill", at[0] <= nameX && nameX < at[0] + name.getWidth());
        assertTrue("no room between the name and the pill", nameBottom < pillTop);
        float onName = nameBottom - 1;
        assertTrue("the name is out of the pill's reach, so this proves nothing: pill top " + pillTop + ", height "
                        + pill.getHeight() + ", target " + target + ", name bottom " + nameBottom + ", x " + nameX + " of " + pillRight,
                pillTop - onName < (target - pill.getHeight()) / 2f && nameX < pillRight);

        assertFalse("a tap on the name went to the pill",
                StoryMentions.touch(activity, event(MotionEvent.ACTION_DOWN, nameX, onName)));
        float between = pillTop - 1;
        assertTrue(StoryMentions.touch(activity, event(MotionEvent.ACTION_DOWN, center[0], between)));
        assertTrue(StoryMentions.touch(activity, event(MotionEvent.ACTION_UP, center[0], between)));
        assertNotNull("the tap didn't open the list", ShadowDialog.getLatestDialog());
    }

    private AlertDialog openedList() {
        assertTrue(pill().performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        return dialog;
    }

    private void tapAccount(AlertDialog dialog, String description) {
        View row = find(dialog.getWindow().getDecorView(), view -> description.contentEquals(String.valueOf(view.getContentDescription())));
        assertNotNull(row);
        assertTrue(row.performClick());
    }

    /** #403: the list comes back when the story does, so the next account is one tap away. */
    @Test
    public void theListComesBackAfterAProfileWhenTheStoryReturns() {
        bind(ANA, BO);
        AlertDialog first = openedList();
        tapAccount(first, "Ana Lima, @ana");
        assertFalse(first.isShowing());
        StoryMentions.Pill pill = pill();
        pill.setVisibility(View.GONE);
        idle();
        pill.setVisibility(View.VISIBLE);
        idle();
        AlertDialog again = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(first, again);
        assertTrue("the list is open again", again.isShowing());
        assertNotNull(find(again.getWindow().getDecorView(), view -> "@bo.k".equals(textOf(view))));
    }

    @Test
    public void theListStaysClosedWhileTheProfileIsStillUp() {
        bind(ANA, BO);
        AlertDialog first = openedList();
        tapAccount(first, "Ana Lima, @ana");
        idle();
        bind(ANA, BO);
        assertFalse("the pill never went away, so the profile still has the screen", ((AlertDialog) ShadowDialog.getLatestDialog()).isShowing());
    }

    @Test
    public void closingTheListEndsTheComeBack() {
        bind(ANA, BO);
        AlertDialog first = openedList();
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        StoryMentions.Pill pill = pill();
        pill.setVisibility(View.GONE);
        idle();
        pill.setVisibility(View.VISIBLE);
        idle();
        assertSame("Close ends it", first, ShadowDialog.getLatestDialog());
    }

    @Test
    public void anotherStoryEndsTheComeBack() {
        bind(ANA, BO);
        AlertDialog first = openedList();
        tapAccount(first, "Ana Lima, @ana");
        StoryMentions.Pill pill = pill();
        pill.setVisibility(View.GONE);
        idle();
        bind(BO);
        idle();
        assertSame("a story with other mentions doesn't open the list", first, ShadowDialog.getLatestDialog());
        bind(ANA, BO);
        idle();
        assertSame("and the old list is forgotten", first, ShadowDialog.getLatestDialog());
    }

    private static String textOf(View view) {
        return view instanceof TextView ? String.valueOf(((TextView) view).getText()) : null;
    }

    private void bind(Account... mentioned) {
        StoryMentions.bind(itemView, Arrays.asList(mentioned));
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private StoryMentions.Pill pill() {
        return StoryMentions.pillIn(header);
    }

    /** Lays the screen out at a phone's size and answers [view]'s center in the window. */
    private float[] layOut(View view) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 2400);
        assertTrue("the pill has a size", view.getWidth() > 0 && view.getHeight() > 0);
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return new float[] {at[0] + view.getWidth() / 2f, at[1] + view.getHeight() / 2f};
    }

    private static MotionEvent event(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    private static View find(View at, Predicate<View> wanted) {
        if (wanted.test(at)) return at;
        if (at instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) at;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }
}
