/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** What counts as the menu's Saved row, and what the bookmark on a profile holds on to. */
@RunWith(RobolectricTestRunner.class)
public class ProfileSavedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void aRowLabelledSavedIsTheSavedRowInAnyCase() {
        assertTrue(ProfileSaved.isSavedLabel("Saved"));
        assertTrue(ProfileSaved.isSavedLabel("  saved "));
    }

    /** The menu's rows are found through this lookup, so a refusal shows the menu at once. */
    @Test
    public void theChildLookupIsFoundOnce() {
        assertNotNull(ProfileSaved.childId());
        assertSame(ProfileSaved.childId(), ProfileSaved.childId());
    }

    /**
     * In a language HushGram has no table for, the menu row can't be found by its label, so the tap goes to
     * Instagram's own Saved link instead, with no menu involved.
     */
    @Test
    public void openingSavedAsksInstagramsOwnSavedLinkWithoutTheMenu() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("instagram://saved")).setPackage(activity.getPackageName());
        ResolveInfo answer = new ResolveInfo();
        answer.activityInfo = new ActivityInfo();
        answer.activityInfo.packageName = activity.getPackageName();
        answer.activityInfo.name = "com.instagram.urlhandlers.saved.SavedUrlHandlerActivity";
        shadowOf(activity.getPackageManager()).addResolveInfoForIntent(link, answer);

        ProfileSaved.open(activity);

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("Saved was asked for", started);
        assertEquals("instagram://saved", String.valueOf(started.getData()));
        assertEquals(activity.getPackageName(), started.getPackage());
        assertEquals("the screen was never held back for a menu", 1f, activity.getWindow().getDecorView().getAlpha(), 0f);
    }

    @Test
    public void withNothingAnsweringTheLinkOpenSavedScreenSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertFalse(ProfileSaved.openSavedScreen(activity));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    /**
     * With Hide the Create button on, your profile has no plus (#431). The chevron after your username
     * still says it's yours. Someone else's profile has neither, and a hidden chevron says nothing.
     */
    @Test
    public void withTheCreateButtonHiddenTheAccountChevronStillSaysItsYourProfile() {
        int createId = android.view.View.generateViewId();
        int chevronId = android.view.View.generateViewId();
        GlassTabBar.idForTests(ProfileSaved.CREATE_BUTTON, createId);
        GlassTabBar.idForTests(ProfileSaved.SWITCH_ACCOUNTS, chevronId);
        try {
            Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
            LinearLayout bar = new LinearLayout(activity);
            android.widget.ImageView chevron = new android.widget.ImageView(activity);
            chevron.setId(chevronId);
            bar.addView(chevron);
            activity.setContentView(bar);
            android.view.View decor = activity.getWindow().getDecorView();

            assertTrue("no plus, but the chevron shows", ProfileSaved.yours(decor));
            chevron.setVisibility(android.view.View.GONE);
            assertFalse("a hidden chevron counted as your profile", ProfileSaved.yours(decor));
            bar.removeView(chevron);
            assertFalse("someone else's profile counted as yours", ProfileSaved.yours(decor));
            android.widget.ImageView create = new android.widget.ImageView(activity);
            create.setId(createId);
            bar.addView(create);
            assertTrue("the plus alone still says it's yours", ProfileSaved.yours(decor));
        } finally {
            GlassTabBar.idForTests(ProfileSaved.CREATE_BUTTON, null);
            GlassTabBar.idForTests(ProfileSaved.SWITCH_ACCOUNTS, null);
        }
    }

    /**
     * The top bar other screens share keeps a gone chevron ahead of your profile's (#431). Looked at
     * before your profile shows, it's the only one, and once yours shows it still says it's yours.
     */
    @Test
    public void aGoneChevronInAnotherBarDoesNotHideYours() {
        int createId = android.view.View.generateViewId();
        int chevronId = android.view.View.generateViewId();
        GlassTabBar.idForTests(ProfileSaved.CREATE_BUTTON, createId);
        GlassTabBar.idForTests(ProfileSaved.SWITCH_ACCOUNTS, chevronId);
        try {
            Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
            LinearLayout screen = new LinearLayout(activity);
            LinearLayout shared = new LinearLayout(activity);
            android.widget.ImageView hidden = new android.widget.ImageView(activity);
            hidden.setId(chevronId);
            hidden.setVisibility(android.view.View.GONE);
            shared.addView(hidden);
            screen.addView(shared);
            activity.setContentView(screen);
            android.view.View decor = activity.getWindow().getDecorView();

            assertFalse("only the gone chevron is there", ProfileSaved.yours(decor));
            LinearLayout bar = new LinearLayout(activity);
            android.widget.ImageView chevron = new android.widget.ImageView(activity);
            chevron.setId(chevronId);
            bar.addView(chevron);
            screen.addView(bar);
            assertTrue("the gone chevron hid yours", ProfileSaved.yours(decor));
        } finally {
            GlassTabBar.idForTests(ProfileSaved.CREATE_BUTTON, null);
            GlassTabBar.idForTests(ProfileSaved.SWITCH_ACCOUNTS, null);
        }
    }

    @Test
    public void otherRowsAreNot() {
        assertFalse(ProfileSaved.isSavedLabel("Archive"));
        assertFalse(ProfileSaved.isSavedLabel("Saved posts and more"));
        assertFalse(ProfileSaved.isSavedLabel(null));
    }

    /**
     * The row kept for a profile's tabs is keyed weakly on them, and it mustn't hold them itself: a
     * row that did kept every profile screen ever looked at, activity and all, for as long as
     * Instagram ran.
     */
    @Test
    public void aKeptRowLetsTheProfileScreenGoWhenItCloses() throws Exception {
        try {
            int before = ProfileSaved.rememberedForTests();
            WeakReference<?>[] held = shownThenClosed(before);
            for (int attempt = 0; attempt < 100 && (held[0].get() != null || held[1].get() != null
                    || ProfileSaved.rememberedForTests() != before); attempt++) {
                System.gc();
                Thread.sleep(5);
            }
            assertNull("the kept row held the tabs it was keyed on", held[1].get());
            assertNull("the kept row held the profile's activity", held[0].get());
            assertEquals(before, ProfileSaved.rememberedForTests());
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * Puts tabs on a profile screen, keeps a row for them the way a look does, then closes the screen.
     * Made in a method of its own, so no local of the test keeps the activity or the tabs reachable.
     */
    private static WeakReference<?>[] shownThenClosed(int before) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        LinearLayout tabs = new LinearLayout(activity);
        activity.setContentView(tabs);
        ProfileSaved.rememberForTests(activity, tabs);
        assertEquals(before + 1, ProfileSaved.rememberedForTests());
        WeakReference<?>[] held = {new WeakReference<>(activity), new WeakReference<ViewGroup>(tabs)};
        controller.close();
        return held;
    }
}
