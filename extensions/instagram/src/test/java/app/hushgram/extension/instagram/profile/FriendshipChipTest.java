/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import app.hushgram.extension.instagram.profile.FriendshipStatus.Relation;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Show it as a chip: the chip under a profile's counts, and the label by the name when there's none. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = FriendshipChipTest.Patched.class)
public class FriendshipChipTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private LinearLayout header;
    private TextView pronouns;
    private LinearLayout counts;
    private TextView bio;
    private int followersId;

    @Before
    public void build() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout name = new LinearLayout(activity);
        name.addView(new TextView(activity));
        pronouns = new TextView(activity);
        pronouns.setTextColor(Color.rgb(0xA8, 0xA8, 0xA8));
        name.addView(pronouns);
        header.addView(name);
        counts = new LinearLayout(activity);
        counts.setPadding(0, 4, 0, 6);
        TextView followers = new TextView(activity);
        followersId = View.generateViewId();
        followers.setId(followersId);
        followers.setText("120");
        counts.addView(followers);
        header.addView(counts);
        bio = new TextView(activity);
        bio.setText("A bio");
        header.addView(bio);
        activity.setContentView(header);
        FriendshipChip.followersCountIdForTests = followersId;
        Settings.FRIENDSHIP_STATUS_CHIP.save(true);
    }

    @After
    public void restore() {
        Settings.FRIENDSHIP_STATUS_CHIP.resetToDefault();
        Settings.SHOW_FRIENDSHIP_STATUS.resetToDefault();
        PauseForTests.resume();
        // Takes any chip away, so the next test starts with none showing.
        counts.setVisibility(View.VISIBLE);
        FriendshipChip.followersCountIdForTests = followersId;
        FriendshipStatus.besidePronouns(pronouns, new Profile(null, null));
        assertFalse(FriendshipChip.anyShown());
        FriendshipChip.followersCountIdForTests = 0;
        FriendshipChip.pictureFrameIdForTests = 0;
        Settings.FOLLOW_LABEL_PLACE.resetToDefault();
        HookStatus.clear();
        controller.close();
    }

    private LinearLayout row;
    private FrameLayout column;
    private View frame;
    private LinearLayout right;

    /**
     * Builds a header the way Instagram's new profile lays it out, and shows it in place of the
     * plain one: the picture's column at the start of a row, the name and the counts beside it,
     * and the bio under the row. [rightLines] more lines make the column beside the picture taller.
     */
    private void pictured(int frameWidth, int frameHeight, int rightLines) {
        Activity activity = controller.get();
        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(30, 0, 30, 0);
        column = new FrameLayout(activity);
        column.setPadding(0, 0, 0, 5);
        frame = new View(activity);
        frame.setId(View.generateViewId());
        column.addView(frame, new FrameLayout.LayoutParams(frameWidth, frameHeight));
        row.addView(column, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        right = new LinearLayout(activity);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout name = new LinearLayout(activity);
        name.addView(new TextView(activity));
        pronouns = new TextView(activity);
        pronouns.setTextColor(Color.rgb(0xA8, 0xA8, 0xA8));
        name.addView(pronouns);
        right.addView(name);
        counts = new LinearLayout(activity);
        counts.setPadding(0, 4, 0, 6);
        TextView followers = new TextView(activity);
        followersId = View.generateViewId();
        followers.setId(followersId);
        followers.setText("120");
        counts.addView(followers);
        right.addView(counts);
        for (int i = 0; i < rightLines; i++) {
            TextView line = new TextView(activity);
            line.setText("Line " + i);
            right.addView(line);
        }
        row.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(row);
        bio = new TextView(activity);
        bio.setText("A bio");
        header.addView(bio);
        activity.setContentView(header);
        FriendshipChip.followersCountIdForTests = followersId;
        FriendshipChip.pictureFrameIdForTests = frame.getId();
        Settings.FOLLOW_LABEL_PLACE.save(FollowLabelPlace.UNDER_PICTURE);
        HookStatus.clear();
    }

    /** [view]'s bounds in [row]. */
    private Rect inRow(View view) {
        int left = 0;
        int top = 0;
        for (View at = view; at != row; at = (View) at.getParent()) {
            left += at.getLeft();
            top += at.getTop();
        }
        return new Rect(left, top, left + view.getWidth(), top + view.getHeight());
    }

    /**
     * Small, under the picture (#157): a smaller pill, centered under the picture in room made at
     * the bottom of the picture's column, so the bio moves down and nothing beside it is touched.
     */
    @Test
    public void theSmallChipGoesCenteredUnderThePicture() {
        pictured(260, 260, 0);
        int bioTop = layOut();
        int ownBottom = column.getPaddingBottom();
        int countsBottom = counts.getPaddingBottom();

        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        int movedBioTop = layOut();

        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertNotNull(chip);
        assertTrue(chip.small);
        assertTrue(chip.underPicture);
        assertEquals("Following each other", chip.pill.text);
        assertEquals(ownBottom + chip.room, column.getPaddingBottom());
        assertEquals("the counts keep their padding", countsBottom, counts.getPaddingBottom());
        assertEquals("the bio moves down by the chip's room", bioTop + chip.room, movedBioTop);
        Rect pill = chip.pill.getBounds();
        Rect picture = inRow(frame);
        assertFalse(pill.isEmpty());
        assertEquals("centered under the picture", picture.centerX(), pill.centerX(), 1);
        assertTrue("under the picture", pill.top >= picture.bottom);
        assertEquals(column.getBottom() - ownBottom, pill.bottom);
        assertFalse("never on the name or the counts", Rect.intersects(pill, inRow(right)));
        assertEquals("a block at the row's top, shorter than it, is left alone", 0, right.getPaddingBottom());
        assertTrue(String.join("\n", HookStatus.report()),
                String.join("\n", HookStatus.report()).contains(FriendshipChip.UNDER_PICTURE + " 1"));

        // Smaller than the usual chip: smaller words, icon and padding.
        FriendshipChip.Pill usual = new FriendshipChip.Pill(RuntimeEnvironment.getApplication());
        usual.set(chip.pill.text, null, Color.WHITE, null);
        FriendshipChip.Pill small = new FriendshipChip.Pill(RuntimeEnvironment.getApplication(), true);
        small.set(chip.pill.text, null, Color.WHITE, null);
        assertTrue(small.getIntrinsicHeight() < usual.getIntrinsicHeight());
        assertTrue(small.getIntrinsicWidth() < usual.getIntrinsicWidth());

        // Binding again changes the words, not the room.
        FriendshipStatus.besidePronouns(pronouns, new Profile(false, null));
        assertSame(chip, FriendshipChip.shownUnder(counts));
        assertEquals("Doesn't follow you", chip.pill.text);
        assertEquals(ownBottom + chip.room, column.getPaddingBottom());
    }

    /** Right to left, the picture is at the other end of the row and the pill is still centered under it. */
    @Test
    public void rightToLeftThePillFollowsThePicture() {
        pictured(260, 260, 0);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        layOut();
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        Rect picture = inRow(frame);
        assertTrue("the picture is at the right", picture.left > inRow(right).left);
        assertEquals(picture.centerX(), chip.pill.getBounds().centerX(), 1);
        assertEquals(View.LAYOUT_DIRECTION_RTL, chip.pill.getLayoutDirection());
    }

    /**
     * A pill wider than a small picture, beside a column taller than the picture, would sit on the
     * counts, so it moves under the counts once the layout is done, small, and stays there.
     */
    @Test
    public void withNoRoomUnderThePictureTheSmallChipGoesUnderTheCounts() {
        pictured(20, 60, 6);
        int ownBottom = column.getPaddingBottom();
        int countsBottom = counts.getPaddingBottom();
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        layOut();
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertTrue("nothing drawn over the counts while it moves", chip.pill.getBounds().isEmpty());

        ShadowLooper.idleMainLooper();
        layOut();
        assertSame(chip, FriendshipChip.shownUnder(counts));
        assertFalse(chip.underPicture);
        assertTrue(chip.pictureBlocked);
        assertTrue(chip.small);
        assertEquals("the column gets its room back", ownBottom, column.getPaddingBottom());
        assertEquals(countsBottom + chip.roomNow(), counts.getPaddingBottom());
        assertEquals(counts.getHeight() - countsBottom, chip.pill.getBounds().bottom);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FriendshipChip.NO_PICTURE_ROOM + " 1"));
        assertTrue("and what it would have run into", report.contains(FriendshipChip.WOULD_TOUCH));

        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertSame("the next bind keeps it under the counts", chip, FriendshipChip.shownUnder(counts));
        assertFalse(chip.underPicture);
    }

    /**
     * 451 stretches the counts block and its tappable counts to the picture column's height, empty
     * under the numbers but for a press ripple (#157, S22 2026-10-14). A pill wider than the picture
     * reaching into that room stays under the picture: only what the neighbor shows at rest is in
     * the way.
     */
    @Test
    public void thePillMayReachIntoANeighborsEmptyRoom() {
        pictured(20, 260, 0);
        right.getLayoutParams().height = LinearLayout.LayoutParams.MATCH_PARENT;
        right.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.GRAY), null, null));
        right.addView(new View(right.getContext()), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        layOut();
        ShadowLooper.idleMainLooper();
        layOut();
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertTrue(chip.underPicture);
        Rect pill = chip.pill.getBounds();
        assertFalse(pill.isEmpty());
        assertTrue("it reaches past the picture's column", pill.right > inRow(column).right);
        assertTrue("into the stretched block's rectangle", pill.bottom <= inRow(right).bottom);
        String report = String.join("\n", HookStatus.report());
        assertFalse(report, report.contains(FriendshipChip.NO_PICTURE_ROOM));
    }

    /**
     * 0.0.12 on a phone (#157): the name and counts sit centered in a block stretched to the
     * picture column's height, so the room under the picture moved them down by half of it. The
     * block gets the same room at its bottom and they stay where Instagram put them, and taking the
     * chip away gives the block its own padding back.
     */
    @Test
    public void theNameAndCountsCenteredBesideThePictureStayPut() {
        pictured(260, 260, 0);
        right.getLayoutParams().height = LinearLayout.LayoutParams.MATCH_PARENT;
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setPadding(0, 0, 0, 3);
        int bioTop = layOut();
        int countsTop = inRow(counts).top;
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        layOut();
        ShadowLooper.idleMainLooper();
        int movedBioTop = layOut();
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertTrue(chip.underPicture);
        assertFalse(chip.pill.getBounds().isEmpty());
        assertEquals("the bio moves down by the chip's room", bioTop + chip.room, movedBioTop);
        assertEquals("the counts stay where they were", countsTop, inRow(counts).top);
        assertEquals(3 + chip.room, right.getPaddingBottom());

        FriendshipStatus.besidePronouns(pronouns, new Profile(false, null));
        layOut();
        assertEquals("binding again adds no more room", 3 + chip.room, right.getPaddingBottom());

        FriendshipChip.clear(counts);
        assertEquals(3, right.getPaddingBottom());
        assertEquals(bioTop, layOut());
        assertEquals(countsTop, inRow(counts).top);
    }

    /** A block as tall as its words, centered in the row beside the picture, stays put the same way. */
    @Test
    public void aBlockCenteredInTheRowStaysPutToo() {
        pictured(260, 260, 0);
        row.setGravity(Gravity.CENTER_VERTICAL);
        layOut();
        int countsTop = inRow(counts).top;
        assertTrue("centered below the row's top", inRow(right).top > 0);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        layOut();
        ShadowLooper.idleMainLooper();
        layOut();
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertTrue(chip.underPicture);
        assertEquals(countsTop, inRow(counts).top);
        assertEquals(chip.room, right.getPaddingBottom());
    }

    /**
     * 0.0.12 on the S22 (#157, 2026-10-17): 451's header is one ConstraintLayout, with the name
     * and counts a chain centered on the picture's column and the bio in the same row under it.
     * The column never reaches that row's bottom, so the name and counts still went down by half
     * the room. Now they stay put, and the bio still moves down to make the room.
     */
    @Test
    public void theNameAndCountsCenteredOnThePictureInOneLayoutStayPut() {
        Activity activity = controller.get();
        CenteredBeside row451 = new CenteredBeside(activity);
        FrameLayout column451 = new FrameLayout(activity);
        View frame451 = new View(activity);
        frame451.setId(View.generateViewId());
        column451.addView(frame451, new FrameLayout.LayoutParams(248, 248));
        row451.addView(column451, wrapped(0, 0));
        LinearLayout name = new LinearLayout(activity);
        TextView fullName = new TextView(activity);
        fullName.setText("National Geographic");
        name.addView(fullName);
        pronouns = new TextView(activity);
        name.addView(pronouns);
        row451.addView(name, wrapped(23, 0));
        counts = new LinearLayout(activity);
        TextView followers = new TextView(activity);
        followersId = View.generateViewId();
        followers.setId(followersId);
        followers.setText("268M");
        counts.addView(followers);
        row451.addView(counts, wrapped(0, 4));
        bio = new TextView(activity);
        bio.setText("A bio");
        row451.addView(bio, wrapped(0, 0));
        // 451's internal_only_badges: shown, empty, at the corner under the picture.
        row451.addView(new LinearLayout(activity), wrapped(0, 0));
        header = new LinearLayout(activity);
        header.addView(row451);
        activity.setContentView(header);
        FriendshipChip.followersCountIdForTests = followersId;
        FriendshipChip.pictureFrameIdForTests = frame451.getId();
        Settings.FOLLOW_LABEL_PLACE.save(FollowLabelPlace.UNDER_PICTURE);
        HookStatus.clear();

        int bioTop = layOut();
        int nameTop = name.getTop();
        int countsTop = counts.getTop();
        assertTrue("centered below the column's top", nameTop > 23);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        layOut();
        ShadowLooper.idleMainLooper();
        int movedBioTop = layOut();
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertTrue(chip.underPicture);
        assertFalse(chip.pill.getBounds().isEmpty());
        assertEquals("the bio moves down by the chip's room", bioTop + chip.room, movedBioTop);
        assertEquals("the name stays where it was", nameTop, name.getTop());
        assertEquals("the counts stay where they were", countsTop, counts.getTop());
        assertEquals("only the chain's lowest view takes the room", chip.room, counts.getPaddingBottom());
        assertEquals(0, name.getPaddingBottom());

        FriendshipChip.clear(counts);
        assertEquals(0, counts.getPaddingBottom());
        assertEquals(bioTop, layOut());
        assertEquals(nameTop, name.getTop());
    }

    private static ViewGroup.MarginLayoutParams wrapped(int topMargin, int bottomMargin) {
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        params.bottomMargin = bottomMargin;
        return params;
    }

    /**
     * 451's profile header the way its ConstraintLayout places it: the picture's column at the top
     * start, the next two children a chain beside it centered on its height (outer margins
     * counted), the fourth under whichever ends lower, and any more at the corner under the column.
     */
    static final class CenteredBeside extends ViewGroup {
        private static final int START = 45;
        private static final int GAP = 45;

        CenteredBeside(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            View column = getChildAt(0);
            column.measure(any, any);
            int beside = MeasureSpec.makeMeasureSpec(width - 2 * START - column.getMeasuredWidth() - GAP, MeasureSpec.AT_MOST);
            getChildAt(1).measure(beside, any);
            getChildAt(2).measure(beside, any);
            getChildAt(3).measure(MeasureSpec.makeMeasureSpec(width - 2 * START, MeasureSpec.EXACTLY), any);
            for (int i = 4; i < getChildCount(); i++) getChildAt(i).measure(any, any);
            setMeasuredDimension(width, arrange(false));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            arrange(true);
        }

        /** Places the children when [apply], and answers the height they take. */
        private int arrange(boolean apply) {
            View column = getChildAt(0);
            View first = getChildAt(1);
            View second = getChildAt(2);
            View under = getChildAt(3);
            int columnBottom = column.getMeasuredHeight();
            int topMargin = ((MarginLayoutParams) first.getLayoutParams()).topMargin;
            int bottomMargin = ((MarginLayoutParams) second.getLayoutParams()).bottomMargin;
            int chain = topMargin + first.getMeasuredHeight() + second.getMeasuredHeight() + bottomMargin;
            int firstTop = (columnBottom - chain) / 2 + topMargin;
            int secondTop = firstTop + first.getMeasuredHeight();
            int underTop = Math.max(columnBottom, secondTop + second.getMeasuredHeight() + bottomMargin);
            if (apply) {
                int left = START + column.getMeasuredWidth() + GAP;
                column.layout(START, 0, START + column.getMeasuredWidth(), columnBottom);
                first.layout(left, firstTop, left + first.getMeasuredWidth(), secondTop);
                second.layout(left, secondTop, left + second.getMeasuredWidth(), secondTop + second.getMeasuredHeight());
                under.layout(START, underTop, START + under.getMeasuredWidth(), underTop + under.getMeasuredHeight());
                for (int i = 4; i < getChildCount(); i++) {
                    View corner = getChildAt(i);
                    corner.layout(START, columnBottom, START + corner.getMeasuredWidth(), columnBottom + corner.getMeasuredHeight());
                }
            }
            return underTop + under.getMeasuredHeight();
        }
    }

    /** A neighbor whose own color fills the room the pill would take is in the way, and the report names it. */
    @Test
    public void aNeighborsColoredBackgroundIsInTheWay() {
        pictured(20, 260, 0);
        right.getLayoutParams().height = LinearLayout.LayoutParams.MATCH_PARENT;
        right.setBackgroundColor(Color.DKGRAY);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        layOut();
        ShadowLooper.idleMainLooper();
        layOut();
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        String report = String.join("\n", HookStatus.report());
        assertFalse(report, chip.underPicture);
        assertTrue(chip.pictureBlocked);
        assertTrue(report, report.contains(FriendshipChip.WOULD_TOUCH + "LinearLayout 1"));
    }

    /** A picture column with a height of its own would squeeze the picture, so the small chip goes under the counts. */
    @Test
    public void aColumnNotSizedToItsPictureGetsNoRoom() {
        pictured(260, 260, 0);
        column.getLayoutParams().height = 300;
        int ownBottom = column.getPaddingBottom();
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertFalse(chip.underPicture);
        assertTrue(chip.small);
        assertEquals(ownBottom, column.getPaddingBottom());
        assertNull(FriendshipChip.pictureColumn(counts));
        String report = String.join("\n", HookStatus.report());
        assertTrue("the report says how tall the column is held", report.contains(FriendshipChip.COLUMN_HELD + "300 px 1"));
    }

    /** Changing the choice moves the chip at the next bind and gives the old place its room back. */
    @Test
    public void changingTheChoiceMovesTheChip() {
        pictured(260, 260, 0);
        int columnBottom = column.getPaddingBottom();
        int countsBottom = counts.getPaddingBottom();
        Settings.FOLLOW_LABEL_PLACE.save(FollowLabelPlace.UNDER_COUNTS);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        FriendshipChip.Chip usual = FriendshipChip.shownUnder(counts);
        assertFalse(usual.small);
        assertFalse(usual.underPicture);
        assertEquals(countsBottom + usual.room, counts.getPaddingBottom());

        Settings.FOLLOW_LABEL_PLACE.save(FollowLabelPlace.UNDER_PICTURE);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        FriendshipChip.Chip small = FriendshipChip.shownUnder(counts);
        assertNotSame(usual, small);
        assertTrue(small.underPicture);
        assertEquals(countsBottom, counts.getPaddingBottom());
        assertEquals(columnBottom + small.room, column.getPaddingBottom());

        Settings.FRIENDSHIP_STATUS_CHIP.save(false);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertNull(FriendshipChip.shownUnder(counts));
        assertEquals(columnBottom, column.getPaddingBottom());
    }

    /** At twice the usual font size the small pill grows with its words, so the outline never cuts them. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theSmallPillGrowsWithTheFontSize() {
        FriendshipChip.Pill usual = new FriendshipChip.Pill(RuntimeEnvironment.getApplication(), true);
        assertEquals("the small pill is 24dp", usual.dp(24), usual.getIntrinsicHeight());
        try {
            RuntimeEnvironment.setFontScale(2f);
            FriendshipChip.Pill large = new FriendshipChip.Pill(RuntimeEnvironment.getApplication(), true);
            assertTrue(large.getIntrinsicHeight() >= large.textHeight() + large.dp(6));
            assertTrue(large.getIntrinsicHeight() > usual.getIntrinsicHeight());
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    public void theChipSaysWhatYouAreToEachOther() {
        assertEquals(Relation.FOLLOWING_EACH_OTHER, FriendshipStatus.relation(true, true));
        assertEquals(Relation.FOLLOWS_YOU, FriendshipStatus.relation(true, false));
        assertEquals("not known whether you follow them", Relation.FOLLOWS_YOU, FriendshipStatus.relation(true, null));
        assertEquals(Relation.DOESNT_FOLLOW_YOU, FriendshipStatus.relation(false, true));
        assertEquals(Relation.DOESNT_FOLLOW_YOU, FriendshipStatus.relation(false, null));
        assertEquals("Following each other", FriendshipChip.text(Relation.FOLLOWING_EACH_OTHER));
        assertEquals("Follows you", FriendshipChip.text(Relation.FOLLOWS_YOU));
        assertEquals("Doesn't follow you", FriendshipChip.text(Relation.DOESNT_FOLLOW_YOU));
        // The text label is as it always was: following each other still reads Follows you.
        assertEquals("Follows you", FriendshipStatus.textFor(Relation.FOLLOWING_EACH_OTHER));
    }

    /**
     * Under the counts, in the relation's color, with room made so the bio moves down. The
     * pronouns stay as Instagram set them, and binding again changes the words, not the room.
     */
    @Test
    public void theChipGoesUnderTheCountsAndTheNameKeepsItsPronouns() {
        int bioTop = layOut();
        int ownBottom = counts.getPaddingBottom();
        pronouns.setText("she/her");
        pronouns.setTextColor(0xFFA8A8A8);

        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));

        assertEquals("she/her", pronouns.getText().toString());
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertNotNull(chip);
        assertEquals("Following each other", chip.pill.text);
        assertEquals(FriendshipChip.color(Relation.FOLLOWING_EACH_OTHER, false), chip.pill.color());
        assertEquals(ownBottom + chip.room, counts.getPaddingBottom());
        assertEquals("the bio moves down by the chip's room", bioTop + chip.room, layOut());
        assertEquals("the chip sits in the room, at the counts' start", counts.getHeight() - ownBottom, chip.pill.getBounds().bottom);
        assertEquals(counts.getPaddingStart(), chip.pill.getBounds().left);
        assertEquals(chip.pill.getIntrinsicHeight(), chip.pill.getBounds().height());
        assertTrue(chip.pill.getBounds().top >= counts.getHeight() - counts.getPaddingBottom());

        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertSame(chip, FriendshipChip.shownUnder(counts));
        assertEquals("Follows you", chip.pill.text);
        assertEquals(FriendshipChip.color(Relation.FOLLOWS_YOU, false), chip.pill.color());
        FriendshipStatus.besidePronouns(pronouns, new Profile(false, true));
        assertEquals("Doesn't follow you", chip.pill.text);
        assertEquals(FriendshipChip.color(Relation.DOESNT_FOLLOW_YOU, false), chip.pill.color());
        assertEquals("the room is made once", ownBottom + chip.room, counts.getPaddingBottom());
    }

    /**
     * Each relation has its own color, and a light profile (dark gray pronouns, as Instagram's
     * light theme draws them) gets the darker shade of it so the words stay readable on white.
     */
    @Test
    public void aLightProfileGetsTheDarkerShades() {
        pronouns.setTextColor(0xFF737373);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertEquals(FriendshipChip.color(Relation.FOLLOWING_EACH_OTHER, true), chip.pill.color());
        FriendshipStatus.besidePronouns(pronouns, new Profile(false, true));
        assertEquals(FriendshipChip.color(Relation.DOESNT_FOLLOW_YOU, true), chip.pill.color());
        for (Relation relation : Relation.values()) {
            assertNotEquals(relation.name(), FriendshipChip.color(relation, true), FriendshipChip.color(relation, false));
            for (Relation other : Relation.values()) {
                if (other != relation) assertNotEquals(FriendshipChip.color(relation, true), FriendshipChip.color(other, true));
            }
        }
    }

    /** An account with no pronouns: the slot Instagram just hid stays hidden while the chip shows. */
    @Test
    public void withoutPronounsTheSlotStaysHidden() {
        pronouns.setVisibility(View.GONE);
        FriendshipStatus.inPlaceOfPronouns(pronouns, new Profile(true, null));
        assertEquals(View.GONE, pronouns.getVisibility());
        assertEquals("", pronouns.getText().toString());
        assertEquals("Follows you", FriendshipChip.shownUnder(counts).pill.text);
    }

    /** Switched off, paused or not known any more, the chip goes and the counts get their room back. */
    @Test
    public void theChipGoesWithItsSwitchOrThePause() {
        int ownBottom = counts.getPaddingBottom();
        int bioTop = layOut();
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));

        Settings.FRIENDSHIP_STATUS_CHIP.save(false);
        pronouns.setText("he/him");
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertNull(FriendshipChip.shownUnder(counts));
        assertFalse(FriendshipChip.anyShown());
        assertEquals(ownBottom, counts.getPaddingBottom());
        assertEquals(bioTop, layOut());
        assertEquals("the label is back by the name", "he/him" + FriendshipStatus.SEPARATOR + "Follows you", pronouns.getText().toString());

        Settings.FRIENDSHIP_STATUS_CHIP.save(true);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertNotNull(FriendshipChip.shownUnder(counts));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertNull("paused", FriendshipChip.shownUnder(counts));
        assertEquals(ownBottom, counts.getPaddingBottom());
        PauseForTests.resume();

        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        FriendshipStatus.besidePronouns(pronouns, new Profile(null, null));
        assertNull("Instagram no longer says", FriendshipChip.shownUnder(counts));
    }

    /** Padding Instagram sets on the counts after the chip went in becomes theirs, and the room goes on top. */
    @Test
    public void paddingInstagramSetsLaterIsKept() {
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        counts.setPadding(0, 4, 0, 20);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertEquals(20 + chip.room, counts.getPaddingBottom());
        Settings.FRIENDSHIP_STATUS_CHIP.save(false);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertEquals(20, counts.getPaddingBottom());
    }

    /** A header whose counts can't be found keeps the text label by the name. */
    @Test
    public void withoutCountsTheLabelStaysByTheName() {
        FriendshipChip.followersCountIdForTests = View.generateViewId();
        pronouns.setText("they/them");
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertEquals("they/them" + FriendshipStatus.SEPARATOR + "Follows you", pronouns.getText().toString());
        assertFalse(FriendshipChip.anyShown());

        pronouns.setText("");
        pronouns.setVisibility(View.GONE);
        FriendshipStatus.inPlaceOfPronouns(pronouns, new Profile(false, null));
        assertEquals("Doesn't follow you", pronouns.getText().toString());
        assertEquals(View.VISIBLE, pronouns.getVisibility());

        counts.setVisibility(View.GONE);
        FriendshipChip.followersCountIdForTests = followersId;
        assertNull("counts that aren't on screen", FriendshipChip.counts(pronouns));
    }

    /**
     * #157: Instagram hides the name of a profile with no name, and the label alone in the slot
     * beside it ran into the counts. The name comes back empty so the label keeps the name's line;
     * a named profile's name, and a nameless one's while the chip shows, are left as they are.
     */
    @Test
    public void aNamelessProfileKeepsTheNamesLineForTheLabel() {
        TextView name = (TextView) ((LinearLayout) pronouns.getParent()).getChildAt(0);
        name.setId(View.generateViewId());
        FriendshipStatus.fullNameIdForTests = name.getId();
        try {
            name.setVisibility(View.GONE);
            pronouns.setVisibility(View.GONE);
            FriendshipStatus.inPlaceOfPronouns(pronouns, new Profile(false, null));
            assertEquals("the chip shows, so the name stays hidden", View.GONE, name.getVisibility());

            Settings.FRIENDSHIP_STATUS_CHIP.save(false);
            name.setText("Someone else");
            FriendshipStatus.inPlaceOfPronouns(pronouns, new Profile(false, null));
            assertEquals("Doesn't follow you", pronouns.getText().toString());
            assertEquals(View.VISIBLE, pronouns.getVisibility());
            assertEquals("the name's line is back", View.VISIBLE, name.getVisibility());
            assertEquals("with nothing in it", "", name.getText().toString());
            assertTrue(String.join("\n", HookStatus.report()).contains(FriendshipStatus.NAME_LINE + " 1"));

            name.setText("Named");
            FriendshipStatus.inPlaceOfPronouns(pronouns, new Profile(true, null));
            assertEquals("a name showing is left alone", "Named", name.getText().toString());

            Settings.SHOW_FRIENDSHIP_STATUS.save(false);
            name.setVisibility(View.GONE);
            FriendshipStatus.inPlaceOfPronouns(pronouns, new Profile(false, null));
            assertEquals("switched off, it's as Instagram left it", View.GONE, name.getVisibility());
        } finally {
            FriendshipStatus.fullNameIdForTests = 0;
        }
    }

    /**
     * The map of chips is weak on the counts block, so its value mustn't hold the block, or any
     * view or context that leads back to it, strongly: the entry would keep the header and the
     * Activity alive, and anyShown() true, for as long as the app runs. Neither the chip nor its
     * drawing has a field that does, and the chip reaches its block through a weak reference.
     */
    @Test
    public void aChipDoesntKeepItsBlockAlive() {
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertNotNull(chip);
        assertSame(counts, chip.block.get());
        for (Class<?> type : new Class<?>[]{FriendshipChip.Chip.class, FriendshipChip.Pill.class}) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> held = field.getType();
                boolean leads = View.class.isAssignableFrom(held) || Context.class.isAssignableFrom(held)
                        || ViewParent.class.isAssignableFrom(held);
                assertFalse(type.getSimpleName() + "." + field.getName() + " holds a " + held.getName() + " strongly", leads);
                if (field.getName().equals("block")) assertTrue(Reference.class.isAssignableFrom(held));
            }
        }
    }

    /**
     * At twice the usual font size the words are taller than a 28dp pill leaves room for, so the
     * pill grows with them instead of its outline cutting them off.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void thePillGrowsWithTheFontSize() {
        FriendshipChip.Pill usual = new FriendshipChip.Pill(RuntimeEnvironment.getApplication());
        assertEquals("the usual pill is 28dp", usual.dp(28), usual.getIntrinsicHeight());
        try {
            RuntimeEnvironment.setFontScale(2f);
            FriendshipChip.Pill large = new FriendshipChip.Pill(RuntimeEnvironment.getApplication());
            assertTrue("words " + large.textHeight() + "px in a " + large.getIntrinsicHeight() + "px pill",
                    large.getIntrinsicHeight() >= large.textHeight() + large.dp(8));
            assertTrue(large.getIntrinsicHeight() > usual.getIntrinsicHeight());
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    /** Your own profile gets no chip. */
    @Test
    public void yourOwnProfileHasNoChip() {
        Profile own = new Profile(true, true);
        own.viewer = own.id;
        FriendshipStatus.besidePronouns(pronouns, own);
        assertFalse(FriendshipChip.anyShown());
    }

    /** Lays the header out on a phone-wide screen and answers where the bio starts. */
    private int layOut() {
        header.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        header.layout(0, 0, 1080, header.getMeasuredHeight());
        return bio.getTop();
    }

    /** A profile header that is its own user, with what Instagram has said about it. */
    static final class Profile {
        final Boolean followedBy;
        final Boolean following;
        final String id = "51234567";
        String viewer = "1";

        Profile(Boolean followedBy, Boolean following) {
            this.followedBy = followedBy;
            this.following = following;
        }
    }

    /** The stubs as the patch fills them, over [Profile] and a TextView as the slot. */
    @Implements(value = FriendshipStatus.class, isInAndroidSdk = false)
    public static class Patched {
        @Implementation protected static Object profileUser(Object header) { return header; }
        @Implementation protected static Boolean friendshipFollowedBy(Object user) { return ((Profile) user).followedBy; }
        @Implementation protected static Boolean friendshipFollowing(Object user) { return ((Profile) user).following; }
        @Implementation protected static Boolean followedBy(Object user) { return null; }
        @Implementation protected static String userId(Object user) { return ((Profile) user).id; }
        @Implementation protected static String viewerId(Object header) { return ((Profile) header).viewer; }
        @Implementation protected static View slotView(Object slot) { return (View) slot; }

        @Implementation
        protected static boolean setSlotVisibility(Object slot, int visibility) {
            ((View) slot).setVisibility(visibility);
            return true;
        }
    }
}
