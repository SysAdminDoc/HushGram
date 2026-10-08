/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewParent;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import app.hushgram.extension.instagram.profile.FriendshipStatus.Relation;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
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
        controller.close();
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
     * Under the counts, in the pronouns line's color, with room made so the bio moves down. The
     * pronouns stay as Instagram set them, and binding again changes the words, not the room.
     */
    @Test
    public void theChipGoesUnderTheCountsAndTheNameKeepsItsPronouns() {
        int bioTop = layOut();
        int ownBottom = counts.getPaddingBottom();
        pronouns.setText("she/her");

        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));

        assertEquals("she/her", pronouns.getText().toString());
        FriendshipChip.Chip chip = FriendshipChip.shownUnder(counts);
        assertNotNull(chip);
        assertEquals("Following each other", chip.pill.text);
        assertEquals(pronouns.getCurrentTextColor(), chip.pill.color());
        assertEquals(ownBottom + chip.room, counts.getPaddingBottom());
        assertEquals("the bio moves down by the chip's room", bioTop + chip.room, layOut());
        assertEquals("the chip sits in the room, at the counts' start", counts.getHeight() - ownBottom, chip.pill.getBounds().bottom);
        assertEquals(counts.getPaddingStart(), chip.pill.getBounds().left);
        assertEquals(chip.pill.getIntrinsicHeight(), chip.pill.getBounds().height());
        assertTrue(chip.pill.getBounds().top >= counts.getHeight() - counts.getPaddingBottom());

        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertSame(chip, FriendshipChip.shownUnder(counts));
        assertEquals("Follows you", chip.pill.text);
        FriendshipStatus.besidePronouns(pronouns, new Profile(false, true));
        assertEquals("Doesn't follow you", chip.pill.text);
        assertEquals("the room is made once", ownBottom + chip.room, counts.getPaddingBottom());
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
