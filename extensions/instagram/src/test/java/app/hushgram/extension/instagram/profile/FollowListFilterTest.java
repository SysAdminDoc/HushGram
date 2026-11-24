/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

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
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hold a follow list's tabs to filter it (#847): everyone, only public accounts or only private ones,
 * in any Followers or Following list, with accounts Instagram hasn't said either way about kept.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FollowListFilterTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    /** Instagram's kinds of list as the binder holds them: enum constants, of which only the name is read. */
    enum Kind { FOLLOWERS, FOLLOWING, FOLLOWING_SIMPLIFIED, MUTUAL_FOLLOWERS }

    private final Object open = new Object();
    private final Object locked = new Object();
    private final Object unsaid = new Object();

    /** A list as the patch reads it: its kind and which accounts are private. */
    private class FakeList implements FollowListFilter.Parts {
        Object kind = Kind.FOLLOWERS;
        final Object binder = new Object();
        final Map<Object, Boolean> privacy = new HashMap<>();

        FakeList() {
            privacy.put(open, false);
            privacy.put(locked, true);
            privacy.put(unsaid, null);
        }

        @Override public Object binder(Object list) { return binder; }
        @Override public Object kind(Object binder) { return kind; }
        @Override public Boolean isPrivate(Object user) { return privacy.get(user); }
    }

    private final List<Object> rebuilt = new ArrayList<>();
    private FollowListFilter.Rebuilder before;

    @Before
    public void prepare() {
        FollowListFilter.forget();
        before = FollowListFilter.rebuilder;
        FollowListFilter.rebuilder = rebuilt::add;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After
    public void restore() {
        FollowListFilter.rebuilder = before;
        FollowListFilter.forget();
        Settings.FOLLOW_LIST_FILTER.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private List<Object> everyone() {
        return Arrays.asList(open, locked, unsaid, open);
    }

    /** Public shows the public accounts, private the private ones, and an account not said about stays in both. */
    @Test
    public void eachChoiceShowsItsAccountsAndKeepsTheUnknown() {
        FakeList parts = new FakeList();
        Object list = new Object();
        List<Object> users = everyone();

        assertSame("everyone to start", users, FollowListFilter.filtered(users, list, parts, ON));

        FollowListFilter.show(FollowListFilter.PUBLIC);
        Object shown = FollowListFilter.filtered(users, list, parts, ON);
        assertEquals(Arrays.asList(open, unsaid, open), shown);
        assertNotSame(users, shown);

        FollowListFilter.show(FollowListFilter.PRIVATE);
        assertEquals(Arrays.asList(locked, unsaid), FollowListFilter.filtered(users, list, parts, ON));
        assertEquals("Instagram's list stays as it was", everyone(), users);

        FollowListFilter.show(FollowListFilter.EVERYONE);
        assertSame(users, FollowListFilter.filtered(users, list, parts, ON));
    }

    /** Followers and both Following lists are filtered, any account's. Other lists, or the switch off, aren't. */
    @Test
    public void onlyFollowersAndFollowingListsWithTheSwitchOnAreFiltered() {
        FollowListFilter.show(FollowListFilter.PUBLIC);
        List<Object> users = everyone();
        FakeList parts = new FakeList();
        for (Kind kind : new Kind[] {Kind.FOLLOWERS, Kind.FOLLOWING, Kind.FOLLOWING_SIMPLIFIED}) {
            parts.kind = kind;
            assertEquals(kind.name(), 3, ((List<?>) FollowListFilter.filtered(users, new Object(), parts, ON)).size());
        }
        parts.kind = Kind.MUTUAL_FOLLOWERS;
        assertSame(users, FollowListFilter.filtered(users, new Object(), parts, ON));
        parts.kind = "FOLLOWERS";
        assertSame("only an enum constant is a kind", users, FollowListFilter.filtered(users, new Object(), parts, ON));
        parts.kind = Kind.FOLLOWERS;
        assertSame(users, FollowListFilter.filtered(users, new Object(), parts, OFF));
        assertSame(users, FollowListFilter.filtered(users, null, parts, ON));
        assertSame("not a list", open, FollowListFilter.filtered(open, new Object(), parts, ON));
    }

    /** A pick says what's shown and has every follow list filtered so far built again. */
    @Test
    public void aPickSaysSoAndRebuildsTheListsOpen() {
        FakeList parts = new FakeList();
        Object followers = new Object();
        Object following = new Object();
        FollowListFilter.filtered(everyone(), followers, parts, ON);
        parts.kind = Kind.FOLLOWING;
        FollowListFilter.filtered(everyone(), following, parts, ON);
        parts.kind = Kind.MUTUAL_FOLLOWERS;
        FollowListFilter.filtered(everyone(), new Object(), parts, ON);

        FollowListFilter.show(FollowListFilter.PRIVATE);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("Showing private accounts", ShadowToast.getTextOfLatestToast());
        assertEquals(2, rebuilt.size());
        assertTrue(rebuilt.contains(followers) && rebuilt.contains(following));
        FollowListFilter.show(FollowListFilter.PUBLIC);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Showing public accounts", ShadowToast.getTextOfLatestToast());
        FollowListFilter.show(FollowListFilter.EVERYONE);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Showing everyone", ShadowToast.getTextOfLatestToast());

        FollowListFilter.show(7);
        assertEquals(FollowListFilter.EVERYONE, FollowListFilter.shown());
        assertEquals(6, rebuilt.size());
    }

    /** A list that can't be read, or a rebuild that fails, is left as it was and noted. */
    @Test
    public void aFailureLeavesTheListAlone() {
        FollowListFilter.show(FollowListFilter.PUBLIC);
        List<Object> users = everyone();
        FollowListFilter.Parts broken = new FakeList() {
            @Override
            public Boolean isPrivate(Object user) {
                throw new IllegalStateException("moved");
            }
        };

        assertSame(users, FollowListFilter.filtered(users, new Object(), broken, ON));
        FollowListFilter.rebuilder = list -> {
            throw new IllegalStateException("gone");
        };
        FollowListFilter.show(FollowListFilter.PRIVATE);

        assertEquals(FollowListFilter.PRIVATE, FollowListFilter.shown());
        assertTrue(HookStatus.missing(FamilyNames.FRIENDSHIP_STATUS).toString(),
                HookStatus.missing(FamilyNames.FRIENDSHIP_STATUS).toString().contains(FollowListFilter.FILTER));
    }

    /**
     * The tabs above a follow list's rows: the first row drawn finds Instagram's strip from its list,
     * whether it's in the list yet or not, and holding a tab opens the choice. Picking one shows it.
     */
    @Test
    public void holdingATabOpensTheChoice() {
        Settings.FOLLOW_LIST_FILTER.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FollowListFilter.stripId = View.generateViewId();
        FrameLayout screen = new FrameLayout(activity);
        LinearLayout strip = new LinearLayout(activity);
        strip.setId(FollowListFilter.stripId);
        TextView followersTab = tab(activity, "Followers");
        TextView followingTab = tab(activity, "Following");
        strip.addView(followersTab);
        strip.addView(followingTab);
        screen.addView(strip);
        FrameLayout rows = new FrameLayout(activity);
        screen.addView(rows);
        activity.setContentView(screen);
        shadowOf(Looper.getMainLooper()).idle();
        View row = new View(activity);

        FollowListFilter.watch(row);
        rows.addView(row);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(followingTab.performLongClick());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Show in follow lists", shadowOf(dialog).getTitle().toString());
        ListView choices = dialog.getListView();
        assertEquals(3, choices.getCount());
        assertEquals("Everyone", choices.getItemAtPosition(0).toString());
        assertEquals("Public accounts", choices.getItemAtPosition(1).toString());
        assertEquals("Private accounts", choices.getItemAtPosition(2).toString());
        assertEquals(0, choices.getCheckedItemPosition());

        shadowOf(dialog).clickOnItem(2);
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(FollowListFilter.PRIVATE, FollowListFilter.shown());
        assertFalse(dialog.isShowing());
        assertTrue(followersTab.performLongClick());
        assertEquals(2, ShadowAlertDialog.getLatestAlertDialog().getListView().getCheckedItemPosition());
    }

    /** With the switch off, holding a tab is Instagram's and the strip is never looked for. */
    @Test
    public void withTheSwitchOffTheTabsAreInstagrams() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FollowListFilter.stripId = View.generateViewId();
        FrameLayout screen = new FrameLayout(activity);
        LinearLayout strip = new LinearLayout(activity);
        strip.setId(FollowListFilter.stripId);
        TextView tab = tab(activity, "Followers");
        strip.addView(tab);
        screen.addView(strip);
        View row = new View(activity);
        screen.addView(row);

        FollowListFilter.watch(row);
        assertFalse(tab.performLongClick());

        assertFalse(FollowListFilter.held(tab));
        Settings.FOLLOW_LIST_FILTER.save(true);
        assertTrue(FollowListFilter.held(tab));
        Settings.FOLLOW_LIST_FILTER.save(false);
        assertFalse(FollowListFilter.held(tab));
    }

    /** Every clickable view in the strip gets the hold, however deep. */
    @Test
    public void everyTabInTheStripGetsTheHold() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout strip = new LinearLayout(activity);
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.addView(tab(activity, "Followers"));
        strip.addView(wrapper);
        strip.addView(tab(activity, "Following"));
        strip.addView(new TextView(activity));

        assertEquals(2, FollowListFilter.holdTabs(strip));
    }

    private static TextView tab(Activity activity, String text) {
        TextView tab = new TextView(activity);
        tab.setText(text);
        tab.setClickable(true);
        return tab;
    }
}
