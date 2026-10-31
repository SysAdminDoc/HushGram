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

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the profile suggestion hooks answer, and that each one leaves Instagram's answer when it can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfileSuggestionsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_PROFILE_SUGGESTIONS.save(true);
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_PROFILE_SUGGESTIONS.resetToDefault();
        Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.resetToDefault();
        Settings.HIDE_PROFILE_COMPLETION.resetToDefault();
        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.resetToDefault();
        Settings.HIDE_NOTIFICATION_SUGGESTIONS.resetToDefault();
        Settings.HIDE_NOTIFICATION_FEATURED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** On, the inline row reads as having nothing to show, the standalone row is left out and the button goes. */
    @Test
    public void withTheSwitchOnEverySuggestionIsHidden() {
        View button = new View(context);

        assertEquals(0, ProfileSuggestions.inlineRow(1));
        assertEquals(0, ProfileSuggestions.keepStandaloneRow());
        ProfileSuggestions.chainingButton(button);

        assertEquals(View.GONE, button.getVisibility());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.ROUTE));
        assertTrue(report, report.contains("3 removed"));
        assertTrue(report, report.contains(ProfileSuggestions.INLINE_ROW));
        assertTrue(report, report.contains(ProfileSuggestions.STANDALONE_ROW));
        assertTrue(report, report.contains(ProfileSuggestions.CHAINING_BUTTON));
    }

    /** Off, every hook hands back what Instagram had, and the hooks still count that they ran. */
    @Test
    public void withTheSwitchOffInstagramsAnswersStand() {
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        View button = new View(context);

        assertEquals(1, ProfileSuggestions.inlineRow(1));
        assertEquals(1, ProfileSuggestions.keepStandaloneRow());
        ProfileSuggestions.chainingButton(button);

        assertEquals(View.VISIBLE, button.getVisibility());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("0 removed"));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.report().toString().contains(FamilyNames.PROFILE_SUGGESTIONS));
    }

    /** A row Instagram already had nothing for stays that way, whatever the switch says, and no button is no work. */
    @Test
    public void nothingToHideIsLeftAsItIs() {
        assertEquals(0, ProfileSuggestions.inlineRow(0));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        assertEquals(0, ProfileSuggestions.inlineRow(0));
        ProfileSuggestions.chainingButton(null);
        assertTrue(FeedFilterCounters.report().toString(), FeedFilterCounters.report().isEmpty());
    }

    /**
     * Instagram reuses the button and never sets its visibility, so one this hid gets its own
     * visibility back once the switch is off, and one it never touched keeps whatever it has.
     */
    @Test
    public void aButtonHiddenEarlierComesBackWhenTheSwitchGoesOff() {
        View shown = new View(context);
        View invisible = new View(context);
        invisible.setVisibility(View.INVISIBLE);
        View untouched = new View(context);
        untouched.setVisibility(View.GONE);

        ProfileSuggestions.chainingButton(shown);
        ProfileSuggestions.chainingButton(invisible);
        ProfileSuggestions.chainingButton(shown);
        assertEquals(View.GONE, shown.getVisibility());
        assertEquals(View.GONE, invisible.getVisibility());

        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        ProfileSuggestions.chainingButton(shown);
        ProfileSuggestions.chainingButton(invisible);
        ProfileSuggestions.chainingButton(untouched);

        assertEquals("a second bind while hidden wrote GONE over the button's own visibility", View.VISIBLE, shown.getVisibility());
        assertEquals(View.INVISIBLE, invisible.getVisibility());
        assertEquals(View.GONE, untouched.getVisibility());
    }

    /** Paused, or called before the settings are read, every hook hands back what Instagram had. */
    @Test
    public void offPausedAndUnreadyLeaveInstagramsAnswers() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        View paused = new View(context);
        assertEquals(1, ProfileSuggestions.inlineRow(1));
        assertEquals(1, ProfileSuggestions.keepStandaloneRow());
        ProfileSuggestions.chainingButton(paused);
        assertEquals(View.VISIBLE, paused.getVisibility());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        View unready = new View(context);
        SettingsContextRule.withoutContext(() -> {
            assertEquals(1, ProfileSuggestions.inlineRow(1));
            assertEquals(1, ProfileSuggestions.keepStandaloneRow());
            ProfileSuggestions.chainingButton(unready);
        });
        assertEquals(View.VISIBLE, unready.getVisibility());
    }

    /** A switch that throws leaves every answer as Instagram's and says which hook threw. */
    @Test
    public void aThrowingSwitchFailsOpenAndIsReported() {
        View button = new View(context);

        assertEquals(1, ProfileSuggestions.inlineRow(1, THROWS));
        assertEquals(1, ProfileSuggestions.keepStandaloneRow(THROWS));
        ProfileSuggestions.chainingButton(button, THROWS);

        assertEquals(View.VISIBLE, button.getVisibility());
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.INLINE_ROW + "'"));
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.STANDALONE_ROW + "'"));
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.CHAINING_BUTTON + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** A button that throws when hidden is left to Instagram, and the failure is reported, not thrown. */
    @Test
    public void aButtonThatThrowsIsLeftAndReported() {
        View button = new View(context) {
            @Override
            public void setVisibility(int visibility) {
                throw new UnsupportedOperationException("detached");
            }
        };

        ProfileSuggestions.chainingButton(button, () -> true);

        assertEquals(View.VISIBLE, button.getVisibility());
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.CHAINING_BUTTON + "'"));
        assertTrue(missing, missing.contains(UnsupportedOperationException.class.getName()));
    }

    /**
     * #87: with the person-plus button hidden, the text buttons Instagram sized to leave room for it
     * share that room in equal parts, so no gap sits at the end of the row. Turned off again, they go
     * back to Instagram's own widths and the button takes its place.
     */
    @Test
    public void theRowsOtherButtonsTakeTheHiddenButtonsSpace() {
        LinearLayout row = row();
        View edit = add(row, "generic", 400);
        View share = add(row, "generic", 400);
        View button = add(row, "chaining_icon", 150);
        assertEquals("Instagram's own widths, room left for the button", List.of(400, 400, 150), measure(row, edit, share, button));

        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals(View.GONE, button.getVisibility());
        assertEquals("the gap split between the two", List.of(500, 500), measure(row, edit, share));
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals("a second bind changes nothing more", List.of(500, 500), measure(row, edit, share));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ProfileSuggestions.ROW_SPREAD + " 1"));

        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals("Instagram's widths again", List.of(400, 400, 150), measure(row, edit, share, button));
        assertEquals(0f, ((LinearLayout.LayoutParams) edit.getLayoutParams()).weight, 0f);
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ProfileSuggestions.ROW_PUT_BACK + " 1"));
    }

    /** A recycled button needs its share again after Instagram resets or replaces its layout params. */
    @Test
    public void refreshedActionButtonsKeepSharingTheHiddenSpace() {
        LinearLayout row = row();
        View edit = add(row, "generic", 400);
        View share = add(row, "generic", 400);
        View button = add(row, "chaining_icon", 150);

        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals(List.of(500, 500), measure(row, edit, share));

        ((LinearLayout.LayoutParams) edit.getLayoutParams()).weight = 0;
        share.setLayoutParams(new LinearLayout.LayoutParams(400, 100));
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals("both forms of native rebind keep filling the row", List.of(500, 500), measure(row, edit, share));

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals(List.of(400, 400, 150), measure(row, edit, share, button));
    }

    /**
     * #220: a refresh rebinds the row and asks for a layout before the button is set up, and that
     * layout drew a frame at Instagram's widths before a posted fit ran, so the buttons twitched.
     * The fit runs before that frame draws now, nothing posted has to run first, and the frame after
     * it draws as usual. dispatchOnPreDraw answers whether the frame is canceled.
     */
    @Test
    public void aRefreshedRowIsSpreadBeforeItsNextFrameDraws() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout row = row();
        View edit = add(row, "generic", 400);
        View share = add(row, "generic", 400);
        View button = add(row, "chaining_icon", 150);
        activity.setContentView(row);
        ViewTreeObserver frame = row.getViewTreeObserver();

        ProfileSuggestions.chainingButton(button);
        assertTrue("the frame that would show the gap is skipped", frame.dispatchOnPreDraw());
        assertEquals(List.of(500, 500), measure(row, edit, share));
        assertFalse("and the next one draws", frame.dispatchOnPreDraw());

        share.setLayoutParams(new LinearLayout.LayoutParams(400, 100));
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.chainingButton(button);
        assertTrue(frame.dispatchOnPreDraw());
        assertEquals("the refresh's new params are spread before they draw", List.of(500, 500), measure(row, edit, share));
        assertFalse("two binds before a frame skip it once", frame.dispatchOnPreDraw());

        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        ProfileSuggestions.chainingButton(button);
        assertTrue(frame.dispatchOnPreDraw());
        assertEquals("put back before the button draws again", List.of(400, 400, 150), measure(row, edit, share, button));
        assertFalse(frame.dispatchOnPreDraw());
    }

    /** Replacement native weights belong to Instagram even if they equal the weight we used before. */
    @Test
    public void refreshedNativeWeightsAreNotTakenBackOnSwitchOff() {
        LinearLayout row = row();
        View edit = add(row, "generic", 400);
        View share = add(row, "generic", 400);
        View button = add(row, "chaining_icon", 150);
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);

        edit.setLayoutParams(new LinearLayout.LayoutParams(300, 100, 1));
        ((LinearLayout.LayoutParams) share.getLayoutParams()).weight = 2;
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);

        assertEquals(1f, ((LinearLayout.LayoutParams) edit.getLayoutParams()).weight, 0f);
        assertEquals(2f, ((LinearLayout.LayoutParams) share.getLayoutParams()).weight, 0f);
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    /** Instagram may replace params and turn the switch off before another hidden-row bind. */
    @Test
    public void refreshedNativeWeightsAreNotTakenBackOnPauseBeforeRebind() {
        LinearLayout row = row();
        View edit = add(row, "generic", 400);
        View button = add(row, "chaining_icon", 150);
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);

        edit.setLayoutParams(new LinearLayout.LayoutParams(400, 100, 1));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);

        assertEquals(1f, ((LinearLayout.LayoutParams) edit.getLayoutParams()).weight, 0f);
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    /**
     * An icon button Instagram keeps at its own width (Saved, the overflow menu) stays that width,
     * and a button Instagram weighted itself keeps its weight when the row is put back.
     */
    @Test
    public void iconButtonsAndInstagramsOwnWeightsAreLeftAlone() {
        LinearLayout row = row();
        View follow = add(row, "generic", 300);
        View saved = add(row, "saved", 100);
        View weighted = add(row, "generic", 300);
        ((LinearLayout.LayoutParams) weighted.getLayoutParams()).weight = 2;
        View button = add(row, "chaining_icon", 150);

        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals("only the unweighted text button grows here", List.of(300 + 300 / 3, 100, 300 + 600 / 3),
                measure(row, follow, saved, weighted));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals(0f, ((LinearLayout.LayoutParams) follow.getLayoutParams()).weight, 0f);
        assertEquals(2f, ((LinearLayout.LayoutParams) weighted.getLayoutParams()).weight, 0f);
        assertEquals(0f, ((LinearLayout.LayoutParams) saved.getLayoutParams()).weight, 0f);
    }

    /** A button outside a row, or in a row that runs down the screen, changes nothing around it. */
    @Test
    public void aButtonOutsideAHorizontalRowChangesNothing() {
        View alone = new View(context);
        ProfileSuggestions.chainingButton(alone);
        ProfileSuggestions.fitRow(alone);
        LinearLayout column = row();
        column.setOrientation(LinearLayout.VERTICAL);
        View text = add(column, "generic", 400);
        View button = add(column, "chaining_icon", 150);
        ProfileSuggestions.chainingButton(button);
        ProfileSuggestions.fitRow(button);
        assertEquals(0f, ((LinearLayout.LayoutParams) text.getLayoutParams()).weight, 0f);
        assertFalse(HookStatus.report().toString(), HookStatus.report().toString().contains(ProfileSuggestions.ROW_SPREAD));
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private View add(LinearLayout row, String tag, int width) {
        View view = new View(context);
        view.setTag(tag);
        row.addView(view, new LinearLayout.LayoutParams(width, 100));
        return view;
    }

    /** The widths [views] get in a row 1000 px wide, as a phone measures it. */
    private static List<Integer> measure(LinearLayout row, View... views) {
        row.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        List<Integer> widths = new ArrayList<>();
        for (View view : views) widths.add(view.getMeasuredWidth());
        return widths;
    }

    /**
     * The follow list's section goes only with its own switch: on, the list's fetch reads as no and
     * is counted, with the profile switch on or off; off, paused or unready, the list's own answer
     * stands, and a list that wasn't going to fetch stays that way.
     */
    @Test
    public void theFollowListSectionGoesOnlyWithItsOwnSwitch() {
        assertEquals("the profile switch alone", 1, ProfileSuggestions.followList(1));

        Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.save(true);
        assertEquals(0, ProfileSuggestions.followList(1));
        assertEquals(0, ProfileSuggestions.followList(0));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.ROUTE));
        assertTrue(report, report.contains("1 removed"));
        assertTrue(report, report.contains(ProfileSuggestions.FOLLOW_LIST));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        assertEquals("with the profile switch off", 0, ProfileSuggestions.followList(1));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1, ProfileSuggestions.followList(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("unready", 1, ProfileSuggestions.followList(1)));

        Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.save(false);
        assertEquals("off", 1, ProfileSuggestions.followList(1));
    }

    /** A follow list switch that throws leaves the list's answer and says which hook threw. */
    @Test
    public void aThrowingFollowListSwitchFailsOpenAndIsReported() {
        assertEquals(1, ProfileSuggestions.followList(1, THROWS));
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.FOLLOW_LIST + "'"));
    }

    /**
     * #302: the follow list switch also takes the Suggested tab off someone else's follow lists and
     * the Suggested for you section under their mutual followers. Off, paused or unready, both stay.
     */
    @Test
    public void theSuggestedTabAndTheMutualSectionGoWithTheFollowListSwitch() {
        List<Object> accounts = Arrays.asList(new Object(), new Object());
        assertEquals("the profile switch alone", 1, ProfileSuggestions.suggestedTab(1));
        assertSame("the profile switch alone", accounts, ProfileSuggestions.mutualSuggestions(accounts));

        Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.save(true);
        assertEquals(0, ProfileSuggestions.suggestedTab(1));
        assertEquals("no tab stays no tab", 0, ProfileSuggestions.suggestedTab(0));
        assertTrue(ProfileSuggestions.mutualSuggestions(accounts).isEmpty());
        assertEquals("the list keeps its accounts", 2, accounts.size());
        List<Object> none = Collections.emptyList();
        assertSame("nothing to hide", none, ProfileSuggestions.mutualSuggestions(none));
        assertNull(ProfileSuggestions.mutualSuggestions(null));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.SUGGESTED_TAB));
        assertTrue(report, report.contains(ProfileSuggestions.MUTUAL_SUGGESTIONS));
        assertTrue(report, report.contains("2 removed"));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        assertEquals("with the profile switch off", 0, ProfileSuggestions.suggestedTab(1));
        assertTrue("with the profile switch off", ProfileSuggestions.mutualSuggestions(accounts).isEmpty());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1, ProfileSuggestions.suggestedTab(1));
        assertSame("paused", accounts, ProfileSuggestions.mutualSuggestions(accounts));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertEquals("unready", 1, ProfileSuggestions.suggestedTab(1));
            assertSame("unready", accounts, ProfileSuggestions.mutualSuggestions(accounts));
        });

        Settings.HIDE_FOLLOW_LIST_SUGGESTIONS.save(false);
        assertEquals("off", 1, ProfileSuggestions.suggestedTab(1));
        assertSame("off", accounts, ProfileSuggestions.mutualSuggestions(accounts));
    }

    /** A follow list switch that throws keeps the tab and the section, and says which hook threw. */
    @Test
    public void aThrowingSwitchKeepsTheSuggestedTabAndTheMutualSection() {
        List<Object> accounts = Collections.singletonList(new Object());
        assertEquals(1, ProfileSuggestions.suggestedTab(1, THROWS));
        assertSame(accounts, ProfileSuggestions.mutualSuggestions(accounts, THROWS));
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.SUGGESTED_TAB + "'"));
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.MUTUAL_SUGGESTIONS + "'"));
    }

    /**
     * #159: the Complete your profile cards go only with their own switch. A hidden set is counted,
     * with the profile switch on or off; off, paused or unready, Instagram's own check stands.
     */
    @Test
    public void theCompletionCardsGoOnlyWithTheirOwnSwitch() {
        assertFalse("the profile switch alone", ProfileSuggestions.hideCompletionCards());

        Settings.HIDE_PROFILE_COMPLETION.save(true);
        assertTrue(ProfileSuggestions.hideCompletionCards());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.ROUTE));
        assertTrue(report, report.contains("1 removed"));
        assertTrue(report, report.contains(ProfileSuggestions.COMPLETION_CARDS));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        assertTrue("with the profile switch off", ProfileSuggestions.hideCompletionCards());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ProfileSuggestions.hideCompletionCards());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("unready", ProfileSuggestions.hideCompletionCards()));

        Settings.HIDE_PROFILE_COMPLETION.save(false);
        assertFalse("off", ProfileSuggestions.hideCompletionCards());
    }

    /** A completion switch that throws leaves Instagram's check alone and says which hook threw. */
    @Test
    public void aThrowingCompletionSwitchFailsOpenAndIsReported() {
        assertFalse(ProfileSuggestions.hideCompletionCards(THROWS));
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.COMPLETION_CARDS + "'"));
    }

    /**
     * #161: People you don't follow back goes only with its own switch. On, the parsed accounts come
     * back null, as if the reply had none, and that's counted, with the profile switch on or off; off,
     * paused or unready, the same list comes back. A missing or empty list passes as it came, with no
     * removal counted, though the report still shows the hook ran.
     */
    @Test
    public void peopleYouDontFollowBackGoOnlyWithTheirOwnSwitch() {
        assertNull(ProfileSuggestions.followBacks(null));
        String ran = String.join("\n", HookStatus.report());
        assertTrue("an empty answer still shows the hook ran: " + ran, ran.contains(FamilyNames.PROFILE_SUGGESTIONS));

        List<String> accounts = new ArrayList<>(Arrays.asList("one", "two"));
        assertSame("the profile switch alone", accounts, ProfileSuggestions.followBacks(accounts));

        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.save(true);
        assertNull(ProfileSuggestions.followBacks(accounts));
        assertEquals("the list itself is left alone", 2, accounts.size());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.ROUTE));
        assertTrue(report, report.contains("1 removed"));
        assertTrue(report, report.contains(ProfileSuggestions.FOLLOW_BACKS));
        assertNull(ProfileSuggestions.followBacks(null));
        List<String> none = new ArrayList<>();
        assertSame(none, ProfileSuggestions.followBacks(none));
        report = FeedFilterCounters.report().toString();
        assertTrue("nothing to hide isn't counted: " + report, report.contains("1 removed"));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        assertNull("with the profile switch off", ProfileSuggestions.followBacks(accounts));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", accounts, ProfileSuggestions.followBacks(accounts));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame("unready", accounts, ProfileSuggestions.followBacks(accounts)));

        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.save(false);
        assertSame("off", accounts, ProfileSuggestions.followBacks(accounts));
    }

    /** A follow back switch that throws hands the accounts back and says which hook threw. */
    @Test
    public void aThrowingFollowBackSwitchFailsOpenAndIsReported() {
        List<String> accounts = Collections.singletonList("one");
        assertSame(accounts, ProfileSuggestions.followBacks(accounts, THROWS));
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.FOLLOW_BACKS + "'"));
    }

    /**
     * #161 on the newer reply: the notifications' builder skips a section typed FOLLOW_BACK only while
     * Hide People you don't follow back is on, and counts it. Any other section, or no type, is drawn.
     * Off, paused or unready, Instagram draws them all. Every call shows in the report, any type.
     */
    @Test
    public void theFollowBackSectionGoesOnlyWithItsOwnSwitch() {
        assertFalse("the profile switch alone", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE));
        String ran = String.join("\n", HookStatus.report());
        assertTrue(ran, ran.contains(ProfileSuggestions.SECTIONS));

        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.save(true);
        assertTrue(ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE));
        assertFalse("another section", ProfileSuggestions.hidesSuggestionSection("SCHOOLMATES"));
        assertFalse("follow requests", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_REQUEST_TYPE));
        assertFalse("no type", ProfileSuggestions.hidesSuggestionSection(null));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.FOLLOW_BACKS));
        assertTrue("only the follow back section is counted: " + report, report.contains("1 removed"));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        assertTrue("with the profile switch off", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("unready", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE)));

        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.save(false);
        assertFalse("off", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE));
    }

    /**
     * #112: with Hide suggestions in notifications on, the builder skips Suggested for you, typed
     * SUGGESTED_USERS or untyped, SCHOOLMATES and a type it hasn't seen, and counts each. Follow
     * requests always stay, and People you don't follow back keeps to its own switch. Off, paused or
     * unready, Instagram draws them all. Each type shows in the report.
     */
    @Test
    public void suggestionSectionsInNotificationsGoWithTheirSwitch() {
        assertFalse("off", ProfileSuggestions.hidesSuggestionSection("SUGGESTED_USERS"));
        assertFalse("off, no type", ProfileSuggestions.hidesSuggestionSection(null));

        Settings.HIDE_NOTIFICATION_SUGGESTIONS.save(true);
        assertTrue(ProfileSuggestions.hidesSuggestionSection("SUGGESTED_USERS"));
        assertTrue("no type", ProfileSuggestions.hidesSuggestionSection(null));
        assertTrue(ProfileSuggestions.hidesSuggestionSection("SCHOOLMATES"));
        assertTrue("a new type", ProfileSuggestions.hidesSuggestionSection("SOMETHING_NEW"));
        assertFalse("follow requests", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_REQUEST_TYPE));
        assertFalse("people you don't follow back", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.SECTION_TYPES));
        assertTrue(report, report.contains("SUGGESTED_USERS"));
        assertTrue(report, report.contains(ProfileSuggestions.NO_TYPE));
        assertTrue(report, report.contains(ProfileSuggestions.FOLLOW_REQUEST_TYPE));
        assertTrue(report, report.contains("Removed: " + ProfileSuggestions.SUGGESTED_SECTIONS + " 4"));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        assertTrue("with the profile switch off", ProfileSuggestions.hidesSuggestionSection(null));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ProfileSuggestions.hidesSuggestionSection("SUGGESTED_USERS"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("unready", ProfileSuggestions.hidesSuggestionSection("SUGGESTED_USERS")));

        Settings.HIDE_FOLLOW_BACK_SUGGESTIONS.save(true);
        Settings.HIDE_NOTIFICATION_SUGGESTIONS.save(false);
        assertFalse("off again", ProfileSuggestions.hidesSuggestionSection("SUGGESTED_USERS"));
        assertTrue("the follow back switch alone", ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE));
    }

    /** A section switch that throws while the builder asks leaves the section to Instagram and says which hook threw. */
    @Test
    public void aThrowingSectionSwitchFailsOpenAndIsReported() {
        assertFalse(ProfileSuggestions.hidesSuggestionSection(ProfileSuggestions.FOLLOW_BACK_TYPE, THROWS, () -> true));
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.FOLLOW_BACKS + "'"));
        assertFalse(ProfileSuggestions.hidesSuggestionSection("SUGGESTED_USERS", () -> true, THROWS));
        missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.SUGGESTED_SECTIONS + "'"));
    }

    /**
     * #181: the kinds the S22's inbox sent for "who you might know", "Check out your new follow
     * suggestions" and a suggested thread are suggestions, and so is any Threads kind naming an account
     * you aren't connected to or a suggestion. Likes, comments, follows and Threads replies aren't.
     */
    @Test
    public void onlyKindsThatSuggestSomethingAreSuggestions() {
        assertTrue(ProfileSuggestions.isSuggestion("connect_lowlness"));
        assertTrue("#112's Follow suggestions row", ProfileSuggestions.isSuggestion("find_friend_activity"));
        assertTrue(ProfileSuggestions.isSuggestion("ig_text_app_non_onboarded_unconnected_daily_digest"));
        assertTrue(ProfileSuggestions.isSuggestion("ig_text_app_recommended_threads"));
        assertTrue(ProfileSuggestions.isSuggestion("ig_text_app_suggested_profiles"));
        assertFalse(ProfileSuggestions.isSuggestion("ig_text_app_reply"));
        assertFalse(ProfileSuggestions.isSuggestion("ig_text_app_mention"));
        assertFalse("a suggestion word outside Threads", ProfileSuggestions.isSuggestion("suggested_users"));
        assertFalse(ProfileSuggestions.isSuggestion("like"));
        assertFalse(ProfileSuggestions.isSuggestion("comment"));
        assertFalse(ProfileSuggestions.isSuggestion("user_followed"));
        assertFalse(ProfileSuggestions.isSuggestion(""));
        assertFalse(ProfileSuggestions.isSuggestion(null));
    }

    /**
     * #181: a row the parser called a suggestion leaves the list only while Hide suggestions in
     * notifications is on, once, and only that same row. Every kind shows in the report. Off, paused or
     * unready, every row stays.
     */
    @Test
    public void suggestionRowsInNotificationsGoOnlyWithTheirOwnSwitch() {
        Object suggestion = new Object();
        Object like = new Object();
        ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness");
        assertSame("the profile switch alone", suggestion, ProfileSuggestions.keepNotification(suggestion));
        String ran = String.join("\n", HookStatus.report());
        assertTrue(ran, ran.contains(FamilyNames.PROFILE_SUGGESTIONS));

        Settings.HIDE_NOTIFICATION_SUGGESTIONS.save(true);
        ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness");
        assertNull(ProfileSuggestions.keepNotification(suggestion));
        assertSame("a row is only taken once", suggestion, ProfileSuggestions.keepNotification(suggestion));
        ProfileSuggestions.notificationParsed(like, "like");
        assertSame(like, ProfileSuggestions.keepNotification(like));
        ProfileSuggestions.notificationParsed(like, null);
        assertSame("no kind", like, ProfileSuggestions.keepNotification(like));
        ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness");
        assertSame("another row than the one parsed", like, ProfileSuggestions.keepNotification(like));
        assertNull(ProfileSuggestions.keepNotification(suggestion));
        assertNull("no row", ProfileSuggestions.keepNotification(null));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.NOTIFICATIONS));
        assertTrue(report, report.contains("connect_lowlness"));
        assertTrue(report, report.contains(ProfileSuggestions.NO_TYPE));
        assertTrue(report, report.contains(ProfileSuggestions.SUGGESTION_ROWS + " 2"));
        Settings.HIDE_PROFILE_SUGGESTIONS.save(false);
        ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness");
        assertNull("with the profile switch off", ProfileSuggestions.keepNotification(suggestion));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness");
        assertSame("paused", suggestion, ProfileSuggestions.keepNotification(suggestion));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness");
        SettingsContextRule.withoutContext(() -> assertSame("unready", suggestion, ProfileSuggestions.keepNotification(suggestion)));

        Settings.HIDE_NOTIFICATION_SUGGESTIONS.save(false);
        ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness");
        assertSame("off", suggestion, ProfileSuggestions.keepNotification(suggestion));
    }

    /** A row parsed on another thread isn't taken from this thread's list. */
    @Test
    public void aSuggestionParsedOnAnotherThreadStays() throws InterruptedException {
        Settings.HIDE_NOTIFICATION_SUGGESTIONS.save(true);
        Object suggestion = new Object();
        Thread parser = new Thread(() -> ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness"));
        parser.start();
        parser.join();
        assertSame(suggestion, ProfileSuggestions.keepNotification(suggestion));
    }

    /** A notification switch that throws leaves the row in the list and says which hook threw. */
    @Test
    public void aThrowingNotificationSwitchFailsOpenAndIsReported() {
        Object suggestion = new Object();
        ProfileSuggestions.notificationParsed(suggestion, "connect_lowlness");
        assertSame(suggestion, ProfileSuggestions.keepNotification(suggestion, THROWS));
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.SUGGESTION_ROWS + "'"));
    }

    /**
     * #259: with Hide Featured in notifications on, the Featured section's rows come back as an empty
     * list, never the one Instagram filled, and its header as none. Off, paused or unready, both stay
     * as Instagram kept them. A missing or empty list goes through as it came.
     */
    @Test
    public void theFeaturedSectionGoesOnlyWithItsOwnSwitch() {
        List<Object> rows = new ArrayList<>(Arrays.asList(new Object(), new Object()));
        Object header = new Object();
        Settings.HIDE_NOTIFICATION_SUGGESTIONS.save(true);
        assertSame("the suggestions switch alone", rows, ProfileSuggestions.featuredRows(rows));
        assertSame(header, ProfileSuggestions.featuredHeader(header));

        Settings.HIDE_NOTIFICATION_FEATURED.save(true);
        List<?> left = ProfileSuggestions.featuredRows(rows);
        assertTrue(String.valueOf(left), left.isEmpty());
        assertEquals("Instagram's list is left as it was", 2, rows.size());
        assertNull(ProfileSuggestions.featuredHeader(header));
        assertNull("no list", ProfileSuggestions.featuredRows(null));
        List<Object> none = new ArrayList<>();
        assertSame("an empty list", none, ProfileSuggestions.featuredRows(none));
        assertNull("no header", ProfileSuggestions.featuredHeader(null));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.FEATURED + " 1"));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", rows, ProfileSuggestions.featuredRows(rows));
        assertSame("paused", header, ProfileSuggestions.featuredHeader(header));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertSame("unready", rows, ProfileSuggestions.featuredRows(rows));
            assertSame("unready", header, ProfileSuggestions.featuredHeader(header));
        });

        Settings.HIDE_NOTIFICATION_FEATURED.save(false);
        assertSame("off", rows, ProfileSuggestions.featuredRows(rows));
        assertSame("off", header, ProfileSuggestions.featuredHeader(header));
    }

    /** A Featured switch that throws leaves the rows and the header and says which hook threw. */
    @Test
    public void aThrowingFeaturedSwitchFailsOpenAndIsReported() {
        List<Object> rows = new ArrayList<>(Collections.singletonList(new Object()));
        Object header = new Object();
        assertSame(rows, ProfileSuggestions.featuredRows(rows, THROWS));
        assertSame(header, ProfileSuggestions.featuredHeader(header, THROWS));
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.FEATURED + "'"));
    }

    /**
     * #259: with Hide Featured in notifications on, a Threads row that is Instagram nudging you toward
     * Threads ("posted a thread you might like") leaves the list, even in a section with no Featured
     * header. A Threads reply, like or follow, and every other row, stay. Its own switch only.
     */
    @Test
    public void aThreadsNudgeGoesWithTheFeaturedSwitch() {
        assertTrue(ProfileSuggestions.isFeaturedNudge("ig_text_app_new_post_from_following"));
        assertTrue(ProfileSuggestions.isFeaturedNudge("ig_text_app_thread_you_may_enjoy"));
        assertFalse(ProfileSuggestions.isFeaturedNudge("ig_text_app_reply"));
        assertFalse(ProfileSuggestions.isFeaturedNudge("ig_text_app_like_milestone"));
        assertFalse(ProfileSuggestions.isFeaturedNudge("ig_text_app_new_follower"));
        assertFalse(ProfileSuggestions.isFeaturedNudge("comment"));
        assertFalse(ProfileSuggestions.isFeaturedNudge(null));

        Object nudge = new Object();
        Object reply = new Object();
        Settings.HIDE_NOTIFICATION_SUGGESTIONS.save(true);
        ProfileSuggestions.notificationParsed(nudge, "ig_text_app_thread_you_may_enjoy");
        assertSame("the suggestions switch alone", nudge, ProfileSuggestions.keepNotification(nudge));

        Settings.HIDE_NOTIFICATION_FEATURED.save(true);
        ProfileSuggestions.notificationParsed(nudge, "ig_text_app_thread_you_may_enjoy");
        assertNull(ProfileSuggestions.keepNotification(nudge));
        ProfileSuggestions.notificationParsed(reply, "ig_text_app_reply");
        assertSame(reply, ProfileSuggestions.keepNotification(reply));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfileSuggestions.notificationParsed(nudge, "ig_text_app_thread_you_may_enjoy");
        assertSame("paused", nudge, ProfileSuggestions.keepNotification(nudge));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_NOTIFICATION_FEATURED.save(false);
        ProfileSuggestions.notificationParsed(nudge, "ig_text_app_thread_you_may_enjoy");
        assertSame("off", nudge, ProfileSuggestions.keepNotification(nudge));
    }

    /** #259: the list builder skips a row tagged priority_stories only while the Featured switch is on. */
    @Test
    public void theBuilderSkipsFeaturedRowsOnlyWithTheFeaturedSwitch() {
        assertFalse("off", ProfileSuggestions.skipsFeaturedRow("priority_stories"));
        Settings.HIDE_NOTIFICATION_FEATURED.save(true);
        assertTrue(ProfileSuggestions.skipsFeaturedRow("priority_stories"));
        assertFalse(ProfileSuggestions.skipsFeaturedRow("new_stories"));
        assertFalse(ProfileSuggestions.skipsFeaturedRow("old_stories"));
        assertFalse(ProfileSuggestions.skipsFeaturedRow(null));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ProfileSuggestions.skipsFeaturedRow("priority_stories"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("unready", ProfileSuggestions.skipsFeaturedRow("priority_stories")));
        assertFalse("a switch that throws", ProfileSuggestions.skipsFeaturedRow("priority_stories", THROWS));
    }

    /**
     * #189: the heart's dot count is zeroed only while Keep Featured off the heart's dot is on and no
     * like, comment, follow or tag is counted. Any activity keeps the dot, as do off, paused, unready
     * and a switch that throws. A count of 0 stays 0.
     */
    @Test
    public void theHeartsDotGoesOnlyWhenNothingButFeaturedIsBehindIt() {
        assertEquals("off", 1, ProfileSuggestions.featuredDot(1, 0));
        Settings.HIDE_FEATURED_DOT.save(true);
        assertEquals(0, ProfileSuggestions.featuredDot(1, 0));
        assertEquals("a like", 1, ProfileSuggestions.featuredDot(1, 1));
        assertEquals("several", 3, ProfileSuggestions.featuredDot(3, 7));
        assertEquals("no dot", 0, ProfileSuggestions.featuredDot(0, 0));
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(ProfileSuggestions.FEATURED_DOT + " 1"));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1, ProfileSuggestions.featuredDot(1, 0));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("unready", 1, ProfileSuggestions.featuredDot(1, 0)));
        assertEquals("a switch that throws", 1, ProfileSuggestions.featuredDot(1, 0, THROWS));
        String missing = HookStatus.missing(FamilyNames.PROFILE_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + ProfileSuggestions.FEATURED_DOT + "'"));
    }
}
