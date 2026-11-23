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

import app.hushgram.extension.instagram.download.ProfileExtrasTest;
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
    private LinearLayout barLayout;
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
        barLayout = new LinearLayout(activity);
        TextView title = new TextView(activity);
        title.setId(View.generateViewId());
        title.setText("national_geographic_kids");
        barLayout.addView(title, new LinearLayout.LayoutParams(0, 120, 1f));
        FollowIcon.titleIdForTests = title.getId();
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
        FollowIcon.titleIdForTests = 0;
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

    /** Lays the whole top bar out across a 1080 pixel screen, the username taking what the buttons leave. */
    private void layOutBar() {
        for (int pass = 0; pass < 2; pass++) {
            barLayout.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(147, View.MeasureSpec.EXACTLY));
            barLayout.layout(0, 0, 1080, 147);
            ShadowLooper.idleMainLooper();
        }
    }

    /**
     * A business profile's bar holds more buttons before the menu (natgeo's on 456.1, where the icon left the
     * username 4 pixels): once a layout leaves the username too little room, the icon keeps only a little side room.
     * The window is a phone's 1080 pixels too, since the activity lays the bar out at its own width first.
     */
    @Test
    @Config(qualifiers = "w1080dp-h1920dp")
    public void aCrowdedBarGivesTheUsernameItsRoomBack() {
        float density = controller.get().getResources().getDisplayMetrics().density;
        FriendshipStatus.besidePronouns(pronouns, new Profile(false, null));
        layOutBar();
        assertIcon(false);
        assertEquals("a roomy bar keeps the usual side room", Math.round(FollowIcon.SIDE_DP * density),
                FollowIcon.iconIn(buttons).getPaddingLeft());
        assertFalse(FollowIcon.squeezed(buttons));

        for (int added = 0; added < 6; added++) buttons.addView(button(controller.get(), "Button " + added), 0);
        layOutBar();
        ImageView icon = FollowIcon.iconIn(buttons);
        assertNotNull(icon);
        assertEquals("a crowded bar gets the narrow icon", Math.round(FollowIcon.TIGHT_SIDE_DP * density), icon.getPaddingLeft());
        assertEquals(icon.getPaddingLeft(), icon.getPaddingRight());
        assertSame("still just before the menu", icon, buttons.getChildAt(buttons.getChildCount() - 2));
        assertSame(menu, buttons.getChildAt(buttons.getChildCount() - 1));
        assertFalse(((FollowBadge) icon.getDrawable()).followsYou);
    }

    /**
     * natgeo's bar on 456.1 (#842): Jump to a date, HushGram's profile actions, About this account and the bell before
     * the menu. With the icon in, even at its narrowest, the username kept about 60 of the 157 pixels it needs, so the
     * mark moves onto the profile actions button as a small badge and the icon leaves the bar.
     */
    @Test
    @Config(qualifiers = "w1080dp-h1920dp")
    public void aCrowdedBarWithTheActionsButtonPutsTheMarkOnItAndGivesTheUsernameItsWholeRoom() {
        ImageView actions = ProfileExtrasTest.actionsButton(controller.get());
        actions.setLayoutParams(new LinearLayout.LayoutParams(127, 147));
        buttons.addView(actions, 0);
        for (int added = 0; added < 5; added++) buttons.addView(button(controller.get(), "Button " + added), 0);
        TextView title = barLayout.findViewById(FollowIcon.titleIdForTests);
        layOutBar();
        int without = title.getWidth();
        float density = controller.get().getResources().getDisplayMetrics().density;
        assertTrue("the bar leaves the username its room without the icon", without >= FollowIcon.TITLE_ROOM_DP * density);

        FriendshipStatus.besidePronouns(pronouns, new Profile(false, null));
        layOutBar();

        assertNull("the icon leaves a crowded bar", FollowIcon.iconIn(buttons));
        assertEquals("only Instagram's buttons and the actions button are left", 8, buttons.getChildCount());
        FollowIcon.Corner corner = FollowIcon.cornerOn(actions);
        assertNotNull("the actions button carries the mark", corner);
        assertFalse(corner.followsYou);
        assertEquals("HushGram profile actions, Doesn't follow you", actions.getContentDescription().toString());
        assertEquals("the username keeps all the room the buttons leave", without, title.getWidth());
        assertTrue(FollowIcon.anyShown());
        assertEquals("no words by the name", "", pronouns.getText().toString());

        // A bind again with a profile that now follows you swaps the badge, and the button's own words stay first.
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        layOutBar();
        assertTrue(FollowIcon.cornerOn(actions).followsYou);
        assertEquals("HushGram profile actions, Follows you", actions.getContentDescription().toString());
        assertNull(FollowIcon.iconIn(buttons));

        // Turned off, the badge comes off and the button gets its own description back.
        Settings.FOLLOW_ICON_AT_TOP.save(false);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        assertNull(FollowIcon.cornerOn(actions));
        assertEquals("HushGram profile actions", actions.getContentDescription().toString());
        assertEquals("Follows you", pronouns.getText().toString());
    }

    /** A roomy bar keeps the separate icon even when it has the profile actions button. */
    @Test
    @Config(qualifiers = "w1080dp-h1920dp")
    public void aRoomyBarWithTheActionsButtonKeepsTheSeparateIcon() {
        ImageView actions = ProfileExtrasTest.actionsButton(controller.get());
        actions.setLayoutParams(new LinearLayout.LayoutParams(127, 147));
        buttons.addView(actions, 0);
        FriendshipStatus.besidePronouns(pronouns, new Profile(true, false));
        layOutBar();
        assertNull("no badge on a roomy bar", FollowIcon.cornerOn(actions));
        ImageView icon = FollowIcon.iconIn(buttons);
        assertNotNull(icon);
        assertSame("just before the menu", icon, buttons.getChildAt(buttons.getChildCount() - 2));
        assertEquals("HushGram profile actions", actions.getContentDescription().toString());
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
