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
import android.app.AlertDialog;
import android.view.View;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashMap;

import app.hushgram.extension.instagram.profile.FriendshipChipTest.Profile;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Show it as an icon at the top (#842): the follow icon by the menu in a profile's top bar. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = FriendshipChipTest.Patched.class)
public class FollowIconTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private FrameLayout screen;
    private LinearLayout buttons;
    private ImageView bell;
    private ImageView menu;
    private TextView pronouns;
    private LinearLayout counts;
    private int buttonsId;
    private int followersId;

    /**
     * A profile screen laid out as Instagram's is: its own frame holding the top bar, whose row of
     * buttons ends in the menu, and beside the bar the list the header scrolls in.
     */
    @Before
    public void build() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        screen = new FrameLayout(activity);
        FrameLayout bar = new FrameLayout(activity);
        LinearLayout barLayout = new LinearLayout(activity);
        barLayout.addView(new TextView(activity), new LinearLayout.LayoutParams(0, 120, 1f));
        buttons = new LinearLayout(activity);
        buttonsId = View.generateViewId();
        buttons.setId(buttonsId);
        bell = button(activity, "Open notification settings");
        menu = button(activity, "Options");
        buttons.addView(bell);
        buttons.addView(menu);
        barLayout.addView(buttons);
        bar.addView(barLayout);
        screen.addView(bar);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout name = new LinearLayout(activity);
        name.addView(new TextView(activity));
        pronouns = new TextView(activity);
        name.addView(pronouns);
        header.addView(name);
        counts = new LinearLayout(activity);
        TextView followers = new TextView(activity);
        followersId = View.generateViewId();
        followers.setId(followersId);
        followers.setText("120");
        counts.addView(followers);
        header.addView(counts);
        list.addView(header);
        screen.addView(list);
        activity.setContentView(screen);
        FollowIcon.buttonsIdForTests = buttonsId;
        FriendshipChip.followersCountIdForTests = followersId;
        Settings.FOLLOW_ICON_AT_TOP.save(true);
    }

    private static ImageView button(Activity activity, String description) {
        ImageView button = new ImageView(activity);
        button.setContentDescription(description);
        button.setLayoutParams(new LinearLayout.LayoutParams(127, 147));
        return button;
    }

    @After
    public void restore() {
        Settings.FOLLOW_ICON_AT_TOP.resetToDefault();
        Settings.FRIENDSHIP_STATUS_CHIP.resetToDefault();
        Settings.SHOW_FRIENDSHIP_STATUS.resetToDefault();
        PauseForTests.resume();
        // Takes any icon and chip away, so the next test starts with none showing.
        FollowIcon.buttonsIdForTests = buttonsId;
        FriendshipChip.followersCountIdForTests = followersId;
        FriendshipStatus.besidePronouns(pronouns, new Profile(null, null));
        assertFalse(FollowIcon.anyShown());
        assertFalse(FriendshipChip.anyShown());
        FollowIcon.buttonsIdForTests = 0;
        FriendshipChip.followersCountIdForTests = 0;
        HookStatus.clear();
        controller.close();
    }

    /** Lays [view] out as its parent would after Instagram changes what it holds. */
    private static void layOut(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(147, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        ShadowLooper.idleMainLooper();
    }

    private void assertIcon(boolean followsYou) {
        ImageView icon = FollowIcon.iconIn(buttons);
        assertNotNull("the top bar has the icon", icon);
        assertEquals("one icon, between the bell and the menu", 3, buttons.getChildCount());
        assertSame(bell, buttons.getChildAt(0));
        assertSame(icon, buttons.getChildAt(1));
        assertSame("the menu stays last", menu, buttons.getChildAt(2));
        assertTrue(icon.getDrawable() instanceof FollowBadge);
        assertEquals(followsYou, ((FollowBadge) icon.getDrawable()).followsYou);
        assertEquals(followsYou ? "Follows you" : "Doesn't follow you", icon.getContentDescription().toString());
        assertEquals("no words by the name", "", pronouns.getText().toString());
    }

    @Test
    public void aProfileThatFollowsYouGetsAGreenCheckBeforeTheMenu() {
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertIcon(true);
        assertTrue(FollowIcon.anyShown());
    }

    @Test
    public void aProfileThatDoesntFollowYouGetsARedCrossAndABindAgainSwapsIt() {
        FriendshipStatus.besidePronouns(pronouns, new Profile(false, null));
        assertIcon(false);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, true));
        assertIcon(true);
    }

    /** With no pronouns, the slot Instagram hid stays hidden: the icon says it instead. */
    @Test
    public void theSlotForAProfileWithNoPronounsStaysHidden() {
        pronouns.setVisibility(View.GONE);
        FriendshipStatus.inPlaceOfPronouns(pronouns, new Profile(true, false));
        assertIcon(true);
        assertEquals(View.GONE, pronouns.getVisibility());
    }

    @Test
    public void theIconStandsInForTheChip() {
        Settings.FOLLOW_ICON_AT_TOP.save(false);
        Settings.FRIENDSHIP_STATUS_CHIP.save(true);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertNotNull("the chip shows with the icon off", FriendshipChip.shownUnder(counts));

        Settings.FOLLOW_ICON_AT_TOP.save(true);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertIcon(true);
        assertNull("the icon takes the chip's place", FriendshipChip.shownUnder(counts));
    }

    @Test
    public void turningItOffTakesTheIconAwayAndPutsTheWordsBack() {
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertIcon(true);
        Settings.FOLLOW_ICON_AT_TOP.save(false);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertNull(FollowIcon.iconIn(buttons));
        assertEquals(2, buttons.getChildCount());
        assertEquals("Follows you", pronouns.getText().toString());
        assertFalse(FollowIcon.anyShown());
    }

    @Test
    public void turningOffShowIfAProfileFollowsYouTakesTheIconAway() {
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertIcon(true);
        Settings.SHOW_FRIENDSHIP_STATUS.save(false);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertNull(FollowIcon.iconIn(buttons));
        assertEquals("", pronouns.getText().toString());
    }

    @Test
    public void yourOwnProfileGetsNoIcon() {
        Profile own = new Profile(true, true);
        own.viewer = own.id;
        FriendshipStatus.besidePronouns(pronouns, own);
        assertNull(FollowIcon.iconIn(buttons));
        assertEquals(2, buttons.getChildCount());
    }

    /** Instagram filling the bar's row again as it sets the bar up, after the header was bound. */
    @Test
    public void aRowFilledAgainGetsTheIconBackBeforeTheMenu() {
        FriendshipStatus.besidePronouns(pronouns, new Profile(false, null));
        assertIcon(false);
        buttons.removeAllViews();
        buttons.addView(bell);
        buttons.addView(menu);
        assertNull(FollowIcon.iconIn(buttons));
        layOut(buttons);
        assertIcon(false);

        // A button added at the end moves the icon up to sit just before it.
        ImageView added = button(controller.get(), "More");
        buttons.addView(added);
        layOut(buttons);
        assertEquals(4, buttons.getChildCount());
        assertSame(added, buttons.getChildAt(3));
        assertSame(FollowIcon.iconIn(buttons), buttons.getChildAt(2));
        assertSame(menu, buttons.getChildAt(1));
    }

    @Test
    public void aProfileWithNoTopBarKeepsTheWords() {
        FollowIcon.buttonsIdForTests = View.generateViewId();
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertEquals("Follows you", pronouns.getText().toString());
        assertEquals(2, buttons.getChildCount());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FollowIcon.NO_TOP_BAR + " 1"));
    }

    @Test
    public void tappingTheIconListsTheFriendshipFlags() {
        ShadowAlertDialog.reset();
        Profile profile = new Profile(true, false);
        profile.screen = new HashMap<>();
        profile.screen.put("followed_by".hashCode(), true);
        profile.screen.put("following".hashCode(), false);
        FriendshipStatus.besidePronouns(pronouns, profile);
        ImageView icon = FollowIcon.iconIn(buttons);
        assertNotNull(icon);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());

        assertTrue(icon.performClick());

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("a tap on the icon opens the list", dialog);
        assertTrue(dialog.isShowing());
        dialog.dismiss();
    }
}
